package com.jdmmc.kurumamod.car;

/**
 * 運転しているクライアントが確定させ、サーバーを通して周りへ配る値。<b>読むだけの口。</b>
 *
 * <p>並びと意味は {@code CarStatePacket} と同じ（パケットもこの口を実装する）。版の {@code CarEntity} は、
 * 同期項目（{@code entityData}）を読む形でこれを実装する。運転していない側は、ここから読んだ値を補間して描く。</p>
 *
 * <p><b>Minecraft と同じ名前のメソッドを置かないこと</b>（配布環境で名前が変わり {@code AbstractMethodError} になる）。</p>
 */
public interface CarSync {

    /** 前輪の切れ角 [rad]。 */
    float steerAngle();

    /** 前後方向の速度 [m/s]。 */
    float speed();

    /** 向き [度]（Minecraft のヨーと同じ向き）。 */
    float yaw();

    /** 縦の傾き [rad]。 */
    float pitch();

    /** 横の傾き [rad]。 */
    float roll();

    /** その輪のサスペンションの長さ [m]。{@code wheel} は {@code Wheel#ordinal()}。 */
    float suspension(int wheel);

    /** その輪の回転の速さ [rad/s]。 */
    float wheelSpeed(int wheel);

    /** その輪の摩擦の仕事率（煙・痕・音の強さに使う）。 */
    float friction(int wheel);

    /** エンジンの回転数 [rpm]。 */
    float rpm();

    /** ギア（0 はニュートラル、負はバック）。 */
    int gear();

    /** アクセル 0..1。 */
    float throttle();

    /** 車体の滑り角 [度]。 */
    float slipAngle();

    /** 4 輪の路面と浮いているかを詰めたもの（詰め方は {@code CarEntity#packSurfaces}）。 */
    int surface();

    /** 灯火のビット（{@code CarEntity#LIGHT_HEAD}・{@code LIGHT_BRAKE}）。 */
    byte lights();

    /** ブースト 0..1。 */
    float boost();
}
