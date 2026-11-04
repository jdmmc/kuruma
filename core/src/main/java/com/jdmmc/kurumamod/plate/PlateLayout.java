package com.jdmmc.kurumamod.plate;

/**
 * 欄の枠に字を並べる計算。<b>字の数で幅が変わるので、枠に合わせて広げたり詰めたりする。</b>
 *
 * <p>実物のナンバーは、2 文字の地名（品川）なら字の間を広げて枠いっぱいに並べ、4 文字（相模原など）なら
 * 字を細くして詰める。これを欄ごとの揃え方（{@link Fit}）で選べるようにしてある。</p>
 *
 * <p>Minecraft に依存しない。字の幅はフォントから測った値を渡してもらう。</p>
 */
public final class PlateLayout {

    /** 横に詰めるのはここまで。これより細くすると読めない。入らない長さは欄の上限で止める。 */
    public static final float MIN_SCALE_X = 0.5F;
    /** {@link Fit#JUSTIFY} で字の間を広げる上限（字の平均の幅に対する比）。 */
    public static final float MAX_SPREAD = 0.6F;

    private PlateLayout() {
    }

    /** 揃え方。 */
    public enum Fit {
        /** 短ければ字の間を広げて枠の幅に揃える。長ければ横だけ縮める（地名・分類番号）。 */
        JUSTIFY,
        /** 収まるうちはそのまま中央。はみ出すときだけ横に縮める（一連番号・本文）。 */
        CONDENSE,
        /** {@link #CONDENSE} と同じだが、縮めるのも {@link #MIN_SCALE_X} まで。小さな字向け。 */
        CENTER;

        /** JSON の名前から。知らない名前は {@link #CONDENSE}。 */
        public static Fit byName(String name) {
            if (name == null) {
                return CONDENSE;
            }
            return switch (name) {
                case "justify" -> JUSTIFY;
                case "center" -> CENTER;
                default -> CONDENSE;
            };
        }
    }

    /**
     * 並べた結果。字 {@code i} の左端は {@code startX + i * gap + (それより前の字の幅の和) * scaleX}。
     *
     * @param scaleX 字の横の倍率（1 なら元の形）
     * @param gap    字と字の間に足す幅（枠の単位）
     * @param startX 1 字目の左端（枠の左端から）
     */
    public record Result(float scaleX, float gap, float startX) {
    }

    /**
     * 並べる。
     *
     * @param advances 字ごとの送り幅（枠と同じ単位、倍率 1 のとき）
     * @param boxWidth 枠の幅
     */
    public static Result layout(float[] advances, float boxWidth, Fit fit) {
        float natural = 0.0F;
        for (float advance : advances) {
            natural += advance;
        }
        if (advances.length == 0 || natural <= 0.0F || boxWidth <= 0.0F) {
            return new Result(1.0F, 0.0F, 0.0F);
        }
        if (natural > boxWidth) {
            // はみ出す。横だけ縮めて枠いっぱいに（下限より細くはしない。そのときは中央からはみ出す）
            float scale = Math.max(MIN_SCALE_X, boxWidth / natural);
            return new Result(scale, 0.0F, (boxWidth - natural * scale) * 0.5F);
        }
        if (fit == Fit.JUSTIFY && advances.length > 1) {
            // 余りを字の間に配る。広げすぎると間が抜けるので上限を置き、残りは中央へ寄せる
            float average = natural / advances.length;
            float gap = Math.min((boxWidth - natural) / (advances.length - 1), average * MAX_SPREAD);
            float used = natural + gap * (advances.length - 1);
            return new Result(1.0F, gap, (boxWidth - used) * 0.5F);
        }
        return new Result(1.0F, 0.0F, (boxWidth - natural) * 0.5F);
    }
}
