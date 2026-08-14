package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * この MOD 用のパケットチャンネル。
 *
 * <p>車の位置・向きはバニラの {@code ServerboundMoveVehiclePacket}（運転クライアントが自動で送る）
 * に相乗りするので、ここで送るのは切れ角と車速だけ。周囲のクライアントでタイヤを描画するために使う。</p>
 */
public final class KurumaNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Kurumamod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private KurumaNetwork() {
    }

    /** パケットの登録。MOD の共通セットアップから 1 度だけ呼ぶ。 */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(CarStatePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CarStatePacket::encode)
                .decoder(CarStatePacket::new)
                .consumerMainThread(CarStatePacket::handle)
                .add();
        CHANNEL.messageBuilder(CarTuningPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CarTuningPacket::encode)
                .decoder(CarTuningPacket::new)
                .consumerMainThread(CarTuningPacket::handle)
                .add();
        CHANNEL.messageBuilder(CarSpecPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CarSpecPacket::encode)
                .decoder(CarSpecPacket::new)
                .consumerMainThread(CarSpecPacket::handle)
                .add();
        CHANNEL.messageBuilder(RaceEventPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RaceEventPacket::encode)
                .decoder(RaceEventPacket::new)
                .consumerMainThread(RaceEventPacket::handle)
                .add();
        CHANNEL.messageBuilder(CourseLinesPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CourseLinesPacket::encode)
                .decoder(CourseLinesPacket::new)
                .consumerMainThread(CourseLinesPacket::handle)
                .add();
        // 衝突だけは行きも帰りもある（運転者 → サーバー → 周りのクライアント）ので方向を決めない
        CHANNEL.messageBuilder(CarImpactPacket.class, id++)
                .encoder(CarImpactPacket::encode)
                .decoder(CarImpactPacket::new)
                .consumerMainThread(CarImpactPacket::handle)
                .add();
        CHANNEL.messageBuilder(RaceStandingsPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RaceStandingsPacket::encode)
                .decoder(RaceStandingsPacket::new)
                .consumerMainThread(RaceStandingsPacket::handle)
                .add();
        // カーパックが足した車種。ログイン時と /reload 時に丸ごと配る
        CHANNEL.messageBuilder(CarTypesPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CarTypesPacket::encode)
                .decoder(CarTypesPacket::new)
                .consumerMainThread(CarTypesPacket::handle)
                .add();
        // サーバーが決めた走行のルール（車同士が当たるか）。車種と同じ場面で配る
        CHANNEL.messageBuilder(CarRulesPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CarRulesPacket::encode)
                .decoder(CarRulesPacket::new)
                .consumerMainThread(CarRulesPacket::handle)
                .add();
        // ぶつけられた車を弾く。運転者 → サーバー → 相手の運転者（無人ならサーバーが足す）
        CHANNEL.messageBuilder(CarPushPacket.class, id++)
                .encoder(CarPushPacket::encode)
                .decoder(CarPushPacket::new)
                .consumerMainThread(CarPushPacket::handle)
                .add();
    }
}
