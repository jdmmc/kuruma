package com.jdmmc.kurumamod.debug;

import com.jdmmc.kurumamod.physics.CarBody;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.CarState;
import com.jdmmc.kurumamod.physics.GroundContact;
import com.jdmmc.kurumamod.physics.Wheel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 接地の記録。運転中の車の 4 輪が、物理の 1 刻みごとにどの地面に乗っていたかを CSV へ書く。
 *
 * <p>「どういう状況で跳ねる・浮くのか」を実際に走って集め、セッティングで直るのか
 * コードを直すべきなのかを見分けるための道具。分析は {@code tools/groundlog_report.py}。</p>
 *
 * <p>見分けの決め手になるのは 3 つ:</p>
 * <ul>
 *   <li><b>地面の高さの出どころ</b>（{@link Source}）。真下の地面か、階段の線か、先読みの斜面か……。
 *       地面が 1 刻みで大きく跳んでいれば、作っている側（コード）の問題</li>
 *   <li><b>サスの隙間</b>（ハードポイントから地面までの距離 − タイヤ半径）。ストロークの内側なのに
 *       荷重が 0 なら、伸び側のダンパーが荷重を削りきっている（セッティングで変わる）。
 *       ストロークの外ならタイヤが地面から離れている（飛んでいる）</li>
 *   <li><b>地面が動く速さ</b>。ダンパーはこれを見て力を出す</li>
 * </ul>
 *
 * <p>クライアントのスレッドからだけ呼ぶこと。シングルプレイでは同じ JVM の中でサーバーも
 * 無人の車の物理を解くので、呼ぶ側で {@code level().isClientSide} を確かめる。</p>
 */
public final class GroundLog {

    /** その輪へ渡した地面の高さがどこから来たか。 */
    public enum Source {
        /** 真下の地面をそのまま */
        FLAT,
        /** 階段（段の角を結んだ線） */
        STAIR,
        /** 先読みの斜面（単独の低い段） */
        AHEAD,
        /** 高い段を、上がる速さを制限しながら上げている途中 */
        RISE_LIMIT,
        /** 登坂限界より上まで続く壁。前の高さのまま据え置き */
        WALL_HOLD,
        /** 上から地面に入った輪を、上にある天面へ戻している */
        BURIED,
        /** 真下には無いが、1 段下に階段の線があった（階段の下り） */
        DEEP_STAIR,
        /** 地面が見つからない */
        MISS,
        /** 車が大きく傾いていて、タイヤが地面に届かない扱い */
        TILTED
    }

    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String[] WHEEL_NAMES = {"fl", "fr", "rl", "rr"};

    private static BufferedWriter out;
    private static Path file;
    private static long rows;
    /** 前の刻みで渡した地面。地面が動く速さを出すのに使う。 */
    private static final double[] previousGround = new double[Wheel.COUNT];

    private GroundLog() {
    }

    public static boolean active() {
        return out != null;
    }

    /** いま書いているファイル。記録していなければ null。 */
    public static Path file() {
        return file;
    }

    public static long rows() {
        return rows;
    }

    /**
     * 記録を始める。先頭に諸元を {@code #} 行で書く（分析でストロークや停車時荷重を使う）。
     *
     * @param dir   書き出すフォルダ。無ければ作る
     * @param carId 車種の id
     */
    public static Path start(Path dir, String carId, CarSpec spec) throws IOException {
        stop();
        Files.createDirectories(dir);
        Path path = dir.resolve("groundlog-" + LocalDateTime.now().format(FILE_NAME) + ".csv");
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writer.write("# car=" + carId);
        writer.newLine();
        writer.write(String.format(Locale.ROOT,
                "# stroke=%.4f radius=%.4f mass=%.1f static_front=%.1f static_rear=%.1f"
                        + " spring_front=%.1f spring_rear=%.1f"
                        + " damp_bump_front=%.3f damp_rebound_front=%.3f damp_bump_rear=%.3f damp_rebound_rear=%.3f"
                        + " arb_front=%.1f arb_rear=%.1f climb=%.3f",
                spec.suspensionMaxLength(), spec.wheelRadius(), spec.mass(),
                spec.staticWheelLoad(Wheel.FRONT_LEFT), spec.staticWheelLoad(Wheel.REAR_LEFT),
                spec.suspensionStiffness(Wheel.FRONT_LEFT), spec.suspensionStiffness(Wheel.REAR_LEFT),
                spec.dampingRatio(Wheel.FRONT_LEFT, false), spec.dampingRatio(Wheel.FRONT_LEFT, true),
                spec.dampingRatio(Wheel.REAR_LEFT, false), spec.dampingRatio(Wheel.REAR_LEFT, true),
                spec.antiRollStiffness(Wheel.FRONT_LEFT), spec.antiRollStiffness(Wheel.REAR_LEFT),
                spec.maxClimbStep()));
        writer.newLine();
        // 記録の途中で切り替えることもあるので、行ごとにも smooth 列で残す
        writer.write("# smooth=" + GroundSmoothing.mode().code());
        writer.newLine();
        StringBuilder header = new StringBuilder(
                "tick,sub,x,y,z,yaw,pitch,roll,v_fwd,v_lat,v_up,yaw_rate,slip,throttle,brake,handbrake,steer");
        for (String w : WHEEL_NAMES) {
            header.append(",load_").append(w)
                    .append(",src_").append(w)
                    .append(",raw_").append(w)
                    .append(",ground_").append(w)
                    .append(",gspeed_").append(w)
                    .append(",gap_").append(w)
                    .append(",slipratio_").append(w)
                    .append(",surface_").append(w);
        }
        header.append(",body_floor,body_wall,impact,smooth");
        writer.write(header.toString());
        writer.newLine();
        out = writer;
        file = path;
        rows = 0;
        java.util.Arrays.fill(previousGround, Double.NaN);
        return path;
    }

