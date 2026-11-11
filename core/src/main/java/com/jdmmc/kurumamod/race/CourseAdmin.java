package com.jdmmc.kurumamod.race;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * コースを作るコマンド（{@code /kuruma-admin point}・{@code course ...}）の中身。<b>Minecraft に触らない。</b>
 * コマンドの木（Brigadier）と引数の取り出しは版ごとの {@code RaceCommands}。
 *
 * <p>線は<b>端 2 点</b>で決める。WorldEdit のように、先に 2 点を打ってから
 * 「それがスタートラインだ」「チェックポイントだ」と宣言する形。</p>
 *
 * <p><b>2 点だけでは通過方向が決まらない</b>（線はどちらからでも跨げる）ので、
 * 宣言した瞬間の視線を「走る向き」として取る。あとから
 * {@code /kuruma-admin course direction} で引き直せる。</p>
 */
public final class CourseAdmin {

    /** 線の判定に使う高さの幅 [ブロック]。打った点の高さから上へこれだけ。 */
    private static final double HEIGHT = 6.0;

    /** ほかのコースのスタートラインから、少なくともこれだけ離す [ブロック]。 */
    private static final double START_LINE_SPACING = 5.0;

    /** コマンドを打った人と場所。版の {@code CommandSourceStack} を包む。 */
    public interface Source {
        RaceLevel level();

        RaceServer server();

        /** 成功の知らせ。{@code broadcast} なら OP 全員にも出す（バニラの {@code sendSuccess}）。 */
        void success(RaceText message, boolean broadcast);

        void failure(RaceText message);
    }

    /**
     * 打った人の立ち位置。
     *
     * @param facing 東西南北（{@code north} など。設定した向きが合っているか目で確かめられるように）
     */
    public record Stand(UUID id, double x, double y, double z, float yRot, String facing) {
    }

    private final RaceEngine engine;
    /** プレイヤーごとに打ちかけの 2 点を覚えておく。サーバーを立て直せば消える。 */
    private final Map<UUID, double[]> pending = new HashMap<>();

    public CourseAdmin(RaceEngine engine) {
        this.engine = engine;
    }

    /**
     * 打ちかけの 2 点はサーバーと一緒に捨てる。
     *
     * <p>プロセスが生きている限り残るので、シングルプレイでワールドを移っても残ってしまう。
     * 座標は前のワールドのものなので、そのまま線にすると<b>まったく違う場所に引かれる</b>。</p>
     */
    public void onServerStopped() {
        pending.clear();
    }

    /** コース名の候補（タブ補完）。 */
    public List<String> courseNames(RaceLevel level) {
        return level.records().courses().stream().map(Course::name).toList();
    }

    /**
     * 「そのコースが無い」と「そもそもコースが 1 つも無い」を区別して返す。
     *
     * <p>名前を打ち間違えたのか、まだ何も作っていないのかで<b>次にやることが違う</b>。
     * 前者は補完を使えばよく、後者はコースの作り方を知る必要がある。</p>
     */
    private int failNoCourse(Source source, String name) {
        source.failure(source.level().records().courses().isEmpty()
                ? RaceText.translatable("race.kurumamod.no_courses_hint")
                : RaceText.translatable("race.kurumamod.no_course", name));
        return 0;
    }

    // ------------------------------------------------------------------
    // 2 点を打つ
    // ------------------------------------------------------------------

    public int markPoint(Source source, Stand stand) {
        double[] points = pending.get(stand.id());
        if (points == null || points.length == 6) {
            // 1 点目。前の組は捨てる
            pending.put(stand.id(), new double[]{stand.x(), stand.z(), stand.y()});
            source.success(RaceText.translatable("race.kurumamod.point_a",
                    String.format("%.1f", stand.x()), String.format("%.1f", stand.z())), false);
            return 1;
        }
        // 2 点目
        double[] pair = {points[0], points[1], points[2], stand.x(), stand.z(), stand.y()};
        pending.put(stand.id(), pair);
        double length = Math.hypot(pair[3] - pair[0], pair[4] - pair[1]);
        source.success(RaceText.translatable("race.kurumamod.point_b",
                String.format("%.1f", stand.x()), String.format("%.1f", stand.z()),
                String.format("%.1f", length)), false);
        return 1;
    }

