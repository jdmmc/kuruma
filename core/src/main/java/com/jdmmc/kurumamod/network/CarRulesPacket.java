package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.car.CarRules;

/**
 * サーバーが決めた走行のルールをクライアントへ配る。
 *
 * <p>送るのはログイン時と {@code /reload} 時（{@code OnDatapackSyncEvent}）、それに
 * {@code /kuruma-admin collide} で書き換えたとき。</p>
 *
 * <p><b>設定ファイルを両側で読ませてはいけない。</b>{@code Config} は
 * COMMON なのでクライアントにも同名のファイルがあるが、中身が違えば
 * <b>片方だけ当たる</b>ことになり、運転者がサーバーに引き戻され続ける。</p>
 */
public record CarRulesPacket(boolean carCollision) implements RacePayload {

    public CarRulesPacket(RaceBuf buf) {
        this(buf.readBoolean());
    }

    public void encode(RaceBuf buf) {
        buf.writeBoolean(carCollision);
    }

    /** いまサーバーで効いている値。 */
    public static CarRulesPacket current() {
        return new CarRulesPacket(CarRules.carCollision());
    }

}
