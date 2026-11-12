package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.Platform;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.buffers.Std140Builder;
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
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * ヘッドライトの光を<b>画面の画素ごとに</b>解く（{@code shaders/core/headlight_volume.fsh}）。26.3 版。
 *
 * <h2>何をしているか</h2>
 *
 * <p>世界を描き終えたところ（{@code LevelRenderer#render} の終わり。フレームグラフは実行済みで、
 * 主画面に世界が描き終わっている）で主画面の色を写し取り、画面いっぱいの三角形を 1 枚描く。
 * シェーダーは画素ごとに:</p>
 * <ul>
 *   <li><b>路面</b> … 深度から位置と面の向きを組み直し、配光から照度を求めてその画素を明るくする。
 *       見えている面にしか乗らないので、ブロックでも車でも人でも、形どおりに照らされる。
 *       影は点からランプまでブロックの格子を辿って決める（{@link HeadlightShadows}）</li>
 *   <li><b>霧の中の光線</b> … カメラから見えている面までの視線を刻み、光の円錐の中で
 *       霧がカメラへ散らす光を足す。影は距離の地図で見る</li>
 * </ul>
 *
 * <p>形を積んで描く {@link CarLights} の路面と光の筋は、これが使えるときは描かない
 * （二重に明るくなる）。光芒（レンズの滲み）はどちらの場合も {@link CarLights} が描く。</p>
 *
 * <h2>1.21.1 との違い</h2>
 *
 * <ul>
 *   <li>OpenGL を直接呼ばず renderpearl（{@link RenderPipeline}・{@link RenderPass}）で描く。
 *       OpenGL と Vulkan のどちらでも動く</li>
 *   <li><b>深度は逆向き</b>（手前ほど大きい、空が 0）。シェーダーの組み直しを合わせてある</li>
 *   <li><b>射影はシェーダーが Projection のブロックから取る。</b>{@link CameraRenderState#projectionMatrix} には
 *       画面の揺れが入っていないため（{@code headlight_volume.vsh}）。視点の回転の逆だけ Java から渡す</li>
 *   <li>値はばらの uniform ではなく 1 つの UBO（{@code HeadlightInfo}、{@code include/headlight_info.glsl}）</li>
 *   <li><b>粒の直前の写しは取っていない。</b>26.3 には 1.21.1 の {@code AFTER_TRIPWIRE_BLOCKS} に当たる段が無いので、
 *       描き終えた画面 1 枚で「粒の前」と「粒の後」を兼ねている（シェーダーの煙の扱いは働かない）</li>
 * </ul>
 *
 * <h2>使えないとき</h2>
 *
 * <p>パイプラインのコンパイルや描画に失敗したら、ログを 1 回残して {@link #isActive()} を false にし、
 * {@link CarLights} の形を積む代用に落ちる。シェーダーパックが使われていて、設定で「使用中も光を描く」を
 * 切っているときも描かない。<b>ミラーの描画中は描かない</b>（写し取る画面が主画面ではない）。</p>
 */
public final class HeadlightVolume {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * ランプを集める入れ物の大きさ。<b>シェーダー側に灯数の決め打ちは無い</b>（ランプはテクスチャで渡す。
     * {@link HeadlightShadows#uploadLamps}）。実際に描く台数は設定
     * （{@link ClientConfig#headlightCars}、無制限あり）で絞り、天井は GPU の
     * テクスチャの大きさ（{@link HeadlightShadows#maxLamps}）。
     */
    private static final int MAX_LAMPS = HeadlightShadows.LAMP_CAP;
    /** これより遠い車の光は計算しない。 */
    private static final double MAX_DISTANCE = 128.0;
    /** 車 1 台が出す光の数の上限（ヘッドライト 2 灯とナンバー灯 1 灯）。 */
    private static final int LAMPS_PER_CAR = 3;

    /** 光を描く車を集める距離。128m か、光の届く距離 +32m の大きい方（96m 以下では 128m のまま）。 */
    private static double collectDistance() {
        return Math.max(MAX_DISTANCE, ClientConfig.headlightRange + 32.0);
    }

    /** 霧の濃さ（1m あたり）。晴れの濃さは設定（Ctrl+L）、雨と雷で足す。 */
    private static final float RAIN_FOG = 0.05F;
    private static final float THUNDER_FOG = 0.03F;

    /** 明るさ 1.0 のときの、照らされた路面を何倍まで明るくするか。 */
    private static final float SURFACE_GAIN = 5.0F;
    /** 明るさ 1.0 のときの、霧の中のビームの強さ。 */
    private static final float VOLUME_GAIN = 120.0F;

    /** {@code HeadlightInfo} の大きさ（mat4 1 つと vec4 7 つ）。並びは {@link #writeInfo} と headlight_info.glsl。 */
    private static final int INFO_SIZE = 64 + 7 * 16;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Kurumamod.MODID, path);
    }

    /** ランプ・影の格子・UBO。光と影の地図の両方が読む（タイヤの煙も読めるように名前を揃えておく）。 */
    private static final BindGroupLayout LAMP_LAYOUT = BindGroupLayout.builder()
            .withUniform("HeadlightInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("LampSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("VoxelSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    /** 画面いっぱいに光を足す。色はシェーダーが全部決めるので混色も深度も要らない。描き先は主画面（RGBA8）。 */
    private static final RenderPipeline VOLUME_PIPELINE = volumeBuilder("pipeline/headlight_volume")
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    /**
     * 同じものを浮動小数の描き先へ（シェーダーパックの仕上げ前の絵。{@link IrisScene#target()}）。
     * パイプラインは描き先の色の形式ごとに要る。
     */
    private static final RenderPipeline VOLUME_PIPELINE_HDR = volumeBuilder("pipeline/headlight_volume_hdr")
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static RenderPipeline.Builder volumeBuilder(String location) {
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id(location))
            .withVertexShader(id("core/headlight_volume"))
            .withFragmentShader(id("core/headlight_volume"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(LAMP_LAYOUT)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("FinalDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("SolidSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("ShadowSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withCull(false);
    }

    /** ランプから見た距離の地図を描く（{@link HeadlightShadows}）。 */
    private static final RenderPipeline SHADOW_PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/headlight_shadow"))
            .withVertexShader(id("core/headlight_shadow"))
            .withFragmentShader(id("core/headlight_shadow"))
            .withBindGroupLayout(LAMP_LAYOUT)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            .build();

    /** コンパイルか描画に一度でも失敗した。以後は {@link CarLights} の代用に任せる。 */
    private static boolean failed;
    private static CompiledRenderPipeline volumeCompiled;
    private static CompiledRenderPipeline shadowCompiled;

    /**
     * 描き先の色の写し。シェーダーはこれを読み、描き先へ書く（書いている最中の画面は同時に読めない）。
     * 描き先の形式ごとに 1 枚（パック使用中は仕上げ前の RGBA16F と主画面の RGBA8 を同じフレームで使うので、
     * 1 枚を使い回すと毎フレーム 2 回作り直すことになる）。
     */
    private static final java.util.Map<GpuFormat, GpuTexture> COPIES = new java.util.EnumMap<>(GpuFormat.class);
    private static final java.util.Map<GpuFormat, GpuTextureView> COPY_VIEWS = new java.util.EnumMap<>(GpuFormat.class);

    /** {@code HeadlightInfo} の入れ物。3 つを回して、GPU が読んでいる最中のものに書かない。 */
    private static MappableRingBuffer infoRing;
    /** 最後に詰めた {@code HeadlightInfo}（タイヤの煙が次のフレームで読む）。 */
    private static GpuBuffer lastInfo;
    /** 最後に光を描いたときの灯数。0 なら影の地図もランプも用意していない。 */
    private static int lastLampCount;
    /** 最後に光を描いたフレーム（{@link #bindLampsForSmoke} が古い値を渡さないため）。 */
    private static long lastFrame = -1;
    private static long frame;
    /** 影の地図を描いたフレーム（仕上げ前と後の 2 回に分けて描くとき、2 回目は使い回す）。 */
    private static long mapFrame = -1;
    /** このフレームはシェーダーパックの仕上げ前に路面を描いた。後では光線だけを足す（PackMode 2）。 */
    private static boolean packSurfaceDone;
    /** 仕上げ前に集めた灯数（後で光線を足すときに使い回す）。 */
    private static int packLampCount;

    private static final Matrix4f[] LAMPS = new Matrix4f[MAX_LAMPS];
    private static boolean announced;

    private HeadlightVolume() {
    }

    /**
     * 画素ごとの光が使えるか。使えないなら {@link CarLights} が形を積んで代用する。
     *
     * <p>パイプラインは {@link #levelEnd} で初めてコンパイルするので、それまでは「使える」ものとして扱う
     * （失敗が分かった次のフレームから代用に切り替わる）。</p>
     */
    public static boolean isActive() {
        return !failed && !suppressedByShaderPack();
    }

    /**
     * パイプラインをコンパイルする（初回だけ。結果は Minecraft のパイプラインの置き場が覚える）。
     * 資源の読み直し（F3+T）で置き場ごと作り直されたときは、次に聞いたときにまたコンパイルされる。
     */
    private static boolean ensureCompiled() {
        if (failed) {
            return false;
        }
        try {
            volumeCompiled = RenderSystem.getCompiledPipelineNullable(VOLUME_PIPELINE);
            shadowCompiled = RenderSystem.getCompiledPipelineNullable(SHADOW_PIPELINE);
        } catch (RuntimeException e) {
            fail("ヘッドライトのパイプラインをコンパイルできなかった", e);
            return false;
        }
        if (volumeCompiled == null || shadowCompiled == null) {
            // 失敗の中身（シェーダーの文法など）は Minecraft の側が直前にログへ出している
            fail("ヘッドライトのパイプラインをコンパイルできなかった（直前のログの Couldn't compile pipeline を参照）", null);
            return false;
        }
        return true;
    }

    private static void fail(String message, Exception e) {
        failed = true;
        lastLampCount = 0;
        LOGGER.error("{}。以後は形を積む描き方（CarLights）で代用する", message, e);
    }

    /**
     * ワールドを描き終えたところで呼ぶ（{@code LevelStageHooks#levelEnd}）。光を主画面に描き足す。
     * <b>例外は外へ出さない。</b>失敗したら代用に切り替える。
     */
    /** ワールドを描き始めるところ（{@code LevelStageHooks#levelStart}）。ミラーの描画中は呼ばれない。 */
    public static void levelStart() {
        frame++;
        packSurfaceDone = false;
        packLampCount = 0;
    }

    public static void levelEnd(CameraRenderState camera) {
        if (failed || !camera.initialized || MirrorRenderer.isRendering()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        // コンパイルはランプが無くても最初のフレームで済ませる（失敗を早くログに出すため）
        if (!ensureCompiled() || suppressedByShaderPack()) {
            return;
        }
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (main.getColorTextureView() == null || main.getDepthTextureView() == null) {
            return;
        }
        try {
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            // パックの仕上げ前に路面を描いていれば、ランプはそのとき集めたもの
            int count = packSurfaceDone ? packLampCount : collect(level, camera.pos, partialTick);
            if (count == 0) {
                lastLampCount = 0;
                return;
            }
            // 主画面の色を写し、それを読んで主画面へ書く。パック使用中は仕上がった絵に重ねる:
            // 路面を仕上げ前に入れていれば光線だけ（PackMode 2）、入れられなかったら両方（PackMode 3）
            float packMode = packSurfaceDone ? 2.0F : ShaderPacks.inUse() ? 3.0F : 0.0F;
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            GpuTextureView scene = snapshot(encoder, main.getColorTexture());
            render(encoder, level, camera, count, partialTick, main.getColorTextureView(), scene,
                    main.getDepthTextureView(), packMode);
        } catch (RuntimeException e) {
            fail("ヘッドライトを描けなかった", e);
        }
    }

    /**
     * 光を描く。<b>描く処理はここ 1 つ</b>（後でシェーダーパックの仕上げの前へ入れるときも、これを呼ぶ）。
     *
     * @param target    書く先（色）。RGBA8（主画面）か RGBA16F（パックの仕上げ前の絵）
     * @param scene     照らす前の絵（{@code target} の写し。{@code target} そのものは読めない）
     * @param depth     世界の深度（逆向き）
     * @param packMode  シェーダーの PackMode（0 = パック無し、1 = パックの仕上げ前に路面だけ、
     *                  2 = 仕上がった絵に光線だけ、3 = 仕上がった絵に両方）
     */
    static void render(CommandEncoder encoder, ClientLevel level, CameraRenderState camera, int count, float partialTick,
                       GpuTextureView target, GpuTextureView scene, GpuTextureView depth, float packMode) {
        CompiledRenderPipeline pipeline = target.texture().getFormat() == GpuFormat.RGBA16_FLOAT
                ? RenderSystem.getCompiledPipelineNullable(VOLUME_PIPELINE_HDR) : volumeCompiled;
        if (pipeline == null) {
            throw new IllegalStateException("ヘッドライトのパイプラインが無い（" + target.texture().getFormat() + "）");
        }
        // 影。ブロックの格子を少し読み直し、ランプを送ってから、ランプから見た距離の地図を描く。
        // 同じフレームの 2 回目（パックの仕上げの前と後に分けて描くとき）は使い回す
        boolean mapReady = mapFrame == frame;
        if (!mapReady) {
            HeadlightShadows.update(encoder, level, camera.pos);
            HeadlightShadows.uploadLamps(encoder, LAMPS, count);
        }

        if (infoRing == null) {
            infoRing = new MappableRingBuffer(() -> "kurumamod HeadlightInfo",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, INFO_SIZE);
        }
        GpuBuffer info = infoRing.currentBuffer();
        try (GpuBufferSlice.MappedView view = info.map(false, true)) {
            writeInfo(Std140Builder.intoBuffer(view.data()), level, camera, count, partialTick,
                    target.getWidth(0), target.getHeight(0), packMode);
        }

        if (!mapReady) {
            HeadlightShadows.renderMap(encoder, shadowCompiled, info, count);
            mapFrame = frame;
        }

        try (RenderPass pass = encoder.createRenderPass(() -> "kurumamod headlight volume", target,
                Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            // Projection（ワールドを描いたときの射影。画面の揺れ込み）と Globals
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("HeadlightInfo", info);
            pass.setUniform("LampSampler", HeadlightShadows.lampView(), HeadlightShadows.nearest());
            pass.setUniform("VoxelSampler", HeadlightShadows.voxelView(), HeadlightShadows.nearest());
            pass.setUniform("ShadowSampler", HeadlightShadows.mapView(), HeadlightShadows.nearest());
            // 26.3 には粒の直前の段が無いので、「粒の前」と「粒の後」は同じ絵・同じ深度
            pass.setUniform("SceneSampler", scene, HeadlightShadows.nearest());
            pass.setUniform("SolidSampler", scene, HeadlightShadows.nearest());
            pass.setUniform("FinalDepthSampler", depth, HeadlightShadows.nearest());
            pass.setUniform("DepthSampler", depth, HeadlightShadows.nearest());
            pass.draw(3, 1, 0, 0);
        }
        infoRing.rotate();
        lastInfo = info;
        lastLampCount = count;
        lastFrame = frame;
        if (!announced) {
            // 光が見えないときに「描いていない」のか「描いているが外れている」のかを切り分けるため
            announced = true;
            LOGGER.info("ヘッドライトを画素ごとに描き始めた（{} 灯、{}x{}、{}）", count, target.getWidth(0), target.getHeight(0),
                    RenderSystem.getDevice().getDeviceInfo().backendName());
        }
    }

    /**
     * {@code HeadlightInfo} を詰める。<b>並びは {@code include/headlight_info.glsl} と同じ</b>（std140）。
     */
    private static void writeInfo(Std140Builder out, ClientLevel level, CameraRenderState camera, int count,
                                  float partialTick, int width, int height, float packMode) {
        Vec3 pos = camera.pos;
        BlockPos block = BlockPos.containing(pos);
        // 視点の回転の逆。射影の逆はシェーダーが Projection から作って掛ける
        out.putMat4f(new Matrix4f(camera.viewRotationMatrix).invert());
        out.putVec4(block.getX(), block.getY(), block.getZ(), count);
        out.putVec4((float) (pos.x - block.getX()), (float) (pos.y - block.getY()), (float) (pos.z - block.getZ()),
                (float) ClientConfig.headlightRange);
        out.putVec4(HeadlightShadows.grid(), HeadlightShadows.GRID_Y,
                HeadlightShadows.MAP_WIDTH, HeadlightShadows.mapHeight(count));
        out.putVec4(width, height, fogDensity(level, partialTick), packMode);
        // 明るさは 1 本のつまみ（Ctrl+L）で、路面とビームに同じ割合で掛ける
        float brightness = (float) ClientConfig.headlightBrightness;
        out.putVec4(SURFACE_GAIN * brightness, VOLUME_GAIN * brightness, (float) ClientConfig.headlightSmoke,
                (float) ClientConfig.headlightPackContrast);
        out.putVec4((float) ClientConfig.headlightPackTint, (float) ClientConfig.headlightPackAdd, 0.0F, 0.0F);
        float[] color = ClientConfig.headlightColor.rgb();
        out.putVec4(color[0], color[1], color[2], 0.0F);
    }

    /** 描き先の色を写しへ写す（大きさか形式が違えば作り直す）。 */
    private static GpuTextureView snapshot(CommandEncoder encoder, GpuTexture source) {
        int width = source.getWidth(0);
        int height = source.getHeight(0);
        GpuFormat format = source.getFormat();
        GpuTexture copy = COPIES.get(format);
        if (copy == null || copy.getWidth(0) != width || copy.getHeight(0) != height) {
            if (copy != null) {
                COPY_VIEWS.get(format).close();
                copy.close();
            }
            GpuDevice device = RenderSystem.getDevice();
            copy = device.createTexture("kurumamod headlight scene copy",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, format, width, height, 1, 1);
            COPIES.put(format, copy);
            COPY_VIEWS.put(format, device.createTextureView(copy));
        }
        encoder.copyTextureToTexture(source, copy, 0, 0, 0, 0, 0, width, height);
        return COPY_VIEWS.get(format);
    }

    /** 近い車から順に、ヘッドライトを点けている車のランプを詰める。 */
    private static int collect(ClientLevel level, Vec3 camera, float partialTick) {
        List<CarEntity> cars = new ArrayList<>();
        double reach = collectDistance();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car && car.isHeadlightOn() && !car.isGhost()
                    && car.position().distanceToSqr(camera) < reach * reach) {
                cars.add(car);
            }
        }
        cars.sort(Comparator.comparingDouble(car -> car.position().distanceToSqr(camera)));
        int wanted = ClientConfig.headlightCars;
        int limit = wanted >= ClientConfig.UNLIMITED_HEADLIGHT_CARS
                ? HeadlightShadows.maxLamps()
                : Math.min(HeadlightShadows.maxLamps(), Math.max(1, wanted) * LAMPS_PER_CAR);
        int count = 0;
        int taken = 0;
        for (CarEntity car : cars) {
            if (count >= limit || taken >= limit / LAMPS_PER_CAR) {
                break;
            }
            count += CarLights.collectLamps(car, partialTick, camera, LAMPS, count);
            taken++;
        }
        return Math.min(count, limit);
    }

    private static float fogDensity(ClientLevel level, float partialTick) {
        // 晴れの濃さは調整画面（Ctrl+L、HeadlightScreen）で動かせる。既定 0.025
        return (float) ClientConfig.headlightFog + RAIN_FOG * level.getRainLevel(partialTick)
                + THUNDER_FOG * level.getThunderLevel(partialTick);
    }

    // ---- タイヤの煙へランプを渡す口（1.21.1 の bindLampsForSmoke） ----

    /**
     * 煙のシェーダーが使える灯数。光を描いていない（昼・ランプ無し・失敗・パックのために止めている）なら 0。
     *
     * <p><b>渡すのは直前のフレームのランプと影</b>。煙は世界の描画の中（フレームグラフ）で描かれ、
     * こちらはその後（{@link #levelEnd}）で描くので、同じフレームのものはまだ無い。ランプの位置と
     * {@code CameraBlock}・{@code CameraFrac} は 1 フレーム前のカメラ基準なので、煙の側でも
     * {@code HeadlightInfo} の値（同じフレームのもの同士）を使うこと。2 フレーム以上空いたら 0。</p>
     */
    public static int smokeLampCount() {
        return !failed && lastInfo != null && frame - lastFrame <= 1 && isActive() ? lastLampCount : 0;
    }

    /**
     * 煙を描く {@link RenderPass} へ、ランプのテクスチャ（{@code LampSampler}）・影の地図（{@code ShadowSampler}）・
     * 影の格子（{@code VoxelSampler}）・{@code HeadlightInfo}（灯数・光の届く距離・光の色・格子と地図の大きさ・
     * カメラのブロック）を渡す。シェーダーは {@code #include <kurumamod:headlight_info.glsl>} で読む。
     * パイプラインには {@link #lampBindGroupLayout()} と {@code ShadowSampler} を足しておくこと。
     *
     * @return 渡した灯数（{@link #smokeLampCount()}）。0 なら何も渡していない
     */
    public static int bindLampsForSmoke(RenderPass pass) {
        int count = smokeLampCount();
        if (count == 0) {
            return 0;
        }
        pass.setUniform("HeadlightInfo", lastInfo);
        pass.setUniform("LampSampler", HeadlightShadows.lampView(), HeadlightShadows.nearest());
        pass.setUniform("VoxelSampler", HeadlightShadows.voxelView(), HeadlightShadows.nearest());
        pass.setUniform("ShadowSampler", HeadlightShadows.mapView(), HeadlightShadows.nearest());
        return count;
    }

    /** {@code HeadlightInfo}・{@code LampSampler}・{@code VoxelSampler} の並び（煙のパイプラインが足す）。 */
    public static BindGroupLayout lampBindGroupLayout() {
        return LAMP_LAYOUT;
    }

    // ---- シェーダーパック ----

    /**
     * シェーダーパックのために光を止めているか。<b>止めるのは、パックが使われていて、かつ設定で
     * 「使用中も光を描く」を切っているときだけ</b>（{@link ClientConfig#headlightWithShaderPacks}）。
     */
    public static boolean suppressedByShaderPack() {
        return !ClientConfig.headlightWithShaderPacks && ShaderPacks.inUse();
    }

    /**
     * シェーダーパックが<b>いま実際に使われているか</b>。
     *
     * <p>Iris のシェーダーは世界の描画そのものを差し替えるので、そこへ画面を写し取って重ねると
     * 見え方が変わる。<b>MOD が入っているかではなく、パックが有効か</b>で見る——入っているだけで止めると、
     * シェーダーを切って遊んでいる人まで光を失う。パックは遊んでいる途中に切り替えられるので毎回聞く。</p>
     *
     * <p>Iris の公開 API（{@code net.irisshaders.iris.api.v0.IrisApi}。Minecraft の名前ではないので
     * リフレクションで引いてよい）で聞く。<b>Iris が入っていなければ「使われていない」</b>、入っているのに
     * 聞けなかったら「使われている」とみなす（壊れた画面より、控えめな代用の方がまし）。</p>
     */
    static final class ShaderPacks {
        private static boolean resolved;
        private static java.lang.reflect.Method irisInUse;
        private static java.lang.reflect.Method irisShadowPass;
        private static Object irisApi;
        private static java.lang.reflect.Method optifineInUse;
        /** 聞き方が分からなかった（API が変わった）。安全側に倒す。 */
        private static boolean unknown;

        private ShaderPacks() {
        }

        static boolean inUse() {
            if (!resolved) {
                resolve();
            }
            if (unknown) {
                return true;
            }
            boolean inUse;
            try {
                inUse = (irisInUse != null && (Boolean) irisInUse.invoke(irisApi))
                        || (optifineInUse != null && (Boolean) optifineInUse.invoke(null));
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("シェーダーパックが使われているか聞けなかった。以後は使われているものとして扱う", e);
                unknown = true;
                return true;
            }
            if (last == null || last != inUse) {
                // 切り替わったときだけ残す。光が出ない・おかしいときに、どちらの描き方かを切り分けるため
                LOGGER.info(inUse
                        ? "シェーダーパックが使われている（光の効果を描くかは設定 headlightWithShaderPacks しだい）"
                        : "シェーダーパックは使われていない。ヘッドライトを画素ごとに描く");
                last = inUse;
            }
            return inUse;
        }

        private static Boolean last;

        /**
         * Iris が<b>影を描いている最中</b>か。パックは影のために光源の視点からもエンティティを
         * 描くので、その間に光芒を記録すると、光源の視点の座標が混ざって変な場所に描かれる。
         * 聞けなければ false（パック無しと同じ扱い）。
         */
        static boolean renderingShadowPass() {
            if (!resolved) {
                resolve();
            }
            if (irisShadowPass == null) {
                return false;
            }
            try {
                return (Boolean) irisShadowPass.invoke(irisApi);
            } catch (ReflectiveOperationException | RuntimeException e) {
                irisShadowPass = null;
                return false;
            }
        }

        private static void resolve() {
            resolved = true;
            boolean iris = Platform.isModLoaded("oculus") || Platform.isModLoaded("iris");
            if (iris) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = api.getMethod("getInstance").invoke(null);
                    irisInUse = api.getMethod("isShaderPackInUse");
                    irisShadowPass = api.getMethod("isRenderingShadowPass");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOGGER.warn("Oculus / Iris の API が見つからない。シェーダーパックが使われているものとして扱う", e);
                    unknown = true;
                }
            }
            try {
                // OptiFine は MOD 一覧に載らないので、クラスがあるかで見る
                Class<?> config = Class.forName("net.optifine.Config");
                optifineInUse = config.getMethod("isShaders");
            } catch (ClassNotFoundException e) {
                // OptiFine は入っていない
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("OptiFine のシェーダーの状態が聞けない。使われているものとして扱う", e);
                unknown = true;
            }
        }
    }

    /**
     * シェーダーパック（Iris）が仕上げ（composite・final）を流す<b>直前</b>に、仕上げ前の絵へ路面の照り返しを
     * 入れる（PackMode 1。{@code LevelStageHooks#beforePackComposite} から、{@link IrisScene#beginPreComposite()} の後に）。
     * ここならブロックの色が残った絵を明るくでき、パックに霧・ブルーム・トーンマップを一度だけ掛けてもらえる。
     * 光線（霧の中の光）は後で仕上がった絵に足す（PackMode 2、{@link #levelEnd}）。
     *
     * <p>設定 {@code headlightPackPreComposite} を切っていれば何もしない（{@link #levelEnd} が両方を描く）。
     * <b>例外は外へ出さない。</b></p>
     */
    public static void renderBeforePackComposite(CameraRenderState camera, GpuTextureView target) {
        if (failed || camera == null || !camera.initialized || MirrorRenderer.isRendering()
                || !ClientConfig.headlightPackPreComposite || !isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (level == null || main.getDepthTextureView() == null || !ensureCompiled()) {
            return;
        }
        try {
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            int count = collect(level, camera.pos, partialTick);
            packLampCount = count;
            packSurfaceDone = true;
            if (count == 0) {
                return;
            }
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            GpuTextureView scene = snapshot(encoder, target.texture());
            render(encoder, level, camera, count, partialTick, target, scene, main.getDepthTextureView(), 1.0F);
        } catch (RuntimeException e) {
            packSurfaceDone = false;
            fail("シェーダーパックの仕上げ前にヘッドライトを描けなかった", e);
        }
    }
}
