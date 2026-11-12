package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;

/**
 * 滑っている車輪から煙を出す。
 *
 * <p>濃さは<b>その輪が接地面で捨てている摩擦の仕事率</b>（タイヤの力 × 滑り速度。
 * {@link EmitterCar#getRenderFrictionPower}）で決める。タイヤが煙を上げるのはゴムが
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
 * <p><b>粒は砂利道の土煙（{@code CarDust}）と同じ形で、その場に残って昇る</b>
 * （版の {@code DustParticle#TIRE_SMOKE}）。バニラの雲の粒は 1 秒ほどで消えるので、
 * どれだけ数を出しても「ちょろちょろ」にしか見えなかった。数は仕事率に比例させ、
 * ドリフトの後輪（実測 11.6）で頭打ちにする——サイドターン（6.1）はその半分弱、
 * 発進のホイールスピン（3.2）はまばら。</p>
 *
 * <p>未舗装で滑ったときは、同じ仕事率で<b>砂塵を上乗せ</b>する。走っているだけで出る
 * {@code CarDust} の上に、流しているぶんだけ濃くなる。</p>
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
 * （{@code Entity#shouldRender}）で自分で絞る。
 * この距離判定にカメラが要るので、車を集めるのは版の {@code CarSmoke}。</p>
 */
public final class SmokeEmitter {

    /** 煙が出始める仕事率。車重×g で割った値 [m/s]。 */
    private static final double MIN_POWER = 2.5;
    /**
     * 煙がいちばん多くなる仕事率。同上。
     *
     * <p>ドリフト中の後輪（実測 11.6）で頭打ちになる位置。ここより低くすると、
     * サイドターン（6.1）もドリフトも同じ量になって「擦るほど増える」が消える。</p>
     */
    private static final double FULL_POWER = 11.0;
    /** これ以下の速度では出さない。 */
    private static final double MIN_SPEED = 1.0;
    /** いちばん多いときに 1 輪から 1 ティックで出す粒の数。 */
    private static final double MAX_PER_WHEEL = 4.5;
    /** 未舗装で滑ったときに 1 輪から出す砂塵の数の上限。{@code CarDust} の上に足される。 */
    private static final double MAX_DUST_PER_WHEEL = 3.0;

    /** 車速 1 m/s ぶんで後ろへ弾かれる速さ [blocks/tick]。 */
    private static final double KICK_PER_SPEED = 0.008;
    /** 後ろへ弾かれる速さの上限 [blocks/tick]。 */
    private static final double MAX_KICK = 0.22;
    /** 体積の煙で、強さ 1 あたり後ろへ吐き出す速さ [blocks/tick]（空転するタイヤが煙を吐くぶん）。 */
    private static final double SPIN_KICK = 0.06;
    /** 向きの散らばり [blocks/tick]。 */
    private static final double JITTER = 0.035;
    /** 出た瞬間の上向きの速さ [blocks/tick]。 */
    private static final double LIFT_BASE = 0.02;
    /** 濃いほど高く跳ね上げるぶん [blocks/tick]。 */
    private static final double LIFT_PER_INTENSITY = 0.05;
    /** 出る位置の散らばり [m]。タイヤの幅ぶん。 */
    private static final double SPAWN_SPREAD = 0.25;

    private SmokeEmitter() {
    }