    /** 打ちかけの 2 点から線を作る。揃っていなければ null。 */
    private CourseLine takeLine(Source source, Stand stand) {
        double[] pair = pending.get(stand.id());
        if (pair == null) {
            source.failure(RaceText.translatable("race.kurumamod.need_points"));
            return null;
        }
        if (pair.length < 6) {
            // 1 点目だけ打ってある。「2 点打て」と言われても、もう 1 点でよいと分からない
            source.failure(RaceText.translatable("race.kurumamod.need_second_point",
                    String.format("%.1f", pair[0]), String.format("%.1f", pair[1])));
            return null;
        }
        // 高さは 2 点の低い方を基準にする
        double y = Math.min(pair[2], pair[5]);
        return CourseLine.between(pair[0], pair[1], pair[3], pair[4], y, HEIGHT, stand.yRot());
    }

    // ------------------------------------------------------------------
    // コース
    // ------------------------------------------------------------------

    public int createCourse(Source source, Stand stand, String name) {
        CourseLine line = takeLine(source, stand);
        if (line == null) {
            return 0;
        }
        // 名前は画面のあちこちとパケットに載る。長すぎる名前は作らせない
        if (name.length() > Course.MAX_NAME_LENGTH) {
            source.failure(RaceText.translatable("race.kurumamod.course_name_too_long", Course.MAX_NAME_LENGTH));
            return 0;
        }
        RaceLevel level = source.level();
        RaceRecords data = level.records();
        if (refuseWhileRacing(source, level, name)) {
            return 0;
        }
        // <b>ほかのコースのスタートラインと近すぎる線は引かせない。</b>1 ティックで 2 本を越えると、どちらのコースを
        // 走るのか決められない（そのときは計測を始めない。RaceEngine）
        for (Course other : data.courses()) {
            if (!other.name().equals(name) && other.start() != null
                    && line.distanceTo(other.start()) < START_LINE_SPACING) {
                source.failure(RaceText.translatable("race.kurumamod.start_too_close",
                        other.name(), (int) START_LINE_SPACING));
                return 0;
            }
        }
        if (data.course(name).isEmpty() && data.courses().size() >= RaceRecords.MAX_COURSES) {
            source.failure(RaceText.translatable("race.kurumamod.too_many_courses", RaceRecords.MAX_COURSES));
            return 0;
        }
        Course course = data.course(name).orElseGet(() -> data.createCourse(name));
        course.setStart(line);
        course.clearCheckpoints();
        data.setDirty();
        engine.onCourseChanged(level, name);
        engine.syncAll(source.server());
        source.success(RaceText.translatable("race.kurumamod.course_created",
                name, String.format("%.1f", line.length()), stand.facing()).withStyle(RaceColor.GREEN), true);
        invalidateBests(source, level, name);
        return 1;
    }

    /**
     * 線が変わったので、そのコースの記録を消す（過去のタイムとは比べられない）。消した記録があれば、その次元の
     * 全員のゴーストも消させ、クラスの一覧の記録の件数も配り直す。
     *
     * <p>チェックポイントを足したときも消す。消さないと、チェックポイントが無かった頃の近道し放題のタイムが、
     * 新しい線の記録と同じランキングに並んだ。</p>
     */
    private void invalidateBests(Source source, RaceLevel level, String name) {
        if (level.records().clearCourseBests(name) > 0) {
            engine.notifyCourseBestsCleared(level, name);
            engine.classesToAll(source.server());
            source.success(RaceText.translatable("race.kurumamod.bests_invalidated", name)
                    .withStyle(RaceColor.YELLOW), false);
        }
    }

