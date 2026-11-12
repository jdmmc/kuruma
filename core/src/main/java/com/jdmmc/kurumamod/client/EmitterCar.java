package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;

/**
 * 煙・土煙（{@link SmokeEmitter}・{@link DustEmitter}）が読む車の値。版の {@code CarEntity} がそのまま持つ
 * （同じ名前のメソッドが元からある）。どれも同期されているので、他人の車からも同じように出る。
 *
 * <p><b>Minecraft と同じ名前のメソッドを置かないこと</b>（{@code getId}・{@code getX} など）。開発環境ではバニラから継いだ
 * メソッドで満たされるが、Fabric（intermediary）・Forge（SRG）の配布環境ではバニラのメソッドの名前が変わるので満たされず、
 * {@code AbstractMethodError} で落ちる（実際に落ちた）。{@code CarEntity} の側で明示的に実装する。</p>
 */
public interface EmitterCar {

    /** エンティティの番号（{@code getId}）。 */
    int carEntityId();

    CarSpec getSpec();

    /** 前後方向の速度 [m/s]。バックで負。 */
    float getRenderSpeed();

    /** 描画と同じ補間済みのヨー角 [度]。 */
    float getRenderYaw(float partialTick);

    /** バニラのヨー角 [度]（1.4 度刻みに量子化されている）。 */
    float vanillaYaw();

    /** 位置（{@code getX}・{@code getY}・{@code getZ}）。 */
    double posX();

    double posY();

    double posZ();

    RoadSurface getRenderSurface(Wheel wheel);

    /** その輪が接地面で捨てている摩擦の仕事率 [W]（タイヤの力 × 滑り速度）。 */
    double getRenderFrictionPower(Wheel wheel);

    /** その輪が路面を撫でる速さ [m/s]。 */
    double getRenderScrubSpeed(Wheel wheel);

    double getRenderWheelLoad(Wheel wheel);

    double getRenderSuspensionLength(Wheel wheel);
}
