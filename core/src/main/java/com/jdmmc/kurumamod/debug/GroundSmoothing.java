package com.jdmmc.kurumamod.debug;

/**
 * 地面をならす（周りのブロック中心の高さで双線形補間する）かどうかの、開発用の切り替え。
 *
 * <p>同じ道を入りと切りで 1 回ずつ走り、接地の記録（{@link GroundLog}）で比べるためのもの。
 * 運転中に Ctrl+J（キーは変えられる）で切り替わる。保存はしない（起動するたびに入りに戻る）。</p>
 *
 * <p>物理を解くクライアントのスレッドからだけ読み書きする。シングルプレイで同じ JVM にいる
 * サーバー側の無人の車にも効くが、そちらは運転者のいない車なので比べる対象には入らない。</p>
 */
public final class GroundSmoothing {

    private static boolean enabled = true;

    private GroundSmoothing() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 入り切りを反転して、新しい状態を返す。 */
    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
