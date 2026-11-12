package com.jdmmc.kurumamod.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import com.jdmmc.kurumamod.client.effect.DustLook;

/**
 * 車輪が掻き飛ばした砂塵と、タイヤが擦れて上げる白煙。
 *
 * <p>2 つは<b>同じ動かし方で、数字（{@link DustLook}）と絵だけが違う</b>。
 * 砂利道の土煙の見え方が良かったので、舗装路の煙もそれに揃えた。</p>
 *
 * <p><b>その場に残る</b>のがこの粒の役目。車は自分が
 * 立てた砂塵から走り去っていくので、通ったあとに帯が残る——ラリーで前走車が見えなく
 * なるあれ。そのために、</p>
 *
 * <ul>
 *   <li><b>車の速度を引き継がない。</b>初速は「タイヤに弾かれたぶん」だけで、
 *       すぐ空気に食われてその場へ留まる</li>
 *   <li><b>寿命が長い</b>（2 秒 〜 5 秒）。バニラの雲の粒は 1 秒ほどで消える</li>
 *   <li><b>時間とともに膨らみ、薄くなる</b>。舞い上がった土煙は広がりながら消えていく</li>
 *   <li><b>ゆっくり昇る</b>。重力ではなく、掻き上げられた粒が浮いている状態</li>
 * </ul>
 *
 * <p><b>色は足元のブロックから取る。</b>{@code MapColor} を引いて白へ寄せるだけなので、
 * 赤い砂の上なら赤い土煙、雪の上なら白い雪煙になる。<b>色をコードや JSON で持たない</b>ので、
 * 他 MOD のブロックの上を走ってもそれらしい色で舞う。</p>
 *
 * <p>絵はバニラの {@code big_smoke_*} を借りている（{@code assets/kurumamod/particles/dust.json}）。
 * <b>このスプライトは白ではなく、可視部の RGB が 0.50/0.48/0.45 の中間灰色</b>。テクスチャの
 * 色はこちらの色に<b>掛かる</b>ので、指定した色は半分の明るさで出る。<b>それでもこれを
 * 使っている</b>——煙の形をしているのはこちらで、白い {@code generic_*}（バニラの CLOUD）に
 * 替えると色は正しく出るが<b>丸いぼやけた玉になって土煙に見えない</b>。
 * 暗いぶんは不透明度で補う。</p>
 *
 * <p><b>地形との当たりは見ない</b>（{@code hasPhysics = false}）。土煙が塀をすり抜けても
 * 見て分からないのに対し、数が出るぶん当たり判定の代金は高い。</p>
 */
public class DustParticle extends SingleQuadParticle {

    /** 砂塵（{@link DustLook#DUST}）。 */
    public static final DustLook DUST = DustLook.DUST;
    /** 白煙（{@link DustLook#TIRE_SMOKE}）。 */
    public static final DustLook TIRE_SMOKE = DustLook.TIRE_SMOKE;

    private final SpriteSet sprites;
    private final DustLook look;
    private final float baseSize;

    private DustParticle(ClientLevel level, double x, double y, double z,
                         double xd, double yd, double zd, float size, SpriteSet sprites, DustLook look) {
        // 速度を渡さない 4 引数のほうを使うこと。7 引数の Particle は
        // 渡した速度を大きく撹拌して上書きしてしまうので、狙った向きへ飛ばせない
        super(level, x, y, z, sprites.first());
        this.sprites = sprites;
        this.look = look;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = look.friction();
        this.lifetime = look.minLifetime() + this.random.nextInt(look.lifetimeSpread());
        this.quadSize = size;
        this.baseSize = size;
        this.alpha = 0.0F;
        this.roll = this.random.nextFloat() * (float) (Math.PI * 2.0);
        this.oRoll = this.roll;
        if (look.groundColor()) {
            applyGroundColor(level, x, y, z);
        } else {
            // 煙は無彩色。粒ごとに明るさだけ散らす
            float shade = 0.88F + this.random.nextFloat() * 0.12F;
            setColor(shade, shade, shade);
        }
        setSpriteFromAge(sprites);
    }

    /** 足元のブロックの色を、少し白へ寄せて粒の色にする。 */
    private void applyGroundColor(ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y - 0.15, z);
        BlockState state = level.getBlockState(pos);
        MapColor color = state.getMapColor(level, pos);
        int rgb = color == MapColor.NONE ? DustLook.FALLBACK_COLOR : color.col;
        if (rgb == 0) {
            rgb = DustLook.FALLBACK_COLOR;
        }
        // 同じ色ばかりだと板に見えるので、粒ごとに明るさを少し散らす
        float shade = 0.85F + this.random.nextFloat() * 0.25F;
        float[] tint = DustLook.groundTint(rgb, shade);
        setColor(tint[0], tint[1], tint[2]);
    }


    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            remove();
            return;
        }
        setSpriteFromAge(this.sprites);

        this.yd += this.look.rise();
        move(this.xd, this.yd, this.zd);
        this.xd *= this.friction;
        this.yd *= this.friction;
        this.zd *= this.friction;

        this.alpha = this.look.alpha(this.age, this.lifetime);
    }

    /** 時間とともに膨らむ。{@code partialTick} を効かせないと 20Hz の階段になる。 */
    @Override
    public float getQuadSize(float partialTick) {
        return this.look.quadSize(this.baseSize, this.age, partialTick, this.lifetime);
    }

    @Override
    protected SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }

    /**
     * 大きさは呼ぶ側が決める。
     *
     * <p>{@code ParticleOptions} には数字を 1 つも載せられない（{@code SimpleParticleType}）ので、
     * <b>速度の大きさから大きさを決めている</b>——強く弾き飛ばされた粒ほど大きく舞う、という
     * 意味づけになるので都合がよい。粒の種類ごとに {@link DustLook} を渡して作る。</p>
     */
    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;
        private final DustLook look;

        public Provider(SpriteSet sprites, DustLook look) {
            this.sprites = sprites;
            this.look = look;
        }

        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level,
                                       double x, double y, double z,
                                       double xd, double yd, double zd, RandomSource random) {
            float size = look.sizeFor(Math.sqrt(xd * xd + yd * yd + zd * zd));
            return new DustParticle(level, x, y, z, xd, yd, zd, size, sprites, look);
        }
    }
}
