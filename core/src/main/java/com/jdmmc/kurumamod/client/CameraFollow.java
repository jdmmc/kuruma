package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.CameraMode;
import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.util.Mth;

/**
 * 視線を車へ追従させる計算と、速度で視野を広げる計算。<b>プレイヤーの向きを読んで書くのは版の {@code CarCamera}</b>で、
 * ここはどちらへどれだけ向けるかを決めるところだけ。
 *
 * <p>追従先は車の向きそのものではなく、<b>向きと進行方向の間</b>。こうするとドリフト中に
 * カメラが進行方向側へ振られ、横を向いた車体が見える。</p>
 *
 * <p>マウスを動かしている間は自動追従を止める。勝手に引き戻されると周りを見られないため。</p>
 */
public final class CameraFollow {

    /** 1 ティックで詰める角度の割合。小さいほど緩やかに追従する。 */
    private static final float FOLLOW_RATE = 0.18F;
    /** 三人称で、進行方向をどれだけ向くか。1 で完全に進行方向、0 で車の向き（一人称は CockpitLook が決める）。 */
    private static final float DRIFT_LOOK = 0.6F;
    /** 見下ろす角度 [度]。 */
    private static final float LOOK_DOWN = 8.0F;
    /** マウス操作の後、これだけのティック数は自動追従を止める。 */
    private static final int MANUAL_LOOK_TICKS = 30;
    /** 視線が既に合っているとみなす角度差 [度]。ここを下回れば動かさない。 */
    private static final float SETTLED_DEGREES = 0.05F;

    /** 一人称の視線を車体からどれだけ振るか（{@link CockpitLook}）。見回している間も毎ティック進める。 */
    private static final CockpitLook COCKPIT_LOOK = new CockpitLook();
    /** {@link #COCKPIT_LOOK} がどの車を、どのティックまで見てきたか。つながらなければ測り直す。 */
    private static int lookCarId = -1;
    private static int lookCarTick;

    /**
     * 広がりを速度域のどこへ寄せるか。1 で速度に比例、0.5（平方根）で低中速側へ寄る。
     *
     * <p>比例にすると<b>変化のほとんどが滅多に出さない最高速側に溜まる</b>。
     * 実際に走る 80〜150km/h では数度しか広がらず、効いているのか分からない。
     * 平方根にすると 100km/h の時点で広がりの 6 割が出る。</p>
     */
    private static final double FOV_CURVE = 0.5;

    /** 前回こちらが設定した視線。これと違えばプレイヤーがマウスを動かしたと判断する。 */
    private static float appliedYaw;
    private static float appliedPitch;
    private static boolean hasApplied;
    private static int manualLookCooldown;

    private CameraFollow() {
    }

    /** 追従を最初からやり直す（降りた・モードを変えた・車の向きへ揃えた）。 */
    public static void reset() {
        hasApplied = false;
        manualLookCooldown = 0;
    }

    /**
     * いまのカメラモードを次へ送る。設定ファイルへもそのまま書く。static フィールドも一緒に書くのは、
     * ファイル監視の {@code ModConfigEvent} を待つと反映が遅れるため。
     *
     * @return 新しいモード
     */
    public static CameraMode cycle(CameraMode current) {
        CameraMode mode = current.next();
        if (mode.isFirstPerson()) {
            ClientConfig.firstPersonCamera = mode;
            ClientConfig.FIRST_PERSON_CAMERA.set(mode);
        } else {
            ClientConfig.thirdPersonCamera = mode;
            ClientConfig.THIRD_PERSON_CAMERA.set(mode);
        }
        ClientConfig.SPEC.save();
        reset();
        return mode;
    }

