package com.jdmmc.kurumamod.client;

/**
 * 一人称の視線を、車体の向きからどれだけ振るか（度）。Minecraft に依存しない（両方の版で共有し、
 * ゲーム抜きで時系列を出して検算できる）。<b>毎ティック 1 回 {@link #tick} を呼ぶ。</b>
 *
 * <p><b>返すのは「目標」で、呼ぶ側はワールドに対して均してから視線に入れること</b>（{@code CarCamera} が
 * 1 ティック 18% ＝時定数 0.25 秒で追う）。車の急な回転にそのまま付いていくと、ドリフトの振り出しで
 * 画面がぐいぐい回る（物理コアで測ると、回転速度の変化が車体に固定したときの約 3 分の 1 になる）。</p>
 *
 * <ul>
 *   <li><b>スリップ項</b> {@code 0.6 × 40°·tanh(β/40°)} … 進行方向の側を見る。もとからあった一人称と同じ 0.6 倍で、
 *       ドリフトの見え方は好評だったのでそのまま</li>
 *   <li><b>先読み項</b> {@code 25°·tanh(K·ω/25°)} … ω は<b>進路の向き（車の向き＋スリップ角）の回転率</b>を
 *       時定数 {@link #DEFAULT_OMEGA_TAU} で均したもの。K=0.8 秒のうち半分ほどは、ワールドで均すことによる遅れを
 *       打ち消すのに使われる（43km/h・ヨー 44°/s の定常円で 13 度曲がる側を見る。先読みが無いと −8.6 度で外を見る）</li>
 *   <li><b>先読みはグリップの間だけ</b>（スリップ角 3〜10 度で消す）。ドリフトの入りでは、まず先読みが内へ押し、
 *       スリップ角が育つとスリップ項が外へ引き戻す——この往復が「ハンドルの切り始めで画面がぐいぐい動く」の正体だった。
 *       消すときはすぐ、戻すときは {@link #GATE_RELEASE_TAU} でゆっくり（すぐ戻すと抜けで跳ねる）</li>
 * </ul>
 *
 * <p><b>舵やヨーレートで動かしてはいけない</b>（一度入れて捨てた）。ヨーレートは舵にすぐ反応するので、
 * 舵を入れた瞬間に視線が左右へ振られる（72km/h・1Hz の修正舵で 21 度）。カウンターステアや車体の振れは、
 * スリップ項にも先読み項にも直接は入らない。</p>
 *
 * <p>計算はすべてティック（{@link #DT} 固定）で回す。描く側はティックの間を補間するだけでよく、
 * フレームの速さに左右されない。</p>
 */
public final class CockpitLook {

    /** 1 ティック [s]。 */
    public static final double DT = 0.05;

    /**
     * 先読みの時間 [s]。<b>既定は 0（先読みしない）</b>——乗って比べた結果。H メニューで 1.6 秒まで上げられる。
     * 0.8 にすると、ワールドで均す遅れ（約 0.25 秒ぶん）を差し引いて実際に見る先は 0.4 秒ぶんで、
     * 43km/h・ヨー 44°/s の定常円で 13 度曲がる側を見る（0 では均す遅れのぶん外を見る）
     */
    public static final double DEFAULT_LOOK_AHEAD = 0.0;
    /**
     * 進路の回転率を均す時定数 [s]。1〜2Hz の修正舵を 1/3〜1/6 に落とし、コーナー単位（1〜5 秒）の
     * 変化は通す。オープンソースの TORCS のカメラ（0.4 秒）より少し重め
     */
    public static final double DEFAULT_OMEGA_TAU = 0.45;

    /** スリップ角を均す時定数 [s]。ティックの段差とノイズを取るだけの軽いもの */
    private static final double SLIP_TAU = 0.10;
    /** 直進中の修正舵を消す不感帯 [deg/s]。段差は付けない（越えたぶんだけ通す） */
    private static final double DEAD_ZONE = 2.0;
    /** 進路の回転率の上限 [deg/s]。段差や着地で向きが跳んでも先読みが跳ねないように */
    private static final double MAX_OMEGA = 150.0;
    /** 先読み項の柔らかい上限 [度] */
    private static final double LOOK_AHEAD_SOFT_MAX = 25.0;
    /** スリップ項の柔らかい上限 [度] */
    private static final double SLIP_SOFT_MAX = 40.0;
    /** 全体の上限 [度] */
    private static final double MAX_OFFSET = 60.0;
    /** 1 ティックに動かせる量 [度]（120°/s）。切り返しやスピンで視線が跳ばないように */
    private static final double MAX_STEP = 6.0;

