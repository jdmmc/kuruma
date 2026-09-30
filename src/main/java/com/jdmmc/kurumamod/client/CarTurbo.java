package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
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
 * ターボのブースト（過給圧）。車ごとに持つ、<b>音と表示のためだけの</b>状態。
 *
 * <p>物理のトルクには効かない。<b>回転数とアクセルから決まる</b>ので、どちらも配ってある
 * 他人の車でも同じように上がる。過給音（{@link CarSound} の {@code TURBO}）とメーターの
 * TURBO ランプ（{@link CarGaugeHud}）がここを読み、ブローオフ（プシュッ）はここから鳴らす。
 * 以前は過給音の中に持っていたが、メーターからも読むために切り出した——2 か所で数えると
 * 聞こえているブーストと見えているブーストが食い違う。</p>
 *
 * <p>ターボかどうかは車の見た目の定義（{@code vehicles/<car>.json} の
 * {@code sounds.turbo}）で決まる。書かなければターボ付き。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarTurbo {

    /** 過給が立ち上がり始める／いっぱいになる回転の割合（アイドル 0 〜 レブ 1）。 */
    private static final double SPOOL_FROM = 0.22;
    private static final double SPOOL_FULL = 0.60;
    /** 1 ティックに目標へ寄る割合。上がるのは遅く（ターボラグ）、回すほど速い。 */
    private static final double SPOOL_RATE = 0.06;
    /** アクセルを抜いたときの抜け方。ブローオフが開くので一気に落ちる。 */
    private static final double VENT_RATE = 0.35;
    /** 踏んだまま目標が下がったとき（回転が落ちたなど）の落ち方。 */
    private static final double DECAY_RATE = 0.12;
    /** これ以上ブーストが掛かっていなければブローオフは鳴らない。 */
    private static final double BLOWOFF_MIN_BOOST = 0.30;
    /** ブローオフを鳴らしてから次に鳴らせるまで [tick]。連打で鳴り続けないように。 */
    private static final int BLOWOFF_COOLDOWN = 8;

    /** 1 台ぶん。ティックの境目の値を 2 つ持って、描画では間を取る。 */
    private static final class State {
        double boost;
        double previous;
        double lastThrottle;
        int lastGear;
        int cooldown;
    }

    private static final Map<CarEntity, State> STATES = new WeakHashMap<>();

    private CarTurbo() {
    }

    /** その車がターボ付きか。 */
    public static boolean hasTurbo(CarEntity car) {
        return CarModel.get(car.getCarId()).sounds().turbo();
    }

    /** ブースト 0..1。ティックの間を補間する（メーターが 20Hz の階段にならないように）。 */
    public static double boost(CarEntity car, float partialTick) {
        State state = STATES.get(car);
        return state == null ? 0.0 : Mth.lerp(partialTick, state.previous, state.boost);
    }

    /** ブースト 0..1（ティックの値そのまま。音はティックで決めるのでこちら）。 */
    public static double boost(CarEntity car) {
        State state = STATES.get(car);
        return state == null ? 0.0 : state.boost;
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
        if (!hasTurbo(car)) {
            state.boost = 0.0;
            return;
        }
        CarSpec spec = car.getSpec();
        double span = Math.max(1.0, spec.redlineRpm() - spec.idleRpm());
        double rev = Mth.clamp((car.getRenderRpm() - spec.idleRpm()) / span, 0.0, 1.0);
        double throttle = car.getRenderThrottle();
        int gear = car.getRenderGear();

        double spool = Mth.clamp((rev - SPOOL_FROM) / (SPOOL_FULL - SPOOL_FROM), 0.0, 1.0);
        spool = spool * spool * (3.0 - 2.0 * spool);
        double target = throttle * spool;
        double rate;
        if (target > state.boost) {
            // 排気が多い（回っている）ほどタービンは速く立ち上がる
            rate = SPOOL_RATE * (0.4 + rev);
        } else {
            rate = throttle < 0.25 ? VENT_RATE : DECAY_RATE;
        }

        // アクセルを抜いた瞬間と、シフトアップの瞬間に、溜まった圧を逃がす
        boolean lifted = state.lastThrottle > 0.5 && throttle < 0.25;
        boolean upshift = gear > state.lastGear && state.lastGear > 0;
        if (state.cooldown > 0) {
            state.cooldown--;
        }
        if ((lifted || upshift) && state.boost > BLOWOFF_MIN_BOOST && state.cooldown == 0) {
            float loud = (float) (0.35 + 0.65 * state.boost);
            // 少しだけ音程をばらつかせる。毎回まったく同じだと機械的に聞こえる
            float tone = 0.95F + 0.10F * car.level().getRandom().nextFloat();
            car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                    CarModel.get(car.getCarId()).sounds().blowoff(), SoundSource.NEUTRAL, loud, tone, false);
            state.boost *= 0.3;
            state.cooldown = BLOWOFF_COOLDOWN;
        }
        state.boost += (target - state.boost) * rate;
        state.lastThrottle = throttle;
        state.lastGear = gear;
    }
}
