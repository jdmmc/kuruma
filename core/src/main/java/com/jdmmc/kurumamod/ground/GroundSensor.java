package com.jdmmc.kurumamod.ground;

import com.jdmmc.kurumamod.debug.GroundTrace;
import com.jdmmc.kurumamod.physics.CarBody;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.CarState;
import com.jdmmc.kurumamod.physics.GroundContact;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;

import java.util.Arrays;

/**
 * 4 輪の接地面を探す（段差・階段・先読みの斜面・埋まった輪・壁）。車 1 台に 1 つ持つ。
 *
 * <p><b>世界を読むのは {@link World} の 3 つの口だけ</b>（鉛直のレイ・上にある天面・タイヤの幅）。版の
 * {@code CarEntity} がそれを持つ。ここは Minecraft に触らないので、ゲームを起動せずに地形を作って試せる。</p>
 */
public final class GroundSensor {

    /** 世界を読む口。版の {@code CarEntity} が持つ。 */
    public interface World {
        /** (x, fromY, z) から (x, toY, z) へ真下へ撃つレイ。当たり判定（COLLIDER）だけを見る。液体は見ない。 */
        Hit castDown(double x, double fromY, double toY, double z);

        /**
         * 点がブロックの当たり判定の中にあれば、そこから上へすき間なく続くブロックの天面。
         * 中に無ければ負の無限大。
         */
        double solidTopAbove(double x, double y, double z);

        /** 接地を探すタイヤの内側と外側の端 {内, 外}（車輪の位置から外向き） [m]。 */
        double[] tireGroundEdges();

        /** 接地の記録（F3+B の線）を書くか。クライアントで物理を解いているときだけ。 */
        boolean recordsTrace();
    }

    /** レイの当たり。 */
    public interface Hit {
        /** 何にも当たらなかった。 */
        boolean miss();

        /** 起点がブロックの中にあった（＝壁）。 */
        boolean inside();

        double x();

        double y();

        double z();

        /** 当たったブロックの路面。知らないブロックは舗装路。 */
        RoadSurface surface();
    }

    /** 1 ティックの長さ [s]。 */
    private static final double TICK_SECONDS = 0.05;
    /** 接地レイの余長 [m]。伸びきり位置ちょうどで取りこぼさないための余裕。 */
    private static final double GROUND_RAY_MARGIN = 0.1;
    /**
     * 接地面が 1 ティックで上がってよい高さ [m]。＝段差を乗り越える速さ（3m/s）。
     *
     * <p>段差の天面をいきなり接地面として渡すと、1m ぶんの食い込みがそのまま
     * バンプストップに入り、<b>8 倍の荷重で車が跳ね上がる</b>。実測（{@code WallTest}）では
     * 1 ブロックの段差で上昇 8.7m/s・100 ティック中 75 ティック宙に浮き、ピッチが
     * 2779 度まで回った——<b>段差に当たっただけで車がとんぼ返りする</b>。少しずつ渡せば
     * サスが縮みながら車体を持ち上げるので、跳ねずに乗り越える（0.15m/tick で
     * 上昇 2.57m/s・浮きなし・5 ティックで段の上に落ち着く）。</p>
     *
     * <p>大きすぎても跳ねる。0.2m/tick ではストロークを食いきって行き過ぎ、
     * 落ち着くまでに 29 ティック掛かった。</p>
     */
    private static final double GROUND_RISE_PER_TICK = 0.15;
    /**
     * 低い段（{@link CarBody#STEP_LIFT} 以内）は先読みして、縁の手前から斜面で地面を上げておく。
     * 斜面の長さは「この秒数で進む距離」——段の高さ（{@link #LOOKAHEAD_RISE}）をこの時間かけて登る。
     *
     * <p>上がる速さの制限（{@link #GROUND_RISE_PER_TICK}）だけだと、ハーフの段を登りきるのに
     * 4 ティックかかり、その間（20m/s なら 4m）タイヤが段の中を走った。スラブを積んだ坂では
     * 次の段がもう来ているので遅れが溜まり、タイヤが床を突き抜け、20m/s の 25% の坂で止まって
     * 後ろへ下がり、30m/s では宙返りした。先読みすると縁に着いたときには段の高さまで上がっていて、
     * 同じ坂を 17〜20m/s のまま登る（ピッチ最大 20 度前後）。</p>
     *
     * <p>1 ブロック以上の段は先読みしない（今までどおり上がる速さを制限する）。先読みすると
     * 壁の手前で車が持ち上がり、地上高を上げた車が 1 ブロックの段を跳ねて越える。</p>
     */
    private static final double LOOKAHEAD_SECONDS = 0.15;
    /** 先読みの斜面の長さの上限 [m]。 */
    private static final double LOOKAHEAD_MAX = 3.0;
    /**
     * 下りの斜面の長さの上限 [m]。段を降りた直後は、後ろの段から斜面を下ろして地面にする。
     *
     * <p><b>登りより短くする（＝急にする）こと。</b>降りていく坂より緩い斜面を置くと地面が実際より
     * 高く残り、段ごとに大きく揺さぶられて、25% の下りを 30m/s で走ると宙返りした（上限 3m）。
     * 2m（段 50cm を 25% で降りる）ならロールの揺れが 7 度から 0〜1 度になり、宙返りもしない。
     * 1m では先読みしないのと変わらない。</p>
     *
     * <p>崖や単独の段では効かない（車は自然に放物線で飛び、その軌道が斜面より上を通る）。
     * 55cm を超える落差は斜面を作らない——作ると崖の先で宙に支えられる。</p>
     */
    private static final double LOOKAHEAD_DOWN_MAX = 2.0;
    /**
     * 階段を探す範囲 [m]。前後それぞれ。<b>段の幅の 2 倍は要る。</b>
     *
     * <p>坂の入口では、段の角を結んだ線は 1 つ目の縁から段の幅ぶん手前で地面と交わる。2 つ目の縁
     * （＝段の幅）が見えるのは 1 つ目の縁が「範囲 − 段の幅」まで近づいたときなので、それが段の幅より
     * 遠くないと、見えた時点で線がもう足元より上にあり、<b>地面が一瞬で跳ね上がる</b>。3m では幅 2m の
     * スラブの坂（14 度）の入口で 27cm 跳ね、8m/s で 4 輪の 4 割が浮いた（なめらかな坂では 0）。
     * 8m なら幅 4m の段まで入る。</p>
     */
    private static final double STAIR_REACH = 8.0;
    /**
     * これより先は {@link #STAIR_FAR_SPACING} おきに調べる [m]。範囲を広げたぶんレイが増えるので、
     * 遠くは粗くする（縁の位置は二分で詰めるので、粗くしても線の傾きは変わらない）。
     */
    private static final double STAIR_NEAR_REACH = 3.0;
    /** 遠くの段を調べる間隔 [m]。段の幅（1 ブロック）より狭くしておけば縁を取りこぼさない。 */
    private static final double STAIR_FAR_SPACING = 0.5;
    /**
     * 段の縁を詰める二分の回数。0.25m を 8 回で 1mm、0.5m でも 2mm。
     *
     * <p>3 回（3cm 幅）では縁の位置が ±1.5cm ずれ、段の角を越えるたびに地面が数 mm 跳ねた。ダンパーは
     * 地面の動く速さを見るので、1/80 秒の刻みでは数 mm でも 0.5m/s になり、<b>段の角ごとに荷重が
     * 1.6 倍に跳ねた</b>（坂の途中なのに 1m おきにガタつく）。</p>
     */
    private static final int STAIR_EDGE_BISECTIONS = 8;
    /**
     * これより小さい高さの違いは段と見ない [m]。roads の階層ブロックは 1/16（6.25cm）刻みなので、
     * どんな緩い坂でも段はこれより大きい。
     */
    private static final double STAIR_EPSILON = 0.05;
    /** 階段と見る段の高さの上限 [m]。1 ブロック（＋余裕）。 */
    private static final double STAIR_STEP_MAX = 1.05;
    /**
     * 階段と見る傾きの上限。35 度。roads の 30 度の坂（1 マスに 58cm）は入り、
     * 1 マスに 1 ブロックずつ上がる坂（45 度）は入らない——それは登れない決まり。
     */
    private static final double STAIR_SLOPE_MAX = Math.tan(Math.toRadians(35.0));
    /**
     * 地面をならす（{@link #smoothGround}）とき、混ぜてよいブロックの高さの差 [m]。roads の層ブロックの坂
     * （1 マスに 1〜3 層）は入り、ハーフの段（50cm）・縁石・壁は入らない。
     *
     * <p>30cm にすると段 25cm・幅 1m の坂まで混ざり、階段の線より悪くなった（8m/s の登りで跳ねが 0 → 34 回）。</p>
     */
    private static final double SMOOTH_GROUND_LIMIT = 0.2;
    /**
     * 縁の高さを補間で決めた階段の線（{@link #stairSide}）が、真下の地面より下へ潜ってよい量 [m]。
     *
     * <p>縁での補間の高さは段の上と下のちょうど中間なので、線は段の天面より最大で段の半分だけ低くなる
     * （roads の 1 層なら 3cm、混ぜてよい上限 {@link #SMOOTH_GROUND_LIMIT} の段でも 10cm）。
     * 真下の地面と高いほうを取ると、補間した地面のほうが線より常に上にあるので、1 ブロックごとに
     * 傾きが折れる補間の形に戻ってしまう。隣の縁石や壁にタイヤの端が乗ったときは、そちらを取る。</p>
     */
    private static final double STAIR_SINK_MAX = 0.1;
    /**
     * 階段の線を進む向きで引く（{@link #stairHeight}）のは、車がこれより速く動いているとき [m/s]。
     * {@link #STAIR_TRAVEL_FULL_SPEED} までは車体の向きと混ぜる。止まりかけの速度の向きは当てにならない。
     */
    private static final double STAIR_TRAVEL_MIN_SPEED = 0.5;
    private static final double STAIR_TRAVEL_FULL_SPEED = 2.0;
    /** 先読みで地面を調べる間隔 [m]。 */
    private static final double LOOKAHEAD_SPACING = 0.25;
    /** 先読みの斜面で登る高さ [m]。ハーフブロックの段。 */
    private static final double LOOKAHEAD_RISE = 0.5;
    /** 車体の上向きの鉛直成分がこれ以下（70 度より傾いた）なら、タイヤは地面に届かない。 */
    private static final double WHEEL_UPRIGHT_MIN = 0.34;

