package com.jdmmc.kurumamod.plate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 車 1 台のナンバーの中身。<b>打った文字と、前後のどの位置に付けるか。</b>
 *
 * <p>どのプレートを付けるか（様式）は部品の場所 {@code plate} が持つ。こちらは塗装と同じく
 * 「プレイヤーが変えられる見た目で、車 1 台ごとの状態」なので {@code PartFitment} の直下に持つ。</p>
 *
 * <ul>
 *   <li><b>{@link #text()} は打った欄だけ。</b>打っていない欄は様式の既定の文字を出す
 *       （{@link PlateRules#display}）。車には保存しないので、パックが既定を変えれば置いてある車も変わる</li>
 *   <li><b>様式を替えても文字は残す。</b>欄の名前ごとに覚えておき、同じ名前の欄があればそこに出る</li>
 *   <li>{@link #front()} / {@link #rear()} は車の JSON の {@code plates} の候補の id。
 *       null なら先頭の候補、{@link #HIDDEN} ならその側には付けない</li>
 * </ul>
 */
public record PlateLook(Map<String, String> text, String front, String rear) {

    /** その側に付けない印。 */
    public static final String HIDDEN = "none";

    /** 何も決めていない状態。 */
    public static final PlateLook EMPTY = new PlateLook(Map.of(), null, null);

    /** 取り付け位置の id の長さの上限。 */
    public static final int MOUNT_MAX_LENGTH = 32;
    /** 欄の名前の長さの上限。 */
    public static final int FIELD_ID_MAX_LENGTH = 32;

    public PlateLook {
        text = cleanLoose(text == null ? Map.of() : text);
        front = cleanMount(front);
        rear = cleanMount(rear);
    }

    public boolean isEmpty() {
        return text.isEmpty() && front == null && rear == null;
    }

    /** 1 欄だけ書き換える。空にすれば「打っていない」（＝既定の文字）に戻る。 */
    public PlateLook withText(String fieldId, String value) {
        Map<String, String> next = new LinkedHashMap<>(text);
        if (value == null || value.isEmpty()) {
            next.remove(fieldId);
        } else {
            next.put(fieldId, value);
        }
        return new PlateLook(next, front, rear);
    }

    public PlateLook withFront(String mount) {
        return new PlateLook(text, mount, rear);
    }

    public PlateLook withRear(String mount) {
        return new PlateLook(text, front, mount);
    }

    /** その側に付けるか。 */
    public boolean shows(boolean frontSide) {
        return !HIDDEN.equals(frontSide ? front : rear);
    }

    /**
     * どの様式でも通る最低限の検査。<b>様式の決まりを知らない場所（NBT を読むとき・通信を受けたとき）でも
     * 必ず通す。</b>欄の数・名前の形・字の数を上限で切り、制御文字と書式記号を落とす。
     */
    static Map<String, String> cleanLoose(Map<String, String> text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : text.entrySet()) {
            if (result.size() >= PlateRules.MAX_FIELDS) {
                break;
            }
            String id = entry.getKey();
            if (!isValidId(id, FIELD_ID_MAX_LENGTH)) {
                continue;
            }
            String value = PlateRules.clean(entry.getValue() == null ? "" : entry.getValue(),
                    PlateChars.ANY, PlateRules.FIELD_MAX_LENGTH);
            if (!value.isEmpty()) {
                result.put(id, value);
            }
        }
        return result.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(result);
    }

    private static String cleanMount(String mount) {
        return isValidId(mount, MOUNT_MAX_LENGTH) ? mount : null;
    }

    /** 名前として受け付けられる形か（小文字・数字・{@code _ -}）。 */
    public static boolean isValidId(String id, int maxLength) {
        return id != null && !id.isEmpty() && id.length() <= maxLength
                && id.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-');
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof PlateLook look && text.equals(look.text)
                && Objects.equals(front, look.front) && Objects.equals(rear, look.rear));
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, front, rear);
    }
}
