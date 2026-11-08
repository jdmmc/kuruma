package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * 主催者画面。<b>レースを開き、決まり（レギュレーション）を決め、参加者の判定を見てスタートする。</b>
 *
 * <p>レースが無いときは開く画面（コースと周回を選んで「レースを開く」）、受付中は決まりを変えながら
 * 参加者の判定を見る画面になる。<b>決まりを変えたらその場でサーバーへ送る</b>ので、参加者の判定も
 * 次の配信（0.5 秒以内）で変わる。</p>
 *
 * <p>Esc で閉じても、主催している間は画面の右の中ほどに主催中の表示が残り、K でここへ戻れる。</p>
 */
public final class RaceOrganizerScreen extends Screen {

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

    private final List<AbstractButton> regulationWidgets = new ArrayList<>();
    private Button courseButton;
    private Button lapsDown;
    private Button lapsUp;
    private Button openButton;
    private Button startButton;
    private Button stopButton;

    private int leftX;
    private int topY;

    /** 素性を項目ごとに選ぶ一覧を開いているか（「個別」）。 */
    private boolean showTraits;
    private Button traitsButton;
    /** 素性の項目ごとの入れ切り（{@link Regulation#FIXED_PARAMETERS} の順）。 */
    private final List<AbstractButton> traitToggles = new ArrayList<>();
    /** 参加者を外す「×」。行の並びが変わったら作り直す。 */
    private final List<Button> kickButtons = new ArrayList<>();
    private List<String> kickNames = List.of();

    /** レギュレーションのプリセット（サーバーに保存）。選んでいる番号と、保存する名前。 */
    private int presetIndex = -1;
    private Button presetButton;
    /** プリセットの一覧を開いているか。 */
    private boolean showPresets;
    private Button presetsButton;
    private Button presetLoad;
    private Button presetDelete;
    private Button presetSave;
    private EditBox presetName;
    private String presetNameText = "";