    /** 車体の角がこれより深くブロックに入っていたら、埋まったと見て持ち上げる [m]（{@link #buriedLift}）。 */
    private static final double BURIED_DEPTH = 0.5;
    /** 起き上がり補助の最中に、潜ったぶんとして持ち上げる上限 [m]。これより深いのは壁（上へ乗せない）。 */
    private static final double RIGHTING_MAX_LIFT = 1.0;
    /** 起き上がり補助の最中に、潜った角の横をどこまで見るか [m]。壁に触れた角を地面と取り違えないよう。 */
    private static final double RIGHTING_SIDE_CHECK = 0.2;
    /** 持ち上げるとき、ブロックの天面からさらに離す量 [m]。 */
    private static final double BURIED_CLEARANCE = 0.05;
    /** 1 回で持ち上げる上限 [m]。地中深くに落ちたものを地上まで飛ばさない（次のティックでまた上げる）。 */
    private static final double BURIED_MAX_LIFT = 6.0;

    private final World world;
    private CarState state;
    private CarSpec spec;
    private CarSpec gripSpec;
    private double wetness;

    /** 各輪の接地面の高さ。毎ティック使い回す。 */
    private final GroundContact groundContact = new GroundContact();
    /**
     * 前ティックで各輪へ渡した接地面の高さ [m]。段差の立ち上がりを制限するのと、
     * 壁に食い込んだ輪を据え置くのに使う。
     */
    private final double[] previousGroundHeight = newGroundHeights();
    /** 地面をならすときに、ブロックの列ごとの高さを覚えておく表（{@link #columnGround}）。輪ごとに作り直す。 */
    private final ColumnTable groundColumns = new ColumnTable();
    /** 同じく、階段の線を探すとき用。1 段深くまでレイを飛ばすので、表を分ける。 */
    private final ColumnTable stairColumns = new ColumnTable();
    /** 各輪へ渡した地面の出どころ。F3+B の地面の線（{@link GroundTrace}）の色分けに使う。 */
    private final GroundTrace.Source[] groundSources = new GroundTrace.Source[Wheel.COUNT];
    /** 各輪の真下の地面の高さ（線や斜面を足す前）。接地の記録にだけ使う。 */
    private final double[] rawGroundHeight = newGroundHeights();
    /** 次に {@link #setGroundHeight} へ渡す斜面が階段の線か（false なら先読み）。接地の記録にだけ使う。 */
    private boolean aheadIsStair;
    /** いま求めた階段の線が、縁の高さを補間で決めたものか（{@link #stairSide}）。 */
    private boolean stairSmoothed;
    /**
     * {@link #stairSide} が結んだ 2 点。{@code high} の向きへの距離と高さ（反対側は負の距離）。
     * {@link #stairHeight} が世界の座標に直して {@link #stairLine} へ入れる。
     */
    private double stairNearDistance, stairNearHeight, stairFarDistance, stairFarHeight;
    /** いま求めた階段の線の両端 {x1, y1, z1, x2, y2, z2}。F3+B で描くためだけのもの。 */
    private final double[] stairLine = new double[6];
    /** いま調べている輪の真ん中。F3+B の線を置く場所。 */
    private double probeX, probeZ;
    /** 各輪へ渡した地面の少し前までの記録。F3+B で描く。クライアントで物理を解いたときだけ書く。 */
    private final GroundTrace groundTrace = new GroundTrace();
    /**
     * 地面の中に埋まった輪（{@link #updateGroundContact}）。壁に当たった輪とは違い、
     * 上にある地面の天面まで少しずつ持ち上げる。
     */
    private final boolean[] buriedWheel = new boolean[Wheel.COUNT];
    private static double[] newGroundHeights() {
        double[] heights = new double[Wheel.COUNT];
        Arrays.fill(heights, Double.NEGATIVE_INFINITY);
        return heights;
    }
    /** 各輪が乗っている路面。物理には倍率として渡すので、こちらは表示用。 */
    private final RoadSurface[] surfaces = new RoadSurface[Wheel.COUNT];

