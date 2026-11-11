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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * クライアント側のレースの様子（{@link RaceLobbyPacket}）。<b>参加と主催の入口。</b>
 *
 * <ul>
 *   <li>誰かがレースを開いたら、画面の右の中ほどに<b>誘いの通知</b>を数秒出す。K でレースの画面を開き、「参加する」で参加</li>
 *   <li>主催している間は、同じ場所に<b>主催中の表示</b>を出し続ける。K で主催者画面へ戻れる</li>
 *   <li>参加している間の K は、参加したレースの画面（{@link RaceInfoScreen}）を開く</li>
 *   <li>レースが無いときの K は、タイムアタックの画面（{@link TimeAttackScreen}）を開く。タブでレースを開く画面へ</li>
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
        TimeAttackClass.accept(packet);
        boolean wasOpen = previous != null && previous.phase() == RaceLobbyPacket.Phase.OPEN;
        lobby = packet.phase() == RaceLobbyPacket.Phase.NONE ? null : packet;
        coursesSeen = packet.courses();
        classAdminSeen = packet.classAdmin();
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
    private static List<RaceLobbyPacket.RaceClass> classesSeen = List.of();
    private static boolean classAdminSeen;

    /** クラスを保存・削除してよいか（OP）。最後に受け取ったもの。 */
    static boolean classAdmin() {
        return classAdminSeen;
    }

    /** クラスの一覧を受け取る（{@code RaceClassesPacket}。変わったときだけ届く）。 */
    public static void acceptClasses(List<RaceLobbyPacket.RaceClass> classes) {
        classesSeen = List.copyOf(classes);
    }

    /** サーバーに保存してあるクラス（最後に受け取ったもの）。 */
    static List<RaceLobbyPacket.RaceClass> classes() {
        return classesSeen;
    }

    /**
     * 決まりとぴったり同じ中身のクラスの名前。無ければ null。
     *
     * <p><b>レースはクラスの名前を持たない。</b>持つのは決まりの中身だけなので、どのクラスで開いたかは
     * 中身を比べて引く。1 項目でも変えれば別物（＝どのクラスでもない）になる。同じ中身のクラスが
     * 2 つあれば先のほう。</p>
     */
    static String classNameOf(Regulation regulation) {
        double[] values = regulation.toArray();
        for (RaceLobbyPacket.RaceClass raceClass : classesSeen) {
            // 保存したときと項目の数が違っても比べられるよう、一度読み直して並びを揃える
            double[] other = Regulation.fromArray(raceClass.regulation()).toArray();
            if (sameValues(values, other)) {
                return raceClass.name();
            }
        }
        return null;
    }

    private static boolean sameValues(double[] a, double[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-6 * Math.max(1.0, Math.abs(a[i]))) {
                return false;
            }
        }
        return true;
    }

    /** 決まりを 1 行に縮めたもの（「250 PS 以下 · FR」）。何も縛っていなければ「決まりはありません」。 */
    static Component summary(Regulation regulation) {
        List<Rule> rules = rules(regulation);
        if (rules.isEmpty()) {
            return Component.translatable("race.kurumamod.rule.free");
        }
        MutableComponent out = Component.empty();
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                out.append(" · ");
            }
            out.append(rules.get(i).rule());
        }
        return out;
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
        return minecraft.player != null && minecraft.player.getGameProfile().getName().equals(name);
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
        TimeAttackClass.onClientTick();
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
        classesSeen = List.of();
        classAdminSeen = false;
        inviteTicks = 0;
        knownEntrants = new HashSet<>();
        pingTicks = 0;
        TimeAttackClass.reset();
    }

    // ------------------------------------------------------------------
    // K
    // ------------------------------------------------------------------

    /**
     * K を押した。<b>いまの立場で意味が変わる。</b>
     *
     * <p>レースがあればそのレースの画面（主催していれば主催者画面）、レースが無ければタイムアタックの画面。
     * どの画面も上のタブで行き来できる。</p>
     */
    /** エントリーか観戦をしているレースのコース。どちらでもなければ null。ゲートの見せ方に使う。 */
    static String followedCourse() {
        return lobby != null && (lobby.joined() || lobby.spectating()) && !lobby.course().isEmpty()
                ? lobby.course() : null;
    }

    /** 観戦しているか。観戦者は信号の間も車を止めない。 */
    public static boolean spectating() {
        return lobby != null && lobby.spectating();
    }

    public static void onKey(Minecraft minecraft) {
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        // レースが無ければタイムアタックの画面（タブでレースを開く画面へ行ける）
        if (lobby == null) {
            KurumaNetwork.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.QUERY));
            minecraft.setScreen(new TimeAttackScreen());
            return;
        }
        // レースがあるときは、そのレースの画面。<b>K を押しただけでは参加しない</b>——タイムアタックの宣言を
        // 変えたいだけの人まで参加させてしまうため。参加は画面の「参加する」から
        inviteTicks = 0;
        openRaceTab(minecraft);
    }

    /**
     * レースのタブ。主催者画面へ行くのは主催した本人とレースが無いとき（開く入口）。OP でも主催者でなければ
     * 参加したレースの画面（LAN に公開したワールドでは入ってきた人も OP なので、OP を通すと参加できなくなる）。
     */
    static void openRaceTab(Minecraft minecraft) {
        if (lobby == null) {
            KurumaNetwork.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.QUERY));
            minecraft.setScreen(new RaceOrganizerScreen());
        } else if (lobby.isHost()) {
            minecraft.setScreen(new RaceOrganizerScreen());
        } else {
            minecraft.setScreen(new RaceInfoScreen());
        }
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
     *
     * <p>レースに出ていなければ、<b>タイムアタックで宣言しているクラス</b>で縛る（観戦中は測らないので縛らない）。
     * サーバーの {@code RaceManager#restrictionFor} と同じ順で決める。</p>
     */
    public static Regulation restriction() {
        Regulation race = raceRestriction();
        if (race != null || racingNow()) {
            return race;
        }
        if (spectating()) {
            return null;
        }
        Regulation declared = TimeAttackClass.regulation();
        return declared == null || declared.isFree() ? null : declared;
    }

    /** エントリーしていてまだゴールしていない（宣言よりレースの決まりが優先される）。 */
    static boolean racingNow() {
        return lobby != null && lobby.joined() && !RaceHud.hasFinished()
                && lobby.phase() != RaceLobbyPacket.Phase.RESULT;
    }

    private static Regulation raceRestriction() {
        if (!racingNow()) {
            return null;
        }
        Regulation regulation = regulation();
        return regulation.isFree() ? null : regulation;
    }

    /**
     * 戻す（R・すべて戻す）を決まりに関係なく通してよいか。縛られていないか、まだスタート前なら通す。
     * クラスで縛られているときは通さない（戻した先が決まりに合うかを見る）。
     */
    public static boolean allowsReset() {
        if (raceRestriction() != null) {
            return lobby.phase() == RaceLobbyPacket.Phase.OPEN;
        }
        return restriction() == null;
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
        return myValue(kind, regulation());
    }

    /** 乗っている車の値を、{@code regulation} の決まりの行に並べる形で（素性の固定は、その決まりで固定する項目で数える）。 */
    static String myValue(Regulation.Kind kind, Regulation regulation) {
        CarEntity car = CarClientDriver.ridingCar(Minecraft.getInstance());
        if (car == null) {
            return "-";
        }
        CarSpec spec = car.getSpec();
        return switch (kind) {
            case FIXED -> Regulation.alteredTraits(spec, CarTypes.get(car.getCarId()).spec(), regulation.fixedMask()) == 0
                    ? Component.translatable("race.kurumamod.rule.fixed.mine_ok").getString()
                    : Component.translatable("race.kurumamod.violation.fixed",
                    Regulation.alteredTraits(spec, CarTypes.get(car.getCarId()).spec(), regulation.fixedMask())).getString();
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
        ResourceLocation id = ResourceLocation.tryParse(carId);
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
        if (minecraft.options.hideGui || isRaceScreen(minecraft.screen) || lobby == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
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

    private static void renderInvite(GuiGraphics graphics, Font font, int width, int height) {
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
        // クラスで開いたレースなら、クラスの名前を先頭に出す（どの車で出ればいいかがまず分かる）
        String className = classNameOf(Regulation.fromArray(lobby.regulation()));
        Component detail = className != null
                ? Component.translatable("race.kurumamod.invite.detail_class", className,
                lobby.course(), lobby.laps(), lobby.entrants().size())
                : Component.translatable("race.kurumamod.invite.detail",
                lobby.course(), lobby.laps(), lobby.entrants().size());
        Slant.fit(graphics, font, detail, left + 6, row + 5, panel - joinWidth - 16, WHITE);
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
    private static void renderRoster(GuiGraphics graphics, Font font, int width, int height, boolean host) {
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