    public RaceOrganizerScreen() {
        super(Component.translatable("screen.kurumamod.race_host.title"));
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby != null) {
            draft = Regulation.fromArray(lobby.regulation());
            laps = lobby.laps();
        }
    }

    @Override
    protected void init() {
        regulationWidgets.clear();
        limitButtons.clear();
        leftX = 12;
        compact = height < 330 || width < 600;
        leftWidth = compact ? Math.min(200, width / 2 - 18) : 240;
        rowHeight = compact ? 15 : 18;
        topY = compact ? 26 : 30;
        int x = leftX;
        int y = topY;

        // コースと周回（レースを開く前だけ選べる。周回は受付中も変えられる）
        courseButton = addRenderableWidget(Button.builder(Component.empty(), button -> cycleCourse())
                .bounds(x + 52, y, 100, 16).build());
        lapsDown = addRenderableWidget(Button.builder(Component.literal("-"), button -> changeLaps(-1))
                .bounds(x + leftWidth - 72, y, 16, 16).build());
        lapsUp = addRenderableWidget(Button.builder(Component.literal("+"), button -> changeLaps(1))
                .bounds(x + leftWidth - 16, y, 16, 16).build());
        y += 22 + 14;

        // 車の素性の固定
        addToggle(x + 6, y + (compact ? 2 : 4), leftWidth - 60, "screen.kurumamod.race_host.tip.fixed",
                () -> draft.fixesAll(), () -> draft.fixedTraits() && !draft.fixesAll(),
                () -> draft = draft.withFixedTraits(!draft.fixesAll()));
        traitsButton = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_host.traits"),
                button -> {
                    showTraits = !showTraits;
                    showPresets = false;
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
            addChip(x + 74 + i * 42, y + chipY, 38, Component.literal(drives[i]),
                    () -> (draft.drives() & bit) != 0, () -> draft = draft.withDrives(draft.drives() ^ bit));
        }
        y += rowHeight;
        // 過給機
        addHelp(x + 6, y + 3, 64, "screen.kurumamod.race_host.tip.turbo");
        addChip(x + 74, y + chipY, 60, Component.translatable("screen.kurumamod.race_host.turbo_yes"),
                () -> draft.turbo(), () -> draft = draft.withTurbo(true));
        addChip(x + 138, y + chipY, 60, Component.translatable("screen.kurumamod.race_host.turbo_no"),
                () -> !draft.turbo(), () -> draft = draft.withTurbo(false));
        y += rowHeight;
        // 運転補助（白は使ってよい）
        addHelp(x + 6, y + 3, 64, "screen.kurumamod.race_host.tip.assist");
        addChip(x + 74, y + chipY, 38, Component.literal("TCS"),
                () -> draft.tractionControl(), () -> draft = draft.withTractionControl(!draft.tractionControl()));
        addChip(x + 116, y + chipY, 38, Component.literal("ABS"),
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

        // 下の段の左: レギュレーションのプリセット。押すと右に一覧を開く（選ぶ・読み込む・消す・名前を付けて保存）。
        // 下の段に全部並べると、小さい画面ではスタートのボタンと重なった
        int py = height - 28;
        presetsButton = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_host.presets"),
                button -> {
                    showPresets = !showPresets;
                    showTraits = false;
                }).bounds(12, py, 90, 20).build());
        presetButton = addRenderableWidget(Button.builder(Component.empty(), button -> cyclePreset())
                .bounds(0, 0, 110, 20).build());
        presetLoad = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_host.preset_load"),
                button -> loadPreset()).bounds(0, 0, 40, 20).build());
        presetDelete = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_host.preset_delete"),
                button -> {
                    RaceLobbyPacket.Preset preset = selectedPreset();
                    if (preset != null) {
                        KurumaNetwork.CHANNEL.sendToServer(new RaceActionPacket(RaceActionPacket.Action.DELETE_PRESET, preset.name(), 0,
                                new double[0]));
                        presetIndex = -1;
                    }
                }).bounds(0, 0, 40, 20).build());
        presetName = new EditBox(font, 0, 0, 90, 20, Component.translatable("screen.kurumamod.race_host.preset_name"));
        presetName.setHint(Component.translatable("screen.kurumamod.race_host.preset_name"));
        presetName.setMaxLength(32);
        presetName.setValue(presetNameText);
        presetName.setResponder(text -> presetNameText = text);
        addRenderableWidget(presetName);
        presetSave = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_host.preset_save"),
                button -> savePreset()).bounds(0, 0, 40, 20).build());

        // 下の進行のボタン
        int by = height - 28;
        openButton = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_host.open"),
                button -> openRace()).bounds(width - 12 - 110, by, 110, 20).build());
        startButton = addRenderableWidget(Button.builder(Component.empty(),
                        button -> KurumaNetwork.CHANNEL.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.START)))
                .bounds(width - 12 - 110, by, 110, 20).build());
        stopButton = addRenderableWidget(Button.builder(Component.empty(),
                        button -> KurumaNetwork.CHANNEL.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.STOP)))
                .bounds(width - 12 - 110 - 6 - 90, by, 90, 20).build());
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
    private AbstractButton addToggle(int x, int y, int w, String tooltip, BooleanSupplier on, BooleanSupplier partial,
                                     Runnable flip) {
        Toggle toggle = new Toggle(x, y, w, on, partial, () -> {
            flip.run();
            changed();
        });
        if (tooltip != null) {
            toggle.setTooltip(Tooltip.create(Component.translatable(tooltip)));
        }
        regulationWidgets.add(addRenderableWidget(toggle));
        return toggle;
    }

    /** 説明だけを出す場所（札の行の名前）。押しても何も起きない。主催者でなくても読める。 */
    private void addHelp(int x, int y, int w, String tooltip) {
        Toggle help = new Toggle(x, y, w, null, null, () -> {
        });
        help.setTooltip(Tooltip.create(Component.translatable(tooltip)));
        addRenderableWidget(help);
    }

    /** 参加者の「×」を、いまの並びに合わせて作り直す。 */
    private void rebuildKickButtons(RaceLobbyPacket lobby) {
        List<String> names = new ArrayList<>();
        if (lobby != null) {
            for (RaceLobbyPacket.Entrant entrant : lobby.entrants()) {
                names.add(entrant.you() ? "" : entrant.name());
            }
        }
        if (names.equals(kickNames)) {
            return;
        }
        kickNames = names;
        for (Button button : kickButtons) {
            removeWidget(button);
        }
        kickButtons.clear();
        for (String name : names) {
            Button button = Button.builder(Component.literal("x"), b -> KurumaNetwork.CHANNEL.sendToServer(
                            new RaceActionPacket(RaceActionPacket.Action.KICK, name, 0, new double[0])))
                    .bounds(0, 0, 14, 14)
                    .tooltip(Tooltip.create(Component.translatable("screen.kurumamod.race_host.kick")))
                    .build();
            button.visible = false;
            kickButtons.add(addRenderableWidget(button));
        }
    }

    /** 「個別」の一覧の位置（参加者の表の上に重ねる）。{x, y, 幅, 1 列の幅}。 */
    private int[] traitsArea() {
        int x = leftX + leftWidth + 12;
        int w = width - 12 - x;
        return new int[]{x, topY + 14, w, w / 2};
    }

    private void addLimit(int x, int y, String tooltip, BooleanSupplier on, Runnable flip, Step step) {
        // 四角だけでなく行の名前まで押せる。名前にカーソルを乗せると説明が出る
        int size = compact ? 13 : 16;
        addToggle(x + 6, y + (compact ? 1 : 3), leftWidth - 116, tooltip, on, null, flip);
        Button down = Button.builder(Component.literal("-"), button -> {
            step.apply(-1);
            changed();
        }).bounds(x + leftWidth - 108, y + 1, size, size).build();
        Button up = Button.builder(Component.literal("+"), button -> {
            step.apply(1);
            changed();
        }).bounds(x + leftWidth - 2 - size, y + 1, size, size).build();
        regulationWidgets.add(addRenderableWidget(down));
        regulationWidgets.add(addRenderableWidget(up));
        limitButtons.add(new LimitButtons(on, down, up));
    }

    private record LimitButtons(BooleanSupplier on, Button down, Button up) {
    }

    private final List<LimitButtons> limitButtons = new ArrayList<>();

    private void addChip(int x, int y, int w, Component label, BooleanSupplier on, Runnable press) {
        regulationWidgets.add(addRenderableWidget(new Chip(x, y, w, label, on, () -> {
            press.run();
            changed();
        })));
    }

    private RaceLobbyPacket.Preset selectedPreset() {
        List<RaceLobbyPacket.Preset> presets = RaceLobby.presets();
        return presetIndex >= 0 && presetIndex < presets.size() ? presets.get(presetIndex) : null;
    }

    private void cyclePreset() {
        int count = RaceLobby.presets().size();
        if (count == 0) {
            presetIndex = -1;
            return;
        }
        presetIndex = presetIndex + 1 >= count ? -1 : presetIndex + 1;
        RaceLobbyPacket.Preset preset = selectedPreset();
        if (preset != null) {
            presetNameText = preset.name();
            presetName.setValue(preset.name());
        }
    }

    /** 選んだプリセットを、画面の決まりへ読み込む（受付中ならそのまま送る）。 */
    private void loadPreset() {
        RaceLobbyPacket.Preset preset = selectedPreset();
        if (preset != null) {
            draft = Regulation.fromArray(preset.regulation());
            changed();
        }
    }

    /** 画面の決まりを、入れた名前でサーバーへ保存する。名前が空なら選んでいるプリセットへ上書き。 */
    private void savePreset() {
        String name = presetNameText.trim();
        if (name.isEmpty() && selectedPreset() != null) {
            name = selectedPreset().name();
        }
        if (!name.isEmpty()) {
            KurumaNetwork.CHANNEL.sendToServer(new RaceActionPacket(RaceActionPacket.Action.SAVE_PRESET, name, 0, draft.toArray()));
        }
    }

    /** 決まりを変えた。受付中ならその場で送る。 */
    private void changed() {
        pendingTicks = 20;
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby != null && lobby.phase() == RaceLobbyPacket.Phase.OPEN && lobby.canManage()) {
            KurumaNetwork.CHANNEL.sendToServer(new RaceActionPacket(RaceActionPacket.Action.UPDATE, "", laps,
                    draft.toArray()));
        }
    }

    private void cycleCourse() {
        List<RaceLobbyPacket.CourseInfo> courses = RaceLobby.courses();
        if (courses.isEmpty()) {
            return;
        }
        courseIndex = (courseIndex + 1) % courses.size();
        laps = courses.get(courseIndex).laps();
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
        KurumaNetwork.CHANNEL.sendToServer(new RaceActionPacket(RaceActionPacket.Action.OPEN, course.name(),
                laps > 0 ? laps : course.laps(), draft.toArray()));
    }

    @Override
    public void tick() {
        if (pendingTicks > 0) {
            pendingTicks--;
        }
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby != null && pendingTicks == 0) {
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
        boolean editable = manage && (none || open);

        List<RaceLobbyPacket.CourseInfo> courses = RaceLobby.courses();
        if (none) {
            String name = courses.isEmpty() ? Component.translatable("screen.kurumamod.race_host.no_course").getString()
                    : courses.get(Math.min(courseIndex, courses.size() - 1)).name();
            courseButton.setMessage(Component.literal(name));
        } else {
            courseButton.setMessage(Component.literal(lobby.course()));
        }
        courseButton.active = none && courses.size() > 1;
        lapsDown.active = editable && laps > 1;
        lapsUp.active = editable && laps < 99;

        for (AbstractButton widget : regulationWidgets) {
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
            AbstractButton toggle = traitToggles.get(i);
            toggle.visible = showTraits;
            toggle.setX(area[0] + 6 + (i % 2) * area[3]);
            toggle.setY(area[1] + 18 + (i / 2) * 16);
            toggle.setWidth(area[3] - 12);
        }
        traitsButton.active = editable || showTraits;
        traitsButton.setMessage(Component.translatable(showTraits
                ? "screen.kurumamod.race_host.traits_close" : "screen.kurumamod.race_host.traits"));

        // 参加者の「×」（主催者だけ。素性の一覧を開いている間は隠す）
        rebuildKickButtons(lobby);
        boolean kickable = lobby != null && lobby.canManage() && !showTraits && !showPresets
                && phase != RaceLobbyPacket.Phase.RESULT;
        int rowTop = topY + 15 + 11;
        for (int i = 0; i < kickButtons.size(); i++) {
            Button button = kickButtons.get(i);
            int rowY = rowTop + i * 22;
            button.setX(width - 12 - 16);
            button.setY(rowY + 4);
            button.visible = kickable && !kickNames.get(i).isEmpty() && rowY + 22 <= height - 36;
        }

        // プリセットの一覧（右の表の上に重ねる）
        int pw = area[2] - 12;
        int px = area[0] + 6;
        int pyTop = area[1] + 18;
        presetButton.setX(px);
        presetButton.setY(pyTop);
        presetButton.setWidth(pw);
        presetLoad.setX(px);
        presetLoad.setY(pyTop + 24);
        presetLoad.setWidth((pw - 4) / 2);
        presetDelete.setX(px + (pw - 4) / 2 + 4);
        presetDelete.setY(pyTop + 24);
        presetDelete.setWidth(pw - (pw - 4) / 2 - 4);
        presetName.setX(px);
        presetName.setY(pyTop + 52);
        presetName.setWidth(pw - 54);
        presetSave.setX(px + pw - 50);
        presetSave.setY(pyTop + 52);
        presetSave.setWidth(50);
        for (net.minecraft.client.gui.components.AbstractWidget widget
                : List.of(presetButton, presetLoad, presetDelete, presetName, presetSave)) {
            widget.visible = showPresets;
        }
        presetsButton.setMessage(Component.translatable(showPresets
                ? "screen.kurumamod.race_host.traits_close" : "screen.kurumamod.race_host.presets"));

        // プリセット
        RaceLobbyPacket.Preset preset = selectedPreset();
        if (presetIndex >= RaceLobby.presets().size()) {
            presetIndex = -1;
        }
        presetButton.setMessage(preset == null
                ? Component.translatable(RaceLobby.presets().isEmpty()
                ? "screen.kurumamod.race_host.preset_none" : "screen.kurumamod.race_host.preset_pick")
                : Component.literal(preset.name()));
        presetButton.active = !RaceLobby.presets().isEmpty();
        presetLoad.active = preset != null && editable;
        // 保存と削除は OP だけ（サーバーに残るもの）。選んで読み込むのは誰でもできる
        boolean admin = lobby != null ? lobby.presetAdmin() : RaceLobby.presetAdmin();
        presetDelete.active = preset != null && admin;
        presetSave.active = (!presetNameText.trim().isEmpty() || preset != null) && admin;
        presetName.setEditable(admin);

        openButton.visible = none;
        openButton.active = !courses.isEmpty();
        startButton.visible = open && manage;
        stopButton.visible = !none && manage;
        int ok = 0;
        if (lobby != null) {
            for (RaceLobbyPacket.Entrant entrant : lobby.entrants()) {
                if (entrant.verdict() != RaceLobbyPacket.Verdict.VIOLATION) {
                    ok++;
                }
            }
        }
        startButton.setMessage(Component.translatable("screen.kurumamod.race_host.start", ok));
        startButton.active = ok > 0;
        stopButton.setMessage(Component.translatable(open
                ? "screen.kurumamod.race_host.cancel" : "screen.kurumamod.race_host.stop"));
        if (!open && !none) {
            // 走っている間は「終える」を右端へ
            stopButton.setX(width - 12 - 90);
        } else {
            stopButton.setX(width - 12 - 110 - 6 - 90);
        }
    }

    /** 背景と、ボタンの下に敷くもの（表・文字）。1.20.1 の {@code Screen#render} は背景を描かないので自分で敷く。 */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        renderPanels(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderPanels(GuiGraphics graphics) {
        Font font = this.font;
        RaceLobbyPacket lobby = RaceLobby.lobby();
        RaceLobbyPacket.Phase phase = lobby == null ? RaceLobbyPacket.Phase.NONE : lobby.phase();

        // 見出し
        graphics.drawString(font, title, 12, 11, RaceLobby.WHITE, false);
        String tag = switch (phase) {
            case NONE -> "NO RACE";
            case OPEN -> "ENTRY OPEN";
            case COUNTDOWN, RUNNING -> "LIVE";
            case RESULT -> "RESULT";
        };
        int tagX = 12 + font.width(title) + 12;
        int tagWidth = font.width(tag) + 12;
        Slant.fill(graphics, tagX, 8, tagWidth, 14, phase == RaceLobbyPacket.Phase.NONE ? 0xFF3A3A3A : RaceLobby.ACCENT);
        graphics.drawString(font, tag, tagX + 6, 11, RaceLobby.WHITE, false);
        if (lobby != null) {
            Component course = Component.translatable("race.kurumamod.hud_course", lobby.course(), lobby.laps());
            int room = width - 24 - (tagX + tagWidth) - 14;
            int shown = Math.min(font.width(course), room);
            Slant.fill(graphics, tagX + tagWidth, 8, shown + 14, 14, RaceLobby.PANEL);
            Slant.fit(graphics, font, course, tagX + tagWidth + 7, 11, room, RaceLobby.WHITE);
        }

        renderLeft(graphics, font);
        renderEntrants(graphics, font, lobby);
        if (showTraits) {
            renderTraits(graphics, font);
        }
        if (showPresets) {
            renderPresets(graphics, font);
        }

        // 下の説明
        Component note = noteFor(lobby);
        if (!compact) {
            Slant.fit(graphics, font, note, 12, height - 42, width - 24, RaceLobby.GREY);
        }
    }

    private Component noteFor(RaceLobbyPacket lobby) {
        if (lobby == null) {
            return RaceLobby.courses().isEmpty()
                    ? Component.translatable("screen.kurumamod.race_host.note_no_course")
                    : Component.translatable("screen.kurumamod.race_host.note_open");
        }
        if (lobby.phase() == RaceLobbyPacket.Phase.OPEN) {
            int ng = 0;
            for (RaceLobbyPacket.Entrant entrant : lobby.entrants()) {
                if (entrant.verdict() == RaceLobbyPacket.Verdict.VIOLATION) {
                    ng++;
                }
            }
            return ng > 0 ? Component.translatable("screen.kurumamod.race_host.note_violators", ng)
                    : Component.translatable("screen.kurumamod.race_host.note_ready");
        }
        return Component.translatable("screen.kurumamod.race_host.note_running");
    }

    private void row(GuiGraphics graphics, int y, int height, boolean alt) {
        graphics.fill(leftX, y, leftX + leftWidth, y + height, alt ? RaceLobby.PANEL_LIGHT : RaceLobby.PANEL);
    }

    private void renderLeft(GuiGraphics graphics, Font font) {
        int x = leftX;
        int y = topY;
        row(graphics, y - 2, 20, false);
        graphics.drawString(font, Component.translatable("screen.kurumamod.race_host.course"), x + 6, y + 4,
                RaceLobby.WHITE, false);
        String lapText = Component.translatable("screen.kurumamod.race_host.laps", laps).getString();
        graphics.drawString(font, lapText, x + leftWidth - 36 - font.width(lapText) / 2, y + 4, RaceLobby.WHITE, false);
        y += 22;

        Slant.fill(graphics, x, y, leftWidth, 12, RaceLobby.INK);
        graphics.drawString(font, "REGULATION", x + 7, y + 2, RaceLobby.WHITE, false);
        Component hint = Component.translatable("screen.kurumamod.race_host.off_hint");
        graphics.drawString(font, hint, x + leftWidth - 7 - font.width(hint), y + 2, RaceLobby.GREY, false);
        y += 14;

        // 素性の固定（既定でオン）
        int fixedHeight = compact ? 16 : 24;
        row(graphics, y, fixedHeight, false);
        if (draft.fixedTraits()) {
            graphics.fill(x, y, x + 2, y + fixedHeight, RaceLobby.ACCENT);
        }
        Slant.fit(graphics, font, draft.fixesAll() || !draft.fixedTraits()
                        ? Component.translatable("screen.kurumamod.race_host.fixed")
                        : Component.translatable("screen.kurumamod.race_host.fixed_partial",
                        draft.fixedCount(), Regulation.FIXED_PARAMETERS.size()),
                x + 24, y + (compact ? 4 : 3), leftWidth - 80, RaceLobby.WHITE);
        if (!compact) {
            Slant.fit(graphics, font, Component.translatable("screen.kurumamod.race_host.fixed_hint"),
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
            Slant.fit(graphics, font, Component.translatable(limits[i][0]), x + 24, textY, leftWidth - 136, color);
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
            Slant.fit(graphics, font, Component.translatable(labels[i]), x + 6, y + (compact ? 3 : 5), 64, RaceLobby.WHITE);
            y += rowHeight;
        }
        if (!compact) {
            Slant.fit(graphics, font, Component.translatable("screen.kurumamod.race_host.assist_hint"),
                    x + 6, y + 3, leftWidth - 12, RaceLobby.GREY);
        }
    }

    /** プリセットの一覧。参加者の表の上に重ねる。 */
    private void renderPresets(GuiGraphics graphics, Font font) {
        int[] area = traitsArea();
        graphics.fill(area[0], area[1] - 2, area[0] + area[2], area[1] + 18 + 76, 0xF0111111);
        Slant.fill(graphics, area[0], area[1] - 2, area[2], 14, RaceLobby.ACCENT);
        Slant.fit(graphics, font, Component.translatable("screen.kurumamod.race_host.presets_title"),
                area[0] + 7, area[1] + 1, area[2] - 14, RaceLobby.WHITE);
    }

    /** 「個別」: 固定する素性を項目ごとに選ぶ一覧。参加者の表の上に重ねる。 */
    private void renderTraits(GuiGraphics graphics, Font font) {
        int[] area = traitsArea();
        int rows = (Regulation.FIXED_PARAMETERS.size() + 1) / 2;
        graphics.fill(area[0], area[1] - 2, area[0] + area[2], area[1] + 18 + rows * 16 + 4, 0xF0111111);
        Slant.fill(graphics, area[0], area[1] - 2, area[2], 14, RaceLobby.ACCENT);
        Slant.fit(graphics, font, Component.translatable("screen.kurumamod.race_host.traits_title",
                draft.fixedCount(), Regulation.FIXED_PARAMETERS.size()), area[0] + 7, area[1] + 1, area[2] - 14,
                RaceLobby.WHITE);
        for (int i = 0; i < Regulation.FIXED_PARAMETERS.size(); i++) {
            TunableParameter parameter = Tunables.byName(Regulation.FIXED_PARAMETERS.get(i));
            Component name = parameter == null ? Component.literal(Regulation.FIXED_PARAMETERS.get(i))
                    : Component.translatable(parameter.translationKey());
            int x = area[0] + 6 + (i % 2) * area[3];
            int y = area[1] + 18 + (i / 2) * 16;
            Slant.fit(graphics, font, name, x + 16, y + 2, area[3] - 30,
                    draft.fixesTrait(i) ? RaceLobby.WHITE : 0xFF9A9A9A);
        }
    }

    private void renderEntrants(GuiGraphics graphics, Font font, RaceLobbyPacket lobby) {
        int x = leftX + leftWidth + 12;
        int right = width - 12;
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
        Component okText = Component.translatable("screen.kurumamod.race_host.ok_count", ok);
        Component ngText = Component.translatable("screen.kurumamod.race_host.ng_count", ng);
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
        graphics.drawString(font, Component.translatable("screen.kurumamod.race_host.driver"), x + 6, y,
                RaceLobby.GREY, false);
        for (int i = 0; i < valueColumns; i++) {
            graphics.drawString(font, heads[i], cx + widths[i] - 4 - font.width(heads[i]), y, RaceLobby.GREY, false);
            cx += widths[i];
        }
        y += 11;

        if (entrants.isEmpty()) {
            graphics.fill(x, y, right, y + 20, RaceLobby.PANEL_LIGHT);
            graphics.drawString(font, Component.translatable(lobby == null
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
            RaceInfoScreen.verdictBadge(graphics, font, entrant, vx + 40, y + 2);
            Component reason = good ? Component.translatable("screen.kurumamod.race_host.can_start")
                    : entrant.verdict() == RaceLobbyPacket.Verdict.NO_CAR
                    ? Component.translatable("race.kurumamod.no_car")
                    : RaceLobby.describeAll(entrant.violations());
            Slant.fit(graphics, font, reason, vx, y + 13, verdictWidth - 20, 0xFFD4D7DC);
            y += 22;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (presetName != null && presetName.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (KurumaKeys.OPEN_RACE.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (CarClientDriver.isDrivingKey(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 入れ切りの四角。入っていれば赤く塗ってレ点を描く。 */
    private static final class Toggle extends AbstractButton {

        private final BooleanSupplier on;
        private final Runnable press;

        /** 一部だけ入っている（素性の一括）。null なら使わない。 */
        private final BooleanSupplier partial;

        private Toggle(int x, int y, int w, BooleanSupplier on, BooleanSupplier partial, Runnable press) {
            super(x, y, w, 12, Component.empty());
            this.on = on;
            this.partial = partial;
            this.press = press;
        }

        @Override
        public void onPress() {
            press.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** 札（駆動方式・過給機・補助）。選んでいるものは白い。 */
    private static final class Chip extends AbstractButton {

        private final BooleanSupplier on;
        private final Runnable press;

        private Chip(int x, int y, int w, Component label, BooleanSupplier on, Runnable press) {
            super(x, y, w, 14, label);
            this.on = on;
            this.press = press;
        }

        @Override
        public void onPress() {
            press.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
            Font font = net.minecraft.client.Minecraft.getInstance().font;
            int color = selected ? RaceLobby.INK : active ? 0xFFC8C8C8 : 0xFF707070;
            graphics.drawString(font, getMessage(), x + (w - font.width(getMessage())) / 2, y + 3, color, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
