package com.jdmmc.kurumamod.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * クライアントでのパケットの受け口と、サーバーへの送り口（Fabric 版）。<b>専用サーバーには無いクラス
 * （{@link ClientPlayNetworking}）に触るので、{@link KurumaNetwork} から分けてある。</b>
 */
public final class KurumaNetworkClient {

    private KurumaNetworkClient() {
    }

    /** クライアントで 1 回だけ呼ぶ（{@code onInitializeClient}）。種類の登録は {@link KurumaNetwork#register()} が済ませている。 */
    public static void register() {
        on(CarSpecPacket.TYPE, p -> p::handle);
        on(RaceEventPacket.TYPE, p -> p::handle);
        on(CourseLinesPacket.TYPE, p -> p::handle);
        on(CarImpactPacket.TYPE, p -> p::handle);
        on(CarScrapePacket.TYPE, p -> p::handle);
        on(RaceStandingsPacket.TYPE, p -> p::handle);
        on(CarTypesPacket.TYPE, p -> p::handle);
        on(CarRulesPacket.TYPE, p -> p::handle);
        on(CarPushPacket.TYPE, p -> p::handle);
        on(CarPartTypesPacket.TYPE, p -> p::handle);
        on(CarPartsPacket.TYPE, p -> p::handle);
        on(GhostRidePacket.TYPE, p -> p::handle);
        on(CarSpawnDataPacket.TYPE, p -> p::handle);
    }

    private static <T extends CustomPacketPayload> void on(CustomPacketPayload.Type<T> type,
                                                           Function<T, Consumer<PacketContext>> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                handler.apply(payload).accept(new PacketContext() {
                    @Override
                    public Player player() {
                        return context.player();
                    }

                    @Override
                    public void enqueueWork(Runnable task) {
                        Minecraft.getInstance().execute(task);
                    }
                }));
    }

    static void send(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
}
