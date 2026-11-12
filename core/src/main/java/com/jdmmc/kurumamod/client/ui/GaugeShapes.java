package com.jdmmc.kurumamod.client.ui;

/**
 * 円弧・棒・針・四角形（小数で置ける）を描く道具。メーター（{@code CarGaugeHud}）と、換装画面の照準やタイヤの絵に使う。
 * 版の {@code GaugePainter}（メーターが使う）と同じ形の描き方を、{@link UiGraphics#fillQuad} の上に書いたもの。
 *
 * <p><b>文字を重ねる前に {@link #flush} を呼ぶこと。</b>図形は溜めてからまとめて描かれる。</p>
 */
public final class GaugeShapes {

    /** 円弧を何度ごとに折るか。細かくすると滑らかになるが四角形が増える。 */
    private static final float ARC_STEP_DEGREES = 4.0F;

    private GaugeShapes() {
    }

    /** 円弧の帯。{@code innerRadius} を 0 にすれば扇形、開始と終了を 0/360 にすれば円になる。 */
    public static void arc(UiGraphics graphics, float centerX, float centerY, float innerRadius, float outerRadius,
                           float fromDegrees, float toDegrees, int color) {
        if (toDegrees < fromDegrees) {
            float swap = fromDegrees;
            fromDegrees = toDegrees;
            toDegrees = swap;
        }
        int steps = Math.max(1, (int) Math.ceil((toDegrees - fromDegrees) / ARC_STEP_DEGREES));
        float step = (toDegrees - fromDegrees) / steps;
        for (int i = 0; i < steps; i++) {
            float a0 = fromDegrees + step * i;
            float a1 = a0 + step;
            float cos0 = cos(a0), sin0 = sin(a0);
            float cos1 = cos(a1), sin1 = sin(a1);
            graphics.fillQuad(color,
                    centerX + cos0 * innerRadius, centerY + sin0 * innerRadius,
                    centerX + cos1 * innerRadius, centerY + sin1 * innerRadius,
                    centerX + cos1 * outerRadius, centerY + sin1 * outerRadius,
                    centerX + cos0 * outerRadius, centerY + sin0 * outerRadius);
        }
    }

    /** 半径方向に伸びる棒。 */
    public static void radialBar(UiGraphics graphics, float centerX, float centerY, float innerRadius,
                                 float outerRadius, float degrees, float width, int color) {
        float cos = cos(degrees), sin = sin(degrees);
        // 半径方向に直交する向き。そのまま棒の太さの向きになる
        float nx = -sin * width * 0.5F;
        float ny = cos * width * 0.5F;
        float ix = centerX + cos * innerRadius, iy = centerY + sin * innerRadius;
        float ox = centerX + cos * outerRadius, oy = centerY + sin * outerRadius;
        graphics.fillQuad(color, ix - nx, iy - ny, ix + nx, iy + ny, ox + nx, oy + ny, ox - nx, oy - ny);
    }

    /** 針。根元は {@code baseWidth} の幅、先端は 1 点。 */
    public static void needle(UiGraphics graphics, float centerX, float centerY, float innerRadius,
                              float outerRadius, float degrees, float baseWidth, int color) {
        float cos = cos(degrees), sin = sin(degrees);
        float nx = -sin * baseWidth * 0.5F;
        float ny = cos * baseWidth * 0.5F;
        float ix = centerX + cos * innerRadius, iy = centerY + sin * innerRadius;
        float ox = centerX + cos * outerRadius, oy = centerY + sin * outerRadius;
        // 先端は 1 点。四角形なので同じ点を 2 回積む（縮退四角形）
        graphics.fillQuad(color, ix - nx, iy - ny, ix + nx, iy + ny, ox, oy, ox, oy);
    }

    /** 軸に平行な長方形。 */
    public static void rect(UiGraphics graphics, float left, float top, float right, float bottom, int color) {
        graphics.fillQuad(color, left, bottom, right, bottom, right, top, left, top);
    }

    public static void flush(UiGraphics graphics) {
        graphics.flush();
    }

    private static float cos(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    private static float sin(float degrees) {
        return (float) Math.sin(Math.toRadians(degrees));
    }
}
