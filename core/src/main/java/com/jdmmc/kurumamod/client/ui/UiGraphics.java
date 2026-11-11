package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * 画面に描く道具。バニラの {@code GuiGraphics}（26.3 は {@code GuiGraphicsExtractor}）の代わり。
 * 版ごとの {@code McUiGraphics} が中身を持つ。
 *
 * <p>レースの画面と HUD が使うものだけを持つ: 塗り・文字・枠・切り抜き・行列（ずらす・拡げる・せん断）。
 * 名前はバニラに揃えてある。26.3 は描いた順で重なる（Z が無い）ので、どの版でも<b>下に敷くものから描く</b>こと。</p>
 */
public abstract class UiGraphics {

    /** {@code [x0, x1) × [y0, y1)} を塗る。色は ARGB。 */
    public abstract void fill(int x0, int y0, int x1, int y1, int color);

    public abstract void drawString(UiFont font, String text, int x, int y, int color, boolean shadow);

    public abstract void drawString(UiFont font, RaceText text, int x, int y, int color, boolean shadow);

    /** 影付きの文字。 */
    public void drawString(UiFont font, String text, int x, int y, int color) {
        drawString(font, text, x, y, color, true);
    }

    public void drawString(UiFont font, RaceText text, int x, int y, int color) {
        drawString(font, text, x, y, color, true);
    }

    /** 中央揃えの影付きの文字。 */
    public void drawCenteredString(UiFont font, RaceText text, int centerX, int y, int color) {
        drawString(font, text, centerX - font.width(text) / 2, y, color, true);
    }

    public void drawCenteredString(UiFont font, String text, int centerX, int y, int color) {
        drawString(font, text, centerX - font.width(text) / 2, y, color, true);
    }

    /** 1 ピクセルの枠。 */
    public void renderOutline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color);
        fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y + 1, x + 1, y + height - 1, color);
        fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** ここから先は {@code [x0, x1) × [y0, y1)} の中だけに描く（{@link #disableScissor} まで）。 */
    public abstract void enableScissor(int x0, int y0, int x1, int y1);

    public abstract void disableScissor();

    /** 行列を積む。{@link #popPose} と組で使う。 */
    public abstract void pushPose();

    public abstract void popPose();

    public abstract void translate(float x, float y);

    public abstract void scale(float x, float y);

    /** 横へずらす（せん断）。{@code x' = x - slant·y}（上の辺が右へ、下の辺が左へ）。 */
    public abstract void shearX(float slant);

    /**
     * {@code [left, right)} に収まらない文字を左右に流して描く（バニラのボタンの字の流し方）。
     * 収まるなら縦の中ほどに描く。
     */
    public abstract void renderScrollingString(UiFont font, RaceText text, int left, int top, int right, int bottom,
                                               int color);
}
