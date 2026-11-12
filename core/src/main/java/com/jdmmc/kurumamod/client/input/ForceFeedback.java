package com.jdmmc.kurumamod.client.input;

/**
 * フォースフィードバック（ハンコンへ返す力）を決めるところ。<b>力を出すのは版の {@link Output}</b>
 * （26.3 は SDL の haptic。1.20.1・1.21.1 は出す口が無いので置かない）。
 *
 * <p>元になる力は物理の {@code SteeringTorque}（セルフアライニングトルク）で、毎ティック {@link #tick} で受け取る。
 * 出すのは毎フレーム（{@link #frame}）。物理は 1 秒に 20 回しか進まないので、そのまま出すと力が階段状に変わる。
 * フレームごとに目標へ寄せていく（変化の速さの上限）ことで、滑らかにするのと安全策を兼ねる。</p>
 *
 * <h2>安全策</h2>
 * <p>DD ハンコンは手首を痛めるほどの力を出せる。ゲームの側でおかしな値が出ても、手元のハンドルが
 * 急に回らないようにする。</p>
 * <ul>
 *   <li><b>強さの上限</b> … 出力は「強さ」（既定 30%）を超えない。機器が出せる最大に対する割合</li>
 *   <li><b>強める向きの変化の速さの上限</b> … {@link #RISE_PER_SECOND}。力が増えるのは 0 から上限まで最短 0.25 秒。
 *       弱める向き（0 へ近づく）は速くてよい——急に力が抜けても怪我はしない。向きが反転するときは、
 *       いったん 0 まで速く抜けてから、反対側へはゆっくり立ち上がる</li>
 *   <li><b>立ち上がり</b> … 運転を始めた・画面を閉じた・ウィンドウへ戻った直後は、上限そのものを
 *       {@link #FADE_IN_SECONDS} かけて 0 から上げる</li>
 *   <li><b>止める場面</b> … 運転していない・画面を開いている・ウィンドウが手前にない・ハンドルの割り当てが
 *       ハンコンでない、のどれかで即座に 0（{@link Output#stop}）</li>
 *   <li><b>ゲームが止まったら力も止まる</b> … 版の {@link Output} は、短い時間で切れる力として出し、毎フレーム
 *       出し直すこと。ゲームが固まったり落ちたりすれば出し直しが止まり、力はひとりでに消える</li>
 * </ul>
 */
public final class ForceFeedback {

    /** 強める向きの変化の速さの上限 [フルスケール/秒]。 */
    static final double RISE_PER_SECOND = 4.0;
    /** 弱める向き（0 へ近づく）の変化の速さの上限 [フルスケール/秒]。 */
    static final double FALL_PER_SECOND = 30.0;
    /** 立ち上がりにかける時間 [秒]。 */
    static final double FADE_IN_SECONDS = 1.5;
    /**
     * 1 フレームとして扱う時間の上限 [秒]。固まったあとの最初のフレームで一気に寄せないため。
     * 0.1 秒にしていたら、固まった直後の 1 フレームで 0 から 0.4 まで跳んだ（強めの衝撃）。0.02 秒なら 1 回で 0.08 まで。
     * 50fps を割ると立ち上がりがそのぶん遅くなるが、遅いのは安全な側
     */
    private static final double MAX_FRAME_SECONDS = 0.02;

    /** 止まっている・ゆっくり動いているときのセンタリング（力の元が 0 になるので、ハンドルがだらんとしないように）。 */
    static final double LOW_SPEED_CENTERING = 0.25;
    /** センタリングが抜けきる速さ [m/s]。これより速ければセルフアライニングトルクだけ。 */
    static final double LOW_SPEED_FADE = 5.0;

    /**
     * 力を出す口。版が {@link #setOutput} で置く。
     *
     * <p><b>{@link #send} の力は短い時間（0.2 秒以下）で切れるように出すこと。</b>毎フレーム呼ばれるので、
     * 呼ばれなくなったら止まる、という形にしておく。例外は投げないこと。</p>
     */
    public interface Output {
        /**
         * 機器へ力を出す。
         *
         * @param pad   ハンドルの割り当ての機器
         * @param level -1〜1。正でハンドルを右へ回す向き（機器が出せる最大に対する割合）
         */
        void send(ControllerInput.Pad pad, double level);

        /** 力を止める。何度呼んでもよい。 */
        void stop();
    }

    private static Output output;

    /** 物理から受け取った目標（-1〜1、強さを掛ける前）。 */
    private static double target;
    /** いま出している値（強さ・立ち上がりを掛けたあと）。 */
    private static double current;
    /** 立ち上がりの進み（0〜1）。 */
    private static double fade;
    /** 毎ティックの目標が来ているか（運転しているか）。 */
    private static boolean driving;
    private static long lastNanos;

    private ForceFeedback() {
    }

    public static void setOutput(Output out) {
        output = out;
    }

    /** この版で力を出せるか（画面で設定を出すか決める）。 */
    public static boolean available() {
        return output != null;
    }

