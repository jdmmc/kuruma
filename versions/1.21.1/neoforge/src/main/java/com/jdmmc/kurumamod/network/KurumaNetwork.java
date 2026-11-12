package com.jdmmc.kurumamod.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * この MOD のパケットの登録。
 *
 * <p>車の位置・向きはバニラの {@code ServerboundMoveVehiclePacket}（運転クライアントが自動で送る）
 * に相乗りするので、ここで送るのは切れ角と車速だけ。周囲のクライアントでタイヤを描画するために使う。</p>
 *
 * <p>送るときはこのクラスの static メソッドを使う（{@code sendToServer}・{@code sendToPlayer}・
 * {@code sendToPlayersTrackingEntity} など）。<b>Fabric 版（versions/1.21.1/fabric/）にも同じ名前・同じメソッドの
 * このクラスがある。</b>versions/1.21.1/common/ の共有コードはどちらも同じように呼ぶので、メソッドを足したら両方に足すこと。
 * 受けたパケットの処理には、{@code IPayloadContext} を {@link PacketContext} に包んで渡す。</p>
 *
 * <p><b>Forge 版（SimpleChannel）と違って番号の並び順に意味は無い。</b>パケットは
 * それぞれの {@code TYPE}（名前）で見分けられる。</p>
 */
public final class KurumaNetwork {

    /**
     * パケットの中身の形を変えたら上げる。揃っていない同士は接続の時点で弾かれるので、
     * 読み違えたまま遊べてしまうことがない。
     */
    private static final String PROTOCOL_VERSION = "6";

    private KurumaNetwork() {
    }

    /** パケットの登録。MOD イベントバスの {@link RegisterPayloadHandlersEvent} で呼ぶ。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        // 処理（handle）は既定でメインスレッドで走る
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(CorePackets.CAR_STATE.type(), CorePackets.CAR_STATE.codec(), (packet, context) -> packet.handle(wrap(context)));
        registrar.playToServer(CorePackets.CAR_TUNING.type(), CorePackets.CAR_TUNING.codec(), (packet, context) -> packet.handle(wrap(context)));
        registrar.playToClient(CorePackets.CAR_SPEC.type(), CorePackets.CAR_SPEC.codec(), (packet, context) -> packet.handle(wrap(context)));
        registrar.playToClient(CorePackets.RACE_EVENT.type(), CorePackets.RACE_EVENT.codec(), (packet, context) -> packet.handle(wrap(context)));
        registrar.playToClient(CorePackets.COURSE_LINES.type(), CorePackets.COURSE_LINES.codec(), (packet, context) -> packet.handle(wrap(context)));
        // 衝突だけは行きも帰りもある（運転者 → サーバー → 周りのクライアント）
        registrar.playBidirectional(CorePackets.CAR_IMPACT.type(), CorePackets.CAR_IMPACT.codec(), (packet, context) -> packet.handle(wrap(context)));
        // 車体が壁と擦れている合図（火花）。衝突と同じ道
        registrar.playBidirectional(CorePackets.CAR_SCRAPE.type(), CorePackets.CAR_SCRAPE.codec(), (packet, context) -> packet.handle(wrap(context)));
        registrar.playToClient(CorePackets.RACE_STANDINGS.type(), CorePackets.RACE_STANDINGS.codec(), (packet, context) -> packet.handle(wrap(context)));
        // カーパックが足した車種。ログイン時と /reload 時に丸ごと配る
        registrar.playToClient(CorePackets.CAR_TYPES.type(), CorePackets.CAR_TYPES.codec(), (packet, context) -> packet.handle(wrap(context)));
        // サーバーが決めた走行のルール（車同士が当たるか）。車種と同じ場面で配る
        registrar.playToClient(CorePackets.CAR_RULES.type(), CorePackets.CAR_RULES.codec(), (packet, context) -> packet.handle(wrap(context)));
        // ぶつけられた車を弾く。運転者 → サーバー → 相手の運転者（無人ならサーバーが足す）
        registrar.playBidirectional(CorePackets.CAR_PUSH.type(), CorePackets.CAR_PUSH.codec(), (packet, context) -> packet.handle(wrap(context)));
        // カーパックが足した部品。車種と同じ場面で配る
        registrar.playToClient(CorePackets.CAR_PART_TYPES.type(), CorePackets.CAR_PART_TYPES.codec(), (packet, context) -> packet.handle(wrap(context)));
        // 車 1 台の装着状態。走行中に換装したときだけ飛ぶ（現れるときはスポーンデータが運ぶ）
        registrar.playToClient(CorePackets.CAR_PARTS.type(), CorePackets.CAR_PARTS.codec(), (packet, context) -> packet.handle(wrap(context)));
        // 換装の要求。何を履けるかを決めるのはサーバーなので、結果ではなく要求を送る
        registrar.playToServer(CorePackets.CAR_PART_REQUEST.type(), CorePackets.CAR_PART_REQUEST.codec(), (packet, context) -> packet.handle(wrap(context)));
        // 塗装の要求。一覧はクライアントだけが知っているので、サーバーは形だけ見て覚えて配る
        registrar.playToServer(CorePackets.CAR_PAINT_REQUEST.type(), CorePackets.CAR_PAINT_REQUEST.codec(), (packet, context) -> packet.handle(wrap(context)));
        // ナンバーの中身（打った字と付ける位置）の要求。様式の決まりと看板と同じフィルタを通してから配る
        registrar.playToServer(CorePackets.CAR_PLATE_REQUEST.type(), CorePackets.CAR_PLATE_REQUEST.codec(), (packet, context) -> packet.handle(wrap(context)));
        // 運転者の画面で走っているゴーストを、同じ車の助手席の人へ。運転者 → サーバー → ほかの乗員
        // レースの様子（受付中か・決まり・参加者と判定）。受け取る人ごとに組んで配る
        registrar.playToClient(CorePackets.RACE_LOBBY.type(), CorePackets.RACE_LOBBY.codec(), (packet, context) -> packet.handle(wrap(context)));
        registrar.playToClient(CorePackets.RACE_CLASSES.type(), CorePackets.RACE_CLASSES.codec(), (packet, context) -> packet.handle(wrap(context)));
        // タイムアタックの記録（K の画面がコースを選んだとき）
        registrar.playToClient(CorePackets.TIME_ATTACK_RECORDS.type(), CorePackets.TIME_ATTACK_RECORDS.codec(), (packet, context) -> packet.handle(wrap(context)));
        // レースの画面とキー（K）からの操作。コマンドと同じ処理を呼ぶ
        registrar.playToServer(CorePackets.RACE_ACTION.type(), CorePackets.RACE_ACTION.codec(), (packet, context) -> packet.handle(wrap(context)));
        registrar.playBidirectional(GhostRidePacket.TYPE, GhostRidePacket.STREAM_CODEC, (packet, context) -> packet.handle(wrap(context)));
    }

    private static PacketContext wrap(IPayloadContext context) {
        return new PacketContext() {
            @Override
            public Player player() {
                return context.player();
            }

            @Override
            public void enqueueWork(Runnable task) {
                context.enqueueWork(task);
            }
        };
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** その実体を見ている（追跡している）プレイヤーへ。実体そのものがプレイヤーでも、本人は含まない。 */
    public static void sendToPlayersTrackingEntity(Entity entity, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingEntity(entity, payload);
    }

    public static void sendToPlayersInDimension(ServerLevel level, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersInDimension(level, payload);
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        PacketDistributor.sendToAllPlayers(payload);
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
