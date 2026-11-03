package com.jdmmc.kurumamod.client;

import java.util.SplittableRandom;

/**
 * タイヤの煙の塊（パフ）の群れ。<b>動かし方だけ</b>を持ち、Minecraft に依存しない
 * （描くのは版ごとの {@code TireSmokeVolume}）。時間はティック、長さはブロック（= m）。
 *
 * <h2>塊 1 つの一生</h2>
 * <ul>
 *   <li><b>生まれる</b> … 接地面のすぐ上に、半径 30〜45cm の濃い塊として。タイヤに弾かれた初速を持つ</li>
 *   <li><b>膨らむ</b> … 最初の 1.5 秒ほどで一気に広がり（{@link #GROWTH_TICKS}）、そのあともゆっくり広がり続ける。
 *       最後は半径 1.3〜3m</li>
 *   <li><b>薄まる</b> … 中身（煙の量）は増えないので、膨らんだぶん薄まる。体積どおり（半径の 3 乗）に
 *       薄めると 1 秒で消えてしまうので、{@link #DILUTION_POWER} 乗で薄める。重なり合った塊の帯が
 *       煙の壁として残るのはこのため</li>
 *   <li><b>昇る・流れる</b> … 熱いうちは速く昇り、冷えると風に流されるだけになる。速度は空気に食われて
 *       （{@link #DRAG}）「昇る速さ＋風」へ寄っていく。<b>車の速度は引き継がない</b>（その場に残って帯になる）</li>
 *   <li><b>巻き込まれる</b> … 近くを走る車の後ろへ引きずられる（{@link #WAKE_RADIUS}）。煙の中を車が
 *       抜けると、煙が車に付いていって渦を巻く</li>
 * </ul>
 *
 * <p>数の上限（{@link #setCapacity}）を超えたら、いちばん消えかけの塊を新しい塊で置き換える。
 * 古い煙から消えるので、上限に当たっても目の前の煙は欠けない。</p>
 */
public final class SmokePuffs {

    /**
     * 塊の素材。動かし方の数字だけが違い、描き方（シェーダー）は同じ。色は塊ごとに呼ぶ側が渡す
     * （土煙は足元のブロックの色）。
     *
     * @param rise       昇る速さの倍率。煙は熱いので昇る。砂塵や雪はほとんど昇らない
     * @param settle     落ちてくる速さ [m/tick]。重い粒は掻き上げられた後ゆっくり沈む（年齢とともに効いてくる）
     * @param extinction 濃さの倍率
     * @param size       膨らみきったときの大きさの倍率
     * @param life       寿命の倍率
     * @param flatTicks  縦に潰れきるまでの年齢 [tick]。小さいほど早く地面に沿って平たく広がる
     * @param turbulence 乱流の倍率
     */
    public enum Material {
        /** タイヤが擦れて上げる白煙。 */
        SMOKE(1.0, 0.0, 1.0, 1.0, 1.0, 220.0, 1.0),
        /** 土・砂・砂利を掻き飛ばした砂塵。昇らずに低く広がり、ゆっくり沈む。 */
        DUST(0.3, 0.006, 0.85, 0.95, 0.8, 110.0, 0.8),
        /** 雪煙。軽く舞って、沈みながら散る。 */
        SNOW(0.3, 0.004, 0.9, 0.85, 0.6, 140.0, 1.2);

        private final double rise;
        private final double settle;
        private final double extinction;
        private final double size;
        private final double life;
        private final double flatTicks;
        private final double turbulence;

        Material(double rise, double settle, double extinction, double size, double life,
                 double flatTicks, double turbulence) {
            this.rise = rise;
            this.settle = settle;
            this.extinction = extinction;
            this.size = size;
            this.life = life;
            this.flatTicks = flatTicks;
            this.turbulence = turbulence;
        }
    }

