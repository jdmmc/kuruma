package com.jdmmc.kurumamod.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

/**
 * 四角形を積む口（{@code McUiGraphics#fillQuad}）。円弧・針などの形は core の {@code GaugeShapes} が
 * この上に組み立てる。<b>テクスチャを使わず、四角形を積んで描く。</b>
 *
 * <p>画像で用意しないのは、<b>目盛りの上限が車ごとに変わる</b>ため。最高速もレブリミットも
 * セッティングとカーパックで動くので、文字盤は毎フレーム諸元から組み立てるしかない。</p>
 *
 * <p><b>{@code RenderType.gui()} ではなく {@link KurumaRenderTypes#GUI_SHAPE} を使う。</b>
 * バニラの gui はカリング有りなので、四角形の頂点を積む向き（表裏）を間違えると
 * <b>何も描かれない</b>。円弧のセグメントは向きが角度によって変わるように見えて紛らわしいので、
 * カリングを外したものを別に用意してある。</p>
 *
 */
public final class GaugePainter {

    private GaugePainter() {
    }

    /** 四隅を指定する四角形。頂点の並びは {@link GuiGraphics#fill} と同じ（左下→右下→右上→左上）。 */
    public static void quad(GuiGraphics graphics, int color,
                     float x0, float y0, float x1, float y1,
                     float x2, float y2, float x3, float y3) {
        quad(consumer(graphics), graphics.pose().last().pose(), color,
                x0, y0, x1, y1, x2, y2, x3, y3);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix, int color,
                             float x0, float y0, float x1, float y1,
                             float x2, float y2, float x3, float y3) {
        int a = color >>> 24, r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        consumer.addVertex(matrix, x0, y0, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2, y2, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(matrix, x3, y3, 0.0F).setColor(r, g, b, a);
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
    public static void flush(GuiGraphics graphics) {
        graphics.flush();
    }

    private static float cos(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    private static float sin(float degrees) {
        return (float) Math.sin(Math.toRadians(degrees));
    }
}
