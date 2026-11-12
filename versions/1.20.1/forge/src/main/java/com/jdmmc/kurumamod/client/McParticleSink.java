package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.particle.KurumaParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import com.jdmmc.kurumamod.surface.RoadSurface;

/** 粒を置く口（core の {@link ParticleSink}）。煙・土煙・火花（{@link SmokeEmitter} など）が使う。 */
final class McParticleSink implements ParticleSink {

    private final ClientLevel level;

    McParticleSink(ClientLevel level) {
        this.level = level;
    }

    @Override
    public double nextDouble() {
        return level.random.nextDouble();
    }

    @Override
    public Object smoke() {
        return KurumaParticles.TIRE_SMOKE.get();
    }

    @Override
    public Object dust() {
        return KurumaParticles.DUST.get();
    }

    @Override
    public Object spark() {
        return KurumaParticles.SPARK.get();
    }

    /** 足元のブロックの欠片。空気の上なら白煙で代用する。 */
    @Override
    public Object debris(double x, double y, double z) {
        BlockPos below = BlockPos.containing(x, y - 0.2, z);
        BlockState state = level.getBlockState(below);
        if (state.isAir()) {
            return KurumaParticles.TIRE_SMOKE.get();
        }
        return new BlockParticleOption(ParticleTypes.BLOCK, state);
    }

    @Override
    public void add(Object particle, boolean force, double x, double y, double z, double xd, double yd, double zd) {
        ParticleOptions options = (ParticleOptions) particle;
        if (force) {
            level.addParticle(options, true, x, y, z, xd, yd, zd);
        } else {
            level.addParticle(options, x, y, z, xd, yd, zd);
        }
    }

    @Override
    public boolean volumetric() {
        return TireSmokeVolume.usable();
    }

    @Override
    public double smokeStrength(int key, double intensity) {
        return TireSmokeVolume.strength(key, intensity);
    }

    @Override
    public void emitSmoke(int key, double x, double y, double z, double stepX, double stepY, double stepZ,
                          double pushX, double pushZ, double outX, double outZ, double strength) {
        TireSmokeVolume.emit(level, key, x, y, z, stepX, stepY, stepZ, pushX, pushZ, outX, outZ, strength);
    }

    @Override
    public void emitDust(int key, RoadSurface surface, double x, double y, double z, double stepX, double stepY,
                         double stepZ, double pushX, double pushZ, double amount) {
        TireSmokeVolume.emitDust(level, key, surface, x, y, z, stepX, stepY, stepZ, pushX, pushZ, amount);
    }
}