    /**
     * ソフトロックの壁が立ち上がりきるまでの角度 [度]。車のロックをこれだけ越えたところで壁の力が最大になる。
     * 0 にすると、ロックを 1° 越えただけで最大の力が掛かる（壁に当たる衝撃が強すぎる）。
     */
    static final double SOFT_LOCK_RAMP_DEGREES = 15.0;

    /**
     * 毎ティック。運転していれば元になる力を渡す。手元のハンドルの位置とロックの越え方は {@link ControllerInput} から読む。
     *
     * @param aligning 物理のセルフアライニングトルク（-1〜1）。運転していなければ {@link Double#NaN}
     * @param speed    車の速さ [m/s]
     */
    public static void tick(double aligning, double speed) {
        if (Double.isNaN(aligning)) {
            driving = false;
            target = 0.0;
            return;
        }
        driving = true;
        target = targetOf(aligning, speed, ControllerInput.value(ControllerAction.STEER),
                ControllerInput.beyondLockDegrees());
    }

    /**
     * 元になる力から目標を決める。低速ではセンタリングを足し、車のロックを越えていればソフトロック（壁）を足す。
     *
     * <p><b>壁も安全策の内側にある。</b>強さの上限・変化の速さの上限を通るので、ロックに勢いよく当てても、
     * 手元に返る力は設定の強さを超えず、立ち上がりも 0.25 秒かかる。</p>
     *
     * @param steerAxis  手元のハンドルの位置（-1〜1）
     * @param beyondLock 車のロックを越えている量 [度]（正で右へ越えている）
     */
    static double targetOf(double aligning, double speed, double steerAxis, double beyondLock) {
        double low = Math.max(0.0, 1.0 - Math.abs(speed) / LOW_SPEED_FADE);
        double value = aligning - steerAxis * LOW_SPEED_CENTERING * low;
        if (beyondLock != 0.0) {
            // 越えた向きと逆へ押し返す。越えるほど強く、SOFT_LOCK_RAMP_DEGREES で最大。ほかの力より優先する
            double wall = Math.min(1.0, Math.abs(beyondLock) / SOFT_LOCK_RAMP_DEGREES);
            value = value * (1.0 - wall) - Math.signum(beyondLock) * wall;
        }
        return Double.isFinite(value) ? Math.max(-1.0, Math.min(1.0, value)) : 0.0;
    }

    /**
     * 毎フレーム。力を寄せて出す。
     *
     * @param active 画面を開いておらず、ウィンドウが手前にあるか
     */
    public static void frame(boolean active) {
        if (output == null) {
            return;
        }
        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : Math.min(MAX_FRAME_SECONDS, (now - lastNanos) / 1.0e9);
        lastNanos = now;

        ControllerInput.Pad pad = active && driving ? wheelPad() : null;
        if (pad == null) {
            halt();
            return;
        }
        fade = Math.min(1.0, fade + dt / FADE_IN_SECONDS);
        double limit = ControllerInput.feedbackStrength() * fade;
        double sign = ControllerInput.feedbackInvert() ? -1.0 : 1.0;
        current = approach(current, sign * target * limit, dt);
        try {
            output.send(pad, current);
        } catch (RuntimeException | LinkageError e) {
            // 出せなくても運転は続ける
        }
    }

    /**
     * 安全策の変化の速さで寄せる。0 へ近づく向きは速く、離れる向きはゆっくり。
     * 向きが反転するときは、まず 0 まで速く抜く。
     */
    static double approach(double from, double to, double dt) {
        if (from != 0.0 && Math.signum(to) != Math.signum(from)) {
            // 反対側（か 0）へ: 0 まで速く抜く
            double step = FALL_PER_SECOND * dt;
            return Math.abs(from) <= step ? 0.0 : from - Math.signum(from) * step;
        }
        double diff = to - from;
        boolean rising = Math.abs(to) > Math.abs(from);
        double step = (rising ? RISE_PER_SECOND : FALL_PER_SECOND) * dt;
        return Math.abs(diff) <= step ? to : from + Math.signum(diff) * step;
    }

    /** 力を出す相手。ハンドルが「ハンコン」で割り当ててあり、その機器がつながっていて、設定で有効なとき。 */
    private static ControllerInput.Pad wheelPad() {
        if (!ControllerInput.isEnabled() || !ControllerInput.feedbackEnabled() || !ControllerInput.isDirectSteer()) {
            return null;
        }
        return ControllerInput.padOf(ControllerInput.binding(ControllerAction.STEER));
    }

    /** 即座に止め、次に出すときは立ち上がりからやり直す。 */
    private static void halt() {
        boolean wasOutput = current != 0.0 || fade > 0.0;
        current = 0.0;
        fade = 0.0;
        if (wasOutput) {
            try {
                output.stop();
            } catch (RuntimeException | LinkageError e) {
                // 止められなくても、出し直しが止まるので力は切れる
            }
        }
    }
}
