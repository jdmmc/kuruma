package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * シェーダーパックを使っている間の光芒（レンズの前の滲み）。<b>世界を描き終えた後に、自前のシェーダーで足す。</b>
 *
 * <p>ふだんの光芒は車を描く途中で、加算の {@code RenderType}（{@link KurumaRenderTypes#LIGHT_ADDITIVE}）で
 * 積んでいる。シェーダーパック（Oculus / Iris）はこの描き方をパック自身のプログラムに差し替えるので、
 * <b>加算が普通の半透明の重ね合わせに化け、パックの陰影まで乗って、灰色の板と暗いトゲの模様として見えた</b>。
 * 一方、路面の照り返し（{@link HeadlightVolume}）は世界を描き終えた後に KurumaMod 自前のシェーダーで
 * 重ねていて、パックが手を出さないので崩れなかった。そこで光芒もその段へ回す。</p>
 *
 * <p>形の計算は {@link CarLights} のまま。書き込み先をここの記録器に替えるだけで、頂点は
 * 車を描いている時点の行列（カメラの向き込み）で変換済みの座標として残る。描き終えた後の段
 * （{@code AFTER_LEVEL}）の PoseStack にはカメラの向きが入っていないので、ここでは射影だけ掛ける。</p>
 *
 * <p><b>パックが無いときは使わない</b>（{@link #active()}）。バニラの見た目はそれまでと同じ。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class DeferredGlows {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static ShaderInstance shader;
    /** このフレームの頂点。x, y, z, r, g, b, a の 7 個ずつ（カメラ基準の座標）。 */
    private static float[] data = new float[7 * 1024];
    private static int count;
    private static final Recorder RECORDER = new Recorder();

    private DeferredGlows() {
    }

    /** シェーダーパックを使っていて、光を描く設定のときだけ。 */
    static boolean active() {
        ensureShader();
        return shader != null && HeadlightVolume.ShaderPacks.inUse() && !HeadlightVolume.suppressedByShaderPack()
                && !MirrorRenderer.isRendering();
    }

    /** 光芒を描かずに捨てる場面か（パックが影を描いている最中）。そのときは記録も描画もしない。 */
    static boolean skip() {
        return HeadlightVolume.ShaderPacks.renderingShadowPass();
    }

    private static boolean loadAttempted;

    /** シェーダーが無ければ自分で読む。理由は {@link HeadlightVolume#ensureShaders} と同じ。 */
    private static void ensureShader() {
        if (shader != null || loadAttempted) {
            return;
        }
        loadAttempted = true;
        try {
            shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                    new ResourceLocation(Kurumamod.MODID, "light_glow"), DefaultVertexFormat.POSITION_COLOR);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("光芒のシェーダーを読めなかった。シェーダーパック使用中の光芒は崩れたまま描く", e);
        }
    }

    /** 光芒の頂点を受け取る書き込み先。 */
    static VertexConsumer recorder() {
        return RECORDER;
    }

    @Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            loadAttempted = false;
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                                new ResourceLocation(Kurumamod.MODID, "light_glow"),
                                DefaultVertexFormat.POSITION_COLOR),
                        loaded -> shader = loaded);
            } catch (IOException e) {
                // 光芒がパックで崩れるだけで遊べなくはない。ふだんの描き方に落ちる
                LOGGER.error("光芒のシェーダーを読めなかった。シェーダーパック使用中の光芒は崩れたまま描く", e);
                shader = null;
            }
        }
    }

    /**
     * 世界を描き終えた後に足す。<b>路面の照り返し（{@link HeadlightVolume}）より後</b>——あちらは画面を写して
     * 描き直すので、先に足すと消される。優先度を下げて後に回している。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // 前のフレームの描き残しを捨てる（描き終えた後の段が飛ばなかったとき）
            if (!MirrorRenderer.isRendering()) {
                count = 0;
            }
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || count == 0 || shader == null) {
            return;
        }
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);

        // <b>ブレンドは OpenGL を直接呼んで掛ける。</b>Oculus は RenderSystem / GlStateManager のブレンドの
        // 呼び出しを横取りして固定するので、それを通すと加算が掛からず、光芒がベタ塗りの円盤と扇形に
        // なった（輪郭のぼかしの α が丸ごと無視された）。直接掛けて、描き終えたら元の状態に戻す。
        // GlStateManager の覚えている値とずれないよう、戻すのも直接（前の状態をそのまま書き戻す）
        boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int equation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515); // GL_LEQUAL
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> shader);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            int o = i * 7;
            builder.vertex(data[o], data[o + 1], data[o + 2])
                    .color(data[o + 3], data[o + 4], data[o + 5], data[o + 6])
                    .endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
        count = 0;

        GL14.glBlendEquation(equation);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        if (!blendWas) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
    }

    /** 頂点を記録するだけの書き込み先。QUADS 前提なので 4 頂点で 1 枚。 */
    private static final class Recorder implements VertexConsumer {
        private double x;
        private double y;
        private double z;
        private float r = 1.0F;
        private float g = 1.0F;
        private float b = 1.0F;
        private float a = 1.0F;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            r = red / 255.0F;
            g = green / 255.0F;
            b = blue / 255.0F;
            a = alpha / 255.0F;
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            if ((count + 1) * 7 > data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            int o = count * 7;
            data[o] = (float) x;
            data[o + 1] = (float) y;
            data[o + 2] = (float) z;
            data[o + 3] = r;
            data[o + 4] = g;
            data[o + 5] = b;
            data[o + 6] = a;
            count++;
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
