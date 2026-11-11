package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.network.TimeAttackRecordsPacket;
import com.jdmmc.kurumamod.race.RaceManager;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * タイムアタックの画面（レースが無いときの K。レースがあればタブから）。<b>主催者画面と同じ組み方。</b>
 *
 * <ul>
 *   <li>左の列: 「クラス ▼」の行（押すと右の列に一覧が重なって開く。<b>選んだらそのクラスで走る</b>——
 *   先頭の「クラスなし」を選べば宣言しない）、そのクラスの<b>条件と自分の車の対照表</b>、「計測をやめる」</li>
 *   <li>右の列: 先頭の行の「トラック ▼」で選んだコースの<b>クラスなしと、選んだクラスのランキング</b>（自分の順位も）。
 *   幅があれば横に並べる。コースは記録を見るためだけのものなので、記録の側に置く</li>
 * </ul>
 *
 * <p>計測は今どおり、スタートラインを跨げば始まる。走るコースは跨いだラインで決まり、<b>ここで選ぶコースは
 * 記録を見るためだけのもの</b>。宣言するのはクラスだけで、どのコースを走ってもそのクラスの記録になる。
 * 走りながら開く画面なので、ゲームは止めない。</p>
 */
public final class TimeAttackScreen extends Screen {

    private static final int RULE_ROW = 16;
    private static final int RECORD_ROW = 12;
    /** 「×」を押してから「消す?」のままでいる時間 [ティック]。過ぎたら「×」に戻る。 */
    private static final int ARM_TICKS = 60;
    /** 「消す?」になってから、もう一度押して消せるまでの間 [ティック]。 */
    private static final int CONFIRM_DELAY_TICKS = 6;
    /** 開いている間、記録を頼み直す間隔 [ティック]（走って更新した記録を出すため）。 */
    private static final int REFRESH_TICKS = 100;

    /** 最後に受け取った記録。開き直したときにすぐ出せるよう、画面の外に持つ。 */
    private static TimeAttackRecordsPacket records;
    /** 記録を見ていたコース（開き直しても同じコースから）。 */
    private static String viewedCourse;
    /**
     * 走っているクラスの名前（宣言そのもの。ドロップダウンで選んだらその場で宣言する）。<b>空は「クラスなし」</b>——
     * 宣言しないことも一覧の 1 つとして選ばせる（「走る」「やめる」のボタンを別に置かない）。
     */
    private String selectedClass = "";

    private final PickList pick = new PickList();
    /** 開いている一覧がどちらか（"course" か "class"）。 */
    private String picking;
    private Button courseButton;
    private Button classButton;
    /** 計測中のタイムアタックをやめる（次にラインを越えたら測り直す）。 */
    private Button resetButton;
    private int refreshTicks;

    /** 1 回押して「消す?」になっている行（コース・クラス・UUID）。もう一度押すと消す。 */
    private String armedDelete;
    private int armedTicks;
    private int hoverX;
    private int hoverY;
    /** 描いた「×」の場所（クリックの当たり）。描くたびに作り直す。 */
    private final List<DeleteHit> deleteHits = new java.util.ArrayList<>();

    private record DeleteHit(int x0, int y0, int x1, int y1, String key, String raceClass, java.util.UUID player) {
    }

    private int leftX;
    private int frameRight;
    private int leftWidth;
    private int topY;
    private boolean compact;
    private int tabsRight;

    public TimeAttackScreen() {
        super(Component.translatable("screen.kurumamod.time_attack.title"));
    }

    /** 記録を受け取った（{@code CarClientPackets}）。 */
    static void acceptRecords(TimeAttackRecordsPacket packet) {
        records = packet;
    }

    /** サーバーで記録が消された。開いていれば、次のティックで頼み直す（古い記録を出したままにしない）。 */
    private static boolean recordsStale;

    static void recordsChanged() {
        recordsStale = true;
    }