    public GroundSensor(World world) {
        this.world = world;
        Arrays.fill(surfaces, RoadSurface.PAVED);
    }

    /** 各輪の接地面の高さ。物理（{@code CarPhysics}・{@code CarBody}）へ渡す。 */
    public GroundContact contact() {
        return groundContact;
    }

    /** 各輪が乗っている路面（表示用。物理には倍率として渡してある）。 */
    public RoadSurface[] surfaces() {
        return surfaces;
    }

    /** 各輪へ渡した地面の少し前までの記録（F3+B で描く）。 */
    public GroundTrace trace() {
        return groundTrace;
    }

    /**
     * 前に渡した地面の高さを忘れる。段差の立ち上がり制限は前ティックの値を見るので、運転を引き継いだときと、
     * 埋まった車を持ち上げたときに捨てる。
     */
    public void forgetHeights() {
        Arrays.fill(previousGroundHeight, Double.NEGATIVE_INFINITY);
    }

    /**
     * 各輪の真下へレイを飛ばして、接地面の高さを調べる。
     *
     * <p>ここが物理コアと Minecraft の境目。物理コアは {@link GroundContact} を読むだけで、
     * 地形の調べ方を知らない。</p>
     *
     * <p><b>レイはハードポイントからではなく、タイヤ下端 + 登坂限界から飛ばす。</b>
     * 車の当たり判定の箱は車体ぶんしかない（1.8 × 0.7 × 1.8）のに対し、タイヤは
     * 前後へ 0.5m 以上はみ出している。そのため<b>箱が壁に当たるより先にタイヤの
     * サンプル点が壁の中へ入る</b>。ハードポイントから飛ばすとレイの起点自体が
     * 壁の中に入り、{@code VoxelShape#clip} は<b>起点そのものを当たり位置として返す</b>
     * ので、「シャシーの高さに地面がある」＝サスが 1m 近く食い込んだ、と解釈されて
     * <b>8 倍の荷重でその輪が跳ね上がる</b>。斜めに突っ込むと片側だけが跳ね上がるので、
     * 車が壁を登りながら回転していく。</p>
     *
     * <p>起点を登坂限界に置けば、そこまで塞がっているかどうかで<b>乗り越えられる段差と
     * 壁を見分けられる</b>。塞がっていれば（＝起点が block の中）壁なので、その輪の
     * 接地面は<b>据え置く</b>——タイヤは路面に接したまま壁に当たっているのだから、
     * 高さは変わらないのが正しい。前へ進めないのはバニラの箱の衝突が止めてくれる。</p>
     */
    /**
     * 4 輪の接地面を探し直して {@link #contact()} へ入れる。物理の 1 刻みごとに呼ぶ。
     *
     * @param geometrySpec 車輪の位置・ストローク・半径を読む諸元
     * @param gripSpec     タイヤの路面ごとの倍率を読む諸元（部品を足したもの）
     * @param wet          路面の濡れ具合 0..1
     */
    public void update(CarState carState, CarSpec geometrySpec, CarSpec gripSpec, double wet,
                       double carX, double carZ, double dt) {
        this.state = carState;
        this.spec = geometrySpec;
        this.gripSpec = gripSpec;
        this.wetness = wet;
        updateGroundContact(carX, carZ, dt);
    }

    private void updateGroundContact(double carX, double carZ, double dt) {
        // 大きく傾いた（70 度を超えた）車のタイヤは地面に届かない。サスペンションは真下へしか
        // 伸びないので、そのままだと横倒しや裏返しの車がタイヤで屋根を突き抜けて地面を押す。
        // 横倒しの車は側面で、裏返しの車は屋根で地面に乗る（車体の仕事）
        if (Math.cos(state.pitch) * Math.cos(state.roll) <= WHEEL_UPRIGHT_MIN) {
            for (Wheel wheel : Wheel.VALUES) {
                setGroundHeight(wheel, Double.NEGATIVE_INFINITY, 0.0);
                groundSources[wheel.ordinal()] = GroundTrace.Source.TILTED;
                rawGroundHeight[wheel.ordinal()] = Double.NEGATIVE_INFINITY;
            }
            return;
        }
        double forwardX = state.forwardX();
        double forwardZ = state.forwardZ();
        double rightX = state.rightX();
        double rightZ = state.rightZ();
        // 落ちているときは、この 1 刻みで落ちる距離だけ先まで探す。届かないと、地面を見つける
        // 前に車体が地面へ着き、縮みきったサスペンションを止める当たり（CarBody）も間に合わず埋まった
        // （10m の落下で 1 ティックに 68cm 落ちる）
        double fallReach = Math.max(0.0, -state.verticalSpeed) * dt;
        // 段差の立ち上がり制限は 1 ティックあたりの量なので、刻みの長さで割り振る
        double rise = GROUND_RISE_PER_TICK * dt / TICK_SECONDS;
        double rayLength = spec.suspensionMaxLength() + spec.wheelRadius() + GROUND_RAY_MARGIN + fallReach;

        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);

