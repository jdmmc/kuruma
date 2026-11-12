package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.mixin.GuiGraphicsExtractorAccessor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

/**
 * 四角形を積む口（{@code McUiGraphics#fillQuad}）。円弧・針などの形は core の {@code GaugeShapes} が
 * この上に組み立てる。<b>テクスチャを使わず、四角形を積んで描く。</b>
 *
 * <p>画像で用意しないのは、<b>目盛りの上限が車ごとに変わる</b>ため。最高速もレブリミットも
 * セッティングとカーパックで動くので、文字盤は毎フレーム諸元から組み立てるしかない。</p>
 *
 * <p><b>26.3 では GUI の要素（{@link GuiElementRenderState}）として足し、パイプラインはバニラの
 * {@link RenderPipelines#GUI} をそのまま使う。</b>このパイプラインはカリング有りなので、四角形の頂点を積む
 * 向き（表裏）を間違えると<b>何も描かれない</b>。そこで画面上の座標に直したあとで向きを測り、
 * {@link GuiGraphicsExtractor#fill} と同じ向きにそろえてから積む（1.21.1 まではカリングを外した
 * 自前の RenderType を使っていた）。自前のパイプラインは登録の仕組みがローダーごとに違うので持たない。</p>
 *
 * <p>重なりの順番は GUI の描画状態が足した順に決める（後から足したものほど上）ので、
 * 文字を重ねる前に溜まった図形を流す必要はもう無い（{@link #flush} は何もしない）。</p>
 *
 */
public final class GaugePainter {

    private GaugePainter() {
    }

    /** 四隅を指定する四角形。頂点の並びは {@link GuiGraphicsExtractor#fill} と同じ（左下→右下→右上→左上）。 */
    public static void quad(GuiGraphicsExtractor graphics, int color,
                     float x0, float y0, float x1, float y1,
                     float x2, float y2, float x3, float y3) {
        submit(graphics, color, new float[]{x0, y0, x1, y1, x2, y2, x3, y3});
    }

    /**
     * 四角形の並び（1 つにつき 8 個の座標）を、いまの変換で画面の座標に直して GUI の要素として足す。
     * 切り抜き（{@code enableScissor}）は文字と同じものを使う。
     */
    private static void submit(GuiGraphicsExtractor graphics, int color, float[] local) {
        Matrix3x2fc pose = graphics.pose();
        float[] points = new float[local.length];
        Vector2f scratch = new Vector2f();
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < local.length; i += 2) {
            pose.transformPosition(local[i], local[i + 1], scratch);
            points[i] = scratch.x;
            points[i + 1] = scratch.y;
            minX = Math.min(minX, scratch.x);
            minY = Math.min(minY, scratch.y);
            maxX = Math.max(maxX, scratch.x);
            maxY = Math.max(maxY, scratch.y);
        }
        for (int q = 0; q < points.length; q += 8) {
            faceFront(points, q);
        }
        int left = (int) Math.floor(minX), top = (int) Math.floor(minY);
        ScreenRectangle bounds = new ScreenRectangle(left, top,
                Math.max(1, (int) Math.ceil(maxX) - left), Math.max(1, (int) Math.ceil(maxY) - top));
        ScreenRectangle scissor = graphics.textRenderer().defaultParameters().scissor();
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
            if (bounds == null) {
                return; // 切り抜きの外。描くものが無い
            }
        }
        ((GuiGraphicsExtractorAccessor) graphics).kuruma$getGuiRenderState()
                .addGuiElement(new Shape(points, color, scissor, bounds));
    }

    /**
     * 四角形の頂点の並びを {@link GuiGraphicsExtractor#fill} と同じ向きにそろえる。
     *
     * <p>fill は（Y が下向きの画面座標で）右下→右上→左上→左下の順に積み、このとき符号付き面積
     * （靴ひもの公式）は負になる。正なら 1 番目と 3 番目を入れ替えて向きを裏返す。
     * 先端が 1 点に縮んだ四角形（針）でも、残りの 3 点で面積が出るのでそのまま測れる。</p>
     */
    private static void faceFront(float[] p, int o) {
        float area = 0.0F;
        for (int i = 0; i < 4; i++) {
            int a = o + i * 2;
            int b = o + ((i + 1) % 4) * 2;
            area += p[a] * p[b + 1] - p[b] * p[a + 1];
        }
        if (area > 0.0F) {
            float x = p[o + 2], y = p[o + 3];
            p[o + 2] = p[o + 6];
            p[o + 3] = p[o + 7];
            p[o + 6] = x;
            p[o + 7] = y;
        }
    }

    /** 画面の座標に直した四角形の並び。色は 1 色。 */
    private record Shape(float[] points, int color, @Nullable ScreenRectangle scissorArea,
                         @Nullable ScreenRectangle bounds) implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer vertexConsumer) {
            for (int i = 0; i < points.length; i += 2) {
                vertexConsumer.addVertex(points[i], points[i + 1], 0.0F).setColor(color);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }

    /**
     * 1.21.1 までは、積んだ図形を文字より先に描かせるために呼んでいた。
     *
     * <p>26.3 の GUI は足した順に重なりを決めるので、呼ばなくても文字が図形の上に来る。
     * 呼び出し側をそろえておくために口だけ残してある。</p>
     */
    public static void flush(GuiGraphicsExtractor graphics) {
    }

    private static float cos(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    private static float sin(float degrees) {
        return (float) Math.sin(Math.toRadians(degrees));
    }
}
