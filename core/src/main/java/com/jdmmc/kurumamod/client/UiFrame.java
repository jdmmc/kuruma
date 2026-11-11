package com.jdmmc.kurumamod.client;


/**
 * 画面の中身を置く枠。<b>幅はフル HD（1920 ピクセル）が上限。</b>
 *
 * <p>狭いウィンドウでは画面いっぱいを枠にし、中身はその幅に合わせて詰める。フル HD より広い画面では、
 * フル HD の幅の枠を中央に置く（端まで間延びさせない）。座標は GUI の座標なので、上限は
 * {@code 1920 ÷ GUI スケール}。</p>
 */
final class UiFrame {

    /** 枠の幅の上限 [実ピクセル]。 */
    private static final int MAX_PIXELS = 1920;

    private UiFrame() {
    }

    /** 枠の幅（GUI の座標）。 */
    static int width(int screenWidth) {
        double scale = RaceClient.get().guiScale();
        int max = (int) Math.floor(MAX_PIXELS / Math.max(1.0, scale));
        return Math.min(screenWidth, max);
    }

    /** 枠の左端。 */
    static int left(int screenWidth) {
        return (screenWidth - width(screenWidth)) / 2;
    }

    /** 枠の右端。 */
    static int right(int screenWidth) {
        return left(screenWidth) + width(screenWidth);
    }
}
