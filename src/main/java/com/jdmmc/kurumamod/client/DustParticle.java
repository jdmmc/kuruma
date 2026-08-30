package com.jdmmc.kurumamod.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * 車輪が掻き飛ばした砂塵。
 *
 * <p>{@code CarSmoke} の白煙と違って<b>その場に残る</b>のがこの粒の役目。車は自分が
 * 立てた砂塵から走り去っていくので、通ったあとに帯が残る——ラリーで前走車が見えなく
 * なるあれ。そのために、</p>
 *
 * <ul>
 *   <li><b>車の速度を引き継がない。</b>初速は「タイヤに弾かれたぶん」だけで、
 *       すぐ空気に食われて（{@link #FRICTION}）その場へ留まる</li>
 *   <li><b>寿命が長い</b>（{@link #MIN_LIFETIME} 〜 数秒）。バニラの雲の粒は 1 秒ほどで消える</li>
 *   <li><b>時間とともに膨らみ、薄くなる</b>。舞い上がった土煙は広がりながら消えていく</li>
 *   <li><b>ゆっくり昇る</b>（{@link #RISE}）。重力ではなく、掻き上げられた粒が浮いている状態</li>
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
public class DustParticle extends TextureSheetParticle {

    /** 毎ティック上向きに足す速度 [blocks/tick]。掻き上げられた粒が浮いていくぶん。 */
    private static final double RISE = 0.0032;
    /** 毎ティック速度に掛ける減衰。空気に食われて初速がすぐ抜ける。 */
    private static final float FRICTION = 0.93F;
    /** いちばん短い寿命 [ティック]。 */
    private static final int MIN_LIFETIME = 40;
    /** 寿命の振れ幅 [ティック]。 */
    private static final int LIFETIME_SPREAD = 36;
    /** 寿命の終わりまでに大きさが何倍になるか。 */
    private static final float GROWTH = 2.6F;
    /**
     * いちばん濃いときの不透明度。
     *
     * <p><b>薄くしすぎると色が出ない。</b>見えている画素のほとんどが<b>後ろの空</b>に
     * なるので、粒がどんな色を持っていても画面には空の色が出る（＝土煙が青く見える）。
     * 寿命を通した平均は {@code PEAK_ALPHA / (FADE_POWER + 1)} なので、ここの数字より
     * ずっと薄いことに注意。</p>
     */
    private static final float PEAK_ALPHA = 0.42F;
    /**
     * 消えぎわの落ち方。大きいほど早く薄くなる。
     *
     * <p>2 にすると寿命を通した平均の不透明度が {@code PEAK_ALPHA} の 3 分の 1 まで落ちて、
     * 粒が 4 つ重なっても空の色に負ける（実測: 画面の R-B が -0.25 と青いまま）。
     * 1.5 なら -0.02 とほぼ中立になる。</p>
     */
    private static final float FADE_POWER = 2.0F;
    /** 出てから濃さが乗りきるまでのティック数。いきなり現れると点滅して見える。 */
    private static final float FADE_IN_TICKS = 4.0F;

    /** ブロックの色が引けなかったときの色。乾いた土の色。 */
    private static final int FALLBACK_COLOR = 0xA08050;
    /**
     * ブロックの色をどれだけ白へ寄せるか。舞い上がった粉は地面より明るく見えるため。
     *
     * <p><b>寄せすぎると色が消える。</b>これは明るさを上げる代わりに<b>彩度を落として</b>
     * いるので、砂のようにもともと淡い色は無彩色に近づき、後ろの空に染められてしまう
     * （砂の生の色は青が赤より 34% 低いが、0.42 も寄せると 19% まで痩せる）。
     * 明るさはスプライトが白いこと（下記）で足りているので、ここは軽く掛けるだけでよい。</p>
     */
    private static final float WHITEN = 0.42F;

    private final SpriteSet sprites;
    private final float baseSize;

    private DustParticle(ClientLevel level, double x, double y, double z,
                         double xd, double yd, double zd, float size, SpriteSet sprites) {
        // 速度を渡さない 4 引数のほうを使うこと。7 引数の Particle は
        // 渡した速度を大きく撹拌して上書きしてしまうので、狙った向きへ飛ばせない
        super(level, x, y, z);
        this.sprites = sprites;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = FRICTION;
        this.lifetime = MIN_LIFETIME + this.random.nextInt(LIFETIME_SPREAD);
        this.quadSize = size;
        this.baseSize = size;
        this.alpha = 0.0F;
        this.roll = this.random.nextFloat() * (float) (Math.PI * 2.0);
        this.oRoll = this.roll;
        applyGroundColor(level, x, y, z);
        setSpriteFromAge(sprites);
    }

    /** 足元のブロックの色を、少し白へ寄せて粒の色にする。 */
    private void applyGroundColor(ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y - 0.15, z);
        BlockState state = level.getBlockState(pos);
        MapColor color = state.getMapColor(level, pos);
        int rgb = color == MapColor.NONE ? FALLBACK_COLOR : color.col;
        if (rgb == 0) {
            rgb = FALLBACK_COLOR;
        }
        // 同じ色ばかりだと板に見えるので、粒ごとに明るさを少し散らす
        float shade = 0.85F + this.random.nextFloat() * 0.25F;
        setColor(
                whiten(((rgb >> 16) & 0xFF) / 255.0F) * shade,
                whiten(((rgb >> 8) & 0xFF) / 255.0F) * shade,
                whiten((rgb & 0xFF) / 255.0F) * shade);
    }

    private static float whiten(float channel) {
        return channel + (1.0F - channel) * WHITEN;
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

        this.yd += RISE;
        move(this.xd, this.yd, this.zd);
        this.xd *= this.friction;
        this.yd *= this.friction;
        this.zd *= this.friction;

        // 出るときは素早く、消えるときはゆっくり。切れ目が見えないように
        float life = (float) this.age / this.lifetime;
        float fadeIn = Math.min(1.0F, this.age / FADE_IN_TICKS);
        float fadeOut = (float) Math.pow(1.0F - life, FADE_POWER);
        this.alpha = PEAK_ALPHA * fadeIn * fadeOut;
    }

    /** 時間とともに膨らむ。{@code partialTick} を効かせないと 20Hz の階段になる。 */
    @Override
    public float getQuadSize(float partialTick) {
        float life = Math.min(1.0F, (this.age + partialTick) / this.lifetime);
        return this.baseSize * (1.0F + GROWTH * life);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /**
     * 大きさは呼ぶ側が決める。
     *
     * <p>{@code ParticleOptions} には数字を 1 つも載せられない（{@code SimpleParticleType}）ので、
     * <b>速度の大きさから大きさを決めている</b>——強く弾き飛ばされた粒ほど大きく舞う、という
     * 意味づけになるので都合がよい。</p>
     */
    public static class Provider implements ParticleProvider<SimpleParticleType> {

        /** いちばん小さいときの大きさ [blocks]。 */
        private static final float MIN_SIZE = 0.35F;
        /** 速度 1 blocks/tick ぶんで足す大きさ。 */
        private static final float SIZE_PER_SPEED = 1.6F;
        /** いちばん大きいときの大きさ [blocks]。 */
        private static final float MAX_SIZE = 1.1F;

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level,
                                       double x, double y, double z,
                                       double xd, double yd, double zd) {
            double speed = Math.sqrt(xd * xd + yd * yd + zd * zd);
            float size = (float) Math.min(MAX_SIZE, MIN_SIZE + speed * SIZE_PER_SPEED);
            return new DustParticle(level, x, y, z, xd, yd, zd, size, sprites);
        }
    }
}
