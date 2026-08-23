package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.part.PartFitment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * サーバー → 追跡しているクライアント。車 1 台の装着状態を配る。
 *
 * <p>車が現れるときはスポーンデータが運ぶので、これが要るのは<b>走行中に換装したとき</b>だけ。
 * 配らないと、換えた本人の画面でしか見た目が変わらない。</p>
 *
 * <p><b>本人のぶんも捨てない。</b>諸元は調整画面（クライアント）が正なので送り返されたぶんを
 * 捨てるが、装着状態は<b>サーバーが決める</b>（部品の一覧と突き合わせて弾く）。届いたものが正。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param parts    その車の装着状態
 */
public record CarPartsPacket(int entityId, PartFitment parts) {

    public CarPartsPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), PartFitment.read(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        parts.write(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyParts(entityId, parts)));
        context.setPacketHandled(true);
    }
}
