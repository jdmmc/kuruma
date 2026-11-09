package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.race.RaceManager;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * クライアント → サーバー。レースの画面とキー（K）からの操作。
 *
 * <p>コマンドだった頃と同じ処理を呼ぶ。<b>できるかどうかを決めるのはサーバー</b>
 * なので、画面はボタンを押しただけで結果を決めつけない（次の {@link RaceLobbyPacket} で分かる）。</p>
 *
 * @param action     何をするか
 * @param course     開くコース（{@link Action#OPEN} のとき）
 * @param laps       周回数（{@link Action#OPEN}・{@link Action#UPDATE} のとき）
 * @param regulation 決まり（{@link Action#OPEN}・{@link Action#UPDATE} のとき。{@link Regulation#toArray}）
 */
public record RaceActionPacket(Action action, String course, int laps, double[] regulation) {

    public enum Action {
        /** 今の様子を送ってほしい（主催者画面を開いたとき）。 */
        QUERY,
        /** レースを開く。 */
        OPEN,
        /** 決まりと周回数を変える（受付中だけ）。 */
        UPDATE,
        /** 参加する。 */
        JOIN,
        /** 抜ける。 */
        LEAVE,
        /** スタートする。 */
        START,
        /** 中止する・終える。 */
        STOP,
        /** 観戦に切り替える（エントリーしていれば取り消す）。 */
        SPECTATE,
        /** 観戦をやめる。 */
        UNSPECTATE,
        /** 参加者をエントリーから外す（主催者だけ）。{@code course} に外す人の名前を入れる。 */
        KICK,
        /** クラスを保存する。{@code course} に名前、{@code regulation} に中身。 */
        SAVE_CLASS,
        /** クラスを消す。{@code course} に名前。 */
        DELETE_CLASS
    }

    private static final Action[] ACTIONS = Action.values();

    public static RaceActionPacket of(Action action) {
        return new RaceActionPacket(action, "", 0, new double[0]);
    }

    public RaceActionPacket(FriendlyByteBuf buf) {
        this(ACTIONS[buf.readByte()], buf.readUtf(256), buf.readVarInt(), readDoubles(buf));
    }

    private static double[] readDoubles(FriendlyByteBuf buf) {
        int count = Math.min(64, buf.readVarInt());
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readDouble();
        }
        return values;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action.ordinal());
        buf.writeUtf(course, 256);
        buf.writeVarInt(laps);
        buf.writeVarInt(regulation.length);
        for (double value : regulation) {
            buf.writeDouble(value);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                RaceManager.handleAction(player, this);
            }
        });
        context.setPacketHandled(true);
    }
}
