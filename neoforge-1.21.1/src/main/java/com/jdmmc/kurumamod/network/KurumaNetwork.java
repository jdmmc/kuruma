package com.jdmmc.kurumamod.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * この MOD のパケットの登録。
 *
 * <p>車の位置・向きはバニラの {@code ServerboundMoveVehiclePacket}（運転クライアントが自動で送る）
 * に相乗りするので、ここで送るのは切れ角と車速だけ。周囲のクライアントでタイヤを描画するために使う。</p>
 *
 * <p>送るときは {@code PacketDistributor} の static メソッドを直接使う
 * （{@code sendToServer}・{@code sendToPlayer}・{@code sendToPlayersTrackingEntity} など）。</p>
 *
 * <p><b>Forge 版（SimpleChannel）と違って番号の並び順に意味は無い。</b>パケットは
 * それぞれの {@code TYPE}（名前）で見分けられる。</p>
 */
public final class KurumaNetwork {

    /**
     * パケットの中身の形を変えたら上げる。揃っていない同士は接続の時点で弾かれるので、
     * 読み違えたまま遊べてしまうことがない。
     */
    private static final String PROTOCOL_VERSION = "3";

    private KurumaNetwork() {
    }

    /** パケットの登録。MOD イベントバスの {@link RegisterPayloadHandlersEvent} で呼ぶ。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        // 処理（handle）は既定でメインスレッドで走る
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(CarStatePacket.TYPE, CarStatePacket.STREAM_CODEC, CarStatePacket::handle);
        registrar.playToServer(CarTuningPacket.TYPE, CarTuningPacket.STREAM_CODEC, CarTuningPacket::handle);
        registrar.playToClient(CarSpecPacket.TYPE, CarSpecPacket.STREAM_CODEC, CarSpecPacket::handle);
        registrar.playToClient(RaceEventPacket.TYPE, RaceEventPacket.STREAM_CODEC, RaceEventPacket::handle);
        registrar.playToClient(CourseLinesPacket.TYPE, CourseLinesPacket.STREAM_CODEC, CourseLinesPacket::handle);
        // 衝突だけは行きも帰りもある（運転者 → サーバー → 周りのクライアント）
        registrar.playBidirectional(CarImpactPacket.TYPE, CarImpactPacket.STREAM_CODEC, CarImpactPacket::handle);
        // 車体が壁と擦れている合図（火花）。衝突と同じ道
        registrar.playBidirectional(CarScrapePacket.TYPE, CarScrapePacket.STREAM_CODEC, CarScrapePacket::handle);
        registrar.playToClient(RaceStandingsPacket.TYPE, RaceStandingsPacket.STREAM_CODEC, RaceStandingsPacket::handle);
        // カーパックが足した車種。ログイン時と /reload 時に丸ごと配る
        registrar.playToClient(CarTypesPacket.TYPE, CarTypesPacket.STREAM_CODEC, CarTypesPacket::handle);
        // サーバーが決めた走行のルール（車同士が当たるか）。車種と同じ場面で配る
        registrar.playToClient(CarRulesPacket.TYPE, CarRulesPacket.STREAM_CODEC, CarRulesPacket::handle);
        // ぶつけられた車を弾く。運転者 → サーバー → 相手の運転者（無人ならサーバーが足す）
        registrar.playBidirectional(CarPushPacket.TYPE, CarPushPacket.STREAM_CODEC, CarPushPacket::handle);
        // カーパックが足した部品。車種と同じ場面で配る
        registrar.playToClient(CarPartTypesPacket.TYPE, CarPartTypesPacket.STREAM_CODEC, CarPartTypesPacket::handle);
        // 車 1 台の装着状態。走行中に換装したときだけ飛ぶ（現れるときはスポーンデータが運ぶ）
        registrar.playToClient(CarPartsPacket.TYPE, CarPartsPacket.STREAM_CODEC, CarPartsPacket::handle);
        // 換装の要求。何を履けるかを決めるのはサーバーなので、結果ではなく要求を送る
        registrar.playToServer(CarPartRequestPacket.TYPE, CarPartRequestPacket.STREAM_CODEC, CarPartRequestPacket::handle);
        // 運転者の画面で走っているゴーストを、同じ車の助手席の人へ。運転者 → サーバー → ほかの乗員
        registrar.playBidirectional(GhostRidePacket.TYPE, GhostRidePacket.STREAM_CODEC, GhostRidePacket::handle);
    }
}
