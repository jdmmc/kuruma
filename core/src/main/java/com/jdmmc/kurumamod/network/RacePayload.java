package com.jdmmc.kurumamod.network;

/**
 * core に置いたパケット。中身と読み書きの順番だけを持ち、Minecraft の包み（{@code CustomPacketPayload}・
 * SimpleChannel）は版の {@code CorePackets} が付ける。
 *
 * <p>読む側は {@code (RaceBuf)} を受け取るコンストラクタ。名前（{@code race_event} など）と、届いたときに
 * 何を呼ぶかも版の {@code CorePackets} が決める（クライアントの処理は版ごとのクラスにあるため）。</p>
 */
public interface RacePayload {

    void encode(RaceBuf buf);
}
