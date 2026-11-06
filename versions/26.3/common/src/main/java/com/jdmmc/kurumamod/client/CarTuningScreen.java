package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
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
                        button -> minecraft.gui.setScreen(new CarPresetScreen(this)))
                .bounds(right - buttonWidth * 3 - GAP * 2, y, buttonWidth, 20)
                .build()));
        // 換装はここから開く。設定の置き場所を 2 か所にしないのと同じ理由で、
        // 車をいじる入り口はこの画面に集める
        headerButtons.add(addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.tuning.parts"),
                        button -> minecraft.gui.setScreen(new CarPartScreen(this)))
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
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
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (super.mouseDragged(event, dragX, dragY)) {
            return true;
        }
        CarChaseCamera.orbit(dragX, dragY);
        return true;
    }

    /**
     * 背景は自分で描く（または描かない）。<b>1.21 の {@code Screen#render} は先に背景（暗幕とぼかし）を
     * 描く</b>ので、ここで止めておかないと、この画面の上に描いたものが背景で塗りつぶされるうえ、
     * 走りながら見たい車がぼけて見えなくなる。ワールドの外（タイトル画面から開いたとき）だけは
     * 後ろに何も無いので、ふつうの背景を描く。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (minecraft == null || minecraft.level == null) {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        } else {
            // 26.3 では画面を開いているあいだの字幕を背景の段で描く（Screen#extractBackground）。止めると字幕が消える
            minecraft.gui.hud.extractDeferredSubtitles();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
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

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.text(font, title, TEXT_LEFT, TITLE_Y, 0xFFFFFFFF);
        graphics.text(font, Component.translatable(groups().get(selectedGroup).translationKey()),
                TEXT_LEFT, TITLE_Y + 12, ScreenStyle.ACCENT);
        graphics.text(font, Component.translatable(editable
                        ? "screen.kurumamod.tuning.hint" : "screen.kurumamod.tuning.hint_passenger"),
                TEXT_LEFT, TITLE_Y + 26, ScreenStyle.HINT);

        for (Row row : rows) {
            if (!row.visible) {
                continue;
            }
            String name = Component.translatable(row.parameter.translationKey()).getString();
            boolean changed = !row.parameter.isDefault(CarTuning.spec(), CarTuning.base());
            graphics.text(font, font.plainSubstrByWidth(name, row.nameWidth),
                    panelLeft, row.y + 5, changed ? ScreenStyle.ACCENT : COLOR_NAME, false);
        }

        renderScrollBar(graphics);
        renderTabHints(graphics);
        renderRowTooltip(graphics, mouseX, mouseY);
    }

    /**
     * 項目名にマウスを乗せたら解説を出す。
     *
     * <p><b>スライダーの上では出さない。</b>動かしている最中に値が隠れると、何に合わせたのか
     * 分からなくなる。名前は列の幅で切れていることがあるので、解説の頭に名前を全部出す。
     * 解説の無い項目（拡張 MOD が足したものなど）は何も出さない。</p>
     */
    private void renderRowTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (mouseX < panelLeft || mouseX >= panelLeft + LABEL_WIDTH) {
            return;
        }
        for (Row row : rows) {
            if (!row.visible || mouseY < row.y || mouseY >= row.y + ROW_HEIGHT - 2) {
                continue;
            }
            String key = row.parameter.tooltipKey();
            if (!net.minecraft.locale.Language.getInstance().has(key)) {
                return;
            }
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(Component.translatable(row.parameter.translationKey())
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
            lines.addAll(font.split(Component.translatable(key), TOOLTIP_WIDTH));
            graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
            return;
        }
    }

    private void renderScrollBar(GuiGraphicsExtractor graphics) {
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
    private void renderTabHints(GuiGraphicsExtractor graphics) {
        int centerY = tabTop + TAB_RAISE + TAB_HEIGHT / 2 - 4;
        if (tabScroll > 0) {
            graphics.centeredText(font, "‹", tabLeft - SIDE_MARGIN / 2, centerY, ScreenStyle.LABEL);
        }
        if (tabScroll + visibleTabs < tabs.size()) {
            int right = tabLeft + visibleTabs * (tabWidth + TAB_GAP) - TAB_GAP;
            graphics.centeredText(font, "›", right + SIDE_MARGIN / 2, centerY, ScreenStyle.LABEL);
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
    public boolean keyPressed(KeyEvent event) {
        if (ScreenStyle.isDrivingKey(event)) {
            return true;
        }
        return super.keyPressed(event);
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
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (index == selectedGroup) {
                return;
            }
            selectedGroup = index;
            scroll = 0;
            rebuildWidgets();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = index == selectedGroup;
            boolean hovered = isHovered() && !selected;
            // 下端は揃えたまま、選んでいるものだけ上へ伸ばす（列の底が波打たない）
            int top = selected ? getY() : getY() + TAB_RAISE;
            int height = getY() + TAB_HEIGHT + TAB_RAISE - top;
            // 高さと同じ帯を渡すとタイル全体が黄色くなる。名前しか入らないタブはこの方が読みやすい
            ScreenStyle.tileBox(graphics, getX(), top, tabWidth, height, selected, hovered, height);

            String name = font.plainSubstrByWidth(getMessage().getString(), tabWidth - 6);
            graphics.text(font, name, getX() + (tabWidth - font.width(name)) / 2,
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
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (!unlinked.remove(key)) {
                unlinked.add(key);
            }
            updateTooltip();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
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
            slider.active = editable;
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

        @Override
        protected void applyValue() {
            if (linked()) {
                // 同じだけ動かして差を保つ。相手が端に着いたら、ここで止める（差が崩れないように）
                com.jdmmc.kurumamod.physics.CarSpec spec = CarTuning.spec();
                double own = parameter.sliderPosition(spec);
                double other = partner.parameter.sliderPosition(spec);
                double delta = Math.max(-other, Math.min(1.0 - other, value - own));
                value = own + delta;
                CarTuning.set(partner.parameter, partner.parameter.displayFromSlider(other + delta));
                partner.syncFromTuning();
            }
            CarTuning.set(parameter, parameter.displayFromSlider(value));
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            syncToServer();
        }
    }
}
