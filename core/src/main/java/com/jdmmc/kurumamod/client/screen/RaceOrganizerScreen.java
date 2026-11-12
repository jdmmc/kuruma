package com.jdmmc.kurumamod.client.screen;

import com.jdmmc.kurumamod.config.SpeedUnit;
import com.jdmmc.kurumamod.client.ui.Slant;
import com.jdmmc.kurumamod.client.ui.UiButton;
import com.jdmmc.kurumamod.client.ui.UiEditBox;
import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.client.ui.UiKey;
import com.jdmmc.kurumamod.client.ui.UiPressable;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.client.ui.UiTooltip;
import com.jdmmc.kurumamod.client.ui.UiWidget;
import com.jdmmc.kurumamod.race.RaceClassBook;
import com.jdmmc.kurumamod.race.RaceCar;
import com.jdmmc.kurumamod.race.RaceEngine;
import com.jdmmc.kurumamod.race.RaceText;

import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.tuning.Regulation;
import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import com.jdmmc.kurumamod.client.race.RaceClient;
import com.jdmmc.kurumamod.client.race.RaceLobby;

/**
 * 主催者画面。<b>レースを開き、決まり（レギュレーション）を決め、参加者の判定を見てスタートする。</b>
 *
 * <p>レースが無いときは開く画面（コースと周回を選んで「レースを開く」）、受付中は決まりを変えながら
 * 参加者の判定を見る画面になる。<b>決まりを変えたらその場でサーバーへ送る</b>ので、参加者の判定も
 * 次の配信（0.5 秒以内）で変わる。</p>
 *
 * <p>Esc で閉じても、主催している間は画面の右の中ほどに主催中の表示が残り、K でここへ戻れる。</p>
 */
public final class RaceOrganizerScreen extends UiScreen {

    /**
     * 左の列の幅と、1 行の高さ。<b>画面が小さいとき（GUI スケールが大きいとき）は詰める</b>
     * （{@link #compact}）。GUI スケール Auto の 1080p では、画面は 480×270 しかない。
     */
    private int leftWidth = 240;
    private int rowHeight = 18;
    /** 画面が小さい。行を詰め、説明の 2 行目と下の説明を省く。 */
    private boolean compact;

    /** 画面で決めている決まり。受付中はサーバーの値に合わせ直す（自分が変えた直後を除く）。 */
    private Regulation draft = Regulation.DEFAULT;
    private int laps;
    private int courseIndex;
    /** 自分で変えてから、サーバーの値で上書きしないでおく残りティック。 */
    private int pendingTicks;

    private final List<UiWidget> regulationWidgets = new ArrayList<>();
    private UiButton courseButton;
    private UiButton lapsDown;
    private UiButton lapsUp;
    private UiButton openButton;
    private UiButton startButton;
    private UiButton stopButton;

    private int leftX;
    private int topY;
    /** 中身の枠の右端（{@link UiFrame}。フル HD の幅が上限で、広い画面では中央に寄せる）。 */
    private int frameRight;
    /** 上のタブの右端。見出しの札はこの右に置く。 */
    private int tabsRight;

    /** 素性を項目ごとに選ぶ一覧を開いているか（「個別」）。 */
    private boolean showTraits;
    private UiButton traitsButton;
    /** 素性の項目ごとの入れ切り（{@link Regulation#FIXED_PARAMETERS} の順）。 */
    private final List<UiPressable> traitToggles = new ArrayList<>();
    /** 参加者を外す「×」。行の並びが変わったら作り直す。 */
    private final List<UiButton> kickButtons = new ArrayList<>();
    private List<String> kickNames = List.of();

    /** クラスの一覧 1 行の高さ。 */
    private static final int CLASS_ROW = 22;
    /** 一覧の右端の、消すボタンの列の幅（「本当に？」が収まる幅＋隙間）。 */
    private static final int CLASS_DELETE_COLUMN = 58;
    /** 消すボタンの左の、並べ替えの「▲」「▼」の列の幅。 */
    private static final int CLASS_MOVE_COLUMN = 32;
    /** 上書きの警告の帯の高さ。 */
    private static final int WARNING_HEIGHT = 22;
    /** 「本当に？」を出しておく長さ [ティック]。 */
    private static final int ARM_TICKS = 60;

    /** クラスの一覧を開いているか。 */
    private boolean showClasses;
    /** 左の列の「クラス」の行。今の決まりがどのクラスかを出し、押すと右に一覧を開く。 */
    private UiButton classPicker;
    /** 一覧の行（クラス 1 つにつき 1 つ）と、消すボタン。クラスの並びか中身が変わったら作り直す。 */
    private final List<ClassRow> classRows = new ArrayList<>();
    private final List<UiButton> classDeletes = new ArrayList<>();
    /** 並べ替えの「▲」「▼」（クラスのタブだけ）。行と同じ順。 */
    private final List<UiButton> classUps = new ArrayList<>();
    private final List<UiButton> classDowns = new ArrayList<>();
    private List<String> classRowKeys = List.of();
    /** 一覧の先頭に出している行の番号（スクロール）。 */
    private int classScroll;
    /** 「本当に？」を出しているクラスの名前と、残りティック。 */
    private String armedDelete;
    private int armedTicks;
    /** 「もう一度で上書き」を出しているクラスの名前（記録が消える上書きだけ 2 回押させる）。 */
    private String armedSave;
    /** 最後に読み込んだ（保存した）クラスの名前。中身を変えたら「（変更あり）」と出すため。 */
    private String loadedClass;
    /** 名前を付けて保存。 */
    private UiEditBox className;
    private String classNameText = "";
    private UiButton classSave;
    /** クラスのタブ: 今の決まりを白紙に戻して、新しいクラスを作り始める。 */
    private UiButton newClassButton;
    /** レースのタブ: 今の決まりを持ってクラスのタブへ移る（保存はクラスのタブだけでする）。 */
    private UiButton toClassesButton;

    /**
     * <b>クラスのタブとして開いているか。</b>決まりの編集はレースと同じものを使い、コース・周回・参加者・
     * 進行のボタンを隠して、右にクラスの一覧と「名前を付けて保存」を常に出す。サーバーへは送らない
     * （保存したときだけ送る）。
     */
    private final boolean classMode;
    /** クラスのタブを開いたら、名前の欄にフォーカスを入れる（レースのタブから決まりを持ってきたとき）。 */
    private boolean focusName;

