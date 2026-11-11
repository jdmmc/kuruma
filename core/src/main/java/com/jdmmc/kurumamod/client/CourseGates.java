package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.network.CourseLinesPacket;

import java.util.List;

/**
 * クライアントが受け取ったコースの線（ゲート）と、どれを見せるか。描くのは版ごとの {@code CourseRenderer}。
 *
 * <p>ゴースト（{@link GhostEngine}）も「いつスタートラインを跨いだか」を割り出すのにここを読む。</p>
 */
public final class CourseGates {

    private static List<CourseLinesPacket.Gate> gates = List.of();

    private CourseGates() {
    }

    /** 配られてきたゲート。 */
    public static List<CourseLinesPacket.Gate> gates() {
        return gates;
    }

    public static void accept(CourseLinesPacket packet) {
        gates = packet.gates();
    }

    /**
     * 接続が変わったら捨てる。
     *
     * <p><b>{@code static} なので放っておくと前のワールドのゲートがそのまま残る。</b>
     * 線は変わったときにしか配られない（{@code RaceSync}）ので、コースの無いワールドへ
     * 入っても上書きされず、<b>何も無い場所に門が立ち続ける</b>。</p>
     */
    public static void reset() {
        gates = List.of();
    }

    /**
     * どのコースのゲートを描くか（null なら全部のコースのスタートラインだけ）。<b>見せ方はクライアントが自分の状態で決める</b>——サーバーは
     * コースの形を送るだけで、レースのことは何も載せない（{@link CourseLinesPacket}）。
     *
     * <ul>
     *   <li><b>レースに加わっている（エントリー・観戦）なら、そのレースのコースだけ</b>（歩いていても車に乗っていても。
     *       チェックポイントも）。観戦中と号砲までのエントリー中はタイムアタックを測らない（サーバーの
     *       {@code RaceEngine#trackCar}）ので、ほかのコースは要らない</li>
     *   <li>運転してコースを走っている（計測中・ウォームアップ中）なら、そのコースだけ。関係ないコースの線が
     *       視界に入ると走りにくい</li>
     *   <li>どれでもなければ<b>全部のコースのスタートラインだけ</b>（車に乗っていても歩いていても）。どこから
     *       走り出せるかが分かれば足り、チェックポイントまで並べると門だらけになる</li>
     * </ul>
     *
     * @param driving 運転席にいるか。助手席は歩いているのと同じ（人の車で走っているわけではない）
     */
    public static String focus(boolean driving) {
        String followed = RaceLobby.followedCourse();
        if (followed != null) {
            return followed;
        }
        if (!driving) {
            return null;
        }
        return RaceHud.runningCourse();
    }

    /** そのゲートを描くか。 */
    public static boolean shows(String focus, CourseLinesPacket.Gate gate) {
        return focus == null ? gate.start() : focus.equals(gate.course());
    }
}
