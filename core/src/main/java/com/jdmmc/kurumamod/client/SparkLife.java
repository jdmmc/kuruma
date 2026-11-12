package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.util.Mth;

/**
 * 火花の粒（版の {@code SparkParticle}）の色の冷め方と、筋の長さ。描き方（板の出し方）は版ごとに違う。
 */
public final class SparkLife {

    /** 生まれたときの色（白っぽい黄色）。 */
    static final float HOT_R = 1.0F;
    static final float HOT_G = 0.96F;
    static final float HOT_B = 0.72F;
    /** 冷えきったときの色（赤みの強い橙）。 */
    static final float COLD_R = 1.0F;
    static final float COLD_G = 0.35F;
    static final float COLD_B = 0.08F;
    /** 最後のどれだけの割合で薄れていくか。 */
    static final float FADE_SHARE = 0.4F;
    /** 筋の長さを、直前のティックに動いた距離の何倍にするか。 */
    static final float STREAK_TICKS = 1.8F;
    /** 筋の長さの下限（幅の何倍か）。止まった粒も短い筋として見える。 */
    static final float MIN_LENGTH_PER_WIDTH = 1.5F;

    private SparkLife() {
    }

    /** 寿命の割合 {@code t}（0..1）での色 {r, g, b}。白っぽい黄色から赤みの強い橙へ冷める。 */
    public static float[] color(float t) {
        return new float[]{Mth.lerp(t, HOT_R, COLD_R), Mth.lerp(t, HOT_G, COLD_G), Mth.lerp(t, HOT_B, COLD_B)};
    }

    /** 寿命の割合 {@code t} での濃さ。最後の {@link #FADE_SHARE} で薄れる。 */
    public static float alpha(float t) {
        return Mth.clamp((1.0F - t) / FADE_SHARE, 0.0F, 1.0F);
    }

    /** 筋の長さ。直前のティックに動いた距離 {@code moved} から（止まった粒も短い筋として見える）。 */
    public static float length(float moved, float width) {
        return Math.max(moved * STREAK_TICKS, width * MIN_LENGTH_PER_WIDTH);
    }
}
