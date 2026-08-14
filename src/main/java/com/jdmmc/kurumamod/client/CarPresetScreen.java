package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * セッティングのプリセット画面。{@link CarTuningScreen} のフッターから開く。
 *
 * <p>2 種類を 1 つの一覧に並べる。</p>
 *
 * <ul>
 *   <li><b>組み込み</b>（{@link CarPresets}）… MOD 同梱。読み込めるだけで、消せない</li>
 *   <li><b>ユーザー</b>（{@link CarSetups}）… 自分で保存したもの。上書きも削除もできる</li>
 * </ul>
 *
 * <p>読み込むと {@link CarTuning} が丸ごと差し替わり、運転中なら次のティックから挙動が
 * 変わる。サーバーへも送る（降りた後の車高がずれないように）。</p>
 *
 * <p><b>削除は 2 度押しで確かめる。</b>作り込んだセッティングが 1 クリックで消えると
 * 取り返しがつかないため、1 度目で「本当に？」に変わり、他を触ると元に戻る。</p>
 */
public class CarPresetScreen extends Screen {

    private static final int PANEL_WIDTH = 300;
    private static final int ROW_HEIGHT = 22;
    private static final int GAP = 4;
    private static final int LOAD_WIDTH = 52;
    private static final int DELETE_WIDTH = 46;
    private static final int TITLE_Y = 12;
    private static final int LIST_TOP = 34;
    private static final int FOOTER_HEIGHT = 60;

    private static final int COLOR_PANEL = 0x60000000;
    private static final int COLOR_STRIPE = 0x24FFFFFF;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_KIND = 0xFF909090;
    private static final int COLOR_HINT = 0xFF909090;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private EditBox nameBox;
    private Button saveButton;
    private int scroll;
    private int visibleRows;
    private int panelLeft;

    /** 削除待ちの行。他を触ると外れる。 */
    private Row pendingDelete;

    /**
     * 入力中の名前。<b>ウィジェットではなくこちらが正</b>にしてある。
     * 一覧を作り直しても打ちかけの名前が消えないようにするため。
     */
    private String nameText = "";

    /**
     * 一覧を作り直す必要があるか。
     *
     * <p><b>ボタンの中から {@code rebuildWidgets()} を呼んではいけない。</b>クリックの
     * 伝播中にウィジェット一覧を入れ替えることになる。旗を立てておいて、次の描画の
     * 頭で作り直す。</p>
     */
    private boolean needsRebuild;

