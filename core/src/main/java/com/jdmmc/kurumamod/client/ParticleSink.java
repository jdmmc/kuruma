package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.surface.RoadSurface;

/**
 * 粒を置く口。煙・土煙・火花（{@link SmokeEmitter}・{@link DustEmitter}・{@link SparkEmitter}）が使い、
 * 版の {@code McParticleSink} が世界に置く。粒の種類は版の型（{@code ParticleOptions}）なので {@code Object} で渡す。
 */
public interface ParticleSink {

    /** 0 以上 1 未満の乱数（世界の乱数）。 */
    double nextDouble();

    /** タイヤの白煙の粒。 */
    Object smoke();

    /** 土煙の粒。 */
    Object dust();

    /** 火花の粒。 */
    Object spark();

    /** 足元のブロックの欠片（空気の上なら白煙で代用）。 */
    Object debris(double x, double y, double z);

    /**
     * 粒を 1 つ置く。速度は 1 ティックあたり。
     *
     * @param force 32 ブロックの打ち切りを外すか（{@code addParticle} の {@code force}）。外すときは呼ぶ側が
     *              描かれる範囲の車に絞ってある
     */
    void add(Object particle, boolean force, double x, double y, double z, double xd, double yd, double zd);

    /** 体積の煙で描くか（H メニューの「煙の描き方」。版の {@code TireSmokeVolume}）。 */
    boolean volumetric();

    /** 体積の煙の強さ。滑りの強さと、滑り続けて溜まったタイヤの熱から（熱を冷ますため、滑っていない輪も毎ティック通す）。 */
    double smokeStrength(int key, double intensity);

    /** 体積の煙を出す。 */
    void emitSmoke(int key, double x, double y, double z, double stepX, double stepY, double stepZ,
                   double pushX, double pushZ, double outX, double outZ, double strength);

    /** 体積の砂塵・雪煙を出す。 */
    void emitDust(int key, RoadSurface surface, double x, double y, double z, double stepX, double stepY,
                  double stepZ, double pushX, double pushZ, double amount);
}
