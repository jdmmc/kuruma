package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
public record RaceStandingsPacket(Phase phase, String course, int laps, List<Row> rows) {

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
                      boolean started) {
    }

    public RaceStandingsPacket(FriendlyByteBuf buf) {
        this(PHASES[buf.readByte()], buf.readUtf(64), buf.readVarInt(), readRows(buf));
    }

    private static List<Row> readRows(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Row(buf.readUtf(32), buf.readVarInt(), buf.readLong(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
        }
        return rows;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(phase.ordinal());
        buf.writeUtf(course, 64);
        buf.writeVarInt(laps);
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeUtf(row.name(), 32);
            buf.writeVarInt(row.laps());
            buf.writeLong(row.millis());
            buf.writeBoolean(row.leader());
            buf.writeBoolean(row.finished());
            buf.writeBoolean(row.started());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyStandings(this)));
        context.setPacketHandled(true);
    }
}
