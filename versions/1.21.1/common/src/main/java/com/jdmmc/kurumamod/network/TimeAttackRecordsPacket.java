package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.CarClientPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。1 つのコースのタイムアタックの記録（総合と各クラスのランキング）。
 *
 * <p>K のタイムアタックの画面がコースを選んだときに頼む（{@link RaceActionPacket.Action#RECORDS}）。
 * 各ランキングは上位 {@link #TOP} 人と、そこに入っていなければ受け取った人の行。</p>
 *
 * @param course コースの名前
 * @param boards 先頭が総合（{@code raceClass} が空）、そのあとクラスの並び順
 */
public record TimeAttackRecordsPacket(String course, List<Board> boards) implements CustomPacketPayload {

    /** 1 つのランキングに載せる人数。 */
    public static final int TOP = 5;

    public static final CustomPacketPayload.Type<TimeAttackRecordsPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "time_attack_records"));

    public static final StreamCodec<FriendlyByteBuf, TimeAttackRecordsPacket> STREAM_CODEC =
            StreamCodec.ofMember(TimeAttackRecordsPacket::encode, TimeAttackRecordsPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** ランキングの 1 行。{@code place} は 1 から。 */
    public record Row(int place, String name, long millis, boolean you) {
    }

    /**
     * 1 つのランキング。
     *
     * @param raceClass クラスの名前。空なら総合
     * @param total     記録を持っている人数
     * @param rows      上位と、入っていなければ自分の行（順位の順）
     */
    public record Board(String raceClass, int total, List<Row> rows) {
    }

    public TimeAttackRecordsPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(256), readBoards(buf));
    }

    private static List<Board> readBoards(FriendlyByteBuf buf) {
        int count = Math.min(128, buf.readVarInt());
        List<Board> boards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String raceClass = buf.readUtf(256);
            int total = buf.readVarInt();
            int rowCount = Math.min(TOP + 1, buf.readVarInt());
            List<Row> rows = new ArrayList<>(rowCount);
            for (int j = 0; j < rowCount; j++) {
                rows.add(new Row(buf.readVarInt(), buf.readUtf(64), buf.readLong(), buf.readBoolean()));
            }
            boards.add(new Board(raceClass, total, rows));
        }
        return boards;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(course, 256);
        buf.writeVarInt(boards.size());
        for (Board board : boards) {
            buf.writeUtf(board.raceClass(), 256);
            buf.writeVarInt(board.total());
            buf.writeVarInt(board.rows().size());
            for (Row row : board.rows()) {
                buf.writeVarInt(row.place());
                buf.writeUtf(row.name(), 64);
                buf.writeLong(row.millis());
                buf.writeBoolean(row.you());
            }
        }
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> CarClientPackets.applyTimeAttackRecords(this));
    }
}
