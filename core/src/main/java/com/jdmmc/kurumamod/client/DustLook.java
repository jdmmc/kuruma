package com.jdmmc.kurumamod.client;

/**
 * 土煙・白煙の粒の性格（版の {@code DustParticle}）。形（スプライト）と動かし方は共通で、数字だけが違う。
 * 大きさ・濃さの移り変わりと、地面の色の混ぜ方もここで決める。
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
public record DustLook(double rise, float friction, int minLifetime, int lifetimeSpread,
                       float growth, float peakAlpha, float fadePower,
                       float minSize, float maxSize, boolean groundColor) {


    /**
     * 未舗装で掻き飛ばした砂塵。
     *
     * <p><b>不透明度を薄くしすぎると色が出ない。</b>見えている画素のほとんどが<b>後ろの空</b>に
     * なるので、粒がどんな色を持っていても画面には空の色が出る（＝土煙が青く見える）。
     * 寿命を通した平均は {@code peakAlpha / (fadePower + 1)} なので、数字よりずっと薄い。
     * 減衰を 2 にすると粒が 4 つ重なっても空の色に負ける（実測: 画面の R-B が -0.25）。
     * 1.5 なら -0.02 とほぼ中立になるが、見た目が悪くなったので 2 のままにしてある。</p>
     */
    public static final DustLook DUST = new DustLook(0.0032, 0.93F, 40, 36, 2.6F, 0.42F, 2.0F,
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
    public static final DustLook TIRE_SMOKE = new DustLook(0.0055, 0.90F, 55, 55, 3.4F, 0.50F, 1.5F,
            0.55F, 1.4F, false);

    /** 速度 1 blocks/tick ぶんで足す大きさ。強く弾かれた粒ほど大きく舞う。 */
    private static final float SIZE_PER_SPEED = 1.6F;
    /** 出てから濃さが乗りきるまでのティック数。いきなり現れると点滅して見える。 */
    private static final float FADE_IN_TICKS = 4.0F;

    /** ブロックの色が引けなかったときの色。乾いた土の色。 */
    public static final int FALLBACK_COLOR = 0xA08050;
    /**
     * ブロックの色をどれだけ白へ寄せるか。舞い上がった粉は地面より明るく見えるため。
     *
     * <p><b>寄せすぎると色が消える。</b>これは明るさを上げる代わりに<b>彩度を落として</b>
     * いるので、砂のようにもともと淡い色は無彩色に近づき、後ろの空に染められてしまう。</p>
     */
    private static final float WHITEN = 0.42F;

    /** 出たときの大きさ。強く弾かれた粒ほど大きく舞う。 */
    public float sizeFor(double speed) {
        return (float) Math.min(maxSize, minSize + speed * SIZE_PER_SPEED);
    }

    /** その時点の濃さ。出るときは素早く、消えるときはゆっくり（切れ目が見えないように）。 */
    public float alpha(int age, int lifetime) {
        float life = (float) age / lifetime;
        float fadeIn = Math.min(1.0F, age / FADE_IN_TICKS);
        float fadeOut = (float) Math.pow(1.0F - life, fadePower);
        return peakAlpha * fadeIn * fadeOut;
    }

    /** 時間とともに膨らむ。{@code partialTick} を効かせないと 20Hz の階段になる。 */
    public float quadSize(float baseSize, int age, float partialTick, int lifetime) {
        float life = Math.min(1.0F, (age + partialTick) / lifetime);
        return baseSize * (1.0F + growth * life);
    }

    /**
     * 足元のブロックの色を、少し白へ寄せて粒の色にする {r, g, b}。
     *
     * @param rgb   ブロックの地図の色。引けなければ {@link #FALLBACK_COLOR}
     * @param shade 粒ごとに散らす明るさ（同じ色ばかりだと板に見える）
     */
    public static float[] groundTint(int rgb, float shade) {
        return new float[]{
                whiten(((rgb >> 16) & 0xFF) / 255.0F) * shade,
                whiten(((rgb >> 8) & 0xFF) / 255.0F) * shade,
                whiten((rgb & 0xFF) / 255.0F) * shade};
    }

    private static float whiten(float channel) {
        return channel + (1.0F - channel) * WHITEN;
    }
}