    public CarPresetScreen(Screen parent) {
        super(Component.translatable("screen.kurumamod.preset.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        pendingDelete = null;
        needsRebuild = false;
        panelLeft = (width - PANEL_WIDTH) / 2;

        List<Entry> entries = entries();
        int available = height - LIST_TOP - FOOTER_HEIGHT;
        visibleRows = Math.max(1, Math.min(Math.max(entries.size(), 1), available / ROW_HEIGHT));
        for (Entry entry : entries) {
            rows.add(createRow(entry));
        }

        int footerY = LIST_TOP + visibleRows * ROW_HEIGHT + 8;
        addSaveRow(footerY);

        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(panelLeft, footerY + 24, PANEL_WIDTH, 20)
                .build());

        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows)));
        layout();
    }

    /** 組み込みが先、ユーザーが後。組み込みは出発点なので上にある方が探しやすい。 */
    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (CarPresets.Preset preset : CarPresets.all()) {
            entries.add(new Entry(preset.id(), preset.displayName(), true));
        }
        for (String name : CarSetups.names()) {
            entries.add(new Entry(name, Component.literal(name), false));
        }
        return entries;
    }

    /** 名前を入れて今の設定を保存する行。名前が既にあれば「上書き」に変わる。 */
    private void addSaveRow(int y) {
        int fieldWidth = PANEL_WIDTH - LOAD_WIDTH - GAP;
        nameBox = new EditBox(font, panelLeft + 1, y, fieldWidth - 2, 20,
                Component.translatable("screen.kurumamod.preset.name"));
        nameBox.setHint(Component.translatable("screen.kurumamod.preset.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(nameText);
        nameBox.setResponder(text -> {
            nameText = text;
            refreshSaveButton();
        });
        addRenderableWidget(nameBox);

        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.preset.save"),
                        button -> saveCurrent())
                .bounds(panelLeft + fieldWidth + GAP, y, LOAD_WIDTH, 20)
                .build());
        refreshSaveButton();
    }

    private Row createRow(Entry entry) {
        int deleteLeft = panelLeft + PANEL_WIDTH - DELETE_WIDTH;
        int loadLeft = deleteLeft - GAP - LOAD_WIDTH;

        Button load = addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.preset.load"),
                        button -> loadPreset(entry))
                .bounds(loadLeft, 0, LOAD_WIDTH, 20)
                .build());

        Row row = new Row(entry, load);

        // 組み込みは消せないので、ボタン自体を置かずに理由をツールチップで出す
        Button delete = addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.preset.delete"),
                        button -> clickDelete(row))
                .tooltip(Tooltip.create(Component.translatable(entry.builtin()
                        ? "screen.kurumamod.preset.builtin_locked"
                        : "screen.kurumamod.preset.delete_tip")))
                .bounds(deleteLeft, 0, DELETE_WIDTH, 20)
                .build());
        delete.active = !entry.builtin();
        row.delete = delete;
        return row;
    }

    private void loadPreset(Entry entry) {
        CarSpec spec = entry.builtin() ? builtinSpec(entry.id()) : CarSetups.load(entry.id());
        if (spec == null) {
            return;
        }
        CarTuning.apply(spec);
        CarClientDriver.sendTuningToServer();
        pendingDelete = null;
        // 読み込んだ名前を入れておくと、少し直して上書き保存する流れがそのまま通る
        if (!entry.builtin()) {
            nameText = entry.id();
            nameBox.setValue(nameText);
        }
        refreshSaveButton();
    }

    private static CarSpec builtinSpec(String id) {
        for (CarPresets.Preset preset : CarPresets.all()) {
            if (preset.id().equals(id)) {
                return preset.toSpec();
            }
        }
        return null;
    }

    /** 1 度目は確認に変え、2 度目で消す。 */
    private void clickDelete(Row row) {
        if (pendingDelete != row) {
            pendingDelete = row;
            return;
        }
        CarSetups.delete(row.entry.id());
        pendingDelete = null;
        needsRebuild = true;
    }

    private void saveCurrent() {
        String name = nameText.trim();
        if (!isValidName(name)) {
            return;
        }
        CarSetups.save(name, CarTuning.spec());
        nameText = name;
        pendingDelete = null;
        needsRebuild = true;
    }

    /**
     * 保存できる名前か。
     *
     * <p>組み込みと同じ名前は弾く。<b>一覧に同じ名前が 2 つ並ぶと、どちらが読まれるか
     * 分からなくなる</b>ため（読み込みは組み込みを先に見る）。</p>
     */
    private static boolean isValidName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (CarPresets.Preset preset : CarPresets.all()) {
            if (preset.id().equalsIgnoreCase(name)) {
                return false;
            }
        }
        return true;
    }

    private void refreshSaveButton() {
        String name = nameText.trim();
        saveButton.active = isValidName(name);
        saveButton.setMessage(Component.translatable(CarSetups.exists(name)
                ? "screen.kurumamod.preset.overwrite"
                : "screen.kurumamod.preset.save"));
    }

    private void layout() {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int index = i - scroll;
            row.y = LIST_TOP + index * ROW_HEIGHT;
            row.setVisible(index >= 0 && index < visibleRows);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, rows.size() - visibleRows);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            layout();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 一覧の作り直しはここで行う。ボタンの中でやるとクリックの伝播中に
        // ウィジェット一覧を入れ替えることになる
        if (needsRebuild) {
            rebuildWidgets();
        }

        renderBackground(graphics);

        for (Row row : rows) {
            row.refresh(row == pendingDelete);
        }

        int listHeight = visibleRows * ROW_HEIGHT;
        graphics.fill(panelLeft - 5, LIST_TOP - 5, panelLeft + PANEL_WIDTH + 5,
                LIST_TOP + listHeight + 3, COLOR_PANEL);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.visible && ((i - scroll) & 1) == 0) {
                graphics.fill(panelLeft - 3, row.y - 2, panelLeft + PANEL_WIDTH + 3,
                        row.y + ROW_HEIGHT - 3, COLOR_STRIPE);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, TITLE_Y, 0xFFFFFFFF);

        int nameWidth = PANEL_WIDTH - LOAD_WIDTH - DELETE_WIDTH - GAP * 2;
        for (Row row : rows) {
            if (!row.visible) {
                continue;
            }
            String name = row.entry.label().getString();
            graphics.drawString(font, font.plainSubstrByWidth(name, nameWidth - 46),
                    panelLeft, row.y + 6, COLOR_NAME, false);
            Component kind = Component.translatable(row.entry.builtin()
                    ? "screen.kurumamod.preset.builtin" : "screen.kurumamod.preset.user");
            graphics.drawString(font, kind, panelLeft + nameWidth - 44, row.y + 6, COLOR_KIND, false);
        }

        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.preset.empty"),
                    width / 2, LIST_TOP + 6, COLOR_HINT);
        }

        renderScrollBar(graphics, listHeight);

        graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.preset.hint"),
                width / 2, LIST_TOP + listHeight + 54, COLOR_HINT);
    }

    private void renderScrollBar(GuiGraphics graphics, int listHeight) {
        int total = rows.size();
        if (total <= visibleRows) {
            return;
        }
        int left = panelLeft + PANEL_WIDTH + 8;
        graphics.fill(left, LIST_TOP, left + 3, LIST_TOP + listHeight, 0x40FFFFFF);
        int thumb = Math.max(12, listHeight * visibleRows / total);
        int top = LIST_TOP + (listHeight - thumb) * scroll / (total - visibleRows);
        graphics.fill(left, top, left + 3, top + thumb, 0xC0FFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** セッティング画面と揃える。プリセットを当てた効果をその場で見られるように。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 一覧の 1 件。
     *
     * @param id      読み込みに使う識別子。ユーザーのものは名前そのもの
     * @param label   画面に出す名前
     * @param builtin 組み込みなら true（消せない）
     */
    private record Entry(String id, Component label, boolean builtin) {
    }

    /** 一覧の 1 行ぶんのウィジェット。 */
    private static final class Row {

        private final Entry entry;
        private final Button load;
        private Button delete;

        private int y;
        private boolean visible;

        private Row(Entry entry, Button load) {
            this.entry = entry;
            this.load = load;
        }

        void setVisible(boolean visible) {
            this.visible = visible;
            load.visible = visible;
            load.active = visible;
            load.setY(y);
            delete.visible = visible;
            delete.setY(y);
        }

        void refresh(boolean confirming) {
            if (!visible) {
                return;
            }
            delete.active = !entry.builtin();
            delete.setMessage(Component.translatable(confirming
                    ? "screen.kurumamod.preset.delete_confirm"
                    : "screen.kurumamod.preset.delete"));
        }
    }
}
