package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 運転者の画面で走っているゴーストを、<b>同じ車の助手席の人にも見せる</b>。行きと帰りの両方向に使う。
 *
 * <ul>
 *   <li>運転クライアント → サーバー … ゴーストは運転者の手元の記録から運転者の画面で再生している
 *       （{@code CarGhosts}）。その 1 コマずつを送る</li>
 *   <li>サーバー → 同じ車のほかの乗員 … 送り主がその車の運転者のときだけ中継する。ほかのプレイヤーには送らない</li>
 * </ul>
 *
 * <p>記録そのもの（ファイル）は送らない。<b>コマは運転者の車の状態と同じ遅れで届く</b>ので、助手席から見た
 * 「ゴーストと自分の車の間隔」は運転者が見ているものとほぼ同じになる。</p>
 *
 * @param carEntityId 乗っている車（ゴーストではない）
 * @param kind        {@link #LOOK}・{@link #FRAME}・{@link #STOP}
 * @param look        見た目（車種・諸元・装着）。{@link #LOOK} のときだけ
 * @param state       1 コマの状態。{@link #FRAME} のときだけ（位置は x・y・z）
 */
public record GhostRidePacket(int carEntityId, byte kind, @Nullable CompoundTag look,
                              double x, double y, double z, @Nullable CarStatePacket state) {

    /** ゴーストの見た目。走り出すとき・乗員が変わったとき・ときどき送り直す。 */
    public static final byte LOOK = 0;
    /** 1 コマ。運転者の画面でゴーストが 1 ティック進むたびに。 */
    public static final byte FRAME = 1;
    /** 走り終えた・止めた。 */
    public static final byte STOP = 2;

    public static GhostRidePacket look(int carEntityId, CompoundTag look) {
        return new GhostRidePacket(carEntityId, LOOK, look, 0.0, 0.0, 0.0, null);
    }

    public static GhostRidePacket frame(int carEntityId, double x, double y, double z, CarStatePacket state) {
        return new GhostRidePacket(carEntityId, FRAME, null, x, y, z, state);
    }

    public static GhostRidePacket stop(int carEntityId) {
        return new GhostRidePacket(carEntityId, STOP, null, 0.0, 0.0, 0.0, null);
    }

    public GhostRidePacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readByte(), buf);
    }

    private GhostRidePacket(int carEntityId, byte kind, FriendlyByteBuf buf) {
        this(carEntityId, kind,
                kind == LOOK ? buf.readNbt() : null,
                kind == FRAME ? buf.readDouble() : 0.0,
                kind == FRAME ? buf.readDouble() : 0.0,
                kind == FRAME ? buf.readDouble() : 0.0,
                kind == FRAME ? new CarStatePacket(buf) : null);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(carEntityId);
        buf.writeByte(kind);
        if (kind == LOOK) {
            buf.writeNbt(look);
        } else if (kind == FRAME) {
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            state.encode(buf);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CarClientPackets.applyGhostRide(this));
                return;
            }
            Entity entity = sender.level().getEntity(carEntityId);
            // 運転している本人からのものだけ、同じ車のほかの乗員へ
            if (!(entity instanceof CarEntity car) || car.getControllingPassenger() != sender) {
                return;
            }
            for (Entity passenger : car.getPassengers()) {
                if (passenger != sender && passenger instanceof ServerPlayer rider) {
                    KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> rider), this);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
