package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;
import com.jdmmc.kurumamod.client.sound.BlowoffTrigger;
import com.jdmmc.kurumamod.client.sound.CarSoundSet;

/**
 * ターボの見た目と音の側。<b>ブーストそのものは物理が持つ</b>（{@code CarState#boost}）。
 * ここはそれを読んで、メーターのために補間し、ブローオフ（プシュッ）を鳴らすだけ。
 * 鳴らすかどうかと補間は core の {@link BlowoffTrigger}。
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarTurbo {

    private static final Map<CarEntity, BlowoffTrigger> STATES = new WeakHashMap<>();

    private CarTurbo() {
    }

    /** その車にターボが付いているか。 */
    public static boolean hasTurbo(CarEntity car) {
        return car.hasTurbo();
    }

    /** ブースト 0..1。ティックの間を補間する（メーターが 20Hz の階段にならないように）。 */
    public static double boost(CarEntity car, float partialTick) {
        BlowoffTrigger trigger = STATES.get(car);
        return trigger == null ? car.getRenderBoost() : trigger.boost(partialTick);
    }

    /** ブースト 0..1（ティックの値そのまま。音はティックで決めるのでこちら）。 */
    public static double boost(CarEntity car) {
        return car.getRenderBoost();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car && !car.isRemoved() && !car.isGhost()) {
                tick(car, STATES.computeIfAbsent(car, key -> new BlowoffTrigger()));
            }
        }
    }

    private static void tick(CarEntity car, BlowoffTrigger trigger) {
        float loud = trigger.tick(car.getRenderBoost(), car.getRenderThrottle(), car.getRenderGear(), car.hasBlowoff());
        if (loud <= 0.0F) {
            return;
        }
        // 少しだけ音程をばらつかせる。毎回まったく同じだと機械的に聞こえる
        float tone = 0.95F + 0.10F * car.level().getRandom().nextFloat();
        CarSoundSet sounds = CarSoundSet.of(car.getCarId());
        CarSoundSet.Tuning tuning = sounds.tuningOf(CarSoundSet.BLOWOFF);
        car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                CarSoundEvents.blowoff(sounds), SoundSource.NEUTRAL,
                CarSound.volume(loud, ClientConfig.soundTurbo),
                tone * (float) tuning.pitch(), false);
    }
}
