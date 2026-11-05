package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntityBase;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * 車が現れるときに一緒に運ぶデータ（車種・諸元・部品）。<b>Fabric 版だけのパケット</b>で、NeoForge 版では
 * NeoForge の {@code IEntityWithComplexSpawn} が同じことをする。
 *
 * <p>送るのは {@code fabric.mixin.ServerEntityMixin}。実体を足すパケットのすぐ後ろ（同じ束の中）に入るので、
 * クライアントでは実体がワールドに足された後で、ほかのどのパケットよりも先に読まれる（NeoForge と同じ順）。</p>
 */
public record CarSpawnDataPacket(int entityId, byte[] data) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final CustomPacketPayload.Type<CarSpawnDataPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "car_spawn_data"));

    public static final StreamCodec<FriendlyByteBuf, CarSpawnDataPacket> STREAM_CODEC =
            StreamCodec.ofMember(CarSpawnDataPacket::encode, CarSpawnDataPacket::new);

    public CarSpawnDataPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readByteArray());
    }

    /** 車のスポーンデータを書いて包む。 */
    public static CarSpawnDataPacket of(CarEntityBase car) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), car.registryAccess());
        try {
            car.writeSpawnData(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new CarSpawnDataPacket(car.getId(), bytes);
        } finally {
            buf.release();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeByteArray(data);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            Entity entity = context.player().level().getEntity(entityId);
            if (!(entity instanceof CarEntityBase car)) {
                return;
            }
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), car.registryAccess());
            try {
                car.readSpawnData(buf);
            } catch (RuntimeException e) {
                LOGGER.error("車のスポーンデータを読めなかった（実体 {}）", entityId, e);
            } finally {
                buf.release();
            }
        });
    }
}
