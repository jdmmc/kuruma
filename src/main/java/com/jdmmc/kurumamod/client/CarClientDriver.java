package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CarTuningPacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.physics.CarInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * 運転中のキー入力を車へ渡すクライアント側の橋渡し。
 *
 * <p>ティックの頭（START）で入力を差し込んでおくことで、この後のエンティティティックで
 * {@link CarEntity#tick()} が最新の入力を使って物理を解ける。さらにその直後に
 * 同乗者として {@code LocalPlayer} がティックされ、確定した車の位置がバニラの
 * {@code ServerboundMoveVehiclePacket} でサーバーへ送られる。</p>
 *
 * <p>調整画面（{@link CarTuningScreen}）の値を運転中の車へ流し込むのもここ。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarClientDriver {

    /** 直前まで運転していた車。降りたときに入力を戻すために覚えておく。 */
    @Nullable
    private static CarEntity driving;
    /** 運転中の車へ反映済みの調整値の版。-1 は未反映。 */
    private static int appliedTuningRevision = -1;

    private CarClientDriver() {
    }

    /**
     * ヘッドライトを点けているか。
     *
     * <p>車ではなく<b>運転者</b>が持つ。降りて乗り直しても点いたままの方が自然だし、
     * 車ごとに覚えさせるほどのものでもない。</p>
     */
    private static boolean headlightsOn;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        openTuningScreenIfRequested(minecraft);
        openControllerScreenIfRequested(minecraft);
        // 画面を開いていても読む。割り当て画面が今の入力を出せるように
        ControllerInput.poll();
        while (KurumaKeys.OPEN_MENU.consumeClick()) {
            minecraft.setScreen(new KurumaMenuScreen(null));
        }
        while (KurumaKeys.TOGGLE_CAMERA.consumeClick()) {
            CarCamera.toggle();
        }
        while (KurumaKeys.TOGGLE_HEADLIGHTS.consumeClick()) {
            headlightsOn = !headlightsOn;
        }
        while (KurumaKeys.CAMERA_CLOSER.consumeClick()) {
            CarChaseCamera.stepDistance(-1);
        }
        while (KurumaKeys.CAMERA_FARTHER.consumeClick()) {
            CarChaseCamera.stepDistance(1);
        }

        CarEntity car = null;
        if (player != null && !minecraft.isPaused()
                && player.getVehicle() instanceof CarEntity vehicle
                && vehicle.getControllingPassenger() == player) {
            car = vehicle;
        }

        if (driving != car) {
            if (driving != null) {
                driving.setDriverInput(CarInput.NONE);
            }
            driving = car;
            if (car != null) {
                // 乗り換えたら、その車の値を調整画面へ<b>取り込む</b>。逆に押し込むと、
                // カーパックの車に乗った瞬間、前の車の調整値で上書きされて別物になる。
                // 「戻す」の戻り先はその車種の素の諸元
                CarTuning.adopt(car.getSpec(), CarTypes.get(car.getCarId()).spec());
            }
            // 取り込んだぶんを送り返さない
            appliedTuningRevision = CarTuning.revision();
        }

        if (car == null) {
            return;
        }

        if (appliedTuningRevision != CarTuning.revision()) {
            car.setSpec(CarTuning.spec());
            appliedTuningRevision = CarTuning.revision();
            sendTuningToServer();
        }

        // 変速の要求は押した瞬間だけ立てる。押しっぱなしで段が流れないよう、
        // 溜まっているクリックは（画面を開いていても）ここで必ず吸い出す
        boolean shiftUp = false;
        while (KurumaKeys.SHIFT_UP.consumeClick()) {
            shiftUp = true;
        }
        boolean shiftDown = false;
        while (KurumaKeys.SHIFT_DOWN.consumeClick()) {
            shiftDown = true;
        }

        // 灯火は操作ではなく状態なので、画面を開いていても消えないよう先に渡す
        car.setHeadlights(headlightsOn);

        // 画面を開いている間は運転操作を受け付けない（入力が画面に取られているため）。
        // カウントダウン中も動かせない。スタート信号を意味のあるものにするため
        if (minecraft.screen != null || RaceHud.isFrozen()) {
            car.setDriverInput(CarInput.NONE);
            return;
        }

        // コントローラを触っている間はそちらを使い、触っていなければキーボードへ戻す。
        // どちらかに固定すると、持ち替えるたびに設定を触ることになる
        CarInput pad = ControllerInput.toCarInput();
        if (pad != null && !ControllerInput.isIdle()) {
            car.setDriverInput(pad);
            return;
        }

        Input in = player.input;
        // サイドブレーキはスペース。独自のキーバインドを作るとバニラのジャンプと
        // 衝突するので、ジャンプの押下状態をそのまま借りる
        car.setDriverInput(new CarInput(in.up, in.down, in.left, in.right,
                shiftUp, shiftDown, in.jumping));
    }

    /** 調整画面の値をサーバー側の車へ送る。運転していなければ何もしない。 */
    public static void sendTuningToServer() {
        CarEntity car = driving;
        if (car == null) {
            return;
        }
        KurumaNetwork.CHANNEL.sendToServer(CarTuningPacket.of(car.getId(), CarTuning.spec()));
    }

    private static void openControllerScreenIfRequested(Minecraft minecraft) {
        boolean requested = false;
        while (KurumaKeys.OPEN_CONTROLLER.consumeClick()) {
            requested = true;
        }
        if (requested && minecraft.screen == null) {
            minecraft.setScreen(new ControllerScreen());
        }
    }

    private static void openTuningScreenIfRequested(Minecraft minecraft) {
        boolean requested = false;
        while (KurumaKeys.OPEN_TUNING.consumeClick()) {
            requested = true;
        }
        if (requested && minecraft.screen == null) {
            minecraft.setScreen(new CarTuningScreen());
        }
    }
}
