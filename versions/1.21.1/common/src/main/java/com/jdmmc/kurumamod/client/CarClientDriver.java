package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.mixin.KeyMappingAccessor;

import com.jdmmc.kurumamod.network.KurumaNetwork;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CarPartRequestPacket;
import com.jdmmc.kurumamod.network.CarTuningPacket;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarInput;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import com.jdmmc.kurumamod.client.screen.CarPartScreen;
import com.jdmmc.kurumamod.client.screen.CarTuningScreen;
import com.jdmmc.kurumamod.client.input.ControllerInput;
import com.jdmmc.kurumamod.client.screen.ControllerScreen;
import com.jdmmc.kurumamod.client.input.DriverInput;
import com.jdmmc.kurumamod.client.screen.HeadlightScreen;
import com.jdmmc.kurumamod.client.screen.KurumaMenuScreen;
import com.jdmmc.kurumamod.client.hud.RaceHud;
import com.jdmmc.kurumamod.client.race.RaceLobby;
import com.jdmmc.kurumamod.client.screen.SeatScreen;
import com.jdmmc.kurumamod.platform.McUiScreen;

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
public final class CarClientDriver {

    /** 直前まで運転していた車。降りたときに入力を戻すために覚えておく。 */
    @Nullable
    private static CarEntity driving;
    /** 運転中の車へ反映済みの調整値の版。-1 は未反映。 */
    private static int appliedTuningRevision = -1;

    private CarClientDriver() {
    }

    /**
     * 運転している車のヘッドライトを点けているか。
     *
     * <p><b>状態そのものは車が持つ</b>（{@link CarEntity#isHeadlightOnSynced}）。乗り込んだときに
     * 車の状態を引き継ぎ、L キーで切り替えたぶんを毎ティック車へ渡す。点けたまま降りた車は
     * 点いたまま残り、別の車へ乗り換えるとその車の状態になる。</p>
     */
    private static boolean headlightsOn;

