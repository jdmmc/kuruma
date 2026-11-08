package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.hook.GuiRender;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * クライアント側のレースの様子（{@link RaceLobbyPacket}）。<b>参加と主催の入口。</b>
 *
 * <ul>
 *   <li>誰かがレースを開いたら、画面の右の中ほどに<b>誘いの通知</b>を数秒出す。K でその場で参加</li>
 *   <li>主催している間は、同じ場所に<b>主催中の表示</b>を出し続ける。K で主催者画面へ戻れる</li>
 *   <li>参加している間の K は、参加したレースの画面（{@link RaceInfoScreen}）を開く</li>
 *   <li>レースが無いときの K は、主催者画面（{@link RaceOrganizerScreen}）を開く＝レースを開く入口</li>
 * </ul>
 *
 * <p>G セッティングの制限（{@link #restriction}・{@link #allowsTuning}）もここで決める。
 * 判定の中身は {@link Regulation} で、サーバーと同じ式を使う。</p>
 */
public final class RaceLobby {

    /** 誘いの通知を出しておく長さ [ティック]。走りながら読んで K を押す余裕を見て 10 秒。 */
    private static final int INVITE_TICKS = 200;
    /** 「〜が参加しました」を出しておく長さ [ティック]。 */
    private static final int PING_TICKS = 60;

    static final int ACCENT = 0xFFE1261C;
    static final int INK = 0xFF111111;
    static final int PANEL = 0xD8111111;
    static final int PANEL_LIGHT = 0xB8111111;
    static final int WHITE = 0xFFFFFFFF;
    static final int GREY = 0xFFC8CCD2;
    /** 適合。<b>違反の赤と明るさでも分かれる青</b>（赤と緑の見分けに頼らない）。 */
    static final int OK = 0xFF1D4ED8;
    static final int OK_DOT = 0xFF5B8CFF;
    /** 適合していない項目。赤に白い文字を載せる。 */
    static final int NG = 0xFFDC2626;
    /** 参加している人の欄の色。主催者の赤と分ける。 */
    static final int GREEN = 0xFF16A34A;

    private static RaceLobbyPacket lobby;
    private static int inviteTicks;
    private static Set<String> knownEntrants = new HashSet<>();
    private static String pingName = "";
    private static boolean pingJoined;
    private static int pingTicks;

    private RaceLobby() {
    }

    // ------------------------------------------------------------------
    // 受け取る
    // ------------------------------------------------------------------

    public static void accept(RaceLobbyPacket packet) {
        RaceLobbyPacket previous = lobby;
        boolean wasOpen = previous != null && previous.phase() == RaceLobbyPacket.Phase.OPEN;
        lobby = packet.phase() == RaceLobbyPacket.Phase.NONE ? null : packet;
        coursesSeen = packet.courses();
        presetsSeen = packet.presets();
        presetAdminSeen = packet.presetAdmin();
        if (lobby == null) {
            inviteTicks = 0;
            knownEntrants = new HashSet<>();
            pingTicks = 0;
            return;
        }
        // 自分が入った・抜けた（外された）ときの音。レースが終わって畳まれたときは鳴らさない
        boolean wasJoined = previous != null && previous.joined();
        if (packet.joined() && !wasJoined) {
            RaceSounds.entered();
        } else if (!packet.joined() && wasJoined && packet.phase() == RaceLobbyPacket.Phase.OPEN) {
            RaceSounds.left();
        }
        // 受付が始まった瞬間だけ誘う。主催した本人と、もう入っている人には出さない
        if (!wasOpen && packet.phase() == RaceLobbyPacket.Phase.OPEN && !packet.isHost() && !packet.joined()) {
            inviteTicks = INVITE_TICKS;
        }
        if (packet.joined() || packet.spectating() || packet.phase() != RaceLobbyPacket.Phase.OPEN) {
            inviteTicks = 0;
        }
        // 主催者には、参加・離脱を一言知らせる
        Set<String> names = new HashSet<>();
        for (RaceLobbyPacket.Entrant entrant : packet.entrants()) {
            names.add(entrant.name());
        }
        if (previous != null && packet.isHost()) {
            for (String name : names) {
                if (!knownEntrants.contains(name) && !isSelf(name)) {
                    ping(name, true);
                }
            }
            for (String name : knownEntrants) {
                if (!names.contains(name) && !isSelf(name)) {
                    ping(name, false);
                }
            }
        }
        knownEntrants = names;
    }

    private static List<RaceLobbyPacket.CourseInfo> coursesSeen = List.of();
    private static List<RaceLobbyPacket.Preset> presetsSeen = List.of();
    private static boolean presetAdminSeen;

    /** プリセットを保存・削除してよいか（OP）。最後に受け取ったもの。 */
    static boolean presetAdmin() {
        return presetAdminSeen;
    }

    /** サーバーに保存してあるレギュレーションのプリセット（最後に受け取ったもの）。 */
    static List<RaceLobbyPacket.Preset> presets() {
        return presetsSeen;
    }

    private static void ping(String name, boolean joined) {
        if (joined) {
            RaceSounds.entered();
        } else {
            RaceSounds.left();
        }
        pingName = name;
        pingJoined = joined;
        pingTicks = PING_TICKS;
    }

    private static boolean isSelf(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getGameProfile().name().equals(name);
    }

    /** いまのレースの様子。レースが無ければ null。 */
    static RaceLobbyPacket lobby() {
        return lobby;
    }

    /** そのワールドのコース（最後に受け取ったもの）。 */
    static List<RaceLobbyPacket.CourseInfo> courses() {
        return coursesSeen;
    }

    static Regulation regulation() {
        return lobby == null ? Regulation.DEFAULT : Regulation.fromArray(lobby.regulation());
    }

    public static void onClientTick() {
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        if (inviteTicks > 0) {
            inviteTicks--;
        }
        if (pingTicks > 0) {
            pingTicks--;
        }
    }

    public static void onLoggingIn() {
        reset();
    }

    public static void onLoggingOut() {
        reset();
    }

    private static void reset() {
        lobby = null;
        coursesSeen = List.of();
        presetsSeen = List.of();
        presetAdminSeen = false;
        inviteTicks = 0;
        knownEntrants = new HashSet<>();
        pingTicks = 0;
    }

    // ------------------------------------------------------------------
    // K
    // ------------------------------------------------------------------

    /**
     * K を押した。<b>いまの立場で意味が変わる。</b>
     *
     * <p>受付中で入っていなければ参加（誘いの通知が消えたあとでも参加できる）、入っていれば
     * 参加したレースの画面、主催していれば主催者画面、レースが無ければ主催者画面（＝開く入口）。</p>
     */
    /** 観戦しているか。観戦者は信号の間も車を止めない。 */
    public static boolean spectating() {
        return lobby != null && lobby.spectating();
    }

    public static void onKey(Minecraft minecraft) {
        if (minecraft.gui.screen() != null || minecraft.player == null) {
            return;
        }
        if (lobby == null) {
            KurumaNetwork.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.QUERY));
            minecraft.gui.setScreen(new RaceOrganizerScreen());
            return;
        }
        // 主催者画面へ行くのは主催した本人だけ。OP でも主催者でなければ、K は参加（OP はコマンドで操作できる）。
        // LAN に公開したワールドでは入ってきた人も OP なので、ここで OP を通すと参加できなくなる
        if (lobby.isHost()) {
            minecraft.gui.setScreen(new RaceOrganizerScreen());
            return;
        }
        if (lobby.phase() == RaceLobbyPacket.Phase.OPEN && !lobby.joined() && !lobby.spectating()) {
            KurumaNetwork.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.JOIN));
            inviteTicks = 0;
            // 参加したら、そのまま参加したレースの画面を開く（参加済みかどうかは次の配信で画面に反映される）
        }
        minecraft.gui.setScreen(new RaceInfoScreen());
    }

    // ------------------------------------------------------------------
    // G セッティングの制限
    // ------------------------------------------------------------------

    /**
     * いま G セッティングを縛っている決まり。縛っていなければ null。
     *
     * <p>縛るのは<b>エントリーしてからゴールするまで</b>。受付中も縛る——決まりに合わせて入ったあとで
     * 外へ出られては判定の意味が無い。決まりに外れたまま入った車は、<b>直す向きにだけ</b>動かせる
     * （{@link Regulation#allowsChange}）。</p>
     */
    public static Regulation restriction() {
        if (lobby == null || !lobby.joined() || RaceHud.hasFinished()) {
            return null;
        }
        if (lobby.phase() == RaceLobbyPacket.Phase.RESULT) {
            return null;
        }
        Regulation regulation = regulation();
        return regulation.isFree() ? null : regulation;
    }

    /** 戻す（R・すべて戻す）を決まりに関係なく通してよいか。縛られていないか、まだスタート前なら通す。 */
    public static boolean allowsReset() {
        return restriction() == null || lobby.phase() == RaceLobbyPacket.Phase.OPEN;
    }

    /** 調整画面の値に、いま乗っている車の部品（ターボ・タイヤ・空力）を載せる。 */
    static CarSpec withParts(CarSpec tuned) {
        CarEntity car = CarClientDriver.ridingCar(Minecraft.getInstance());
        if (car == null) {
            return tuned;
        }
        CarSpec fitted = car.getSpec();
        return tuned.toBuilder().turbo(fitted.turbo()).tire(fitted.tire()).partAero(fitted.partAero()).build();
    }

    /** {@code from} から {@code to} へ変えてよいか。縛られていなければ常に true。 */
    public static boolean allowsTuning(CarSpec from, CarSpec to) {
        Regulation regulation = restriction();
        if (regulation == null) {
            return true;
        }
        return regulation.allowsChange(withParts(from), withParts(to), CarTuning.base());
    }

    // ------------------------------------------------------------------
    // 文言
    // ------------------------------------------------------------------

    /** 違反 1 つを短く（「出力 +80 PS」など）。 */
    static Component describe(Regulation.Violation violation) {
        String key = "race.kurumamod.violation." + violation.kind().name().toLowerCase(Locale.ROOT);
        return switch (violation.kind()) {
            case FIXED -> Component.translatable(key, (int) violation.actual());
            case POWER -> Component.translatable(key, round(violation.actual() - violation.limit()));
            case MASS -> Component.translatable(key, round(violation.limit() - violation.actual()));
            case PWR -> Component.translatable(key, String.format(Locale.ROOT, "%.1f", violation.actual()),
                    String.format(Locale.ROOT, "%.1f", violation.limit()));
            case GRIP -> Component.translatable(key,
                    String.format(Locale.ROOT, "%.2f", violation.actual() - violation.limit()));
            case DOWNFORCE -> Component.translatable(key, round(violation.actual() - violation.limit()));
            default -> Component.translatable(key);
        };
    }

    /** 違反をまとめて「・」でつなぐ。 */
    static Component describeAll(List<Regulation.Violation> violations) {
        Component out = Component.empty();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                out = Component.empty().append(out).append(" · ");
            }
            out = Component.empty().append(out).append(describe(violations.get(i)));
        }
        return out;
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    /**
     * 決まり 1 行。
     *
     * @param kind  どの決まりか（自分の車の値を出すため。真偽の決まりは対応する種類）
     * @param label 名前
     * @param rule  決まりの中身
     */
    record Rule(Regulation.Kind kind, Component label, Component rule) {
    }

    /** 入っている決まりを並べる。何も縛っていなければ空。 */
    static List<Rule> rules(Regulation regulation) {
        List<Rule> rules = new ArrayList<>();
        if (regulation.fixedTraits()) {
            rules.add(new Rule(Regulation.Kind.FIXED, Component.translatable("race.kurumamod.rule.fixed"),
                    regulation.fixesAll() ? Component.translatable("race.kurumamod.rule.fixed.value")
                            : Component.translatable("race.kurumamod.rule.fixed.partial", regulation.fixedCount())));
        }
        if (regulation.powerOn()) {
            rules.add(new Rule(Regulation.Kind.POWER, Component.translatable("race.kurumamod.rule.power"),
                    Component.translatable("race.kurumamod.rule.power.value", round(regulation.powerMax()))));
        }
        if (regulation.massOn()) {
            rules.add(new Rule(Regulation.Kind.MASS, Component.translatable("race.kurumamod.rule.mass"),
                    Component.translatable("race.kurumamod.rule.mass.value", round(regulation.massMin()))));
        }
        if (regulation.pwrOn()) {
            rules.add(new Rule(Regulation.Kind.PWR, Component.translatable("race.kurumamod.rule.pwr"),
                    Component.translatable("race.kurumamod.rule.pwr.value",
                            String.format(Locale.ROOT, "%.1f", regulation.pwrMin()))));
        }
        if (regulation.gripOn()) {
            rules.add(new Rule(Regulation.Kind.GRIP, Component.translatable("race.kurumamod.rule.grip"),
                    Component.translatable("race.kurumamod.rule.grip.value",
                            String.format(Locale.ROOT, "%.2f", regulation.gripMax()))));
        }
        if (regulation.downforceOn()) {
            rules.add(new Rule(Regulation.Kind.DOWNFORCE, Component.translatable("race.kurumamod.rule.downforce"),
                    Component.translatable("race.kurumamod.rule.downforce.value", round(regulation.downforceMax()))));
        }
        if (regulation.drives() != Regulation.ALL_DRIVES) {
            rules.add(new Rule(Regulation.Kind.DRIVE, Component.translatable("race.kurumamod.rule.drive"),
                    Component.literal(driveList(regulation.drives()))));
        }
        if (!regulation.turbo()) {
            rules.add(new Rule(Regulation.Kind.TURBO, Component.translatable("race.kurumamod.rule.turbo"),
                    Component.translatable("race.kurumamod.rule.turbo.value")));
        }
        if (!regulation.tractionControl() || !regulation.abs()) {
            Component value = !regulation.tractionControl() && !regulation.abs()
                    ? Component.translatable("race.kurumamod.rule.assist.none")
                    : !regulation.tractionControl()
                    ? Component.translatable("race.kurumamod.rule.assist.no_tcs")
                    : Component.translatable("race.kurumamod.rule.assist.no_abs");
            rules.add(new Rule(regulation.tractionControl() ? Regulation.Kind.ABS : Regulation.Kind.TRACTION_CONTROL,
                    Component.translatable("race.kurumamod.rule.assist"), value));
        }
        return rules;
    }

    /** 使ってよい駆動方式（「FF · FR」）。 */
    static String driveList(int drives) {
        List<String> names = new ArrayList<>();
        String[] all = {"FF", "FR", "4WD"};
        for (int i = 0; i < all.length; i++) {
            if ((drives & (1 << i)) != 0) {
                names.add(all[i]);
            }
        }
        return names.isEmpty() ? "-" : String.join(" · ", names);
    }

    /** 自分の車の、その決まりに当たる値。乗っていなければ「-」。 */
    static String myValue(Regulation.Kind kind) {
        CarEntity car = CarClientDriver.ridingCar(Minecraft.getInstance());
        if (car == null) {
            return "-";
        }
        CarSpec spec = car.getSpec();
        return switch (kind) {
            case FIXED -> Regulation.alteredTraits(spec, CarTypes.get(car.getCarId()).spec(), regulation().fixedMask()) == 0
                    ? Component.translatable("race.kurumamod.rule.fixed.mine_ok").getString()
                    : Component.translatable("race.kurumamod.violation.fixed",
                    Regulation.alteredTraits(spec, CarTypes.get(car.getCarId()).spec(), regulation().fixedMask())).getString();
            case POWER -> round(Regulation.powerPs(spec)) + " PS";
            case MASS -> round(spec.mass()) + " kg";
            case PWR -> String.format(Locale.ROOT, "%.1f", Regulation.powerWeightRatio(spec));
            case GRIP -> String.format(Locale.ROOT, "%.2f", Regulation.grip(spec));
            case DOWNFORCE -> round(Regulation.downforceKg(spec)) + " kg";
            case DRIVE -> new String[]{"FF", "FR", "4WD"}[spec.layout().ordinal()];
            case TURBO -> spec.turbo().present()
                    ? Component.translatable("race.kurumamod.rule.turbo.mine_on").getString() : "-";
            case TRACTION_CONTROL, ABS -> (spec.tractionControl() > 0.0 ? "TCS " : "")
                    + (spec.abs() > 0.0 ? "ABS" : "");
        };
    }

    /** 車種の名前。乗っていなければ「乗車していません」。 */
    static Component carName(String carId) {
        if (carId == null || carId.isEmpty()) {
            return Component.translatable("race.kurumamod.no_car");
        }
        Identifier id = Identifier.tryParse(carId);
        return id == null ? Component.literal(carId) : CarTypes.get(id).displayName();
    }

    /** 自分の判定。参加していなければ null。 */
    static RaceLobbyPacket.Entrant self() {
        if (lobby == null) {
            return null;
        }
        for (RaceLobbyPacket.Entrant entrant : lobby.entrants()) {
            if (entrant.you()) {
                return entrant;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 右の中ほどの通知
    // ------------------------------------------------------------------

    /**
     * 誘いの通知と参加者の欄。<b>G・H・チャットを開いていても出す</b>（エントリー中であることを
     * いつでも知れるように）。レースの画面（参加したレース・主催者画面）を開いている間だけは出さない——
     * 画面に同じものがあり、右の列と重なる。
     */
    public static void onRenderOverlay(GuiRender event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.hud.isHidden() || isRaceScreen(minecraft.gui.screen()) || lobby == null) {
            return;
        }
        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        Font font = minecraft.font;
        if (lobby.phase() == RaceLobbyPacket.Phase.OPEN && (lobby.isHost() || lobby.joined())) {
            renderRoster(graphics, font, width, height, lobby.isHost());
        } else if (inviteTicks > 0 && lobby.phase() == RaceLobbyPacket.Phase.OPEN && !lobby.joined()) {
            renderInvite(graphics, font, width, height);
        }
    }

    private static boolean isRaceScreen(Object screen) {
        return screen instanceof RaceInfoScreen || screen instanceof RaceOrganizerScreen;
    }

    private static String keyName() {
        return KurumaKeys.OPEN_RACE.getTranslatedKeyMessage().getString();
    }

    private static void renderInvite(GuiGraphicsExtractor graphics, Font font, int width, int height) {
        int panel = 170;
        int left = width - 12 - panel;
        int top = height / 2 - 20;

        Component tag = Component.literal("ENTRY OPEN");
        int tagWidth = font.width(tag) + 10;
        Slant.fill(graphics, left, top, tagWidth, 13, ACCENT);
        Slant.fill(graphics, left + tagWidth, top, panel - tagWidth, 13, PANEL);
        Slant.text(graphics, font, tag, left + 5, top + 3, WHITE);
        Slant.fit(graphics, font, Component.translatable("race.kurumamod.invite.opened", lobby.hostName()),
                left + tagWidth + 5, top + 3, panel - tagWidth - 10, WHITE);

        int row = top + 14;
        Slant.fill(graphics, left, row, panel, 17, PANEL_LIGHT);
        Component join = Component.translatable("race.kurumamod.invite.join", keyName());
        int joinWidth = font.width(join) + 8;
        Slant.fit(graphics, font, Component.translatable("race.kurumamod.invite.detail",
                lobby.course(), lobby.laps(), lobby.entrants().size()), left + 6, row + 5,
                panel - joinWidth - 16, WHITE);
        Slant.fill(graphics, left + panel - joinWidth - 4, row + 3, joinWidth, 11, WHITE);
        Slant.text(graphics, font, join, left + panel - joinWidth, row + 5, INK);

        // 残り時間。右端から縮む
        int bar = Math.round(panel * inviteTicks / (float) INVITE_TICKS);
        graphics.fill(left, row + 18, left + panel, row + 19, 0x33FFFFFF);
        graphics.fill(left, row + 18, left + bar, row + 19, WHITE);
    }

    /**
     * 参加者の欄。<b>主催者には赤（HOSTING）、参加している人には緑（ENTERED）</b>で、
     * スタートまで出し続ける。G や H を開いていても、チャットを開いていても出す——
     * エントリー中であることを、いつでも画面の端で知れるように。
     */
    private static void renderRoster(GuiGraphicsExtractor graphics, Font font, int width, int height, boolean host) {
        int color = host ? ACCENT : GREEN;
        String tag = host ? "HOSTING" : "ENTERED";
        int panel = 150;
        int left = width - 12 - panel;
        List<RaceLobbyPacket.Entrant> entrants = lobby.entrants();
        int rows = Math.min(8, entrants.size());
        int bodyHeight = 15 + rows * 13 + 16;
        int top = height / 2 - bodyHeight / 2;

        // 主催者には、誰かが入った・抜けたときだけ上に一言
        if (host && pingTicks > 0) {
            Component ping = Component.translatable(pingJoined
                    ? "race.kurumamod.hosting.joined" : "race.kurumamod.hosting.left", pingName);
            int pingWidth = Math.min(panel, font.width(ping) + 10);
            Slant.fill(graphics, left + panel - pingWidth, top - 15, pingWidth, 12, WHITE);
            Slant.fit(graphics, font, ping, left + panel - pingWidth + 5, top - 13, pingWidth - 10, INK);
        }

        graphics.fill(left, top, left + panel, top + bodyHeight, PANEL);
        graphics.fill(left, top, left + 3, top + bodyHeight, color);
        Slant.text(graphics, font, Component.literal(tag), left + 8, top + 4, color);
        int courseX = left + 8 + font.width(tag) + 6;
        Slant.fit(graphics, font, Component.translatable("race.kurumamod.hud_course", lobby.course(), lobby.laps()),
                courseX, top + 4, left + panel - 6 - courseX, WHITE);
        graphics.fill(left + 3, top + 14, left + panel, top + 15, 0x22FFFFFF);

        int ok = 0;
        int y = top + 17;
        for (int i = 0; i < entrants.size(); i++) {
            RaceLobbyPacket.Entrant entrant = entrants.get(i);
            boolean good = entrant.verdict() == RaceLobbyPacket.Verdict.OK;
            if (good) {
                ok++;
            }
            if (i >= rows) {
                continue;
            }
            if (entrant.you()) {
                graphics.fill(left + 3, y - 2, left + panel, y + 10, 0x26FFFFFF);
            }
            int dot = good ? OK_DOT : entrant.verdict() == RaceLobbyPacket.Verdict.NO_CAR ? GREY : NG;
            graphics.fill(left + 9, y + 2, left + 13, y + 6, dot);
            Component note = good ? Component.translatable("race.kurumamod.verdict.ok")
                    : entrant.verdict() == RaceLobbyPacket.Verdict.NO_CAR
                    ? Component.translatable("race.kurumamod.verdict.no_car")
                    : describe(entrant.violations().get(0));
            int noteWidth = Math.min(60, font.width(note));
            Slant.fit(graphics, font, entrant.name(), left + 17, y, panel - 17 - noteWidth - 12, WHITE);
            if (good || entrant.verdict() == RaceLobbyPacket.Verdict.NO_CAR) {
                Slant.fit(graphics, font, note, left + panel - 6 - noteWidth, y, noteWidth, GREY);
            } else {
                graphics.fill(left + panel - 9 - noteWidth, y - 1, left + panel - 4, y + 9, NG);
                Slant.fit(graphics, font, note, left + panel - 6 - noteWidth, y, noteWidth, WHITE);
            }
            y += 13;
        }

        int footer = top + bodyHeight - 16;
        graphics.fill(left + 3, footer, left + panel, footer + 1, 0x22FFFFFF);
        Component open = Component.translatable(host ? "race.kurumamod.hosting.open" : "race.kurumamod.entered.open",
                keyName());
        int openWidth = font.width(open) + 8;
        Slant.fit(graphics, font, Component.translatable("race.kurumamod.hosting.summary",
                ok, entrants.size() - ok), left + 8, footer + 5, panel - openWidth - 18, GREY);
        graphics.fill(left + panel - openWidth - 4, footer + 3, left + panel - 4, footer + 14, WHITE);
        Slant.text(graphics, font, open, left + panel - openWidth, footer + 5, INK);
    }
}
