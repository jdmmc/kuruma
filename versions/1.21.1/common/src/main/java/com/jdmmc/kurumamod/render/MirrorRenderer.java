package com.jdmmc.kurumamod.render;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;

import com.jdmmc.kurumamod.mixin.CameraAccessor;


import com.jdmmc.kurumamod.client.hook.FrameRender;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import com.jdmmc.kurumamod.client.model.CarModel;

/**
 * ルームミラーとドアミラーに、<b>実際に後ろの景色を映す</b>。
 *
 * <p>Minecraft には反射が無いので、映すには<b>世界をもう 1 回描く</b>しかない。
 * {@code LevelRenderer#renderLevel} は public なので、自前のカメラと射影行列を渡せば
 * 好きな位置・向きから描ける。描き先をフレームバッファへ差し替えて、その結果を
 * 鏡面のメッシュに貼る。</p>
 *
 * <h2>1 フレームに 1 枚だけ描く</h2>
 *
 * <p><b>ミラーが 3 枚あっても、世界を描き直す回数はフレームあたり 1 回に保つ。</b>
 * 順繰りに 1 枚ずつ更新するので、3 枚なら各ミラーは 1/3 の頻度で更新される。
 * ミラーは小さく、映っているのも遠くの景色なので、更新が粗くても見て分からない——
 * <b>3 枚を毎フレーム描くと、それだけでフレーム時間が 3〜4 倍になる。</b></p>
 *
 * <h2>狙いは車体基準で固定。板の傾きは見ない</h2>
 *
 * <p><b>入射角＝反射角をそのまま計算してはいけない。</b>板は「調整前」の角度で置かれて
 * いるうえ、運転者の目は板の正面には無いので、物理どおりに跳ね返すと<b>あさっての方向を
 * 映す</b>。同梱の見本で実測すると、3 枚とも<b>下を 21〜32 度、横を 11〜27 度</b>向いて
 * いて、地面しか映らなかった。</p>
 *
 * <p>実車ではこれを<b>乗った人がミラーを動かして合わせている</b>。板の角度は調整前の
 * 姿でしかない。そこで<b>「運転者が調整し終えたミラー」として、狙いをコードが決める</b>:</p>
 *
 * <ul>
 *   <li>ルームミラー … 真後ろ、中心が {@value #REAR_DOWN} 度下。横の画角 {@value #REAR_FOV} 度</li>
 *   <li>ドアミラー … 中心が {@value #DOOR_DOWN} 度下で、<b>地平線が鏡の中ほどより少し上</b>に来る。
 *       横の画角 {@value #DOOR_FOV} 度で、<b>自分の車の横腹が鏡の内側 {@value #DOOR_BODY_SHARE} 割ほどに
 *       映る</b>ところまで外へ振る（実車でいちばん普通の合わせ方）</li>
 * </ul>
 *
 * <p><b>画角は目の位置から決めない。固定の値を使う。</b>以前は「目から見た鏡の見込み角」に
 * していたが、ドアミラーは小さく目から遠いので縦 14 度ほどしかなく、そこへ 7 度下向きを
 * 足すと鏡の上端がちょうど地平線になり、<b>路面しか映らなかった</b>。値は実車の
 * ミラーの視野（UN ECE R46 の乗用車向けの規定と、実際の合わせ方）に寄せてある。
 * 縦の画角は鏡の横縦比から決まる。</p>
 *
 * <p>どちらかは<b>鏡の位置で決める</b>（車体中心から {@value #INTERIOR_HALF_WIDTH}m 以内なら
 * ルームミラー）。名前は見ない——left / right が実際の左右と入れ替わっていても正しく映る。</p>
 *
 * <p><b>板が担うのは「どこに・どれだけ・どの面に映すか」だけ。</b>位置と大きさと貼り付け先の
 * 平面はメッシュのまま使うので、モデル側で角度を合わせ込む作業は要らない。</p>
 *
 *
 * <h2>効かない場合</h2>
 *
 * <p>次のときは<b>暗いガラスとして描くだけ</b>にして、世界の描き直しはしない:</p>
 *
 * <ul>
 *   <li>設定が切ってある（H のメニュー。既定は<b>切</b>——重いので、要る人が入れる）</li>
 *   <li>描画設定が「最高（Fabulous）」。この設定では {@code renderLevel} が自分で
 *       描き先を持ち替えるので、こちらの差し替えが上書きされる</li>
 *   <li>シェーダーパックを使っている（Iris / Oculus）。描き直しを入れ子にすると画面全体が真っ黒になる。
 *       <b>入っているだけ（パックを切っている）なら映す</b>。以前はこのとき鏡面が消えたが、原因は描き先の
 *       不透明度が 0 になることだった（{@code renderFace} の終わりで 1 に塗り直している）</li>
 *   <li>運転していない。<b>映すのは自分が運転している車のミラーだけ</b>——他人の車のぶんまで
 *       描くと、映っているのを誰も見ないまま台数ぶん重くなる</li>
 * </ul>
 */