    public static void onClientTick() {

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        openTuningScreenIfRequested(minecraft);
        openControllerScreenIfRequested(minecraft);
        // 画面を開いていても読む。割り当て画面が今の入力を出せるように
        ControllerInput.poll();
        while (KurumaKeys.OPEN_MENU.consumeClick()) {
            minecraft.setScreen(new McUiScreen(new KurumaMenuScreen(null)));
        }
        // レース。車に乗っていなくても開ける（歩いて集まってから参加・主催することもある）
        while (KurumaKeys.OPEN_RACE.consumeClick()) {
            RaceLobby.onKey();
        }
        while (KurumaKeys.OPEN_HEADLIGHT_SETTINGS.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new McUiScreen(new HeadlightScreen()));
            }
        }
        while (KurumaKeys.OPEN_SEAT_DEBUG.consumeClick()) {
            // 座席は車種ごとの値なので、乗っている車の車種を合わせる
            CarEntity riding = ridingCar(minecraft);
            if (riding == null) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable("message.kurumamod.need_car"), true);
                }
            } else if (minecraft.screen == null) {
                minecraft.setScreen(new McUiScreen(new SeatScreen(riding.getCarId())));
            }
        }
        while (KurumaKeys.OPEN_SOUND_DEBUG.consumeClick()) {
            // 音は車種ごとの値なので、乗っている車の車種を合わせる
            CarEntity riding = ridingCar(minecraft);
            if (riding == null) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable("message.kurumamod.need_car"), true);
                }
            } else if (minecraft.screen == null) {
                minecraft.setScreen(new McUiScreen(KurumaMenuScreen.soundDebug()));
            }
        }
        while (KurumaKeys.TOGGLE_CAMERA.consumeClick()) {
            CarCamera.cycleMode();
        }
        while (KurumaKeys.TOGGLE_HEADLIGHTS.consumeClick()) {
            // ライトは車の状態なので、運転していなければ切り替えない
            if (requireDriver(minecraft)) {
                headlightsOn = !headlightsOn;
            }
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
                // ヘッドライトも車の状態を引き継ぐ。運転者の側の状態を押し込むと、
                // 点けたまま置いてあった車に乗った瞬間に消える
                headlightsOn = car.isHeadlightOnSynced();
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

        // カウントダウン中は動かせない。スタート信号を意味のあるものにするため
        if (RaceHud.isFrozen()) {
            car.setDriverInput(frozenInput(minecraft, car));
            return;
        }

        // 調整画面（G）だけは開いたまま運転できる。挙動の変化を走りながら確かめる画面なので。
        // それ以外の画面は入力が画面に取られているので受け付けない
        if (minecraft.screen != null) {
            if (isDrivableScreen(minecraft.screen)) {
                car.setDriverInput(inputThroughScreen(minecraft));
            } else {
                car.setDriverInput(CarInput.NONE);
            }
            return;
        }
        // 画面越しの変速は押しっぱなしの状態で閉じたときに 1 回ぶん誤って拾わないよう、
        // 画面の外では「押されている」で揃えておく（離すまで次の押下にならない）
        DriverInput.trackShiftKeys(isKeyDown(minecraft, KurumaKeys.SHIFT_UP),
                isKeyDown(minecraft, KurumaKeys.SHIFT_DOWN));

        Input in = player.input;
        // サイドブレーキはスペース。独自のキーバインドを作るとバニラのジャンプと
        // 衝突するので、ジャンプの押下状態をそのまま借りる
        // コントローラを触っている間はそちらを使う（DriverInput#choose）
        car.setDriverInput(DriverInput.choose(new CarInput(in.up, in.down, in.left, in.right,
                shiftUp, shiftDown, in.jumping)));
    }

    /** スタートの信号の間の入力（{@link DriverInput#frozen}）。 */
    private static CarInput frozenInput(Minecraft minecraft, CarEntity car) {
        Input in = minecraft.player.input;
        return DriverInput.frozen(car.getRenderSpeed() > 0.3F, in.left, in.right);
    }

    /**
     * 画面を開いたままの運転入力。
     *
     * <p><b>{@code KeyMapping#isDown} は使えない。</b>画面を開くとバニラが {@code KeyMapping.releaseAll()}
     * を呼び、開いている間のキーは画面にしか届かないので、押していても false のまま。
     * そこで割り当てられているキーの物理的な状態を GLFW から直接読む（割り当ては尊重する）。
     * 変速は押した瞬間だけを拾う。</p>
     */
    private static CarInput inputThroughScreen(Minecraft minecraft) {
        var options = minecraft.options;
        return DriverInput.throughScreen(isKeyDown(minecraft, options.keyUp), isKeyDown(minecraft, options.keyDown),
                isKeyDown(minecraft, options.keyLeft), isKeyDown(minecraft, options.keyRight),
                isKeyDown(minecraft, KurumaKeys.SHIFT_UP), isKeyDown(minecraft, KurumaKeys.SHIFT_DOWN),
                isKeyDown(minecraft, options.keyJump));
    }

    /**
     * 開いたまま運転できる画面か。走りながら見て決める画面（セッティング・パーツ・メニュー・座席）。
     *
     * <p><b>入力欄（{@code EditBox}）のある画面を足してはいけない。</b>運転に使うキーを画面が握りつぶすので、
     * W・A・S・D とスペースが打てなくなる（プリセットの名前を付ける画面がそう）。</p>
     */
    public static boolean isDrivableScreen(Screen raw) {
        // core の画面（McUiScreen）は中身で見分ける
        Object screen = McUiScreen.unwrap(raw);
        // ナンバーの字を打っている間は運転しない。W・A・S・D を打つたびに車が動いてしまう
        if (screen instanceof CarPartScreen parts && parts.isTyping()) {
            return false;
        }
        return screen instanceof CarTuningScreen || screen instanceof CarPartScreen
                || screen instanceof KurumaMenuScreen || screen instanceof SeatScreen;
    }

    /** 画面を開いたまま運転するときに車へ渡すキーか。画面側がこれらを握りつぶすのに使う。 */
    public static boolean isDrivingKey(int keyCode, int scanCode) {
        var options = Minecraft.getInstance().options;
        for (KeyMapping mapping : new KeyMapping[]{options.keyUp, options.keyDown, options.keyLeft,
                options.keyRight, options.keyJump, KurumaKeys.SHIFT_UP, KurumaKeys.SHIFT_DOWN}) {
            if (mapping.matches(keyCode, scanCode)) {
                return true;
            }
        }
        return false;
    }

    /** 割り当てられたキー（またはマウスボタン）がいま物理的に押されているか。 */
    private static boolean isKeyDown(Minecraft minecraft, KeyMapping mapping) {
        InputConstants.Key key = ((KeyMappingAccessor) mapping).kuruma$getKey();
        if (key.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        long window = minecraft.getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(window, key.getValue());
    }

    /** いま運転している車。運転していなければ null。 */
    @Nullable
    public static CarEntity driving() {
        return driving;
    }

    /**
     * 換装を<b>要求</b>する。運転していなければ何もしない。
     *
     * <p><b>手元では反映しない。</b>何を履けるか決めるのはサーバーなので、通ったかどうかは
     * {@code CarPartsPacket} が戻ってきて初めて分かる。先に反映すると、サーバーが弾いた
     * ときに<b>自分にだけ見える部品</b>が残る。</p>
     *
     * <p><b>キャンバー角だけは手元で先に反映してよい</b>（{@code CarPartScreen} がそうしている）。
     * 部品と違って弾かれることが無く、範囲へ丸められるだけなので、先に出しても食い違わない。
     * スライダーを動かすたびに送ると 1 秒に何十回も飛ぶので、送るのは変わったティックごと（最大 20 回/秒）と閉じるとき。</p>
     *
     * @param setting その場所をどうしたいか（部品と、キャンバー角の上書き）
     */
    public static void requestPart(PartSlot slot, PartFitment.Setting setting) {
        CarEntity car = driving;
        if (car == null) {
            return;
        }
        KurumaNetwork.sendToServer(new CarPartRequestPacket(car.getId(), slot, setting));
    }

    /**
     * 塗装を<b>要求</b>する。運転していなければ何もしない。
     *
     * <p>部品と同じく手元では反映せず、{@code CarPartsPacket} が戻ってきて変わる。</p>
     *
     * @param paint 塗装の id。null で既定の色へ戻す
     */
    public static void requestPaint(@Nullable String paint) {
        CarEntity car = driving;
        if (car == null) {
            return;
        }
        KurumaNetwork.sendToServer(new com.jdmmc.kurumamod.network.CarPaintRequestPacket(car.getId(), paint));
    }

    /**
     * ナンバーの中身（打った字と付ける位置）を<b>要求</b>する。運転していなければ何もしない。
     *
     * <p>サーバーが様式の決まりとフィルタに通してから {@code CarPartsPacket} で配り直す。</p>
     */
    public static void requestPlate(com.jdmmc.kurumamod.plate.PlateLook plate) {
        CarEntity car = driving;
        if (car == null) {
            return;
        }
        KurumaNetwork.sendToServer(new com.jdmmc.kurumamod.network.CarPlateRequestPacket(car.getId(), plate));
    }

    /** 調整画面の値をサーバー側の車へ送る。運転していなければ何もしない。 */
    public static void sendTuningToServer() {
        CarEntity car = driving;
        if (car == null) {
            return;
        }
        KurumaNetwork.sendToServer(CarTuningPacket.of(car.getId(), CarTuning.spec()));
    }

    private static void openControllerScreenIfRequested(Minecraft minecraft) {
        boolean requested = false;
        while (KurumaKeys.OPEN_CONTROLLER.consumeClick()) {
            requested = true;
        }
        if (requested && minecraft.screen == null) {
            minecraft.setScreen(new McUiScreen(new ControllerScreen()));
        }
    }

    private static void openTuningScreenIfRequested(Minecraft minecraft) {
        boolean requested = false;
        while (KurumaKeys.OPEN_TUNING.consumeClick()) {
            requested = true;
        }
        // セッティングは乗っている車の諸元を変える画面なので、乗っていなければ開かない。
        // 助手席なら開けるが見るだけ（人の車なので触れない。CarTuningScreen#editable）
        if (requested && minecraft.screen == null) {
            CarEntity car = ridingCar(minecraft);
            if (car == null) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable("message.kurumamod.need_car"), true);
                }
            } else {
                if (car.getControllingPassenger() != minecraft.player) {
                    viewPassengerCar(car);
                }
                minecraft.setScreen(new McUiScreen(new CarTuningScreen()));
            }
        }
    }

    /** 乗っている車（運転席でも助手席でも）。乗っていなければ null。 */
    public static CarEntity ridingCar(Minecraft minecraft) {
        return minecraft.player != null && minecraft.player.getVehicle() instanceof CarEntity car ? car : null;
    }

    /** 自分が運転しているか（セッティングを変えてよいか）。 */
    public static boolean isDriver(Minecraft minecraft) {
        CarEntity car = ridingCar(minecraft);
        return car != null && car.getControllingPassenger() == minecraft.player;
    }

    /**
     * 助手席から見ている車の値を調整画面の入れ物へ取り込む（見るだけ。送り返さない）。
     * 運転している人がセッティングを変えると車の諸元が届くので、画面は毎フレームこれを呼んで追う。
     */
    public static void viewPassengerCar(CarEntity car) {
        if (CarTuning.spec() != car.getSpec()) {
            CarTuning.adopt(car.getSpec(), CarTypes.get(car.getCarId()).spec());
        }
    }

    /**
     * 運転しているか。していなければアクションバーで知らせて false を返す。
     *
     * <p>G（セッティング）や L（ライト）は乗っている車の状態を変えるキーなので、降りているときに
     * 押しても何も起きない。黙って無視すると壊れているように見えるので理由を出す。
     * <b>助手席では何もしない（知らせもしない）。</b>人の車なので、触れないのが当たり前。</p>
     */
    private static boolean requireDriver(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        if (player.getVehicle() instanceof CarEntity car) {
            return car.getControllingPassenger() == player;
        }
        player.displayClientMessage(Component.translatable("message.kurumamod.need_car"), true);
        return false;
    }
}
