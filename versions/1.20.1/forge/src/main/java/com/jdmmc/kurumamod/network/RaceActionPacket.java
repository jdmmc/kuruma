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
 * @param extra      操作ごとの 2 つ目の値（{@link Action#DELETE_RECORD} のとき）
 */
public record RaceActionPacket(Action action, String course, int laps, double[] regulation,
                               String extra) {

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
        DELETE_CLASS,
        /**
         * タイムアタックで走るクラスを宣言する。{@code course} にクラスの名前（空ならやめる）。
         * クライアントがサーバーごとに覚えていて、入ったときにも送り直す。
         */
        DECLARE_CLASS,
        /** タイムアタックの記録を送ってほしい。{@code course} にコースの名前。 */
        RECORDS,
        /** クラスを並びの中で 1 つ動かす。{@code course} に名前、{@code laps} に向き（-1 で前、1 で後ろ）。 */
        MOVE_CLASS,
        /** 計測中のタイムアタックをやめる（K の「計測をやめる」）。 */
        RESET_TIME_ATTACK,
        /**
         * タイムアタックの記録を 1 つ消す（OP だけ）。{@code course} にコース、{@code extra} に
         * 「消す人の UUID」と「クラスの名前（空ならクラスなし）」を空白 1 つで区切って入れる。
         */
        DELETE_RECORD
    }

    private static final Action[] ACTIONS = Action.values();
    /** 決まりの値の数の上限（読むとき）。 */
    private static final int MAX_VALUES = 64;

    /**
     * 画面から送られうる形か。決まりは空（使わない操作）か {@link Regulation#toArray} と同じ長さで、
     * どれも数であること。<b>外れていたらサーバーは何もしない</b>（改造したクライアント）。
     */
    public boolean wellFormed() {
        if (regulation.length != 0 && regulation.length != Regulation.DEFAULT.toArray().length) {
            return false;
        }
        for (double value : regulation) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    public static RaceActionPacket of(Action action) {
        return new RaceActionPacket(action, "", 0, new double[0]);
    }

    /** 2 つ目の値を使わない操作。 */
    public RaceActionPacket(Action action, String course, int laps, double[] regulation) {
        this(action, course, laps, regulation, "");
    }

    public RaceActionPacket(FriendlyByteBuf buf) {
        this(readAction(buf), buf.readUtf(256), buf.readVarInt(), readDoubles(buf), buf.readUtf(256));
    }

    /**
     * <b>画面からは作れない形のものは読まずに断る</b>（改造したクライアント）。切り詰めて読むと残りのバイトで
     * 読み込みがずれ、範囲外の番号は配列の外を引いた。断ると、送ってきたその人だけが切断される。
     */
    private static Action readAction(FriendlyByteBuf buf) {
        int index = buf.readByte();
        if (index < 0 || index >= ACTIONS.length) {
            throw new io.netty.handler.codec.DecoderException("レースの操作の番号が範囲外です: " + index);
        }
        return ACTIONS[index];
    }

    private static double[] readDoubles(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_VALUES) {
            throw new io.netty.handler.codec.DecoderException("レースの決まりの数が範囲外です: " + count);
        }
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
        buf.writeUtf(extra, 256);
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
