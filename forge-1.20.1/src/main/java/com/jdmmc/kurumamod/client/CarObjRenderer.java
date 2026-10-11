package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 車の描画。Blender で作った OBJ を、車体 1 つ ＋ タイヤ 4 つとして組み立てる。
 *
 * <h2>モデルのパスと基準寸法は {@link CarModel}（JSON）にある</h2>
 *
 * <ul>
 *   <li>車体 … <b>原点は地面の高さ・前後輪の中点・左右中央</b>。
 *       大きさは JSON の {@code bodyScale}（既定 1.0 ＝ メッシュのまま）</li>
 *   <li>タイヤ … <b>原点は車軸の中心、回転軸は左右方向</b>。半径を {@code designWheelRadius} に書く。
 *       <b>左側用を 1 つだけ</b>作る（右側は鏡像にして描く）。Blender で前方を -Y に向けると
 *       +X が車体の左になるので、<b>ホイールの表を +X へ向けて作る</b>のがこれにあたる</li>
 * </ul>
 *
 * <p>向きは Blender の標準のまま（前方 -Y・上 +Z）でよい。エンティティ空間への読み替えは
 * {@link ObjModel} が読み込み時に済ませる。</p>
 *
 * <h2>ホイールは換装できる</h2>
 *
 * <p>車が {@code PartFitment} で部品を履いていれば、タイヤのモデル・テクスチャ・取り付け位置は
 * そちらが決める（{@link CarPartModel}）。<b>置く位置と大きさの決め方は変わらない</b>——
 * 位置は諸元、大きさは諸元のタイヤ半径をメッシュの作りの半径で割った比。部品が差し替えるのは
 * 「どのメッシュをどれだけずらして描くか」だけで、接地の辻褄は物理側のままになる。</p>
 *
 * <h2>タイヤは諸元に追従し、車体はしない</h2>
 *
 * <p><b>ホイールベース・トレッド・タイヤ半径はタイヤの位置と大きさの定義そのもの</b>なので、
 * タイヤは {@link CarSpec} の値どおりに置いて拡大する（そうしないと見た目の接地点と、
 * 物理が旋回を解いている位置がずれる）。<b>調整画面で寸法を動かすとタイヤだけが動く。</b></p>
 *
 * <p>車体のほうはメッシュの形が正で、ホイールベースに合わせて伸ばすとキャビンごと
 * 伸びてしまうため追従させない。大きさは JSON の {@code bodyScale} で明示する。
 * <b>寸法を大きく動かせばタイヤはフェンダーからはみ出す。</b></p>
 *
 * <h2>ミラー</h2>
 *
 * <p>{@code mirror_*} のオブジェクトは車体とは別に描く（{@link MirrorRenderer}）。
 * 映せるときは<b>そのミラーの視点から描き直した景色</b>を貼り、そうでなければ暗いガラスにする。</p>
 *
 * <h2>ガラス</h2>
 *
 * <p>透ける面は<b>車体もタイヤも描き終えた最後</b>に、深度を書かない {@code RenderType} で
 * 重ねる（{@link #renderGlass}）。どの面が透けるかは {@link ObjModel} が読み込みのときに
 * 決めていて、ここは知らない。</p>
 *
 * <h2>姿勢を掛ける順番</h2>
 *
 * <p>ヨー → ピッチ → ロールの順。<b>先にヨーを掛けてからでないと</b>、ピッチとロールを
 * 世界座標のまま掛けることになり、車が東西を向いているときにロールがピッチとして出る。</p>
 */
public class CarObjRenderer extends EntityRenderer<CarEntity> {

    public CarObjRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = CarModel.defaults().shadowRadius();
    }

    @Override
    public ResourceLocation getTextureLocation(CarEntity car) {
        return CarModel.get(car.getCarId()).bodyTexture();
    }

    @Override
    public void render(CarEntity car, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        if (car.isGhost()) {
            renderGhost(car, partialTick, pose, buffer, packedLight);
            return;
        }
        CarModel model = CarModel.get(car.getCarId());
        // 影の大きさは描画より前に読まれるので、JSON を書き換えた直後の 1 フレームだけ古い値で出る
        this.shadowRadius = model.shadowRadius();

        pose.pushPose();
        applyRotations(car, pose, partialTick);

        CarSpec spec = car.getSpec();
        // シェーダーパックが太陽・月の影を描いている最中は、いちばん粗く間引いた版で描く。
        // 影の地図は 1 画素が数 cm 以上あるので形はほとんど変わらず、台数が多いときの影の描画が軽くなる
        int lod = HeadlightVolume.ShaderPacks.renderingShadowPass() ? SHADOW_LOD : lodLevel(car, partialTick);
        renderBody(car, model, spec, pose, buffer, packedLight, lod);
        renderWheels(car, model, spec, pose, buffer, packedLight, partialTick, lod);
        // ガラスは<b>いちばん最後</b>。ガラスより後に描いたものにはガラスの色が乗らないので、
        // 車体とタイヤを描き終えてから重ねる（理由は KurumaRenderTypes#glass）
        renderGlass(car, model, spec, pose, buffer, packedLight);

        pose.popPose();
        // 光（路面の照り返し・光の筋・光芒）は<b>車体もタイヤも描き終えてから</b>。
        // どれも深度を書かないので、先に描くと後から描いた車体に塗りつぶされる。
        // 姿勢は CarLights が自分で組み直すので、姿勢を掛ける前の pose を渡す
        CarLights.renderEffects(car, model, partialTick, pose, buffer);
        if (entityRenderDispatcher.shouldRenderHitBoxes() && !car.isInvisible()) {
            renderHull(car, partialTick, pose, buffer);
        }
        // 名前タグなどはバニラに任せる
        super.render(car, entityYaw, partialTick, pose, buffer, packedLight);
    }

    /** ゴーストの不透明度。追いかけていて前が見えなくならない濃さ。 */
    private static final float GHOST_ALPHA = 0.42F;

    /** ゴーストを描いている最中なら、いまどちらの回か。普通の車なら null。 */
    private enum GhostPass { DEPTH, COLOR }

    @Nullable
    private GhostPass ghostPass;

    /**
     * 記録を再生している車（ゴースト）。<b>同じ形を 2 回描く。</b>
     *
     * <p>1 回目は深度だけ、2 回目は色を半透明で（{@link KurumaRenderTypes#ghostDepth}）。
     * 形を組む処理は普通の車と共通で、どの {@code RenderType} に積むかだけを
     * {@link #ghostPass} で切り替える。</p>
     *
     * <p>描かないもの: シェーダーパックの影（地面に影が落ちると実在の車に見える）、
     * 灯火の光（レンズはただの面として描く）、ミラーの映り込み、名前タグ。</p>
     */
    private void renderGhost(CarEntity car, float partialTick, PoseStack pose,
                             MultiBufferSource buffer, int packedLight) {
        if (HeadlightVolume.ShaderPacks.renderingShadowPass()) {
            return;
        }
        CarModel model = CarModel.get(car.getCarId());
        this.shadowRadius = 0.0F;
        CarSpec spec = car.getSpec();
        try {
            for (GhostPass pass : GhostPass.values()) {
                ghostPass = pass;
                ObjModel.alphaScale = pass == GhostPass.COLOR ? GHOST_ALPHA : 1.0F;
                pose.pushPose();
                applyRotations(car, pose, partialTick);
                renderBody(car, model, spec, pose, buffer, packedLight, 0);
                renderWheels(car, model, spec, pose, buffer, packedLight, partialTick, 0);
                renderGlass(car, model, spec, pose, buffer, packedLight);
                pose.popPose();
            }
        } finally {
            ghostPass = null;
            ObjModel.alphaScale = 1.0F;
        }
    }

    /** 不透明な面を積む先。ゴーストのときは回に応じたものへ差し替える。 */
    private RenderType solid(ResourceLocation texture) {
        if (ghostPass == null) {
            return RenderType.entityCutoutNoCull(texture);
        }
        return ghostPass == GhostPass.DEPTH ? KurumaRenderTypes.ghostDepth(texture) : KurumaRenderTypes.ghostColor(texture);
    }

    /** 透ける面（ガラス）を積む先。ゴーストのときは不透明な面と同じ扱いにする。 */
    private RenderType clear(ResourceLocation texture) {
        return ghostPass == null ? KurumaRenderTypes.glass(texture) : solid(texture);
    }

    /**
     * GPU に置いたメッシュで描くか。ゴーストは頂点を積んで描く（α を掛けられるのはそちらだけ）。
     * アイコンも頂点を積む（{@link CarMeshes} はその場で描いてしまい、積む先の束と順番が揃わないため）。
     */
    private boolean meshes() {
        return ghostPass == null && !icon && CarMeshes.enabled();
    }

    /** アイテムのアイコンとして描いている最中か（{@link #renderIcon}）。 */
    private boolean icon;

    /**
     * アイテムのアイコンとして描く（{@link CarIcons} が画像に焼く）。姿勢は掛けず、エンティティの
     * 原点（重心・シャシー基準面）を中心に「+X 右・+Y 上・-Z 前」の向きのまま描く。
     *
     * <p>車は {@link CarEntity#becomeIcon} で用意したもの。描くのは車体・タイヤ・ガラスだけで、
     * 路面の照り返しや光の筋、当たり判定の箱、名前タグは描かない。</p>
     */
    public void renderIcon(CarEntity car, PoseStack pose, MultiBufferSource buffer, int packedLight) {
        CarModel model = CarModel.get(car.getCarId());
        CarSpec spec = car.getSpec();
        icon = true;
        try {
            renderBody(car, model, spec, pose, buffer, packedLight, 0);
            renderWheels(car, model, spec, pose, buffer, packedLight, 1.0F, 0);
            renderGlass(car, model, spec, pose, buffer, packedLight);
        } finally {
            icon = false;
        }
    }

    /**
     * {@link #renderIcon} で描くものを囲む箱 {minX, minY, minZ, maxX, maxY, maxZ}（同じ座標系）。
     * 車体のメッシュの箱と、4 本のタイヤの位置と大きさを合わせたもの。
     */
    public static double[] iconBounds(CarEntity car) {
        CarModel model = CarModel.get(car.getCarId());
        CarSpec spec = car.getSpec();
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};

        // 車体。描くときと同じ変換（applyBodyTransform）を 8 隅に掛ける
        double[] mesh = ObjModel.get(model.bodyModel()).bounds();
        PoseStack pose = new PoseStack();
        applyBodyTransform(model, spec, pose);
        org.joml.Matrix4f matrix = pose.last().pose();
        for (int corner = 0; corner < 8; corner++) {
            org.joml.Vector3f point = matrix.transformPosition(new org.joml.Vector3f(
                    (float) mesh[(corner & 1) == 0 ? 0 : 3],
                    (float) mesh[(corner & 2) == 0 ? 1 : 4],
                    (float) mesh[(corner & 4) == 0 ? 2 : 5]));
            include(box, point.x(), point.y(), point.z());
        }

        // タイヤ。太さは半径の半分を見込む（車体からはみ出すのは外側だけ）
        CarModel.Vec3 offset = CarPartModel.wheelOf(model, car.getEffectiveFitment()).offset();
        double radius = spec.wheelRadius();
        for (Wheel wheel : Wheel.VALUES) {
            double outward = wheel.isLeft() ? -offset.x() : offset.x();
            double x = spec.wheelRightOffset(wheel) + outward;
            double y = -car.getRenderSuspensionLength(wheel, 1.0F) + offset.y();
            double z = -spec.wheelForwardOffset(wheel) + offset.z();
            double side = Math.signum(x) * radius * 0.5;
            include(box, x + side, y - radius, z - radius);
            include(box, x, y + radius, z + radius);
        }
        return box;
    }

    private static void include(double[] box, double x, double y, double z) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x);
        box[4] = Math.max(box[4], y);
        box[5] = Math.max(box[5], z);
    }

    /**
     * 車体の姿勢。
     *
     * <p>渡ってくる {@code entityYaw} は {@code LevelRenderer} が<b>素の線形補間</b>で作った値で、
     * ヨー角は ±180 度で折り返すため<b>真北をまたぐ瞬間に「-358 度ぶんの回転」と解釈され、
     * 1 ティックで車体が 1 周する</b>。{@link Mth#rotLerp} で取り直すこと。</p>
     *
     * <p>{@link MirrorRenderer} も鏡の位置と向きを出すのにこれを呼ぶ。<b>車体の変換は
     * 1 か所に保つこと</b>——別の式で書き直すと、車高や荷重配分を変えたときに
     * 鏡だけが取り残される。</p>
     */
    static void applyRotations(CarEntity car, PoseStack pose, float partialTick) {
        // 渡ってくる entityYaw も car.getYRot() も、他人の車ではバニラが 1.4 度刻みに
        // 量子化した値から作られている。旋回中に回転の速さがガタつき、車体の端で
        // いちばん大きく出る（ケツが揺れて見える）ので、自前で配った float を使う
        float yaw = car.getRenderYaw(partialTick);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        // ここまで来ると「+X が車体右・+Y が上・-Z が車体前方」。傾きはこの向きで掛ける
        // 姿勢もティックの間を補間する。位置だけ補間して傾きを生の同期値で描くと、
        // 車体だけが 20Hz で階段状に動いてカクついて見える
        pose.mulPose(Axis.XP.rotationDegrees(car.getRenderPitch(partialTick) * Mth.RAD_TO_DEG));
        // ロールは正で右下がり。+X（右）を下げたいので符号を反転させる
        pose.mulPose(Axis.ZP.rotationDegrees(-car.getRenderRoll(partialTick) * Mth.RAD_TO_DEG));
    }

    /** ここより遠い車は頂点を間引いた版で描く [ブロック]（{@link ObjModel#lod}）。 */
    private static final double LOD_NEAR = 24.0;
    private static final double LOD_FAR = 64.0;

    /** シェーダーパックの影を描くときの間引き（{@link ObjModel#lod} のいちばん粗い段）。 */
    private static final int SHADOW_LOD = 2;

    /**
     * 何段階間引くか。カメラからの距離で決める。
     *
     * <p>遠い車ほど画面では小さく、細部は数画素に潰れて見分けられない。シェーダーパックを使っていると
     * 車 1 台の頂点の数がそのまま重さになる（影の段と本描画で 2 回描くうえ、1 頂点ごとにパック用の
     * 情報を足す）ので、見えないぶんを減らす。</p>
     */
    private static int lodLevel(CarEntity car, float partialTick) {
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double distance = eye.distanceToSqr(car.getPosition(partialTick));
        return distance > LOD_FAR * LOD_FAR ? 2 : distance > LOD_NEAR * LOD_NEAR ? 1 : 0;
    }

    private void renderBody(CarEntity car, CarModel model, CarSpec spec, PoseStack pose,
                            MultiBufferSource buffer, int packedLight, int lod) {
        pose.pushPose();
        applyBodyTransform(model, spec, pose);

        // ここでは不透明な面だけ。ガラスは renderGlass が最後に描く。
        // GPU に置いたまま描けるならそうする（CarMeshes。シェーダーパック中などは以前の描き方）
        // 遠ければ間引いた版。灯火・鏡・ガラスは元のメッシュのまま（数が少なく、位置を測るのにも使うため）
        ObjModel bodyMesh = ObjModel.get(model.bodyModel()).lod(lod);
        if (HeadlightVolume.ShaderPacks.renderingShadowPass()) {
            // シェーダーパックが太陽・月の影を描いている最中は、サスペンション（susp*）を落とさない
            bodyMesh.renderShadowCaster(pose.last(),
                    buffer.getBuffer(RenderType.entityCutoutNoCull(model.bodyTexture())),
                    packedLight, OverlayTexture.NO_OVERLAY);
        } else if (meshes()) {
            CarMeshes.draw(bodyMesh, false, false, model.bodyTexture(), pose.last(), packedLight);
        } else {
            bodyMesh.render(pose.last(),
                    buffer.getBuffer(solid(model.bodyTexture())),
                    packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F, null, false,
                    ObjModel.Pass.OPAQUE);
        }

        // 部品の形（part_）。本体の面からは外してあるので、ここが描かないと穴が開く。
        // 数が少ないので間引かず、GPU にも置かない（車ごとに出すものが違う）
        ObjModel body = ObjModel.get(model.bodyModel());
        CarModel.BodyParts parts = model.bodyParts(car.getEffectiveFitment(), body.optionalNames());
        body.renderOptional(pose.last(), buffer.getBuffer(solid(model.bodyTexture())),
                packedLight, OverlayTexture.NO_OVERLAY, parts.shown(), ObjModel.Pass.OPAQUE);

        // 灯火は車体と同じ姿勢・同じ拡大率で描く。位置も向きもメッシュから読むので、
        // ここで車体と同じ変換の中に入れておけば JSON に座標を書かなくて済む
        if (ghostPass != null) {
            // ゴーストは光らせない。レンズは車体のパスから外してあるので、ただの面として埋める
            body.renderLightsPlain(pose.last(), buffer.getBuffer(solid(model.bodyTexture())),
                    packedLight, OverlayTexture.NO_OVERLAY);
            pose.popPose();
            return;
        }
        CarLights.renderLenses(car, body, pose, buffer, packedLight, model.bodyTexture());

        // 鏡面も同じ変換の中で描く。<b>車体のパスから外してあるので、ここが描かないと穴が開く</b>
        MirrorRenderer.renderSurfaces(car, body, pose, buffer, packedLight, model.bodyTexture());

        pose.popPose();
    }

    /**
     * 車体の透ける面（ガラス）。<b>車体もタイヤも描き終えた後に呼ぶこと。</b>
     *
     * <p>ガラスは深度を書かない（{@link KurumaRenderTypes#glass}）ので、<b>後から描いたものは
     * ガラスの色を通らない</b>。先に描いてしまうと、窓越しのタイヤにガラスの色が乗らない。</p>
     */
    private void renderGlass(CarEntity car, CarModel model, CarSpec spec, PoseStack pose,
                             MultiBufferSource buffer, int packedLight) {
        ObjModel body = ObjModel.get(model.bodyModel());
        boolean optional = body.hasOptional(ObjModel.Pass.TRANSLUCENT);
        if (!body.hasTranslucent() && !optional) {
            return;
        }
        pose.pushPose();
        applyBodyTransform(model, spec, pose);
        var consumer = buffer.getBuffer(clear(model.bodyTexture()));
        body.render(pose.last(), consumer,
                packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F, null, false,
                ObjModel.Pass.TRANSLUCENT);
        if (optional) {
            // 部品の形の透ける面（ウイングの透明な翼端板など）
            CarModel.BodyParts parts = model.bodyParts(car.getEffectiveFitment(), body.optionalNames());
            body.renderOptional(pose.last(), consumer, packedLight, OverlayTexture.NO_OVERLAY,
                    parts.shown(), ObjModel.Pass.TRANSLUCENT);
        }
        pose.popPose();
    }

    /**
     * 車体メッシュを置く（平行移動と拡大）。姿勢はこれより先に掛かっている。
     *
     * <p>{@link MirrorRenderer} が鏡の世界での位置を出すのにも使う。</p>
     */
    static void applyBodyTransform(CarModel model, CarSpec spec, PoseStack pose) {
        // エンティティの位置はシャシー基準面。モデルは地面に立った状態で作られているので下げる。
        // 現在の車高で下げてはいけない。サスを伸ばすとシャシー基準面も同じだけ上がるので、
        // 車体とタイヤの両方を車高で動かすと打ち消し合い、車体とタイヤの間隔が変わらなくなる。
        // 拡大より先に平行移動すること（後に置くと下げ量まで拡大される）
        // 前後輪の中点まで下がる。エンティティの原点は<b>重心</b>なので、重心を前寄りにすると
        // タイヤは相対的に後ろへ動く（前 56% なら 0.187m）。車体メッシュは前後輪の中点を
        // 原点に作られているので、この差を埋めないとホイールアーチがタイヤから前へずれる。
        // 荷重配分を入れた日にここを直し忘れ、body.offset で手当てする羽目になった
        // エンティティ空間は -Z が前なので、物理側（+Z が前）とは符号が逆になる
        double axleMidpoint = -spec.axleMidpointOffset();

        CarModel.Vec3 offset = model.bodyOffset();
        pose.translate(offset.x(), offset.y() - model.designRideHeight(), offset.z() + axleMidpoint);

        // メッシュの作りの大きさを既定の車格へ正規化する。5.2m で作ったモデルに
        // designWheelBase = 5.2 と書けば半分に縮み、ホイールアーチがタイヤの位置に合う。
        //
        // 比べる相手は CarSpec.DEFAULT であって、今の諸元ではない。ここを spec.wheelBase() に
        // すると、ホイールベースのスライダーで車体まで伸び縮みする（キャビンごと伸びる）。
        // 定数なので走行中に変わることはない
        double fit = CarSpec.DEFAULT.wheelBase() / model.designWheelBase();

        // その上に手で決める拡大率。原点が地面なので、上げると車体は上へ伸びる
        CarModel.Vec3 scale = model.bodyScale();
        pose.scale((float) (fit * scale.x()), (float) (fit * scale.y()), (float) (fit * scale.z()));
    }

    private void renderWheels(CarEntity car, CarModel model, CarSpec spec, PoseStack pose,
                              MultiBufferSource buffer, int packedLight, float partialTick, int lod) {
        // 見た目だけ抑えた角度。物理が使う切れ角とは別物（大きく流したとき、進行方向に対して
        // 垂直を超えたタイヤが描かれるのを防ぐ）
        float steer = car.getVisualSteerAngle(partialTick);

        // 履いている部品と車種の定義を重ねた結果。何も履いていなければ車種の値がそのまま返る。
        // 重ね合わせは CarPartModel に寄せてあるので、ここは「部品があれば／無ければ」を知らない
        CarPartModel.Wheel look = CarPartModel.wheelOf(model, car.getEffectiveFitment());
        CarModel.Vec3 offset = look.offset();

        // 拡大率は 2 段構え。メッシュが作られた半径から諸元の半径へ正規化したうえで、
        // JSON の拡大率を掛ける。前者があるので、調整画面でタイヤ半径を変えると
        // 見た目もそのまま追従する（車高も上がるので接地したまま車体が持ち上がる）。
        // 後者で縦（Y・Z）を触ると接地が崩れる——物理はこの補正を知らないため。
        // 太さ（X）だけなら安全
        double scale = spec.wheelRadius() / look.designRadius();
        CarModel.Vec3 wheelScale = look.scale();
        float scaleX = (float) (scale * wheelScale.x());
        float scaleY = (float) (scale * wheelScale.y());
        float scaleZ = (float) (scale * wheelScale.z());

        // タイヤ。タイヤ部品を履いておらず、ホイールのメッシュにタイヤが付属していれば、今までどおり
        // メッシュを丸ごと描く（既存のパックの見た目はそのまま）。それ以外はホイールをタイヤ抜きで描き、
        // タイヤ（選んだもの、無ければ MOD 標準）を、内径がリムへ・外径がメッシュのタイヤ半径へ来るよう
        // 半径方向に合わせ直して描く。拡大はホイールと同じ pose が受け持つので、物理のタイヤ半径に接地する
        ObjModel wheelMesh = ObjModel.get(look.model());
        CarPartModel.Tire tire = CarPartModel.tireOf(car.getEffectiveFitment());
        boolean attachedTire = tire == null && wheelMesh.hasTire();
        ObjModel tireMesh = null;
        ResourceLocation tireTexture = null;
        if (!attachedTire) {
            CarPartModel.Tire chosen = tire != null ? tire : CarPartModel.DEFAULT_TIRE;
            tireMesh = ObjModel.fittedTire(chosen.model(), chosen.innerRadius(), chosen.outerRadius(),
                    beadRadius(look, wheelMesh), look.designRadius()).lod(lod);
            tireTexture = chosen.texture();
        }
        // 寸法を測り終えてから間引いた版へ（ビードの半径などは元のメッシュで測る）
        ObjModel wheelDrawn = wheelMesh.lod(lod);
        // シェーダーパックが太陽・月の影を描いている最中は、ホイール（リム）を描かずタイヤだけ描く。
        // タイヤの影は落ち、スポークの影は落ちない
        boolean shadowPass = HeadlightVolume.ShaderPacks.renderingShadowPass();

        for (Wheel wheel : Wheel.VALUES) {
            pose.pushPose();

            // 車軸の位置。前後・左右は諸元（ホイールベースとトレッドはタイヤの位置の定義
            // そのものなので、ここを見た目の都合でずらすと接地点と食い違う）、上下はサスが
            // どれだけ伸びているか。タイヤ中心 = シャシー基準面からサス長ぶん下。静止時は
            // サス長 = 車高 - タイヤ半径 なので、タイヤはちょうど地面に接する。
            // JSON のオフセットはこれに対する補正で、X は左右で符号を反転させて「外向き」にする
            double outward = wheel.isLeft() ? -offset.x() : offset.x();
            pose.translate(spec.wheelRightOffset(wheel) + outward,
                    -car.getRenderSuspensionLength(wheel, partialTick) + offset.y(),
                    -spec.wheelForwardOffset(wheel) + offset.z());

            // キャンバー。負で「上が内側」（ネガティブキャンバー）になるよう、左右で符号を
            // 反転させる。Z 軸まわりの正回転はタイヤの上を左（-X）へ倒す。
            // 鏡像化より先に掛かるので、鏡像に倒れたりはしない
            if (look.camber() != 0.0) {
                float camber = (float) look.camber();
                pose.mulPose(Axis.ZP.rotationDegrees(wheel.isLeft() ? camber : -camber));
            }

            // 切れ角は正で右だが、Y 軸まわりの正回転は左へ向くので反転する。
            // 切れ角はラジアン（最大でも 0.61）なので、度へ直さずに渡すと 0.6 度しか切れず
            // 「ハンドルを切ってもタイヤの向きが変わらない」ように見える
            // 輪ごとの角度はアッカーマンとトーのぶんだけずれる（後輪もトーのぶん向く）。
            // ロールや横力で変わるぶんは同期していないので描かない（1 度前後で見て分からない）
            float wheelSteer = (float) spec.staticSteer(wheel, wheel.isFront() ? steer : 0.0);
            if (wheelSteer != 0.0f) {
                pose.mulPose(Axis.YP.rotationDegrees(-wheelSteer * Mth.RAD_TO_DEG));
            }

            // タイヤの回転。輪ごとに持っているので、空転もロックも見える。
            // X 軸まわりの正回転はタイヤ上部を後ろへ送る（＝後退）ので、前進には反転が要る
            pose.mulPose(Axis.XP.rotationDegrees(
                    -car.getWheelRoll(wheel, partialTick) * Mth.RAD_TO_DEG));

            pose.scale(scaleX, scaleY, scaleZ);

            // モデルは左側用。右側は鏡像にする。
            // 鏡像化は頂点の段でやる（PoseStack ではなく ObjModel が X を反転する）。
            // pose.scale(-1, 1, 1) だと法線行列が壊れて鏡像側の陰影だけおかしくなる——
            // 理由は ObjModel#render の javadoc にある。ここより外側の回転は鏡像化された
            // メッシュに掛かるので、左右のタイヤは同じ向きへ切れる（鏡像に切れたりはしない）。
            // ホイールの表裏が逆に見えるときはこの条件を反転させる
            if (attachedTire && shadowPass) {
                wheelDrawn.renderTireOnly(pose.last(),
                        buffer.getBuffer(RenderType.entityCutoutNoCull(look.texture())),
                        packedLight, OverlayTexture.NO_OVERLAY, !wheel.isLeft());
            } else if (attachedTire) {
                drawMesh(wheelDrawn, look.texture(), pose, buffer, packedLight, !wheel.isLeft());
            } else {
                if (!shadowPass) {
                    drawRim(wheelDrawn, look.texture(), pose, buffer, packedLight, !wheel.isLeft());
                }
                drawMesh(tireMesh, tireTexture, pose, buffer, packedLight, !wheel.isLeft());
            }
            pose.popPose();
        }
    }

    /**
     * タイヤの内径（ビード）を置く半径（ホイールのメッシュの寸法）。
     *
     * <p>書かれていればその値。無ければ、付属のタイヤがあればその内径（作った人が嵌めた位置そのもの）、
     * それも無ければリムの外縁の {@link CarPartModel#BEAD_RATIO} 倍。</p>
     */
    private static double beadRadius(CarPartModel.Wheel look, ObjModel wheelMesh) {
        if (look.rimRadius() != null) {
            return look.rimRadius();
        }
        double[] attached = wheelMesh.tireRadialRange();
        if (attached != null) {
            return attached[0];
        }
        return wheelMesh.rimOuterRadius() * CarPartModel.BEAD_RATIO;
    }

    /** ホイールを、付属のタイヤを除いて描く。 */
    private void drawRim(ObjModel obj, ResourceLocation texture, PoseStack pose,
                         MultiBufferSource buffer, int packedLight, boolean mirrorX) {
        if (meshes()) {
            CarMeshes.draw(obj, true, mirrorX, texture, pose.last(), packedLight);
        } else {
            obj.renderWithoutTire(pose.last(), buffer.getBuffer(solid(texture)),
                    packedLight, OverlayTexture.NO_OVERLAY, mirrorX, ObjModel.Pass.OPAQUE);
        }
        if (obj.hasTranslucentRim()) {
            obj.renderWithoutTire(pose.last(), buffer.getBuffer(clear(texture)),
                    packedLight, OverlayTexture.NO_OVERLAY, mirrorX, ObjModel.Pass.TRANSLUCENT);
        }
    }

    /**
     * モデルを 1 つ描く。<b>不透明な面と透ける面（ガラス）を 2 回に分けて描く。</b>
     *
     * <p>{@code entityCutoutNoCull} を使うのは 2 つ理由がある。<b>右側のタイヤを鏡像で描くと
     * 面の巻き方向が裏返る</b>のでカリングを切る必要があること、そしてテクスチャの透明部分
     * （窓やグリルの抜き）を使えるようにするため。</p>
     *
     * <p><b>ただし cutout は「抜くか、抜かないか」しか扱えない。</b>シェーダが
     * α 0.1 未満を捨てるだけで混色しないので、半透明のテクスチャを貼っても
     * <b>ガラスはべったり不透明に出る</b>。透かすには混色する {@code RenderType} が要る。</p>
     *
     * <p>そこで透ける面だけ {@code entityTranslucent}（同じくカリング無し）で描き直す。
     * <b>順番が要点</b>——{@code MultiBufferSource} は {@code RenderType} が切り替わった
     * 時点で前の面をまとめて流すので、この呼び分けがそのまま「車体を描いてからガラス」に
     * なる。逆にすると、ガラスが深度を埋めた後ろに車体が来て<b>窓の向こうの車内が抜ける</b>。</p>
     *
     * <p>透ける面が 1 つも無ければ 2 パス目は呼ばない。呼ぶと {@code RenderType} が
     * 切り替わって<b>何も描かない描画呼び出しがタイヤ 4 本ぶん増える</b>だけになる。</p>
     */
    private void drawMesh(ObjModel obj, ResourceLocation texture, PoseStack pose,
                          MultiBufferSource buffer, int packedLight, boolean mirrorX) {
        if (meshes()) {
            CarMeshes.draw(obj, false, mirrorX, texture, pose.last(), packedLight);
        } else {
            VertexConsumer consumer = buffer.getBuffer(solid(texture));
            obj.render(pose.last(), consumer, packedLight, OverlayTexture.NO_OVERLAY,
                    1.0F, 1.0F, 1.0F, 1.0F, null, mirrorX, ObjModel.Pass.OPAQUE);
        }

        if (!obj.hasTranslucent()) {
            return;
        }
        // タイヤや部品にも透ける面があれば、そのメッシュの直後に重ねる。車体のガラスだけは
        // 全部を描き終えた最後へ回してある（{@link #renderGlass}）
        obj.render(pose.last(), buffer.getBuffer(clear(texture)),
                packedLight, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F, null, mirrorX, ObjModel.Pass.TRANSLUCENT);
    }

    /**
     * F3+B のときに、本当の当たり判定（{@link com.jdmmc.kurumamod.physics.CarHull}）を描く。
     * 地形用を橙、車同士用を水色で。
     *
     * <p><b>バニラが白で描く箱は当たり判定ではない。</b>サーバーの移動の検査に弾かれないよう、
     * 車体の箱の内側に収まる小さな板にしてある（{@code CarEntity#makeBoundingBox}）。</p>
     */
    private static void renderHull(CarEntity car, float partialTick, PoseStack pose, MultiBufferSource buffer) {
        // 地形用は橙、車同士用は水色
        renderHull(car, car.currentHull(), 1.0F, 0.75F, 0.0F, partialTick, pose, buffer);
        renderHull(car, car.currentBody(), 0.2F, 0.85F, 1.0F, partialTick, pose, buffer);
    }

    private static void renderHull(CarEntity car, com.jdmmc.kurumamod.physics.CarHull hull,
                                   float red, float green, float blue,
                                   float partialTick, PoseStack pose, MultiBufferSource buffer) {
        double[][] corners = hull.corners();
        // 箱はティックの位置で組んであり、描画の原点は補間した位置
        double originX = Mth.lerp(partialTick, car.xo, car.getX());
        double originY = Mth.lerp(partialTick, car.yo, car.getY());
        double originZ = Mth.lerp(partialTick, car.zo, car.getZ());
        VertexConsumer consumer = buffer.getBuffer(RenderType.lines());
        PoseStack.Pose entry = pose.last();
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                int j = i | bit;
                if (j == i) {
                    continue;
                }
                float[] a = {(float) (corners[i][0] - originX), (float) (corners[i][1] - originY),
                        (float) (corners[i][2] - originZ)};
                float[] b = {(float) (corners[j][0] - originX), (float) (corners[j][1] - originY),
                        (float) (corners[j][2] - originZ)};
                float dx = b[0] - a[0];
                float dy = b[1] - a[1];
                float dz = b[2] - a[2];
                float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
                float nx = dx / length;
                float ny = dy / length;
                float nz = dz / length;
            consumer.vertex(entry.pose(), a[0], a[1], a[2]).color(red, green, blue, 1.0F)
                    .normal(entry.normal(), nx, ny, nz).endVertex();
            consumer.vertex(entry.pose(), b[0], b[1], b[2]).color(red, green, blue, 1.0F)
                    .normal(entry.normal(), nx, ny, nz).endVertex();
            }
        }
    }
}
