package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.hook.GuiRender;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.network.RaceStandingsPacket;
import com.jdmmc.kurumamod.race.RaceManager;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * レース中の表示。<b>見た目は中継グラフィック（斜めの帯）</b>——左上に順位の塔、右上にラップ時間と
 * CP の差、画面の中ほど上に知らせ（CP 通過・ベストラップ・周回無効・最終周・ゴール）。
 *
 * <p>走行中のタイムはサーバーから毎ティック貰わず、<b>「ラップが始まった」を受け取ってから
 * 自分で数える</b>。確定値はラップ完了時にサーバーの値で上書きされるので、ずれは残らない。</p>
 *
 * <p><b>数えるのはティックであって実時間ではない。</b>サーバーはティック数（{@code getTickCount()}）で
 * ラップタイムを確定させているので、こちらが壁時計で数えると<b>TPS が落ちたぶんだけ表示が先に進み</b>、
 * ゴールした瞬間に数字が戻る。シングルプレイでポーズしている間も進んでしまう。</p>
 *
 * <p>メーター（速度・ギア・回転）は {@code CarGaugeHud} のまま。ここでは描かない。</p>
 */
public final class RaceHud {

    private static final int MARGIN = 12;
    private static final int TOWER_WIDTH = 125;
    /**
     * 知らせ（CP・ラップの差、ベストラップ、周回無効、計測開始）を出す高さ。<b>画面の上の真ん中</b>——
     * 左上の塔と右上の時計の間は走っている間は空いていて、真ん中より下に出すと前が見えなくなる。
     */
    private static final int NOTICE_TOP = MARGIN;
    /** タイムアタックの区間の塔が使ってよい画面の高さの割合と、最低の行数。 */
    private static final double SECTOR_TOWER_SHARE = 0.35;
    private static final int SECTOR_ROWS_MIN = 4;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF111111;
    private static final int PANEL = 0xD8111111;
    private static final int ROW = 0xC7111111;
    private static final int POS = 0xFF2A2A2A;
    private static final int GREY = 0xFFC8CCD2;
    /** ベストより速い。 */
    private static final int FASTER = 0xFF15803D;
    /** ベストより遅い。 */
    private static final int SLOWER = 0xFFB91C1C;
    /** 自己ベスト。 */
    private static final int BEST = 0xFF6D28D9;
    private static final int GOLD = 0xFFFFD24A;
    private static final int VOID = 0xFFFF8A00;
    /** 帯の色（順位の塔の自分の番号・ゴールの帯）。 */
    private static final int ACCENT = RaceLobby.ACCENT;

    private static final int LIGHT_OFF = 0xFF2A1110;
    private static final int LIGHT_ON = 0xFFFF3B30;
    private static final int LIGHT_GO = 0xFF22C55E;

    /** 区間タイムを出しておく長さ [ティック]。 */
    private static final int SPLIT_TICKS = 60;
    /** 周回が無効だったときの表示。<b>読み落とすと 1 周ぶん損をする</b>ので長めに。 */
    private static final int VOID_TICKS = 100;
    /** ゴールの大きな表示を出しておく長さ [ティック]。 */
    private static final int FINISH_TICKS = 120;
    /** 最終周の帯を出しておく長さ [ティック]。 */
    private static final int FINAL_TICKS = 60;

    /** 点灯しているライトの数。0 かつ {@link #frozen} が false ならスタート済み。 */
    private static int lights;
    private static boolean frozen;
    /** 消灯した瞬間からの経過ティック。少しの間 GO を出すために使う。 */
    private static int goTicks;
    /** タイムアタックの計測が始まったときの帯を出しておく残りティック。 */
    private static int attackStartTicks;
    private static final int ATTACK_START_TICKS = 50;
    /** タイムアタックのウォームアップ中か（1 回目にラインを越えてから、計測が始まるまで）。 */
    private static boolean warmingUp;
    private static String warmUpCourse = "";

    private static boolean timing;
    private static int lap;
    private static int totalLaps;
    /** 今の周が始まってからのティック数。実時間ではなくこれで数える。 */
    private static int lapTicks;
    /** 観戦中の時計（号砲からの経過ティック）。サーバーの順位表で合わせ、間はクライアントで進める。 */
    private static double watchTicks;
    private static boolean watchKnown;
    private static long lastLapMillis;
    private static long bestMillis;
    private static boolean lastWasBest;
    private static int place;
    /** ゴールまでの総時間 [ms]。ゴール後はこれを出したまま止める。 */
    private static long finishMillis;
    /** 画面中央に大きく「ゴール」を出しておく残りティック。 */
    private static int finishTicks;
    /** 最終周の帯の残りティック。 */
    private static int finalTicks;
    private static String courseName = "";
    /** 今の周の比べる相手のクラス（空なら総合）。タイムアタックの札に出す。 */
    private static String lapClass = "";
    /** 宣言しているクラスの決まりに、乗っている車が外れているか（{@link #CLASS_CHECK_TICKS} ごとに測る）。 */
    private static boolean classViolating;
    private static int classCheckTicks;
    private static final int CLASS_CHECK_TICKS = 10;

    /** 区間・ラップの通過表示。残りティックが 0 なら出さない。 */
    private static int splitTicks;
    /** 何の通過か。0 ならラップ（ゴールライン）、1 以上なら CP の番号。 */
    private static int splitIndex;
    private static String splitTime = "";
    private static long splitDelta;
    private static boolean splitHasDelta;
    /** ラップ（ゴールライン）でベストを更新したか。紫の帯で出す。 */
    private static boolean splitBest;
    /** 警告（周回が無効）として出しているか。 */
    private static boolean splitAlert;

    /** 右上に出したままにする、この周で最後に通った CP の差。 */
    private static int lastCheckpoint;
    private static long lastCheckpointDelta;
    private static boolean lastCheckpointHasDelta;

    /** 通ったチェックポイントの数と本数。0 本のコースでは出さない。 */
    private static int checkpointsPassed;
    private static int checkpointCount;

    /** 区間（タイムアタックの塔）。CP の数 + 1 本。 */
    private static long[] sectorTimes = new long[0];
    private static long[] sectorDiffs = new long[0];
    private static boolean[] sectorHasDiff = new boolean[0];
    private static long previousSplit;
    private static long previousDelta;
    private static boolean previousHasDelta;
    /** 区間を出しきった（周を終えた）。次の CP で新しい周の区間へ切り替える。 */
    private static boolean sectorsComplete;

    /** レースの一覧（走行中・リザルト）。空なら何も出さない。 */
    private static List<RaceStandingsPacket.Row> standings = List.of();
    private static RaceStandingsPacket.Phase phase = RaceStandingsPacket.Phase.ENTRY;
    private static String course = "";
    private static int courseLaps;
    /** リザルトを Esc で閉じた。次のリザルトが届くまで出さない。 */
    private static boolean resultsDismissed;

