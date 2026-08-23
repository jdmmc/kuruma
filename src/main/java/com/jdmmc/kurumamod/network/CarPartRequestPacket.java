package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * クライアント → サーバー。換装の<b>要求</b>。
 *
 * <p><b>要求であって結果ではない。</b>諸元（{@link CarTuningPacket}）はクライアントが解いた
 * 結果をサーバーが受け取るだけだが、装着状態は<b>サーバーが決める</b>——手元にあるだけの
 * 部品を勝手に履けてしまうと、他の人には見えない部品を履いた車が走ることになる。
 * サーバーが知らない部品と、場所の合わない部品はここで弾く。</p>
 *
 * <p>受け付けるのは<b>運転している本人</b>から届いたものだけ。同乗者や、外から見ている人が
 * 他人の車を換装できてはいけない。</p>
 *
 * <p>運ぶのは<b>その場所の状態まるごと</b>（部品と調整値）。部品だけ・角度だけの
 * パケットに分けると、片方を送ったときにもう片方を消してしまわないよう両側で気を使うことになる。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param slot     付け替える場所
 * @param setting  その場所をどうしたいか（部品と、キャンバー角の上書き）
 */
public record CarPartRequestPacket(int entityId, PartSlot slot, PartFitment.Setting setting) {

    public CarPartRequestPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), PartSlot.byName(buf.readUtf()), PartFitment.readSetting(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeUtf(slot.getSerializedName());
        PartFitment.writeSetting(buf, setting);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null || slot == null) {
                return; // slot が null なのは、送り手だけが知っている場所を指しているとき
            }

            ResourceLocation partId = setting.partId();
            if (partId != null) {
                CarPart part = CarParts.get(partId);
                // 知らない部品と、場所の合わない部品は履かせない
                if (part == null || part.slot() != slot) {
                    return;
                }
            }
            // 角度は弾かずに押し込む（範囲は PartFitment.readSetting が既に掛けている）。
            // 部品と違って「無い値」というものが無いので、弾くのではなく丸めるのが正しい

            Entity entity = sender.level().getEntity(entityId);
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                // setFitment がそのまま周囲のクライアントへ配る
                car.setFitment(car.getFitment().with(slot, setting));
            }
        });
        context.setPacketHandled(true);
    }
}
