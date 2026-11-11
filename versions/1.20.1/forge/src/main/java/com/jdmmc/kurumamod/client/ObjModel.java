package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * OBJ のメッシュを GPU に積んで描く。<b>形（頂点・面・名前・寸法・読み込み）は core の {@link ObjMesh}</b>。
 * ここは描くところだけ（頂点を積む・GPU に置く・鏡面に描く）。
 *
 * <p>core が作るメッシュはすべてこのクラスになる（{@link ObjMesh#setFactory}）ので、{@link #get} などは
 * いままでどおり {@code ObjModel} を返す。</p>
 */
public final class ObjModel extends ObjMesh {

    static {
        ObjMesh.setFactory(ObjModel::new);
    }

    private ObjModel(float[] vertices, List<Group> groups) {
        super(vertices, groups);
    }

    /** モデルを取り出す。まだ読んでいなければ読む（{@link ObjMesh#get}）。 */
    public static ObjModel get(CarId location) {
        return (ObjModel) ObjMesh.get(location);
    }

    /** タイヤに合わせて伸び縮みさせたもの（{@link ObjMesh#fittedTire}）。 */
    public static ObjModel fittedTire(CarId location, @org.jetbrains.annotations.Nullable Double fromInner,
                                      @org.jetbrains.annotations.Nullable Double fromOuter,
                                      double toInner, double toOuter) {
        return (ObjModel) ObjMesh.fittedTire(location, fromInner, fromOuter, toInner, toOuter);
    }

    @Override
    public ObjModel lod(int level) {
        return (ObjModel) super.lod(level);
    }

    @Override
    public ObjModel fitRadially(double fromInner, double fromOuter, double toInner, double toOuter, double xScale) {
        return (ObjModel) super.fitRadially(fromInner, fromOuter, toInner, toOuter, xScale);
    }

    /** リソースの再読み込みで呼ぶ。GPU に置いた頂点を返してから、形の読み込みを捨てる。 */
    public static void clearCache() {
        ObjMesh.forEachLoaded(mesh -> ((ObjModel) mesh).releaseGpu());
        ObjMesh.clearCache();
    }

    /** 鏡面 1 枚（{@link ObjMesh.MirrorFace} をバニラの {@link Vec3} で）。 */
    public record MirrorFace(String name, Vec3 center, Vec3 normal, Vec3 right, Vec3 up,
                             double width, double height, int[] faces) {

        /** 横縦比。描き先の大きさと射影のアスペクトに使う。 */
        public double aspect() {
            return height < 1.0e-6 ? 1.0 : width / height;
        }
    }

    private List<MirrorFace> mirrorFaces;

    /** 鏡面の一覧。持っていなければ空。 */
    public List<MirrorFace> mirrors() {
        if (mirrorFaces == null) {
            List<MirrorFace> out = new ArrayList<>();
            for (ObjMesh.MirrorFace face : meshMirrors()) {
                out.add(new MirrorFace(face.name(), vec(face.center()), vec(face.normal()), vec(face.right()),
                        vec(face.up()), face.width(), face.height(), face.faces()));
            }
            mirrorFaces = List.copyOf(out);
        }
        return mirrorFaces;
    }

    /** その名前の形の左右それぞれの中心（{@link ObjMesh#sideCentersOf}）。 */
    public List<Vec3> groupSideCenters(String name) {
        return sideCentersOf(name).stream().map(ObjModel::vec).toList();
    }

    /** その名前の形の向き（{@link ObjMesh#normalOf}）。 */
    public Vec3 groupNormal(String name) {
        return vec(normalOf(name));
    }

    private static Vec3 vec(Vec3d v) {
        return new Vec3(v.x, v.y, v.z);
    }


    /**
     * GPU に置いた不透明な面（{@link CarMeshes}）。添字は {@code (タイヤ抜き ? 2 : 0) | (左右反転 ? 1 : 0)}。
     * 初めて描くときに作り、モデルを読み直すとき（{@link #clearCache}）に捨てる。
     */
    private final VertexBuffer[] gpuMeshes = new VertexBuffer[4];
    private final Map<Integer, VertexBuffer> packMeshes = new HashMap<>();
    /** 作ってみたら面が無かった組み合わせ。毎フレーム作り直そうとしないため。 */
    private final boolean[] gpuEmpty = new boolean[4];

    /**
     * 部品の形のうち、見せる名前に入っているものを描く。
     *
     * @param shown 見せる部品の形の名前
     */
    public void renderOptional(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                               java.util.Set<String> shown, Pass pass) {
        if (optionalFaces.isEmpty()) {
            return;
        }
        int index = pass == Pass.TRANSLUCENT ? 1 : 0;
        for (Map.Entry<String, int[][]> entry : optionalFaces.entrySet()) {
            if (shown.contains(entry.getKey()) && entry.getValue()[index].length > 0) {
                renderFaces(entry.getValue()[index], pose.pose(), pose.normal(), 1.0F, consumer,
                        packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    /**
     * ハンドルを回して描く。
     *
     * <p>傾き（{@code tilt}）は中心を通る左右の軸のまわりに掛け、その<b>内側で</b>回す。
     * 回す軸もメッシュと一緒に傾くので、傾けても振れずに回る。軸が取れていなければどちらも掛けない
     * （中心が分からない）。</p>
     *
     * @param axis  回す中心と軸（{@link #steeringAxis}）。null なら回さずに描く
     * @param angle ハンドルの回転角 [rad]。<b>正で右へ切る</b>（運転者から見て時計回り）
     * @param tilt  コラムの傾きの足し分 [rad]。<b>正でコラムが起きる</b>（ハンドルの面が寝る）
     */
    public void renderSteeringWheel(PoseStack pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                                    @Nullable SteeringWheelAxis.Result axis, double angle, double tilt, Pass pass) {
        int[] faces = steeringFaces[pass == Pass.TRANSLUCENT ? 1 : 0];
        if (faces.length == 0) {
            return;
        }
        boolean turn = axis != null && (angle != 0.0 || tilt != 0.0);
        if (turn) {
            double[] c = axis.center();
            double[] a = axis.axis();
            pose.pushPose();
            pose.translate(c[0], c[1], c[2]);
            if (tilt != 0.0) {
                // 運転者側を向いた軸 (0, sin e, cos e) は、+X まわりに -θ 回すと仰角が e+θ になる
                pose.mulPose(new org.joml.Quaternionf().rotationX((float) -tilt));
            }
            // 軸は運転者の側を向いている。その軸まわりの正の回転は運転者から見て反時計回りなので、符号を返す
            pose.mulPose(new org.joml.Quaternionf().rotationAxis((float) -angle, (float) a[0], (float) a[1], (float) a[2]));
            pose.translate(-c[0], -c[1], -c[2]);
        }
        renderFaces(faces, pose.last().pose(), pose.last().normal(), 1.0F, consumer,
                packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
        if (turn) {
            pose.popPose();
        }
    }

    /**
     * 鏡面に、別に描いておいた景色を貼る。
     *
     * <p>UV は<b>メッシュの UV を使わない</b>。面内の 2 軸へ落とした位置をそのまま 0..1 に
     * するので、Blender 側で展開する必要がない（板を 1 枚置いて名前を付けるだけでよい）。</p>
     *
     * <p><b>横は反転する。</b>鏡の位置にカメラを置いて後ろを向いて撮った絵は「振り返って
     * 見た景色」であって、鏡に映る像はそれを左右反転したもの。<b>縦も反転する</b>——
     * フレームバッファは下が原点なので、そのままでは上下が逆になる。</p>
     *
     * <p>頂点は {@code POSITION_COLOR_TEX} の順で積む（{@link KurumaRenderTypes#mirror}）。
     * 明るさも法線も渡さない——映っている絵は<b>すでに light を通って描かれている</b>ので、
     * ここで陰影を掛けると二重になる。</p>
     */
    public void renderMirror(PoseStack.Pose pose, VertexConsumer consumer, MirrorFace face,
                             float red, float green, float blue, float alpha) {
        Matrix4f matrix = pose.pose();
        double halfWidth = face.width() * 0.5;
        double halfHeight = face.height() * 0.5;

        for (int index : face.faces()) {
            int base = index * FACE_FLOATS;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                double dx = vertices[offset] - face.center().x;
                double dy = vertices[offset + 1] - face.center().y;
                double dz = vertices[offset + 2] - face.center().z;
                Vec3 d = new Vec3(dx, dy, dz);
                // 面内の位置を 0..1 へ。中心が 0.5
                float s = (float) (0.5 + d.dot(face.right()) / (halfWidth * 2.0));
                float t = (float) (0.5 + d.dot(face.up()) / (halfHeight * 2.0));
                consumer.vertex(matrix, vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .color(red, green, blue, alpha)
                        .uv(1.0F - s, t)
                        .endVertex();
            }
        }
    }

    /**
     * GPU に置いた不透明な面。<b>初めて呼ばれたときに作る</b>（描画スレッドから呼ぶこと）。
     *
     * <p>頂点の中身は {@link #render} が毎フレーム積んでいたものと同じで、変換だけを掛けていない
     * （メッシュの座標のまま）。明るさとオーバーレイは使わないので 0 を入れておく
     * （{@code kurumamod:car_mesh} は外から渡された値を読む）。</p>
     *
     * @param withoutTire 付属のタイヤを除くか（タイヤを別に描くときのホイール）
     * @param mirrorX     左右を反転するか（右側のタイヤ）。反転は頂点の段で済ませる（{@link #render} の理由と同じ）
     * @return 面が 1 つも無ければ null
     */
    VertexBuffer gpuMesh(boolean withoutTire, boolean mirrorX) {
        int index = (withoutTire ? 2 : 0) | (mirrorX ? 1 : 0);
        if (gpuMeshes[index] != null || gpuEmpty[index]) {
            return gpuMeshes[index];
        }
        int[] faces = withoutTire ? opaqueRimFaces : opaqueFaces;
        if (faces.length == 0) {
            gpuEmpty[index] = true;
            return null;
        }
        float flip = mirrorX ? -1.0F : 1.0F;
        int[] corners = cornerOrder(flip);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (int face : faces) {
            int base = face * FACE_FLOATS;
            for (int k = 0; k < 4; k++) {
                int offset = base + corners[k] * STRIDE;
                builder.vertex(flip * vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .color(vertices[offset + 8], vertices[offset + 9], vertices[offset + 10],
                                vertices[offset + ALPHA_OFFSET])
                        .uv(vertices[offset + 6], vertices[offset + 7])
                        .overlayCoords(0)
                        .uv2(0)
                        .normal(flip * vertices[offset + 3], vertices[offset + 4], vertices[offset + 5])
                        .endVertex();
            }
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        gpuMeshes[index] = buffer;
        return buffer;
    }

    /**
     * シェーダーパック用に GPU に置いた不透明な面。<b>ワールドの描画の最中に呼ぶこと</b>（その間だけ、
     * {@code BufferBuilder} がパックの長い形式で積むので、接線などがそのまま焼き込まれる）。
     *
     * <p>頂点を積む描き方と同じ道（{@link #renderFaces}）を通す。パックの頂点シェーダーは明るさを頂点から読むので、
     * <b>明るさごとに別に焼く</b>（普通は数通りで済み、値は 16 段×16 段なので増えても上限がある）。</p>
     *
     * @param shadowCaster 影の段で使う面（サスペンションを除く）か
     */
    VertexBuffer packMesh(boolean withoutTire, boolean shadowCaster, boolean mirrorX, int packedLight) {
        int key = packedLight << 3 | (shadowCaster ? 4 : 0) | (withoutTire ? 2 : 0) | (mirrorX ? 1 : 0);
        VertexBuffer mesh = packMeshes.get(key);
        if (mesh != null) {
            return mesh;
        }
        int[] faces = shadowCaster ? opaqueShadowFaces : withoutTire ? opaqueRimFaces : opaqueFaces;
        if (faces.length == 0) {
            return null;
        }
        BufferBuilder builder = new BufferBuilder(faces.length * 4 * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        renderFaces(faces, new Matrix4f(), new Matrix3f(), mirrorX ? -1.0F : 1.0F, builder, packedLight,
                OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        packMeshes.put(key, buffer);
        return buffer;
    }

    /** GPU に置いた頂点を返す。 */
    private void releaseGpu() {
        packMeshes.values().forEach(VertexBuffer::close);
        packMeshes.clear();
        for (ObjMesh lod : lods) {
            if (lod != null) {
                ((ObjModel) lod).releaseGpu();
            }
        }
        for (int i = 0; i < gpuMeshes.length; i++) {
            if (gpuMeshes[i] != null) {
                gpuMeshes[i].close();
                gpuMeshes[i] = null;
            }
            gpuEmpty[i] = false;
        }
    }

    /**
     * 描く。
     *
     * <p>Minecraft のエンティティ用 {@code RenderType} はどれも QUADS なので、
     * <b>四角形はそのまま、三角形は最後の頂点を 2 回積んで四角形として流す</b>（縮退四角形）。
     * 面積 0 の辺ができるだけで見た目には影響しない。並べ替えは読み込み時に済んでいる
     * （{@link #appendFace}）。</p>
     *
     * @param tint 全体に掛ける色。テクスチャをそのまま出すなら白
     */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha) {
        render(pose, consumer, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha, null, false);
    }

    /** 名前を選んで、左右を反転して描く。パスは不透明側。 */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha, String only,
                       boolean mirrorX) {
        render(pose, consumer, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha,
                only, mirrorX, Pass.OPAQUE);
    }

    /**
     * 名前を選んで描く。
     *
     * @param only この名前のオブジェクトだけ描く。null なら<b>光る部分を除いた全部</b>
     */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha, String only) {
        render(pose, consumer, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha, only, false);
    }

    /**
     * 名前を選んで、左右を反転して描く。
     *
     * <p><b>鏡像は {@code PoseStack} でやってはいけない。</b>{@code PoseStack#scale(-1, 1, 1)} は
     * 一様でないスケールの分岐に入り、法線行列に {@code Mth.fastInvCubeRoot(-1)} を掛ける。
     * この関数は逆立方根をニュートン法で解いているので<b>負の入力では収束せず 2.19e25 を返す</b>。
     * 結果、法線が 10^25 倍されてバイトへ詰める段で潰れ、<b>鏡像側だけ陰影が壊れる</b>
     * （右前・右後のタイヤだけおかしく見える、という形で出た）。一様な {@code scale(-1, -1, -1)}
     * でも同じ分岐へ落ちるので逃げられない。</p>
     *
     * <p>そこで頂点の段で X を反転する。反射行列 {@code diag(-1, 1, 1)} は逆転置が自分自身
     * なので、<b>法線も同じように X を反転させるのが正しい</b>。面の巻き方向は裏返るが、
     * {@code entityCutoutNoCull} でカリングを切ってあるので問題にならない。</p>
     *
     * @param mirrorX 左右を反転して描くか
     * @param pass 不透明な面と透ける面のどちらを描くか。{@code only} を指定したときは見ない
     */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha, String only,
                       boolean mirrorX, Pass pass) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        float flip = mirrorX ? -1.0F : 1.0F;

        // 名指し（灯火のレンズ）はパスを問わない。RenderType は呼ぶ側が選んでいる
        int[] faces = only != null ? facesByName.getOrDefault(only, NO_FACES)
                : pass == Pass.TRANSLUCENT ? translucentFaces : opaqueFaces;
        renderFaces(faces, matrix, normal, flip, consumer, packedLight, packedOverlay,
                tintRed, tintGreen, tintBlue, alpha);
    }

    /**
     * 灯火のレンズ（{@link #LIGHT_PREFIX}）を、光らせずにただの面として描く。
     *
     * <p>レンズは車体のパスから外してあるので、ゴーストのように灯火を描かないときは
     * これで埋めないと<b>そこだけ穴が開く</b>。</p>
     */
    public void renderLightsPlain(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay) {
        for (Map.Entry<String, int[]> entry : facesByName.entrySet()) {
            if (entry.getKey().startsWith(LIGHT_PREFIX)) {
                renderFaces(entry.getValue(), pose.pose(), pose.normal(), 1.0F, consumer,
                        packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    /** ホイールを、付属のタイヤ（{@link #TIRE_PREFIX}）を除いて描く。タイヤを別に描くときに使う。 */
    public void renderWithoutTire(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                                  boolean mirrorX, Pass pass) {
        renderFaces(pass == Pass.TRANSLUCENT ? translucentRimFaces : opaqueRimFaces,
                pose.pose(), pose.normal(), mirrorX ? -1.0F : 1.0F, consumer, packedLight, packedOverlay,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 不透明な面を、サスペンション（{@link #SUSPENSION_PREFIX}）を除いて描く。シェーダーパックの影を描くときに使う。 */
    public void renderShadowCaster(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay) {
        renderFaces(opaqueShadowFaces, pose.pose(), pose.normal(), 1.0F, consumer,
                packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 付属のタイヤ（{@link #TIRE_PREFIX}）の不透明な面だけを描く。シェーダーパックの影を描くときに使う。 */
    public void renderTireOnly(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                               boolean mirrorX) {
        renderFaces(opaqueTireFaces, pose.pose(), pose.normal(), mirrorX ? -1.0F : 1.0F, consumer,
                packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * すべての面の不透明度に掛ける倍率。ゴーストを描く間だけ 1 未満にする（{@code CarObjRenderer}）。
     *
     * <p>描く口（{@code render}・{@code renderOptional}・{@code renderWithoutTire} …）ごとに
     * α の引数を足すより、行き着く先の 1 か所で掛けるほうが漏れが無い。描画は 1 本のスレッドなので
     * static でよいが、<b>描き終えたら必ず 1 に戻すこと</b>。</p>
     */
    static float alphaScale = 1.0F;

    private static final int[] CORNERS = {0, 1, 2, 3};
    private static final int[] MIRRORED_CORNERS = {2, 1, 0, 3};

    /**
     * 面の 4 隅を積む順。鏡像は 4 隅を逆に回して、面の表裏を元に戻す。
     *
     * <p>X を反転すると巻き方向が裏返る。カリングを切ってあるので描画は変わらないが、
     * <b>シェーダーパックには裏向きの面として届く。</b>Complementary は
     * {@code gl_FrontFacing} が偽の面の法線を反転するので、右のタイヤだけ下を向いて真っ暗になった。</p>
     *
     * <p>0-3-2-1 ではなく 2-1-0-3 にするのは、縮退した四角形（a, b, c, c）の最初の 3 頂点を
     * 潰さないため。四角形を割る対角線（0-2）も元のまま。</p>
     */
    static int[] cornerOrder(float flip) {
        return flip < 0.0F ? MIRRORED_CORNERS : CORNERS;
    }

    private void renderFaces(int[] faces, Matrix4f matrix, Matrix3f normal, float flip, VertexConsumer consumer,
                             int packedLight, int packedOverlay,
                             float tintRed, float tintGreen, float tintBlue, float alpha) {
        alpha *= alphaScale;
        // Embeddium / Sodium があれば頂点を塊で渡す。シェーダーパック使用中にいちばん効く（BulkVertexWriter）
        if (BulkVertexWriter.write(consumer, vertices, faces, FACE_FLOATS, STRIDE, ALPHA_OFFSET, matrix, normal,
                flip, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha)) {
            return;
        }

        int[] corners = cornerOrder(flip);
        for (int face : faces) {
            int base = face * FACE_FLOATS;
            for (int k = 0; k < 4; k++) {
                int offset = base + corners[k] * STRIDE;
                consumer.vertex(matrix, flip * vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .color(vertices[offset + 8] * tintRed,
                                vertices[offset + 9] * tintGreen,
                                vertices[offset + 10] * tintBlue,
                                vertices[offset + ALPHA_OFFSET] * alpha)
                        .uv(vertices[offset + 6], vertices[offset + 7])
                        .overlayCoords(packedOverlay)
                        .uv2(packedLight)
                        .normal(normal, flip * vertices[offset + 3], vertices[offset + 4], vertices[offset + 5])
                        .endVertex();
            }
        }
    }

    /**
     * 鏡面を、映さずにただの面として描く（暗いガラス）。
     *
     * <p>反射面だけを描くこと。<b>名前で丸ごと描くと筐体まで暗く塗ってしまう</b>——
     * ガワは車体として既に描かれているので二重になる。</p>
     */
    public void renderMirrorPlain(PoseStack.Pose pose, VertexConsumer consumer, MirrorFace face,
                                  int packedLight, float red, float green, float blue) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        for (int index : face.faces()) {
            int base = index * FACE_FLOATS;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                consumer.vertex(matrix, vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .color(red, green, blue, 1.0F)
                        .uv(vertices[offset + 6], vertices[offset + 7])
                        .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                        .uv2(packedLight)
                        .normal(normal, vertices[offset + 3], vertices[offset + 4], vertices[offset + 5])
                        .endVertex();
            }
        }
    }
}
