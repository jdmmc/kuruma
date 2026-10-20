package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartFitment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * クライアント → サーバー。塗装の<b>要求</b>。
 *
 * <p>換装（{@link CarPartRequestPacket}）と同じく、受け付けるのは<b>運転している本人</b>から
 * 届いたものだけ。通ったら {@code setFitment} が {@link CarPartsPacket} で周りへ配る。</p>
 *
 * <p><b>部品と違って一覧と突き合わせない。</b>塗装の一覧は {@code vehicles/<car>.json}
 * （クライアントだけが読む {@code assets/}）にあり、サーバーは知らない。形の合わない id だけ
 * 既定の色に読み替え（{@link PartFitment#isValidPaint}）、あとは覚えて配る。知らない id を
 * 受け取ったクライアントは既定の色で描く。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param paint    塗装の id。null で既定の色へ戻す
 */
public record CarPaintRequestPacket(int entityId, @Nullable String paint) {

    public CarPaintRequestPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readBoolean() ? buf.readUtf(PartFitment.PAINT_MAX_LENGTH) : null);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(paint != null);
        if (paint != null) {
            buf.writeUtf(paint, PartFitment.PAINT_MAX_LENGTH);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            Entity entity = sender.level().getEntity(entityId);
            if (!(entity instanceof CarEntity car) || car.getControllingPassenger() != sender) {
                return;
            }
            // setFitment がそのまま周囲のクライアントへ配る
            car.setFitment(car.getFitment().withPaint(paint));
        });
        context.setPacketHandled(true);
    }
}
