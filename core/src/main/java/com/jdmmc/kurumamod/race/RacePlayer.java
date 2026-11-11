package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.network.RacePayload;

import java.util.UUID;

/**
 * {@link RaceEngine} から見たプレイヤー。版ごとの {@code McRaceServer} が {@code ServerPlayer} を包む。
 *
 * <p><b>同じ人を表す包みが 2 つあっても、同じ人かどうかは {@link #getUUID()} で比べること</b>
 * （包みは呼ぶたびに作られうる）。</p>
 */
public interface RacePlayer {

    UUID getUUID();

    /** プロフィールの名前。 */
    String name();

    /** チームの色などが付いた表示名（版の {@code Component} を中に持つ）。 */
    RaceText getDisplayName();

    /** いまいる次元。{@link RaceServer#level} と同じ実体。 */
    RaceLevel level();

    RaceServer getServer();

    /** OP のコマンドが打てるか（バニラの権限の段 2。26.3 は {@code Permissions.COMMANDS_GAMEMASTER}）。 */
    boolean hasGamemasterPermission();

    /** 自分で運転している車。乗っていない・助手席なら null。 */
    RaceCar drivenCar();

    /** チャットに出す。 */
    void sendSystemMessage(RaceText message);

    /** アクションバーに出す（画面を開いたままでも読める）。 */
    void sendOverlay(RaceText message);

    /** パケットを送る。 */
    void send(RacePayload payload);
}
