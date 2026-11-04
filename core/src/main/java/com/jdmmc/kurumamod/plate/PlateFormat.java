package com.jdmmc.kurumamod.plate;

/**
 * 打った字を、プレートに出す形へ整える（欄の {@code format}）。<b>保存するのは打った字のまま。</b>
 *
 * <p>整えた形で保存すると、様式を替えたときに「・・12」のような点が別の国のプレートに出てしまう。</p>
 */
public final class PlateFormat {

    private PlateFormat() {
    }

    /** 整え方の名前から整える。知らない名前・null ならそのまま。 */
    public static String apply(String format, String text) {
        if (format == null) {
            return text;
        }
        return switch (format) {
            case "jp_serial" -> jpSerial(text);
            case "upper" -> text.toUpperCase(java.util.Locale.ROOT);
            default -> text;
        };
    }

    /**
     * 日本のナンバーの一連番号。<b>4 桁の枠に右詰めで、空いた桁は「・」で埋める。</b>
     * ハイフンは 4 桁のときだけで、それより短ければ同じ位置を空白にする。
     *
     * <pre>
     * 1    → ・・ ・1
     * 12   → ・・ 12
     * 123  → ・1 23
     * 1234 → 12-34
     * </pre>
     *
     * <p>数字以外が混ざっていたら整えずにそのまま返す（パックが決まりを変えた欄でも壊さない）。</p>
     */
    public static String jpSerial(String text) {
        if (text.isEmpty() || text.length() > 4 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return text;
        }
        String padded = "・".repeat(4 - text.length()) + text;
        String separator = text.length() == 4 ? "-" : " ";
        return padded.substring(0, 2) + separator + padded.substring(2);
    }
}
