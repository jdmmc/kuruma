package com.jdmmc.kurumamod.race;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

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
 */
public class RaceData extends SavedData {

    private static final String FILE = "kurumamod_race";

    private static final long[] NONE = new long[0];

    // 名前順。線が重なったときにどちらが選ばれるかを再現可能にしておく
    private final Map<String, Course> courses = new java.util.TreeMap<>();
    /** プレイヤーごと・コースごとのベスト。 */
    private final Map<UUID, Map<String, Best>> bestLaps = new HashMap<>();

    public static RaceData get(ServerLevel level) {
        // コースは次元ごとに持つ。オーバーワールドの筑波とネザーのコースが混ざらない
        return level.getDataStorage().computeIfAbsent(RaceData::load, RaceData::new, FILE);
    }

    /**
     * 1 人ぶん・1 コースぶんのベスト。
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

    /**
     * 1 周ぶんの記録を取り込む。
     *
     * <p>ラップタイムが自己ベストを更新したときだけ true を返すが、<b>区間ベストは
     * 更新できていれば毎回取り込む</b>。理論ベストは「別々の周に出した最速区間の合計」なので、
     * ベストラップでなかった周にも更新の機会がある。</p>
     *
     * @param splits 各チェックポイントの通過時刻。ラップ開始からの相対 [ms]
     */
    public boolean recordLap(UUID player, String playerName, String course, long millis, long[] splits) {
        Best best = bestLaps.computeIfAbsent(player, key -> new HashMap<>())
                .computeIfAbsent(course, key -> new Best());
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

    /** そのプレイヤーのそのコースのベストを消す。消したら true。 */
    public boolean clearBest(UUID player, String course) {
        Map<String, Best> byCourse = bestLaps.get(player);
        if (byCourse == null || byCourse.remove(course) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** そのプレイヤーのベストを全部消す。消した件数。 */
    public int clearBests(UUID player) {
        Map<String, Best> byCourse = bestLaps.remove(player);
        if (byCourse == null) {
            return 0;
        }
        setDirty();
        return byCourse.size();
    }

    /**
     * そのコースの全員のベストを消す。消した件数。
     *
     * <p><b>コースを引き直したら過去のタイムは比較できなくなる。</b>そのときに使う。</p>
     */
    public int clearCourseBests(String course) {
        int removed = 0;
        for (Map<String, Best> byCourse : bestLaps.values()) {
            if (byCourse.remove(course) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    /** そのプレイヤーが記録を持っているコース名。 */
    public java.util.Set<String> recordedCourses(UUID player) {
        Map<String, Best> byCourse = bestLaps.get(player);
        return byCourse == null ? java.util.Set.of() : new java.util.HashSet<>(byCourse.keySet());
    }

    /** ベストラップ [ms]。無ければ 0。 */
    public long bestLap(UUID player, String course) {
        Best best = best(player, course);
        return best == null ? 0L : best.millis;
    }

    /**
     * ベストラップでの各チェックポイント通過時刻 [ms]。無ければ null。
     *
     * <p>走行中のデルタ表示の比較相手。<b>チェックポイントの数が変わっていたら使えない</b>ので、
     * 呼ぶ側は長さを確かめること。</p>
     */
    public long[] bestSplits(UUID player, String course) {
        Best best = best(player, course);
        return best == null || best.millis <= 0L ? null : best.splits;
    }

    /**
     * 理論ベスト [ms]。区間ベストの合計。揃っていなければ 0。
     *
     * <p>ベストラップと同じ値になることもある（全区間を同じ周に出した場合）。</p>
     */
    public long theoreticalBest(UUID player, String course) {
        Best best = best(player, course);
        if (best == null || best.sectors.length == 0) {
            return 0L;
        }
        long total = 0L;
        for (long sector : best.sectors) {
            if (sector <= 0L) {
                return 0L;
            }
            total += sector;
        }
        return total;
    }

    private Best best(UUID player, String course) {
        Map<String, Best> byCourse = bestLaps.get(player);
        return byCourse == null ? null : byCourse.get(course);
    }

    /** ランキングの 1 行。名前は記録した時点のもの。 */
    public record Entry(UUID player, String name, long millis) {
    }

    /**
     * そのコースの速い順。
     *
     * <p>オフラインの人も含めて出す。名前を一緒に保存しているので、プロフィールキャッシュに
     * 頼らずに表示できる（内訳を持つ前に取った古い記録だけ名前が空になる）。</p>
     */
    public List<Entry> leaderboard(String course, int limit) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Best>> row : bestLaps.entrySet()) {
            Best best = row.getValue().get(course);
            if (best != null && best.millis > 0L) {
                entries.add(new Entry(row.getKey(), best.name, best.millis));
            }
        }
        entries.sort(java.util.Comparator.comparingLong(Entry::millis));
        return entries.size() > limit ? new ArrayList<>(entries.subList(0, limit)) : entries;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag courseList = new ListTag();
        for (Course course : courses.values()) {
            courseList.add(course.save());
        }
        tag.put("Courses", courseList);

        ListTag bestList = new ListTag();
        for (Map.Entry<UUID, Map<String, Best>> entry : bestLaps.entrySet()) {
            for (Map.Entry<String, Best> best : entry.getValue().entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putUUID("Player", entry.getKey());
                row.putString("Course", best.getKey());
                row.putString("PlayerName", best.getValue().name);
                row.putLong("Millis", best.getValue().millis);
                row.putLongArray("Splits", best.getValue().splits);
                row.putLongArray("Sectors", best.getValue().sectors);
                bestList.add(row);
            }
        }
        tag.put("Best", bestList);
        return tag;
    }

    public static RaceData load(CompoundTag tag) {
        RaceData data = new RaceData();
        ListTag courseList = tag.getList("Courses", Tag.TAG_COMPOUND);
        for (int i = 0; i < courseList.size(); i++) {
            Course course = Course.load(courseList.getCompound(i));
            data.courses.put(course.name(), course);
        }
        ListTag bestList = tag.getList("Best", Tag.TAG_COMPOUND);
        for (int i = 0; i < bestList.size(); i++) {
            CompoundTag row = bestList.getCompound(i);
            // 内訳を持つ前に保存された記録も読める。タイムだけあって内訳が無い状態になり、
            // 次にそのコースを 1 周すれば内訳が入る
            Best best = new Best();
            best.name = row.getString("PlayerName");
            best.millis = row.getLong("Millis");
            best.splits = row.getLongArray("Splits");
            best.sectors = row.getLongArray("Sectors");
            data.bestLaps.computeIfAbsent(row.getUUID("Player"), key -> new HashMap<>())
                    .put(row.getString("Course"), best);
        }
        return data;
    }
}
