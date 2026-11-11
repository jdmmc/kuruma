package com.jdmmc.kurumamod.race;

/**
 * チャットやアクションバーに出す文面。バニラの {@code Component} の代わり。版ごとの {@code RaceTexts} が
 * {@code Component} に直す。
 *
 * <p>作り方もバニラに揃えてある（{@link #translatable}・{@link #literal}・{@link #withStyle}）。引数には
 * 文字列・数・{@code RaceText} を渡せる。</p>
 *
 * <p>{@link #ofNative} は版の {@code Component} をそのまま運ぶ口（プレイヤーの表示名。チームの色や、
 * 名前に乗せたホバーをそのまま出すため）。core からは中身を見ない。</p>
 */
public final class RaceText {

    public enum Kind {
        TRANSLATABLE, LITERAL, NATIVE
    }

    private static final Object[] NO_ARGS = new Object[0];
    private static final RaceText[] NO_SIBLINGS = new RaceText[0];

    /**
     * 文面を文字列にする口（クライアントが {@link #setResolver} で置く。翻訳はクライアントの言語で引く）。
     * 置かれていなければ、翻訳キー・文字列をそのまま返す。
     */
    private static volatile java.util.function.Function<RaceText, String> resolver;

    private final Kind kind;
    /** 翻訳キー・文字列（{@link Kind#NATIVE} のときは null）。 */
    private final String text;
    private final Object[] args;
    private final Object nativeText;
    private final RaceColor color;
    /** 後ろに続ける文面（{@link #append}）。 */
    private final RaceText[] siblings;

    private RaceText(Kind kind, String text, Object[] args, Object nativeText, RaceColor color, RaceText[] siblings) {
        this.kind = kind;
        this.text = text;
        this.args = args;
        this.nativeText = nativeText;
        this.color = color;
        this.siblings = siblings;
    }

    public static RaceText translatable(String key, Object... args) {
        return new RaceText(Kind.TRANSLATABLE, key, args == null ? NO_ARGS : args.clone(), null, null, NO_SIBLINGS);
    }

    public static RaceText literal(String text) {
        return new RaceText(Kind.LITERAL, text, NO_ARGS, null, null, NO_SIBLINGS);
    }

    /** 版の {@code Component} をそのまま運ぶ。 */
    public static RaceText ofNative(Object component) {
        return new RaceText(Kind.NATIVE, null, NO_ARGS, component, null, NO_SIBLINGS);
    }

    /** 色を付けた写し。 */
    public RaceText withStyle(RaceColor color) {
        return new RaceText(kind, text, args, nativeText, color, siblings);
    }

    /** 空の文面（{@link #append} で継ぎ足す土台）。 */
    public static RaceText empty() {
        return literal("");
    }

    /** 後ろに {@code part} を続けた写し。 */
    public RaceText append(RaceText part) {
        RaceText[] more = java.util.Arrays.copyOf(siblings, siblings.length + 1);
        more[siblings.length] = part;
        return new RaceText(kind, text, args, nativeText, color, more);
    }

    public RaceText append(String part) {
        return append(literal(part));
    }

    /** 後ろに続ける文面。 */
    public java.util.List<RaceText> siblings() {
        return java.util.List.of(siblings);
    }

    /** 文字列にした文面（クライアントの言語で翻訳する）。 */
    public String getString() {
        java.util.function.Function<RaceText, String> resolve = resolver;
        if (resolve != null) {
            return resolve.apply(this);
        }
        StringBuilder out = new StringBuilder(text == null ? "" : text);
        for (RaceText sibling : siblings) {
            out.append(sibling.getString());
        }
        return out.toString();
    }

    /** 文字列にする口を置く（クライアントの起動時）。 */
    public static void setResolver(java.util.function.Function<RaceText, String> resolve) {
        resolver = resolve;
    }

    public Kind kind() {
        return kind;
    }

    /** 翻訳キー、または文字列。 */
    public String text() {
        return text;
    }

    public Object[] args() {
        return args.clone();
    }

    public Object nativeText() {
        return nativeText;
    }

    /** 色。付けていなければ null。 */
    public RaceColor color() {
        return color;
    }
}
