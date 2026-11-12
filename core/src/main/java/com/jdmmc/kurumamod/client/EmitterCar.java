package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;

/**
 * 煙・土煙（{@link SmokeEmitter}・{@link DustEmitter}）が読む車の値。版の {@code CarEntity} がそのまま持つ
 * （同じ名前のメソッドが元からある）。どれも同期されているので、他人の車からも同じように出る。
 */
public interface EmitterCar {

    int getId();

    CarSpec getSpec();

    /** 前後方向の速度 [m/s]。バックで負。 */
    float getRenderSpeed();

    /** 描画と同じ補間済みのヨー角 [度]。 */
    float getRenderYaw(float partialTick);

    /** バニラのヨー角 [度]（1.4 度刻みに量子化されている）。 */
    float getYRot();

    double getX();

    double getY();

    double getZ();

    RoadSurface getRenderSurface(Wheel wheel);

    /** その輪が接地面で捨てている摩擦の仕事率 [W]（タイヤの力 × 滑り速度）。 */
    double getRenderFrictionPower(Wheel wheel);

    /** その輪が路面を撫でる速さ [m/s]。 */
    double getRenderScrubSpeed(Wheel wheel);

    double getRenderWheelLoad(Wheel wheel);

    double getRenderSuspensionLength(Wheel wheel);
}
