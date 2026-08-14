package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 運転中の視点。
 *
 * <p><b>カメラそのものは動かさず、プレイヤーの視線を車へ追従させる。</b>
 * バニラの三人称カメラはプレイヤーの視線の逆方向へ置かれるので、視線が車の向きを向いていれば
 * そのまま追跡カメラになる。カメラの位置を直接触るには {@code Camera#setPosition} を
 * アクセストランスフォーマで開ける必要があるが、この ForgeGradle では AT 付きの成果物の
 * 生成に失敗するため採らなかった（距離がバニラ固定の 4 ブロックになるのが唯一の妥協点）。</p>
 *
 * <p>追従先は車の向きそのものではなく、<b>向きと進行方向の間</b>。こうするとドリフト中に
 * カメラが進行方向側へ振られ、横を向いた車体が見える。</p>
 *
 * <p>マウスを動かしている間は自動追従を止める。勝手に引き戻されると周りを見られないため。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarCamera {

    /** 1 ティックで詰める角度の割合。小さいほど緩やかに追従する。 */
    private static final float FOLLOW_RATE = 0.18F;
    /** 進行方向をどれだけ向くか。1 で完全に進行方向、0 で車の向き。 */
    private static final float DRIFT_LOOK = 0.6F;
    /** 見下ろす角度 [度]。 */
    private static final float LOOK_DOWN = 8.0F;
    /** マウス操作の後、これだけのティック数は自動追従を止める。 */
    private static final int MANUAL_LOOK_TICKS = 30;
    /** 視線が既に合っているとみなす角度差 [度]。ここを下回れば動かさない。 */
    private static final float SETTLED_DEGREES = 0.05F;

    /** 最高速で視野をどれだけ広げるか [度]。速度感を出すための味付け。 */
    private static final float SPEED_FOV_GAIN = 12.0F;

    /** 自動追従を使うか。好みが分かれるので切れるようにしてある。 */
    private static boolean following = true;

    /** 前回こちらが設定した視線。これと違えばプレイヤーがマウスを動かしたと判断する。 */
    private static float appliedYaw;
    private static float appliedPitch;
    private static boolean hasApplied;
    private static int manualLookCooldown;

    private CarCamera() {
    }

    /** 視線が車へ自動追従しているか。 */
    public static boolean isFollowing() {
        return following;
    }

    public static void toggle() {
        following = !following;
        hasApplied = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        CarEntity car = ridingCar(minecraft);
        if (car == null || player == null) {
            hasApplied = false;
            manualLookCooldown = 0;
            return;
        }
        if (!following) {
            return;
        }

        // 前回こちらが入れた値から動いていれば、プレイヤーが自分で見回している
        if (hasApplied
                && (Math.abs(Mth.wrapDegrees(player.getYRot() - appliedYaw)) > SETTLED_DEGREES
                || Math.abs(player.getXRot() - appliedPitch) > SETTLED_DEGREES)) {
            manualLookCooldown = MANUAL_LOOK_TICKS;
        }
        if (manualLookCooldown > 0) {
            manualLookCooldown--;
            hasApplied = false;
            return;
        }

        // 車の向きと進行方向の間を向く。ドリフト中は進行方向側へ振られて車体の横が見える
        float targetYaw = car.getYRot() + (float) car.getRenderSlipAngleDegrees() * DRIFT_LOOK;
        float yaw = player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot()) * FOLLOW_RATE;
        float pitch = player.getXRot() + (LOOK_DOWN - player.getXRot()) * FOLLOW_RATE;

        player.setYRot(yaw);
        player.setXRot(pitch);
        appliedYaw = yaw;
        appliedPitch = pitch;
        hasApplied = true;
    }

    /** 車速に応じて視野を広げる。速度計を見なくても速さが分かるようにするため。 */
    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        CarEntity car = ridingCar(Minecraft.getInstance());
        if (car == null) {
            return;
        }
        double ratio = Math.min(1.0, Math.abs(car.getRenderSpeed()) / car.getSpec().maxSpeed());
        // 視野角そのものではなく倍率で来るので、既定 70 度を基準に換算する
        float widened = (70.0F + SPEED_FOV_GAIN * (float) ratio) / 70.0F;
        event.setNewFovModifier(event.getNewFovModifier() * widened);
    }

    /**
     * ローカルプレイヤーが乗っている車。乗っていなければ null。
     *
     * <p><b>運転者に限らない。</b>助手席でも視線が車へ追従した方がよいし、
     * 追従を止めたければマウスを動かすか V で切れるのは同じ。カメラの位置を置く
     * {@link CarChaseCamera} も乗員かどうかしか見ていない。</p>
     */
    private static CarEntity ridingCar(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.isPaused()) {
            return null;
        }
        return player.getVehicle() instanceof CarEntity car ? car : null;
    }
}
