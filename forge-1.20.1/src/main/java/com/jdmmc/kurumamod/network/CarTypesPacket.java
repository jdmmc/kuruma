package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * サーバー → クライアント。読み込まれている車種を丸ごと配る。
 *
 * <p>送るのはログイン時とデータパックの再読み込み時（{@code OnDatapackSyncEvent}）。
 * 車種は走行中に増えないので、差分ではなく毎回まるごと送る。</p>
 *
 * <p><b>クライアントが自分でデータパックを読まないのが要点。</b>シングルプレイなら
 * 同じプロセスなので読めてしまうが、マルチプレイで車種を決めるのはサーバーであって
 * 手元のカーパックではない。「配られたものだけを見る」で揃えておけば、
 * サーバーに無い車がクリエイティブタブに並ぶ、ということが起きない。</p>
 *
 * <p>クライアントが車種を知る必要があるのは次の 3 つ。<b>走っている車の諸元そのものは
 * これとは別に、車のスポーンデータと {@link CarSpecPacket} が運ぶ</b>（調整画面でいじった
 * 結果は車種の諸元とは違うものになるため）。</p>
 *
 * <ul>
 *   <li>クリエイティブタブに車種ぶんのアイテムを並べる</li>
 *   <li>アイテムと車の名前を出す</li>
 *   <li>調整画面の「戻す」で、既定値ではなく<b>その車種の値</b>へ戻す</li>
 * </ul>
 */
public record CarTypesPacket(List<CarType> types) {

    public CarTypesPacket(FriendlyByteBuf buf) {
        this(read(buf));
    }

    private static List<CarType> read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<CarType> types = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            types.add(new CarType(buf.readResourceLocation(), CarSpecCodec.read(buf), buf.readVarInt()));
        }
        return types;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(types.size());
        for (CarType type : types) {
            buf.writeResourceLocation(type.id());
            CarSpecCodec.write(buf, type.spec());
            buf.writeVarInt(type.order());
        }
    }

    /** いま読み込まれている車種を全部詰めたパケット。 */
    public static CarTypesPacket current() {
        return new CarTypesPacket(CarTypes.all());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyCarTypes(types)));
        context.setPacketHandled(true);
    }
}
