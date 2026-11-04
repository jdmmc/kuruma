package com.jdmmc.kurumamod.plate;

import java.util.ArrayList;
import java.util.List;

/**
 * 欄に使える字。<b>範囲・1 字・名前の 3 つを並べて書く。</b>
 *
 * <pre>
 * "chars": ["0-9", "A-Z", " ", "-"]     // 数字・英大文字・空白・ハイフン
 * "chars": ["hiragana"]                  // ひらがな
 * </pre>
 *
 * <p>名前で書けるのは {@code hiragana}・{@code katakana}・{@code kanji}・{@code latin}（英字の大小）・
 * {@code digits}。3 字で真ん中が {@code -} なら範囲、それ以外の 1 字はその字だけ。
 * <b>何も書かなければどの字でもよい</b>（制御文字と書式記号 {@code §} だけはどの欄でも弾く）。</p>
 *
 * <p>読めない書き方は、その項目だけ捨てる（{@link #parse} が返す警告を呼ぶ側がログに出す）。</p>
 */
public final class PlateChars {

    /** どの字でもよい。 */
    public static final PlateChars ANY = new PlateChars(List.of());

    /** {@code [from, to]} の組。両端を含む。 */
    private final List<int[]> ranges;

    private PlateChars(List<int[]> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    /** どの字でもよいか。 */
    public boolean isAny() {
        return ranges.isEmpty();
    }

    /** その字を使えるか。 */
    public boolean allows(int codePoint) {
        if (ranges.isEmpty()) {
            return true;
        }
        for (int[] range : ranges) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** 書き出す形（通信で送る）。範囲の組を平らに並べたもの。 */
    public int[] toArray() {
        int[] flat = new int[ranges.size() * 2];
        for (int i = 0; i < ranges.size(); i++) {
            flat[i * 2] = ranges.get(i)[0];
            flat[i * 2 + 1] = ranges.get(i)[1];
        }
        return flat;
    }

    /** {@link #toArray()} の逆。 */
    public static PlateChars fromArray(int[] flat) {
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i + 1 < flat.length; i += 2) {
            ranges.add(new int[]{flat[i], flat[i + 1]});
        }
        return ranges.isEmpty() ? ANY : new PlateChars(ranges);
    }

    /**
     * JSON の配列（文字列の並び）から読む。
     *
     * @param warnings 読めなかった項目をここへ足す（呼ぶ側がログに出す）
     */
    public static PlateChars parse(List<String> items, List<String> warnings) {
        List<int[]> ranges = new ArrayList<>();
        for (String item : items) {
            int[] named = named(item);
            if (named != null) {
                for (int i = 0; i + 1 < named.length; i += 2) {
                    ranges.add(new int[]{named[i], named[i + 1]});
                }
                continue;
            }
            int[] points = item.codePoints().toArray();
            if (points.length == 1) {
                ranges.add(new int[]{points[0], points[0]});
            } else if (points.length == 3 && points[1] == '-' && points[0] <= points[2]) {
                ranges.add(new int[]{points[0], points[2]});
            } else {
                warnings.add(item);
            }
        }
        return ranges.isEmpty() ? ANY : new PlateChars(ranges);
    }

    /** 名前で書ける字の集まり。知らない名前なら null。 */
    private static int[] named(String name) {
        return switch (name) {
            // ぁ〜ゖ と、長音・踊り字（ー ゝ ゞ）
            case "hiragana" -> new int[]{0x3041, 0x3096, 0x309D, 0x309E, 0x30FC, 0x30FC};
            case "katakana" -> new int[]{0x30A1, 0x30FA, 0x30FC, 0x30FE};
            // CJK 統合漢字と、々（同じ字の繰り返し。「代々木」）
            case "kanji" -> new int[]{0x4E00, 0x9FFF, 0x3005, 0x3005};
            case "latin" -> new int[]{'A', 'Z', 'a', 'z'};
            case "digits" -> new int[]{'0', '9'};
            default -> null;
        };
    }
}
