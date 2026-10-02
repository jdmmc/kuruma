package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * ぶつかった合図。<b>行きと帰りの両方向に使う。</b>
 *
 * <ul>
 *   <li>運転クライアント → サーバー … 物理を解いているのは運転者なので、衝突に気づけるのも運転者だけ。
 *       自分の画面では即座に鳴らして揺らし、同じものを周りにも届けてもらうためにサーバーへ送る</li>
 *   <li>サーバー → その車を追跡しているクライアント … 送り主以外へ中継する。
 *       無人の車はサーバーが物理を解いているので、その場合はサーバー発で全員へ送る</li>
 * </ul>
 *
 * <p>方向を決めずに登録してあるので、{@link #handle} は<b>送り主の有無</b>で自分がどちら側かを判断する。</p>
 *
 * @param entityId 対象の車
 * @param impact   衝突で失った速度 [m/s]。音量とピッチ、画面の揺れの大きさになる
 */
public record CarImpactPacket(int entityId, float impact) {

    public CarImpactPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readFloat());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeFloat(impact);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CarClientPackets.applyImpact(entityId, impact));
                return;
            }
            Entity entity = sender.level().getEntity(entityId);
            // 運転している本人からのものだけ中継する
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                broadcast(car, impact);
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * その車を追跡しているクライアントへ配る。
     *
     * <p>運転者本人にも届くが、<b>本人は既に自分の画面で鳴らしている</b>ので受信側で捨てる
     * （{@code CarClientPackets#applyImpact}）。送り先から除くよりこちらの方が単純で、
     * 無人の車をサーバーが解いているときも同じ道を通せる。</p>
     */
    public static void broadcast(CarEntity car, float impact) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> car),
                new CarImpactPacket(car.getId(), impact));
    }
}
