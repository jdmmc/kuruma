package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * シェーダーパックを使っている間の、光芒・タイヤ痕・ゲートの描き方。<b>ワールドの描画の流れには乗せず、
 * 頂点を記録しておいて、パックの手が届かないところで自前のパイプラインで描く。</b>
 *
 * <h2>なぜ</h2>
 * <p>パック使用中（Iris 26.3）に {@code SubmitNodeCollector} で出した面は、パックの gbuffer の枠へ描かれる。
 * 自前のパイプライン（{@link EffectRenderTypes}）は Iris の一覧に無いのでプログラムは差し替わらないが、
 * 書く先はパックが材質や法線を入れる複数の枠で、こちらのシェーダーが書かない枠の中身は決まらない。
 * 後段がそれを読むので、加算の光が<b>灰色の板や黒い面</b>になり、タイヤ痕やゲートは空に映った
 * （1.21.1 で踏んだのと同じ形）。そこで頂点だけを覚え、次の 2 か所で描く:</p>
 * <ul>
 *   <li>パックの仕上げの直前（{@code mixin/IrisPipelineMixin} → {@code LevelStageHooks#beforePackComposite}）。
 *       タイヤ痕とゲートを、パックの仕上げ前の絵（{@link IrisScene#target()}）へ</li>
 *   <li>ワールドを描き終えた後（{@code LevelStageHooks#levelEnd}）。光芒を仕上がった絵へ（1.21.1 の {@code AFTER_LEVEL}）。
 *       仕上げ前の絵が読めなかったときはタイヤ痕とゲートもここで</li>
 * </ul>
 *
 * <p>頂点はカメラを原点にした世界の向き（車を描くときの {@code PoseStack} を掛けた値）で覚える。描くときに
 * 視点の回転（{@code DynamicTransforms} の {@code ModelViewMat}）と射影（{@code Projection}）を掛ける。
 * シェーダーはバニラの {@code core/position_color} をそのまま使う。深度はパック無しと同じく逆向き
 * （{@link IrisScene} の説明を参照）なので、比較も同じ {@code GREATER_THAN_OR_EQUAL}。</p>
 *
 * <p><b>パックが無いときは使わない</b>（{@link #active()}）。バニラ・Sodium だけのときの見た目は変わらない。</p>
 */
public final class DeferredGlows {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 色の足し方。 */
    public enum Blend {
        /** 光を足す（色 × α）。光芒・光の筋。 */
        ADD(new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)),
        /** 路面の色に掛けて暗くする（α ぶん消す）。タイヤ痕。黒を重ねると夜や霧で浮くため */
        DARKEN(new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE)),
        /** ふつうの半透明。ゲート */
        TRANSLUCENT(new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE));

        final BlendFunction function;

        Blend(BlendFunction function) {
            this.function = function;
        }
    }

    /** 光芒・光の筋（加算）。 */
    public static final Batch GLOWS = new Batch();
    /** タイヤ痕（暗くする）。 */
    public static final Batch MARKS = new Batch();
    /** コースのゲート（半透明）。 */
    public static final Batch GATES = new Batch();

    /** パイプライン。足し方・描き先の色の形式・深度のずらしの有無ごと。 */
    private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();
    /** 描いたフレームの頂点バッファ。GPU が読み終えるまで次のフレームの頭まで閉じずに持つ。 */
    private static final List<GpuBuffer> IN_FLIGHT = new ArrayList<>();
    private static final List<GpuBuffer> RETIRING = new ArrayList<>();
    private static ByteBufferBuilder bytes;
    private static boolean failed;

    private DeferredGlows() {
    }

    /**
     * シェーダーパックを使っているか（このとき面を記録に回す）。光を描くかどうか（設定 {@code headlightWithShaderPacks}）は
     * 光の側（{@link CarLights}）が見る。タイヤ痕とゲートは設定に関わらずこちらへ回す。
     */
    public static boolean active() {
        return !failed && HeadlightVolume.ShaderPacks.inUse() && !MirrorRenderer.isRendering();
    }

    /** 光を描かずに捨てる場面か（パックが影を描いている最中）。そのときは記録も描画もしない。 */
    static boolean skip() {
        return HeadlightVolume.ShaderPacks.renderingShadowPass();
    }

    /**
     * ワールドを描き始めるところ（{@code LevelStageHooks#levelStart}）。前のフレームの描き残しを捨て、
     * 2 フレーム前に描いた頂点バッファを閉じる。ミラーの描画中は鏡の視点なので触らない。
     */
    public static void beginFrame() {
        if (MirrorRenderer.isRendering()) {
            return;
        }
        GLOWS.reset();
        MARKS.reset();
        GATES.reset();
        RETIRING.forEach(GpuBuffer::close);
        RETIRING.clear();
        RETIRING.addAll(IN_FLIGHT);
        IN_FLIGHT.clear();
    }

    /**
     * 記録した面を描く。
     *
     * @param color        描き先（色）
     * @param depth        深度（テストだけして書かない）。主画面の深度
     * @param viewRotation 視点の回転（{@code CameraRenderState#viewRotationMatrix}）
     * @param depthBias    手前へ寄せるか（路面に貼るタイヤ痕。パックの TAA のずらしと張り合わないように）
     */
    public static void draw(Batch batch, Blend blend, GpuTextureView color, GpuTextureView depth, Matrix4f viewRotation,
                     boolean depthBias) {
        batch.commit();
        if (batch.count < 4 || failed) {
            batch.count = 0;
            return;
        }
        int vertices = batch.count - batch.count % 4;
        try {
            CompiledRenderPipeline pipeline = RenderSystem.getCompiledPipelineNullable(
                    pipeline(blend, color.texture().getFormat(), depthBias));
            if (pipeline == null) {
                fail("光芒・タイヤ痕のパイプラインを作れなかった（直前のシェーダーのログを参照）", null);
                return;
            }
            if (bytes == null) {
                bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION_COLOR.getVertexSize() * 1024);
            }
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float[] d = batch.data;
            for (int i = 0; i < vertices; i++) {
                int o = i * Batch.STRIDE;
                builder.addVertex(d[o], d[o + 1], d[o + 2]).setColor(d[o + 3], d[o + 4], d[o + 5], d[o + 6]);
            }
            int indexCount;
            GpuBuffer vertexBuffer;
            try (MeshData mesh = builder.buildOrThrow()) {
                indexCount = mesh.drawState().indexCount();
                vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "kurumamod deferred glows",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
            IN_FLIGHT.add(vertexBuffer);
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(indexCount);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(viewRotation));
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "kurumamod deferred glows", color, Optional.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, vertexBuffer.slice());
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
            }
        } catch (RuntimeException e) {
            fail("光芒・タイヤ痕を描けなかった", e);
        } finally {
            batch.count = 0;
        }
    }

    private static void fail(String message, Exception e) {
        failed = true;
        LOGGER.error("{}。以後シェーダーパック使用中は光芒・タイヤ痕・ゲートを描かない", message, e);
    }

    private static RenderPipeline pipeline(Blend blend, GpuFormat format, boolean depthBias) {
        String key = blend.name() + "_" + format.name() + (depthBias ? "_bias" : "");
        return PIPELINES.computeIfAbsent(key, k -> RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Kurumamod.MODID,
                        "pipeline/deferred_" + k.toLowerCase(java.util.Locale.ROOT)))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                // 深度は見るが書かない。深度は逆向き（手前ほど大きい）なので、手前へ寄せるのは正の向き
                .withDepthStencilState(depthBias
                        ? new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 2.0F, 4.0F)
                        : new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(Optional.of(blend.function), format, ColorTargetState.WRITE_ALL))
                .withCull(false)
                .build());
    }

    /**
     * 頂点を記録するだけの書き込み先。QUADS 前提なので 4 頂点で 1 枚。
     *
     * <p>{@link VertexConsumer} には「頂点の終わり」が無く、<b>次の {@code addVertex} が前の頂点を確定させる</b>。
     * 最後の 1 頂点は {@link #commit()} で確定させてから描くこと（{@link #draw} がする）。</p>
     */
    public static final class Batch implements VertexConsumer {
        private static final int STRIDE = 7;
        private float[] data = new float[STRIDE * 1024];
        private int count;
        private boolean pending;
        private float x;
        private float y;
        private float z;
        private float r = 1.0F;
        private float g = 1.0F;
        private float b = 1.0F;
        private float a = 1.0F;

        void reset() {
            pending = false;
            count = 0;
        }

        boolean isEmpty() {
            return count == 0 && !pending;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            commit();
            this.x = x;
            this.y = y;
            this.z = z;
            r = g = b = a = 1.0F;
            pending = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            r = red / 255.0F;
            g = green / 255.0F;
            b = blue / 255.0F;
            a = alpha / 255.0F;
            return this;
        }

        @Override
        public VertexConsumer setColor(float red, float green, float blue, float alpha) {
            r = red;
            g = green;
            b = blue;
            a = alpha;
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >>> 24) & 0xFF);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        /** 積みかけの頂点を確定させる。 */
        void commit() {
            if (!pending) {
                return;
            }
            pending = false;
            if ((count + 1) * STRIDE > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            int o = count * STRIDE;
            data[o] = x;
            data[o + 1] = y;
            data[o + 2] = z;
            data[o + 3] = r;
            data[o + 4] = g;
            data[o + 5] = b;
            data[o + 6] = a;
            count++;
        }
    }
}