    /** スリップ項を効かせはじめる／効かせきる速さ [m/s]。止まりかけは進路が定まらない */
    private static final double SLIP_MIN_SPEED = 2.0;
    private static final double SLIP_FULL_SPEED = 5.0;
    /** 先読み項を効かせはじめる／効かせきる速さ [m/s]。低速の回転率はノイズが多いので遅らせる */
    private static final double PATH_MIN_SPEED = 3.0;
    private static final double PATH_FULL_SPEED = 8.0;
    /** 前進か後退かを決める速さ [m/s]（この間は前の判定を保つ）。後退では車の正面を見る */
    private static final double DIRECTION_HYSTERESIS = 0.5;
    /** 前進と後退を切り替えるのにかける時間 [s] */
    private static final double DIRECTION_BLEND = 0.25;

    /** スピンと見なすスリップ角 [度]。進路を見続けると真横から真後ろを向くので、車の正面へ戻す */
    private static final double SPIN_ENTER = 75.0;
    /** スピンから戻ったと見なすスリップ角 [度]（ヒステリシス） */
    private static final double SPIN_EXIT = 55.0;
    /** ドリフトを抜けたあと、先読みを戻すのにかける時定数 [s]。すぐ戻すと抜けで視線が曲がる側へ跳ねる */
    private static final double GATE_RELEASE_TAU = 0.5;
    private final double gateRelease = rate(GATE_RELEASE_TAU);

    /** スピンで正面へ戻す時定数 [s] */
    private static final double SPIN_TAU = 0.3;

    private double lookAhead;
    private final double omegaRate;
    private final double pathRate;
    private final double slipRate;
    private double slipGain;
    private final double gateStart;
    private final double gateEnd;
    private final double spinRate = rate(SPIN_TAU);

    private boolean started;
    private double lastCourse;
    private double omega;
    private double path;
    private double slip;
    private double forward = 1.0;
    private boolean forwardTarget = true;
    private double spinWeight = 1.0;
    private boolean spinning;
    private double offset;
    /** 先読みを消すかを決めるスリップ角 [度]。上がるときはすぐ、下がるときは {@link #GATE_RELEASE_TAU} で追う */
    private double gateSlip;

    /** 先読みをもう一段均す時定数 [s]。立ち上がりを S 字にして、切り始めにぐいっと来ないように */
    public static final double DEFAULT_PATH_TAU = 0.5;
    /** スリップ項の倍率。乗って比べて 0.7（もとの一人称は 0.6） */
    public static final double DEFAULT_SLIP_GAIN = 0.7;
    /** 先読みを消しはじめる／消しきるスリップ角 [度]。グリップはおよそ 3 度まで */
    public static final double DEFAULT_GATE_START = 3.0;
    public static final double DEFAULT_GATE_END = 10.0;

    public CockpitLook() {
        this(DEFAULT_LOOK_AHEAD, DEFAULT_OMEGA_TAU, DEFAULT_PATH_TAU, SLIP_TAU,
                DEFAULT_SLIP_GAIN, DEFAULT_GATE_START, DEFAULT_GATE_END);
    }

    /** 係数を振って比べるとき用。 */
    public CockpitLook(double lookAhead, double omegaTau) {
        this(lookAhead, omegaTau, 0.0, SLIP_TAU);
    }

    /** 係数を振って比べるとき用。時定数 0 はその段を均さない。 */
    public CockpitLook(double lookAhead, double omegaTau, double pathTau, double slipTau) {
        this(lookAhead, omegaTau, pathTau, slipTau, 1.0, Double.NaN, Double.NaN);
    }

