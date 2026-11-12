package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.plate.PlateLook;

import java.util.List;

/**
 * 車まわりの画面（ライト・換装・座席・セッティング・H）が、版にしか無いもの（描画の状態・運転・カメラ）に触る口。
 * 版ごとの {@code McCarClient} が中身を持ち、クライアントの起動時に {@link #set} で置く。
 *
 * <p>レースの {@link RaceClient} と同じ考え方。画面が版の何かに触るようになったら、ここに口を足して 3 つの版に実装する。</p>
 */
public interface CarClient {

    /** 画素ごとの光を描く車の台数の設定で、これ以上は無制限（{@code ClientConfig.UNLIMITED_HEADLIGHT_CARS}）。 */
    int UNLIMITED_HEADLIGHT_CARS = 11;

    /** シェーダーパックを使っているか（{@code HeadlightVolume.ShaderPacks.inUse}）。 */
    boolean shaderPackInUse();

    /** シェーダーパックのせいでヘッドライトの光を描いていないか（{@code HeadlightVolume.suppressedByShaderPack}）。 */
    boolean headlightSuppressedByShaderPack();

    /** 操作の一覧の 1 行（キーの名前と、説明の翻訳キー）。右下の操作ヘルプと同じもの（{@code CarHelp#lines}）。 */
    record HelpLine(String key, String descriptionKey) {
    }

    List<HelpLine> helpLines();

    /** 乗っている車（HUD が読む値）。乗っていなければ null。 */
    HudCar ridingHud();

    /** 乗っている車のブーストの掛かり具合 0..1（描画の途中の値）。乗っていなければ 0。 */
    double turboBoost();

    /** いまのカメラ。 */
    com.jdmmc.kurumamod.CameraMode cameraMode();

    /** カメラを切り替えるキーの表示名（割り当てを変えたら追従する）。 */
    String cameraKeyName();

    /** 乗っている車の車種。乗っていなければ null。 */
    CarId ridingCarId();

    /** 乗っている車の音の組（車種の差し替えにマフラーの音源を重ねたもの）。乗っていなければ null。 */
    CarSoundSet ridingCarSounds();

    /** 乗っている車にターボ・ブローオフバルブが付いているか（鳴らない音の行を灰色にする）。 */
    boolean ridingCarHasTurbo();

    boolean ridingCarHasBlowoff();

    void setClipboard(String text);

    /**
     * 画面を閉じて、開く前の画面（{@code parent}。版のバニラの画面。null ならゲームへ）に戻る。
     * MOD 一覧の「Config」から開いたときは MOD 一覧へ戻る。
     */
    void returnTo(Object parent);

    // ----- 運転している車（換装・セッティング・座席） -----

    /** 乗っている車（助手席も）。乗っていなければ null（{@code CarClientDriver.ridingCar}）。 */
    DrivenCar riding();

    /** 運転している車。運転席でなければ null（{@code CarClientDriver.driving}）。 */
    DrivenCar driving();

    /** 自分が運転しているか（セッティングを変えてよいか）。 */
    boolean isDriver();

    /** その場所の部品・調整値を付け替えるよう、サーバーへ頼む。 */
    void requestPart(PartSlot slot, PartFitment.Setting setting);

    /** 塗装を替えるよう頼む（null で既定の色）。 */
    void requestPaint(String paint);

    /** ナンバーの中身（字と位置）を替えるよう頼む。 */
    void requestPlate(PlateLook plate);

    /** いまのセッティング（{@code CarTuning}）をサーバーへ送る。 */
    void sendTuningToServer();

    /** 助手席から見ている車の諸元を、セッティング画面に写す（{@code CarClientDriver.viewPassengerCar}）。 */
    void viewPassengerCar(DrivenCar car);

    /** 車をいじる画面のカメラを回す・寄せる（{@code CarChaseCamera}）。 */
    void orbitCamera(double dragX, double dragY);

    void zoomCamera(double delta);

    // ----- 換装・座席の画面 -----

    /** 換装画面の照準を置く画面上の点 {x, y}（{@code PartAnchors}）。まだ測っていなければ null。 */
    float[] partAnchor(PartSlot slot);

    float[] paintAnchor();

    float[] centerAnchor();

    /** ホイールのビード（タイヤがはまる縁）の半径（{@code CarObjRenderer.beadRadius}）。 */
    double beadRadius(CarPartModel.Wheel look);

    /** Shift を押しているか（刻みを 10 倍にする）。 */
    boolean shiftDown();

    /** 一人称か。 */
    boolean firstPerson();

    /** プレイヤーの向きを変える（一人称で座席を合わせるとき。{@code Entity#turn} の値）。 */
    void turnPlayer(double yaw, double pitch);

    /** 目の高さ − 乗っている乗り物の高さ [ブロック]。乗っていなければ NaN。 */
    double eyeAboveVehicle();

    static CarClient get() {
        return Holder.client;
    }

    static void set(CarClient client) {
        Holder.client = client;
    }

    final class Holder {
        private static CarClient client;

        private Holder() {
        }
    }
}
