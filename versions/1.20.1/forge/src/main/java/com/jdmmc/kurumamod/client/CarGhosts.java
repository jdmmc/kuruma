package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CarStatePacket;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import com.jdmmc.kurumamod.network.GhostRidePacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * タイムアタックのゴースト。自己ベストの周を記録して、自分の画面にだけ半透明の車として走らせる。
 *
 * <p><b>記録するのは、運転中にサーバーへ送っている状態そのもの</b>（{@link CarEntity#lastSentState()}）
 * と位置。他人の画面はこれを受け取って車を描いているので、再生側は「自分の画面にだけいる車」を
 * 1 台作り、記録を 1 ティックずつ流し込めば他人の車と同じ見た目で走る
 * （{@link CarEntity#playGhostFrame}）。物理は解き直さない。</p>
 *
 * <p>ゴーストは<b>サーバーに存在しない</b>。ぶつからず、殴れず、音・煙・タイヤ痕も出さない。
 * 記録は手元に置き、誰にも送らない（シングルはワールドのセーブの {@code kurumamod/ghosts/<UUID>/<次元>/}、マルチは
 * {@code config/kurumamod/ghosts/<アドレス>/<UUID>/<次元>/}。{@link #worldDir}）。</p>
 *
 * <p><b>タイムアタックでは何もしなくても動く</b>（{@link ClientConfig#timeAttackGhost}）。運転中は
 * 常に走りを書き溜めておき、サーバーから「周回完了」がベスト更新として届いたら、その周を
 * そのコースのゴーストにする（ゴーストはサーバーの自己ベストと連動する）。スタートラインを跨ぐたびに、そのコースの
 * ゴーストが一緒に走り出す。</p>
 *
 * <p><b>周回の区切りはサーバーの合図ではなく、自分の記録とスタートゲートで決める。</b>合図は
 * 跨いだ瞬間から通信の遅れぶん遅れて届くので、届いた時点で区切るとゴーストが数ティック
 * 後ろへずれる。届いたら、直近の記録の中から「スタートゲートを跨いだ 1 ティック」を探して
 * そこで切り（{@link #crossingIndex}）、ゴーストも遅れたぶん進めた位置から走らせる。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarGhosts {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 記録の上限。30 分ぶん（1 ティックおよそ 110 バイトなので 4MB ほど）。 */
    private static final int MAX_FRAMES = 20 * 60 * 30;

    /** 走り終えたゴーストを、消すまでその場に残しておく時間 [tick]。 */
    private static final int FINISH_HOLD_TICKS = 40;

    /** 周回の外で書き溜めておく長さ [tick]。合図が届くまでの遅れより十分長ければよい。 */
    private static final int TRAIL_IDLE_FRAMES = 60;

    /** スタートゲートを跨いだ 1 ティックを、合図が届いた時点からどこまで遡って探すか [tick]。 */
    private static final int CROSSING_SEARCH_FRAMES = 40;

    /** ゴーストのエンティティ id。サーバーが振る id（正の数）と重ならないよう負にする。 */
    private static final int GHOST_ENTITY_ID = -0x4B5247;

    private static final int FILE_VERSION = 1;

    /** 助手席で見るゴーストのエンティティ id。自分のゴースト（{@link #GHOST_ENTITY_ID}）と重ならないように。 */
    private static final int RIDE_GHOST_ENTITY_ID = GHOST_ENTITY_ID - 1;
    /** 助手席へ見た目を送り直す間隔 [tick]。途中で乗ってきた人や、取りこぼしに備えて */
    private static final int LOOK_RESEND_TICKS = 40;
    /** 助手席で、これだけコマが届かなければゴーストを下げる [tick]（運転者が落ちたなど）。走り終えて残す時間より長く */
    private static final int RIDE_TIMEOUT_TICKS = FINISH_HOLD_TICKS + 20;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_.\\-]");
    private static final String EXTENSION = ".ghost";

    /** 1 ティックぶん。位置と、その瞬間に送った状態。 */
    private record Frame(double x, double y, double z, CarStatePacket state) {
    }

    /**
     * 記録 1 本。車種・諸元・装着は記録した車のもので、再生するときの姿になる。
     *
     * @param lapMillis サーバーが測ったラップタイム [ms]
     */
    private record Ghost(ResourceLocation carId, CarSpec spec, PartFitment parts, String dimension,
                         long lapMillis, List<Frame> frames) {
    }

    /** 運転中の走り。周回中はスタートラインを跨いだ瞬間から、そうでなければ直近だけ。 */
    private static final List<Frame> trail = new ArrayList<>();
    /** いま周回を測っているコース。測っていなければ null。 */
    private static String lapCourse;
    /** いまの周の比べる相手のクラス（空ならクラスなし）。走らせるゴーストもこれで選ぶ。 */
    private static String lapClass = "";
    private static CarEntity trailCar;

    // 助手席へ流している車（運転者の側）。流していなければ -1
    private static int sharedCarId = -1;
    private static int sharedRiders;
    private static int lookCooldown;

    // 助手席で見ているゴースト（助手席の側）
    private static CarEntity rideGhost;
    private static int rideCarId = -1;
    private static CompoundTag rideLook;
    private static int rideSilence;

    // 再生中
    private static Ghost playing;
    private static CarEntity ghostEntity;
    private static int index;
    private static int finishHold;

    private CarGhosts() {
    }

    // ------------------------------------------------------------------
    // ティック
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isPaused()) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            // 車がティックする前に流し込む。lerpTo の位置へはその車のティックで寄る
            tickPlayback(minecraft);
            tickRide(minecraft);
        } else {
            // 車がティックし終えて、送った状態が出揃ってから取る
            tickTrail(minecraft);
        }
    }

    /** 走りを書き溜める。運転中は常に。 */
    private static void tickTrail(Minecraft minecraft) {
        CarEntity car = drivenCar(minecraft);
        if (car != trailCar) {
            // 降りた・乗り換えた。測っていた周は捨て、走らせていたゴーストも下げる
            resetLap();
            trailCar = car;
        }
        if (car == null) {
            return;
        }
        CarStatePacket state = car.lastSentState();
        if (state == null) {
            return;
        }
        trail.add(new Frame(car.getX(), car.getY(), car.getZ(), state));
        if (lapCourse == null && trail.size() > TRAIL_IDLE_FRAMES) {
            trail.subList(0, trail.size() - TRAIL_IDLE_FRAMES).clear();
        } else if (trail.size() > MAX_FRAMES) {
            // 30 分回っても周回が終わらない。ゴーストにはしない
            resetLap();
        }
    }

    private static void tickPlayback(Minecraft minecraft) {
        if (playing == null) {
            return;
        }
        ClientLevel level = minecraft.level;
        if (level == null || ghostEntity == null || ghostEntity.isRemoved() || ghostEntity.level() != level) {
            stopPlayback();
            return;
        }
        if (index < playing.frames().size()) {
            Frame frame = playing.frames().get(index++);
            ghostEntity.playGhostFrame(frame.x(), frame.y(), frame.z(), frame.state());
            share(minecraft, frame);
            return;
        }
        if (finishHold-- <= 0) {
            stopPlayback();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // ワールドを抜けたら捨てる
        trail.clear();
        lapCourse = null;
        trailCar = null;
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
        Minecraft minecraft = Minecraft.getInstance();
        switch (packet.type()) {
            case LAP_STARTED -> {
                if (packet.totalLaps() == 0) {
                    beginLap(minecraft, packet.course(), packet.raceClass(), packet.targetBest());
                } else {
                    resetLap();
                }
            }
            case LAP_COMPLETED -> {
                if (packet.totalLaps() == 0) {
                    completeLap(minecraft, packet);
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
                    deleteClassLapGhost(minecraft, dimension, packet.course(), "");
                } else if (packet.raceClass().isEmpty()) {
                    deleteLapGhosts(minecraft, dimension, packet.course());
                } else if (packet.course().isEmpty()) {
                    deleteClassGhosts(minecraft, packet.raceClass());
                } else {
                    deleteClassLapGhost(minecraft, dimension, packet.course(), packet.raceClass());
                }
            }
            default -> {
            }
        }
    }

    private static void beginLap(Minecraft minecraft, String course, String raceClass, long targetBest) {
        trail.subList(0, crossingIndex(course)).clear();
        lapCourse = course;
        lapClass = raceClass;
        startLapGhost(minecraft, course, raceClass, targetBest);
    }

    /**
     * 1 周終わった。<b>ゴーストはサーバーのベストと同じ周にする</b>——ベストを更新した周
     * （{@code best}）だけを残す。手元のゴーストと速さを比べると、ベストを消した後も古い
     * ゴーストが残り、それより遅い新しいベストが記録されなくなる。
     */
    private static void completeLap(Minecraft minecraft, RaceEventPacket packet) {
        String course = packet.course();
        long millis = packet.millis();
        int at = crossingIndex(course);
        if (course.equals(lapCourse) && at > 0 && trailCar != null) {
            Ghost ghost = null;
            // 入るのはクラスなしか、記録したクラスのどちらか 1 か所（念のため両方を見る。知らせは 1 回だけ）
            if (packet.overallBest()) {
                ghost = toGhost(new ArrayList<>(trail.subList(0, at)), trailCar, millis);
                saveLapGhost(minecraft, lapFile(minecraft, course, ""), ghost, course, millis,
                        !packet.classBest());
            }
            if (packet.classBest() && !packet.countedClass().isEmpty()) {
                if (ghost == null) {
                    ghost = toGhost(new ArrayList<>(trail.subList(0, at)), trailCar, millis);
                }
                saveLapGhost(minecraft, lapFile(minecraft, course, packet.countedClass()), ghost,
                        course + " / " + packet.countedClass(), millis, true);
            }
        }
        trail.subList(0, at).clear();
        lapCourse = course;
        lapClass = packet.raceClass();
        startLapGhost(minecraft, course, lapClass, packet.targetBest());
    }

    private static void saveLapGhost(Minecraft minecraft, Path file, Ghost ghost, String label, long millis,
                                     boolean announce) {
        Ghost current = load(file);
        if (save(file, ghost) && announce) {
            message(minecraft, (current == null || millis >= current.lapMillis()
                    ? Component.translatable("ghost.kurumamod.timeattack_new", label, lapTime(millis))
                    : Component.translatable("ghost.kurumamod.timeattack_faster", label, lapTime(millis),
                            "-" + lapTime(current.lapMillis() - millis)))
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /** 1 つのコースの 1 つのクラス（空ならクラスなし）の記録を消した（OP が K の画面から）。そのゴーストだけ消す。 */
    private static void deleteClassLapGhost(Minecraft minecraft, String dimension, String course, String raceClass) {
        if (playing != null && dimension.equals(currentDimension(minecraft))
                && course.equals(lapCourse) && raceClass.equals(lapClass)) {
            stopPlayback();
        }
        Path file = lapFile(minecraft, dimension, course, raceClass);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("タイムアタックのゴーストを消せませんでした: {}", file, e);
        }
    }

    /** クラスの記録を消したので、そのクラスのゴーストを全コースぶん消す。 */
    private static void deleteClassGhosts(Minecraft minecraft, String raceClass) {
        if (playing != null && raceClass.equals(lapClass)) {
            stopPlayback();
        }
        // クラスはサーバーに 1 組。記録は全部の次元から消えたので、ゴーストも全部の次元のフォルダから消す
        Path player = playerDir(minecraft);
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
    private static void deleteLapGhosts(Minecraft minecraft, String dimension, String course) {
        if (playing != null && dimension.equals(currentDimension(minecraft))
                && (course.isEmpty() || course.equals(lapCourse))) {
            stopPlayback();
        }
        Path world = dimDir(minecraft, dimension);
        if (course.isEmpty()) {
            deleteTree(world);
            return;
        }
        // クラスなしと、全クラスのそのコースのぶん
        try {
            Files.deleteIfExists(lapFile(minecraft, dimension, course, ""));
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
    private static void startLapGhost(Minecraft minecraft, String course, String raceClass, long targetBest) {
        Ghost ghost = null;
        if (ClientConfig.timeAttackGhost && minecraft.level != null) {
            Path file = lapFile(minecraft, course, raceClass);
            ghost = load(file);
            // 別の次元で取ったゴーストは使わない（消しもしない）。フォルダを次元で分けてあるので、ふつうは起きない
            if (ghost != null && !minecraft.level.dimension().location().toString().equals(ghost.dimension())) {
                ghost = null;
            }
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
        startPlayback(minecraft, ghost, Math.max(0, trail.size() - 1));
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
            Frame from = trail.get(i - 1);
            Frame to = trail.get(i);
            for (CourseLinesPacket.Gate gate : CourseRenderer.gates()) {
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

    private static Ghost toGhost(List<Frame> frames, CarEntity car, long lapMillis) {
        return new Ghost(car.getCarId(), car.getSpec(), car.getFitment(),
                car.level().dimension().location().toString(), lapMillis, List.copyOf(frames));
    }

    /** @param from 何ティック目から走らせるか（跨いでから合図が届くまでのぶん） */
    private static void startPlayback(Minecraft minecraft, Ghost ghost, int from) {
        stopPlayback();
        ClientLevel level = minecraft.level;
        from = Math.min(from, ghost.frames().size() - 1);
        Frame first = ghost.frames().get(from);
        CarEntity entity = new CarEntity(Kurumamod.CAR.get(), level);
        entity.becomeGhost(ghost.carId(), ghost.spec(), ghost.parts());
        entity.setId(GHOST_ENTITY_ID);
        entity.moveTo(first.x(), first.y(), first.z(), first.state().yaw(), 0.0F);
        entity.playGhostFrame(first.x(), first.y(), first.z(), first.state());
        level.putNonPlayerEntity(GHOST_ENTITY_ID, entity);

        playing = ghost;
        ghostEntity = entity;
        index = from + 1;
        finishHold = FINISH_HOLD_TICKS;
    }

    private static void stopPlayback() {
        if (ghostEntity != null && !ghostEntity.isRemoved()) {
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
    private static void share(Minecraft minecraft, Frame frame) {
        CarEntity car = drivenCar(minecraft);
        int riders = car == null ? 0 : car.getPassengers().size() - 1;
        if (riders <= 0 || playing == null) {
            stopSharing();
            return;
        }
        if (car.getId() != sharedCarId || riders != sharedRiders || --lookCooldown <= 0) {
            CompoundTag look = new CompoundTag();
            look.putString("Car", playing.carId().toString());
            look.put("Spec", CarSpecCodec.save(playing.spec()));
            look.put("Parts", playing.parts().save());
            send(GhostRidePacket.look(car.getId(), look));
            sharedCarId = car.getId();
            sharedRiders = riders;
            lookCooldown = LOOK_RESEND_TICKS;
        }
        send(GhostRidePacket.frame(car.getId(), frame.x(), frame.y(), frame.z(), frame.state()));
    }

    /** 助手席へ流すのをやめる（走り終えた・止めた・乗員がいなくなった）。 */
    private static void stopSharing() {
        if (sharedCarId >= 0) {
            send(GhostRidePacket.stop(sharedCarId));
        }
        sharedCarId = -1;
        sharedRiders = 0;
    }

    private static void send(GhostRidePacket packet) {
        if (Minecraft.getInstance().getConnection() != null) {
            KurumaNetwork.CHANNEL.sendToServer(packet);
        }
    }

    /**
     * 助手席の側。運転者の画面で走っているゴーストの 1 コマが届いた（{@code CarClientPackets#applyGhostRide}）。
     * 自分のゴーストとは別に 1 台出す。H メニューでゴーストを切っていれば出さない。
     */
    public static void applyRide(GhostRidePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || !ClientConfig.timeAttackGhost) {
            stopRide();
            return;
        }
        // 乗っている車のものだけ（降りた後に遅れて届いたものは捨てる）
        if (minecraft.player.getVehicle() == null || minecraft.player.getVehicle().getId() != packet.carEntityId()) {
            return;
        }
        switch (packet.kind()) {
            case GhostRidePacket.LOOK -> {
                // 見た目が変わった（別の車で取ったベストに替わった）なら出し直す
                if (rideGhost != null && !packet.look().equals(rideLook)) {
                    stopRide();
                }
                rideLook = packet.look();
                rideCarId = packet.carEntityId();
            }
            case GhostRidePacket.FRAME -> {
                if (rideLook == null || rideCarId != packet.carEntityId()) {
                    return;
                }
                if (rideGhost == null || rideGhost.isRemoved() || rideGhost.level() != level) {
                    ResourceLocation carId = ResourceLocation.tryParse(rideLook.getString("Car"));
                    if (carId == null) {
                        return;
                    }
                    CarEntity entity = new CarEntity(Kurumamod.CAR.get(), level);
                    try {
                        entity.becomeGhost(carId, CarSpecCodec.load(rideLook.getCompound("Spec")),
                                PartFitment.load(rideLook.getCompound("Parts")));
                    } catch (RuntimeException e) {
                        // <b>運転者のクライアントから届いたもので、サーバーは中身を見ていない。</b>壊れていても
                        // 同乗者の画面を落とさない。次の見た目が届くまで出さない
                        LOGGER.warn("同乗のゴーストの見た目を読めないので出しません", e);
                        rideLook = null;
                        return;
                    }
                    entity.setId(RIDE_GHOST_ENTITY_ID);
                    entity.moveTo(packet.x(), packet.y(), packet.z(), packet.state().yaw(), 0.0F);
                    level.putNonPlayerEntity(RIDE_GHOST_ENTITY_ID, entity);
                    rideGhost = entity;
                }
                rideGhost.playGhostFrame(packet.x(), packet.y(), packet.z(), packet.state());
                rideSilence = 0;
            }
            case GhostRidePacket.STOP -> stopRide();
            default -> {
            }
        }
    }

    /** 助手席の側。降りた・コマが途切れた・ゴーストを切ったら下げる。 */
    private static void tickRide(Minecraft minecraft) {
        if (rideGhost == null) {
            return;
        }
        boolean riding = minecraft.player != null && minecraft.player.getVehicle() != null
                && minecraft.player.getVehicle().getId() == rideCarId;
        if (!riding || ++rideSilence > RIDE_TIMEOUT_TICKS || !ClientConfig.timeAttackGhost
                || rideGhost.isRemoved() || rideGhost.level() != minecraft.level) {
            stopRide();
        }
    }

    private static void stopRide() {
        if (rideGhost != null && !rideGhost.isRemoved()) {
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
        return FMLPaths.CONFIGDIR.get().resolve("kurumamod").resolve("ghosts");
    }
    /**
     * タイムアタックのゴーストの置き場所。<b>ワールド（サーバー）ごと・コースごと。</b>
     *
     * <p>コースの名前はワールドごとに付けるので、名前だけで引くと別のワールドの同名のコースの
     * ゴーストが、何もない場所を走り出す。シングルはセーブのフォルダ名、マルチは接続先で分ける。</p>
     */
    private static Path lapFile(Minecraft minecraft, String course, String raceClass) {
        return lapFile(minecraft, currentDimension(minecraft), course, raceClass);
    }

    /** 次元を決めて引く（記録を消した知らせは、別の次元のものも届く）。 */
    private static Path lapFile(Minecraft minecraft, String dimension, String course, String raceClass) {
        Path base = dimDir(minecraft, dimension);
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


    /** クラスごとのゴーストを置くフォルダの名前（ワールドのフォルダの下）。 */
    private static final String CLASS_DIR = "class";

    /**
     * そのワールド（サーバー）のゴーストを置くフォルダ。
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
    private static Path worldDir(Minecraft minecraft) {
        return dimDir(minecraft, currentDimension(minecraft));
    }

    /** その次元のゴーストのフォルダ。次元の名前（minecraft:the_nether など）も、そのままではファイル名にしない。 */
    private static Path dimDir(Minecraft minecraft, String dimension) {
        return playerDir(minecraft).resolve(dimension != null ? fileKey(dimension) : "unknown");
    }

    /** いまいる次元の名前。ワールドに入っていなければ null。 */
    static String currentDimension(Minecraft minecraft) {
        return minecraft.level != null ? minecraft.level.dimension().location().toString() : null;
    }

    /** そのワールド（サーバー）の、いま入っているプレイヤーのフォルダ（次元のフォルダはこの下）。 */
    private static Path playerDir(Minecraft minecraft) {
        Path world;
        if (minecraft.getSingleplayerServer() != null) {
            world = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize()
                    .resolve("kurumamod").resolve("ghosts");
        } else {
            String address = minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().ip : null;
            world = serversDir().resolve(address != null ? safe(serverKey(address)) : "unknown");
        }
        // <b>プレイヤーごとに分ける。</b>分けないと、同じ PC から別のアカウントで入った人が相手のゴーストを読み、
        // 自分のベストと合わないので消してしまった
        return world.resolve(minecraft.player != null ? minecraft.player.getUUID().toString() : "unknown");
    }

    /** いま入っているワールド（サーバー）の名前。シングルはセーブのフォルダ名、マルチは接続先。 */
    static String worldKey(Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() != null) {
            Path root = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize();
            return "local-" + root.getFileName();
        }
        if (minecraft.getCurrentServer() != null) {
            return "server-" + serverKey(minecraft.getCurrentServer().ip);
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
     * 1 本を書く。NBT（圧縮）の中に、車の情報と、毎ティックの値を詰めたバイト列を入れる。
     * 毎ティックの値を NBT のリストにしないのは、1 ティックに 30 個近い数があって重いため。
     */
    private static boolean save(Path path, Ghost ghost) {
        try {
            Files.createDirectories(path.getParent());
            CompoundTag tag = new CompoundTag();
            tag.putInt("Version", FILE_VERSION);
            tag.putString("Car", ghost.carId().toString());
            tag.put("Spec", CarSpecCodec.save(ghost.spec()));
            tag.put("Parts", ghost.parts().save());
            tag.putString("Dimension", ghost.dimension());
            tag.putLong("LapMillis", ghost.lapMillis());
            tag.putInt("Frames", ghost.frames().size());
            tag.putByteArray("Data", encode(ghost.frames()));
            try (OutputStream out = Files.newOutputStream(path)) {
                NbtIo.writeCompressed(tag, out);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("ゴーストを保存できませんでした: {}", path, e);
            return false;
        }
    }

    private static Ghost load(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            CompoundTag tag = NbtIo.readCompressed(in);
            if (tag.getInt("Version") != FILE_VERSION) {
                LOGGER.warn("知らない版のゴーストです: {} (版 {})", path, tag.getInt("Version"));
                return null;
            }
            List<Frame> frames = decode(tag.getByteArray("Data"), tag.getInt("Frames"));
            if (frames.isEmpty()) {
                return null;
            }
            ResourceLocation car = ResourceLocation.tryParse(tag.getString("Car"));
            return new Ghost(car != null ? car : new ResourceLocation(Kurumamod.MODID, "car"),
                    CarSpecCodec.load(tag.getCompound("Spec")), PartFitment.load(tag.getCompound("Parts")),
                    tag.getString("Dimension"), tag.getLong("LapMillis"), frames);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ゴーストを読めませんでした: {}", path, e);
            return null;
        }
    }

    private static byte[] encode(List<Frame> frames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(frames.size() * 112);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (Frame frame : frames) {
                CarStatePacket s = frame.state();
                out.writeDouble(frame.x());
                out.writeDouble(frame.y());
                out.writeDouble(frame.z());
                out.writeFloat(s.steerAngle());
                out.writeFloat(s.speed());
                out.writeFloat(s.yaw());
                out.writeFloat(s.pitch());
                out.writeFloat(s.roll());
                for (int i = 0; i < Wheel.COUNT; i++) {
                    out.writeFloat(s.suspension()[i]);
                    out.writeFloat(s.wheelSpeed()[i]);
                    out.writeFloat(s.friction()[i]);
                }
                out.writeFloat(s.rpm());
                out.writeByte(s.gear());
                out.writeFloat(s.throttle());
                out.writeFloat(s.slipAngle());
                out.writeShort(s.surface());
                out.writeByte(s.lights());
                out.writeFloat(s.boost());
            }
        }
        return bytes.toByteArray();
    }

    private static List<Frame> decode(byte[] data, int count) throws IOException {
        List<Frame> frames = new ArrayList<>(count);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            for (int n = 0; n < count; n++) {
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float steer = in.readFloat();
                float speed = in.readFloat();
                float yaw = in.readFloat();
                float pitch = in.readFloat();
                float roll = in.readFloat();
                float[] suspension = new float[Wheel.COUNT];
                float[] wheelSpeed = new float[Wheel.COUNT];
                float[] friction = new float[Wheel.COUNT];
                for (int i = 0; i < Wheel.COUNT; i++) {
                    suspension[i] = in.readFloat();
                    wheelSpeed[i] = in.readFloat();
                    friction[i] = in.readFloat();
                }
                float rpm = in.readFloat();
                int gear = in.readByte();
                float throttle = in.readFloat();
                float slip = in.readFloat();
                int surface = in.readUnsignedShort();
                byte lights = in.readByte();
                float boost = in.readFloat();
                frames.add(new Frame(x, y, z, new CarStatePacket(GHOST_ENTITY_ID, steer, speed, yaw, pitch, roll,
                        suspension, wheelSpeed, friction, rpm, gear, throttle, slip, surface, lights, boost)));
            }
        }
        return frames;
    }

    // ------------------------------------------------------------------

    private static CarEntity drivenCar(Minecraft minecraft) {
        if (minecraft.player == null) {
            return null;
        }
        Entity vehicle = minecraft.player.getVehicle();
        return vehicle instanceof CarEntity car && car.getControllingPassenger() == minecraft.player ? car : null;
    }

    /** ラップタイムの書式。レースの表示と同じ 1:23.456。 */
    private static String lapTime(long millis) {
        return String.format("%d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000);
    }

    private static void message(Minecraft minecraft, Component text) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(text, false);
        }
    }
}
