package com.jdmmc.kurumamod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 押すと開いて 1 つ選ぶ一覧（ドロップダウン）。K の画面の「コース ▼」「クラス ▼」が、右の列の上に重ねて開く。
 *
 * <p><b>開く位置は、押したボタンの行より下にすること。</b>Minecraft は文字をまとめて最後に描くので、
 * 上に重ねてもボタンの字が一覧を突き抜けて見える（実際に見えた）。</p>
 *
 * <p>ウィジェットではなく、画面が描画とクリック・ホイールを渡す。<b>ボタンより上に描く</b>ので、画面の
 * {@code render} の最後（{@code super.render} の後）で {@link #render} を呼ぶこと。開いている間の外側のクリックは
 * 閉じるだけで、下のボタンには届けない（押すつもりの無いボタンが押されないように）。</p>
 */
final class PickList {

    static final int ROW = 22;
    private static final int HEADER = 14;

    /**
     * 1 行。
     *
     * @param key    選んだときに返す値
     * @param sub    名前の下の小さい字（要約）。null なら 1 行
     * @param badge  右端の札。null なら出さない
     * @param marked 左に赤い印（宣言している・今のもの）
     */
    record Item(String key, Component name, Component sub, Component badge, int badgeColor, boolean marked) {
    }

    private Component title = Component.empty();
    private List<Item> items = List.of();
    private String selected;
    private Consumer<String> onPick;
    private boolean open;
    private int x;
    private int y;
    private int w;
    private int bottom;
    private int scroll;

    boolean isOpen() {
        return open;
    }

    /** {@code [x, x+w) × [y, bottom)} に開く。{@code selected} の行を明るくする。 */
    void open(Component title, int x, int y, int w, int bottom, List<Item> items, String selected,
              Consumer<String> onPick) {
        this.title = title;
        this.x = x;
        this.y = y;
        this.w = w;
        this.bottom = bottom;
        this.items = items;
        this.selected = selected;
        this.onPick = onPick;
        this.open = true;
        // 選んでいる行が見えるところから
        int index = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(selected)) {
                index = i;
            }
        }
        scroll = Math.max(0, Math.min(index - visibleRows() / 2, items.size() - visibleRows()));
    }

    void close() {
        open = false;
    }

    /** 開いたまま中身だけ差し替える（一覧が配り直されたとき）。 */
    void update(List<Item> items, String selected) {
        this.items = items;
        this.selected = selected;
        scroll = Math.max(0, Math.min(scroll, items.size() - visibleRows()));
    }

    private int visibleRows() {
        return Math.max(1, (bottom - y - HEADER - 2) / ROW);
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < bottom;
    }

    /** @return クリックを使ったか（開いていれば常に使う） */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (!open) {
            return false;
        }
        if (!contains(mouseX, mouseY)) {
            close();
            return true;
        }
        int row = (int) ((mouseY - y - HEADER - 2) / ROW);
        int index = scroll + row;
        if (mouseY >= y + HEADER + 2 && row < visibleRows() && index >= 0 && index < items.size()) {
            // ボタンと同じクリックの音
            net.minecraft.client.Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            close();
            onPick.accept(items.get(index).key());
        }
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), items.size() - visibleRows()));
        return true;
    }

    void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        int rows = Math.min(visibleRows(), items.size());
        // 開いた場所から下の端まで黒く塗る（主催者画面のクラスの一覧と同じ）。行のぶんだけ塗ると、
        // 下の表が見えたままで選ぶことに集中できない
        graphics.fill(x, y, x + w, bottom, 0xF0111111);
        Slant.fill(graphics, x, y, w, HEADER, RaceLobby.ACCENT);
        Slant.fit(graphics, font, title, x + 7, y + 3, w - 70, RaceLobby.WHITE);
        String count = items.size() > visibleRows()
                ? (scroll + 1) + "-" + Math.min(items.size(), scroll + visibleRows()) + " / " + items.size()
                : String.valueOf(items.size());
        graphics.text(font, count, x + w - 7 - font.width(count), y + 3, RaceLobby.WHITE, false);
        int rowY = y + HEADER + 2;
        if (items.isEmpty()) {
            Slant.fit(graphics, font, Component.translatable("screen.kurumamod.pick.empty"), x + 8, rowY + 6,
                    w - 16, RaceLobby.GREY);
            return;
        }
        for (int i = scroll; i < scroll + rows; i++) {
            Item item = items.get(i);
            boolean current = item.key().equals(selected);
            boolean over = mouseX >= x + 4 && mouseX < x + w - 4 && mouseY >= rowY && mouseY < rowY + ROW - 2;
            graphics.fill(x + 4, rowY, x + w - 4, rowY + ROW - 2,
                    current ? 0x55E1261C : over ? 0x33FFFFFF : 0x18FFFFFF);
            if (item.marked()) {
                graphics.fill(x + 4, rowY, x + 6, rowY + ROW - 2, RaceLobby.ACCENT);
            }
            int badgeWidth = 0;
            if (item.badge() != null) {
                badgeWidth = font.width(item.badge()) + 8;
                int bx = x + w - 8 - badgeWidth;
                graphics.fill(bx, rowY + 5, bx + badgeWidth, rowY + 16, item.badgeColor());
                graphics.text(font, item.badge(), bx + 4, rowY + 7, RaceLobby.WHITE, false);
            }
            int room = w - 20 - (badgeWidth > 0 ? badgeWidth + 6 : 0);
            if (item.sub() == null) {
                Slant.fit(graphics, font, item.name(), x + 10, rowY + 6, room, RaceLobby.WHITE);
            } else {
                Slant.fit(graphics, font, item.name(), x + 10, rowY + 2, room, RaceLobby.WHITE);
                Slant.fit(graphics, font, item.sub(), x + 10, rowY + 11, room, RaceLobby.GREY);
            }
            rowY += ROW;
        }
    }
}
