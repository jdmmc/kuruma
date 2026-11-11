package com.jdmmc.kurumamod.client.ui;

/**
 * 車まわりの画面（換装・セッティング・座席・H）の色と、タイルの箱。版の {@code ScreenStyle} と同じ値
 * （版のほうには、いま開いているのが車をいじる画面か、を見る口が残っている）。
 */
public final class ScreenStyle {

    /** 選んでいるものの色。<b>90 年代の計器と同じ琥珀寄りの黄色</b>（{@code GaugeTheme} と揃えてある）。 */
    public static final int ACCENT = 0xFFFFC83C;

    public static final int TILE = 0xD0141414;
    public static final int TILE_HOVER = 0xE0202020;
    public static final int BORDER = 0x50FFFFFF;
    public static final int BORDER_HOVER = 0xA0FFFFFF;

    public static final int LABEL = 0xFFE8E8E8;
    /** 黄色の上に載せる文字。<b>白のままだと読めない。</b> */
    public static final int LABEL_ON_ACCENT = 0xFF141414;
    public static final int SUB = 0xFF8A8A8A;
    public static final int HINT = 0xFFB0B0B0;

    /**
     * 一覧の 1 行の下敷き。
     *
     * <p><b>画面を暗くしない代わりに、文字の後ろにだけ敷く。</b>後ろが空でも木でも
     * 同じように読めて、なおかつ車は透けて見える濃さにしてある。</p>
     */
    public static final int ROW = 0x66000000;
    public static final int ROW_ALT = 0x8C000000;

    private ScreenStyle() {
    }

    /**
     * タイルの箱。<b>選んでいるものは黄色い帯と黄色い枠になる。</b>
     *
     * @param labelHeight 下端の黄色い帯の高さ。<b>高さと同じ値を渡せばタイル全体が黄色くなる</b>
     */
    public static void tileBox(UiGraphics graphics, int left, int top, int width, int height,
                               boolean selected, boolean hovered, int labelHeight) {
        int right = left + width;
        int bottom = top + height;
        graphics.fill(left, top, right, bottom, hovered ? TILE_HOVER : TILE);
        if (selected) {
            graphics.fill(left, bottom - Math.min(labelHeight, height), right, bottom, ACCENT);
        }
        graphics.renderOutline(left, top, width, height, selected ? ACCENT : hovered ? BORDER_HOVER : BORDER);
    }
}
