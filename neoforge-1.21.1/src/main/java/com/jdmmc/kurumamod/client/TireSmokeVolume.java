package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.SmokeStyle;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * タイヤの煙を<b>体積として</b>描く。粒（板に煙の絵を貼ったもの）ではなく、煙の塊（パフ）を
 * 球として持ち、画素ごとに視線が塊を通る区間を刻んで濃さと光を積む（{@code tire_smoke.fsh}）。
 *
 * <h2>なぜ</h2>
 * <p>粒の煙は、近づくと板の縁が見え、地面と交わるところで切れ、光は絵に描いた陰影のままだった。
 * 塊を体積として解くと、</p>
 * <ul>
 *   <li><b>地面に柔らかく沈む</b> … 深度から求めた面までで刻むのをやめるので、板が地面に刺さった線が出ない</li>
 *   <li><b>日の光で陰影が付く</b> … 日の方へ塊を抜けるまでの濃さで暗くする（自己影）。逆光では縁が光る（前方散乱）</li>
 *   <li><b>ヘッドライトで照らされる</b> … 路面や光線と同じ配光・同じ影（{@link HeadlightVolume#bindLampsForSmoke}）</li>
 *   <li><b>中に入っても破綻しない</b> … カメラが塊の中にいれば、その塊は画面いっぱいに描く</li>
 * </ul>
 *
 * <h2>重さ</h2>
 * <ul>
 *   <li><b>縮小した画面に描く</b>（{@link com.jdmmc.kurumamod.SmokeQuality#divisor}）。煙はぼやけたものなので
 *       縮小しても見た目はほとんど変わらない。拡大するときに深度を見て、車や柱の輪郭で煙がにじまないようにする
 *       （{@code tire_smoke_composite.fsh}）</li>
 *   <li><b>形のノイズはテクスチャ</b>（{@link SmokeNoise}。起動後に別スレッドで一度だけ作る）。画素ごとに
 *       ハッシュでノイズを作るより桁違いに安い</li>
 *   <li><b>光は塊ごとに 1 回</b>（頂点シェーダー）。ヘッドライトの灯数ぶんの計算を画素ごとにしない</li>
 *   <li><b>頂点の属性を使わない。</b>塊の中身は毎フレーム 1 枚のテクスチャで送り、{@code gl_VertexID} から
 *       読む。1 塊あたり 16 個の float だけ</li>
 *   <li>煙が 1 つも無いフレームは何もしない（深度の写しも取らない）</li>
 * </ul>
 *
 * <h2>シェーダーパック</h2>
 * <p>タイヤ痕やヘッドライトと同じく、<b>パックが仕上げ（霧・トーンマップ・TAA）を流す直前に、パックの絵
 * （colortex0）へ描き込む</b>（{@link #renderBeforePackComposite()}）。ふだんの {@code RenderType} の粒は
 * パックのプログラムに差し替えられるが、自前のシェーダーと自前の枠で描くこれは差し替えられない。
 * パックの絵が読めない（OptiFine など）ときは {@code AFTER_LEVEL} で仕上がった画面へ描く。
 * パックによって仕上げ前の絵の明るさの約束が違うので、明るさの倍率を H メニューに出してある。</p>
 *
 * <h2>描く順</h2>
 * <p>ふだんは {@code AFTER_LEVEL} の、ヘッドライト（NORMAL）の後。<b>先に描くと、ヘッドライトが煙を路面として
 * 明るくしてしまう</b>（煙は深度を書かないので、粒として見分けられない）。煙自身はランプの光を自分で受ける。
 * パック使用中は仕上げの直前で、タイヤ痕・ヘッドライトの後、ゲートの前。</p>
 */
@EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class TireSmokeVolume {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 1 塊あたりの画素数と、1 行に並べる塊の数（{@code tire_smoke.vsh} と同じ）。 */
    private static final int TEXELS_PER_PUFF = 4;
    private static final int PUFFS_PER_ROW = 256;
    /** 形のノイズの一辺。64³ × 4 バイト = 1MB。 */
    private static final int NOISE_SIZE = 64;
    /** ノイズを渡すテクスチャの番号。ShaderInstance が使う番号（0 から順）とぶつけない。 */
    private static final int NOISE_UNIT = 7;
    /**
     * 重ね塗りの予算。画面を覆う塊の面積の合計（画面何枚ぶんか）がこれを超えたら、歩数を割合で減らす
     * （2 歩まで）。煙の帯の中にいると、近くの塊がどれも画面いっぱいを覆って、重さが塊の数に比例して
     * 増える（RTX 2070 SUPER の「中」で 8ms）。煙の中では視界が煙で埋まって細部は見えないので、
     * そこで手を抜くのが一番効く。
     */
    private static final double OVERDRAW_BUDGET = 24.0;
    /** これを超えたら、さらに 1 段粗い解像度で描く。 */
    private static final double OVERDRAW_COARSE = 60.0;
    /** このフレームの重ね塗りの見積もり（{@link #upload}）。 */
    private static double overdraw;
    /** これより遠い塊は描かない [m]。 */
    private static final double MAX_DISTANCE = 256.0;

    /** 1 輪から 1 ティックで出す塊の数（強さ 1・量 1.0）。速いときは隙間が空かないよう増やす。 */
    private static final double PUFFS_PER_TICK = 1.2;
    /**
     * 煙の強さの上限と、滑りの強さに対する飽和の速さ。強さは {@code AMOUNT_CAP × (1 − exp(−AMOUNT_RATE × 滑り))}。
     * 滑りはドリフトの後輪でおよそ 1（実測 11.6 の仕事率）。頭打ちにしないので、もっと強く擦ればまだ増える。
     */
    private static final double AMOUNT_CAP = 1.7;
    private static final double AMOUNT_RATE = 1.1;
    /** タイヤの熱が溜まる・冷める速さ [1/tick]。溜まるのに 1.5 秒、冷めるのに 2.5 秒ほど。 */
    private static final double HEAT_UP = 1.0 / 30.0;
    private static final double HEAT_DOWN = 1.0 / 50.0;
    /** 冷えたタイヤでの割合と、熱が溜まりきったときに足す割合。 */
    private static final double COLD_SHARE = 0.5;
    private static final double HOT_SHARE = 0.9;
    /** 輪ごとのタイヤの熱（0..1）。番号は {@code 車の番号 × 4 + 輪}。 */
    private static final java.util.Map<Integer, double[]> HEAT = new java.util.HashMap<>();
    /** 塊どうしの間隔の目安 [m]。1 ティックでこれ以上進むと数を増やす。 */
    private static final double PUFF_SPACING = 1.2;
    /** 車の動きを初速へどれだけ乗せるか。0 だと煙がその場に置かれ、1 だと車に付いていく。 */
    private static final double CARRY = 0.12;
    /** 輪の外側へ吹き出す速さ [m/tick]。 */
    private static final double OUTWARD = 0.02;
    /** 向きの散らばり [m/tick]。 */
    private static final double JITTER = 0.03;
    /** 出た瞬間の上向きの速さ [m/tick]。 */
    private static final double LIFT_BASE = 0.02;
    private static final double LIFT_PER_INTENSITY = 0.03;

    private static final SmokePuffs PUFFS = new SmokePuffs();
    private static ClientLevel trackedLevel;
    private static long ticks;
    /** 風の向きと強さ。ゆっくりさまよう。 */
    private static double windAngle = Math.random() * Math.PI * 2.0;
    private static double[] wakes = new double[6 * 8];
    private static int wakeCount;

    private static ShaderInstance puffShader;
    private static ShaderInstance compositeShader;
    private static boolean loadAttempted;
    private static boolean broken;

    private static volatile byte[] noiseData;
    private static boolean noiseStarted;
    private static int noiseTexture = -1;

    private static int puffTexture = -1;
    private static int puffTextureRows;
    private static FloatBuffer puffBuffer;
    private static long[] order = new long[0];

    private static int lowTexture = -1;
    private static int lowFramebuffer = -1;
    private static int lowWidth;
    private static int lowHeight;
    private static TextureTarget depthCopy;
    private static int vertexArray = -1;

    /** このフレームの行列などは {@code AFTER_SKY} / {@code AFTER_TRIPWIRE_BLOCKS} で覚える。 */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static Vec3 frameCamera = Vec3.ZERO;
    private static final Vector3f FRAME_LOOK = new Vector3f(0.0F, 0.0F, -1.0F);
    private static float framePartialTick;
    private static final float[] FRAME_FOG = new float[5];
    private static boolean viewCaptured;
    private static boolean framePrepared;
    private static boolean drawnThisFrame;
    private static boolean announced;

    private TireSmokeVolume() {
    }

    // ------------------------------------------------------------------
    // 出す
    // ------------------------------------------------------------------

    /**
     * いま体積の煙で描けるか。描けなければ呼ぶ側（{@link CarSmoke}）は粒を出す。
     * 設定で粒を選んだとき・シェーダーを読めなかったとき・パック使用中に切ってあるとき。
     */
    static boolean usable() {
        if (ClientConfig.smokeStyle != SmokeStyle.VOLUMETRIC || broken) {
            return false;
        }
        if (HeadlightVolume.ShaderPacks.inUse() && !ClientConfig.smokeWithShaderPacks) {
            return false;
        }
        ensureShaders();
        startNoise();
        return puffShader != null && compositeShader != null;
    }

    /**
     * 輪 1 つの煙の強さ（0 なら出さない。ドリフトを続けると 2 を超える）。<b>毎ティック、滑っていない輪も呼ぶこと</b>
     * （タイヤの熱を冷ますため）。
     *
     * <ul>
     *   <li><b>滑りの強さ</b>（{@code drive}。摩擦の仕事率を、出始め 0・ドリフトの後輪でおよそ 1 に揃えたもの）。
     *       頭打ちにせず、ゆるやかに飽和させる。強く擦るほど増える</li>
     *   <li><b>タイヤの熱</b>。滑っている間は溜まり、やめると冷める。滑り始めは控えめで、ドリフトやバーンアウトを
     *       続けるほど煙が育っていく（ゴムが熱を持つほど煙が出る）</li>
     * </ul>
     *
     * <p>H メニューの「量」「濃さ」は、この全体に掛かる基準の倍率。</p>
     */
    static double strength(int stream, double drive) {
        double slide = Math.max(0.0, drive);
        double[] heat = HEAT.get(stream);
        if (heat == null) {
            if (slide <= 0.0) {
                return 0.0;
            }
            heat = new double[1];
            HEAT.put(stream, heat);
        }
        double target = Math.min(1.0, slide);
        heat[0] += (target - heat[0]) * (target > heat[0] ? HEAT_UP : HEAT_DOWN);
        if (slide <= 0.0) {
            if (heat[0] < 0.02) {
                HEAT.remove(stream);
            }
            return 0.0;
        }
        double amount = AMOUNT_CAP * (1.0 - Math.exp(-AMOUNT_RATE * slide));
        return amount * (COLD_SHARE + HOT_SHARE * heat[0]);
    }

    /**
     * 滑っている輪 1 つから、この 1 ティックぶんの煙を出す。
     *
     * @param x         接地点（ティックの終わり）
     * @param stepX     この 1 ティックで車が動いたぶん。塊をその間へ撒く（速いときに点線にならないよう）
     * @param kickX     後ろへ弾かれる初速 [m/tick]
     * @param outwardX  輪の外側への向き（水平の単位ベクトル）
     * @param intensity 煙の強さ（{@link #strength}。0..2.4 くらい）。数・大きさ・濃さ・昇る勢いが増える
     * @param stream    輪ごとの番号（出方のムラを輪ごとに変えるため）
     */
    static void emit(ClientLevel level, int stream, double x, double y, double z,
                     double stepX, double stepY, double stepZ,
                     double kickX, double kickZ, double outwardX, double outwardZ, double intensity) {
        double travelled = Math.sqrt(stepX * stepX + stepZ * stepZ);
        // 出方は脈打つ（ぷわっ、ぷわっ）。輪ごとに違う調子で
        double rate = intensity * ClientConfig.dustDensity * PUFFS_PER_TICK
                * Math.max(1.0, travelled / PUFF_SPACING) * SmokePuffs.gust(stream, level.getGameTime());
        int count = (int) rate;
        // 端数は確率で 1 つ。切り上げると、ごく軽く擦っただけでも毎ティック必ず出てしまう
        if (level.random.nextDouble() < rate - count) {
            count++;
        }
        double lift = LIFT_BASE + LIFT_PER_INTENSITY * Math.min(intensity, 2.0);
        for (int k = 0; k < count; k++) {
            double back = level.random.nextDouble();
            double px = x - stepX * back + (level.random.nextDouble() - 0.5) * 0.3;
            double py = y - stepY * back;
            double pz = z - stepZ * back + (level.random.nextDouble() - 0.5) * 0.3;
            double vx = kickX + stepX * CARRY + outwardX * OUTWARD + jitter(level);
            double vz = kickZ + stepZ * CARRY + outwardZ * OUTWARD + jitter(level);
            int i = PUFFS.add(px, py + 0.25, pz, vx, lift, vz, py, intensity, ClientConfig.smokeLifetime,
                    SmokePuffs.Material.SMOKE, SMOKE_COLOR[0], SMOKE_COLOR[1], SMOKE_COLOR[2]);
            sampleLight(level, i);
        }
    }

    /** タイヤの煙の色（少し青みに寄せる。細かい粒は青を散らす）。 */
    private static final float[] SMOKE_COLOR = {0.87F, 0.88F, 0.91F};
    /** 雪煙の色。 */
    private static final float[] SNOW_COLOR = {0.93F, 0.95F, 0.99F};
    /** ブロックの色が引けなかったときの砂塵の色（乾いた土）。 */
    private static final int FALLBACK_DUST = 0xA08050;
    /**
     * ブロックの色をどれだけ白へ寄せるか（粒の土煙と同じ 0.42）。舞い上がった粉は地面より明るく見える。
     * 寄せすぎると彩度が落ちて、赤い砂も灰色の砂塵になる。
     */
    private static final double DUST_WHITEN = 0.42;
    /** 1 輪から 1 ティックで出す砂塵の塊の数（強さ 1・量 1.0）。 */
    private static final double DUST_PUFFS_PER_TICK = 0.7;
    /** 砂塵の塊どうしの間隔の目安 [m]。 */
    private static final double DUST_SPACING = 1.6;

    /**
     * 未舗装の輪 1 つから、この 1 ティックぶんの砂塵（雪の上なら雪煙）を出す。走っているだけで出る
     * {@link CarDust} と、滑ったぶんの上乗せ（{@link CarSmoke}）の両方から呼ぶ。
     *
     * <p><b>色は足元のブロックから取る</b>（{@code MapColor} を白へ寄せる。粒の土煙と同じ）。赤い砂なら赤い砂塵、
     * 他 MOD のブロックでもそれらしい色で舞う。色をコードや JSON に持たない。</p>
     *
     * @param kickX     弾き飛ばす初速 [m/tick]（後ろ＋外側）
     * @param intensity 強さ（0..1.5 くらい）
     */
    static void emitDust(ClientLevel level, int stream, RoadSurface surface, double x, double y, double z,
                         double stepX, double stepY, double stepZ, double kickX, double kickZ, double intensity) {
        double travelled = Math.sqrt(stepX * stepX + stepZ * stepZ);
        double rate = intensity * ClientConfig.dustDensity * DUST_PUFFS_PER_TICK
                * Math.max(1.0, travelled / DUST_SPACING) * SmokePuffs.gust(stream, level.getGameTime());
        int count = (int) rate;
        if (level.random.nextDouble() < rate - count) {
            count++;
        }
        if (count <= 0) {
            return;
        }
        boolean snow = surface == RoadSurface.SNOW;
        float[] color = snow ? SNOW_COLOR : groundColor(level, x, y, z);
        SmokePuffs.Material material = snow ? SmokePuffs.Material.SNOW : SmokePuffs.Material.DUST;
        double lift = 0.03 + 0.04 * Math.min(intensity, 1.5);
        for (int k = 0; k < count; k++) {
            double back = level.random.nextDouble();
            double px = x - stepX * back + (level.random.nextDouble() - 0.5) * 0.4;
            double py = y - stepY * back;
            double pz = z - stepZ * back + (level.random.nextDouble() - 0.5) * 0.4;
            double vx = kickX + stepX * CARRY + jitter(level);
            double vz = kickZ + stepZ * CARRY + jitter(level);
            int i = PUFFS.add(px, py + 0.2, pz, vx, lift, vz, py, intensity, ClientConfig.smokeLifetime,
                    material, color[0], color[1], color[2]);
            sampleLight(level, i);
        }
    }

    /** 足元のブロックの色を、白へ寄せて砂塵の色にする。 */
    private static float[] groundColor(ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y - 0.15, z);
        BlockState state = level.getBlockState(pos);
        MapColor mapColor = state.getMapColor(level, pos);
        int rgb = mapColor == MapColor.NONE || mapColor.col == 0 ? FALLBACK_DUST : mapColor.col;
        return new float[]{
                whiten(((rgb >> 16) & 0xFF) / 255.0),
                whiten(((rgb >> 8) & 0xFF) / 255.0),
                whiten((rgb & 0xFF) / 255.0)};
    }

    private static float whiten(double channel) {
        return (float) (channel + (1.0 - channel) * DUST_WHITEN);
    }

    private static double jitter(ClientLevel level) {
        return (level.random.nextDouble() - 0.5) * 2.0 * JITTER;
    }

    // ------------------------------------------------------------------
    // 動かす
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        PUFFS.clear();
        HEAT.clear();
        trackedLevel = null;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PUFFS.clear();
        trackedLevel = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        if (level != trackedLevel) {
            // 次元を移ったら捨てる（座標の意味が変わる）
            PUFFS.clear();
            trackedLevel = level;
        }
        PUFFS.setCapacity(ClientConfig.smokeQuality.maxPuffs());
        if (PUFFS.count() == 0) {
            return;
        }
        ticks++;
        // 風はゆっくり向きを変える。雨・雷では強い
        windAngle += (level.random.nextDouble() - 0.5) * 0.02;
        double windSpeed = (0.35 + 0.5 * level.getRainLevel(1.0F) + 0.4 * level.getThunderLevel(1.0F)) / 20.0;
        collectWakes(level);
        PUFFS.tick(Math.cos(windAngle) * windSpeed, Math.sin(windAngle) * windSpeed, wakes, wakeCount);
        // 明るさは 4 ティックに 1 回ずつ、塊ごとにずらして調べる
        int phase = (int) (ticks & 3);
        for (int i = phase; i < PUFFS.count(); i += 4) {
            sampleLight(level, i);
        }
        // 帯全体の陰り（日の方・上に他の塊がどれだけあるか）。4 ティックに 1 回
        if (phase == 0) {
            float[] sun = sunDirection(level, 1.0F);
            PUFFS.shadeTrail(sun[0], sun[1], sun[2]);
        }
    }

    /** 煙を引きずる車（走っているものだけ。近い順ではなく見つかった順に 8 台まで）。 */
    private static void collectWakes(ClientLevel level) {
        wakeCount = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof CarEntity car) || car.isGhost()) {
                continue;
            }
            double dx = car.getX() - car.xo;
            double dy = car.getY() - car.yo;
            double dz = car.getZ() - car.zo;
            if (dx * dx + dz * dz < 0.01) {
                continue;
            }
            int o = wakeCount * 6;
            wakes[o] = car.getX();
            wakes[o + 1] = car.getY() + 0.4;
            wakes[o + 2] = car.getZ();
            wakes[o + 3] = dx;
            wakes[o + 4] = dy;
            wakes[o + 5] = dz;
            if (++wakeCount * 6 >= wakes.length) {
                break;
            }
        }
    }

    /** 塊のいる場所の明るさ。ブロックの中にめり込んでいたら 1 つ上を見る（壁際で真っ黒にならないように）。 */
    private static void sampleLight(ClientLevel level, int i) {
        BlockPos pos = BlockPos.containing(PUFFS.x(i), PUFFS.y(i), PUFFS.z(i));
        if (level.getBlockState(pos).isSolidRender(level, pos)) {
            pos = pos.above();
        }
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        int sky = level.getBrightness(LightLayer.SKY, pos);
        // 日が直接当たるか。空が見えなくても、空の明るさが残っていれば少しは回り込む
        float sun = level.canSeeSky(pos) ? 1.0F : Mth.clamp((sky - 10) / 5.0F, 0.0F, 1.0F) * 0.4F;
        PUFFS.setLight(i, block, sky, sun);
    }

    // ------------------------------------------------------------------
    // シェーダーとノイズ
    // ------------------------------------------------------------------

    @EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            loadAttempted = false;
            broken = false;
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                                ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "tire_smoke"), DefaultVertexFormat.POSITION),
                        loaded -> puffShader = loaded);
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                                ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "tire_smoke_composite"), DefaultVertexFormat.POSITION),
                        loaded -> compositeShader = loaded);
            } catch (IOException e) {
                LOGGER.error("タイヤの煙のシェーダーを読めなかった。粒の煙で代用する", e);
                puffShader = null;
                compositeShader = null;
            }
        }
    }

    /** シェーダーが無ければ自分で読む。パックを有効にしたまま起動すると読み直しの合図が来ない（HeadlightVolume と同じ）。 */
    private static void ensureShaders() {
        if ((puffShader != null && compositeShader != null) || loadAttempted) {
            return;
        }
        loadAttempted = true;
        try {
            var resources = Minecraft.getInstance().getResourceManager();
            if (puffShader == null) {
                puffShader = new ShaderInstance(resources, ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "tire_smoke"),
                        DefaultVertexFormat.POSITION);
            }
            if (compositeShader == null) {
                compositeShader = new ShaderInstance(resources,
                        ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "tire_smoke_composite"), DefaultVertexFormat.POSITION);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("タイヤの煙のシェーダーを読めなかった。粒の煙で代用する", e);
            puffShader = null;
            compositeShader = null;
        }
    }

    /** 形のノイズを別スレッドで作り始める（64³ で 0.2 秒ほど。出来るまでは球の形のまま描く）。 */
    private static void startNoise() {
        if (noiseStarted) {
            return;
        }
        noiseStarted = true;
        Thread thread = new Thread(() -> noiseData = SmokeNoise.generate(NOISE_SIZE), "KurumaMod smoke noise");
        thread.setDaemon(true);
        thread.start();
    }

    /** 出来上がったノイズをテクスチャにする。出来ていなければ -1。 */
    private static int noiseTexture() {
        if (noiseTexture >= 0) {
            return noiseTexture;
        }
        byte[] data = noiseData;
        if (data == null) {
            return -1;
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(data.length);
        buffer.put(data).flip();
        int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        noiseTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, noiseTexture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        resetUnpack();
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, NOISE_SIZE, NOISE_SIZE, NOISE_SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
        noiseData = null;
        return noiseTexture;
    }

    /** {@code NativeImage} は転送の設定を戻さないので、自前で送る前に既定へ戻す（HeadlightShadows と同じ）。 */
    private static void resetUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    // ------------------------------------------------------------------
    // 描く
    // ------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (MirrorRenderer.isRendering()) {
            return;
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // カメラの向き。1.21 では PoseStack に入っておらず、別の行列で渡される（HeadlightVolume と同じ）
            VIEW.set(event.getModelViewMatrix());
            viewCaptured = true;
            framePrepared = false;
            drawnThisFrame = false;
            return;
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            if (!viewCaptured) {
                return;
            }
            PROJECTION.set(event.getProjectionMatrix());
            frameCamera = event.getCamera().getPosition();
            FRAME_LOOK.set(event.getCamera().getLookVector());
            framePartialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
            // 地形と同じ霧（この段では地形の霧が掛かっている）
            float[] fogColor = RenderSystem.getShaderFogColor();
            FRAME_FOG[0] = fogColor[0];
            FRAME_FOG[1] = fogColor[1];
            FRAME_FOG[2] = fogColor[2];
            FRAME_FOG[3] = RenderSystem.getShaderFogStart();
            FRAME_FOG[4] = RenderSystem.getShaderFogEnd();
            framePrepared = true;
            return;
        }
        if (stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        viewCaptured = false;
        if (!framePrepared || drawnThisFrame || PUFFS.count() == 0 || !usable()) {
            return;
        }
        // パック使用中で仕上げの前に入れなかった（パックの絵が読めない）ときは、仕上がった画面へ
        boolean pack = HeadlightVolume.ShaderPacks.inUse();
        draw(-1, 0, 0, pack ? (float) ClientConfig.smokePackBrightness : 1.0F, !pack);
    }

    /**
     * シェーダーパックが仕上げを流す直前に、パックの絵（colortex0）へ描く（{@code IrisPipelineMixin}）。
     * パックの絵が読めなければ何もしない（{@code AFTER_LEVEL} で描かれる）。
     */
    public static void renderBeforePackComposite() {
        if (!framePrepared || drawnThisFrame || PUFFS.count() == 0 || MirrorRenderer.isRendering() || !usable()) {
            return;
        }
        int packScene = IrisScene.colorTexture();
        if (packScene < 0) {
            return;
        }
        draw(packScene, IrisScene.width(), IrisScene.height(), (float) ClientConfig.smokePackBrightness, false);
    }

    /**
     * 塊を縮小した画面へ描き、深度を見て拡大しながら書き込み先へ重ねる。
     *
     * @param packScene パックの絵のテクスチャ。-1 なら主画面
     * @param fog       バニラの霧を掛けるか（パック使用中はパックが掛ける）
     */
    private static void draw(int packScene, int packWidth, int packHeight, float brightness, boolean fog) {
        drawnThisFrame = true;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        int outWidth = packScene >= 0 ? packWidth : main.width;
        int outHeight = packScene >= 0 ? packHeight : main.height;
        int puffs = upload(framePartialTick);
        if (puffs == 0) {
            return;
        }

        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
        int oldSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int oldDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int oldSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int oldDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int oldEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        float[] clearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        try {
            copyDepth(main);
            int divisor = ClientConfig.smokeQuality.divisor();
            ensureLowTarget(Math.max(1, outWidth / divisor), Math.max(1, outHeight / divisor));
            // 重ね塗りが多すぎるフレームは、同じ枠の左下だけを使って 1 段粗く描く（枠を作り直さない）
            int drawWidth = lowWidth;
            int drawHeight = lowHeight;
            if (overdraw > OVERDRAW_COARSE) {
                drawWidth = Math.max(1, outWidth / (divisor + 1));
                drawHeight = Math.max(1, outHeight / (divisor + 1));
            }
            int baseSteps = ClientConfig.smokeQuality.steps();
            int steps = overdraw > OVERDRAW_BUDGET
                    ? Math.max(2, (int) Math.round(baseSteps * OVERDRAW_BUDGET / overdraw))
                    : baseSteps;
            if (vertexArray < 0) {
                vertexArray = GL30.glGenVertexArrays();
            }

            Matrix4f inverse = new Matrix4f(PROJECTION).mul(VIEW).invert();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            // ---- 塊を縮小した画面へ（奥から順に、色に α を掛けた形で上へ重ねる） ----
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, lowFramebuffer);
            GlStateManager._viewport(0, 0, lowWidth, lowHeight);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GlStateManager._viewport(0, 0, drawWidth, drawHeight);

            ShaderInstance shader = puffShader;
            shader.setSampler("PuffSampler", puffTexture);
            shader.setSampler("DepthSampler", depthCopy.getDepthTextureId());
            shader.setSampler("LightMapSampler", lightMapTexture(minecraft));
            shader.safeGetUniform("ProjMat").set(PROJECTION);
            shader.safeGetUniform("ViewMat").set(VIEW);
            shader.safeGetUniform("InvViewProj").set(inverse);
            shader.safeGetUniform("TargetSize").set((float) drawWidth, (float) drawHeight);
            shader.safeGetUniform("Steps").set(steps);
            int noise = noiseTexture();
            shader.safeGetUniform("NoiseReady").set(noise >= 0 ? 1.0F : 0.0F);
            setSun(shader, level, framePartialTick);
            if (fog) {
                shader.safeGetUniform("FogColor").set(FRAME_FOG[0], FRAME_FOG[1], FRAME_FOG[2]);
                shader.safeGetUniform("FogStart").set(FRAME_FOG[3]);
                shader.safeGetUniform("FogEnd").set(FRAME_FOG[4]);
            } else {
                shader.safeGetUniform("FogEnd").set(-1.0F);
            }
            HeadlightVolume.bindLampsForSmoke(shader);
            shader.apply();
            // ブレンドは apply の後に OpenGL を直接呼んで掛ける。apply は JSON の blend（無ければ不透明）を
            // 掛け直すうえ、Oculus は RenderSystem のブレンドの呼び出しを横取りする（DeferredGlows と同じ）
            blend(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // 3D のノイズは ShaderInstance が 2D として結んでしまうので、自分で結ぶ
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, Math.max(noise, 0));
            GL20.glUniform1i(GL20.glGetUniformLocation(shader.getId(), "NoiseSampler"), NOISE_UNIT);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL30.glBindVertexArray(vertexArray);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, puffs * 6);
            shader.clear();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);

            // ---- 拡大して重ねる ----
            if (packScene >= 0) {
                IrisScene.bindForWrite(packScene);
            } else {
                main.bindWrite(false);
            }
            GlStateManager._viewport(0, 0, outWidth, outHeight);
            ShaderInstance composite = compositeShader;
            composite.setSampler("SmokeSampler", lowTexture);
            composite.setSampler("DepthSampler", depthCopy.getDepthTextureId());
            composite.safeGetUniform("InvViewProj").set(inverse);
            composite.safeGetUniform("SmokeSize").set((float) drawWidth, (float) drawHeight);
            composite.safeGetUniform("OutSize").set((float) outWidth, (float) outHeight);
            composite.safeGetUniform("Brightness").set(brightness);
            composite.apply();
            // 色だけ重ねる。書き込み先の α はそのまま
            blend(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            composite.clear();

            if (!announced) {
                // 煙が見えないときに「描いていない」のか「描いているが見えない」のかを切り分けるため
                announced = true;
                LOGGER.info("タイヤの煙を体積で描き始めた（{} 塊、{}x{} → {}x{}{}）", puffs, lowWidth, lowHeight,
                        outWidth, outHeight, packScene >= 0 ? "、シェーダーパックの仕上げ前の絵へ" : "");
            }
        } catch (RuntimeException e) {
            // 描けない環境（GL の機能が足りない等）。以後は粒の煙に落とす
            LOGGER.error("タイヤの煙を体積で描けなかった。以後は粒の煙で代用する", e);
            broken = true;
        } finally {
            GL30.glBindVertexArray(previousVertexArray);
            GL14.glBlendEquation(oldEquation);
            GL14.glBlendFuncSeparate(oldSrcRgb, oldDstRgb, oldSrcAlpha, oldDstAlpha);
            // apply がブレンドを切った（RenderSystem の覚えている値も変わった）ので、覚えている値と実際を両方戻す
            if (blendWas) {
                RenderSystem.enableBlend();
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                RenderSystem.disableBlend();
                GL11.glDisable(GL11.GL_BLEND);
            }
            GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    private static void blend(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    /**
     * 見えている塊を奥から順に並べてテクスチャへ送る。送った数を返す。
     * 並べ替えは距離と番号を 1 つの long に詰めて、プリミティブのまま整列する（ソートで物を作らない）。
     */
    private static int upload(float partialTick) {
        int count = PUFFS.count();
        if (order.length < count) {
            order = new long[Math.max(count, order.length * 2)];
        }
        Vec3 camera = frameCamera;
        double opacity = ClientConfig.smokeOpacity;
        int visible = 0;
        // 射影の拡大率（1 / tan(画角の半分)）。塊が画面のどれだけを覆うかの見積もりに使う
        double scaleX = Math.abs(PROJECTION.m00());
        double scaleY = Math.abs(PROJECTION.m11());
        overdraw = 0.0;
        for (int i = 0; i < count; i++) {
            double r = PUFFS.radius(i, partialTick);
            double dx = PUFFS.renderX(i, partialTick) - camera.x;
            double dy = PUFFS.renderY(i, partialTick) - camera.y;
            double dz = PUFFS.renderZ(i, partialTick) - camera.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > MAX_DISTANCE + r) {
                continue;
            }
            // 丸ごとカメラの後ろ
            if (dx * FRAME_LOOK.x() + dy * FRAME_LOOK.y() + dz * FRAME_LOOK.z() < -r) {
                continue;
            }
            // 中心を通っても透けて見えないほど薄い
            if (PUFFS.extinction(i, partialTick) * opacity * r * 2.0 < 0.004) {
                continue;
            }
            // 画面のどれだけを覆うか（画面の面積は正規化した座標で 4）。中にいれば画面いっぱい
            if (distance <= r) {
                overdraw += 1.0;
            } else {
                double angular = r / Math.sqrt(distance * distance - r * r);
                overdraw += Math.min(1.0, Math.PI * angular * angular * scaleX * scaleY / 4.0);
            }
            // 正の float はビット列の大小が値の大小と一致する
            order[visible++] = ((long) Float.floatToIntBits((float) distance) << 32) | i;
        }
        if (visible == 0) {
            return 0;
        }
        java.util.Arrays.sort(order, 0, visible);

        int rows = (visible + PUFFS_PER_ROW - 1) / PUFFS_PER_ROW;
        int floats = rows * PUFFS_PER_ROW * TEXELS_PER_PUFF * 4;
        if (puffBuffer == null || puffBuffer.capacity() < floats) {
            puffBuffer = BufferUtils.createFloatBuffer(floats);
        }
        FloatBuffer buffer = puffBuffer;
        buffer.clear();
        // 遠いものから（並びの後ろから）
        for (int k = visible - 1; k >= 0; k--) {
            int i = (int) order[k];
            double r = PUFFS.radius(i, partialTick);
            buffer.put((float) (PUFFS.renderX(i, partialTick) - camera.x))
                    .put((float) (PUFFS.renderY(i, partialTick) - camera.y))
                    .put((float) (PUFFS.renderZ(i, partialTick) - camera.z))
                    .put((float) r);
            buffer.put((float) (PUFFS.extinction(i, partialTick) * opacity))
                    .put((float) PUFFS.ageSeconds(i, partialTick))
                    .put(PUFFS.seed(i))
                    .put(PUFFS.ambientOcclusion(i));
            buffer.put(PUFFS.red(i)).put(PUFFS.green(i)).put(PUFFS.blue(i)).put(PUFFS.flatten(i));
            buffer.put(PUFFS.blockLight(i)).put(PUFFS.skyLight(i))
                    .put(PUFFS.sunVisibility(i) * PUFFS.sunOcclusion(i)).put(0.0F);
        }
        while (buffer.position() < floats) {
            buffer.put(0.0F);
        }
        buffer.flip();

        if (puffTexture < 0 || puffTextureRows < rows) {
            if (puffTexture >= 0) {
                GlStateManager._deleteTexture(puffTexture);
            }
            puffTextureRows = Math.max(rows, (ClientConfig.smokeQuality.maxPuffs() + PUFFS_PER_ROW - 1) / PUFFS_PER_ROW);
            puffTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(puffTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, PUFFS_PER_ROW * TEXELS_PER_PUFF,
                    puffTextureRows, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        }
        GlStateManager._bindTexture(puffTexture);
        resetUnpack();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, PUFFS_PER_ROW * TEXELS_PER_PUFF, rows,
                GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
        GlStateManager._bindTexture(0);
        return visible;
    }

    /**
     * 日（夜は月）の向きと色。バニラの空と同じ向き（Y 軸で -90 度回してから、時刻ぶん X 軸で回す）。
     * 色は昼の割合（{@code getSkyDarken(partialTick)}。int の版はクライアントでは更新されない）と雨で弱め、
     * 低いときは夕日の色へ寄せる。空の明るさは別にライトマップで掛かる。
     */
    /** 光の来る向き（昼は日、夜は月）。空の無い次元では真上。 */
    private static float[] sunDirection(ClientLevel level, float partialTick) {
        if (!level.dimensionType().hasSkyLight()) {
            return new float[]{0.0F, 1.0F, 0.0F};
        }
        float angle = level.getTimeOfDay(partialTick) * (float) (Math.PI * 2.0);
        float sunX = -Mth.sin(angle);
        float sunY = Mth.cos(angle);
        return sunY >= -0.05F ? new float[]{sunX, Math.max(sunY, 0.02F), 0.0F} : new float[]{-sunX, -sunY, 0.0F};
    }

    private static void setSun(ShaderInstance shader, ClientLevel level, float partialTick) {
        if (!level.dimensionType().hasSkyLight()) {
            shader.safeGetUniform("SunDir").set(0.0F, 1.0F, 0.0F);
            shader.safeGetUniform("SunColor").set(0.0F, 0.0F, 0.0F);
            return;
        }
        float angle = level.getTimeOfDay(partialTick) * (float) (Math.PI * 2.0);
        float sunX = -Mth.sin(angle);
        float sunY = Mth.cos(angle);
        float day = Mth.clamp((level.getSkyDarken(partialTick) - 0.2F) / 0.8F, 0.0F, 1.0F);
        float weather = 1.0F - 0.75F * level.getRainLevel(partialTick);
        if (sunY >= -0.05F) {
            float high = Mth.clamp(sunY / 0.3F, 0.0F, 1.0F);
            high = high * high * (3.0F - 2.0F * high);
            float strength = day * weather;
            shader.safeGetUniform("SunDir").set(sunX, Math.max(sunY, 0.02F), 0.0F);
            shader.safeGetUniform("SunColor").set(strength, strength * Mth.lerp(high, 0.62F, 0.97F),
                    strength * Mth.lerp(high, 0.38F, 0.92F));
        } else {
            // 月。夜の空の明るさ（ライトマップ）が暗いので、色は控えめでよい
            float strength = 0.35F * weather;
            shader.safeGetUniform("SunDir").set(-sunX, -sunY, 0.0F);
            shader.safeGetUniform("SunColor").set(strength * 0.7F, strength * 0.8F, strength);
        }
    }

    /** バニラのライトマップのテクスチャ。 */
    private static int lightMapTexture(Minecraft minecraft) {
        minecraft.gameRenderer.lightTexture().turnOnLightLayer();
        int texture = RenderSystem.getShaderTexture(2);
        minecraft.gameRenderer.lightTexture().turnOffLightLayer();
        return texture;
    }

    /**
     * 主画面の深度を写す。煙は書き込み先とは別の縮小した枠に描くが、拡大して重ねるときは主画面へ書くので、
     * その深度をそのまま読むと読み書きが同じになる。ステンシルの有無が違うと深度を写せない。
     */
    private static void copyDepth(RenderTarget main) {
        int width = main.width;
        int height = main.height;
        if (depthCopy == null) {
            depthCopy = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (depthCopy.width != width || depthCopy.height != height) {
            depthCopy.resize(width, height, Minecraft.ON_OSX);
        }
        if (main.isStencilEnabled() && !depthCopy.isStencilEnabled()) {
            depthCopy.enableStencil();
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthCopy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
    }

    /** 縮小した煙の枠（色に α を掛けた形を、重ねても潰れないよう浮動小数で持つ）。 */
    private static void ensureLowTarget(int width, int height) {
        if (lowTexture >= 0 && lowWidth == width && lowHeight == height) {
            return;
        }
        if (lowTexture >= 0) {
            GlStateManager._deleteTexture(lowTexture);
            GlStateManager._glDeleteFramebuffers(lowFramebuffer);
        }
        lowTexture = GlStateManager._genTexture();
        GlStateManager._bindTexture(lowTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GlStateManager._bindTexture(0);
        lowFramebuffer = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, lowFramebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, lowTexture, 0);
        lowWidth = width;
        lowHeight = height;
    }
}
