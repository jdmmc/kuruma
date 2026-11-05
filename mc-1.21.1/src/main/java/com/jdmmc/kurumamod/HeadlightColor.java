package com.jdmmc.kurumamod;

/**
 * ヘッドライトが照らす光の色。Ctrl+L の画面で選ぶ。
 *
 * <p>路面の照り返し・霧の中の光線・光芒のすべてがこの色で描かれる（レンズそのものの色は別）。
 * 既定のハロゲンは、選べるようにする前の色そのまま。</p>
 *
 * <p><b>{@code client} パッケージには置けない。</b>{@link ClientConfig} が参照する以上、
 * spec を登録する専用サーバー側でも読み込まれるため（{@link GateDisplay} と同じ）。
 * 設定ファイルには名前で書くので、並べ替えても保存済みの設定は壊れない。</p>
 */
public enum HeadlightColor {

    /** 暖かい白。既定。 */
    HALOGEN(1.0F, 0.94F, 0.82F),
    /** 真っ白。 */
    WHITE(1.0F, 1.0F, 1.0F),
    /** わずかに青い白（HID）。 */
    HID(0.92F, 0.96F, 1.0F),
    /** 青白い（LED）。 */
    LED(0.82F, 0.90F, 1.0F),
    /** 黄色（イエローバルブ）。 */
    YELLOW(1.0F, 0.85F, 0.45F);

    private final float[] rgb;

    HeadlightColor(float r, float g, float b) {
        this.rgb = new float[] {r, g, b};
    }

    /** 色。<b>書き換えないこと</b>（共有している配列）。 */
    public float[] rgb() {
        return rgb;
    }

    /** 翻訳キー。 */
    public String translationKey() {
        return "screen.kurumamod.headlight.color." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
