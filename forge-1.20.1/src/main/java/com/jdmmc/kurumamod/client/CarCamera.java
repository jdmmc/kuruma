package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.CameraMode;
import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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
 *
 * <p><b>V はカメラモードの切り替え</b>（{@link CameraMode}）。一人称と三人称で別々に覚えていて、
 * 押したときの視点のほうを次へ送る。視線の追従はここ、カメラの位置と向きは
 * {@link CarChaseCamera}（三人称）と {@link CarCockpitCamera}（一人称）が受け持つ。</p>
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

    /** 前回こちらが設定した視線。これと違えばプレイヤーがマウスを動かしたと判断する。 */
    private static float appliedYaw;
    private static float appliedPitch;
    private static boolean hasApplied;
    private static int manualLookCooldown;

    private CarCamera() {
    }

    /** いまの視点（一人称／三人称）で選ばれているカメラモード。 */
    public static CameraMode currentMode() {
        return Minecraft.getInstance().options.getCameraType().isFirstPerson()
                ? ClientConfig.firstPersonCamera : ClientConfig.thirdPersonCamera;
    }

    /**
     * いまの視点のカメラモードを次へ送り、名前をアクションバーに出す。
     *
     * <p>設定ファイルへもそのまま書く。static フィールドも一緒に書くのは、
     * ファイル監視の {@code ModConfigEvent} を待つと反映が遅れるため。</p>
     */
    public static void cycleMode() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(player.getVehicle() instanceof CarEntity)) {
            return;
        }
        CameraMode mode = currentMode().next();
        if (mode.isFirstPerson()) {
            ClientConfig.firstPersonCamera = mode;
            ClientConfig.FIRST_PERSON_CAMERA.set(mode);
        } else {
            ClientConfig.thirdPersonCamera = mode;
            ClientConfig.THIRD_PERSON_CAMERA.set(mode);
        }
        ClientConfig.SPEC.save();
        hasApplied = false;
        manualLookCooldown = 0;
        player.displayClientMessage(Component.translatable("hud.kurumamod.camera_mode",
                Component.translatable(mode.translationKey())), true);
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
        CameraMode mode = currentMode();
        if (!mode.followsView() && mode != CameraMode.HEADING) {
            return;
        }
        if (mode == CameraMode.HEADING) {
            // 見た目は CarChaseCamera が毎フレーム車の向きで置く。ここでは照準や音の向きが
            // 画面と食い違わないよう、プレイヤーの向きも車へ揃えておくだけ
            player.setYRot(car.getYRot());
            hasApplied = false;
            manualLookCooldown = 0;
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
     * <p>割合の分母は {@link CarSpec#topGearSpeed()}（最高段でレブまで回した速度）。
     * 以前は暴走止めの上限（既定 300km/h）で割っていたので、実際に出る 255km/h でも
     * 0.85 止まりで、上限を上げるほど広がりが痩せていた。</p>
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
     * 追従を止めたければマウスを動かすか V で自由カメラにできるのは同じ。カメラの位置を置く
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
