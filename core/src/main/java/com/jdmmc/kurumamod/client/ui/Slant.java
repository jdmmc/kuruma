package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * レース HUD（中継グラフィックの見た目）を描く道具。<b>斜めの帯・丸・斜線・拡大した文字。</b>
 *
 * <p>斜めの帯は四角形を行列で横にずらして（せん断して）描く（{@link UiGraphics#shearX}）。文字はずらさない——
 * 帯だけを傾け、文字はまっすぐ載せるのが中継グラフィックの作法。</p>
 */
public final class Slant {

    /** 帯の傾き（tan 12 度）。上の辺が右へ、下の辺が左へずれる。 */
    public static final float BAND = 0.2126F;
    /** 速度の帯などの、より強い傾き（tan 20 度）。 */
    public static final float STEEP = 0.364F;

    private Slant() {
    }

    /** 傾けた帯。{@code (x, y)} は傾ける前の左上。上下の中ほどを軸に傾ける。 */
    public static void fill(UiGraphics graphics, int x, int y, int width, int height, int color) {
        fill(graphics, x, y, width, height, color, BAND);
    }

    public static void fill(UiGraphics graphics, int x, int y, int width, int height, int color, float slant) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.pushPose();
        graphics.translate(x, y + height / 2.0F);
        graphics.shearX(slant);
        int top = -height / 2;
        graphics.fill(0, top, width, top + height, color);
        graphics.popPose();
    }

    /** 文字（影なし）。 */
    public static void text(UiGraphics graphics, UiFont font, RaceText text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
    }

    public static void text(UiGraphics graphics, UiFont font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
    }

    /** 拡大した文字。{@code (x, y)} は拡大した後の左上。 */
    public static void scaled(UiGraphics graphics, UiFont font, String text, float x, float y, float scale, int color) {
        graphics.pushPose();
        graphics.translate(x, y);
        graphics.scale(scale, scale);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.popPose();
    }

    public static void scaled(UiGraphics graphics, UiFont font, RaceText text, float x, float y, float scale, int color) {
        graphics.pushPose();
        graphics.translate(x, y);
        graphics.scale(scale, scale);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.popPose();
    }

    /**
     * 幅に収まらない文字は、<b>左右に流して全部見せる</b>（G セッティングのスライダーの文字と同じ動き）。
     * 収まるならそのまま描く。切り詰めて「…」にすると、名前や理由の後ろが読めなくなる。
     */
    public static void fit(UiGraphics graphics, UiFont font, RaceText text, int x, int y, int maxWidth, int color) {
        fit(graphics, font, text.getString(), x, y, maxWidth, color);
    }

    public static void fit(UiGraphics graphics, UiFont font, String text, int x, int y, int maxWidth, int color) {
        int width = font.width(text);
        if (maxWidth <= 0 || width <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        // バニラの流れる文字（AbstractWidget の流し方）と同じ: 端で止まり、ゆっくり往復する
        int overflow = width - maxWidth;
        double seconds = System.nanoTime() / 1.0e9;
        double period = Math.max(overflow * 0.5, 3.0);
        double phase = Math.sin(Math.PI / 2.0 * Math.cos(Math.PI * 2.0 * seconds / period)) / 2.0 + 0.5;
        int offset = (int) Math.round(phase * overflow);
        graphics.enableScissor(x, y - 1, x + maxWidth, y + font.lineHeight);
        graphics.drawString(font, text, x - offset, y, color, false);
        graphics.disableScissor();
    }

    /** 拡大した文字の幅。 */
    public static int width(UiFont font, String text, float scale) {
        return Math.round(font.width(text) * scale);
    }

    public static int width(UiFont font, RaceText text, float scale) {
        return Math.round(font.width(text) * scale);
    }

    /** 塗りつぶした丸。横の線を積んで描く。 */
    public static void circle(UiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy < radius; dy++) {
            double mid = dy + 0.5;
            int half = (int) Math.round(Math.sqrt(Math.max(0.0, radius * radius - mid * mid)));
            graphics.fill(centerX - half, centerY + dy, centerX + half, centerY + dy + 1, color);
        }
    }

    /**
     * 斜線で塞いだ範囲（G セッティングの「ここから先は決まりの外」）。
     *
     * <p>薄く塗った上に、濃い斜めの線を等間隔で引く。範囲の外へはみ出さないよう切り抜いて描く。</p>
     */
    public static void hatch(UiGraphics graphics, int x0, int y0, int x1, int y1, int strong, int weak) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        graphics.fill(x0, y0, x1, y1, weak);
        graphics.enableScissor(x0, y0, x1, y1);
        int height = y1 - y0;
        float slant = 1.0F;
        for (int x = x0 - height; x < x1 + height; x += 5) {
            fill(graphics, x, y0, 2, height, strong, slant);
        }
        graphics.disableScissor();
    }
}
