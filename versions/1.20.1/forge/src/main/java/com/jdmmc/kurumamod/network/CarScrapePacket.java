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
 * 車体が地形や他の車と擦れた・ぶつかった合図（火花を出す）。<b>行きと帰りの両方向に使う</b>（{@link CarImpactPacket} と同じ道）。
 *
 * <ul>
 *   <li>運転クライアント → サーバー … 擦れに気づけるのは物理を解いている運転者だけ。
 *       自分の画面には先に出し、周りにも届けてもらうためにサーバーへ送る</li>
 *   <li>サーバー → その車を追跡しているクライアント … 運転者本人にも届くが受信側で捨てる。
 *       無人の車はサーバーが物理を解いているので、その場合はサーバー発で全員へ送る</li>
 * </ul>
 *
 * <p>擦れている間は毎ティック飛ぶ。座標は<b>車の位置からのずれ</b>で運ぶ（受け取った側の補間済みの
 * 車の位置へ足すので、車と火花がずれない）。</p>
 *
 * @param offset   擦れている点の、車の位置からのずれ（ワールドの向き）。x, y, z
 * @param normal   壁が押し返す向き（水平）。x, z
 * @param velocity 擦れている点が壁に沿って動く速度 [m/s]。x, y, z
 * @param impact   このティックにぶつかった強さ（近づいていた速さ） [m/s]。擦っているだけなら 0。
 *                 火花をまとめて飛び散らせる量になる
 */
public record CarScrapePacket(int entityId, float[] offset, float[] normal, float[] velocity, float impact) {

    public CarScrapePacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(),
                new float[]{buf.readFloat(), buf.readFloat(), buf.readFloat()},
                new float[]{buf.readFloat(), buf.readFloat()},
                new float[]{buf.readFloat(), buf.readFloat(), buf.readFloat()},
                buf.readFloat());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        for (float value : offset) {
            buf.writeFloat(value);
        }
        for (float value : normal) {
            buf.writeFloat(value);
        }
        for (float value : velocity) {
            buf.writeFloat(value);
        }
        buf.writeFloat(impact);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CarClientPackets.applyScrape(entityId, offset, normal, velocity, impact));
                return;
            }
            Entity entity = sender.level().getEntity(entityId);
            // 運転している本人からのものだけ中継する
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                broadcast(car, offset, normal, velocity, impact);
            }
        });
        context.setPacketHandled(true);
    }

    /** その車を追跡しているクライアントへ配る。運転者本人のぶんは受信側で捨てる。 */
    public static void broadcast(CarEntity car, float[] offset, float[] normal, float[] velocity, float impact) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> car),
                new CarScrapePacket(car.getId(), offset, normal, velocity, impact));
    }
}
