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
 * 車輪が掻き飛ばした砂塵と、タイヤが擦れて上げる白煙。
 *
 * <p>2 つは<b>同じ動かし方で、数字（{@link Look}）と絵だけが違う</b>。
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
public class DustParticle extends TextureSheetParticle {

    /**
     * 粒の性格。形（スプライト）と動かし方は共通で、数字だけが違う。
     *
     * @param rise           毎ティック上向きに足す速度 [blocks/tick]。浮いていくぶん
     * @param friction       毎ティック速度に掛ける減衰。空気に食われて初速がすぐ抜ける
     * @param minLifetime    いちばん短い寿命 [ティック]
     * @param lifetimeSpread 寿命の振れ幅 [ティック]
     * @param growth         寿命の終わりまでに大きさが何倍になるか（1 に足す量）
     * @param peakAlpha      いちばん濃いときの不透明度
     * @param fadePower      消えぎわの落ち方。大きいほど早く薄くなる
     * @param minSize        いちばん小さいときの大きさ [blocks]
     * @param maxSize        いちばん大きいときの大きさ [blocks]
     * @param groundColor    足元のブロックの色を取るか。取らなければ明るい灰色
     */
    public record Look(double rise, float friction, int minLifetime, int lifetimeSpread,
                       float growth, float peakAlpha, float fadePower,
                       float minSize, float maxSize, boolean groundColor) {
    }

    /**
     * 未舗装で掻き飛ばした砂塵。
     *
     * <p><b>不透明度を薄くしすぎると色が出ない。</b>見えている画素のほとんどが<b>後ろの空</b>に
     * なるので、粒がどんな色を持っていても画面には空の色が出る（＝土煙が青く見える）。
     * 寿命を通した平均は {@code peakAlpha / (fadePower + 1)} なので、数字よりずっと薄い。
     * 減衰を 2 にすると粒が 4 つ重なっても空の色に負ける（実測: 画面の R-B が -0.25）。
     * 1.5 なら -0.02 とほぼ中立になるが、見た目が悪くなったので 2 のままにしてある。</p>
     */
    public static final Look DUST = new Look(0.0032, 0.93F, 40, 36, 2.6F, 0.42F, 2.0F,
            0.35F, 1.1F, true);

    /**
     * 舗装路でタイヤが擦れて上げる白煙。
     *
     * <p>砂塵と同じく<b>その場に残って帯になる</b>のが肝。ドリフトで車が通り過ぎたあとに
     * 煙の壁が残るのはこれ。砂塵より<b>大きく・長く残り・よく昇る</b>（熱いので）。
     * 濃さは寿命の後ろまで残したいので落ち方を緩め（1.5）、そのぶん頂点も少し上げてある。</p>
     *
     * <p><b>絵は自前の白い {@code tire_smoke_*}</b>（{@code tools/make_particles.py} が描く）。
     * {@code big_smoke_*} は灰色なので白を指定しても半分の明るさにしかならず、白い
     * {@code generic_*} は形がドット絵の丸い塊で煙に見えなかった。形は big_smoke の流儀に
     * 合わせてあるが、<b>バニラの絵の塗り直しではない</b>（改変した絵を配ることになるため）。</p>
     */
    public static final Look TIRE_SMOKE = new Look(0.0055, 0.90F, 55, 55, 3.4F, 0.50F, 1.5F,
            0.55F, 1.4F, false);

    /** 速度 1 blocks/tick ぶんで足す大きさ。強く弾かれた粒ほど大きく舞う。 */
    private static final float SIZE_PER_SPEED = 1.6F;
    /** 出てから濃さが乗りきるまでのティック数。いきなり現れると点滅して見える。 */
    private static final float FADE_IN_TICKS = 4.0F;

    /** ブロックの色が引けなかったときの色。乾いた土の色。 */
    private static final int FALLBACK_COLOR = 0xA08050;
    /**
     * ブロックの色をどれだけ白へ寄せるか。舞い上がった粉は地面より明るく見えるため。
     *
     * <p><b>寄せすぎると色が消える。</b>これは明るさを上げる代わりに<b>彩度を落として</b>
     * いるので、砂のようにもともと淡い色は無彩色に近づき、後ろの空に染められてしまう。</p>
     */
    private static final float WHITEN = 0.42F;

    private final SpriteSet sprites;
    private final Look look;
    private final float baseSize;

    private DustParticle(ClientLevel level, double x, double y, double z,
                         double xd, double yd, double zd, float size, SpriteSet sprites, Look look) {
        // 速度を渡さない 4 引数のほうを使うこと。7 引数の Particle は
        // 渡した速度を大きく撹拌して上書きしてしまうので、狙った向きへ飛ばせない
        super(level, x, y, z);
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

        this.yd += this.look.rise();
        move(this.xd, this.yd, this.zd);
        this.xd *= this.friction;
        this.yd *= this.friction;
        this.zd *= this.friction;

        // 出るときは素早く、消えるときはゆっくり。切れ目が見えないように
        float life = (float) this.age / this.lifetime;
        float fadeIn = Math.min(1.0F, this.age / FADE_IN_TICKS);
        float fadeOut = (float) Math.pow(1.0F - life, this.look.fadePower());
        this.alpha = this.look.peakAlpha() * fadeIn * fadeOut;
    }

    /** 時間とともに膨らむ。{@code partialTick} を効かせないと 20Hz の階段になる。 */
    @Override
    public float getQuadSize(float partialTick) {
        float life = Math.min(1.0F, (this.age + partialTick) / this.lifetime);
        return this.baseSize * (1.0F + this.look.growth() * life);
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
     * 意味づけになるので都合がよい。粒の種類ごとに {@link Look} を渡して作る。</p>
     */
    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;
        private final Look look;

        public Provider(SpriteSet sprites, Look look) {
            this.sprites = sprites;
            this.look = look;
        }

        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level,
                                       double x, double y, double z,
                                       double xd, double yd, double zd) {
            double speed = Math.sqrt(xd * xd + yd * yd + zd * zd);
            float size = (float) Math.min(look.maxSize(), look.minSize() + speed * SIZE_PER_SPEED);
            return new DustParticle(level, x, y, z, xd, yd, zd, size, sprites, look);
        }
    }
}
