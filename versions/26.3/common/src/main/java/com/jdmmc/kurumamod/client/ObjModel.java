package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
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
import org.joml.Vector3f;
import com.jdmmc.kurumamod.client.model.ObjMesh;
import com.jdmmc.kurumamod.client.input.SteeringWheelAxis;

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
     * どのパスでどの面を描くか。<b>読み込みのときに振り分けておく</b>。
     *
     * <p>以前は描くたびに全面を回り、面ごとにオブジェクト一覧を線形に探して名前を
     * 照合していた（灯火 1 つ描くのにも車体を丸ごと 1 周）。答えは走行中に変わらないので、
     * 毎フレーム計算し直す理由がない。</p>
     */
    private final GpuMesh[] gpuMeshes = new GpuMesh[4];
    private final Map<Integer, GpuMesh> packMeshes = new HashMap<>();
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
                pose.rotate(new org.joml.Quaternionf().rotationX((float) -tilt));
            }
            // 軸は運転者の側を向いている。その軸まわりの正の回転は運転者から見て反時計回りなので、符号を返す
            pose.rotate(new org.joml.Quaternionf().rotationAxis((float) -angle, (float) a[0], (float) a[1], (float) a[2]));
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
                consumer.addVertex(matrix, vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .setColor(red, green, blue, alpha)
                        .setUv(1.0F - s, t);
            }
        }
    }

    /** GPU に置いた頂点（{@link CarMeshes}）。 */
    record GpuMesh(GpuBuffer vertices, int indexCount) {
    }

    /**
     * GPU に置いた不透明な面。<b>初めて呼ばれたときに作る</b>（描画スレッドから呼ぶこと）。
     *
     * <p>頂点の中身は {@link #render} が毎フレーム積むものと同じで、変換だけを掛けていない（メッシュの座標のまま）。
     * 明るさは使わないので 0 を入れておく（{@code car_mesh.vsh} は外から渡された値を読む）。</p>
     *
     * @param withoutTire 付属のタイヤを除くか（タイヤを別に描くときのホイール）
     * @param mirrorX     左右を反転するか（右側のタイヤ）。反転は頂点の段で済ませる（{@link #render} の理由と同じ）
     * @return 面が 1 つも無ければ null
     */
    GpuMesh gpuMesh(boolean withoutTire, boolean mirrorX) {
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
        int size = faces.length * 4 * DefaultVertexFormat.ENTITY.getVertexSize();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(size)) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            for (int face : faces) {
                int base = face * FACE_FLOATS;
                for (int k = 0; k < 4; k++) {
                    int offset = base + corners[k] * STRIDE;
                    builder.addVertex(flip * vertices[offset], vertices[offset + 1], vertices[offset + 2])
                            .setColor(vertices[offset + 8], vertices[offset + 9], vertices[offset + 10],
                                    vertices[offset + ALPHA_OFFSET])
                            .setUv(vertices[offset + 6], vertices[offset + 7])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(0)
                            .setNormal(flip * vertices[offset + 3], vertices[offset + 4], vertices[offset + 5]);
                }
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "kurumamod car mesh",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                gpuMeshes[index] = new GpuMesh(buffer, mesh.drawState().indexCount());
                return gpuMeshes[index];
            }
        }
    }

    /**
     * シェーダーパック（Iris）用に GPU に置いた不透明な面。<b>ワールドの描画の最中に呼ぶこと。</b>
     * その間は {@code BufferBuilder} が Iris の長い形式で積むので、接線などがそのまま焼き込まれる。
     *
     * <p>パックの頂点シェーダーは明るさを頂点から読むので外から渡せない。そこで<b>明るさごとに別に焼く</b>
     * （普通は数通りで済み、値は 16 段×16 段なので増えても上限がある）。</p>
     */
    GpuMesh packMesh(boolean withoutTire, boolean shadowCaster, boolean mirrorX, int packedLight) {
        int key = packedLight << 3 | (shadowCaster ? 4 : 0) | (withoutTire ? 2 : 0) | (mirrorX ? 1 : 0);
        GpuMesh mesh = packMeshes.get(key);
        if (mesh != null) {
            return mesh;
        }
        int[] faces = shadowCaster ? opaqueShadowFaces : withoutTire ? opaqueRimFaces : opaqueFaces;
        if (faces.length == 0) {
            return null;
        }
        float flip = mirrorX ? -1.0F : 1.0F;
        int size = faces.length * 4 * DefaultVertexFormat.ENTITY.getVertexSize() * 2;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(size)) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            // 頂点を積む描き方と同じ道を通す。Iris の BufferBuilder は 1 頂点ずつ積むと法線を面から計算し直すが、
            // Sodium へ塊で渡す道（BulkVertexWriter）はそこを通らないので、別の道で焼くと陰影が変わる
            renderFaces(faces, new Matrix4f(), new Matrix3f(), flip, builder, packedLight, OverlayTexture.NO_OVERLAY,
                    1.0F, 1.0F, 1.0F, 1.0F);
            try (MeshData data = builder.buildOrThrow()) {
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "kurumamod car mesh (pack)",
                        GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
                mesh = new GpuMesh(buffer, data.drawState().indexCount());
                packMeshes.put(key, mesh);
                return mesh;
            }
        }
    }

    /** GPU に置いた頂点を返す。 */
    private void releaseGpu() {
        packMeshes.values().forEach(mesh -> mesh.vertices().close());
        packMeshes.clear();
        for (ObjMesh lod : lods) {
            if (lod != null) {
                ((ObjModel) lod).releaseGpu();
            }
        }
        for (int i = 0; i < gpuMeshes.length; i++) {
            if (gpuMeshes[i] != null) {
                gpuMeshes[i].vertices().close();
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

    /**
     * 頂点を積む仕事を {@code SubmitNodeCollector} に預ける。<b>26.3 の描画は「集めてから流す」</b>ので、
     * {@code render} は預けたときではなく、あとで段ごとに呼ばれる。
     *
     * <p>そのため、預けたときの {@link #alphaScale}（ゴーストの濃さ・車内から見たガラス）を覚えておいて、
     * 呼ばれたときに戻して描く。<b>static な状態を読む描き方は、ここを通さずに預けると
     * 描かれるときには 1 に戻っている。</b>姿勢は {@code SubmitNodeCollector} が預けた時点で写し取る。</p>
     */
    public static void submit(net.minecraft.client.renderer.SubmitNodeCollector collector, PoseStack pose,
                              net.minecraft.client.renderer.rendertype.RenderType type,
                              net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer geometry) {
        float alpha = alphaScale;
        if (alpha == 1.0F) {
            collector.submitCustomGeometry(pose, type, geometry);
            return;
        }
        collector.submitCustomGeometry(pose, type, (p, consumer) -> {
            float saved = alphaScale;
            alphaScale = alpha;
            try {
                geometry.render(p, consumer);
            } finally {
                alphaScale = saved;
            }
        });
    }

    /** 預けた先で {@link PoseStack} を使う描き方（ハンドルなど）のために、{@code pose} を頭に持つ PoseStack を作る。 */
    public static PoseStack stackOf(PoseStack.Pose pose) {
        PoseStack stack = new PoseStack();
        stack.last().set(pose);
        return stack;
    }

    /** 面の 4 隅を積む順。 */
    private static final int[] CORNERS = {0, 1, 2, 3};
    private static final int[] MIRRORED_CORNERS = {2, 1, 0, 3};

    /**
     * 面の 4 隅を積む順。<b>鏡像（{@code flip} が負）のときは逆に回す。</b>
     *
     * <p>X を反転すると面の巻き方向が裏返る。1.21.1 はカリング無しなので気にしなくてよかったが、
     * 26.3 の {@code entity_cutout} は<b>表と裏で陰影を分けて付ける</b>（{@code PER_FACE_LIGHTING}。裏から見た面は
     * 法線を反転して照らす）ので、裏返ったままだと右側のタイヤだけ陰影が逆になる。積む順を逆にして表に戻す。</p>
     *
     * <p><b>逆に回すときは 2-1-0-3 にすること（0-3-2-1 ではいけない）。</b>三角形は 3 番目の頂点を 2 回積んだ
     * 縮退四角形（a, b, c, c）なので、0-3-2-1 だと a, c, c, b となり<b>最初の 3 頂点のうち 2 つが同じ点</b>になる。
     * シェーダーパック（Iris）は 4 頂点ごとに最初の 3 頂点から接線を求めて頂点に足す
     * （{@code ModelToEntityVertexSerializer}・{@code MixinBufferBuilder#fillExtendedData}）ので、ここで 0 割りになって
     * 接線が NaN になり、パックの後段のぼかしが NaN を広げて<b>右のタイヤから空へ伸びる巨大な黒い三角</b>になった
     * （タイヤの回転に合わせて動く。パック無しでは出ない）。2-1-0-3 なら c, b, a, c で最初の 3 つが揃わず、
     * 四角形を割る対角線（0-2）も 0-1-2-3 と同じまま（{@code QUADS} は 1 枚目の頂点から割る）。</p>
     */
    static int[] cornerOrder(float flip) {
        return flip < 0.0F ? MIRRORED_CORNERS : CORNERS;
    }

    /** UV をずらす量（積む順の 4 隅ごと）。テクスチャの 1 画素よりずっと小さい。 */
    private static final float UV_NUDGE = 2.0e-6F;
    static final float[] NUDGE_U = {0.0F, UV_NUDGE, UV_NUDGE, 0.0F};
    static final float[] NUDGE_V = {0.0F, 0.0F, UV_NUDGE, UV_NUDGE};

    /**
     * 面の UV が 1 点（か 1 本の線）に潰れているか。積む順（{@code corners}）の最初の 3 頂点で見る。
     *
     * <p><b>シェーダーパック（Iris）は 4 頂点ごとに位置と UV から接線を求めて頂点に足す</b>
     * （{@code NormalHelper#computeTangent}）。UV が潰れていると接線が長さ 0 になり、パックのシェーダーが
     * {@code normalize} して NaN になる。NaN はパックの後段（ブルーム・TAA・ぼかし）で周りへ広がり、
     * <b>タイヤから空へ伸びる巨大な黒い三角</b>になった（タイヤの回転に合わせて動く。左右どちらの輪でも出る）。
     * 色を 1 画素で指すだけのホイール・タイヤ・ナンバー灯の面は UV が 1 点に潰れているのがふつうなので、
     * パック使用中だけ 4 隅の UV をほんの少し（{@link #UV_NUDGE}）ずらして接線が決まるようにする。
     * 指している色は変わらない。パック無しではずらさない。</p>
     */
    static boolean uvDegenerate(float[] vertices, int base, int[] corners, int stride) {
        int o0 = base + corners[0] * stride;
        int o1 = base + corners[1] * stride;
        int o2 = base + corners[2] * stride;
        float du1 = vertices[o1 + 6] - vertices[o0 + 6];
        float dv1 = vertices[o1 + 7] - vertices[o0 + 7];
        float du2 = vertices[o2 + 6] - vertices[o0 + 6];
        float dv2 = vertices[o2 + 7] - vertices[o0 + 7];
        return Math.abs(du1 * dv2 - du2 * dv1) < 1.0e-12F;
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

        // 1.21 の VertexConsumer には Matrix3f で法線を回す口が無いので、ここで回して渡す
        Vector3f n = new Vector3f();
        int[] corners = cornerOrder(flip);
        // シェーダーパック使用中は、UV の潰れた面の UV を少しずらす（uvDegenerate）
        boolean pack = IrisScene.packActive();
        for (int face : faces) {
            int base = face * FACE_FLOATS;
            boolean nudge = pack && uvDegenerate(vertices, base, corners, STRIDE);
            for (int k = 0; k < 4; k++) {
                int offset = base + corners[k] * STRIDE;
                normal.transform(n.set(flip * vertices[offset + 3], vertices[offset + 4], vertices[offset + 5]));
                consumer.addVertex(matrix, flip * vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .setColor(vertices[offset + 8] * tintRed,
                                vertices[offset + 9] * tintGreen,
                                vertices[offset + 10] * tintBlue,
                                vertices[offset + ALPHA_OFFSET] * alpha)
                        .setUv(vertices[offset + 6] + (nudge ? NUDGE_U[k] : 0.0F),
                                vertices[offset + 7] + (nudge ? NUDGE_V[k] : 0.0F))
                        .setOverlay(packedOverlay)
                        .setLight(packedLight)
                        .setNormal(n.x, n.y, n.z);
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
        Vector3f n = new Vector3f();
        boolean pack = IrisScene.packActive();
        for (int index : face.faces()) {
            int base = index * FACE_FLOATS;
            // シェーダーパック使用中は、UV の潰れた面の UV を少しずらす（uvDegenerate）
            boolean nudge = pack && uvDegenerate(vertices, base, CORNERS, STRIDE);
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                normal.transform(n.set(vertices[offset + 3], vertices[offset + 4], vertices[offset + 5]));
                consumer.addVertex(matrix, vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .setColor(red, green, blue, 1.0F)
                        .setUv(vertices[offset + 6] + (nudge ? NUDGE_U[corner] : 0.0F),
                                vertices[offset + 7] + (nudge ? NUDGE_V[corner] : 0.0F))
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                        .setLight(packedLight)
                        .setNormal(n.x, n.y, n.z);
            }
        }
    }
}