    @Override
    protected void init() {
        // 枠（フル HD の幅が上限。広い画面では中央に寄せる）。狭ければ詰める
        leftX = UiFrame.left(width) + 12;
        frameRight = UiFrame.right(width) - 12;
        int frameWidth = frameRight - leftX;
        compact = height < 330 || frameWidth < 576;
        leftWidth = compact ? Math.min(200, frameWidth / 2 - 6) : 240;
        topY = compact ? 26 : 30;

        List<RaceTabs.Tab> tabs = RaceTabs.create(leftX, 6, RaceTabs.Page.TIME_ATTACK);
        for (RaceTabs.Tab tab : tabs) {
            addRenderableWidget(tab);
        }
        tabsRight = RaceTabs.right(tabs);

        int x = leftX;
        int rightX = leftX + leftWidth + 12;
        // 右の列の先頭の「トラック」の行。左の「クラス」の行と同じ形（名前の右にドロップダウン）
        courseButton = addRenderableWidget(new DropdownButton(rightX + 52, topY,
                Math.min(leftWidth - 52, frameRight - rightX - 52), 16,
                () -> pick.isOpen() && "course".equals(picking), button -> togglePick("course")));
        classButton = addRenderableWidget(new DropdownButton(x + 52, topY, leftWidth - 52, 16,
                () -> pick.isOpen() && "class".equals(picking), button -> togglePick("class")));
        resetButton = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.time_attack.reset"),
                        button -> KurumaNetwork.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.RESET_TIME_ATTACK)))
                .bounds(x, 0, leftWidth, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("screen.kurumamod.time_attack.reset_tip")))
                .build());
        pick.close();
        picking = null;
        ensureCourse();
        requestRecords();
        refreshWidgets();
    }

    // ------------------------------------------------------------------
    // 選ぶ
    // ------------------------------------------------------------------

    private void togglePick(String which) {
        if (pick.isOpen() && which.equals(picking)) {
            pick.close();
            refreshWidgets();
            return;
        }
        picking = which;
        int x = leftX + leftWidth + 12;
        if ("course".equals(which)) {
            // コースは右の列の見出しの下に開く（見出しのボタンは見えたまま）
            pick.open(Component.translatable("screen.kurumamod.time_attack.pick_course"), x, topY + 20,
                    frameRight - x, height - 36, courseItems(), viewedCourse, key -> {
                        viewedCourse = key;
                        requestRecords();
                        refreshWidgets();
                    });
        } else {
            // トラックの行の下に開く（トラックの行は見えたまま。上に重ねると字が透ける）
            pick.open(Component.translatable("screen.kurumamod.time_attack.pick_class"), x, topY + 20,
                    frameRight - x, height - 36, classItems(), selectedClass, key -> {
                        // 選んだらそのクラスで走る（周の途中なら、比べる相手が変わるのは次の周から）
                        TimeAttackClass.declare(key);
                        refreshWidgets();
                    });
        }
        refreshWidgets();
    }

    private static List<PickList.Item> courseItems() {
        List<PickList.Item> items = new ArrayList<>();
        for (RaceLobbyPacket.CourseInfo course : RaceLobby.courses()) {
            items.add(new PickList.Item(course.name(), Component.literal(course.name()),
                    Component.translatable("screen.kurumamod.time_attack.course_sub", course.checkpoints()),
                    null, 0, false));
        }
        return items;
    }

    private static List<PickList.Item> classItems() {
        List<PickList.Item> items = new ArrayList<>();
        String declared = TimeAttackClass.declared();
        // 先頭は「クラスなし」。選んで走れば宣言しないのと同じ
        items.add(new PickList.Item("", Component.translatable("screen.kurumamod.time_attack.overall_choice"),
                Component.translatable("screen.kurumamod.time_attack.overall_choice_sub"), null, 0, declared.isEmpty()));
        for (RaceLobbyPacket.RaceClass raceClass : RaceLobby.classes()) {
            Regulation regulation = Regulation.fromArray(raceClass.regulation());
            List<Regulation.Violation> violations = violations(regulation);
            Component badge = violations == null ? null : Component.translatable(violations.isEmpty()
                    ? "race.kurumamod.verdict.ok" : "race.kurumamod.verdict.violation");
            int color = violations != null && violations.isEmpty() ? RaceLobby.OK : RaceLobby.NG;
            items.add(new PickList.Item(raceClass.name(), Component.literal(raceClass.name()),
                    RaceLobby.summary(regulation), badge, color, raceClass.name().equals(declared)));
        }
        return items;
    }

    private void ensureCourse() {
        List<RaceLobbyPacket.CourseInfo> courses = RaceLobby.courses();
        if (courses.isEmpty()) {
            viewedCourse = null;
        } else if (viewedCourse == null || courses.stream().noneMatch(c -> c.name().equals(viewedCourse))) {
            viewedCourse = courses.get(0).name();
        }
    }

    private void requestRecords() {
        refreshTicks = REFRESH_TICKS;
        if (viewedCourse != null) {
            KurumaNetwork.sendToServer(new RaceActionPacket(RaceActionPacket.Action.RECORDS, viewedCourse, 0,
                    new double[0]));
        }
    }

    @Override
    public void tick() {
        if (armedTicks > 0 && --armedTicks == 0) {
            armedDelete = null;
        }
        if (recordsStale) {
            recordsStale = false;
            refreshTicks = 0;
        }
        if (--refreshTicks <= 0) {
            ensureCourse();
            requestRecords();
        }
        refreshWidgets();
    }

    /** 選択・宣言・一覧に合わせて、ボタンの字と出し分けを揃える。 */
    private void refreshWidgets() {
        List<RaceLobbyPacket.RaceClass> classes = RaceLobby.classes();
        String declared = TimeAttackClass.declared();
        // 出すのは今の宣言（クラスが消されたら、サーバーが宣言を外すのでクラスなしに戻る）
        selectedClass = declared;
        if (pick.isOpen()) {
            pick.update("course".equals(picking) ? courseItems() : classItems(),
                    "course".equals(picking) ? viewedCourse : selectedClass);
        }
        courseButton.setMessage(viewedCourse == null
                ? Component.translatable("screen.kurumamod.race_host.no_course") : Component.literal(viewedCourse));
        courseButton.active = viewedCourse != null;
        classButton.setMessage(selectedClass.isEmpty()
                ? Component.translatable("screen.kurumamod.time_attack.overall_choice") : Component.literal(selectedClass));

        // 計測をやめる（宣言とは別）。レースの参加者として走っている間は出さない（抜けるのはレースの画面）
        resetButton.setY(rulesBottom() + 6);
        resetButton.visible = RaceHud.isTimeAttacking() && !RaceLobby.racingNow();
    }

    /** 左の列の条件の表（と自分の車の判定）の下の端。 */
    private int rulesBottom() {
        Regulation regulation = selectedRegulation();
        int rows = regulation == null ? (RaceLobby.classes().isEmpty() ? 2 : 1) : Math.max(1, rulesOf(regulation).size());
        return topY + 22 + 14 + rows * RULE_ROW + (regulation != null ? 22 : 0);
    }

    private Regulation selectedRegulation() {
        for (RaceLobbyPacket.RaceClass raceClass : RaceLobby.classes()) {
            if (raceClass.name().equals(selectedClass)) {
                return Regulation.fromArray(raceClass.regulation());
            }
        }
        return null;
    }

    private static List<RaceLobby.Rule> rulesOf(Regulation regulation) {
        return regulation == null ? List.of() : RaceLobby.rules(regulation);
    }

    /** 乗っている車がその条件を満たしていない項目。乗っていなければ null。 */
    private static List<Regulation.Violation> violations(Regulation regulation) {
        CarEntity car = CarClientDriver.ridingCar(Minecraft.getInstance());
        if (car == null || regulation == null) {
            return null;
        }
        return regulation.check(car.getSpec(), CarTypes.get(car.getCarId()).spec());
    }

    // ------------------------------------------------------------------
    // 描画
    // ------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (minecraft == null || minecraft.level == null) {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        } else {
            // 26.3 では画面を開いているあいだの字幕を背景の段で描く。止めると字幕が消える
            minecraft.gui.hud.extractDeferredSubtitles();
            extractTransparentBackground(graphics);
        }
        renderPanels(graphics);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 「×」の上にマウスがあるか（記録の一覧は別のメソッドで描くので、ここで覚えておく）
        hoverX = mouseX;
        hoverY = mouseY;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // 一覧はボタンより上に
        pick.render(graphics, font, mouseX, mouseY);
    }

    private void row(GuiGraphicsExtractor graphics, int y, int h, boolean alt) {
        graphics.fill(leftX, y, leftX + leftWidth, y + h, alt ? RaceLobby.PANEL_LIGHT : RaceLobby.PANEL);
    }

    private void renderPanels(GuiGraphicsExtractor graphics) {
        Font font = this.font;
        String declared = TimeAttackClass.declared();

        // 見出し: 今どのクラスで測っているか
        String tag = "TIME ATTACK";
        int tagX = tabsRight + 10;
        int tagWidth = font.width(tag) + 12;
        Slant.fill(graphics, tagX, 8, tagWidth, 14, declared.isEmpty() ? 0xFF3A3A3A : RaceLobby.ACCENT);
        graphics.text(font, tag, tagX + 6, 11, RaceLobby.WHITE, false);
        Component status;
        if (RaceLobby.racingNow()) {
            status = Component.translatable("screen.kurumamod.time_attack.status_racing");
        } else if (RaceLobby.spectating()) {
            status = Component.translatable("screen.kurumamod.time_attack.status_spectating");
        } else if (declared.isEmpty()) {
            status = Component.translatable("screen.kurumamod.time_attack.status_none");
        } else {
            status = Component.translatable("screen.kurumamod.time_attack.status_class", declared);
        }
        int room = frameRight - (tagX + tagWidth) - 14;
        int shown = Math.min(font.width(status), room);
        Slant.fill(graphics, tagX + tagWidth, 8, shown + 14, 14, RaceLobby.PANEL);
        Slant.fit(graphics, font, status, tagX + tagWidth + 7, 11, room, RaceLobby.WHITE);

        renderLeft(graphics, font, declared);
        renderRecords(graphics, font);

        if (!compact) {
            Slant.fit(graphics, font, Component.translatable("screen.kurumamod.time_attack.note"),
                    leftX, height - 24, frameRight - leftX, RaceLobby.GREY);
        }
    }

    private void renderLeft(GuiGraphicsExtractor graphics, Font font, String declared) {
        int x = leftX;
        int y = topY;
        row(graphics, y - 2, 20, false);
        if (selectedClass.equals(declared)) {
            graphics.fill(x, y - 2, x + 2, y + 18, RaceLobby.ACCENT);
        }
        graphics.text(font, Component.translatable("screen.kurumamod.race_host.class"), x + 6, y + 4,
                RaceLobby.WHITE, false);
        y += 22;

        // 選んだクラスの条件と、自分の車の値
        Slant.fill(graphics, x, y, leftWidth, 12, RaceLobby.INK);
        graphics.text(font, "REGULATION", x + 7, y + 2, RaceLobby.WHITE, false);
        Component mineHead = Component.translatable("screen.kurumamod.race_info.rule_mine");
        graphics.text(font, mineHead, x + leftWidth - 7 - font.width(mineHead), y + 2, RaceLobby.GREY, false);
        y += 14;
        Regulation regulation = selectedRegulation();
        if (regulation == null) {
            // クラスなし: 条件は無い。クラスがまだ 1 つも無ければ、作り方も添える
            row(graphics, y, RULE_ROW - 1, false);
            Slant.fit(graphics, font, Component.translatable("screen.kurumamod.time_attack.overall_rules"),
                    x + 6, y + 4, leftWidth - 12, RaceLobby.GREY);
            if (RaceLobby.classes().isEmpty()) {
                row(graphics, y + RULE_ROW, RULE_ROW - 1, true);
                Slant.fit(graphics, font, Component.translatable("screen.kurumamod.time_attack.no_classes_sub"),
                        x + 6, y + RULE_ROW + 4, leftWidth - 12, RaceLobby.GREY);
            }
            return;
        }
        List<RaceLobby.Rule> rules = rulesOf(regulation);
        List<Regulation.Violation> violations = violations(regulation);
        if (rules.isEmpty()) {
            row(graphics, y, RULE_ROW - 1, false);
            Slant.text(graphics, font, Component.translatable("race.kurumamod.rule.free"), x + 6, y + 4,
                    RaceLobby.GREY);
            y += RULE_ROW;
        }
        for (int i = 0; i < rules.size(); i++) {
            RaceLobby.Rule rule = rules.get(i);
            row(graphics, y, RULE_ROW - 1, (i & 1) == 1);
            Slant.fit(graphics, font, rule.label(), x + 6, y + 4, leftWidth / 3, RaceLobby.WHITE);
            String mine = RaceLobby.myValue(rule.kind(), regulation);
            boolean bad = violations != null && violations.stream().anyMatch(v -> sameRule(v.kind(), rule.kind()));
            int mineX = x + leftWidth - 7 - font.width(mine);
            if (bad) {
                graphics.fill(mineX - 3, y + 2, x + leftWidth - 4, y + RULE_ROW - 3, RaceLobby.NG);
            }
            Slant.text(graphics, font, mine, mineX, y + 4, bad ? RaceLobby.WHITE : RaceLobby.OK_DOT);
            Slant.text(graphics, font, rule.rule(), mineX - 10 - font.width(rule.rule()), y + 4, RaceLobby.WHITE);
            y += RULE_ROW;
        }
        // 自分の車の判定
        boolean noCar = violations == null;
        boolean ok = !noCar && violations.isEmpty();
        Slant.fill(graphics, x, y + 2, leftWidth, 18, noCar ? 0xFF3A3A3A : ok ? RaceLobby.OK : RaceLobby.NG);
        Component verdict = noCar ? Component.translatable("race.kurumamod.no_car")
                : ok ? Component.translatable("screen.kurumamod.time_attack.fits")
                : Component.translatable("screen.kurumamod.time_attack.unfit", RaceLobby.describeAll(violations));
        Slant.fit(graphics, font, verdict, x + 8, y + 7, leftWidth - 16, RaceLobby.WHITE);
    }

    /** 条件の行と違反の種類が同じものを指すか。運転補助は 1 行にまとめてある。 */
    private static boolean sameRule(Regulation.Kind violation, Regulation.Kind rule) {
        boolean assist = violation == Regulation.Kind.TRACTION_CONTROL || violation == Regulation.Kind.ABS;
        boolean assistRule = rule == Regulation.Kind.TRACTION_CONTROL || rule == Regulation.Kind.ABS;
        return assist ? assistRule : violation == rule;
    }

    private void renderRecords(GuiGraphicsExtractor graphics, Font font) {
        deleteHits.clear();
        int x = leftX + leftWidth + 12;
        int w = frameRight - x;
        int y = topY - 2;
        // 先頭の行: 「トラック」と、そのドロップダウン（ボタンは init で置いてある）。左の「クラス」の行と同じ形
        graphics.fill(x, y, x + w, y + 20, RaceLobby.PANEL);
        graphics.text(font, Component.translatable("screen.kurumamod.race_host.course"), x + 6, y + 6,
                RaceLobby.WHITE, false);
        y += 22;
        if (viewedCourse == null) {
            graphics.fill(x, y, x + w, y + 20, RaceLobby.PANEL_LIGHT);
            Slant.fit(graphics, font, Component.translatable("screen.kurumamod.time_attack.no_courses"),
                    x + 7, y + 6, w - 14, RaceLobby.GREY);
            return;
        }
        TimeAttackRecordsPacket shown = records != null && records.course().equals(viewedCourse) ? records : null;
        // クラスなしと、選んでいるクラス。幅があれば横に並べる
        if (!selectedClass.isEmpty() && w >= 420) {
            int half = (w - 4) / 2;
            renderBoard(graphics, font, x, y, half, shown, "");
            renderBoard(graphics, font, x + half + 4, y, w - half - 4, shown, selectedClass);
        } else {
            y = renderBoard(graphics, font, x, y, w, shown, "") + 4;
            if (!selectedClass.isEmpty()) {
                renderBoard(graphics, font, x, y, w, shown, selectedClass);
            }
        }
    }

    /**
     * 記録を消す「×」（OP だけ）。1 回押すと「消す?」に変わる。
     *
     * @return 使った幅（OP でなければ 0）
     */
    private int deleteMark(GuiGraphicsExtractor graphics, Font font, int right, int y, String raceClass,
                           TimeAttackRecordsPacket.Row row) {
        if (!RaceLobby.classAdmin() || viewedCourse == null) {
            return 0;
        }
        String key = viewedCourse + "\n" + raceClass + "\n" + row.player();
        boolean armed = key.equals(armedDelete);
        String label = armed ? Component.translatable("screen.kurumamod.time_attack.delete_confirm").getString()
                : "\u00D7";
        int width = font.width(label) + 6;
        int x1 = right - 2;
        int x0 = x1 - width;
        boolean hover = hoverX >= x0 && hoverX < x1 && hoverY >= y && hoverY < y + RECORD_ROW;
        if (armed || hover) {
            graphics.fill(x0, y, x1, y + RECORD_ROW, armed ? 0xD8A01818 : 0x60A01818);
        }
        Slant.text(graphics, font, label, x0 + 3, y + 2, armed || hover ? RaceLobby.WHITE : RaceLobby.GREY);
        deleteHits.add(new DeleteHit(x0, y, x1, y + RECORD_ROW, key, raceClass, row.player()));
        return width;
    }

    /** @return 描いた下の端 */
    private int renderBoard(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, TimeAttackRecordsPacket shown,
                            String raceClass) {
        TimeAttackRecordsPacket.Board board = null;
        if (shown != null) {
            for (TimeAttackRecordsPacket.Board candidate : shown.boards()) {
                if (candidate.raceClass().equals(raceClass)) {
                    board = candidate;
                }
            }
        }
        graphics.fill(x, y, x + w, y + 13, raceClass.isEmpty() ? RaceLobby.PANEL : 0xD8401010);
        Component head = raceClass.isEmpty() ? Component.translatable("screen.kurumamod.time_attack.overall")
                : Component.literal(raceClass);
        Slant.fit(graphics, font, head, x + 7, y + 3, w - 60, RaceLobby.WHITE);
        if (board != null) {
            String count = Component.translatable("screen.kurumamod.time_attack.count", board.total()).getString();
            Slant.text(graphics, font, count, x + w - 7 - font.width(count), y + 3, RaceLobby.GREY);
        }
        y += 13;
        if (board == null || board.rows().isEmpty()) {
            graphics.fill(x, y, x + w, y + RECORD_ROW, RaceLobby.PANEL_LIGHT);
            Slant.fit(graphics, font, Component.translatable(shown == null
                            ? "screen.kurumamod.time_attack.loading" : "screen.kurumamod.time_attack.no_records"),
                    x + 7, y + 2, w - 14, RaceLobby.GREY);
            return y + RECORD_ROW;
        }
        long fastest = board.rows().get(0).millis();
        int previousPlace = 0;
        for (int i = 0; i < board.rows().size(); i++) {
            TimeAttackRecordsPacket.Row row = board.rows().get(i);
            if (row.place() > previousPlace + 1 && previousPlace > 0) {
                // 上位と自分の間が空いている
                graphics.fill(x, y, x + w, y + 7, RaceLobby.PANEL_LIGHT);
                graphics.text(font, "···", x + 8, y - 1, RaceLobby.GREY, false);
                y += 7;
            }
            previousPlace = row.place();
            graphics.fill(x, y, x + w, y + RECORD_ROW, row.you() ? 0x40FFFFFF
                    : (i & 1) == 0 ? RaceLobby.PANEL : RaceLobby.PANEL_LIGHT);
            if (row.you()) {
                graphics.fill(x, y, x + 2, y + RECORD_ROW, RaceLobby.ACCENT);
            }
            String place = String.valueOf(row.place());
            Slant.text(graphics, font, place, x + 14 - font.width(place), y + 2, RaceLobby.GREY);
            // OP には行の右端に「×」を出す（押すと「消す?」に変わり、もう一度押すと消す）
            int right = x + w - 7 - deleteMark(graphics, font, x + w, y, raceClass, row);
            String time = RaceManager.format(row.millis());
            String gap = row.place() == 1 ? "" : "+" + RaceManager.format(row.millis() - fastest);
            int timeX = right - font.width(time) - (gap.isEmpty() ? 0 : font.width(gap) + 6);
            Slant.fit(graphics, font, row.name(), x + 20, y + 2, timeX - x - 26, RaceLobby.WHITE);
            Slant.text(graphics, font, time, timeX, y + 2, RaceLobby.WHITE);
            if (!gap.isEmpty()) {
                Slant.text(graphics, font, gap, right - font.width(gap), y + 2, RaceLobby.GREY);
            }
            y += RECORD_ROW;
        }
        return y;
    }

    // ------------------------------------------------------------------
    // 入力
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        // 記録を消す「×」（OP だけ）。1 回目は「消す?」に変わるだけで、もう一度押すと消す
        // 左クリックだけ。<b>26.3 は入力が SDL3 で、左ボタンは 1</b>（GLFW の頃は 0。0 と書くと左クリックが全部素通りした）
        if (!pick.isOpen() && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            for (DeleteHit hit : deleteHits) {
                if (mouseX >= hit.x0() && mouseX < hit.x1() && mouseY >= hit.y0() && mouseY < hit.y1()) {
                    if (hit.key().equals(armedDelete)) {
                        if (ARM_TICKS - armedTicks < CONFIRM_DELAY_TICKS) {
                            return true;   // ダブルクリックで、確かめと削除が一度に済んでしまわないように
                        }
                        armedDelete = null;
                        KurumaNetwork.sendToServer(new RaceActionPacket(RaceActionPacket.Action.DELETE_RECORD,
                                viewedCourse, 0, new double[0], hit.player() + " " + hit.raceClass()));
                    } else {
                        armedDelete = hit.key();
                        armedTicks = ARM_TICKS;
                    }
                    return true;
                }
            }
        }
        if (pick.isOpen()) {
            // 「▼」のボタンを押したら、ボタンに任せる（同じなら閉じる、別なら開き直す）
            boolean onToggle = courseButton.isMouseOver(mouseX, mouseY) || classButton.isMouseOver(mouseX, mouseY);
            if (!pick.contains(mouseX, mouseY) && onToggle) {
                return super.mouseClicked(event, doubleClick);
            }
            pick.mouseClicked(mouseX, mouseY);
            refreshWidgets();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pick.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // Esc は、開いている一覧を先に閉じる
        if (event.isEscape() && pick.isOpen()) {
            pick.close();
            refreshWidgets();
            return true;
        }
        if (KurumaKeys.OPEN_RACE.matches(event)) {
            onClose();
            return true;
        }
        if (ScreenStyle.isDrivingKey(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
