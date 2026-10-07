package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 一人称のカメラを<b>車体に固定する</b>。
 *
 * <p>乗員の位置は車の位置にヨーだけで席を足したものだったので、車体がロールしても
 * ピッチしても目線は水平のまま動かず、<b>車内だけが傾いて見えた</b>（座席は動かないのに
 * ダッシュボードとピラーが揺れる）。実車の運転者は車内と一緒に揺れ、外の景色が傾く。</p>
 *
 * <ul>
 *   <li><b>位置</b>…席と目の高さを車体の姿勢で回し、補間済みの車の位置へ足す
 *       （{@link CarEntity#riderEyePosition}）。毎フレーム置くので 20Hz の階段にならない</li>
 *   <li><b>向き</b>…プレイヤーの向きを「車体に対する向き」として読み、車体の姿勢を掛けてから
 *       ヨー・ピッチ・ロールに戻す。横を向いていればロールは見上げ／見下ろしとして効く</li>
 * </ul>
 *
 * <p><b>プレイヤーの向きそのものは書き換えない</b>（{@link CarImpact} と同じ理由）。
 * 照準の判定はプレイヤーの向きのままなので、車体が傾いているぶんだけ画面中央からずれる。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarCockpitCamera {

    private CarCockpitCamera() {
    }

    /**
     * 優先度を下げてあるのは、{@link CarImpact} の揺れを<b>車体に対する向き</b>として
     * 取り込むため。揺れを足した後の角度を回すので、揺れは打ち消されない。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !minecraft.options.getCameraType().isFirstPerson()
                || minecraft.getCameraEntity() != player
                || !(player.getVehicle() instanceof CarEntity car)) {
            return;
        }
        float partial = (float) event.getPartialTick();

        // Forge 1.20.1 ではこのイベントが Camera#setup の後に飛ぶので、ここで置いた位置がそのまま使われる
        if (CarChaseCamera.SET_POSITION != null) {
            Vec3 eye = car.riderEyePosition(player, partial);
            try {
                CarChaseCamera.SET_POSITION.invoke(event.getCamera(), eye.x, eye.y, eye.z);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("カメラの位置を置けなかった", e);
            }
        }

        float[] angles = tilt(car, event.getYaw(), event.getPitch(), partial);
        event.setYaw(angles[0]);
        event.setPitch(angles[1]);
        event.setRoll(event.getRoll() + angles[2]);
    }

    /**
     * 車体の姿勢を掛けた視線の向き。
     *
     * <p>オイラー角を足し引きするのではなく、<b>前と上のベクトルを回してから角度へ戻す</b>。
     * 足し引きは傾きが小さいうちしか成り立たず、横転したときに視線が暴れる。</p>
     *
     * @return {ヨー, ピッチ, ロール}（度。ロールは正で右へ傾く）
     */
    static float[] tilt(CarEntity car, float yaw, float pitch, float partial) {
        float carYaw = car.getRenderYaw(partial);
        float carPitch = car.getRenderPitch(partial);
        float carRoll = car.getRenderRoll(partial);
        // 車体に対する向き。乗員の座標系（+Z 前・+X 左）はヨー 0 のバニラの向きと一致する
        float relativeYaw = yaw - carYaw;
        Vec3 forward = CarEntity.toWorld(Vec3.directionFromRotation(pitch, relativeYaw),
                carYaw, carPitch, carRoll);
        Vec3 up = CarEntity.toWorld(Vec3.directionFromRotation(pitch - 90.0F, relativeYaw),
                carYaw, carPitch, carRoll);

        float newPitch = (float) (-Math.asin(Mth.clamp(forward.y, -1.0, 1.0)) * Mth.RAD_TO_DEG);
        float newYaw = (float) (Mth.atan2(-forward.x, forward.z) * Mth.RAD_TO_DEG);
        // 元の角度から連続させる（-180/180 で飛ばさない）
        newYaw = yaw + Mth.wrapDegrees(newYaw - yaw);

        // 傾けずにその向きを見たときの上と右に対して、実際の上がどれだけ右へ倒れているか
        Vec3 levelUp = Vec3.directionFromRotation(newPitch - 90.0F, newYaw);
        Vec3 right = forward.cross(levelUp);
        float roll = (float) (Mth.atan2(up.dot(right), up.dot(levelUp)) * Mth.RAD_TO_DEG);
        return new float[] {newYaw, newPitch, roll};
    }
}
