package com.jdmmc.kurumamod.physics;

/**
 * 運転操作の入力。
 *
 * <p>Minecraft のクラスに依存させないため、キーバインドではなく値として持つ。</p>
 *
 * <h2>キーボードとコントローラ</h2>
 *
 * <p>キーボードは 0/100 しかないので、真偽値で受けて<b>変化率に上限を掛けて</b>アナログに
 * 近づける（{@code pedalPressSeconds} / {@code steerRateSeconds}）。コントローラは
 * 最初からアナログなので、<b>その必要が無いどころか、掛けると操作が遅れる</b>。
 * {@code analog} でどちらかを区別し、物理側で扱いを変える。</p>
 *
 * <p>真偽値の側も残してあるのは、段の選択やブレーキとバックの判定が「踏んでいるか」で
 * 分岐しているため。アナログのときは軸の値から導く。</p>
 *
 * <p>変速の 2 つだけは押しっぱなしではなく<b>押した瞬間の 1 回</b>を表す。物理は 1 ティックを
 * 4 分割して解くので、2 刻み目以降には {@link #withoutShift()} を渡すこと（でないと 1 回の操作で
 * 4 段ぶん要求したことになる）。</p>
 *
 * @param throttle     アクセルを踏んでいるか
 * @param brake        ブレーキを踏んでいるか（後退の判定にも使う）
 * @param left         左ステア
 * @param right        右ステア
 * @param shiftUp      シフトアップ。押した瞬間だけ true
 * @param shiftDown    シフトダウン。押した瞬間だけ true
 * @param handbrake    サイドブレーキ
 * @param throttleAxis アクセルの踏み込み量 0..1。{@code analog} のときだけ意味を持つ
 * @param brakeAxis    ブレーキの踏み込み量 0..1。同上
 * @param steerAxis    ステアの位置 -1..1。正で右。同上
 * @param handbrakeAxis サイドブレーキの引き量 0..1。同上。ハンコンのサイドレバーのように無段階に引ける入力
 * @param analog       コントローラなど、位置をそのまま渡せる入力か
 */
public record CarInput(boolean throttle, boolean brake, boolean left, boolean right,
                       boolean shiftUp, boolean shiftDown, boolean handbrake,
                       double throttleAxis, double brakeAxis, double steerAxis, double handbrakeAxis,
                       boolean analog) {

    /** 軸がこの値を超えたら「踏んでいる」と見なす。 */
    public static final double AXIS_THRESHOLD = 0.05;

    /** キーボード相当。変速もサイドブレーキも使わない。実験用のコードがそのまま通るように残してある。 */
    public CarInput(boolean throttle, boolean brake, boolean left, boolean right) {
        this(throttle, brake, left, right, false, false, false);
    }

    /** キーボード相当。 */
    public CarInput(boolean throttle, boolean brake, boolean left, boolean right,
                    boolean shiftUp, boolean shiftDown, boolean handbrake) {
        this(throttle, brake, left, right, shiftUp, shiftDown, handbrake,
                throttle ? 1.0 : 0.0, brake ? 1.0 : 0.0,
                (right ? 1.0 : 0.0) - (left ? 1.0 : 0.0), handbrake ? 1.0 : 0.0, false);
    }

    /**
     * コントローラ相当。軸の値から真偽値を導く。
     *
     * @param throttleAxis 0..1
     * @param brakeAxis    0..1
     * @param steerAxis    -1..1。正で右
     */
    public static CarInput analog(double throttleAxis, double brakeAxis, double steerAxis,
                                  boolean shiftUp, boolean shiftDown, boolean handbrake) {
        return analog(throttleAxis, brakeAxis, steerAxis, shiftUp, shiftDown, handbrake ? 1.0 : 0.0);
    }

    /**
     * コントローラ相当で、サイドブレーキも軸で渡す。
     *
     * @param handbrakeAxis 0..1。ボタンに割り当てていれば 0 か 1
     */
    public static CarInput analog(double throttleAxis, double brakeAxis, double steerAxis,
                                  boolean shiftUp, boolean shiftDown, double handbrakeAxis) {
        double th = clamp01(throttleAxis);
        double br = clamp01(brakeAxis);
        double st = Math.max(-1.0, Math.min(1.0, steerAxis));
        double hb = clamp01(handbrakeAxis);
        return new CarInput(th > AXIS_THRESHOLD, br > AXIS_THRESHOLD,
                st < -AXIS_THRESHOLD, st > AXIS_THRESHOLD,
                shiftUp, shiftDown, hb > AXIS_THRESHOLD, th, br, st, hb, true);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * 変速の要求だけを落とした入力。1 ティックを分割して解くとき、2 刻み目以降に渡す。
     *
     * <p>変速の要求は押した瞬間の 1 回なので、刻みごとに同じ入力を渡すと 4 回押したことになる。
     * ふだんは変速時間（{@code shiftTimer}）が 2 回目以降を弾くが、変速時間を 0 にすると
     * 弾かれず、1 回押しただけで 4 段動く。</p>
     */
    public CarInput withoutShift() {
        if (!shiftUp && !shiftDown) {
            return this;
        }
        return new CarInput(throttle, brake, left, right, false, false, handbrake,
                throttleAxis, brakeAxis, steerAxis, handbrakeAxis, analog);
    }

    /** 無操作。誰も乗っていない車や、運転していない側で使う。 */
    public static final CarInput NONE = new CarInput(false, false, false, false);
}
