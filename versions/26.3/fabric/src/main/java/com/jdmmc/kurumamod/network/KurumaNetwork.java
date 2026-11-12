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
 * <p><b>NeoForge 版（versions/26.3/neoforge/・forge/）にも同じ名前・同じメソッドのこのクラスがある。</b>versions/26.3/common/ の共有コードは
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
        toServer(CorePackets.CAR_STATE.type(), CorePackets.CAR_STATE.codec(), p -> p::handle);
        toServer(CorePackets.CAR_TUNING.type(), CorePackets.CAR_TUNING.codec(), p -> p::handle);
        toClient(CorePackets.CAR_SPEC.type(), CorePackets.CAR_SPEC.codec());
        toClient(CorePackets.RACE_EVENT.type(), CorePackets.RACE_EVENT.codec());
        toClient(CorePackets.COURSE_LINES.type(), CorePackets.COURSE_LINES.codec());
        // 衝突だけは行きも帰りもある（運転者 → サーバー → 周りのクライアント）
        both(CorePackets.CAR_IMPACT.type(), CorePackets.CAR_IMPACT.codec(), p -> p::handle);
        // 車体が壁と擦れている合図（火花）。衝突と同じ道
        both(CorePackets.CAR_SCRAPE.type(), CorePackets.CAR_SCRAPE.codec(), p -> p::handle);
        toClient(CorePackets.RACE_STANDINGS.type(), CorePackets.RACE_STANDINGS.codec());
        toClient(CorePackets.CAR_TYPES.type(), CorePackets.CAR_TYPES.codec());
        toClient(CorePackets.CAR_RULES.type(), CorePackets.CAR_RULES.codec());
        both(CorePackets.CAR_PUSH.type(), CorePackets.CAR_PUSH.codec(), p -> p::handle);
        toClient(CorePackets.CAR_PART_TYPES.type(), CorePackets.CAR_PART_TYPES.codec());
        toClient(CorePackets.CAR_PARTS.type(), CorePackets.CAR_PARTS.codec());
        toServer(CorePackets.CAR_PART_REQUEST.type(), CorePackets.CAR_PART_REQUEST.codec(), p -> p::handle);
        toServer(CorePackets.CAR_PAINT_REQUEST.type(), CorePackets.CAR_PAINT_REQUEST.codec(), p -> p::handle);
        toServer(CorePackets.CAR_PLATE_REQUEST.type(), CorePackets.CAR_PLATE_REQUEST.codec(), p -> p::handle);
        toClient(CorePackets.RACE_LOBBY.type(), CorePackets.RACE_LOBBY.codec());
        toClient(CorePackets.RACE_CLASSES.type(), CorePackets.RACE_CLASSES.codec());
        toClient(CorePackets.TIME_ATTACK_RECORDS.type(), CorePackets.TIME_ATTACK_RECORDS.codec());
        toServer(CorePackets.RACE_ACTION.type(), CorePackets.RACE_ACTION.codec(), p -> p::handle);
        both(GhostRidePacket.TYPE, GhostRidePacket.STREAM_CODEC, p -> p::handle);
        // Fabric 版だけ。車が現れるときに一緒に運ぶデータ（NeoForge では IEntityWithComplexSpawn が運ぶ）
        toClient(CarSpawnDataPacket.TYPE, CarSpawnDataPacket.STREAM_CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
    }

    private static <T extends CustomPacketPayload> void toServer(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<FriendlyByteBuf, T> codec,
                                                                 Function<T, java.util.function.Consumer<PacketContext>> handler) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                handler.apply(payload).accept(wrap(context.player(), context.server())));
    }

    private static <T extends CustomPacketPayload> void toClient(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<FriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
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
        for (ServerPlayer player : PlayerLookup.level(level)) {
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

    // ------------------------------------------------------------------
    // core に置いたパケット（RacePayload）。包みは CorePackets が付ける
    // ------------------------------------------------------------------

    public static void sendToServer(RacePayload payload) {
        sendToServer(CorePackets.wrap(payload));
    }

    public static void sendToPlayer(ServerPlayer player, RacePayload payload) {
        sendToPlayer(player, CorePackets.wrap(payload));
    }

    public static void sendToPlayersInDimension(ServerLevel level, RacePayload payload) {
        sendToPlayersInDimension(level, CorePackets.wrap(payload));
    }

    public static void sendToAllPlayers(RacePayload payload) {
        sendToAllPlayers(CorePackets.wrap(payload));
    }

    public static void sendToPlayersTrackingEntity(Entity entity, RacePayload payload) {
        sendToPlayersTrackingEntity(entity, CorePackets.wrap(payload));
    }
}