    public RaceOrganizerScreen() {
        this(false);
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby != null) {
            draft = Regulation.fromArray(lobby.regulation());
            laps = lobby.laps();
        }
    }

    private RaceOrganizerScreen(boolean classMode) {
        super(RaceText.translatable(classMode ? "screen.kurumamod.classes.title" : "screen.kurumamod.race_host.title"));
        this.classMode = classMode;
    }

    /**
     * クラスのタブ。{@code carried} はレースのタブから持ってきた決まり（null なら、最初のクラスを読み込んで開く）。
     * 持ってきたときは名前の欄にフォーカスを入れる。
     */
    static RaceOrganizerScreen classes(Regulation carried) {
        // 一覧と記録の件数（上書き・削除の警告に出す）を今のものにしてもらう
        RaceClient.get().sendToServer(RaceActionPacket.of(RaceActionPacket.Action.QUERY));
        RaceOrganizerScreen screen = new RaceOrganizerScreen(true);
        if (carried != null) {
            screen.draft = carried;
            screen.focusName = true;
        } else if (!RaceLobby.classes().isEmpty()) {
            RaceLobbyPacket.RaceClass first = RaceLobby.classes().get(0);
            screen.draft = Regulation.fromArray(first.regulation());
            screen.loadedClass = first.name();
            screen.classNameText = first.name();
        }
        return screen;
    }

    /** クラスの一覧を出しているか。クラスのタブでは常に出す（素性の一覧を開いている間を除く）。 */
    private boolean classesShown() {
        return classMode ? !showTraits : showClasses;
    }

    @Override
    protected void init() {
        regulationWidgets.clear();
        limitButtons.clear();
        leftX = UiFrame.left(width) + 12;
        frameRight = UiFrame.right(width) - 12;
        int frameWidth = frameRight - leftX;
        compact = height < 330 || frameWidth < 576;
        leftWidth = compact ? Math.min(200, frameWidth / 2 - 6) : 240;
        rowHeight = compact ? 15 : 18;
        topY = compact ? 26 : 30;
        int x = leftX;
        int y = topY;
        List<RaceTabs.Tab> tabs = RaceTabs.create(leftX, 6, classMode ? RaceTabs.Page.CLASSES : RaceTabs.Page.RACE);
        for (RaceTabs.Tab tab : tabs) {
            addRenderableWidget(tab);
        }
        tabsRight = RaceTabs.right(tabs);

        // コースと周回（レースを開く前だけ選べる。周回は受付中も変えられる）
        // トラックのボタンは周回の「−」の手前まで。固定の幅だと、狭い画面（左の列が詰まる）で周回に重なる
        courseButton = addRenderableWidget(new DropdownButton(x + 52, y, leftWidth - 52 - 76, 16,
                coursePick::isOpen, button -> toggleCoursePick()));
        lapsDown = addRenderableWidget(UiButton.builder(RaceText.literal("-"), button -> changeLaps(-1))
                .bounds(x + leftWidth - 72, y, 16, 16).build());
        lapsUp = addRenderableWidget(UiButton.builder(RaceText.literal("+"), button -> changeLaps(1))
                .bounds(x + leftWidth - 16, y, 16, 16).build());
        y += 22;

        // クラス（名前を付けて保存した決まり）。押すと右に一覧を開く
        classPicker = addRenderableWidget(new DropdownButton(x + 52, y, leftWidth - 52 - 64, 16,
                () -> showClasses, button -> toggleClasses()));
        toClassesButton = addRenderableWidget(UiButton.builder(
                        RaceText.translatable("screen.kurumamod.race_host.to_classes"),
                        button -> RaceClient.get().setScreen(classes(draft)))
                .bounds(x + leftWidth - 60, y, 60, 16)
                .tooltip(UiTooltip.create(RaceText.translatable("screen.kurumamod.race_host.to_classes_tip")))
                .build());
        newClassButton = addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.classes.new"),
                button -> newClass()).bounds(x + leftWidth - 60, topY, 60, 16).build());
        y += 22 + 14;

        // 車の素性の固定
        addToggle(x + 6, y + (compact ? 2 : 4), leftWidth - 60, "screen.kurumamod.race_host.tip.fixed",
                () -> draft.fixesAll(), () -> draft.fixedTraits() && !draft.fixesAll(),
                () -> draft = draft.withFixedTraits(!draft.fixesAll()));
        traitsButton = addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.race_host.traits"),
                button -> {
                    showTraits = !showTraits;
                    showClasses = false;
                }).bounds(x + leftWidth - 48, y + (compact ? 1 : 4), 44, compact ? 14 : 16).build());
        y += compact ? 18 : 26;

        // 数値の条件
        addLimit(x, y, "screen.kurumamod.race_host.tip.power", () -> draft.powerOn(), () -> draft = draft.withPower(!draft.powerOn(), draft.powerMax()),
                d -> draft = draft.withPower(draft.powerOn(), draft.powerMax() + d * 10));
        y += rowHeight;
        addLimit(x, y, "screen.kurumamod.race_host.tip.mass", () -> draft.massOn(), () -> draft = draft.withMass(!draft.massOn(), draft.massMin()),
                d -> draft = draft.withMass(draft.massOn(), draft.massMin() + d * 50));
        y += rowHeight;
        addLimit(x, y, "screen.kurumamod.race_host.tip.pwr", () -> draft.pwrOn(), () -> draft = draft.withPwr(!draft.pwrOn(), draft.pwrMin()),
                d -> draft = draft.withPwr(draft.pwrOn(), draft.pwrMin() + d * 0.5));
        y += rowHeight;
        addLimit(x, y, "screen.kurumamod.race_host.tip.grip", () -> draft.gripOn(), () -> draft = draft.withGrip(!draft.gripOn(), draft.gripMax()),
                d -> draft = draft.withGrip(draft.gripOn(), Math.round((draft.gripMax() + d * 0.05) * 100.0) / 100.0));
        y += rowHeight;
        addLimit(x, y, "screen.kurumamod.race_host.tip.downforce", () -> draft.downforceOn(),
                () -> draft = draft.withDownforce(!draft.downforceOn(), draft.downforceMax()),
                d -> draft = draft.withDownforce(draft.downforceOn(), draft.downforceMax() + d * 10));
        y += rowHeight;

        // 駆動方式
        int chipY = compact ? 1 : 2;
        addHelp(x + 6, y + 3, 64, "screen.kurumamod.race_host.tip.drive");
        String[] drives = {"FF", "FR", "4WD"};
        for (int i = 0; i < drives.length; i++) {
            int bit = 1 << i;
            addChip(x + 74 + i * 42, y + chipY, 38, RaceText.literal(drives[i]),
                    () -> (draft.drives() & bit) != 0, () -> draft = draft.withDrives(draft.drives() ^ bit));
        }
        y += rowHeight;
        // 過給機
        addHelp(x + 6, y + 3, 64, "screen.kurumamod.race_host.tip.turbo");
        addChip(x + 74, y + chipY, 60, RaceText.translatable("screen.kurumamod.race_host.turbo_yes"),
                () -> draft.turbo(), () -> draft = draft.withTurbo(true));
        addChip(x + 138, y + chipY, 60, RaceText.translatable("screen.kurumamod.race_host.turbo_no"),
                () -> !draft.turbo(), () -> draft = draft.withTurbo(false));
        y += rowHeight;
        // 運転補助（白は使ってよい）
        addHelp(x + 6, y + 3, 64, "screen.kurumamod.race_host.tip.assist");
        addChip(x + 74, y + chipY, 38, RaceText.literal("TCS"),
                () -> draft.tractionControl(), () -> draft = draft.withTractionControl(!draft.tractionControl()));
        addChip(x + 116, y + chipY, 38, RaceText.literal("ABS"),
                () -> draft.abs(), () -> draft = draft.withAbs(!draft.abs()));

        // 素性を項目ごとに（「個別」で開く一覧）
        traitToggles.clear();
        for (int i = 0; i < Regulation.FIXED_PARAMETERS.size(); i++) {
            int index = i;
            TunableParameter parameter = Tunables.byName(Regulation.FIXED_PARAMETERS.get(i));
            traitToggles.add(addToggle(0, 0, 100, parameter == null ? null : parameter.tooltipKey(),
                    () -> draft.fixesTrait(index), null,
                    () -> draft = draft.withTrait(index, !draft.fixesTrait(index))));
        }
        kickButtons.clear();
        kickNames = List.of();

        // クラスの一覧のいちばん下: 名前を付けて保存（OP とシングルプレイの本人だけ。出し分けは refreshWidgets）。
        // 一覧の行と消すボタンは、クラスの並びに合わせて refreshWidgets が作る
        classRows.clear();
        classDeletes.clear();
        classRowKeys = List.of();
        className = new UiEditBox(font, 0, 0, 90, 20, RaceText.translatable("screen.kurumamod.race_host.class_name"));
        className.setHint(RaceText.translatable("screen.kurumamod.race_host.class_name"));
        className.setMaxLength(RaceClassBook.MAX_NAME);
        className.setValue(classNameText);
        className.setResponder(text -> classNameText = text);
        addRenderableWidget(className);
        classSave = addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.race_host.class_save"),
                button -> saveClass()).bounds(0, 0, 56, 20).build());
        if (focusName && classMode) {
            focusName = false;
            setInitialFocus(className);
        }

        // 下の進行のボタン
        int by = height - 28;
        openButton = addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.race_host.open"),
                button -> openRace()).bounds(frameRight - 110, by, 110, 20).build());
        startButton = addRenderableWidget(UiButton.builder(RaceText.empty(),
                        button -> RaceClient.get().sendToServer(RaceActionPacket.of(RaceActionPacket.Action.START)))
                .bounds(frameRight - 110, by, 110, 20).build());
        stopButton = addRenderableWidget(UiButton.builder(RaceText.empty(),
                        button -> {
                            RaceClient.get().sendToServer(RaceActionPacket.of(RaceActionPacket.Action.STOP));
                            // 終えた（中止した）ら主催者画面は閉じる。開いたままだと、そのあとのリザルトが画面の裏に隠れる
                            onClose();
                        })
                .bounds(frameRight - 110 - 6 - 90, by, 90, 20).build());
        refreshWidgets();
    }

    private interface Step {
        void apply(int direction);
    }

    private void addToggle(int x, int y, BooleanSupplier on, Runnable flip) {
        addToggle(x, y, 12, null, on, null, flip);
    }

    /**
     * 入れ切りの四角。{@code w} を広げると行の名前まで押せる。{@code partial} は一部だけ入っている印
     * （素性の一括）。{@code tooltip} は名前にカーソルを乗せたときの説明。
     */
    private UiPressable addToggle(int x, int y, int w, String tooltip, BooleanSupplier on, BooleanSupplier partial,
                                     Runnable flip) {
        Toggle toggle = new Toggle(x, y, w, on, partial, () -> {
            flip.run();
            changed();
        });
        if (tooltip != null) {
            toggle.setTooltip(UiTooltip.create(RaceText.translatable(tooltip, SpeedUnit.reference(200.0))));
        }
        regulationWidgets.add(addRenderableWidget(toggle));
        return toggle;
    }

    /** 説明だけを出す場所（札の行の名前）。押しても何も起きない。主催者でなくても読める。 */
    private void addHelp(int x, int y, int w, String tooltip) {
        Toggle help = new Toggle(x, y, w, null, null, () -> {
        });
        help.setTooltip(UiTooltip.create(RaceText.translatable(tooltip)));
        addRenderableWidget(help);
    }

    /** 参加者の「×」を、いまの並びに合わせて作り直す。 */
    private void rebuildKickButtons(RaceLobbyPacket lobby) {
        List<String> names = new ArrayList<>();
        if (lobby != null) {
            for (int i = 0; i < lobby.entrants().size(); i++) {
                names.add(lobby.isSelf(i) ? "" : lobby.entrants().get(i).name());
            }
        }
        if (names.equals(kickNames)) {
            return;
        }
        kickNames = names;
        for (UiButton button : kickButtons) {
            removeWidget(button);
        }
        kickButtons.clear();
        for (String name : names) {
            UiButton button = UiButton.builder(RaceText.literal("x"), b -> RaceClient.get().sendToServer(
                            new RaceActionPacket(RaceActionPacket.Action.KICK, name, 0, new double[0])))
                    .bounds(0, 0, 14, 14)
                    .tooltip(UiTooltip.create(RaceText.translatable("screen.kurumamod.race_host.kick")))
                    .build();
            button.visible = false;
            kickButtons.add(addRenderableWidget(button));
        }
    }

    /** 「個別」の一覧の位置（参加者の表の上に重ねる）。{x, y, 幅, 1 列の幅}。 */
    private int[] traitsArea() {
        int x = leftX + leftWidth + 12;
        int w = frameRight - x;
        return new int[]{x, topY + 14, w, w / 2};
    }

    private void addLimit(int x, int y, String tooltip, BooleanSupplier on, Runnable flip, Step step) {
        // 四角だけでなく行の名前まで押せる。名前にカーソルを乗せると説明が出る
        int size = compact ? 13 : 16;
        addToggle(x + 6, y + (compact ? 1 : 3), leftWidth - 116, tooltip, on, null, flip);
        UiButton down = UiButton.builder(RaceText.literal("-"), button -> {
            step.apply(-1);
            changed();
        }).bounds(x + leftWidth - 108, y + 1, size, size).build();
        UiButton up = UiButton.builder(RaceText.literal("+"), button -> {
            step.apply(1);
            changed();
        }).bounds(x + leftWidth - 2 - size, y + 1, size, size).build();
        regulationWidgets.add(addRenderableWidget(down));
        regulationWidgets.add(addRenderableWidget(up));
        limitButtons.add(new LimitButtons(on, down, up));
    }

    private record LimitButtons(BooleanSupplier on, UiButton down, UiButton up) {
    }

    private final List<LimitButtons> limitButtons = new ArrayList<>();

    private void addChip(int x, int y, int w, RaceText label, BooleanSupplier on, Runnable press) {
        regulationWidgets.add(addRenderableWidget(new Chip(x, y, w, label, on, () -> {
            press.run();
            changed();
        })));
    }

    private void toggleClasses() {
        coursePick.close();
        showClasses = !showClasses;
        showTraits = false;
        armedDelete = null;
        refreshWidgets();
    }

    /** 保存できる人か（OP とシングルプレイの本人）。サーバーが判定して配ってくる。 */
    private boolean classAdmin() {
        RaceLobbyPacket lobby = RaceLobby.lobby();
        return lobby != null ? lobby.classAdmin() : RaceLobby.classAdmin();
    }

    /** 決まりを変えられる場面か（主催者で、受付中かレースを開く前）。 */
    private boolean editable() {
        if (classMode) {
            return classAdmin();
        }
        RaceLobbyPacket lobby = RaceLobby.lobby();
        RaceLobbyPacket.Phase phase = lobby == null ? RaceLobbyPacket.Phase.NONE : lobby.phase();
        boolean none = phase == RaceLobbyPacket.Phase.NONE;
        return (none || lobby.canManage()) && (none || phase == RaceLobbyPacket.Phase.OPEN);
    }

    /**
     * 一覧の行を押した。<b>押したらその場で読み込む</b>（「選ぶ」と「読み込む」を分けると、選んだだけで
     * 読み込み忘れる）。保存の名前の欄が空なら、そのクラスの名前を入れておく（直して上書きしやすいように）。
     */
    private void loadClass(RaceLobbyPacket.RaceClass raceClass) {
        if (!editable()) {
            return;
        }
        draft = Regulation.fromArray(raceClass.regulation());
        loadedClass = raceClass.name();
        // クラスのタブでは、読み込んだクラスの名前を入れる（直してそのまま上書きできるように）
        if (classMode || classNameText.trim().isEmpty()) {
            classNameText = raceClass.name();
            className.setValue(raceClass.name());
        }
        armedSave = null;
        changed();
        if (!classMode) {
            // レースのタブはドロップダウン。選んだら閉じる（クラスのタブは一覧が常に出ている）
            showClasses = false;
            refreshWidgets();
        }
    }

    /** 新しいクラスを作り始める。決まりを既定に戻し、名前の欄を空にしてフォーカスを入れる。 */
    private void newClass() {
        draft = Regulation.DEFAULT;
        loadedClass = null;
        armedSave = null;
        classNameText = "";
        className.setValue("");
        setFocused(className);
        refreshWidgets();
    }

    /** クラスを並びの中で 1 つ動かす（サーバーが保存して、全員へ配り直す）。 */
    private void moveClass(String name, int direction) {
        armedDelete = null;
        RaceClient.get().sendToServer(new RaceActionPacket(RaceActionPacket.Action.MOVE_CLASS, name, direction,
                new double[0]));
    }

    /** 消すボタンを押した。<b>1 回目は「本当に？」に変わるだけ</b>で、もう一度押すと消す。 */
    private void deleteClass(String name) {
        if (!name.equals(armedDelete)) {
            armedDelete = name;
            armedSave = null;
            armedTicks = ARM_TICKS;
            warnRecords(name);
            return;
        }
        armedDelete = null;
        RaceClient.get().sendToServer(new RaceActionPacket(RaceActionPacket.Action.DELETE_CLASS, name, 0, new double[0]));
    }

    /** 画面の決まりを、入れた名前でサーバーへ保存する。同じ名前があれば上書き（ボタンが「上書き」になる）。 */
    private void saveClass() {
        String name = classNameText.trim();
        if (name.isEmpty() || !classAdmin()) {
            return;
        }
        // 中身を変えて上書きすると、そのクラスの記録は消える。記録があるときだけ 2 回押させる
        RaceLobbyPacket.RaceClass existing = findClass(name);
        boolean changes = existing != null && !java.util.Arrays.equals(
                Regulation.fromArray(existing.regulation()).toArray(), draft.toArray());
        if (changes && existing.records() > 0 && !name.equals(armedSave)) {
            armedSave = name;
            armedDelete = null;
            armedTicks = ARM_TICKS;
            warnRecords(name);
            return;
        }
        armedSave = null;
        RaceClient.get().sendToServer(new RaceActionPacket(RaceActionPacket.Action.SAVE_CLASS, name, 0, draft.toArray()));
        loadedClass = name;
    }

    private static RaceLobbyPacket.RaceClass findClass(String name) {
        for (RaceLobbyPacket.RaceClass raceClass : RaceLobby.classes()) {
            if (raceClass.name().equals(name)) {
                return raceClass;
            }
        }
        return null;
    }

    /** 消す・上書きする前に、消えるタイムアタックの記録の件数を知らせる（アクションバー）。 */
    private void warnRecords(String name) {
        RaceLobbyPacket.RaceClass raceClass = findClass(name);
        if (raceClass != null && raceClass.records() > 0 && RaceClient.get().hasPlayer()) {
            RaceClient.get().overlayMessage(RaceText.translatable(
                    "screen.kurumamod.race_host.class_records_warning", name, raceClass.records()));
        }
    }

    private boolean classExists(String name) {
        for (RaceLobbyPacket.RaceClass raceClass : RaceLobby.classes()) {
            if (raceClass.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** クラスのタブの「編集中」の行。一致するクラス／「〇〇（変更あり）」／「新しいクラス」。 */
    private RaceText editingLabel() {
        RaceLobbyPacket.RaceClass loaded = loadedClass == null ? null : findClass(loadedClass);
        if (loaded == null) {
            return RaceText.translatable("screen.kurumamod.classes.unsaved");
        }
        return sameAsSaved(loaded) ? RaceText.literal(loaded.name())
                : RaceText.translatable("screen.kurumamod.race_host.class_modified", loaded.name());
    }

    /** 画面の決まりが、保存してあるそのクラスの中身と同じか。 */
    private boolean sameAsSaved(RaceLobbyPacket.RaceClass raceClass) {
        return java.util.Arrays.equals(Regulation.fromArray(raceClass.regulation()).toArray(), draft.toArray());
    }

    /**
     * 保存すると中身の違う既存のクラスを上書きすることになるなら、そのクラス。<b>上書きすると記録とゴーストが
     * 全員ぶん消える</b>ので、保存する前から一覧の下に警告を出す。
     */
    private RaceLobbyPacket.RaceClass overwriteTarget() {
        if (!classMode) {
            return null;
        }
        RaceLobbyPacket.RaceClass target = findClass(classNameText.trim());
        return target != null && !sameAsSaved(target) ? target : null;
    }

    /** 左の列の「クラス」の行に出す名前。一致するクラス／「〇〇（変更あり）」／「カスタム」。 */
    private RaceText currentClassLabel() {
        String current = RaceLobby.classNameOf(draft);
        if (current != null) {
            return RaceText.literal(current);
        }
        if (loadedClass != null && classExists(loadedClass)) {
            return RaceText.translatable("screen.kurumamod.race_host.class_modified", loadedClass);
        }
        return RaceText.translatable("screen.kurumamod.race_host.class_custom");
    }

    /** クラスの一覧の場所（参加者の表の上に重ねる）。{x, 上, 幅, 下}。 */
    private int[] classesArea() {
        int[] area = traitsArea();
        return new int[]{area[0], area[1], area[2], height - 36};
    }

    /** 一覧の下の段（保存の欄か、保存できない人への一言）の上端。 */
    private int classFooterTop() {
        // 名前を付けて保存する段はクラスのタブだけ（レースのタブは選んで読み込むだけ）。
        // 上書きの警告を出すときは、その帯のぶんも空ける
        return classesArea()[3] - (classMode ? 24 : 0) - (overwriteTarget() != null ? WARNING_HEIGHT : 0);
    }

    /** 一覧に一度に並べられる行の数。 */
    private int visibleClassRows() {
        int rowsTop = classesArea()[1] + 16;
        return Math.max(1, (classFooterTop() - 2 - rowsTop) / CLASS_ROW);
    }

    /** クラスの並びか中身が変わったら、行と消すボタンを作り直す。 */
    private void rebuildClassRows() {
        List<RaceLobbyPacket.RaceClass> classes = RaceLobby.classes();
        List<String> keys = new ArrayList<>();
        for (RaceLobbyPacket.RaceClass raceClass : classes) {
            keys.add(raceClass.name() + "\u0000" + java.util.Arrays.toString(raceClass.regulation()));
        }
        if (keys.equals(classRowKeys)) {
            return;
        }
        classRowKeys = keys;
        for (ClassRow row : classRows) {
            removeWidget(row);
        }
        for (UiButton button : classDeletes) {
            removeWidget(button);
        }
        for (UiButton button : classUps) {
            removeWidget(button);
        }
        for (UiButton button : classDowns) {
            removeWidget(button);
        }
        classRows.clear();
        classDeletes.clear();
        classUps.clear();
        classDowns.clear();
        for (RaceLobbyPacket.RaceClass raceClass : classes) {
            ClassRow row = new ClassRow(raceClass);
            row.visible = false;
            classRows.add(addRenderableWidget(row));
            String name = raceClass.name();
            UiButton delete = UiButton.builder(RaceText.literal("x"), b -> deleteClass(name))
                    .bounds(0, 0, 14, 14)
                    .tooltip(UiTooltip.create(RaceText.translatable("screen.kurumamod.race_host.class_delete")))
                    .build();
            delete.visible = false;
            classDeletes.add(addRenderableWidget(delete));
            // 並べ替え。並びはサーバーに保存し、全員の一覧とランキングの順になる
            UiButton up = UiButton.builder(RaceText.literal("▲"), b -> moveClass(name, -1))
                    .bounds(0, 0, 14, 14)
                    .tooltip(UiTooltip.create(RaceText.translatable("screen.kurumamod.classes.move_up")))
                    .build();
            UiButton down = UiButton.builder(RaceText.literal("▼"), b -> moveClass(name, 1))
                    .bounds(0, 0, 14, 14)
                    .tooltip(UiTooltip.create(RaceText.translatable("screen.kurumamod.classes.move_down")))
                    .build();
            up.visible = false;
            down.visible = false;
            classUps.add(addRenderableWidget(up));
            classDowns.add(addRenderableWidget(down));
        }
    }

    /** 決まりを変えた。受付中ならその場で送る。 */
    private void changed() {
        if (classMode) {
            // クラスのタブは手元で直すだけ。サーバーへ送るのは保存したとき
            return;
        }
        pendingTicks = 20;
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby != null && lobby.phase() == RaceLobbyPacket.Phase.OPEN && lobby.canManage()) {
            RaceClient.get().sendToServer(new RaceActionPacket(RaceActionPacket.Action.UPDATE, "", laps,
                    draft.toArray()));
        }
    }

    /** コースの一覧（右の列に重ねて開く。タイムアタックの画面と同じ）。 */
    private final PickList coursePick = new PickList();

    private void toggleCoursePick() {
        if (coursePick.isOpen()) {
            coursePick.close();
            refreshWidgets();
            return;
        }
        List<RaceLobbyPacket.CourseInfo> courses = RaceLobby.courses();
        List<PickList.Item> items = new ArrayList<>();
        for (RaceLobbyPacket.CourseInfo course : courses) {
            items.add(new PickList.Item(course.name(), RaceText.literal(course.name()),
                    RaceText.translatable("screen.kurumamod.race_host.course_sub", course.laps(), course.checkpoints()),
                    null, 0, false));
        }
        String current = courses.isEmpty() ? null : courses.get(Math.min(courseIndex, courses.size() - 1)).name();
        showClasses = false;
        showTraits = false;
        int x = leftX + leftWidth + 12;
        // クラスの一覧と同じ位置（参加者の見出しの下）に開く
        coursePick.open(RaceText.translatable("screen.kurumamod.race_host.pick_course"), x, classesArea()[1] - 2,
                frameRight - x, height - 36, items, current, key -> {
                    for (int i = 0; i < courses.size(); i++) {
                        if (courses.get(i).name().equals(key)) {
                            courseIndex = i;
                            laps = courses.get(i).laps();
                        }
                    }
                    refreshWidgets();
                });
        refreshWidgets();
    }

    @Override
    public void render(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // 一覧はボタンより上に
        coursePick.render(graphics, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (coursePick.isOpen()) {
            if (!coursePick.contains(mouseX, mouseY) && courseButton.isMouseOver(mouseX, mouseY)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
            coursePick.mouseClicked(mouseX, mouseY);
            refreshWidgets();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void changeLaps(int direction) {
        laps = Math.max(1, Math.min(99, laps + direction));
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby != null) {
            changed();
        }
    }

    private void openRace() {
        List<RaceLobbyPacket.CourseInfo> courses = RaceLobby.courses();
        if (courses.isEmpty()) {
            return;
        }
        RaceLobbyPacket.CourseInfo course = courses.get(Math.min(courseIndex, courses.size() - 1));
        RaceClient.get().sendToServer(new RaceActionPacket(RaceActionPacket.Action.OPEN, course.name(),
                laps > 0 ? laps : course.laps(), draft.toArray()));
    }

    @Override
    public void tick() {
        if (pendingTicks > 0) {
            pendingTicks--;
        }
        if (armedTicks > 0 && --armedTicks == 0) {
            armedDelete = null;
            armedSave = null;
        }
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby != null && pendingTicks == 0 && !classMode) {
            draft = Regulation.fromArray(lobby.regulation());
            laps = lobby.laps();
        }
        if (lobby == null) {
            List<RaceLobbyPacket.CourseInfo> courses = RaceLobby.courses();
            if (laps <= 0 && !courses.isEmpty()) {
                laps = courses.get(Math.min(courseIndex, courses.size() - 1)).laps();
            }
        }
        refreshWidgets();
    }

    /** 場面に合わせてボタンを出し分ける。 */
    private void refreshWidgets() {
        RaceLobbyPacket lobby = RaceLobby.lobby();
        RaceLobbyPacket.Phase phase = lobby == null ? RaceLobbyPacket.Phase.NONE : lobby.phase();
        boolean none = phase == RaceLobbyPacket.Phase.NONE;
        boolean open = phase == RaceLobbyPacket.Phase.OPEN;
        boolean manage = none || (lobby != null && lobby.canManage());
        boolean editable = classMode ? classAdmin() : manage && (none || open);

        List<RaceLobbyPacket.CourseInfo> courses = RaceLobby.courses();
        if (none) {
            String name = courses.isEmpty() ? RaceText.translatable("screen.kurumamod.race_host.no_course").getString()
                    : courses.get(Math.min(courseIndex, courses.size() - 1)).name();
            courseButton.setMessage(RaceText.literal(name));
        } else {
            courseButton.setMessage(RaceText.literal(lobby.course()));
        }
        courseButton.active = none && !courses.isEmpty();
        lapsDown.active = editable && laps > 1;
        lapsUp.active = editable && laps < 99;
        // クラスのタブにはコース・周回・クラスの選び口が無い（右の一覧から選ぶ）
        courseButton.visible = !classMode;
        lapsDown.visible = !classMode;
        lapsUp.visible = !classMode;
        classPicker.visible = !classMode;
        newClassButton.visible = classMode && classAdmin();
        // レースのタブから、今の決まりを持ってクラスのタブへ（保存できる人だけ）
        toClassesButton.visible = !classMode && classAdmin();
        classPicker.setWidth(toClassesButton.visible ? leftWidth - 52 - 64 : leftWidth - 52);

        for (UiWidget widget : regulationWidgets) {
            widget.active = editable;
        }
        for (LimitButtons buttons : limitButtons) {
            boolean on = buttons.on().getAsBoolean();
            buttons.down().active = editable && on;
            buttons.up().active = editable && on;
        }

        // 素性の一覧
        int[] area = traitsArea();
        for (int i = 0; i < traitToggles.size(); i++) {
            UiPressable toggle = traitToggles.get(i);
            toggle.visible = showTraits;
            toggle.setX(area[0] + 6 + (i % 2) * area[3]);
            toggle.setY(area[1] + 18 + (i / 2) * 16);
            toggle.setWidth(area[3] - 12);
        }
        traitsButton.active = editable || showTraits;
        traitsButton.setMessage(RaceText.translatable(showTraits
                ? "screen.kurumamod.race_host.traits_close" : "screen.kurumamod.race_host.traits"));

        // 参加者の「×」（主催者だけ。素性の一覧を開いている間は隠す）
        rebuildKickButtons(lobby);
        boolean kickable = !classMode && lobby != null && lobby.canManage() && !showTraits && !showClasses
                && !coursePick.isOpen()
                && phase != RaceLobbyPacket.Phase.RESULT;
        int rowTop = topY + 15 + 11;
        for (int i = 0; i < kickButtons.size(); i++) {
            UiButton button = kickButtons.get(i);
            int rowY = rowTop + i * 22;
            button.setX(frameRight - 16);
            button.setY(rowY + 4);
            button.visible = kickable && !kickNames.get(i).isEmpty() && rowY + 22 <= height - 36;
        }

        // クラス。左の列の行は、今の決まりがどのクラスかを出す（押すと右に一覧を開く）
        classPicker.setMessage(currentClassLabel());

        // クラスの一覧（右の表の上に重ねる）。行を押すと読み込み、右端の「x」を 2 回押すと消す
        rebuildClassRows();
        // 消すのはクラスのタブだけ（レースのタブは選ぶだけ）
        boolean admin = classMode && classAdmin();
        int[] ca = classesArea();
        int rowsTop = ca[1] + 16;
        int visibleRows = visibleClassRows();
        classScroll = Math.max(0, Math.min(classScroll, classRows.size() - visibleRows));
        for (int i = 0; i < classRows.size(); i++) {
            ClassRow row = classRows.get(i);
            UiButton delete = classDeletes.get(i);
            int slot = i - classScroll;
            boolean shown = classesShown() && slot >= 0 && slot < visibleRows;
            int rowY = rowsTop + slot * CLASS_ROW;
            row.visible = shown;
            row.active = editable;
            row.setX(ca[0] + 4);
            row.setY(rowY);
            // 消すボタンの列は行に含めない。行が下に敷かれていると、先に足した行がクリックを取ってしまい
            // 「x」を押しても読み込みになる（実際に消せなかった）
            row.setWidth(ca[2] - 8 - (admin ? CLASS_DELETE_COLUMN + CLASS_MOVE_COLUMN : 0));
            UiButton up = classUps.get(i);
            UiButton down = classDowns.get(i);
            int moveX = ca[0] + ca[2] - 8 - CLASS_DELETE_COLUMN - CLASS_MOVE_COLUMN + 2;
            up.visible = shown && admin;
            down.visible = shown && admin;
            up.active = i > 0;
            down.active = i < classRows.size() - 1;
            up.setX(moveX);
            down.setX(moveX + 15);
            up.setY(rowY + (CLASS_ROW - 2 - 14) / 2);
            down.setY(rowY + (CLASS_ROW - 2 - 14) / 2);
            boolean armed = row.raceClass.name().equals(armedDelete);
            int deleteWidth = armed ? 52 : 14;
            delete.visible = shown && admin;
            delete.setMessage(armed ? RaceText.translatable("screen.kurumamod.race_host.class_delete_confirm")
                    : RaceText.literal("x"));
            delete.setWidth(deleteWidth);
            delete.setX(ca[0] + ca[2] - 8 - deleteWidth);
            delete.setY(rowY + (CLASS_ROW - 2 - 14) / 2);
        }

        // 名前を付けて保存。保存できない人には欄ごと出さない（打てない入力欄を見せない）
        // 保存の欄は、上書きの警告の帯の下
        int footerTop = classFooterTop() + (overwriteTarget() != null ? WARNING_HEIGHT : 0);
        boolean saving = classesShown() && admin;
        className.visible = saving;
        className.setX(ca[0] + 6);
        className.setY(footerTop + 2);
        className.setWidth(ca[2] - 12 - 60);
        classSave.visible = saving;
        classSave.setX(ca[0] + ca[2] - 6 - 56);
        classSave.setY(footerTop + 2);
        String typed = classNameText.trim();
        classSave.setMessage(RaceText.translatable(typed.equals(armedSave)
                ? "screen.kurumamod.race_host.class_overwrite_confirm" : classExists(typed)
                ? "screen.kurumamod.race_host.class_overwrite" : "screen.kurumamod.race_host.class_save"));
        classSave.active = !typed.isEmpty();
        // 隠した入力欄がキーを吸い続けないように（K で閉じられなくなる）
        if (!saving && getFocused() == className) {
            setFocused(null);
        }

        openButton.visible = none && !classMode;
        openButton.active = !courses.isEmpty();
        startButton.visible = open && manage && !classMode;
        stopButton.visible = !none && manage && !classMode;
        int ok = 0;
        if (lobby != null) {
            for (RaceLobbyPacket.Entrant entrant : lobby.entrants()) {
                if (entrant.verdict() != RaceLobbyPacket.Verdict.VIOLATION) {
                    ok++;
                }
            }
        }
        startButton.setMessage(RaceText.translatable("screen.kurumamod.race_host.start", ok));
        startButton.active = ok > 0;
        stopButton.setMessage(RaceText.translatable(open
                ? "screen.kurumamod.race_host.cancel" : "screen.kurumamod.race_host.stop"));
        if (!open && !none) {
            // 走っている間は「終える」を右端へ
            stopButton.setX(frameRight - 90);
        } else {
            stopButton.setX(frameRight - 110 - 6 - 90);
        }
    }

    /**
     * 背景と、ボタンの下に敷くもの（表・文字）。<b>1.21 の {@code Screen#render} は先に背景を描いてから
     * ボタンを描く</b>ので、表をボタンより下に置くにはここで描く。
     */
    @Override
    public void renderBackground(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ワールドの中では半透明の暗幕（ぼかし無し）、外では普通の背景（版の画面が描き分ける）
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        renderPanels(graphics);
    }

    private void renderPanels(UiGraphics graphics) {
        UiFont font = this.font;
        RaceLobbyPacket lobby = RaceLobby.lobby();
        RaceLobbyPacket.Phase phase = lobby == null ? RaceLobbyPacket.Phase.NONE : lobby.phase();

        // 見出し
        String tag = classMode ? "CLASSES" : switch (phase) {
            case NONE -> "NO RACE";
            case OPEN -> "ENTRY OPEN";
            case COUNTDOWN, RUNNING -> "LIVE";
            case RESULT -> "RESULT";
        };
        int tagX = tabsRight + 10;
        int tagWidth = font.width(tag) + 12;
        Slant.fill(graphics, tagX, 8, tagWidth, 14, phase == RaceLobbyPacket.Phase.NONE || classMode
                ? 0xFF3A3A3A : RaceLobby.ACCENT);
        graphics.drawString(font, tag, tagX + 6, 11, RaceLobby.WHITE, false);
        if (lobby != null && !classMode) {
            RaceText course = RaceText.translatable("race.kurumamod.hud_course", lobby.course(), lobby.laps());
            int room = frameRight - (tagX + tagWidth) - 14;
            int shown = Math.min(font.width(course), room);
            Slant.fill(graphics, tagX + tagWidth, 8, shown + 14, 14, RaceLobby.PANEL);
            Slant.fit(graphics, font, course, tagX + tagWidth + 7, 11, room, RaceLobby.WHITE);
        }

        renderLeft(graphics, font);
        if (!classMode) {
            renderEntrants(graphics, font, lobby);
        }
        if (showTraits) {
            renderTraits(graphics, font);
        }
        if (classesShown()) {
            renderClasses(graphics, font);
        }

        // 下の説明
        RaceText note = noteFor(lobby);
        if (!compact) {
            Slant.fit(graphics, font, note, leftX, height - 42, frameRight - leftX, RaceLobby.GREY);
        }
    }

    private RaceText noteFor(RaceLobbyPacket lobby) {
        if (classMode) {
            return RaceText.translatable("screen.kurumamod.classes.note");
        }
        if (lobby == null) {
            return RaceLobby.courses().isEmpty()
                    ? RaceText.translatable("screen.kurumamod.race_host.note_no_course")
                    : RaceText.translatable("screen.kurumamod.race_host.note_open");
        }
        if (lobby.phase() == RaceLobbyPacket.Phase.OPEN) {
            int ng = 0;
            for (RaceLobbyPacket.Entrant entrant : lobby.entrants()) {
                if (entrant.verdict() == RaceLobbyPacket.Verdict.VIOLATION) {
                    ng++;
                }
            }
            return ng > 0 ? RaceText.translatable("screen.kurumamod.race_host.note_violators", ng)
                    : RaceText.translatable("screen.kurumamod.race_host.note_ready");
        }
        return RaceText.translatable("screen.kurumamod.race_host.note_running");
    }

    private void row(UiGraphics graphics, int y, int height, boolean alt) {
        graphics.fill(leftX, y, leftX + leftWidth, y + height, alt ? RaceLobby.PANEL_LIGHT : RaceLobby.PANEL);
    }

    private void renderLeft(UiGraphics graphics, UiFont font) {
        int x = leftX;
        int y = topY;
        if (classMode) {
            // クラスのタブ: いま直している決まりがどのクラスか（一致・変更あり・新規）
            row(graphics, y - 2, 20, false);
            if (loadedClass != null && findClass(loadedClass) != null) {
                graphics.fill(x, y - 2, x + 2, y + 18, RaceLobby.ACCENT);
            }
            graphics.drawString(font, RaceText.translatable("screen.kurumamod.classes.editing"), x + 6, y + 4,
                    RaceLobby.GREY, false);
            int labelX = x + 12 + font.width(RaceText.translatable("screen.kurumamod.classes.editing"));
            int room = leftWidth - (labelX - x) - (newClassButton.visible ? 66 : 6);
            Slant.fit(graphics, font, editingLabel(), labelX, y + 4, room, RaceLobby.WHITE);
            y += 22;
            row(graphics, y - 2, 20, true);
            Slant.fit(graphics, font, RaceLobby.summary(draft), x + 6, y + 4, leftWidth - 12, RaceLobby.GREY);
            y += 22;
        } else {
            row(graphics, y - 2, 20, false);
            graphics.drawString(font, RaceText.translatable("screen.kurumamod.race_host.course"), x + 6, y + 4,
                    RaceLobby.WHITE, false);
            String lapText = RaceText.translatable("screen.kurumamod.race_host.laps", laps).getString();
            graphics.drawString(font, lapText, x + leftWidth - 36 - font.width(lapText) / 2, y + 4, RaceLobby.WHITE, false);
            y += 22;

            // クラス。クラスに一致しているときは左に赤い印
            row(graphics, y - 2, 20, false);
            if (RaceLobby.classNameOf(draft) != null) {
                graphics.fill(x, y - 2, x + 2, y + 18, RaceLobby.ACCENT);
            }
            graphics.drawString(font, RaceText.translatable("screen.kurumamod.race_host.class"), x + 6, y + 4,
                    RaceLobby.WHITE, false);
            y += 22;
        }

        Slant.fill(graphics, x, y, leftWidth, 12, RaceLobby.INK);
        graphics.drawString(font, "REGULATION", x + 7, y + 2, RaceLobby.WHITE, false);
        RaceText hint = RaceText.translatable("screen.kurumamod.race_host.off_hint");
        graphics.drawString(font, hint, x + leftWidth - 7 - font.width(hint), y + 2, RaceLobby.GREY, false);
        y += 14;

        // 素性の固定（既定でオン）
        int fixedHeight = compact ? 16 : 24;
        row(graphics, y, fixedHeight, false);
        if (draft.fixedTraits()) {
            graphics.fill(x, y, x + 2, y + fixedHeight, RaceLobby.ACCENT);
        }
        Slant.fit(graphics, font, draft.fixesAll() || !draft.fixedTraits()
                        ? RaceText.translatable("screen.kurumamod.race_host.fixed")
                        : RaceText.translatable("screen.kurumamod.race_host.fixed_partial",
                        draft.fixedCount(), Regulation.FIXED_PARAMETERS.size()),
                x + 24, y + (compact ? 4 : 3), leftWidth - 80, RaceLobby.WHITE);
        if (!compact) {
            Slant.fit(graphics, font, RaceText.translatable("screen.kurumamod.race_host.fixed_hint"),
                    x + 24, y + 13, leftWidth - 80, RaceLobby.GREY);
        }
        y += compact ? 18 : 26;

        String[][] limits = {
                {"screen.kurumamod.race_host.power", String.format(Locale.ROOT, "%.0f PS", draft.powerMax())},
                {"screen.kurumamod.race_host.mass", String.format(Locale.ROOT, "%,.0f kg", draft.massMin())},
                {"screen.kurumamod.race_host.pwr", String.format(Locale.ROOT, "%.1f kg/PS", draft.pwrMin())},
                {"screen.kurumamod.race_host.grip", String.format(Locale.ROOT, "μ %.2f", draft.gripMax())},
                {"screen.kurumamod.race_host.downforce", String.format(Locale.ROOT, "%.0f kg", draft.downforceMax())}};
        boolean[] on = {draft.powerOn(), draft.massOn(), draft.pwrOn(), draft.gripOn(), draft.downforceOn()};
        for (int i = 0; i < limits.length; i++) {
            row(graphics, y, rowHeight - 1, (i & 1) == 0);
            int color = on[i] ? RaceLobby.WHITE : 0xFF8A8A8A;
            int textY = y + (compact ? 3 : 5);
            // 余分な引数は使わない文言では無視される。ダウンフォースの行だけ基準の速さを差し込む
            Slant.fit(graphics, font, RaceText.translatable(limits[i][0], SpeedUnit.reference(200.0)), x + 24, textY,
                    leftWidth - 136, color);
            String value = limits[i][1];
            int valueRoom = compact ? 72 : 68;
            int valueCenter = x + leftWidth - 2 - (compact ? 13 : 16) - (100 - (compact ? 13 : 16)) / 2 + 4;
            Slant.fit(graphics, font, value, valueCenter - Math.min(font.width(value), valueRoom) / 2, textY,
                    valueRoom, color);
            y += rowHeight;
        }
        String[] labels = {"screen.kurumamod.race_host.drive", "screen.kurumamod.race_host.turbo",
                "screen.kurumamod.race_host.assist"};
        for (int i = 0; i < labels.length; i++) {
            row(graphics, y, rowHeight - 1, ((i + 1) & 1) == 0);
            Slant.fit(graphics, font, RaceText.translatable(labels[i]), x + 6, y + (compact ? 3 : 5), 64, RaceLobby.WHITE);
            y += rowHeight;
        }
        if (!compact) {
            Slant.fit(graphics, font, RaceText.translatable("screen.kurumamod.race_host.assist_hint"),
                    x + 6, y + 3, leftWidth - 12, RaceLobby.GREY);
        }
    }

    /** クラスの一覧の地と見出し、空のときの一言、下の段。行そのものは {@link ClassRow} が描く。 */
    private void renderClasses(UiGraphics graphics, UiFont font) {
        int[] ca = classesArea();
        int x = ca[0];
        int w = ca[2];
        graphics.fill(x, ca[1] - 2, x + w, ca[3], 0xF0111111);
        Slant.fill(graphics, x, ca[1] - 2, w, 14, RaceLobby.ACCENT);
        Slant.fit(graphics, font, RaceText.translatable("screen.kurumamod.race_host.classes_title"),
                x + 7, ca[1] + 1, w - 60, RaceLobby.WHITE);
        // 見出しの右に件数と、隠れている行があるときの印
        int total = classRows.size();
        int visible = visibleClassRows();
        String count = total > visible
                ? (classScroll + 1) + "-" + Math.min(total, classScroll + visible) + " / " + total
                : String.valueOf(total);
        graphics.drawString(font, count, x + w - 7 - font.width(count), ca[1] + 1, RaceLobby.WHITE, false);

        int rowsTop = ca[1] + 16;
        if (total == 0) {
            Slant.fit(graphics, font, RaceText.translatable("screen.kurumamod.race_host.class_none"),
                    x + 8, rowsTop + 6, w - 16, RaceLobby.GREY);
        }

        if (classMode) {
            int footerTop = classFooterTop();
            RaceLobbyPacket.RaceClass target = overwriteTarget();
            if (target != null) {
                // 上書きで消えるもの。記録が無ければ消えるものは無いと言う（色も落とす）
                boolean loses = target.records() > 0;
                graphics.fill(x + 4, footerTop + 1, x + w - 4, footerTop + WARNING_HEIGHT - 1,
                        loses ? RaceLobby.NG : 0xFF3A3A3A);
                RaceText line1 = RaceText.translatable(loses ? "screen.kurumamod.classes.overwrite_warning"
                        : "screen.kurumamod.classes.overwrite_empty", target.name(), target.records());
                Slant.fit(graphics, font, line1, x + 9, footerTop + 3, w - 18, RaceLobby.WHITE);
                if (loses) {
                    Slant.fit(graphics, font, RaceText.translatable("screen.kurumamod.classes.overwrite_warning_sub"),
                            x + 9, footerTop + 12, w - 18, RaceLobby.WHITE);
                }
                footerTop += WARNING_HEIGHT;
            }
            graphics.fill(x, footerTop - 1, x + w, footerTop, 0x33FFFFFF);
            if (!classAdmin()) {
                Slant.fit(graphics, font, RaceText.translatable("screen.kurumamod.race_host.class_admin_only"),
                        x + 8, footerTop + 3, w - 16, RaceLobby.GREY);
            }
        }
    }

    /** 「個別」: 固定する素性を項目ごとに選ぶ一覧。参加者の表の上に重ねる。 */
    private void renderTraits(UiGraphics graphics, UiFont font) {
        int[] area = traitsArea();
        int rows = (Regulation.FIXED_PARAMETERS.size() + 1) / 2;
        graphics.fill(area[0], area[1] - 2, area[0] + area[2], area[1] + 18 + rows * 16 + 4, 0xF0111111);
        Slant.fill(graphics, area[0], area[1] - 2, area[2], 14, RaceLobby.ACCENT);
        Slant.fit(graphics, font, RaceText.translatable("screen.kurumamod.race_host.traits_title",
                draft.fixedCount(), Regulation.FIXED_PARAMETERS.size()), area[0] + 7, area[1] + 1, area[2] - 14,
                RaceLobby.WHITE);
        for (int i = 0; i < Regulation.FIXED_PARAMETERS.size(); i++) {
            TunableParameter parameter = Tunables.byName(Regulation.FIXED_PARAMETERS.get(i));
            RaceText name = parameter == null ? RaceText.literal(Regulation.FIXED_PARAMETERS.get(i))
                    : RaceText.translatable(parameter.translationKey());
            int x = area[0] + 6 + (i % 2) * area[3];
            int y = area[1] + 18 + (i / 2) * 16;
            Slant.fit(graphics, font, name, x + 16, y + 2, area[3] - 30,
                    draft.fixesTrait(i) ? RaceLobby.WHITE : 0xFF9A9A9A);
        }
    }

    private void renderEntrants(UiGraphics graphics, UiFont font, RaceLobbyPacket lobby) {
        int x = leftX + leftWidth + 12;
        int right = frameRight;
        int w = right - x;
        int y = topY;
        List<RaceLobbyPacket.Entrant> entrants = lobby == null ? List.of() : lobby.entrants();
        int ok = 0;
        for (RaceLobbyPacket.Entrant entrant : entrants) {
            if (entrant.verdict() == RaceLobbyPacket.Verdict.OK) {
                ok++;
            }
        }
        int ng = 0;
        for (RaceLobbyPacket.Entrant entrant : entrants) {
            if (entrant.verdict() == RaceLobbyPacket.Verdict.VIOLATION) {
                ng++;
            }
        }
        Slant.fill(graphics, x, y - 2, w, 14, RaceLobby.INK);
        graphics.drawString(font, "ENTRANTS " + entrants.size(), x + 7, y + 1, RaceLobby.WHITE, false);
        RaceText okText = RaceText.translatable("screen.kurumamod.race_host.ok_count", ok);
        RaceText ngText = RaceText.translatable("screen.kurumamod.race_host.ng_count", ng);
        int ngX = right - 8 - font.width(ngText) - 6;
        graphics.fill(ngX - 3, y - 1, ngX + font.width(ngText) + 3, y + 10, ng > 0 ? RaceLobby.NG : 0x33FFFFFF);
        graphics.drawString(font, ngText, ngX, y + 1, ng > 0 ? RaceLobby.WHITE : RaceLobby.GREY, false);
        int okX = ngX - 10 - font.width(okText);
        graphics.fill(okX - 3, y - 1, okX + font.width(okText) + 3, y + 10, RaceLobby.OK);
        graphics.drawString(font, okText, okX, y + 1, RaceLobby.WHITE, false);
        y += 15;

        // 列。狭い画面では値の列を減らす（名前と判定は必ず残す）
        String[] heads = {"PS", "kg", "μ", "DF", ""};
        int[] widths = {38, 42, 32, 32, 0};
        int verdictWidth = w >= 300 ? 108 : 92;
        int valueColumns = w >= 380 ? 4 : w >= 300 ? 2 : w >= 220 ? 1 : 0;
        int valuesWidth = 0;
        for (int i = 0; i < valueColumns; i++) {
            valuesWidth += widths[i];
        }
        int nameWidth = w - valuesWidth - verdictWidth - 8;
        int cx = x + 6 + nameWidth;
        graphics.drawString(font, RaceText.translatable("screen.kurumamod.race_host.driver"), x + 6, y,
                RaceLobby.GREY, false);
        for (int i = 0; i < valueColumns; i++) {
            graphics.drawString(font, heads[i], cx + widths[i] - 4 - font.width(heads[i]), y, RaceLobby.GREY, false);
            cx += widths[i];
        }
        y += 11;

        if (entrants.isEmpty()) {
            graphics.fill(x, y, right, y + 20, RaceLobby.PANEL_LIGHT);
            graphics.drawString(font, RaceText.translatable(lobby == null
                    ? "screen.kurumamod.race_host.no_race" : "screen.kurumamod.race_host.no_entrants"),
                    x + 6, y + 6, RaceLobby.GREY, false);
            return;
        }
        int bottomLimit = height - 36;
        for (int i = 0; i < entrants.size() && y + 22 <= bottomLimit; i++) {
            RaceLobbyPacket.Entrant entrant = entrants.get(i);
            boolean good = entrant.verdict() == RaceLobbyPacket.Verdict.OK;
            graphics.fill(x, y, right, y + 21, (i & 1) == 0 ? RaceLobby.PANEL : RaceLobby.PANEL_LIGHT);
            graphics.fill(x, y, x + 2, y + 21, good ? RaceLobby.OK : entrant.verdict() == RaceLobbyPacket.Verdict.NO_CAR
                    ? 0xFF555555 : RaceLobby.NG);
            Slant.fit(graphics, font, entrant.name(), x + 6, y + 2, nameWidth, RaceLobby.WHITE);
            Slant.fit(graphics, font, RaceLobby.carName(entrant.carId()), x + 6, y + 11, nameWidth, RaceLobby.GREY);
            cx = x + 6 + nameWidth;
            Regulation.Kind[] kinds = {Regulation.Kind.POWER, Regulation.Kind.MASS, Regulation.Kind.GRIP,
                    Regulation.Kind.DOWNFORCE};
            int[] indices = {RaceLobbyPacket.POWER, RaceLobbyPacket.MASS, RaceLobbyPacket.GRIP, RaceLobbyPacket.DOWNFORCE};
            for (int c = 0; c < valueColumns; c++) {
                double value = entrant.value(indices[c]);
                String text = Double.isNaN(value) ? "-"
                        : kinds[c] == Regulation.Kind.GRIP ? String.format(Locale.ROOT, "%.2f", value)
                        : String.format(Locale.ROOT, "%,.0f", value);
                Regulation.Kind kind = kinds[c];
                boolean bad = entrant.violations().stream().anyMatch(v -> v.kind() == kind
                        || (kind == Regulation.Kind.POWER && v.kind() == Regulation.Kind.PWR));
                int tx = cx + widths[c] - 4 - font.width(text);
                if (bad) {
                    graphics.fill(tx - 2, y + 5, cx + widths[c] - 2, y + 16, RaceLobby.NG);
                }
                graphics.drawString(font, text, tx, y + 7, RaceLobby.WHITE, false);
                cx += widths[c];
            }
            // 判定と理由
            int vx = right - verdictWidth;
            // 札は下の理由の行と左端を揃える（右端で揃えると、OK と Breach で札の幅が違うぶん左右にずれた）
            RaceInfoScreen.verdictBadge(graphics, font, entrant,
                    vx + RaceInfoScreen.verdictBadgeWidth(font, entrant), y + 2);
            RaceText reason = good ? RaceText.translatable("screen.kurumamod.race_host.can_start")
                    : entrant.verdict() == RaceLobbyPacket.Verdict.NO_CAR
                    ? RaceText.translatable("race.kurumamod.no_car")
                    : RaceLobby.describeAll(entrant.violations());
            Slant.fit(graphics, font, reason, vx, y + 13, verdictWidth - 20, 0xFFD4D7DC);
            y += 22;
        }
    }

    @Override
    public boolean keyPressed(UiKey key) {
        // Esc は、開いている一覧を先に閉じる（画面ごと閉じると、選びかけたものを探し直すことになる）
        if (key.escape() && coursePick.isOpen()) {
            coursePick.close();
            refreshWidgets();
            return true;
        }
        if (key.escape() && ((showClasses && !classMode) || showTraits)) {
            showClasses = false;
            showTraits = false;
            setFocused(null);
            refreshWidgets();
            return true;
        }
        if (className != null && className.isFocused()) {
            if (key.enter()) {
                saveClass();
                return true;
            }
            return super.keyPressed(key);
        }
        if (key.openRace()) {
            onClose();
            return true;
        }
        if (key.driving()) {
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (coursePick.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (classesShown() && overClassList(mouseX, mouseY)) {
            classScroll -= (int) Math.signum(scrollY);
            refreshWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean overClassList(double mouseX, double mouseY) {
        int[] ca = classesArea();
        return mouseX >= ca[0] && mouseX < ca[0] + ca[2] && mouseY >= ca[1] && mouseY < classFooterTop();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * クラスの一覧の 1 行。名前と、決まりの要約（「250 PS 以下 · FR」）。<b>押すとその場で読み込む。</b>
     * 今の決まりと一致している行は、左に赤い印。
     */
    private final class ClassRow extends UiPressable {

        private final RaceLobbyPacket.RaceClass raceClass;
        private final RaceText summary;

        private ClassRow(RaceLobbyPacket.RaceClass raceClass) {
            super(0, 0, 100, CLASS_ROW - 2, RaceText.literal(raceClass.name()));
            this.raceClass = raceClass;
            this.summary = RaceLobby.summary(Regulation.fromArray(raceClass.regulation()));
        }

        @Override
        public void onPress() {
            loadClass(raceClass);
        }

        @Override
        public void renderWidget(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            // クラスのタブは「読み込んで直しているクラス」を光らせる（新規なら何も光らせない）。中身で比べると、
            // 既定の中身と同じクラスが新規のときにも光ってしまう
            boolean current = classMode ? raceClass.name().equals(loadedClass)
                    : raceClass.name().equals(RaceLobby.classNameOf(draft));
            int background = current ? 0x55E1261C : isHovered() && active ? 0x33FFFFFF : 0x18FFFFFF;
            graphics.fill(x, y, x + w, y + h, background);
            if (current) {
                graphics.fill(x, y, x + 2, y + h, RaceLobby.ACCENT);
            }
            UiFont font = RaceClient.get().font();
            int room = w - 12;
            Slant.fit(graphics, font, getMessage(), x + 6, y + 2, room, active ? RaceLobby.WHITE : 0xFF9A9A9A);
            Slant.fit(graphics, font, summary, x + 6, y + 11, room, RaceLobby.GREY);
        }

    }

    /** 入れ切りの四角。入っていれば赤く塗ってレ点を描く。 */
    private static final class Toggle extends UiPressable {

        private final BooleanSupplier on;
        private final Runnable press;

        /** 一部だけ入っている（素性の一括）。null なら使わない。 */
        private final BooleanSupplier partial;

        private Toggle(int x, int y, int w, BooleanSupplier on, BooleanSupplier partial, Runnable press) {
            super(x, y, w, 12, RaceText.empty());
            this.on = on;
            this.partial = partial;
            this.press = press;
        }

        @Override
        public void onPress() {
            press.run();
        }

        @Override
        public void renderWidget(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (on == null) {
                return;     // 説明だけの場所。何も描かない
            }
            boolean checked = on.getAsBoolean();
            int x = getX();
            int y = getY();
            int border = !active ? 0xFF555555 : checked ? RaceLobby.ACCENT : isHovered() ? 0xFFFFFFFF : 0xFF9A9A9A;
            graphics.fill(x, y, x + 12, y + 12, border);
            graphics.fill(x + 1, y + 1, x + 11, y + 11, checked ? RaceLobby.ACCENT : 0xFF111111);
            if (checked) {
                // レ点
                graphics.fill(x + 2, y + 6, x + 4, y + 8, RaceLobby.WHITE);
                graphics.fill(x + 4, y + 7, x + 6, y + 9, RaceLobby.WHITE);
                graphics.fill(x + 6, y + 5, x + 8, y + 7, RaceLobby.WHITE);
                graphics.fill(x + 8, y + 3, x + 10, y + 5, RaceLobby.WHITE);
            } else if (partial != null && partial.getAsBoolean()) {
                // 一部だけ入っている。横棒
                graphics.fill(x + 2, y + 2, x + 10, y + 10, RaceLobby.ACCENT);
                graphics.fill(x + 3, y + 5, x + 9, y + 7, RaceLobby.WHITE);
            }
        }

    }

    /** 札（駆動方式・過給機・補助）。選んでいるものは白い。 */
    private static final class Chip extends UiPressable {

        private final BooleanSupplier on;
        private final Runnable press;

        private Chip(int x, int y, int w, RaceText label, BooleanSupplier on, Runnable press) {
            super(x, y, w, 14, label);
            this.on = on;
            this.press = press;
        }

        @Override
        public void onPress() {
            press.run();
        }

        @Override
        public void renderWidget(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = on.getAsBoolean();
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            if (selected) {
                graphics.fill(x, y, x + w, y + h, active ? 0xFFFFFFFF : 0xFF9A9A9A);
            } else {
                graphics.renderOutline(x, y, w, h, isHovered() && active ? 0xFFFFFFFF : 0x60FFFFFF);
            }
            UiFont font = RaceClient.get().font();
            int color = selected ? RaceLobby.INK : active ? 0xFFC8C8C8 : 0xFF707070;
            graphics.drawString(font, getMessage(), x + (w - font.width(getMessage())) / 2, y + 3, color, false);
        }

    }
}
