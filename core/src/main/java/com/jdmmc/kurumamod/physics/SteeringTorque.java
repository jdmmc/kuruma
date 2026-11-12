package com.jdmmc.kurumamod.physics;

/**
 * ハンドルに返ってくる力（セルフアライニングトルク）。フォースフィードバックの元になる。
 * <b>物理の結果を読むだけで、物理には何も返さない。</b>
 *
 * <p>前輪の横力が、タイヤの接地面の中心より後ろ（トレール）で働くので、タイヤを進む向きへ戻すねじりが生じる。
 * トレールは 2 つ:</p>
 * <ul>
 *   <li><b>ニューマチックトレール</b> … 接地面の中で横力の重心が後ろへずれるぶん。スリップ角が小さいうちは大きく、
 *       ピークに近づくにつれて 0 へ縮む。<b>限界の手前でハンドルが軽くなるのはこれ</b>で、運転者はそれで
 *       切りすぎに気づく</li>
 *   <li><b>メカニカルトレール</b> … キャスター角で決まる一定のぶん。滑りきっても残るので、後ろが出て前輪が
 *       進む向きへ戻ろうとする力（カウンター側へハンドルが回る力）が消えない</li>
 * </ul>
 *
 * <p>値は無次元の -1〜1 で返す。1 は「前軸の停車時荷重 × μ の横力が、2 つのトレールの和の
 * {@link #REFERENCE_SHARE} 倍の腕で働いた」ときで、車の大小に依らない。既定の車・80km/h で切り増していくと、
 * 限界の手前（前輪のスリップ角 4°）で 0.74、越えると 0.39 とおよそ半分に落ちる（2026-11-13 実測）。
 * <b>正はハンドルを右へ回す向き</b>（{@code CarInput#steerAxis} と同じ）。右へ切って曲がっていれば負（中央へ戻す）、
 * 右旋回で後ろが出れば負のまま（カウンター側へ回す）。</p>
 */
public final class SteeringTorque {

    /** ニューマチックトレール（スリップ角 0 のとき）の、メカニカルトレールに対する比。実車で 2〜3 倍。 */
    private static final double PNEUMATIC_TRAIL = 3.0;
    /** メカニカルトレール（1 とした基準）。 */
    private static final double MECHANICAL_TRAIL = 1.0;
    /**
     * ニューマチックトレールが 0 になるスリップ角の、横力のピーク角に対する比。
     * ピークの少し先で 0 になる（ピークでは横力はまだ増えているので、手応えのピークはその手前に来る）。
     */
    private static final double TRAIL_ZERO_RATIO = 1.3;
    /**
     * 正規化の基準に掛ける比。トレールの最大（スリップ角 0 のときの和）に対して、限界の手前の手応えが
     * 1 に張り付かない（0.7 前後に来る）ところ。張り付くと、限界の手前で増えていく手応えが消える。
     */
    private static final double REFERENCE_SHARE = 0.6;

    private SteeringTorque() {
    }

    /**
     * 今の状態でハンドルに返る力。
     *
     * @return -1〜1。正でハンドルを右へ回す向き
     */
    public static double of(CarSpec spec, CarState state) {
        double peak = spec.tireFriction() / Math.max(spec.effectiveCorneringStiffness(), 1e-6);
        double zero = peak * TRAIL_ZERO_RATIO;
        double torque = 0.0;
        double reference = 0.0;
        for (Wheel wheel : Wheel.VALUES) {
            if (!wheel.isFront()) {
                continue;
            }
            int index = wheel.ordinal();
            double alpha = Math.abs(state.wheelSlipAngle[index]);
            double pneumatic = PNEUMATIC_TRAIL * Math.max(0.0, 1.0 - alpha / zero);
            // 横力はタイヤの右向きが正。後ろのトレールで働く右向きの力は、タイヤを左へ回す（＝負）
            torque -= state.wheelLateralForce[index] * (pneumatic + MECHANICAL_TRAIL);
            reference += spec.staticWheelLoad(wheel) * spec.tireFriction()
                    * (PNEUMATIC_TRAIL + MECHANICAL_TRAIL) * REFERENCE_SHARE;
        }
        if (reference <= 0.0) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, torque / reference));
    }
}
