package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import com.jdmmc.kurumamod.client.effect.DustEmitter;
import com.jdmmc.kurumamod.platform.McParticleSink;

/**
 * 車を集めて、未舗装を走る車輪から土煙を出す（{@link DustEmitter}）。
 *
 * <p>車のモデルが描かれる範囲（{@code Entity#shouldRender}）の車だけ。この距離判定にカメラが要るのでここ（クライアント）で集める。</p>
 */
public final class CarDust {

    private CarDust() {
    }

    public static void onClientTick() {
        if (!ClientConfig.showDust) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.getPosition();
        McParticleSink sink = new McParticleSink(level);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car && !car.isGhost() && car.shouldRender(eye.x, eye.y, eye.z)) {
                DustEmitter.spawn(car, car.xo, car.yo, car.zo,
                        level.isRainingAt(car.blockPosition()), sink);
            }
        }
    }
}
