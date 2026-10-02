package com.jdmmc.kurumamod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * 車をいじる画面の共通の見た目。<b>配色とタイルの描き方をここ 1 か所に置く。</b>
 *
 * <p>換装画面（{@link CarPartScreen}）とセッティング画面（{@link CarTuningScreen}）は
 * 構図を揃えてある——<b>画面を暗くせず、下端にタイルの列を置き、選んでいるものだけ
 * 黄色く一段高い</b>。同じ見た目を 2 か所に書くと、片方だけ直したつもりが揃わなくなる。</p>
 *
 * <p>寸法（タイルの幅や高さ）は<b>画面ごとに違ってよい</b>ので、ここには置かない。
 * 換装画面のタイルはホイールの絵が入るぶん背が高く、セッティングのタブは名前だけなので低い。</p>
 */
final class ScreenStyle {

    /** 選んでいるものの色。<b>90 年代の計器と同じ琥珀寄りの黄色</b>（{@code GaugeTheme} と揃えてある）。 */
    static final int ACCENT = 0xFFFFC83C;

    static final int TILE = 0xD0141414;
    static final int TILE_HOVER = 0xE0202020;
    static final int BORDER = 0x50FFFFFF;
    static final int BORDER_HOVER = 0xA0FFFFFF;

    static final int LABEL = 0xFFE8E8E8;
    /** 黄色の上に載せる文字。<b>白のままだと読めない。</b> */
    static final int LABEL_ON_ACCENT = 0xFF141414;
    static final int SUB = 0xFF8A8A8A;
    static final int HINT = 0xFFB0B0B0;

    /**
     * 一覧の 1 行の下敷き。
     *
     * <p><b>画面を暗くしない代わりに、文字の後ろにだけ敷く。</b>後ろが空でも木でも
     * 同じように読めて、なおかつ車は透けて見える濃さにしてある。</p>
     */
    static final int ROW = 0x66000000;
    static final int ROW_ALT = 0x8C000000;

    private ScreenStyle() {
    }

    /**
     * いま開いているのが<b>車をいじる画面</b>（換装・セッティング）か。
     *
     * <p>この 2 つは同じ扱いをする:</p>
     *
     * <ul>
     *   <li><b>車の HUD を引っ込める</b>……どちらも下端を使うので、出したままだとタイルや
     *       タブと重なる。開いているあいだは運転の入力も止まる（{@code CarClientDriver}）ので、
     *       そのあいだ計器を見せる意味もない</li>
     *   <li><b>カメラをショールームの構えにする</b>……{@link CarChaseCamera} が引いて、
     *       回転の中心を車のフロアまで下げる</li>
     * </ul>
     */
    static boolean isCarScreen() {
        Screen screen = Minecraft.getInstance().screen;
        return screen instanceof CarPartScreen || screen instanceof CarTuningScreen;
    }

    /**
     * タイルの箱。<b>選んでいるものは黄色い帯と黄色い枠になる。</b>
     *
     * <p>高さを呼ぶ側が決めるのは、<b>一段高くするぶんを外で足している</b>ため
     * （下端は揃えたまま上へ伸ばすので、列の底が波打たない）。</p>
     *
     * @param labelHeight 下端の黄色い帯の高さ。<b>高さと同じ値を渡せばタイル全体が黄色くなる</b>
     */
    static void tileBox(GuiGraphics graphics, int left, int top, int width, int height,
                        boolean selected, boolean hovered, int labelHeight) {
        int right = left + width;
        int bottom = top + height;
        graphics.fill(left, top, right, bottom, hovered ? TILE_HOVER : TILE);
        if (selected) {
            graphics.fill(left, bottom - Math.min(labelHeight, height), right, bottom, ACCENT);
        }
        graphics.renderOutline(left, top, width, height,
                selected ? ACCENT : hovered ? BORDER_HOVER : BORDER);
    }
}
