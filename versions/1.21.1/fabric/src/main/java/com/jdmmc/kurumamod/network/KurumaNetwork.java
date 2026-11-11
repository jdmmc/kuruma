package com.jdmmc.kurumamod.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.function.Function;

/**
 * この MOD のパケットの登録と送り口（Fabric 版）。
 *
 * <p><b>NeoForge 版（versions/1.21.1/neoforge/）にも同じ名前・同じメソッドのこのクラスがある。</b>versions/1.21.1/common/ の共有コードは
 * どちらにも同じように呼ぶので、メソッドを足したら両方に足すこと。パケットの一覧と向きも両方で揃えること。</p>
 *
 * <p>クライアントでの受け口は {@link KurumaNetworkClient}（専用サーバーに無いクラスに触るので分けてある）。
 * Fabric API の受け口は最初からメインスレッドで走るので、{@link PacketContext#enqueueWork} はその場で走る。</p>
 */
public final class KurumaNetwork {

    /** いま動いているサーバー。全員へ送るのに使う（NeoForge の PacketDistributor は自分で持っている）。 */
    private static MinecraftServer server;

    private KurumaNetwork() {
    }

    /** 両側で 1 回だけ呼ぶ（{@code onInitialize}）。種類の登録と、サーバーでの受け口。 */
    public static void register() {
        toServer(CarStatePacket.TYPE, CarStatePacket.STREAM_CODEC, p -> p::handle);
        toServer(CarTuningPacket.TYPE, CarTuningPacket.STREAM_CODEC, p -> p::handle);
        toClient(CarSpecPacket.TYPE, CarSpecPacket.STREAM_CODEC);
        toClient(RaceEventPacket.TYPE, RaceEventPacket.STREAM_CODEC);
        toClient(CourseLinesPacket.TYPE, CourseLinesPacket.STREAM_CODEC);
        // 衝突だけは行きも帰りもある（運転者 → サーバー → 周りのクライアント）
        both(CarImpactPacket.TYPE, CarImpactPacket.STREAM_CODEC, p -> p::handle);
        // 車体が壁と擦れている合図（火花）。衝突と同じ道
        both(CarScrapePacket.TYPE, CarScrapePacket.STREAM_CODEC, p -> p::handle);
        toClient(RaceStandingsPacket.TYPE, RaceStandingsPacket.STREAM_CODEC);
        toClient(CarTypesPacket.TYPE, CarTypesPacket.STREAM_CODEC);
        toClient(CarRulesPacket.TYPE, CarRulesPacket.STREAM_CODEC);
        both(CarPushPacket.TYPE, CarPushPacket.STREAM_CODEC, p -> p::handle);
        toClient(CarPartTypesPacket.TYPE, CarPartTypesPacket.STREAM_CODEC);
        toClient(CarPartsPacket.TYPE, CarPartsPacket.STREAM_CODEC);
        toServer(CarPartRequestPacket.TYPE, CarPartRequestPacket.STREAM_CODEC, p -> p::handle);
        toServer(CarPaintRequestPacket.TYPE, CarPaintRequestPacket.STREAM_CODEC, p -> p::handle);
        toServer(CarPlateRequestPacket.TYPE, CarPlateRequestPacket.STREAM_CODEC, p -> p::handle);
        toClient(RaceLobbyPacket.TYPE, RaceLobbyPacket.STREAM_CODEC);
        toClient(RaceClassesPacket.TYPE, RaceClassesPacket.STREAM_CODEC);
        toClient(TimeAttackRecordsPacket.TYPE, TimeAttackRecordsPacket.STREAM_CODEC);
        toServer(RaceActionPacket.TYPE, RaceActionPacket.STREAM_CODEC, p -> p::handle);
        both(GhostRidePacket.TYPE, GhostRidePacket.STREAM_CODEC, p -> p::handle);
        // Fabric 版だけ。車が現れるときに一緒に運ぶデータ（NeoForge では IEntityWithComplexSpawn が運ぶ）
        toClient(CarSpawnDataPacket.TYPE, CarSpawnDataPacket.STREAM_CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
    }

    private static <T extends CustomPacketPayload> void toServer(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<FriendlyByteBuf, T> codec,
                                                                 Function<T, java.util.function.Consumer<PacketContext>> handler) {
        PayloadTypeRegistry.playC2S().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                handler.apply(payload).accept(wrap(context.player(), context.server())));
    }

    private static <T extends CustomPacketPayload> void toClient(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<FriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.playS2C().register(type, codec);
    }

    private static <T extends CustomPacketPayload> void both(CustomPacketPayload.Type<T> type,
                                                             StreamCodec<FriendlyByteBuf, T> codec,
                                                             Function<T, java.util.function.Consumer<PacketContext>> handler) {
        toClient(type, codec);
        toServer(type, codec, handler);
    }

    private static PacketContext wrap(ServerPlayer player, MinecraftServer server) {
        return new PacketContext() {
            @Override
            public Player player() {
                return player;
            }

            @Override
            public void enqueueWork(Runnable task) {
                server.execute(task);
            }
        };
    }

    public static void sendToServer(CustomPacketPayload payload) {
        KurumaNetworkClient.send(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    /** その実体を見ている（追跡している）プレイヤーへ。実体そのものがプレイヤーでも、本人は含まない。 */
    public static void sendToPlayersTrackingEntity(Entity entity, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.tracking(entity)) {
            if (player != entity) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    public static void sendToPlayersInDimension(ServerLevel level, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.world(level)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : PlayerLookup.all(server)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
