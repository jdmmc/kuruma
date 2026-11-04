package com.jdmmc.kurumamod.plate;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * プレートのテクスチャに字を焼き込む。<b>地の絵（パックのテクスチャ）の上に、欄ごとの字を描いた 1 枚を作る。</b>
 *
 * <p>字を別に描かずテクスチャへ焼くのは、プレートを<b>車体と同じ 1 枚の面</b>として扱えるため。
 * ゴーストの半透明・ヘッドライトの照り返し・ナンバー灯・シェーダーパックの影が、字にもそのまま効く。</p>
 *
 * <p>Minecraft に依存しない（{@code java.awt} だけ）。ゲームを起動せずに画像を書き出して確かめられる。</p>
 *
 * <h2>字の大きさ</h2>
 *
 * <p>字の<b>見えている高さ</b>（字形の外形）を枠の高さの {@link #FILL} 倍に合わせる。フォントの
 * 字の大きさ（em）で合わせると、フォントごとに数字の高さが違って枠からはみ出したり小さすぎたりする。
 * 横は {@link PlateLayout} が揃える（短ければ間を広げ、長ければ横に詰める）。</p>
 */
public final class PlatePainter {

    /** 字の見えている高さを枠の何倍にするか。枠いっぱいだと縁に触れて窮屈に見える。 */
    public static final float FILL = 0.9F;
    /** 焼くテクスチャの横の画素数の下限。地の絵が小さくても、字はこれ以上の細かさで描く。 */
    public static final int MIN_WIDTH = 1024;
    /** 横の画素数の上限。大きな地の絵をそのまま使うとテクスチャが重くなる。 */
    public static final int MAX_WIDTH = 2048;

    private PlatePainter() {
    }

    /**
     * 欄 1 つ。枠はテクスチャの上の位置（0〜1。左上が原点）。
     *
     * @param text     出す字（整えた後）
     * @param font     字のフォント（大きさは問わない。ここで決め直す）
     * @param fallback 字の無い文字に使うフォント。null ならそのまま {@code font}
     * @param argb     字の色
     * @param emboss   浮き出しの深さ（枠の高さに対する比）。0 なら平らな字
     * @param mono     等幅で並べるか。1 字ずつ「0」の幅のマスの中央に置く（{@link #SEPARATOR_CELL}）。
     *                 日本の一連番号の「・・ ・1」のように、点も数字と同じ間隔で並ぶ欄に使う
     */
    public record Field(String text, Font font, Font fallback, int argb, PlateLayout.Fit fit, float emboss,
                        boolean mono, double u0, double v0, double u1, double v1) {
    }

    /** 等幅の欄で、ハイフンと空白に割り当てるマスの幅（数字のマスに対する比）。実物も区切りは細い。 */
    public static final float SEPARATOR_CELL = 0.6F;

    /** 地の絵に字を描いた 1 枚。地の絵は描き換えない。 */
    public static BufferedImage paint(BufferedImage base, List<Field> fields) {
        int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, base.getWidth()));
        int height = Math.max(1, Math.round(width * (float) base.getHeight() / base.getWidth()));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(base, 0, 0, width, height, null);
            for (Field field : fields) {
                if (!field.text().isBlank()) {
                    drawField(graphics, field, width, height);
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void drawField(Graphics2D graphics, Field field, int width, int height) {
        double left = field.u0() * width;
        double top = field.v0() * height;
        double boxWidth = (field.u1() - field.u0()) * width;
        double boxHeight = (field.v1() - field.v0()) * height;
        if (boxWidth <= 1.0 || boxHeight <= 1.0) {
            return;
        }
        FontRenderContext context = graphics.getFontRenderContext();
        int[] codePoints = field.text().codePoints().toArray();

        // 字ごとにフォントを選ぶ（字の無い文字だけ代わりのフォント）。大きさは 100 で測ってから決め直す
        Font[] fonts = new Font[codePoints.length];
        for (int i = 0; i < codePoints.length; i++) {
            Font chosen = field.font();
            if (!chosen.canDisplay(codePoints[i]) && field.fallback() != null) {
                chosen = field.fallback();
            }
            fonts[i] = chosen.deriveFont(100.0F);
        }

        // 見えている高さ（字形の外形の上下）を測る
        double inkTop = Double.POSITIVE_INFINITY;
        double inkBottom = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < codePoints.length; i++) {
            if (Character.isWhitespace(codePoints[i])) {
                continue;
            }
            Rectangle2D bounds = glyph(fonts[i], context, codePoints[i]).getVisualBounds();
            if (bounds.getHeight() <= 0.0) {
                continue;
            }
            inkTop = Math.min(inkTop, bounds.getMinY());
            inkBottom = Math.max(inkBottom, bounds.getMaxY());
        }
        if (!(inkBottom > inkTop)) {
            return;
        }
        double scale = boxHeight * FILL / (inkBottom - inkTop);

        float[] advances = new float[codePoints.length];
        // マスの中で字をずらす量（等幅のとき、字をマスの中央へ）
        float[] insets = new float[codePoints.length];
        GlyphVector[] glyphs = new GlyphVector[codePoints.length];
        float cell = field.mono()
                ? (float) glyph(field.font().deriveFont((float) (100.0 * scale)), context, '0')
                .getGlyphMetrics(0).getAdvanceX()
                : 0.0F;
        for (int i = 0; i < codePoints.length; i++) {
            fonts[i] = fonts[i].deriveFont((float) (100.0 * scale));
            glyphs[i] = glyph(fonts[i], context, codePoints[i]);
            float advance = (float) glyphs[i].getGlyphMetrics(0).getAdvanceX();
            if (field.mono()) {
                boolean separator = codePoints[i] == '-' || Character.isWhitespace(codePoints[i]);
                advances[i] = separator ? cell * SEPARATOR_CELL : cell;
                Rectangle2D ink = glyphs[i].getVisualBounds();
                // 見えている形をマスの中央へ（送り幅の中央ではない。全角の「・」は送り幅が広く、片寄る）
                insets[i] = ink.getWidth() > 0.0
                        ? (float) ((advances[i] - ink.getWidth()) * 0.5 - ink.getMinX())
                        : 0.0F;
            } else {
                advances[i] = advance;
            }
        }
        PlateLayout.Result layout = PlateLayout.layout(advances, (float) boxWidth, field.fit());

        // 縦は見えている高さを枠の中央へ
        double inkHeight = (inkBottom - inkTop) * scale;
        double baseline = top + (boxHeight - inkHeight) * 0.5 - inkTop * scale;

        double emboss = field.emboss() * boxHeight;
        Color color = new Color(field.argb(), true);
        if (emboss > 0.0) {
            // 型押しの影（右下）と照り（左上）。字の色の濃淡で作る
            drawGlyphs(graphics, glyphs, advances, insets, layout, left, baseline, emboss, emboss, new Color(0, 0, 0, 90));
            drawGlyphs(graphics, glyphs, advances, insets, layout, left, baseline, -emboss * 0.6, -emboss * 0.6,
                    new Color(255, 255, 255, 70));
        }
        drawGlyphs(graphics, glyphs, advances, insets, layout, left, baseline, 0.0, 0.0, color);
    }

    private static void drawGlyphs(Graphics2D graphics, GlyphVector[] glyphs, float[] advances, float[] insets,
                                   PlateLayout.Result layout, double left, double baseline,
                                   double dx, double dy, Color color) {
        graphics.setColor(color);
        double x = left + layout.startX();
        for (int i = 0; i < glyphs.length; i++) {
            AffineTransform saved = graphics.getTransform();
            graphics.translate(x + dx, baseline + dy);
            graphics.scale(layout.scaleX(), 1.0);
            graphics.translate(insets[i], 0.0);
            graphics.fill(glyphs[i].getOutline());
            graphics.setTransform(saved);
            x += advances[i] * layout.scaleX() + layout.gap();
        }
    }

    private static GlyphVector glyph(Font font, FontRenderContext context, int codePoint) {
        return font.createGlyphVector(context, new String(Character.toChars(codePoint)));
    }
}