    private RaceHud() {
    }

    /** カウントダウン中は運転操作を受け付けない。 */
    public static boolean isFrozen() {
        // 観戦者には信号を見せるが、車は止めない
        return frozen && !RaceLobby.spectating();
    }

    /** タイムアタックを計測しているか、ウォームアップ中か（レースの周ではない）。 */
    public static boolean isTimeAttacking() {
        return timing && totalLaps == 0 || warmingUp && !timing;
    }

    /** このレースをゴールしたか。ゴールした人は G セッティングを縛らない。 */
    public static boolean hasFinished() {
        return place > 0;
    }

    /** 順位表・リザルトを受け取る。 */
    public static void acceptStandings(RaceStandingsPacket packet) {
        if (packet.phase() == RaceStandingsPacket.Phase.RESULT && phase != RaceStandingsPacket.Phase.RESULT) {
            resultsDismissed = false;
            // レースは終わった（全員ゴール・主催者が中止）。走っている途中の人の時計もここで止める。
            // 止めないと、リザルトを閉じたあとに回り続ける時計が出てくる
            timing = false;
            splitTicks = 0;
            finalTicks = 0;
        }
        if (packet.phase() == RaceStandingsPacket.Phase.RUNNING) {
            double server = packet.elapsedMillis() / 50.0;
            // 届くたびに合わせると、10 ティックごとに数字が少し戻って見える。ずれが大きいときだけ合わせる
            if (!watchKnown || Math.abs(server - watchTicks) > 3.0) {
                watchTicks = server;
            }
            watchKnown = true;
        } else {
            watchKnown = false;
        }
        standings = packet.rows();
        phase = packet.phase();
        course = packet.course();
        courseLaps = packet.laps();
    }

    public static void accept(RaceEventPacket packet) {
        switch (packet.kind()) {
            case COUNTDOWN -> {
                boolean wasCountdown = frozen;
                // カウントダウン中のパケットは毎ティック届く。増えた瞬間だけ鳴らす
                if (packet.lights() > lights) {
                    RaceSounds.light();
                }
                lights = packet.lights();
                frozen = packet.frozen();
                if (wasCountdown && !frozen) {
                    goTicks = 40;
                    RaceSounds.start();
                }
            }
            case WARM_UP -> {
                warmingUp = true;
                warmUpCourse = packet.course();
                lapClass = packet.raceClass();
                RaceSounds.light();
            }
            case LAP_STARTED -> {
                warmingUp = false;
                if (packet.totalLaps() == 0 && !timing) {
                    // タイムアタックの計測が始まった。ラインを越えた瞬間だと分かるように、音と帯で知らせる
                    attackStartTicks = ATTACK_START_TICKS;
                    RaceSounds.start();
                }
                timing = true;
                lap = packet.lap();
                totalLaps = packet.totalLaps();
                courseName = packet.course();
                lapClass = packet.raceClass();
                if (totalLaps == 0) {
                    // BEST は、この周の比べる相手（宣言したクラスか総合）のベスト
                    bestMillis = packet.targetBest();
                }
                lapTicks = 0;
                place = 0;
                finishMillis = 0L;
                finishTicks = 0;
                splitTicks = 0;
                checkpointsPassed = 0;
                checkpointCount = Math.max(0, packet.sectorCount() - 1);
                resetSectors();
                lastCheckpoint = 0;
                if (totalLaps > 1 && lap == totalLaps) {
                    finalTicks = FINAL_TICKS;
                }
            }
            case SPLIT -> {
                checkpointsPassed = packet.sector();
                checkpointCount = Math.max(0, packet.sectorCount() - 1);
                if (sectorsComplete) {
                    resetSectors();
                }
                recordSector(packet.sector() - 1, packet.millis(), packet.delta(), packet.hasDelta());
                lastCheckpoint = packet.sector();
                lastCheckpointDelta = packet.delta();
                lastCheckpointHasDelta = packet.hasDelta();
                showSplit(packet.sector(), packet.millis(), packet.delta(), packet.hasDelta(), false);
                RaceSounds.split(packet.delta(), packet.hasDelta());
            }
            case LAP_VOID -> {
                // 取りこぼしたチェックポイントの数は捨てない。この先で通えば周回が成立する
                checkpointsPassed = packet.sector();
                checkpointCount = Math.max(0, packet.sectorCount() - 1);
                splitTicks = VOID_TICKS;
                splitTime = "";
                splitHasDelta = false;
                splitBest = false;
                splitAlert = true;
            }
            case LAP_COMPLETED -> {
                recordSector(checkpointCount, packet.millis(), packet.delta(), packet.hasDelta());
                sectorsComplete = true;
                lap = packet.lap();
                totalLaps = packet.totalLaps();
                courseName = packet.course();
                lapClass = packet.raceClass();
                lastLapMillis = packet.millis();
                bestMillis = totalLaps == 0 ? packet.targetBest() : packet.bestMillis();
                lastWasBest = packet.best();
                lapTicks = 0;
                checkpointsPassed = 0;
                lastCheckpoint = 0;
                showSplit(0, packet.millis(), packet.delta(), packet.hasDelta(), packet.best());
                if (totalLaps > 1 && lap == totalLaps) {
                    finalTicks = FINAL_TICKS;
                }
                // ゴールラインもチェックポイントと同じ音。速い／遅いでピッチが振れる
                RaceSounds.split(packet.delta(), packet.hasDelta());
            }
            case FINISHED -> {
                timing = false;
                lastLapMillis = packet.millis();
                bestMillis = packet.bestMillis();
                place = packet.place();
                finishMillis = packet.total();
                finishTicks = FINISH_TICKS;
                splitTicks = 0;     // 区間の表示と重ねない
                finalTicks = 0;
                RaceSounds.finish();
            }
            case CLEARED -> clear();
            case BEST_CLEARED -> {
                // 記録が消された（クラスの書き換え・削除、コースの引き直し、/kuruma best clear）。
                // 今の周の比べる相手が消えたなら、周の終わりを待たずに BEST を消す
                boolean classGone = !packet.raceClass().isEmpty() && packet.raceClass().equals(lapClass);
                boolean courseGone = packet.raceClass().isEmpty()
                        && (packet.course().isEmpty() || packet.course().equals(courseName));
                if (totalLaps == 0 && (classGone || courseGone)) {
                    bestMillis = 0L;
                }
                TimeAttackScreen.recordsChanged();
            }
            default -> {
            }
        }
    }