    /**
     * 1 台ぶん、1 ティックの粒を出す。
     *
     * @param xo      前のティックの位置（この 1 ティックで動いたぶんの間へ撒く）
     * @param raining 車の位置で雨が降っているか（濡れた未舗装は欠片だけ）
     */
    public static void spawn(EmitterCar car, double xo, double yo, double zo, boolean raining, ParticleSink sink) {
        double signedSpeed = car.getRenderSpeed();
        double speed = Math.abs(signedSpeed);
        // 止まったままのバーンアウトでも、体積の煙は出す。摩擦の仕事率（力 × 滑り速度）は止まっていても
        // 正しく出るので足切りは要らない。粒・土煙・欠片は今までどおり止まりかけでは出さない
        boolean crawling = speed < MIN_SPEED;
        boolean volumetric = sink.volumetric();
        if (crawling && !volumetric) {
            return;
        }
        CarSpec spec = car.getSpec();

        // 描画と同じ補間済みのヨー角。バニラの値は 1.4 度刻みに量子化されている
        float yaw = car.getRenderYaw(1.0F) * (float) (Math.PI / 180.0);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        // 進んでいる向きの逆へ弾き飛ばす。バックなら前へ飛ぶ
        double travel = signedSpeed >= 0.0 ? 1.0 : -1.0;
        double kick = Math.min(MAX_KICK, speed * KICK_PER_SPEED);
        // この 1 ティックで動いたぶん。粒をその間へ撒かないと、速いときに煙が点線になる
        double stepX = car.getX() - xo;
        double stepY = car.getY() - yo;
        double stepZ = car.getZ() - zo;

        for (Wheel wheel : Wheel.VALUES) {
            // 路面は輪ごとに見る。車全体の路面（いちばん滑りやすい輪）で決めると、
            // 前輪が土に乗った瞬間に、コンクリートの上で空転している後輪まで土煙になる
            RoadSurface surface = car.getRenderSurface(wheel);
            // 未舗装は滑りやすさに関わらず土砂が舞う
            double reference = spec.mass() * CarPhysics.GRAVITY
                    * (surface == RoadSurface.PAVED ? 1.0 : surface.gripScale());
            ParticleKind kind = kindOf(surface, raining);
            double power = car.getRenderFrictionPower(wheel) / reference;
            double intensity = (power - MIN_POWER) / (FULL_POWER - MIN_POWER);
            if (kind == ParticleKind.SMOKE && volumetric) {
                // 体積の煙（H メニューの既定）。滑りの強さ（頭打ちにしない）と、滑り続けて溜まった
                // タイヤの熱で、量・大きさ・濃さが増えていく。熱を冷ますため、滑っていない輪も毎ティック通す
                double strength = sink.smokeStrength(car.getId() * 4 + wheel.ordinal(), intensity);
                if (strength <= 0.0) {
                    continue;
                }
                double forwardOffset = spec.wheelForwardOffset(wheel);
                double rightOffset = spec.wheelRightOffset(wheel);
                double x = car.getX() + forwardX * forwardOffset + rightX * rightOffset;
                double z = car.getZ() + forwardZ * forwardOffset + rightZ * rightOffset;
                double y = car.getY() - car.getRenderSuspensionLength(wheel) - spec.wheelRadius();
                // 輪の外側へも吹き出す（車体の下へは潜らない）
                double side = rightOffset >= 0.0 ? 1.0 : -1.0;
                sink.emitSmoke(car.getId() * 4 + wheel.ordinal(), x, y, z, stepX, stepY, stepZ,
                        // 空転するタイヤは煙を後ろへ吐き出す。止まったままのバーンアウトでも後ろへ流れるよう、強さのぶん足す
                        -forwardX * travel * (kick + SPIN_KICK * Math.min(strength, 2.0)),
                        -forwardZ * travel * (kick + SPIN_KICK * Math.min(strength, 2.0)),
                        rightX * side, rightZ * side, strength);
                continue;
            }
            if (kind == ParticleKind.DUST && volumetric) {
                // 未舗装で滑ったぶんの砂塵（雪煙）を、走っているだけで出る CarDust の上へ上乗せする。
                // 止まったままの空転でも掻き上げる
                if (intensity <= 0.0) {
                    continue;
                }
                double amount = 1.7 * (1.0 - Math.exp(-1.1 * intensity)) * surface.dustScale();
                double forwardOffset = spec.wheelForwardOffset(wheel);
                double rightOffset = spec.wheelRightOffset(wheel);
                double x = car.getX() + forwardX * forwardOffset + rightX * rightOffset;
                double z = car.getZ() + forwardZ * forwardOffset + rightZ * rightOffset;
                double y = car.getY() - car.getRenderSuspensionLength(wheel) - spec.wheelRadius();
                double push = kick + SPIN_KICK * Math.min(amount, 2.0);
                // 輪ごとの番号は CarDust と分ける（出方のムラが同じ調子にならないように）
                sink.emitDust(1_000_000 + car.getId() * 4 + wheel.ordinal(), surface,
                        x, y, z, stepX, stepY, stepZ, -forwardX * travel * push, -forwardZ * travel * push, amount);
                continue;
            }
            if (intensity <= 0.0 || crawling) {
                continue;
            }
            intensity = Math.min(1.0, intensity);
            double rate = intensity * ClientConfig.dustDensity * switch (kind) {
                case SMOKE -> MAX_PER_WHEEL;
                case DUST -> MAX_DUST_PER_WHEEL * surface.dustScale();
                case DEBRIS -> 2.0;
            };
            int count = (int) rate;
            // 端数は確率で 1 つ。切り上げると、ごく軽く擦っただけでも毎ティック必ず出てしまう
            if (sink.nextDouble() < rate - count) {
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
            double y = car.getY() - car.getRenderSuspensionLength(wheel) - spec.wheelRadius();

            Object particle = switch (kind) {
                case SMOKE -> sink.smoke();
                case DUST -> sink.dust();
                case DEBRIS -> sink.debris(x, y, z);
            };
            double lift = LIFT_BASE + LIFT_PER_INTENSITY * intensity;
            for (int i = 0; i < count; i++) {
                double back = sink.nextDouble();
                double spreadX = (sink.nextDouble() - 0.5) * 2.0 * SPAWN_SPREAD;
                double spreadZ = (sink.nextDouble() - 0.5) * 2.0 * SPAWN_SPREAD;
                double xd = -forwardX * travel * kick + jitter(sink);
                double zd = -forwardZ * travel * kick + jitter(sink);
                sink.add(particle, true,
                        x - stepX * back + spreadX,
                        y - stepY * back + 0.1,
                        z - stepZ * back + spreadZ,
                        xd, lift, zd);
            }
        }
    }

    /** 滑った輪から何を出すか。 */
    private enum ParticleKind {
        /** 舗装路の白煙。 */
        SMOKE,
        /** 未舗装の砂塵。{@code CarDust} と同じ粒で、滑ったぶんだけ上乗せする。 */
        DUST,
        /** 雨で濡れた未舗装・氷。舞い上がらないので、ブロックの欠片が飛ぶだけ。 */
        DEBRIS
    }

    private static ParticleKind kindOf(RoadSurface surface, boolean raining) {
        if (surface == RoadSurface.PAVED) {
            return ParticleKind.SMOKE;
        }
        if (surface.dustScale() > 0.0 && !raining) {
            return ParticleKind.DUST;
        }
        return ParticleKind.DEBRIS;
    }

    private static double jitter(ParticleSink sink) {
        return (sink.nextDouble() - 0.5) * 2.0 * JITTER;
    }
}
