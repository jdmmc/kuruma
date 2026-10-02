package com.jdmmc.kurumamod.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.CarRules;
import net.minecraft.network.FriendlyByteBuf;


/**
 * サーバーが決めた走行のルールをクライアントへ配る。
 *
 * <p>送るのはログイン時と {@code /reload} 時（{@code OnDatapackSyncEvent}）、それに
 * {@code /kuruma-admin collide} で書き換えたとき。</p>
 *
 * <p><b>設定ファイルを両側で読ませてはいけない。</b>{@link com.jdmmc.kurumamod.Config} は
 * COMMON なのでクライアントにも同名のファイルがあるが、中身が違えば
 * <b>片方だけ当たる</b>ことになり、運転者がサーバーに引き戻され続ける。</p>
 */
public record CarRulesPacket(boolean carCollision) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CarRulesPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "car_rules"));

    public static final StreamCodec<FriendlyByteBuf, CarRulesPacket> STREAM_CODEC =
            StreamCodec.ofMember(CarRulesPacket::encode, CarRulesPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


    public CarRulesPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(carCollision);
    }

    /** いまサーバーで効いている値。 */
    public static CarRulesPacket current() {
        return new CarRulesPacket(CarRules.carCollision());
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> CarRules.setCarCollision(carCollision));
    }
}
