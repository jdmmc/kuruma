package com.jdmmc.kurumamod.client;

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
 * 円形メーターを描くための図形。<b>テクスチャを使わず、四角形を積んで描く。</b>
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
    static void arc(GuiGraphicsExtractor graphics, float centerX, float centerY,
                    float innerRadius, float outerRadius,
                    float fromDegrees, float toDegrees, int color) {
        if (toDegrees < fromDegrees) {
            float swap = fromDegrees;
            fromDegrees = toDegrees;
            toDegrees = swap;
        }
        int steps = Math.max(1, (int) Math.ceil((toDegrees - fromDegrees) / ARC_STEP_DEGREES));
        float step = (toDegrees - fromDegrees) / steps;

        float[] points = new float[steps * 8];
        for (int i = 0; i < steps; i++) {
            float a0 = fromDegrees + step * i;
            float a1 = a0 + step;
            float cos0 = cos(a0), sin0 = sin(a0);
            float cos1 = cos(a1), sin1 = sin(a1);
            int o = i * 8;
            points[o] = centerX + cos0 * innerRadius;
            points[o + 1] = centerY + sin0 * innerRadius;
            points[o + 2] = centerX + cos1 * innerRadius;
            points[o + 3] = centerY + sin1 * innerRadius;
            points[o + 4] = centerX + cos1 * outerRadius;
            points[o + 5] = centerY + sin1 * outerRadius;
            points[o + 6] = centerX + cos0 * outerRadius;
            points[o + 7] = centerY + sin0 * outerRadius;
        }
        submit(graphics, color, points);
    }

    /**
     * 半径方向に伸びる棒。目盛りの線がこれ。
     *
     * @param width 棒の幅
     */
    static void radialBar(GuiGraphicsExtractor graphics, float centerX, float centerY,
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
    static void needle(GuiGraphicsExtractor graphics, float centerX, float centerY,
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

    /** 軸に平行な長方形。{@link GuiGraphicsExtractor#fill} と違って小数で置ける。 */
    static void rect(GuiGraphicsExtractor graphics, float left, float top, float right, float bottom, int color) {
        quad(graphics, color, left, bottom, right, bottom, right, top, left, top);
    }

    /** 四隅を指定する四角形。頂点の並びは {@link GuiGraphicsExtractor#fill} と同じ（左下→右下→右上→左上）。 */
    static void quad(GuiGraphicsExtractor graphics, int color,
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
    static void flush(GuiGraphicsExtractor graphics) {
    }

    private static float cos(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    private static float sin(float degrees) {
        return (float) Math.sin(Math.toRadians(degrees));
    }
}
