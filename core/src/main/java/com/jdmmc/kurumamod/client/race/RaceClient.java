package com.jdmmc.kurumamod.client.race;

import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.network.RacePayload;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.race.RaceCar;
import com.jdmmc.kurumamod.race.RaceText;

import java.nio.file.Path;

/**
 * レースのクライアント側（{@link RaceLobby}・{@link RaceHud}・K の画面）が Minecraft に触る口。
 * 版ごとの {@code McRaceClient} が中身を持ち、クライアントの起動時に {@link #set} で置く。
 *
 * <p>サーバー側の {@code RaceServer} と同じ考え方: 版で違うもの（名前の取り方・画面の開き方・キー・音）は
 * すべてここの向こうにある。</p>
 */
public interface RaceClient {

    /** レースの合図の音と、一覧を選んだときのクリックの音。 */
    enum Sound {
        RACE_LIGHT, RACE_START, RACE_FINISH, RACE_SPLIT, UI_CLICK
    }

    static RaceClient get() {
        return Holder.client;
    }

    static void set(RaceClient client) {
        Holder.client = client;
    }

    final class Holder {
        private static RaceClient client;

        private Holder() {
        }
    }

    UiFont font();

    /** 画面の大きさ [GUI の座標]。 */
    int guiWidth();

    int guiHeight();

    double guiScale();

    /** ゲームが止まっているか（シングルプレイのポーズ）。 */
    boolean isPaused();

    /** F1 で HUD を消しているか。 */
    boolean hideGui();

    boolean hasPlayer();

    /** ワールドの中か（画面の背景を半透明にするか）。 */
    boolean inWorld();

    /** 自分の名前（プロフィールの名前）。いなければ空。 */
    String playerName();

    /** 何かの画面を開いているか（チャット・インベントリも）。 */
    boolean screenOpen();

    /** いま開いているレースの画面。ほかの画面か、開いていなければ null。 */
    UiScreen raceScreen();

    /** ポーズ画面を開いているか。 */
    boolean pauseScreenOpen();

    /** 画面を開く（null なら閉じる）。 */
    void setScreen(UiScreen screen);

    void sendToServer(RacePayload packet);

    /** アクションバーに出す。 */
    void overlayMessage(RaceText text);

    /** レースの画面を開くキー（K）の名前。 */
    RaceText openRaceKey();

    /** 運転している車。運転していなければ null。 */
    RaceCar ridingCar();

    /** 車種の名前（{@code carId} は {@code kurumamod:s15} の形）。 */
    RaceText carTypeName(String carId);

    /** セッティングの基準（G の画面の「初期値」）。 */
    CarSpec tuningBase();

    /** いる次元の名前（{@code minecraft:overworld} の形）。いなければ空。 */
    String currentDimension();

    /** いまのサーバー（ワールド）を見分ける名前。ゴーストとクラスの宣言をワールドごとに覚えるため。 */
    String worldKey();

    /** {@code config/} の場所。 */
    Path configDir();

    void playSound(Sound sound, float pitch, float volume);
}
