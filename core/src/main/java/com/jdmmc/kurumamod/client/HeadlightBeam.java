package com.jdmmc.kurumamod.client;

/**
 * ヘッドライトの配光（ロービームのカットオフ・エルボー・広がり・ホットスポット）と、照度から明るさへの換算。
 * 版の {@code CarLights}（路面の照り返し・光の筋）が使う。<b>Minecraft には触らない</b>純粋な計算。
 *
 * <p>画素ごとに光を解く {@code HeadlightVolume} のシェーダー（{@code headlight_volume.fsh}）も同じ配光を
 * 持っている。<b>片方を直したら、もう片方も揃えること。</b></p>
 */
public final class HeadlightBeam {

    // 角度はすべて光軸からの<b>正接</b>で持つ（横 = x/z、縦 = y/z）。角度にすると
    // 頂点ごとに atan2 が 2 回要るうえ、小さい角度では正接とほとんど変わらない
    /**
     * 光軸を水平からどれだけ下げるか。
     *
     * <p>実車のロービームは 1% 前後（0.6 度）下向きに合わせる。ここでは 0.9 度で、
     * 車高 60cm なら光軸がおよそ 40m 先で路面に当たる。1.2 度では手前に寄りすぎた。</p>
     */
    static final double AIM_DOWN = Math.toRadians(0.9);
    /**
     * オートレベライザーの効き。サスの沈み込みで生じた前後の傾きのうち、どれだけ光軸を戻すか。
     *
     * <p>入れないと、ブレーキのノーズダイブで光軸が数度下を向き、<b>止まろうとした瞬間に
     * 前が見えなくなる</b>（加速のスクワットでは逆に空を照らす）。実車にも同じ理由で付いている。
     * 見るのは<b>前後のサスの長さの差</b>だけで、坂による車体の傾きは戻さない——戻すと
     * 上り坂の手前で光が坂を照らさなくなる。1 にしないのは、少しだけ沈んだほうが
     * 「ブレーキを踏んだ」ことが光で分かるから。</p>
     */
    static final double LEVELING = 0.85;
    /** カットオフ（これより上は照らさない）の、光軸からの高さ。 */
    static final double CUTOFF = Math.tan(Math.toRadians(0.6));
    /** カットオフのぼかし幅。0 にするとくっきりしすぎて段が見える。 */
    static final double CUTOFF_SOFT = Math.tan(Math.toRadians(0.5));
    /**
     * 歩道側のカットオフの跳ね上げ（エルボー）。
     *
     * <p>ロービームは対向車を眩惑しないよう道路側を低く抑え、<b>歩道側だけ</b>
     * 15 度の傾きで持ち上げて標識や歩行者を照らす。日本は左側通行なので<b>左が歩道側</b>。</p>
     */
    static final double KICK_SLOPE = Math.tan(Math.toRadians(15.0));
    static final double KICK_MAX = Math.tan(Math.toRadians(2.5));
    /** 広がり（横・縦）。路面を広く薄く照らす成分。 */
    static final double WIDE_X = Math.tan(Math.toRadians(24.0));
    static final double WIDE_Y = Math.tan(Math.toRadians(8.0));
    static final double WIDE_WEIGHT = 0.45;
    /** 集光（ホットスポット）。カットオフのすぐ下に集まる明るい芯。 */
    static final double SPOT_X = Math.tan(Math.toRadians(7.0));
    static final double SPOT_Y = Math.tan(Math.toRadians(2.2));
    static final double SPOT_CENTER = -Math.tan(Math.toRadians(0.8));
    static final double SPOT_WEIGHT = 0.80;

    /**
     * 照度から明るさへの換算。{@code 明るさ = 1 − exp(−(照度 × GAIN)^GAMMA)}。
     *
     * <p>照度は距離の 3 乗で落ちる（距離の 2 乗 × 浅くなる入射角）ので、そのままでは
     * 手前だけ白飛びして奥が真っ暗になる。GAMMA で圧縮して、25m 先でも 4 割ほど残す。</p>
     */
    static final double EXPOSURE_GAIN = 8600.0;
    static final double EXPOSURE_GAMMA = 0.6;

    private HeadlightBeam() {
    }

    /**
     * 光軸を水平から下げる角度 [rad]。取り付けの下向き（Ctrl+L で動かせる。既定は {@link #AIM_DOWN}）に、
     * サスの沈み込みの戻し（既定は {@link #LEVELING}）を足す。
     *
     * @param suspensionPitch サスの沈み込みで生じた前後の傾き [rad]。正で鼻上げ
     */
    public static double aimDown(double suspensionPitch) {
        return Math.toRadians(com.jdmmc.kurumamod.ClientConfig.headlightAim)
                + suspensionPitch * com.jdmmc.kurumamod.ClientConfig.headlightLeveling;
    }

    /** 光軸からの正接 (tx, ty) に出る光の強さ。光軸上でおよそ 1。 */
    public static double pattern(double tx, double ty) {
        // 歩道側（左 = tx が負）はカットオフが持ち上がる
        double cut = CUTOFF + Math.min(KICK_MAX, Math.max(0.0, -tx) * KICK_SLOPE);
        double gate = 1.0 - smoothstep(-CUTOFF_SOFT, CUTOFF_SOFT, ty - cut);
        if (gate <= 0.0) {
            return 0.0;
        }
        double wx = tx / WIDE_X;
        double wy = ty / WIDE_Y;
        double sx = tx / SPOT_X;
        double sy = (ty - SPOT_CENTER) / SPOT_Y;
        return gate * (WIDE_WEIGHT * Math.exp(-(wx * wx + wy * wy))
                + SPOT_WEIGHT * Math.exp(-(sx * sx + sy * sy)));
    }

    /** 照度を画面の明るさ 0..1 へ。 */
    public static float exposure(double illuminance) {
        if (illuminance <= 0.0) {
            return 0.0F;
        }
        return (float) (1.0 - Math.exp(-Math.pow(illuminance * EXPOSURE_GAIN, EXPOSURE_GAMMA)));
    }

    public static double smoothstep(double edge0, double edge1, double x) {
        double t = com.jdmmc.kurumamod.util.Mth.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
