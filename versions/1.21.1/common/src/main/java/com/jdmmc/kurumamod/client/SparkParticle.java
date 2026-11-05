package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 車体が壁と擦れて飛ぶ火花 1 粒（{@link CarSparks} が撒く）。
 *
 * <p><b>点ではなく、飛んでいる向きに伸びた筋として描く</b>（{@link #render}）。カメラを向いた四角の
 * ままだと小さな点の集まりにしか見えず、走りながらでは火花だと分からなかった。筋の長さは
 * 直前のティックに動いた距離 × {@link #STREAK_TICKS}。速く飛ぶほど長く、地面に落ちて止まれば
 * 短い点に縮む。絵（{@code textures/particle/spark.png}、{@code tools/make_particles.py} が描く）は
 * 先頭が太く濃く、尾へ向かって細く薄くなる。</p>
 *
 * <p><b>自分で光る。</b>明るさは周りに依らず最大（{@link #getLightColor}）なので、夜道やトンネルでも
 * 暗くならない。色は白っぽい黄色で飛び出し、冷えるにつれて橙から赤へ落ち、最後は薄れて消える。
 * 重力で落ち、ブロックに当たれば止まる（バニラの {@link Particle#tick} の当たり判定に任せる）。</p>
 */
public class SparkParticle extends TextureSheetParticle {

    /** 生まれたときの色（白っぽい黄色）。 */
    private static final float HOT_R = 1.0F;
    private static final float HOT_G = 0.96F;
    private static final float HOT_B = 0.72F;
    /** 冷えきったときの色（赤みの強い橙）。 */
    private static final float COLD_R = 1.0F;
    private static final float COLD_G = 0.35F;
    private static final float COLD_B = 0.08F;
    /** 最後のどれだけの割合で薄れていくか。 */
    private static final float FADE_SHARE = 0.4F;
    /** 筋の長さを、直前のティックに動いた距離の何倍にするか。 */
    private static final float STREAK_TICKS = 1.8F;
    /** 筋の長さの下限（幅の何倍か）。止まった粒も短い筋として見える。 */
    private static final float MIN_LENGTH_PER_WIDTH = 1.5F;

    /** 最後に動いた向き（単位ベクトル）。止まったあとも筋の向きを保つ。 */
    private float dirX;
    private float dirY = 1.0F;
    private float dirZ;

    protected SparkParticle(ClientLevel level, double x, double y, double z,
                            double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        // 速度は呼ぶ側が決めたものをそのまま使う（バニラの既定はばらつきを足してしまう）
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.gravity = 0.7F;
        this.friction = 0.96F;
        this.hasPhysics = true;
        this.lifetime = 10 + random.nextInt(11);
        // 筋の幅
        this.quadSize = 0.06F + random.nextFloat() * 0.04F;
        pickSprite(sprites);
        setColor(HOT_R, HOT_G, HOT_B);
        float length = Mth.sqrt((float) (xd * xd + yd * yd + zd * zd));
        if (length > 1.0e-4F) {
            dirX = (float) xd / length;
            dirY = (float) yd / length;
            dirZ = (float) zd / length;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) {
            return;
        }
        float t = Mth.clamp(age / (float) lifetime, 0.0F, 1.0F);
        setColor(Mth.lerp(t, HOT_R, COLD_R), Mth.lerp(t, HOT_G, COLD_G), Mth.lerp(t, HOT_B, COLD_B));
        setAlpha(Mth.clamp((1.0F - t) / FADE_SHARE, 0.0F, 1.0F));
    }

    /**
     * 先頭（いまの位置）から、動いてきた向きの後ろへ伸びる筋を描く。幅の向きは筋と視線の両方に
     * 垂直にとるので、どこから見ても筋の幅が見える。表裏の両方を出す（裏向きの面は描かれないため）。
     */
    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTick) {
        Vec3 eye = camera.getPosition();
        float hx = (float) (Mth.lerp(partialTick, xo, x) - eye.x);
        float hy = (float) (Mth.lerp(partialTick, yo, y) - eye.y);
        float hz = (float) (Mth.lerp(partialTick, zo, z) - eye.z);

        float mx = (float) (x - xo);
        float my = (float) (y - yo);
        float mz = (float) (z - zo);
        float moved = Mth.sqrt(mx * mx + my * my + mz * mz);
        if (moved > 1.0e-4F) {
            dirX = mx / moved;
            dirY = my / moved;
            dirZ = mz / moved;
        }
        float width = quadSize;
        float length = Math.max(moved * STREAK_TICKS, width * MIN_LENGTH_PER_WIDTH);
        float tx = hx - dirX * length;
        float ty = hy - dirY * length;
        float tz = hz - dirZ * length;

        // 幅の向き = 筋 × 視線（カメラが原点なので、先頭の位置がそのまま視線）
        float sx = dirY * hz - dirZ * hy;
        float sy = dirZ * hx - dirX * hz;
        float sz = dirX * hy - dirY * hx;
        float side = Mth.sqrt(sx * sx + sy * sy + sz * sz);
        if (side < 1.0e-5F) {
            // 真正面から筋を見ている。どの向きでも同じなので、横へとる
            sx = 1.0F;
            sy = 0.0F;
            sz = 0.0F;
            side = 1.0F;
        }
        float half = width * 0.5F / side;
        sx *= half;
        sy *= half;
        sz *= half;

        float u0 = getU0();
        float u1 = getU1();
        float v0 = getV0();
        float v1 = getV1();
        int light = getLightColor(partialTick);
        // 表
        vertex(buffer, hx + sx, hy + sy, hz + sz, u1, v0, light);
        vertex(buffer, hx - sx, hy - sy, hz - sz, u0, v0, light);
        vertex(buffer, tx - sx, ty - sy, tz - sz, u0, v1, light);
        vertex(buffer, tx + sx, ty + sy, tz + sz, u1, v1, light);
        // 裏
        vertex(buffer, tx + sx, ty + sy, tz + sz, u1, v1, light);
        vertex(buffer, tx - sx, ty - sy, tz - sz, u0, v1, light);
        vertex(buffer, hx - sx, hy - sy, hz - sz, u0, v0, light);
        vertex(buffer, hx + sx, hy + sy, hz + sz, u1, v0, light);
    }

    private void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, int light) {
        buffer.addVertex(x, y, z).setUv(u, v).setColor(rCol, gCol, bCol, alpha).setLight(light);
    }

    /** 自分で光るので、周りの明るさに依らず最大。 */
    @Override
    public int getLightColor(float partialTick) {
        return 0xF000F0;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level,
                                       double x, double y, double z,
                                       double xd, double yd, double zd) {
            return new SparkParticle(level, x, y, z, xd, yd, zd, sprites);
        }
    }
}
