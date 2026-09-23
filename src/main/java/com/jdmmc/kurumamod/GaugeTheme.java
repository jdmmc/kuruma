package com.jdmmc.kurumamod;

/**
 * メーターの配色。<b>形はひとつ（90 年代の丸メーター）で、色だけを差し替える。</b>
 *
 * <p>文字盤の作りは共通なので、ここで変わるのは色だけ。ベゼルを銀にすれば
 * {@link #MODERN}（それまでの二眼メーターの色）に、暗い縁とガラスの照りを付ければ
 * {@link #CLASSIC}（90 年代の純正メーターの色）になる。</p>
 *
 * <p><b>{@code client} パッケージには置けない。</b>{@link ClientConfig} が参照する以上、
 * spec を登録する専用サーバー側でもこのクラスが読み込まれるため（{@link GateDisplay} と
 * 同じ事情）。中身は色の数値だけで {@code net.minecraft.client.*} に触れていない。</p>
 *
 * <p>設定ファイルには名前で書かれるので、<b>並べ替えても保存済みの設定は壊れない</b>。</p>
 */
public enum GaugeTheme {

    /**
     * 銀の縁に平らな文字盤、赤い針。それまでの二眼メーターの色をそのまま持ってきたもの。
     *
     * <p>どの車にも当たり障りがない代わりに、車の性格は出ない。</p>
     */
    MODERN(
            0xFF121418, // face        文字盤の地
            0xFFB0B8BF, // bezel       外周の縁。銀
            0xFFD8DEE3, // bezelRing   縁の上のハイライト
            0xFF171B21, // glassInner  ガラスの照り（中心側）。ほぼ平ら
            0xFF0E1114, // glassOuter  同（外周側）
            0xFFE23A2E, // redline     レッドゾーン
            0xFFD8DEE3, // tickMajor
            0xFF7E868C, // tickMinor
            0xFFD8DEE3, // label       目盛りの数字
            0xFF8A9299, // unit        単位
            0xFF59626A, // sub         添え字
            0xFFFF3B30, // needle
            0xFF2A3036, // hub         針の軸
            0xFF6E7A84, // hubRing     軸の縁
            0xC00E1013, // panel       中央の小窓
            0xFFFFFFFF, // text        小窓の数字
            0xFFE23A2E, // gearBox
            0xFFFFFFFF, // gearText
            0xFFFFD24A, // aidOn       ABS・TCS の作動灯
            0x30FFFFFF, // aidOff
            0x66FFFFFF, // aidLabel    消えている灯の文字。<b>消えていても読める</b>こと
            0xFF15181C, // aidLabelOn  点いている灯の文字
            0xFFE23A2E, // handbrake   サイドブレーキの灯。実車と同じく赤
            0xFF4FD8F7, // ledLow      シフトインジケーター
            0xFFFFD24A, // ledMid
            0xFFFF3B30, // ledHigh
            0x40FFFFFF, // ledOff
            0xFF4CD964, // throttle
            0xFFFF3B30), // brake

    /**
     * 深い縁、白い数字、細いオレンジの針。90 年代の国産スポーツの純正メーター。
     *
     * <p>レッドゾーンを外周のリングではなく<b>文字盤の内側の弧</b>で出し、
     * その範囲の数字も赤くするのが当時の作法。</p>
     */
    CLASSIC(
            0xFF0A0C0F, // face
            0xFF1B2126, // bezel       黒い樹脂の縁
            0xFF5A656F, // bezelRing   縁に入る細い金属光沢
            0xFF2C333A, // glassInner  ガラスの照り。中心が明るい
            0xFF05070A, // glassOuter
            0xFFC8241A, // redline
            0xFFFFFFFF, // tickMajor
            0xFF98A2AA, // tickMinor
            0xFFF2F5F7, // label
            0xFF7C868F, // unit
            0xFF59626A, // sub
            0xFFFF5A28, // needle      オレンジ
            0xFF141A1F, // hub
            0xFF6E7A84, // hubRing
            0xD90A0D10, // panel
            0xFFFFFFFF, // text
            0xFFC8241A, // gearBox
            0xFFFFFFFF, // gearText
            0xFFFFD24A, // aidOn
            0x22FFFFFF, // aidOff
            0x5CFFFFFF, // aidLabel
            0xFF0A0C0F, // aidLabelOn
            0xFFC8241A, // handbrake
            0xFF4FD8F7, // ledLow
            0xFFFFD24A, // ledMid
            0xFFFF3B30, // ledHigh
            0x38FFFFFF, // ledOff
            0xFF4CD964, // throttle
            0xFFFF5A28); // brake

    public final int face;
    public final int bezel;
    public final int bezelRing;
    public final int glassInner;
    public final int glassOuter;
    public final int redline;
    public final int tickMajor;
    public final int tickMinor;
    public final int label;
    public final int unit;
    public final int sub;
    public final int needle;
    public final int hub;
    public final int hubRing;
    public final int panel;
    public final int text;
    public final int gearBox;
    public final int gearText;
    public final int aidOn;
    public final int aidOff;
    public final int aidLabel;
    public final int aidLabelOn;
    public final int handbrake;
    public final int ledLow;
    public final int ledMid;
    public final int ledHigh;
    public final int ledOff;
    public final int throttle;
    public final int brake;

    GaugeTheme(int face, int bezel, int bezelRing, int glassInner, int glassOuter, int redline,
               int tickMajor, int tickMinor, int label, int unit, int sub,
               int needle, int hub, int hubRing, int panel, int text,
               int gearBox, int gearText, int aidOn, int aidOff,
               int aidLabel, int aidLabelOn, int handbrake,
               int ledLow, int ledMid, int ledHigh, int ledOff, int throttle, int brake) {
        this.face = face;
        this.bezel = bezel;
        this.bezelRing = bezelRing;
        this.glassInner = glassInner;
        this.glassOuter = glassOuter;
        this.redline = redline;
        this.tickMajor = tickMajor;
        this.tickMinor = tickMinor;
        this.label = label;
        this.unit = unit;
        this.sub = sub;
        this.needle = needle;
        this.hub = hub;
        this.hubRing = hubRing;
        this.panel = panel;
        this.text = text;
        this.gearBox = gearBox;
        this.gearText = gearText;
        this.aidOn = aidOn;
        this.aidOff = aidOff;
        this.aidLabel = aidLabel;
        this.aidLabelOn = aidLabelOn;
        this.handbrake = handbrake;
        this.ledLow = ledLow;
        this.ledMid = ledMid;
        this.ledHigh = ledHigh;
        this.ledOff = ledOff;
        this.throttle = throttle;
        this.brake = brake;
    }

    /** シフトインジケーターの色。{@code position} は左端 0・右端 1。 */
    public int shiftLight(float position) {
        if (position < 0.6F) {
            return ledLow;
        }
        if (position < 0.85F) {
            return ledMid;
        }
        return ledHigh;
    }

    /** 翻訳キー。設定画面（H のメニュー）の選択肢に出す。 */
    public String translationKey() {
        return "config.kurumamod.gauge_theme." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
