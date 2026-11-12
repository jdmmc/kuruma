package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlateRules;


/**
 * クライアント → サーバー。ナンバーの中身（打った字と付ける位置）の<b>要求</b>。
 *
 * <p>どのプレートを付けるかは換装（{@link CarPartRequestPacket}）が運ぶ。こちらは塗装と同じく、
 * 受け付けるのは<b>運転している本人</b>から届いたものだけ。</p>
 *
 * <h2>他人の画面に出る字なので、2 段で検査する</h2>
 *
 * <ol>
 *   <li><b>様式の決まり</b>（{@link PlateRules}）… 付けているプレートの部品の {@code data/} 側に
 *       書かれた欄の決まりで、使えない字と長すぎる字を落とす。サーバーが部品の一覧を持っているので、
 *       クライアントの手元の検査を当てにしない</li>
 *   <li><b>看板と同じフィルタ</b>（{@code ServerPlayer#getTextFilter()}）… サーバーが文字の
 *       フィルタを有効にしていれば、看板に書いた字と同じ扱いで伏せる。無効なら素通し</li>
 * </ol>
 *
 * <p>フィルタは別スレッドで返ってくることがあるので、結果を受けてから<b>サーバーのスレッドで</b>車へ書く。
 * 待っている間に車が消えたり運転者が替わったりしていたら捨てる。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param plate    ナンバーの中身
 */
public record CarPlateRequestPacket(int entityId, PlateLook plate) implements RacePayload {

    public CarPlateRequestPacket(RaceBuf buf) {
        this(buf.readVarInt(), PartFitment.readPlate(buf));
    }

    public void encode(RaceBuf buf) {
        buf.writeVarInt(entityId);
        PartFitment.writePlate(buf, plate);
    }

}
