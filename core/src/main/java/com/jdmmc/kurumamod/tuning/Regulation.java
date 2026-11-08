package com.jdmmc.kurumamod.tuning;

import com.jdmmc.kurumamod.physics.CarSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * レースのレギュレーション（性能の決まり）。<b>Minecraft に依存しない。</b>
 *
 * <p>主催者が決め、サーバーが参加者の車を判定し、クライアントは G セッティングで
 * 決まりの外へ動かせないようにする。どちらも同じこのクラスで測るので、判定が食い違わない。</p>
 *
 * <p>測るのは<b>実際に走る諸元</b>（部品込み）。最高出力はターボのブーストが乗りきった値、
 * グリップは舗装路で付けているタイヤの倍率込み、ダウンフォースは 200km/h のときの前後の合計。</p>
 *
 * <p><b>「車の素性」は数値の上限ではなく、車種の値からの固定</b>にしてある。
 * 駆動の効率・転がり抵抗・寸法などは好みではなく得か損かしかない値なので、
 * 主催者に 1 つずつ上限を決めさせる意味が無い（{@link #FIXED_PARAMETERS}）。</p>
 *
 * @param fixedMask       車種の値に固定する素性（{@link #FIXED_PARAMETERS} の順のビット）。0 なら固定しない
 * @param powerOn         最高出力の上限を使うか
 * @param powerMax        最高出力の上限 [PS]
 * @param massOn          車重の下限を使うか
 * @param massMin         車重の下限 [kg]
 * @param pwrOn           パワーウエイトレシオの下限を使うか
 * @param pwrMin          パワーウエイトレシオの下限 [kg/PS]
 * @param gripOn          タイヤのグリップの上限を使うか
 * @param gripMax         グリップの上限（舗装路の μ）
 * @param downforceOn     ダウンフォースの上限を使うか
 * @param downforceMax    ダウンフォースの上限 [kg]（200km/h、前後の合計）
 * @param drives          使ってよい駆動方式。{@link CarSpec.Layout} の順のビット（FF=1・FR=2・4WD=4）
 * @param turbo           過給機を付けてよいか
 * @param tractionControl トラクションコントロールを使ってよいか
 * @param abs             ABS を使ってよいか
 */
public record Regulation(int fixedMask,
                         boolean powerOn, double powerMax,
                         boolean massOn, double massMin,
                         boolean pwrOn, double pwrMin,
                         boolean gripOn, double gripMax,
                         boolean downforceOn, double downforceMax,
                         int drives, boolean turbo, boolean tractionControl, boolean abs) {

    /** 1PS [W]。 */
    public static final double WATTS_PER_PS = 735.5;
    /** ダウンフォースを比べる速さ [m/s]（200km/h）。 */
    public static final double DOWNFORCE_SPEED = 200.0 / 3.6;
    /** すべての駆動方式。 */
    public static final int ALL_DRIVES = 0b111;

    /**
     * 車種の値に固定する項目（{@link Tunables} の名前）。
     *
     * <p>寸法・駆動の効率・転がり抵抗・空気抵抗・変速の時間・エンジンの慣性・タイヤの性質（μ 以外）。
     * どれも動かして損をする場面が無い（あるいはトレッドのように、見た目を変えずに旋回が速くなる）。</p>
     */
    public static final List<String> FIXED_PARAMETERS = List.of(
            "wheel_base", "front_track_width", "rear_track_width", "wheel_radius",
            "drivetrain_efficiency", "rolling_resistance", "drag_area", "shift_time", "engine_inertia",
            "tire_falloff", "tire_load_sensitivity", "cornering_stiffness", "longitudinal_stiffness");

    /** すべての素性を固定するビット。 */
    public static final int ALL_FIXED = (1 << FIXED_PARAMETERS.size()) - 1;

    /**
     * レースを開いたときの決まり。<b>素性の固定だけが入っていて、数値の条件は切ってある。</b>
     * 値は入れておくので、主催者は入れるだけで目安から始められる。
     */
    public static final Regulation DEFAULT = new Regulation(ALL_FIXED,
            false, 250.0, false, 1200.0, false, 5.0, false, 1.10, false, 100.0,
            ALL_DRIVES, true, true, true);

    /** 何も縛らない決まり。 */
    public static final Regulation FREE = new Regulation(0,
            false, 250.0, false, 1200.0, false, 5.0, false, 1.10, false, 100.0,
            ALL_DRIVES, true, true, true);

    /** 値の範囲。主催者画面の − / + はこの中で動く。 */
    public static final double POWER_MIN = 50.0;
    public static final double POWER_MAX = 1500.0;
    public static final double MASS_MIN = 500.0;
    public static final double MASS_MAX = 2500.0;
    public static final double PWR_MIN = 1.0;
    public static final double PWR_MAX = 20.0;
    public static final double GRIP_MIN = 0.3;
    public static final double GRIP_MAX = 3.0;
    public static final double DOWNFORCE_MIN = 0.0;
    public static final double DOWNFORCE_MAX = 2000.0;

    public Regulation {
        powerMax = clamp(powerMax, POWER_MIN, POWER_MAX, 250.0);
        massMin = clamp(massMin, MASS_MIN, MASS_MAX, 1200.0);
        pwrMin = clamp(pwrMin, PWR_MIN, PWR_MAX, 5.0);
        gripMax = clamp(gripMax, GRIP_MIN, GRIP_MAX, 1.10);
        downforceMax = clamp(downforceMax, DOWNFORCE_MIN, DOWNFORCE_MAX, 100.0);
        drives &= ALL_DRIVES;
        fixedMask &= ALL_FIXED;
    }

    /** 素性を 1 つでも固定しているか。 */
    public boolean fixedTraits() {
        return fixedMask != 0;
    }

    /** 素性をすべて固定しているか。 */
    public boolean fixesAll() {
        return fixedMask == ALL_FIXED;
    }

    /** 固定している素性の数。 */
    public int fixedCount() {
        return Integer.bitCount(fixedMask);
    }

    /** {@link #FIXED_PARAMETERS} の {@code index} 番目を固定しているか。 */
    public boolean fixesTrait(int index) {
        return (fixedMask & (1 << index)) != 0;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** 何も縛っていないか。縛っていなければ G セッティングも判定も素通りでよい。 */
    public boolean isFree() {
        return fixedMask == 0 && !powerOn && !massOn && !pwrOn && !gripOn && !downforceOn
                && drives == ALL_DRIVES && turbo && tractionControl && abs;
    }

    /** その駆動方式を使ってよいか。 */
    public boolean allows(CarSpec.Layout layout) {
        return (drives & (1 << layout.ordinal())) != 0;
    }

    // ------------------------------------------------------------------
    // 測る
    // ------------------------------------------------------------------

    /** 最高出力 [PS]。ターボはブーストが乗りきった値。 */
    public static double powerPs(CarSpec spec) {
        return spec.peakPowerWithBoost() / WATTS_PER_PS;
    }

    /** 舗装路のグリップ（付けているタイヤの倍率込みの μ）。 */
    public static double grip(CarSpec spec) {
        return spec.tireFriction() * spec.tire().gripOn("paved");
    }

    /** 200km/h のときのダウンフォース [kg]（前後の合計）。 */
    public static double downforceKg(CarSpec spec) {
        return spec.totalDownforce(DOWNFORCE_SPEED) / 9.81;
    }

    /** パワーウエイトレシオ [kg/PS]。 */
    public static double powerWeightRatio(CarSpec spec) {
        return spec.mass() / Math.max(1e-6, powerPs(spec));
    }

    /** 車種の値から変えている素性の項目の数（すべての素性で数える）。 */
    public static int alteredTraits(CarSpec spec, CarSpec base) {
        return alteredTraits(spec, base, ALL_FIXED);
    }

    /** 車種の値から変えている素性の項目の数（{@code mask} の素性だけ）。 */
    public static int alteredTraits(CarSpec spec, CarSpec base, int mask) {
        int count = 0;
        for (int i = 0; i < FIXED_PARAMETERS.size(); i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            TunableParameter parameter = Tunables.byName(FIXED_PARAMETERS.get(i));
            if (parameter == null) {
                continue;
            }
            double value = parameter.internalValue(spec);
            double original = parameter.internalValue(base);
            if (Math.abs(value - original) > 1e-6 * Math.max(1.0, Math.abs(original))) {
                count++;
            }
        }
        return count;
    }

    /** その項目が、素性として車種の値に固定されているか。 */
    public boolean fixes(TunableParameter parameter) {
        int index = FIXED_PARAMETERS.indexOf(parameter.name());
        return index >= 0 && fixesTrait(index);
    }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /** 違反の種類。並びは画面に出す順。 */
    public enum Kind {
        /** 車の素性を車種の値から変えている（{@code actual} は項目の数）。 */
        FIXED,
        POWER,
        MASS,
        PWR,
        GRIP,
        DOWNFORCE,
        DRIVE,
        TURBO,
        TRACTION_CONTROL,
        ABS
    }

    /**
     * 違反 1 つ。
     *
     * @param kind   何の決まりか
     * @param actual 車の値
     * @param limit  決まりの値（上限か下限）。真偽の決まりでは 0
     */
    public record Violation(Kind kind, double actual, double limit) {

        /** どれだけ外れているか。大きいほど悪い。真偽の決まりは 1。 */
        public double excess() {
            return switch (kind) {
                case FIXED -> actual;
                case POWER, GRIP, DOWNFORCE -> actual - limit;
                case MASS, PWR -> limit - actual;
                default -> 1.0;
            };
        }
    }

    /**
     * 車を判定する。
     *
     * @param spec 実際に走る諸元（部品込み）
     * @param base その車種の素の諸元（素性の固定の比べ先）
     * @return 違反。無ければ空
     */
    public List<Violation> check(CarSpec spec, CarSpec base) {
        List<Violation> out = new ArrayList<>();
        if (fixedMask != 0) {
            int altered = alteredTraits(spec, base, fixedMask);
            if (altered > 0) {
                out.add(new Violation(Kind.FIXED, altered, 0.0));
            }
        }
        double power = powerOn || pwrOn ? powerPs(spec) : 0.0;
        if (powerOn && power > powerMax + 0.5) {
            out.add(new Violation(Kind.POWER, power, powerMax));
        }
        if (massOn && spec.mass() < massMin - 0.5) {
            out.add(new Violation(Kind.MASS, spec.mass(), massMin));
        }
        if (pwrOn) {
            double ratio = spec.mass() / Math.max(1e-6, power);
            if (ratio < pwrMin - 0.005) {
                out.add(new Violation(Kind.PWR, ratio, pwrMin));
            }
        }
        if (gripOn) {
            double mu = grip(spec);
            if (mu > gripMax + 0.005) {
                out.add(new Violation(Kind.GRIP, mu, gripMax));
            }
        }
        if (downforceOn) {
            double kg = downforceKg(spec);
            if (kg > downforceMax + 0.5) {
                out.add(new Violation(Kind.DOWNFORCE, kg, downforceMax));
            }
        }
        if (!allows(spec.layout())) {
            out.add(new Violation(Kind.DRIVE, spec.layout().ordinal(), 0.0));
        }
        if (!turbo && spec.turbo().present()) {
            out.add(new Violation(Kind.TURBO, 1.0, 0.0));
        }
        if (!tractionControl && spec.tractionControl() > 0.0) {
            out.add(new Violation(Kind.TRACTION_CONTROL, 1.0, 0.0));
        }
        if (!abs && spec.abs() > 0.0) {
            out.add(new Violation(Kind.ABS, 1.0, 0.0));
        }
        return out;
    }

    /**
     * {@code from} から {@code to} へ変えてよいか。<b>違反を増やしも悪くもしないなら通す。</b>
     *
     * <p>「違反が無いこと」だけで決めると、もともと何かに外れている車（車種そのものが
     * 決まりを超えている、など）ではどの項目も動かせなくなる。直す方向の変更は通したい。</p>
     */
    public boolean allowsChange(CarSpec from, CarSpec to, CarSpec base) {
        List<Violation> before = check(from, base);
        for (Violation after : check(to, base)) {
            Violation same = null;
            for (Violation v : before) {
                if (v.kind() == after.kind()) {
                    same = v;
                    break;
                }
            }
            if (same == null || after.excess() > same.excess() + 1e-6) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 送る形
    // ------------------------------------------------------------------

    /** 並べた値。パケットはこの順で送る。 */
    public double[] toArray() {
        return new double[]{
                fixedMask,
                powerOn ? 1 : 0, powerMax,
                massOn ? 1 : 0, massMin,
                pwrOn ? 1 : 0, pwrMin,
                gripOn ? 1 : 0, gripMax,
                downforceOn ? 1 : 0, downforceMax,
                drives, turbo ? 1 : 0, tractionControl ? 1 : 0, abs ? 1 : 0};
    }

    /** {@link #toArray} の逆。長さが合わなければ {@link #DEFAULT}。 */
    public static Regulation fromArray(double[] v) {
        if (v == null || v.length != 15) {
            return DEFAULT;
        }
        return new Regulation((int) v[0],
                v[1] != 0, v[2], v[3] != 0, v[4], v[5] != 0, v[6], v[7] != 0, v[8], v[9] != 0, v[10],
                (int) v[11], v[12] != 0, v[13] != 0, v[14] != 0);
    }

    // ------------------------------------------------------------------
    // 書き換え（主催者画面）
    // ------------------------------------------------------------------

    /** 素性をまとめて固定する／外す。 */
    public Regulation withFixedTraits(boolean value) {
        return withFixedMask(value ? ALL_FIXED : 0);
    }

    /** {@link #FIXED_PARAMETERS} の {@code index} 番目だけを入れ切りする。 */
    public Regulation withTrait(int index, boolean value) {
        return withFixedMask(value ? fixedMask | (1 << index) : fixedMask & ~(1 << index));
    }

    public Regulation withFixedMask(int mask) {
        return new Regulation(mask, powerOn, powerMax, massOn, massMin, pwrOn, pwrMin, gripOn, gripMax,
                downforceOn, downforceMax, drives, turbo, tractionControl, abs);
    }

    public Regulation withPower(boolean on, double max) {
        return new Regulation(fixedMask, on, max, massOn, massMin, pwrOn, pwrMin, gripOn, gripMax,
                downforceOn, downforceMax, drives, turbo, tractionControl, abs);
    }

    public Regulation withMass(boolean on, double min) {
        return new Regulation(fixedMask, powerOn, powerMax, on, min, pwrOn, pwrMin, gripOn, gripMax,
                downforceOn, downforceMax, drives, turbo, tractionControl, abs);
    }

    public Regulation withPwr(boolean on, double min) {
        return new Regulation(fixedMask, powerOn, powerMax, massOn, massMin, on, min, gripOn, gripMax,
                downforceOn, downforceMax, drives, turbo, tractionControl, abs);
    }

    public Regulation withGrip(boolean on, double max) {
        return new Regulation(fixedMask, powerOn, powerMax, massOn, massMin, pwrOn, pwrMin, on, max,
                downforceOn, downforceMax, drives, turbo, tractionControl, abs);
    }

    public Regulation withDownforce(boolean on, double max) {
        return new Regulation(fixedMask, powerOn, powerMax, massOn, massMin, pwrOn, pwrMin, gripOn, gripMax,
                on, max, drives, turbo, tractionControl, abs);
    }

    public Regulation withDrives(int value) {
        return new Regulation(fixedMask, powerOn, powerMax, massOn, massMin, pwrOn, pwrMin, gripOn, gripMax,
                downforceOn, downforceMax, value, turbo, tractionControl, abs);
    }

    public Regulation withTurbo(boolean value) {
        return new Regulation(fixedMask, powerOn, powerMax, massOn, massMin, pwrOn, pwrMin, gripOn, gripMax,
                downforceOn, downforceMax, drives, value, tractionControl, abs);
    }

    public Regulation withTractionControl(boolean value) {
        return new Regulation(fixedMask, powerOn, powerMax, massOn, massMin, pwrOn, pwrMin, gripOn, gripMax,
                downforceOn, downforceMax, drives, turbo, value, abs);
    }

    public Regulation withAbs(boolean value) {
        return new Regulation(fixedMask, powerOn, powerMax, massOn, massMin, pwrOn, pwrMin, gripOn, gripMax,
                downforceOn, downforceMax, drives, turbo, tractionControl, value);
    }
}
