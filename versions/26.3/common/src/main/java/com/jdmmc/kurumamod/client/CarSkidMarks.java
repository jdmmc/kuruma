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
 * <h2>描く段</h2>
 *
 * <p>ワールドの面を出す段（{@link com.jdmmc.kurumamod.client.hook.LevelStageHooks#submit}）で、深度を書かない
 * 半透明（{@link EffectRenderTypes#TRANSLUCENT_OVERLAY}）として出す。地形と車の深度でテストされるので、
 * 車の下に潜った跡は隠れる。1.21.1 のシェーダーパック向けの描き分け（パックの仕上げの直前に、路面の色に
 * 掛けて暗くする）は、26.3 の Iris が無いので持っていない。</p>
 */
public final class CarSkidMarks {

    /** 跡の色（ほぼ黒のゴム）。 */
    private static final float SHADE = 0.05F;

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
        SkidMarkTrails.reset();
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
        SkidMarkTrails.tick();

        Camera camera = minecraft.gameRenderer.mainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.position();
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

    /**
     * 跡を出す。座標はカメラを原点にした世界の向き（{@code eye} を引いた値）で積む。
     * カメラからの差を double のまま取ってから float にするので、原点から遠くても桁が落ちない。
     */
    public static void submit(SubmitNodeCollector collector, Vec3 eye, float partialTick) {
        if (SkidMarkTrails.isEmpty()) {
            return;
        }
        double now = SkidMarkTrails.ticks() + partialTick;
        // 頂点は描く直前に積まれ、そのときには次のティックで捨てられているかもしれないので、ここで写しを取る
        List<double[]> vertices = new ArrayList<>(SkidMarkTrails.size());
        List<Float> fades = new ArrayList<>(SkidMarkTrails.size());
        for (SkidMarkTrails.Patch patch : SkidMarkTrails.patches()) {
            if (!SkidMarkTrails.shows(patch.own())) {
                continue;
            }
            float fade = SkidMarkTrails.fade(patch, now);
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
