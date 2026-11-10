package com.jdmmc.kurumamod.race;

import com.mojang.logging.LogUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * コースの定義とベストタイムの保管庫。
 *
 * <p>バニラの {@link SavedData} に載せているので、ワールドと一緒に保存され、
 * サーバーを立て直しても残る。手で NBT を書く必要はない。</p>
 *
 * <p>ベストは<b>タイムだけでなく内訳も持つ</b>。走行中に「自己ベストに対して今どれだけ
 * 速いか」を出すには、ベスト走行が各チェックポイントを何秒で通ったかが要る。あわせて
 * 区間ごとのベスト（別々の周に出したものでよい）も持つので、その合計が理論ベストになる。</p>
 *
 * <p>ベストは<b>「プレイヤー × コース × クラス」ごとに 1 本</b>。クラスの名前が空（{@link #OVERALL}）のものが
 * <b>総合</b>で、宣言の有無に関係なく全部の周の最速。クラスを宣言して走った周は、総合とそのクラスの両方に入る。</p>
 */
public class RaceData extends SavedData {

    private static final String FILE = "kurumamod_race";

    /**
     * 26.3 から保存先は名前空間付きの id で決まる（{@code dimensions/<次元>/data/kurumamod/race.dat}）。
     * 1.21.1 までは {@code <次元のフォルダ>/data/kurumamod_race.dat} だったので、新しい方が無いときだけ
     * 古いファイルを探して読み直す（{@link #loadLegacy}）。中身の形は変えていない。
     */
    private static final SavedDataType<RaceData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("kurumamod", "race"), RaceData::new,
            CompoundTag.CODEC.xmap(RaceData::load, RaceData::save), null);

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long[] NONE = new long[0];

    /** 総合の記録のクラス名。宣言していない周はここにだけ入る。 */
    public static final String OVERALL = "";

    // 名前順。線が重なったときにどちらが選ばれるかを再現可能にしておく
    private final Map<String, Course> courses = new java.util.TreeMap<>();
    /** プレイヤーごと・コースごと・クラスごとのベスト。 */
    private final Map<UUID, Map<String, Map<String, Best>>> bestLaps = new HashMap<>();

    public static RaceData get(ServerLevel level) {
        // コースは次元ごとに持つ。オーバーワールドの筑波とネザーのコースが混ざらない
        SavedDataStorage storage = level.getDataStorage();
        RaceData data = storage.get(TYPE);
        if (data == null) {
            data = loadLegacy(level);
            if (data == null) {
                return storage.computeIfAbsent(TYPE);
            }
            // 新しい場所へ書き出させる。古いファイルは残しておく（消すと戻せない）
            storage.set(TYPE, data);
        }
        return data;
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

    /**
     * 1 人ぶん・1 コース・1 クラスぶんのベスト。
     *
     * <p><b>名前を一緒に持つ</b>のは、ランキングをオフラインのプレイヤーぶんも出すため。
     * UUID からの逆引きはプロフィールキャッシュ次第で外れることがある。</p>
     */
    public static final class Best {
        private String name = "";
        private long millis;
        /** ベストラップでの各チェックポイント通過時刻。ラップ開始からの相対 [ms]。 */
        private long[] splits = NONE;
        /** 区間ごとのベスト [ms]。別々の周のものが混ざってよい。合計が理論ベスト。 */
        private long[] sectors = NONE;
    }

    public Optional<Course> course(String name) {
        return Optional.ofNullable(courses.get(name));
    }

    public Course createCourse(String name) {
        Course course = new Course(name);
        courses.put(name, course);
        setDirty();
        return course;
    }

    public boolean removeCourse(String name) {
        boolean removed = courses.remove(name) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public java.util.Collection<Course> courses() {
        return courses.values();
    }

    /** 1 周を取り込んだ結果。ベストを更新したか（総合・クラス）。 */
    public record LapRecord(boolean overall, boolean inClass) {
    }

    /**
     * 1 周ぶんの記録を取り込む。総合には必ず、{@code raceClass} が null でなければそのクラスにも入れる。
     *
     * @param raceClass 宣言して走ったクラスの名前。宣言していなければ null
     * @param splits    各チェックポイントの通過時刻。ラップ開始からの相対 [ms]
     */
    public LapRecord recordLap(UUID player, String playerName, String course, String raceClass,
                               long millis, long[] splits) {
        boolean overall = recordInto(player, playerName, course, OVERALL, millis, splits);
        boolean inClass = raceClass != null && !raceClass.isEmpty()
                && recordInto(player, playerName, course, raceClass, millis, splits);
        return new LapRecord(overall, inClass);
    }

    /**
     * 1 本ぶんへ取り込む。
     *
     * <p>ラップタイムが自己ベストを更新したときだけ true を返すが、<b>区間ベストは
     * 更新できていれば毎回取り込む</b>。理論ベストは「別々の周に出した最速区間の合計」なので、
     * ベストラップでなかった周にも更新の機会がある。</p>
     */
    private boolean recordInto(UUID player, String playerName, String course, String raceClass,
                               long millis, long[] splits) {
        Best best = bestLaps.computeIfAbsent(player, key -> new HashMap<>())
                .computeIfAbsent(course, key -> new HashMap<>())
                .computeIfAbsent(raceClass, key -> new Best());
        best.name = playerName;
        setDirty();

        long[] sectors = toSectors(millis, splits);
        if (best.sectors.length != sectors.length) {
            // チェックポイントの数が変わった。前の区間ベストは比べようがないので捨てる
            best.sectors = sectors;
        } else {
            for (int i = 0; i < sectors.length; i++) {
                if (best.sectors[i] <= 0L || sectors[i] < best.sectors[i]) {
                    best.sectors[i] = sectors[i];
                }
            }
        }

        if (best.millis > 0L && best.millis <= millis) {
            return false;
        }
        best.millis = millis;
        best.splits = splits.clone();
        return true;
    }

    /** 通過時刻の並びを区間タイムへ直す。区間はチェックポイントの数 + 1 本。 */
    private static long[] toSectors(long millis, long[] splits) {
        long[] sectors = new long[splits.length + 1];
        long previous = 0L;
        for (int i = 0; i < splits.length; i++) {
            sectors[i] = splits[i] - previous;
            previous = splits[i];
        }
        sectors[splits.length] = millis - previous;
        return sectors;
    }

    /**
     * そのコースの全員のベストを消す。消した人数。
     *
     * <p><b>コースを引き直したら過去のタイムは比較できなくなる。</b>そのときに使う。</p>
     */
    public int clearCourseBests(String course) {
        int removed = 0;
        for (Map<String, Map<String, Best>> byCourse : bestLaps.values()) {
            if (byCourse.remove(course) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    /** そのクラスの記録の件数（全員・全コース）。クラスを変える前の確認に出す。 */
    public int countClassBests(String raceClass) {
        int count = 0;
        for (Map<String, Map<String, Best>> byCourse : bestLaps.values()) {
            for (Map<String, Best> byClass : byCourse.values()) {
                Best best = byClass.get(raceClass);
                if (best != null && best.millis > 0L) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * そのクラスの記録を全員・全コースぶん消す。消した件数。
     *
     * <p><b>クラスの中身が変わったら、前の決まりで出したタイムとは比べられない。</b>上書きと削除のときに使う。
     * 総合の記録は消さない（決まりに関係なく出したタイムだから）。</p>
     */
    public int clearClassBests(String raceClass) {
        if (raceClass == null || raceClass.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Map<String, Map<String, Best>> byCourse : bestLaps.values()) {
            for (Map<String, Best> byClass : byCourse.values()) {
                Best best = byClass.remove(raceClass);
                if (best != null && best.millis > 0L) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    /** 総合のベストラップ [ms]。無ければ 0。 */
    public long bestLap(UUID player, String course) {
        return bestLap(player, course, OVERALL);
    }

    /** そのクラスのベストラップ [ms]。無ければ 0。{@code raceClass} が空なら総合。 */
    public long bestLap(UUID player, String course, String raceClass) {
        Best best = best(player, course, raceClass);
        return best == null ? 0L : best.millis;
    }

    /**
     * ベストラップでの各チェックポイント通過時刻 [ms]。無ければ null。
     *
     * <p>走行中のデルタ表示の比較相手。<b>チェックポイントの数が変わっていたら使えない</b>ので、
     * 呼ぶ側は長さを確かめること。</p>
     */
    public long[] bestSplits(UUID player, String course, String raceClass) {
        Best best = best(player, course, raceClass);
        return best == null || best.millis <= 0L ? null : best.splits;
    }

    private Best best(UUID player, String course, String raceClass) {
        Map<String, Map<String, Best>> byCourse = bestLaps.get(player);
        Map<String, Best> byClass = byCourse == null ? null : byCourse.get(course);
        return byClass == null ? null : byClass.get(raceClass == null ? OVERALL : raceClass);
    }

    /** そのコースに記録のあるクラスの名前（総合は含めない）。 */
    public java.util.Set<String> recordedClasses(String course) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Map<String, Map<String, Best>> byCourse : bestLaps.values()) {
            Map<String, Best> byClass = byCourse.get(course);
            if (byClass != null) {
                for (Map.Entry<String, Best> entry : byClass.entrySet()) {
                    if (!entry.getKey().isEmpty() && entry.getValue().millis > 0L) {
                        out.add(entry.getKey());
                    }
                }
            }
        }
        return out;
    }

    /** ランキングの 1 行。名前は記録した時点のもの。 */
    public record Entry(UUID player, String name, long millis) {
    }

    /**
     * そのコース・そのクラスの速い順（{@code limit} が 0 以下なら全員）。
     *
     * <p>オフラインの人も含めて出す。名前を一緒に保存しているので、プロフィールキャッシュに
     * 頼らずに表示できる（内訳を持つ前に取った古い記録だけ名前が空になる）。</p>
     */
    public List<Entry> leaderboard(String course, String raceClass, int limit) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Map<String, Best>>> row : bestLaps.entrySet()) {
            Map<String, Best> byClass = row.getValue().get(course);
            Best best = byClass == null ? null : byClass.get(raceClass == null ? OVERALL : raceClass);
            if (best != null && best.millis > 0L) {
                entries.add(new Entry(row.getKey(), best.name, best.millis));
            }
        }
        entries.sort(java.util.Comparator.comparingLong(Entry::millis));
        return limit > 0 && entries.size() > limit ? new ArrayList<>(entries.subList(0, limit)) : entries;
    }

    private CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag courseList = new ListTag();
        for (Course course : courses.values()) {
            courseList.add(course.save());
        }
        tag.put("Courses", courseList);

        ListTag bestList = new ListTag();
        for (Map.Entry<UUID, Map<String, Map<String, Best>>> entry : bestLaps.entrySet()) {
            for (Map.Entry<String, Map<String, Best>> byCourse : entry.getValue().entrySet()) {
                for (Map.Entry<String, Best> best : byCourse.getValue().entrySet()) {
                    CompoundTag row = new CompoundTag();
                    row.store("Player", UUIDUtil.CODEC, entry.getKey());
                    row.putString("Course", byCourse.getKey());
                    row.putString("Class", best.getKey());
                    row.putString("PlayerName", best.getValue().name);
                    row.putLong("Millis", best.getValue().millis);
                    row.putLongArray("Splits", best.getValue().splits);
                    row.putLongArray("Sectors", best.getValue().sectors);
                    bestList.add(row);
                }
            }
        }
        tag.put("Best", bestList);
        return tag;
    }

    private static RaceData load(CompoundTag tag) {
        RaceData data = new RaceData();
        ListTag courseList = tag.getListOrEmpty("Courses");
        for (int i = 0; i < courseList.size(); i++) {
            Course course = Course.load(courseList.getCompoundOrEmpty(i));
            data.courses.put(course.name(), course);
        }
        ListTag bestList = tag.getListOrEmpty("Best");
        for (int i = 0; i < bestList.size(); i++) {
            CompoundTag row = bestList.getCompoundOrEmpty(i);
            Optional<UUID> player = row.read("Player", UUIDUtil.CODEC);
            if (player.isEmpty()) {
                continue;
            }
            // 内訳を持つ前に保存された記録も読める。タイムだけあって内訳が無い状態になり、
            // 次にそのコースを 1 周すれば内訳が入る。クラスを持つ前の記録（"Class" が無い）は総合になる
            Best best = new Best();
            best.name = row.getStringOr("PlayerName", "");
            best.millis = row.getLongOr("Millis", 0L);
            best.splits = row.getLongArray("Splits").orElse(new long[0]);
            best.sectors = row.getLongArray("Sectors").orElse(new long[0]);
            data.bestLaps.computeIfAbsent(player.get(), key -> new HashMap<>())
                    .computeIfAbsent(row.getStringOr("Course", ""), key -> new HashMap<>())
                    .put(row.getStringOr("Class", ""), best);
        }
        return data;
    }
}
