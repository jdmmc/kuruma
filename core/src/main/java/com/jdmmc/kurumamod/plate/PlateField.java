package com.jdmmc.kurumamod.plate;

import java.util.List;

/**
 * ナンバーの欄 1 つの決まり。<b>サーバーが文字を検査するのに使う</b>ので、部品の {@code data/} 側に書く。
 *
 * <pre>
 * "fields": {
 *   "region": { "max": 4, "default": "品川" },
 *   "number": { "max": 4, "chars": ["0-9"], "default": "1234" }
 * }
 * </pre>
 *
 * <p>字の見た目（フォント・色・揃え方）は {@code assets/} 側に書く。そちらはクライアントしか読まない。</p>
 *
 * @param id          欄の名前。プレートのメッシュの {@code text_<id>} と揃える
 * @param max         入る字の数の上限（{@link PlateRules#FIELD_MAX_LENGTH} まで）
 * @param chars       使える字。空ならどの字でもよい（{@link PlateChars}）
 * @param defaultText 編集していないときに出す字。空なら何も出さない
 */
public record PlateField(String id, int max, PlateChars chars, String defaultText) {

    public PlateField {
        max = Math.max(1, Math.min(max, PlateRules.FIELD_MAX_LENGTH));
        // 既定の文字も同じ決まりに通す。パックが書き間違えても、検査を通らない字が出回らないように
        defaultText = PlateRules.clean(defaultText == null ? "" : defaultText, chars, max);
    }

    /** 決まりを書かなかった欄（どの字でも、上限いっぱいまで）。 */
    public static PlateField loose(String id) {
        return new PlateField(id, PlateRules.FIELD_MAX_LENGTH, PlateChars.ANY, "");
    }

    /** 画面に並べる順と、名前を引く鍵の一覧。 */
    static List<String> ids(List<PlateField> fields) {
        return fields.stream().map(PlateField::id).toList();
    }
}
