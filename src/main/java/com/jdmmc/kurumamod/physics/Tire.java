package com.jdmmc.kurumamod.physics;

import java.util.Map;

/**
 * タイヤの性能。<b>部品（{@code car_parts} の {@code tire}）から来る</b>もので、調整画面の項目ではない
 * （{@link Turbo} と同じ扱い）。
 *
 * <ul>
 *   <li>{@code stiffness} … 横・縦の剛性（スリップ角や滑り率あたりにどれだけ力が立つか）の倍率。
 *       ブロックの大きいタイヤはよれるので小さくなり、舵の効き始めが鈍くなる。グリップの上限は変えない</li>
 *   <li>{@code grip} … 路面ごとのグリップの倍率。路面の名前（{@code paved} / {@code dirt} / {@code gravel} /
 *       {@code sand} / {@code snow} / {@code ice}）から引く。書かれていない路面は 1</li>
 *   <li>{@code rolling} … 路面ごとの転がり抵抗の倍率。同上</li>
 * </ul>
 *
 * <p><b>路面ごとの倍率は物理コアでは掛けない。</b>物理コアはブロックも路面の種類も知らず、
 * 解決済みのグリップ倍率（{@link GroundContact#gripScale}）だけを読む。路面を調べている側
 * （{@code CarEntity}）が「路面のグリップ × タイヤのその路面での倍率」にしてから渡す。
 * 路面の名前を文字列で持つのも、物理コアを {@code RoadSurface} に依存させないため。</p>
 *
 * <p>倍率は<b>ノーマルタイヤ（{@link #NORMAL}）に対する比</b>。ノーマルはすべて 1 なので、
 * タイヤを入れる前と挙動が変わらない。</p>
 */
public record Tire(double stiffness, Map<String, Double> grip, Map<String, Double> rolling) {

    /** ノーマルタイヤ。何も付けていないときもこれ。 */
    public static final Tire NORMAL = new Tire(1.0, Map.of(), Map.of());

    /** 読み込める範囲。カーパックの値はここへ丸める。 */
    public static final double MIN_SCALE = 0.3;
    public static final double MAX_SCALE = 3.0;

    public Tire {
        stiffness = clamp(stiffness);
        grip = clampAll(grip);
        rolling = clampAll(rolling);
    }

    /** その路面でのグリップの倍率。 */
    public double gripOn(String surface) {
        return grip.getOrDefault(surface, 1.0);
    }

    /** その路面での転がり抵抗の倍率。 */
    public double rollingOn(String surface) {
        return rolling.getOrDefault(surface, 1.0);
    }

    private static Map<String, Double> clampAll(Map<String, Double> values) {
        Map<String, Double> copy = new java.util.HashMap<>();
        values.forEach((surface, value) -> copy.put(surface, clamp(value)));
        return Map.copyOf(copy);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 1.0;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }
}
