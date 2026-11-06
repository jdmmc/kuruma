package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.jdmmc.kurumamod.surface.SurfaceLookup;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * <h2>描く段</h2>
 *
 * <p>ワールドの面を出す段（{@link com.jdmmc.kurumamod.client.hook.LevelStageHooks#submit}）で、深度を書かない
 * 半透明（{@link EffectRenderTypes#TRANSLUCENT_OVERLAY}）として出す。地形と車の深度でテストされるので、
 * 車の下に潜った跡は隠れる。1.21.1 のシェーダーパック向けの描き分け（パックの仕上げの直前に、路面の色に
 * 掛けて暗くする）は、26.3 の Iris が無いので持っていない。</p>
 */
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

    private CarSkidMarks() {
    }

    /**
     * 接続が変わったら捨てる。<b>{@code static} なので放っておくと前のワールドの跡が残る。</b>
     */
    public static void onLoggingIn() {
        reset();
    }

    public static void onLoggingOut() {
        reset();
    }

    private static void reset() {
        SEGMENTS.clear();
        TRAILS.clear();
        trackedLevel = null;
    }

    public static void onClientTick() {
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

        Camera camera = minecraft.gameRenderer.mainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.position();
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
        // ティックの始まりの姿勢で打つ。画面の車は前のティックとの間を補間して描かれるので、ティックの終わりの
        // 位置で打つと跡がタイヤより前に出る（30m/s なら最大 1.5m）
        float yaw = car.getRenderYaw(0.0F) * Mth.DEG_TO_RAD;
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
            double x = car.xo + forwardX * forwardOffset + rightX * rightOffset;
            double z = car.zo + forwardZ * forwardOffset + rightZ * rightOffset;
            // 接地面。サス長とタイヤ半径だけ下
            double y = car.yo - car.getRenderSuspensionLength(wheel, 0.0F) - spec.wheelRadius() + LIFT;

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
        List<double[][]> pieces = new ArrayList<>(4);
        List<Double> tops = new ArrayList<>(4);
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

    /**
     * その点の下のブロックの天面の高さ。{@code near} の上下 {@link #SURFACE_SEARCH} で探す。無ければ NaN。
     *
     * <p><b>舗装でないブロックも NaN（貼らない）。</b>跡を残すかは輪の中心の路面で決めるので、舗装と土の境を
     * 踏むと、帯の幅のぶんや進んだ先のブロックが土のことがある。ここで捨てないと跡が土の上に付く。</p>
     */
    private static double surfaceTop(Level level, Entity car, double x, double z, double near) {
        BlockHitResult hit = level.clip(new ClipContext(
                new Vec3(x, near + SURFACE_SEARCH, z),
                new Vec3(x, near - SURFACE_SEARCH, z),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                car));
        if (hit.getType() == HitResult.Type.MISS || hit.isInside()
                || SurfaceLookup.of(level.getBlockState(hit.getBlockPos())) != RoadSurface.PAVED) {
            return Double.NaN;
        }
        return hit.getLocation().y;
    }

    /**
     * 跡を出す。座標はカメラを原点にした世界の向き（{@code eye} を引いた値）で積む。
     * カメラからの差を double のまま取ってから float にするので、原点から遠くても桁が落ちない。
     */
    public static void submit(SubmitNodeCollector collector, Vec3 eye, float partialTick) {
        if (SEGMENTS.isEmpty()) {
            return;
        }
        double now = ticks + partialTick;
        double lifetime = lifetimeTicks();
        double fadeTicks = lifetime * FADE_SHARE;
        // 頂点は描く直前に積まれ、そのときには次のティックで捨てられているかもしれないので、ここで写しを取る
        List<double[]> vertices = new ArrayList<>(SEGMENTS.size());
        List<Float> fades = new ArrayList<>(SEGMENTS.size());
        for (Patch patch : SEGMENTS) {
            if (!shows(patch.own())) {
                continue;
            }
            double remaining = lifetime - (now - patch.born());
            float fade = (float) Mth.clamp(remaining / fadeTicks, 0.0, 1.0);
            if (fade <= 0.0F) {
                continue;
            }
            vertices.add(patch.vertices());
            fades.add(fade);
        }
        if (vertices.isEmpty()) {
            return;
        }
        if (DeferredGlows.active()) {
            // シェーダーパック使用中は記録だけして、パックの仕上げの直前に路面の色へ掛けて暗くする
            // （そのまま出すとパックの gbuffer に乗り、跡が空に折り返して映る。docs/rendering.md「タイヤ痕とシェーダーパック」）。
            // α は「ほぼ黒（SHADE）を α で重ねたとき」と同じ暗さになる量
            emit(DeferredGlows.MARKS, new Matrix4f(), eye, vertices, fades, 1.0F - SHADE);
            return;
        }
        collector.submitCustomGeometry(new PoseStack(), EffectRenderTypes.TRANSLUCENT_OVERLAY,
                (pose, consumer) -> emit(consumer, pose.pose(), eye, vertices, fades, 1.0F));
    }

    private static void emit(VertexConsumer consumer, Matrix4f matrix, Vec3 eye, List<double[]> vertices,
                             List<Float> fades, float alphaScale) {
        for (int i = 0; i < vertices.size(); i++) {
            double[] v = vertices.get(i);
            float fade = fades.get(i) * alphaScale;
            // 面のまわりを一周する順（QUADS）
            for (int k = 0; k < 16; k += 4) {
                vertex(consumer, matrix, eye, v[k], v[k + 1], v[k + 2], (float) v[k + 3], fade);
            }
        }
    }

    /** 頂点を 1 つ積む。 */
    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Vec3 eye, double x, double y, double z,
                               float alpha, float fade) {
        consumer.addVertex(matrix, (float) (x - eye.x), (float) (y - eye.y), (float) (z - eye.z))
                .setColor(SHADE, SHADE, SHADE, alpha * fade);
    }
}
