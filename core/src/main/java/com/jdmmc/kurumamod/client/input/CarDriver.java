package com.jdmmc.kurumamod.client.input;

import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.CarTuning;
import com.jdmmc.kurumamod.client.hud.RaceHud;
import com.jdmmc.kurumamod.client.race.RaceClient;
import com.jdmmc.kurumamod.client.race.RaceLobby;
import com.jdmmc.kurumamod.client.screen.CarPartScreen;
import com.jdmmc.kurumamod.client.screen.CarPresetScreen;
import com.jdmmc.kurumamod.client.screen.CarTuningScreen;
import com.jdmmc.kurumamod.client.screen.ControllerScreen;
import com.jdmmc.kurumamod.client.screen.HeadlightScreen;
import com.jdmmc.kurumamod.client.screen.KurumaMenuScreen;
import com.jdmmc.kurumamod.client.screen.SeatScreen;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.network.CarPaintRequestPacket;
import com.jdmmc.kurumamod.network.CarPartRequestPacket;
import com.jdmmc.kurumamod.network.CarPlateRequestPacket;
import com.jdmmc.kurumamod.network.CarTuningPacket;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarInput;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.race.RaceText;
import org.jetbrains.annotations.Nullable;

/**
 * 運転中のキー入力を車へ渡す。版の {@code CarClientDriver} がティックの頭（START）で {@link #tick} を呼ぶ。
 *
 * <p>ティックの頭で入力を差し込んでおくことで、この後のエンティティのティックで車が最新の入力を使って物理を解ける。
 * 調整画面（{@link CarTuningScreen}）の値を運転中の車へ流し込むのもここ。</p>
 *
 * <p><b>判断はここにだけ置く。</b>版が持つのはキーの読み方（{@link Host}）と、車のエンティティ（{@link Car}）だけ。</p>
 */
public final class CarDriver {

    /** 押した回数を数えるキー（溜まったクリックを吸い出す）。 */
    public enum Press {
        OPEN_MENU, OPEN_RACE, OPEN_HEADLIGHT_SETTINGS, OPEN_SEAT_DEBUG, OPEN_SOUND_DEBUG, OPEN_TUNING,
        OPEN_CONTROLLER, TOGGLE_CAMERA, TOGGLE_HEADLIGHTS, CAMERA_CLOSER, CAMERA_FARTHER, SHIFT_UP, SHIFT_DOWN
    }

    /** 画面を開いたままでも物理的な状態を読むキー（割り当ては尊重する）。 */
    public enum Held {
        UP, DOWN, LEFT, RIGHT, JUMP, SHIFT_UP, SHIFT_DOWN
    }

    /** プレイヤーの移動入力（バニラの {@code Input}）。サイドブレーキはジャンプを借りる。 */
    public record Movement(boolean up, boolean down, boolean left, boolean right, boolean jump) {
    }

    /** 運転する車。版の {@code CarEntity} が実装する（名前は {@code CarEntity} のメソッドのまま。Minecraft と重ならない）。 */
    public interface Car {
        int carEntityId();

        CarId getCarId();

        CarSpec getSpec();

        void setSpec(CarSpec spec);

        boolean isHeadlightOnSynced();

        void setHeadlights(boolean on);

        void setDriverInput(CarInput input);

        float getRenderSpeed();
    }

    /** 版が読むもの。 */
    public interface Host {
        /** そのキーの溜まったクリックを全部吸い出し、その回数を返す。 */
        int clicks(Press key);

        /** 割り当てられたキーがいま物理的に押されているか（画面を開いていても読める）。 */
        boolean held(Held key);

        /** プレイヤーの移動入力。プレイヤーがいなければ null。 */
        @Nullable
        Movement movement();

        /** 乗っている車（運転席でも助手席でも）。乗っていなければ null。 */
        @Nullable
        Car riding();

        /** 自分がその車を運転しているか。 */
        boolean drives(Car car);

        void cycleCamera();

        void stepCameraDistance(int steps);
    }

    private static Host host;

    /** 直前まで運転していた車。降りたときに入力を戻すために覚えておく。 */
    @Nullable
    private static Car driving;
    /** 運転中の車へ反映済みの調整値の版。-1 は未反映。 */
    private static int appliedTuningRevision = -1;
    /**
     * 運転している車のヘッドライトを点けているか。
     *
     * <p><b>状態そのものは車が持つ</b>（{@link Car#isHeadlightOnSynced}）。乗り込んだときに
     * 車の状態を引き継ぎ、L キーで切り替えたぶんを毎ティック車へ渡す。点けたまま降りた車は
     * 点いたまま残り、別の車へ乗り換えるとその車の状態になる。</p>
     */
    private static boolean headlightsOn;

    private CarDriver() {
    }

    /** クライアントの起動時に版が置く。 */
    public static void install(Host value) {
        host = value;
    }

    private static RaceClient client() {
        return RaceClient.get();
    }