    /**
     * そのコースでレースを受け付けている・走っている間は、線を変えさせない・消させない。変えると、走っている人の周が
     * 無効になったり、古い線のまま測られたりする。タイムアタックは止めて変える（{@link RaceEngine#onCourseChanged}）。
     */
    private boolean refuseWhileRacing(Source source, RaceLevel level, String name) {
        if (!engine.raceOnCourse(level, name)) {
            return false;
        }
        source.failure(RaceText.translatable("race.kurumamod.course_in_use", name));
        return true;
    }

    public int addCheckpoint(Source source, Stand stand, String name) {
        RaceLevel level = source.level();
        RaceRecords data = level.records();
        Course course = data.course(name).orElse(null);
        if (course == null) {
            return failNoCourse(source, name);
        }
        if (refuseWhileRacing(source, level, name)) {
            return 0;
        }
        if (course.checkpointCount() >= Course.MAX_CHECKPOINTS) {
            source.failure(RaceText.translatable("race.kurumamod.too_many_checkpoints",
                    name, Course.MAX_CHECKPOINTS));
            return 0;
        }
        CourseLine line = takeLine(source, stand);
        if (line == null) {
            return 0;
        }
        course.addCheckpoint(line);
        data.setDirty();
        engine.onCourseChanged(level, name);
        engine.syncAll(source.server());
        int index = course.checkpointCount();
        source.success(RaceText.translatable("race.kurumamod.checkpoint_added", name, index)
                .withStyle(RaceColor.GREEN), true);
        invalidateBests(source, level, name);
        return 1;
    }

    /** スタートラインの通過方向を、今向いている方向へ引き直す。 */
    public int setDirection(Source source, Stand stand, String name) {
        RaceLevel level = source.level();
        RaceRecords data = level.records();
        Course course = data.course(name).orElse(null);
        if (course == null || course.start() == null) {
            return failNoCourse(source, name);
        }
        if (refuseWhileRacing(source, level, name)) {
            return 0;
        }
        course.setStart(course.start().withDirection(stand.yRot()));
        data.setDirty();
        engine.onCourseChanged(level, name);
        engine.syncAll(source.server());
        source.success(RaceText.translatable("race.kurumamod.direction_set", name, stand.facing()), true);
        // 向きが変われば逆回りのコースになる。前の向きのタイムとは比べられない
        invalidateBests(source, level, name);
        return 1;
    }

    public int setLaps(Source source, String name, int laps) {
        RaceRecords data = source.level().records();
        Course course = data.course(name).orElse(null);
        if (course == null) {
            return failNoCourse(source, name);
        }
        course.setLaps(laps);
        data.setDirty();
        source.success(RaceText.translatable("race.kurumamod.laps_set", name, laps), true);
        return 1;
    }

    /** コマンドを打った場所の次元のコースを消す（コンソールからならオーバーワールド）。 */
    public int removeCourse(Source source, String name) {
        RaceLevel level = source.level();
        RaceRecords data = level.records();
        if (data.course(name).isEmpty()) {
            return failNoCourse(source, name);
        }
        // レースをしている間は消させない（走っているレースが、リザルトも出さずに消えた）
        if (refuseWhileRacing(source, level, name)) {
            return 0;
        }
        data.removeCourse(name);
        // そのコースのタイムアタック・記録・ゴーストを片付ける
        engine.onCourseRemoved(level, name);
        engine.syncAll(source.server());
        source.success(RaceText.translatable("race.kurumamod.course_removed", name), true);
        return 1;
    }

    public int listCourses(Source source) {
        RaceRecords data = source.level().records();
        if (data.courses().isEmpty()) {
            source.success(RaceText.translatable("race.kurumamod.no_courses"), false);
            return 0;
        }
        for (Course course : data.courses()) {
            source.success(RaceText.translatable("race.kurumamod.course_entry",
                    course.name(), course.laps(), course.checkpointCount()), false);
        }
        return data.courses().size();
    }
}