    private static void resetSectors() {
        int count = checkpointCount + 1;
        sectorTimes = new long[count];
        sectorDiffs = new long[count];
        sectorHasDiff = new boolean[count];
        Arrays.fill(sectorTimes, -1L);
        previousSplit = 0L;
        previousDelta = 0L;
        previousHasDelta = true;
        sectorsComplete = false;
    }

    /** 区間 {@code index} のタイムと、自己ベストの同じ区間との差を覚える。 */
    private static void recordSector(int index, long elapsed, long delta, boolean hasDelta) {
        if (index < 0 || index >= sectorTimes.length) {
            return;
        }
        sectorTimes[index] = elapsed - previousSplit;
        sectorHasDiff[index] = hasDelta && previousHasDelta;
        sectorDiffs[index] = delta - previousDelta;
        previousSplit = elapsed;
        previousDelta = delta;
        previousHasDelta = hasDelta;
    }

    /** 走行中の表示を消す。サーバーが周回状態を捨てたときの合図。 */
    private static void clear() {
        timing = false;
        frozen = false;
        lights = 0;
        place = 0;
        finishMillis = 0L;
        finishTicks = 0;
        finalTicks = 0;
        splitTicks = 0;
        attackStartTicks = 0;
        warmingUp = false;
        checkpointsPassed = 0;
        checkpointCount = 0;
        lastCheckpoint = 0;
        sectorTimes = new long[0];
        lapClass = "";
        // <b>順位表もここで消すこと。</b>抜けた人へは以後何も届かないので、
        // 消さないと最後に受け取った一覧が画面に残り続ける
        standings = List.of();
        watchKnown = false;
    }

    /**
     * 接続が変わったら全部捨てる。
     *
     * <p><b>この画面の状態はすべて {@code static} なので、放っておくとプロセスが生きている限り
     * 残る。</b>計測中にサーバーを抜けると、次に入ったところでも時計が回ったままになる。
     * カウントダウン中に抜けたときは {@link #isFrozen()} が true のまま残って<b>運転操作を受け付けなくなる</b>。</p>
     *
     * <p>入るときと出るときの両方で捨てる。落ちかたによっては {@code LoggingOut} が
     * 来ないことがあるので、<b>入る側だけが最後の砦</b>になる。</p>
     */
    public static void onLoggingIn() {
        reset();
    }

    public static void onLoggingOut() {
        reset();
    }

    private static void reset() {
        clear();
        lap = 0;
        totalLaps = 0;
        lapTicks = 0;
        lastLapMillis = 0L;
        bestMillis = 0L;
        lastWasBest = false;
        goTicks = 0;
        splitTime = "";
        splitDelta = 0L;
        splitHasDelta = false;
        splitAlert = false;
        splitBest = false;
        phase = RaceStandingsPacket.Phase.ENTRY;
        course = "";
        courseName = "";
        courseLaps = 0;
    }

    private static void showSplit(int index, long millis, long delta, boolean hasDelta, boolean best) {
        splitTicks = SPLIT_TICKS;
        splitIndex = index;
        splitTime = RaceManager.format(millis);
        splitDelta = delta;
        splitHasDelta = hasDelta;
        splitBest = best;
        splitAlert = false;
    }

    /**
     * 時間を進めるのはここだけ。
     *
     * <p>描画側で数えると<b>フレームレートで速さが変わる</b>。以前は GO の表示と
     * 区間タイムの表示がこれで、120fps なら 2 秒のはずが 0.7 秒しか出なかった。</p>
     */
    /** リザルト（ゴールの帯と結果の表）を出しているか。 */
    /**
     * リザルトを出しているか。出している間は<b>メーターと操作ヘルプを引っ込める</b>
     * （どちらも HUD の最後に描くので、暗幕の上に明るく残り、小さい画面では表を隠す）。
     */
    public static boolean isShowingResults() {
        return showingResults();
    }

    private static boolean showingResults() {
        return phase == RaceStandingsPacket.Phase.RESULT && !standings.isEmpty() && !resultsDismissed;
    }

    /**
     * リザルトを出している間の Esc は、ポーズ画面ではなく<b>リザルトを閉じる</b>。
     * 15 秒待たずに走りへ戻れるように。開いたポーズ画面をすぐ閉じる形で受ける
     * （Esc のキーそのものはバニラが先に取るので、ここで横取りできない）。
     */
    private static void dismissResultsOnEscape() {
        Minecraft minecraft = Minecraft.getInstance();
        if ((showingResults() || finishTicks > 0) && minecraft.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            resultsDismissed = true;
            finishTicks = 0;
            minecraft.setScreen(null);
        }
    }

    public static void onClientTick() {
        dismissResultsOnEscape();
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        if (timing) {
            lapTicks++;
        }
        if (watchKnown) {
            watchTicks++;
        }
        if (goTicks > 0 && !frozen) {
            goTicks--;
        }
        if (attackStartTicks > 0) {
            attackStartTicks--;
        }
        if (splitTicks > 0) {
            splitTicks--;
        }
        if (finishTicks > 0) {
            finishTicks--;
        }
        if (finalTicks > 0) {
            finalTicks--;
        }
        if (--classCheckTicks <= 0) {
            classCheckTicks = CLASS_CHECK_TICKS;
            classViolating = checkClassViolation();
        }
    }

