package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 * <h2>時間はティックで数える</h2>
 *
 * <p>薄れ方は描画の {@code partialTick} を足して滑らかにするが、<b>時間そのものは
 * ティックで数える</b>。壁時計で数えるとポーズ中にも消えていく。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarSkidMarks {

    /** 跡が付き始める仕事率。車重×g で割った値 [m/s]。{@link CarSmoke} と同じ。 */
    private static final double MIN_POWER = 2.5;
    /** 跡がいちばん濃くなる仕事率。煙（8.0）より手前で濃くなりきる。 */
    private static final double FULL_POWER = 6.0;
    /** これ以下の速度では付けない。止まりかけの仕事率は当てにならない。 */
    private static final double MIN_SPEED = 1.0;

    /** 消えるまでのうち、最後のどれだけの割合で薄れていくか（5 秒なら最後の 2 秒）。 */
    private static final double FADE_SHARE = 0.4;
    /** いちばん濃いときの不透明度。 */
    private static final float MAX_ALPHA = 0.6F;
    /** 跡の色（ほぼ黒のゴム）。 */
    private static final float SHADE = 0.05F;

    /**
     * 跡の幅をタイヤ半径の何倍にするか。諸元にタイヤ幅は無いので半径から決める
     * （既定の 45cm で 22.5cm）。
     */
    private static final double WIDTH_PER_RADIUS = 0.5;
    /**
     * 路面からどれだけ浮かせるか [m]。同じ高さに置くと地面と深度が張り合ってちらつく。
     */
    private static final double LIFT = 0.015;
    /**
     * 1 ティックでこれ以上離れたら線を切る [m]。最高速でも 1 ティック 4m ほどなので、
     * それを超えるのはテレポートや読み直し。結ぶと地面を横切る帯になる。
     */
    private static final double MAX_STEP = 6.0;
    /**
     * 残しておく四角形の上限を、消えるまでの 1 秒あたりで。超えたら古いものから捨てる。
     * 4 台が 4 輪とも滑り続けてちょうど埋まる量（5 秒なら 4096 枚）。
     * <b>秒数に比例させること</b>——固定にすると、長くしたときに古い跡が途中で千切れる。
     */
    private static final int SEGMENTS_PER_SECOND = 820;

    /**
     * 接地点 1 つ。
     *
     * @param sideX 帯の右端へ向かう単位ベクトル（水平）。幅の半分を掛けて使う
     * @param alpha この点での不透明度（滑りの強さ）
     */
    private record Point(double x, double y, double z, double sideX, double sideZ,
                         double halfWidth, float alpha) {
    }

    /**
     * 帯の四角形 1 枚。{@code born} は付いたときのティック。
     *
     * @param own 自分が運転している車の跡か。<b>描くときに設定を見て振り分ける</b>ので、
     *            メニューで消したときは残っている跡もその場で消える
     */
    private record Segment(Point from, Point to, long born, boolean own) {
    }

    /** 付いた順に並ぶので、古いものは先頭から捨てればよい。 */
    private static final ArrayDeque<Segment> SEGMENTS = new ArrayDeque<>();
    /** 車ごと・輪ごとの直前の点。滑っていない輪は null。 */
    private static final Map<Integer, Point[]> TRAILS = new HashMap<>();
    private static long ticks;
    /** 跡がどのワールドのものか。次元を移ったら捨てる（座標の意味が変わる）。 */
    private static ClientLevel trackedLevel;

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
        SEGMENTS.clear();
        TRAILS.clear();
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
        ticks++;
        double lifetime = lifetimeTicks();
        while (!SEGMENTS.isEmpty() && ticks - SEGMENTS.peekFirst().born() > lifetime) {
            SEGMENTS.pollFirst();
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.getPosition();
        Set<Integer> seen = new HashSet<>();
        int limit = (int) Math.ceil(ClientConfig.skidMarkSeconds * SEGMENTS_PER_SECOND);
        for (Entity entity : level.entitiesForRendering()) {
            // 煙と同じく、車のモデルが描かれる範囲だけ（見えていない車の跡は要らない）
            if (!(entity instanceof CarEntity car) || !car.shouldRender(eye.x, eye.y, eye.z)) {
                continue;
            }
            // 「自分の」は自分が運転している車。助手席に乗っている車は運転者のものなので他人側
            boolean own = car.getControllingPassenger() == minecraft.player;
            if (!shows(own)) {
                // 消してある側は点も積まない（戻したときに離れた点どうしを結ばないよう線も切る）
                continue;
            }
            seen.add(car.getId());
            track(car, own, limit);
        }
        // 見えなくなった車の線は切る。戻ってきたときに離れた点どうしを結ばないように
        TRAILS.keySet().retainAll(seen);
    }

    /** 消えるまでの時間 [tick]。 */
    private static double lifetimeTicks() {
        return ClientConfig.skidMarkSeconds * 20.0;
    }

    private static boolean shows(boolean own) {
        return own ? ClientConfig.showOwnSkidMarks : ClientConfig.showOtherSkidMarks;
    }

    private static void track(CarEntity car, boolean own, int limit) {
        Point[] trail = TRAILS.computeIfAbsent(car.getId(), id -> new Point[Wheel.VALUES.length]);
        double speed = Math.abs(car.getRenderSpeed());
        if (speed < MIN_SPEED || car.getRenderSurface() != RoadSurface.PAVED) {
            java.util.Arrays.fill(trail, null);
            return;
        }

        CarSpec spec = car.getSpec();
        double reference = spec.mass() * CarPhysics.GRAVITY;
        // 描画と同じ補間済みのヨー角（ティックの終わり）。バニラの値は 1.4 度刻みに量子化されて
        // いて、輪の位置が数 cm ずつ跳ねるので跡がギザギザになる
        float yaw = car.getRenderYaw(1.0F) * Mth.DEG_TO_RAD;
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double halfWidth = spec.wheelRadius() * WIDTH_PER_RADIUS * 0.5;

        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            double intensity = (car.getRenderFrictionPower(wheel) / reference - MIN_POWER)
                    / (FULL_POWER - MIN_POWER);
            if (intensity <= 0.0) {
                trail[index] = null;
                continue;
            }
            intensity = Math.min(1.0, intensity);

            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);
            double x = car.getX() + forwardX * forwardOffset + rightX * rightOffset;
            double z = car.getZ() + forwardZ * forwardOffset + rightZ * rightOffset;
            // 接地面。サス長とタイヤ半径だけ下
            double y = car.getY() - car.getRenderSuspensionLength(wheel) - spec.wheelRadius() + LIFT;

            Point previous = trail[index];
            // 帯の幅の向きは、この点へ来た向きに垂直。横へ滑っていれば跡も斜めに太く付く
            // （タイヤの向きではなく進んだ向きで引くので、ドリフト中でも帯が途切れない）
            double dx = previous == null ? forwardX : x - previous.x();
            double dz = previous == null ? forwardZ : z - previous.z();
            double step = Math.hypot(dx, dz);
            if (previous != null && (step > MAX_STEP || Math.abs(y - previous.y()) > 1.0)) {
                previous = null;
            }
            double sideX;
            double sideZ;
            if (step < 1.0E-4) {
                sideX = rightX;
                sideZ = rightZ;
            } else {
                sideX = -dz / step;
                sideZ = dx / step;
            }
            Point point = new Point(x, y, z, sideX, sideZ, halfWidth, (float) (MAX_ALPHA * intensity));
            if (previous != null) {
                while (SEGMENTS.size() >= limit) {
                    SEGMENTS.pollFirst();
                }
                SEGMENTS.addLast(new Segment(previous, point, ticks, own));
            }
            trail[index] = point;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // ゲートと同じ段。地形と車を描き終えた後なので、深度テストで車の下に潜った跡は隠れる
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || SEGMENTS.isEmpty()) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        double now = ticks + event.getPartialTick();
        double lifetime = lifetimeTicks();
        double fadeTicks = lifetime * FADE_SHARE;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        Matrix4f matrix = pose.last().pose();

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        // 深度を書かない半透明（ゲートと同じ）。書くと、重なった跡や光の筋が互いを消し合う
        VertexConsumer consumer = buffers.getBuffer(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        for (Segment segment : SEGMENTS) {
            if (!shows(segment.own())) {
                continue;
            }
            double remaining = lifetime - (now - segment.born());
            float fade = (float) Mth.clamp(remaining / fadeTicks, 0.0, 1.0);
            if (fade <= 0.0F) {
                continue;
            }
            Point from = segment.from();
            Point to = segment.to();
            // 面のまわりを一周する順（QUADS）
            edge(consumer, matrix, from, -1.0, fade);
            edge(consumer, matrix, to, -1.0, fade);
            edge(consumer, matrix, to, 1.0, fade);
            edge(consumer, matrix, from, 1.0, fade);
        }
        buffers.endBatch(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        pose.popPose();
    }

    /** 点の左端（{@code side} = -1）か右端（+1）に頂点を 1 つ積む。 */
    private static void edge(VertexConsumer consumer, Matrix4f matrix, Point point, double side,
                             float fade) {
        double offset = point.halfWidth() * side;
        consumer.vertex(matrix,
                        (float) (point.x() + point.sideX() * offset),
                        (float) point.y(),
                        (float) (point.z() + point.sideZ() * offset))
                .color(SHADE, SHADE, SHADE, point.alpha() * fade)
                .endVertex();
    }
}
