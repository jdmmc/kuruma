package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * ぶつけられた車を弾き飛ばす。<b>行きと帰りの両方向に使う。</b>
 *
 * <p><b>ぶつけた側は相手の速度を直接は書けない。</b>物理はそれぞれの運転クライアントが
 * 解いているので、こちらで相手の車を動かしても、次のティックに相手自身の計算で
 * 上書きされる。そこで<b>弾かれぶんを相手の持ち主へ届けて、そちらの物理へ足してもらう</b>。</p>
 *
 * <ul>
 *   <li>ぶつけた運転クライアント → サーバー … 相手に渡す速度差を送る</li>
 *   <li>サーバー → 相手を運転しているクライアント … そのまま回す。<b>無人の車なら
 *       サーバー自身が物理を解いている</b>ので、その場でサーバーが足す</li>
 * </ul>
 *
 * <p>クライアント権威なので<b>値は信用できない</b>。サーバーで大きさを頭打ちにしてある
 * （それ以上は物理的に出ないので、通しても得をしない）。</p>
 *
 * @param entityId 弾かれる側の車
 * @param dx       ワールド座標での速度差の X 成分 [m/s]
 * @param dz       同 Z 成分 [m/s]
 * @param dYawRate ヨー角速度の変化 [rad/s]。側面に当たったときに回るぶん
 */
public record CarPushPacket(int entityId, float dx, float dz, float dYawRate) {

    /** 受け付ける速度差の上限 [m/s]。 */
    private static final float MAX_DELTA_SPEED = 30.0F;
    /** 受け付けるヨー角速度の変化の上限 [rad/s]。 */
    private static final float MAX_DELTA_YAW_RATE = 8.0F;

    public CarPushPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeFloat(dx);
        buf.writeFloat(dz);
        buf.writeFloat(dYawRate);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CarClientPackets.applyPush(entityId, dx, dz, dYawRate));
                return;
            }
            // ぶつけた側が本当に車を運転しているか確かめる
            if (!(sender.getVehicle() instanceof CarEntity source)
                    || source.getControllingPassenger() != sender) {
                return;
            }
            Entity entity = sender.level().getEntity(entityId);
            if (!(entity instanceof CarEntity target) || target == source) {
                return;
            }
            CarPushPacket clamped = clamp();
            if (target.getControllingPassenger() instanceof Player driver) {
                if (driver instanceof ServerPlayer serverDriver) {
                    KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverDriver), clamped);
                }
            } else {
                // 無人の車はサーバーが解いているので、ここで足す
                target.applyPush(clamped.dx, clamped.dz, clamped.dYawRate);
            }
        });
        context.setPacketHandled(true);
    }

    private CarPushPacket clamp() {
        double length = Math.hypot(dx, dz);
        double scale = length > MAX_DELTA_SPEED ? MAX_DELTA_SPEED / length : 1.0;
        return new CarPushPacket(entityId, (float) (dx * scale), (float) (dz * scale),
                Math.max(-MAX_DELTA_YAW_RATE, Math.min(MAX_DELTA_YAW_RATE, dYawRate)));
    }
}
