package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlateRules;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * クライアント → サーバー。ナンバーの中身（打った字と付ける位置）の<b>要求</b>。
 *
 * <p>どのプレートを付けるかは換装（{@link CarPartRequestPacket}）が運ぶ。こちらは塗装と同じく、
 * 受け付けるのは<b>運転している本人</b>から届いたものだけ。</p>
 *
 * <h2>他人の画面に出る字なので、2 段で検査する</h2>
 *
 * <ol>
 *   <li><b>様式の決まり</b>（{@link PlateRules}）… 付けているプレートの部品の {@code data/} 側に
 *       書かれた欄の決まりで、使えない字と長すぎる字を落とす。サーバーが部品の一覧を持っているので、
 *       クライアントの手元の検査を当てにしない</li>
 *   <li><b>看板と同じフィルタ</b>（{@code ServerPlayer#getTextFilter()}）… サーバーが文字の
 *       フィルタを有効にしていれば、看板に書いた字と同じ扱いで伏せる。無効なら素通し</li>
 * </ol>
 *
 * <p>フィルタは別スレッドで返ってくることがあるので、結果を受けてから<b>サーバーのスレッドで</b>車へ書く。
 * 待っている間に車が消えたり運転者が替わったりしていたら捨てる。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param plate    ナンバーの中身
 */
public record CarPlateRequestPacket(int entityId, PlateLook plate) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CarPlateRequestPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "car_plate_request"));

    public static final StreamCodec<FriendlyByteBuf, CarPlateRequestPacket> STREAM_CODEC =
            StreamCodec.ofMember(CarPlateRequestPacket::encode, CarPlateRequestPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public CarPlateRequestPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), PartFitment.readPlate(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        PartFitment.writePlate(buf, plate);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = context.player() instanceof ServerPlayer p ? p : null;
            if (sender == null || !(sender.level().getEntity(entityId) instanceof CarEntity car)
                    || car.getControllingPassenger() != sender) {
                return;
            }
            Map<String, String> text = rulesOf(car).clean(plate.text());
            List<String> keys = new ArrayList<>(text.keySet());
            List<String> values = keys.stream().map(text::get).toList();
            sender.getTextFilter().processMessageBundle(values).thenAcceptAsync(filtered -> {
                Entity entity = sender.level().getEntity(entityId);
                if (!(entity instanceof CarEntity current) || current.getControllingPassenger() != sender) {
                    return;
                }
                Map<String, String> passed = new LinkedHashMap<>();
                for (int i = 0; i < keys.size() && i < filtered.size(); i++) {
                    FilteredText result = filtered.get(i);
                    passed.put(keys.get(i), result.filteredOrEmpty());
                }
                // 伏せ字（#）が欄の決まりに合わなければここで落ちる。空になった欄は既定の文字に戻る
                PlateLook cleaned = new PlateLook(rulesOf(current).clean(passed), plate.front(), plate.rear());
                // setFitment がそのまま周囲のクライアントへ配る
                current.setFitment(current.getFitment().withPlate(cleaned));
            }, sender.server);
        });
    }

    /** 付けているプレートの欄の決まり。付けていない・知らない部品なら決まり無し（最低限の検査だけ）。 */
    private static PlateRules rulesOf(CarEntity car) {
        ResourceLocation id = car.getEffectiveFitment().getPart(PartSlot.PLATE);
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null || part.plate() == null ? PlateRules.NONE : part.plate();
    }
}
