package com.jdmmc.kurumamod.network;

/**
 * ぶつかった合図。<b>行きと帰りの両方向に使う。</b>
 *
 * <ul>
 *   <li>運転クライアント → サーバー … 物理を解いているのは運転者なので、衝突に気づけるのも運転者だけ。
 *       自分の画面では即座に鳴らして揺らし、同じものを周りにも届けてもらうためにサーバーへ送る</li>
 *   <li>サーバー → その車を追跡しているクライアント … 送り主以外へ中継する。
 *       無人の車はサーバーが物理を解いているので、その場合はサーバー発で全員へ送る</li>
 * </ul>
 *
 * <p>方向を決めずに登録してあるので、{@code CarPacketHandlers} は<b>送り主の有無</b>で自分がどちら側かを判断する。</p>
 *
 * @param entityId 対象の車
 * @param impact   衝突で失った速度 [m/s]。音量とピッチ、画面の揺れの大きさになる
 */
public record CarImpactPacket(int entityId, float impact) implements RacePayload {

    public CarImpactPacket(RaceBuf buf) {
        this(buf.readVarInt(), buf.readFloat());
    }

    public void encode(RaceBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeFloat(impact);
    }

}