            // 水平の位置は傾きの cos ぶん縮む。高さだけ傾けると、坂の上で前輪が地面を先で探す
            double forward = state.horizontalForwardOffset(forwardOffset);
            double right = state.horizontalRightOffset(rightOffset);
            double x = carX + forwardX * forward + rightX * right;
            double z = carZ + forwardZ * forward + rightZ * right;
            double top = state.hardpointHeight(forwardOffset, rightOffset);
            double bottom = top - rayLength;
            // 今のタイヤ下端。ここから登坂限界ぶん上までが「乗り越えられる」範囲
            double wheelBottom = top - state.suspensionLength[index] - spec.wheelRadius();
            double from = Math.max(wheelBottom + spec.maxClimbStep(), bottom + GROUND_RAY_MARGIN);
            probeX = x;
            probeZ = z;
            // レイの範囲が輪ごとに違うので、覚えた高さは輪ごとに捨てる
            groundColumns.clear();
            stairColumns.clear();

            Hit hit = world.castDown(x, from, bottom, z);
            // タイヤには幅がある。内側と外側の端でも地面を探し、いちばん高いところに乗せる。
            // 中心だけで見ると、台の端から半分はみ出したタイヤが台の下まで落ち、
            // 内側の面が台の側面に刺さった（そこをタイヤの当たりが横へ押し返して車が揺れた）。
            // 中心が壁の中なら壁のまま（据え置き）
            if (!hit.inside()) {
                double outward = wheel.isLeft() ? -1.0 : 1.0;
                for (double edge : world.tireGroundEdges()) {
                    double ex = x + rightX * outward * edge;
                    double ez = z + rightZ * outward * edge;
                    Hit side = world.castDown(ex, from, bottom, ez);
                    if (side.miss() || side.inside()) {
                        continue;
                    }
                    if (hit.miss() || side.y() > hit.y()) {
                        hit = side;
                    }
                }
            }

            rawGroundHeight[index] = hit.miss()
                    ? Double.NEGATIVE_INFINITY : hit.y();
            if (hit.miss()) {
                // 階段の下りでは、縁を越えた直後の下の段がレイの先にある。1 段ぶん深く探して、
                // 階段なら段の角を結んだ斜面で渡す。崖なら今までどおり地面なし（落ちる）
                Hit deep = world.castDown(x, from, bottom - STAIR_STEP_MAX, z);
                if (!deep.miss() && !deep.inside()) {
                    double height = deep.y();
                    double stair = stairHeight(x, z, from, bottom, height);
                    if (stair >= bottom) {
                        RoadSurface surface = deep.surface();
                        surfaces[index] = surface;
                        double previous = previousGroundHeight[index];
                        double base = previous > Double.NEGATIVE_INFINITY ? previous : Math.min(height, wheelBottom);
                        aheadIsStair = true;
                        setGroundHeight(wheel, height, tireGrip(surface), tireRolling(surface), base, stair, rise);
                        groundSources[index] = GroundTrace.Source.DEEP_STAIR;
                        rawGroundHeight[index] = height;
                        continue;
                    }
                }
                setGroundHeight(wheel, Double.NEGATIVE_INFINITY, rise);
                groundSources[index] = GroundTrace.Source.MISS;
                continue;
            }
            if (!hit.inside()) {
                buriedWheel[index] = false;
            } else if (buriedWheel[index] || previousGroundHeight[index] == Double.NEGATIVE_INFINITY) {
                // 地面を見ていなかった輪が、いきなり登坂限界より深く地面の中にある＝走って壁に
                // 当たったのではなく、上から（回って）地面に入った。起き上がり補助が横倒しの車を
                // 起こすとき、伸びきったサスペンションの先のタイヤが地面へ 1.5m ほど潜る
                // （大きいタイヤほど深い）。据え置くと地面が無いままで、タイヤを埋めたまま
                // 車体の角で斜めに止まった。上にある地面の天面を、上がる速さを制限して渡す
                double surface = world.solidTopAbove(x, from, z);
                if (surface > Double.NEGATIVE_INFINITY && surface <= top + spec.wheelRadius()) {
                    buriedWheel[index] = true;
                    double previous = previousGroundHeight[index];
                    double base = previous > Double.NEGATIVE_INFINITY ? previous : wheelBottom;
                    setGroundHeight(wheel, surface, tireGrip(surfaces[index]), tireRolling(surfaces[index]),
                            base, Double.NEGATIVE_INFINITY, rise);
                    groundSources[index] = GroundTrace.Source.BURIED;
                    continue;
                }
            }
            if (hit.inside()) {
                // 起点が塞がっている＝登坂限界より上まで続く壁。乗せずに据え置く
                groundContact.set(wheel, previousGroundHeight[index],
                        tireGrip(surfaces[index]), tireRolling(surfaces[index]));
                groundSources[index] = GroundTrace.Source.WALL_HOLD;
                continue;
            }

