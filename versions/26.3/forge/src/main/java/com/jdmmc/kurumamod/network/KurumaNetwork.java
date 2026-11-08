package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.Channel;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * この MOD のパケットの登録と送り口（Forge 版）。
 *
 * <p><b>NeoForge 版・Fabric 版（versions/26.3/neoforge/・fabric/）にも同じ名前・同じメソッドのこのクラスがある。</b>
 * versions/26.3/common/ の共有コードはどれにも同じように呼ぶので、メソッドを足したら 3 つに足すこと。
 * パケットの一覧と向きも揃えること。</p>
 *
 * <p>Forge 26.3 の {@code PayloadChannel}（{@link CustomPacketPayload} をそのまま流せる通り道）を 1 本使う。
 * パケットは {@code TYPE}（名前）で見分けるので、NeoForge 版と同じく並び順に意味は無い。
 * 受けた処理はメインスレッドで走らせる（{@code addMain}）。</p>
 */
public final class KurumaNetwork {

    /** パケットの中身の形を変えたら上げる。揃っていない同士は接続の時点で弾かれる。 */
    private static final int PROTOCOL_VERSION = 6;

    private static Channel<CustomPacketPayload> channel;

    private KurumaNetwork() {
    }

    /** 両側で 1 回だけ呼ぶ（{@code Kurumamod} のコンストラクタ）。 */
    public static void register() {
        channel = ChannelBuilder.named(Identifier.fromNamespaceAndPath(Kurumamod.MODID, "main"))
                .networkProtocolVersion(PROTOCOL_VERSION)
                .payloadChannel()
                .play()
                .serverbound()
                .addMain(CarStatePacket.TYPE, codec(CarStatePacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarTuningPacket.TYPE, codec(CarTuningPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarPartRequestPacket.TYPE, codec(CarPartRequestPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarPaintRequestPacket.TYPE, codec(CarPaintRequestPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarPlateRequestPacket.TYPE, codec(CarPlateRequestPacket.STREAM_CODEC), handler(p -> p::handle))
                // レースの画面とキー（K）からの操作。コマンドと同じ処理を呼ぶ
                .addMain(RaceActionPacket.TYPE, codec(RaceActionPacket.STREAM_CODEC), handler(p -> p::handle))
                .clientbound()
                .addMain(CarSpecPacket.TYPE, codec(CarSpecPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(RaceEventPacket.TYPE, codec(RaceEventPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CourseLinesPacket.TYPE, codec(CourseLinesPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(RaceStandingsPacket.TYPE, codec(RaceStandingsPacket.STREAM_CODEC), handler(p -> p::handle))
                // カーパックが足した車種・部品と、サーバーが決めた走行のルール。ログイン時と /reload 時に配る
                .addMain(CarTypesPacket.TYPE, codec(CarTypesPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarRulesPacket.TYPE, codec(CarRulesPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarPartTypesPacket.TYPE, codec(CarPartTypesPacket.STREAM_CODEC), handler(p -> p::handle))
                // 車 1 台の装着状態。走行中に換装したときだけ飛ぶ（現れるときはスポーンデータが運ぶ）
                .addMain(CarPartsPacket.TYPE, codec(CarPartsPacket.STREAM_CODEC), handler(p -> p::handle))
                // レースの様子（受付中か・決まり・参加者と判定）。受け取る人ごとに組んで配る
                .addMain(RaceLobbyPacket.TYPE, codec(RaceLobbyPacket.STREAM_CODEC), handler(p -> p::handle))
                // 行きも帰りもあるもの（運転者 → サーバー → 周りのクライアント）
                .bidirectional()
                .addMain(CarImpactPacket.TYPE, codec(CarImpactPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarScrapePacket.TYPE, codec(CarScrapePacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(CarPushPacket.TYPE, codec(CarPushPacket.STREAM_CODEC), handler(p -> p::handle))
                .addMain(GhostRidePacket.TYPE, codec(GhostRidePacket.STREAM_CODEC), handler(p -> p::handle))
                .build();
    }

    /**
     * 共有側のパケットは {@link FriendlyByteBuf} の StreamCodec を持つ。PLAY の通り道は
     * {@link RegistryFriendlyByteBuf} を渡してくるが、これは FriendlyByteBuf の子なのでそのまま読み書きできる。
     * 型引数だけを合わせる。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return (StreamCodec) codec;
    }

    private static <T> java.util.function.BiConsumer<T, CustomPayloadEvent.Context> handler(
            Function<T, Consumer<PacketContext>> handler) {
        return (packet, context) -> handler.apply(packet).accept(wrap(context));
    }

    private static PacketContext wrap(CustomPayloadEvent.Context context) {
        return new PacketContext() {
            @Override
            public Player player() {
                // サーバーで受けたら送ってきた人、クライアントで受けたら自分
                return context.isServerSide() ? context.getSender() : ClientAccess.player();
            }

            @Override
            public void enqueueWork(Runnable task) {
                // addMain で受けているので、ここはもうメインスレッド
                task.run();
            }
        };
    }

    public static void sendToServer(CustomPacketPayload payload) {
        channel.send(payload, PacketDistributor.SERVER.noArg());
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        channel.send(payload, PacketDistributor.PLAYER.with(player));
    }

    /** その実体を見ている（追跡している）プレイヤーへ。実体そのものがプレイヤーでも、本人は含まない。 */
    public static void sendToPlayersTrackingEntity(Entity entity, CustomPacketPayload payload) {
        channel.send(payload, PacketDistributor.TRACKING_ENTITY.with(entity));
    }

    public static void sendToPlayersInDimension(ServerLevel level, CustomPacketPayload payload) {
        channel.send(payload, PacketDistributor.DIMENSION.with(level.dimension()));
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        channel.send(payload, PacketDistributor.ALL.noArg());
    }

    /** クライアントだけのクラスに触るところ。サーバーでは呼ばれないので読み込まれない。 */
    private static final class ClientAccess {
        private ClientAccess() {
        }

        static Player player() {
            return Minecraft.getInstance().player;
        }
    }
}
