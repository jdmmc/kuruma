package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.util.Mth;

/**
 * 描画に使う車の姿勢と、見た目のタイヤ角の計算。版の {@code CarEntity} が、手元で解いた値か同期された値を渡して呼ぶ。
 * <b>物理には一切入らない</b>（物理は実際の切れ角で解かれる）。
 *
 * <p>角度の単位: ヨーは度、ピッチ・ロール・切れ角はラジアン（{@code CarEntity} の同期値と同じ）。</p>
 */
public final class CarPose {

    private static final float PI = (float) Math.PI;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** 見た目のタイヤ角を寄せ始める速度 [m/s]。これ以下では進行方向が定まらないので実際の舵のまま。 */
    private static final double VISUAL_SLIP_FADE_START = 2.0;
    /** 見た目のタイヤ角を寄せきる速度 [m/s]。間はなめらかにつなぐ。 */
    private static final double VISUAL_SLIP_FADE_FULL = 4.0;

    private CarPose() {
    }

    /** 角度 [rad] を -π..π へ巻き取る。 */
    public static float wrapRadians(float angle) {
        float wrapped = angle % TWO_PI;
        if (wrapped >= PI) {
            wrapped -= TWO_PI;
        } else if (wrapped < -PI) {
            wrapped += TWO_PI;
        }
        return wrapped;
    }

    /**
     * 姿勢 (yaw, pitch, roll) を別の表し方（ヨー +180 度・ピッチ π−p・ロール +π。回転としては同じ）に
     * 直したほうが、目標の姿勢に数字の上で近いか。
     *
     * <p>届いた姿勢が別の表し方に切り替わっていたら、寄せる前にこちらも揃える。揃えずに寄せると、
     * 同じ向きのはずの車が数ティックかけてでんぐり返る。</p>
     */
    public static boolean isOtherRepresentationCloser(float yaw, float pitch, float roll,
                                                      float targetYaw, float targetPitch, float targetRoll) {
        float same = poseDistance(yaw, pitch, roll, targetYaw, targetPitch, targetRoll);
        float other = poseDistance(yaw + 180.0F, PI - pitch, roll + PI, targetYaw, targetPitch, targetRoll);
        return other < same;
    }

    private static float poseDistance(float yaw, float pitch, float roll,
                                      float targetYaw, float targetPitch, float targetRoll) {
        return Math.abs(Mth.wrapDegrees(targetYaw - yaw)) * DEG_TO_RAD
                + Math.abs(wrapRadians(targetPitch - pitch))
                + Math.abs(wrapRadians(targetRoll - roll));
    }

    /** 別の表し方のピッチ（{@link #isOtherRepresentationCloser}）。 */
    public static float otherPitch(float pitch) {
        return wrapRadians(PI - pitch);
    }

    /** 別の表し方のロール。 */
    public static float otherRoll(float roll) {
        return wrapRadians(roll + PI);
    }

    /**
     * <b>描画専用</b>の前輪の切れ角 [rad]。
     *
     * <p>大きく流すと自動カウンターがフルロックまで当たる。そこから逆へ切ると、車体に対する
     * 角度は最大切れ角の範囲内（35 度）で正しいのに、<b>進行方向に対しては垂直を超える</b>
     * （車体スリップ角 -72 度＋切れ角 +35 度 = 進行方向から 107 度）。タイヤが横を向いて
     * 見えるのはこれが理由。</p>
     *
     * <p>そこで<b>カウンターを当てている角度を基準にして、そこから {@code visualSlipLimit} までに
     * 収める</b>（{@code counterSteer}）。基準は物理の自動カウンターが当てる角度そのものなので、
     * 舵を放している間は実際の舵が基準へ戻っていくだけで、見た目は何も変わらない。カウンター中に
     * 内側へ切れば、カウンターの角度から上限ぶんだけ内側へ切れて見える。</p>
     *
     * @param steer        実際の切れ角 [rad]
     * @param groundSpeed  地面に対する水平の速さ [m/s]（前後と横の合成）
     * @param counterSteer 自動カウンターが当てる角度 [rad]（{@link #counterSteerLocal}・{@link #counterSteerRemote}）
     */
    public static float visualSteerAngle(CarSpec spec, float steer, double groundSpeed, double counterSteer) {
        double limit = spec.visualSlipLimit();
        if (limit <= 0.0) {
            return steer;
        }
        // 止まりかけでは進む向きが定まらないので弱める。しきい値で切り替えると止まる瞬間に
        // タイヤが跳ぶ（実測で 0 → 65 度）。<b>前後の速度ではなく合成の速さで見ること</b>——
        // 深く流れている間は前後の速度が小さく、補正が抜けたり入ったりして 67 度跳んだ
        double fade = Mth.clamp((groundSpeed - VISUAL_SLIP_FADE_START)
                / (VISUAL_SLIP_FADE_FULL - VISUAL_SLIP_FADE_START), 0.0, 1.0);
        fade = fade * fade * (3.0 - 2.0 * fade);
        if (fade <= 0.0) {
            return steer;
        }
        double max = spec.maxSteerAngle();
        double shown = Mth.clamp(Mth.clamp(steer, counterSteer - limit, counterSteer + limit), -max, max);
        return (float) (steer + (shown - steer) * fade);
    }