    /** 記録を止めてファイルを閉じる。書いていたファイルを返す（記録していなければ null）。 */
    public static Path stop() {
        Path closed = file;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 閉じられなくても、書けたところまでは残っている
            }
        }
        out = null;
        file = null;
        return closed;
    }

    /**
     * 物理の 1 刻みぶんを書く。{@code CarPhysics.step} と {@code CarBody#solve} の後に呼ぶ。
     *
     * @param raw      各輪の真下の地面の高さ（線や斜面を足す前）。無ければ負の無限大
     * @param surfaces 各輪の路面の名前
     */
    public static void record(long tick, int sub, double x, double z, double dt, CarSpec spec, CarState state,
                              GroundContact contact, Source[] sources, double[] raw, String[] surfaces,
                              CarBody.Result hit) {
        if (out == null) {
            return;
        }
        StringBuilder row = new StringBuilder(512);
        row.append(tick).append(',').append(sub);
        num(row, x, 3);
        num(row, state.height, 3);
        num(row, z, 3);
        num(row, Math.toDegrees(state.yaw), 2);
        num(row, Math.toDegrees(state.pitch), 2);
        num(row, Math.toDegrees(state.roll), 2);
        num(row, state.forwardSpeed, 3);
        num(row, state.lateralSpeed, 3);
        num(row, state.verticalSpeed, 3);
        num(row, state.yawRate, 3);
        num(row, Math.toDegrees(state.slipAngle()), 2);
        num(row, state.throttle, 2);
        num(row, state.brake, 2);
        num(row, state.handbrake, 2);
        num(row, Math.toDegrees(state.steerAngle), 2);
        for (Wheel wheel : Wheel.VALUES) {
            int i = wheel.ordinal();
            double ground = contact.groundHeight[i];
            boolean hasGround = ground > Double.NEGATIVE_INFINITY;
            double previous = previousGround[i];
            double groundSpeed = hasGround && !Double.isNaN(previous) ? (ground - previous) / dt : Double.NaN;
            previousGround[i] = hasGround ? ground : Double.NaN;
            double gap = hasGround
                    ? state.hardpointHeight(spec.wheelForwardOffset(wheel), spec.wheelRightOffset(wheel))
                    - ground - spec.wheelRadius()
                    : Double.NaN;
            num(row, state.wheelLoad[i], 0);
            row.append(',').append(sources[i] == null ? "" : sources[i].name());
            num(row, raw[i] > Double.NEGATIVE_INFINITY ? raw[i] : Double.NaN, 4);
            num(row, hasGround ? ground : Double.NaN, 4);
            num(row, groundSpeed, 3);
            num(row, gap, 4);
            num(row, state.wheelSlipRatio[i], 3);
            row.append(',').append(surfaces[i] == null ? "" : surfaces[i]);
        }
        row.append(',').append(hit.vertical ? 1 : 0).append(',').append(hit.horizontal ? 1 : 0);
        num(row, hit.impact, 3);
        row.append(',').append(GroundSmoothing.mode().code());
        try {
            out.write(row.toString());
            out.newLine();
            rows++;
        } catch (IOException e) {
            // 書けなくなったら止める（ディスクが一杯など）。走るのは邪魔しない
            stop();
        }
    }

    private static void num(StringBuilder row, double value, int digits) {
        row.append(',');
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        row.append(String.format(Locale.ROOT, "%." + digits + "f", value));
    }
}
