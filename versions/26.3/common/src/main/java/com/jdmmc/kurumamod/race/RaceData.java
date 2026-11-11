package com.jdmmc.kurumamod.race;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * レースの記録（{@link RaceRecords}）をワールドと一緒に保存する殻。
 *
 * <p>中身は core の {@link RaceRecords} が持ち、読み書きも向こうが {@link RaceTag} を通して行う。
 * ここは「次元ごとの SavedData に載せる」ことと、1.21.1 までの場所からの読み直しだけ。</p>
 */
public final class RaceData extends SavedData {

    private static final String FILE = "kurumamod_race";

    /**
     * 26.3 から保存先は名前空間付きの id で決まる（{@code dimensions/<次元>/data/kurumamod/race.dat}）。
     * 1.21.1 までは {@code <次元のフォルダ>/data/kurumamod_race.dat} だったので、新しい方が無いときだけ
     * 古いファイルを探して読み直す（{@link #loadLegacy}）。中身の形は変えていない。
     */
    private static final SavedDataType<RaceData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("kurumamod", "race"), RaceData::create,
            CompoundTag.CODEC.xmap(RaceData::load, RaceData::save), null);

    private static final Logger LOGGER = LogUtils.getLogger();

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
        // コースは次元ごとに持つ。オーバーワールドの筑波とネザーのコースが混ざらない
        SavedDataStorage storage = level.getDataStorage();
        RaceData data = storage.get(TYPE);
        if (data == null) {
            data = loadLegacy(level);
            if (data == null) {
                return storage.computeIfAbsent(TYPE).records;
            }
            // 新しい場所へ書き出させる。古いファイルは残しておく（消すと戻せない）
            storage.set(TYPE, data);
        }
        return data.records;
    }

    /**
     * 1.21.1 までの場所にある記録を読む。無ければ null。
     *
     * <p>古いワールドの次元のフォルダ（オーバーワールドは {@code data/}、ネザーは {@code DIM-1/data/}、
     * エンドは {@code DIM1/data/}、そのほかは {@code dimensions/<ns>/<path>/data/}）と、
     * 26.3 がワールドを変換したあとに残りうる場所を順に見る。</p>
     */
    @Nullable
    private static RaceData loadLegacy(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimension = DimensionType.getStorageFolder(level.dimension(), root);
        List<Path> candidates = new ArrayList<>();
        candidates.add(dimension.resolve("data").resolve(FILE + ".dat"));
        candidates.add(dimension.resolve("data").resolve("minecraft").resolve(FILE + ".dat"));
        if (level.dimension() == Level.OVERWORLD) {
            candidates.add(root.resolve("data").resolve(FILE + ".dat"));
        } else if (level.dimension() == Level.NETHER) {
            candidates.add(root.resolve("DIM-1").resolve("data").resolve(FILE + ".dat"));
        } else if (level.dimension() == Level.END) {
            candidates.add(root.resolve("DIM1").resolve("data").resolve(FILE + ".dat"));
        } else {
            Identifier id = level.dimension().identifier();
            candidates.add(root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath())
                    .resolve("data").resolve(FILE + ".dat"));
        }
        for (Path file : candidates) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                RaceData data = load(tag.getCompoundOrEmpty("data"));
                LOGGER.info("1.21.1 までの場所からレースの記録を読みました: {}", file);
                return data;
            } catch (Exception e) {
                LOGGER.error("古いレースの記録を読めませんでした: {}", file, e);
            }
        }
        return null;
    }

    private CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        records.save(new NbtRaceTag(tag));
        return tag;
    }

    private static RaceData load(CompoundTag tag) {
        return new RaceData(RaceRecords.load(new NbtRaceTag(tag)));
    }
}
