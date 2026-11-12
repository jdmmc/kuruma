package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.hook.CameraAngles;

import com.jdmmc.kurumamod.config.CameraMode;
import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.jdmmc.kurumamod.client.model.CarModel;
import com.jdmmc.kurumamod.client.camera.ShowroomCamera;


/**
 * 三人称のカメラを<b>車の中心を軸に、決めた距離で</b>置き直す。
 *
 * <p><b>カメラの位置を毎フレーム自分で置く。</b>NeoForge 版は {@code Camera#setup} の最後に
 * 割り込む mixin（{@code mixin/CameraMixin}）が {@link #placement} を聞いて置き直す。</p>
 *
 * <p><b>これ以前は「乗り手の位置＝カメラの起点」をずらして距離を稼いでいたが、その方式は
 * 原理的に揺れる。</b>乗り手の位置は 20Hz でしか更新できないのに、カメラの角度は毎フレーム
 * 変わるためで、視点を振るたびに起点が遅れて追いかけ、画面がぐにゃぐにゃした。
 * 位置を直接置けば<b>毎フレーム・補間済みの車の位置から計算できる</b>ので、この食い違いが
 * そもそも生じない。</p>
 *
 * <p><b>Forge 1.20.1 版とはイベントの飛ぶ順番が違う。</b>あちらの {@code ComputeCameraAngles} は
 * {@code Camera#setup} の<b>後</b>に飛ぶので、イベントの中で位置を置けた。NeoForge 1.21.1 では
 * {@code setup} の<b>中で、位置を決める前</b>に飛ぶので、イベントで置いた位置はすぐバニラに
 * 上書きされる（H メニューの距離・高さが効かず、バニラの 4 ブロック固定になった）。そこで
 * 角度はイベントで足し、位置は {@code setup} の最後（mixin）で置く、と 2 つに分けてある。</p>
 *
 * <p>後方視点（F5 を 2 回）では {@code Camera#setup} が最後に向きを 180 度回すので、
 * 位置を置く時点の視線の逆は<b>車の前</b>になる。分岐は要らない。</p>
 */
public final class CarChaseCamera {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 壁に当たったときに手前で止める余裕 [ブロック]。 */
    private static final double WALL_MARGIN = 0.3;

    // 距離・ショールームの構え・回したぶんは core の ShowroomCamera

    /**
     * このフレームのショールームの構えの寄り具合と、イベントの時点の（自分で足す前の）角度。
     * イベントで決めて、{@link #placement} で使う。<b>寄り具合を進めるのは 1 フレーム 1 回</b>
     * （両方で進めると倍の速さで寄る）。
     */
    private static float frameLift;
    private static float frameYaw;
    private static float framePitch;

    private CarChaseCamera() {
    }

