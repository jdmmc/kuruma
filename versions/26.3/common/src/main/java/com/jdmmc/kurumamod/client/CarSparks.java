package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** 火花（{@link SparkEmitter}）を、車のいる世界に出す。 */
public final class CarSparks {

    private CarSparks() {
    }

    /**
     * 擦れた・ぶつかった点から火花を出す（{@link SparkEmitter#emit}）。
     *
     * @param offset 擦れている（ぶつかった）点の、車の位置からのずれ（ワールドの向き） [m]。x, y, z
     */
    public static void emit(CarEntity car, float[] offset, float[] normal, float[] velocity, float impact) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || car.level() != level) {
            return;
        }
        SparkEmitter.emit(car.getX() + offset[0], car.getY() + offset[1], car.getZ() + offset[2],
                normal, velocity, impact, new McParticleSink(level));
    }
}
