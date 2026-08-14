package com.jdmmc.kurumamod;

/**
 * サーバーが決める走行のルール。両側が同じものを持つ。
 *
 * <p><b>設定そのものではなく「いま効いている値」を持つ。</b>{@link Config} は COMMON なので
 * クライアントにも同名のファイルがあるが、マルチプレイで決めるのは<b>接続先のサーバー</b>で
 * あって手元のファイルではない。そこでサーバーが {@code CarRulesPacket} で配り、
 * クライアントは配られたものだけを見る（車種の扱いと同じ）。</p>
 *
 * <p>クライアントとサーバーの両方が読むので、<b>{@code net.minecraft.client.*} に触れないこと。</b>
 * シングルプレイでは同じ JVM なのでこの静的フィールドは共用になるが、統合サーバーが
 * 権威なのでそれで正しい。</p>
 */
public final class CarRules {

    /**
     * 車同士が当たるか。
     *
     * <p><b>両側で一致していないと運転者が引き戻される。</b>クライアントが「当たらない」と
     * 思って重なる位置まで走ると、サーバーの {@code handleMoveVehicle} が
     * 「移動前は重なっていなかったのに移動後は重なっている」と判定して位置を巻き戻し、
     * {@code ClientboundMoveVehiclePacket} で運転者を引き戻す。</p>
     */
    private static boolean carCollision = true;

    private CarRules() {
    }

    public static boolean carCollision() {
        return carCollision;
    }

    /** サーバー側は設定とコマンドから、クライアント側は受け取ったパケットから呼ぶ。 */
    public static void setCarCollision(boolean value) {
        carCollision = value;
    }
}
