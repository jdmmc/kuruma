package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
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

    /**
     * 広がりを速度域のどこへ寄せるか。1 で速度に比例、0.5（平方根）で低中速側へ寄る。
     *
     * <p>比例にすると<b>変化のほとんどが滅多に出さない最高速側に溜まる</b>。
     * 実際に走る 80〜150km/h では数度しか広がらず、効いているのか分からない。
     * 平方根にすると 100km/h の時点で広がりの 6 割が出る。</p>
     */
    private static final double FOV_CURVE = 0.5;

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

    /**
     * 車速に応じて視野を広げる。速度計を見なくても速さが分かるようにするため。
     *
     * <p><b>割合の分母は {@code maxSpeed} ではなく {@link CarSpec#topGearSpeed()}。</b>
     * {@code maxSpeed} は暴走を止めるための上限で<b>実際には届かない</b>ため、
     * そこで割ると全開でも割合が 1 に届かず、上限を上げるほど広がりが痩せていく。
     * 既定の諸元では 300km/h で割ることになり、実際に出る 255km/h でも 0.85 止まりだった。</p>
     *
     * <p><b>広げる量は倍率ではなく度で足す。</b>倍率で掛けると視野を 100 度にしている人には
     * 同じ 1.36 倍が 36 度ぶんの広がりとして出てしまう。プレイヤーが設定している視野角を
     * 読んで、そこへ一定の度数を足した比を返す。</p>
     */
    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        CarEntity car = ridingCar(minecraft);
        if (car == null || ClientConfig.speedFov <= 0.0) {
            return;
        }
        double reference = car.getSpec().topGearSpeed();
        if (reference <= 0.0) {
            return;
        }
        double ratio = Math.min(1.0, Math.abs(car.getRenderSpeed()) / reference);
        double base = minecraft.options.fov().get();
        double widened = (base + ClientConfig.speedFov * Math.pow(ratio, FOV_CURVE)) / base;
        event.setNewFovModifier(event.getNewFovModifier() * (float) widened);
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
