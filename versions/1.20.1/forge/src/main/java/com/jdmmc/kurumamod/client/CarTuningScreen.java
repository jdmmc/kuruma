package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 車のセッティング画面。
 *
 * <h2>換装画面と同じ構図</h2>
 *
 * <p><b>画面は暗くしない</b>（{@link Screen#renderBackground} を呼ばない）。ここは
 * 走らせながら詰める画面なので、沈み込みも姿勢も見えていないと決められない。
 * グループは<b>下端のタブ 1 列</b>、項目は<b>そのすぐ上</b>、見出しは左上——
 * {@link CarPartScreen} とまったく同じ並びで、配色も {@link ScreenStyle} を共有し、
 * カメラも同じショールームの構えになる（{@link CarChaseCamera}）。</p>
 *
 * <p>項目は {@link Tunables} の定義から組み立てるので、<b>この画面は項目が増えても
 * 触らなくてよい</b>。1 行は「項目名 ／ スライダー（値と補足） ／ 個別リセット」の 3 列で、
 * 既定値から変えた項目は名前が黄色くなる。</p>
 *
 * <p>スライダーを動かすとその場で {@link CarTuning} に反映され、運転中の車の挙動が次の
 * ティックから変わる。ゲームを止めないので、車が沈み込む様子をそのまま観察できる。</p>
 */
public class CarTuningScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int LABEL_WIDTH = 116;
    private static final int RESET_WIDTH = 18;
    private static final int GAP = 4;
    private static final int ROW_HEIGHT = 21;
    private static final int LOCK_WIDTH = 13;
    /** 鍵のボタンの高さ。上の行のスライダーの中ほどから、下の行の中ほどまで。 */
    private static final int LOCK_HEIGHT = ROW_HEIGHT + 10;
    /** 解説の折り返し幅。画面の端まで広がると読みにくい */
    private static final int TOOLTIP_WIDTH = 220;

    // 下端のタブ。名前しか入らないので、換装画面のタイルより低くしてある
    private static final int TAB_HEIGHT = 30;
    private static final int TAB_RAISE = 8;
    private static final int TAB_GAP = 6;
    private static final int TAB_MIN_WIDTH = 44;
    private static final int TAB_MAX_WIDTH = 92;
    private static final int BOTTOM_MARGIN = 8;
    private static final int SIDE_MARGIN = 22;

    private static final int TEXT_LEFT = 24;
    private static final int TITLE_Y = 18;
    /** 一覧がここより上へは伸びない。見出しと右上のボタンの下。 */
    private static final int LIST_MIN_TOP = 58;
    /** 一覧の下端とタブの間。 */
    private static final int LIST_BOTTOM_GAP = 10;

    private static final int COLOR_NAME = 0xFFC8C8C8;

    /** 開き直したときに同じタブへ戻れるよう覚えておく。 */
    private static int selectedGroup;

    /** 鍵を開けた組（上の行の項目名）。既定は閉じている（前後が一緒に動く）。 */
    private static final Set<String> unlinked = new HashSet<>();

    private final List<Row> rows = new ArrayList<>();
    private final List<Lock> locks = new ArrayList<>();
    private final List<GroupTab> tabs = new ArrayList<>();

    private int scroll;
    private int visibleRows;
    private int panelLeft;
    /**
     * 一覧の上端。<b>下端から決める</b>ので、グループによって変わる。
     *
     * <p>項目の少ないグループでも<b>タブのすぐ上に並ぶ</b>ようにするため。上から積むと、
     * グループを切り替えるたびに一覧の下端が上下して落ち着かない。</p>
     */
    private int listTop;

    /** 左端に出しているタブの番号。入りきらない画面では横に送る。 */
    private int tabScroll;
    private int visibleTabs;
    private int tabWidth;
    private int tabLeft;
    private int tabTop;

    /** 上のボタン（プリセット・換装・全部戻す）。助手席では押せなくする。 */
    private final List<Button> headerButtons = new ArrayList<>();

    public CarTuningScreen() {
        super(Component.translatable("screen.kurumamod.tuning.title"));
    }

    /**
     * 変えてよいか。<b>助手席では見るだけ</b>（人の車なので触れない）。
     * 運転している人が降りて自分が運転席へ移れば、開いたまま変えられるようになる。
     */
    private boolean editable() {
        return minecraft == null || CarClientDriver.isDriver(minecraft);
    }

    private static List<Tunables.Group> groups() {
        return Tunables.GROUPS;
    }

    @Override
    protected void init() {
        rows.clear();
        locks.clear();
        tabs.clear();
        headerButtons.clear();
        panelLeft = (width - PANEL_WIDTH) / 2;
        selectedGroup = Math.max(0, Math.min(selectedGroup, groups().size() - 1));

        addTabs();
        addHeaderButtons();

        List<TunableParameter> parameters = groups().get(selectedGroup).parameters();
        int listBottom = tabTop - LIST_BOTTOM_GAP;
        int available = listBottom - LIST_MIN_TOP;
        visibleRows = Math.max(1, Math.min(parameters.size(), available / ROW_HEIGHT));
        // 下端に揃える。換装画面のスライダーがタイルの真上に来るのと同じ
        listTop = listBottom - visibleRows * ROW_HEIGHT;
        for (TunableParameter parameter : parameters) {
            rows.add(createRow(parameter));
        }
        addLocks();

        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows));
        layout();
    }

    /**
     * 下端のタブ。<b>幅は画面の空きから決める。</b>
     *
     * <p>8 つを固定幅で並べると狭い画面からはみ出す。入るなら全部入る幅まで詰め、
     * それでも入らなければ横に送れるようにする（メーターの大きさを画面の空きから
     * 決めているのと同じ理屈）。</p>
     */
    private void addTabs() {
        int count = groups().size();
        int available = width - SIDE_MARGIN * 2;
        tabWidth = Math.max(TAB_MIN_WIDTH,
                Math.min(TAB_MAX_WIDTH, (available - TAB_GAP * (count - 1)) / count));
        visibleTabs = Math.max(1, Math.min(count, (available + TAB_GAP) / (tabWidth + TAB_GAP)));
        tabTop = height - BOTTOM_MARGIN - TAB_HEIGHT - TAB_RAISE;
        tabLeft = (width - (visibleTabs * (tabWidth + TAB_GAP) - TAB_GAP)) / 2;

        tabScroll = Math.max(0, Math.min(tabScroll, count - visibleTabs));
        // 選んでいるタブが送られて見えなくなっていたら、見える位置まで戻す
        if (selectedGroup < tabScroll) {
            tabScroll = selectedGroup;
        } else if (selectedGroup >= tabScroll + visibleTabs) {
            tabScroll = selectedGroup - visibleTabs + 1;
        }

        for (int i = 0; i < count; i++) {
            tabs.add(addRenderableWidget(new GroupTab(i)));
        }
        layoutTabs();
    }

    /**
     * 右上のボタン。<b>「完了」は置かない。</b>
     *
     * <p>閉じるのは Esc で、それは見出しの下に書いてある。下端はタブが占めているので、
     * ボタンを増やすほど見える車が減る。</p>
     */
    private void addHeaderButtons() {
        int buttonWidth = 68;
        int right = width - SIDE_MARGIN;
        int y = TITLE_Y - 5;
        headerButtons.add(addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.tuning.presets"),
                        button -> minecraft.setScreen(new CarPresetScreen(this)))
                .bounds(right - buttonWidth * 3 - GAP * 2, y, buttonWidth, 20)
                .build()));
        // 換装はここから開く。設定の置き場所を 2 か所にしないのと同じ理由で、
        // 車をいじる入り口はこの画面に集める
        headerButtons.add(addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.tuning.parts"),
                        button -> minecraft.setScreen(new CarPartScreen(this)))
                .bounds(right - buttonWidth * 2 - GAP, y, buttonWidth, 20)
                .build()));
        headerButtons.add(addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.tuning.reset_all"),
                        button -> {
                            CarTuning.reset();
                            rows.forEach(Row::syncSlider);
                            syncToServer();
                            // パーツも純正へ（角度・オフセット・太さの上書きも消える）。
                            // 「すべて」と書いてあるのに換装だけ残ると、元の車に戻らない
                            PresetParts.apply(PartFitment.EMPTY);
                        })
                .bounds(right - buttonWidth, y, buttonWidth, 20)
                .build()));
    }

    private Row createRow(TunableParameter parameter) {
        int sliderLeft = panelLeft + LABEL_WIDTH;
        int sliderWidth = PANEL_WIDTH - LABEL_WIDTH - RESET_WIDTH - GAP;
        ParameterSlider slider = addRenderableWidget(
                new ParameterSlider(sliderLeft, 0, sliderWidth, 18, parameter));
        // ウィジェットを作り直すのではなくスライダーの値だけ差し替える。
        // 作り直すと、クリックの伝播中にウィジェット一覧を入れ替えることになって危うい
        Button reset = addRenderableWidget(Button.builder(
                        Component.literal("R"),
                        button -> {
                            CarTuning.resetParameter(parameter);
                            slider.resetPartner();
                            slider.syncFromTuning();
                            syncToServer();
                        })
                .tooltip(Tooltip.create(Component.translatable("screen.kurumamod.tuning.reset_one")))
                .bounds(sliderLeft + sliderWidth + GAP, 0, RESET_WIDTH, 18)
                .build());
        return new Row(parameter, slider, reset);
    }

    /** 連動する 2 行の間に鍵を置く。 */
    private void addLocks() {
        for (int i = 0; i + 1 < rows.size(); i++) {
            Row upper = rows.get(i);
            Row lower = rows.get(i + 1);
            if (Tunables.linkedWith(upper.parameter) != lower.parameter) {
                continue;
            }
            String key = upper.parameter.name();
            upper.slider.link(lower.slider, key);
            lower.slider.link(upper.slider, key);
            upper.nameWidth = lower.nameWidth = LABEL_WIDTH - GAP - LOCK_WIDTH;
            int x = panelLeft + LABEL_WIDTH - LOCK_WIDTH - 1;
            locks.add(new Lock(upper, lower, addRenderableWidget(new LinkLock(x, key))));
        }
    }

    /** 表示範囲に入る行だけを並べ、外れた行は隠す。 */
    private void layout() {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int index = i - scroll;
            row.y = listTop + index * ROW_HEIGHT;
            row.setVisible(index >= 0 && index < visibleRows);
        }
        for (Lock lock : locks) {
            lock.widget.visible = lock.upper.visible && lock.lower.visible;
            lock.widget.setY(lock.upper.y + 4);
        }
    }

    /** 送ったぶんだけタブを置き直す。作り直さないのは、クリックの伝播中に触らないため。 */
    private void layoutTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            GroupTab tab = tabs.get(i);
            int index = i - tabScroll;
            tab.visible = index >= 0 && index < visibleTabs;
            tab.setPosition(tabLeft + index * (tabWidth + TAB_GAP), tabTop);
        }
    }

    /**
     * ホイールは<b>指している場所で意味が変わる</b>。
     *
     * <p>タブの上ならタブを送り、項目の上なら一覧を送り、それ以外（＝車が写っている
     * ところ）なら寄り引き。換装画面と同じ振り分け方。</p>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= tabTop) {
            int max = Math.max(0, tabs.size() - visibleTabs);
            int next = Math.max(0, Math.min(max, tabScroll - (int) Math.signum(delta)));
            if (next != tabScroll) {
                tabScroll = next;
                layoutTabs();
            }
            return true;
        }
        if (mouseY < listTop) {
            CarChaseCamera.zoom(delta);
            return true;
        }
        int max = Math.max(0, rows.size() - visibleRows);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            layout();
        }
        return true;
    }

    /**
     * 何も無いところをドラッグしたら、車のまわりを回す。
     *
     * <p>先にウィジェットへ渡すこと。スライダーをつまんだ指がカメラも動かしては困る。</p>
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        CarChaseCamera.orbit(dragX, dragY);
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // renderBackground は呼ばない。走らせながら詰める画面なので、
        // 暗くすると肝心の車の動きが見えなくなる

        boolean editable = editable();
        if (!editable) {
            // 助手席: 運転している人の今の値を追って表示する
            CarEntity car = CarClientDriver.ridingCar(minecraft);
            if (car != null && CarTuning.spec() != car.getSpec()) {
                CarClientDriver.viewPassengerCar(car);
                rows.forEach(Row::syncSlider);
            }
        }
        for (Button button : headerButtons) {
            button.active = editable;
        }
        for (Row row : rows) {
            row.refresh(editable);
        }

        for (Lock lock : locks) {
            lock.widget.active = editable;
        }

        // 下敷きは行ごとに敷く。パネルで一枚に覆うと、そのぶん車が隠れる
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.visible) {
                graphics.fill(panelLeft - 4, row.y - 2, panelLeft + PANEL_WIDTH + 4,
                        row.y + ROW_HEIGHT - 3,
                        ((i - scroll) & 1) == 0 ? ScreenStyle.ROW_ALT : ScreenStyle.ROW);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, TEXT_LEFT, TITLE_Y, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable(groups().get(selectedGroup).translationKey()),
                TEXT_LEFT, TITLE_Y + 12, ScreenStyle.ACCENT);
        graphics.drawString(font, Component.translatable(editable
                        ? "screen.kurumamod.tuning.hint" : "screen.kurumamod.tuning.hint_passenger"),
                TEXT_LEFT, TITLE_Y + 26, ScreenStyle.HINT);

        for (Row row : rows) {
            if (!row.visible) {
                continue;
            }
            String name = Component.translatable(row.parameter.translationKey()).getString();
            boolean changed = !row.parameter.isDefault(CarTuning.spec(), CarTuning.base());
            graphics.drawString(font, font.plainSubstrByWidth(name, row.nameWidth),
                    panelLeft, row.y + 5, regulationRed(row) ? 0xFFFF5555 : changed ? ScreenStyle.ACCENT : COLOR_NAME, false);
        }

        renderScrollBar(graphics);
        renderTabHints(graphics);
        renderRegulationLimits(graphics, mouseX, mouseY);
        renderRowTooltip(graphics, mouseX, mouseY);
    }

    /** タブごとの「決まりに外れている項目があるか」。値か決まりが変わったときだけ測り直す。 */
    private boolean[] groupRed = new boolean[0];
    private int groupRedRevision = Integer.MIN_VALUE;
    private Object groupRedRule;

    /** そのタブに、決まりに外れている項目があるか（タブを赤くする）。 */
    private boolean groupViolating(int index) {
        com.jdmmc.kurumamod.tuning.Regulation rule = RaceLobby.restriction();
        if (rule == null || !editable()) {
            return false;
        }
        int revision = CarTuning.revision();
        if (revision != groupRedRevision || !java.util.Objects.equals(rule, groupRedRule)) {
            groupRedRevision = revision;
            groupRedRule = rule;
            groupRed = computeGroupRed(rule);
        }
        return index < groupRed.length && groupRed[index];
    }

    /**
     * タブごとに、外れている項目があるかを調べる。
     *
     * <p>項目の名前を赤くするのと同じ考え方で、<b>いま外れている決まりの外れ量を、その項目を動かすと
     * 変えられるか</b>で見る（スライダーの両端で測り直して比べる）。固定の項目は、車種の値から
     * 変えてあれば外れ。表示していないタブの項目も見るので、行のスライダーには頼らない。</p>
     */
    private static boolean[] computeGroupRed(com.jdmmc.kurumamod.tuning.Regulation rule) {
        List<Tunables.Group> groups = groups();
        boolean[] out = new boolean[groups.size()];
        com.jdmmc.kurumamod.physics.CarSpec spec = CarTuning.spec();
        com.jdmmc.kurumamod.physics.CarSpec base = CarTuning.base();
        List<com.jdmmc.kurumamod.tuning.Regulation.Violation> now = rule.check(RaceLobby.withParts(spec), base);
        if (now.isEmpty()) {
            return out;
        }
        for (int g = 0; g < groups.size(); g++) {
            for (TunableParameter parameter : groups.get(g).parameters()) {
                boolean red = rule.fixes(parameter)
                        ? !parameter.isDefault(spec, base)
                        : affectsViolation(rule, parameter, spec, base, now);
                if (red) {
                    out[g] = true;
                    break;
                }
            }
        }
        return out;
    }

    /** その項目を動かすと、いま外れている決まりの外れ量が変わるか。 */
    private static boolean affectsViolation(com.jdmmc.kurumamod.tuning.Regulation rule, TunableParameter parameter,
                                            com.jdmmc.kurumamod.physics.CarSpec spec,
                                            com.jdmmc.kurumamod.physics.CarSpec base,
                                            List<com.jdmmc.kurumamod.tuning.Regulation.Violation> now) {
        for (double position : new double[]{0.0, 1.0}) {
            com.jdmmc.kurumamod.physics.CarSpec.Builder builder = spec.toBuilder();
            parameter.applyDisplay(builder, parameter.displayFromSlider(position));
            List<com.jdmmc.kurumamod.tuning.Regulation.Violation> at =
                    rule.check(RaceLobby.withParts(builder.build()), base);
            for (com.jdmmc.kurumamod.tuning.Regulation.Violation violation : now) {
                double excess = 0.0;
                for (com.jdmmc.kurumamod.tuning.Regulation.Violation other : at) {
                    if (other.kind() == violation.kind()) {
                        excess = other.excess();
                    }
                }
                if (Math.abs(excess - violation.excess()) > 1e-6) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 車の素性として固定された項目のスライダー。<b>押せないだけでは分かりにくい</b>ので、
     * 灰色の斜線で覆い、左端に錠前と「固定」の札を出す。値は読めるように薄く覆うだけにする。
     */
    private void renderLocked(GuiGraphics graphics, int x0, int y0, int x1, int y1) {
        Slant.hatch(graphics, x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x66C8C8C8, 0x59000000);
        int x = x0 + 4;
        int y = y0 + (y1 - y0 - 13) / 2;
        int color = 0xFFE8E8E8;
        // つる
        graphics.fill(x + 1, y + 1, x + 8, y + 3, color);
        graphics.fill(x + 1, y + 1, x + 3, y + 6, color);
        graphics.fill(x + 6, y + 1, x + 8, y + 6, color);
        // 本体と鍵穴
        graphics.fill(x, y + 6, x + 9, y + 13, color);
        graphics.fill(x + 4, y + 8, x + 5, y + 11, 0xFF333333);
        Component tag = Component.translatable("race.kurumamod.tuning_fixed_tag");
        int tagX = x + 12;
        int tagWidth = font.width(tag) + 6;
        graphics.fill(tagX, y + 2, tagX + tagWidth, y + 12, 0xE6444444);
        Slant.text(graphics, font, tag, tagX + 3, y + 3, color);
    }

    /** 決まりに外れている項目（名前を赤くする）。固定の項目は、車種の値から変えてあれば外れ。 */
    private boolean regulationRed(Row row) {
        com.jdmmc.kurumamod.tuning.Regulation restriction = RaceLobby.restriction();
        if (restriction == null || !editable()) {
            return false;
        }
        if (restriction.fixes(row.parameter)) {
            return !row.parameter.isDefault(CarTuning.spec(), CarTuning.base());
        }
        return row.slider.violating();
    }

    /**
     * レース中の決まり（レギュレーション）の見せ方。<b>画面の構図は変えない。</b>
     *
     * <ul>
     *   <li>決まりの外になる範囲を、スライダーの上に橙の斜線で塞ぐ（つまみはその手前で止まる）</li>
     *   <li>車の素性として固定された項目は、スライダーを押せない見た目にする（{@link Row#refresh}）</li>
     *   <li>斜線・固定の行にカーソルを乗せたら、いつものツールチップで理由を出す</li>
     * </ul>
     */
    private void renderRegulationLimits(GuiGraphics graphics, int mouseX, int mouseY) {
        com.jdmmc.kurumamod.tuning.Regulation restriction = RaceLobby.restriction();
        if (restriction == null || !editable()) {
            return;
        }
        Component reason = null;
        for (Row row : rows) {
            if (!row.visible) {
                continue;
            }
            ParameterSlider slider = row.slider;
            int x0 = slider.getX();
            int x1 = slider.getX() + slider.getWidth();
            int y0 = slider.getY();
            int y1 = slider.getY() + slider.getHeight();
            boolean over = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
            if (restriction.fixes(row.parameter)) {
                renderLocked(graphics, x0, y0, x1, y1);
                if (over) {
                    reason = Component.translatable("race.kurumamod.tuning_fixed");
                }
                continue;
            }
            int travel = slider.getWidth() - 8;
            for (double[] segment : slider.outsideRanges()) {
                // 端まで外なら、スライダーの縁まで塗る
                int a = segment[0] <= 1e-3 ? x0 + 1 : x0 + 4 + (int) Math.round(segment[0] * travel);
                int b = segment[1] >= 1.0 - 1e-3 ? x1 - 1 : x0 + 4 + (int) Math.round(segment[1] * travel);
                Slant.hatch(graphics, a, y0 + 1, b, y1 - 1, 0xCCFF8A00, 0x33FF8A00);
                if (segment[0] > 1e-3) {
                    graphics.fill(a, y0, a + 1, y1, 0xFFFF8A00);
                }
                if (segment[1] < 1.0 - 1e-3) {
                    graphics.fill(b - 1, y0, b, y1, 0xFFFF8A00);
                }
                if (over && mouseX >= a && mouseX < b) {
                    reason = Component.translatable("race.kurumamod.tuning_limit");
                }
            }
        }
        if (reason != null) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(Component.translatable("race.kurumamod.tuning_regulation")
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
            lines.addAll(font.split(reason, TOOLTIP_WIDTH));
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    /**
     * 項目名にマウスを乗せたら解説を出す。
     *
     * <p><b>スライダーの上では出さない。</b>動かしている最中に値が隠れると、何に合わせたのか
     * 分からなくなる。名前は列の幅で切れていることがあるので、解説の頭に名前を全部出す。
     * 解説の無い項目（拡張 MOD が足したものなど）は何も出さない。</p>
     */
    private void renderRowTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (mouseX < panelLeft || mouseX >= panelLeft + LABEL_WIDTH) {
            return;
        }
        // 鍵は項目名の列にある。鍵の上では鍵の説明だけを出す（重なって読めなくなる）
        for (Lock lock : locks) {
            if (lock.widget.visible && lock.widget.isMouseOver(mouseX, mouseY)) {
                return;
            }
        }
        for (Row row : rows) {
            if (!row.visible || mouseY < row.y || mouseY >= row.y + ROW_HEIGHT - 2) {
                continue;
            }
            String key = row.parameter.tooltipKey();
            if (!I18n.exists(key)) {
                return;
            }
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(Component.translatable(row.parameter.translationKey())
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
            lines.addAll(font.split(Component.translatable(key), TOOLTIP_WIDTH));
            graphics.renderTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    private void renderScrollBar(GuiGraphics graphics) {
        int total = rows.size();
        if (total <= visibleRows) {
            return;
        }
        int listHeight = visibleRows * ROW_HEIGHT;
        int left = panelLeft + PANEL_WIDTH + 8;
        graphics.fill(left, listTop, left + 3, listTop + listHeight, 0x40FFFFFF);
        int thumb = Math.max(12, listHeight * visibleRows / total);
        int top = listTop + (listHeight - thumb) * scroll / (total - visibleRows);
        graphics.fill(left, top, left + 3, top + thumb, 0xC0FFFFFF);
    }

    /** タブがまだ先にあることを出す。<b>出さないと 1 画面に収まっていると読める。</b> */
    private void renderTabHints(GuiGraphics graphics) {
        int centerY = tabTop + TAB_RAISE + TAB_HEIGHT / 2 - 4;
        if (tabScroll > 0) {
            graphics.drawCenteredString(font, "‹", tabLeft - SIDE_MARGIN / 2, centerY, ScreenStyle.LABEL);
        }
        if (tabScroll + visibleTabs < tabs.size()) {
            int right = tabLeft + visibleTabs * (tabWidth + TAB_GAP) - TAB_GAP;
            graphics.drawCenteredString(font, "›", right + SIDE_MARGIN / 2, centerY, ScreenStyle.LABEL);
        }
    }

    @Override
    public void removed() {
        // キーボードで動かした場合は onRelease が来ないので、閉じるときにも送っておく
        syncToServer();
    }

    /**
     * 運転に使うキーは画面へ渡さない。開いたまま運転できるので（{@link CarClientDriver}）、
     * 渡すとスペース（サイドブレーキ）でフォーカス中のボタンが押されたり、
     * 矢印キーに割り当てた人のハンドルでフォーカスやスライダーが動いたりする。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CarClientDriver.isDrivingKey(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** ゲームを止めない。止めると挙動の変化を見ながら調整できないため。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void syncToServer() {
        CarClientDriver.sendTuningToServer();
    }

    /**
     * 下端のタブ 1 つ。
     *
     * <p><b>選んでいるものは黄色く一段高い</b>（換装画面のタイルと同じ）。色だけだと、
     * 色覚の差や小さい画面でどれを見ているのか分からなくなる。</p>
     */
    private final class GroupTab extends AbstractWidget {

        private final int index;

        private GroupTab(int index) {
            super(0, 0, tabWidth, TAB_HEIGHT + TAB_RAISE,
                    Component.translatable(groups().get(index).shortKey()));
            this.index = index;
            setTooltip(Tooltip.create(Component.translatable(groups().get(index).translationKey())));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (index == selectedGroup) {
                return;
            }
            selectedGroup = index;
            scroll = 0;
            rebuildWidgets();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = index == selectedGroup;
            boolean hovered = isHovered() && !selected;
            // 下端は揃えたまま、選んでいるものだけ上へ伸ばす（列の底が波打たない）
            int top = selected ? getY() : getY() + TAB_RAISE;
            int height = getY() + TAB_HEIGHT + TAB_RAISE - top;
            // 高さと同じ帯を渡すとタイル全体が黄色くなる。名前しか入らないタブはこの方が読みやすい
            ScreenStyle.tileBox(graphics, getX(), top, tabWidth, height, selected, hovered, height);
            // 決まりに外れている項目があるタブは赤くする。選んでいるタブは黄色のまま、赤い枠で囲む
            if (groupViolating(index)) {
                int red = 0xFFDC2626;
                if (selected) {
                    graphics.fill(getX(), top, getX() + tabWidth, top + 2, red);
                    graphics.fill(getX(), top + height - 2, getX() + tabWidth, top + height, red);
                    graphics.fill(getX(), top, getX() + 2, top + height, red);
                    graphics.fill(getX() + tabWidth - 2, top, getX() + tabWidth, top + height, red);
                } else {
                    graphics.fill(getX() + 1, top + 1, getX() + tabWidth - 1, top + height - 1, 0xE6B91C1C);
                }
            }

            String name = font.plainSubstrByWidth(getMessage().getString(), tabWidth - 6);
            graphics.drawString(font, name, getX() + (tabWidth - font.width(name)) / 2,
                    top + (height - 8) / 2, selected ? ScreenStyle.LABEL_ON_ACCENT : ScreenStyle.LABEL,
                    false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** 鍵と、それがつなぐ 2 行。 */
    private record Lock(Row upper, Row lower, LinkLock widget) {
    }

    /**
     * 隣り合う 2 行を連動させる鍵。閉じていれば差を保って一緒に動き、開いていれば別々に動く。
     *
     * <p>状態は画面を閉じても残る（ゲームを閉じると閉じた状態に戻る）。絵は持たず、四角で描く。</p>
     */
    private final class LinkLock extends AbstractWidget {

        private final String key;

        private LinkLock(int x, String key) {
            super(x, 0, LOCK_WIDTH, LOCK_HEIGHT, Component.translatable("screen.kurumamod.tuning.link"));
            this.key = key;
            updateTooltip();
        }

        private void updateTooltip() {
            setTooltip(Tooltip.create(Component.translatable(unlinked.contains(key)
                    ? "screen.kurumamod.tuning.link.open" : "screen.kurumamod.tuning.link.closed")));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (!unlinked.remove(key)) {
                unlinked.add(key);
            }
            updateTooltip();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean closed = !unlinked.contains(key);
            int color = !active ? ScreenStyle.SUB
                    : isHovered() ? ScreenStyle.LABEL
                    : closed ? ScreenStyle.HINT : ScreenStyle.ACCENT;
            int x = getX() + getWidth() / 2;
            int y = getY() + getHeight() / 2;
            // 2 行をつなぐ線
            graphics.fill(x, getY(), x + 1, y - 9, color);
            graphics.fill(x, y + 7, x + 1, getY() + getHeight(), color);
            // 本体と鍵穴
            graphics.fill(x - 4, y, x + 5, y + 6, color);
            graphics.fill(x, y + 2, x + 1, y + 4, ScreenStyle.LABEL_ON_ACCENT);
            // つる。開いているときは上へ抜けて、左の足が本体から離れる
            int top = closed ? y - 6 : y - 8;
            graphics.fill(x - 3, top, x + 4, top + 2, color);
            graphics.fill(x + 2, top, x + 4, y, color);
            graphics.fill(x - 3, top, x - 1, closed ? y : y - 4, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** 項目 1 つぶんの行。 */
    private static final class Row {

        private final TunableParameter parameter;
        private final ParameterSlider slider;
        private final Button reset;

        private int y;
        private boolean visible;
        private int nameWidth = LABEL_WIDTH - GAP;

        private Row(TunableParameter parameter, ParameterSlider slider, Button reset) {
            this.parameter = parameter;
            this.slider = slider;
            this.reset = reset;
        }

        void setVisible(boolean visible) {
            this.visible = visible;
            slider.visible = visible;
            slider.active = visible;
            slider.setY(y);
            reset.visible = visible;
            reset.setY(y);
        }

        /** スライダーのつまみの位置を今の設定値に合わせる。 */
        void syncSlider() {
            slider.syncFromTuning();
        }

        /** 表示中の行の状態を今の設定値に合わせる。変えてよくなければ押せなくする（助手席）。 */
        void refresh(boolean editable) {
            if (!visible) {
                return;
            }
            // レース中、車の素性として固定された項目は動かせない（バニラの押せないスライダーの見た目になる）
            com.jdmmc.kurumamod.tuning.Regulation restriction = RaceLobby.restriction();
            boolean fixed = restriction != null && restriction.fixes(parameter);
            slider.active = editable && !fixed;
            // 固定の項目でも、車種の値から変えたまま入った人は R で自分で戻せる（勝手には動かさない）
            reset.active = editable && !parameter.isDefault(CarTuning.spec(), CarTuning.base());
            // 減衰比のように他項目の影響を受ける補足表示があるので、毎フレーム作り直す
            slider.refreshLabel();
        }
    }

    /** {@link TunableParameter} 1 項目ぶんのスライダー。出すのは値と補足だけで、名前は左の列。 */
    private static class ParameterSlider extends AbstractSliderButton {

        private final TunableParameter parameter;
        /** 鍵でつながった相手。無ければ null。 */
        private ParameterSlider partner;
        private String linkKey;

        ParameterSlider(int x, int y, int width, int height, TunableParameter parameter) {
            super(x, y, width, height, Component.empty(), parameter.sliderPosition(CarTuning.spec()));
            this.parameter = parameter;
            updateMessage();
        }

        void link(ParameterSlider partner, String linkKey) {
            this.partner = partner;
            this.linkKey = linkKey;
        }

        private boolean linked() {
            return partner != null && !unlinked.contains(linkKey);
        }

        /** 鍵が閉じていれば、相手も既定値へ戻す。 */
        void resetPartner() {
            if (linked()) {
                CarTuning.resetParameter(partner.parameter);
                partner.syncFromTuning();
            }
        }

        void refreshLabel() {
            updateMessage();
        }

        /** つまみの位置を設定値から作り直す。リセットで呼ばれる。 */
        void syncFromTuning() {
            value = parameter.sliderPosition(CarTuning.spec());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                    parameter.valueKey(), translateArguments(parameter.labelArguments(displaySpec()))));
        }

        /**
         * 補足を計算する諸元。<b>調整画面の値に、いま乗っている車の部品（ターボ・タイヤ・空力）を載せたもの。</b>
         *
         * <p>{@code CarTuning.spec()} は乗ったときに取り込んだ写しなので、乗った後に換装した部品が
         * 入っていない。ダウンフォースの補足に「うち部品」を出すには、今の部品が要る。</p>
         */
        private static com.jdmmc.kurumamod.physics.CarSpec displaySpec() {
            com.jdmmc.kurumamod.physics.CarSpec tuned = CarTuning.spec();
            CarEntity car = CarClientDriver.ridingCar(net.minecraft.client.Minecraft.getInstance());
            if (car == null) {
                return tuned;
            }
            com.jdmmc.kurumamod.physics.CarSpec fitted = car.getSpec();
            return tuned.toBuilder().turbo(fitted.turbo()).tire(fitted.tire()).partAero(fitted.partAero()).build();
        }

        /** 補足のうち {@link TunableParameter.Text} を翻訳つきの部品に直す。入れ子も辿る。 */
        private static Object[] translateArguments(Object[] args) {
            Object[] out = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                out[i] = args[i] instanceof TunableParameter.Text text
                        ? Component.translatable(text.key(), translateArguments(text.args()))
                        : args[i];
            }
            return out;
        }

        /** つまみを {@code position} に置いたときの諸元。鍵が閉じていれば相手も同じだけ動かす。 */
        private com.jdmmc.kurumamod.physics.CarSpec candidateAt(double position) {
            com.jdmmc.kurumamod.physics.CarSpec spec = CarTuning.spec();
            com.jdmmc.kurumamod.physics.CarSpec.Builder builder = spec.toBuilder();
            if (linked()) {
                double own = parameter.sliderPosition(spec);
                double other = partner.parameter.sliderPosition(spec);
                double delta = Math.max(-other, Math.min(1.0 - other, position - own));
                position = own + delta;
                partner.parameter.applyDisplay(builder, partner.parameter.displayFromSlider(other + delta));
            }
            parameter.applyDisplay(builder, parameter.displayFromSlider(position));
            return builder.build();
        }

        /** つまみを {@code position} に置いてよいか（レース中の決まり）。 */
        private boolean allowedAt(double position) {
            outsideRanges();
            for (double[] end : blockedEnds) {
                if (position >= end[0] - 1e-9 && position <= end[1] + 1e-9) {
                    return false;
                }
            }
            return RaceLobby.allowsTuning(CarTuning.spec(), candidateAt(position));
        }

        /** 決まりの外になる範囲（つまみの位置 0〜1 の区間の並び）。縛られていなければ空。 */
        private List<double[]> outside = List.of();
        /**
         * 両端に細く残った空きを塞いだぶん。<b>つまみもそこへは置かせない</b>（{@link #allowedAt}）。
         *
         * <p>最大トルク回転数をレブより上に置くと回しきれずに出力がわずかに下がる、というように、
         * 端の近くで一度外に出た値がまた中へ戻ることがある。計算としては合っているが、使う意味の無い値が
         * 斜線の先に島のように残って紛らわしいので、幅が {@link #END_GAP} 未満なら外として扱う。</p>
         */
        private List<double[]> blockedEnds = List.of();
        /** 両端の空きを塞ぐ幅（スライダーの幅に対する割合）。 */
        private static final double END_GAP = 0.05;
        /** いまのつまみの位置が決まりの外か。項目の名前を赤くする。 */
        private boolean violating;
        private int rangeRevision = Integer.MIN_VALUE;
        private Object rangeRule;

        /**
         * 決まりの外になる範囲。値が変わったときだけ測り直す。
         *
         * <p><b>つまみの位置からではなく、実際の範囲で測る。</b>決まりが変わって今の値が外にあるときも、
         * 斜線は本当の境目まで塗る（つまみは斜線の中にいる状態になる）。<b>値は動かさない</b>——直すのは本人。</p>
         *
         * <p>見るのは<b>この項目を動かすと変わる決まりだけ</b>。ほかの項目のせいで外れている決まり
         * （ターボ禁止など）のために、この項目の全体が塞がって見えないようにする。</p>
         */
        List<double[]> outsideRanges() {
            com.jdmmc.kurumamod.tuning.Regulation rule = RaceLobby.restriction();
            int revision = CarTuning.revision() * 31 + (linked() ? 1 : 0);
            if (revision == rangeRevision && java.util.Objects.equals(rule, rangeRule)) {
                return outside;
            }
            rangeRevision = revision;
            rangeRule = rule;
            outside = new ArrayList<>();
            blockedEnds = new ArrayList<>();
            violating = false;
            if (rule == null) {
                return outside;
            }
            int samples = 48;
            double[][] excess = new double[samples + 1][];
            for (int i = 0; i <= samples; i++) {
                excess[i] = excessAt(rule, i / (double) samples);
            }
            // この項目で動く決まり
            boolean[] affected = new boolean[excess[0].length];
            for (int k = 0; k < affected.length; k++) {
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (double[] e : excess) {
                    min = Math.min(min, e[k]);
                    max = Math.max(max, e[k]);
                }
                affected[k] = max - min > 1e-6;
            }
            boolean previous = isBad(excess[0], affected);
            double start = previous ? 0.0 : Double.NaN;
            for (int i = 1; i <= samples; i++) {
                boolean bad = isBad(excess[i], affected);
                if (bad != previous) {
                    // 境目を詰める
                    double inside = (i - 1) / (double) samples;
                    double other = i / (double) samples;
                    for (int j = 0; j < 10; j++) {
                        double mid = (inside + other) / 2.0;
                        if (isBad(excessAt(rule, mid), affected) == previous) {
                            inside = mid;
                        } else {
                            other = mid;
                        }
                    }
                    if (bad) {
                        start = other;
                    } else {
                        outside.add(new double[]{start, inside});
                    }
                    previous = bad;
                }
            }
            if (previous) {
                outside.add(new double[]{start, 1.0});
            }
            // 両端に細く残った空きは塞ぐ
            if (!outside.isEmpty()) {
                double[] first = outside.get(0);
                if (first[0] > 0.0 && first[0] < END_GAP) {
                    blockedEnds.add(new double[]{0.0, first[0]});
                    first[0] = 0.0;
                }
                double[] last = outside.get(outside.size() - 1);
                if (last[1] < 1.0 && 1.0 - last[1] < END_GAP) {
                    blockedEnds.add(new double[]{last[1], 1.0});
                    last[1] = 1.0;
                }
            }
            violating = isBad(excessAt(rule, parameter.sliderPosition(CarTuning.spec())), affected);
            return outside;
        }

        /** いまのつまみの位置が決まりの外か（{@link #outsideRanges} を測ったあとの値）。 */
        boolean violating() {
            outsideRanges();
            return violating;
        }

        /** つまみを {@code position} に置いたときの、決まりの種類ごとの外れ量（外れていなければ 0）。 */
        private double[] excessAt(com.jdmmc.kurumamod.tuning.Regulation rule, double position) {
            double[] out = new double[com.jdmmc.kurumamod.tuning.Regulation.Kind.values().length];
            for (com.jdmmc.kurumamod.tuning.Regulation.Violation violation
                    : rule.check(RaceLobby.withParts(candidateAt(position)), CarTuning.base())) {
                out[violation.kind().ordinal()] = Math.max(1e-6, violation.excess());
            }
            return out;
        }

        private static boolean isBad(double[] excess, boolean[] affected) {
            for (int k = 0; k < excess.length; k++) {
                if (affected[k] && excess[k] > 0.0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void applyValue() {
            com.jdmmc.kurumamod.physics.CarSpec spec = CarTuning.spec();
            double own = parameter.sliderPosition(spec);
            // レース中は決まりの外へ出る手前で止める（斜線の境目）
            if (!allowedAt(value)) {
                double inside = own;
                double outside = value;
                for (int i = 0; i < 24; i++) {
                    double mid = (inside + outside) / 2.0;
                    if (allowedAt(mid)) {
                        inside = mid;
                    } else {
                        outside = mid;
                    }
                }
                value = inside;
            }
            if (linked()) {
                // 同じだけ動かして差を保つ。相手が端に着いたら、ここで止める（差が崩れないように）
                double other = partner.parameter.sliderPosition(spec);
                double delta = Math.max(-other, Math.min(1.0 - other, value - own));
                value = own + delta;
                CarTuning.setPair(parameter, parameter.displayFromSlider(value),
                        partner.parameter, partner.parameter.displayFromSlider(other + delta));
                partner.syncFromTuning();
                return;
            }
            CarTuning.set(parameter, parameter.displayFromSlider(value));
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            syncToServer();
        }
    }
}
