package com.jdmmc.kurumamod.debug;

/**
 * 地面をならす（周りのブロック中心の高さで双線形補間する）かどうかと、階段の線を引く向きの、開発用の切り替え。
 *
 * <p>同じ道を切り替えながら走り、接地の記録（{@link GroundLog}）で比べるためのもの。
 * 運転中に Ctrl+J（キーは変えられる）で {@link Mode} を順に回す。保存はしない（起動するたびに既定へ戻る）。</p>
 *
 * <p>物理を解くクライアントのスレッドからだけ読み書きする。シングルプレイで同じ JVM にいる
 * サーバー側の無人の車にも効くが、そちらは運転者のいない車なので比べる対象には入らない。</p>
 */
public final class GroundSmoothing {

    /** 切り替えの段。{@link #code()} は接地の記録の {@code smooth} 列に書く値。 */
    public enum Mode {
        /** ならさない。階段の線は段の角を結ぶ */
        OFF(0),
        /** ならす。階段の線は縁の高さを補間で決め、車体の前後の向きで引く */
        BODY(1),
        /** ならす。階段の線は車の進む向きで引く（止まりかけは車体の向き） */
        TRAVEL(2);

        private final int code;

        Mode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private static Mode mode = Mode.TRAVEL;

    private GroundSmoothing() {
    }

    public static Mode mode() {
        return mode;
    }

    /** 地面をならすか。 */
    public static boolean enabled() {
        return mode != Mode.OFF;
    }

    /** 階段の線を進む向きで引くか。 */
    public static boolean alongTravel() {
        return mode == Mode.TRAVEL;
    }

    /** 次の段へ回して、新しい段を返す。 */
    public static Mode cycle() {
        Mode[] all = Mode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        return mode;
    }
}
