package com.jdmmc.kurumamod.network;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。レースの参加者一覧・順位表・リザルト。
 *
 * <p>3 つの場面で同じ形を使い回す。並びと数字の意味が変わるだけなので、
 * {@link Phase} で切り替える。</p>
 *
 * @param phase  どの場面か
 * @param course コース名
 * @param laps   規定周回数
 * @param rows   並び順（受付中は入った順、走行中とリザルトは順位順）
 */
public record RaceStandingsPacket(Phase phase, String course, int laps, List<Row> rows, long elapsedMillis) implements RacePayload {

    public enum Phase {
        /** エントリー受付中。名前だけ。 */
        ENTRY,
        /** 走行中。先頭は経過時間、以降は先頭との差。 */
        RUNNING,
        /** リザルト。先頭は総時間、以降は先頭との差。完走していない人は周回数だけ。 */
        RESULT
    }

    private static final Phase[] PHASES = Phase.values();

    /**
     * 1 人ぶん。
     *
     * @param name     表示名
     * @param laps     完了した周回数
     * @param millis   先頭なら時間、それ以外なら先頭との差 [ms]
     * @param leader   先頭か
     * @param finished 完走したか。リザルトで完走していない人を分けるのに使う
     * @param started  スタートラインを跨いだか。<b>まだの参加者も順位表に出す</b>ので、
     *                 タイムの代わりに「未スタート」と出すために要る
     */
    public record Row(String name, int laps, long millis, boolean leader, boolean finished,
                      boolean started, boolean retired) {

        /** リタイアしていない行。 */
        public Row(String name, int laps, long millis, boolean leader, boolean finished, boolean started) {
            this(name, laps, millis, leader, finished, started, false);
        }
    }

    /** 経過時間の要らない場面（受付・リザルト）。 */
    public RaceStandingsPacket(Phase phase, String course, int laps, List<Row> rows) {
        this(phase, course, laps, rows, 0L);
    }

    public RaceStandingsPacket(RaceBuf buf) {
        this(PHASES[buf.readByte()], buf.readUtf(256), buf.readVarInt(), readRows(buf), buf.readLong());
    }

    /**
     * 行の数の上限（ロビーの参加者の上限と同じ）。<b>書く側も同じ数で切る。</b>届いた数をそのまま配列の大きさに
     * すると、壊れた数で巨大な配列を作ろうとして落ちる（ほかのパケットとそろえてある）。
     */
    private static final int MAX_ROWS = 256;

    private static List<Row> readRows(RaceBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ROWS) {
            throw buf.invalid("順位表の行の数が範囲外です: " + count);
        }
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Row(buf.readUtf(32), buf.readVarInt(), buf.readLong(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
        }
        return rows;
    }

    public void encode(RaceBuf buf) {
        buf.writeByte(phase.ordinal());
        buf.writeUtf(course, 256);
        buf.writeVarInt(laps);
        List<Row> sent = rows.subList(0, Math.min(MAX_ROWS, rows.size()));
        buf.writeVarInt(sent.size());
        for (Row row : sent) {
            buf.writeUtf(row.name(), 32);
            buf.writeVarInt(row.laps());
            buf.writeLong(row.millis());
            buf.writeBoolean(row.leader());
            buf.writeBoolean(row.finished());
            buf.writeBoolean(row.started());
            buf.writeBoolean(row.retired());
        }
        // 号砲からの経過時間 [ms]。観戦者の時計に使う（走っている人は自分のラップを測っている）
        buf.writeLong(elapsedMillis);
    }

}
