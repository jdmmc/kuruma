package com.jdmmc.kurumamod.physics;

/**
 * 車同士がぶつかったときの弾かれぶきを解く。<b>Minecraft には依存しない。</b>
 *
 * <p>壁と違って相手も動くので、止めるだけでは足りない。反発係数つきの衝突として
 * 力積を求め、<b>質量の比で分け合う</b>（重い車がぶつかれば軽い車がよく飛ぶ）。</p>
 *
 * <p>どの向きに当たったか（法線）と、相手の重心から接触点までの腕は呼び出し側が渡す。
 * Minecraft では当たり判定の箱から出すが、ここではその出どころを問わない。</p>
 */
public final class CarCollision {

    /**
     * 反発係数。0 で完全に非弾性（くっついて一緒に動く）、1 で跳ね返る。
     *
     * <p>鉄板がひしゃげる衝突なので、実車もほとんど非弾性。少しだけ残してあるのは、
     * 0 にすると当たった 2 台が同じ速度になって<b>貼り付いたまま走る</b>ため。</p>
     */
    public static final double RESTITUTION = 0.2;

    /**
     * ぶつけられた側が受け取る速度差の割合。
     *
     * <p><b>ここは物理ではなく、遊びの都合で運動量の保存を崩している。</b>そのまま渡すと
     * <b>ぶつけるだけでレースを制せてしまう</b>——実測で、後ろから 60cm ずらして当てるだけで
     * 相手の進路が 5.7 度ずれ、100km/h なら 10 秒で 24m 外へ流れる。ぶつけた側は
     * 何も失わないので、いたずらが一方的に得をする。</p>
     *
     * <p>減らすのは<b>受け取る側だけ</b>で、ぶつけた側の損はそのまま。当てても相手が
     * ほとんど動かないので、いたずらをしても得にならない。</p>
     */
    public static final double VICTIM_LINEAR_SHARE = 0.35;

    /**
     * ぶつけられた側が受け取るヨーの割合。<b>速度差より強く削る。</b>
     *
     * <p>レースを壊すのは押されることではなく<b>回されること</b>だから。実測（2200kg が
     * 900kg へ幅寄せ）で、そのまま渡すと 6 秒で 71.8 度回ってスピンするが、0.15 なら
     * 1.4 度に収まる。押し出される量（4.8m）は残るので、当てられたこと自体は分かる。</p>
     */
    public static final double VICTIM_YAW_SHARE = 0.15;

    /**
     * 解いた結果。ワールド座標での速度の変化ぶん。
     *
     * @param aDx       ぶつけた側の速度差の X 成分 [m/s]
     * @param aDz       同 Z 成分
     * @param bDx       ぶつけられた側の速度差の X 成分 [m/s]
     * @param bDz       同 Z 成分
     * @param bYawRate  ぶつけられた側のヨー角速度の変化 [rad/s]
     */
    public record Result(double aDx, double aDz, double bDx, double bDz, double bYawRate) {
    }

    private CarCollision() {
    }

    /**
     * 力積を解く。<b>離れていく向きなら null</b>（すでに弾いた後にもう一度弾かないため）。
     *
     * @param nx          衝突の法線の X 成分（ぶつけた側から相手へ向かう単位ベクトル）
     * @param nz          同 Z 成分
     * @param armX        相手の重心から接触点までの X 成分 [m]
     * @param armZ        同 Z 成分
     * @param aVx         ぶつけた側のワールド速度 [m/s]
     * @param aVz         同上
     * @param bVx         ぶつけられた側のワールド速度 [m/s]
     * @param bVz         同上
     * @param massA       ぶつけた側の車重 [kg]
     * @param massB       ぶつけられた側の車重 [kg]
     * @param yawInertiaB ぶつけられた側のヨー慣性 [kg*m^2]
     */
    public static Result solve(double nx, double nz, double armX, double armZ,
                               double aVx, double aVz, double bVx, double bVz,
                               double massA, double massB, double yawInertiaB) {
        double closing = (aVx - bVx) * nx + (aVz - bVz) * nz;
        if (closing <= 0.0) {
            return null;
        }
        double impulse = -(1.0 + RESTITUTION) * closing / (1.0 / massA + 1.0 / massB);

        double aDx = impulse / massA * nx;
        double aDz = impulse / massA * nz;
        // ぶつけられた側は割り引いて受け取る（上の定数を参照）。ぶつけた側は割り引かない
        double bDx = -impulse / massB * nx * VICTIM_LINEAR_SHARE;
        double bDz = -impulse / massB * nz * VICTIM_LINEAR_SHARE;

        // 接触点が相手の重心から外れていれば、その腕の長さぶんヨーのモーメントになる
        // （側面に当てれば相手は回る）。ヨー角は「前が右へ倒れる向き」が正なので、
        // 世界座標の (x, z) に対する腕と力の外積はこの順序になる
        double force = -impulse;
        double torque = armX * (force * nz) - armZ * (force * nx);
        double bYawRate = yawInertiaB > 0.0 ? torque / yawInertiaB * VICTIM_YAW_SHARE : 0.0;

        return new Result(aDx, aDz, bDx, bDz, bYawRate);
    }
}
