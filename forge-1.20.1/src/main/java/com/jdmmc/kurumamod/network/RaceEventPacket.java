package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * サーバー → クライアント。ラップ計測とスタート信号の合図。
 *
 * <p><b>走行中のタイムは毎ティック送らない。</b>「ラップが始まった」を送れば、あとは
 * クライアントが自分で数えられる。確定したタイムはラップ完了時にサーバーの値で上書きされるので、
 * 表示のずれは残らない。</p>
 *
 * <p>デルタ（自己ベストとの差）も<b>サーバーが出して送る</b>。比較相手はベスト走行の
 * 通過時刻で、それを持っているのはサーバーだからで、クライアントへ配って計算させる理由がない。</p>
 *
 * @param type        何が起きたか
 * @param lights      点灯しているスタート信号の数（{@link Type#COUNTDOWN} のとき）
 * @param frozen      カウントダウン中で車を動かせないか
 * @param lap         今の周回（1 から）
 * @param totalLaps   規定周回数。タイムアタックでは 0
 * @param millis      直前のラップタイム、あるいは区間通過時点の経過 [ms]
 * @param bestMillis  ベストラップ [ms]
 * @param best        今のラップでベストを更新したか
 * @param place       完走順位（{@link Type#FINISHED} のとき）
 * @param total       ゴールまでの総時間 [ms]。<b>ラップタイムとは別に要る</b>——
 *                    ゴールした人の表示はここで止めるので、最終ラップだけでは足りない
 * @param sector      区間の番号（1 から）。{@link Type#SPLIT} のとき
 * @param sectorCount 区間の総数（チェックポイント数 + 1）
 * @param delta       自己ベストの同じ地点との差 [ms]。負なら速い
 * @param hasDelta    比較できるベストがあるか。無ければ {@code delta} は無意味
 */
public record RaceEventPacket(Type type, int lights, boolean frozen, int lap, int totalLaps,
                              long millis, long bestMillis, boolean best, int place,
                              int sector, int sectorCount, long delta, boolean hasDelta,
                              long total) {

    public enum Type {
        /** スタート信号の状態が変わった。 */
        COUNTDOWN,
        /** 計測が始まった。 */
        LAP_STARTED,
        /** チェックポイントを通った。 */
        SPLIT,
        /** 1 周終わった。 */
        LAP_COMPLETED,
        /**
         * ラインは通ったが周回として認められなかった。
         *
         * <p><b>黙って数えないのがいちばん悪い。</b>走っている本人には「1 周したのに
         * カウントが増えない」としか見えず、規定より 1 周多く走らされた理由が分からない。</p>
         */
        LAP_VOID,
        /** 規定周回を走りきった。 */
        FINISHED,
        /** 表示を消す。 */
        CLEARED
    }

    private static final Type[] TYPES = Type.values();

    public static RaceEventPacket countdown(int lights, boolean frozen) {
        return new RaceEventPacket(Type.COUNTDOWN, lights, frozen, 0, 0, 0, 0, false, 0,
                0, 0, 0, false, 0);
    }

    /** @param sectorCount 区間の総数（チェックポイント数 + 1）。通過数の表示に使う */
    public static RaceEventPacket lapStarted(int lap, int totalLaps, int sectorCount) {
        return new RaceEventPacket(Type.LAP_STARTED, 0, false, lap, totalLaps, 0, 0, false, 0,
                0, sectorCount, 0, false, 0);
    }

    /**
     * 周回として認めなかった合図。
     *
     * <p>理由は<b>数から分かる</b>ので旗を増やさない。通ったチェックポイントが足りなければ
     * 取りこぼし、足りているならラップが短すぎたということ。</p>
     *
     * @param passed      通ったチェックポイントの数
     * @param sectorCount 区間の総数（チェックポイント数 + 1）
     */
    public static RaceEventPacket lapVoid(int passed, int sectorCount) {
        return new RaceEventPacket(Type.LAP_VOID, 0, false, 0, 0, 0, 0, false, 0,
                passed, sectorCount, 0, false, 0);
    }

    /**
     * チェックポイント通過。
     *
     * @param elapsed ラップ開始からの経過 [ms]
     */
    public static RaceEventPacket split(int sector, int sectorCount, long elapsed,
                                        long delta, boolean hasDelta) {
        return new RaceEventPacket(Type.SPLIT, 0, false, 0, 0, elapsed, 0, false, 0,
                sector, sectorCount, delta, hasDelta, 0);
    }

    public static RaceEventPacket lapCompleted(int lap, int totalLaps, long millis,
                                               long bestMillis, boolean best,
                                               long delta, boolean hasDelta) {
        return new RaceEventPacket(Type.LAP_COMPLETED, 0, false, lap, totalLaps,
                millis, bestMillis, best, 0, 0, 0, delta, hasDelta, 0);
    }

    public static RaceEventPacket finished(int place, long millis, long bestMillis,
                                           long delta, boolean hasDelta, long total) {
        return new RaceEventPacket(Type.FINISHED, 0, false, 0, 0, millis, bestMillis, false, place,
                0, 0, delta, hasDelta, total);
    }

    public static RaceEventPacket cleared() {
        return new RaceEventPacket(Type.CLEARED, 0, false, 0, 0, 0, 0, false, 0,
                0, 0, 0, false, 0);
    }

    public RaceEventPacket(FriendlyByteBuf buf) {
        this(TYPES[buf.readByte()], buf.readByte(), buf.readBoolean(), buf.readVarInt(),
                buf.readVarInt(), buf.readLong(), buf.readLong(), buf.readBoolean(), buf.readByte(),
                buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readBoolean(),
                buf.readLong());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(type.ordinal());
        buf.writeByte(lights);
        buf.writeBoolean(frozen);
        buf.writeVarInt(lap);
        buf.writeVarInt(totalLaps);
        buf.writeLong(millis);
        buf.writeLong(bestMillis);
        buf.writeBoolean(best);
        buf.writeByte(place);
        buf.writeVarInt(sector);
        buf.writeVarInt(sectorCount);
        buf.writeLong(delta);
        buf.writeBoolean(hasDelta);
        buf.writeLong(total);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyRaceEvent(this)));
        context.setPacketHandled(true);
    }
}
