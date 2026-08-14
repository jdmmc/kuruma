package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.network.RaceStandingsPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ラップ計測とレースの進行。<b>サーバー側だけで動く。</b>
 *
 * <p>物理はクライアント権威だが、車の位置は毎ティック {@code ServerboundMoveVehiclePacket} で
 * サーバーへ届いている。つまりサーバーは 20Hz で正しい軌跡を持っているので、ここで判定できる。
 * 複数人のレースでは<b>裁定者が 1 つであること</b>が要るので、精度以前にここで測るのが正しい。</p>
 *
 * <p><b>すべてワールドごとに持つ。</b>複数ワールドを運用しているサーバーでは、レースの状態を
 * サーバー全体で 1 つにすると次の 3 つが壊れる:</p>
 * <ul>
 *   <li>カウントダウンを全員へ配ると、<b>他ワールドで走っている人まで操作を奪われる</b></li>
 *   <li>ワールド A のレース中にワールド B で始めると、A のレースが黙って上書きされる</li>
 *   <li>同名のコースがあると、<b>別ワールドの走者が同じレースの参加者として計測される</b></li>
 * </ul>
 *
 * <p>あわせて、周回状態は<b>ワールド移動で捨てる</b>。持ち越すと、移動前の座標が「前ティックの
 * 位置」として残り、ワールドをまたぐ巨大な線分が 1 回できてしまう。</p>
 *
 * <p><b>タイムの信頼性はクライアントの信頼性を超えない。</b>物理も位置もクライアントが
 * 決めているので、改造クライアントは速度を偽装できる。身内で走るぶんには問題ないが、
 * 公開サーバーのランキングとして使うなら別の仕組みが要る。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID)
public final class RaceManager {

    /** 1 ティックの長さ [ms]。交差した位置の比率と掛けて、ティックの中を補間する。 */
    private static final double TICK_MILLIS = 50.0;

    /** 順位表を配る間隔 [ティック]。 */
    private static final int STANDINGS_INTERVAL = 10;

    /** スタート信号のライトの数。 */
    public static final int LIGHT_COUNT = 5;
    /** ライトが 1 つ増えるまでのティック数。 */
    private static final int LIGHT_INTERVAL = 20;
    /** 全灯してから消えるまでの幅 [ティック]。ここが読めないから飛び出しを抑えられる。 */
    private static final int HOLD_MIN = 20;
    private static final int HOLD_MAX = 50;

    /**
     * スタートしてからレースを締められるようになるまでの猶予 [ティック]。
     *
     * <p>締める条件は「そこに居る参加者が全員ゴールした」なので、参加者が一時的に
     * 読み込み中や次元移動中に見えるだけで締まってしまわないようにする保険。
     * <b>降りただけの人は待つ</b>（{@link #allFinished}）ので、号砲の直後に締まることは無い。</p>
     */
    private static final int START_GRACE_TICKS = 100;

    /** リザルトを出してからセッションを畳むまで [ティック]。 */
    private static final int RESULT_TICKS = 300;

    /** 周回状態。ワールドごと・プレイヤーごと。 */
    private static final Map<ResourceKey<Level>, Map<UUID, LapState>> STATES = new HashMap<>();
    /** 進行中のレース。ワールドごとに 1 つ。 */
    private static final Map<ResourceKey<Level>, Session> SESSIONS = new HashMap<>();

    private RaceManager() {
    }

