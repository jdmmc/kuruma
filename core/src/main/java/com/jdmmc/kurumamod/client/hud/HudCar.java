package com.jdmmc.kurumamod.client.hud;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;

/**
 * HUD（{@link CarGaugeHud}・{@link CarHud}）が読む、乗っている車の値。版の {@code CarEntity} がそのまま持つ
 * （同じ名前のメソッドが元からあるので、{@code implements} を足しただけ）。
 *
 * <p>どれも描画用の値（同期された値か、運転者なら手元で解いた値）。同乗者にも出せる。</p>
 */
public interface HudCar {

    CarSpec getSpec();

    boolean hasTurbo();

    /** 回転数 [rpm]。 */
    double getRenderRpm();

    /** 前後方向の速度 [m/s]。バックで負。 */
    float getRenderSpeed();

    /** アクセル・ブレーキの踏み量 0..1。 */
    double getRenderThrottle();

    double getRenderBrake();

    boolean isAbsActive();

    boolean isTractionControlActive();

    boolean isHandbrakeOn();

    /** サイドブレーキの引き量 0..1。運転している本人でなければ負（メーターに出さない）。 */
    double getRenderHandbrake();

    /**
     * クラッチペダルの踏み込み量 0..1。マニュアルの車でクラッチペダルを割り当てた運転者でなければ負
     * （メーターに出さない）。
     */
    double getRenderClutchPedal();

    /**
     * H シフターのレバーの位置。クラッチを踏まないと段に入らない場面（マニュアル・H シフター・クラッチペダル）で
     * なければ {@code CarInput#NO_GEAR_SELECT}。入っている段と違えば、メーターがレバーの位置を添える。
     */
    int getRenderLeverGear();

    /** 段。正が前進、0 がニュートラル、負がバック。 */
    int getRenderGear();

    /** 横加速度 [m/s²]。 */
    double getRenderLateralAcceleration();

    /** ドリフト角 [度]。 */
    double getRenderSlipAngleDegrees();

    /** いちばんグリップの低い輪の路面。 */
    RoadSurface getRenderSurface();

    /** 路面の濡れ 0..1。 */
    double getRenderWetness();

    /** その輪の接地荷重 [N]。 */
    double getRenderWheelLoad(Wheel wheel);

    /** その輪の縦の滑り率。 */
    double getRenderSlipRatio(Wheel wheel);
}