    /**
     * 自動カウンターが当てる角度 [rad]（手元で物理を解いている車）。物理（{@code CarPhysics#selfAligningAngle} ×
     * 強さ）と同じ式。自動カウンターが 0 の車では、前輪が進む向きそのもの（強さ 1 と同じ）を基準にする。
     *
     * <p>前軸の進む向きは、真横を越えて後ろへ流れても同じ側のまま。裏返すのはバックの段だけ。
     * 分母には物理と同じ下限（{@link CarPhysics#SLIP_REFERENCE_SPEED}）を入れる——入れないと、
     * 真横を向いて前後の速度が 0 を通る瞬間に ±90 度の間で振れる。</p>
     *
     * @param reversing バックの段で後ろへ動いているか
     */
    public static double counterSteerLocal(CarSpec spec, double forward, double lateral, double yawRate,
                                           boolean reversing) {
        double toFront = spec.wheelForwardOffset(Wheel.FRONT_LEFT);
        double angle = Math.atan2(lateral + toFront * yawRate,
                Math.max(Math.abs(forward), CarPhysics.SLIP_REFERENCE_SPEED));
        return counterSteer(spec, angle, reversing);
    }

    /**
     * 同上（他人の車）。他人の車は横速度とヨー角速度を持っていないので、同期しているスリップ角と描画用の
     * ヨー角の差から組み立てる。横速度は {@code |前後速度|·tan(スリップ角)} だが、真横の近くで発散しないよう
     * 両辺に cos を掛けた形で角度だけ出す。
     *
     * @param slip    スリップ角 [rad]
     * @param yawRate 描画用のヨー角の 1 ティックの変化から出した角速度 [rad/s]
     */
    public static double counterSteerRemote(CarSpec spec, double forward, double slip, double yawRate,
                                            boolean reversing) {
        double toFront = spec.wheelForwardOffset(Wheel.FRONT_LEFT);
        double cos = Math.cos(slip);
        double angle = Math.atan2(Math.abs(forward) * Math.sin(slip) + toFront * yawRate * cos,
                Math.max(Math.abs(forward), CarPhysics.SLIP_REFERENCE_SPEED) * cos);
        return counterSteer(spec, angle, reversing);
    }

    private static double counterSteer(CarSpec spec, double angle, boolean reversing) {
        if (reversing) {
            angle = -angle;
        }
        double strength = spec.selfAligning() > 0.0 ? spec.selfAligning() : 1.0;
        double max = spec.maxSteerAngle();
        return Mth.clamp(angle * strength, -max, max);
    }

    /** 同期された前後の速度とスリップ角から、地面に対する水平の速さ [m/s]。 */
    public static double groundSpeedFromSlip(double forwardSpeed, double slipDegrees) {
        double cos = Math.cos(Math.toRadians(slipDegrees));
        return Math.abs(forwardSpeed) / Math.max(cos, 0.05);
    }
}
