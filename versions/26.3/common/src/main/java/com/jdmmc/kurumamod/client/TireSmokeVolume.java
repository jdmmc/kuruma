package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.SmokeStyle;
import com.jdmmc.kurumamod.client.hook.ShaderRegistrar;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

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
 *   <li><b>中に入っても破綻しない</b> … カメラが塊の中にいれば、その塊は画面いっぱいに描く</li>
 * </ul>
 *
 * <h2>26.3 での描き方（renderpearl）</h2>
 * <p>26.3 の描画は OpenGL と Vulkan を切り替えられるので、GL を直接呼ばず、すべて renderpearl の
 * パイプライン（{@link RenderPipeline}）と描画パス（{@link RenderPass}）で書いてある。1.21.1 との違い:</p>
 * <ul>
 *   <li><b>描く段</b>は {@code LevelRenderer#render} の終わり（{@code LevelStageHooks#levelEnd}）。フレームグラフは
 *       実行し終えていて、主画面に世界が描き終わっている。主画面の深度を読み、縮小した自前の枠
 *       （{@link #lowTexture}）へ塊を描き、深度を見て拡大しながら主画面の色へ重ねる。<b>深度は写さない</b>——
 *       重ねるパスは色だけを書き込み先にして深度を読むので、読み書きがぶつからない</li>
 *   <li><b>3D テクスチャが無い</b>（renderpearl が扱わない）。形のノイズ 64³ は 64 枚の断面を 8×8 に並べた
 *       2D の絵にして（{@link #noiseAtlas}）、シェーダーで隣り合う 2 枚を混ぜて引く</li>
 *   <li><b>ばらの uniform が無い</b>（SPIR-V を経由する）。値は 1 つのブロック（{@code SmokeInfo}。
 *       {@code shaders/include/tire_smoke_info.glsl}）にまとめ、毎フレーム {@link MappableRingBuffer} へ詰める</li>
 *   <li><b>射影はバニラの {@code Projection} のブロック</b>から取る（画面の揺れが入っている方。シェーダー側で
 *       逆行列も作る）。霧はバニラの {@code Fog} のブロック（地形と同じ霧）</li>
 *   <li><b>深度は逆向き</b>（手前ほど大きく、空が 0）</li>
 * </ul>
 *
 * <h2>重さ</h2>
 * <ul>
 *   <li><b>縮小した画面に描く</b>（{@link com.jdmmc.kurumamod.SmokeQuality#divisor}）。煙はぼやけたものなので
 *       縮小しても見た目はほとんど変わらない。拡大するときに深度を見て、車や柱の輪郭で煙がにじまないようにする
 *       （{@code tire_smoke_composite.fsh}）</li>
 *   <li><b>形のノイズはテクスチャ</b>（{@link SmokeNoise}。起動後に別スレッドで一度だけ作る）</li>
 *   <li><b>光は塊ごとに 1 回</b>（頂点シェーダー）</li>
 *   <li><b>頂点の属性を使わない。</b>塊の中身は毎フレーム 1 枚のテクスチャで送り、{@code gl_VertexIndex} から
 *       読む。1 塊あたり 16 個の float だけ</li>
 *   <li>煙が 1 つも無いフレームは何もしない</li>
 * </ul>
 *
 * <h2>ヘッドライト</h2>
 * <p>煙はヘッドライトの光を受ける（路面・光線と同じ配光と影の地図。{@link HeadlightVolume#bindLampsForSmoke}）。
 * ランプがあるフレームは {@link #PUFF_LAMP_PIPELINE}、無いフレームは {@link #PUFF_PIPELINE} で描く
 * （宣言したテクスチャはすべて結ばないといけないので、ランプの口の無いパイプラインを別に持つ）。
 * <b>ヘッドライトの後に描く</b>（{@code LevelStageHooks#levelEnd} で {@code HeadlightVolume#levelEnd} の後）。
 * 先に描くと、深度を書かない煙をヘッドライトが路面として明るくするうえ、ランプが 1 フレーム古くなる。</p>
 *
 * <h2>シェーダーパック</h2>
 * <p>Iris のパック使用中は、パックが仕上げ（composite）を流す直前に、仕上げ前の絵（{@link IrisScene#target()}、
 * RGBA16F）へ描く（{@link #renderBeforePackComposite}。霧・トーンマップはパックが煙の上にも掛ける）。その絵が
 * 読めなかったときは、仕上がった画面へ描く（霧はパックに任せ、明るさは H メニューの倍率）。どちらも 1.21.1 と同じ。</p>
 *
 * <h2>失敗したら</h2>
 * <p>パイプラインのコンパイルや描画に失敗したら、ログを 1 回出して {@link #usable()} を false にする。
 * 呼ぶ側（{@link CarSmoke}・{@link CarDust}）は粒の煙・土煙に戻る。クラッシュはさせない。</p>
 */
public final class TireSmokeVolume {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 1 塊あたりの画素数と、1 行に並べる塊の数（{@code tire_smoke.vsh} と同じ）。 */
    private static final int TEXELS_PER_PUFF = 4;
    private static final int PUFFS_PER_ROW = 256;
    /** 1 画素の大きさ（RGBA の float）。 */
    private static final int BYTES_PER_TEXEL = 16;
    /** 形のノイズの一辺。64³ × 4 バイト = 1MB。 */
    private static final int NOISE_SIZE = 64;
    /** ノイズの断面 1 枚の一辺（縁に 1 画素ずつ反対側を写す）と、1 行に並べる枚数（{@code tire_smoke.fsh} と同じ）。 */
    private static final int NOISE_TILE = NOISE_SIZE + 2;
    private static final int NOISE_TILES_PER_ROW = 8;
    private static final int NOISE_ATLAS = NOISE_TILE * NOISE_TILES_PER_ROW;
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
    /** 輪の外側へ吹き出す速さ [m/tick]。0.02 では輪の真後ろに細く溜まり、横へ広がらなかった。 */
    private static final double OUTWARD = 0.08;
    /** 向きの散らばり [m/tick]。 */
    private static final double JITTER = 0.05;
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

    // ------------------------------------------------------------------
    // パイプライン
    // ------------------------------------------------------------------

    /** 煙のシェーダーが共有する値のブロック（{@code tire_smoke_info.glsl}）。 */
    private static final String INFO_BLOCK = "SmokeInfo";
    /** {@code SmokeInfo} の大きさ（mat4 1 つと vec4 6 つ）。 */
    private static final int INFO_SIZE = new Std140SizeCalculator()
            .putMat4f().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();

    /** 塊を描くパスが読むもの。 */
    private static final BindGroupLayout PUFF_LAYOUT = BindGroupLayout.builder()
            .withUniform(INFO_BLOCK, UniformType.UNIFORM_BUFFER)
            .withUniform("PuffSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("LightMapSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    /**
     * 塊を縮小した枠へ描く（ランプ無し）。奥から順に、色に α を掛けた形で上へ重ねる（色も α も
     * ONE, ONE_MINUS_SRC_ALPHA）。深度は使わない（面との交わりはシェーダーが深度を読んで解く）。
     * 枠は重ねても潰れないよう浮動小数。
     */
    private static final RenderPipeline PUFF_PIPELINE = puffPipeline("pipeline/tire_smoke", false);

    /**
     * ランプのあるフレーム用。{@code tire_smoke.vsh} のランプの計算（{@code KURUMAMOD_SMOKE_LAMPS}）を入れ、
     * ヘッドライトのブロックとテクスチャ（{@link HeadlightVolume#lampBindGroupLayout}：{@code HeadlightInfo}・
     * {@code LampSampler}・{@code VoxelSampler}）と距離の地図（{@code ShadowSampler}）の口を足したもの。
     * 結ぶのは {@link HeadlightVolume#bindLampsForSmoke}。
     */
    private static final RenderPipeline PUFF_LAMP_PIPELINE = puffPipeline("pipeline/tire_smoke_lamps", true);

    private static RenderPipeline puffPipeline(String location, boolean lamps) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(id(location))
                .withVertexShader(id("core/tire_smoke"))
                .withFragmentShader(id("core/tire_smoke"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withBindGroupLayout(PUFF_LAYOUT)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
                        GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                .withCull(false);
        if (lamps) {
            builder.withShaderDefine("KURUMAMOD_SMOKE_LAMPS")
                    .withBindGroupLayout(HeadlightVolume.lampBindGroupLayout())
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform("ShadowSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                            .build());
        }
        return builder.build();
    }

    /** 拡大して重ねるパスが読むもの。 */
    private static final BindGroupLayout COMPOSITE_LAYOUT = BindGroupLayout.builder()
            .withUniform(INFO_BLOCK, UniformType.UNIFORM_BUFFER)
            .withUniform("SmokeSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    /** 色だけ重ねる。書き込み先の α はそのまま。 */
    private static final BlendFunction COMPOSITE_BLEND = new BlendFunction(
            BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE);

    /**
     * 重ねるパイプラインは書き込み先の色の形式ごとに作る（パイプラインの形式が描き先と違うと描けない。
     * 主画面はふつう RGBA8 だが、ほかの MOD が変えても動くように）。
     */
    private static final Map<GpuFormat, RenderPipeline> COMPOSITE_PIPELINES = new EnumMap<>(GpuFormat.class);

    private static RenderPipeline compositePipeline(GpuFormat format) {
        return COMPOSITE_PIPELINES.computeIfAbsent(format, f -> RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(id("pipeline/tire_smoke_composite_" + f.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader(id("core/tire_smoke_composite"))
                .withFragmentShader(id("core/tire_smoke_composite"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(COMPOSITE_LAYOUT)
                .withColorTargetState(new ColorTargetState(Optional.of(COMPOSITE_BLEND), f, ColorTargetState.WRITE_ALL))
                .withCull(false)
                .build());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Kurumamod.MODID, path);
    }

    /** 一度でも失敗したら true（以後は粒の煙）。 */
    private static boolean broken;
    /** パイプラインを確かめ終えたか（初めの 1 回だけ、その場でコンパイルさせて確かめる）。 */
    private static boolean pipelinesChecked;

    // ------------------------------------------------------------------
    // GPU の持ち物
    // ------------------------------------------------------------------

    private static volatile byte[] noiseData;
    private static boolean noiseStarted;
    private static GpuTexture noiseTexture;
    private static GpuTextureView noiseView;

    private static GpuTexture puffTexture;
    private static GpuTextureView puffView;
    private static int puffTextureRows;
    private static ByteBuffer puffBuffer;
    private static long[] order = new long[0];

    private static GpuTexture lowTexture;
    private static GpuTextureView lowView;
    private static int lowWidth;
    private static int lowHeight;

    private static MappableRingBuffer infoUbo;

    /** このフレームのカメラ（{@link #renderAfterLevel} で覚える）。 */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static Vec3 frameCamera = Vec3.ZERO;
    private static final Vector3f FRAME_LOOK = new Vector3f(0.0F, 0.0F, -1.0F);
    private static float framePartialTick;
    private static boolean announced;

    private TireSmokeVolume() {
    }

    // ------------------------------------------------------------------
    // 出す
    // ------------------------------------------------------------------

    /**
     * いま体積の煙で描けるか。描けなければ呼ぶ側（{@link CarSmoke}）は粒を出す。
     * 設定で粒を選んだとき・パイプラインを作れなかった（描けなかった）とき・パック使用中に切ってあるとき。
     */
    static boolean usable() {
        if (ClientConfig.smokeStyle != SmokeStyle.VOLUMETRIC || broken) {
            return false;
        }
        if (HeadlightVolume.ShaderPacks.inUse() && !ClientConfig.smokeWithShaderPacks) {
            return false;
        }
        startNoise();
        if (!pipelinesChecked) {
            pipelinesChecked = true;
            checkPipelines();
        }
        return !broken;
    }

    /**
     * パイプラインを 1 度だけその場でコンパイルさせて、作れるか確かめる（シェーダーの読み込みや
     * コンパイルに失敗すると {@code null} が返る。中身はバニラのログに出る）。作れなければ以後は粒の煙。
     */
    private static void checkPipelines() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
            GpuTexture color = main.getColorTexture();
            GpuFormat format = color != null ? color.getFormat() : GpuFormat.RGBA8_UNORM;
            if (RenderSystem.getCompiledPipelineNullable(PUFF_PIPELINE) == null
                    || RenderSystem.getCompiledPipelineNullable(PUFF_LAMP_PIPELINE) == null
                    || RenderSystem.getCompiledPipelineNullable(compositePipeline(format)) == null) {
                fail("タイヤの煙のパイプラインを作れなかった（理由は直前のシェーダーのログ）。粒の煙で代用する", null);
            }
        } catch (RuntimeException e) {
            fail("タイヤの煙のパイプラインを作れなかった。粒の煙で代用する", e);
        }
    }

    private static void fail(String message, RuntimeException e) {
        if (!broken) {
            if (e != null) {
                LOGGER.error(message, e);
            } else {
                LOGGER.error(message);
            }
        }
        broken = true;
        // 体積で溜めていた塊は描けないので捨てる（呼ぶ側は次のティックから粒を出す）
        PUFFS.clear();
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
        if (level.getRandom().nextDouble() < rate - count) {
            count++;
        }
        double lift = LIFT_BASE + LIFT_PER_INTENSITY * Math.min(intensity, 2.0);
        for (int k = 0; k < count; k++) {
            double back = level.getRandom().nextDouble();
            double px = x - stepX * back + (level.getRandom().nextDouble() - 0.5) * 0.3;
            double py = y - stepY * back;
            double pz = z - stepZ * back + (level.getRandom().nextDouble() - 0.5) * 0.3;
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
        if (level.getRandom().nextDouble() < rate - count) {
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
            double back = level.getRandom().nextDouble();
            double px = x - stepX * back + (level.getRandom().nextDouble() - 0.5) * 0.4;
            double py = y - stepY * back;
            double pz = z - stepZ * back + (level.getRandom().nextDouble() - 0.5) * 0.4;
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
        return (level.getRandom().nextDouble() - 0.5) * 2.0 * JITTER;
    }

    // ------------------------------------------------------------------
    // 動かす
    // ------------------------------------------------------------------

    public static void onLoggingIn() {
        PUFFS.clear();
        HEAT.clear();
        trackedLevel = null;
    }

    public static void onLoggingOut() {
        PUFFS.clear();
        trackedLevel = null;
    }

    public static void onClientTick() {
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
        windAngle += (level.getRandom().nextDouble() - 0.5) * 0.02;
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
            float[] sun = lightDirection(level, 1.0F);
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
        if (level.getBlockState(pos).isSolidRender()) {
            pos = pos.above();
        }
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        int sky = level.getBrightness(LightLayer.SKY, pos);
        // 日が直接当たるか。空が見えなくても、空の明るさが残っていれば少しは回り込む
        float sun = level.canSeeSky(pos) ? 1.0F : Mth.clamp((sky - 10) / 5.0F, 0.0F, 1.0F) * 0.4F;
        PUFFS.setLight(i, block, sky, sun);
    }

    // ------------------------------------------------------------------
    // ノイズ
    // ------------------------------------------------------------------

    /** 形のノイズを別スレッドで作り始める（64³ で 0.2 秒ほど。出来るまでは球の形のまま描く）。 */
    private static void startNoise() {
        if (noiseStarted) {
            return;
        }
        noiseStarted = true;
        Thread thread = new Thread(() -> noiseData = noiseAtlas(SmokeNoise.generate(NOISE_SIZE)), "KurumaMod smoke noise");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 64³ のノイズ（x が最も速く変わる順）を、z の断面 64 枚を 8×8 に並べた 2D の絵にする。
     * 断面 1 枚は縁に 1 画素ずつ反対側を写した 66×66 で、シェーダーの線形補間が縁で途切れない
     * （1.21.1 の 3D テクスチャの GL_REPEAT と同じに一周する）。並びは {@code tire_smoke.fsh} の {@code noise3} と同じ。
     */
    private static byte[] noiseAtlas(byte[] volume) {
        byte[] atlas = new byte[NOISE_ATLAS * NOISE_ATLAS * 4];
        for (int slice = 0; slice < NOISE_SIZE; slice++) {
            int tileX = (slice % NOISE_TILES_PER_ROW) * NOISE_TILE;
            int tileY = (slice / NOISE_TILES_PER_ROW) * NOISE_TILE;
            for (int j = 0; j < NOISE_TILE; j++) {
                int sy = Math.floorMod(j - 1, NOISE_SIZE);
                for (int i = 0; i < NOISE_TILE; i++) {
                    int sx = Math.floorMod(i - 1, NOISE_SIZE);
                    int from = ((slice * NOISE_SIZE + sy) * NOISE_SIZE + sx) * 4;
                    int to = ((tileY + j) * NOISE_ATLAS + tileX + i) * 4;
                    System.arraycopy(volume, from, atlas, to, 4);
                }
            }
        }
        return atlas;
    }

    /** 出来上がったノイズをテクスチャにする。出来ていなければ null（球の形のまま描く）。 */
    private static GpuTextureView noiseView(GpuDevice device, CommandEncoder encoder) {
        if (noiseView != null) {
            return noiseView;
        }
        byte[] data = noiseData;
        if (data == null) {
            return null;
        }
        noiseTexture = device.createTexture("KurumaMod smoke noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, NOISE_ATLAS, NOISE_ATLAS, 1, 1);
        noiseView = device.createTextureView(noiseTexture);
        ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
        try {
            buffer.put(data).flip();
            encoder.writeToTexture(noiseTexture, buffer, 0, 0, 0, 0, NOISE_ATLAS, NOISE_ATLAS);
        } finally {
            MemoryUtil.memFree(buffer);
        }
        noiseData = null;
        return noiseView;
    }

    // ------------------------------------------------------------------
    // 描く
    // ------------------------------------------------------------------

    /**
     * ワールドを描き終えたところ（{@code LevelStageHooks#levelEnd}、{@code LevelRenderer#render} の終わり）。
     * 主画面に世界が描き終わっていて、主画面の深度はワールドのもの（手はまだ描いていない）。
     * <b>例外は外へ出さない</b>（失敗したら粒の煙に落とす）。
     */
    public static void renderAfterLevel(CameraRenderState camera) {
        if (drawnBeforePackComposite) {
            // このフレームの煙はパックの仕上げ前の絵へ入れてある
            drawnBeforePackComposite = false;
            return;
        }
        if (camera == null || !camera.initialized || PUFFS.count() == 0 || MirrorRenderer.isRendering() || !usable()) {
            return;
        }
        try {
            prepare(camera);
            // パックが使われていれば（仕上げ前の絵へ入れられなかったとき）、仕上がった絵に重ねる。
            // 霧はパックに任せ、明るさは H メニューの倍率
            boolean pack = HeadlightVolume.ShaderPacks.inUse();
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            draw(main.getColorTexture(), main.getColorTextureView(),
                    pack ? (float) ClientConfig.smokePackBrightness : 1.0F, !pack);
        } catch (RuntimeException e) {
            // 描けない環境（形式が使えない・パスが張れない等）。以後は粒の煙に落とす
            fail("タイヤの煙を体積で描けなかった。以後は粒の煙で代用する", e);
        }
    }

    /** このフレームの煙をパックの仕上げ前の絵へ入れた（{@link #renderAfterLevel} では描かない）。 */
    private static boolean drawnBeforePackComposite;

    private static void prepare(CameraRenderState camera) {
        VIEW.set(camera.viewRotationMatrix);
        PROJECTION.set(camera.projectionMatrix);
        frameCamera = camera.pos;
        // 視線の向き（世界の向き）= 視点の回転の逆で (0, 0, -1) を戻したもの
        new Matrix4f(VIEW).invert().transformDirection(FRAME_LOOK.set(0.0F, 0.0F, -1.0F)).normalize();
        framePartialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    /**
     * シェーダーパック（Iris）が仕上げを流す<b>直前</b>に、仕上げ前の絵（{@link IrisScene#target()}）へ煙を入れる
     * （{@code LevelStageHooks#beforePackComposite}。ヘッドライトの路面の後）。霧・ブルーム・トーンマップは
     * パックが煙の上にも掛ける。明るさは H メニューの倍率（{@code smokePackBrightness}）。
     */
    public static void renderBeforePackComposite(CameraRenderState camera, GpuTextureView target) {
        drawnBeforePackComposite = false;
        if (camera == null || !camera.initialized || PUFFS.count() == 0 || MirrorRenderer.isRendering() || !usable()) {
            return;
        }
        try {
            prepare(camera);
            draw(target.texture(), target, (float) ClientConfig.smokePackBrightness, false);
            drawnBeforePackComposite = true;
        } catch (RuntimeException e) {
            fail("タイヤの煙をシェーダーパックの仕上げ前に描けなかった。以後は粒の煙で代用する", e);
        }
    }

    /**
     * 塊を縮小した枠へ描き、深度を見て拡大しながら主画面へ重ねる。
     *
     * @param mainColor     重ねる先の色（主画面か、パックの仕上げ前の絵）
     * @param mainColorView その view
     * @param brightness    重ねるときの明るさの倍率（パック使用中だけ 1 以外）
     * @param fog           バニラの霧を掛けるか（パック使用中はパックが掛ける）
     */
    private static void draw(GpuTexture mainColor, GpuTextureView mainColorView, float brightness, boolean fog) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        GpuTextureView mainDepthView = main.getDepthTextureView();
        if (mainColor == null || mainColorView == null || mainDepthView == null) {
            return;
        }
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fogBuffer = RenderSystem.getShaderFog();
        if (projection == null || fogBuffer == null) {
            return;
        }
        int outWidth = main.width;
        int outHeight = main.height;
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();

        int puffs = upload(device, encoder, framePartialTick);
        if (puffs == 0) {
            return;
        }
        // ランプがあればランプの口のあるパイプラインで（灯数は HeadlightVolume が決める。昼・ランプ無しなら 0）
        boolean lamps = HeadlightVolume.smokeLampCount() > 0;
        CompiledRenderPipeline puffPipeline = RenderSystem.getCompiledPipelineNullable(lamps ? PUFF_LAMP_PIPELINE : PUFF_PIPELINE);
        CompiledRenderPipeline compositePipeline = RenderSystem.getCompiledPipelineNullable(compositePipeline(mainColor.getFormat()));
        if (puffPipeline == null || compositePipeline == null) {
            fail("タイヤの煙のパイプラインを作れなかった（理由は直前のシェーダーのログ）。粒の煙で代用する", null);
            return;
        }

        int divisor = ClientConfig.smokeQuality.divisor();
        ensureLowTarget(device, Math.max(1, outWidth / divisor), Math.max(1, outHeight / divisor));
        // 重ね塗りが多すぎるフレームは、同じ枠の左下だけを使って 1 段粗く描く（枠を作り直さない）
        int drawWidth = lowWidth;
        int drawHeight = lowHeight;
        if (overdraw > OVERDRAW_COARSE) {
            drawWidth = Math.min(lowWidth, Math.max(1, outWidth / (divisor + 1)));
            drawHeight = Math.min(lowHeight, Math.max(1, outHeight / (divisor + 1)));
        }
        int baseSteps = ClientConfig.smokeQuality.steps();
        int steps = overdraw > OVERDRAW_BUDGET
                ? Math.max(2, (int) Math.round(baseSteps * OVERDRAW_BUDGET / overdraw))
                : baseSteps;
        GpuTextureView noise = noiseView(device, encoder);

        if (infoUbo == null) {
            infoUbo = new MappableRingBuffer(() -> "KurumaMod smoke info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, INFO_SIZE);
        }
        GpuBuffer info = infoUbo.currentBuffer();
        writeInfo(info, level, drawWidth, drawHeight, steps, noise != null, fog, brightness, outWidth, outHeight);

        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        // まだノイズが無いときも何かを結ぶ（宣言した口はすべて結ぶ）。シェーダーは NoiseReady を見て使わない
        GpuTextureView noiseOrPlaceholder = noise != null ? noise : puffView;

        // ---- 塊を縮小した枠へ（奥から順に、色に α を掛けた形で上へ重ねる） ----
        // 描く範囲は枠の左下だけ（重ね塗りが多いフレームは枠より小さい）。シザーで絞り、頂点シェーダーが
        // そこへ寄せる（バニラの RenderPass にはビューポートを変える口が無い）
        try (RenderPass pass = encoder.createRenderPass(() -> "KurumaMod tire smoke", lowView,
                Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 0.0F)), null, OptionalDouble.empty(),
                new RenderPass.RenderArea(0, 0, drawWidth, drawHeight))) {
            pass.setPipeline(puffPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(INFO_BLOCK, info);
            pass.setUniform("PuffSampler", puffView, nearest);
            pass.setUniform("DepthSampler", mainDepthView, nearest);
            pass.setUniform("LightMapSampler", minecraft.gameRenderer.levelLightmap(), linear);
            pass.setUniform("NoiseSampler", noiseOrPlaceholder, linear);
            if (lamps && HeadlightVolume.bindLampsForSmoke(pass) == 0) {
                // 灯数を聞いてから結ぶまでの間に 0 になることは無いはずだが、結ばずに描くと落ちうるので描かない
                return;
            }
            pass.draw(puffs * 6, 1, 0, 0);
        }

        // ---- 拡大して主画面へ重ねる ----
        try (RenderPass pass = encoder.createRenderPass(() -> "KurumaMod tire smoke composite", mainColorView,
                Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(compositePipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(INFO_BLOCK, info);
            pass.setUniform("SmokeSampler", lowView, nearest);
            pass.setUniform("DepthSampler", mainDepthView, nearest);
            pass.draw(3, 1, 0, 0);
        }
        infoUbo.rotate();

        if (!announced) {
            // 煙が見えないときに「描いていない」のか「描いているが見えない」のかを切り分けるため
            announced = true;
            LOGGER.info("タイヤの煙を体積で描き始めた（{} 塊、{}x{} → {}x{}、{}）", puffs, lowWidth, lowHeight,
                    outWidth, outHeight, device.getDeviceInfo().isZZeroToOne() ? "深度 0..1" : "深度 -1..1");
        }
    }

    /** 煙のシェーダーが共有する値（{@code tire_smoke_info.glsl} の {@code SmokeInfo}。並びを揃えること）。 */
    private static void writeInfo(GpuBuffer info, ClientLevel level, int drawWidth, int drawHeight, int steps,
                                  boolean noiseReady, boolean fog, float brightness, int outWidth, int outHeight) {
        float[] sun = sunLight(level, framePartialTick);
        // ヘッドライトのランプは HeadlightInfo のカメラ基準なので、煙の側のカメラも同じ分け方で渡す
        BlockPos cameraBlock = BlockPos.containing(frameCamera);
        try (GpuBufferSlice.MappedView view = info.slice().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(VIEW)
                    .putVec4(drawWidth, drawHeight, steps, noiseReady ? 1.0F : 0.0F)
                    .putVec4(sun[0], sun[1], sun[2], fog ? 1.0F : 0.0F)
                    .putVec4(sun[3], sun[4], sun[5], brightness)
                    .putVec4(lowWidth, lowHeight, outWidth, outHeight)
                    .putVec4(cameraBlock.getX(), cameraBlock.getY(), cameraBlock.getZ(), 0.0F)
                    .putVec4((float) (frameCamera.x - cameraBlock.getX()), (float) (frameCamera.y - cameraBlock.getY()),
                            (float) (frameCamera.z - cameraBlock.getZ()), 0.0F);
        }
    }

    /**
     * 見えている塊を奥から順に並べてテクスチャへ送る。送った数を返す。
     * 並べ替えは距離と番号を 1 つの long に詰めて、プリミティブのまま整列する（ソートで物を作らない）。
     */
    private static int upload(GpuDevice device, CommandEncoder encoder, float partialTick) {
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
        int bytes = rows * PUFFS_PER_ROW * TEXELS_PER_PUFF * BYTES_PER_TEXEL;
        if (puffBuffer == null || puffBuffer.capacity() < bytes) {
            if (puffBuffer != null) {
                MemoryUtil.memFree(puffBuffer);
            }
            puffBuffer = MemoryUtil.memAlloc(bytes);
        }
        ByteBuffer buffer = puffBuffer;
        buffer.clear();
        // 遠いものから（並びの後ろから）
        for (int k = visible - 1; k >= 0; k--) {
            int i = (int) order[k];
            double r = PUFFS.radius(i, partialTick);
            buffer.putFloat((float) (PUFFS.renderX(i, partialTick) - camera.x))
                    .putFloat((float) (PUFFS.renderY(i, partialTick) - camera.y))
                    .putFloat((float) (PUFFS.renderZ(i, partialTick) - camera.z))
                    .putFloat((float) r);
            buffer.putFloat((float) (PUFFS.extinction(i, partialTick) * opacity))
                    .putFloat((float) PUFFS.ageSeconds(i, partialTick))
                    .putFloat(PUFFS.seed(i))
                    .putFloat(PUFFS.ambientOcclusion(i));
            buffer.putFloat(PUFFS.red(i)).putFloat(PUFFS.green(i)).putFloat(PUFFS.blue(i)).putFloat(PUFFS.flatten(i));
            buffer.putFloat(PUFFS.blockLight(i)).putFloat(PUFFS.skyLight(i))
                    .putFloat(PUFFS.sunVisibility(i) * PUFFS.sunOcclusion(i)).putFloat(0.0F);
        }
        while (buffer.position() < bytes) {
            buffer.putFloat(0.0F);
        }
        buffer.flip();

        if (puffTexture == null || puffTextureRows < rows) {
            if (puffTexture != null) {
                puffView.close();
                puffTexture.close();
            }
            puffTextureRows = Math.max(rows, (ClientConfig.smokeQuality.maxPuffs() + PUFFS_PER_ROW - 1) / PUFFS_PER_ROW);
            puffTexture = device.createTexture("KurumaMod smoke puffs", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA32_FLOAT, PUFFS_PER_ROW * TEXELS_PER_PUFF, puffTextureRows, 1, 1);
            puffView = device.createTextureView(puffTexture);
        }
        encoder.writeToTexture(puffTexture, buffer, 0, 0, 0, 0, PUFFS_PER_ROW * TEXELS_PER_PUFF, rows);
        return visible;
    }

    /** 縮小した煙の枠（色に α を掛けた形を、重ねても潰れないよう浮動小数で持つ）。 */
    private static void ensureLowTarget(GpuDevice device, int width, int height) {
        if (lowTexture != null && lowWidth == width && lowHeight == height) {
            return;
        }
        if (lowTexture != null) {
            lowView.close();
            lowTexture.close();
        }
        lowTexture = device.createTexture("KurumaMod smoke low", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
        lowView = device.createTextureView(lowTexture);
        lowWidth = width;
        lowHeight = height;
    }

    // ------------------------------------------------------------------
    // 日と月
    // ------------------------------------------------------------------

    /**
     * 光の来る向き（昼は日、夜は月）。空の無い次元では真上。
     * バニラの空と同じ向き（Y 軸で -90 度回してから、太陽の角度ぶん X 軸で回す。{@code SkyRenderer}）。
     */
    private static float[] lightDirection(ClientLevel level, float partialTick) {
        float[] light = sunLight(level, partialTick);
        return new float[]{light[0], light[1], light[2]};
    }

    /**
     * 日（夜は月）の向きと色（向き 3 つ・色 3 つ）。角度は 26.3 の環境の値（{@code SUN_ANGLE}・{@code MOON_ANGLE}）。
     * 色は昼の割合（空の明るさの段。1.21.1 の {@code getSkyDarken(partialTick)} の代わり）と雨で弱め、
     * 低いときは夕日の色へ寄せる。空の明るさは別にライトマップで掛かる。
     */
    private static float[] sunLight(ClientLevel level, float partialTick) {
        if (!level.dimensionType().hasSkyLight()) {
            return new float[]{0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F};
        }
        Camera mainCamera = Minecraft.getInstance().gameRenderer.mainCamera();
        EnvironmentAttributeProbe probe = mainCamera.attributeProbe();
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) * Mth.DEG_TO_RAD;
        float sunX = -Mth.sin(sunAngle);
        float sunY = Mth.cos(sunAngle);
        float skyLevel = probe.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, partialTick) / 15.0F;
        float day = Mth.clamp((skyLevel - 0.2F) / 0.8F, 0.0F, 1.0F);
        float weather = 1.0F - 0.75F * level.getRainLevel(partialTick);
        if (sunY >= -0.05F) {
            float high = Mth.clamp(sunY / 0.3F, 0.0F, 1.0F);
            high = high * high * (3.0F - 2.0F * high);
            float strength = day * weather;
            return new float[]{sunX, Math.max(sunY, 0.02F), 0.0F,
                    strength, strength * Mth.lerp(high, 0.62F, 0.97F), strength * Mth.lerp(high, 0.38F, 0.92F)};
        }
        // 月。夜の空の明るさ（ライトマップ）が暗いので、色は控えめでよい
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick) * Mth.DEG_TO_RAD;
        float moonX = -Mth.sin(moonAngle);
        float moonY = Math.max(Mth.cos(moonAngle), 0.02F);
        float strength = 0.35F * weather;
        return new float[]{moonX, moonY, 0.0F, strength * 0.7F, strength * 0.8F, strength};
    }

    // ------------------------------------------------------------------
    // 登録
    // ------------------------------------------------------------------

    /**
     * 読み込み画面のうちにパイプラインをコンパイルさせる口（{@code ClientHooks#registerShaders} から呼べる）。
     * 呼ばなくても、初めて煙を出すときにその場でコンパイルされる（少し引っかかるだけ）。
     */
    public static final class Registration {
        private Registration() {
        }

        public static void onRegisterShaders(ShaderRegistrar registrar) {
            registrar.registerPipeline(PUFF_PIPELINE);
            registrar.registerPipeline(PUFF_LAMP_PIPELINE);
            // 主画面のふつうの形式。ほかの形式は使うときに作る
            registrar.registerPipeline(compositePipeline(GpuFormat.RGBA8_UNORM));
        }
    }
}
