package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.network.RaceClassesPacket;
import com.jdmmc.kurumamod.network.TimeAttackRecordsPacket;
import com.jdmmc.kurumamod.tuning.Regulation;
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

    /** 走行中に車を降りたままリタイアになるまで [ティック]（60 秒）。 */
    private static final int AWAY_RETIRE_TICKS = 20 * 60;
    /** 降りたままの参加者に、残りの秒数を出し始めるまで [ティック]（30 秒）。 */
    private static final int AWAY_WARN_TICKS = 20 * 30;


    /** リザルトを出してからセッションを畳むまで [ティック]。 */
    private static final int RESULT_TICKS = 300;

    /** 周回状態。ワールドごと・プレイヤーごと。 */
    private static final Map<ResourceKey<Level>, Map<UUID, LapState>> STATES = new HashMap<>();
    /** 進行中のレース。ワールドごとに 1 つ。 */
    private static final Map<ResourceKey<Level>, Session> SESSIONS = new HashMap<>();
    /**
     * タイムアタックで宣言しているクラスの名前。プレイヤーごと（サーバー全体で 1 つ。クラスはサーバーに 1 組）。
     *
     * <p><b>覚えているのはクライアント</b>で、入ったとき・宣言を変えたときに送ってくる。サーバーは
     * 落ちた人のぶんを捨てる。</p>
     */
    private static final Map<UUID, String> DECLARED = new HashMap<>();

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
     * レースを開くの が「すでに開催中です」で弾かれ、ゲートは常時表示になり、
     * 誰も参加していないセッションの順位表が配られ続ける。</p>
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
        SESSIONS.clear();
        DECLARED.clear();
        RaceClasses.clear();
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
        // 主催者が別の次元へ移ったら、落ちたときと同じ（受付中は中止、走行中は続ける）。元のレースを操作できなくなるため
        cancelIfHost(event.getEntity(), event.getFrom(), true);
        // 捨てたことは<b>必ず伝える</b>。伝えないと画面の時計だけが回り続け、
        // サーバーには周回状態が無いので、K の「計測をやめる」が
        // 「計測していません」を返す——画面とサーバーが食い違う
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player, RaceEventPacket.cleared());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DECLARED.remove(event.getEntity().getUUID());
        dropOut(event.getEntity(), event.getEntity().level().dimension());
        cancelIfHost(event.getEntity(), event.getEntity().level().dimension(), false);
    }

    /**
     * 主催者が落ちた・別の次元へ移ったら、受付中のレースは中止する（リザルトも出さない）。
     *
     * <p>主催者のいないレースを残すと、誰が畳むのか・誰がスタートを押すのかの取り回しが面倒になる。</p>
     *
     * <p><b>走行中に別の次元へ移ったら、レースを終える</b>（「終える」と同じ。ゴールした人の順位が残るよう、
     * リザルトは出す）。移った主催者はレースの画面からそのレースを操作できない。<b>落ちたときは続ける</b>——回線が
     * 一瞬切れただけで、ほかの人が走っている途中で打ち切らない。全員がゴールすれば締まり、OP と参加者が「終える」を
     * 押せる。抜けたのが主催者でなければ何もしない。</p>
     *
     * @param changedDimension 別の次元へ移った（false なら落ちた）
     */
    private static void cancelIfHost(net.minecraft.world.entity.player.Player player, ResourceKey<Level> from,
                                     boolean changedDimension) {
        Session session = SESSIONS.get(from);
        if (session == null || !player.getUUID().equals(session.host) || player.getServer() == null) {
            return;
        }
        ServerLevel level = player.getServer().getLevel(from);
        if (level == null) {
            return;
        }
        if (session.started && !session.showingResult && changedDimension) {
            broadcast(level, Component.translatable("race.kurumamod.host_left_dimension", session.hostName)
                    .withStyle(ChatFormatting.GOLD));
            stopRace(level);
            return;
        }
        if (session.started && !session.showingResult) {
            // <b>落ちたときは続ける。</b>ほかの人が走っている途中で打ち切らない。全員がゴールすれば締まり、
            // OP と（主催者がいない間は）参加者が「終える」を押せる（canManage）
            broadcast(level, Component.translatable("race.kurumamod.host_left_running", session.hostName)
                    .withStyle(ChatFormatting.GRAY));
            broadcastLobby(level, session);
            return;
        }
        cleanup(level, session);
        broadcast(level, Component.translatable("race.kurumamod.host_left", session.hostName)
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 走行状態を捨て、レース中ならリタイアとして知らせる。
     *
     * <p>エントリー名簿からは<b>消さない</b>。リザルトに「完走していない人」として
     * 残す方が、誰が出走していたか分かる。代わりに {@code retired} に入れ、入り直してもレースの HUD は出さず、
     * レースに参加していない人として扱う。</p>
     */
    private static void dropOut(net.minecraft.world.entity.player.Player player,
                                ResourceKey<Level> from) {
        // <b>消す前に、レースの走りを覚えておく</b>（リザルトを出し終えるまで）。消すと、ゴールした人が落ちたら
        // リザルトから消え（2 位の人が 1 位として並んだ）、落ちてリタイアした人も出なかった
        Session departing = SESSIONS.get(from);
        Map<UUID, LapState> fromStates = STATES.get(from);
        LapState raceState = fromStates == null ? null : fromStates.get(player.getUUID());
        if (departing != null && departing.started && raceState != null
                && departing.entrants.contains(player.getUUID()) && !departing.retired.contains(player.getUUID())) {
            departing.kept.put(player.getUUID(), raceState);
        }
        for (Map<UUID, LapState> byPlayer : STATES.values()) {
            byPlayer.remove(player.getUUID());
        }
        Session session = SESSIONS.get(from);
        if (session != null) {
            session.spectators.remove(player.getUUID());
        }
        if (session == null || session.showingResult
                || !session.entrants.contains(player.getUUID())
                || session.finishOrder.containsKey(player.getUUID())) {
            return;
        }
        // <b>主催者がスタートを押す前（受付中）なら、エントリーから外す。</b>名簿に残すと、入り直したあと
        // 走行中のレースに 1 周目から加わり、走らずにいると全員ゴールの判定がその人を待ってレースが締まらなかった
        boolean entryOpen = !session.started && session.countdown < 0;
        if (entryOpen) {
            session.entrants.remove(player.getUUID());
        } else {
            // <b>スタートを押した後（信号の間も）は、落ちた時点でリタイア。</b>名簿には残す（リザルトに
            // 「完走していない人」として出す）が、入り直してもそのレースには数えない
            session.retired.add(player.getUUID());
        }
        ServerLevel level = player.getServer() == null ? null : player.getServer().getLevel(from);
        if (level != null) {
            if (entryOpen) {
                broadcast(level, Component.translatable("race.kurumamod.left_announce",
                        player.getGameProfile().getName(), session.entrants.size()));
                broadcastLobby(level, session);
            } else {
                broadcast(level, Component.translatable("race.kurumamod.retired",
                        player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    private static void tickLevel(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        Map<UUID, LapState> states = states(level);

        if (session != null) {
            tickCountdown(level, session, states);
        }

        for (ServerPlayer player : level.players()) {
            CarEntity driven = drivenCar(player);
            if (driven != null) {
                trackCar(level, session, states, player, driven);
            } else {
                dismount(session, states, player);
            }
            if (session != null) {
                watchAway(level, session, states, player);
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
            if (session.started && !session.showingResult) {
                disqualifyViolators(level, session, states);
                broadcastStandings(level, session, states);
            }
            // 決まりと判定は、受付中もレース中も配る（誘い・主催中の表示・参加したレースの画面）
            broadcastLobby(level, session);
        }
    }

    /**
     * 走行中の参加者が車を降りたまま（自分で運転していないまま）{@link #AWAY_RETIRE_TICKS} たったらリタイアにする。
     * {@link #AWAY_WARN_TICKS} を過ぎたら、本人に残りの秒数を毎秒出す。車に戻れば数え直す。
     *
     * <p>降りた参加者を待つのは、ひっくり返した車を立て直す人を見捨てないため。ただ、降りたままどこかへ行った人がいると
     * 全員ゴールの判定がその人を待ち続け、レースが締まらなかった。</p>
     */
    private static void watchAway(ServerLevel level, Session session, Map<UUID, LapState> states, ServerPlayer player) {
        UUID id = player.getUUID();
        if (!session.started || session.showingResult || !session.entrants.contains(id)
                || session.retired.contains(id) || session.finishOrder.containsKey(id)) {
            session.awayTicks.remove(id);
            return;
        }
        if (drivenCar(player) != null) {
            session.awayTicks.remove(id);
            return;
        }
        int away = session.awayTicks.merge(id, 1, Integer::sum);
        if (away >= AWAY_RETIRE_TICKS) {
            retire(session, states, id);
            // レースの HUD を消す（以後はレースに参加していない人として扱う）
            send(player, RaceEventPacket.cleared());
            player.sendSystemMessage(Component.translatable("race.kurumamod.away_retired_self")
                    .withStyle(ChatFormatting.RED));
            broadcast(level, Component.translatable("race.kurumamod.away_retired",
                    player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
            broadcastLobby(level, session);
        } else if (away >= AWAY_WARN_TICKS && away % 20 == 0) {
            tell(player, Component.translatable("race.kurumamod.away_warning", (AWAY_RETIRE_TICKS - away) / 20)
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * 走り出した後の参加者をリタイアにする（抜けた・外された・失格・降りたまま）。落ちた人は {@link #dropOut}。
     *
     * <p><b>名簿からは消さず、走りはリザルトまで覚えておく。</b>名簿と走りを消すと、リザルトにリタイアの行が
     * 出なかった。以後はレースに参加していない人として扱う（レースの HUD は出さず、ラインを越えれば
     * タイムアタックが始まる）。</p>
     */
    private static void retire(Session session, Map<UUID, LapState> states, UUID id) {
        session.retired.add(id);
        session.awayTicks.remove(id);
        LapState raceState = states.remove(id);
        if (raceState != null) {
            session.kept.put(id, raceState);
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
     * 消えては競技にならない。<b>抜けるのは レースの画面（K）の「抜ける」 だけ。</b></p>
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
        if (state != null && (state.timing || state.finished || state.course != null)) {
            send(player, RaceEventPacket.cleared());
        }
    }

    private static void tickCountdown(ServerLevel level, Session session, Map<UUID, LapState> states) {
        if (session.countdown < 0) {
            return;
        }
        // 信号の途中で全員が抜けた（取り消し・キック・観戦への切り替え・落ちた）。誰もいないまま号砲を鳴らすと、
        // 走る人のいないレースが猶予のあいだ残る。受付に戻す。<b>落ちた人はリタイアとして名簿に残る</b>ので、
        // 名簿が空かどうかでは見分けられない（全員が落ちてもスタートしていた）
        if (session.retired.containsAll(session.entrants)) {
            session.countdown = -1;
            // 受付に戻すので、リタイアにした人は名簿から外す（落ちた人は受付中なら外す、と同じ）
            session.entrants.removeAll(session.retired);
            session.retired.clear();
            session.kept.clear();
            toViewers(level, session, RaceEventPacket.cleared());
            broadcast(level, Component.translatable("race.kurumamod.countdown_aborted")
                    .withStyle(ChatFormatting.GRAY));
            broadcastLobby(level, session);
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
        for (UUID id : session.entrants) {
            if (!session.retired.contains(id)) {
                states.remove(id);
            }
        }
        startFirstLap(level, session, states);
    }

    /**
     * <b>号砲で 1 周目を始める。</b>スタートラインを越えるのを待たない——グリッドがラインの手前でも
     * 先でも、号砲からの時間がそのまま 1 周目と総時間になる。
     *
     * <p>グリッドがラインの手前にあると、号砲の直後にラインを越える。それを周回の終わりと数えないよう
     * {@link LapState#gridStart} を立てておく（{@link #trackCar}）。車に乗っていない人も始めておき、
     * 乗ったところから線分を取る。</p>
     */
    private static void startFirstLap(ServerLevel level, Session session, Map<UUID, LapState> states) {
        Course course = RaceData.get(level).course(session.course).orElse(null);
        if (course == null || !course.isReady()) {
            return;
        }
        double now = level.getServer().getTickCount() * TICK_MILLIS;
        for (UUID id : session.entrants) {
            // 信号の間に落ちた人はリタイア。入り直していても、レースの 1 周目としては測らない
            // （測ると号砲からの周がタイムアタックの記録とゴーストに入った）
            if (session.retired.contains(id)) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != level) {
                continue;
            }
            LapState state = new LapState(session.course);
            state.gridStart = true;
            CarEntity car = drivenCar(player);
            if (car != null) {
                state.lastX = car.getX();
                state.lastZ = car.getZ();
                state.hasLast = true;
            }
            states.put(id, state);
            beginLap(player, session, course, state, now);
            if (car != null) {
                // ラインの手前に並んだときだけ、号砲の直後の通過を見逃す。先に並んだなら、次の通過が 1 周目の終わり。
                // どちらとも言えない（線の幅の外）ときは、ふつうの並び方（手前）とみなす。
                // CP のあるコースは、最初の CP を通るまで見逃す（通ったところで下ろす）
                double fromStart = course.start().signedDistance(car.getX(), car.getZ(), car.getY());
                state.gridStart = course.checkpointCount() > 0 || !(fromStart > 0.0);
                skipCheckpointsBehindGrid(course, state, car, now);
            }
        }
    }

    /** グリッドがチェックポイントの先にあるとみなす距離 [ブロック]。 */
    private static final double GRID_PAST_DISTANCE = 32.0;

    /**
     * 号砲の時点で<b>もう越えているチェックポイントは、通ったことにする。</b>
     *
     * <p>グリッドがスタートラインの先、最初のチェックポイントのすぐ先にあると、そのチェックポイントを
     * もう 1 周してくるまで取ったことにならず、<b>1 周目のライン通過が数えられなかった</b>。
     * ゲートのすぐ先（{@link #GRID_PAST_DISTANCE} 以内・ゲートの幅の中）にいるものだけを順に飛ばす。</p>
     */
    private static void skipCheckpointsBehindGrid(Course course, LapState state, CarEntity car, double now) {
        while (state.nextCheckpoint < course.checkpointCount()) {
            double ahead = course.checkpoint(state.nextCheckpoint).signedDistance(car.getX(), car.getZ(), car.getY());
            if (!(ahead > 0.0 && ahead <= GRID_PAST_DISTANCE)) {
                return;
            }
            state.nextCheckpoint++;
            state.passGate(now);
            // チェックポイントの先から出たので、次にラインを越えたら 1 周目の終わり
            state.gridStart = false;
        }
    }

    /**
     * カウントダウンは<b>参加者と観戦者にだけ</b>。観戦者の操作は止めない（クライアントが
     * {@code RaceLobby#spectating} を見て止めない）。
     */
    private static void broadcastCountdown(ServerLevel level, Session session,
                                           int lights, boolean frozen) {
        toViewers(level, session, RaceEventPacket.countdown(lights, frozen));
    }

    /**
     * 参加者と観戦者に配る（信号・順位・リザルト）。<b>エントリーも観戦もしていない人にはレースの HUD を出さない。</b>
     *
     * <p>そのワールドにいる人だけに配る。別の次元へ移った参加者に信号を送ると、移った先で車が止まる。</p>
     */
    private static void toViewers(ServerLevel level, Session session, Object packet) {
        List<UUID> ids = new ArrayList<>(session.entrants);
        // リタイアした人（落ちて入り直した人）にはレースの HUD を出さない
        ids.removeAll(session.retired);
        ids.addAll(session.spectators);
        for (UUID id : ids) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null && player.level() == level) {
                KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    /** 終了の告知。開催の告知と同じく、主催者とトラック（と周回数）を出す。 */
    private static net.minecraft.network.chat.MutableComponent raceOver(Session session) {
        return Component.translatable("race.kurumamod.race_over", session.hostName, session.course, session.laps);
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
        // 観戦している人は測らない。タイムアタックの線を跨いでも始めない（観戦を優先する）
        if (session != null && session.spectators.contains(player.getUUID())) {
            return;
        }
        // エントリーしてから号砲までの参加者も測らない。レースを待っている間にタイムアタックの計測や
        // ウォームアップが始まると、どちらの準備をしているのか分からなくなる（信号の間に止めきれずに
        // ラインを越えた場合も、ここで止まる）
        if (session != null && !session.started && session.entrants.contains(player.getUUID())) {
            return;
        }
        // ゴールしてから落ちた（別のワールドへ移った）人が戻ってきた。覚えておいたゴールの状態を戻す。
        // 戻さないとラインを越えたところでレースの周を測り直し、もう一度ゴールして順位が書き換わった
        // リザルトを出している間も戻す（戻さないと、リザルトの最中にタイムアタックが始まった）
        if (session != null && session.started
                && session.finishOrder.containsKey(player.getUUID()) && !states.containsKey(player.getUUID())) {
            LapState finished = session.kept.remove(player.getUUID());
            if (finished != null) {
                finished.hasLast = false;
                states.put(player.getUUID(), finished);
            }
        }
        RaceData data = RaceData.get(level);

        LapState state = states.get(player.getUUID());
        // どのコースを走るかは、あくまで「跨いだ線」で決まる。レース中だからといって
        // そのワールドの全員をレースのコースへ乗せてはいけない。1 ワールドに複数コースが
        // あると、別コースをタイムアタックしている人まで参加者にされてしまう
        String courseName = state != null ? state.course : null;
        if (courseName == null) {
            List<String> crossed = coursesCrossed(data, car, state);
            if (crossed.isEmpty()) {
                rememberPosition(states, player, car, null);
                return;
            }
            startWarmUp(states, player, car, crossed);
            return;
        }
        // ウォームアップ中（まだ測っていない・レースの参加者ではない）は、トラックを決め直せる
        if (!state.timing && !state.finished && state.hasLast && !isRacer(session, player, state.course)) {
            // 1. 別のトラックのスタートラインを越えたら、そちらへ乗り換える。最初に越えたトラックに縛られると、
            //    隣のトラックへ移っても降りるか「計測をやめる」まで測れなかった
            List<String> crossed = coursesCrossed(data, car, state);
            crossed.removeAll(state.candidates);
            crossed.remove(state.course);
            if (!crossed.isEmpty()) {
                startWarmUp(states, player, car, crossed);
                return;
            }
            // 2. スタートラインを共有するトラックがいくつかあるなら、通ったチェックポイントで絞る
            if (state.candidates.size() > 1) {
                state = narrowCandidates(data, states, player, car, state);
                courseName = state.course;
            }
        }

        Course course = data.course(courseName).orElse(null);
        if (course == null || !course.isReady()) {
            return;
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
                state.gridStart = false;
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
            // タイムアタックで宣言したクラスの条件を満たしていなければ、計測を始めない（ウォームアップのまま。
            // 直して次にラインを越えたら始まる）。レースの参加者は号砲で始まるのでここには来ない
            String declared = isRacer(session, player, state.course) ? null : declaredClass(player);
            Regulation rules = declared == null ? null : RaceClasses.regulation(player.getServer(), declared);
            List<Regulation.Violation> violations = rules == null ? null : violations(player, rules);
            if (violations != null && !violations.isEmpty()) {
                tell(player, Component.translatable("race.kurumamod.class_start_blocked", declared)
                        .withStyle(ChatFormatting.RED));
                return;
            }
            beginLap(player, session, course, state, crossedAt);
            return;
        }
        if (state.gridStart) {
            // 号砲の直後に、グリッドからラインを越えただけ。1 周目はもう号砲から測っているので数えない。
            // <b>見逃すのは 1 回だけ。</b>以前は CP の無いコースで「号砲から 10 秒」を見逃していたため、
            // 1 周が 10 秒を切る短いコースでは本当の 1 周目まで捨てられ、1 周多く走らされた
            state.gridStart = false;
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

    /**
     * 前のティックから今までに越えたスタートラインのトラック（名前順）。<b>スタートラインを共有するトラックは
     * 全部返す</b>（レイアウト違いなど）。どれを走るかは {@link #narrowCandidates} がチェックポイントで決める。
     */
    private static List<String> coursesCrossed(RaceData data, CarEntity car, LapState state) {
        List<String> out = new ArrayList<>();
        if (state == null || !state.hasLast) {
            return out;
        }
        for (Course course : data.courses()) {
            CourseLine start = course.start();
            if (course.isReady() && start != null && start.crossingFraction(
                    state.lastX, state.lastZ, car.getX(), car.getZ(), car.getY()) >= 0.0) {
                out.add(course.name());
            }
        }
        return out;
    }

    /**
     * ウォームアップを始める（越えたスタートラインのトラックで）。候補が 2 つ以上なら、HUD には「A / B」と並べ、
     * 計測が始まるまでにチェックポイントで絞る。絞り切れなければ名前順で先のトラックで測る。
     */
    private static void startWarmUp(Map<UUID, LapState> states, ServerPlayer player, CarEntity car,
                                     List<String> candidates) {
        LapState state = new LapState(candidates.get(0));
        state.candidates = List.copyOf(candidates);
        state.lastX = car.getX();
        state.lastZ = car.getZ();
        state.hasLast = true;
        states.put(player.getUUID(), state);
        String declared = declaredClass(player);
        send(player, RaceEventPacket.warmUp(String.join(" / ", candidates), declared == null ? "" : declared));
    }

    /**
     * スタートラインを共有するトラックを、ウォームアップ中に通ったチェックポイントで絞る。通った線を持つ候補だけを
     * 残し、1 つになったら確定してウォームアップを出し直す。どの候補も持つ線（共有の区間）では絞らない。
     *
     * @return 今のトラックの周回状態（絞ったら作り直したもの）
     */
    private static LapState narrowCandidates(RaceData data, Map<UUID, LapState> states, ServerPlayer player,
                                             CarEntity car, LapState state) {
        List<String> passed = new ArrayList<>();
        for (String name : state.candidates) {
            Course candidate = data.course(name).orElse(null);
            if (candidate == null) {
                continue;
            }
            for (int i = 0; i < candidate.checkpointCount(); i++) {
                if (candidate.checkpoint(i).crossingFraction(state.lastX, state.lastZ, car.getX(), car.getZ(),
                        car.getY()) >= 0.0) {
                    passed.add(name);
                    break;
                }
            }
        }
        if (passed.isEmpty() || passed.size() == state.candidates.size()) {
            return state;
        }
        LapState narrowed = new LapState(passed.get(0));
        narrowed.candidates = List.copyOf(passed);
        narrowed.lastX = state.lastX;
        narrowed.lastZ = state.lastZ;
        narrowed.hasLast = true;
        states.put(player.getUUID(), narrowed);
        String declared = declaredClass(player);
        send(player, RaceEventPacket.warmUp(String.join(" / ", passed), declared == null ? "" : declared));
        return narrowed;
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
                && session.entrants.contains(player.getUUID())
                && !session.retired.contains(player.getUUID());
    }

    /** そのクラスの今の中身（{@link Regulation#toArray}）。クラスが無ければ null。周の始めに覚えておく。 */
    private static double[] classRules(ServerPlayer player, String raceClass) {
        Regulation regulation = raceClass == null ? null : RaceClasses.regulation(player.getServer(), raceClass);
        return regulation == null ? null : regulation.toArray();
    }

    /** デルタとベストの比べる相手のクラス（空ならクラスなし）。 */
    private static String comparedClass(LapState state) {
        return state.raceClass == null ? RaceData.NO_CLASS : state.raceClass;
    }

    /**
     * この周をクラスの記録に入れるなら、そのクラスの名前。入れないなら null。
     *
     * <p>入れるのは<b>周を始めたときと終えたときで宣言が同じ</b>で、<b>終えた時点の車がそのクラスの決まりに
     * 合っている</b>とき。G と換装はクラスの外へ出られないようにしてあるが、受付中のレースにエントリーしていると
     * レースの決まりのほうで縛られるので、ここで確かめ直す。</p>
     */
    private static String countedClass(ServerPlayer player, LapState state) {
        if (state.raceClass == null || !state.raceClass.equals(declaredClass(player))) {
            return null;
        }
        Regulation regulation = RaceClasses.regulation(player.getServer(), state.raceClass);
        // 周の途中でクラスの中身が書き換えられたら入れない。前半を古い条件で走った周が、新しい条件の記録になってしまう
        if (regulation == null || !java.util.Arrays.equals(regulation.toArray(), state.classRules)) {
            return null;
        }
        List<Regulation.Violation> violations = violations(player, regulation);
        return violations != null && violations.isEmpty() ? state.raceClass : null;
    }

    /** その人が宣言しているクラスの名前。宣言していない・もう無いクラスなら null。 */
    public static String declaredClass(ServerPlayer player) {
        String name = DECLARED.get(player.getUUID());
        if (name == null || player.getServer() == null
                || RaceClasses.regulation(player.getServer(), name) == null) {
            return null;
        }
        return name;
    }

    /**
     * タイムアタックで走るクラスを宣言する（空ならやめる）。
     *
     * <p>周の途中で変えても、比べる相手が変わるのは次の周から（{@link #completeLap}）。</p>
     */
    private static void declare(ServerPlayer player, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            DECLARED.remove(player.getUUID());
        } else if (RaceClasses.regulation(player.getServer(), trimmed) == null) {
            // 覚えていたクラスがサーバーで消されていた（入り直したときなど）。宣言していないことにする
            DECLARED.remove(player.getUUID());
            tell(player, Component.translatable("race.kurumamod.class_gone", trimmed));
        } else {
            DECLARED.put(player.getUUID(), trimmed);
        }
        sendLobby(player);
    }

    /**
     * クラスの中身が変わった・消えたので、そのクラスの記録を全ワールドから消し、いま入っている全員のゴーストも消させる。
     * 様子の配り直しは呼んだ側（{@link #lobbyToAll}）。
     */
    private static int clearClassRecords(MinecraftServer server, String name, boolean deleted) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            removed += RaceData.get(level).clearClassBests(name);
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            send(online, RaceEventPacket.bestCleared("", "", name));
            if (deleted && name.equals(DECLARED.get(online.getUUID()))) {
                DECLARED.remove(online.getUUID());
                online.sendSystemMessage(Component.translatable("race.kurumamod.class_gone", name)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        return removed;
    }

    /**
     * サーバーの全員へ、レースの様子（クラスの一覧を含む）を配り直す。<b>クラスはサーバーに 1 組</b>なので、
     * 追加・上書き・削除はどのワールドにいる人にも届けること（届けないと、ほかの人の画面に新しいクラスが出ず、
     * 宣言している人の締め付けも古い中身のまま残る）。後から入ってきた人にはログインのときに送っている。
     */
    private static void lobbyToAll(MinecraftServer server) {
        classesToAll(server);
        for (ServerLevel level : server.getAllLevels()) {
            Session session = SESSIONS.get(level.dimension());
            if (session != null) {
                broadcastLobby(level, session);
            } else {
                for (ServerPlayer player : level.players()) {
                    sendLobby(player);
                }
            }
        }
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
        long[] best = data.bestSplits(player.getUUID(), course.name(), comparedClass(state));
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
        state.raceClass = isRacer(session, player, state.course) ? null : declaredClass(player);
        state.classRules = classRules(player, state.raceClass);
        send(player, RaceEventPacket.lapStarted(state.lap, lapTarget(session, player, state.course),
                course.checkpointCount() + 1, course.name(), comparedClass(state),
                RaceData.get((ServerLevel) player.level()).bestLap(player.getUUID(), course.name(),
                        comparedClass(state))));
    }

    private static void completeLap(ServerLevel level, Session session, Map<UUID, LapState> states,
                                    ServerPlayer player, RaceData data, Course course,
                                    LapState state, long lapMillis, double crossedAt) {
        // 差を出す相手は「更新する前の」ベスト。取り込んでから引くと必ず 0 になる。
        // 相手はこの周を始めたときに宣言していたクラスの自己ベスト（宣言していなければクラスなし）
        String compared = comparedClass(state);
        long previousBest = data.bestLap(player.getUUID(), course.name(), compared);
        // <b>レースの周はタイムアタックのベストに数えない。</b>他の車がいてレギュレーションに縛られた周で、
        // ゴーストもレース中は残さないため、数えるとベストとゴーストが食い違う（ランキングにも載せない）
        boolean racing = isRacer(session, player, course.name());
        String counted = racing ? null : countedClass(player, state);
        RaceData.LapRecord record = racing ? new RaceData.LapRecord(false, false)
                : data.recordLap(player.getUUID(), player.getGameProfile().getName(),
                        course.name(), counted, lapMillis, state.splits);
        // クラスが消されていたら言わない（消えたことは削除のときに伝えてある）
        if (!racing && state.raceClass != null && counted == null
                && RaceClasses.regulation(player.getServer(), state.raceClass) != null) {
            // 宣言して走り出したのに、クラスの記録に入らなかった（条件を満たしていない・宣言を変えた・
            // 中身が書き換えられた）。黙っていると、記録が増えない理由が分からない
            player.sendSystemMessage(Component.translatable("race.kurumamod.class_lap_not_counted",
                    state.raceClass).withStyle(ChatFormatting.GRAY));
        }
        state.passGate(crossedAt);
        state.lastLapMillis = lapMillis;
        state.lapStartMillis = crossedAt;
        state.nextCheckpoint = 0;
        state.splits = new long[course.checkpointCount()];
        long bestMillis = data.bestLap(player.getUUID(), course.name(), compared);
        long delta = lapMillis - previousBest;
        boolean hasDelta = previousBest > 0L;
        // 比べたクラスのベストを更新したか（HUD の「ベスト」の表示はこちら）
        boolean best = compared.isEmpty() ? record.noClass() : counted != null && record.inClass();
        if (counted != null && record.inClass() && previousBest <= 0L) {
            // そのクラスのはじめての記録。一覧の記録の件数が 1 つ増える
            classesToAll(player.getServer());
        }

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
                broadcast(level, raceOver(session)
                        .withStyle(ChatFormatting.GOLD));
            }
            return;
        }

        state.lap++;
        // 宣言を周の途中で変えたら、比べる相手が変わるのはここ（次の周）から
        String nextClass = racing ? null : declaredClass(player);
        state.raceClass = nextClass;
        state.classRules = classRules(player, nextClass);
        send(player, RaceEventPacket.lapCompleted(state.lap, racing ? session.laps : 0,
                lapMillis, bestMillis, best, delta, hasDelta, course.name(),
                counted == null ? "" : counted, record.inClass(), record.noClass(),
                nextClass == null ? "" : nextClass,
                data.bestLap(player.getUUID(), course.name(), nextClass == null ? RaceData.NO_CLASS : nextClass)));
        if (!best && record.noClass() && !compared.isEmpty()) {
            // クラスの条件を満たさず、クラスなしの記録になってベストを更新した。HUD はクラスの方を出すので、チャットで知らせる
            player.sendSystemMessage(Component.translatable("race.kurumamod.overall_best",
                    course.name(), format(lapMillis)).withStyle(ChatFormatting.AQUA));
        }
    }

    /**
     * 参加者が全員ゴールしたか。
     *
     * <p><b>そこに居る参加者は、車に乗っていなくても待つ。</b>降りただけで見捨てると、
     * ひっくり返した車を立て直している間にレースが終わってしまう。<b>自分から抜けるには
     * レースの画面（K）の「抜ける」</b>（名簿から消える）。</p>
     *
     * <p>待たないのは<b>もうそこに居ない人</b>だけ——落ちた人と、別のワールドへ移った人。
     * 待ち続けるとレースが自動で終わらなくなる。</p>
     */
    private static boolean allFinished(ServerLevel level, Session session) {
        for (UUID id : session.entrants) {
            if (session.finishOrder.containsKey(id) || session.retired.contains(id)) {
                continue;   // ゴールした・リタイアした（落ちて入り直した人も待たない）
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
        if (session.finishOrder.isEmpty() && session.retired.containsAll(session.entrants)) {
            // 誰もゴールせずに全員が抜けた（リタイア・落ちた）。リタイアだけを並べても仕方がないのでリザルトは出さない
            cleanup(level, session);
            broadcast(level, Component.translatable("race.kurumamod.all_retired").withStyle(ChatFormatting.GRAY));
            return;
        }
        broadcastResult(level, session, states);
        broadcast(level, raceOver(session)
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
        // レースが無くなったことを、そのワールドの全員へ。誘い・主催中の表示が残らないように
        for (ServerPlayer player : level.players()) {
            sendLobby(player);
        }
        for (UUID id : session.entrants) {
            // リタイアした人は入り直してタイムアタックを走っていることがある。そちらは消さない
            if (!session.retired.contains(id)) {
                states(level).remove(id);
            }
        }
        toViewers(level, session, RaceEventPacket.cleared());
        toViewers(level, session,
                new RaceStandingsPacket(RaceStandingsPacket.Phase.ENTRY, "", 0, List.of()));
        RaceSync.sendToAll(level.getServer());
    }

    /**
     * そのコースの全員のベストを消したことを、<b>サーバーの全員</b>に知らせる（ゴースト・HUD の BEST・K の画面）。
     *
     * <p>タイムアタックのゴーストはクライアントが持っているので、知らせないと<b>消したはずのベストの走りが
     * ゴーストとして走り続ける</b>。記録はレースと違って次元に縛られないので、別の次元にいる人にも送る。
     * 知らせには次元を載せ、ゴーストはその次元のフォルダから消させる（戻ってきたときに残っていない）。
     * 落ちている人のものは、次にそのコースを走り出したとき、サーバーのベストと周が合わずに消える（{@code CarGhosts}）。</p>
     */
    public static void notifyCourseBestsCleared(ServerLevel level, String course) {
        RaceEventPacket packet = RaceEventPacket.bestCleared(dimensionId(level), course);
        for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
            send(online, packet);
        }
    }

    /** 次元の名前（{@code minecraft:the_nether} など）。記録を消した知らせに載せる。 */
    private static String dimensionId(Level level) {
        return level.dimension().location().toString();
    }

    private static void send(ServerPlayer player, RaceEventPacket packet) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    // ------------------------------------------------------------------
    // 順位表
    // ------------------------------------------------------------------

    /**
     * リザルトを組んで配る。
     *
     * <p>完走した人を順位順に、そのあと完走していない人を進んだ地点の多い順。
     * 先頭は総時間、以降は先頭との差。</p>
     */
    private static void broadcastResult(ServerLevel level, Session session, Map<UUID, LapState> states) {
        List<RaceStandingsPacket.Row> rows = new ArrayList<>();
        long winner = 0L;
        // 落ちた人・リタイアした人も並べる。走りは覚えておいたもの（kept）、名前はエントリーしたときのもの
        for (UUID id : session.finishOrder.keySet()) {
            LapState state = raceState(session, states, id);
            if (state == null) {
                continue;
            }
            boolean first = rows.isEmpty();
            if (first) {
                winner = state.totalMillis;
            }
            rows.add(new RaceStandingsPacket.Row(entrantName(level, session, id),
                    state.lap, first ? state.totalMillis : state.totalMillis - winner, first, true, true, false));
        }
        // 完走していない人（リタイア・強制終了）。進んだ地点の多い順
        List<UUID> rest = new ArrayList<>(session.entrants);
        rest.removeAll(session.finishOrder.keySet());
        rest.sort((a, b) -> Integer.compare(progressOf(session, states, b), progressOf(session, states, a)));
        for (UUID id : rest) {
            LapState state = raceState(session, states, id);
            rows.add(new RaceStandingsPacket.Row(entrantName(level, session, id),
                    state != null ? Math.max(0, state.lap - 1) : 0, 0L, false, false,
                    state != null && state.progress > 0, session.retired.contains(id)));
        }
        session.showingResult = true;
        session.resultTick = level.getServer().getTickCount();
        // レースは終わった。まだ走っている人の計測も止める（中止したとき、周回を数え続けないように）。
        // <b>リタイアした人には触らない</b>——入り直してタイムアタックを走っているので、止めると時計だけが回り続けた
        for (UUID id : session.entrants) {
            LapState state = session.retired.contains(id) ? null : states.get(id);
            if (state != null) {
                // finished も立てる。立てないと、ラインを越えたところで計測が測り直しになる
                state.timing = false;
                state.finished = true;
            }
        }
        toViewers(level, session, new RaceStandingsPacket(RaceStandingsPacket.Phase.RESULT,
                session.course, session.laps, rows));
    }

    /** その参加者のレースの走り。落ちた・リタイアした人は覚えておいたもの（今の状態はタイムアタックのもの）。 */
    private static LapState raceState(Session session, Map<UUID, LapState> states, UUID id) {
        LapState kept = session.kept.get(id);
        if (kept != null || session.retired.contains(id)) {
            return kept;
        }
        return states.get(id);
    }

    private static int progressOf(Session session, Map<UUID, LapState> states, UUID id) {
        LapState state = raceState(session, states, id);
        return state == null ? -1 : state.progress;
    }

    /** 参加者の名前。落ちていてもエントリーしたときの名前で出す。 */
    private static String entrantName(ServerLevel level, Session session, UUID id) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
        return player != null ? player.getGameProfile().getName() : session.names.getOrDefault(id, "?");
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
        List<UUID> racers = new ArrayList<>();
        List<UUID> waiting = new ArrayList<>();
        for (UUID id : session.entrants) {
            if (session.retired.contains(id)) {
                continue;
            }
            // <b>ゴールした人は、落ちても別のワールドへ移っても残す</b>（走りは覚えておいたもの）。
            // 消すと、ゴールした人が順位の塔から消えて後ろの人が繰り上がって見えた
            if (session.finishOrder.containsKey(id)) {
                if (raceState(session, states, id) != null) {
                    racers.add(id);
                }
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != level) {
                continue;
            }
            LapState state = states.get(id);
            if (state != null && (state.timing || state.finished)
                    && session.course.equals(state.course)) {
                racers.add(id);
            } else {
                // まだスタートラインを跨いでいない人。<b>出さないと「誰が出走しているのか」が
                // スタート直後だけ分からなくなる</b>
                waiting.add(id);
            }
        }
        if (racers.isEmpty() && waiting.isEmpty()) {
            return;
        }
        racers.sort((a, b) -> {
            LapState sa = raceState(session, states, a);
            LapState sb = raceState(session, states, b);
            if (sa.progress != sb.progress) {
                return Integer.compare(sb.progress, sa.progress);
            }
            return Double.compare(sa.gateTimes.getOrDefault(sa.progress, 0.0),
                    sb.gateTimes.getOrDefault(sb.progress, 0.0));
        });

        double nowMillis = level.getServer().getTickCount() * TICK_MILLIS;
        List<RaceStandingsPacket.Row> rows = new ArrayList<>();
        LapState leader = racers.isEmpty() ? null : raceState(session, states, racers.get(0));
        for (int i = 0; i < racers.size(); i++) {
            UUID id = racers.get(i);
            LapState state = raceState(session, states, id);
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
            rows.add(new RaceStandingsPacket.Row(entrantName(level, session, id),
                    state.finished ? state.lap : state.lap - 1, value,
                    i == 0, state.finished, true));
        }
        for (UUID id : waiting) {
            rows.add(new RaceStandingsPacket.Row(
                    entrantName(level, session, id), 0, 0L, false, false, false));
        }
        toViewers(level, session, new RaceStandingsPacket(RaceStandingsPacket.Phase.RUNNING,
                session.course, session.laps, rows,
                (long) ((level.getServer().getTickCount() - session.startedTick) * TICK_MILLIS)));
    }

    // ------------------------------------------------------------------
    // レギュレーションとレースの様子
    // ------------------------------------------------------------------

    /**
     * その人が<b>運転席に座っている</b>車。助手席・後ろの席や、車以外に乗っているなら null。
     *
     * <p><b>レースでは運転していない人は「車に乗っていない」とみなす。</b>人の車の助手席に乗っても、その車で
     * 走っていることにはならない（計測しない・決まりで判定しない・グリッドの位置にも使わない・一覧に車種を出さない）。</p>
     */
    private static CarEntity drivenCar(ServerPlayer player) {
        return player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == player ? car : null;
    }

    /**
     * 乗っている車を決まりで判定する。<b>運転していなければ null</b>（判定できない）。
     *
     * <p>比べるのは実際に走る諸元（{@link CarEntity#getSpec}、部品込み）と、その車種の素の諸元。</p>
     */
    private static List<Regulation.Violation> violations(ServerPlayer player, Regulation regulation) {
        CarEntity car = drivenCar(player);
        if (car == null) {
            return null;
        }
        return regulation.check(car.getSpec(), CarTypes.get(car.getCarId()).spec());
    }

    /**
     * いまレースの決まりに縛られている人の決まり。縛られていなければ null。
     *
     * <p>縛るのは<b>エントリーしてからゴールするまで</b>の参加者と、タイムアタックでクラスを宣言している人
     * （G セッティングの制限と同じ）。
     * 換装の要求（{@code CarPartRequestPacket}）が、決まりの外へ出る部品を弾くのに使う。</p>
     */
    public static Regulation restrictionFor(ServerPlayer player) {
        Session session = SESSIONS.get(player.level().dimension());
        boolean entered = session != null && !session.showingResult
                && session.entrants.contains(player.getUUID())
                && !session.retired.contains(player.getUUID())
                && !session.finishOrder.containsKey(player.getUUID());
        if (entered) {
            // エントリーしている間はレースの決まりが優先。宣言は止めておく（ゴールか抜けたら戻る）
            return session.regulation.isFree() ? null : session.regulation;
        }
        if (session != null && session.spectators.contains(player.getUUID())) {
            // 観戦している間は測らないので、宣言も止めておく
            return null;
        }
        // タイムアタックで宣言しているクラス
        String declared = declaredClass(player);
        Regulation regulation = declared == null ? null : RaceClasses.regulation(player.getServer(), declared);
        return regulation == null || regulation.isFree() ? null : regulation;
    }

    /**
     * 走っている最中に決まりの外へ出た人を失格にする。
     *
     * <p>G セッティングは決まりの外へ動かせないようにしてあるので、ふつうはここに掛からない。
     * 掛かるのは、レース中に別の車へ乗り換えた人と、改造したクライアント。</p>
     */
    private static void disqualifyViolators(ServerLevel level, Session session, Map<UUID, LapState> states) {
        if (session.regulation.isFree()) {
            return;
        }
        boolean removed = false;
        for (UUID id : new ArrayList<>(session.entrants)) {
            if (session.finishOrder.containsKey(id) || session.retired.contains(id)) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            List<Regulation.Violation> violations = player == null ? null : violations(player, session.regulation);
            if (violations == null || violations.isEmpty()) {
                continue;
            }
            // 失格はリタイアとして扱う（名簿に残し、リザルトにリタイアの行を出す）。観戦したければ K から
            retire(session, states, id);
            removed = true;
            send(player, RaceEventPacket.cleared());
            player.sendSystemMessage(Component.translatable("race.kurumamod.disqualified")
                    .withStyle(ChatFormatting.RED));
            broadcast(level, Component.translatable("race.kurumamod.disqualified_announce",
                    player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
        }
        if (removed) {
            closeIfDone(level, session, states);
        }
    }

    private static RaceLobbyPacket.Phase phaseOf(Session session) {
        if (session == null) {
            return RaceLobbyPacket.Phase.NONE;
        }
        if (session.showingResult) {
            return RaceLobbyPacket.Phase.RESULT;
        }
        if (session.started) {
            return RaceLobbyPacket.Phase.RUNNING;
        }
        return session.countdown >= 0 ? RaceLobbyPacket.Phase.COUNTDOWN : RaceLobbyPacket.Phase.OPEN;
    }

    /** そのワールドのコース。主催者画面で選ぶ。 */
    private static List<RaceLobbyPacket.CourseInfo> courseInfos(ServerLevel level) {
        List<RaceLobbyPacket.CourseInfo> list = new ArrayList<>();
        for (Course course : RaceData.get(level).courses()) {
            if (course.isReady()) {
                list.add(new RaceLobbyPacket.CourseInfo(course.name(), course.laps(), course.checkpointCount()));
            }
        }
        return list;
    }

    /** 参加者の一覧と、行ごとの UUID（同じ順）。受け取る人が何行目かを引くため。 */
    private record Roster(List<UUID> ids, List<RaceLobbyPacket.Entrant> entrants) {
    }

    /**
     * 受け取る人ごとにレースの様子を組む。<b>参加者の一覧はそのまま渡し、「自分が何行目か」だけを人ごとに付ける</b>
     * （{@link RaceLobbyPacket#self}）。
     *
     * @param roster 参加者の一覧（{@link #listEntrants}）。null なら組む
     */
    private static RaceLobbyPacket lobbyFor(ServerLevel level, Session session, ServerPlayer viewer,
                                            List<RaceLobbyPacket.CourseInfo> courses, Roster roster) {
        if (session == null) {
            return RaceLobbyPacket.none(courses, isOp(viewer),
                    declaredName(viewer));
        }
        if (roster == null) {
            roster = listEntrants(level, session);
        }
        List<RaceLobbyPacket.Entrant> list = roster.entrants();
        int self = roster.ids().indexOf(viewer.getUUID());
        RaceData data = RaceData.get(level);
        int checkpoints = data.course(session.course).map(Course::checkpointCount).orElse(0);
        long best = data.bestLap(viewer.getUUID(), session.course);
        return new RaceLobbyPacket(phaseOf(session), session.course, session.laps, checkpoints,
                session.hostName, viewer.getUUID().equals(session.host),
                session.entrants.contains(viewer.getUUID()) && !session.retired.contains(viewer.getUUID()),
                session.spectators.contains(viewer.getUUID()),
                canManage(viewer), Math.max(0L, best),
                session.regulation.toArray(), list, courses,
                isOp(viewer), declaredName(viewer), self);
    }

    /**
     * 参加者の一覧（決まりの判定つき）。<b>受け取る人で変わらないので、配るときに 1 回だけ組む。</b>
     * 受け取る人ごとに組むと、その次元の人数 × 参加者数の回数だけ決まりを判定していた。
     */
    private static Roster listEntrants(ServerLevel level, Session session) {
        List<UUID> ids = new ArrayList<>();
        List<RaceLobbyPacket.Entrant> list = new ArrayList<>();
        for (UUID id : session.entrants) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null || session.retired.contains(id)) {
                continue;
            }
            List<Regulation.Violation> violations = violations(player, session.regulation);
            CarEntity car = drivenCar(player);
            String carId = car != null ? car.getCarId().toString() : "";
            double[] values = violations != null ? RaceLobbyPacket.valuesOf(car.getSpec()) : new double[0];
            RaceLobbyPacket.Verdict verdict = violations == null ? RaceLobbyPacket.Verdict.NO_CAR
                    : violations.isEmpty() ? RaceLobbyPacket.Verdict.OK : RaceLobbyPacket.Verdict.VIOLATION;
            ids.add(id);
            list.add(new RaceLobbyPacket.Entrant(player.getGameProfile().getName(), carId, verdict,
                    violations == null ? List.of() : violations, values, false));
        }
        return new Roster(ids, list);
    }

    private static String declaredName(ServerPlayer player) {
        String name = declaredClass(player);
        return name == null ? "" : name;
    }

    /** そのワールドの全員へ、受け取る人ごとに組んで配る。 */
    private static void broadcastLobby(ServerLevel level, Session session) {
        List<RaceLobbyPacket.CourseInfo> courses = courseInfos(level);
        Roster roster = session == null ? null : listEntrants(level, session);
        for (ServerPlayer player : level.players()) {
            KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    lobbyFor(level, session, player, courses, roster));
        }
    }

    /**
     * クラスの一覧（記録の件数つき）を 1 人へ。ログインしたとき（レースの様子より先に。宣言したクラスがまだ
     * あるかを、届いた一覧で確かめるため）。
     */
    public static void sendClasses(ServerPlayer player) {
        RaceClassesPacket packet = new RaceClassesPacket(RaceClasses.list(player.getServer()));
        KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * クラスの一覧を全員へ。<b>組むのは 1 回</b>（記録の件数を数えるのが重い）。クラスの保存・削除・並べ替えと、
     * クラスの記録の件数が変わったとき（はじめての記録・コースの記録を消したとき）。
     */
    public static void classesToAll(MinecraftServer server) {
        RaceClassesPacket packet = new RaceClassesPacket(RaceClasses.list(server));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /**
     * コースが消された（{@code /kuruma-admin course remove}）。<b>測っていたタイムアタックを止め、記録とゴーストも消す。</b>
     * そのコースでレースをしている間は、消すこと自体を断っている（{@code RaceCommands}）。
     *
     * <p>放っておくと、ラインを判定できないまま画面の時計とゴーストが回り続けた。記録はサーバーに残り、
     * K の画面には出ないのにクラスの記録の件数には数えられていた。</p>
     */
    public static void onCourseRemoved(ServerLevel level, String name) {
        Map<UUID, LapState> states = states(level);
        for (ServerPlayer player : level.players()) {
            LapState state = states.get(player.getUUID());
            if (state != null && name.equals(state.course)) {
                states.remove(player.getUUID());
                send(player, RaceEventPacket.cleared());
            }
        }
        if (RaceData.get(level).clearCourseBests(name) > 0) {
            notifyCourseBestsCleared(level, name);
        }
        classesToAll(level.getServer());
    }

    /** 1 人へ配る。ログインしたとき・次元を移ったとき・主催者画面を開いたとき。 */
    public static void sendLobby(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                lobbyFor(level, SESSIONS.get(level.dimension()), player, courseInfos(level), null));
    }

    /**
     * レースの画面とキーからの操作（{@link RaceActionPacket}）。
     *
     * <p><b>コマンドと同じ処理を呼び、断る理由も同じ文言で返す。</b>断った結果は
     * アクションバーに出す（画面を開いたままでも読める）。</p>
     */
    public static void handleAction(ServerPlayer player, RaceActionPacket packet) {
        // 画面からは作れない値（決まりの長さが違う・数でない）は受け付けない。改造したクライアント
        if (!packet.wellFormed()) {
            com.mojang.logging.LogUtils.getLogger().warn("{} から形の合わないレースの操作が届いたので無視します: {}",
                    player.getGameProfile().getName(), packet.action());
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Session session = SESSIONS.get(level.dimension());
        switch (packet.action()) {
            case QUERY -> sendLobby(player);
            case OPEN -> {
                Course course = RaceData.get(level).course(packet.course()).orElse(null);
                if (course == null || !course.isReady()) {
                    tell(player, Component.translatable("race.kurumamod.no_course", packet.course()));
                    sendLobby(player);
                } else if (!openRace(level, course,
                        packet.laps() > 0 ? Math.min(99, packet.laps()) : course.laps(),
                        player, Regulation.fromArray(packet.regulation()))) {
                    String open = openCourse(level);
                    tell(player, open != null
                            ? Component.translatable("race.kurumamod.already_open_entry", open)
                            : Component.translatable("race.kurumamod.already_open_running", racingCourse(level)));
                    sendLobby(player);
                } else {
                    // 主催者は自分も走ることが多い。開いた人はそのまま参加しておく（抜けるのも画面から）
                    join(player);
                }
            }
            case UPDATE -> {
                if (session == null || session.started || session.countdown >= 0) {
                    sendLobby(player);
                } else if (!canManage(player)) {
                    tell(player, Component.translatable("race.kurumamod.not_host_named", session.hostName));
                } else {
                    session.regulation = Regulation.fromArray(packet.regulation());
                    if (packet.laps() > 0) {
                        session.laps = Math.min(99, packet.laps());
                    }
                    broadcastLobby(level, session);
                }
            }
            case JOIN -> {
                switch (join(player)) {
                    case NO_RACE -> tell(player, Component.translatable("race.kurumamod.no_entry"));
                    case ALREADY_STARTED -> tell(player,
                            Component.translatable("race.kurumamod.already_started", racingCourse(level)));
                    case ALREADY_JOINED -> tell(player, Component.translatable("race.kurumamod.already_joined"));
                    case JOINED -> tell(player, Component.translatable("race.kurumamod.joined")
                            .withStyle(ChatFormatting.GREEN));
                    default -> {
                    }
                }
            }
            case LEAVE -> {
                switch (leave(player)) {
                    case NOT_ENTERED -> tell(player, Component.translatable("race.kurumamod.not_entered"));
                    case RETIRED -> tell(player, Component.translatable("race.kurumamod.retired_self"));
                    case LEFT -> tell(player, Component.translatable("race.kurumamod.left"));
                    case FINISHED -> tell(player, Component.translatable("race.kurumamod.leave_finished"));
                }
                sendLobby(player);
            }
            case START -> {
                if (session == null) {
                    tell(player, Component.translatable("race.kurumamod.no_race_to_start"));
                } else if (!canManage(player)) {
                    tell(player, Component.translatable("race.kurumamod.not_host_named", session.hostName));
                } else {
                    switch (beginCountdown(level)) {
                        case ALREADY_STARTED -> tell(player,
                                Component.translatable("race.kurumamod.already_started", racingCourse(level)));
                        case NO_ENTRANTS -> tell(player,
                                Component.translatable("race.kurumamod.no_entrants_to_start"));
                        default -> {
                        }
                    }
                }
            }
            case SAVE_CLASS, DELETE_CLASS -> {
                // サーバーに残るものなので OP だけ。選んで読み込むのは誰でもできる（画面の側）
                boolean allowed = isOp(player);
                if (!allowed) {
                    tell(player, Component.translatable("race.kurumamod.class_denied"));
                } else if (packet.action() == RaceActionPacket.Action.SAVE_CLASS) {
                    String name = packet.course().trim();
                    Regulation before = RaceClasses.regulation(level.getServer(), name);
                    if (RaceClasses.put(level.getServer(), name, packet.regulation())) {
                        tell(player, Component.translatable("race.kurumamod.class_saved", name));
                        // <b>中身が変わったら、そのクラスの記録は消す</b>（前の決まりで出したタイムとは比べられない）。
                        // 同じ中身で上書きしただけなら残す
                        if (before != null && !java.util.Arrays.equals(before.toArray(),
                                Regulation.fromArray(packet.regulation()).toArray())) {
                            announceClassRecordsCleared(player, name,
                                    clearClassRecords(level.getServer(), name, false));
                        }
                    } else {
                        tell(player, Component.translatable("race.kurumamod.class_invalid"));
                    }
                } else if (RaceClasses.remove(level.getServer(), packet.course())) {
                    tell(player, Component.translatable("race.kurumamod.class_deleted", packet.course()));
                    announceClassRecordsCleared(player, packet.course(),
                            clearClassRecords(level.getServer(), packet.course(), true));
                }
                if (allowed) {
                    lobbyToAll(level.getServer());
                } else {
                    // 断ったら本人にだけ送り直す（画面を今の一覧に戻す）。全員へ配り直すと、権限の無い人が
                    // 連打するだけで、記録の数え直しとサーバー全員への配信を何度でも起こせた
                    sendClasses(player);
                    sendLobby(player);
                }
            }
            case DECLARE_CLASS -> declare(player, packet.course());
            case RECORDS -> sendRecords(player, packet.course());
            case RESET_TIME_ATTACK -> tell(player, switch (resetTimeAttack(player)) {
                case NOT_TIMING -> Component.translatable("race.kurumamod.not_timing");
                case RACING -> Component.translatable("race.kurumamod.reset_while_racing");
                case RESET -> Component.translatable("race.kurumamod.timeattack_reset");
            });
            case DELETE_RECORD -> {
                if (!isOp(player)) {
                    tell(player, Component.translatable("race.kurumamod.record_denied"));
                } else {
                    deleteRecord(player, packet.course(), packet.extra());
                }
            }
            case MOVE_CLASS -> {
                if (!isOp(player)) {
                    tell(player, Component.translatable("race.kurumamod.class_denied"));
                    sendClasses(player);
                    sendLobby(player);
                } else {
                    RaceClasses.move(level.getServer(), packet.course(), packet.laps());
                    lobbyToAll(level.getServer());
                }
            }
            case SPECTATE -> {
                if (spectate(player)) {
                    tell(player, Component.translatable("race.kurumamod.spectate_on"));
                } else {
                    sendLobby(player);
                }
            }
            case UNSPECTATE -> {
                if (unspectate(player)) {
                    tell(player, Component.translatable("race.kurumamod.spectate_off"));
                } else {
                    sendLobby(player);
                }
            }
            case KICK -> {
                if (session == null) {
                    sendLobby(player);
                } else if (!canManage(player)) {
                    tell(player, Component.translatable("race.kurumamod.not_host_named", session.hostName));
                } else {
                    kick(level, session, player, packet.course());
                }
            }
            case STOP -> {
                if (session == null) {
                    sendLobby(player);
                } else if (!canManage(player)) {
                    tell(player, Component.translatable("race.kurumamod.not_host_named", session.hostName));
                } else {
                    stopRace(level);
                    tell(player, Component.translatable("race.kurumamod.race_stopped"));
                    Session after = SESSIONS.get(level.dimension());
                    if (after != null) {
                        broadcastLobby(level, after);
                    }
                }
            }
        }
    }

    /**
     * 主催者が参加者をエントリーから外す。<b>抜けたのと同じ扱い</b>（走行中ならリタイア）で、
     * 外された本人にはそう知らせる。自分自身は外せない（抜けるなら参加したレースの画面から）。
     */
    private static void kick(ServerLevel level, Session session, ServerPlayer host, String name) {
        if (name.equals(host.getGameProfile().getName())) {
            return;
        }
        for (UUID id : new ArrayList<>(session.entrants)) {
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(id);
            if (target == null || session.retired.contains(id) || !target.getGameProfile().getName().equals(name)) {
                continue;
            }
            // <b>ゴールした人は外さない。</b>外すとリザルトから消え、後ろの人が繰り上がった
            if (session.finishOrder.containsKey(id)) {
                tell(host, Component.translatable("race.kurumamod.kick_finished", name));
                return;
            }
            Map<UUID, LapState> states = states(level);
            if (session.started && !session.showingResult) {
                // 走行中はリタイア（名簿に残し、リザルトにリタイアの行を出す）
                retire(session, states, id);
            } else {
                session.entrants.remove(id);
                states.remove(id);
            }
            send(target, RaceEventPacket.cleared());
            target.sendSystemMessage(Component.translatable("race.kurumamod.kicked", session.hostName)
                    .withStyle(ChatFormatting.GOLD));
            broadcast(level, Component.translatable("race.kurumamod.kicked_announce", name)
                    .withStyle(ChatFormatting.GRAY));
            if (session.started && !session.showingResult) {
                closeIfDone(level, session, states);
            }
            Session after = SESSIONS.get(level.dimension());
            if (after != null) {
                broadcastLobby(level, after);
            }
            sendLobby(target);
            return;
        }
    }

    /**
     * クラスを保存・削除してよいか。<b>OP と、シングルプレイの本人。</b>
     *
     * <p>チートを切ったシングルプレイでは本人も OP の権限を持たないが、ほかに誰もいないワールドで
     * 保存を断る理由は無い（以前は入力欄が黙って打てなくなっていた）。</p>
     */
    private static boolean isOp(ServerPlayer player) {
        return player.hasPermissions(2) || player.getServer() != null
                && player.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * タイムアタックの記録を 1 つ消す（OP が K の画面から）。消したら、記録の持ち主にも知らせる。
     *
     * <p><b>ゴーストは持ち主のクライアントにある。</b>持ち主が入っていれば、知らせを受けてその場で消す。入っていなければ
     * 手元に残るが、次にそのコースを走り出したとき、サーバーのベストと周が合わないので消える（{@code CarGhosts}）。</p>
     *
     * @param target 「消す人の UUID」と「クラスの名前（空ならクラスなし）」を空白 1 つで区切ったもの
     */
    private static void deleteRecord(ServerPlayer player, String course, String target) {
        int space = target.indexOf(' ');
        UUID id;
        try {
            id = UUID.fromString(space < 0 ? target : target.substring(0, space));
        } catch (IllegalArgumentException e) {
            return;
        }
        String raceClass = space < 0 ? "" : target.substring(space + 1);
        MinecraftServer server = player.getServer();
        RaceData data = RaceData.get((ServerLevel) player.level());
        RaceData.Entry entry = data.entry(id, course, raceClass);
        String name = entry == null ? null : displayName(server, entry);
        if (name == null || !data.removeBest(id, course, raceClass)) {
            tell(player, Component.translatable("race.kurumamod.record_not_found"));
            sendRecords(player, course);
            return;
        }
        Component board = raceClass.isEmpty()
                ? Component.translatable("screen.kurumamod.time_attack.overall") : Component.literal(raceClass);
        player.sendSystemMessage(Component.translatable("race.kurumamod.record_deleted", name, course, board)
                .withStyle(ChatFormatting.GRAY));
        ServerPlayer owner = server.getPlayerList().getPlayer(id);
        if (owner != null) {
            // 持ち主のゴーストと、HUD の比べる相手を消させる（消した 1 つだけ）。持ち主が別の次元にいても送る。
            // ゴーストは知らせに載せた次元のフォルダから消し、HUD は今いる次元のときだけ変える
            String dimension = dimensionId(player.level());
            send(owner, raceClass.isEmpty() ? RaceEventPacket.noClassCleared(dimension, course)
                    : RaceEventPacket.bestCleared(dimension, course, raceClass));
            if (owner != player) {
                owner.sendSystemMessage(Component.translatable("race.kurumamod.record_deleted_owner",
                        course, board, player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
            }
        }
        // クラスの記録の件数が変わる。消した本人の画面は、すぐ新しいランキングに
        classesToAll(server);
        sendRecords(player, course);
    }

    /**
     * そのコースの記録（クラスなしと、サーバーにある全クラス）を送る。K のタイムアタックの画面から頼まれる。
     *
     * <p>記録がまだ無いクラスも空のランキングとして送る（画面で「まだ記録がありません」と出すため）。</p>
     */
    private static void sendRecords(ServerPlayer player, String course) {
        RaceData data = RaceData.get((ServerLevel) player.level());
        List<TimeAttackRecordsPacket.Board> boards = new ArrayList<>();
        boards.add(board(data, player, course, RaceData.NO_CLASS));
        for (String raceClass : RaceClasses.names(player.getServer())) {
            boards.add(board(data, player, course, raceClass));
        }
        KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TimeAttackRecordsPacket(course, boards));
    }

    private static TimeAttackRecordsPacket.Board board(RaceData data, ServerPlayer player, String course,
                                                       String raceClass) {
        List<RaceData.Entry> all = data.leaderboard(course, raceClass, 0);
        List<TimeAttackRecordsPacket.Row> rows = new ArrayList<>();
        boolean listedSelf = false;
        for (int i = 0; i < all.size(); i++) {
            RaceData.Entry entry = all.get(i);
            boolean you = entry.player().equals(player.getUUID());
            if (i < TimeAttackRecordsPacket.TOP || you && !listedSelf) {
                rows.add(new TimeAttackRecordsPacket.Row(i + 1, displayName(player.getServer(), entry),
                        entry.millis(), you, entry.player()));
                listedSelf |= you;
            }
            if (i >= TimeAttackRecordsPacket.TOP && listedSelf) {
                break;
            }
        }
        return new TimeAttackRecordsPacket.Board(raceClass, all.size(), rows);
    }

    /** 記録に名前が入っていなければ（古い記録）プロフィールキャッシュで引く。 */
    private static String displayName(MinecraftServer server, RaceData.Entry entry) {
        if (!entry.name().isEmpty()) {
            return entry.name();
        }
        return server.getProfileCache() == null ? "?" : server.getProfileCache().get(entry.player())
                .map(com.mojang.authlib.GameProfile::getName).orElse("?");
    }

    /** クラスの記録を消したことを、消した本人のチャットに残す（アクションバーは保存の知らせで埋まる）。 */
    private static void announceClassRecordsCleared(ServerPlayer player, String name, int removed) {
        if (removed > 0) {
            player.sendSystemMessage(Component.translatable("race.kurumamod.class_records_cleared", name, removed)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /** 画面を開いたままでも読めるよう、アクションバーに出す。 */
    private static void tell(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
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
    public static boolean openRace(ServerLevel level, Course course, int laps, ServerPlayer host,
                                   Regulation regulation) {
        if (SESSIONS.containsKey(level.dimension())) {
            return false;
        }
        Session session = new Session(course.name(), laps);
        session.regulation = regulation;
        session.host = host.getUUID();
        session.hostName = host.getGameProfile().getName();
        SESSIONS.put(level.dimension(), session);
        RaceSync.sendToAll(level.getServer());
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("race.kurumamod.opened", session.hostName, course.name(), laps)
                        .withStyle(ChatFormatting.YELLOW), false);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("race.kurumamod.how_to_join").withStyle(ChatFormatting.GRAY), false);
        broadcastLobby(level, session);
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
        return !hostOnline && session.entrants.contains(player.getUUID())
                && !session.retired.contains(player.getUUID());
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
        /** すでに入っている。 */
        ALREADY_JOINED,
        /** 入れた。 */
        JOINED
    }

    /** 受付中のレースにエントリーする。 */
    public static JoinResult join(ServerPlayer player) {
        Session session = SESSIONS.get(player.level().dimension());
        if (session == null) {
            return JoinResult.NO_RACE;
        }
        // 途中参加は不可。信号が点いた時点で締め切る（点いてから入ると、決まりの判定を通らずに走れてしまう）
        if (session.started || session.countdown >= 0) {
            return JoinResult.ALREADY_STARTED;
        }
        if (!session.entrants.add(player.getUUID())) {
            return JoinResult.ALREADY_JOINED;
        }
        session.spectators.remove(player.getUUID());
        session.names.put(player.getUUID(), player.getGameProfile().getName());
        // 測っていたタイムアタック（ウォームアップ中も）は捨て、時計を消す。号砲まではタイムアタックを測らない
        LapState dropped = states((ServerLevel) player.level()).remove(player.getUUID());
        if (dropped != null && (dropped.timing || dropped.course != null)) {
            send(player, RaceEventPacket.cleared());
        }
        broadcast((ServerLevel) player.level(), Component.translatable("race.kurumamod.joined_announce",
                player.getGameProfile().getName(), session.entrants.size()));
        broadcastLobby((ServerLevel) player.level(), session);
        return JoinResult.JOINED;
    }

    /**
     * 観戦に切り替える。<b>エントリーはせず、レースの HUD（信号・順位・リザルト）だけを受け取る。</b>
     *
     * <p>エントリーしていたなら取り消す（走行中ならリタイア）。ゴールした人はリザルトに残すため切り替えない。
     * 観戦者は信号の間も車を止められず、G の制限も掛からない。</p>
     *
     * @return 切り替えたか
     */
    public static boolean spectate(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        Session session = SESSIONS.get(level.dimension());
        UUID id = player.getUUID();
        if (session == null || session.showingResult || session.finishOrder.containsKey(id)
                || session.spectators.contains(id)) {
            return false;
        }
        if (session.entrants.contains(id) && !session.retired.contains(id)) {
            leave(player);
            // 最後の 1 人が抜けると、その場で畳まれることがある
            session = SESSIONS.get(level.dimension());
            if (session == null || session.showingResult) {
                return false;
            }
        }
        session.spectators.add(id);
        // 測っていたタイムアタックは捨てる（観戦を優先する）
        states(level).remove(id);
        // 信号で止まっていた車を放す・タイムアタックの時計を消す（以後の信号は観戦者として受け取る）
        send(player, RaceEventPacket.cleared());
        broadcastLobby(level, session);
        return true;
    }

    /** 観戦をやめる。レースの HUD は消える。 */
    public static boolean unspectate(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        Session session = SESSIONS.get(level.dimension());
        if (session == null || !session.spectators.remove(player.getUUID())) {
            return false;
        }
        send(player, RaceEventPacket.cleared());
        broadcastLobby(level, session);
        return true;
    }

    /** {@link #leave} の結果。 */
    public enum LeaveResult {
        /** そもそも入っていない。 */
        NOT_ENTERED,
        /** 受付中に取り消した。 */
        LEFT,
        /** 走行中に抜けた＝リタイア。 */
        RETIRED,
        /** もうゴールしている（リザルトに残すので抜けられない）。 */
        FINISHED
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
        UUID id = player.getUUID();
        if (session == null || !session.entrants.contains(id) || session.retired.contains(id)) {
            return LeaveResult.NOT_ENTERED;
        }
        // <b>ゴールした人は抜けられない。</b>抜けるとリザルトから消え、2 位の人が 1 位として並んだ
        if (session.finishOrder.containsKey(id)) {
            return LeaveResult.FINISHED;
        }
        Map<UUID, LapState> states = states(level);
        send(player, RaceEventPacket.cleared());
        if (!session.started || session.showingResult) {
            session.entrants.remove(id);
            states.remove(id);
            broadcast(level, Component.translatable("race.kurumamod.left_announce",
                    player.getGameProfile().getName(), session.entrants.size()));
            broadcastLobby(level, session);
            return LeaveResult.LEFT;
        }
        // 走行中に抜けたらリタイア（名簿に残し、リザルトにリタイアの行を出す）
        retire(session, states, id);
        broadcast(level, Component.translatable("race.kurumamod.retired",
                player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
        broadcastLobby(level, session);
        closeIfDone(level, session, states);
        return LeaveResult.RETIRED;
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
        // 決まりに外れたまま信号を迎えた人は観戦にする（名簿から外す）。
        // 車に乗っていない人は判定できないので残す——乗った時点でレース中の判定に掛かる
        for (UUID id : new ArrayList<>(session.entrants)) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            List<Regulation.Violation> violations = player == null ? null : violations(player, session.regulation);
            if (violations != null && !violations.isEmpty()) {
                session.entrants.remove(id);
                session.spectators.add(id);
                player.sendSystemMessage(Component.translatable("race.kurumamod.spectating")
                        .withStyle(ChatFormatting.GOLD));
                broadcast(level, Component.translatable("race.kurumamod.spectating_announce",
                        player.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
            }
        }
        if (session.entrants.isEmpty()) {
            broadcastLobby(level, session);
            return StartResult.NO_ENTRANTS;
        }
        session.countdown = 0;
        session.holdTicks = HOLD_MIN + level.random.nextInt(HOLD_MAX - HOLD_MIN + 1);
        // 参加者が測っていたタイムアタックは捨て、<b>捨てたことを伝える</b>。伝えないと信号の間も
        // タイムアタックの時計が回り続け、そのコースのゴーストもグリッドの前を走り続ける
        Map<UUID, LapState> lapStates = states(level);
        for (UUID id : session.entrants) {
            LapState dropped = lapStates.remove(id);
            ServerPlayer entrant = level.getServer().getPlayerList().getPlayer(id);
            if (dropped != null && (dropped.timing || dropped.finished) && entrant != null) {
                send(entrant, RaceEventPacket.cleared());
            }
        }
        broadcast(level, Component.translatable("race.kurumamod.starting",
                session.course, session.laps).withStyle(ChatFormatting.YELLOW));
        broadcastLobby(level, session);
        return StartResult.STARTED;
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
            broadcast(level, raceOver(session)
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
     * レースの画面（K）の「抜ける」 を使う。</p>
     */
    public static ResetResult resetTimeAttack(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        Map<UUID, LapState> states = states(level);
        LapState state = states.get(player.getUUID());
        // ウォームアップ中（コースは決まったが、まだ測っていない）も取り消せる
        if (state == null || !state.timing && state.course == null) {
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

    /** そのコースでレースを受け付けているか、走っているか。線を引き直させない（{@code RaceCommands}）。 */
    public static boolean raceOnCourse(ServerLevel level, String course) {
        Session session = SESSIONS.get(level.dimension());
        return session != null && session.course.equals(course);
    }

    /**
     * そのコースの線が変わった（引き直し・チェックポイントの追加・向きの変更）。<b>測っていた人の計測を止める。</b>
     *
     * <p>止めないと、走りかけの周が古い線の続きとして測られる。チェックポイントを足したときは、
     * 走っている全員の周が「取りこぼし」で無効になり、1 周余計に走らされた。</p>
     */
    public static void onCourseChanged(ServerLevel level, String course) {
        Map<UUID, LapState> states = states(level);
        for (ServerPlayer player : level.players()) {
            LapState state = states.get(player.getUUID());
            if (state != null && course.equals(state.course)) {
                states.remove(player.getUUID());
                send(player, RaceEventPacket.cleared());
                player.sendSystemMessage(Component.translatable("race.kurumamod.course_changed_timing", course)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
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
        /** 規定周回数。受付中は主催者が変えられる。 */
        private int laps;
        /**
         * エントリーした人。
         *
         * <p><b>参加は明示的に申告してもらう。</b>そのワールドにいる全員を参加者にすると、
         * 同じワールドで別コースを走っている人や、ただ見ている人まで
         * カウントダウンで操作を奪われる。</p>
         */
        private final java.util.Set<UUID> entrants = new java.util.LinkedHashSet<>();
        /**
         * 観戦している人。エントリーはせず、レースの HUD（信号・順位・リザルト）だけを受け取る。
         * <b>参加者と重ならない</b>（入ったら外し、観戦に切り替えたらエントリーを取り消す）。
         */
        private final java.util.Set<UUID> spectators = new java.util.LinkedHashSet<>();
        /**
         * 走行中に落ちた（別の次元へ移った）参加者。<b>名簿には残す</b>（リザルトに完走していない人として出す）が、
         * 入り直してもそのレースには数えない（HUD を出さず、参加していない人として扱う）。
         */
        private final java.util.Set<UUID> retired = new java.util.HashSet<>();
        /**
         * 落ちた・リタイアした参加者のレースの走り。<b>リザルトを出し終えるまで覚えておく</b>
         * （その人の今の状態は消えているか、入り直してタイムアタックのものになっている）。
         */
        private final Map<UUID, LapState> kept = new HashMap<>();
        /** エントリーしたときの名前。落ちた人もリザルトに名前で出す。 */
        private final Map<UUID, String> names = new HashMap<>();
        /** 走行中の参加者が車を降りてからのティック数（{@link #watchAway}）。車に戻れば消す。 */
        private final Map<UUID, Integer> awayTicks = new HashMap<>();
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
        /** 決まり。受付中は主催者が変えられる。 */
        private Regulation regulation = Regulation.DEFAULT;

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
        /**
         * 号砲で 1 周目を始めたグリッドが、まだスタートラインを越えていない（ラインの手前に並んだ）。
         * 次の 1 回の通過は周回の終わりと数えない。CP のあるコースでは最初の CP を通ったところでも下ろす。
         */
        private boolean gridStart;
        /**
         * この周を始めたときに宣言していたクラス（宣言していない・レースの周なら null）。
         * デルタとベストの比べる相手で、宣言を周の途中で変えても次の周まで変わらない。
         */
        private String raceClass;
        /**
         * スタートラインを越えたときの候補のトラック（共有のスタートラインなら 2 つ以上）。ウォームアップ中に
         * チェックポイントで絞る。{@link #course} は候補の先頭。
         */
        private List<String> candidates = List.of();
        /**
         * この周を始めたときのクラスの中身（{@link Regulation#toArray}）。周の終わりに今の中身と比べ、
         * 途中で書き換えられていたらクラスの記録に入れない。
         */
        private double[] classRules;

        private LapState(String course) {
            this.course = course;
        }

        private void passGate(double millis) {
            progress++;
            gateTimes.put(progress, millis);
        }
    }
}