    /**
     * カメラの距離を刻む。負で近づき、正で遠ざかる。
     *
     * <p>設定ファイルへそのまま書いて保存する。<b>走りながら何度も触る値</b>なので、
     * 設定画面を開かせるより手元で刻めた方がよい。今いくつなのかは画面下に出す
     * （数字が見えないと、効いているのか分からない）。</p>
     */
    public static void stepDistance(int steps) {
        double distance = ShowroomCamera.stepDistance(steps);

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable("hud.kurumamod.camera_distance",
                    String.format("%.1f", distance)), true);
        }
    }

    /** 車をいじる画面で車のまわりを回す（{@link ShowroomCamera#orbit}）。 */
    public static void orbit(double dragX, double dragY) {
        ShowroomCamera.orbit(dragX, dragY);
    }

    /** 車をいじる画面での寄り引き（{@link ShowroomCamera#zoom}）。 */
    public static void zoom(double delta) {
        ShowroomCamera.zoom(delta);
    }

    /**
     * 三人称のあいだ、カメラの<b>角度</b>を足す（ショールームで回したぶんと見下ろすぶん）。
     *
     * <p>位置はここでは置けない（NeoForge 1.21.1 では、このあとバニラが位置を決め直す）。
     * 位置は {@link #placement} が {@code Camera#setup} の最後で置く。</p>
     *
     * <p>優先度を下げてあるのは、角度を触る他のハンドラ（{@link CarImpact} の揺れなど）と
     * 順番を争わないため。どちらも<b>足すだけ</b>なので打ち消し合わない。</p>
     */
    public static void onComputeCameraAngles(CameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        CarEntity car = riding(minecraft);
        if (car == null) {
            frameLift = 0.0F;
            return;
        }
        // 換装画面のあいだは引いて、回転の中心をフロアまで下げる
        frameLift = updateShowroom(minecraft);
        frameYaw = event.getYaw();
        framePitch = event.getPitch();

        // 車体向き固定では、左右の向きを車の向きに置き換える。差分で足すので衝突の揺れは残る。
        // 後方視点の 180 度はこの後 setup が回すので、ここでは足さない。位置はこの向きの
        // 視線から placement が置く
        boolean heading = ClientConfig.thirdPersonCamera == CameraMode.HEADING;
        if (heading) {
            float partial = (float) event.getPartialTick();
            frameYaw += car.getRenderYaw(partial) - minecraft.player.getViewYRot(partial);
            event.setYaw(frameYaw);
        }

        // 回したぶんは位置と同じだけ回し、そのうえで ShowroomCamera.SHOWROOM_PITCH だけ余分に下を向く——
        // 位置は「車が中心に来る」向きのままなので、下げたぶんだけ車が上へずれる
        if (frameLift > 0.0F) {
            event.setYaw(event.getYaw() + ShowroomCamera.orbitYaw() * frameLift);
            event.setPitch(event.getPitch() + ShowroomCamera.orbitPitch() * frameLift + ShowroomCamera.SHOWROOM_PITCH * frameLift);
        }
    }

    /** 三人称で車に乗っているならその車。それ以外は null（バニラのまま）。 */
    private static CarEntity riding(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.getCameraType().isFirstPerson()
                || minecraft.getCameraEntity() != player
                || !(player.getVehicle() instanceof CarEntity car)) {
            return null;
        }
        return car;
    }

    /**
     * 三人称のあいだ、カメラを置く位置。<b>{@code Camera#setup} の最後に mixin から呼ばれる。</b>
     *
     * <p>この時点でバニラは位置と向きを決め終えている（後方視点の 180 度もここまでに回してある）。
     * 返した位置で置き直す。</p>
     *
     * @return 置き直す位置。バニラのままでよければ null
     */
    public static Vec3 placement(Camera camera, float partial) {
        Minecraft minecraft = Minecraft.getInstance();
        CarEntity car = riding(minecraft);
        if (car == null) {
            return null;
        }
        float lift = frameLift;

        // 車の中心。位置は毎フレーム補間して取り直す（ここが 20Hz に縛られない理由）。
        //
        // エンティティの Y はシャシー基準面なので、車体メッシュはそこから
        // designRideHeight だけ下——つまりフロアはその高さにある。描画と同じ値を使うので、
        // 車高を変えても中心はフロアに乗ったままになる
        double bodyY = Mth.lerp(partial, car.yo, car.getY());
        double floorY = bodyY - CarModel.get(car.getCarId()).designRideHeight();
        Vec3 pivot = new Vec3(
                Mth.lerp(partial, car.xo, car.getX()),
                ShowroomCamera.pivotY(bodyY, floorY, lift),
                Mth.lerp(partial, car.zo, car.getZ()));

        double speedRatio = Math.min(1.0, Math.abs(car.getRenderSpeed()) / car.getSpec().topGearSpeed());
        double distance = ShowroomCamera.distance(speedRatio, lift);

        // 視線の逆方向。回したぶんは足す（＝カメラが車のまわりを回る）。一方
        // ShowroomCamera.SHOWROOM_PITCH は<b>足さない</b>——あれは視線だけを下げて車を画面の上へ
        // ずらすためのもので、位置にまで効かせると車が中心へ戻ってしまう
        Vec3 back;
        if (lift > 0.0F) {
            back = Vec3.directionFromRotation(framePitch + ShowroomCamera.orbitPitch() * lift,
                    frameYaw + ShowroomCamera.orbitYaw() * lift).scale(-1.0);
        } else {
            Vector3f look = camera.getLookVector();
            back = new Vec3(-look.x(), -look.y(), -look.z());
        }
        distance = clipDistance(car, pivot, back, distance);

        Vec3 position = pivot.add(back.scale(distance));
        // 中心をフロアへ下げたぶん、少し見上げただけで地面を割る。最後に押し戻す
        position = new Vec3(position.x, ShowroomCamera.keepAboveFloor(position.y, floorY, lift), position.z);
        return position;
    }

    /** ショールームの構えへの寄り具合を進める（{@link ShowroomCamera#update}）。 */
    private static float updateShowroom(Minecraft minecraft) {
        return ShowroomCamera.update(minecraft.getTimer().getGameTimeDeltaTicks());
    }

    /**
     * 壁に埋まらない距離まで縮める。
     *
     * <p>バニラの {@code getMaxZoom} と同じ考え方だが、こちらは<b>自分で置く位置</b>に対して
     * 掛ける必要がある（バニラの判定は 4 ブロックぶんにしか効かない）。</p>
     */
    private static double clipDistance(CarEntity car, Vec3 pivot, Vec3 back, double distance) {
        double shortest = distance;
        // 中心 1 本だと角がすり抜けるので、上下左右に少し振った 4 本も見る
        for (int i = 0; i < 5; i++) {
            double sideways = i == 0 ? 0.0 : ((i & 1) == 0 ? 0.1 : -0.1);
            double vertical = i == 0 ? 0.0 : (i <= 2 ? 0.1 : -0.1);
            Vec3 from = pivot.add(sideways, vertical, 0.0);
            BlockHitResult hit = car.level().clip(new ClipContext(from, from.add(back.scale(distance)),
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, car));
            if (hit.getType() != HitResult.Type.MISS) {
                shortest = Math.min(shortest, hit.getLocation().distanceTo(from) - WALL_MARGIN);
            }
        }
        return Math.max(0.0, shortest);
    }
}
