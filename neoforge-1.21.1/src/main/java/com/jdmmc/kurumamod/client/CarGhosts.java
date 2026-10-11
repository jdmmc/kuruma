package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CarStatePacket;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
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
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
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
 * 記録は {@code config/kurumamod-ghosts/} に置き、誰にも送らない。</p>
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
@EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
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
    private static CarEntity trailCar;

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

    /** 車がティックする前に流し込む。lerpTo の位置へはその車のティックで寄る。 */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isPaused()) {
            tickPlayback(minecraft);
        }
    }

    /** 車がティックし終えて、送った状態が出揃ってから取る。 */
    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isPaused()) {
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
     * <p>周回が無効だった合図（取りこぼし・短すぎ）では何もしない。サーバーは測り直さずに
     * 元のスタートから測り続けるので、こちらも書き溜めを続ける。</p>
     */
    public static void onRaceEvent(RaceEventPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        switch (packet.kind()) {
            case LAP_STARTED -> {
                if (packet.totalLaps() == 0) {
                    beginLap(minecraft, packet.course());
                } else {
                    resetLap();
                }
            }
            case LAP_COMPLETED -> {
                if (packet.totalLaps() == 0) {
                    completeLap(minecraft, packet.course(), packet.millis(), packet.best());
                } else {
                    resetLap();
                }
            }
            case FINISHED, CLEARED -> resetLap();
            case BEST_CLEARED -> deleteLapGhosts(minecraft, packet.course());
            default -> {
            }
        }
    }

    private static void beginLap(Minecraft minecraft, String course) {
        trail.subList(0, crossingIndex()).clear();
        lapCourse = course;
        startLapGhost(minecraft, course);
    }

    /**
     * 1 周終わった。<b>ゴーストはサーバーのベストと同じ周にする</b>——ベストを更新した周
     * （{@code best}）だけを残す。手元のゴーストと速さを比べると、ベストを消した後も古い
     * ゴーストが残り、それより遅い新しいベストが記録されなくなる。
     */
    private static void completeLap(Minecraft minecraft, String course, long millis, boolean best) {
        int at = crossingIndex();
        if (best && course.equals(lapCourse) && at > 0 && trailCar != null) {
            Path file = lapFile(minecraft, course);
            Ghost current = load(file);
            Ghost ghost = toGhost(new ArrayList<>(trail.subList(0, at)), trailCar, millis);
            if (save(file, ghost)) {
                message(minecraft, (current == null || millis >= current.lapMillis()
                        ? Component.translatable("ghost.kurumamod.timeattack_new", course, lapTime(millis))
                        : Component.translatable("ghost.kurumamod.timeattack_faster", course, lapTime(millis),
                                "-" + lapTime(current.lapMillis() - millis)))
                        .withStyle(ChatFormatting.AQUA));
            }
        }
        trail.subList(0, at).clear();
        lapCourse = course;
        startLapGhost(minecraft, course);
    }

    /**
     * ベストを消したので、そのコース（空なら今のワールドの全コース）のゴーストも消す。
     * 走っている最中なら、いま出ているゴーストも引っ込める。
     */
    private static void deleteLapGhosts(Minecraft minecraft, String course) {
        if (playing != null && (course.isEmpty() || course.equals(lapCourse))) {
            stopPlayback();
        }
        Path target = lapFile(minecraft, course.isEmpty() ? "_" : course);
        try {
            if (!course.isEmpty()) {
                Files.deleteIfExists(target);
                return;
            }
            Path world = target.getParent();
            if (Files.isDirectory(world)) {
                try (var files = Files.list(world)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(EXTENSION)).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("タイムアタックのゴーストを消せませんでした: {}", target, e);
        }
    }

    private static void resetLap() {
        trail.clear();
        lapCourse = null;
        stopPlayback();
    }

    /**
     * そのコースのゴーストを走らせる。<b>跨いだ瞬間に揃える</b>——合図が届くまでに
     * 自分はもう数ティック進んでいるので、ゴーストもそのぶん進めた位置から始める。
     */
    private static void startLapGhost(Minecraft minecraft, String course) {
        Ghost ghost = null;
        if (ClientConfig.timeAttackGhost && minecraft.level != null) {
            ghost = load(lapFile(minecraft, course));
            if (ghost != null && !minecraft.level.dimension().location().toString().equals(ghost.dimension())) {
                ghost = null;
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
     * <p>どのゲートがどのコースのものかはクライアントには分からないが、合図が届く直前に
     * 跨いだスタートゲートは 1 本しかないので、いちばん新しい跨ぎを取れば足りる。
     * 見つからなければ（ゲートがまだ届いていない等）、いちばん新しい記録で区切る。</p>
     */
    private static int crossingIndex() {
        int last = trail.size() - 1;
        if (last <= 0) {
            return Math.max(last, 0);
        }
        int stop = Math.max(1, last - CROSSING_SEARCH_FRAMES);
        for (int i = last; i >= stop; i--) {
            Frame from = trail.get(i - 1);
            Frame to = trail.get(i);
            for (CourseLinesPacket.Gate gate : CourseRenderer.gates()) {
                if (gate.start() && crosses(from.x(), from.z(), to.x(), to.z(),
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
        level.addEntity(entity);

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
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("kurumamod-ghosts");
    }

    /**
     * タイムアタックのゴーストの置き場所。<b>ワールド（サーバー）ごと・コースごと。</b>
     *
     * <p>コースの名前はワールドごとに付けるので、名前だけで引くと別のワールドの同名のコースの
     * ゴーストが、何もない場所を走り出す。シングルはセーブのフォルダ名、マルチは接続先で分ける。</p>
     */
    private static Path lapFile(Minecraft minecraft, String course) {
        String world;
        if (minecraft.getSingleplayerServer() != null) {
            Path root = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize();
            world = "local-" + root.getFileName();
        } else if (minecraft.getCurrentServer() != null) {
            world = "server-" + minecraft.getCurrentServer().ip;
        } else {
            world = "unknown";
        }
        return dir().resolve("timeattack").resolve(safe(world)).resolve(safe(course) + EXTENSION);
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
            CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            if (tag.getInt("Version") != FILE_VERSION) {
                LOGGER.warn("知らない版のゴーストです: {} (版 {})", path, tag.getInt("Version"));
                return null;
            }
            List<Frame> frames = decode(tag.getByteArray("Data"), tag.getInt("Frames"));
            if (frames.isEmpty()) {
                return null;
            }
            ResourceLocation car = ResourceLocation.tryParse(tag.getString("Car"));
            return new Ghost(car != null ? car : ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "car"),
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
