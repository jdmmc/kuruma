package com.jdmmc.kurumamod.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * core に置いたパケット（{@link RacePayload}）を、Minecraft のパケットとして運ぶ包み。
 *
 * <p>どの種類かは {@link CorePackets.Kind} が持つ。<b>包みのクラスは全部で 1 つだが、ローダーはどれも
 * {@link #type()} の名前で見分ける</b>（NeoForge・Fabric・Forge 26.3 の {@code PayloadChannel} で確認）。</p>
 */
public record CorePayload(CorePackets.Kind<?> kind, RacePayload body) implements CustomPacketPayload {

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return kind.type();
    }

    public void handle(PacketContext context) {
        kind.handle(body, context);
    }
}