    /** 生まれたときの半径 [m]。 */
    private static final double MIN_START_RADIUS = 0.26;
    private static final double START_RADIUS_SPREAD = 0.26;
    /** 煙の強さの上限。 */
    private static final double MAX_INTENSITY = 2.5;
    /** 膨らみきったときの半径 [m]（強さで 0.75 倍〜。強さ 2 で 1.65 倍）。 */
    private static final double MIN_FINAL_RADIUS = 1.3;
    private static final double FINAL_RADIUS_SPREAD = 1.1;
    /** 一気に膨らむ時間の目安 [tick]。塊ごとに散らす。 */
    private static final double MIN_GROWTH_TICKS = 20.0;
    private static final double GROWTH_TICKS_SPREAD = 22.0;
    /**
     * そのあともゆっくり広がり続ける速さ [m/tick]（10 秒で 2.4m）。煙は時間とともに薄く広く拡散していく。
     * 0.005 では膨らみきった後の塊がその大きさのまま漂い、輪郭のある玉が残って見えた。
     */
    private static final double LATE_GROWTH = 0.012;
    /** 寿命 [tick]（設定の「煙が残る長さ」が掛かる）。 */
    private static final double MIN_LIFE = 110.0;
    private static final double LIFE_SPREAD = 90.0;
    /** 生まれたときの消散係数 [1/m]。強さで 0.45 倍〜（強さ 1 で 1 倍、2 で 1.55 倍）。 */
    private static final double START_EXTINCTION = 3.2;
    /** 膨らんだぶん薄まる割合の指数。3 なら体積どおり。大きいほど広がりながら早く霞になる。 */
    private static final double DILUTION_POWER = 1.9;
    /** 出てから濃さが乗りきるまで [tick]。 */
    private static final double FADE_IN = 3.0;
    /** 寿命の最後のこの割合で消えていく。 */
    private static final double FADE_OUT_SHARE = 0.45;
    /** 毎ティック、速度を目標へ寄せる割合（空気の抵抗）。 */
    private static final double DRAG = 0.07;
    /** 昇る速さ [m/tick]。冷めた後と、生まれたての上乗せぶん。 */
    private static final double RISE = 0.010;
    private static final double HOT_RISE = 0.028;
    private static final double HOT_TICKS = 35.0;
    /**
     * 乱流。塊ごとに<b>ゆっくりさまよう</b>速度を持たせる（毎ティック前の値を {@link #TURBULENCE_MEMORY} だけ残し、
     * 乱数を少し足す）。毎ティック独立に揺らすと、煙が流れるのではなく震えて見える。
     * 定常での速さの目安は {@code TURBULENCE / sqrt(1 - MEMORY²)} ≒ 0.017 m/tick（0.35 m/s）。
     */
    private static final double TURBULENCE = 0.0068;
    private static final double TURBULENCE_MEMORY = 0.92;
    /** 乱流がこの年齢 [tick] ごとに 1 倍ずつ強くなる。 */
    private static final double SPREAD_TICKS = 80.0;
    /** 走っている車がこの距離 [m] まで煙を引きずる。 */
    private static final double WAKE_RADIUS = 3.2;
    private static final double WAKE_GAIN = 0.10;
    /** 地面にこれ（半径の割合）より沈まない。 */
    private static final double GROUND_CLEARANCE = 0.35;

    private final SplittableRandom random = new SplittableRandom(0x5EED5EEDL);

    private int capacity = 800;
    private int count;
    private double[] x = new double[0];
    private double[] y = new double[0];
    private double[] z = new double[0];
    private double[] oldX = new double[0];
    private double[] oldY = new double[0];
    private double[] oldZ = new double[0];
    private double[] velocityX = new double[0];
    private double[] velocityY = new double[0];
    private double[] velocityZ = new double[0];
    private double[] ground = new double[0];
    private float[] age = new float[0];
    private float[] life = new float[0];
    private float[] startRadius = new float[0];
    private float[] finalRadius = new float[0];
    private float[] extinction = new float[0];
    private float[] seed = new float[0];
    /** 色（反射率）。 */
    private float[] red = new float[0];
    private float[] green = new float[0];
    private float[] blue = new float[0];
    /** 素材ごとの動き。落ちてくる速さ [m/tick]、縦に潰れきるまでの年齢 [tick]、乱流の倍率。 */
    private float[] settle = new float[0];
    private float[] flatTicks = new float[0];
    private float[] turbulenceScale = new float[0];
    /** 塊ごとのばらつき。膨らむ速さ [tick] と昇る速さの倍率。 */
    private float[] growth = new float[0];
    private float[] riseScale = new float[0];
    /** さまよう速度 [m/tick]。 */
    private double[] turbulenceX = new double[0];
    private double[] turbulenceY = new double[0];
    private double[] turbulenceZ = new double[0];
    /** 呼ぶ側が書く明るさ（ブロック 0..15、空 0..15、日の当たり 0..1）。 */
    private float[] blockLight = new float[0];
    private float[] skyLight = new float[0];
    private float[] sunVisibility = new float[0];
    /** 帯全体での陰り（{@link #shadeTrail}）。日の方向・上方向に他の塊がどれだけあるか。 */
    private float[] sunOcclusion = new float[0];
    private float[] ambientOcclusion = new float[0];
    /** {@link #shadeTrail} の格子。セルの番号 → そのセルの平均の消散係数 [1/m]。 */
    private final java.util.HashMap<Long, double[]> grid = new java.util.HashMap<>();