    public static void tick() {
        Host h = host;
        RaceClient client = client();
        if (h == null || client == null) {
            return;
        }

        openTuningScreenIfRequested(h, client);
        if (h.clicks(Press.OPEN_CONTROLLER) > 0 && !client.screenOpen()) {
            client.setScreen(new ControllerScreen());
        }
        // 画面を開いていても読む。割り当て画面が今の入力を出せるように
        ControllerInput.poll();
        for (int i = h.clicks(Press.OPEN_MENU); i > 0; i--) {
            client.setScreen(new KurumaMenuScreen(null));
        }
        // レース。車に乗っていなくても開ける（歩いて集まってから参加・主催することもある）
        for (int i = h.clicks(Press.OPEN_RACE); i > 0; i--) {
            RaceLobby.onKey();
        }
        for (int i = h.clicks(Press.OPEN_HEADLIGHT_SETTINGS); i > 0; i--) {
            if (!client.screenOpen()) {
                client.setScreen(new HeadlightScreen());
            }
        }
        for (int i = h.clicks(Press.OPEN_SEAT_DEBUG); i > 0; i--) {
            // 座席は車種ごとの値なので、乗っている車の車種を合わせる
            Car riding = h.riding();
            if (riding == null) {
                needCar(client);
            } else if (!client.screenOpen()) {
                client.setScreen(new SeatScreen(riding.getCarId()));
            }
        }
        for (int i = h.clicks(Press.OPEN_SOUND_DEBUG); i > 0; i--) {
            // 音は車種ごとの値なので、乗っている車の車種を合わせる
            if (h.riding() == null) {
                needCar(client);
            } else if (!client.screenOpen()) {
                client.setScreen(KurumaMenuScreen.soundDebug());
            }
        }
        for (int i = h.clicks(Press.TOGGLE_CAMERA); i > 0; i--) {
            h.cycleCamera();
        }
        for (int i = h.clicks(Press.TOGGLE_HEADLIGHTS); i > 0; i--) {
            // ライトは車の状態なので、運転していなければ切り替えない
            if (requireDriver(h, client)) {
                headlightsOn = !headlightsOn;
            }
        }
        for (int i = h.clicks(Press.CAMERA_CLOSER); i > 0; i--) {
            h.stepCameraDistance(-1);
        }
        for (int i = h.clicks(Press.CAMERA_FARTHER); i > 0; i--) {
            h.stepCameraDistance(1);
        }

        Movement movement = h.movement();
        Car car = null;
        if (movement != null && !client.isPaused()) {
            Car riding = h.riding();
            if (riding != null && h.drives(riding)) {
                car = riding;
            }
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
        boolean shiftUp = h.clicks(Press.SHIFT_UP) > 0;
        boolean shiftDown = h.clicks(Press.SHIFT_DOWN) > 0;

        // 灯火は操作ではなく状態なので、画面を開いていても消えないよう先に渡す
        car.setHeadlights(headlightsOn);

        // カウントダウン中は動かせない。スタート信号を意味のあるものにするため
        if (RaceHud.isFrozen()) {
            car.setDriverInput(DriverInput.frozen(car.getRenderSpeed() > 0.3F, movement.left(), movement.right()));
            return;
        }

        // 調整画面（G）などは開いたまま運転できる。挙動の変化を走りながら確かめる画面なので。
        // それ以外の画面は入力が画面に取られているので受け付けない
        if (client.screenOpen()) {
            if (isDrivableScreen(client.raceScreen())) {
                car.setDriverInput(inputThroughScreen(h));
            } else {
                car.setDriverInput(CarInput.NONE);
            }
            return;
        }
        // 画面越しの変速は押しっぱなしの状態で閉じたときに 1 回ぶん誤って拾わないよう、
        // 画面の外では「押されている」で揃えておく（離すまで次の押下にならない）
        DriverInput.trackShiftKeys(h.held(Held.SHIFT_UP), h.held(Held.SHIFT_DOWN));

        // サイドブレーキはスペース。独自のキーバインドを作るとバニラのジャンプと
        // 衝突するので、ジャンプの押下状態をそのまま借りる
        // コントローラを触っている間はそちらを使う（DriverInput#choose）
        car.setDriverInput(DriverInput.choose(new CarInput(movement.up(), movement.down(), movement.left(),
                movement.right(), shiftUp, shiftDown, movement.jump())));
    }

    /**
     * 画面を開いたままの運転入力。
     *
     * <p><b>{@code KeyMapping#isDown} は使えない。</b>画面を開くとバニラが {@code KeyMapping.releaseAll()}
     * を呼び、開いている間のキーは画面にしか届かないので、押していても false のまま。
     * そこで版が割り当てられているキーの物理的な状態を直接読む（{@link Host#held}）。変速は押した瞬間だけを拾う。</p>
     */
    private static CarInput inputThroughScreen(Host h) {
        return DriverInput.throughScreen(h.held(Held.UP), h.held(Held.DOWN), h.held(Held.LEFT), h.held(Held.RIGHT),
                h.held(Held.SHIFT_UP), h.held(Held.SHIFT_DOWN), h.held(Held.JUMP));
    }

    /**
     * 開いたまま運転できる画面か。走りながら見て決める画面（セッティング・パーツ・プリセット・メニュー・座席）。
     *
     * <p><b>入力欄のある画面を足してはいけない。</b>運転に使うキーを画面が握りつぶすので、
     * W・A・S・D とスペースが打てなくなる。入力欄のある画面は、打っている間だけ外す（換装のナンバー・プリセットの名前）。</p>
     *
     * @param screen 開いている core の画面。バニラの画面なら null
     */
    public static boolean isDrivableScreen(@Nullable UiScreen screen) {
        // ナンバーの字を打っている間は運転しない。W・A・S・D を打つたびに車が動いてしまう
        if (screen instanceof CarPartScreen parts && parts.isTyping()) {
            return false;
        }
        // プリセットも G と同じく走りながら当てる。名前を打っている間だけは運転しない
        if (screen instanceof CarPresetScreen preset) {
            return !preset.isTyping();
        }
        return screen instanceof CarTuningScreen || screen instanceof CarPartScreen
                || screen instanceof KurumaMenuScreen || screen instanceof SeatScreen;
    }

    /** いま運転している車。運転していなければ null。 */
    @Nullable
    public static Car driving() {
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
        Car car = driving;
        if (car != null) {
            client().sendToServer(new CarPartRequestPacket(car.carEntityId(), slot, setting));
        }
    }

    /**
     * 塗装を<b>要求</b>する。運転していなければ何もしない。部品と同じく手元では反映せず、{@code CarPartsPacket} が戻ってきて変わる。
     *
     * @param paint 塗装の id。null で既定の色へ戻す
     */
    public static void requestPaint(@Nullable String paint) {
        Car car = driving;
        if (car != null) {
            client().sendToServer(new CarPaintRequestPacket(car.carEntityId(), paint));
        }
    }

    /**
     * ナンバーの中身（打った字と付ける位置）を<b>要求</b>する。運転していなければ何もしない。
     * サーバーが様式の決まりとフィルタに通してから {@code CarPartsPacket} で配り直す。
     */
    public static void requestPlate(PlateLook plate) {
        Car car = driving;
        if (car != null) {
            client().sendToServer(new CarPlateRequestPacket(car.carEntityId(), plate));
        }
    }

    /** 調整画面の値をサーバー側の車へ送る。運転していなければ何もしない。 */
    public static void sendTuningToServer() {
        Car car = driving;
        if (car != null) {
            client().sendToServer(CarTuningPacket.of(car.carEntityId(), CarTuning.spec()));
        }
    }

    /**
     * 助手席から見ている車の値を調整画面の入れ物へ取り込む（見るだけ。送り返さない）。
     * 運転している人がセッティングを変えると車の諸元が届くので、画面は毎フレームこれを呼んで追う。
     */
    public static void viewPassengerCar(Car car) {
        if (CarTuning.spec() != car.getSpec()) {
            CarTuning.adopt(car.getSpec(), CarTypes.get(car.getCarId()).spec());
        }
    }

    /** 自分が運転しているか（セッティングを変えてよいか）。 */
    public static boolean isDriver() {
        Host h = host;
        Car car = h == null ? null : h.riding();
        return car != null && h.drives(car);
    }

    private static void openTuningScreenIfRequested(Host h, RaceClient client) {
        // セッティングは乗っている車の諸元を変える画面なので、乗っていなければ開かない。
        // 助手席なら開けるが見るだけ（人の車なので触れない。CarTuningScreen#editable）
        if (h.clicks(Press.OPEN_TUNING) == 0 || client.screenOpen()) {
            return;
        }
        Car car = h.riding();
        if (car == null) {
            needCar(client);
            return;
        }
        if (!h.drives(car)) {
            viewPassengerCar(car);
        }
        client.setScreen(new CarTuningScreen());
    }

    /**
     * 運転しているか。していなければアクションバーで知らせて false を返す。
     *
     * <p>L（ライト）は乗っている車の状態を変えるキーなので、降りているときに押しても何も起きない。
     * 黙って無視すると壊れているように見えるので理由を出す。
     * <b>助手席では何もしない（知らせもしない）。</b>人の車なので、触れないのが当たり前。</p>
     */
    private static boolean requireDriver(Host h, RaceClient client) {
        if (h.movement() == null) {
            return false;
        }
        Car car = h.riding();
        if (car != null) {
            return h.drives(car);
        }
        needCar(client);
        return false;
    }

    private static void needCar(RaceClient client) {
        if (client.hasPlayer()) {
            client.overlayMessage(RaceText.translatable("message.kurumamod.need_car"));
        }
    }
}
