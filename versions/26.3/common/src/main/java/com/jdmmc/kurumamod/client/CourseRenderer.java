package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.GateDisplay;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;
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
 *
 * <p>描くのはワールドの面を出す段（{@link com.jdmmc.kurumamod.client.hook.LevelStageHooks#submit}）。
 * 深度を書かない半透明（{@link EffectRenderTypes#TRANSLUCENT_OVERLAY}）なので、地形や車の後ろに回ったゲートは隠れ、
 * ゲートどうしは奥から並べ直されて重なる。</p>
 */
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

    /**
     * どのコースのゲートを描くか（null なら全部のコースのスタートラインだけ）。2 つ以上なら、そのどれか。<b>見せ方はクライアントが自分の状態で決める</b>——サーバーは
     * コースの形を送るだけで、レースのことは何も載せない（{@link CourseLinesPacket}）。
     *
     * <ul>
     *   <li><b>レースに加わっている（エントリー・観戦）なら、そのレースのコースだけ</b>（歩いていても車に乗っていても。
     *       チェックポイントも）。観戦中と号砲までのエントリー中はタイムアタックを測らない（サーバーの
     *       {@code RaceManager#trackCar}）ので、ほかのコースは要らない</li>
     *   <li>運転してコースを走っている（計測中・ウォームアップ中）なら、そのコースだけ。関係ないコースの線が
     *       視界に入ると走りにくい</li>
     *   <li>どれでもなければ<b>全部のコースのスタートラインだけ</b>（車に乗っていても歩いていても）。どこから
     *       走り出せるかが分かれば足り、チェックポイントまで並べると門だらけになる</li>
     * </ul>
     */
    private static List<String> focus(Minecraft minecraft) {
        String followed = RaceLobby.followedCourse();
        if (followed != null) {
            return List.of(followed);
        }
        // 運転席だけを「車に乗っている」とみなす。助手席は歩いているのと同じ（人の車で走っているわけではない）
        if (!(minecraft.player.getVehicle() instanceof CarEntity car) || car.getControllingPassenger() != minecraft.player) {
            return null;
        }
        String running = RaceHud.runningCourse();
        // スタートラインを共有するコースのウォームアップでは、候補が「A / B」と並んで届く（どれを走るかは
        // チェックポイントで絞る）。候補のどのコースのゲートも描く。コースの名前に空白は入らない（word で受ける）
        return running == null ? null : List.of(running.split(" / "));
    }

    /** そのゲートを描くか。 */
    private static boolean shows(List<String> focus, CourseLinesPacket.Gate gate) {
        return focus == null ? gate.start() : focus.contains(gate.course());
    }

    private CourseRenderer() {
    }

    public static void accept(CourseLinesPacket packet) {
        gates = packet.gates();
    }

    /**
     * 接続が変わったら捨てる。
     *
     * <p><b>{@code static} なので放っておくと前のワールドのゲートがそのまま残る。</b>
     * 線は変わったときにしか配られない（{@code RaceSync}）ので、コースの無いワールドへ
     * 入っても上書きされず、<b>何も無い場所に門が立ち続ける</b>。</p>
     */
    public static void onLoggingIn() {
        reset();
    }

    public static void onLoggingOut() {
        reset();
    }

    private static void reset() {
        gates = List.of();
    }

    /**
     * ゲートを出す。座標はカメラを原点にした世界の向き（{@code eye} を引いた値）で積む。
     * カメラからの差を double のまま取ってから float にするので、原点から遠くても桁が落ちない。
     */
    public static void submit(SubmitNodeCollector collector, Vec3 eye) {
        GateDisplay display = ClientConfig.gateDisplay;
        if (gates.isEmpty() || display == GateDisplay.HIDDEN) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        List<String> focus = focus(minecraft);
        List<CourseLinesPacket.Gate> shown = gates.stream().filter(gate -> shows(focus, gate)).toList();
        // 頂点は描く直前（同じフレームのうち）に積まれる。設定はここで読んだ値を使う
        if (DeferredGlows.active()) {
            // シェーダーパック使用中は記録だけして、パックの仕上げの直前（ヘッドライトの後）に半透明で重ねる
            // （そのまま出すとパックの gbuffer に乗り、ゲートが空に映る）
            emit(new Sink(DeferredGlows.GATES, new Matrix4f(), eye), shown, display, eye);
            return;
        }
        collector.submitCustomGeometry(new PoseStack(), EffectRenderTypes.TRANSLUCENT_OVERLAY,
                (pose, consumer) -> emit(new Sink(consumer, pose.pose(), eye), shown, display, eye));
    }

    private static void emit(Sink sink, List<CourseLinesPacket.Gate> shown, GateDisplay display, Vec3 eye) {
        for (CourseLinesPacket.Gate gate : shown) {
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
    }

    /** 頂点の書き込み先。カメラからの差を double のまま取ってから行列を掛ける。 */
    private record Sink(VertexConsumer consumer, Matrix4f view, Vec3 eye) {
        void vertex(double x, double y, double z, float r, float g, float b, float a) {
            consumer.addVertex(view, (float) (x - eye.x), (float) (y - eye.y), (float) (z - eye.z))
                    .setColor(r, g, b, a);
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
