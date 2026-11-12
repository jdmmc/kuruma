package com.jdmmc.kurumamod.client.sound;

/**
 * ブローオフ（プシュッ）を鳴らすかどうかと、メーター用に補間したブースト。1 台に 1 つ持つ。
 * Minecraft に依存しない（鳴らすのは版の {@code CarTurbo}）。
 *
 * <p><b>ブーストそのものは物理が持つ</b>（{@code CarState#boost}、部品の {@code turbo} で性能が決まる）。
 * ここはそれをティックごとに受け取るだけ。</p>
 *
 * <p>ブローオフは<b>ブローオフバルブの部品が付いているときだけ</b>鳴る（音だけで、走りは変わらない）。
 * ブーストが掛かっている状態でアクセルを抜いた瞬間と、シフトアップの瞬間に 1 回。
 * 大きさはそのときのブースト。</p>
 */
public final class BlowoffTrigger {

    /** これ以上ブーストが掛かっていなければブローオフは鳴らない。 */
    private static final double MIN_BOOST = 0.30;
    /** ブローオフを鳴らしてから次に鳴らせるまで [tick]。連打で鳴り続けないように。 */
    private static final int COOLDOWN = 8;

    /** ティックの境目のブーストを 2 つ持って、描画では間を取る。 */
    private double boost;
    private double previous;
    /** アクセルを抜く直前のブースト。抜いた瞬間には物理のブーストがもう落ち始めているので。 */
    private double peak;
    private double lastThrottle;
    private int lastGear;
    private int cooldown;

    /**
     * 1 ティック進める。
     *
     * @return 鳴らすならその大きさ（0.35〜1）、鳴らさないなら 0
     */
    public float tick(double boost, double throttle, int gear, boolean hasBlowoff) {
        previous = this.boost;
        this.boost = boost;

        boolean lifted = lastThrottle > 0.5 && throttle < 0.25;
        boolean upshift = gear > lastGear && lastGear > 0;
        if (cooldown > 0) {
            cooldown--;
        }
        // 抜いた瞬間の物理のブーストは、同じティックの中ですでに落ち始めている。
        // 抜く前のティックの値で判定と大きさを決める
        double released = Math.max(peak, boost);
        float loud = 0.0F;
        if ((lifted || upshift) && released > MIN_BOOST && cooldown == 0 && hasBlowoff) {
            loud = (float) (0.35 + 0.65 * released);
            cooldown = COOLDOWN;
        }
        peak = boost;
        lastThrottle = throttle;
        lastGear = gear;
        return loud;
    }

    /** ブースト 0..1。ティックの間を補間する（メーターが 20Hz の階段にならないように）。 */
    public double boost(float partialTick) {
        return previous + partialTick * (boost - previous);
    }
}
