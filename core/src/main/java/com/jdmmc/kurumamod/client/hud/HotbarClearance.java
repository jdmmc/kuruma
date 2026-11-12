package com.jdmmc.kurumamod.client.hud;

/**
 * 画面の下の角に置く表（操作ヘルプ・テレメトリ）を、<b>ホットバーとだけは重ねない</b>ための持ち上げ幅。
 *
 * <p>GUI スケールが大きい画面では、角に置いた表が横にホットバーまで届く。ホットバーは手元の確認に要るので、
 * 横に重なるときだけ、その上まで持ち上げる（メーターとは重なってもよい）。</p>
 */
final class HotbarClearance {

    /** ホットバーの半分の幅（182 / 2）。 */
    private static final int HALF_WIDTH = 91;
    /** ホットバーの高さと、その上の隙間。 */
    private static final int HEIGHT = 22 + 2;

    private HotbarClearance() {
    }

    /** 横 {@code [left, right)} に置く表を、下からどれだけ持ち上げるか。重ならなければ 0。 */
    static int lift(int left, int right, int screenWidth) {
        int hotbarLeft = screenWidth / 2 - HALF_WIDTH;
        int hotbarRight = screenWidth / 2 + HALF_WIDTH;
        return right > hotbarLeft && left < hotbarRight ? HEIGHT : 0;
    }
}
