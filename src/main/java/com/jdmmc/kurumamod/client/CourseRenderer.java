package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.GateDisplay;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

/**
 * コースの線をゲートとして描く。
 *
 * <p>線がどこにあるか分からないと走りようがないので、<b>面と 2 本の柱</b>で門のように見せる。
 * スタート／ゴールとチェックポイントで色を変えてある。</p>
 *
 * <p>見せる相手は「レース中なら全員」「そうでなければ車に乗っている人だけ」。歩いている
 * 人の視界を塞がないための切り分けで、タイムアタック中は自分にだけ見えることになる。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
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
    private static boolean raceActive;

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

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
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

        Camera camera = event.getCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camX, -camY, -camZ);

        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        // 四角形として積む。debugFilledBox は三角形ストリップなので、同じバッファへ複数の面を
        // 入れると<b>前の面の終端と次の面の始端が 1 本の帯として繋がってしまう</b>
        // （スタートラインの端とチェックポイントの端が線で結ばれて見える）。
        //
        // バニラの debugQuads() ではなく自前の RenderType を使う。あちらは半透明なのに
        // <b>深度を書く</b>ので、ヘッドライトの光の筋と重なった部分のゲートが消える
        VertexConsumer consumer = buffers.getBuffer(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        Matrix4f matrix = pose.last().pose();

        for (CourseLinesPacket.Gate gate : gates) {
            double midX = (gate.ax() + gate.bx()) / 2.0;
            double midZ = (gate.az() + gate.bz()) / 2.0;
            if (Math.hypot(midX - camX, midZ - camZ) > VIEW_DISTANCE) {
                continue;
            }
            float[] color = gate.start() ? START_COLOR : CHECKPOINT_COLOR;
            if (display.showsPlane()) {
                // 面。くぐる場所がそのまま見える。コースが長いと視界が板だらけになるので切れる
                quad(consumer, matrix, gate.ax(), gate.az(), gate.bx(), gate.bz(),
                        gate.yBottom(), gate.yTop(), color, PLANE_ALPHA);
            }
            if (display.showsPosts()) {
                // 両端の柱。どこからどこまでが有効な幅かを示す
                post(consumer, matrix, gate.ax(), gate.az(), gate.bx(), gate.bz(), gate, color);
                post(consumer, matrix, gate.bx(), gate.bz(), gate.ax(), gate.az(), gate, color);
            }
        }

        buffers.endBatch(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        pose.popPose();
    }

    /** 端点に立てる柱。線の内側へ太さぶん伸ばした細い面を 2 枚、十字に組む。 */
    private static void post(VertexConsumer consumer, Matrix4f matrix,
                             double x, double z, double towardX, double towardZ,
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
        quad(consumer, matrix, x, z, x + dx * POST_WIDTH, z + dz * POST_WIDTH,
                gate.yBottom(), gate.yTop(), color, POST_ALPHA);
        // 線に垂直な面。斜めから見ても柱として見える
        quad(consumer, matrix, x - dz * POST_WIDTH / 2.0, z + dx * POST_WIDTH / 2.0,
                x + dz * POST_WIDTH / 2.0, z - dx * POST_WIDTH / 2.0,
                gate.yBottom(), gate.yTop(), color, POST_ALPHA);
    }

    /**
     * 2 点の間に垂直な面を張る。
     *
     * <p>{@code debugQuads} は 4 頂点で 1 枚なので、<b>面のまわりを一周する順</b>に並べる。
     * 三角形ストリップの並び（対角の行き来）で入れると形が壊れる。</p>
     */
    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             double x1, double z1, double x2, double z2,
                             double yBottom, double yTop, float[] color, float alpha) {
        float r = color[0];
        float g = color[1];
        float b = color[2];
        consumer.vertex(matrix, (float) x1, (float) yBottom, (float) z1).color(r, g, b, alpha).endVertex();
        consumer.vertex(matrix, (float) x2, (float) yBottom, (float) z2).color(r, g, b, alpha).endVertex();
        consumer.vertex(matrix, (float) x2, (float) yTop, (float) z2).color(r, g, b, alpha).endVertex();
        consumer.vertex(matrix, (float) x1, (float) yTop, (float) z1).color(r, g, b, alpha).endVertex();
    }
}
