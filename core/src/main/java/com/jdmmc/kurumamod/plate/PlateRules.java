package com.jdmmc.kurumamod.plate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ナンバーの様式の決まり（欄の並び）。<b>プレートの部品の {@code data/} 側に書く {@code "plate"}。</b>
 *
 * <p>サーバーが読んでクライアントへ配る（部品の一覧と同じ道）。サーバーはこれで送られてきた文字を
 * 検査する——知らない欄・使えない字・長すぎる字は落とす。</p>
 *
 * <p><b>欄が 1 つも無いプレートは字を打たない飾り</b>（換装画面は入力欄を出さない）。</p>
 */
public record PlateRules(List<PlateField> fields) {

    /** 1 欄に入る字の数の上限。どんな様式でもこれより長くはしない（通信と NBT を膨らませないため）。 */
    public static final int FIELD_MAX_LENGTH = 16;
    /** 欄の数の上限。 */
    public static final int MAX_FIELDS = 8;

    /** 欄の決まりを持たない様式（どの欄の名前でも、上限いっぱいまで）。 */
    public static final PlateRules NONE = new PlateRules(List.of());

    public PlateRules {
        fields = List.copyOf(fields.size() > MAX_FIELDS ? fields.subList(0, MAX_FIELDS) : fields);
    }

    /** その名前の欄。無ければ null。 */
    public PlateField field(String id) {
        for (PlateField field : fields) {
            if (field.id().equals(id)) {
                return field;
            }
        }
        return null;
    }

    /** 欄の名前の並び（画面に並べる順）。 */
    public List<String> ids() {
        return PlateField.ids(fields);
    }

    /**
     * 送られてきた文字を、この様式の決まりに通す。
     *
     * <p><b>この様式に無い欄は消さずに残す。</b>様式を替えても文字を欄の名前ごとに持ち越すため
     * （JP → EU → JP で番号が戻る）。残す欄も、どの様式でも通る最低限の検査
     * （{@link PlateLook#cleanLoose}）は受けている。</p>
     */
    public Map<String, String> clean(Map<String, String> text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : text.entrySet()) {
            PlateField field = field(entry.getKey());
            String value = field == null ? entry.getValue() : clean(entry.getValue(), field.chars(), field.max());
            if (!value.isEmpty()) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /** その欄に出す字。編集していなければ既定の文字。 */
    public String display(PlateLook look, String fieldId) {
        String typed = look.text().get(fieldId);
        if (typed != null) {
            return typed;
        }
        PlateField field = field(fieldId);
        return field == null ? "" : field.defaultText();
    }

    /**
     * 字を決まりに通す。使えない字を落とし、上限で切る。大文字だけの欄の小文字は大文字に直す。
     *
     * <p><b>どの欄でも制御文字と書式記号（{@code §}）は落とす。</b>他人の画面に出る字なので、
     * 色を変える・改行するといった細工をさせない。</p>
     */
    public static String clean(String text, PlateChars chars, int max) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < text.length() && count < max; ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            // 大文字しか使えない欄に小文字を打ったら、落とさずに大文字へ直す（EU の本文に "km" と打っても通る）
            if (!chars.allows(codePoint) && chars.allows(Character.toUpperCase(codePoint))) {
                codePoint = Character.toUpperCase(codePoint);
            }
            if (!printable(codePoint) || !chars.allows(codePoint)) {
                continue;
            }
            out.appendCodePoint(codePoint);
            count++;
        }
        // 先頭と末尾の空白は残さない（揃え方が狂う）
        return out.toString().strip();
    }

    /**
     * 入力欄に打っている途中の字として受け付けるか。{@link #clean} と違って<b>空白を切り詰めない</b>
     * （「KM 1234」の空白を打った瞬間に弾かれないように）。小文字は大文字に直せれば受け付ける。
     */
    public static boolean accepts(String text, PlateChars chars, int max) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!printable(codePoint)
                    || !(chars.allows(codePoint) || chars.allows(Character.toUpperCase(codePoint)))) {
                return false;
            }
            count++;
        }
        return count <= max;
    }

    /** 他人の画面に出してよい字か。 */
    static boolean printable(int codePoint) {
        if (codePoint == '§' || Character.isISOControl(codePoint)) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type != Character.FORMAT && type != Character.PRIVATE_USE && type != Character.SURROGATE
                && type != Character.UNASSIGNED && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR;
    }
}