    public int count() {
        return count;
    }

    public void clear() {
        count = 0;
    }

    /** 数の上限。下げたときは消えかけのものから捨てる。 */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(16, capacity);
        while (count > this.capacity) {
            remove(mostFaded());
        }
    }

    /**
     * 塊を 1 つ足す。
     *
     * @param groundY       接地面の高さ。塊はこれより（半径の一部ぶん）下へは沈まない
     * @param intensity     煙の強さ（滑りの強さとタイヤの熱から。0..2.5。1 がドリフトの冷えたタイヤくらい）。
     *                      大きいほど大きく・濃く・勢いよく昇る
     * @param lifetimeScale 寿命の倍率
     * @param material      素材（動かし方）
     * @param r             色（反射率 0..1）
     * @return 足した塊の番号（明るさを書くため）
     */
    public int add(double px, double py, double pz, double vx, double vy, double vz,
                   double groundY, double intensity, double lifetimeScale,
                   Material material, float r, float g, float b) {
        int i;
        if (count >= capacity) {
            i = mostFaded();
        } else {
            ensureArrays(count + 1);
            i = count++;
        }
        intensity = Math.max(0.0, Math.min(MAX_INTENSITY, intensity));
        double r0 = MIN_START_RADIUS + random.nextDouble() * START_RADIUS_SPREAD;
        x[i] = px;
        y[i] = Math.max(py, groundY + r0 * GROUND_CLEARANCE);
        z[i] = pz;
        oldX[i] = x[i];
        oldY[i] = y[i];
        oldZ[i] = z[i];
        velocityX[i] = vx;
        velocityY[i] = vy;
        velocityZ[i] = vz;
        ground[i] = groundY;
        age[i] = 0.0F;
        life[i] = (float) ((MIN_LIFE + random.nextDouble() * LIFE_SPREAD) * lifetimeScale * material.life);
        startRadius[i] = (float) r0;
        finalRadius[i] = (float) ((MIN_FINAL_RADIUS + random.nextDouble() * FINAL_RADIUS_SPREAD)
                * (0.75 + 0.45 * intensity) * material.size);
        // 濃さ・膨らむ速さ・昇る速さを塊ごとに散らす。揃っていると同じ形の玉が並んで見える
        extinction[i] = (float) (START_EXTINCTION * (0.45 + 0.55 * intensity) * (0.65 + 0.7 * random.nextDouble())
                * material.extinction);
        growth[i] = (float) (MIN_GROWTH_TICKS + random.nextDouble() * GROWTH_TICKS_SPREAD);
        // 強い煙ほど熱いので勢いよく昇る
        riseScale[i] = (float) ((0.6 + 0.8 * random.nextDouble()) * (0.8 + 0.25 * intensity) * material.rise);
        settle[i] = (float) (material.settle * (0.7 + 0.6 * random.nextDouble()));
        flatTicks[i] = (float) material.flatTicks;
        turbulenceScale[i] = (float) material.turbulence;
        turbulenceX[i] = gaussian() * TURBULENCE * 2.5;
        turbulenceY[i] = gaussian() * TURBULENCE;
        turbulenceZ[i] = gaussian() * TURBULENCE * 2.5;
        seed[i] = (float) random.nextDouble();
        // 塊ごとに明るさだけ少し散らす
        float shade = (float) (0.9 + random.nextDouble() * 0.1);
        red[i] = r * shade;
        green[i] = g * shade;
        blue[i] = b * shade;
        blockLight[i] = 0.0F;
        skyLight[i] = 15.0F;
        sunVisibility[i] = 1.0F;
        sunOcclusion[i] = 1.0F;
        ambientOcclusion[i] = 1.0F;
        return i;
    }

    /**
     * 1 ティック進める。
     *
     * @param windX    風 [m/tick]
     * @param cars     近くを走る車。x, y, z, vx, vy, vz [m/tick] の 6 個ずつ
     * @param carCount 車の台数
     */
    public void tick(double windX, double windZ, double[] cars, int carCount) {
        if (++ticks % MERGE_INTERVAL == 0) {
            merge();
        }
        int i = 0;
        while (i < count) {
            age[i] += 1.0F;
            if (age[i] >= life[i]) {
                remove(i);
                continue;
            }
            oldX[i] = x[i];
            oldY[i] = y[i];
            oldZ[i] = z[i];
            // 昇る速さ。重い粒（砂塵・雪）は掻き上げられた後、年齢とともに沈んでくる
            double rise = (RISE + HOT_RISE * Math.exp(-age[i] / HOT_TICKS)) * riseScale[i]
                    - settle[i] * (1.0 - Math.exp(-age[i] / HOT_TICKS));
            // さまよう速度。上下は横より弱い（煙は横へ広がる）
            // 古いほど大きな渦に乗って散っていく（出たての塊はまとまって、時間とともに帯の形が崩れる）
            double turbulence = TURBULENCE * turbulenceScale[i] * (1.0 + age[i] / SPREAD_TICKS);
            turbulenceX[i] = turbulenceX[i] * TURBULENCE_MEMORY + gaussian() * turbulence;
            turbulenceY[i] = turbulenceY[i] * TURBULENCE_MEMORY + gaussian() * turbulence * 0.5;
            turbulenceZ[i] = turbulenceZ[i] * TURBULENCE_MEMORY + gaussian() * turbulence;
            double vx = velocityX[i] + (windX + turbulenceX[i] - velocityX[i]) * DRAG * 2.0;
            double vy = velocityY[i] + (rise + turbulenceY[i] - velocityY[i]) * DRAG;
            double vz = velocityZ[i] + (windZ + turbulenceZ[i] - velocityZ[i]) * DRAG * 2.0;
            for (int c = 0; c < carCount; c++) {
                int o = c * 6;
                double dx = x[i] - cars[o];
                double dy = y[i] - cars[o + 1];
                double dz = z[i] - cars[o + 2];
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= WAKE_RADIUS * WAKE_RADIUS) {
                    continue;
                }
                double weight = (1.0 - Math.sqrt(d2) / WAKE_RADIUS) * WAKE_GAIN;
                vx += (cars[o + 3] - vx) * weight;
                vy += (cars[o + 4] - vy) * weight * 0.3;
                vz += (cars[o + 5] - vz) * weight;
            }
            velocityX[i] = vx;
            velocityY[i] = vy;
            velocityZ[i] = vz;
            x[i] += vx;
            y[i] += vy;
            z[i] += vz;
            double floor = ground[i] + radius(i, 0.0F) * GROUND_CLEARANCE;
            if (y[i] < floor) {
                y[i] = floor;
                velocityY[i] = Math.max(velocityY[i], 0.0);
            }
            i++;
        }
    }

    /** 描く位置（ティックの間を補間）。 */
    public double renderX(int i, float partialTick) {
        return oldX[i] + (x[i] - oldX[i]) * partialTick;
    }

    public double renderY(int i, float partialTick) {
        return oldY[i] + (y[i] - oldY[i]) * partialTick;
    }

    public double renderZ(int i, float partialTick) {
        return oldZ[i] + (z[i] - oldZ[i]) * partialTick;
    }

    /** 明るさを調べる位置（ティックの終わり）。 */
    public double x(int i) {
        return x[i];
    }

    public double y(int i) {
        return y[i];
    }

    public double z(int i) {
        return z[i];
    }

    /** 半径 [m]。 */
    public double radius(int i, float partialTick) {
        double t = age[i] + partialTick;
        double r0 = startRadius[i];
        return r0 + (finalRadius[i] - r0) * (1.0 - Math.exp(-t / growth[i])) + LATE_GROWTH * t;
    }

    /**
     * 縦にどれだけ潰れているか（0..0.35）。古い煙ほど地面に沿って横へ広がる。シェーダーは縦の座標を
     * {@code 1 + これ} 倍して読むので、球の中で平たい形になる。
     */
    public float flatten(int i) {
        return Math.min(0.35F, age[i] / flatTicks[i] * 0.35F);
    }

    /** 消散係数 [1/m]（中心の濃さ）。膨らんだぶん薄まり、出始めと終わりで消える。 */
    public double extinction(int i, float partialTick) {
        double t = age[i] + partialTick;
        double r = radius(i, partialTick);
        double dilution = Math.pow(startRadius[i] / r, DILUTION_POWER);
        double fadeIn = Math.min(1.0, t / FADE_IN);
        double remaining = Math.max(0.0, Math.min(1.0, (1.0 - t / life[i]) / FADE_OUT_SHARE));
        double fadeOut = remaining * remaining * (3.0 - 2.0 * remaining);
        return extinction[i] * dilution * fadeIn * fadeOut;
    }

    /** 年齢 [秒]。 */
    public double ageSeconds(int i, float partialTick) {
        return (age[i] + partialTick) * 0.05;
    }

    public float seed(int i) {
        return seed[i];
    }

    /** 色（反射率）。 */
    public float red(int i) {
        return red[i];
    }

    public float green(int i) {
        return green[i];
    }

    public float blue(int i) {
        return blue[i];
    }

    public void setLight(int i, float block, float sky, float sun) {
        blockLight[i] = block;
        skyLight[i] = sky;
        sunVisibility[i] = sun;
    }

    public float blockLight(int i) {
        return blockLight[i];
    }

    public float skyLight(int i) {
        return skyLight[i];
    }

    public float sunVisibility(int i) {
        return sunVisibility[i];
    }

    /**
     * 煙の出方のムラ（0.35..1.65 くらい、平均 1）。輪ごと（{@code stream}）に、時間 [tick] でなめらかに変わる。
     * 滑っている間ずっと同じ量が出ると、帯が均一なホースの水のように見える。実際の煙は「ぷわっ、ぷわっ」と
     * 脈打って出るので、出す数にこれを掛ける。0.6 秒ほどの周期と、それより速い揺らぎの 2 つを重ねる。
     */
    public static double gust(int stream, double tick) {
        return 0.35 + 1.3 * (0.65 * valueNoise(stream * 31 + 7, tick / 12.0) + 0.35 * valueNoise(stream * 17 + 3, tick / 4.0));
    }

    /** 1 次元のなめらかな乱数（0..1）。 */
    private static double valueNoise(int stream, double t) {
        long cell = (long) Math.floor(t);
        double f = t - cell;
        f = f * f * (3.0 - 2.0 * f);
        return lerp(hash01(stream, cell), hash01(stream, cell + 1), f);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double hash01(int stream, long cell) {
        long h = cell * 0x9E3779B97F4A7C15L + stream * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 帯の陰りを見る格子の一辺 [m]。塊の大きさ（半径 1〜3m）と同じくらい。 */
    private static final double SHADE_CELL = 2.5;
    /** 日の方へ辿る歩数（{@link #SHADE_CELL} ずつ）。 */
    private static final int SHADE_STEPS = 4;
    /** 日の光・空の明るさの陰りの効き。1 で見積もった濃さどおり。帯の外側の塊からの回り込みのぶん弱めてある。 */
    private static final double SUN_OCCLUSION = 0.5;
    private static final double AMBIENT_OCCLUSION = 0.5;
    /** 塊の濃さの分布（中心ほど濃い {@code (1 - q²)²}）を球の体積で平均した割合（8/35）。 */
    private static final double PROFILE_MEAN = 8.0 / 35.0;

    /**
     * 帯全体での陰りを求める。<b>塊ごとの自己影（シェーダー）だけだと、何百もの塊が重なった帯が
     * 一様に明るく、のっぺりした壁に見える。</b>実際の煙は日の当たる側が明るく、帯の奥と下は暗い。
     *
     * <p>塊の煙の量を格子へ積み（セルごとの平均の消散係数）、各塊から日の方向へ数セル辿って、
     * <b>自分以外の塊</b>がどれだけ光を遮るかを見る。上方向にも辿って、空の明るさの陰り
     * （帯の下ほど暗い）にする。どちらも O(塊の数)。数ティックに 1 回で十分（塊はゆっくり動く）。</p>
     *
     * @param sunX 日（夜は月）の向き（単位ベクトル）
     */
    public void shadeTrail(double sunX, double sunY, double sunZ) {
        grid.clear();
        for (int i = 0; i < count; i++) {
            double[] cell = grid.computeIfAbsent(cellKey(x[i], y[i], z[i]), key -> new double[1]);
            cell[0] += cellExtinction(i);
        }
        for (int i = 0; i < count; i++) {
            double own = cellExtinction(i);
            // 日の方へ。最初のセルは自分のセル（自分のぶんは除く。それはシェーダーが球として解く）
            double toSun = 0.0;
            for (int k = 0; k < SHADE_STEPS; k++) {
                double d = k * SHADE_CELL;
                double[] cell = grid.get(cellKey(x[i] + sunX * d, y[i] + sunY * d, z[i] + sunZ * d));
                if (cell != null) {
                    double amount = k == 0 ? Math.max(0.0, cell[0] - own) : cell[0];
                    // 最初のセルは半分だけ通る
                    toSun += amount * SHADE_CELL * (k == 0 ? 0.5 : 1.0);
                }
            }
            double above = 0.0;
            for (int k = 0; k < 3; k++) {
                double[] cell = grid.get(cellKey(x[i], y[i] + k * SHADE_CELL, z[i]));
                if (cell != null) {
                    double amount = k == 0 ? Math.max(0.0, cell[0] - own) : cell[0];
                    above += amount * SHADE_CELL * (k == 0 ? 0.5 : 1.0);
                }
            }
            // 下限を付ける。帯の外側の塊から回り込む光と、塊の中心で測っているぶん（日の側の面はもっと明るい）
            sunOcclusion[i] = (float) (0.3 + 0.7 * Math.exp(-toSun * SUN_OCCLUSION));
            ambientOcclusion[i] = (float) (0.7 + 0.3 * Math.exp(-above * AMBIENT_OCCLUSION));
        }
    }

    /**
     * 塊 1 つがセルに足す平均の消散係数 [1/m]。<b>塊の量をセルの体積で割るだけではいけない。</b>半径 2.5m の塊
     * （体積 65m³）は一辺 2.5m のセル（15.6m³）より大きく、量を全部 1 セルに押し込むと濃さを 4 倍に見積もって、
     * 帯が灰色に沈んだ。塊の濃さ × 「セルのうち塊が占める割合」（1 で頭打ち）で数える。
     */
    private double cellExtinction(int i) {
        double r = radius(i, 0.0F);
        double volume = (4.0 / 3.0) * Math.PI * r * r * r;
        return extinction(i, 0.0F) * PROFILE_MEAN * Math.min(1.0, volume / (SHADE_CELL * SHADE_CELL * SHADE_CELL));
    }

    private static long cellKey(double px, double py, double pz) {
        long cx = (long) Math.floor(px / SHADE_CELL) & 0x1FFFFF;
        long cy = (long) Math.floor(py / SHADE_CELL) & 0x1FFFFF;
        long cz = (long) Math.floor(pz / SHADE_CELL) & 0x1FFFFF;
        return (cx << 42) | (cy << 21) | cz;
    }

    /** 帯の陰りのうち、日の光が届く割合（0..1）。 */
    public float sunOcclusion(int i) {
        return sunOcclusion[i];
    }

    /** 帯の陰りのうち、空の明るさが届く割合（0.5..1）。 */
    public float ambientOcclusion(int i) {
        return ambientOcclusion[i];
    }

    /** 合体を見る間隔 [tick]。 */
    private static final int MERGE_INTERVAL = 8;
    /** これより若い塊は合体させない [tick]。生まれたての濃い塊は形が見えるので、まとめると目立つ。 */
    private static final float MERGE_MIN_AGE = 30.0F;
    /** 中心がこの距離（小さい方の半径の割合）より近い塊どうしを合体させる。 */
    private static final double MERGE_DISTANCE = 0.45;
    /** 合体してもこれより大きくはしない [m]。 */
    private static final double MERGE_MAX_RADIUS = 3.6;
    /** 合体の相手を探す格子の一辺 [m]。 */
    private static final double MERGE_CELL = 0.8;
    private final java.util.HashMap<Long, Integer> mergeCells = new java.util.HashMap<>();
    private long ticks;

    /**
     * <b>ほとんど重なった古い塊どうしを、煙の量を保ったまま 1 つにまとめる。</b>
     *
     * <p>描く重さは「画面のある画素を何個の塊が覆っているか」で決まる。帯の大部分は膨らんで重なり合った
     * 古い塊で、帯の中から帯に沿って見ると、何百もの塊が画面いっぱいを重ね塗りしていた（RTX 2070 SUPER の
     * 1/2 解像度で 3ms）。古い塊はぼやけているので、ほぼ同じ場所の 2 つを 1 つにしても見た目はほとんど
     * 変わらない。量（消散係数 × 半径³）を保つように大きさと濃さを決め、位置と速度は量の重みで平均する。</p>
     */
    private void merge() {
        mergeCells.clear();
        int i = 0;
        while (i < count) {
            if (age[i] < MERGE_MIN_AGE || age[i] >= life[i]) {
                i++;
                continue;
            }
            double ri = radius(i, 0.0F);
            long key = cellKey(x[i] / MERGE_CELL * SHADE_CELL, y[i] / MERGE_CELL * SHADE_CELL,
                    z[i] / MERGE_CELL * SHADE_CELL);
            Integer other = mergeCells.get(key);
            if (other == null) {
                mergeCells.put(key, i);
                i++;
                continue;
            }
            int j = other;
            if (settle[i] != settle[j] && (settle[i] == 0.0F || settle[j] == 0.0F)) {
                // 煙と砂塵は合体させない（動き方も色も違う）
                i++;
                continue;
            }
            double rj = radius(j, 0.0F);
            double dx = x[i] - x[j];
            double dy = y[i] - y[j];
            double dz = z[i] - z[j];
            double limit = Math.min(ri, rj) * MERGE_DISTANCE;
            if (dx * dx + dy * dy + dz * dz > limit * limit) {
                i++;
                continue;
            }
            double si = extinction(i, 0.0F);
            double sj = extinction(j, 0.0F);
            double mi = si * ri * ri * ri;
            double mj = sj * rj * rj * rj;
            double total = mi + mj;
            if (total <= 0.0) {
                i++;
                continue;
            }
            double wi = mi / total;
            double wj = mj / total;
            double r = Math.min(MERGE_MAX_RADIUS, Math.cbrt(ri * ri * ri + rj * rj * rj));
            double sigma = total / (r * r * r);
            x[j] = x[i] * wi + x[j] * wj;
            y[j] = y[i] * wi + y[j] * wj;
            z[j] = z[i] * wi + z[j] * wj;
            oldX[j] = oldX[i] * wi + oldX[j] * wj;
            oldY[j] = oldY[i] * wi + oldY[j] * wj;
            oldZ[j] = oldZ[i] * wi + oldZ[j] * wj;
            velocityX[j] = velocityX[i] * wi + velocityX[j] * wj;
            velocityY[j] = velocityY[i] * wi + velocityY[j] * wj;
            velocityZ[j] = velocityZ[i] * wi + velocityZ[j] * wj;
            red[j] = (float) (red[i] * wi + red[j] * wj);
            green[j] = (float) (green[i] * wi + green[j] * wj);
            blue[j] = (float) (blue[i] * wi + blue[j] * wj);
            // 残るのは長く残る方の寿命（消えかけの塊に吸われて一緒に消えないように）
            float remainingI = life[i] - age[i];
            if (remainingI > life[j] - age[j]) {
                life[j] = age[j] + remainingI;
            }
            setRadiusNow(j, r);
            // いまの濃さが sigma になるよう、生まれたときの濃さを掛け直す
            double now = extinction(j, 0.0F);
            if (now > 1.0e-6) {
                extinction[j] = (float) (extinction[j] * sigma / now);
            }
            // その場では詰めない（詰めると表に入れた番号がずれる）。濃さを 0 にして、次のティックで消える印を付ける
            extinction[i] = 0.0F;
            life[i] = age[i];
            i++;
        }
    }

    /** いまの半径が {@code r} になるよう、膨らみきったときの半径を決め直す。 */
    private void setRadiusNow(int i, double r) {
        double t = age[i];
        double r0 = startRadius[i];
        double grown = 1.0 - Math.exp(-t / growth[i]);
        if (grown > 1.0e-3) {
            finalRadius[i] = (float) (r0 + (r - r0 - LATE_GROWTH * t) / grown);
        }
    }

    private double gaussian() {
        // 一様乱数 3 つの和を、平均 0・分散 1 に揃える。正規分布に十分近く、速い
        return (random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5) * 2.0;
    }

    /** いちばん消えかけ（寿命の割合がいちばん進んだ）の塊。 */
    private int mostFaded() {
        int best = 0;
        float bestShare = -1.0F;
        for (int i = 0; i < count; i++) {
            float share = age[i] / life[i];
            if (share > bestShare) {
                bestShare = share;
                best = i;
            }
        }
        return best;
    }

    /** 最後の塊を {@code i} へ移して詰める（順番は描くときに並べ直すので要らない）。 */
    private void remove(int i) {
        int last = --count;
        if (i == last) {
            return;
        }
        x[i] = x[last];
        y[i] = y[last];
        z[i] = z[last];
        oldX[i] = oldX[last];
        oldY[i] = oldY[last];
        oldZ[i] = oldZ[last];
        velocityX[i] = velocityX[last];
        velocityY[i] = velocityY[last];
        velocityZ[i] = velocityZ[last];
        ground[i] = ground[last];
        age[i] = age[last];
        life[i] = life[last];
        startRadius[i] = startRadius[last];
        finalRadius[i] = finalRadius[last];
        extinction[i] = extinction[last];
        seed[i] = seed[last];
        red[i] = red[last];
        green[i] = green[last];
        blue[i] = blue[last];
        settle[i] = settle[last];
        flatTicks[i] = flatTicks[last];
        turbulenceScale[i] = turbulenceScale[last];
        growth[i] = growth[last];
        riseScale[i] = riseScale[last];
        turbulenceX[i] = turbulenceX[last];
        turbulenceY[i] = turbulenceY[last];
        turbulenceZ[i] = turbulenceZ[last];
        blockLight[i] = blockLight[last];
        skyLight[i] = skyLight[last];
        sunVisibility[i] = sunVisibility[last];
        sunOcclusion[i] = sunOcclusion[last];
        ambientOcclusion[i] = ambientOcclusion[last];
    }

    private void ensureArrays(int needed) {
        if (x.length >= needed) {
            return;
        }
        int size = Math.max(needed, Math.max(64, x.length * 2));
        x = java.util.Arrays.copyOf(x, size);
        y = java.util.Arrays.copyOf(y, size);
        z = java.util.Arrays.copyOf(z, size);
        oldX = java.util.Arrays.copyOf(oldX, size);
        oldY = java.util.Arrays.copyOf(oldY, size);
        oldZ = java.util.Arrays.copyOf(oldZ, size);
        velocityX = java.util.Arrays.copyOf(velocityX, size);
        velocityY = java.util.Arrays.copyOf(velocityY, size);
        velocityZ = java.util.Arrays.copyOf(velocityZ, size);
        ground = java.util.Arrays.copyOf(ground, size);
        age = java.util.Arrays.copyOf(age, size);
        life = java.util.Arrays.copyOf(life, size);
        startRadius = java.util.Arrays.copyOf(startRadius, size);
        finalRadius = java.util.Arrays.copyOf(finalRadius, size);
        extinction = java.util.Arrays.copyOf(extinction, size);
        seed = java.util.Arrays.copyOf(seed, size);
        red = java.util.Arrays.copyOf(red, size);
        green = java.util.Arrays.copyOf(green, size);
        blue = java.util.Arrays.copyOf(blue, size);
        settle = java.util.Arrays.copyOf(settle, size);
        flatTicks = java.util.Arrays.copyOf(flatTicks, size);
        turbulenceScale = java.util.Arrays.copyOf(turbulenceScale, size);
        growth = java.util.Arrays.copyOf(growth, size);
        riseScale = java.util.Arrays.copyOf(riseScale, size);
        turbulenceX = java.util.Arrays.copyOf(turbulenceX, size);
        turbulenceY = java.util.Arrays.copyOf(turbulenceY, size);
        turbulenceZ = java.util.Arrays.copyOf(turbulenceZ, size);
        blockLight = java.util.Arrays.copyOf(blockLight, size);
        skyLight = java.util.Arrays.copyOf(skyLight, size);
        sunVisibility = java.util.Arrays.copyOf(sunVisibility, size);
        sunOcclusion = java.util.Arrays.copyOf(sunOcclusion, size);
        ambientOcclusion = java.util.Arrays.copyOf(ambientOcclusion, size);
    }
}
