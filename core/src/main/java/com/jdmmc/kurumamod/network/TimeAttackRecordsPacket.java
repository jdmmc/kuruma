package com.jdmmc.kurumamod.network;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。1 つのコースのタイムアタックの記録（クラスなしと各クラスのランキング）。
 *
 * <p>K のタイムアタックの画面がコースを選んだときに頼む（{@link RaceActionPacket.Action#RECORDS}）。
 * 各ランキングは上位 {@link #TOP} 人と、そこに入っていなければ受け取った人の行。</p>
 *
 * @param course コースの名前
 * @param boards 先頭がクラスなし（{@code raceClass} が空）、そのあとクラスの並び順
 */
public record TimeAttackRecordsPacket(String course, List<Board> boards) implements RacePayload {

    /** 1 つのランキングに載せる人数。 */
    public static final int TOP = 5;

    /** ランキングの 1 行。{@code place} は 1 から。 */
    public record Row(int place, String name, long millis, boolean you, java.util.UUID player) {
    }

    /**
     * 1 つのランキング。
     *
     * @param raceClass クラスの名前。空ならクラスなし
     * @param total     記録を持っている人数
     * @param rows      上位と、入っていなければ自分の行（順位の順）
     */
    public record Board(String raceClass, int total, List<Row> rows) {
    }

    public TimeAttackRecordsPacket(RaceBuf buf) {
        this(buf.readUtf(256), readBoards(buf));
    }

    /**
     * ランキングの数の上限。<b>書く側も同じ数で切る</b>（読む側だけで切ると、上限を超えたときに残りのバイトを
     * 読み違えて切断された）。
     */
    private static final int MAX_BOARDS = 128;

    private static int readCount(RaceBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw buf.invalid("タイムアタックの記録の件数が範囲外です: " + count);
        }
        return count;
    }

    private static List<Board> readBoards(RaceBuf buf) {
        int count = readCount(buf, MAX_BOARDS);
        List<Board> boards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String raceClass = buf.readUtf(256);
            int total = buf.readVarInt();
            int rowCount = readCount(buf, TOP + 1);
            List<Row> rows = new ArrayList<>(rowCount);
            for (int j = 0; j < rowCount; j++) {
                rows.add(new Row(buf.readVarInt(), buf.readUtf(64), buf.readLong(), buf.readBoolean(), buf.readUUID()));
            }
            boards.add(new Board(raceClass, total, rows));
        }
        return boards;
    }

    public void encode(RaceBuf buf) {
        buf.writeUtf(course, 256);
        List<Board> sentBoards = boards.subList(0, Math.min(MAX_BOARDS, boards.size()));
        buf.writeVarInt(sentBoards.size());
        for (Board board : sentBoards) {
            buf.writeUtf(board.raceClass(), 256);
            buf.writeVarInt(board.total());
            List<Row> rows = board.rows().subList(0, Math.min(TOP + 1, board.rows().size()));
            buf.writeVarInt(rows.size());
            for (Row row : rows) {
                buf.writeVarInt(row.place());
                buf.writeUtf(row.name(), 64);
                buf.writeLong(row.millis());
                buf.writeBoolean(row.you());
                buf.writeUUID(row.player());
            }
        }
    }

}
