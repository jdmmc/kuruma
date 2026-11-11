package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.tuning.Regulation;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。そのワールドのレースの様子（受付中か・決まり・参加者と判定）。
 * クラスの一覧は {@link RaceClassesPacket}（変わったときだけ送る）。
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
 * @param classAdmin 受け取った人がクラスを保存・削除してよいか（OP）
 * @param declaredClass 受け取った人がタイムアタックで宣言しているクラスの名前。宣言していなければ空
 */
public record RaceLobbyPacket(Phase phase, String course, int laps, int checkpoints, String hostName,
                              boolean isHost, boolean joined, boolean spectating, boolean canManage,
                              long bestMillis,
                              double[] regulation, List<Entrant> entrants, List<CourseInfo> courses,
                              boolean classAdmin, String declaredClass, int self)
        implements RacePayload {

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
     */
    public record Entrant(String name, String carId, Verdict verdict,
                          List<Regulation.Violation> violations, double[] values) {

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

    /**
     * クラス 1 つ。
     *
     * @param regulation {@code Regulation#toArray} の並び
     * @param records    このクラスのタイムアタックの記録の件数（全員・全コース）。上書き・削除で消える数
     */
    public record RaceClass(String name, double[] regulation, int records) {
    }

    /** レースが無いときの中身。 */
    public static RaceLobbyPacket none(List<CourseInfo> courses, boolean classAdmin,
                                       String declaredClass) {
        return new RaceLobbyPacket(Phase.NONE, "", 0, 0, "", false, false, false, false, 0L,
                Regulation.DEFAULT.toArray(), List.of(), courses, classAdmin, declaredClass, -1);
    }

    /**
     * 受け取った本人の行か。<b>本人かどうかは行に持たせず、{@code self}（何行目か）で引く。</b>
     *
     * <p>行に持たせて送るときに付けると、シングルプレイと LAN のホスト（1.21 からはメモリの中の接続で、パケットが
     * バイトにされずにそのまま届く）では付かず、自分の行が分からなかった。番号ならどちらの経路でも同じに届く。</p>
     */
    public boolean isSelf(int index) {
        return index >= 0 && index == self;
    }

    public RaceLobbyPacket(RaceBuf buf) {
        this(PHASES[buf.readByte()], buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readLong(),
                readDoubles(buf), readEntrants(buf), readCourses(buf), buf.readBoolean(),
                buf.readUtf(256), buf.readVarInt() - 1);
    }

    /**
     * 件数の上限。<b>書く側も同じ数で切る。</b>読む側だけで切ると、上限を超えたときに残りのバイトを読み違え、
     * その次元の全員が定期便のたびに切断された（コースの数には上限が無い）。
     */
    private static final int MAX_DOUBLES = 64;
    private static final int MAX_ENTRANTS = 256;
    private static final int MAX_LIST = 16;
    private static final int MAX_COURSES = com.jdmmc.kurumamod.race.RaceRecords.MAX_COURSES;

    /** 件数を読む。上限を超えていたら、読み違える前に止める。 */
    private static int readCount(RaceBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw buf.invalid("レースの様子の件数が範囲外です: " + count);
        }
        return count;
    }

    private static double[] readDoubles(RaceBuf buf) {
        int count = readCount(buf, MAX_DOUBLES);
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readDouble();
        }
        return values;
    }

    private static List<Entrant> readEntrants(RaceBuf buf) {
        int count = readCount(buf, MAX_ENTRANTS);
        List<Entrant> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf(32);
            String carId = buf.readUtf(128);
            Verdict verdict = VERDICTS[buf.readByte()];
            int violationCount = readCount(buf, MAX_LIST);
            List<Regulation.Violation> violations = new ArrayList<>(violationCount);
            for (int j = 0; j < violationCount; j++) {
                violations.add(new Regulation.Violation(KINDS[buf.readByte()], buf.readDouble(), buf.readDouble()));
            }
            int valueCount = readCount(buf, MAX_LIST);
            double[] values = new double[valueCount];
            for (int j = 0; j < valueCount; j++) {
                values[j] = buf.readDouble();
            }
            list.add(new Entrant(name, carId, verdict, violations, values));
        }
        return list;
    }

    private static List<CourseInfo> readCourses(RaceBuf buf) {
        int count = readCount(buf, MAX_COURSES);
        List<CourseInfo> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new CourseInfo(buf.readUtf(256), buf.readVarInt(), buf.readVarInt()));
        }
        return list;
    }

    public void encode(RaceBuf buf) {
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
        writeDoubles(buf, regulation, MAX_DOUBLES);
        List<Entrant> sentEntrants = entrants.subList(0, Math.min(MAX_ENTRANTS, entrants.size()));
        buf.writeVarInt(sentEntrants.size());
        for (Entrant entrant : sentEntrants) {
            buf.writeUtf(entrant.name(), 32);
            buf.writeUtf(entrant.carId(), 128);
            buf.writeByte(entrant.verdict().ordinal());
            List<Regulation.Violation> violations =
                    entrant.violations().subList(0, Math.min(MAX_LIST, entrant.violations().size()));
            buf.writeVarInt(violations.size());
            for (Regulation.Violation violation : violations) {
                buf.writeByte(violation.kind().ordinal());
                buf.writeDouble(violation.actual());
                buf.writeDouble(violation.limit());
            }
            writeDoubles(buf, entrant.values(), MAX_LIST);
        }
        List<CourseInfo> sentCourses = courses.subList(0, Math.min(MAX_COURSES, courses.size()));
        buf.writeVarInt(sentCourses.size());
        for (CourseInfo info : sentCourses) {
            buf.writeUtf(info.name(), 256);
            buf.writeVarInt(info.laps());
            buf.writeVarInt(info.checkpoints());
        }
        buf.writeBoolean(classAdmin);
        buf.writeUtf(declaredClass, 256);
        // 一覧の外（切った後ろ）なら、いないことにする。-1 を送れるように 1 ずらす
        buf.writeVarInt(self < sentEntrants.size() ? self + 1 : 0);
    }

    private static void writeDoubles(RaceBuf buf, double[] values, int max) {
        int count = Math.min(max, values.length);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeDouble(values[i]);
        }
    }

}
