package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
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
 * <p><b>帯はブロックの格子で切り分け、一片ずつその下のブロックの天面に貼る</b>（{@link #addPatches}）。
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
     * 帯 1 枚が何枚の四角形になるかの見込み。平らな路面では切り分けないので 1 枚。段をまたぐと
     * 1 ティックに進む距離に応じて 2〜4 枚（30m/s で平均 3.5 片）。残しておく上限はこれを掛けた枚数で数える。
     */
    private static final int PATCHES_PER_SEGMENT = 2;
    /** 天面を探す範囲 [m]。物理の接地点の上下これだけ。登れない段（1 ブロック）は入らない。 */
    private static final double SURFACE_SEARCH = 0.6;

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
     * 帯をブロックごとに切り分けた一片（四角形 1 枚。三角形なら最後の頂点を重ねる）。
     * {@code vertices} は x, y, z, 不透明度 を 4 頂点ぶん。{@code born} は付いたときのティック。
     *
     * @param own 自分が運転している車の跡か。<b>描くときに設定を見て振り分ける</b>ので、
     *            メニューで消したときは残っている跡もその場で消える
     */
    private record Patch(double[] vertices, long born, boolean own) {
    }

    /** 付いた順に並ぶので、古いものは先頭から捨てればよい。 */
    private static final ArrayDeque<Patch> SEGMENTS = new ArrayDeque<>();
    /** 車ごと・輪ごとの直前の点。滑っていない輪は null。 */
    private static final Map<Integer, Point[]> TRAILS = new HashMap<>();
    private static long ticks;
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
        int limit = (int) Math.ceil(ClientConfig.skidMarkSeconds * SEGMENTS_PER_SECOND * PATCHES_PER_SEGMENT);
        for (Entity entity : level.entitiesForRendering()) {
            // 煙と同じく、車のモデルが描かれる範囲だけ（見えていない車の跡は要らない）
            if (!(entity instanceof CarEntity car) || car.isGhost() || !car.shouldRender(eye.x, eye.y, eye.z)) {
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
        if (speed < MIN_SPEED) {
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
            // 路面は輪ごとに見る。前輪が土に乗っても、舗装の上の後輪の跡は途切れさせない
            if (car.getRenderSurface(wheel) != RoadSurface.PAVED) {
                trail[index] = null;
                continue;
            }
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
                addPatches(car, previous, point, own, limit);
            }
            trail[index] = point;
        }
    }

    /**
     * {@code from} から {@code to} までの帯を、ブロックの格子で切り分けて積む。一片ずつ、その真ん中の下の
     * ブロックの天面に置く（天面が見つからない片は描かない——崖の外や、登れない段の壁）。
     */
    private static void addPatches(CarEntity car, Point from, Point to, boolean own, int limit) {
        // 帯の四角形。頂点ごとに x, z, 不透明度, 物理の接地点の高さ
        double[][] quad = {
                corner(from, -1.0), corner(to, -1.0), corner(to, 1.0), corner(from, 1.0)
        };
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (double[] v : quad) {
            minX = Math.min(minX, Mth.floor(v[0]));
            maxX = Math.max(maxX, Mth.floor(v[0]));
            minZ = Math.min(minZ, Mth.floor(v[1]));
            maxZ = Math.max(maxZ, Mth.floor(v[1]));
        }
        Level level = car.level();
        java.util.List<double[][]> pieces = new java.util.ArrayList<>(4);
        java.util.List<Double> tops = new java.util.ArrayList<>(4);
        boolean flat = true;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                double[][] piece = clip(quad, 0, bx, false);
                piece = clip(piece, 0, bx + 1, true);
                piece = clip(piece, 1, bz, false);
                piece = clip(piece, 1, bz + 1, true);
                if (piece.length < 3) {
                    continue;
                }
                double cx = 0.0, cz = 0.0, cy = 0.0;
                for (double[] v : piece) {
                    cx += v[0];
                    cz += v[1];
                    cy += v[3];
                }
                cx /= piece.length;
                cz /= piece.length;
                cy /= piece.length;
                double top = surfaceTop(level, car, cx, cz, cy - LIFT);
                if (Double.isNaN(top)) {
                    flat = false;
                    continue;
                }
                if (!tops.isEmpty() && Math.abs(top - tops.get(0)) > 1.0e-3) {
                    flat = false;
                }
                pieces.add(piece);
                tops.add(top);
            }
        }
        if (pieces.isEmpty()) {
            return;
        }
        if (flat) {
            // 平らな路面（ほとんどの場合）。切り分けずに 1 枚のまま置く
            addPatch(quad, tops.get(0) + LIFT, own, limit);
            return;
        }
        for (int i = 0; i < pieces.size(); i++) {
            addPatch(pieces.get(i), tops.get(i) + LIFT, own, limit);
        }
    }

    /**
     * 凸多角形 1 つを高さ {@code y} に置いて積む。四角形ずつに割る（0-1-2-3、0-3-4-5、……。端数は最後の頂点を重ねる）。
     * 切り分けた片はたいてい四角形か五角形なので、1〜2 枚で済む。
     */
    private static void addPatch(double[][] polygon, double y, boolean own, int limit) {
        for (int k = 1; k + 1 < polygon.length; k += 2) {
            double[] a = polygon[0], b = polygon[k], c = polygon[k + 1];
            double[] d = polygon[Math.min(k + 2, polygon.length - 1)];
            double[] vertices = {
                    a[0], y, a[1], a[2],
                    b[0], y, b[1], b[2],
                    c[0], y, c[1], c[2],
                    d[0], y, d[1], d[2]};
            while (SEGMENTS.size() >= limit) {
                SEGMENTS.pollFirst();
            }
            SEGMENTS.addLast(new Patch(vertices, ticks, own));
        }
    }

    /** 点の左端（{@code side} = -1）か右端（+1）。{x, z, 不透明度, 高さ}。 */
    private static double[] corner(Point point, double side) {
        double offset = point.halfWidth() * side;
        return new double[]{point.x() + point.sideX() * offset, point.z() + point.sideZ() * offset,
                point.alpha(), point.y()};
    }

    /**
     * 多角形を、軸 {@code axis}（0 = x、1 = z）の {@code bound} で切る（Sutherland–Hodgman）。
     * {@code upper} なら {@code bound} 以下を、そうでなければ以上を残す。切り口の頂点は不透明度と高さも内分する。
     */
    private static double[][] clip(double[][] polygon, int axis, double bound, boolean upper) {
        if (polygon.length == 0) {
            return polygon;
        }
        java.util.List<double[]> out = new java.util.ArrayList<>(polygon.length + 2);
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            boolean aIn = upper ? a[axis] <= bound : a[axis] >= bound;
            boolean bIn = upper ? b[axis] <= bound : b[axis] >= bound;
            if (aIn) {
                out.add(a);
            }
            if (aIn != bIn) {
                double t = (bound - a[axis]) / (b[axis] - a[axis]);
                double[] cut = new double[a.length];
                for (int k = 0; k < a.length; k++) {
                    cut[k] = a[k] + (b[k] - a[k]) * t;
                }
                cut[axis] = bound;
                out.add(cut);
            }
        }
        return out.toArray(new double[0][]);
    }

    /** その点の下のブロックの天面の高さ。{@code near} の上下 {@link #SURFACE_SEARCH} で探す。無ければ NaN。 */
    private static double surfaceTop(Level level, Entity car, double x, double z, double near) {
        BlockHitResult hit = level.clip(new ClipContext(
                new Vec3(x, near + SURFACE_SEARCH, z),
                new Vec3(x, near - SURFACE_SEARCH, z),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                car));
        if (hit.getType() == HitResult.Type.MISS || hit.isInside()) {
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
            if (!SEGMENTS.isEmpty() && HeadlightVolume.ShaderPacks.inUse() && !MirrorRenderer.isRendering()) {
                captureDepth(Minecraft.getInstance().getMainRenderTarget());
            }
            return;
        }
        // ゲートと同じ段。地形と車を描き終えた後なので、深度テストで車の下に潜った跡は隠れる
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || SEGMENTS.isEmpty()) {
            return;
        }
        boolean useDeferred = HeadlightVolume.ShaderPacks.inUse() && DeferredGlows.shaderReady();
        if (useDeferred && MirrorRenderer.isRendering()) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        double now = ticks + event.getPartialTick();
        double lifetime = lifetimeTicks();
        double fadeTicks = lifetime * FADE_SHARE;

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
        for (Patch patch : SEGMENTS) {
            if (!shows(patch.own())) {
                continue;
            }
            double remaining = lifetime - (now - patch.born());
            float fade = (float) Mth.clamp(remaining / fadeTicks, 0.0, 1.0);
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