public final class MirrorRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 同時に映せる鏡の枚数。これを超えたぶんは暗いガラスのまま。 */
    public static final int MAX_MIRRORS = 4;

    /** 描き先の高さ [px]。幅は鏡の横縦比から決まる。 */
    private static final int TARGET_HEIGHT = 160;
    private static final int MIN_TARGET_WIDTH = 64;
    private static final int MAX_TARGET_WIDTH = 640;

    /** ここより車体中心に近ければルームミラー、遠ければドアミラー [m]。 */
    private static final double INTERIOR_HALF_WIDTH = 0.35;
    /** ルームミラーの中心が下を向く角度 [度]。地平線がほぼ真ん中に来る。 */
    private static final double REAR_DOWN = 1.0;
    /** ルームミラーの横の画角 [度]。リアガラス越しに後ろの車線ぶん。 */
    private static final double REAR_FOV = 34.0;
    /** ドアミラーの中心が下を向く角度 [度]。地平線が中ほどより少し上に来る。 */
    private static final double DOOR_DOWN = 1.5;
    /** ドアミラーの横の画角 [度]。平面鏡と凸面鏡の間くらい。 */
    private static final double DOOR_FOV = 22.0;
    /** ドアミラーの内側のうち、自分の車の横腹が映る割合。そのぶんだけ外へ振る。 */
    private static final double DOOR_BODY_SHARE = 0.15;

    private static final double MIN_FOV = 4.0;
    private static final double MAX_FOV = 120.0;

    /** 映していないときの鏡面の色。真っ黒にすると穴に見えるので、少しだけ明るくしておく。 */
    private static final float DEAD_DIM = 0.16F;

    /** 使い回すカメラ。毎フレーム作ると無駄なうえ、{@code setup} が持つ状態が捨てられる。 */
    private static final Camera CAMERA = new Camera();

    /** 鏡 1 枚ぶんの描き先。並びは {@code ObjModel#mirrors()} と同じ。 */
    private static final List<Slot> SLOTS = new ArrayList<>();

    /** 次に更新する鏡。 */
    private static int next;
    /** ミラーのために世界を描いている最中か。<b>入れ子で自分を映さないための旗。</b> */
    private static boolean rendering;
    /** いま描いている鏡の描き先（{@link #drawingTarget()}）。 */
    private static RenderTarget drawingTarget;
    /** 描画に失敗したので、この車を降りるまで描かない。 */
    private static boolean broken;
    /** 同じ理由で何度もログへ出さないための旗。 */
    private static String complaint;

    private record Slot(String name, RenderTarget target) {
    }

    private MirrorRenderer() {
    }

    /**
     * ミラーのために世界を描いている最中か。
     *
     * <p><b>この間はミラーを映してはいけない。</b>いま書き込んでいるフレームバッファを
     * 同時に読むことになる（GL では結果が定義されない）。</p>
     */
    public static boolean isRendering() {
        return rendering;
    }

    /**
     * ミラーのために世界を描いている間の描き先。それ以外のときは null。
     *
     * <p>{@code mixin/MainRenderTargetMixin} が、この間 {@code Minecraft#getMainRenderTarget()} の答えをこれに
     * 差し替える。<b>描画の途中で「画面の描き先」へつなぎ直す所がいくつもあり</b>（バニラの輪郭の枝、Iris。
     * Iris はシェーダーパックを切っていてもつなぎ直す）、そのたびにミラー用の地形や物が画面の描き先へ流れ出て、
     * 本編の描画に上書きされていた。鏡には描き始めに塗った空の色しか残らなかった（Iris で確認）。</p>
     */
    public static RenderTarget drawingTarget() {
        return rendering ? drawingTarget : null;
    }

    /** その鏡に貼る GL のテクスチャ id。{@link KurumaRenderTypes#mirror} が描く直前に聞きにくる。 */
    public static int textureId(int slot) {
        return slot >= 0 && slot < SLOTS.size() ? SLOTS.get(slot).target().getColorTextureId() : 0;
    }

    // ------------------------------------------------------------------
    // 毎フレーム、1 枚だけ描く
    // ------------------------------------------------------------------

    /**
     * <p><b>ここで描くのは、{@code GameRenderer#render} が呼ばれる直前。</b>
     * {@code Minecraft#runTick} は {@code onRenderTickStart} を投げてから画面を描くので、
     * この時点では描画の入れ子にならない（{@code renderLevel} の中から
     * {@code renderLevel} を呼ぶことになると、チャンクの可視判定もバッファも壊れる）。</p>
     */
    public static void onRenderTick(FrameRender event) {
        Minecraft mc = Minecraft.getInstance();
        CarEntity car = drivenCar(mc);
        if (car == null) {
            // 降りたら、失敗して止めていたのを解く（次に乗ったときにもう一度試す）
            broken = false;
            release();
            return;
        }
        if (broken || !available(mc)) {
            release();
            return;
        }

        CarModel model = CarModel.get(car.getCarId());
        ObjModel body = ObjModel.get(model.bodyModel());
        List<ObjModel.MirrorFace> faces = body.mirrors();
        if (faces.isEmpty()) {
            release();
            return;
        }

        syncSlots(faces);
        if (SLOTS.isEmpty()) {
            return;
        }

        next = (next + 1) % SLOTS.size();
        rendering = true;
        // パノラマ扱いにしておくこと。LevelRenderer#renderLevel は地形を描いた直後に
        //
        //     if (shouldShowEntityOutlines()) { entityTarget.clear(); getMainRenderTarget().bindWrite(false); }
        //
        // という枝を通る（バイトコードで確認）。この判定は<b>普通に遊んでいるあいだ常に真</b>
        // なので、そのままだと<b>地形だけミラーへ描かれ、エンティティから先は画面へ流れ出す</b>。
        // shouldShowEntityOutlines() は最初に isPanoramicMode() を見るので、ここで立てておけば
        // 枝ごと飛ぶ。バニラがパノラマ画像を撮るときと同じ手
        mc.gameRenderer.setPanoramicMode(true);
        try {
            renderFace(mc, car, model, faces.get(next), SLOTS.get(next), event.getPartialTick());
        } catch (RuntimeException e) {
            complain("ミラーの描画に失敗した。この車を降りるまで暗いガラスとして描く", e);
            // 止めておかないと、次のフレームで描き先を作り直してはまた失敗し続ける
            broken = true;
            release();
        } finally {
            mc.gameRenderer.setPanoramicMode(false);
            rendering = false;
            drawingTarget = null;
            // 失敗しても必ず画面へ戻す。ここを飛ばすと、以降の描画がミラーの
            // フレームバッファへ流れ込んで画面が真っ黒になる
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** 鏡 1 枚ぶん、その鏡が映す景色を描く。 */
    private static void renderFace(Minecraft mc, CarEntity car, CarModel model,
                                   ObjModel.MirrorFace face, Slot slot, DeltaTracker deltaTracker) {
        // 本編の世界の描画（GameRenderer#renderLevel）が使うのと同じ補間の比
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        // 車体と<b>まったく同じ変換</b>を通す。ここを別の式で書くと、車高や荷重配分を
        // 変えたときに鏡の位置だけが取り残される
        PoseStack pose = new PoseStack();
        CarObjRenderer.applyRotations(car, pose, partialTick);
        CarObjRenderer.applyBodyTransform(model, car.getSpec(), pose);
        Matrix4f matrix = new Matrix4f(pose.last().pose());
        Matrix3f normalMatrix = new Matrix3f(pose.last().normal());

        // 回転を掛けない版。<b>車体基準での位置</b>が要る（ルームミラーかドアミラーかは
        // 「車体中心からどれだけ外か」で決めるので、世界座標では判定できない）
        PoseStack local = new PoseStack();
        CarObjRenderer.applyBodyTransform(model, car.getSpec(), local);
        Vec3 onCar = transform(new Matrix4f(local.last().pose()), face.center());

        Vec3 carPos = new Vec3(
                Mth.lerp(partialTick, car.xOld, car.getX()),
                Mth.lerp(partialTick, car.yOld, car.getY()),
                Mth.lerp(partialTick, car.zOld, car.getZ()));

        Vec3 world = transform(matrix, face.center());
        Vec3 center = carPos.add(world);

        // 鏡の実寸は<b>変換した後で測る</b>。bodyScale もホイールベースの正規化も掛かった
        // 後の大きさが要るので、メッシュの寸法をそのまま使ってはいけない
        double halfWidth = transform(matrix, face.center().add(face.right().scale(face.width() * 0.5)))
                .subtract(world).length();
        double halfHeight = transform(matrix, face.center().add(face.up().scale(face.height() * 0.5)))
                .subtract(world).length();
        if (halfWidth < 1.0e-5 || halfHeight < 1.0e-5) {
            return;
        }

        // 狙いは車体基準で固定。<b>板の角度は使わない</b>（理由はクラスの javadoc）
        Vec3 look = rotate(normalMatrix, aim(onCar));

        // Minecraft の向きの決まり: 視線 = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
        float yaw = (float) (Math.atan2(-look.x, look.z) * Mth.RAD_TO_DEG);
        float pitch = (float) (-Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * Mth.RAD_TO_DEG);

        // 画角は固定の横の画角から、鏡の横縦比で縦を出す（射影は縦の画角で作る）
        double aspect = halfWidth / halfHeight;
        double horizontal = Math.toRadians(isInterior(onCar) ? REAR_FOV : DOOR_FOV);
        double fov = Mth.clamp(Math.toDegrees(2.0 * Math.atan(Math.tan(horizontal * 0.5) / aspect)),
                MIN_FOV, MAX_FOV);
        // 遠くの面が決まるまで（ワールドに入ってから本編が 1 回描かれるまで）は映さない。getDepthFar() は
        // GameRenderer#renderLevel が初めて走ったときに決まり、それまでは 0。0 のまま射影を作ると
        // 視錐台が潰れ、視錐台を合わせる繰り返し（Frustum#offsetToFullyIncludeCameraCube）から抜けられず
        // 画面が固まる（乗ったままワールドに入った最初のフレームで、Fabric 版で実際に起きた）
        float far = mc.gameRenderer.getDepthFar();
        if (!(far > 1.0F)) {
            return;
        }
        // 向きか画角が計算できない値なら、この鏡はこのフレームだけ描かない。NaN の行列で世界を描くと、
        // 視錐台を合わせる繰り返し（Frustum#offsetToFullyIncludeCameraCube）から抜けられず画面が固まる
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Double.isFinite(fov) || !Double.isFinite(aspect)
                || !Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)) {
            String message = "ミラーの向きが計算できない値になった。その鏡はそのフレームだけ描かない";
            if (!message.equals(complaint)) {
                // 値は最初の 1 回だけ（文言に入れると毎フレーム違う文言になり、ログが埋まる）
                LOGGER.warn("{}（yaw {}・pitch {}・画角 {}・縦横比 {}・位置 {}）", message, yaw, pitch, fov, aspect, center);
            }
            complaint = message;
            return;
        }

        // 乗っているプレイヤーをカメラの持ち主にしておくと、LevelRenderer が
        // 「一人称なので自分は描かない」と判断してくれる（鏡に自分の顔は要らない）
        CAMERA.setup(mc.level, mc.player, false, false, partialTick);
        // 鏡面そのものの中に埋まらないよう、映す向きへわずかに出す。<b>setPosition / setRotation は protected</b>
        // なので mixin の口（CameraAccessor）から呼ぶ。リフレクションで名前を引くと、Fabric の配布環境
        // （intermediary 名）で見つからない
        Vec3 at = center.add(look.scale(0.02));
        CameraAccessor access = (CameraAccessor) CAMERA;
        access.kuruma$setPosition(at.x, at.y, at.z);
        access.kuruma$setRotation(yaw, pitch);

        RenderTarget target = slot.target();
        drawingTarget = target;
        target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        // 射影は<b>鏡の横縦比で作る</b>。バニラの getProjectionMatrix は画面の比を使うので、
        // 横長の鏡に貼ると引き伸ばされる
        Matrix4f projection = new Matrix4f().perspective(
                (float) (fov * Mth.DEG_TO_RAD), (float) aspect, 0.05F, far);
        mc.gameRenderer.resetProjectionMatrix(projection);

        // GameRenderer#renderLevel がやっているのと同じ順で組む（カメラの向きの逆回転）
        Matrix4f view = new Matrix4f().rotation(CAMERA.rotation().conjugate(new Quaternionf()));

        mc.gameRenderer.lightTexture().updateLightTexture(partialTick);
        mc.levelRenderer.prepareCullFrustum(CAMERA.getPosition(), view, projection);
        mc.levelRenderer.renderLevel(deltaTracker, false, CAMERA,
                mc.gameRenderer, mc.gameRenderer.lightTexture(), view, projection);

        // <b>不透明度だけを 1 に塗り直す</b>（色はそのまま）。鏡面は position_tex_color で貼るが、このシェーダーは
        // 不透明度が 0.1 未満の画素を捨てる（discard）。Iris が入っていると、シェーダーパックを切っていても
        // 世界を描いた結果の不透明度が 0 になり、鏡面がまるごと捨てられて透けて消えた（Fabric と NeoForge の
        // 両方で確認。描き先を不透明の赤で塗っただけなら映るので、鏡面の描き方そのものは動いている）
        target.bindWrite(false);
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.colorMask(true, true, true, true);
    }

    // ------------------------------------------------------------------
    // 鏡面を描く（車の描画から呼ばれる）
    // ------------------------------------------------------------------

    /**
     * 鏡面を描く。映せるものは映し、そうでなければ暗いガラスにする。
     *
     * <p><b>鏡面は車体のパスから外してあるので、ここが必ず描くこと</b>——描かないと
     * その部分に穴が開く（{@code ObjModel} が読み込み時に車体のパスから外している）。</p>
     */
    public static void renderSurfaces(CarEntity car, ObjModel body, PoseStack pose,
                                      MultiBufferSource buffer, int packedLight,
                                      ResourceLocation texture) {
        List<ObjModel.MirrorFace> faces = body.mirrors();
        if (faces.isEmpty()) {
            return;
        }
        boolean live = !rendering && isLocalDriver(car);

        for (int i = 0; i < faces.size(); i++) {
            ObjModel.MirrorFace face = faces.get(i);
            if (live && i < SLOTS.size() && SLOTS.get(i).name().equals(face.name())) {
                body.renderMirror(pose.last(), buffer.getBuffer(KurumaRenderTypes.mirror(i)),
                        face, 1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                // 反射面だけを暗く塗る。筐体は車体として既に描かれている
                body.renderMirrorPlain(pose.last(),
                        buffer.getBuffer(RenderType.entityCutoutNoCull(texture)),
                        face, packedLight, DEAD_DIM, DEAD_DIM, DEAD_DIM * 1.15F);
            }
        }
    }

    // ------------------------------------------------------------------
    // 下ごしらえ
    // ------------------------------------------------------------------

    /** 鏡の枚数や名前が変わったら描き先を作り直す。大きさは鏡の横縦比から決まる。 */
    private static void syncSlots(List<ObjModel.MirrorFace> faces) {
        int wanted = Math.min(faces.size(), MAX_MIRRORS);
        if (SLOTS.size() == wanted) {
            boolean same = true;
            for (int i = 0; i < wanted && same; i++) {
                same = SLOTS.get(i).name().equals(faces.get(i).name());
            }
            if (same) {
                return;
            }
        }
        release();
        for (int i = 0; i < wanted; i++) {
            ObjModel.MirrorFace face = faces.get(i);
            int width = Mth.clamp((int) Math.round(TARGET_HEIGHT * face.aspect()),
                    MIN_TARGET_WIDTH, MAX_TARGET_WIDTH);
            SLOTS.add(new Slot(face.name(), new TextureTarget(width, TARGET_HEIGHT, true, Minecraft.ON_OSX)));
        }
        LOGGER.info("ミラーの描き先を {} 枚作った", SLOTS.size());
    }

    /** 描き先を捨てる。GL の資源なので、要らなくなったら必ず返すこと。 */
    private static void release() {
        if (SLOTS.isEmpty()) {
            return;
        }
        for (Slot slot : SLOTS) {
            slot.target().destroyBuffers();
        }
        SLOTS.clear();
        next = 0;
    }

    private static CarEntity drivenCar(Minecraft mc) {
        return mc.player != null && mc.player.getVehicle() instanceof CarEntity car
                && car.getControllingPassenger() == mc.player ? car : null;
    }

    private static boolean isLocalDriver(CarEntity car) {
        return drivenCar(Minecraft.getInstance()) == car;
    }

    private static boolean available(Minecraft mc) {
        if (!ClientConfig.liveMirrors) {
            return false;
        }
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return false;
        }
        // ワールドを抜ける途中（保存して終了）は、画面のカメラが追う実体がもう無い。そのまま世界を描くと、
        // 霧の計算（NeoForge の ClientHooks#onFogRender など）が実体を引いて落ちる（Sodium の雲の描画から実際に落ちた）
        if (mc.gameRenderer.getMainCamera().getEntity() == null || mc.getCameraEntity() == null) {
            return false;
        }
        if (Minecraft.useShaderTransparency()) {
            complain("描画設定が「最高」のあいだはミラーに映せない（描き先を持ち替えられるため）。"
                    + "「きれい」以下にすると映る", null);
            return false;
        }
        // Iris / Oculus が入っているだけなら映す。パックを実際に使っている間は、世界の描き直しを
        // 入れ子にすると画面全体が真っ黒になる（Oculus で確認）。パックを切り替えたらその場で効く
        if (HeadlightVolume.ShaderPacks.inUse()) {
            complain("シェーダーパックを使っている間はミラーに映せない。ミラーは暗いガラスになる", null);
            return false;
        }
        return true;
    }

    /** 同じ理由では 1 度しか出さない。毎フレーム出すとログが埋まる。 */
    private static void complain(String message, Throwable cause) {
        if (message.equals(complaint)) {
            return;
        }
        complaint = message;
        if (cause == null) {
            LOGGER.info(message);
        } else {
            LOGGER.warn(message, cause);
        }
    }

    /**
     * その鏡が映す向き（車体基準・単位ベクトル）。エンティティ空間は <b>+Z が後ろ</b>。
     *
     * <p>ルームミラーかドアミラーかは<b>位置で決める</b>。名前で決めると、left / right が
     * 実際の左右と入れ替わっているモデルで外側の向きが逆になる。</p>
     *
     * @param onCar 鏡の位置（車体基準）
     */
    private static boolean isInterior(Vec3 onCar) {
        return Math.abs(onCar.x) < INTERIOR_HALF_WIDTH;
    }

    private static Vec3 aim(Vec3 onCar) {
        boolean interior = isInterior(onCar);
        // 外向きは鏡が付いている側へ。右の鏡（+X）なら +、左なら -。
        // 振る量は「内側の端が車体へ DOOR_BODY_SHARE だけ食い込む」ところ: 中心 = 画角 × (1/2 − 割合)
        double outward = DOOR_FOV * (0.5 - DOOR_BODY_SHARE);
        double yaw = Math.toRadians(interior ? 0.0 : Math.signum(onCar.x) * outward);
        double pitch = Math.toRadians(interior ? REAR_DOWN : DOOR_DOWN);
        double flat = Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * flat, -Math.sin(pitch), Math.cos(yaw) * flat);
    }

    private static Vec3 transform(Matrix4f matrix, Vec3 point) {
        Vector3f v = matrix.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Vec3(v.x, v.y, v.z);
    }

    private static Vec3 rotate(Matrix3f matrix, Vec3 direction) {
        Vector3f v = matrix.transform(
                new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));
        return new Vec3(v.x, v.y, v.z).normalize();
    }
}
