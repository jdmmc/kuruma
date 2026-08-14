package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Vector3f;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.reflect.Method;

/**
 * 三人称のカメラを<b>車の中心を軸に、決めた距離で</b>置き直す。
 *
 * <p><b>カメラの位置を毎フレーム自分で置く。</b>{@code Camera#setPosition} は protected だが、
 * <b>アクセストランスフォーマではなくリフレクションで開ける</b>（この ForgeGradle は AT を
 * 有効にすると成果物の解決に失敗するが、リフレクションはビルドに何も要求しない）。
 * SRG 名を {@link ObfuscationReflectionHelper} に渡すので、開発環境でも製品環境でも通る。</p>
 *
 * <p><b>これ以前は「乗り手の位置＝カメラの起点」をずらして距離を稼いでいたが、その方式は
 * 原理的に揺れる。</b>乗り手の位置は 20Hz でしか更新できないのに、カメラの角度は毎フレーム
 * 変わるためで、視点を振るたびに起点が遅れて追いかけ、画面がぐにゃぐにゃした。
 * 位置を直接置けば<b>毎フレーム・補間済みの車の位置から計算できる</b>ので、この食い違いが
 * そもそも生じない。</p>
 *
 * <p>{@code ViewportEvent.ComputeCameraAngles} は {@code Camera#setup} の<b>後</b>に飛ぶので、
 * ここで置き直した位置がそのまま描画に使われる。角度はこの後 {@code setAnglesInternal} で
 * 入れ直されるが、位置には触られない。</p>
 *
 * <p>後方視点（F5 を 2 回）では {@code Camera#setup} が向きを 180 度回して渡してくるので、
 * <b>同じ式のまま車の前へ回り込む</b>。分岐は要らない。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarChaseCamera {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code Camera#setPosition(double, double, double)}。見つからなければ諦めてバニラのままにする。 */
    private static final Method SET_POSITION = findSetPosition();

    /** 壁に当たったときに手前で止める余裕 [ブロック]。 */
    private static final double WALL_MARGIN = 0.3;

    /** キー 1 回で動く距離 [ブロック]。 */
    private static final double DISTANCE_STEP = 1.0;
    /** 距離の下限。0 まで詰めると車の内側に入って何も見えなくなる。 */
    private static final double MIN_DISTANCE = 1.5;
    /** 距離の上限。 */
    private static final double MAX_DISTANCE = 24.0;

    private CarChaseCamera() {
    }

    private static Method findSetPosition() {
        try {
            return ObfuscationReflectionHelper.findMethod(Camera.class, "m_90584_",
                    double.class, double.class, double.class);
        } catch (RuntimeException e) {
            LOGGER.warn("カメラの位置を置けない（Camera#setPosition が見つからない）。"
                    + "三人称はバニラの 4 ブロック固定になる", e);
            return null;
        }
    }

    /**
     * カメラの距離を刻む。負で近づき、正で遠ざかる。
     *
     * <p>設定ファイルへそのまま書いて保存する。<b>走りながら何度も触る値</b>なので、
     * 設定画面を開かせるより手元で刻めた方がよい。今いくつなのかは画面下に出す
     * （数字が見えないと、効いているのか分からない）。</p>
     */
    public static void stepDistance(int steps) {
        double distance = Mth.clamp(ClientConfig.cameraDistance + steps * DISTANCE_STEP,
                MIN_DISTANCE, MAX_DISTANCE);
        ClientConfig.cameraDistance = distance;
        ClientConfig.CAMERA_DISTANCE.set(distance);
        ClientConfig.SPEC.save();

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable("hud.kurumamod.camera_distance",
                    String.format("%.1f", distance)), true);
        }
    }

    /**
     * 三人称のあいだ、カメラを車の中心の後ろへ置く。
     *
     * <p>優先度を下げてあるのは、角度を触る他のハンドラ（{@link CarImpact} の揺れなど）と
     * 順番を争わないため。位置と角度は独立なので実際には衝突しないが、
     * 「最後に位置を決める」ことをはっきりさせておく。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (SET_POSITION == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.getCameraType().isFirstPerson()
                || minecraft.getCameraEntity() != player
                || !(player.getVehicle() instanceof CarEntity car)) {
            return;
        }

        Camera camera = event.getCamera();
        float partial = (float) event.getPartialTick();
        // 車の中心。位置は毎フレーム補間して取り直す（ここが 20Hz に縛られない理由）
        Vec3 pivot = new Vec3(
                Mth.lerp(partial, car.xo, car.getX()),
                Mth.lerp(partial, car.yo, car.getY()) + ClientConfig.cameraHeight,
                Mth.lerp(partial, car.zo, car.getZ()));

        double speedRatio = Math.min(1.0, Math.abs(car.getRenderSpeed()) / car.getSpec().maxSpeed());
        double distance = ClientConfig.cameraDistance + ClientConfig.cameraSpeedPull * speedRatio;

        // 視線の逆方向。カメラは既に setup 済みなので、向きはここから取れば毎フレーム正確
        Vector3f look = camera.getLookVector();
        Vec3 back = new Vec3(-look.x(), -look.y(), -look.z());
        distance = clipDistance(car, pivot, back, distance);

        Vec3 position = pivot.add(back.scale(distance));
        try {
            SET_POSITION.invoke(camera, position.x, position.y, position.z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("カメラの位置を置けなかった", e);
        }
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
