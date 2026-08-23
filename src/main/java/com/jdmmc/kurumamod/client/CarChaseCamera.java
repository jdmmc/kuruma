package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Vector3f;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.reflect.Method;

/**
 * 三人称のカメラを<b>車の中心を軸に、決めた距離で</b>置き直す。
 *
 * <p><b>カメラの位置を毎フレーム自分で置く。</b>{@code Camera#setPosition} は protected だが、
 * <b>アクセストランスフォーマではなくリフレクションで開ける</b>（この ForgeGradle は AT を
 * 有効にすると成果物の解決に失敗するが、リフレクションはビルドに何も要求しない）。
 * SRG 名を {@link ObfuscationReflectionHelper} に渡すので、開発環境でも製品環境でも通る。</p>
 *
 * <p><b>これ以前は「乗り手の位置＝カメラの起点」をずらして距離を稼いでいたが、その方式は
 * 原理的に揺れる。</b>乗り手の位置は 20Hz でしか更新できないのに、カメラの角度は毎フレーム
 * 変わるためで、視点を振るたびに起点が遅れて追いかけ、画面がぐにゃぐにゃした。
 * 位置を直接置けば<b>毎フレーム・補間済みの車の位置から計算できる</b>ので、この食い違いが
 * そもそも生じない。</p>
 *
 * <p>{@code ViewportEvent.ComputeCameraAngles} は {@code Camera#setup} の<b>後</b>に飛ぶので、
 * ここで置き直した位置がそのまま描画に使われる。角度はこの後 {@code setAnglesInternal} で
 * 入れ直されるが、位置には触られない。</p>
 *
 * <p>後方視点（F5 を 2 回）では {@code Camera#setup} が向きを 180 度回して渡してくるので、
 * <b>同じ式のまま車の前へ回り込む</b>。分岐は要らない。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarChaseCamera {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code Camera#setPosition(double, double, double)}。見つからなければ諦めてバニラのままにする。 */
    private static final Method SET_POSITION = findSetPosition();

    /** 壁に当たったときに手前で止める余裕 [ブロック]。 */
    private static final double WALL_MARGIN = 0.3;

    /** キー 1 回で動く距離 [ブロック]。 */
    private static final double DISTANCE_STEP = 1.0;
    /** 距離の下限。0 まで詰めると車の内側に入って何も見えなくなる。 */
    private static final double MIN_DISTANCE = 1.5;
    /** 距離の上限。 */
    private static final double MAX_DISTANCE = 24.0;

    // ------------------------------------------------------------------
    // 換装画面のあいだの構え（ショールーム）
    // ------------------------------------------------------------------

    /**
     * 換装画面のあいだ、最低でもこれだけ引く。<b>全長 5m の車が枠に収まる距離。</b>
     *
     * <p>手元の設定がこれより遠ければそのまま。近寄って見たい人を引き戻さない。</p>
     */
    private static final double SHOWROOM_DISTANCE = 8.0;

    /**
     * 換装画面のあいだの<b>回転の中心</b>を、車のフロア（＝車体メッシュの原点＝接地面）から
     * どれだけ上に置くか [m]。
     *
     * <p>普段の中心は「車の位置 ＋ {@code cameraHeight}（既定 1.2m）」だが、換装画面では
     * <b>フロアのあたり</b>へ下げる。回すときに車が中心に居座るのはここが軸だからで、
     * 軸が屋根より上にあると、回すたびに車が画面の中で振り回されて見える。</p>
     *
     * <p>ぴったり接地面ではなく少し上げてあるのは、水平に構えたときにカメラが地面と
     * 同じ高さになるのを避けるため。</p>
     */
    private static final double SHOWROOM_PIVOT_LIFT = 0.30;

    /**
     * 換装画面のあいだ、これだけ余分に下を向く [度]。
     *
     * <p><b>車を上へ逃がす仕事の大半は、回転の中心を下げたことが担っている</b>
     * （{@link #SHOWROOM_PIVOT_LIFT}）。中心は必ず画面の中央に来るので、それをフロアへ
     * 下げた時点で車体はまるごと上半分へ移る。ここはその残りの詰めで、<b>大きくすると
     * 今度は上へ行きすぎて画面の外へ出る</b>。</p>
     *
     * <p>車を上へ逃がすのは、パーツのタイルが画面の下端を占めているため。
     * 下に置いた以上、車が下半分にいると自分で自分を隠すことになる。</p>
     */
    private static final float SHOWROOM_PITCH = 3.0F;

    /** 構えの切り替わりの速さ。1 ティックでこの割合だけ寄る。 */
    private static final float SHOWROOM_BLEND_PER_TICK = 0.30F;

    /** ドラッグ 1 画素あたり何度回すか。 */
    private static final float ORBIT_PER_PIXEL = 0.45F;

    /**
     * 見上げ／見下ろしの範囲 [度]。
     *
     * <p><b>下限はカメラが地面へ潜らないための蓋。</b>カメラは回転の中心から視線の逆へ置くので、
     * 見上げる（負の角度）ほど下がる。中心をフロアまで下げてある以上、少し見上げただけで
     * 地面を割るので、<b>角度の蓋だけでは足りない</b>——最後に位置の高さそのものも
     * 押し戻している（{@link #SHOWROOM_FLOOR_MARGIN}）。</p>
     */
    private static final float ORBIT_PITCH_MIN = -5.0F;
    private static final float ORBIT_PITCH_MAX = 50.0F;

    /**
     * 換装画面のあいだ、カメラをフロアからこれ以上は下げない [m]。
     *
     * <p>角度の蓋（{@link #ORBIT_PITCH_MIN}）だけに頼れないのは、<b>基準になる視線の角度が
     * プレイヤー側の向き次第</b>だから。最後に高さを押し戻せば、どんな向きから開いても潜らない。</p>
     */
    private static final double SHOWROOM_FLOOR_MARGIN = 0.20;

    /** ホイール 1 段あたりの寄り引き [ブロック] と、その範囲。 */
    private static final double ZOOM_STEP = 0.8;
    private static final double ZOOM_MIN = -4.0;
    private static final double ZOOM_MAX = 12.0;

    /**
     * 換装画面で回したぶん。<b>車を基準にした相対角なので、車が向きを変えても付いてくる。</b>
     *
     * <p>閉じても捨てない——{@link #showroom} が 0 へ寄るあいだに掛かる係数が
     * 0 になるので勝手に消え、開き直せば<b>さっき見ていた角度から始まる</b>。</p>
     */
    private static float orbitYaw;
    private static float orbitPitch;
    private static double orbitZoom;

    /**
     * いまどれだけショールームの構えに寄っているか（0..1）。
     *
     * <p><b>いきなり切り替えない。</b>画面を開いた瞬間にカメラが飛ぶと、何が起きたのか
     * 分からないうえ酔う。</p>
     */
    private static float showroom;

    private CarChaseCamera() {
    }

    private static Method findSetPosition() {
        try {
            return ObfuscationReflectionHelper.findMethod(Camera.class, "m_90584_",
                    double.class, double.class, double.class);
        } catch (RuntimeException e) {
            LOGGER.warn("カメラの位置を置けない（Camera#setPosition が見つからない）。"
                    + "三人称はバニラの 4 ブロック固定になる", e);
            return null;
        }
    }

    /**
     * カメラの距離を刻む。負で近づき、正で遠ざかる。
     *
     * <p>設定ファイルへそのまま書いて保存する。<b>走りながら何度も触る値</b>なので、
     * 設定画面を開かせるより手元で刻めた方がよい。今いくつなのかは画面下に出す
     * （数字が見えないと、効いているのか分からない）。</p>
     */
    public static void stepDistance(int steps) {
        double distance = Mth.clamp(ClientConfig.cameraDistance + steps * DISTANCE_STEP,
                MIN_DISTANCE, MAX_DISTANCE);
        ClientConfig.cameraDistance = distance;
        ClientConfig.CAMERA_DISTANCE.set(distance);
        ClientConfig.SPEC.save();

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable("hud.kurumamod.camera_distance",
                    String.format("%.1f", distance)), true);
        }
    }

    /**
     * 車をいじる画面（換装・セッティング）で車のまわりを回す。ドラッグ 1 回ぶん。
     *
     * <p><b>プレイヤーの向きは触らない。</b>触ると {@code CarCamera} が「自分でマウスを
     * 動かした」と誤解して車への追従を止めてしまう（衝突の揺れと同じ理由）。</p>
     */
    public static void orbit(double dragX, double dragY) {
        orbitYaw += (float) dragX * ORBIT_PER_PIXEL;
        orbitPitch = Mth.clamp(orbitPitch + (float) dragY * ORBIT_PER_PIXEL,
                ORBIT_PITCH_MIN, ORBIT_PITCH_MAX);
    }

    /** 車をいじる画面での寄り引き。ホイール 1 段ぶん。 */
    public static void zoom(double delta) {
        orbitZoom = Mth.clamp(orbitZoom - delta * ZOOM_STEP, ZOOM_MIN, ZOOM_MAX);
    }

    /**
     * 三人称のあいだ、カメラを車の中心の後ろへ置く。
     *
     * <p>優先度を下げてあるのは、角度を触る他のハンドラ（{@link CarImpact} の揺れなど）と
     * 順番を争わないため。位置と角度は独立なので実際には衝突しないが、
     * 「最後に位置を決める」ことをはっきりさせておく。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (SET_POSITION == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.getCameraType().isFirstPerson()
                || minecraft.getCameraEntity() != player
                || !(player.getVehicle() instanceof CarEntity car)) {
            return;
        }

        Camera camera = event.getCamera();
        float partial = (float) event.getPartialTick();
        // 換装画面のあいだは引いて、回転の中心をフロアまで下げる
        float lift = updateShowroom(minecraft);

        // 車の中心。位置は毎フレーム補間して取り直す（ここが 20Hz に縛られない理由）。
        //
        // エンティティの Y はシャシー基準面なので、車体メッシュはそこから
        // designRideHeight だけ下——つまりフロアはその高さにある。描画と同じ値を使うので、
        // 車高を変えても中心はフロアに乗ったままになる
        double bodyY = Mth.lerp(partial, car.yo, car.getY());
        double floorY = bodyY - CarModel.get(car.getCarId()).designRideHeight();
        Vec3 pivot = new Vec3(
                Mth.lerp(partial, car.xo, car.getX()),
                Mth.lerp(lift, bodyY + ClientConfig.cameraHeight, floorY + SHOWROOM_PIVOT_LIFT),
                Mth.lerp(partial, car.zo, car.getZ()));

        double speedRatio = Math.min(1.0, Math.abs(car.getRenderSpeed()) / car.getSpec().maxSpeed());
        double distance = ClientConfig.cameraDistance + ClientConfig.cameraSpeedPull * speedRatio;
        distance = Mth.lerp(lift, distance,
                Math.max(1.0, Math.max(distance, SHOWROOM_DISTANCE) + orbitZoom));

        // 視線の逆方向。カメラは既に setup 済みなので、向きはここから取れば毎フレーム正確。
        //
        // 回したぶんはここに足す（＝カメラが車のまわりを回る）。一方
        // SHOWROOM_PITCH は<b>足さない</b>——あれは視線だけを下げて車を画面の上へ
        // ずらすためのもので、位置にまで効かせると車が中心へ戻ってしまう
        float orbitYawApplied = orbitYaw * lift;
        float orbitPitchApplied = orbitPitch * lift;
        Vec3 back;
        if (lift > 0.0F) {
            back = Vec3.directionFromRotation(camera.getXRot() + orbitPitchApplied,
                    camera.getYRot() + orbitYawApplied).scale(-1.0);
        } else {
            Vector3f look = camera.getLookVector();
            back = new Vec3(-look.x(), -look.y(), -look.z());
        }
        distance = clipDistance(car, pivot, back, distance);

        Vec3 position = pivot.add(back.scale(distance));
        if (lift > 0.0F) {
            // 中心をフロアへ下げたぶん、少し見上げただけで地面を割る。最後に押し戻す。
            // 狙いの点からわずかにずれるが、土に埋まるよりはよい
            double lowest = Mth.lerp(lift, position.y, floorY + SHOWROOM_FLOOR_MARGIN);
            position = new Vec3(position.x, Math.max(position.y, lowest), position.z);
        }
        try {
            SET_POSITION.invoke(camera, position.x, position.y, position.z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("カメラの位置を置けなかった", e);
        }

        // 位置を決めた後で視線を合わせる。回したぶんは位置と同じだけ回し、そのうえで
        // SHOWROOM_PITCH だけ余分に下を向く——位置は「車が中心に来る」向きのままなので、
        // 下げたぶんだけ車が上へずれる。どちらも<b>足すだけ</b>にしてあるので、
        // 衝突の揺れ（CarImpact）を打ち消さない
        if (lift > 0.0F) {
            event.setYaw(event.getYaw() + orbitYawApplied);
            event.setPitch(event.getPitch() + orbitPitchApplied + SHOWROOM_PITCH * lift);
        }
    }

    /**
     * ショールームの構えへの寄り具合を進める。
     *
     * <p>フレーム時間で刻むのは、カメラの見た目だけの話で決定性が要らないため
     * （物理は固定刻みのまま）。</p>
     */
    private static float updateShowroom(Minecraft minecraft) {
        float target = ScreenStyle.isCarScreen() ? 1.0F : 0.0F;
        float rate = Mth.clamp(minecraft.getDeltaFrameTime() * SHOWROOM_BLEND_PER_TICK, 0.0F, 1.0F);
        showroom += (target - showroom) * rate;
        if (Math.abs(target - showroom) < 0.002F) {
            showroom = target;
        }
        return showroom;
    }

    /**
     * 壁に埋まらない距離まで縮める。
     *
     * <p>バニラの {@code getMaxZoom} と同じ考え方だが、こちらは<b>自分で置く位置</b>に対して
     * 掛ける必要がある（バニラの判定は 4 ブロックぶんにしか効かない）。</p>
     */
    private static double clipDistance(CarEntity car, Vec3 pivot, Vec3 back, double distance) {
        double shortest = distance;
        // 中心 1 本だと角がすり抜けるので、上下左右に少し振った 4 本も見る
        for (int i = 0; i < 5; i++) {
            double sideways = i == 0 ? 0.0 : ((i & 1) == 0 ? 0.1 : -0.1);
            double vertical = i == 0 ? 0.0 : (i <= 2 ? 0.1 : -0.1);
            Vec3 from = pivot.add(sideways, vertical, 0.0);
            BlockHitResult hit = car.level().clip(new ClipContext(from, from.add(back.scale(distance)),
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, car));
            if (hit.getType() != HitResult.Type.MISS) {
                shortest = Math.min(shortest, hit.getLocation().distanceTo(from) - WALL_MARGIN);
            }
        }
        return Math.max(0.0, shortest);
    }
}
