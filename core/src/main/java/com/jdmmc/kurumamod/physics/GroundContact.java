package com.jdmmc.kurumamod.physics;

import java.util.Arrays;

/**
 * 各輪の直下にある接地面の情報。
 *
 * <p>地形を調べるには Minecraft のレイキャストが要るが、物理コアは Minecraft 非依存に保ちたい。
 * そこで**調べるのは {@code CarEntity} の仕事、読むのが物理コアの仕事**として、
 * その受け渡しをこのクラスが担う。ゲーム外で実験するときは任意の地形を直接書き込めばよい。</p>
 *
 * <p>路面の種類（舗装・土・砂…）や雨で濡れているかも、ここには<b>解決済みの倍率</b>としてだけ
 * 現れる。物理コアはブロックもタグも天候も知らない。おかげで実験では、路面をまたぐ瞬間や
 * 左右で路面が違う状況を好きに作れる。</p>
 *
 * <p>毎ティック使い回す前提でミュータブルにしてある。</p>
 */
public final class GroundContact {

    /**
     * 接地面のワールド高さ [m]。地面が見つからなければ {@link Double#NEGATIVE_INFINITY}。
     *
     * <p>負の無限大にしておくと、サスペンションの伸び計算がそのまま「伸びきり」に落ちて
     * 荷重 0 になるので、接地の有無を場合分けせずに済む。</p>
     */
    public final double[] groundHeight = new double[Wheel.COUNT];

    /** 摩擦係数に掛ける倍率。1.0 が舗装路（＝諸元の {@code tireFriction} そのまま）。 */
    public final double[] gripScale = new double[Wheel.COUNT];

    /** 転がり抵抗に掛ける倍率。1.0 が舗装路。砂や雪では大きくなる。 */
    public final double[] rollingScale = new double[Wheel.COUNT];

    public GroundContact() {
        clear();
    }

    /** 全輪を「地面なし・舗装路」に戻す。 */
    public void clear() {
        Arrays.fill(groundHeight, Double.NEGATIVE_INFINITY);
        Arrays.fill(gripScale, 1.0);
        Arrays.fill(rollingScale, 1.0);
    }

    /** 舗装路として接地面を置く。実験用。 */
    public void set(Wheel wheel, double height) {
        set(wheel, height, 1.0, 1.0);
    }

    public void set(Wheel wheel, double height, double gripScale, double rollingScale) {
        int index = wheel.ordinal();
        groundHeight[index] = height;
        this.gripScale[index] = gripScale;
        this.rollingScale[index] = rollingScale;
    }

    public double get(Wheel wheel) {
        return groundHeight[wheel.ordinal()];
    }

    public double gripScale(Wheel wheel) {
        return gripScale[wheel.ordinal()];
    }

    public double rollingScale(Wheel wheel) {
        return rollingScale[wheel.ordinal()];
    }

    /**
     * 前軸 2 輪のグリップ倍率の平均。
     *
     * <p>切れ角の上限を「グリップを使いきる定常旋回」で決めているので、そこで使う。
     * 舗装路のμのまま上限を決めると、<b>砂の上でも舗装前提の角度まで切れて即スピンする</b>。</p>
     */
    public double frontGripScale() {
        return (gripScale(Wheel.FRONT_LEFT) + gripScale(Wheel.FRONT_RIGHT)) / 2.0;
    }
}
