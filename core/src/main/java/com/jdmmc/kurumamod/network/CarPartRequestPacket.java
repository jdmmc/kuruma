package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;

/**
 * クライアント → サーバー。換装の<b>要求</b>。
 *
 * <p><b>要求であって結果ではない。</b>諸元（{@link CarTuningPacket}）はクライアントが解いた
 * 結果をサーバーが受け取るだけだが、装着状態は<b>サーバーが決める</b>——手元にあるだけの
 * 部品を勝手に履けてしまうと、他の人には見えない部品を履いた車が走ることになる。
 * サーバーが知らない部品、場所の合わない部品、その車種に付かない部品はここで弾く。</p>
 *
 * <p>受け付けるのは<b>運転している本人</b>から届いたものだけ。同乗者や、外から見ている人が
 * 他人の車を換装できてはいけない。</p>
 *
 * <p>運ぶのは<b>その場所の状態まるごと</b>（部品と調整値）。部品だけ・角度だけの
 * パケットに分けると、片方を送ったときにもう片方を消してしまわないよう両側で気を使うことになる。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param slot     付け替える場所
 * @param setting  その場所をどうしたいか（部品と、キャンバー角の上書き）
 */
public record CarPartRequestPacket(int entityId, PartSlot slot, PartFitment.Setting setting) implements RacePayload {

    public CarPartRequestPacket(RaceBuf buf) {
        this(buf.readVarInt(), PartSlot.byName(buf.readUtf()), PartFitment.readSetting(buf));
    }

    public void encode(RaceBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeUtf(slot.getSerializedName());
        PartFitment.writeSetting(buf, setting);
    }

}