    /**
     * 一人称の視線のずれ [度] を 1 ティック進める。乗り換えたときと、ティックが飛んだとき
     * （止めていた・読み込み直し）は測り直す。前のティックとの差から回転率を出すので、飛んだ差を
     * 1 ティックの回転として読ませないため。
     */
    public static double cockpitOffset(int carId, int carTick, float carYaw, double slipDegrees, double speed) {
        if (carId != lookCarId || carTick - lookCarTick != 1) {
            COCKPIT_LOOK.reset();
        }
        lookCarId = carId;
        lookCarTick = carTick;
        // H メニューのスライダー。走りながら動かして決めるものなので、毎ティック読む
        COCKPIT_LOOK.setLookAhead(ClientConfig.cockpitLookAhead);
        COCKPIT_LOOK.setSlipGain(ClientConfig.cockpitDriftLook);
        return COCKPIT_LOOK.tick(carYaw, slipDegrees, speed);
    }

    /**
     * 視線を 1 ティック追従させる。
     *
     * @param cockpitOffset 一人称の視線のずれ [度]（{@link #cockpitOffset}）
     * @return 新しい {yaw, pitch}。プレイヤーが自分で見回している間は null（向きを触らない）
     */
    public static float[] follow(CameraMode mode, float carYaw, double slipDegrees, double cockpitOffset,
                                 float playerYaw, float playerPitch) {
        // 前回こちらが入れた値から動いていれば、プレイヤーが自分で見回している
        if (hasApplied
                && (Math.abs(Mth.wrapDegrees(playerYaw - appliedYaw)) > SETTLED_DEGREES
                || Math.abs(playerPitch - appliedPitch) > SETTLED_DEGREES)) {
            manualLookCooldown = MANUAL_LOOK_TICKS;
        }
        if (manualLookCooldown > 0) {
            manualLookCooldown--;
            hasApplied = false;
            return null;
        }

        // 三人称は車の向きと進行方向の間を向く。ドリフト中は進行方向側へ振られて車体の横が見える。
        // 一人称は CockpitLook の目標（ドリフトは同じ 0.6 倍、グリップでは曲がる側を先読み）。
        // どちらも<b>ワールドに対して追いかける</b>。車の急な回転に直接付いていくと、ドリフトの振り出しで
        // 画面がぐいぐい回った（いったん直接合わせる形にして戻した）
        float targetYaw = carYaw + (mode.isFirstPerson()
                ? (float) cockpitOffset : (float) slipDegrees * DRIFT_LOOK);
        float yaw = playerYaw + Mth.wrapDegrees(targetYaw - playerYaw) * FOLLOW_RATE;
        float pitch = playerPitch + (LOOK_DOWN - playerPitch) * FOLLOW_RATE;
        appliedYaw = yaw;
        appliedPitch = pitch;
        hasApplied = true;
        return new float[]{yaw, pitch};
    }

    /**
     * 車速に応じて視野を広げる倍率。速度計を見なくても速さが分かるようにするため。広げないなら 1。
     *
     * <p>割合の分母は {@link CarSpec#topGearSpeed()}（最高段でレブまで回した速度）。
     * 以前は暴走止めの上限（既定 300km/h）で割っていたので、実際に出る 255km/h でも
     * 0.85 止まりで、上限を上げるほど広がりが痩せていた。</p>
     *
     * <p><b>広げる量は倍率ではなく度で足す。</b>倍率で掛けると視野を 100 度にしている人には
     * 同じ 1.36 倍が 36 度ぶんの広がりとして出てしまう。プレイヤーが設定している視野角を
     * 読んで、そこへ一定の度数を足した比を返す。</p>
     *
     * @param baseFov プレイヤーが設定している視野角 [度]
     */
    public static double fovFactor(CarSpec spec, double speed, double baseFov) {
        if (ClientConfig.speedFov <= 0.0) {
            return 1.0;
        }
        double reference = spec.topGearSpeed();
        if (reference <= 0.0) {
            return 1.0;
        }
        double ratio = Math.min(1.0, Math.abs(speed) / reference);
        return (baseFov + ClientConfig.speedFov * Math.pow(ratio, FOV_CURVE)) / baseFov;
    }
}