    // ------------------------------------------------------------------
    // 進行
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level);
        }
    }

    /**
     * サーバーが止まったら全部捨てる。
     *
     * <p><b>この 2 つは {@code static} なので、放っておくとプロセスが生きている限り残る。</b>
     * シングルプレイでワールドを抜けて別のワールドへ入ると、次元キーはどちらも
     * {@code minecraft:overworld} なので<b>前のワールドのレースがそのまま生き続ける</b>——
     * {@code /kuruma race open} が「すでに開催中です」で弾かれ、ゲートは常時表示になり、
     * 誰も参加していないセッションの順位表が配られ続ける。</p>
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
        SESSIONS.clear();
    }

    /**
     * ワールド移動では周回状態を捨てる。
     *
     * <p>残すと移動前の座標が「前ティックの位置」として使われ、ワールドをまたぐ
     * 巨大な線分が 1 回できる。それがたまたま線と交差すればラップが成立してしまう。</p>
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        dropOut(event.getEntity(), event.getFrom());
        // 捨てたことは<b>必ず伝える</b>。伝えないと画面の時計だけが回り続け、
        // サーバーには周回状態が無いので /kuruma timeattack reset が
        // 「計測していません」を返す——画面とサーバーが食い違う
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player, RaceEventPacket.cleared());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        dropOut(event.getEntity(), event.getEntity().level().dimension());
    }

    /**
     * 走行状態を捨て、レース中ならリタイアとして知らせる。
     *
     * <p>エントリー名簿からは<b>消さない</b>。リザルトに「完走していない人」として
     * 残す方が、誰が出走していたか分かる。</p>
     */
    private static void dropOut(net.minecraft.world.entity.player.Player player,
                                ResourceKey<Level> from) {
        for (Map<UUID, LapState> byPlayer : STATES.values()) {
            byPlayer.remove(player.getUUID());
        }
        Session session = SESSIONS.get(from);
        if (session == null || !session.started || session.showingResult
                || !session.entrants.contains(player.getUUID())
                || session.finishOrder.containsKey(player.getUUID())) {
            return;
        }
        ServerLevel level = player.getServer() == null ? null : player.getServer().getLevel(from);
        if (level != null) {
            broadcast(level, Component.translatable("race.kurumamod.retired",
                    player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
        }
    }

    private static void tickLevel(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        Map<UUID, LapState> states = states(level);

        if (session != null) {
            tickCountdown(level, session, states);
        }

        for (ServerPlayer player : level.players()) {
            if (player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == player) {
                trackCar(level, session, states, player, car);
            } else {
                dismount(session, states, player);
            }
        }

        if (session != null) {
            closeIfDone(level, session, states);
            // リザルトはしばらく出したら自分で引っ込む。主催が居なくなっても次のレースを開ける
            if (session.showingResult
                    && level.getServer().getTickCount() - session.resultTick >= RESULT_TICKS) {
                cleanup(level, session);
                return;
            }
        }

        // 一覧は毎ティック配るほどのものではない
        if (session != null && level.getServer().getTickCount() % STANDINGS_INTERVAL == 0) {
            if (!session.started) {
                broadcastEntry(level, session);
            } else if (!session.showingResult) {
                broadcastStandings(level, session, states);
            }
        }
    }

    private static Map<UUID, LapState> states(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), key -> new HashMap<>());
    }

    /**
     * 車から降りた人の扱い。
     *
     * <p>タイムアタックは<b>やめたものとして捨てる</b>（乗り直したところから測り直す）。
     * だがレースの参加者は降りても外れない——ひっくり返して乗り直すたびに周回と順位が
     * 消えては競技にならない。<b>抜けるのは {@code /kuruma race leave} だけ。</b></p>
     *
     * <p>残すのは周回状態だけで、<b>「前ティックの位置」は捨てる</b>。降りて歩いた先で
     * 乗り直すと、その間の移動がまるごと 1 本の線分になり、通り道の線を跨いだことに
     * なってしまう。</p>
     */
    private static void dismount(Session session, Map<UUID, LapState> states, ServerPlayer player) {
        LapState state = states.get(player.getUUID());
        if (state != null && isRacer(session, player, state.course)) {
            state.hasLast = false;
            return;
        }
        states.remove(player.getUUID());
        // 計測していたぶんだけ、捨てたことをクライアントへ伝える。<b>伝えないと画面の時計が
        // 回り続ける</b>。降りるのは毎ティック通る道なので、測っていなかった人には送らない
        if (state != null && (state.timing || state.finished)) {
            send(player, RaceEventPacket.cleared());
        }
    }

    private static void tickCountdown(ServerLevel level, Session session, Map<UUID, LapState> states) {
        if (session.countdown < 0) {
            return;
        }
        session.countdown++;
        int lights = Math.min(LIGHT_COUNT, session.countdown / LIGHT_INTERVAL);
        if (session.countdown < LIGHT_COUNT * LIGHT_INTERVAL) {
            broadcastCountdown(level, session, lights, true);
            return;
        }
        if (session.countdown < LIGHT_COUNT * LIGHT_INTERVAL + session.holdTicks) {
            broadcastCountdown(level, session, LIGHT_COUNT, true);
            return;
        }
        // 消灯＝スタート
        session.countdown = -1;
        session.started = true;
        session.startedTick = level.getServer().getTickCount();
        broadcastCountdown(level, session, 0, false);
        // 参加者の計測だけ捨てる。同じワールドでタイムアタック中の人は巻き込まない
        session.entrants.forEach(states::remove);
        broadcast(level, Component.translatable("race.kurumamod.go").withStyle(ChatFormatting.GREEN));
    }

    /** カウントダウンは<b>参加者にだけ</b>。関係ない人の操作を奪わない。 */
    private static void broadcastCountdown(ServerLevel level, Session session,
                                           int lights, boolean frozen) {
        toEntrants(level, session, RaceEventPacket.countdown(lights, frozen));
    }

    /** 参加者にだけ配る。 */
    private static void toEntrants(ServerLevel level, Session session, Object packet) {
        for (UUID id : session.entrants) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null) {
                KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    /** そのワールドにいる人にだけ配る。他ワールドの走行を邪魔しないため。 */
    private static void toLevel(ServerLevel level, Object packet) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), packet);
    }

    private static void broadcast(ServerLevel level, Component message) {
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(message);
        }
    }

    // ------------------------------------------------------------------
    // 横断判定
    // ------------------------------------------------------------------

    private static void trackCar(ServerLevel level, Session session, Map<UUID, LapState> states,
                                 ServerPlayer player, CarEntity car) {
        RaceData data = RaceData.get(level);

        LapState state = states.get(player.getUUID());
        // どのコースを走るかは、あくまで「跨いだ線」で決まる。レース中だからといって
        // そのワールドの全員をレースのコースへ乗せてはいけない。1 ワールドに複数コースが
        // あると、別コースをタイムアタックしている人まで参加者にされてしまう
        String courseName = state != null ? state.course : null;
        if (courseName == null) {
            courseName = findCourseCrossed(data, car, state);
            if (courseName == null) {
                rememberPosition(states, player, car, null);
                return;
            }
        }

        Course course = data.course(courseName).orElse(null);
        if (course == null || !course.isReady()) {
            return;
        }
        if (state == null || !courseName.equals(state.course)) {
            state = new LapState(courseName);
            states.put(player.getUUID(), state);
            state.lastX = car.xOld;
            state.lastZ = car.zOld;
            state.hasLast = true;
        }

        if (!state.hasLast) {
            state.lastX = car.getX();
            state.lastZ = car.getZ();
            state.hasLast = true;
            return;
        }

        double fromX = state.lastX;
        double fromZ = state.lastZ;
        double toX = car.getX();
        double toZ = car.getZ();
        state.lastX = toX;
        state.lastZ = toZ;

        double now = level.getServer().getTickCount() * TICK_MILLIS;

        // チェックポイントは順番に。飛ばした周回はラップとして認めない
        if (state.timing && state.nextCheckpoint < course.checkpointCount()) {
            CourseLine checkpoint = course.checkpoint(state.nextCheckpoint);
            double at = checkpoint.crossingFraction(fromX, fromZ, toX, toZ, car.getY());
            if (at >= 0.0) {
                double gateAt = now - TICK_MILLIS + at * TICK_MILLIS;
                int index = state.nextCheckpoint;
                state.nextCheckpoint++;
                state.passGate(gateAt);
                sendSplit(player, data, course, state, index,
                        (long) (gateAt - state.lapStartMillis));
            }
        }

        CourseLine start = course.start();
        double fraction = start.crossingFraction(fromX, fromZ, toX, toZ, car.getY());
        if (fraction < 0.0) {
            return;
        }
        double crossedAt = now - TICK_MILLIS + fraction * TICK_MILLIS;

        if (state.finished) {
            // 走りきった人。ゴール後もコースを走り続けられるが、周回は増やさない。
            // ここを素通りさせると beginLap がラップ 1 から測り直し、リザルトの土台である
            // 通過時刻と総時間まで消える
            return;
        }
        if (!state.timing) {
            beginLap(player, session, course, state, crossedAt);
            return;
        }
        long lapMillis = (long) (crossedAt - state.lapStartMillis);
        if (lapMillis < Course.MIN_LAP_MILLIS || state.nextCheckpoint < course.checkpointCount()) {
            // 短すぎるラップ、あるいはチェックポイントの取りこぼし。周回として認めない。
            // <b>黙って落としてはいけない</b>——走っている本人には「1 周したのにカウントが
            // 増えない」としか見えず、規定より 1 周多く走らされた理由が分からない
            voidLap(player, course, state);
            return;
        }
        completeLap(level, session, states, player, data, course, state, lapMillis, crossedAt);
    }

    /** どのコースも走っていないとき、跨いだスタートラインからコースを決める。 */
    private static String findCourseCrossed(RaceData data, CarEntity car, LapState state) {
        if (state == null || !state.hasLast) {
            return null;
        }
        for (Course course : data.courses()) {
            CourseLine start = course.start();
            if (start != null && start.crossingFraction(
                    state.lastX, state.lastZ, car.getX(), car.getZ(), car.getY()) >= 0.0) {
                return course.name();
            }
        }
        return null;
    }

    private static void rememberPosition(Map<UUID, LapState> states, ServerPlayer player,
                                         CarEntity car, String course) {
        LapState state = states.computeIfAbsent(player.getUUID(), key -> new LapState(course));
        state.lastX = car.getX();
        state.lastZ = car.getZ();
        state.hasLast = true;
    }

    /** 参加者がレース対象のコースを走っているなら規定周回数、そうでなければ 0（タイムアタック）。 */
    private static int lapTarget(Session session, ServerPlayer player, String course) {
        return isRacer(session, player, course) ? session.laps : 0;
    }

    /** その人がこのレースの参加者として、このコースを走っているか。 */
    private static boolean isRacer(Session session, ServerPlayer player, String course) {
        return session != null && session.started
                && session.course.equals(course)
                && session.entrants.contains(player.getUUID());
    }

    /**
     * 区間タイムと自己ベストとの差を知らせる。
     *
     * <p>比較相手はベスト走行の同じ地点の通過時刻。<b>チェックポイントの数が変わっていたら
     * 比べない</b>——同じ番号でも別の場所を指していることになるため。</p>
     */
    private static void sendSplit(ServerPlayer player, RaceData data, Course course,
                                  LapState state, int index, long elapsed) {
        if (index < state.splits.length) {
            state.splits[index] = elapsed;
        }
        long[] best = data.bestSplits(player.getUUID(), course.name());
        boolean hasDelta = best != null && best.length == course.checkpointCount()
                && index < best.length && best[index] > 0L;
        send(player, RaceEventPacket.split(index + 1, course.checkpointCount() + 1, elapsed,
                hasDelta ? elapsed - best[index] : 0L, hasDelta));
    }

    /**
     * 周回として認めなかったことを知らせる。
     *
     * <p>チェックポイントの通過数は<b>捨てない</b>。取りこぼした 1 本をこの先で通れば、
     * 次にラインを通ったところで周回が成立する（＝ペナルティは 1 周ぶん）。</p>
     */
    private static void voidLap(ServerPlayer player, Course course, LapState state) {
        send(player, RaceEventPacket.lapVoid(state.nextCheckpoint, course.checkpointCount() + 1));
        if (state.nextCheckpoint < course.checkpointCount()) {
            // 画面の表示は数秒で消えるので、後から読み返せるチャットにも残す
            player.sendSystemMessage(Component.translatable("race.kurumamod.lap_void_chat",
                    state.nextCheckpoint, course.checkpointCount())
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static void beginLap(ServerPlayer player, Session session, Course course,
                                 LapState state, double crossedAt) {
        state.timing = true;
        state.lap = 1;
        state.lapStartMillis = crossedAt;
        state.raceStartMillis = crossedAt;
        state.nextCheckpoint = 0;
        state.splits = new long[course.checkpointCount()];
        state.progress = 0;
        state.gateTimes.clear();
        state.gateTimes.put(0, crossedAt);
        send(player, RaceEventPacket.lapStarted(state.lap, lapTarget(session, player, state.course),
                course.checkpointCount() + 1));
    }

    private static void completeLap(ServerLevel level, Session session, Map<UUID, LapState> states,
                                    ServerPlayer player, RaceData data, Course course,
                                    LapState state, long lapMillis, double crossedAt) {
        // 差を出す相手は「更新する前の」ベスト。取り込んでから引くと必ず 0 になる
        long previousBest = data.bestLap(player.getUUID(), course.name());
        boolean best = data.recordLap(player.getUUID(), player.getGameProfile().getName(),
                course.name(), lapMillis, state.splits);
        state.passGate(crossedAt);
        state.lastLapMillis = lapMillis;
        state.lapStartMillis = crossedAt;
        state.nextCheckpoint = 0;
        state.splits = new long[course.checkpointCount()];
        long bestMillis = data.bestLap(player.getUUID(), course.name());
        long delta = lapMillis - previousBest;
        boolean hasDelta = previousBest > 0L;

        boolean racing = isRacer(session, player, course.name());
        if (racing && state.lap >= session.laps) {
            int place = session.finishOrder.size() + 1;
            session.finishOrder.put(player.getUUID(), place);
            state.timing = false;
            state.finished = true;
            state.totalMillis = (long) (crossedAt - state.raceStartMillis);
            send(player, RaceEventPacket.finished(place, lapMillis, bestMillis, delta, hasDelta,
                    state.totalMillis));
            broadcast(level, Component.translatable("race.kurumamod.finished",
                    player.getDisplayName(), place, format(state.totalMillis)));
            // 参加者が全員ゴールしたらリザルト
            if (allFinished(level, session)) {
                broadcastResult(level, session, states);
                broadcast(level, Component.translatable("race.kurumamod.race_over")
                        .withStyle(ChatFormatting.GOLD));
            }
            return;
        }

        state.lap++;
        send(player, RaceEventPacket.lapCompleted(state.lap, racing ? session.laps : 0,
                lapMillis, bestMillis, best, delta, hasDelta));
    }

    /**
     * 参加者が全員ゴールしたか。
     *
     * <p><b>そこに居る参加者は、車に乗っていなくても待つ。</b>降りただけで見捨てると、
     * ひっくり返した車を立て直している間にレースが終わってしまう。<b>自分から抜けるには
     * {@code /kuruma race leave}</b>（名簿から消える）。</p>
     *
     * <p>待たないのは<b>もうそこに居ない人</b>だけ——落ちた人と、別のワールドへ移った人。
     * 待ち続けるとレースが自動で終わらなくなる。</p>
     */
    private static boolean allFinished(ServerLevel level, Session session) {
        for (UUID id : session.entrants) {
            if (session.finishOrder.containsKey(id)) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != level) {
                continue;   // 落ちた・別のワールドへ行った
            }
            return false;   // まだレースの中にいる
        }
        return true;
    }

    /**
     * 走るのをやめた人がいて、その結果もう全員そろっているなら締める。
     *
     * <p>最後の 1 人が降りた・落ちた場合、ゴールを合図に締める経路では拾えない。
     * <b>1 人もゴールしていなくても締める</b>——全員が車から降りた、あるいは全員が落ちた
     * レースは、待っていても誰もゴールしないため。</p>
     */
    private static void closeIfDone(ServerLevel level, Session session, Map<UUID, LapState> states) {
        if (!session.started || session.showingResult) {
            return;
        }
        // 号砲の直後は待つ。まだ乗り込んでいない状態は「全員が脱落した」と見分けがつかない
        if (level.getServer().getTickCount() - session.startedTick < START_GRACE_TICKS) {
            return;
        }
        if (!allFinished(level, session)) {
            return;
        }
        if (session.entrants.isEmpty()) {
            // 全員が抜けた。並べる相手がいないのでリザルトは出さない
            cleanup(level, session);
            return;
        }
        broadcastResult(level, session, states);
        broadcast(level, Component.translatable("race.kurumamod.race_over")
                .withStyle(ChatFormatting.GOLD));
    }

    /**
     * セッションを畳んで表示を消す。
     *
     * <p>リザルトを出しきったとき（自動）と、主催がもう一度 {@code stop} を撃ったとき
     * （手動）の両方から呼ぶ。</p>
     */
    private static void cleanup(ServerLevel level, Session session) {
        SESSIONS.remove(level.dimension());
        session.entrants.forEach(states(level)::remove);
        toEntrants(level, session, RaceEventPacket.cleared());
        toEntrants(level, session,
                new RaceStandingsPacket(RaceStandingsPacket.Phase.ENTRY, "", 0, List.of()));
        RaceSync.sendToAll(level.getServer());
    }

    private static void send(ServerPlayer player, RaceEventPacket packet) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    // ------------------------------------------------------------------
    // 順位表
    // ------------------------------------------------------------------

    /** 受付中は名前だけ並べて配る。エントリーした人がその場で分かるように。 */
    private static void broadcastEntry(ServerLevel level, Session session) {
        List<RaceStandingsPacket.Row> rows = new ArrayList<>();
        for (String name : entrants(level)) {
            rows.add(new RaceStandingsPacket.Row(name, 0, 0L, false, false, false));
        }
        toLevel(level, new RaceStandingsPacket(RaceStandingsPacket.Phase.ENTRY,
                session.course, session.laps, rows));
    }

    /**
     * リザルトを組んで配る。
     *
     * <p>完走した人を順位順に、そのあと完走していない人を進んだ地点の多い順。
     * 先頭は総時間、以降は先頭との差。</p>
     */
    private static void broadcastResult(ServerLevel level, Session session, Map<UUID, LapState> states) {
        List<RaceStandingsPacket.Row> rows = new ArrayList<>();
        long winner = 0L;
        for (UUID id : session.finishOrder.keySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            LapState state = states.get(id);
            if (player == null || state == null) {
                continue;
            }
            boolean first = rows.isEmpty();
            if (first) {
                winner = state.totalMillis;
            }
            rows.add(new RaceStandingsPacket.Row(player.getGameProfile().getName(),
                    state.lap, first ? state.totalMillis : state.totalMillis - winner, first, true, true));
        }
        // 完走していない人。強制終了したときに出る
        List<UUID> rest = new ArrayList<>(session.entrants);
        rest.removeAll(session.finishOrder.keySet());
        rest.sort((a, b) -> Integer.compare(progressOf(states, b), progressOf(states, a)));
        for (UUID id : rest) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null) {
                continue;
            }
            LapState state = states.get(id);
            rows.add(new RaceStandingsPacket.Row(player.getGameProfile().getName(),
                    state != null ? Math.max(0, state.lap - 1) : 0, 0L, false, false,
                    state != null && state.progress > 0));
        }
        session.showingResult = true;
        session.resultTick = level.getServer().getTickCount();
        toEntrants(level, session, new RaceStandingsPacket(RaceStandingsPacket.Phase.RESULT,
                session.course, session.laps, rows));
    }

    private static int progressOf(Map<UUID, LapState> states, UUID id) {
        LapState state = states.get(id);
        return state == null ? -1 : state.progress;
    }

    /**
     * 順位表を組んで配る。
     *
     * <p>順位は<b>通過した地点の数</b>の多い順、同数なら<b>先に通った方が前</b>。
     * 線しか無くても、これで「どこを走っているか」を正しく順序付けられる。</p>
     *
     * <p>先頭は経過時間、以降は先頭との差。差は<b>同じ地点を通過した時刻の差</b>で出すので、
     * 走っている場所が違っても意味のある値になる。</p>
     */
    private static void broadcastStandings(ServerLevel level, Session session,
                                          Map<UUID, LapState> states) {
        // エントリーした人を、走り出した人とまだの人に分ける。名簿の順に見るので、
        // 同じワールドで別コースをタイムアタックしている人や見物人は最初から入らない
        List<ServerPlayer> racers = new ArrayList<>();
        List<ServerPlayer> waiting = new ArrayList<>();
        for (UUID id : session.entrants) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != level) {
                continue;
            }
            LapState state = states.get(id);
            if (state != null && (state.timing || state.finished)
                    && session.course.equals(state.course)) {
                racers.add(player);
            } else {
                // まだスタートラインを跨いでいない人。<b>出さないと「誰が出走しているのか」が
                // スタート直後だけ分からなくなる</b>
                waiting.add(player);
            }
        }
        if (racers.isEmpty() && waiting.isEmpty()) {
            return;
        }
        racers.sort((a, b) -> {
            LapState sa = states.get(a.getUUID());
            LapState sb = states.get(b.getUUID());
            if (sa.progress != sb.progress) {
                return Integer.compare(sb.progress, sa.progress);
            }
            return Double.compare(sa.gateTimes.getOrDefault(sa.progress, 0.0),
                    sb.gateTimes.getOrDefault(sb.progress, 0.0));
        });

        double nowMillis = level.getServer().getTickCount() * TICK_MILLIS;
        List<RaceStandingsPacket.Row> rows = new ArrayList<>();
        LapState leader = racers.isEmpty() ? null : states.get(racers.get(0).getUUID());
        for (int i = 0; i < racers.size(); i++) {
            ServerPlayer player = racers.get(i);
            LapState state = states.get(player.getUUID());
            long value;
            if (state.finished) {
                // ゴールした人は総時間で止める。<b>ここを経過時間のままにすると、
                // 走り終わった人の数字が最後まで増え続ける</b>（先頭は必ずゴール済みなので
                // いちばん目につく）
                value = state.totalMillis;
            } else if (i == 0) {
                value = (long) (nowMillis - state.raceStartMillis);
            } else {
                // 同じ地点での時刻差。先頭がそこを通った時刻が要る
                Double mine = state.gateTimes.get(state.progress);
                Double theirs = leader.gateTimes.get(state.progress);
                value = mine != null && theirs != null ? (long) (mine - theirs) : 0L;
            }
            // 走行中は「終えた周」＝今の周 - 1。ゴールした人だけは規定周を走りきっているので、
            // そのまま出す（引くと最後の 1 周が消える）
            rows.add(new RaceStandingsPacket.Row(player.getGameProfile().getName(),
                    state.finished ? state.lap : state.lap - 1, value,
                    i == 0, state.finished, true));
        }
        for (ServerPlayer player : waiting) {
            rows.add(new RaceStandingsPacket.Row(
                    player.getGameProfile().getName(), 0, 0L, false, false, false));
        }
        toEntrants(level, session, new RaceStandingsPacket(RaceStandingsPacket.Phase.RUNNING,
                session.course, session.laps, rows));
    }

    // ------------------------------------------------------------------
    // コマンドから呼ばれる口
    // ------------------------------------------------------------------

    /**
     * エントリーを受け付ける。まだ走らない。
     *
     * <p>告知は<b>サーバー全体</b>へ流す。別のワールドにいる人が気づいて集まれるように、
     * どのワールドかも一緒に出す。</p>
     *
     * @return すでにそのワールドで開催中なら false
     */
    public static boolean openRace(ServerLevel level, Course course, int laps, ServerPlayer host) {
        if (SESSIONS.containsKey(level.dimension())) {
            return false;
        }
        Session session = new Session(course.name(), laps);
        session.host = host.getUUID();
        session.hostName = host.getGameProfile().getName();
        SESSIONS.put(level.dimension(), session);
        RaceSync.sendToAll(level.getServer());
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("race.kurumamod.opened", session.hostName, course.name(), laps,
                        level.dimension().location().toString()).withStyle(ChatFormatting.YELLOW), false);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("race.kurumamod.how_to_join").withStyle(ChatFormatting.GRAY), false);
        return true;
    }

    /** 開催した本人か、OP なら操作してよい。 */
    public static boolean canManage(ServerPlayer player) {
        Session session = SESSIONS.get(player.level().dimension());
        if (session == null) {
            return false;
        }
        if (player.getUUID().equals(session.host) || player.hasPermissions(2)) {
            return true;
        }
        // 主催が落ちたままだとレースを畳めなくなるので、その場合は参加者に任せる
        boolean hostOnline = session.host != null
                && player.getServer() != null
                && player.getServer().getPlayerList().getPlayer(session.host) != null;
        return !hostOnline && session.entrants.contains(player.getUUID());
    }

    /** 受付中のレースのコース名。無ければ null。 */
    public static String openCourse(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        return session == null || session.started ? null : session.course;
    }

    /** {@link #join} の結果。<b>断る理由ごとに分ける</b>——「入れません」では次の手が分からない。 */
    public enum JoinResult {
        /** そのワールドでレースを受け付けていない。 */
        NO_RACE,
        /** もう始まっている。次のレースを待つしかない。 */
        ALREADY_STARTED,
        /** 受付中なのは別のコース。 */
        WRONG_COURSE,
        /** すでに入っている。 */
        ALREADY_JOINED,
        /** 入れた。 */
        JOINED
    }

    /**
     * エントリーする。
     *
     * @param course 確かめたいコース名。null なら受付中のものへそのまま入る
     */
    public static JoinResult join(ServerPlayer player, String course) {
        Session session = SESSIONS.get(player.level().dimension());
        if (session == null) {
            return JoinResult.NO_RACE;
        }
        if (session.started) {
            return JoinResult.ALREADY_STARTED;
        }
        if (course != null && !session.course.equals(course)) {
            return JoinResult.WRONG_COURSE;
        }
        if (!session.entrants.add(player.getUUID())) {
            return JoinResult.ALREADY_JOINED;
        }
        broadcast((ServerLevel) player.level(), Component.translatable("race.kurumamod.joined_announce",
                player.getGameProfile().getName(), session.entrants.size()));
        return JoinResult.JOINED;
    }

    /** {@link #leave} の結果。 */
    public enum LeaveResult {
        /** そもそも入っていない。 */
        NOT_ENTERED,
        /** 受付中に取り消した。 */
        LEFT,
        /** 走行中に抜けた＝リタイア。 */
        RETIRED
    }

    /**
     * エントリーを取り消す。
     *
     * <p><b>走行中に抜ける唯一の道。</b>車から降りただけでは外れない（そうしないと、
     * ひっくり返した車を立て直すたびに周回が消える）。抜けた人が最後の 1 人だったなら、
     * ここでレースを締める。</p>
     */
    public static LeaveResult leave(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        Session session = SESSIONS.get(level.dimension());
        if (session == null || !session.entrants.remove(player.getUUID())) {
            return LeaveResult.NOT_ENTERED;
        }
        Map<UUID, LapState> states = states(level);
        states.remove(player.getUUID());
        send(player, RaceEventPacket.cleared());
        if (!session.started || session.showingResult) {
            broadcast(level, Component.translatable("race.kurumamod.left_announce",
                    player.getGameProfile().getName(), session.entrants.size()));
            return LeaveResult.LEFT;
        }
        broadcast(level, Component.translatable("race.kurumamod.retired",
                player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
        closeIfDone(level, session, states);
        return LeaveResult.RETIRED;
    }

    /** エントリーしている人の名前。 */
    public static List<String> entrants(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        List<String> names = new ArrayList<>();
        if (session == null) {
            return names;
        }
        for (UUID id : session.entrants) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            names.add(player != null ? player.getGameProfile().getName() : id.toString());
        }
        return names;
    }

    /** {@link #beginCountdown} の結果。 */
    public enum StartResult {
        /** 受付中のレースが無い。 */
        NO_RACE,
        /** もう始まっている。 */
        ALREADY_STARTED,
        /** エントリーが 1 人もいない。 */
        NO_ENTRANTS,
        /** カウントダウンを始めた。 */
        STARTED
    }

    /** カウントダウンを始める。 */
    public static StartResult beginCountdown(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        if (session == null) {
            return StartResult.NO_RACE;
        }
        if (session.started || session.countdown >= 0) {
            return StartResult.ALREADY_STARTED;
        }
        if (session.entrants.isEmpty()) {
            return StartResult.NO_ENTRANTS;
        }
        session.countdown = 0;
        session.holdTicks = HOLD_MIN + level.random.nextInt(HOLD_MAX - HOLD_MIN + 1);
        session.entrants.forEach(states(level)::remove);
        broadcast(level, Component.translatable("race.kurumamod.starting",
                session.course, session.laps).withStyle(ChatFormatting.YELLOW));
        return StartResult.STARTED;
    }

    /** 開催した人の名前。レースが無ければ空文字。 */
    public static String hostName(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        return session == null ? "" : session.hostName;
    }

    /** 受付中のレースがあるか。 */
    public static boolean hasSession(ServerLevel level) {
        return SESSIONS.containsKey(level.dimension());
    }

    /**
     * レースを終える。
     *
     * <p>走行中に強制終了したときも<b>リザルトは出す</b>。完走していない人は周回数だけ並ぶ。
     * 表示を消したいときはもう一度呼ぶ（放っておいても {@link #RESULT_TICKS} で自分で消える）。</p>
     */
    public static void stopRace(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        if (session == null) {
            return;
        }
        Map<UUID, LapState> states = states(level);
        if (session.started && !session.showingResult) {
            broadcastResult(level, session, states);
            broadcast(level, Component.translatable("race.kurumamod.race_over")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }
        cleanup(level, session);
    }

    /** {@link #resetTimeAttack} の結果。 */
    public enum ResetResult {
        /** そもそも計測していなかった。 */
        NOT_TIMING,
        /** レースの参加者として走っている。勝手に消させない。 */
        RACING,
        /** 取り消した。 */
        RESET
    }

    /**
     * 計測中のタイムアタックを取り消す。
     *
     * <p>周回状態をまるごと捨てるので、<b>次にスタートラインを跨いだところから測り直す</b>。
     * 車から降りたときと同じ扱いで、そこは {@link #tickLevel} が
     * {@code states.remove} でやっているのと同じこと。ミスした周を捨てて入り直すために、
     * わざわざ降りなくてよくする。</p>
     *
     * <p><b>レースの参加者として走っている間は取り消させない。</b>周回状態は順位表と
     * リザルトの土台なので、消すと自分の順位が消えるだけでなく、他の人から見た
     * タイム差の基準（{@code gateTimes}）まで無くなる。抜けたいなら
     * {@code /kuruma race leave} を使う。</p>
     */
    public static ResetResult resetTimeAttack(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        Map<UUID, LapState> states = states(level);
        LapState state = states.get(player.getUUID());
        if (state == null || !state.timing) {
            return ResetResult.NOT_TIMING;
        }
        if (isRacer(SESSIONS.get(level.dimension()), player, state.course)) {
            return ResetResult.RACING;
        }
        states.remove(player.getUUID());
        send(player, RaceEventPacket.cleared());
        return ResetResult.RESET;
    }

    /** そのワールドでレース対象になっているコース名。レース中でなければ null。 */
    public static String racingCourse(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        return session == null ? null : session.course;
    }

    /** そのワールドでレースが進行中か。 */
    public static boolean isRacing(ServerLevel level) {
        return SESSIONS.containsKey(level.dimension());
    }

    /** 表示用に mm:ss.SSS へ整える。 */
    public static String format(long millis) {
        long minutes = millis / 60000L;
        long seconds = (millis % 60000L) / 1000L;
        long rest = millis % 1000L;
        return String.format("%d:%02d.%03d", minutes, seconds, rest);
    }

    /** ワールド 1 つぶんのレース。 */
    private static final class Session {
        private final String course;
        private final int laps;
        /**
         * エントリーした人。
         *
         * <p><b>参加は明示的に申告してもらう。</b>そのワールドにいる全員を参加者にすると、
         * 同じワールドで別コースを走っている人や、ただ見ている人まで
         * カウントダウンで操作を奪われる。</p>
         */
        private final java.util.Set<UUID> entrants = new java.util.LinkedHashSet<>();
        private int countdown = -1;
        private int holdTicks;
        private boolean started;
        /** 号砲が鳴ったサーバーティック。締めるまでの猶予を測る。 */
        private int startedTick;
        private boolean showingResult;
        /** リザルトを出したサーバーティック。畳むまでの時間を測る。 */
        private int resultTick;
        /** 開催した人。開始と終了はこの人（と OP）だけができる。 */
        private UUID host;
        private String hostName = "";
        private final Map<UUID, Integer> finishOrder = new LinkedHashMap<>();

        private Session(String course, int laps) {
            this.course = course;
            this.laps = laps;
        }
    }

    /** プレイヤー 1 人ぶんの周回状態。 */
    private static final class LapState {
        private final String course;
        private double lastX;
        private double lastZ;
        private boolean hasLast;
        private boolean timing;
        private int lap;
        private int nextCheckpoint;
        private double lapStartMillis;
        private long lastLapMillis;
        /**
         * 今の周の各チェックポイント通過時刻。ラップ開始からの相対 [ms]。
         *
         * <p>ベストを更新したときにそのまま保存し、次の周からデルタの比較相手になる。</p>
         */
        private long[] splits = new long[0];

        /**
         * 通過した地点の通し番号。スタートラインもチェックポイントも 1 つと数える。
         *
         * <p>順位はこれの大きい順。同じ番号なら<b>先に通った方が前</b>。
         * これで「どこを走っているか」を線だけで順序付けられる。</p>
         */
        private int progress;
        /** 地点ごとの通過時刻 [ms]。同じ地点での差を出すために持つ。 */
        private final Map<Integer, Double> gateTimes = new HashMap<>();
        /** 計測を始めた時刻 [ms]。 */
        private double raceStartMillis;
        private long totalMillis;
        private boolean finished;

        private LapState(String course) {
            this.course = course;
        }

        private void passGate(double millis) {
            progress++;
            gateTimes.put(progress, millis);
        }
    }
}
