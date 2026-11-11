package com.jdmmc.kurumamod.race;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * レースの記録（{@link RaceRecords}）をワールドと一緒に保存する殻。
 *
 * <p>中身は core の {@link RaceRecords} が持ち、読み書きも向こうが {@link RaceTag} を通して行う。
 * ここは「次元ごとの SavedData に載せる」ことだけ。コースは次元ごとに持つので、オーバーワールドの
 * 筑波とネザーのコースが混ざらない。</p>
 */
public final class RaceData extends SavedData {

    private static final String FILE = "kurumamod_race";

    private final RaceRecords records;

    private RaceData(RaceRecords records) {
        this.records = records;
        records.onChange(this::setDirty);
    }

    private static RaceData create() {
        return new RaceData(new RaceRecords());
    }

    /** その次元の記録。 */
    public static RaceRecords get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                RaceData::load, RaceData::create, FILE).records;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        records.save(new NbtRaceTag(tag));
        return tag;
    }

    private static RaceData load(CompoundTag tag) {
        return new RaceData(RaceRecords.load(new NbtRaceTag(tag)));
    }
}
