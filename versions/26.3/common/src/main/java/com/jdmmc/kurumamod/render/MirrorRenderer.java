package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.client.hook.FrameRender;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.mixin.CameraAccessor;
import com.jdmmc.kurumamod.mixin.MirrorCameraAccessor;
import com.jdmmc.kurumamod.mixin.MirrorGameRendererAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import com.jdmmc.kurumamod.client.model.CarModel;

/**
 * ルームミラーとドアミラーに、<b>実際に後ろの景色を映す</b>。
 *
 * <p>Minecraft には反射が無いので、映すには<b>世界をもう 1 回描く</b>しかない。鏡のカメラから
 * 世界を鏡用の描き先へ描いて、その絵を鏡面のメッシュに貼る。狙いの決め方（板の傾きは見ず、
 * 運転者が合わせ終えた鏡として車体基準で決める）・画角・1 フレームに 1 枚ずつ回す順番は 1.21.1 と同じ。
 * 理由は 1.21.1 版の javadoc と {@code docs/rendering.md}。</p>
 *
 * <h2>26.3 での描き直し方</h2>
 *
 * <p>26.3 の描画は「1 フレームの頭で状態を集める（extract）→ 集めた状態で描く（render）」の 2 段で、
 * 描く段は集めた状態（{@code LevelRenderState}。カメラ・見えるチャンク・出すエンティティ）しか見ない。
 * そこでバニラのパノラマのスクリーンショット（{@code Minecraft#grabPanoramixScreenshot}）と同じく、
 * <b>画面のカメラを鏡の位置へ置いて、集める段から 1 回ぶん回す</b>:</p>
 *
 * <ol>
 *   <li>{@code GameRenderer#update} の頭（{@code mixin/MirrorGameRendererMixin}）。<b>本編の抽出より前</b>なので、
 *       ここで抽出の状態を鏡のもので上書きしても、直後に本編が自分で抽出し直す。本編の状態を戻す手間が要らない</li>
 *   <li>画面のカメラ（{@code mainCamera}）の位置・向き・射影・視錐台を鏡のものへ差し替える。
 *       {@code Camera#update} は呼ばない（車のカメラの mixin が 2 回走るため）。差し替えたものは描いた後に戻すが、
 *       どうせこの後の本編の {@code update} がすべて決め直す</li>
 *   <li>カメラの抽出（{@code extractCamera}）とワールドの抽出（{@code LevelExtractor#extract}）。
 *       {@code GameRenderer#extract} は GUI の抽出まで走るので丸ごとは呼ばない</li>
 *   <li>{@code GameRenderer#renderLevel}。この間 {@code mainRenderTarget()} が鏡の描き先を返すので
 *       （{@code MirrorGameRendererMixin}）、フレームグラフの描き先も、OIT の描き先の大きさも鏡のものになる。
 *       空だけは作ったときの描き先を持ち続けるので {@code MirrorSkyRendererMixin} で付け替える</li>
 * </ol>
 *
 * <p>パノラマ扱い（{@code Camera#enablePanoramicMode}）にしておくこと。手に持った物を描かない、
 * エンティティの輪郭の枝（画面と同じ大きさの描き先を使う）を通らない、霧の向きを鏡の向きで取る、の 3 つが効く。
 * 手の描画とブロックの枠・画面の効果は {@code renderLevel} の後半が本物の画面の描き先へ描くが、
 * 本編の {@code render} が最初に画面を塗りつぶすので残らない。</p>
 *
 * <p>鏡のカメラは目から 1〜2m 離れているので、見えるチャンクの探索と区画の並びを鏡へ寄せない
 * （{@code MirrorSectionsMixin}・{@code MirrorViewAreaMixin}・{@code MirrorSectionTrackerMixin}）。
 * 寄せると、境を挟んでいる間は本編と鏡で毎フレーム作り直しになる。</p>
 *
 * <h2>効かない場合</h2>
 *
 * <p>次のときは<b>暗いガラスとして描くだけ</b>にして、世界の描き直しはしない:</p>
 * <ul>
 *   <li>設定が切ってある（H のメニュー。既定は<b>切</b>——重いので、要る人が入れる）</li>
 *   <li>シェーダーパックを使っている（Iris。入れ子で世界を描くと画面全体が真っ黒になった。1.21.1 と同じ扱い）</li>
 *   <li>運転していない。<b>映すのは自分が運転している車のミラーだけ</b></li>
 *   <li>描き先が作れない・描いている途中で例外が出た。<b>ログを 1 回出して、その車を降りるまで暗いガラス</b></li>
 * </ul>
 * <p>1.21.1 で止めていた「描画設定が最高（Fabulous）」は 26.3 には無い。代わりの「改良された半透明」（OIT）は
 * 描き先の大きさを {@code mainRenderTarget()} から取るので、そのまま鏡の大きさで描ける。</p>
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
    /** 近くの面 [m]。バニラの世界の描画と同じ。 */
    private static final float NEAR = 0.05F;

    /** 映していないときの鏡面の色。真っ黒にすると穴に見えるので、少しだけ明るくしておく。 */
    private static final float DEAD_DIM = 0.16F;

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

    /** 鏡 1 枚。{@code drawn} は 1 回でも描いたか（描く前の描き先は中身が決まっていないので貼らない）。 */
    private static final class Slot {
        final String name;
        final TextureTarget target;
        boolean drawn;

        Slot(String name, TextureTarget target) {
            this.name = name;
            this.target = target;
        }
    }

    private MirrorRenderer() {
    }

    /**
     * ミラーのために世界を描いている最中か。
     *
     * <p><b>この間はミラーを映してはいけない</b>（いま書き込んでいる描き先を同時に読むことになる）。
     * 世界の描画の段から呼ばれる効果（ヘッドライトの体積・煙の体積など）も、この間は描かないこと
     * （鏡に要らないうえ、画面の大きさを前提にした描き先へ描こうとする）。</p>
     */
    public static boolean isRendering() {
        return rendering;
    }

    /**
     * ミラーのために世界を描いている間の描き先。それ以外のときは null。
     * {@code mixin/MirrorGameRendererMixin} が、この間 {@code GameRenderer#mainRenderTarget()} の答えをこれに差し替える。
     */
    public static RenderTarget drawingTarget() {
        return rendering ? drawingTarget : null;
    }

    /**
     * 空を描く描き先（{@code mixin/MirrorSkyRendererMixin}）。
     *
     * <p>鏡を描いている間は鏡の描き先。それ以外で、空が鏡の描き先を持っている（鏡を描いている最中に空が
     * 作り直された）なら画面の描き先へ戻す。そのままにすると、本編の空が鏡へ描かれ続けて画面から消える。</p>
     */
    public static RenderTarget skyTarget(RenderTarget original) {
        if (rendering && drawingTarget != null) {
            return drawingTarget;
        }
        for (Slot slot : SLOTS) {
            if (slot.target == original) {
                return Minecraft.getInstance().gameRenderer.mainRenderTarget();
            }
        }
        return original;
    }

    /** 1 フレームを描き始める前（{@code ClientHooks} から）。26.3 では {@link #beforeFrame} で描くので何もしない。 */
    public static void onRenderTick(FrameRender event) {
    }

    // ------------------------------------------------------------------
    // 毎フレーム、1 枚だけ描く
    // ------------------------------------------------------------------

    /**
     * 本編の {@code GameRenderer#update} の直前（{@code mixin/MirrorGameRendererMixin}）。鏡を 1 枚だけ描く。
     *
     * <p><b>ここから例外を外へ出さないこと。</b>フレームの描画ごと落ちる。</p>
     */
    public static void beforeFrame(DeltaTracker deltaTracker) {
        if (rendering) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            // パノラマのスクリーンショットの最中（あちらがカメラを持っている）
            if (mc.gameRenderer.mainCamera().isPanoramicMode()) {
                return;
            }
            CarEntity car = drivenCar(mc);
            if (car == null) {
                // 降りたら、失敗して止めていたのを解く（次に乗ったときにもう一度試す）
                broken = false;
                release();
                return;
            }
            if (broken || !enabled()) {
                release();
                return;
            }
            if (!canDrawNow(mc)) {
                // 一時的に描けないだけ（止めている・カメラがまだ決まっていない）。前の絵はそのまま貼っておく
                return;
            }

            CarModel model = CarModel.get(car.getCarId());
            ObjModel body = ObjModel.get(model.bodyModel());
            List<ObjModel.MirrorFace> faces = body.mirrors();
            if (faces.isEmpty()) {
                release();
                return;
            }

            syncSlots(mc, faces);
            if (SLOTS.isEmpty()) {
                return;
            }

            next = (next + 1) % SLOTS.size();
            Slot slot = SLOTS.get(next);
            if (renderFace(mc, car, model, faces.get(next), slot, deltaTracker)) {
                slot.drawn = true;
            }
        } catch (RuntimeException | LinkageError e) {
            complain("ミラーの描画に失敗した。この車を降りるまで暗いガラスとして描く", e);
            // 止めておかないと、次のフレームで描き先を作り直してはまた失敗し続ける
            broken = true;
            try {
                release();
            } catch (RuntimeException ignored) {
                // 捨てるのにも失敗したら、もう何もしない（暗いガラスで描き続ける）
            }
        }
    }

    /** 鏡 1 枚ぶん、その鏡が映す景色を描く。描けたら true。 */
    private static boolean renderFace(Minecraft mc, CarEntity car, CarModel model,
                                      ObjModel.MirrorFace face, Slot slot, DeltaTracker deltaTracker) {
        // 本編の世界の描画が使うのと同じ補間の比
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

        // 鏡の実寸は<b>変換した後で測る</b>（bodyScale もホイールベースの正規化も掛かった後の大きさが要る）
        double halfWidth = transform(matrix, face.center().add(face.right().scale(face.width() * 0.5)))
                .subtract(world).length();
        double halfHeight = transform(matrix, face.center().add(face.up().scale(face.height() * 0.5)))
                .subtract(world).length();
        if (halfWidth < 1.0e-5 || halfHeight < 1.0e-5) {
            return false;
        }

        // 狙いは車体基準で固定。<b>板の角度は使わない</b>（理由は 1.21.1 版の javadoc）
        Vec3 look = rotate(normalMatrix, aim(onCar));

        // Minecraft の向きの決まり: 視線 = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
        float yaw = (float) (Math.atan2(-look.x, look.z) * Mth.RAD_TO_DEG);
        float pitch = (float) (-Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * Mth.RAD_TO_DEG);

        // 画角は固定の横の画角から、鏡の横縦比で縦を出す（射影は縦の画角で作る）
        double aspect = halfWidth / halfHeight;
        double horizontal = Math.toRadians(isInterior(onCar) ? REAR_FOV : DOOR_FOV);
        double fov = Mth.clamp(Math.toDegrees(2.0 * Math.atan(Math.tan(horizontal * 0.5) / aspect)),
                MIN_FOV, MAX_FOV);

        GameRenderer gameRenderer = mc.gameRenderer;
        Camera camera = gameRenderer.mainCamera();
        CameraAccessor place = (CameraAccessor) camera;
        MirrorCameraAccessor parts = (MirrorCameraAccessor) camera;

        // 遠くの面が決まるまで（本編が 1 回カメラを決めるまで）は映さない。0 のまま射影を作ると視錐台が潰れる
        float far = parts.kuruma$depthFar();
        if (!(far > 1.0F)) {
            return false;
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
            return false;
        }

        // 鏡面そのものの中に埋まらないよう、映す向きへわずかに出す
        Vec3 at = center.add(look.scale(0.02));
        TextureTarget target = slot.target;

        // 差し替える前のカメラ。描いた後に戻す（この後の本編の update がどうせ決め直すが、念のため）
        Vec3 savedPosition = camera.position();
        float savedYaw = camera.yRot();
        float savedPitch = camera.xRot();
        boolean savedDetached = camera.isDetached();
        Frustum savedFrustum = parts.kuruma$cullFrustum();
        Projection projection = parts.kuruma$projection();
        float savedNear = projection.zNear();
        float savedFar = projection.zFar();
        float savedFov = projection.fov();
        float savedWidth = projection.width();
        float savedHeight = projection.height();

        rendering = true;
        drawingTarget = target;
        try {
            // setPosition / setRotation は protected なので mixin の口から呼ぶ（リフレクションで名前を引くと、
            // Fabric の配布環境（intermediary 名）で見つからない）
            place.kuruma$setPosition(at.x, at.y, at.z);
            place.kuruma$setRotation(yaw, pitch);
            // 一人称の扱いにしておくと、抽出が「カメラの持ち主（自分）は描かない」と判断する（鏡に自分の後頭部は要らない）
            parts.kuruma$setDetached(false);

            // 視錐台。バニラ（Camera#createProjectionMatrixForCulling）と同じく、深度が逆向きでない普通の射影で作る
            Matrix4f view = camera.getViewRotationMatrix(new Matrix4f());
            Matrix4f cullProjection = new Matrix4f().perspective((float) (fov * Mth.DEG_TO_RAD), (float) aspect,
                    NEAR, far, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
            Frustum frustum = new Frustum(view, cullProjection);
            frustum.prepare(at.x, at.y, at.z);
            parts.kuruma$setCullFrustum(frustum);
            // 描く射影は<b>鏡の大きさで作る</b>（画面の比を使うと、横長の鏡に貼ったとき引き伸ばされる）
            projection.setupPerspective(NEAR, far, (float) fov, target.width, target.height);
            // 向きを決めてから入れること（霧はこの時点の向きを覚えて使う）
            camera.enablePanoramicMode();
            gameRenderer.setRenderBlockOutline(false);

            float worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
            ((MirrorGameRendererAccessor) gameRenderer).kuruma$extractCamera(deltaTracker, worldPartialTicks);
            mc.levelExtractor.extract(deltaTracker, camera, worldPartialTicks);

            // 全体の uniform（地形がカメラの位置と描き先の大きさを読む）を鏡のものにする。
            // 本編の render が頭で書き直すので戻さなくてよい
            GameRenderState state = gameRenderer.gameRenderState();
            OptionsRenderState options = state.optionsRenderState;
            ((MirrorGameRendererAccessor) gameRenderer).kuruma$globalSettingsUniform().update(
                    target.width, target.height, options.glintStrength,
                    state.levelRenderState.gameTime, state.levelRenderState.worldPartialTicks,
                    options.menuBackgroundBlurriness, state.levelRenderState.cameraRenderState.pos,
                    options.textureFiltering == TextureFilteringMethod.RGSS);

            gameRenderer.renderLevel();
            return true;
        } finally {
            camera.disablePanoramicMode();
            gameRenderer.setRenderBlockOutline(true);
            place.kuruma$setPosition(savedPosition.x, savedPosition.y, savedPosition.z);
            place.kuruma$setRotation(savedYaw, savedPitch);
            parts.kuruma$setDetached(savedDetached);
            parts.kuruma$setCullFrustum(savedFrustum);
            projection.setupPerspective(savedNear, savedFar, savedFov, savedWidth, savedHeight);
            rendering = false;
            drawingTarget = null;
        }
    }

    // ------------------------------------------------------------------
    // 鏡面を描く（車の描画から呼ばれる）
    // ------------------------------------------------------------------

    /**
     * 鏡面を描く。映せるものは映し、そうでなければ暗いガラスにする。
     *
     * <p><b>鏡面は車体のパスから外してあるので、ここが必ず描くこと</b>——描かないと
     * その部分に穴が開く（{@code ObjModel} が読み込み時に車体のパスから外している）。
     * 姿勢は車体と同じ変換の中で呼ぶこと（{@code pose} をそのまま使う）。</p>
     */
    public static void renderSurfaces(CarEntity car, ObjModel body, PoseStack pose,
                                      SubmitNodeCollector collector, int packedLight, Identifier texture) {
        List<ObjModel.MirrorFace> faces = body.mirrors();
        if (faces.isEmpty()) {
            return;
        }
        boolean live = !rendering && !broken && isLocalDriver(car);

        List<ObjModel.MirrorFace> dead = new ArrayList<>(faces.size());
        for (int i = 0; i < faces.size(); i++) {
            ObjModel.MirrorFace face = faces.get(i);
            if (live && i < SLOTS.size() && SLOTS.get(i).drawn && SLOTS.get(i).name.equals(face.name())
                    && KurumaRenderTypes.hasMirrorTexture(i)) {
                collector.submitCustomGeometry(pose, KurumaRenderTypes.mirror(i),
                        (p, consumer) -> body.renderMirror(p, consumer, face, 1.0F, 1.0F, 1.0F, 1.0F));
            } else {
                dead.add(face);
            }
        }
        if (dead.isEmpty()) {
            return;
        }
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (p, consumer) -> {
            for (ObjModel.MirrorFace face : dead) {
                // 反射面だけを暗く塗る。筐体は車体として既に描かれている
                body.renderMirrorPlain(p, consumer, face, packedLight, DEAD_DIM, DEAD_DIM, DEAD_DIM * 1.15F);
            }
        });
    }

    // ------------------------------------------------------------------
    // 下ごしらえ
    // ------------------------------------------------------------------

    /** 鏡の枚数や名前が変わったら描き先を作り直す。大きさは鏡の横縦比から決まる。 */
    private static void syncSlots(Minecraft mc, List<ObjModel.MirrorFace> faces) {
        int wanted = Math.min(faces.size(), MAX_MIRRORS);
        if (SLOTS.size() == wanted) {
            boolean same = true;
            for (int i = 0; i < wanted && same; i++) {
                same = SLOTS.get(i).name.equals(faces.get(i).name());
            }
            if (same) {
                return;
            }
        }
        release();
        // 深度の形式は画面の描き先に揃える（ローダーがステンシル付きにしていることがある）
        GpuTexture mainDepth = mc.gameRenderer.mainRenderTarget().getDepthTexture();
        GpuFormat depthFormat = mainDepth != null ? mainDepth.getFormat() : GpuFormat.D32_FLOAT;
        for (int i = 0; i < wanted; i++) {
            ObjModel.MirrorFace face = faces.get(i);
            int width = Mth.clamp((int) Math.round(TARGET_HEIGHT * face.aspect()),
                    MIN_TARGET_WIDTH, MAX_TARGET_WIDTH);
            TextureTarget target = new TextureTarget("KurumaMod mirror " + i, width, TARGET_HEIGHT,
                    GpuFormat.RGBA8_UNORM, depthFormat);
            if (target.getColorTextureView() == null || target.getDepthTextureView() == null) {
                target.destroyBuffers();
                release();
                throw new IllegalStateException("ミラーの描き先を作れなかった（" + width + "x" + TARGET_HEIGHT + "）");
            }
            SLOTS.add(new Slot(face.name(), target));
            KurumaRenderTypes.setMirrorTexture(i, target.getColorTextureView());
        }
        next = 0;
        LOGGER.info("ミラーの描き先を {} 枚作った", SLOTS.size());
    }

    /** 描き先を捨てる。GPU の資源なので、要らなくなったら必ず返すこと。 */
    private static void release() {
        if (SLOTS.isEmpty()) {
            return;
        }
        for (int i = 0; i < SLOTS.size(); i++) {
            // 先にテクスチャの一覧から外す（外す前に閉じると、貼る側が閉じた描き先を引く）
            KurumaRenderTypes.setMirrorTexture(i, null);
            SLOTS.get(i).target.destroyBuffers();
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

    /** 映す設定か（切っているなら描き先も持たない）。 */
    private static boolean enabled() {
        if (!ClientConfig.liveMirrors) {
            return false;
        }
        // パックを実際に使っている間は、世界の描き直しを入れ子にすると画面全体が真っ黒になる（1.21.1 の Oculus で確認）。
        // パックを切り替えたらその場で効く
        if (ShaderPack.inUse()) {
            complain("シェーダーパックを使っている間はミラーに映せない。ミラーは暗いガラスになる", null);
            return false;
        }
        return true;
    }

    /** いまこのフレームで描けるか。描けないのが一時的なら描き先は捨てない。 */
    private static boolean canDrawNow(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return false;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        // ワールドを抜ける途中は、画面のカメラが追う実体がもう無い。そのまま世界を描くと霧の計算などが実体を引いて落ちる
        if (!camera.isInitialized() || camera.entity() == null || mc.getCameraEntity() == null) {
            return false;
        }
        // 視錐台を止めている（デバッグの機能）間は、鏡で差し替えると止めた視錐台が崩れる
        if (camera.getCapturedFrustum() != null) {
            return false;
        }
        // 本編がワールドを描いていない（読み込み中など）。抽出の状態が揃っていない
        return mc.gameRenderer.gameRenderState().shouldRenderLevel;
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
     * ルームミラーかドアミラーかは<b>位置で決める</b>。名前で決めると、left / right が
     * 実際の左右と入れ替わっているモデルで外側の向きが逆になる。
     *
     * @param onCar 鏡の位置（車体基準）
     */
    private static boolean isInterior(Vec3 onCar) {
        return Math.abs(onCar.x) < INTERIOR_HALF_WIDTH;
    }

    /** その鏡が映す向き（車体基準・単位ベクトル）。エンティティ空間は <b>+Z が後ろ</b>。 */
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

    /**
     * シェーダーパックをいま使っているか。Iris の公開 API（{@code net.irisshaders.iris.api.v0.IrisApi}）を
     * あれば聞く。Minecraft の名前ではないので、リフレクションで引いても配布環境で名前が変わらない。
     * Iris が無ければ使っていない。聞き方が分からなければ（API が変わった）使っている扱いで、鏡は暗いまま。
     *
     * <p>ヘッドライトの {@code HeadlightVolume.ShaderPacks} と同じ考え。あちらは作り直し中なので、ここに小さく持つ。</p>
     */
    private static final class ShaderPack {
        private static boolean resolved;
        private static boolean unknown;
        private static Object api;
        private static java.lang.reflect.Method inUse;

        private ShaderPack() {
        }

        static boolean inUse() {
            if (!resolved) {
                resolved = true;
                try {
                    Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = type.getMethod("getInstance").invoke(null);
                    inUse = type.getMethod("isShaderPackInUse");
                } catch (ClassNotFoundException e) {
                    // Iris は入っていない
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOGGER.warn("Iris にシェーダーパックを使っているか聞けない。ミラーは暗いガラスにする", e);
                    unknown = true;
                }
            }
            if (unknown) {
                return true;
            }
            if (inUse == null) {
                return false;
            }
            try {
                return (Boolean) inUse.invoke(api);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("Iris にシェーダーパックを使っているか聞けなかった。以後ミラーは暗いガラスにする", e);
                unknown = true;
                return true;
            }
        }
    }
}
