package com.jdmmc.kurumamod.race;

import java.util.List;

/** {@link RaceEngine} から見た 1 つの次元。版ごとの {@code McRaceServer} が {@code ServerLevel} を包む。 */
public interface RaceLevel {

    /** {@code minecraft:overworld} のような次元の名前。ゴーストのフォルダ名にもなる。 */
    String dimension();

    RaceServer getServer();

    /** この次元にいる人。 */
    List<? extends RacePlayer> players();

    /** この次元のコースと記録。 */
    RaceRecords records();

    /** この次元の乱数（信号の保持時間）。 */
    int nextInt(int bound);
}
