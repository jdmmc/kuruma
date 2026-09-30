package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * ターボの見た目と音の側。<b>ブーストそのものは物理が持つ</b>（{@code CarState#boost}、
 * 部品の {@code turbo} で性能が決まる）。ここはそれを読んで、メーターのために補間し、
 * ブローオフ（プシュッ）を鳴らすだけ。
 *
 * <p>ブローオフは<b>ブローオフバルブの部品が付いているときだけ</b>鳴る（音だけで、走りは変わらない）。
 * ブーストが掛かっている状態でアクセルを抜いた瞬間と、シフトアップの瞬間に 1 回。
 * 大きさはそのときのブースト。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarTurbo {

    /** これ以上ブーストが掛かっていなければブローオフは鳴らない。 */
    private static final double BLOWOFF_MIN_BOOST = 0.30;
    /** ブローオフを鳴らしてから次に鳴らせるまで [tick]。連打で鳴り続けないように。 */
    private static final int BLOWOFF_COOLDOWN = 8;

    /** 1 台ぶん。ティックの境目のブーストを 2 つ持って、描画では間を取る。 */
    private static final class State {
        double boost;
        double previous;
        /** アクセルを抜く直前のブースト。抜いた瞬間には物理のブーストがもう落ち始めているので。 */
        double peak;
        double lastThrottle;
        int lastGear;
        int cooldown;
    }

    private static final Map<CarEntity, State> STATES = new WeakHashMap<>();

    private CarTurbo() {
    }

    /** その車にターボが付いているか。 */
    public static boolean hasTurbo(CarEntity car) {
        return car.hasTurbo();
    }

    /** ブースト 0..1。ティックの間を補間する（メーターが 20Hz の階段にならないように）。 */
    public static double boost(CarEntity car, float partialTick) {
        State state = STATES.get(car);
        return state == null ? car.getRenderBoost() : Mth.lerp(partialTick, state.previous, state.boost);
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
            if (entity instanceof CarEntity car && !car.isRemoved()) {
                tick(car, STATES.computeIfAbsent(car, key -> new State()));
            }
        }
    }

    private static void tick(CarEntity car, State state) {
        state.previous = state.boost;
        state.boost = car.getRenderBoost();
        double throttle = car.getRenderThrottle();
        int gear = car.getRenderGear();

        boolean lifted = state.lastThrottle > 0.5 && throttle < 0.25;
        boolean upshift = gear > state.lastGear && state.lastGear > 0;
        if (state.cooldown > 0) {
            state.cooldown--;
        }
        // 抜いた瞬間の物理のブーストは、同じティックの中ですでに落ち始めている。
        // 抜く前のティックの値で判定と大きさを決める
        double released = Math.max(state.peak, state.boost);
        if ((lifted || upshift) && released > BLOWOFF_MIN_BOOST && state.cooldown == 0 && car.hasBlowoff()) {
            float loud = (float) (0.35 + 0.65 * released);
            // 少しだけ音程をばらつかせる。毎回まったく同じだと機械的に聞こえる
            float tone = 0.95F + 0.10F * car.level().getRandom().nextFloat();
            car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                    CarModel.get(car.getCarId()).sounds().blowoff(), SoundSource.NEUTRAL, loud, tone, false);
            state.cooldown = BLOWOFF_COOLDOWN;
        }
        state.peak = state.boost;
        state.lastThrottle = throttle;
        state.lastGear = gear;
    }
}
