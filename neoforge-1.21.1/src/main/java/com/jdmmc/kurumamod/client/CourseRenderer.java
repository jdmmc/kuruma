package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.GateDisplay;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * コースの線をゲートとして描く。
 *
 * <p>線がどこにあるか分からないと走りようがないので、<b>面と 2 本の柱</b>で門のように見せる。
 * スタート／ゴールとチェックポイントで色を変えてある。</p>
 *
 * <p>見せる相手は「レース中なら全員」「そうでなければ車に乗っている人だけ」。歩いている
 * 人の視界を塞がないための切り分けで、タイムアタック中は自分にだけ見えることになる。</p>
 *
 * <p><b>シェーダーパック使用中は、パックが仕上げを流す直前にパックの絵へ自前のシェーダーで描き込む</b>
 * （{@link #renderBeforePackComposite()}）。理由はタイヤ痕（{@link CarSkidMarks}）と同じで、ふだんの
 * {@code RenderType} のまま描くとパックが自分のプログラムに差し替え、深度は奥のまま gbuffer（色と材質）だけを
 * ゲートで上書きするので、その食い違いを後段が読んで<b>ゲートが空に映った</b>。</p>
 */
@EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CourseRenderer {

    /** これより遠いゲートは描かない。 */
    private static final double VIEW_DISTANCE = 256.0;
    /** 柱の太さ [ブロック]。 */
    private static final double POST_WIDTH = 0.6;

    // スタート／ゴールは白、チェックポイントは青
    private static final float[] START_COLOR = {1.0F, 1.0F, 1.0F};
    private static final float[] CHECKPOINT_COLOR = {0.35F, 0.65F, 1.0F};
    private static final float PLANE_ALPHA = 0.16F;
    private static final float POST_ALPHA = 0.65F;

    private static List<CourseLinesPacket.Gate> gates = List.of();

    /** 配られてきたゲート。ゴーストが「いつスタートラインを跨いだか」を割り出すのに使う。 */
    public static List<CourseLinesPacket.Gate> gates() {
        return gates;
    }
    private static boolean raceActive;

    /**
     * シェーダーパック使用中に、仕上げの直前で描くゲートの頂点。x, y, z, r, g, b, a の 7 個ずつで、
     * {@link DeferredGlows#draw} の形（座標はカメラ基準）。
     */
    private static float[] deferred = new float[7 * 256];
    private static int deferredCount;
    private static final Matrix4f DEFERRED_PROJECTION = new Matrix4f();
    private static final Matrix4f DEFERRED_MODEL_VIEW = new Matrix4f();

    private CourseRenderer() {
    }

    public static void accept(CourseLinesPacket packet) {
        gates = packet.gates();
        raceActive = packet.raceActive();
    }

    /**
     * 接続が変わったら捨てる。
     *
     * <p><b>{@code static} なので放っておくと前のワールドのゲートがそのまま残る。</b>
     * 線は変わったときにしか配られない（{@code RaceSync}）ので、コースの無いワールドへ
     * 入っても上書きされず、<b>何も無い場所に門が立ち続ける</b>。</p>
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void reset() {
        gates = List.of();
        raceActive = false;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // 前のフレームの描き残しを捨てる（仕上げの前にも後にも描かれなかったとき）
            if (!MirrorRenderer.isRendering()) {
                deferredCount = 0;
            }
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            // 仕上げの前に入れられなかったゲート（パックの絵が読めない・OptiFine）。仕上がった画面へ描く
            if (!MirrorRenderer.isRendering() && deferredCount > 0) {
                drawDeferred(true);
            }
            return;
        }
        GateDisplay display = ClientConfig.gateDisplay;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || gates.isEmpty() || display == GateDisplay.HIDDEN) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        // レース中は全員に見せる。そうでなければ運転している人だけ
        if (!raceActive && !(minecraft.player.getVehicle() instanceof CarEntity)) {
            return;
        }
        boolean useDeferred = HeadlightVolume.ShaderPacks.inUse() && DeferredGlows.shaderReady();
        if (useDeferred && MirrorRenderer.isRendering()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        // カメラ基準の座標から先の行列（この段の PoseStack が持っているもの）
        Matrix4f view = new Matrix4f(event.getPoseStack().last().pose());

        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        // 四角形として積む。debugFilledBox は三角形ストリップなので、同じバッファへ複数の面を
        // 入れると<b>前の面の終端と次の面の始端が 1 本の帯として繋がってしまう</b>
        // （スタートラインの端とチェックポイントの端が線で結ばれて見える）。
        //
        // バニラの debugQuads() ではなく自前の RenderType を使う。あちらは半透明なのに
        // <b>深度を書く</b>ので、ヘッドライトの光の筋と重なった部分のゲートが消える
        VertexConsumer consumer = useDeferred ? null : buffers.getBuffer(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        deferredCount = 0;
        if (useDeferred) {
            DEFERRED_PROJECTION.set(event.getProjectionMatrix());
            // 1.21 の PoseStack にはカメラの回転が入っていないので、描くときに回転を掛ける
            DEFERRED_MODEL_VIEW.set(event.getModelViewMatrix());
        }
        Sink sink = new Sink(consumer, view, eye);

        for (CourseLinesPacket.Gate gate : gates) {
            double midX = (gate.ax() + gate.bx()) / 2.0;
            double midZ = (gate.az() + gate.bz()) / 2.0;
            if (Math.hypot(midX - eye.x, midZ - eye.z) > VIEW_DISTANCE) {
                continue;
            }
            float[] color = gate.start() ? START_COLOR : CHECKPOINT_COLOR;
            if (display.showsPlane()) {
                // 面。くぐる場所がそのまま見える。コースが長いと視界が板だらけになるので切れる
                quad(sink, gate.ax(), gate.az(), gate.bx(), gate.bz(),
                        gate.yBottom(), gate.yTop(), color, PLANE_ALPHA);
            }
            if (display.showsPosts()) {
                // 両端の柱。どこからどこまでが有効な幅かを示す
                post(sink, gate.ax(), gate.az(), gate.bx(), gate.bz(), gate, color);
                post(sink, gate.bx(), gate.bz(), gate.ax(), gate.az(), gate, color);
            }
        }

        if (!useDeferred) {
            buffers.endBatch(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        }
    }

    /**
     * シェーダーパックが仕上げを流す直前に、積んでおいたゲートをパックの絵（colortex0）へ描き込む
     * （{@code IrisPipelineMixin}）。パックの絵が読めなければ何もしない（{@code AFTER_LEVEL} で描かれる）。
     */
    public static void renderBeforePackComposite() {
        if (deferredCount == 0 || MirrorRenderer.isRendering()) {
            return;
        }
        int packScene = IrisScene.colorTexture();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        // 前後は主画面の深度で見るので、大きさが違えば（パックの描画倍率）重ねられない
        if (packScene < 0 || IrisScene.width() != main.width || IrisScene.height() != main.height) {
            return;
        }
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            IrisScene.bindForWriteWithDepth(packScene, main.getDepthTextureId());
            drawDeferred(false);
        } finally {
            IrisScene.detachDepth();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /** 積んでおいたゲートを、ふだんと同じ半透明の重ね方で描く。 */
    private static void drawDeferred(boolean bindMain) {
        try {
            DeferredGlows.draw(DEFERRED_PROJECTION, DEFERRED_MODEL_VIEW, deferred, deferredCount,
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, bindMain, DeferredGlows.shader());
        } finally {
            deferredCount = 0;
        }
    }

    /**
     * 頂点の書き込み先。パック無しなら {@code RenderType} へ積み、パック使用中は後で描くために記録する。
     * どちらもカメラからの差を double のまま取ってから行列を掛けるので、原点から遠くても桁が落ちない。
     */
    private record Sink(VertexConsumer consumer, Matrix4f view, Vec3 eye) {
        void vertex(double x, double y, double z, float r, float g, float b, float a) {
            float rx = (float) (x - eye.x);
            float ry = (float) (y - eye.y);
            float rz = (float) (z - eye.z);
            if (consumer != null) {
                consumer.addVertex(view, rx, ry, rz).setColor(r, g, b, a);
                return;
            }
            if ((deferredCount + 1) * 7 > deferred.length) {
                deferred = java.util.Arrays.copyOf(deferred, deferred.length * 2);
            }
            Vector3f position = view.transformPosition(new Vector3f(rx, ry, rz));
            int o = deferredCount * 7;
            deferred[o] = position.x;
            deferred[o + 1] = position.y;
            deferred[o + 2] = position.z;
            deferred[o + 3] = r;
            deferred[o + 4] = g;
            deferred[o + 5] = b;
            deferred[o + 6] = a;
            deferredCount++;
        }
    }

    /** 端点に立てる柱。線の内側へ太さぶん伸ばした細い面を 2 枚、十字に組む。 */
    private static void post(Sink sink, double x, double z, double towardX, double towardZ,
                             CourseLinesPacket.Gate gate, float[] color) {
        double dx = towardX - x;
        double dz = towardZ - z;
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-6) {
            return;
        }
        dx /= length;
        dz /= length;
        // 線に沿う面
        quad(sink, x, z, x + dx * POST_WIDTH, z + dz * POST_WIDTH,
                gate.yBottom(), gate.yTop(), color, POST_ALPHA);
        // 線に垂直な面。斜めから見ても柱として見える
        quad(sink, x - dz * POST_WIDTH / 2.0, z + dx * POST_WIDTH / 2.0,
                x + dz * POST_WIDTH / 2.0, z - dx * POST_WIDTH / 2.0,
                gate.yBottom(), gate.yTop(), color, POST_ALPHA);
    }

    /**
     * 2 点の間に垂直な面を張る。
     *
     * <p>{@code debugQuads} は 4 頂点で 1 枚なので、<b>面のまわりを一周する順</b>に並べる。
     * 三角形ストリップの並び（対角の行き来）で入れると形が壊れる。</p>
     */
    private static void quad(Sink sink, double x1, double z1, double x2, double z2,
                             double yBottom, double yTop, float[] color, float alpha) {
        float r = color[0];
        float g = color[1];
        float b = color[2];
        sink.vertex(x1, yBottom, z1, r, g, b, alpha);
        sink.vertex(x2, yBottom, z2, r, g, b, alpha);
        sink.vertex(x2, yTop, z2, r, g, b, alpha);
        sink.vertex(x1, yTop, z1, r, g, b, alpha);
    }
}
