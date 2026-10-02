package com.jdmmc.kurumamod;

/**
 * コースのゲートをどこまで描くか。
 *
 * <p>面まで描くと通る場所が一目で分かる代わりに、コースが長いと視界が板だらけになる。
 * 走り込んだ人ほど邪魔になるので、段階を選べるようにしてある。</p>
 *
 * <p>順序は「情報が多い順」。設定ファイルには名前で書くので、
 * <b>並べ替えても保存済みの設定は壊れない</b>。</p>
 *
 * <p><b>{@code client} パッケージには置けない。</b>{@link ClientConfig} が参照する以上、
 * spec を登録する専用サーバー側でもこのクラスが読み込まれるため。
 * 中身が {@code net.minecraft.client.*} に触れていないので、置き場所さえ守れば安全。</p>
 */
public enum GateDisplay {

    /** 面と柱。既定。 */
    FULL,
    /** 左右のポールだけ。通る幅は分かるが視界は塞がない。 */
    POSTS,
    /** 描かない。 */
    HIDDEN;

    public boolean showsPlane() {
        return this == FULL;
    }

    public boolean showsPosts() {
        return this != HIDDEN;
    }

    /** 翻訳キー。設定画面（H のメニュー）の選択肢に出す。 */
    public String translationKey() {
        return "config.kurumamod.gate_display." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
