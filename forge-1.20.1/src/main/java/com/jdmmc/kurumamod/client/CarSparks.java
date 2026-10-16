package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;

/**
 * 車体が擦れた・ぶつかったところから火花を撒く。
 *
 * <p>擦れている点・壁の向き・擦れている速度・ぶつかった強さは、物理を解いている側（運転クライアント、
 * または無人の車を持つサーバー）が拾って {@code CarScrapePacket} で配る。運転者は自分の画面へ
 * 先に出す（衝突の手応えと同じ。{@code CarEntity#reportScrape}）。粒の量の設定（動画設定）は
 * {@code SimpleParticleType(false)} を通して尊重される。</p>
 *
 * <p>出し方は 2 つの足し算:</p>
 * <ul>
 *   <li><b>擦れ</b> … 擦れている間、毎ティック。数は擦れる速さで決まり、擦れる向きへ速さの一部を
 *       持ったまま、壁から外へ・上へ散る。車は粒より速く進むので、走っている人には後ろへ尾を引いて見える</li>
 *   <li><b>衝突</b> … ぶつかったティックにまとめて。<b>数も勢いもぶつかった強さ（近づいていた速さ）に
 *       比例する</b>。T ボーンのような強い衝突ほど多く、速く、遠くまで飛び散る。向きは当たった面に沿って
 *       周りへ（面の中へは飛ばない）</li>
 * </ul>
 */
public final class CarSparks {

    /** 擦れ始めでも必ず出す粒の数。1 粒ずつだと撫でた程度では見落とす。 */
    private static final int BASE_SPARKS = 3;
    /** 1 ティックに出す粒が 1 つ増える擦れの速さ [m/s]。 */
    private static final double SPEED_PER_SPARK = 1.5;
    /** 擦れで 1 ティックに出す粒の上限。 */
    private static final int MAX_SPARKS = 20;
    /** 擦れている速度のうち、粒が受け継ぐ割合（ばらつきの下限と上限）。 */
    private static final double CARRY_MIN = 0.3;
    private static final double CARRY_MAX = 0.85;
    /** 壁から外へ飛ぶ速さ [m/s]。 */
    private static final double OUTWARD = 2.5;
    /** 上へ飛ぶ速さ [m/s]。 */
    private static final double UPWARD = 3.5;
    /** 速さのばらつき [m/s]。 */
    private static final double SPREAD = 2.5;

    /** ぶつかった強さ 1m/s あたりに飛び散らせる粒の数。10m/s の T ボーンで 40 粒。 */
    private static final double BURST_PER_SPEED = 4.0;
    /** 衝突で飛び散らせる粒の上限。 */
    private static final int MAX_BURST = 90;
    /** 飛び散る速さを、ぶつかった強さの何倍にするか（ばらつきの下限と上限）。 */
    private static final double BURST_SPEED_MIN = 0.2;
    private static final double BURST_SPEED_MAX = 0.65;
    /** 飛び散る速さの上限 [m/s]。速すぎると一瞬で視界の外へ消える。 */
    private static final double BURST_SPEED_CAP = 14.0;
    /** 飛び散る粒が面の中へ向かってしまったとき、どこまで外へ折り返すか。 */
    private static final double BURST_REFLECT = 1.6;

    private CarSparks() {
    }

    /**
     * @param offset   擦れている（ぶつかった）点の、車の位置からのずれ（ワールドの向き） [m]。x, y, z
     * @param normal   当たった面から外への向き（水平）。床や天井なら 0。x, z
     * @param velocity 擦れている点が面に沿って動く速度 [m/s]。x, y, z
     * @param impact   このティックにぶつかった強さ [m/s]。擦っているだけなら 0
     */
    public static void emit(CarEntity car, float[] offset, float[] normal, float[] velocity, float impact) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || car.level() != level) {
            return;
        }
        RandomSource random = level.random;
        double x = car.getX() + offset[0];
        double y = car.getY() + offset[1];
        double z = car.getZ() + offset[2];

        double speed = Math.sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]);
        if (speed >= CarEntity.SPARK_MIN_SPEED) {
            int count = Math.min(MAX_SPARKS, BASE_SPARKS + (int) ((speed - CarEntity.SPARK_MIN_SPEED) / SPEED_PER_SPARK));
            for (int i = 0; i < count; i++) {
                double carry = CARRY_MIN + random.nextDouble() * (CARRY_MAX - CARRY_MIN);
                double out = OUTWARD * (0.3 + random.nextDouble());
                double vx = velocity[0] * carry + normal[0] * out + (random.nextDouble() - 0.5) * SPREAD;
                double vy = velocity[1] * carry + UPWARD * random.nextDouble() + (random.nextDouble() - 0.5) * SPREAD * 0.5;
                double vz = velocity[2] * carry + normal[1] * out + (random.nextDouble() - 0.5) * SPREAD;
                // 1 ティックで車が進むぶんの間へ散らす。1 点から出すと速いときに点線になる
                double along = random.nextDouble() * 0.05;
                spawn(level, x - velocity[0] * along, y - velocity[1] * along, z - velocity[2] * along, vx, vy, vz);
            }
        }

        if (impact > 0.0F) {
            burst(level, random, x, y, z, normal, velocity, impact);
        }
    }

    /** ぶつかった瞬間に、当たった面に沿って周りへ飛び散らせる。数も勢いも強さに比例する。 */
    private static void burst(ClientLevel level, RandomSource random, double x, double y, double z,
                              float[] normal, float[] velocity, float impact) {
        int count = Math.min(MAX_BURST, (int) Math.round(impact * BURST_PER_SPEED));
        boolean wall = normal[0] != 0.0F || normal[1] != 0.0F;
        for (int i = 0; i < count; i++) {
            // 水平のでたらめな向き。面の中へ向かったものは外へ折り返す
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            if (wall) {
                double into = dx * normal[0] + dz * normal[1];
                if (into < 0.0) {
                    dx -= into * BURST_REFLECT * normal[0];
                    dz -= into * BURST_REFLECT * normal[1];
                    double length = Math.hypot(dx, dz);
                    dx /= length;
                    dz /= length;
                }
            }
            double burstSpeed = Math.min(BURST_SPEED_CAP,
                    impact * (BURST_SPEED_MIN + random.nextDouble() * (BURST_SPEED_MAX - BURST_SPEED_MIN)));
            // 擦れていた向きにも少し流す（斜めに当たれば前へ多く散る）
            double carry = random.nextDouble() * CARRY_MIN;
            double vx = dx * burstSpeed + velocity[0] * carry;
            double vy = UPWARD * (0.3 + random.nextDouble()) + burstSpeed * 0.3 * random.nextDouble();
            double vz = dz * burstSpeed + velocity[2] * carry;
            spawn(level, x + (random.nextDouble() - 0.5) * 0.3, y + (random.nextDouble() - 0.5) * 0.2,
                    z + (random.nextDouble() - 0.5) * 0.3, vx, vy, vz);
        }
    }

    /** 速度 [m/s] で 1 粒出す。粒の速度は 1 ティックあたりなので 20 で割る。 */
    private static void spawn(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(KurumaParticles.SPARK.get(), x, y, z, vx / 20.0, vy / 20.0, vz / 20.0);
    }
}
