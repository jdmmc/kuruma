package com.jdmmc.kurumamod.network;

import net.minecraft.world.entity.player.Player;

/**
 * パケットを受けたときに、処理から見えるもの。NeoForge 版は {@code IPayloadContext}、Fabric 版は
 * Fabric API の受け口の文脈を、それぞれの {@link KurumaNetwork} がこの形に包んで渡す。
 *
 * <p>パケットの {@code handle} はこれだけを見るので、ローダーに触らずに済む。</p>
 */
public interface PacketContext {

    /** 送ってきた相手。サーバーで受けたら送り手の {@code ServerPlayer}、クライアントで受けたら自分。 */
    Player player();

    /** メインスレッドで走らせる。受けたスレッドがすでにメインスレッドなら、その場で走ることもある。 */
    void enqueueWork(Runnable task);
}
