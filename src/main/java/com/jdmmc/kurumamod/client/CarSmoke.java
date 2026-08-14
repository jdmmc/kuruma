package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 滑っている車輪から煙を出す。
 *
 * <p>濃さは<b>その輪が接地面で捨てている摩擦の仕事率</b>（タイヤの力 × 滑り速度。
 * {@link CarEntity#getRenderFrictionPower}）で決める。タイヤが煙を上げるのはゴムが
 * 熱を持つからで、その熱の出どころがこれ。<b>回っているだけでも、荷重が掛かっているだけでも
 * 大きくならない</b>のが要点で、</p>
 *
 * <ul>
 *   <li>後輪駆動で後輪をブン回しても、転がっているだけの前輪からは出ない</li>
 *   <li>空転している浮いた輪（荷重 0）からも出ない</li>
 *   <li>限界で曲がっているだけ（スリップ角は立つが滑り速度は小さい）でも出ない</li>
 * </ul>
 *
 * <p>車重で割って正規化してあるので、重い車でも軽い車でもしきい値は同じでよい。</p>
 *
 * <p><b>未舗装では路面のグリップで割り戻す。</b>舗装路の煙は摩擦熱だが、砂や雪で舞うのは
 * 車輪が路面の材料を掻き飛ばしているからで、<b>滑りやすさとは関係なく舞う</b>。
 * 割り戻さないと氷や砂の上でほとんど何も出なくなる。</p>
 *
 * <p><b>パケットは要らない。</b>仕事率もサスの伸縮も同期してあるので、他人の車からも同じように出る。</p>
 *
 * <p><b>バニラの粒は 32 ブロックで打ち切られる。</b>{@code LevelRenderer#addParticle} が
 * カメラからの距離を見て捨てるためで、これだとマルチプレイで少し離れた車の煙が消える。
 * {@code force} を立てて打ち切りを外し、代わりに<b>車のモデルが描かれる範囲</b>
 * （{@link Entity#shouldRender(double, double, double)}）で自分で絞る。
 * この距離判定にカメラが要るのでクライアント側に置いてある。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarSmoke {

    /** 煙が出始める仕事率。車重×g で割った値 [m/s]。 */
    private static final double MIN_POWER = 2.5;
    /** 煙がいちばん濃くなる仕事率。同上。 */
    private static final double FULL_POWER = 8.0;
    /** これ以下の速度では出さない。 */
    private static final double MIN_SPEED = 1.0;
    /** いちばん濃いときに 1 輪から出す粒の数。 */
    private static final int MAX_PARTICLES = 4;

    private CarSmoke() {
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
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.getPosition();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car
                    && car.shouldRender(eye.x, eye.y, eye.z)) {
                spawn(car, level);
            }
        }
    }

    private static void spawn(CarEntity car, ClientLevel level) {
        double speed = Math.abs(car.getRenderSpeed());
        if (speed < MIN_SPEED) {
            return;
        }
        CarSpec spec = car.getSpec();
        RoadSurface surface = car.getRenderSurface();
        // 未舗装は滑りやすさに関わらず土砂が舞う
        double reference = spec.mass() * CarPhysics.GRAVITY
                * (surface == RoadSurface.PAVED ? 1.0 : surface.gripScale());

        double forwardX = -Math.sin(car.getYRot() * Mth.DEG_TO_RAD);
        double forwardZ = Math.cos(car.getYRot() * Mth.DEG_TO_RAD);
        double rightX = -forwardZ;
        double rightZ = forwardX;

        for (Wheel wheel : Wheel.VALUES) {
            double power = car.getRenderFrictionPower(wheel) / reference;
            double intensity = (power - MIN_POWER) / (FULL_POWER - MIN_POWER);
            if (intensity <= 0.0) {
                continue;
            }
            intensity = Math.min(1.0, intensity);
            int count = Math.max(1, (int) Math.ceil(intensity * MAX_PARTICLES));

            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);
            double x = car.getX() + forwardX * forwardOffset + rightX * rightOffset;
            double z = car.getZ() + forwardZ * forwardOffset + rightZ * rightOffset;
            // 接地面。サス長とタイヤ半径だけ下
            double y = car.getY() - car.getRenderSuspensionLength(wheel) - spec.wheelRadius();

            ParticleOptions particle = slipParticle(surface, level, x, y, z);
            for (int i = 0; i < count; i++) {
                double spread = (level.random.nextDouble() - 0.5) * 0.3;
                level.addParticle(particle, true,
                        x + spread, y + 0.1, z + spread,
                        // 車の進行方向と逆へ少し流し、濃いほど高く舞い上げる
                        -forwardX * speed * 0.05, 0.02 + 0.12 * intensity,
                        -forwardZ * speed * 0.05);
            }
        }
    }

    /** 路面に合った粒。舗装路なら白煙、それ以外は地面のブロックの粉塵。 */
    private static ParticleOptions slipParticle(RoadSurface surface, ClientLevel level,
                                                double x, double y, double z) {
        if (surface == RoadSurface.PAVED) {
            return ParticleTypes.CLOUD;
        }
        BlockPos below = BlockPos.containing(x, y - 0.2, z);
        BlockState state = level.getBlockState(below);
        if (state.isAir()) {
            return ParticleTypes.CLOUD;
        }
        return new BlockParticleOption(ParticleTypes.BLOCK, state);
    }
}
