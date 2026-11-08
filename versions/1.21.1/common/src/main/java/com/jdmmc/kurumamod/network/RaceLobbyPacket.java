package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。そのワールドのレースの様子（受付中か・決まり・参加者と判定）。
 *
 * <p><b>受け取る人ごとに組む。</b>主催者か、参加しているか、自己ベストは受け取る人で変わる。
 * 誘いの通知・主催中の表示・参加したレースの画面・主催者画面は、すべてこれだけを見て描く。</p>
 *
 * <p>受付中とレース中は {@code RaceManager} が 10 ティックごとにそのワールドの全員へ配る。
 * レースが無くなったら {@link Phase#NONE} を 1 回配る。主催者画面を開いたときは、コースの一覧を
 * 知るためにクライアントから頼む（{@link RaceActionPacket.Action#QUERY}）。</p>
 *
 * @param phase       レースの場面
 * @param course      コース名（{@link Phase#NONE} では空）
 * @param laps        規定周回数
 * @param checkpoints チェックポイントの本数
 * @param hostName    主催者の名前
 * @param isHost      受け取った人が主催者か
 * @param joined      受け取った人が参加しているか
 * @param spectating  受け取った人が観戦しているか（エントリーせず、レースの HUD だけを見る）
 * @param canManage   受け取った人がスタート・中止・決まりの変更をしてよいか（主催者か OP）
 * @param bestMillis  受け取った人のこのコースの自己ベスト [ms]。無ければ 0
 * @param regulation  決まり（{@link Regulation#toArray}）
 * @param entrants    参加者（入った順）
 * @param courses     そのワールドのコース（主催者画面で選ぶため）
 * @param presets     サーバーに保存してあるレギュレーションのプリセット
 * @param presetAdmin 受け取った人がプリセットを保存・削除してよいか（OP）
 */
public record RaceLobbyPacket(Phase phase, String course, int laps, int checkpoints, String hostName,
                              boolean isHost, boolean joined, boolean spectating, boolean canManage,
                              long bestMillis,
                              double[] regulation, List<Entrant> entrants, List<CourseInfo> courses,
                              List<Preset> presets, boolean presetAdmin)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RaceLobbyPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "race_lobby"));

    public static final StreamCodec<FriendlyByteBuf, RaceLobbyPacket> STREAM_CODEC =
            StreamCodec.ofMember(RaceLobbyPacket::encode, RaceLobbyPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** レースの場面。 */
    public enum Phase {
        /** レースが無い。 */
        NONE,
        /** 受付中。 */
        OPEN,
        /** スタートの信号が点いている。 */
        COUNTDOWN,
        /** 走っている。 */
        RUNNING,
        /** リザルトを出している。 */
        RESULT
    }

    private static final Phase[] PHASES = Phase.values();
    private static final Regulation.Kind[] KINDS = Regulation.Kind.values();

    /** 判定。 */
    public enum Verdict {
        /** 決まりに合っている。 */
        OK,
        /** 決まりに外れている。 */
        VIOLATION,
        /** 車に乗っていないので判定できない。 */
        NO_CAR
    }

    private static final Verdict[] VERDICTS = Verdict.values();

    /**
     * 参加者 1 人。
     *
     * @param name       名前
     * @param carId      乗っている車種の ID。乗っていなければ空
     * @param verdict    判定
     * @param violations 外れている決まり
     * @param values     車の値（{@link #POWER} などの順）。乗っていなければ空
     * @param you        受け取った本人か
     */
    public record Entrant(String name, String carId, Verdict verdict,
                          List<Regulation.Violation> violations, double[] values, boolean you) {

        /** 値を取り出す。無ければ NaN。 */
        public double value(int index) {
            return index < values.length ? values[index] : Double.NaN;
        }
    }

    /** {@link Entrant#values} の並び。最高出力 [PS]。 */
    public static final int POWER = 0;
    /** 車重 [kg]。 */
    public static final int MASS = 1;
    /** グリップ（舗装路の μ）。 */
    public static final int GRIP = 2;
    /** ダウンフォース [kg]（200km/h）。 */
    public static final int DOWNFORCE = 3;
    /** 駆動方式（{@code CarSpec.Layout} の番号）。 */
    public static final int LAYOUT = 4;
    /** 過給機を付けているか（1/0）。 */
    public static final int TURBO = 5;
    /** トラクションコントロールを使っているか（1/0）。 */
    public static final int TCS = 6;
    /** ABS を使っているか（1/0）。 */
    public static final int ABS = 7;
    public static final int VALUE_COUNT = 8;

    /** 車の値を並べる。 */
    public static double[] valuesOf(com.jdmmc.kurumamod.physics.CarSpec spec) {
        double[] v = new double[VALUE_COUNT];
        v[POWER] = Regulation.powerPs(spec);
        v[MASS] = spec.mass();
        v[GRIP] = Regulation.grip(spec);
        v[DOWNFORCE] = Regulation.downforceKg(spec);
        v[LAYOUT] = spec.layout().ordinal();
        v[TURBO] = spec.turbo().present() ? 1 : 0;
        v[TCS] = spec.tractionControl() > 0.0 ? 1 : 0;
        v[ABS] = spec.abs() > 0.0 ? 1 : 0;
        return v;
    }

    /** コース 1 つ。 */
    public record CourseInfo(String name, int laps, int checkpoints) {
    }

    /** レギュレーションのプリセット 1 つ（{@code Regulation#toArray} の並び）。 */
    public record Preset(String name, double[] regulation) {
    }

    /** レースが無いときの中身。 */
    public static RaceLobbyPacket none(List<CourseInfo> courses, List<Preset> presets, boolean presetAdmin) {
        return new RaceLobbyPacket(Phase.NONE, "", 0, 0, "", false, false, false, false, 0L,
                Regulation.DEFAULT.toArray(), List.of(), courses, presets, presetAdmin);
    }

    public RaceLobbyPacket(FriendlyByteBuf buf) {
        this(PHASES[buf.readByte()], buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readLong(),
                readDoubles(buf), readEntrants(buf), readCourses(buf), readPresets(buf), buf.readBoolean());
    }

    private static double[] readDoubles(FriendlyByteBuf buf) {
        int count = Math.min(64, buf.readVarInt());
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readDouble();
        }
        return values;
    }

    private static List<Entrant> readEntrants(FriendlyByteBuf buf) {
        int count = Math.min(256, buf.readVarInt());
        List<Entrant> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf(32);
            String carId = buf.readUtf(128);
            Verdict verdict = VERDICTS[buf.readByte()];
            int violationCount = Math.min(16, buf.readVarInt());
            List<Regulation.Violation> violations = new ArrayList<>(violationCount);
            for (int j = 0; j < violationCount; j++) {
                violations.add(new Regulation.Violation(KINDS[buf.readByte()], buf.readDouble(), buf.readDouble()));
            }
            int valueCount = Math.min(16, buf.readVarInt());
            double[] values = new double[valueCount];
            for (int j = 0; j < valueCount; j++) {
                values[j] = buf.readDouble();
            }
            list.add(new Entrant(name, carId, verdict, violations, values, buf.readBoolean()));
        }
        return list;
    }

    private static List<Preset> readPresets(FriendlyByteBuf buf) {
        int count = Math.min(256, buf.readVarInt());
        List<Preset> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Preset(buf.readUtf(256), readDoubles(buf)));
        }
        return list;
    }

    private static List<CourseInfo> readCourses(FriendlyByteBuf buf) {
        int count = Math.min(256, buf.readVarInt());
        List<CourseInfo> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new CourseInfo(buf.readUtf(256), buf.readVarInt(), buf.readVarInt()));
        }
        return list;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(phase.ordinal());
        buf.writeUtf(course, 256);
        buf.writeVarInt(laps);
        buf.writeVarInt(checkpoints);
        buf.writeUtf(hostName, 256);
        buf.writeBoolean(isHost);
        buf.writeBoolean(joined);
        buf.writeBoolean(spectating);
        buf.writeBoolean(canManage);
        buf.writeLong(bestMillis);
        buf.writeVarInt(regulation.length);
        for (double value : regulation) {
            buf.writeDouble(value);
        }
        buf.writeVarInt(entrants.size());
        for (Entrant entrant : entrants) {
            buf.writeUtf(entrant.name(), 32);
            buf.writeUtf(entrant.carId(), 128);
            buf.writeByte(entrant.verdict().ordinal());
            buf.writeVarInt(entrant.violations().size());
            for (Regulation.Violation violation : entrant.violations()) {
                buf.writeByte(violation.kind().ordinal());
                buf.writeDouble(violation.actual());
                buf.writeDouble(violation.limit());
            }
            buf.writeVarInt(entrant.values().length);
            for (double value : entrant.values()) {
                buf.writeDouble(value);
            }
            buf.writeBoolean(entrant.you());
        }
        buf.writeVarInt(courses.size());
        for (CourseInfo info : courses) {
            buf.writeUtf(info.name(), 256);
            buf.writeVarInt(info.laps());
            buf.writeVarInt(info.checkpoints());
        }
        buf.writeVarInt(presets.size());
        for (Preset preset : presets) {
            buf.writeUtf(preset.name(), 256);
            buf.writeVarInt(preset.regulation().length);
            for (double value : preset.regulation()) {
                buf.writeDouble(value);
            }
        }
        buf.writeBoolean(presetAdmin);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> CarClientPackets.applyRaceLobby(this));
    }
}
