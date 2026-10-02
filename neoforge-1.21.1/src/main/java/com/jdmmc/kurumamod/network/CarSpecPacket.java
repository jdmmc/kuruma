package com.jdmmc.kurumamod.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import net.minecraft.network.FriendlyByteBuf;


/**
 * サーバー → 周囲のクライアント。車の諸元を配る。
 *
 * <p>諸元は見た目に効く。ホイールベースは拡大率に、ストロークとバネはタイヤの位置に効くので、
 * <b>配らないと他人の車だけ既定の寸法で描かれる</b>。</p>
 *
 * <p>車がクライアントに現れたときは {@code IEntityWithComplexSpawn} が同じ内容を運ぶ。
 * こちらは<b>運転者が走行中に調整画面をいじったとき</b>のためにある。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param spec     配る諸元
 */
public record CarSpecPacket(int entityId, CarSpec spec) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CarSpecPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "car_spec"));

    public static final StreamCodec<FriendlyByteBuf, CarSpecPacket> STREAM_CODEC =
            StreamCodec.ofMember(CarSpecPacket::encode, CarSpecPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


    public CarSpecPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), CarSpecCodec.read(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        CarSpecCodec.write(buf, spec);
    }

    public void handle(IPayloadContext context) {
        // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
        context.enqueueWork(() -> CarClientPackets.applySpec(entityId, spec));
    }
}