    /** 乗っている車が、宣言しているクラスの決まりに外れているか。サーバーの判定と同じ式。 */
    private static boolean checkClassViolation() {
        Regulation regulation = TimeAttackClass.regulation();
        CarEntity car = CarClientDriver.ridingCar(Minecraft.getInstance());
        if (regulation == null || car == null) {
            return false;
        }
        return !regulation.check(car.getSpec(), CarTypes.get(car.getCarId()).spec()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 描画
    // ------------------------------------------------------------------

    /**
     * ホットバーの層を描き終えた直後に呼ばれる（NeoForge は {@code RenderGuiLayerEvent.Post} の
     * {@code VanillaGuiLayers.HOTBAR}、Fabric は {@code Gui#renderHotbarAndDecorations} の終わりへの mixin）。
     * チャットなど後ろの層より下に描くため、HUD 全体の後（{@code RenderGuiEvent.Post}）ではなくここで描く。
     */
    public static void onRenderOverlay(GuiRender event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        RaceLobbyPacket lobby = RaceLobby.lobby();

        boolean results = showingResults();
        if (results) {
            graphics.fill(0, 0, width, height, 0x59000000);
        }

        // 左上: 順位の塔（グリッドではエントリー、タイムアタックでは区間）
        if (!results) {
            if (frozen && lobby != null && (lobby.joined() || lobby.spectating())) {
                renderGridTower(graphics, font, lobby);
            } else if (timing && totalLaps == 0) {
                renderSectorTower(graphics, font, height);
            } else if (phase == RaceStandingsPacket.Phase.RUNNING && !standings.isEmpty()) {
                renderTower(graphics, font, minecraft);
            }
        }

        // 右上: 時計・CP の差・CP の進み・前回とベスト
        if ((timing || place > 0) && !results && !(resultsDismissed && phase == RaceStandingsPacket.Phase.RESULT)) {
            int bottom = renderClock(graphics, font, width, partialTick);
            if (timing && totalLaps == 0) {
                renderClassTag(graphics, font, width, bottom + 2, minecraft);
            }
        } else if (!results && !RaceLobby.racingNow() && !RaceLobby.spectating()) {
            // 走り出す前（ラインを跨ぐ前）: ウォームアップ中であることと、どのクラスで測るか
            int y = renderWarmUp(graphics, font, width, minecraft);
            renderClassTag(graphics, font, width, y, minecraft);
        } else if (RaceLobby.spectating() && watchKnown && phase == RaceStandingsPacket.Phase.RUNNING && !results) {
            renderWatchClock(graphics, font, width, partialTick);
        }

        // スタートの灯とレギュレーションの帯
        if (frozen || goTicks > 0) {
            renderLights(graphics, font, width);
            if (frozen && lobby != null && (lobby.joined() || lobby.spectating())) {
                renderRegulationBand(graphics, font, width, height);
            }
        }

        // 知らせ
        if (attackStartTicks > 0 && timing && totalLaps == 0 && splitTicks == 0) {
            renderAttackStart(graphics, font, width);
        }
        if (finishTicks > 0) {
            renderFinishBand(graphics, font, width);
        } else if (splitTicks > 0) {
            if (splitAlert) {
                renderVoid(graphics, font, width);
            } else if (splitBest) {
                renderBestLap(graphics, font, width);
            } else {
                renderSplit(graphics, font, width);
            }
        }
        if (finalTicks > 0 && finishTicks == 0 && !splitAlert) {
            renderFinalLap(graphics, font, width);
        }

        // リザルト
        if (results) {
            if (finishTicks == 0) {
                renderFinishBand(graphics, font, width);
            }
            renderResults(graphics, font, width, minecraft);
        }
    }

    // ----- 順位の塔 -----

    private static void towerHeader(GuiGraphics graphics, Font font, String left, String right) {
        Slant.fill(graphics, MARGIN, MARGIN, TOWER_WIDTH, 15, INK);
        Slant.text(graphics, font, left, MARGIN + 6, MARGIN + 4, WHITE);
        int room = TOWER_WIDTH - 12 - font.width(left) - 8;
        int shown = Math.min(font.width(right), room);
        Slant.fit(graphics, font, right, MARGIN + TOWER_WIDTH - 6 - shown, MARGIN + 4, room, ACCENT);
    }

    /** 塔の 1 行。{@code me} なら白い行に帯の色の番号。 */
    private static void towerRow(GuiGraphics graphics, Font font, int y, String pos, String name, String value,
                                 boolean me, int valueColor) {
        Slant.fill(graphics, MARGIN, y, TOWER_WIDTH, 13, me ? WHITE : ROW);
        Slant.fill(graphics, MARGIN, y, 14, 13, me ? ACCENT : POS);
        Slant.text(graphics, font, pos, MARGIN + 7 - font.width(pos) / 2, y + 3, WHITE);
        int valueWidth = font.width(value);
        Slant.fit(graphics, font, name, MARGIN + 19, y + 3, TOWER_WIDTH - 24 - valueWidth - 6, me ? INK : WHITE);
        Slant.text(graphics, font, value, MARGIN + TOWER_WIDTH - 5 - valueWidth, y + 3,
                me && valueColor == WHITE ? INK : valueColor);
    }

    private static String selfName(Minecraft minecraft) {
        return minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
    }

    private static void renderTower(GuiGraphics graphics, Font font, Minecraft minecraft) {
        int shownLap = Math.max(1, Math.min(lap, Math.max(1, courseLaps)));
        towerHeader(graphics, font, "LAP " + shownLap + " / " + courseLaps, course);
        String self = selfName(minecraft);
        int y = MARGIN + 16;
        for (int i = 0; i < standings.size() && i < 12; i++) {
            RaceStandingsPacket.Row row = standings.get(i);
            String value;
            int color = WHITE;
            if (!row.started()) {
                value = Component.translatable("race.kurumamod.hud_not_started").getString();
                color = GREY;
            } else if (row.finished()) {
                value = RaceManager.format(row.millis());
                color = GOLD;
            } else if (row.leader()) {
                value = "LEADER";
            } else {
                value = gap(row.millis());
            }
            towerRow(graphics, font, y, row.started() ? String.valueOf(i + 1) : "-", row.name(), value,
                    row.name().equals(self), color);
            y += 14;
        }
    }

    /** グリッド（信号が点いている間）。エントリーの名前と車種。 */
    private static void renderGridTower(GuiGraphics graphics, Font font, RaceLobbyPacket lobby) {
        towerHeader(graphics, font, "GRID · " + lobby.laps() + " LAPS", lobby.course());
        int y = MARGIN + 16;
        int index = 1;
        for (RaceLobbyPacket.Entrant entrant : lobby.entrants()) {
            if (index > 12) {
                break;
            }
            towerRow(graphics, font, y, String.valueOf(index), entrant.name(),
                    RaceLobby.carName(entrant.carId()).getString(), entrant.you(), GREY);
            y += 14;
            index++;
        }
    }

    /** タイムアタック: 区間ごとのタイムと自己ベストとの差。 */
    /**
     * タイムアタックの区間の塔。<b>行の数は画面の高さで決め、今の区間のあたりだけを出す。</b>
     *
     * <p>全部の区間を並べると、GUI スケールが大きい画面や区間の多いコースでは画面の縦を埋めてしまう。
     * 出すのは画面の高さの {@link #SECTOR_TOWER_SHARE} まで（少なくとも {@link #SECTOR_ROWS_MIN} 行）。
     * 窓は「終えた区間・走っている区間・次の区間」が見えるようにずらし、前に隠れた区間は差の合計を 1 行にまとめる。</p>
     */
    private static void renderSectorTower(GuiGraphics graphics, Font font, int screenHeight) {
        towerHeader(graphics, font, "TIME ATTACK", courseName);
        int y = MARGIN + 16;
        int current = sectorsComplete ? -1 : checkpointsPassed;
        int total = sectorTimes.length;
        int rows = Math.max(SECTOR_ROWS_MIN, (int) ((screenHeight * SECTOR_TOWER_SHARE - 16) / 13));
        int first = 0;
        if (total > rows) {
            // まとめの行に 1 行使う。今の区間の後ろに 1 行（次の区間）を残す
            int focus = current < 0 ? total - 1 : Math.min(total - 1, current + 1);
            first = Math.max(1, Math.min(total - (rows - 1), focus - (rows - 2)));
            // 前に隠れた区間: 差の合計（全部比べられるときだけ）
            long sum = 0L;
            boolean comparable = true;
            for (int i = 0; i < first; i++) {
                comparable &= sectorTimes[i] >= 0L && sectorHasDiff[i];
                sum += sectorDiffs[i];
            }
            String label = "S1-" + first;
            Slant.fill(graphics, MARGIN, y, TOWER_WIDTH, 12, 0xBF111111);
            Slant.text(graphics, font, label, MARGIN + 5, y + 2, GREY);
            if (comparable) {
                String diff = signed(sum);
                int w = font.width(diff) + 6;
                int x = MARGIN + TOWER_WIDTH - 3 - w;
                Slant.fill(graphics, x, y + 1, w, 10, sum < 0 ? FASTER : SLOWER);
                Slant.text(graphics, font, diff, x + 3, y + 2, WHITE);
            }
            y += 13;
        }
        for (int i = first; i < total && i < first + rows - (first > 0 ? 1 : 0); i++) {
            boolean now = i == current;
            boolean done = sectorTimes[i] >= 0L;
            String label = "S" + (i + 1);
            Slant.fill(graphics, MARGIN, y, TOWER_WIDTH, 12, now ? WHITE : ROW);
            Slant.fill(graphics, MARGIN, y, 17, 12, now ? ACCENT : POS);
            Slant.text(graphics, font, label, MARGIN + 9 - font.width(label) / 2, y + 2, WHITE);
            String time = done ? RaceManager.format(sectorTimes[i]) : now ? "…" : "—";
            Slant.text(graphics, font, time, MARGIN + 22, y + 2, now ? INK : done ? WHITE : 0xFF9A9A9A);
            if (done && sectorHasDiff[i]) {
                String diff = signed(sectorDiffs[i]);
                int w = font.width(diff) + 6;
                int x = MARGIN + TOWER_WIDTH - 3 - w;
                Slant.fill(graphics, x, y + 1, w, 10, sectorDiffs[i] < 0 ? FASTER : SLOWER);
                Slant.text(graphics, font, diff, x + 3, y + 2, WHITE);
            }
            y += 13;
        }
    }

    private static void append(StringBuilder text, String part) {
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(part);
    }

    // ----- 右上の時計 -----

    /**
     * タイムアタックのクラスの札（「〇〇で計測中」）。宣言していなければ何も出さない。
     *
     * <p>乗っている車がクラスの決まりに外れていたら赤くする（その周はクラスの記録に入らない）。
     * 周の途中で宣言を変えたら、次の周から何で測るかを下に添える。</p>
     */
    private static void renderClassTag(GuiGraphics graphics, Font font, int width, int y, Minecraft minecraft) {
        String declared = TimeAttackClass.declared();
        String measuring = timing ? lapClass : declared;
        if ((measuring.isEmpty() && declared.isEmpty()) || CarClientDriver.ridingCar(minecraft) == null) {
            return;
        }
        int right = width - MARGIN;
        // 走っている間のクラスは時計の上の札に出ているので、ここは走り出す前だけ
        if (!measuring.isEmpty() && !timing) {
            boolean bad = classViolating && measuring.equals(declared);
            // 時計の上の札と同じ形（「CLASS」と名前を別の枠に）
            classPills(graphics, font, right, y, bad
                    ? Component.translatable("race.kurumamod.hud_class_violation", measuring)
                    : Component.literal(measuring), bad);
            y += 13;
        }
        if (timing && !declared.equals(lapClass)) {
            Component text = Component.translatable("race.kurumamod.hud_class_next", declared.isEmpty()
                    ? Component.translatable("race.kurumamod.hud_class_overall") : Component.literal(declared));
            int w = font.width(text) + 12;
            Slant.fill(graphics, right - w, y, w, 11, 0xBF111111);
            Slant.text(graphics, font, text, right - w + 6, y + 2, GREY);
        }
    }

    /**
     * クラスの札: 「CLASS」の黒い枠と、名前の白い枠（条件を満たしていなければ赤）を、間を空けて斜めに並べる。
     * 右端を {@code right} に揃える。
     */
    private static void classPills(GuiGraphics graphics, Font font, int right, int y, Component name, boolean bad) {
        int w = font.width(name) + 14;
        Slant.fill(graphics, right - w, y, w, 11, bad ? SLOWER : WHITE);
        Slant.text(graphics, font, name, right - w + 7, y + 2, bad ? WHITE : INK);
        String tag = "CLASS";
        int tagWidth = font.width(tag) + 10;
        int tagX = right - w - 4 - tagWidth;
        Slant.fill(graphics, tagX, y, tagWidth, 11, INK);
        Slant.text(graphics, font, tag, tagX + 5, y + 2, GREY);
    }

    /**
     * 時計の上に出すクラスの札。タイムアタックは比べる相手のクラス（宣言していなければ「総合」）、
     * レースはそのレースのクラス（決まりを変えてあれば「カスタム」、決まりが無ければ出さない）。
     */
    private static Component clockClassLabel() {
        if (totalLaps == 0) {
            if (!timing) {
                return null;
            }
            if (lapClass.isEmpty()) {
                return Component.translatable("race.kurumamod.hud_class_overall_best");
            }
            return clockClassViolating() ? Component.translatable("race.kurumamod.hud_class_violation", lapClass)
                    : Component.literal(lapClass);
        }
        Regulation regulation = RaceLobby.regulation();
        if (RaceLobby.lobby() == null || regulation.isFree()) {
            return null;
        }
        String name = RaceLobby.classNameOf(regulation);
        return name != null ? Component.literal(name)
                : Component.translatable("screen.kurumamod.race_host.class_custom");
    }

    /** 走っているクラスの条件を、乗っている車が満たしていないか（タイムアタックだけ。レースは失格で分かる）。 */
    private static boolean clockClassViolating() {
        return totalLaps == 0 && timing && classViolating && lapClass.equals(TimeAttackClass.declared());
    }

    /**
     * ウォームアップの札（1 回目にラインを越えてから、計測が始まるまで）。
     * タイムアタックは<b>次にラインを越えたところから</b>測るので、それまでは測っていないことを出しておく。
     *
     * @return 次の札を置く高さ
     */
    private static int renderWarmUp(GuiGraphics graphics, Font font, int width, Minecraft minecraft) {
        if (!warmingUp || CarClientDriver.ridingCar(minecraft) == null) {
            return MARGIN;
        }
        int right = width - MARGIN;
        int y = MARGIN;
        String tag = "WARM UP";
        Component sub = Component.translatable("race.kurumamod.hud_warm_up", warmUpCourse);
        int tagWidth = font.width(tag) + 12;
        int subWidth = font.width(sub) + 12;
        Slant.fill(graphics, right - subWidth, y, subWidth, 13, 0xD8111111);
        Slant.text(graphics, font, sub, right - subWidth + 6, y + 3, WHITE);
        Slant.fill(graphics, right - subWidth - tagWidth + 2, y, tagWidth, 13, VOID);
        Slant.text(graphics, font, tag, right - subWidth - tagWidth + 8, y + 3, INK);
        return y + 15;
    }

    /** タイムアタックの計測が始まった帯（画面の中ほど、少しの間）。 */
    private static void renderAttackStart(GuiGraphics graphics, Font font, int width) {
        int center = width / 2;
        int y = NOTICE_TOP + 12;
        String head = "TIME ATTACK";
        Component big = Component.translatable("race.kurumamod.hud_attack_start");
        float scale = 2.0F;
        int bigWidth = Slant.width(font, big.getString(), scale) + 30;
        Slant.fill(graphics, center - bigWidth / 2, y, bigWidth, 24, ACCENT);
        Slant.scaled(graphics, font, big.getString(), center - Slant.width(font, big.getString(), scale) / 2.0F,
                y + 5, scale, WHITE);
        int headWidth = font.width(head) + 16;
        Slant.fill(graphics, center - headWidth / 2, y - 12, headWidth, 12, INK);
        Slant.text(graphics, font, head, center - font.width(head) / 2, y - 10, WHITE);
        Component sub = lapClass.isEmpty() ? Component.translatable("race.kurumamod.hud_class_overall_best")
                : Component.literal(lapClass);
        int subWidth = font.width(sub) + 16;
        Slant.fill(graphics, center - subWidth / 2, y + 25, subWidth, 12, WHITE);
        Slant.text(graphics, font, sub, center - font.width(sub) / 2, y + 27, INK);
    }

    /** @return 描いた下の端 */
    private static int renderClock(GuiGraphics graphics, Font font, int width, float partialTick) {
        int right = width - MARGIN;
        int y = MARGIN;
        // 時計の上: どのクラスで走っているか（BEST もこのクラスのもの）
        Component classLabel = clockClassLabel();
        if (classLabel != null) {
            classPills(graphics, font, right, y, classLabel, clockClassViolating());
            y += 12;
        }
        String time;
        int timeColor = WHITE;
        if (finishMillis > 0L) {
            // ゴールしたら経過時間ではなく総時間を出したまま止める
            time = RaceManager.format(finishMillis);
            timeColor = GOLD;
        } else {
            // ティック + フレーム内の端数。ティックだけだと 50ms 刻みで数字が跳ねる
            long current = timing ? (long) ((lapTicks + partialTick) * 50.0F) : 0L;
            time = RaceManager.format(current);
        }
        float scale = 1.6F;
        int timeWidth = Slant.width(font, time, scale);
        Slant.fill(graphics, right - timeWidth - 16, y, timeWidth + 16, 19, INK);
        Slant.scaled(graphics, font, time, right - timeWidth - 8, y + 3, scale, timeColor);
        y += 20;

        // この周で最後に通った CP の差。比べる相手が無ければ出さない（0.000 はベストと同じと読める）
        if (lastCheckpoint > 0 && lastCheckpointHasDelta && timing) {
            String text = "CP " + lastCheckpoint + "  " + signed(lastCheckpointDelta);
            int w = font.width(text) + 14;
            Slant.fill(graphics, right - w, y, w, 12, lastCheckpointDelta < 0 ? FASTER : SLOWER);
            Slant.text(graphics, font, text, right - w + 7, y + 2, WHITE);
            y += 13;
        }

        // CP の進み。<b>走っている最中に取りこぼしに気づける唯一の手がかり。</b>
        if (checkpointCount > 0 && timing) {
            int segment = 16;
            int gap = 2;
            int total = checkpointCount * segment + (checkpointCount - 1) * gap;
            int x = right - total;
            for (int i = 0; i < checkpointCount; i++) {
                Slant.fill(graphics, x + i * (segment + gap), y + 2, segment, 4,
                        i < checkpointsPassed ? WHITE : 0x99111111, Slant.STEEP);
            }
            y += 8;
        }

        // 前回とベスト（どのクラスのベストかは時計の上の札）
        String best = "BEST " + (bestMillis > 0 ? RaceManager.format(bestMillis) : "--:--.---");

        int bestWidth = font.width(best) + 10;
        Slant.fill(graphics, right - bestWidth, y + 1, bestWidth, 11, BEST);
        Slant.text(graphics, font, best, right - bestWidth + 5, y + 3, WHITE);
        if (lastLapMillis > 0) {
            String last = "LAST " + RaceManager.format(lastLapMillis);
            int lastWidth = font.width(last) + 10;
            Slant.fill(graphics, right - bestWidth - 1 - lastWidth, y + 1, lastWidth, 11, lastWasBest ? BEST : 0xBF111111);
            Slant.text(graphics, font, last, right - bestWidth - 1 - lastWidth + 5, y + 3, lastWasBest ? WHITE : GREY);
        }
        if (place > 0) {
            String text = Component.translatable("race.kurumamod.place", place).getString();
            int w = font.width(text) + 10;
            Slant.fill(graphics, right - w, y + 13, w, 11, WHITE);
            Slant.text(graphics, font, text, right - w + 5, y + 15, INK);
            return y + 24;
        }
        return y + 12;
    }

    /** 観戦中の時計。号砲からの経過時間と、先頭の周回。 */
    private static void renderWatchClock(GuiGraphics graphics, Font font, int width, float partialTick) {
        int right = width - MARGIN;
        int y = MARGIN;
        String time = RaceManager.format((long) ((watchTicks + partialTick) * 50.0));
        float scale = 1.6F;
        int timeWidth = Slant.width(font, time, scale);
        Slant.fill(graphics, right - timeWidth - 16, y, timeWidth + 16, 19, INK);
        Slant.scaled(graphics, font, time, right - timeWidth - 8, y + 3, scale, WHITE);
        y += 20;
        int leaderLaps = standings.isEmpty() ? 0 : standings.get(0).laps();
        String lap = "LAP " + Math.max(1, Math.min(courseLaps, leaderLaps + 1)) + " / " + courseLaps;
        int lapWidth = font.width(lap) + 10;
        Slant.fill(graphics, right - lapWidth, y + 1, lapWidth, 11, 0xBF111111);
        Slant.text(graphics, font, lap, right - lapWidth + 5, y + 3, WHITE);
        String tag = "WATCHING";
        int tagWidth = font.width(tag) + 10;
        Slant.fill(graphics, right - lapWidth - 1 - tagWidth, y + 1, tagWidth, 11, ACCENT);
        Slant.text(graphics, font, tag, right - lapWidth - 1 - tagWidth + 5, y + 3, WHITE);
    }

    // ----- スタート -----

    /** F1 式のスタート信号。全部点いてから<b>消えた瞬間</b>がスタート。 */
    private static void renderLights(GuiGraphics graphics, Font font, int width) {
        int total = RaceManager.LIGHT_COUNT;
        int bandWidth = 210;
        int left = (width - bandWidth) / 2;
        int top = 17;
        Slant.fill(graphics, left, top, bandWidth, 38, INK);
        int spacing = 34;
        int startX = width / 2 - spacing * (total - 1) / 2;
        boolean go = !frozen && goTicks > 0;
        for (int i = 0; i < total; i++) {
            int color = go ? LIGHT_GO : i < lights ? LIGHT_ON : LIGHT_OFF;
            Slant.circle(graphics, startX + i * spacing, top + 19, 11, color);
        }
        if (go) {
            String text = Component.translatable("race.kurumamod.go").getString();
            float scale = 4.0F;
            int w = Slant.width(font, text, scale);
            Slant.scaled(graphics, font, text, (width - w) / 2.0F, top + 50, scale, WHITE);
        }
    }

    /** 信号が点いている間、画面の中ほどに決まりと自分の判定。 */
    private static void renderRegulationBand(GuiGraphics graphics, Font font, int width, int height) {
        Regulation regulation = RaceLobby.regulation();
        List<RaceLobby.Rule> rules = RaceLobby.rules(regulation);
        if (rules.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (RaceLobby.Rule rule : rules) {
            append(text, rule.label().getString() + " " + rule.rule().getString());
        }
        String line = text.toString();
        int lineRoom = Math.min(520, width - 120);
        int lineWidth = Math.min(font.width(line), lineRoom);
        String tag = "REGULATION";
        int tagWidth = font.width(tag) + 12;
        int bandWidth = tagWidth + lineWidth + 16;
        int left = (width - bandWidth) / 2;
        int top = height / 2 + 20;
        Slant.fill(graphics, left, top, tagWidth, 16, ACCENT);
        Slant.text(graphics, font, tag, left + 6, top + 4, WHITE);
        Slant.fill(graphics, left + tagWidth, top, bandWidth - tagWidth, 16, PANEL);
        Slant.fit(graphics, font, line, left + tagWidth + 8, top + 4, lineRoom, WHITE);

        RaceLobbyPacket.Entrant self = RaceLobby.self();
        if (self != null) {
            boolean ok = self.verdict() != RaceLobbyPacket.Verdict.VIOLATION;
            Component verdict = Component.translatable(ok ? "race.kurumamod.verdict.ok" : "race.kurumamod.verdict.violation");
            Component mine = Component.translatable("race.kurumamod.hud_your_car");
            int w = font.width(mine) + font.width(verdict) + 24;
            int x = left + bandWidth - w;
            Slant.fill(graphics, x, top + 17, w, 14, WHITE);
            Slant.text(graphics, font, mine, x + 6, top + 20, INK);
            int bx = x + 12 + font.width(mine);
            graphics.fill(bx - 2, top + 19, bx + font.width(verdict) + 3, top + 29, ok ? RaceLobby.OK : RaceLobby.NG);
            Slant.text(graphics, font, verdict, bx, top + 20, WHITE);
        }
    }

    // ----- 知らせ -----

    /** CP・ラップの通過。<b>差の符号と色がここの本体</b>——今どちらに転んでいるかは走っている最中にしか意味がない。 */
    private static void renderSplit(GuiGraphics graphics, Font font, int width) {
        int center = width / 2;
        int y = NOTICE_TOP;
        String head = splitIndex > 0
                ? "CHECKPOINT " + splitIndex + " / " + checkpointCount
                : Component.translatable("race.kurumamod.hud_lap_label").getString().toUpperCase(Locale.ROOT)
                + " " + splitTime;
        int headWidth = font.width(head) + 18;
        Slant.fill(graphics, center - headWidth / 2, y, headWidth, 12, INK);
        Slant.text(graphics, font, head, center - font.width(head) / 2, y + 2, WHITE);
        y += 13;

        // 比べる相手が無いときは差の代わりにタイム。0.000 と出すとベストと同じと読める
        String big = splitHasDelta ? signed(splitDelta) : splitTime;
        int color = !splitHasDelta ? INK : splitDelta < 0 ? FASTER : SLOWER;
        float scale = 2.6F;
        int bigWidth = Slant.width(font, big, scale) + 26;
        Slant.fill(graphics, center - bigWidth / 2, y, bigWidth, 28, color);
        Slant.scaled(graphics, font, big, center - Slant.width(font, big, scale) / 2.0F, y + 4, scale, WHITE);
        y += 29;

        if (splitHasDelta) {
            Component sub = Component.translatable(splitDelta < 0
                    ? "race.kurumamod.hud_faster" : "race.kurumamod.hud_slower");
            int subWidth = font.width(sub) + 16;
            Slant.fill(graphics, center - subWidth / 2, y, subWidth, 12, 0xCC111111);
            Slant.text(graphics, font, sub, center - font.width(sub) / 2, y + 2, WHITE);
        }
    }

    /** ベストラップ。紫の帯を、上の真ん中に（右寄せだと時計の LAST/BEST に重なる）。 */
    private static void renderBestLap(GuiGraphics graphics, Font font, int width) {
        int y = NOTICE_TOP;
        String time = splitTime;
        String delta = splitHasDelta ? signed(splitDelta) : "";
        float scale = 2.0F;
        int timeWidth = Slant.width(font, time, scale);
        int bandWidth = timeWidth + font.width(delta) + 34;
        int tagWidth = font.width("PERSONAL") + 16;
        int left = (width - (tagWidth + bandWidth + 20)) / 2;
        Slant.fill(graphics, left, y, tagWidth, 32, WHITE);
        Slant.text(graphics, font, "PERSONAL", left + 8, y + 6, BEST);
        Slant.text(graphics, font, "BEST LAP", left + 8, y + 17, BEST);
        Slant.fill(graphics, left + tagWidth, y, bandWidth + 20, 32, BEST);
        Slant.scaled(graphics, font, time, left + tagWidth + 10, y + 8, scale, WHITE);
        Slant.text(graphics, font, delta, left + tagWidth + 18 + timeWidth, y + 12, WHITE);
    }

    /** 周回無効。<b>理由と、次に何をすればいいか</b>まで出す。 */
    private static void renderVoid(GuiGraphics graphics, Font font, int width) {
        int center = width / 2;
        int y = NOTICE_TOP;
        Component title = Component.translatable("race.kurumamod.hud_void_title");
        float scale = 1.6F;
        int tagWidth = font.width("LAP VOID") + 18;
        int titleWidth = Slant.width(font, title, scale) + 26;
        int left = center - (tagWidth + titleWidth) / 2;
        Slant.fill(graphics, left, y, tagWidth, 28, INK);
        Slant.text(graphics, font, "LAP VOID", left + 9, y + 10, WHITE);
        Slant.fill(graphics, left + tagWidth, y, titleWidth, 28, VOID);
        Slant.scaled(graphics, font, title, left + tagWidth + 13, y + 7, scale, INK);
        y += 29;

        boolean missed = checkpointsPassed < checkpointCount;
        Component reason = missed
                ? Component.translatable("race.kurumamod.hud_void_missed", checkpointsPassed + 1,
                checkpointsPassed, checkpointCount)
                : Component.translatable("race.kurumamod.lap_void_short");
        int reasonWidth = font.width(reason) + 18;
        Slant.fill(graphics, center - reasonWidth / 2, y, reasonWidth, 15, 0xD9111111);
        Slant.text(graphics, font, reason, center - font.width(reason) / 2, y + 4, WHITE);
        y += 16;
        if (missed) {
            Component hint = Component.translatable("race.kurumamod.hud_void_hint", checkpointsPassed + 1);
            int hintWidth = font.width(hint) + 18;
            Slant.fill(graphics, center - hintWidth / 2, y, hintWidth, 12, 0xB3111111);
            Slant.text(graphics, font, hint, center - font.width(hint) / 2, y + 2, 0xFFE8E8E8);
        }
    }

    /** 最終周。画面を横切る白い帯。 */
    private static void renderFinalLap(GuiGraphics graphics, Font font, int width) {
        // ラップの差の表示（上の真ん中）と同時に出るので、そのすぐ下
        int y = NOTICE_TOP + 58;
        graphics.fill(0, y, width, y + 34, WHITE);
        String text = "FINAL LAP";
        float scale = 2.6F;
        int w = Slant.width(font, text, scale);
        Component sub = Component.translatable("race.kurumamod.hud_final_lap");
        int total = w + 14 + font.width(sub);
        int x = (width - total) / 2;
        graphics.fill(x - 18, y, x - 10, y + 34, ACCENT);
        graphics.fill(x + total + 10, y, x + total + 18, y + 34, ACCENT);
        Slant.scaled(graphics, font, text, x, y + 6, scale, INK);
        Slant.text(graphics, font, sub, x + w + 14, y + 13, INK);
    }

    /** ゴール。帯の色の帯に FINISH と順位。 */
    private static void renderFinishBand(GuiGraphics graphics, Font font, int width) {
        int y = 30;
        graphics.fill(0, y, width, y + 46, ACCENT);
        String text = "FINISH";
        float scale = 3.0F;
        int w = Slant.width(font, text, scale);
        String placeText = place > 0 ? String.valueOf(place) : "";
        Component unit = Component.translatable("race.kurumamod.hud_place_unit");
        int placeWidth = place > 0 ? Slant.width(font, placeText, scale) + font.width(unit) + 18 : 0;
        int x = (width - w - (placeWidth > 0 ? placeWidth + 16 : 0)) / 2;
        Slant.scaled(graphics, font, text, x, y + 11, scale, WHITE);
        if (place > 0) {
            int px = x + w + 16;
            graphics.fill(px, y + 6, px + placeWidth, y + 40, WHITE);
            Slant.scaled(graphics, font, placeText, px + 8, y + 11, scale, INK);
            Slant.text(graphics, font, unit, px + 10 + Slant.width(font, placeText, scale), y + 24, INK);
        }
    }

    /** リザルト。順位の塔と同じ斜めの列。 */
    private static void renderResults(GuiGraphics graphics, Font font, int width, Minecraft minecraft) {
        int tableWidth = Math.min(390, width - 40);
        int left = (width - tableWidth) / 2;
        int y = 86;
        Slant.fill(graphics, left, y, tableWidth, 15, INK);
        Slant.text(graphics, font, "POS", left + 6, y + 4, WHITE);
        Slant.fit(graphics, font, "DRIVER · " + course + " " + courseLaps + " LAPS", left + 30, y + 4,
                tableWidth - 30 - font.width("TOTAL") - 16, WHITE);
        Slant.text(graphics, font, "TOTAL", left + tableWidth - 8 - font.width("TOTAL"), y + 4, WHITE);
        y += 16;
        String self = selfName(minecraft);
        for (int i = 0; i < standings.size() && i < 12; i++) {
            RaceStandingsPacket.Row row = standings.get(i);
            boolean me = row.name().equals(self);
            Slant.fill(graphics, left, y, tableWidth, 17, me ? WHITE : 0xD1111111);
            String pos = row.finished() ? String.valueOf(i + 1) : "-";
            Slant.fill(graphics, left, y, 22, 17, me ? ACCENT : POS);
            Slant.text(graphics, font, pos, left + 11 - font.width(pos) / 2, y + 5, WHITE);
            Slant.text(graphics, font, row.name(), left + 30, y + 5, me ? INK : WHITE);
            String value;
            if (row.retired()) {
                // 落ちた・降りたままでリタイアした人。リザルトを出し終えるまで覚えておいて、リタイアとして並べる
                value = Component.translatable("race.kurumamod.hud_retired", row.laps()).getString();
            } else if (!row.finished()) {
                value = Component.translatable("race.kurumamod.hud_dnf", row.laps()).getString();
            } else if (row.leader()) {
                value = RaceManager.format(row.millis());
            } else {
                value = "+" + RaceManager.format(row.millis());
            }
            Slant.text(graphics, font, value, left + tableWidth - 8 - font.width(value), y + 5, me ? INK : WHITE);
            y += 18;
        }
        Component foot = Component.translatable("race.kurumamod.hud_result_foot",
                KurumaKeys.OPEN_RACE.getTranslatedKeyMessage());
        int footWidth = Math.min(tableWidth, font.width(foot) + 16);
        Slant.fill(graphics, left, y + 2, footWidth, 13, 0xBF111111);
        Slant.text(graphics, font, foot, left + 8, y + 5, 0xFFE8E8E8);
    }

    // ----- 書式 -----

    /** 符号つきの差（秒）。「−0.412」「+1.020」。 */
    private static String signed(long millis) {
        return String.format(Locale.ROOT, "%+.3f", millis / 1000.0);
    }

    /** 先頭との差。1 分未満は秒、それ以上は分:秒。 */
    private static String gap(long millis) {
        if (millis < 60_000L) {
            return String.format(Locale.ROOT, "+%.2f", millis / 1000.0);
        }
        return "+" + RaceManager.format(millis);
    }
}
