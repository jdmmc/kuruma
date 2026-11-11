package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.network.CourseLinesPacket;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.race.RaceColor;
import com.jdmmc.kurumamod.race.RaceText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * タイムアタックのゴースト。自己ベストの周を記録して、自分の画面にだけ半透明の車として走らせる。
 * <b>中身はここ（Minecraft に触らない）。</b>車を出す・保存する口は版ごとの {@code CarGhosts}（{@link GhostClient}）。
 *
 * <p><b>記録するのは、運転中にサーバーへ送っている状態そのもの</b>（{@code CarEntity#lastSentState()}）
 * と位置。他人の画面はこれを受け取って車を描いているので、再生側は「自分の画面にだけいる車」を
 * 1 台作り、記録を 1 ティックずつ流し込めば他人の車と同じ見た目で走る
 * （{@code CarEntity#playGhostFrame}）。物理は解き直さない。</p>
 *
 * <p>ゴーストは<b>サーバーに存在しない</b>。ぶつからず、殴れず、音・煙・タイヤ痕も出さない。
 * 記録は手元に置き、誰にも送らない（シングルはワールドのセーブの {@code kurumamod/ghosts/<UUID>/<次元>/}、マルチは
 * {@code config/kurumamod/ghosts/<アドレス>/<UUID>/<次元>/}。{@link #dimDir}）。</p>
 *
 * <p><b>タイムアタックでは何もしなくても動く</b>（H メニューの「ゴースト」）。運転中は
 * 常に走りを書き溜めておき、サーバーから「周回完了」がベスト更新として届いたら、その周を
 * そのコースのゴーストにする（ゴーストはサーバーの自己ベストと連動する）。スタートラインを跨ぐたびに、そのコースの
 * ゴーストが一緒に走り出す。</p>
 *
 * <p><b>周回の区切りはサーバーの合図ではなく、自分の記録とスタートゲートで決める。</b>合図は
 * 跨いだ瞬間から通信の遅れぶん遅れて届くので、届いた時点で区切るとゴーストが数ティック
 * 後ろへずれる。届いたら、直近の記録の中から「スタートゲートを跨いだ 1 ティック」を探して
 * そこで切り（{@link #crossingIndex}）、ゴーストも遅れたぶん進めた位置から走らせる。</p>
 */
public final class GhostEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(GhostEngine.class);

    /** 記録の上限。30 分ぶん（1 ティックおよそ 110 バイトなので 4MB ほど）。 */
    private static final int MAX_FRAMES = 20 * 60 * 30;

    /** 走り終えたゴーストを、消すまでその場に残しておく時間 [tick]。 */
    private static final int FINISH_HOLD_TICKS = 40;

    /** 周回の外で書き溜めておく長さ [tick]。合図が届くまでの遅れより十分長ければよい。 */
    private static final int TRAIL_IDLE_FRAMES = 60;

    /** スタートゲートを跨いだ 1 ティックを、合図が届いた時点からどこまで遡って探すか [tick]。 */
    private static final int CROSSING_SEARCH_FRAMES = 40;

    /** ゴーストのエンティティ id。サーバーが振る id（正の数）と重ならないよう負にする。 */
    public static final int GHOST_ENTITY_ID = -0x4B5247;

    private static final int FILE_VERSION = 1;

    /** 助手席で見るゴーストのエンティティ id。自分のゴースト（{@link #GHOST_ENTITY_ID}）と重ならないように。 */
    public static final int RIDE_GHOST_ENTITY_ID = GHOST_ENTITY_ID - 1;
    /** 助手席へ見た目を送り直す間隔 [tick]。途中で乗ってきた人や、取りこぼしに備えて */
    private static final int LOOK_RESEND_TICKS = 40;
    /** 助手席で、これだけコマが届かなければゴーストを下げる [tick]（運転者が落ちたなど）。走り終えて残す時間より長く */
    private static final int RIDE_TIMEOUT_TICKS = FINISH_HOLD_TICKS + 20;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_.\\-]");
    private static final String EXTENSION = ".ghost";
    /** クラスごとのゴーストを置くフォルダの名前（ワールドのフォルダの下）。 */
    private static final String CLASS_DIR = "class";

    /** 助手席に届いたものの種類（{@code GhostRidePacket} の番号と同じ）。 */
    public enum RideKind {
        LOOK, FRAME, STOP
    }

    /**
     * 記録 1 本。見た目（車種・諸元・装着）は記録した車のもので、再生するときの姿になる。
     *
     * @param lapMillis サーバーが測ったラップタイム [ms]
     */
    private record Ghost(Object look, String dimension, long lapMillis, List<GhostFrame> frames) {
    }

    /** 運転中の走り。周回中はスタートラインを跨いだ瞬間から、そうでなければ直近だけ。 */
    private static final List<GhostFrame> trail = new ArrayList<>();
    /** いま周回を測っているコース。測っていなければ null。 */
    private static String lapCourse;
    /** いまの周の比べる相手のクラス（空ならクラスなし）。走らせるゴーストもこれで選ぶ。 */
    private static String lapClass = "";
    private static GhostClient.Car trailCar;
    private static Object trailIdentity;

    // 助手席へ流している車（運転者の側）。流していなければ -1
    private static int sharedCarId = -1;
    private static int sharedRiders;
    private static int lookCooldown;

    // 助手席で見ているゴースト（助手席の側）
    private static GhostClient.Ghost rideGhost;
    private static int rideCarId = -1;
    private static Object rideLook;
    private static int rideSilence;

    // 再生中
    private static Ghost playing;
    private static GhostClient.Ghost ghostEntity;
    private static int index;
    private static int finishHold;

    private GhostEngine() {
    }

    private static GhostClient client() {
        return GhostClient.get();
    }

    // ------------------------------------------------------------------
    // ティック
    // ------------------------------------------------------------------

    /** 車がティックする前に流し込む。lerpTo の位置へはその車のティックで寄る。 */
    public static void onClientTickPre() {
        if (!client().isPaused()) {
            tickPlayback();
            tickRide();
        }
    }

    /** 車がティックし終えて、送った状態が出揃ってから取る。 */
    public static void onClientTickPost() {
        if (!client().isPaused()) {
            tickTrail();
        }
    }

    /** 走りを書き溜める。運転中は常に。 */
    private static void tickTrail() {
        GhostClient.Car car = client().drivenCar();
        Object identity = car == null ? null : car.identity();
        if (identity != trailIdentity) {
            // 降りた・乗り換えた。測っていた周は捨て、走らせていたゴーストも下げる
            resetLap();
            trailIdentity = identity;
        }
        trailCar = car;
        if (car == null) {
            return;
        }
        GhostFrame frame = car.lastSentFrame();
        if (frame == null) {
            return;
        }
        trail.add(frame);
        if (lapCourse == null && trail.size() > TRAIL_IDLE_FRAMES) {
            trail.subList(0, trail.size() - TRAIL_IDLE_FRAMES).clear();
        } else if (trail.size() > MAX_FRAMES) {
            // 30 分回っても周回が終わらない。ゴーストにはしない
            resetLap();
        }
    }

    private static void tickPlayback() {
        if (playing == null) {
            return;
        }
        if (!client().inWorld() || ghostEntity == null || !ghostEntity.alive()) {
            stopPlayback();
            return;
        }
        if (index < playing.frames().size()) {
            GhostFrame frame = playing.frames().get(index++);
            ghostEntity.play(frame);
            share(frame);
            return;
        }
        if (finishHold-- <= 0) {
            stopPlayback();
        }
    }

    public static void onLoggingOut() {
        // ワールドを抜けたら捨てる
        trail.clear();
        lapCourse = null;
        trailCar = null;
        trailIdentity = null;
        stopPlayback();
        stopRide();
    }

    // ------------------------------------------------------------------
    // タイムアタック
    // ------------------------------------------------------------------

    /**
     * サーバーからのラップ計測の合図（{@code CarClientPackets#applyRaceEvent}）。
     *
     * <p>タイムアタックだけ。レース中（規定周回あり）はゴーストを出さない——並んで走る相手が
     * いるところに半透明の車が混ざると、どれが本物か分からなくなる。</p>
     *
     * <p><b>ゴーストは「コース × クラス」ごとに 1 本</b>（クラスなしと、宣言して走ったクラス）。走らせるのは
     * その周の比べる相手（宣言しているクラス、していなければクラスなし）のもの。</p>
     *
     * <p>周の始めに届く比べる相手のベスト（{@code targetBest}）と、手元のゴーストのタイムが合わなければ、
     * そのゴーストは捨てる（落ちている間に記録が消されていた）。</p>
     *
     * <p>周回が無効だった合図（取りこぼし・短すぎ）では何もしない。サーバーは測り直さずに
     * 元のスタートから測り続けるので、こちらも書き溜めを続ける。</p>
     */
    public static void onRaceEvent(RaceEventPacket packet) {
        switch (packet.kind()) {
            case LAP_STARTED -> {
                if (packet.totalLaps() == 0) {
                    beginLap(packet.course(), packet.raceClass(), packet.targetBest());
                } else {
                    resetLap();
                }
            }
            case LAP_COMPLETED -> {
                if (packet.totalLaps() == 0) {
                    completeLap(packet);
                } else {
                    resetLap();
                }
            }
            case FINISHED, CLEARED -> resetLap();
            case BEST_CLEARED -> {
                // 記録を消した次元のフォルダから消す（今いる次元でなくても届く。記録は次元に縛られない）
                String dimension = packet.clearedDimension();
                if (packet.raceClass().isEmpty() && packet.overallBest()) {
                    // クラスなしの記録だけ（OP が K の画面から消した）
                    deleteClassLapGhost(dimension, packet.course(), "");
                } else if (packet.raceClass().isEmpty()) {
                    deleteLapGhosts(dimension, packet.course());
                } else if (packet.course().isEmpty()) {
                    deleteClassGhosts(packet.raceClass());
                } else {
                    deleteClassLapGhost(dimension, packet.course(), packet.raceClass());
                }
            }
            default -> {
            }
        }
    }

    private static void beginLap(String course, String raceClass, long targetBest) {
        trail.subList(0, crossingIndex(course)).clear();
        lapCourse = course;
        lapClass = raceClass;
        startLapGhost(course, raceClass, targetBest);
    }

    /**
     * 1 周終わった。<b>ゴーストはサーバーのベストと同じ周にする</b>——ベストを更新した周
     * （{@code best}）だけを残す。手元のゴーストと速さを比べると、ベストを消した後も古い
     * ゴーストが残り、それより遅い新しいベストが記録されなくなる。
     */
    private static void completeLap(RaceEventPacket packet) {
        String course = packet.course();
        long millis = packet.millis();
        int at = crossingIndex(course);
        if (course.equals(lapCourse) && at > 0 && trailCar != null) {
            Ghost ghost = null;
            // 入るのはクラスなしか、記録したクラスのどちらか 1 か所（念のため両方を見る。知らせは 1 回だけ）
            if (packet.overallBest()) {
                ghost = toGhost(new ArrayList<>(trail.subList(0, at)), trailCar, millis);
                saveLapGhost(lapFile(course, ""), ghost, course, millis, !packet.classBest());
            }
            if (packet.classBest() && !packet.countedClass().isEmpty()) {
                if (ghost == null) {
                    ghost = toGhost(new ArrayList<>(trail.subList(0, at)), trailCar, millis);
                }
                saveLapGhost(lapFile(course, packet.countedClass()), ghost,
                        course + " / " + packet.countedClass(), millis, true);
            }
        }
        trail.subList(0, at).clear();
        lapCourse = course;
        lapClass = packet.raceClass();
        startLapGhost(course, lapClass, packet.targetBest());
    }

    private static void saveLapGhost(Path file, Ghost ghost, String label, long millis, boolean announce) {
        Ghost current = load(file);
        if (save(file, ghost) && announce) {
            client().message((current == null || millis >= current.lapMillis()
                    ? RaceText.translatable("ghost.kurumamod.timeattack_new", label, lapTime(millis))
                    : RaceText.translatable("ghost.kurumamod.timeattack_faster", label, lapTime(millis),
                    "-" + lapTime(current.lapMillis() - millis)))
                    .withStyle(RaceColor.AQUA));
        }
    }

    /** 1 つのコースの 1 つのクラス（空ならクラスなし）の記録を消した（OP が K の画面から）。そのゴーストだけ消す。 */
    private static void deleteClassLapGhost(String dimension, String course, String raceClass) {
        if (playing != null && dimension.equals(client().currentDimension())
                && course.equals(lapCourse) && raceClass.equals(lapClass)) {
            stopPlayback();
        }
        Path file = lapFile(dimension, course, raceClass);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("タイムアタックのゴーストを消せませんでした: {}", file, e);
        }
    }

    /** クラスの記録を消したので、そのクラスのゴーストを全コースぶん消す。 */
    private static void deleteClassGhosts(String raceClass) {
        if (playing != null && raceClass.equals(lapClass)) {
            stopPlayback();
        }
        // クラスはサーバーに 1 組。記録は全部の次元から消えたので、ゴーストも全部の次元のフォルダから消す
        Path player = playerDir();
        if (!Files.isDirectory(player)) {
            return;
        }
        try (var dims = Files.list(player)) {
            for (Path dim : dims.toList()) {
                deleteTree(dim.resolve(CLASS_DIR).resolve(fileKey(raceClass)));
            }
        } catch (IOException e) {
            LOGGER.warn("タイムアタックのゴーストを消せませんでした: {}", player, e);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("タイムアタックのゴーストを消せませんでした: {}", root, e);
        }
    }

    /**
     * ベストを消したので、その次元のそのコース（空なら全コース）のゴーストも消す。
     * その次元で走っている最中なら、いま出ているゴーストも引っ込める。
     */
    private static void deleteLapGhosts(String dimension, String course) {
        if (playing != null && dimension.equals(client().currentDimension())
                && (course.isEmpty() || course.equals(lapCourse))) {
            stopPlayback();
        }
        Path world = dimDir(dimension);
        if (course.isEmpty()) {
            deleteTree(world);
            return;
        }
        // クラスなしと、全クラスのそのコースのぶん
        try {
            Files.deleteIfExists(lapFile(dimension, course, ""));
            Path classes = world.resolve(CLASS_DIR);
            if (Files.isDirectory(classes)) {
                try (var dirs = Files.list(classes)) {
                    for (Path dir : dirs.toList()) {
                        Files.deleteIfExists(dir.resolve(courseFile(course)));
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("タイムアタックのゴーストを消せませんでした: {}", world, e);
        }
    }

    private static void resetLap() {
        trail.clear();
        lapCourse = null;
        lapClass = "";
        stopPlayback();
    }

    /**
     * そのコースのゴーストを走らせる。<b>跨いだ瞬間に揃える</b>——合図が届くまでに
     * 自分はもう数ティック進んでいるので、ゴーストもそのぶん進めた位置から始める。
     */
    private static void startLapGhost(String course, String raceClass, long targetBest) {
        Ghost ghost = null;
        if (client().ghostsEnabled() && client().inWorld()) {
            Path file = lapFile(course, raceClass);
            ghost = load(file);
            // <b>サーバーのベストと同じ周でなければ捨てる。</b>ゴーストは手元にしか無いので、落ちている間に
            // 記録が消された（クラスの上書き・削除、コースの引き直し）ことは、ここでしか分からない
            if (ghost != null && ghost.lapMillis() != targetBest) {
                ghost = null;
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    LOGGER.warn("古いゴーストを消せませんでした: {}", file, e);
                }
            }
        }
        if (ghost == null) {
            stopPlayback();
            return;
        }
        startPlayback(ghost, Math.max(0, trail.size() - 1));
    }

    /**
     * 直近の記録の中で、スタートゲートを跨いだ 1 ティックの位置（跨いだ<b>後</b>の記録の番号）。
     *
     * <p><b>そのコースのスタートゲートだけを見る</b>（ゲートにはコースの名前が付いている）。ほかのコースの
     * スタートの線が近くにあっても取り違えない。見つからなければ（ゲートがまだ届いていない等）、
     * いちばん新しい記録で区切る。</p>
     */
    private static int crossingIndex(String course) {
        int last = trail.size() - 1;
        if (last <= 0) {
            return Math.max(last, 0);
        }
        int stop = Math.max(1, last - CROSSING_SEARCH_FRAMES);
        for (int i = last; i >= stop; i--) {
            GhostFrame from = trail.get(i - 1);
            GhostFrame to = trail.get(i);
            for (CourseLinesPacket.Gate gate : CourseGates.gates()) {
                if (gate.start() && course.equals(gate.course()) && crosses(from.x(), from.z(), to.x(), to.z(),
                        gate.ax(), gate.az(), gate.bx(), gate.bz())) {
                    return i;
                }
            }
        }
        return last;
    }

    /** 線分 p→q と線分 a→b が交わるか（水平面で）。 */
    private static boolean crosses(double px, double pz, double qx, double qz,
                                   double ax, double az, double bx, double bz) {
        double d1 = cross(ax, az, bx, bz, px, pz);
        double d2 = cross(ax, az, bx, bz, qx, qz);
        double d3 = cross(px, pz, qx, qz, ax, az);
        double d4 = cross(px, pz, qx, qz, bx, bz);
        return d1 * d2 < 0.0 && d3 * d4 < 0.0;
    }

    private static double cross(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }

    // ------------------------------------------------------------------
    // 再生
    // ------------------------------------------------------------------

    private static Ghost toGhost(List<GhostFrame> frames, GhostClient.Car car, long lapMillis) {
        return new Ghost(car.look(), car.dimension(), lapMillis, List.copyOf(frames));
    }

    /** @param from 何ティック目から走らせるか（跨いでから合図が届くまでのぶん） */
    private static void startPlayback(Ghost ghost, int from) {
        stopPlayback();
        from = Math.min(from, ghost.frames().size() - 1);
        GhostFrame first = ghost.frames().get(from);
        playing = ghost;
        ghostEntity = client().spawn(ghost.look(), GHOST_ENTITY_ID, first);
        index = from + 1;
        finishHold = FINISH_HOLD_TICKS;
    }

    private static void stopPlayback() {
        if (ghostEntity != null) {
            ghostEntity.discard();
        }
        ghostEntity = null;
        playing = null;
        stopSharing();
    }

    // ------------------------------------------------------------------
    // 助手席にも見せる（GhostRidePacket）
    // ------------------------------------------------------------------

    /**
     * 運転者の側。いま流し込んだ 1 コマを、同じ車の助手席の人へ送る。
     *
     * <p><b>記録そのものは送らない。</b>コマは運転者の車の状態と同じ道（サーバー経由）で届くので、
     * 助手席から見た「ゴーストと自分の車の間隔」は運転者が見ているものとほぼ同じになる。
     * 見た目は走り出したとき・乗員が変わったとき・{@link #LOOK_RESEND_TICKS} ごとに送り直す。</p>
     */
    private static void share(GhostFrame frame) {
        GhostClient.Car car = client().drivenCar();
        int riders = car == null ? 0 : car.riders();
        if (riders <= 0 || playing == null) {
            stopSharing();
            return;
        }
        if (car.entityId() != sharedCarId || riders != sharedRiders || --lookCooldown <= 0) {
            send(() -> client().sendRideLook(car.entityId(), client().rideLook(playing.look())));
            sharedCarId = car.entityId();
            sharedRiders = riders;
            lookCooldown = LOOK_RESEND_TICKS;
        }
        send(() -> client().sendRideFrame(car.entityId(), frame));
    }

    /** 助手席へ流すのをやめる（走り終えた・止めた・乗員がいなくなった）。 */
    private static void stopSharing() {
        if (sharedCarId >= 0) {
            int id = sharedCarId;
            send(() -> client().sendRideStop(id));
        }
        sharedCarId = -1;
        sharedRiders = 0;
    }

    private static void send(Runnable send) {
        if (client().connected()) {
            send.run();
        }
    }

    /**
     * 助手席の側。運転者の画面で走っているゴーストの 1 コマが届いた（{@code CarClientPackets#applyGhostRide}）。
     * 自分のゴーストとは別に 1 台出す。H メニューでゴーストを切っていれば出さない。
     *
     * @param look  {@link RideKind#LOOK} のときの見た目（{@link GhostClient#rideLook} の形）
     * @param frame {@link RideKind#FRAME} のときの 1 コマ
     */
    public static void applyRide(RideKind kind, int carEntityId, Object look, GhostFrame frame) {
        if (!client().inWorld() || client().playerId() == null || !client().ghostsEnabled()) {
            stopRide();
            return;
        }
        // 乗っている車のものだけ（降りた後に遅れて届いたものは捨てる）
        if (client().vehicleId() != carEntityId) {
            return;
        }
        switch (kind) {
            case LOOK -> {
                // 見た目が変わった（別の車で取ったベストに替わった）なら出し直す
                if (rideGhost != null && !look.equals(rideLook)) {
                    stopRide();
                }
                rideLook = look;
                rideCarId = carEntityId;
            }
            case FRAME -> {
                if (rideLook == null || rideCarId != carEntityId) {
                    return;
                }
                if (rideGhost == null || !rideGhost.alive()) {
                    GhostClient.Ghost entity = client().spawnFromRideLook(rideLook, RIDE_GHOST_ENTITY_ID, frame);
                    if (entity == null) {
                        // <b>運転者のクライアントから届いたもので、サーバーは中身を見ていない。</b>壊れていても
                        // 同乗者の画面を落とさない。次の見た目が届くまで出さない
                        rideLook = null;
                        return;
                    }
                    rideGhost = entity;
                }
                rideGhost.play(frame);
                rideSilence = 0;
            }
            case STOP -> stopRide();
            default -> {
            }
        }
    }

    /** 助手席の側。降りた・コマが途切れた・ゴーストを切ったら下げる。 */
    private static void tickRide() {
        if (rideGhost == null) {
            return;
        }
        boolean riding = client().vehicleId() >= 0 && client().vehicleId() == rideCarId;
        if (!riding || ++rideSilence > RIDE_TIMEOUT_TICKS || !client().ghostsEnabled() || !rideGhost.alive()) {
            stopRide();
        }
    }

    private static void stopRide() {
        if (rideGhost != null) {
            rideGhost.discard();
        }
        rideGhost = null;
        rideLook = null;
        rideCarId = -1;
        rideSilence = 0;
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /** マルチプレイのゴーストの置き場所（サーバーごとのフォルダをこの下に作る）。 */
    private static Path serversDir() {
        return client().configDir().resolve("kurumamod").resolve("ghosts");
    }

    /**
     * タイムアタックのゴーストの置き場所。<b>ワールド（サーバー）ごと・コースごと。</b>
     *
     * <p>コースの名前はワールドごとに付けるので、名前だけで引くと別のワールドの同名のコースの
     * ゴーストが、何もない場所を走り出す。シングルはセーブのフォルダ名、マルチは接続先で分ける。</p>
     */
    private static Path lapFile(String course, String raceClass) {
        return lapFile(client().currentDimension(), course, raceClass);
    }

    /** 次元を決めて引く（記録を消した知らせは、別の次元のものも届く）。 */
    private static Path lapFile(String dimension, String course, String raceClass) {
        Path base = dimDir(dimension);
        if (raceClass != null && !raceClass.isEmpty()) {
            base = base.resolve(CLASS_DIR).resolve(fileKey(raceClass));
        }
        return base.resolve(courseFile(course));
    }

    /** コースのゴーストのファイル名（{@link #fileKey}）。 */
    private static String courseFile(String course) {
        return fileKey(course) + EXTENSION;
    }

    /**
     * 名前をファイル名・フォルダ名にする。<b>名前はそのまま使わない。</b>
     *
     * <p>そのまま使うと、Windows では大文字と小文字だけが違う名前（GT と gt）が同じファイルになって
     * ゴーストを上書きし合い、末尾の点は消されて別の名前と重なり、CON・NUL・COM1 のような名前は作れなかった。
     * 日本語の名前を {@link #safe} で潰すと、別の名前と同じになる。</p>
     *
     * <p>UTF-8 のバイト列を 16 進にする（{@code x} で始まる小文字の英数字だけ。名前が違えば必ず別になる）。
     * 長い名前は 16 進だとファイル名の上限（255 文字）を超えるので、SHA-256 にする（{@code h} で始まる）。</p>
     */
    private static String fileKey(String name) {
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 48) {
            return "x" + java.util.HexFormat.of().formatHex(bytes);
        }
        try {
            return "h" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // どの JVM にも必ずある
        }
    }

    /**
     * その次元のゴーストのフォルダ。次元の名前（minecraft:the_nether など）も、そのままではファイル名にしない。
     *
     * <ul>
     *   <li><b>シングルプレイは、ワールドのセーブの中</b>（{@code saves/<ワールド>/kurumamod/ghosts/}）。ワールドを
     *       消せばゴーストも消え、コピーすれば付いていく。以前は {@code config} の下にセーブのフォルダ名で分けて
     *       置いていたので、消したワールドのゴーストが残り、コピーしたワールドには付いてこなかった</li>
     *   <li>マルチプレイは {@code config/kurumamod/ghosts/<アドレス>/}（{@link #serverKey}）</li>
     * </ul>
     *
     * <p>その下を<b>プレイヤーの UUID</b>（{@link #playerDir}）と<b>次元</b>で分ける（{@code <UUID>/<次元>/}）。
     * そこから下の形はどちらも同じ（コースのファイルと {@code class/<クラス>/}）。</p>
     *
     * <p><b>次元で分けるのは、コースが次元ごとにあるから</b>（オーバーワールドとネザーに同じ名前のコースを作れる）。
     * 分けていなかった頃は、ネザーで走り出すとオーバーワールドのゴーストを読み、タイムがネザーのベストと合わないので
     * 消していた。</p>
     *
     * <p>2.0.0 までの置き場所（{@code config/kurumamod-ghosts/}）は見ない。残っていても使わず、そのままにする。</p>
     */
    private static Path dimDir(String dimension) {
        return playerDir().resolve(dimension != null ? fileKey(dimension) : "unknown");
    }

    /** そのワールド（サーバー）の、いま入っているプレイヤーのフォルダ（次元のフォルダはこの下）。 */
    private static Path playerDir() {
        Path world;
        Path single = client().singleplayerWorld();
        if (single != null) {
            world = single.normalize().resolve("kurumamod").resolve("ghosts");
        } else {
            String address = client().serverAddress();
            world = serversDir().resolve(address != null ? safe(serverKey(address)) : "unknown");
        }
        // <b>プレイヤーごとに分ける。</b>分けないと、同じ PC から別のアカウントで入った人が相手のゴーストを読み、
        // 自分のベストと合わないので消してしまった
        String player = client().playerId();
        return world.resolve(player != null ? player : "unknown");
    }

    /** いま入っているワールド（サーバー）の名前。シングルはセーブのフォルダ名、マルチは接続先。 */
    public static String worldKey() {
        Path single = client().singleplayerWorld();
        if (single != null) {
            return "local-" + single.normalize().getFileName();
        }
        String address = client().serverAddress();
        if (address != null) {
            return "server-" + serverKey(address);
        }
        return "unknown";
    }

    /**
     * 接続先のアドレスを、同じサーバーなら同じ文字列になるようにそろえる。小文字にし（ホスト名は大文字と小文字を
     * 区別しない）、末尾の点と既定のポート（25565）を外す。
     *
     * <p>そのまま使うと、{@code play.example.com} と {@code play.example.com:25565} は別のフォルダになり、
     * Windows では大文字と小文字だけが違うアドレスが同じフォルダに、Linux・macOS では別のフォルダになっていた。</p>
     */
    static String serverKey(String address) {
        String text = address.trim().toLowerCase(java.util.Locale.ROOT);
        String host = text;
        String port = "";
        if (text.startsWith("[")) {
            // IPv6（[::1]:25565）
            int end = text.indexOf(']');
            if (end > 0) {
                host = text.substring(0, end + 1);
                String rest = text.substring(end + 1);
                port = rest.startsWith(":") ? rest.substring(1) : "";
            }
        } else {
            int colon = text.lastIndexOf(':');
            if (colon > 0 && text.indexOf(':') == colon) {
                host = text.substring(0, colon);
                port = text.substring(colon + 1);
            }
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (port.equals("25565")) {
            port = "";
        }
        return port.isEmpty() ? host : host + ":" + port;
    }

    /** ファイル名に使えない文字を潰す。点だけの名前（親へ上がる）も潰す。 */
    private static String safe(String text) {
        String cleaned = UNSAFE.matcher(text).replaceAll("_");
        return cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.') ? "_" : cleaned;
    }

    /**
     * 1 本を書く。NBT（圧縮）の中に、車の情報と、毎ティックの値を詰めたバイト列を入れる（NBT は版の側）。
     * 毎ティックの値を NBT のリストにしないのは、1 ティックに 30 個近い数があって重いため。
     */
    private static boolean save(Path path, Ghost ghost) {
        try {
            Files.createDirectories(path.getParent());
            return client().save(path, ghost.look(), ghost.dimension(), ghost.lapMillis(), ghost.frames().size(),
                    encode(ghost.frames()));
        } catch (IOException e) {
            LOGGER.warn("ゴーストを保存できませんでした: {}", path, e);
            return false;
        }
    }

    private static Ghost load(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            GhostClient.Stored stored = client().load(path);
            if (stored.version() != FILE_VERSION) {
                LOGGER.warn("知らない版のゴーストです: {} (版 {})", path, stored.version());
                return null;
            }
            List<GhostFrame> frames = decode(stored.data(), stored.frames());
            if (frames.isEmpty()) {
                return null;
            }
            return new Ghost(stored.look(), stored.dimension(), stored.lapMillis(), frames);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ゴーストを読めませんでした: {}", path, e);
            return null;
        }
    }

    /** ファイルの版（{@code Version}）。版の側が書き込む。 */
    public static int fileVersion() {
        return FILE_VERSION;
    }

    private static byte[] encode(List<GhostFrame> frames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(frames.size() * 112);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (GhostFrame frame : frames) {
                frame.write(out);
            }
        }
        return bytes.toByteArray();
    }

    private static List<GhostFrame> decode(byte[] data, int count) throws IOException {
        List<GhostFrame> frames = new ArrayList<>(count);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            for (int n = 0; n < count; n++) {
                frames.add(GhostFrame.read(in));
            }
        }
        return frames;
    }

    /** ラップタイムの書式。レースの表示と同じ 1:23.456。 */
    private static String lapTime(long millis) {
        return String.format("%d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000);
    }
}
