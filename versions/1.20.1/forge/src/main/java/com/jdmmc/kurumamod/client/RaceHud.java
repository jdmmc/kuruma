package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.network.RaceStandingsPacket;
import com.jdmmc.kurumamod.race.RaceManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * ラップタイムとスタート信号の表示。
 *
 * <p>走行中のタイムはサーバーから毎ティック貰わず、<b>「ラップが始まった」を受け取ってから
 * 自分で数える</b>。確定値はラップ完了時にサーバーの値で上書きされるので、ずれは残らない。</p>
 *
 * <p><b>数えるのはティックであって実時間ではない。</b>サーバーはティック数（{@code getTickCount()}）で
 * ラップタイムを確定させているので、こちらが壁時計で数えると<b>TPS が落ちたぶんだけ表示が先に進み</b>、
 * ゴールした瞬間に数字が戻る。シングルプレイでポーズしている間も進んでしまう。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class RaceHud {

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_LABEL = 0xFFA0A0A0;
    private static final int COLOR_BEST = 0xFFB388FF;
    /** 順位表で自分の行を目立たせる色。 */
    private static final int COLOR_SELF = 0xFFFFD26A;
    /** リザルトの見出し。 */
    private static final int COLOR_RESULT = 0xFFFFD700;
    private static final int COLOR_BACKGROUND = 0x90000000;

    private static final int LIGHT_SIZE = 18;
    private static final int LIGHT_GAP = 6;
    private static final int LIGHT_OFF = 0xFF3A1010;
    private static final int LIGHT_ON = 0xFFFF2020;
    private static final int LIGHT_FRAME = 0xFF101010;

    /** 区間タイムを出しておく長さ [ティック]。 */
    private static final int SPLIT_TICKS = 60;
    /** 周回が無効だったときの表示。<b>読み落とすと 1 周ぶん損をする</b>ので長めに。 */
    private static final int VOID_TICKS = 100;
    /** ゴールの大きな表示を出しておく長さ [ティック]。 */
    private static final int FINISH_TICKS = 120;
    /** ベストより速い。 */
    private static final int COLOR_FASTER = 0xFF4CFF4C;
    /** ベストより遅い。 */
    private static final int COLOR_SLOWER = 0xFFFF6B6B;

    /** 点灯しているライトの数。0 かつ {@link #frozen} が false ならスタート済み。 */
    private static int lights;
    private static boolean frozen;
    /** 消灯した瞬間からの経過ティック。少しの間 GO を出すために使う。 */
    private static int goTicks;

    private static boolean timing;
    private static int lap;
    private static int totalLaps;
    /** 今の周が始まってからのティック数。実時間ではなくこれで数える。 */
    private static int lapTicks;
    private static long lastLapMillis;
    private static long bestMillis;
    private static boolean lastWasBest;
    private static int place;
    /** ゴールまでの総時間 [ms]。ゴール後はこれを出したまま止める。 */
    private static long finishMillis;
    /** 画面中央に大きく「ゴール」を出しておく残りティック。 */
    private static int finishTicks;

    /** 区間・ラップの通過表示。残りティックが 0 なら出さない。 */
    private static int splitTicks;
    private static Component splitLabel = Component.empty();
    private static String splitTime = "";
    private static long splitDelta;
    private static boolean splitHasDelta;
    /** 警告（周回が無効）として出しているか。 */
    private static boolean splitAlert;

    /** 通ったチェックポイントの数と本数。0 本のコースでは出さない。 */
    private static int checkpointsPassed;
    private static int checkpointCount;

    /** レースの一覧（受付中・走行中・リザルト）。空なら何も出さない。 */
    private static java.util.List<RaceStandingsPacket.Row> standings = java.util.List.of();
    private static RaceStandingsPacket.Phase phase = RaceStandingsPacket.Phase.ENTRY;
    private static String course = "";
    private static int courseLaps;

    private RaceHud() {
    }

    /** カウントダウン中は運転操作を受け付けない。 */
    public static boolean isFrozen() {
        return frozen;
    }

    /** 参加者一覧・順位表・リザルトを受け取る。 */
    public static void acceptStandings(RaceStandingsPacket packet) {
        standings = packet.rows();
        phase = packet.phase();
        course = packet.course();
        courseLaps = packet.laps();
    }

    public static void accept(RaceEventPacket packet) {
        switch (packet.type()) {
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
            case LAP_STARTED -> {
                timing = true;
                lap = packet.lap();
                totalLaps = packet.totalLaps();
                lapTicks = 0;
                place = 0;
                finishMillis = 0L;
                finishTicks = 0;
                splitTicks = 0;
                checkpointsPassed = 0;
                checkpointCount = Math.max(0, packet.sectorCount() - 1);
            }
            case SPLIT -> {
                checkpointsPassed = packet.sector();
                checkpointCount = Math.max(0, packet.sectorCount() - 1);
                // 右上の通過数と<b>同じ数え方・同じ分母</b>で出す。区間で数えると
                // 分母が 1 つ多くなるうえ、最後の区間はゴールとして別に出るので
                // 「S2/2」は永久に現れない——到達しない分母を見せることになる
                showSplit(Component.translatable("race.kurumamod.hud_checkpoints",
                        checkpointsPassed, checkpointCount),
                        packet.millis(), packet.delta(), packet.hasDelta());
                RaceSounds.split(packet.delta(), packet.hasDelta());
            }
            case LAP_VOID -> {
                // 取りこぼしたチェックポイントの数は捨てない。この先で通えば周回が成立する
                checkpointsPassed = packet.sector();
                checkpointCount = Math.max(0, packet.sectorCount() - 1);
                splitTicks = VOID_TICKS;
                splitLabel = checkpointsPassed < checkpointCount
                        ? Component.translatable("race.kurumamod.lap_void_checkpoint",
                                checkpointsPassed, checkpointCount)
                        : Component.translatable("race.kurumamod.lap_void_short");
                splitTime = "";
                splitHasDelta = false;
                splitAlert = true;
            }
            case LAP_COMPLETED -> {
                lap = packet.lap();
                totalLaps = packet.totalLaps();
                lastLapMillis = packet.millis();
                bestMillis = packet.bestMillis();
                lastWasBest = packet.best();
                lapTicks = 0;
                checkpointsPassed = 0;
                showSplit(Component.translatable("race.kurumamod.hud_lap_label"),
                        packet.millis(), packet.delta(), packet.hasDelta());
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
                RaceSounds.finish();
                showSplit(Component.translatable("race.kurumamod.hud_lap_label"),
                        packet.millis(), packet.delta(), packet.hasDelta());
            }
            case CLEARED -> clear();
        }
    }

    /** 走行中の表示を消す。サーバーが周回状態を捨てたときの合図。 */
    private static void clear() {
        timing = false;
        frozen = false;
        lights = 0;
        place = 0;
        finishMillis = 0L;
        finishTicks = 0;
        splitTicks = 0;
        checkpointsPassed = 0;
        checkpointCount = 0;
        // <b>順位表もここで消すこと。</b>抜けた人へは以後 toEntrants で何も届かないので、
        // 消さないと最後に受け取った一覧が画面に残り続ける（レースを抜けたのに
        // 順位が出たままになる）。受付中はワールド全体へ配っているので、
        // 見ているだけの人には次の配信でまた出る
        standings = java.util.List.of();
    }

    /**
     * 接続が変わったら全部捨てる。
     *
     * <p><b>この画面の状態はすべて {@code static} なので、放っておくとプロセスが生きている限り
     * 残る。</b>計測中にサーバーを抜けると、次に入ったところでも時計が回ったままになり、
     * サーバーには周回状態が無いので {@code /kuruma timeattack reset} が
     * 「計測していません」を返す——<b>画面とサーバーが食い違う</b>。カウントダウン中に抜けた
     * ときはさらに悪く、{@link #isFrozen()} が true のまま残って<b>運転操作を受け付けなくなる</b>。</p>
     *
     * <p>入るときと出るときの両方で捨てる。落ちかたによっては {@code LoggingOut} が
     * 来ないことがあるので、<b>入る側だけが最後の砦</b>になる。</p>
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
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
        splitLabel = Component.empty();
        splitTime = "";
        splitDelta = 0L;
        splitHasDelta = false;
        splitAlert = false;
        phase = RaceStandingsPacket.Phase.ENTRY;
        course = "";
        courseLaps = 0;
    }

    private static void showSplit(Component label, long millis, long delta, boolean hasDelta) {
        splitTicks = SPLIT_TICKS;
        splitLabel = label;
        splitTime = RaceManager.format(millis);
        splitDelta = delta;
        splitHasDelta = hasDelta;
        splitAlert = false;
    }

    /**
     * 時間を進めるのはここだけ。
     *
     * <p>描画側で数えると<b>フレームレートで速さが変わる</b>。以前は GO の表示と
     * 区間タイムの表示がこれで、120fps なら 2 秒のはずが 0.7 秒しか出なかった。</p>
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().isPaused()) {
            return;
        }
        if (timing) {
            lapTicks++;
        }
        if (goTicks > 0 && !frozen) {
            goTicks--;
        }
        if (splitTicks > 0) {
            splitTicks--;
        }
        if (finishTicks > 0) {
            finishTicks--;
        }
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = minecraft.getWindow().getGuiScaledWidth();

        if (frozen || goTicks > 0) {
            renderLights(graphics, minecraft, width);
        }
        if (finishTicks > 0) {
            renderFinish(graphics, minecraft, width, minecraft.getWindow().getGuiScaledHeight());
        } else if (splitTicks > 0) {
            renderSplit(graphics, minecraft, width);
        }
        int bottom = 8;
        if (timing || place > 0) {
            bottom = renderLapTimes(graphics, minecraft, width, event.getPartialTick());
        }
        if (!standings.isEmpty()) {
            renderStandings(graphics, minecraft, width, bottom + 6);
        }
    }

/**
     * 参加者一覧・順位表・リザルト。同じ枠を場面で使い分ける。
     *
     * <p>走行中とリザルトは<b>先頭が時間、以降は先頭との差</b>。差は「同じ地点を通過した
     * 時刻の差」なので、走っている場所が違っても意味を持つ。</p>
     */
    private static void renderStandings(GuiGraphics graphics, Minecraft minecraft, int width, int top) {
        int left = width - 132;
        int rows = standings.size();
        graphics.fill(left - 6, top - 4, width - 4, top + (rows + 2) * 10 + 2, COLOR_BACKGROUND);

        // どのコースのレースかは、参加する前でも分かる方がよい
        graphics.drawString(minecraft.font,
                Component.translatable("race.kurumamod.hud_course", course, courseLaps),
                left, top, COLOR_TEXT, false);

        Component title = switch (phase) {
            case ENTRY -> Component.translatable("race.kurumamod.hud_entry", rows);
            case RUNNING -> Component.translatable("race.kurumamod.hud_running");
            case RESULT -> Component.translatable("race.kurumamod.hud_result");
        };
        graphics.drawString(minecraft.font, title, left, top + 10,
                phase == RaceStandingsPacket.Phase.RESULT ? COLOR_RESULT : COLOR_LABEL, false);

        String self = minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
        int y = top + 20;
        for (int i = 0; i < rows; i++) {
            RaceStandingsPacket.Row row = standings.get(i);
            int color = row.name().equals(self) ? COLOR_SELF : COLOR_TEXT;
            graphics.drawString(minecraft.font, (i + 1) + ".", left, y, COLOR_LABEL, false);
            graphics.drawString(minecraft.font,
                    minecraft.font.plainSubstrByWidth(row.name(), 58), left + 14, y, color, false);

            if (phase != RaceStandingsPacket.Phase.ENTRY) {
                String value;
                int valueColor;
                if (phase == RaceStandingsPacket.Phase.RUNNING && row.finished()) {
                    // ゴール済み。総時間で止まっているので、色を変えて走行中の行と区別する
                    value = RaceManager.format(row.millis());
                    valueColor = COLOR_RESULT;
                } else if (!row.started()) {
                    // まだスタートラインを跨いでいない人。順位も差も無いが、
                    // <b>誰が出走しているのかはスタート直後こそ知りたい</b>
                    value = Component.translatable("race.kurumamod.hud_not_started").getString();
                    valueColor = COLOR_LABEL;
                } else if (phase == RaceStandingsPacket.Phase.RESULT && !row.finished()) {
                    // 完走していない人は周回数だけ
                    value = Component.translatable("race.kurumamod.hud_dnf", row.laps()).getString();
                    valueColor = COLOR_LABEL;
                } else if (row.leader()) {
                    value = RaceManager.format(row.millis());
                    valueColor = COLOR_TEXT;
                } else {
                    value = "+" + RaceManager.format(row.millis());
                    valueColor = COLOR_LABEL;
                }
                graphics.drawString(minecraft.font, value,
                        width - 8 - minecraft.font.width(value), y, valueColor, false);
            }
            y += 10;
        }
    }

    /**
     * ゴールしたことを画面の中央に大きく出す。
     *
     * <p>右上の数字が止まるだけでは<b>ゴールしたのか計測が外れたのか区別がつかない</b>。
     * 順位と総時間まで一緒に出せば、そのまま結果として読める。</p>
     *
     * <p>やや上に置くのは、走り抜けた先の路面を完全には隠さないため。ゴールしても
     * 車は止まらないので、まだ前を見る必要がある。</p>
     */
    private static void renderFinish(GuiGraphics graphics, Minecraft minecraft,
                                     int width, int height) {
        Component title = Component.translatable("race.kurumamod.finish_banner");
        Component detail = place > 0
                ? Component.translatable("race.kurumamod.finish_detail",
                        place, RaceManager.format(finishMillis))
                : Component.translatable("race.kurumamod.finish_time",
                        RaceManager.format(finishMillis));

        int centerX = width / 2;
        int top = height / 3;
        int half = Math.max(minecraft.font.width(title) * 2, minecraft.font.width(detail)) / 2 + 12;
        graphics.fill(centerX - half, top - 6, centerX + half, top + 32, COLOR_BACKGROUND);

        // 見出しだけ 2 倍。拡大したぶん座標も半分にして描く
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, top, 0.0F);
        graphics.pose().scale(2.0F, 2.0F, 1.0F);
        graphics.drawCenteredString(minecraft.font, title, 0, 0, COLOR_RESULT);
        graphics.pose().popPose();

        graphics.drawCenteredString(minecraft.font, detail, centerX, top + 20, COLOR_TEXT);
    }

    /**
     * 区間タイムと自己ベストとの差。
     *
     * <p>画面の中央上に短く出す。<b>差の符号と色がここの本体</b>——タイムそのものは
     * ゴールしてからでも読めるが、「今どちらに転んでいるか」は走っている最中にしか意味がない。</p>
     *
     * <p>比べる相手が無い（そのコースの初走行）ときは差を出さない。0.000 と出すと
     * 「ベストとぴったり同じ」と読めてしまう。</p>
     */
    private static void renderSplit(GuiGraphics graphics, Minecraft minecraft, int width) {
        String head = splitTime.isEmpty()
                ? splitLabel.getString()
                : splitLabel.getString() + "  " + splitTime;
        String delta = splitHasDelta
                ? String.format(java.util.Locale.ROOT, "%+.3f", splitDelta / 1000.0)
                : "";
        int headWidth = minecraft.font.width(head);
        int deltaWidth = delta.isEmpty() ? 0 : minecraft.font.width(delta) + 8;
        int left = (width - headWidth - deltaWidth) / 2;
        // スタート信号（top 24・高さ 18）の下。カウントダウン直後に重ならない位置
        int top = 58;

        graphics.fill(left - 6, top - 4, left + headWidth + deltaWidth + 6, top + 12, COLOR_BACKGROUND);
        graphics.drawString(minecraft.font, head, left, top,
                splitAlert ? COLOR_SLOWER : COLOR_TEXT, false);
        if (!delta.isEmpty()) {
            graphics.drawString(minecraft.font, delta, left + headWidth + 8, top,
                    splitDelta < 0L ? COLOR_FASTER : COLOR_SLOWER, false);
        }
    }

    /** F1 式のスタート信号。全部点いてから<b>消えた瞬間</b>がスタート。 */
    private static void renderLights(GuiGraphics graphics, Minecraft minecraft, int width) {
        int total = RaceManager.LIGHT_COUNT;
        int stripWidth = total * LIGHT_SIZE + (total - 1) * LIGHT_GAP;
        int left = (width - stripWidth) / 2;
        int top = 24;

        graphics.fill(left - 8, top - 8, left + stripWidth + 8, top + LIGHT_SIZE + 8, COLOR_BACKGROUND);
        for (int i = 0; i < total; i++) {
            int x = left + i * (LIGHT_SIZE + LIGHT_GAP);
            graphics.fill(x - 1, top - 1, x + LIGHT_SIZE + 1, top + LIGHT_SIZE + 1, LIGHT_FRAME);
            graphics.fill(x, top, x + LIGHT_SIZE, top + LIGHT_SIZE, i < lights ? LIGHT_ON : LIGHT_OFF);
        }
        if (!frozen && goTicks > 0) {
            Component go = Component.translatable("race.kurumamod.go");
            graphics.drawCenteredString(minecraft.font, go, width / 2,
                    top + LIGHT_SIZE + 12, 0xFF4CFF4C);
        }
    }

    /** @return 使い終わった下端の Y */
    private static int renderLapTimes(GuiGraphics graphics, Minecraft minecraft, int width,
                                      float partialTick) {
        int left = width - 108;
        int top = 8;
        int lines = (totalLaps > 0 ? 1 : 0) + 3 + (place > 0 ? 1 : 0)
                + (checkpointCount > 0 && timing ? 1 : 0);
        graphics.fill(left - 6, top - 4, width - 4, top + lines * 10 + 2, COLOR_BACKGROUND);

        int y = top;
        if (totalLaps > 0) {
            graphics.drawString(minecraft.font,
                    Component.translatable("race.kurumamod.lap_of", lap, totalLaps),
                    left, y, COLOR_TEXT, false);
            y += 10;
        }
        // ティック + フレーム内の端数。ティックだけだと 50ms 刻みで数字が跳ねる
        if (finishMillis > 0L) {
            // ゴールしたら経過時間ではなく総時間を出したまま止める
            graphics.drawString(minecraft.font,
                    Component.translatable("race.kurumamod.finish_time",
                            RaceManager.format(finishMillis)),
                    left, y, COLOR_RESULT, false);
        } else {
            // ティック + フレーム内の端数。ティックだけだと 50ms 刻みで数字が跳ねる
            long current = timing ? (long) ((lapTicks + partialTick) * 50.0F) : 0L;
            graphics.drawString(minecraft.font,
                    Component.translatable("race.kurumamod.current", RaceManager.format(current)),
                    left, y, COLOR_TEXT, false);
        }
        y += 10;
        if (checkpointCount > 0 && timing) {
            // <b>走っている最中に取りこぼしに気づける唯一の手がかり。</b>ラインまで
            // 分からないと、そこではもう 1 周ぶん取り返せない
            graphics.drawString(minecraft.font,
                    Component.translatable("race.kurumamod.hud_checkpoints",
                            checkpointsPassed, checkpointCount),
                    left, y, checkpointsPassed < checkpointCount ? COLOR_LABEL : COLOR_TEXT, false);
            y += 10;
        }
        graphics.drawString(minecraft.font,
                Component.translatable("race.kurumamod.last", RaceManager.format(lastLapMillis)),
                left, y, lastWasBest ? COLOR_BEST : COLOR_LABEL, false);
        y += 10;
        graphics.drawString(minecraft.font,
                Component.translatable("race.kurumamod.best", RaceManager.format(bestMillis)),
                left, y, COLOR_BEST, false);
        y += 10;
        if (place > 0) {
            graphics.drawString(minecraft.font,
                    Component.translatable("race.kurumamod.place", place), left, y,
                    COLOR_RESULT, false);
            y += 10;
        }
        return y;
    }
}
