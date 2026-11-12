package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.jdmmc.kurumamod.surface.SurfaceLookup;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.jdmmc.kurumamod.client.effect.SkidMarkTrails;

/**
 * 滑った輪が舗装路に残すタイヤ痕。<b>数秒で薄れて消える。</b>
 *
 * <p>H のメニューで、自分の車の跡・他の車の跡をそれぞれ消せる。消えるまでの秒数も
 * そこで決める（{@link ClientConfig#skidMarkSeconds}）。</p>
 *
 * <h2>いつ残すか</h2>
 *
 * <p>{@link CarSmoke} と同じく、<b>その輪が接地面で捨てている摩擦の仕事率</b>
 * （{@link CarEntity#getRenderFrictionPower}）で決める。ゴムが削れて路面に付くのは
 * 熱を持って滑っているときなので、煙と出どころは同じ。回っているだけ・荷重が
 * 掛かっているだけでは残らないので、後輪駆動でブン回しても転がっている前輪は線を引かない。
 * しきい値は煙と同じところから始め、<b>煙より早く濃くなる</b>（跡は薄く出ても見えるので）。</p>
 *
 * <p><b>舗装路だけ。</b>未舗装で滑ったときに見えるのは土煙（{@link CarDust}）であって
 * ゴムの跡ではない。仕事率も路面も同期してあるので、<b>他人の車からも同じように残る</b>
 * （パケットは要らない）。</p>
 *
 * <h2>形</h2>
 *
 * <p>輪ごとにティックの終わりの接地点を点として積み、前の点と結んだ帯にする。
 * 帯の幅の向きは<b>点ごとに</b>持つ（その点へ来た向きに垂直）ので、隣り合う四角形が
 * 同じ辺を共有して、曲がっていても継ぎ目に隙間ができない。</p>
 *
 * <p><b>帯はブロックの格子で切り分け、一片ずつその下のブロックの天面に貼る</b>（{@link SkidMarkTrails}）。
 * 物理が渡す地面（階段の線）は段の角を結んだ斜面で、縁の高さを補間で決めた線はブロックの天面より
 * 最大で段の半分低い（{@code CarEntity#STAIR_SINK_MAX}）。その高さで帯を引くと、跡が天面の下に
 * 埋まり、段をまたぐ四角形は角に刺さった。段の立ち上がりの面には描かない。</p>
 *
 * <h2>時間はティックで数える</h2>
 *
 * <p>薄れ方は描画の {@code partialTick} を足して滑らかにするが、<b>時間そのものは
 * ティックで数える</b>。壁時計で数えるとポーズ中にも消えていく。</p>
 *
 * <h2>シェーダーパックを使っている間</h2>
 *
 * <p><b>パックが仕上げ（霧・トーンマップ・TAA）を流す直前に、パックの絵へ自前のシェーダーで描き込む</b>
 * （{@link #renderBeforePackComposite()}。描き方は {@link DeferredGlows#draw} を借りる）。
 * ふだんの {@code RenderType} のまま描くと、パックがそれを自分のプログラム（{@code gbuffers_basic}）に
 * 差し替え、<b>深度は路面のまま、パックの gbuffer（色と材質）だけを跡で上書きする</b>。その食い違いを
 * パックの後段が読んで、<b>路面の跡を地平線で上下に折り返したような形が空に出た</b>（Complementary・BSL の
 * 両方、晴れでも雨でも。跡を消すと消える）。</p>
 *
 * <ul>
 *   <li><b>{@code AFTER_LEVEL}（仕上げの後）で描いてはいけない。</b>仕上がった絵に黒を重ねることになり、
 *       夜でも霧の中でも真っ黒な線として浮いた。TAA も掛からないので、パックが半画素ずつずらして
 *       描いた路面の深度と張り合ってちらついた。仕上げの前に入れなかったときだけの逃げ道</li>
 *   <li><b>黒を重ねるのではなく、路面の色に掛けて暗くする</b>（ゴムで反射率が下がるのと同じ）。
 *       影や夜の暗い路面では目立たず、照らされた路面でははっきり出る。ヘッドライトの光は
 *       この後に入れるので、跡の上にも光が乗る</li>
 *   <li>深度の張り合いを避けるため、面の傾きに応じて手前へ寄せる（{@code glPolygonOffset}）。
 *       浅い角度の遠くの路面ほど、半画素で深度が大きく変わるため</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarSkidMarks {

    /** 跡の色（ほぼ黒のゴム）。 */
    private static final float SHADE = 0.05F;

    /** 跡がどのワールドのものか。次元を移ったら捨てる（座標の意味が変わる）。 */
    private static ClientLevel trackedLevel;

    /**
     * シェーダーパック使用中に、この後の段で描く跡の頂点。x, y, z, r, g, b, a の 7 個ずつで、
     * {@link DeferredGlows#draw} の形（座標はカメラ基準）。
     */
    private static float[] deferred = new float[7 * 4096];
    private static int deferredCount;
    /** 記録したときの射影と、記録した座標に掛ける行列。 */
    private static final Matrix4f DEFERRED_PROJECTION = new Matrix4f();
    private static final Matrix4f DEFERRED_MODEL_VIEW = new Matrix4f();
    /**
     * 粒を描く直前（{@code AFTER_TRIPWIRE_BLOCKS}）の深度の写し。仕上げの直前に描くときはこれで前後を見る。
     * <b>主画面の深度をそのまま使うと、煙（粒は深度を書く）と重なった所で跡が弾かれ、煙しか描かれなかった</b>
     * （NeoForge 版の Iris で出た）。
     */
    private static TextureTarget depthCopy;

    /**
     * 煙の向こうの跡をどこまで薄めるか（{@code skid_mark.fsh} の {@code ThroughSmoke}）。
     * 粒の濃さは分からないので一律。1 だと煙の中で跡だけがくっきり浮いた
     */
    private static final float THROUGH_SMOKE = 0.3F;

    private static final Logger LOGGER = LogUtils.getLogger();
    /** パック使用中の跡のシェーダー。読めなければ光芒のシェーダーで描く（煙の向こうも薄めない）。 */
    private static ShaderInstance shader;
    private static boolean loadAttempted;

    private CarSkidMarks() {
    }

    /**
     * 接続が変わったら捨てる。<b>{@code static} なので放っておくと前のワールドの跡が残る。</b>
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
        SkidMarkTrails.reset();
        trackedLevel = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        if (level != trackedLevel) {
            reset();
            trackedLevel = level;
        }
        SkidMarkTrails.tick();

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.getPosition();
        Set<Integer> seen = new HashSet<>();
        int limit = SkidMarkTrails.limit();
        for (Entity entity : level.entitiesForRendering()) {
            // 煙と同じく、車のモデルが描かれる範囲だけ（見えていない車の跡は要らない）
            if (!(entity instanceof CarEntity car) || car.isGhost() || !car.shouldRender(eye.x, eye.y, eye.z)) {
                continue;
            }
            // 「自分の」は自分が運転している車。助手席に乗っている車は運転者のものなので他人側
            boolean own = car.getControllingPassenger() == minecraft.player;
            if (!SkidMarkTrails.shows(own)) {
                // 消してある側は点も積まない（戻したときに離れた点どうしを結ばないよう線も切る）
                continue;
            }
            seen.add(car.getId());
            SkidMarkTrails.track(car, car.xo, car.yo, car.zo, own, limit,
                    (x, z, near) -> surfaceTop(level, car, x, z, near));
        }
        // 見えなくなった車の線は切る。戻ってきたときに離れた点どうしを結ばないように
        SkidMarkTrails.keepTrails(seen);
    }








    /**
     * その点の下のブロックの天面の高さ。{@code near} の上下 {@link SkidMarkTrails#SURFACE_SEARCH} で探す。無ければ NaN。
     *
     * <p><b>舗装でないブロックも NaN（貼らない）。</b>跡を残すかは輪の中心の路面で決めるので、舗装と土の境を
     * 踏むと、帯の幅のぶんや進んだ先のブロックが土のことがある。ここで捨てないと跡が土の上に付く。</p>
     */
    private static double surfaceTop(Level level, Entity car, double x, double z, double near) {
        BlockHitResult hit = level.clip(new ClipContext(
                new Vec3(x, near + SkidMarkTrails.SURFACE_SEARCH, z),
                new Vec3(x, near - SkidMarkTrails.SURFACE_SEARCH, z),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                car));
        if (hit.getType() == HitResult.Type.MISS || hit.isInside()
                || SurfaceLookup.of(level.getBlockState(hit.getBlockPos())) != RoadSurface.PAVED) {
            return Double.NaN;
        }
        return hit.getLocation().y;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            // 仕上げの前に入れられなかった跡（パックの絵が読めない・OptiFine）。仕上がった画面へ描く。
            // 路面の照り返し（NORMAL）より後、光芒（LOWEST）より前
            if (!MirrorRenderer.isRendering() && deferredCount > 0) {
                drawDeferred(true);
            }
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            // 粒の直前（路面の照り返しと同じ段）。パック使用中は、ここの深度で跡の前後を見る
            if (!SkidMarkTrails.isEmpty() && HeadlightVolume.ShaderPacks.inUse() && !MirrorRenderer.isRendering()) {
                captureDepth(Minecraft.getInstance().getMainRenderTarget());
            }
            return;
        }
        // ゲートと同じ段。地形と車を描き終えた後なので、深度テストで車の下に潜った跡は隠れる
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || SkidMarkTrails.isEmpty()) {
            return;
        }
        boolean useDeferred = HeadlightVolume.ShaderPacks.inUse() && DeferredGlows.shaderReady();
        if (useDeferred && MirrorRenderer.isRendering()) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        double now = SkidMarkTrails.ticks() + event.getPartialTick();

        PoseStack pose = event.getPoseStack();
        // カメラ基準の座標から先の行列（この段の PoseStack が持っているもの）。パック使用中はこれで記録する
        Matrix4f view = new Matrix4f(pose.last().pose());
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        Matrix4f matrix = pose.last().pose();

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        // 深度を書かない半透明（ゲートと同じ）。書くと、重なった跡や光の筋が互いを消し合う
        VertexConsumer consumer = useDeferred ? null : buffers.getBuffer(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        deferredCount = 0;
        if (useDeferred) {
            DEFERRED_PROJECTION.set(event.getProjectionMatrix());
        // この段の PoseStack はカメラの向き込みなので、記録した座標は向きまで掛かっている
            DEFERRED_MODEL_VIEW.identity();
        }
        for (SkidMarkTrails.Patch patch : SkidMarkTrails.patches()) {
            if (!SkidMarkTrails.shows(patch.own())) {
                continue;
            }
            float fade = SkidMarkTrails.fade(patch, now);
            if (fade <= 0.0F) {
                continue;
            }
            // 面のまわりを一周する順（QUADS）
            double[] v = patch.vertices();
            for (int k = 0; k < 16; k += 4) {
                if (useDeferred) {
                    record(view, eye, v[k], v[k + 1], v[k + 2], (float) v[k + 3], fade);
                } else {
                    vertex(consumer, matrix, v[k], v[k + 1], v[k + 2], (float) v[k + 3], fade);
                }
            }
        }
        if (!useDeferred) {
            buffers.endBatch(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        }
        pose.popPose();
    }

    /**
     * パック使用中の跡の頂点を 1 つ記録する（{@link #vertex} と同じ位置と色）。
     * カメラからの差を double のまま取ってから行列を掛けるので、原点から遠くても桁が落ちない。
     */
    private static void record(Matrix4f view, Vec3 eye, double x, double y, double z, float alpha, float fade) {
        if ((deferredCount + 1) * 7 > deferred.length) {
            deferred = java.util.Arrays.copyOf(deferred, deferred.length * 2);
        }
        Vector3f position = view.transformPosition(new Vector3f(
                (float) (x - eye.x),
                (float) (y - eye.y),
                (float) (z - eye.z)));
        int o = deferredCount * 7;
        deferred[o] = position.x;
        deferred[o + 1] = position.y;
        deferred[o + 2] = position.z;
        // 掛け算で描くので色は使わない。α は「路面の色をどれだけ消すか」＝ふだんの描き方で
        // ほぼ黒（SHADE）を α で重ねたときと同じ暗さになる量
        deferred[o + 3] = 0.0F;
        deferred[o + 4] = 0.0F;
        deferred[o + 5] = 0.0F;
        deferred[o + 6] = alpha * fade * (1.0F - SHADE);
        deferredCount++;
    }

    /**
     * シェーダーパックが仕上げを流す直前に、積んでおいた跡をパックの絵（colortex0）へ描き込む
     * （{@code IrisPipelineMixin}）。パックの絵が読めなければ何もしない（{@code AFTER_LEVEL} で描かれる）。
     */
    public static void renderBeforePackComposite() {
        if (deferredCount == 0 || MirrorRenderer.isRendering()) {
            return;
        }
        int packScene = IrisScene.colorTexture();
        // 深度は主画面の写しなので、大きさが違えば（パックの描画倍率）重ねられない
        if (packScene < 0 || depthCopy == null
                || IrisScene.width() != depthCopy.width || IrisScene.height() != depthCopy.height) {
            return;
        }
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            IrisScene.bindForWriteWithDepth(packScene, depthCopy.getDepthTextureId());
            drawDeferred(false);
        } finally {
            IrisScene.detachDepth();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    private static ShaderInstance markShader() {
        if (shader == null && !loadAttempted) {
            loadAttempted = true;
            try {
                shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                        new ResourceLocation(Kurumamod.MODID, "skid_mark"), DefaultVertexFormat.POSITION_COLOR);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("タイヤ痕のシェーダーを読めなかった。煙の向こうの跡を薄めずに描く", e);
            }
        }
        return shader;
    }

    @Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            loadAttempted = false;
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), new ResourceLocation(Kurumamod.MODID, "skid_mark"),
                        DefaultVertexFormat.POSITION_COLOR), loaded -> shader = loaded);
            } catch (IOException e) {
                LOGGER.error("タイヤ痕のシェーダーを読めなかった。煙の向こうの跡を薄めずに描く", e);
                shader = null;
            }
        }
    }

    /**
     * 主画面の深度を写す。<b>この段ではパックが自分の枠を結んでいる</b>ので、写したら主画面ではなく
     * 元の結び先へ戻す。ステンシルの有無が違うと深度を写せない（形式が一致しないと blit が失敗する）。
     */
    private static void captureDepth(RenderTarget main) {
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
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthCopy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    /** 積んでおいた跡を、路面の色に掛けて暗くする形で描く。 */
    private static void drawDeferred(boolean bindMain) {
        boolean offsetWas = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        float offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        float offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-2.0F, -4.0F);
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            ShaderInstance program = markShader();
            // 主画面へ描くときは、その深度が書き込み先に付いているので読めない（読み書きが同じになる）。
            // 写しを渡して、煙の向こうでも薄めない
            boolean smoke = program != null && !bindMain;
            if (program != null) {
                int depthTexture = smoke ? main.getDepthTextureId()
                        : depthCopy != null ? depthCopy.getDepthTextureId() : 0;
                program.setSampler("FinalDepthSampler", depthTexture);
                program.safeGetUniform("ScreenSize").set((float) main.width, (float) main.height);
                program.safeGetUniform("ThroughSmoke").set(smoke ? THROUGH_SMOKE : 1.0F);
            } else {
                program = DeferredGlows.shaderReady() ? DeferredGlows.shader() : null;
            }
            DeferredGlows.draw(DEFERRED_PROJECTION, DEFERRED_MODEL_VIEW, deferred, deferredCount,
                    GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA, bindMain, program);
        } finally {
            GL11.glPolygonOffset(offsetFactor, offsetUnits);
            if (!offsetWas) {
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            }
            deferredCount = 0;
        }
    }

    /** 頂点を 1 つ積む。 */
    private static void vertex(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z,
                               float alpha, float fade) {
        consumer.vertex(matrix, (float) x, (float) y, (float) z)
                .color(SHADE, SHADE, SHADE, alpha * fade)
                .endVertex();
    }
}
