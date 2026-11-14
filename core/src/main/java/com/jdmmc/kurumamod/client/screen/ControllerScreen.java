package com.jdmmc.kurumamod.client.screen;

import com.jdmmc.kurumamod.client.input.ControllerAction;
import com.jdmmc.kurumamod.client.input.ControllerBinding;
import com.jdmmc.kurumamod.client.input.ControllerInput;
import com.jdmmc.kurumamod.client.input.ForceFeedback;
import com.jdmmc.kurumamod.client.ui.ScreenStyle;
import com.jdmmc.kurumamod.client.ui.UiButton;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.client.ui.UiKey;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.client.ui.UiSlider;
import com.jdmmc.kurumamod.race.RaceText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * コントローラの設定画面（J）。版の {@code McUiScreen} に包んで開く。
 *
 * <p><b>左に区切り（運転・変速・FFB・機器）、右にその中身</b>（H のメニューと同じページ式）。幅は {@link UiFrame}。</p>
 *
 * <h2>決めごと</h2>
 * <ul>
 *   <li><b>状態は目で見せる。</b>軸は棒（ハンドルは中央から左右、ペダルは 0〜100%）、ボタンはランプ。
 *       反転はオンのとき黄色い枠。数字だけでは、ペダルが端まで届いているか・中央が合っているか分からない</li>
 *   <li><b>割り当ては「変更」を押して、割り当てたい軸かボタンを動かす</b>（番号を選ばせると、どれがどれか
 *       分からない）。つながっている機器を全部見ているので、動かした機器ごと割り当たる</li>
 *   <li><b>待ち受けは Esc で取り消し、10 秒で自動的に取り消す。</b>Esc で画面ごと閉じると、閉じたくない人まで閉じる</li>
 *   <li><b>外すのは行ごとの「×」。</b>以前の「待ち受け中にもう一度押すと外す」は、知らなければ見つけられなかった</li>
 *   <li><b>既定に戻すのは 2 段階</b>（1 回目で確認の表示に変わる）。完了の隣で、確認なしに全部消えていた</li>
 *   <li>H シフターは段をゲートの図で見せ、入っている段を光らせる。「順に割り当て」で 1 速から順に入れていけば
 *       まとめて割り当たる</li>
 *   <li>機器のページは、触った機器のランプが光る。名前だけでは、どれがどの機器か分からないことがある</li>
 * </ul>
 *
 * <p>ゲームは止める（{@link #isPauseScreen}）。割り当て中に車が動かないように。</p>
 */
public class ControllerScreen extends UiScreen {

    private static final int MARGIN = 12;
    private static final int MAX_CONTENT = 620;
    private static final int TITLE_Y = 8;
    private static final int TOP = 32;
    private static final int SIDEBAR_WIDTH = 84;
    private static final int SIDEBAR_GAP = 10;
    private static final int TAB_HEIGHT = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = 74;
    private static final int CHANGE_WIDTH = 48;
    private static final int SMALL = 20;
    private static final int VALUE_WIDTH = 46;
    private static final int GEAR_TILE_HEIGHT = 30;

    /** 待ち受け中に「動いた」と見なす軸の変化量。触っていない軸の揺れを拾わないだけの幅が要る。 */
    private static final double DETECT_THRESHOLD = 0.5;
    /** 待ち受けを自動的に取り消すまでの時間 [ms]。 */
    private static final long LISTEN_TIMEOUT_MS = 10_000L;
    /** 「既定に戻す」の確認を出しておく時間 [ms]。 */
    private static final long RESET_CONFIRM_MS = 4_000L;
    /** 機器のランプが光り続ける時間 [ms]。 */
    private static final long LAMP_MS = 300L;
    /** 機器のランプを点ける軸の変化量。 */
    private static final double LAMP_THRESHOLD = 0.03;

    private static final int COLOR_LISTEN_ROW = 0x40FFC83C;
    private static final int COLOR_BAR_BG = 0x80000000;
    private static final int COLOR_BAR = 0xFF7FD8FF;
    private static final int COLOR_WARN = 0xFFFFB060;
    private static final int COLOR_MISSING = 0xFFE08080;
    private static final int COLOR_VALUE = 0xFF7FD8FF;

    /** H シフターの段の並び（ゲートの図の左から）。 */
    private static final ControllerAction[] GEARS = {
            ControllerAction.GEAR_REVERSE, ControllerAction.GEAR_1, ControllerAction.GEAR_2, ControllerAction.GEAR_3,
            ControllerAction.GEAR_4, ControllerAction.GEAR_5, ControllerAction.GEAR_6, ControllerAction.GEAR_7,
    };
    /** 「順に割り当て」で聞く順。1 速から上がっていき、最後にバック（入れる場所が機種で違うので最後）。 */
    private static final ControllerAction[] SEQUENCE = {
            ControllerAction.GEAR_1, ControllerAction.GEAR_2, ControllerAction.GEAR_3, ControllerAction.GEAR_4,
            ControllerAction.GEAR_5, ControllerAction.GEAR_6, ControllerAction.GEAR_7, ControllerAction.GEAR_REVERSE,
    };

    private enum Page {
        DRIVING("driving"),
        SHIFT("shift"),
        /** 力を出せる版でだけ出す */
        FEEDBACK("feedback"),
        DEVICES("devices");

        private final String id;

        Page(String id) {
            this.id = id;
        }

        RaceText title() {
            return RaceText.translatable("screen.kurumamod.controller.page." + id);
        }
    }

    /** ページは開き直しても覚えておく（H シフターを割り当てている途中で画面を閉じることがある）。 */
    private static Page page = Page.DRIVING;

    // 並べ方（init で決める）
    private int contentLeft;
    private int contentRight;
    private int bodyLeft;
    private int bodyRight;
    private int footerY;
    private final List<Page> pages = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<int[]> gearTiles = new ArrayList<>();
    /** 行の下に出す説明（折り返して描く）の上端。 */
    private int noteTop;

    // 待ち受け
    private ControllerAction listening;
    private long listenStarted;
    /** 「順に割り当て」で残っている段。空なら 1 つだけの待ち受け。 */
    private final Deque<ControllerAction> sequence = new ArrayDeque<>();
    private int sequenceTotal;
    /** 待ち受けを始めた瞬間の機器ごとの軸とボタン。ここからの変化で判定する。 */
    private final Map<String, float[]> baselineAxes = new HashMap<>();
    private final Map<String, boolean[]> baselineButtons = new HashMap<>();

    // 既定に戻す（2 段階）
    private long resetArmedAt;

    // 機器のランプ
    private final Map<String, float[]> lastAxes = new HashMap<>();
    private final Map<String, Long> lampUntil = new HashMap<>();

    public ControllerScreen() {
        super(RaceText.translatable("screen.kurumamod.controller.title"));
    }

    // ------------------------------------------------------------------
    // 並べる
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        rows.clear();
        gearTiles.clear();
        pages.clear();
        for (Page each : Page.values()) {
            if (each != Page.FEEDBACK || ForceFeedback.available()) {
                pages.add(each);
            }
        }
        if (!pages.contains(page)) {
            page = Page.DRIVING;
        }

        int frameLeft = UiFrame.left(width) + MARGIN;
        int frameRight = UiFrame.right(width) - MARGIN;
        int total = Math.min(frameRight - frameLeft, MAX_CONTENT);
        contentLeft = (frameLeft + frameRight - total) / 2;
        contentRight = contentLeft + total;
        bodyLeft = contentLeft + SIDEBAR_WIDTH + SIDEBAR_GAP;
        bodyRight = contentRight;
        footerY = height - 28;

        switch (page) {
            case DRIVING -> initDriving();
            case SHIFT -> initShift();
            case FEEDBACK -> initFeedback();
            case DEVICES -> noteTop = TOP + Math.max(1, ControllerInput.pads().size()) * ROW_HEIGHT + 6;
        }
        initFooter();
    }

    private void initDriving() {
        int y = TOP;
        y = actionRow(ControllerAction.STEER, y);
        // ハンドルの扱いと回転角は、ハンドルの行のすぐ下に置く（離れていると何の設定か分からない）
        ControllerBinding steer = ControllerInput.binding(ControllerAction.STEER);
        int x = bodyLeft + LABEL_WIDTH;
        UiButton mode = addRenderableWidget(UiButton.builder(
                        RaceText.translatable(steer.wheel()
                                ? "screen.kurumamod.controller.steer_wheel"
                                : "screen.kurumamod.controller.steer_pad"),
                        button -> {
                            ControllerBinding current = ControllerInput.binding(ControllerAction.STEER);
                            ControllerInput.bind(ControllerAction.STEER, current.withWheel(!current.wheel()));
                            rebuildWidgets();
                        })
                .bounds(x, y + 2, Math.min(150, bodyRight - x), 20)
                .build());
        // ボタンに割り当てたハンドルは位置を持たないので、直結にできない
        mode.active = steer.type() == ControllerBinding.Type.AXIS;
        if (ControllerInput.isDirectSteer()) {
            int rx = x + 156;
            if (rx + 2 * SMALL + 50 <= bodyRight) {
                addRenderableWidget(UiButton.builder(RaceText.literal("-"), button -> stepRotation(-1))
                        .bounds(rx, y + 2, SMALL, 20).build());
                addRenderableWidget(UiButton.builder(RaceText.literal("+"), button -> stepRotation(1))
                        .bounds(rx + SMALL + 50, y + 2, SMALL, 20).build());
            }
        }
        y += ROW_HEIGHT;
        y = actionRow(ControllerAction.THROTTLE, y);
        y = actionRow(ControllerAction.BRAKE, y);
        y = actionRow(ControllerAction.CLUTCH, y);
        y = actionRow(ControllerAction.HANDBRAKE, y);
        noteTop = y + 6;
    }

    private void stepRotation(int direction) {
        ControllerInput.setWheelRotation(ControllerInput.wheelRotation() + direction * ControllerInput.WHEEL_ROTATION_STEP);
    }

    private void initShift() {
        int y = TOP;
        y = actionRow(ControllerAction.SHIFT_UP, y);
        y = actionRow(ControllerAction.SHIFT_DOWN, y);
        // H シフターのゲート
        y += 16;
        int gap = 4;
        int tile = Math.max(22, Math.min(40, (bodyRight - bodyLeft - gap * (GEARS.length - 1)) / GEARS.length));
        for (int i = 0; i < GEARS.length; i++) {
            gearTiles.add(new int[]{bodyLeft + i * (tile + gap), y, tile, GEAR_TILE_HEIGHT});
        }
        y += GEAR_TILE_HEIGHT + 6;
        int half = Math.min(150, (bodyRight - bodyLeft - 6) / 2);
        if (!sequence.isEmpty()) {
            addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.controller.sequence_stop"),
                            button -> stopListening())
                    .bounds(bodyLeft, y, half * 2 + 6, 20).build());
        } else {
            addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.controller.sequence"),
                            button -> startSequence())
                    .bounds(bodyLeft, y, half, 20).build());
            addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.controller.clear_gears"),
                            button -> {
                                for (ControllerAction gear : GEARS) {
                                    ControllerInput.bind(gear, ControllerBinding.UNBOUND);
                                }
                                rebuildWidgets();
                            })
                    .bounds(bodyLeft + half + 6, y, half, 20).build());
        }
        noteTop = y + 28;
    }

    private void initFeedback() {
        int y = TOP;
        int w = bodyRight - bodyLeft;
        addRenderableWidget(UiButton.builder(
                        RaceText.translatable(ControllerInput.feedbackEnabled()
                                ? "screen.kurumamod.controller.feedback.on"
                                : "screen.kurumamod.controller.feedback.off"),
                        button -> {
                            ControllerInput.setFeedbackEnabled(!ControllerInput.feedbackEnabled());
                            rebuildWidgets();
                        })
                .bounds(bodyLeft, y + 2, w, 20).build());
        y += ROW_HEIGHT;
        addRenderableWidget(new StrengthSlider(bodyLeft, y + 2, w));
        y += ROW_HEIGHT;
        addRenderableWidget(UiButton.builder(
                        RaceText.translatable(ControllerInput.feedbackInvert()
                                ? "screen.kurumamod.controller.feedback.inverted"
                                : "screen.kurumamod.controller.feedback.normal"),
                        button -> {
                            ControllerInput.setFeedbackInvert(!ControllerInput.feedbackInvert());
                            rebuildWidgets();
                        })
                .bounds(bodyLeft, y + 2, w, 20).build());
        y += ROW_HEIGHT;
        noteTop = y + 6;
    }

    private void initFooter() {
        int third = (contentRight - contentLeft - 12) / 3;
        addRenderableWidget(UiButton.builder(
                        RaceText.translatable(ControllerInput.isEnabled()
                                ? "screen.kurumamod.controller.on"
                                : "screen.kurumamod.controller.off"),
                        button -> {
                            ControllerInput.setEnabled(!ControllerInput.isEnabled());
                            rebuildWidgets();
                        })
                .bounds(contentLeft, footerY, third, 20).build());
        boolean armed = resetArmed();
        addRenderableWidget(UiButton.builder(
                        RaceText.translatable(armed
                                ? "screen.kurumamod.controller.reset_confirm"
                                : "screen.kurumamod.controller.reset"),
                        button -> {
                            if (resetArmed()) {
                                ControllerInput.resetBindings();
                                resetArmedAt = 0L;
                                stopListening();
                            } else {
                                resetArmedAt = System.currentTimeMillis();
                            }
                            rebuildWidgets();
                        })
                .bounds(contentLeft + third + 6, footerY, third, 20).build());
        addRenderableWidget(UiButton.builder(RaceText.translatable("gui.done"), button -> onClose())
                .bounds(contentRight - third, footerY, third, 20).build());
    }

    /** 操作 1 行（名前・機器と番号・棒かランプ・値・変更・反転・外す）。次の行の y を返す。 */
    private int actionRow(ControllerAction action, int y) {
        ControllerBinding binding = ControllerInput.binding(action);
        int clearX = bodyRight - SMALL;
        int invertX = clearX - SMALL - 2;
        int changeX = invertX - CHANGE_WIDTH - 2;
        boolean waiting = listening == action;
        addRenderableWidget(UiButton.builder(
                        RaceText.translatable(waiting
                                ? "screen.kurumamod.controller.cancel"
                                : "screen.kurumamod.controller.change"),
                        button -> {
                            if (listening == action) {
                                stopListening();
                            } else {
                                listenOne(action);
                            }
                        })
                .bounds(changeX, y + 2, CHANGE_WIDTH, 20).build());
        UiButton invert = addRenderableWidget(UiButton.builder(RaceText.literal("⇅"), button -> {
                    ControllerBinding current = ControllerInput.binding(action);
                    if (current.type() == ControllerBinding.Type.AXIS) {
                        ControllerInput.bind(action, current.inverted());
                    }
                })
                .bounds(invertX, y + 2, SMALL, 20).build());
        // ボタン割り当てには向きが無い
        invert.active = binding.type() == ControllerBinding.Type.AXIS;
        UiButton clear = addRenderableWidget(UiButton.builder(RaceText.literal("×"), button -> {
                    ControllerInput.bind(action, ControllerBinding.UNBOUND);
                    if (listening == action) {
                        stopListening();
                    }
                    rebuildWidgets();
                })
                .bounds(clearX, y + 2, SMALL, 20).build());
        clear.active = binding.isBound();
        rows.add(new Row(action, y, changeX, invert));
        return y + ROW_HEIGHT;
    }

    // ------------------------------------------------------------------
    // 待ち受け
    // ------------------------------------------------------------------

    private void startListening(ControllerAction action) {
        listening = action;
        listenStarted = System.currentTimeMillis();
        // 押しっぱなしのトリガーや、段に入ったままの H シフターを拾わないよう、今の状態を基準にする
        baselineAxes.clear();
        baselineButtons.clear();
        for (ControllerInput.Pad pad : ControllerInput.pads()) {
            baselineAxes.put(pad.id(), pad.axes().clone());
            baselineButtons.put(pad.id(), pad.buttons().clone());
        }
        rebuildWidgets();
    }

    /** 1 つだけ待ち受ける（行の「変更」・ゲートの段）。 */
    private void listenOne(ControllerAction action) {
        sequence.clear();
        sequenceTotal = 0;
        startListening(action);
    }

    private void startSequence() {
        sequence.clear();
        for (ControllerAction gear : SEQUENCE) {
            sequence.add(gear);
        }
        sequenceTotal = sequence.size();
        startListening(sequence.poll());
    }

    private void stopListening() {
        listening = null;
        sequence.clear();
        sequenceTotal = 0;
        rebuildWidgets();
    }

    /** 待ち受け中に、どれかの機器で動いた軸か押されたボタンを拾う。 */
    private void detect() {
        if (listening == null) {
            return;
        }
        if (System.currentTimeMillis() - listenStarted > LISTEN_TIMEOUT_MS) {
            stopListening();
            return;
        }
        for (ControllerInput.Pad pad : ControllerInput.pads()) {
            float[] baseAxes = baselineAxes.get(pad.id());
            boolean[] baseButtons = baselineButtons.get(pad.id());
            if (baseAxes == null || baseButtons == null) {
                // 待ち受けの途中でつないだ機器。今の状態を基準にして次から見る
                baselineAxes.put(pad.id(), pad.axes().clone());
                baselineButtons.put(pad.id(), pad.buttons().clone());
                continue;
            }
            boolean[] buttons = pad.buttons();
            for (int i = 0; i < buttons.length; i++) {
                boolean wasDown = i < baseButtons.length && baseButtons[i];
                if (buttons[i] && !wasDown) {
                    finish(ControllerBinding.button(pad, i));
                    return;
                }
                if (!buttons[i] && wasDown) {
                    // 押したまま始めたボタンは、離したら次に押したときに拾えるようにする
                    baseButtons[i] = false;
                }
            }
            float[] axes = pad.axes();
            for (int i = 0; i < axes.length && i < baseAxes.length; i++) {
                double moved = axes[i] - baseAxes[i];
                if (Math.abs(moved) < DETECT_THRESHOLD) {
                    continue;
                }
                // スティック・ハンドルは静止位置が 0、トリガー・ペダルは端（-1 か 1）。基準値から見分ける
                boolean fullAxis = Math.abs(baseAxes[i]) < 0.5;
                // ハンドルを割り当て直したら、ハンコンかパッドかは機器から推し直す（画面で直せる）
                finish(ControllerBinding.axis(pad, i, moved < 0, fullAxis));
                return;
            }
        }
    }

    private void finish(ControllerBinding binding) {
        ControllerAction action = listening;
        // 同じ軸・ボタンがほかの段に付いていたら外す（H シフターを順に割り当て直したとき、古い割り当てが残らないように）
        if (action.isGear() && binding.type() == ControllerBinding.Type.BUTTON) {
            for (ControllerAction gear : GEARS) {
                ControllerBinding other = ControllerInput.binding(gear);
                if (gear != action && other.type() == binding.type() && other.index() == binding.index()
                        && java.util.Objects.equals(other.device(), binding.device())) {
                    ControllerInput.bind(gear, ControllerBinding.UNBOUND);
                }
            }
        }
        ControllerInput.bind(action, binding);
        if (!sequence.isEmpty()) {
            // 次の段へ。今入れた段のボタンは押されたままなので、基準に取り込まれて拾われない
            startListening(sequence.poll());
            return;
        }
        listening = null;
        sequenceTotal = 0;
        rebuildWidgets();
    }

    private boolean resetArmed() {
        return resetArmedAt != 0L && System.currentTimeMillis() - resetArmedAt < RESET_CONFIRM_MS;
    }

    // ------------------------------------------------------------------
    // 入力
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(UiKey key) {
        // 待ち受け中の Esc は取り消し（画面は閉じない）
        if (key.escape() && listening != null) {
            stopListening();
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 左の区切り
        for (int i = 0; i < pages.size(); i++) {
            int top = TOP + i * (TAB_HEIGHT + 4);
            if (inside(mouseX, mouseY, contentLeft, top, SIDEBAR_WIDTH, TAB_HEIGHT)) {
                if (pages.get(i) != page) {
                    page = pages.get(i);
                    listening = null;
                    sequence.clear();
                    rebuildWidgets();
                }
                return true;
            }
        }
        // H シフターのゲート: 左クリックでその段を割り当て、右クリックで外す
        for (int i = 0; i < gearTiles.size(); i++) {
            int[] t = gearTiles.get(i);
            if (inside(mouseX, mouseY, t[0], t[1], t[2], t[3])) {
                sequence.clear();
                if (button == 1) {
                    ControllerInput.bind(GEARS[i], ControllerBinding.UNBOUND);
                    if (listening == GEARS[i]) {
                        listening = null;
                    }
                    rebuildWidgets();
                } else {
                    listenOne(GEARS[i]);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean inside(double x, double y, int left, int top, int w, int h) {
        return x >= left && x < left + w && y >= top && y < top + h;
    }

    // ------------------------------------------------------------------
    // 描く
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (inWorld()) {
            renderTransparentBackground(graphics);
        } else {
            super.renderBackground(graphics, mouseX, mouseY, partialTick);
        }
        graphics.drawCenteredString(font, title, (contentLeft + contentRight) / 2, TITLE_Y, ScreenStyle.LABEL);
        int count = ControllerInput.pads().size();
        RaceText status = count == 0
                ? RaceText.translatable("screen.kurumamod.controller.none")
                : RaceText.translatable("screen.kurumamod.controller.devices", count);
        graphics.drawString(font, status, contentRight - font.width(status), TITLE_Y,
                count == 0 ? ScreenStyle.SUB : COLOR_VALUE, false);

        // 左の区切り。選んでいるものは黄色（押せない灰色にすると、使えないように見える）
        for (int i = 0; i < pages.size(); i++) {
            int top = TOP + i * (TAB_HEIGHT + 4);
            boolean selected = pages.get(i) == page;
            boolean hovered = inside(mouseX, mouseY, contentLeft, top, SIDEBAR_WIDTH, TAB_HEIGHT);
            ScreenStyle.tileBox(graphics, contentLeft, top, SIDEBAR_WIDTH, TAB_HEIGHT, selected, hovered, TAB_HEIGHT);
            RaceText name = pages.get(i).title();
            graphics.drawString(font, name, contentLeft + 6, top + 6,
                    selected ? ScreenStyle.LABEL_ON_ACCENT : ScreenStyle.LABEL, false);
        }

        // 行の下敷き
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int color = listening == row.action ? COLOR_LISTEN_ROW : (i % 2 == 0 ? ScreenStyle.ROW : ScreenStyle.ROW_ALT);
            graphics.fill(bodyLeft, row.y, bodyRight, row.y + ROW_HEIGHT, color);
            if (row.action == ControllerAction.STEER) {
                // ハンドルの扱いの行も同じ下敷きにして、ハンドルの一部だと分かるように
                graphics.fill(bodyLeft, row.y + ROW_HEIGHT, bodyRight, row.y + 2 * ROW_HEIGHT, color);
            }
        }
    }

    @Override
    public void render(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        detect();
        if (resetArmedAt != 0L && !resetArmed()) {
            resetArmedAt = 0L;
            rebuildWidgets();
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        for (Row row : rows) {
            renderRow(graphics, row);
        }
        switch (page) {
            case DRIVING -> renderDrivingExtras(graphics);
            case SHIFT -> renderGate(graphics, mouseX, mouseY);
            case FEEDBACK -> note(graphics, RaceText.translatable(ControllerInput.isDirectSteer()
                    ? "screen.kurumamod.controller.hint_feedback"
                    : "screen.kurumamod.controller.hint_feedback_pad"), ScreenStyle.HINT);
            case DEVICES -> renderDevices(graphics);
        }
    }

    private void renderRow(UiGraphics graphics, Row row) {
        ControllerBinding binding = ControllerInput.binding(row.action);
        ControllerInput.Pad pad = ControllerInput.padOf(binding);
        int textY = row.y + 8;
        graphics.drawString(font, fit(RaceText.translatable(row.action.translationKey()).getString(), LABEL_WIDTH - 8),
                bodyLeft + 4, textY, ScreenStyle.LABEL, false);

        int valueRight = row.changeX - 6;
        int valueLeft = valueRight - VALUE_WIDTH;
        int barRight = valueLeft - 4;
        int chipLeft = bodyLeft + LABEL_WIDTH;
        int barWidth = Math.max(36, Math.min(120, (barRight - chipLeft) / 2));
        int barLeft = barRight - barWidth;

        // どの機器の、どの軸・ボタンか
        String chip;
        int chipColor = ScreenStyle.SUB;
        if (listening == row.action) {
            long left = Math.max(0L, LISTEN_TIMEOUT_MS - (System.currentTimeMillis() - listenStarted));
            chip = RaceText.translatable("screen.kurumamod.controller.listening", (left + 999L) / 1000L).getString();
            chipColor = ScreenStyle.ACCENT;
        } else if (!binding.isBound()) {
            chip = RaceText.translatable("screen.kurumamod.controller.unbound").getString();
        } else {
            String where = binding.type() == ControllerBinding.Type.AXIS
                    ? RaceText.translatable("screen.kurumamod.controller.axis", binding.index()).getString()
                    : RaceText.translatable("screen.kurumamod.controller.button", binding.index()).getString();
            String device = pad != null ? pad.name() : binding.deviceName();
            chip = device == null ? where : device + " · " + where;
            if (pad == null) {
                chip = RaceText.translatable("screen.kurumamod.controller.missing").getString() + "  " + chip;
                chipColor = COLOR_MISSING;
            } else {
                chipColor = ScreenStyle.LABEL;
            }
        }
        // 待ち受け中は棒の場所まで使って、案内を切らさずに出す
        int chipRight = listening == row.action ? valueRight : barLeft - 6;
        graphics.drawString(font, fit(chip, chipRight - chipLeft), chipLeft, textY, chipColor, false);
        if (listening == row.action) {
            return;
        }

        // 棒かランプと、値
        if (binding.isBound() && pad != null) {
            double value = ControllerInput.value(row.action);
            String text;
            int barTop = row.y + 8;
            int barBottom = row.y + 16;
            if (binding.type() == ControllerBinding.Type.AXIS) {
                graphics.fill(barLeft, barTop, barRight, barBottom, COLOR_BAR_BG);
                if (binding.fullAxis()) {
                    // 中央から左右へ（ハンドル・スティック）
                    int center = (barLeft + barRight) / 2;
                    int end = center + (int) Math.round(value * barWidth / 2.0);
                    graphics.fill(Math.min(center, end), barTop, Math.max(center, end) + 1, barBottom, COLOR_BAR);
                    graphics.fill(center, barTop - 2, center + 1, barBottom + 2, ScreenStyle.SUB);
                } else {
                    int end = barLeft + (int) Math.round(Math.abs(value) * barWidth);
                    graphics.fill(barLeft, barTop, end, barBottom, COLOR_BAR);
                }
                graphics.renderOutline(barLeft - 1, barTop - 1, barWidth + 2, barBottom - barTop + 2, ScreenStyle.BORDER);
                if (row.action == ControllerAction.STEER && ControllerInput.isDirectSteer()) {
                    text = String.format("%+.0f°", ControllerInput.handwheelDegrees());
                } else if (binding.fullAxis() && row.action == ControllerAction.STEER) {
                    text = String.format("%+.0f%%", value * 100.0);
                } else {
                    text = String.format("%.0f%%", Math.abs(value) * 100.0);
                }
            } else {
                // ボタン: ランプ
                boolean on = value > 0.5;
                int lamp = barTop - 1;
                graphics.fill(barLeft, lamp, barLeft + 10, lamp + 10, on ? ScreenStyle.ACCENT : COLOR_BAR_BG);
                graphics.renderOutline(barLeft, lamp, 10, 10, ScreenStyle.BORDER);
                text = on ? "ON" : "—";
            }
            graphics.drawString(font, text, valueRight - font.width(text), textY, COLOR_VALUE, false);
        }
        // 反転がオンなら黄色い枠
        if (binding.type() == ControllerBinding.Type.AXIS && binding.invert()) {
            UiButton invert = row.invert;
            graphics.renderOutline(invert.getX(), invert.getY(), invert.getWidth(), invert.getHeight(), ScreenStyle.ACCENT);
        }
    }

    private void renderDrivingExtras(UiGraphics graphics) {
        Row steerRow = rows.isEmpty() ? null : rows.get(0);
        if (steerRow != null && ControllerInput.isDirectSteer()) {
            int y = steerRow.y + ROW_HEIGHT + 8;
            int rx = bodyLeft + LABEL_WIDTH + 156;
            String rotation = ControllerInput.wheelRotation() + "°";
            if (rx + 2 * SMALL + 50 <= bodyRight) {
                int mid = rx + SMALL + 25;
                graphics.drawCenteredString(font, rotation, mid, y, ScreenStyle.LABEL);
                String lock = RaceText.translatable("screen.kurumamod.controller.lock",
                        (int) ControllerInput.CAR_LOCK_DEGREES * 2).getString();
                int lx = rx + 2 * SMALL + 56;
                graphics.drawString(font, fit(lock, bodyRight - lx), lx, y, ScreenStyle.SUB, false);
            }
        }
        // 説明。回転角がロックに届かないときは、そちらを先に知らせる
        double reach = ControllerInput.wheelRotation() / 2.0 / ControllerInput.CAR_LOCK_DEGREES;
        if (ControllerInput.isDirectSteer() && reach < 1.0) {
            note(graphics, RaceText.translatable("screen.kurumamod.controller.rotation_short",
                    ControllerInput.wheelRotation() / 2, Math.round(reach * 100.0),
                    (int) ControllerInput.CAR_LOCK_DEGREES * 2), COLOR_WARN);
        } else {
            note(graphics, RaceText.translatable(ControllerInput.isDirectSteer()
                    ? "screen.kurumamod.controller.hint_wheel"
                    : "screen.kurumamod.controller.hint"), ScreenStyle.HINT);
        }
    }

    private void renderGate(UiGraphics graphics, int mouseX, int mouseY) {
        if (gearTiles.isEmpty()) {
            return;
        }
        int[] first = gearTiles.get(0);
        graphics.drawString(font, RaceText.translatable("screen.kurumamod.controller.shifter"),
                bodyLeft, first[1] - 12, ScreenStyle.LABEL, false);
        int selected = ControllerInput.selectedGear();
        for (int i = 0; i < GEARS.length; i++) {
            int[] t = gearTiles.get(i);
            ControllerAction gear = GEARS[i];
            boolean bound = ControllerInput.binding(gear).isBound();
            boolean engaged = bound && selected == gear.gear();
            boolean hovered = inside(mouseX, mouseY, t[0], t[1], t[2], t[3]);
            ScreenStyle.tileBox(graphics, t[0], t[1], t[2], t[3], engaged, hovered, t[3]);
            if (listening == gear) {
                graphics.renderOutline(t[0] - 1, t[1] - 1, t[2] + 2, t[3] + 2, ScreenStyle.ACCENT);
            }
            String label = gear.gear() < 0 ? "R" : Integer.toString(gear.gear());
            int color = engaged ? ScreenStyle.LABEL_ON_ACCENT : bound ? ScreenStyle.LABEL : ScreenStyle.SUB;
            graphics.drawCenteredString(font, label, t[0] + t[2] / 2, t[1] + 6, color);
            String sub = bound ? "" : RaceText.translatable("screen.kurumamod.controller.gear_unbound").getString();
            if (!sub.isEmpty()) {
                graphics.drawCenteredString(font, fit(sub, t[2] - 2), t[0] + t[2] / 2, t[1] + 18, ScreenStyle.SUB);
            }
        }
        // 今の段
        RaceText now;
        if (listening != null && listening.isGear()) {
            String gearName = RaceText.translatable(listening.translationKey()).getString();
            now = sequenceTotal == 0
                    ? RaceText.translatable("screen.kurumamod.controller.put_gear", gearName)
                    : RaceText.translatable("screen.kurumamod.controller.put_gear_seq", gearName,
                    sequenceTotal - sequence.size(), sequenceTotal);
        } else if (selected == com.jdmmc.kurumamod.physics.CarInput.NO_GEAR_SELECT) {
            now = RaceText.translatable("screen.kurumamod.controller.shifter_none");
        } else if (selected == 0) {
            now = RaceText.translatable("screen.kurumamod.controller.shifter_neutral");
        } else {
            String gearName = RaceText.translatable(selected < 0 ? ControllerAction.GEAR_REVERSE.translationKey()
                    : ControllerAction.values()[ControllerAction.GEAR_1.ordinal() + selected - 1].translationKey())
                    .getString();
            now = RaceText.translatable("screen.kurumamod.controller.shifter_now", gearName);
        }
        int nowColor = listening != null && listening.isGear() ? ScreenStyle.ACCENT : COLOR_VALUE;
        RaceText heading = RaceText.translatable("screen.kurumamod.controller.shifter");
        int nowX = bodyLeft + font.width(heading) + 10;
        graphics.drawString(font, fit(now.getString(), bodyRight - nowX), nowX, first[1] - 12, nowColor, false);
        note(graphics, RaceText.translatable("screen.kurumamod.controller.hint_shifter"), ScreenStyle.HINT);
    }

    private void renderDevices(UiGraphics graphics) {
        List<ControllerInput.Pad> pads = ControllerInput.pads();
        long now = System.currentTimeMillis();
        if (pads.isEmpty()) {
            graphics.drawString(font, RaceText.translatable("screen.kurumamod.controller.none"),
                    bodyLeft + 4, TOP + 8, ScreenStyle.SUB, false);
        }
        for (int i = 0; i < pads.size(); i++) {
            ControllerInput.Pad pad = pads.get(i);
            int y = TOP + i * ROW_HEIGHT;
            graphics.fill(bodyLeft, y, bodyRight, y + ROW_HEIGHT, i % 2 == 0 ? ScreenStyle.ROW : ScreenStyle.ROW_ALT);
            // 触ったらランプを点ける
            if (moved(pad)) {
                lampUntil.put(pad.id(), now + LAMP_MS);
            }
            boolean lit = lampUntil.getOrDefault(pad.id(), 0L) > now;
            graphics.fill(bodyLeft + 6, y + 7, bodyLeft + 16, y + 17, lit ? ScreenStyle.ACCENT : COLOR_BAR_BG);
            graphics.renderOutline(bodyLeft + 6, y + 7, 10, 10, ScreenStyle.BORDER);
            String kind = RaceText.translatable(pad.gamepad()
                    ? "screen.kurumamod.controller.kind_gamepad"
                    : pad.wheel() ? "screen.kurumamod.controller.kind_wheel"
                    : "screen.kurumamod.controller.kind_raw").getString();
            String counts = RaceText.translatable("screen.kurumamod.controller.counts",
                    pad.axes().length, pad.buttons().length).getString();
            String right = kind + "  " + counts;
            int rightX = bodyRight - 6 - font.width(right);
            graphics.drawString(font, right, rightX, y + 8, ScreenStyle.SUB, false);
            graphics.drawString(font, fit(pad.name(), rightX - bodyLeft - 30), bodyLeft + 22, y + 8, ScreenStyle.LABEL, false);
        }
        note(graphics, RaceText.translatable("screen.kurumamod.controller.hint_devices"), ScreenStyle.HINT);
    }

    /** 前のフレームから軸が動いたか、ボタンが押されているか。 */
    private boolean moved(ControllerInput.Pad pad) {
        float[] axes = pad.axes();
        float[] last = lastAxes.put(pad.id(), axes.clone());
        for (boolean button : pad.buttons()) {
            if (button) {
                return true;
            }
        }
        if (last == null || last.length != axes.length) {
            return false;
        }
        for (int i = 0; i < axes.length; i++) {
            if (Math.abs(axes[i] - last[i]) > LAMP_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** 行の下の説明。幅に合わせて折り返す。 */
    private void note(UiGraphics graphics, RaceText text, int color) {
        int y = noteTop;
        for (RaceText line : font.split(text, bodyRight - bodyLeft)) {
            if (y > footerY - 12) {
                break;
            }
            graphics.drawString(font, line, bodyLeft, y, color, false);
            y += 11;
        }
    }

    private String fit(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    /** 割り当て画面では車が動かないよう、ゲームを止める。 */
    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private record Row(ControllerAction action, int y, int changeX, UiButton invert) {
    }

    /** フォースフィードバックの強さ（0〜100%）。 */
    private static final class StrengthSlider extends UiSlider {

        StrengthSlider(int x, int y, int width) {
            super(x, y, width, 20, RaceText.empty(), ControllerInput.feedbackStrength());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(RaceText.translatable("screen.kurumamod.controller.feedback.strength",
                    Math.round(value * 100.0)));
        }

        @Override
        protected void applyValue() {
            // 5% 刻み
            ControllerInput.setFeedbackStrength(Math.round(value * 20.0) / 20.0);
        }
    }
}
