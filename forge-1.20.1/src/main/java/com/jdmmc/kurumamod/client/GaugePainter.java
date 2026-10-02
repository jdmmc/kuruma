package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

/**
 * 円形メーターを描くための図形。<b>テクスチャを使わず、四角形を積んで描く。</b>
 *
 * <p>画像で用意しないのは、<b>目盛りの上限が車ごとに変わる</b>ため。最高速もレブリミットも
 * セッティングとカーパックで動くので、文字盤は毎フレーム諸元から組み立てるしかない。</p>
 *
 * <p><b>{@code RenderType.gui()} ではなく {@link KurumaRenderTypes#GUI_SHAPE} を使う。</b>
 * バニラの gui はカリング有りなので、四角形の頂点を積む向き（表裏）を間違えると
 * <b>何も描かれない</b>。円弧のセグメントは向きが角度によって変わるように見えて紛らわしいので、
 * カリングを外したものを別に用意してある。</p>
 *
 * <p>角度は<b>度</b>で、x 軸の正方向が 0、そこから<b>時計回り</b>に増える
 * （GUI の Y は下向きなので、数学の向きとは逆に見える）。真上は 270 度。</p>
 */
final class GaugePainter {

    /** 円弧を何度ごとに折るか。細かくすると滑らかになるが四角形が増える。 */
    private static final float ARC_STEP_DEGREES = 4.0F;

    private GaugePainter() {
    }

    /**
     * 円弧の帯。{@code innerRadius} を 0 にすれば扇形、開始と終了を 0/360 にすれば円になる。
     */
    static void arc(GuiGraphics graphics, float centerX, float centerY,
                    float innerRadius, float outerRadius,
                    float fromDegrees, float toDegrees, int color) {
        if (toDegrees < fromDegrees) {
            float swap = fromDegrees;
            fromDegrees = toDegrees;
            toDegrees = swap;
        }
        int steps = Math.max(1, (int) Math.ceil((toDegrees - fromDegrees) / ARC_STEP_DEGREES));
        float step = (toDegrees - fromDegrees) / steps;

        VertexConsumer consumer = consumer(graphics);
        Matrix4f matrix = graphics.pose().last().pose();
        for (int i = 0; i < steps; i++) {
            float a0 = fromDegrees + step * i;
            float a1 = a0 + step;
            float cos0 = cos(a0), sin0 = sin(a0);
            float cos1 = cos(a1), sin1 = sin(a1);
            quad(consumer, matrix, color,
                    centerX + cos0 * innerRadius, centerY + sin0 * innerRadius,
                    centerX + cos1 * innerRadius, centerY + sin1 * innerRadius,
                    centerX + cos1 * outerRadius, centerY + sin1 * outerRadius,
                    centerX + cos0 * outerRadius, centerY + sin0 * outerRadius);
        }
    }

    /**
     * 半径方向に伸びる棒。目盛りの線がこれ。
     *
     * @param width 棒の幅
     */
    static void radialBar(GuiGraphics graphics, float centerX, float centerY,
                          float innerRadius, float outerRadius, float degrees,
                          float width, int color) {
        float cos = cos(degrees), sin = sin(degrees);
        // 半径方向に直交する向き。そのまま棒の太さの向きになる
        float nx = -sin * width * 0.5F;
        float ny = cos * width * 0.5F;
        float ix = centerX + cos * innerRadius, iy = centerY + sin * innerRadius;
        float ox = centerX + cos * outerRadius, oy = centerY + sin * outerRadius;
        quad(graphics, color,
                ix - nx, iy - ny,
                ix + nx, iy + ny,
                ox + nx, oy + ny,
                ox - nx, oy - ny);
    }

    /**
     * 針。根本が太く先が尖る。<b>先を尖らせるのは、どの目盛りを指しているかを読ませるため。</b>
     */
    static void needle(GuiGraphics graphics, float centerX, float centerY,
                       float innerRadius, float outerRadius, float degrees,
                       float baseWidth, int color) {
        float cos = cos(degrees), sin = sin(degrees);
        float nx = -sin * baseWidth * 0.5F;
        float ny = cos * baseWidth * 0.5F;
        float ix = centerX + cos * innerRadius, iy = centerY + sin * innerRadius;
        float ox = centerX + cos * outerRadius, oy = centerY + sin * outerRadius;
        // 先端は 1 点。四角形なので同じ点を 2 回積む（縮退四角形）
        quad(graphics, color,
                ix - nx, iy - ny,
                ix + nx, iy + ny,
                ox, oy,
                ox, oy);
    }

    /** 軸に平行な長方形。{@link GuiGraphics#fill} と違って小数で置ける。 */
    static void rect(GuiGraphics graphics, float left, float top, float right, float bottom, int color) {
        quad(graphics, color, left, bottom, right, bottom, right, top, left, top);
    }

    /** 四隅を指定する四角形。頂点の並びは {@link GuiGraphics#fill} と同じ（左下→右下→右上→左上）。 */
    static void quad(GuiGraphics graphics, int color,
                     float x0, float y0, float x1, float y1,
                     float x2, float y2, float x3, float y3) {
        quad(consumer(graphics), graphics.pose().last().pose(), color,
                x0, y0, x1, y1, x2, y2, x3, y3);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix, int color,
                             float x0, float y0, float x1, float y1,
                             float x2, float y2, float x3, float y3) {
        int a = color >>> 24, r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        consumer.vertex(matrix, x0, y0, 0.0F).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x1, y1, 0.0F).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x2, y2, 0.0F).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x3, y3, 0.0F).color(r, g, b, a).endVertex();
    }

    private static VertexConsumer consumer(GuiGraphics graphics) {
        return graphics.bufferSource().getBuffer(KurumaRenderTypes.GUI_SHAPE);
    }

    /**
     * 積んだ図形を実際に描く。
     *
     * <p><b>文字を重ねる前に必ず呼ぶこと。</b>{@code GuiGraphics} は種類ごとに頂点を溜めてから
     * まとめて流すので、呼ばずに {@code drawString} すると<b>後から積んだ図形が文字を覆う</b>。</p>
     */
    static void flush(GuiGraphics graphics) {
        graphics.flush();
    }

    private static float cos(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    private static float sin(float degrees) {
        return (float) Math.sin(Math.toRadians(degrees));
    }
}
