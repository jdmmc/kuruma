package com.jdmmc.kurumamod.physics;

/**
 * 空力部品の性能。<b>部品（{@code car_parts} の {@code aero}）から来る</b>もので、調整画面の項目ではない。
 *
 * <p>値はダウンフォースの面積 −Cl×面積 [m²] を軸ごとに。ウイングの角度で効きが変わる部品は
 * 範囲（{@code min}〜{@code max}）で持ち、どこに置くかは装着状態の角度（0〜1、
 * {@code PartFitment.Setting#angle}）が決める。角度の無い部品（ダックテール、リップ）は
 * {@code min == max}。</p>
 *
 * <p><b>空気抵抗は持たない。</b>ダウンフォースから {@link CarSpec#DOWNFORCE_PER_DRAG} で決まる。
 * 部品ごとに書けると「抵抗の増えないウイング」が作れてしまう。</p>
 *
 * <p>角度が変えるのは性能だけで、見た目（車体のメッシュ）は動かない。</p>
 *
 * @param frontMin     角度 0 での前軸の ClA
 * @param frontMax     角度 1 での前軸の ClA
 * @param rearMin      角度 0 での後軸の ClA
 * @param rearMax      角度 1 での後軸の ClA
 * @param defaultAngle 調整していないときの角度（0〜1）
 */
public record Aero(double frontMin, double frontMax, double rearMin, double rearMax, double defaultAngle) {

    /** 1 軸あたりの上限。調整画面のダウンフォースのスライダーと同じ。 */
    public static final double MAX_AREA = 3.0;

    public Aero {
        frontMin = clamp(frontMin, 0.0, MAX_AREA);
        frontMax = clamp(frontMax, 0.0, MAX_AREA);
        rearMin = clamp(rearMin, 0.0, MAX_AREA);
        rearMax = clamp(rearMax, 0.0, MAX_AREA);
        defaultAngle = clamp(defaultAngle, 0.0, 1.0);
    }

    /** 角度を調整できるか。範囲が無い部品では換装画面にスライダーを出さない。 */
    public boolean adjustable() {
        return frontMin != frontMax || rearMin != rearMax;
    }

    /** その角度で効く前後の ClA。 */
    public PartAero at(double angle) {
        double t = clamp(angle, 0.0, 1.0);
        return new PartAero(frontMin + (frontMax - frontMin) * t, rearMin + (rearMax - rearMin) * t);
    }

    static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
