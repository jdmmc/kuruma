package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.physics.CarSpec;

/** {@link RaceEngine} から見た、運転している車。版ごとの {@code McRaceServer} が {@code CarEntity} を包む。 */
public interface RaceCar {

    double getX();

    double getY();

    double getZ();

    /** 車種の id（{@code kurumamod:s15} など）。 */
    String carId();

    /** 実際に走る諸元（部品込み）。クラスの決まりの判定に使う。 */
    CarSpec getSpec();

    /** その車種の素の諸元（部品なし）。 */
    CarSpec baseSpec();
}