    /** 係数を振って比べるとき用。{@code gateStart} が NaN なら先読みをスリップ角で消さない。 */
    public CockpitLook(double lookAhead, double omegaTau, double pathTau, double slipTau,
                       double slipGain, double gateStart, double gateEnd) {
        this.lookAhead = lookAhead;
        this.omegaRate = rate(omegaTau);
        this.pathRate = rate(pathTau);
        this.slipRate = rate(slipTau);
        this.slipGain = slipGain;
        this.gateStart = gateStart;
        this.gateEnd = gateEnd;
    }

    /** 先読みの時間 [s] を変える（設定画面のスライダー）。0 で先読みしない。 */
    public void setLookAhead(double lookAhead) {
        this.lookAhead = lookAhead;
    }

    /** スリップ項の倍率を変える（設定画面のスライダー）。0 でドリフト中も車の正面を見る、1 で進行方向そのもの。 */
    public void setSlipGain(double slipGain) {
        this.slipGain = slipGain;
    }

    /** 時定数 τ を 1 ティックで詰める割合にする。 */
    private static double rate(double tau) {
        return tau <= 0.0 ? 1.0 : 1.0 - Math.exp(-DT / tau);
    }

    /** 乗り直したときなど、前のティックとつながらないときに呼ぶ。 */
    public void reset() {
        started = false;
        omega = 0.0;
        path = 0.0;
        slip = 0.0;
        forward = 1.0;
        forwardTarget = true;
        spinWeight = 1.0;
        spinning = false;
        offset = 0.0;
        gateSlip = 0.0;
    }

    /**
     * 1 ティック進める。
     *
     * @param yaw   車体の向き [度]。正で右へ回る向き
     * @param slip  スリップ角 [度]（進路 − 車体）。正で右
     * @param speed 車体前方向の速さ [m/s]。負なら後退
     * @return 車体の向きに足す視線の角度 [度]。正で右
     */
    public double tick(double yaw, double slip, double speed) {
        double course = yaw + slip;
        double rawOmega = started ? wrap(course - lastCourse) / DT : 0.0;
        lastCourse = course;
        started = true;
        rawOmega = clamp(rawOmega, -MAX_OMEGA, MAX_OMEGA);

        omega += omegaRate * (rawOmega - omega);
        this.slip += slipRate * (slip - this.slip);

        double active = Math.signum(omega) * Math.max(0.0, Math.abs(omega) - DEAD_ZONE);
        gateSlip = Math.abs(slip) > gateSlip ? Math.abs(slip) : gateSlip + gateRelease * (Math.abs(slip) - gateSlip);
        double gate = Double.isNaN(gateStart) ? 1.0 : 1.0 - smoothstep(gateStart, gateEnd, gateSlip);
        path += pathRate * (gate * LOOK_AHEAD_SOFT_MAX * Math.tanh(lookAhead * active / LOOK_AHEAD_SOFT_MAX) - path);
        double drift = slipGain * SLIP_SOFT_MAX * Math.tanh(this.slip / SLIP_SOFT_MAX);

        // 前進・後退。止まりかけで判定が行き来しないよう、はっきり動いたときだけ切り替える
        if (speed > DIRECTION_HYSTERESIS) {
            forwardTarget = true;
        } else if (speed < -DIRECTION_HYSTERESIS) {
            forwardTarget = false;
        }
        double blend = DT / DIRECTION_BLEND;
        forward = clamp(forward + clamp((forwardTarget ? 1.0 : 0.0) - forward, -blend, blend), 0.0, 1.0);

        if (Math.abs(this.slip) > SPIN_ENTER) {
            spinning = true;
        } else if (Math.abs(this.slip) < SPIN_EXIT) {
            spinning = false;
        }
        spinWeight += spinRate * ((spinning ? 0.0 : 1.0) - spinWeight);

        double v = Math.abs(speed);
        double target = spinWeight * forward * (smoothstep(SLIP_MIN_SPEED, SLIP_FULL_SPEED, v) * drift
                + smoothstep(PATH_MIN_SPEED, PATH_FULL_SPEED, v) * path);
        target = clamp(target, -MAX_OFFSET, MAX_OFFSET);
        offset += clamp(target - offset, -MAX_STEP, MAX_STEP);
        return offset;
    }

    /** いまの視線の角度 [度]（最後の {@link #tick} の値）。 */
    public double offset() {
        return offset;
    }

    private static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
