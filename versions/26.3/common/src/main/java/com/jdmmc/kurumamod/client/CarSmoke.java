package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.jdmmc.kurumamod.client.effect.SmokeEmitter;
import com.jdmmc.kurumamod.platform.McParticleSink;

/**
 * 車を集めて、滑っている車輪から煙を出す（{@link SmokeEmitter}）。
 *
 * <p>車のモデルが描かれる範囲（{@code Entity#shouldRender}）の車だけ。この距離判定にカメラが要るのでここ（クライアント）で集める。</p>
 */
public final class CarSmoke {

    private CarSmoke() {
    }

    public static void onClientTick() {
        // 設定の「煙と土煙を出す」で土煙（CarDust）と一緒に切れる
        if (!ClientConfig.showDust) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.position();
        McParticleSink sink = new McParticleSink(level);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car && !car.isGhost()
                    && car.shouldRender(eye.x, eye.y, eye.z)) {
                SmokeEmitter.spawn(car, car.xo, car.yo, car.zo,
                        level.isRainingAt(car.blockPosition()), sink);
            }
        }
    }
}