            // 当たったブロックが路面。知らないブロックは舗装路に落ちるので、
            // 他 MOD の道路ブロックはそのままアスファルトとして走れる
            RoadSurface surface = hit.surface();
            surfaces[index] = surface;
            double height = smoothGround(hit.x(), hit.z(), from, bottom,
                    hit.y(), groundColumns);
            double previous = previousGroundHeight[index];
            // 段の高さを測る基準。地面を見失っていた輪は、いまのタイヤ下端から
            double base = previous > Double.NEGATIVE_INFINITY ? previous : Math.min(height, wheelBottom);
            // 階段（段の角を結んだ斜面）が取れればそちらを使う。先読みの斜面は 0.25m おきの
            // 粗いもので、混ぜると地面が数 cm ずつ跳ね、ダンパーがそれを速度として拾って車を跳ね上げる
            double stair = stairHeight(x, z, from, bottom, height);
            double ahead = stair > Double.NEGATIVE_INFINITY ? stair : lookAhead(x, z, from, bottom, base, height);
            aheadIsStair = stair > Double.NEGATIVE_INFINITY;
            setGroundHeight(wheel, height, tireGrip(surface), tireRolling(surface), base, ahead, rise);
        }
    }

    /**
     * 段から斜面を下ろしたときの、いまの位置での高さ。前にある低い段からは手前へ（登り）、
     * 後ろにある低い段からはこちらへ（下り）。低い段が無ければ負の無限大。
     *
     * @param base   この輪のいまの地面。前の段はこれより {@link CarBody#STEP_LIFT} を超えて高いものは見ない
     * @param height この輪の真下の地面。後ろの段はこれより {@link CarBody#STEP_LIFT} を超えて高いものは見ない（崖）
     */
    private double lookAhead(double x, double z, double from, double bottom, double base, double height) {
        double velocityX = state.velocityX();
        double velocityZ = state.velocityZ();
        double speed = Math.hypot(velocityX, velocityZ);
        double best = Double.NEGATIVE_INFINITY;
        if (speed < 0.1) {
            return best;
        }
        double reach = Math.min(LOOKAHEAD_MAX, speed * LOOKAHEAD_SECONDS);
        double slope = LOOKAHEAD_RISE / reach;
        for (double distance = LOOKAHEAD_SPACING; distance <= reach + 1.0e-9; distance += LOOKAHEAD_SPACING) {
            double ground = probeGround(x, z, velocityX / speed, velocityZ / speed, distance, from, bottom, groundColumns);
            if (Double.isNaN(ground) || ground - base > CarBody.STEP_LIFT) {
                continue;
            }
            best = Math.max(best, ground - slope * distance);
        }
        // 下り。後ろの段から斜面を下ろす
        double downReach = Math.min(LOOKAHEAD_DOWN_MAX, speed * LOOKAHEAD_SECONDS);
        double downSlope = LOOKAHEAD_RISE / downReach;
        for (double distance = LOOKAHEAD_SPACING; distance <= downReach + 1.0e-9; distance += LOOKAHEAD_SPACING) {
            double ground = probeGround(x, z, -velocityX / speed, -velocityZ / speed, distance, from, bottom,
                    groundColumns);
            if (Double.isNaN(ground) || ground - height > CarBody.STEP_LIFT) {
                continue;
            }
            best = Math.max(best, ground - downSlope * distance);
        }
        return best;
    }

    /**
     * 階段を斜面として見たときの、いまの位置での高さ。段の角（縁の上端）を結んだ線の上。
     * 階段でなければ負の無限大。
     *
     * <p>roads の階層ブロックで作った坂は、1 マスごとに平らな段が並んだもの。30 度の坂なら
     * 1 マスに 58cm の段になり、低い段の先読み（{@link #lookAhead}、55cm まで）から外れて
     * <b>崖が並んでいるのと同じ扱いになっていた</b>——段を降りるたびに前輪が宙に出て、
     * 下りでは 4 輪とも浮いている時間が 5〜8 割に達した。実際の車が走るのは段の角どうしを
     * 結んだ面なので、その面を地面として渡す。</p>
     *
     * <p>階段と見るのは、段の幅が測れるとき（こちらの段の反対側にも縁があるか、高い側の
     * 向こうに次の段があるとき）だけ。<b>崖・単独の段は今までどおり</b>（放物線で飛ぶ・
     * 段差として登る）。急すぎるもの（{@link #STAIR_SLOPE_MAX} 超え、1 マスに 1 ブロックの坂など）も
     * 階段とは見ない。</p>
     *
     * <p><b>向きは車の進む向き</b>（止まりかけは車体の前後、{@link #STAIR_TRAVEL_MIN_SPEED}）。
     * 線は引いた向きの 1 本の断面しかならさないので、ドリフト中に車体の向きで引くと、タイヤは断面を
     * 横切るように動き、断面がずれるたびに見つかる縁が変わって線が跳ぶ。補間で縁の高さを決めた線でも、
     * 実際の記録では浮いていた時間が横滑り 0〜10 度で 2〜3%、45 度超えで 17〜23% に増えた。
     * 地形だけの計算では、進む向きで引けば横滑りに依らず真っすぐ走ったときと同じになる
     * （6.25%・格子から 20 度の坂、横滑り 60 度で、傾きの変化の 95% 点が 11.9% → 1.3%）。
     * 以前は開発用に車体の向き・補間なしへ切り替えられた（Ctrl+J）が、進む向きに固定して切り替えは消した。</p>
     *
     * <p><b>縁は補間する前の地面で探し、縁での高さは補間で決める</b>（地面の補間が入りのとき）。
     * 補間した地面には段が無いので、そこで縁を探すと線がまったく作られず、ブロックの中心ごとに
     * 傾きが折れる補間の面がそのまま地面になっていた。格子に沿ったゆるい坂（2 ブロックで 1 層）では
     * 傾きが 1m ごとに 0 と 6.25% を行き来し、同じ道を走り比べると浮いていた時間が補間なしの 4.9% から
     * 13.7% に増えた。縁での補間の高さは段の上と下の中間なので、格子に沿った坂では角を結んだ線と
     * 平行な直線（半段低い）になる。斜めの道では、横のブロックを拾った高さが均される。</p>
     *
     * @param height この輪の真下の地面
     */
    private double stairHeight(double x, double z, double from, double bottom, double height) {
        double dirX = state.forwardX();
        double dirZ = state.forwardZ();
        double velocityX = state.velocityX();
        double velocityZ = state.velocityZ();
        double speed = Math.hypot(velocityX, velocityZ);
        if (speed > STAIR_TRAVEL_MIN_SPEED) {
            // 前後の両方を探すので、後ろへ進んでいても線は同じ。車体の向きと揃えてから混ぜる
            double sign = velocityX * dirX + velocityZ * dirZ < 0.0 ? -1.0 : 1.0;
            double weight = Math.min(1.0,
                    (speed - STAIR_TRAVEL_MIN_SPEED) / (STAIR_TRAVEL_FULL_SPEED - STAIR_TRAVEL_MIN_SPEED));
            double mixX = dirX * (1.0 - weight) + sign * velocityX / speed * weight;
            double mixZ = dirZ * (1.0 - weight) + sign * velocityZ / speed * weight;
            double length = Math.hypot(mixX, mixZ);
            if (length > 1.0e-6) {
                dirX = mixX / length;
                dirZ = mixZ / length;
            }
        }
        double deep = bottom - STAIR_STEP_MAX;
        // 段の高さは補間する前の、輪の真ん中で測る（補間した高さは段の途中の値になる）
        double level = height;
        double raw = rawGround(x, z, from, deep);
        if (!Double.isNaN(raw)) {
            level = raw;
        }
        double[] ahead = stairEdges(x, z, dirX, dirZ, from, deep, level);
        double[] behind = stairEdges(x, z, -dirX, -dirZ, from, deep, level);
        // 後ろが高い（下り）か、前が高い（上り）か。谷ならどちらも立つので高いほう
        double down = stairSide(level, behind, ahead);
        boolean downSmoothed = stairSmoothed;
        double downNear = stairNearDistance, downNearHeight = stairNearHeight;
        double downFar = stairFarDistance, downFarHeight = stairFarHeight;
        double up = stairSide(level, ahead, behind);
        if (down > up) {
            stairSmoothed = downSmoothed;
            // 下りは後ろが高い側。距離は後ろ向きに測ってある
            setStairLine(x, z, -dirX, -dirZ, downNear, downNearHeight, downFar, downFarHeight);
        } else if (up > Double.NEGATIVE_INFINITY) {
            setStairLine(x, z, dirX, dirZ, stairNearDistance, stairNearHeight, stairFarDistance, stairFarHeight);
        }
        return Math.max(down, up);
    }

    private void setStairLine(double x, double z, double dirX, double dirZ,
                              double near, double nearHeight, double far, double farHeight) {
        stairLine[0] = x + dirX * near;
        stairLine[1] = nearHeight;
        stairLine[2] = z + dirZ * near;
        stairLine[3] = x + dirX * far;
        stairLine[4] = farHeight;
        stairLine[5] = z + dirZ * far;
    }


    /**
     * 片側へ歩いて、段の縁を手前から 2 つまで探す。{縁 1 までの距離, その先の高さ, 縁 2 までの距離, その先の高さ,
     * 縁 1 での補間の高さ, 縁 2 での補間の高さ}。見つからなければ NaN。縁の位置は二分して 1〜2mm まで詰める
     * （{@link #STAIR_EDGE_BISECTIONS}）。
     *
     * <p>縁は補間する前の地面で探す。補間の高さは、地面の補間が入りで、段が混ぜてよい高さ
     * （{@link #SMOOTH_GROUND_LIMIT}）以下のときだけ。それ以外は NaN（段の角を使う）。</p>
     */
    private double[] stairEdges(double x, double z, double dirX, double dirZ, double from, double bottom,
                                double height) {
        double[] edges = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double level = height;
        double previous = 0.0;
        int found = 0;
        for (double distance = LOOKAHEAD_SPACING; distance <= STAIR_REACH + 1.0e-9 && found < 2;
             distance += distance < STAIR_NEAR_REACH - 1.0e-9 ? LOOKAHEAD_SPACING : STAIR_FAR_SPACING) {
            double ground = rawGround(x + dirX * distance, z + dirZ * distance, from, bottom);
            if (Double.isNaN(ground)) {
                break;
            }
            if (Math.abs(ground - level) > STAIR_EPSILON) {
                double near = previous;
                double far = distance;
                for (int i = 0; i < STAIR_EDGE_BISECTIONS; i++) {
                    double middle = (near + far) / 2.0;
                    double g = rawGround(x + dirX * middle, z + dirZ * middle, from, bottom);
                    if (!Double.isNaN(g) && Math.abs(g - level) <= STAIR_EPSILON) {
                        near = middle;
                    } else {
                        far = middle;
                    }
                }
                // 縁は段差（不連続）でなければならない。なめらかな斜面は詰めても差が縮むので段とは見ない
                double nearGround = rawGround(x + dirX * near, z + dirZ * near, from, bottom);
                double farGround = rawGround(x + dirX * far, z + dirZ * far, from, bottom);
                if (Double.isNaN(nearGround) || Double.isNaN(farGround)
                        || Math.abs(farGround - nearGround) <= STAIR_EPSILON) {
                    break;
                }
                double edge = (near + far) / 2.0;
                edges[found * 2] = edge;
                edges[found * 2 + 1] = ground;
                if (Math.abs(ground - level) <= SMOOTH_GROUND_LIMIT) {
                    // 段の上と下の中間を基準にならす（縁の上ではどちらの段とも言えない）
                    edges[4 + found] = smoothGround(x + dirX * edge, z + dirZ * edge, from, bottom,
                            (ground + level) / 2.0, stairColumns);
                }
                found++;
                level = ground;
            }
            previous = distance;
        }
        return edges;
    }

    /**
     * {@code high} の側に高い段があるときの、段の角を結んだ線の高さ。斜面の長さ（段の幅）は、
     * 反対側の縁（こちらの段の始まり）か、高い側の次の縁（向こうの段の幅）から取る。
     *
     * <p>両端の縁に補間の高さ（{@link #stairEdges}）があれば、角の代わりにそれを結ぶ。そのときは
     * 段の天面より下になることがあるので、天面で頭打ちにしない（{@link #stairSmoothed} が立つ）。</p>
     */
    private double stairSide(double height, double[] high, double[] low) {
        stairSmoothed = false;
        double edge = high[0];
        double top = high[1];
        double rise = top - height;
        if (Double.isNaN(edge) || rise <= STAIR_EPSILON || rise > STAIR_STEP_MAX) {
            return Double.NEGATIVE_INFINITY;
        }
        double run;
        double ground;
        if (!Double.isNaN(low[0]) && low[1] < height - STAIR_EPSILON) {
            // こちらの段は反対側の縁から始まる。その角（高さ height）から高い段の角まで
            run = edge + low[0];
            ground = height + rise * low[0] / run;
            stairNearDistance = -low[0];
            stairNearHeight = height;
            stairFarDistance = edge;
            stairFarHeight = top;
            if (!Double.isNaN(low[4]) && !Double.isNaN(high[4])) {
                // 縁での補間の高さどうしを結ぶ
                ground = low[4] + (high[4] - low[4]) * low[0] / run;
                stairSmoothed = true;
                stairNearHeight = low[4];
                stairFarHeight = high[4];
            }
        } else if (!Double.isNaN(high[2]) && high[3] > top + STAIR_EPSILON) {
            // 階段の端の段。向こうの段の幅で同じ傾きの斜面を延ばす
            run = high[2] - edge;
            ground = top - rise / run * edge;
            // 線は輪の位置まで延ばしてある。描くのは輪から向こうの段の縁まで
            stairNearDistance = 0.0;
            stairNearHeight = ground;
            stairFarDistance = high[2];
            stairFarHeight = high[3];
            if (!Double.isNaN(high[4]) && !Double.isNaN(high[5])) {
                ground = high[4] - (high[5] - high[4]) / run * edge;
                stairSmoothed = true;
                stairNearHeight = ground;
                stairFarHeight = high[5];
            }
        } else {
            return Double.NEGATIVE_INFINITY;
        }
        if (rise / run > STAIR_SLOPE_MAX) {
            stairSmoothed = false;
            return Double.NEGATIVE_INFINITY;
        }
        return stairSmoothed ? ground : Math.max(height, ground);
    }

    /**
     * 輪の位置から {@code dir} へ {@code distance} 進んだところの地面の高さ。周りのブロックとならしたもの
     * （{@link #smoothGround}）。当たらない・起点が埋まっているときは NaN。
     *
     * @param columns ブロックの列ごとの高さを覚えておく表。レイの範囲（{@code from}〜{@code bottom}）ごとに分ける
     */
    private double probeGround(double x, double z, double dirX, double dirZ, double distance,
                               double from, double bottom, ColumnTable columns) {
        double px = x + dirX * distance;
        double pz = z + dirZ * distance;
        double own = rawGround(px, pz, from, bottom);
        return Double.isNaN(own) ? own : smoothGround(px, pz, from, bottom, own, columns);
    }

    /** その点の真下の地面の高さ（ならす前）。当たらない・起点が埋まっているときは NaN。 */
    private double rawGround(double x, double z, double from, double bottom) {
        Hit hit = world.castDown(x, from, bottom, z);
        if (hit.miss() || hit.inside()) {
            return Double.NaN;
        }
        return hit.y();
    }

    /**
     * 地面を周りのブロックとならす。その点を囲む 4 つのブロックの中心の高さを双線形補間する。
     *
     * <p><b>斜めの道のため。</b>roads の層ブロックで作った坂は、ブロックごとに 1/16（6.25cm）刻みの
     * 高さを持つ。格子に対して斜めに走ると、進む向きに沿っても層が上がったり下がったりし
     * （隣のブロックの中心が後ろに来る）、タイヤ幅の端が横のブロックに乗ると 6cm 高い地面を拾う。
     * 段の角を結んだ線（{@link #stairHeight}）は進む向きの 1 本の断面しかならさないので、これは消せない。
     * 実際に既定の車で下ったときの記録では、縁を越えたときに地面が跳ぶ割合が格子に沿うと 1.6%、
     * 30〜45 度では 17〜18% になり、1 分で 129 回、荷重が停車時の 4.5 倍とほぼ 0 の間を跳ねた。
     * 補間すると、シミュレーションの斜めの道（6.25%・12.5%、26〜45 度）で跳びが 100〜200 回から 0〜24 回になる。</p>
     *
     * <p><b>高さの差が {@link #SMOOTH_GROUND_LIMIT} を超えるブロックは混ぜない</b>（その点の高さのまま）。
     * 壁・縁石・ハーフの段・崖は今までどおり段として扱い、階段の線と先読みに任せる。</p>
     */
    private double smoothGround(double x, double z, double from, double bottom, double own,
                                ColumnTable columns) {
        double cx = x - 0.5;
        double cz = z - 0.5;
        int i0 = (int) Math.floor(cx);
        int j0 = (int) Math.floor(cz);
        double fx = cx - i0;
        double fz = cz - j0;
        double h00 = blendColumn(columnGround(i0, j0, from, bottom, columns), own);
        double h10 = blendColumn(columnGround(i0 + 1, j0, from, bottom, columns), own);
        double h01 = blendColumn(columnGround(i0, j0 + 1, from, bottom, columns), own);
        double h11 = blendColumn(columnGround(i0 + 1, j0 + 1, from, bottom, columns), own);
        return (h00 * (1.0 - fx) + h10 * fx) * (1.0 - fz) + (h01 * (1.0 - fx) + h11 * fx) * fz;
    }

    /** 混ぜてよいブロックの高さならそのまま、差が大きい・地面が無いならその点の高さ。 */
    private static double blendColumn(double column, double own) {
        return Double.isNaN(column) || Math.abs(column - own) > SMOOTH_GROUND_LIMIT ? own : column;
    }

    /** ブロックの列の中心での地面の高さ。輪を 1 つ調べる間は覚えておいて使い回す。地面が無ければ NaN。 */
    private double columnGround(int blockX, int blockZ, double from, double bottom, ColumnTable columns) {
        long key = ((long) blockX << 32) ^ (blockZ & 0xffffffffL);
        double known = columns.get(key);
        if (!Double.isNaN(known)) {
            return known == Double.NEGATIVE_INFINITY ? Double.NaN : known;
        }
        double ground = rawGround(blockX + 0.5, blockZ + 0.5, from, bottom);
        columns.put(key, Double.isNaN(ground) ? Double.NEGATIVE_INFINITY : ground);
        return ground;
    }

    /**
     * その路面で物理へ渡すグリップの倍率。路面（と濡れ具合）に、履いているタイヤのその路面での倍率を掛ける。
     *
     * <p><b>タイヤの路面ごとの得意・不得意はここで掛ける</b>（物理コアは路面の種類を知らない）。
     * ノーマルタイヤはどの路面でも 1 なので、路面の値がそのまま渡る。</p>
     */
    private double tireGrip(RoadSurface surface) {
        return surface.gripScale(wetness) * gripSpec.tire().gripOn(surface.surfaceName());
    }

    /** その路面で物理へ渡す転がり抵抗の倍率。路面に、タイヤのその路面での倍率を掛ける。 */
    private double tireRolling(RoadSurface surface) {
        return surface.rollingScale() * gripSpec.tire().rollingOn(surface.surfaceName());
    }

    private void setGroundHeight(Wheel wheel, double height, double rise) {
        int index = wheel.ordinal();
        double previous = previousGroundHeight[index];
        setGroundHeight(wheel, height, 1.0, 1.0, previous > Double.NEGATIVE_INFINITY ? previous : height,
                Double.NEGATIVE_INFINITY, rise);
    }

    /**
     * 接地面の高さを物理コアへ渡す。<b>上がる向きだけ 1 回あたりの量を制限する</b>。
     *
     * <p>段差の天面をいきなり渡すと、その食い込みがまるごとバンプストップに入って
     * 車が跳ね上がる。少しずつ上げれば、サスが縮みながら車体を持ち上げていく。
     * 下がる向き（崖から落ちる、着地する）は制限しない——遅らせると宙に浮く。</p>
     *
     * <p><b>地面を見失っていた輪（穴の上で宙に浮いていた輪）が再び見つけたときも制限する。</b>
     * そのときは前ティックの地面が無いので、いまのタイヤ下端から上げる。制限しないと、穴から
     * 出た瞬間に地面が 1m 一気に上がってバンプストップに乗り、その輪に停車時の 8 倍の荷重が
     * 跳ねた（1 輪を 1 ブロックの穴へ落として抜けるとき）。</p>
     *
     * <p><b>低い段（{@link CarBody#STEP_LIFT} 以内）は制限しない。</b>先読み（{@link #lookAhead}）の
     * 斜面で縁の手前から上げてあるので、跳ね上がらない。制限するのは 1 ブロック以上の段だけ。</p>
     *
     * @param base  この輪のいまの地面（地面を見失っていた輪はいまのタイヤ下端）。段の高さをここから測る
     * @param ahead 先読みの斜面の高さ。無ければ負の無限大
     * @param rise  この 1 回で上げてよい量 [m]（{@link #GROUND_RISE_PER_TICK} を刻みで割り振ったもの）
     */
    private void setGroundHeight(Wheel wheel, double height, double gripScale, double rollingScale,
                                 double base, double ahead, double rise) {
        int index = wheel.ordinal();
        double limited;
        if (height - base <= CarBody.STEP_LIFT) {
            // 低い段は先読みの斜面で登っているので、上がる速さは制限しない。
            // 縁の高さを補間で決めた階段の線は、真下の地面より少し下へ潜ってよい（STAIR_SINK_MAX）
            limited = aheadIsStair && stairSmoothed
                    ? Math.max(ahead, height - STAIR_SINK_MAX)
                    : Math.max(height, ahead);
            // 線が真下の地面より下でも、線を渡していれば線の出どころ
            groundSources[index] = limited != height
                    ? (aheadIsStair ? GroundTrace.Source.STAIR : GroundTrace.Source.AHEAD)
                    : GroundTrace.Source.FLAT;
        } else {
            limited = Math.min(height, Math.max(ahead, base + rise));
            groundSources[index] = limited < height ? GroundTrace.Source.RISE_LIMIT : GroundTrace.Source.FLAT;
        }
        if (world.recordsTrace()) {
            double raw = rawGroundHeight[index];
            groundTrace.add(index, probeX, probeZ, raw > Double.NEGATIVE_INFINITY ? raw : Double.NaN,
                    limited > Double.NEGATIVE_INFINITY ? limited : Double.NaN, groundSources[index],
                    aheadIsStair && stairSmoothed && limited != height);
            if (aheadIsStair) {
                groundTrace.setStair(index, stairLine[0], stairLine[1], stairLine[2],
                        stairLine[3], stairLine[4], stairLine[5], stairSmoothed);
            } else {
                groundTrace.clearStair(index);
            }
        }
        aheadIsStair = false;
        previousGroundHeight[index] = limited;
        groundContact.set(wheel, limited, gripScale, rollingScale);
    }

    /**
     * 車体の角が地面に埋まっていたら、持ち上げる量 [m]（上限 {@link #BURIED_MAX_LIFT}）。埋まっていなければ 0。
     *
     * <p>縦に深いだけでは足りない。壁に数 cm 入った角も「上まで 0.5m 以上ある」ので、
     * 横にも深く囲まれていること（前後左右に離れた点もブロックの中）を見る。
     * そうでないと、壁に当たっただけの車を壁の上へ持ち上げてしまう。</p>
     *
     * <p>起き上がり補助は重心まわりに回すので、地面側の縁が潜る（車体の当たりが押し返しても、
     * 補助が毎刻み角速度を置き直すので追いつかない）。その間は浅く潜ったぶんも持ち上げる。</p>
     *
     * @param corners 車体の角（車体の座標。{@code CarBody#corners}）
     * @param axes    車体の向き（{@code CarBody#axes}）
     */
    public double buriedLift(CarState carState, double[][] corners, double[][] axes, double x, double y, double z) {
        double lift = 0.0;
        boolean righting = com.jdmmc.kurumamod.physics.CarPhysics.isRighting(carState);
        for (double[] corner : corners) {
            double cx = x + axes[0][0] * corner[0] + axes[1][0] * corner[1] + axes[2][0] * corner[2];
            double cy = y + axes[0][1] * corner[0] + axes[1][1] * corner[1] + axes[2][1] * corner[2];
            double cz = z + axes[0][2] * corner[0] + axes[1][2] * corner[1] + axes[2][2] * corner[2];
            double depth = world.solidTopAbove(cx, cy, cz) - cy;
            if (depth > BURIED_DEPTH && surroundedSideways(cx, cy, cz, BURIED_DEPTH)) {
                lift = Math.max(lift, depth + BURIED_CLEARANCE);
            } else if (righting && depth > 0.0 && depth <= RIGHTING_MAX_LIFT
                    && surroundedSideways(cx, cy, cz, RIGHTING_SIDE_CHECK)) {
                lift = Math.max(lift, depth);
            }
        }
        return Math.min(lift, BURIED_MAX_LIFT);
    }

    /** 点から東西南北へ {@code distance} 離れた 4 点が、すべてブロックの中にあるか。壁に触れているだけの点を除く。 */
    private boolean surroundedSideways(double x, double y, double z, double distance) {
        return world.solidTopAbove(x + distance, y, z) > y
                && world.solidTopAbove(x - distance, y, z) > y
                && world.solidTopAbove(x, y, z + distance) > y
                && world.solidTopAbove(x, y, z - distance) > y;
    }
}
