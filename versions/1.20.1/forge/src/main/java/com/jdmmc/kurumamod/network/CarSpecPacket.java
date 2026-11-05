package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * サーバー → 周囲のクライアント。車の諸元を配る。
 *
 * <p>諸元は見た目に効く。ホイールベースは拡大率に、ストロークとバネはタイヤの位置に効くので、
 * <b>配らないと他人の車だけ既定の寸法で描かれる</b>。</p>
 *
 * <p>車がクライアントに現れたときは {@code IEntityAdditionalSpawnData} が同じ内容を運ぶ。
 * こちらは<b>運転者が走行中に調整画面をいじったとき</b>のためにある。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param spec     配る諸元
 */
public record CarSpecPacket(int entityId, CarSpec spec) {

    public CarSpecPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), CarSpecCodec.read(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        CarSpecCodec.write(buf, spec);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applySpec(entityId, spec)));
        context.setPacketHandled(true);
    }
}
