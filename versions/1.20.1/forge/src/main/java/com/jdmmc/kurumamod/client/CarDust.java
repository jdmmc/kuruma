package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 車を集めて、未舗装を走る車輪から土煙を出す（{@link DustEmitter}）。
 *
 * <p>車のモデルが描かれる範囲（{@code Entity#shouldRender}）の車だけ。この距離判定にカメラが要るのでここ（クライアント）で集める。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarDust {

    private CarDust() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClientConfig.showDust) {
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
