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
 * 未舗装路を走っている車が巻き上げる砂塵。
 *
 * <p><b>{@code CarSmoke} とは出どころが違う。</b>あちらはゴムが熱を持って上がる白煙なので
 * <b>滑らないと出ない</b>。こちらはタイヤが路面の材料そのものを掻き飛ばしているので、
 * <b>滑っていなくても、ただ速く走っているだけで舞う</b>。ラリーの土煙はこちら。</p>
 *
 * <table>
 *   <caption>2 つの効果の違い</caption>
 *   <tr><th></th><th>{@code CarSmoke}</th><th>{@code CarDust}（これ）</th></tr>
 *   <tr><td>出どころ</td><td>摩擦熱</td><td>路面の材料を掻き飛ばす</td></tr>
 *   <tr><td>出る条件</td><td>滑っているとき</td><td>走っているだけで</td></tr>
 *   <tr><td>路面</td><td>どこでも</td><td>未舗装だけ</td></tr>
 *   <tr><td>そのあと</td><td>すぐ消える</td><td>その場に残って昇る</td></tr>
 * </table>
 *
 * <p>掻き飛ばす量は<b>接地面が路面を撫でる速さ</b>で決める
 * （{@link CarEntity#getRenderScrubSpeed(Wheel)}）。転がって
 * いるだけなら車速そのもので、滑っていればそのぶん上乗せされる——だから<b>全開で流している
 * ほうが、同じ速度で真っ直ぐ走るより濃く舞う</b>。<b>同じ量が砂利の音
 * （{@code CarSound} の {@code GRAVEL}）も決めている</b>ので、見えている土煙と
 * 聞こえている音が食い違わない。これに路面の
 * {@linkplain RoadSurface#dustScale() 舞い上がりやすさ}と接地荷重を掛ける。</p>
 *
 * <p><b>グリップで決めてはいけない。</b>氷はいちばん滑るが固いので何も舞わず、砂はよく滑り
 * よく舞う。滑りやすさと舞いやすさは無関係なので、{@code RoadSurface} に別の数字として
 * 持たせてある（{@code CarSmoke} が摩擦の仕事率をグリップで割り戻しているのとは別の話）。</p>
 *
 * <p><b>濡れていると舞わない。</b>雨のダートは泥であって土煙ではない。
 * {@code Level#isRainingAt} は空が見えるかまで見てくれるので、<b>トンネルや橋の下では
 * 降っていても舞い続ける</b>。{@code CarEntity#getRenderWetness()} を使わないのは、
 * あれが<b>手元で解いている車でしか進まない</b>ため——他人の車が雨の中で土煙を上げてしまう。</p>
 *
 * <p>粒を絞る仕掛けは {@code CarSmoke} と同じ。バニラは 32 ブロックで粒を捨てるので
 * {@code force} で打ち切りを外し、代わりに<b>車のモデルが描かれる範囲</b>で自分で絞る。
 * その判定にカメラが要るのでクライアントのティックに置いてある。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarDust {

    /** これ以下の撫で速さでは舞わない [m/s]。徐行でダートを走っても土煙は立たない。 */
    private static final double MIN_SWEEP = 4.5;
    /** いちばん濃くなる撫で速さ [m/s]。 */
    private static final double FULL_SWEEP = 18.0;
    /** いちばん濃いときに 1 輪から 1 ティックで出す粒の数。 */
    private static final double MAX_PER_WHEEL = 2.0;
    /** 接地荷重の倍率の上限。荷重が乗った輪ほど深く掻くが、際限なくは増やさない。 */
    private static final double MAX_LOAD_FACTOR = 1.5;
    /** サスが伸びきっているとみなす余裕 [m]。ここまで伸びていれば浮いている。 */
    private static final double AIRBORNE_EPSILON = 0.001;

    /** 車速 1 m/s ぶんで後ろへ弾かれる速さ [blocks/tick]。 */
    private static final double KICK_PER_SPEED = 0.010;
    /** 後ろへ弾かれる速さの上限 [blocks/tick]。 */
    private static final double MAX_KICK = 0.30;
    /** 外側（フェンダーの外）へ散る速さ [blocks/tick]。 */
    private static final double SIDE_KICK = 0.04;
    /** 出た瞬間の上向きの速さ [blocks/tick]。 */
    private static final double LIFT_BASE = 0.03;
    /** 濃いほど高く跳ね上げるぶん [blocks/tick]。 */
    private static final double LIFT_PER_INTENSITY = 0.05;
    /** 向きの散らばり [blocks/tick]。 */
    private static final double JITTER = 0.02;
    /** 出る位置の散らばり [m]。 */
    private static final double SPAWN_SPREAD = 0.18;

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
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car && car.shouldRender(eye.x, eye.y, eye.z)) {
                spawn(car, level);
            }
        }
    }

    private static void spawn(CarEntity car, ClientLevel level) {
        RoadSurface surface = car.getRenderSurface();
        double dustScale = surface.dustScale();
        if (dustScale <= 0.0) {
            // 舗装路と氷。掻き飛ばすものが無い
            return;
        }
        if (level.isRainingAt(car.blockPosition())) {
            // 濡れたダートは泥。土煙は立たない
            return;
        }

        double signedSpeed = car.getRenderSpeed();
        double speed = Math.abs(signedSpeed);
        if (speed <= 0.0) {
            return;
        }
        CarSpec spec = car.getSpec();
        double forwardX = -Math.sin(car.getYRot() * Mth.DEG_TO_RAD);
        double forwardZ = Math.cos(car.getYRot() * Mth.DEG_TO_RAD);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        // 進んでいる向きの逆へ弾き飛ばす。バックなら前へ飛ぶ
        double travel = signedSpeed >= 0.0 ? 1.0 : -1.0;
        double kick = Math.min(MAX_KICK, speed * KICK_PER_SPEED);

        for (Wheel wheel : Wheel.VALUES) {
            double suspension = car.getRenderSuspensionLength(wheel);
            if (suspension >= spec.suspensionMaxLength() - AIRBORNE_EPSILON) {
                // 浮いている輪は路面に触れていないので何も掻かない
                continue;
            }
            double sweep = car.getRenderScrubSpeed(wheel);
            if (sweep <= MIN_SWEEP) {
                continue;
            }
            double base = Math.min(1.0, (sweep - MIN_SWEEP) / (FULL_SWEEP - MIN_SWEEP));
            double load = Math.min(MAX_LOAD_FACTOR,
                    car.getRenderWheelLoad(wheel) / spec.staticWheelLoad(wheel));
            double intensity = base * dustScale * load;
            double rate = intensity * MAX_PER_WHEEL * ClientConfig.dustDensity;
            int count = (int) rate;
            // 端数は確率で 1 つ。切り上げると、ごく薄いときでも必ず 1 つ出てしまう
            if (level.random.nextDouble() < rate - count) {
                count++;
            }
            if (count <= 0) {
                continue;
            }

            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);
            double x = car.getX() + forwardX * forwardOffset + rightX * rightOffset;
            double z = car.getZ() + forwardZ * forwardOffset + rightZ * rightOffset;
            // 接地面。サス長とタイヤ半径だけ下
            double y = car.getY() - suspension - spec.wheelRadius();

            double side = wheel.isLeft() ? -SIDE_KICK : SIDE_KICK;
            double lift = LIFT_BASE + LIFT_PER_INTENSITY * intensity;
            for (int i = 0; i < count; i++) {
                double spreadX = (level.random.nextDouble() - 0.5) * 2.0 * SPAWN_SPREAD;
                double spreadZ = (level.random.nextDouble() - 0.5) * 2.0 * SPAWN_SPREAD;
                double xd = -forwardX * travel * kick + rightX * side + jitter(level);
                double zd = -forwardZ * travel * kick + rightZ * side + jitter(level);
                level.addParticle(KurumaParticles.DUST.get(), true,
                        x + spreadX, y + 0.05, z + spreadZ,
                        xd, lift, zd);
            }
        }
    }

    private static double jitter(ClientLevel level) {
        return (level.random.nextDouble() - 0.5) * 2.0 * JITTER;
    }
}
