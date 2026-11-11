package com.jdmmc.kurumamod.race;

import java.util.List;
import java.util.UUID;

/**
 * {@link RaceEngine} から見たサーバー。版ごとの {@code McRaceServer} が {@code MinecraftServer} を包む。
 *
 * <p>メソッド名は包む相手（{@code MinecraftServer}）に揃えてある。{@code RaceEngine} は {@code RaceManager} を
 * そのまま移したものなので、名前が同じなら読み比べやすい。</p>
 */
public interface RaceServer {

    /** サーバーのティック数。 */
    int getTickCount();

    /** 読み込まれている次元すべて。 */
    List<? extends RaceLevel> levels();

    /** その次元。無ければ null。同じ次元かどうかは {@link RaceLevel#dimension()} で比べる。 */
    RaceLevel level(String dimension);

    /** その人がいま入っていれば、その人。いなければ null。 */
    RacePlayer player(UUID id);

    /** 入っている全員。 */
    List<? extends RacePlayer> players();

    /** 全員のチャットに出す（どの次元にいても）。 */
    void broadcastSystemMessage(RaceText message);

    /** このサーバー（ワールド）のクラス。 */
    RaceClassBook classes();

    /** シングルプレイ（LAN を含む）の持ち主か。 */
    boolean isSingleplayerOwner(RacePlayer player);

    /** プロフィールキャッシュで引いた名前。分からなければ {@code "?"}。古い記録の表示に使う。 */
    String cachedName(UUID id);
}
