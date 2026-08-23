package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartSlot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * サーバー → クライアント。読み込まれている部品を丸ごと配る。
 *
 * <p>{@link CarTypesPacket} とまったく同じ場面（ログイン時とデータパックの再読み込み時）で
 * 送る。部品も走行中に増えないので、差分ではなく毎回まるごと。</p>
 *
 * <p><b>クライアントが自分でデータパックを読まないのが要点。</b>マルチプレイで何を履けるか
 * 決めるのはサーバーであって手元のカーパックではない。配られたものだけを見ることで、
 * サーバーが知らない部品が換装画面に並ぶことがなくなる。</p>
 *
 * <p><b>これが運ぶのは選べる部品の一覧であって、走っている車が何を履いているかではない。</b>
 * 後者は車のスポーンデータと {@link CarPartsPacket} が運ぶ（車種と諸元の関係と同じ）。</p>
 */
public record CarPartTypesPacket(List<CarPart> parts) {

    public CarPartTypesPacket(FriendlyByteBuf buf) {
        this(read(buf));
    }

    private static List<CarPart> read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<CarPart> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            var id = buf.readResourceLocation();
            String name = buf.readUtf();
            int order = buf.readVarInt();
            PartSlot slot = PartSlot.byName(name);
            if (slot != null) {
                parts.add(new CarPart(id, slot, order));
            }
            // 知らない場所の部品は落とす。並べようがないので、黙って無視してよい
        }
        return parts;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(parts.size());
        for (CarPart part : parts) {
            buf.writeResourceLocation(part.id());
            buf.writeUtf(part.slot().getSerializedName());
            buf.writeVarInt(part.order());
        }
    }

    /** いま読み込まれている部品を全部詰めたパケット。 */
    public static CarPartTypesPacket current() {
        return new CarPartTypesPacket(CarParts.all());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyCarParts(parts)));
        context.setPacketHandled(true);
    }
}
