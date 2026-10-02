package com.jdmmc.kurumamod.physics;

/**
 * ターボの性能。<b>部品（{@code car_parts} の {@code turbo}）から来る</b>もので、調整画面の
 * 項目ではない。
 *
 * <p>ブースト（過給の具合 0..1）は {@link CarState#boost} にあり、{@link CarPhysics} が
 * 回転数とアクセルから進める。エンジンのトルクは {@code 1 + boostGain × boost} 倍になる。</p>
 *
 * <ul>
 *   <li>{@code boostGain} … ブーストがいっぱいのときトルクを何割増やすか（0.35 で 1.35 倍）</li>
 *   <li>{@code spoolFrom} / {@code spoolFull} … 過給が立ち上がり始める／いっぱいになる
 *       回転の割合（アイドル 0 〜 レブ 1）。大きいタービンほど高い回転へ寄る</li>
 *   <li>{@code spoolRate} … 目標へ寄る速さ [1/s]。小さいほどターボラグが長い。
 *       回転が高いほど速くなる（排気が多いので）</li>
 * </ul>
 *
 * <p>回転の割合で持つのは、<b>レブリミットの違う車に同じタービンを付けても意味が変わらない</b>ようにするため
 * （rpm で書くと、7000rpm の車向けの値が 4600rpm のディーゼルでは回しきれない）。</p>
 */
public record Turbo(double boostGain, double spoolFrom, double spoolFull, double spoolRate) {

    /** ターボ無し（自然吸気）。 */
    public static final Turbo NONE = new Turbo(0.0, 0.0, 1.0, 0.0);

    /** 読み込める範囲。カーパックの値はここへ丸める。 */
    public static final double MAX_BOOST_GAIN = 1.5;
    public static final double MIN_SPOOL_RATE = 0.1;
    public static final double MAX_SPOOL_RATE = 20.0;

    public Turbo {
        boostGain = clamp(boostGain, 0.0, MAX_BOOST_GAIN);
        spoolFrom = clamp(spoolFrom, 0.0, 0.95);
        spoolFull = clamp(spoolFull, spoolFrom + 0.01, 1.0);
        spoolRate = boostGain > 0.0 ? clamp(spoolRate, MIN_SPOOL_RATE, MAX_SPOOL_RATE) : 0.0;
    }

    /** ターボが付いているか。 */
    public boolean present() {
        return boostGain > 0.0;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
