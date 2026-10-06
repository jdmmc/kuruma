package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/**
 * Blender から書き出した Wavefront OBJ を読んで描くモデル。
 *
 * <p>GeckoLib（Bedrock の geo.json）は<b>箱しか描けない</b>ので、Blender の任意メッシュは載らない。
 * Blockbench に OBJ を読ませてもメッシュ要素になって Bedrock 形式では保存できないため、
 * 造形をそのまま使うには OBJ を自分で読むしかない。</p>
 *
 * <h2>座標系</h2>
 *
 * <p>読み込み時に <b>X と Z を反転</b>して、そのまま
 * {@link CarObjRenderer#applyRotations 姿勢を掛けたあとのエンティティ空間}
 * （+X が車体右・+Y が上・<b>-Z が車体前方</b>）へ移す。これにより Blender 側は
 * <b>標準の向き（前方 -Y・上 +Z）と標準のエクスポート設定（-Z Forward / Y Up）のまま</b>でよい。</p>
 *
 * <p>この符号は推測ではなく Blender 5.2 で実測して決めた。既定のエクスポート設定は
 * Blender の {@code (x, y, z)} を OBJ の {@code (x, z, -y)} にするので、
 * <b>Blender の慣習的な前方 -Y は OBJ では +Z になる</b>（設定名が「-Z Forward」なので
 * -Z になりそうに見えるが、そうはならない）。ここで 180 度回してやる必要がある。</p>
 *
 * <h2>単位</h2>
 *
 * <p><b>モデルはメートルで作る。</b>エンティティ空間も 1 ブロック = 1m なので拡大率は 1.0 が基準。
 * 諸元に合わせた拡大は {@link CarObjRenderer} 側が掛ける。</p>
 *
 * <h2>UV</h2>
 *
 * <p>OBJ の V 軸は下が 0、Minecraft のテクスチャは上が 0 なので、読み込み時に反転する。
 * Blender 側で気にする必要はない。</p>
 *
 * <h2>半透明（ガラス）</h2>
 *
 * <p><b>テクスチャに半透明の画素を置くだけでは透けない。</b>車体を描いている
 * {@code entityCutoutNoCull} は「α が 0.1 未満なら捨てる、それ以外は不透明」でしか扱わない
 * （シェーダに混色そのものが無い）ので、α 128 の窓は<b>べったり不透明</b>に出る。
 * 透かすには<b>別の {@code RenderType} で、車体を描いた後に描き直す</b>しかない。</p>
 *
 * <p>そこで頂点ごとに不透明度を持たせ、{@link Pass} で 2 回に分けて描く。
 * <b>どちらのパスへ行くかは頂点の α ひとつで決まる</b>——名前を見るのは読み込みのときだけで、
 * 描画中に名前を照合しない。α の決まり方:</p>
 *
 * <ol>
 *   <li>MTL に {@code d}（または {@code Tr}）が書いてあればその値。Blender では
 *       マテリアルの <b>Alpha</b> がそのまま出る</li>
 *   <li>書いていなくて、オブジェクト名が {@value #GLASS_PREFIX} で始まるなら
 *       {@value #GLASS_DEFAULT_ALPHA}。<b>マテリアルを割り当てていないメッシュでも
 *       名前だけで透けさせられる</b>ようにするための逃げ道</li>
 *   <li>どちらでもなければ 1.0（不透明）</li>
 * </ol>
 *
 * <p>{@link #LIGHT_PREFIX} と違い、<b>知らない {@code glass*} を足しても穴は開かない</b>。
 * 車体のパスから外れたぶんは必ず半透明のパスが拾うので、描かれなくなることがない。</p>
 */
public final class ObjModel {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 頂点 1 つあたりの float 数。位置 3・法線 3・UV 2・色 3・不透明度 1。 */
    private static final int STRIDE = 12;

    /** 頂点の中の不透明度の位置。 */
    private static final int ALPHA_OFFSET = 11;

    /** {@link #isUvMapped} が「展開されている」と見なす UV の広がり。512px で約 1 画素。 */
    private static final float UV_MAPPED_EPSILON = 0.002F;

    /**
     * ガラスの名前の頭。{@code glass} / {@code glass_side} / {@code glass.001} が当たる
     * （{@code glasshouse} は当たらない）。
     *
     * <p><b>これは既定値を与えるだけで、透けるかどうかを決めているのは頂点の α。</b>
     * MTL が {@code d} を書いていればそちらが勝つ。</p>
     */
    public static final String GLASS_PREFIX = "glass";

    /** 同じ平面と見なす法線の一致（cos 5 度）。 */
    private static final double COPLANAR_COS = 0.996;
    /** 同じ平面と見なす面までの距離 [m]。 */
    private static final double COPLANAR_GAP = 0.002;

    /** マテリアルが透明度を書いていないガラスの不透明度。 */
    private static final float GLASS_DEFAULT_ALPHA = 0.35F;

    /**
     * これ以上を「不透明」と見なす。
     *
     * <p>Blender は不透明なマテリアルにも {@code d 1.000000} と必ず書くので素通しの比較で
     * 足りるが、他のツールが {@code 0.999999} と書いても拾えるようにしてある。</p>
     */
    private static final float OPAQUE_THRESHOLD = 0.999F;

    /** どちらの描画パスか。{@code CarObjRenderer#draw} が 2 回に分けて呼ぶ。 */
    public enum Pass {
        /** 不透明な面。{@code entityCutoutNoCull} で先に描く */
        OPAQUE,
        /** 透ける面。{@code entityTranslucent} で車体の後に描く */
        TRANSLUCENT
    }

    /**
     * この名前で始まるオブジェクトは描かない。
     *
     * <p>{@code tools/blender_gauge.py} が置く基準の枠。<b>書き出しから外し忘れて
     * 混ざっても、車と一緒に地面や円柱が描かれることがないように。</b>Blender 側では
     * 「Selected Only」で車体だけを出すのが正しいが、毎回チェックするのは忘れやすい。</p>
     */
    private static final String GAUGE_PREFIX = "gauge_";

    /** 読み込んだモデルの置き場。リソースの再読み込み（F3+T）で捨てる。 */
    private static final Map<ResourceLocation, ObjModel> CACHE = new HashMap<>();

    /** 面を持たない、描いても何も起きないモデル。読み込みに失敗したときに返す。 */
    private static final ObjModel EMPTY = new ObjModel(new float[0], List.of());

    /**
     * 光る部分の名前の頭。この名前で始まるオブジェクトは車体とは別に描く。
     *
     * <p>Blender 側は<b>役割ごとに 1 オブジェクト</b>あればよく、左右に分ける必要はない
     * （ミラーモディファイアがそのまま使える）。光の筋を出す位置は、中心線の左右で
     * 頂点を振り分けてそれぞれの重心から求める。</p>
     */
    public static final String LIGHT_PREFIX = "light_";

    /**
     * ホイールのメッシュに付属しているタイヤのオブジェクト名の頭（大文字小文字を問わない）。
     * タイヤ部品を履いたらこれを隠して、選んだタイヤを描く。
     */
    public static final String TIRE_PREFIX = "tire";

    /**
     * 鏡面の名前の頭。{@code mirror_rear}（ルームミラー）/ {@code mirror_left} /
     * {@code mirror_right}。
     *
     * <p><b>左右のドアミラーは別々のオブジェクトにすること。</b>{@link #LIGHT_PREFIX} と違い、
     * ミラーは 1 枚ごとに<b>自分の視点から世界を描き直す</b>ので、ミラーモディファイアで
     * 1 オブジェクトにまとめると左右で同じ景色が映る。</p>
     *
     * <p>位置・向き・大きさは<b>メッシュから読む</b>（{@link MirrorFace}）ので、JSON に
     * 座標を書く必要はない。</p>
     */
    public static final String MIRROR_PREFIX = "mirror_";

    /**
     * 面 1 つあたりの float 数。<b>どの面も 4 頂点で持つ</b>——四角形はそのまま、
     * 三角形は 3 番目の頂点を 2 回（縮退四角形）。描くときに形で分岐しなくて済む。
     */
    private static final int FACE_FLOATS = STRIDE * 4;

    private static final int[] NO_FACES = new int[0];

    /** 面の頂点が 4 つずつ（{@link #FACE_FLOATS}）並んだもの。 */
    private final float[] vertices;
    private final int faceCount;
    /** オブジェクトごとの範囲（面の番号）。 */
    private final List<Group> groups;
    /**
     * どのパスでどの面を描くか。<b>読み込みのときに振り分けておく</b>。
     *
     * <p>以前は描くたびに全面を回り、面ごとにオブジェクト一覧を線形に探して名前を
     * 照合していた（灯火 1 つ描くのにも車体を丸ごと 1 周）。答えは走行中に変わらないので、
     * 毎フレーム計算し直す理由がない。</p>
     */
    private final int[] opaqueFaces;
    private final int[] translucentFaces;
    /** タイヤ（{@link #TIRE_PREFIX}）を除いた面。タイヤを別に描くときのホイール。 */
    private final int[] opaqueRimFaces;
    private final int[] translucentRimFaces;
    /** 付属のタイヤ（{@link #TIRE_PREFIX}）の不透明な面だけ。シェーダーパックの影にタイヤだけ落とすときに使う。 */
    private final int[] opaqueTireFaces;
    /** タイヤの面が 1 つでもあるか。 */
    private final boolean hasTire;
    /** タイヤの面の半径の範囲 {内径, 外径}。タイヤが無ければ null。 */
    private final double[] tireRadialRange;
    /** タイヤ以外の面のいちばん外の半径（リムの外縁）。 */
    private final double rimOuterRadius;
    /** 全部の面の半径の範囲 {内径, 外径}。タイヤ部品のメッシュはこちらを使う。 */
    private final double[] radialRange;
    /** オブジェクト名 → その面。灯火のレンズのように名指しで描くときに使う。 */
    private final Map<String, int[]> facesByName;
    /** 鏡面。読み込みのときに 1 度だけ measure しておく。 */
    private final List<MirrorFace> mirrors;
    /**
     * GPU に置いた不透明な面（{@link CarMeshes}）。添字は {@code (タイヤ抜き ? 2 : 0) | (左右反転 ? 1 : 0)}。
     * 初めて描くときに作り、モデルを読み直すとき（{@link #clearCache}）に捨てる。
     */
    private final VertexBuffer[] gpuMeshes = new VertexBuffer[4];
    /** 作ってみたら面が無かった組み合わせ。毎フレーム作り直そうとしないため。 */
    private final boolean[] gpuEmpty = new boolean[4];

    /**
     * 遠くの車に使う、頂点を間引いた版（{@link #lod}）。添字が段階で、0（元のまま）は使わない。
     * 初めて要るときに作る。
     */
    // 長さは定数で書くこと。LOD_CELLS（static）を見ると、クラスの読み込み中に作られる EMPTY から
    // まだ用意されていない表を読むことになって、クラスの初期化ごと落ちる（実際に落ちた）
    private final ObjModel[] lods = new ObjModel[LOD_LEVELS];
    /** {@link #groupSideCenters} の答え。メッシュから決まるので 1 度だけ測る。 */
    private final Map<String, List<Vec3>> sideCenters = new HashMap<>();
    /** {@link #isUvMapped} の答え。同上。 */
    private final Map<String, Boolean> uvMapped = new HashMap<>();

    /** OBJ の {@code o} / {@code g} ひと区切り。 */
    private record Group(String name, int from, int to) {
    }

    /**
     * 間引くときの箱の大きさ [m]。段階 1 は中距離、2 は遠距離（距離は {@code CarObjRenderer} が決める）。
     *
     * <p>S15 の車体なら 5cm で 7 割・12cm で 4 割、ホイールなら 5cm で 5 割・12cm で 2 割の面が残る。
     * 24 ブロック先の 5cm、64 ブロック先の 12cm は、どちらも 1080p の画面で 2 画素ほど。</p>
     */
    private static final double[] LOD_CELLS = {0.0, 0.05, 0.12};
    /** {@link #LOD_CELLS} の長さ。コンパイル時の定数なので、クラスの初期化の順番に左右されない。 */
    private static final int LOD_LEVELS = 3;

    private ObjModel(float[] vertices, List<Group> groups) {
        this.vertices = vertices;
        this.faceCount = vertices.length / FACE_FLOATS;
        this.groups = groups;

        Map<String, List<Integer>> byName = new HashMap<>();
        for (Group group : groups) {
            List<Integer> list = byName.computeIfAbsent(group.name(), name -> new ArrayList<>());
            for (int face = group.from(); face < group.to(); face++) {
                list.add(face);
            }
        }
        this.facesByName = new HashMap<>();
        byName.forEach((name, list) -> facesByName.put(name, toArray(list)));

        // 鏡を先に測る。反射面はどちらのパスからも外すので、振り分けより前に要る
        boolean[] excluded = new boolean[faceCount];
        List<MirrorFace> found = new ArrayList<>();
        for (Group group : groups) {
            if (!group.name().startsWith(MIRROR_PREFIX)) {
                continue;
            }
            MirrorFace face = measureMirror(group.name());
            if (face == null) {
                // 反射面が見つからなくても描画は続く（ぜんぶ車体として描かれる）。
                // 黙って映らないと原因を探すことになるので、理由をログに残す
                LOGGER.warn("{} から鏡にできる平面が見つからない（面が無いか、大きさが 0）",
                        group.name());
                continue;
            }
            found.add(face);
            // 鏡の<b>反射面だけ</b>を外す。筐体（ガワ）は車体として描かれる
            for (int index : face.faces()) {
                excluded[index] = true;
            }
        }
        this.mirrors = List.copyOf(found);

        // 灯火は車体とは別に描く。呼ぶ側（CarLights）が名指しで必ず描くので穴は開かない
        for (Group group : groups) {
            if (group.name().startsWith(LIGHT_PREFIX)) {
                for (int face = group.from(); face < group.to(); face++) {
                    excluded[face] = true;
                }
            }
        }

        // 残りは<b>頂点の不透明度</b>で振り分ける。透ける面は車体のパスから外れ、半透明のパスが拾う
        List<Integer> opaque = new ArrayList<>();
        List<Integer> translucent = new ArrayList<>();
        for (int face = 0; face < faceCount; face++) {
            if (excluded[face]) {
                continue;
            }
            boolean transparent = vertices[face * FACE_FLOATS + ALPHA_OFFSET] < OPAQUE_THRESHOLD;
            (transparent ? translucent : opaque).add(face);
        }
        this.opaqueFaces = toArray(opaque);
        this.translucentFaces = toArray(translucent);

        // タイヤ（名前が tire で始まるオブジェクト）を見分ける。ホイールのメッシュにタイヤが
        // 付属しているとき、タイヤ部品を履いたらこれを隠して選んだタイヤを描く
        boolean[] tire = new boolean[faceCount];
        boolean anyTire = false;
        for (Group group : groups) {
            if (isTireName(group.name())) {
                for (int face = group.from(); face < group.to(); face++) {
                    tire[face] = true;
                    anyTire = true;
                }
            }
        }
        this.hasTire = anyTire;
        this.opaqueRimFaces = without(opaqueFaces, tire);
        this.translucentRimFaces = without(translucentFaces, tire);
        this.opaqueTireFaces = java.util.Arrays.stream(opaqueFaces).filter(face -> tire[face]).toArray();
        double[] tireRange = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double[] allRange = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double rimOuter = 0.0;
        for (int face = 0; face < faceCount; face++) {
            for (int corner = 0; corner < 4; corner++) {
                int offset = face * FACE_FLOATS + corner * STRIDE;
                double r = Math.hypot(vertices[offset + 1], vertices[offset + 2]);
                allRange[0] = Math.min(allRange[0], r);
                allRange[1] = Math.max(allRange[1], r);
                if (tire[face]) {
                    tireRange[0] = Math.min(tireRange[0], r);
                    tireRange[1] = Math.max(tireRange[1], r);
                } else {
                    rimOuter = Math.max(rimOuter, r);
                }
            }
        }
        this.tireRadialRange = anyTire ? tireRange : null;
        this.rimOuterRadius = rimOuter;
        this.radialRange = faceCount > 0 ? allRange : new double[]{0.0, 0.0};
    }

    /** タイヤのオブジェクト名か。Blender の既定の大文字（{@code Tire}）も拾う。 */
    private static boolean isTireName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).startsWith(TIRE_PREFIX);
    }

    private static int[] without(int[] faces, boolean[] excluded) {
        return java.util.Arrays.stream(faces).filter(face -> !excluded[face]).toArray();
    }

    /** タイヤのオブジェクトが付属しているか。 */
    public boolean hasTire() {
        return hasTire;
    }

    /** 付属のタイヤの半径の範囲 {内径, 外径}。付属していなければ null。 */
    @org.jetbrains.annotations.Nullable
    public double[] tireRadialRange() {
        return tireRadialRange == null ? null : tireRadialRange.clone();
    }

    /** タイヤ以外（リム）のいちばん外の半径。 */
    public double rimOuterRadius() {
        return rimOuterRadius;
    }

    /** 全部の面の半径の範囲 {内径, 外径}。 */
    public double[] radialRange() {
        return radialRange.clone();
    }

    /** タイヤ以外に透ける面があるか。 */
    public boolean hasTranslucentRim() {
        return translucentRimFaces.length > 0;
    }

    /**
     * タイヤのメッシュを、半径 {@code fromInner}〜{@code fromOuter} の帯が {@code toInner}〜{@code toOuter} へ
     * 収まるよう<b>半径方向に伸び縮みさせた</b>複製。車軸方向（X）は {@code xScale} 倍。
     *
     * <p>一様な拡大では内径と外径の比が変わらないので、リムの大きさが違うホイールにタイヤを合わせられない。
     * 半径を内径から外径へ線形に移せば、<b>リムが大きければサイドウォールが薄く、小さければ厚く</b>なる
     * （実車のインチアップと同じ）。</p>
     *
     * <p>法線は変形の逆転置で直す（半径方向・円周方向・車軸方向で伸び方が違うため）。</p>
     */
    public ObjModel fitRadially(double fromInner, double fromOuter, double toInner, double toOuter, double xScale) {
        float[] out = vertices.clone();
        double band = Math.max(1e-6, fromOuter - fromInner);
        double radialScale = (toOuter - toInner) / band;
        for (int offset = 0; offset < out.length; offset += STRIDE) {
            double y = out[offset + 1];
            double z = out[offset + 2];
            double r = Math.hypot(y, z);
            double target = toInner + (r - fromInner) * radialScale;
            double tangential = r > 1e-6 ? target / r : 1.0;
            out[offset] = (float) (out[offset] * xScale);
            out[offset + 1] = (float) (y * tangential);
            out[offset + 2] = (float) (z * tangential);

            // 法線を（車軸, 半径, 円周）に分けて、それぞれの伸びの逆数を掛ける
            double nx = out[offset + 3];
            double ny = out[offset + 4];
            double nz = out[offset + 5];
            double cy = r > 1e-6 ? y / r : 0.0;
            double cz = r > 1e-6 ? z / r : 1.0;
            double nRadial = ny * cy + nz * cz;
            double nTangential = -ny * cz + nz * cy;
            double ax = nx / xScale;
            double ar = nRadial / Math.max(1e-6, radialScale);
            double at = nTangential / Math.max(1e-6, tangential);
            double ry = ar * cy - at * cz;
            double rz = ar * cz + at * cy;
            double length = Math.sqrt(ax * ax + ry * ry + rz * rz);
            if (length > 1e-9) {
                out[offset + 3] = (float) (ax / length);
                out[offset + 4] = (float) (ry / length);
                out[offset + 5] = (float) (rz / length);
            }
        }
        return new ObjModel(out, groups);
    }

    /** 合わせ直したタイヤ。リソースの再読み込みで捨てる。 */
    private static final Map<FitKey, ObjModel> FITTED = new HashMap<>();

    private record FitKey(ResourceLocation location, double fromInner, double fromOuter,
                          double toInner, double toOuter, double xScale) {
    }

    /**
     * タイヤのメッシュを読み、{@link #fitRadially} で合わせ直したもの。同じ寸法なら使い回す。
     *
     * <p>車軸方向は外径の比で一様に拡大する。タイヤを作った大きさに依らず、
     * 外径に対する太さの比が保たれる。</p>
     *
     * @param fromInner メッシュのタイヤの内径。null なら測った値
     * @param fromOuter メッシュのタイヤの外径。null なら測った値
     */
    public static ObjModel fittedTire(ResourceLocation location,
                                      @org.jetbrains.annotations.Nullable Double fromInner,
                                      @org.jetbrains.annotations.Nullable Double fromOuter,
                                      double toInner, double toOuter) {
        ObjModel source = get(location);
        double inner = fromInner != null ? fromInner : source.radialRange[0];
        double outer = fromOuter != null ? fromOuter : source.radialRange[1];
        double xScale = outer > 1e-6 ? toOuter / outer : 1.0;
        FitKey key = new FitKey(location, inner, outer, toInner, toOuter, xScale);
        return FITTED.computeIfAbsent(key, k -> source.fitRadially(inner, outer, toInner, toOuter, xScale));
    }

    private static int[] toArray(List<Integer> list) {
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 鏡面 1 枚の幾何。<b>メッシュだけから決まる</b>ので、Blender で板を 1 枚置いて
     * {@code mirror_rear} と名前を付ければそれで済む。
     *
     * @param center 面の中心（メッシュ空間）
     * @param normal 面の向き。運転者のほうを向いている想定
     * @param right  面の中で右へ伸びる軸（車体の右向きに揃えてある）
     * @param up     面の中で上へ伸びる軸
     * @param width  {@code right} 方向の幅 [m]
     * @param height {@code up} 方向の高さ [m]
     * @param faces 反射面をなす面の番号。<b>ガワは含まない</b>
     */
    public record MirrorFace(String name, Vec3 center, Vec3 normal, Vec3 right, Vec3 up,
                             double width, double height, int[] faces) {

        /** 横縦比。描き先の大きさと射影のアスペクトに使う。 */
        public double aspect() {
            return height < 1.0e-6 ? 1.0 : width / height;
        }
    }

    /** 鏡面の一覧。持っていなければ空。 */
    public List<MirrorFace> mirrors() {
        return mirrors;
    }

    /**
     * 鏡面を測る。
     *
     * <p><b>オブジェクト全体の平均法線を使ってはいけない。</b>ミラーは<b>筐体ごと 1 つの箱</b>で
     * 作られるのが自然で、そうすると向かい合う面の法線が打ち消し合う（実測で
     * {@code mirror_rear} は合計がちょうど 0、ドアミラーも長さ 0.28 まで潰れた）。</p>
     *
     * <p>そこで<b>面ごとに見て、いちばん広い平面</b>を反射面とする。残りの面は<b>車体として
     * 普通に描かれる</b>ので、筐体が消えることはない。</p>
     *
     * <p><b>板がどちらを向いていても構わない。</b>映す向きは {@link MirrorRenderer} が車体基準で
     * 決めるので、ここで要るのは「どの平面に貼るか」だけ。法線は<b>後ろ向きに揃えておく</b>
     * ——面内の軸（右と上）をその法線から作るので、裏返っていると映像が左右逆になる。</p>
     *
     * <p>面内の軸は<b>車体の上向きから作る</b>——鏡は水平に置かれるものなので、上を基準にすれば
     * 「映像の上」が鏡の上に一致する。</p>
     */
    private MirrorFace measureMirror(String name) {
        int[] group = facesByName.getOrDefault(name, NO_FACES);

        int[] best = null;
        Vec3 bestNormal = null;
        double bestArea = 0.0;
        for (int candidate : group) {
            Vec3 raw = faceNormal(candidate);
            double area = raw.length();
            if (area < 1.0e-9) {
                continue;
            }
            // 面の向きは問わない。ただし軸を作るために後ろ（+Z）向きへ揃える
            Vec3 normal = raw.scale(1.0 / area);
            if (normal.z < 0.0) {
                normal = normal.scale(-1.0);
            }
            double offset = normal.dot(faceCenter(candidate));

            List<Integer> plane = new ArrayList<>();
            double total = 0.0;
            for (int other : group) {
                Vec3 otherRaw = faceNormal(other);
                double otherArea = otherRaw.length();
                if (otherArea < 1.0e-9
                        || Math.abs(otherRaw.scale(1.0 / otherArea).dot(normal)) < COPLANAR_COS
                        || Math.abs(normal.dot(faceCenter(other)) - offset) > COPLANAR_GAP) {
                    continue;
                }
                plane.add(other);
                total += otherArea;
            }
            if (total > bestArea) {
                bestArea = total;
                bestNormal = normal;
                best = toArray(plane);
            }
        }
        if (best == null) {
            return null;
        }

        Vec3 up = Math.abs(bestNormal.y) > 0.99 ? new Vec3(0.0, 0.0, -1.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = up.cross(bestNormal);
        if (right.lengthSqr() < 1.0e-9) {
            return null;
        }
        right = right.normalize();
        up = bestNormal.cross(right).normalize();

        // 反射面の頂点だけで広がりを測る（筐体を混ぜると鏡が大きすぎることになる）
        double minRight = Double.MAX_VALUE;
        double maxRight = -Double.MAX_VALUE;
        double minUp = Double.MAX_VALUE;
        double maxUp = -Double.MAX_VALUE;
        for (int face : best) {
            int base = face * FACE_FLOATS;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                Vec3 v = new Vec3(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
                minRight = Math.min(minRight, v.dot(right));
                maxRight = Math.max(maxRight, v.dot(right));
                minUp = Math.min(minUp, v.dot(up));
                maxUp = Math.max(maxUp, v.dot(up));
            }
        }
        double width = maxRight - minRight;
        double height = maxUp - minUp;
        if (width < 1.0e-4 || height < 1.0e-4) {
            return null;
        }

        // 中心は広がりの中央。面に載せるため、法線方向だけは実際の平面の位置に合わせる
        double onPlane = bestNormal.dot(faceCenter(best[0]));
        Vec3 center = right.scale((minRight + maxRight) * 0.5)
                .add(up.scale((minUp + maxUp) * 0.5))
                .add(bestNormal.scale(onPlane));
        return new MirrorFace(name, center, bestNormal, right, up, width, height, best);
    }

    /**
     * その面の法線。<b>長さが面積の 2 倍</b>になるので、広さの比較にそのまま使える。
     *
     * <p>2 本の対角線の外積を使う。四角形ならこれがそのまま面積の 2 倍で、
     * 縮退四角形（a, b, c, c）でも {@code (c−a)×(c−b) = (b−a)×(c−a)} と三角形の式に一致する。</p>
     */
    private Vec3 faceNormal(int face) {
        Vec3 a = corner(face, 0);
        Vec3 b = corner(face, 1);
        Vec3 c = corner(face, 2);
        Vec3 d = corner(face, 3);
        return c.subtract(a).cross(d.subtract(b));
    }

    /**
     * その面の中心（4 頂点の平均）。
     *
     * <p>三角形は 3 番目の頂点が 2 回数えられて重心からずれるが、使い道は「面が載っている
     * 平面の位置」だけなので、平面上の点でさえあればよい。</p>
     */
    private Vec3 faceCenter(int face) {
        int base = face * FACE_FLOATS;
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (int corner = 0; corner < 4; corner++) {
            int offset = base + corner * STRIDE;
            x += vertices[offset];
            y += vertices[offset + 1];
            z += vertices[offset + 2];
        }
        return new Vec3(x / 4.0, y / 4.0, z / 4.0);
    }

    private Vec3 corner(int face, int corner) {
        int offset = face * FACE_FLOATS + corner * STRIDE;
        return new Vec3(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
    }

    /**
     * 鏡面に、別に描いておいた景色を貼る。
     *
     * <p>UV は<b>メッシュの UV を使わない</b>。面内の 2 軸へ落とした位置をそのまま 0..1 に
     * するので、Blender 側で展開する必要がない（板を 1 枚置いて名前を付けるだけでよい）。</p>
     *
     * <p><b>横は反転する。</b>鏡の位置にカメラを置いて後ろを向いて撮った絵は「振り返って
     * 見た景色」であって、鏡に映る像はそれを左右反転したもの。<b>縦も反転する</b>——
     * フレームバッファは下が原点なので、そのままでは上下が逆になる。</p>
     *
     * <p>頂点は {@code POSITION_COLOR_TEX} の順で積む（{@link KurumaRenderTypes#mirror}）。
     * 明るさも法線も渡さない——映っている絵は<b>すでに light を通って描かれている</b>ので、
     * ここで陰影を掛けると二重になる。</p>
     */
    public void renderMirror(PoseStack.Pose pose, VertexConsumer consumer, MirrorFace face,
                             float red, float green, float blue, float alpha) {
        Matrix4f matrix = pose.pose();
        double halfWidth = face.width() * 0.5;
        double halfHeight = face.height() * 0.5;

        for (int index : face.faces()) {
            int base = index * FACE_FLOATS;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                double dx = vertices[offset] - face.center().x;
                double dy = vertices[offset + 1] - face.center().y;
                double dz = vertices[offset + 2] - face.center().z;
                Vec3 d = new Vec3(dx, dy, dz);
                // 面内の位置を 0..1 へ。中心が 0.5
                float s = (float) (0.5 + d.dot(face.right()) / (halfWidth * 2.0));
                float t = (float) (0.5 + d.dot(face.up()) / (halfHeight * 2.0));
                consumer.addVertex(matrix, vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .setColor(red, green, blue, alpha)
                        .setUv(1.0F - s, t);
            }
        }
    }

    /** 透ける面を持っているか。false なら半透明のパスは呼ばなくてよい。 */
    public boolean hasTranslucent() {
        return translucentFaces.length > 0;
    }

    /** その名前のオブジェクトを持っているか。 */
    public boolean hasGroup(String name) {
        return facesByName.containsKey(name);
    }

    /**
     * そのオブジェクトの頂点を中心線の左右に振り分けた重心。ライトの位置に使う。
     *
     * <p>左右に分かれていれば 2 つ、中央に 1 つだけなら 1 つ返る。ミラーで作られた
     * 1 オブジェクトからそのまま左右のライトが取れる。</p>
     *
     * <p>毎フレーム呼ばれるが、答えはメッシュだけから決まるので最初の 1 回だけ測る。</p>
     */
    public List<Vec3> groupSideCenters(String name) {
        return sideCenters.computeIfAbsent(name, this::measureSideCenters);
    }

    private List<Vec3> measureSideCenters(String name) {
        double[] sum = new double[6];
        int[] count = new int[2];
        forEachVertex(name, offset -> {
            float x = vertices[offset];
            int side = x < 0.0 ? 0 : 1;
            sum[side * 3] += x;
            sum[side * 3 + 1] += vertices[offset + 1];
            sum[side * 3 + 2] += vertices[offset + 2];
            count[side]++;
        });
        List<Vec3> centers = new ArrayList<>(2);
        for (int side = 0; side < 2; side++) {
            if (count[side] > 0) {
                centers.add(new Vec3(sum[side * 3] / count[side],
                        sum[side * 3 + 1] / count[side], sum[side * 3 + 2] / count[side]));
            }
        }
        return List.copyOf(centers);
    }

    /** そのオブジェクトの平均法線。ライトの照射方向に使う。面が無ければ前方（-Z）。 */
    public Vec3 groupNormal(String name) {
        double[] sum = new double[3];
        forEachVertex(name, offset -> {
            sum[0] += vertices[offset + 3];
            sum[1] += vertices[offset + 4];
            sum[2] += vertices[offset + 5];
        });
        Vec3 normal = new Vec3(sum[0], sum[1], sum[2]);
        return normal.lengthSqr() < 1.0e-6 ? new Vec3(0.0, 0.0, -1.0) : normal.normalize();
    }

    /**
     * そのオブジェクトが UV 展開されているか。
     *
     * <p><b>灯火の色をどちらが決めるかの判定に使う。</b>レンズの UV が 1 点に潰れていれば、
     * テクスチャの 1 画素を全面に写しているだけ——つまりテクスチャは色を持っていないので、
     * 色は{@link CarLights}が掛ける。展開してあるならテクスチャに描かれた色が正で、
     * コード側は明暗（点灯・消灯）だけを扱う。</p>
     *
     * <p>広がりが完全に 0 かで判定すると、丸め誤差ぶんだけばらけた UV を「展開されている」と
     * 読み違える。{@value #UV_MAPPED_EPSILON} は 512px のテクスチャで約 1 画素にあたる。</p>
     */
    public boolean isUvMapped(String name) {
        // 灯火を描くたびに呼ばれる。メッシュだけから決まるので最初の 1 回だけ測る
        return uvMapped.computeIfAbsent(name, this::measureUvMapped);
    }

    private boolean measureUvMapped(String name) {
        float[] range = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        forEachVertex(name, offset -> {
            range[0] = Math.min(range[0], vertices[offset + 6]);
            range[1] = Math.max(range[1], vertices[offset + 6]);
            range[2] = Math.min(range[2], vertices[offset + 7]);
            range[3] = Math.max(range[3], vertices[offset + 7]);
        });
        return range[1] - range[0] > UV_MAPPED_EPSILON || range[3] - range[2] > UV_MAPPED_EPSILON;
    }

    private interface VertexSink {
        /** @param offset その頂点の先頭の添字（{@link #vertices} の中） */
        void accept(int offset);
    }

    /** その名前のオブジェクトの頂点を 1 つずつ渡す。三角形の 3 番目の頂点は 2 回来る。 */
    private void forEachVertex(String name, VertexSink sink) {
        for (int face : facesByName.getOrDefault(name, NO_FACES)) {
            for (int corner = 0; corner < 4; corner++) {
                sink.accept(face * FACE_FLOATS + corner * STRIDE);
            }
        }
    }

    /**
     * モデルを取り出す。まだ読んでいなければ読む。
     *
     * <p>読み込みに失敗しても例外は投げず、空のモデルを返してログに残す。
     * モデルが 1 つ壊れているだけでゲームが落ちるより、車が消えて見える方がましなため。</p>
     */
    public static ObjModel get(ResourceLocation location) {
        return CACHE.computeIfAbsent(location, ObjModel::load);
    }

    /** リソースの再読み込みで呼ぶ。次に描くときに読み直される（F3+T で Blender の書き出しを反映できる）。 */
    public static void clearCache() {
        // GPU に置いた頂点は GC では消えないので、自分で返す
        FITTED.values().forEach(ObjModel::releaseGpu);
        CACHE.values().forEach(ObjModel::releaseGpu);
        FITTED.clear();
        CACHE.clear();
    }

    /**
     * GPU に置いた不透明な面。<b>初めて呼ばれたときに作る</b>（描画スレッドから呼ぶこと）。
     *
     * <p>頂点の中身は {@link #render} が毎フレーム積んでいたものと同じで、変換だけを掛けていない
     * （メッシュの座標のまま）。明るさとオーバーレイは使わないので 0 を入れておく
     * （{@code kurumamod:car_mesh} は外から渡された値を読む）。</p>
     *
     * @param withoutTire 付属のタイヤを除くか（タイヤを別に描くときのホイール）
     * @param mirrorX     左右を反転するか（右側のタイヤ）。反転は頂点の段で済ませる（{@link #render} の理由と同じ）
     * @return 面が 1 つも無ければ null
     */
    VertexBuffer gpuMesh(boolean withoutTire, boolean mirrorX) {
        int index = (withoutTire ? 2 : 0) | (mirrorX ? 1 : 0);
        if (gpuMeshes[index] != null || gpuEmpty[index]) {
            return gpuMeshes[index];
        }
        int[] faces = withoutTire ? opaqueRimFaces : opaqueFaces;
        if (faces.length == 0) {
            gpuEmpty[index] = true;
            return null;
        }
        float flip = mirrorX ? -1.0F : 1.0F;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (int face : faces) {
            int base = face * FACE_FLOATS;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                builder.addVertex(flip * vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .setColor(vertices[offset + 8], vertices[offset + 9], vertices[offset + 10],
                                vertices[offset + ALPHA_OFFSET])
                        .setUv(vertices[offset + 6], vertices[offset + 7])
                        .setOverlay(0)
                        .setLight(0)
                        .setNormal(flip * vertices[offset + 3], vertices[offset + 4], vertices[offset + 5]);
            }
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.buildOrThrow());
        VertexBuffer.unbind();
        gpuMeshes[index] = buffer;
        return buffer;
    }

    /**
     * 頂点を間引いた版。<b>遠くの車を軽く描くため</b>（シェーダーパック使用中は特に効く）。
     *
     * <p>空間を {@link #LOD_CELLS} の大きさの箱に区切り、同じ箱に入った頂点をその平均の位置へ寄せる
     * （頂点クラスタリング）。それで 3 点より少なくつぶれた面（点や線になった面）は捨てる。
     * 細部（ホイールのスポーク、車体の小さな凹凸）から消えていく。</p>
     *
     * <p>結果は普通の {@code ObjModel} なので、面の振り分け（不透明・ガラス・灯火・タイヤ）も
     * 読み込みと同じ道で決まる。UV と法線は元の頂点のまま。</p>
     *
     * @param level 0 なら自分自身。1 以上は間引いた版（大きいほど粗い）
     */
    public ObjModel lod(int level) {
        if (level <= 0 || faceCount == 0) {
            return this;
        }
        int index = Math.min(level, LOD_CELLS.length - 1);
        ObjModel lod = lods[index];
        if (lod == null) {
            lod = simplify(LOD_CELLS[index]);
            lods[index] = lod;
        }
        return lod;
    }

    private ObjModel simplify(double cell) {
        // 箱ごとの平均の位置
        Map<Long, double[]> sums = new HashMap<>();
        for (int face = 0; face < faceCount; face++) {
            for (int corner = 0; corner < 4; corner++) {
                int offset = face * FACE_FLOATS + corner * STRIDE;
                double[] sum = sums.computeIfAbsent(cellKey(offset, cell), key -> new double[4]);
                sum[0] += vertices[offset];
                sum[1] += vertices[offset + 1];
                sum[2] += vertices[offset + 2];
                sum[3]++;
            }
        }

        // 残る面を、順番を保ったまま詰める。kept[i] = 面 i より前に残った面の数（グループの範囲の数え直しに使う）
        int[] kept = new int[faceCount + 1];
        float[] out = new float[vertices.length];
        int outFaces = 0;
        long[] cells = new long[4];
        for (int face = 0; face < faceCount; face++) {
            kept[face] = outFaces;
            int distinct = 0;
            for (int corner = 0; corner < 4; corner++) {
                long key = cellKey(face * FACE_FLOATS + corner * STRIDE, cell);
                boolean seen = false;
                for (int i = 0; i < distinct; i++) {
                    seen |= cells[i] == key;
                }
                if (!seen) {
                    cells[distinct++] = key;
                }
            }
            if (distinct < 3) {
                continue;
            }
            int from = face * FACE_FLOATS;
            int to = outFaces * FACE_FLOATS;
            System.arraycopy(vertices, from, out, to, FACE_FLOATS);
            for (int corner = 0; corner < 4; corner++) {
                double[] sum = sums.get(cellKey(from + corner * STRIDE, cell));
                int offset = to + corner * STRIDE;
                out[offset] = (float) (sum[0] / sum[3]);
                out[offset + 1] = (float) (sum[1] / sum[3]);
                out[offset + 2] = (float) (sum[2] / sum[3]);
            }
            outFaces++;
        }
        kept[faceCount] = outFaces;

        List<Group> simplified = new ArrayList<>(groups.size());
        for (Group group : groups) {
            simplified.add(new Group(group.name(), kept[Math.min(group.from(), faceCount)],
                    kept[Math.min(group.to(), faceCount)]));
        }
        return new ObjModel(java.util.Arrays.copyOf(out, outFaces * FACE_FLOATS), simplified);
    }

    /** 頂点がどの箱に入るか。座標を箱の番号に直して 1 つの long に詰める。 */
    private long cellKey(int offset, double cell) {
        long x = (long) Math.floor(vertices[offset] / cell) & 0x1FFFFF;
        long y = (long) Math.floor(vertices[offset + 1] / cell) & 0x1FFFFF;
        long z = (long) Math.floor(vertices[offset + 2] / cell) & 0x1FFFFF;
        return x | (y << 21) | (z << 42);
    }

    /** GPU に置いた頂点を返す。 */
    private void releaseGpu() {
        for (ObjModel lod : lods) {
            if (lod != null) {
                lod.releaseGpu();
            }
        }
        for (int i = 0; i < gpuMeshes.length; i++) {
            if (gpuMeshes[i] != null) {
                gpuMeshes[i].close();
                gpuMeshes[i] = null;
            }
            gpuEmpty[i] = false;
        }
    }

    public boolean isEmpty() {
        return faceCount == 0;
    }

    /**
     * 描く。
     *
     * <p>Minecraft のエンティティ用 {@code RenderType} はどれも QUADS なので、
     * <b>四角形はそのまま、三角形は最後の頂点を 2 回積んで四角形として流す</b>（縮退四角形）。
     * 面積 0 の辺ができるだけで見た目には影響しない。並べ替えは読み込み時に済んでいる
     * （{@link #appendFace}）。</p>
     *
     * @param tint 全体に掛ける色。テクスチャをそのまま出すなら白
     */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha) {
        render(pose, consumer, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha, null, false);
    }

    /** 名前を選んで、左右を反転して描く。パスは不透明側。 */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha, String only,
                       boolean mirrorX) {
        render(pose, consumer, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha,
                only, mirrorX, Pass.OPAQUE);
    }

    /**
     * 名前を選んで描く。
     *
     * @param only この名前のオブジェクトだけ描く。null なら<b>光る部分を除いた全部</b>
     */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha, String only) {
        render(pose, consumer, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha, only, false);
    }

    /**
     * 名前を選んで、左右を反転して描く。
     *
     * <p><b>鏡像は {@code PoseStack} でやってはいけない。</b>{@code PoseStack#scale(-1, 1, 1)} は
     * 一様でないスケールの分岐に入り、法線行列に {@code Mth.fastInvCubeRoot(-1)} を掛ける。
     * この関数は逆立方根をニュートン法で解いているので<b>負の入力では収束せず 2.19e25 を返す</b>。
     * 結果、法線が 10^25 倍されてバイトへ詰める段で潰れ、<b>鏡像側だけ陰影が壊れる</b>
     * （右前・右後のタイヤだけおかしく見える、という形で出た）。一様な {@code scale(-1, -1, -1)}
     * でも同じ分岐へ落ちるので逃げられない。</p>
     *
     * <p>そこで頂点の段で X を反転する。反射行列 {@code diag(-1, 1, 1)} は逆転置が自分自身
     * なので、<b>法線も同じように X を反転させるのが正しい</b>。面の巻き方向は裏返るが、
     * {@code entityCutoutNoCull} でカリングを切ってあるので問題にならない。</p>
     *
     * @param mirrorX 左右を反転して描くか
     * @param pass 不透明な面と透ける面のどちらを描くか。{@code only} を指定したときは見ない
     */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                       float tintRed, float tintGreen, float tintBlue, float alpha, String only,
                       boolean mirrorX, Pass pass) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        float flip = mirrorX ? -1.0F : 1.0F;

        // 名指し（灯火のレンズ）はパスを問わない。RenderType は呼ぶ側が選んでいる
        int[] faces = only != null ? facesByName.getOrDefault(only, NO_FACES)
                : pass == Pass.TRANSLUCENT ? translucentFaces : opaqueFaces;
        renderFaces(faces, matrix, normal, flip, consumer, packedLight, packedOverlay,
                tintRed, tintGreen, tintBlue, alpha);
    }

    /** ホイールを、付属のタイヤ（{@link #TIRE_PREFIX}）を除いて描く。タイヤを別に描くときに使う。 */
    public void renderWithoutTire(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                                  boolean mirrorX, Pass pass) {
        renderFaces(pass == Pass.TRANSLUCENT ? translucentRimFaces : opaqueRimFaces,
                pose.pose(), pose.normal(), mirrorX ? -1.0F : 1.0F, consumer, packedLight, packedOverlay,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 付属のタイヤ（{@link #TIRE_PREFIX}）の不透明な面だけを描く。シェーダーパックの影を描くときに使う。 */
    public void renderTireOnly(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
                               boolean mirrorX) {
        renderFaces(opaqueTireFaces, pose.pose(), pose.normal(), mirrorX ? -1.0F : 1.0F, consumer,
                packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderFaces(int[] faces, Matrix4f matrix, Matrix3f normal, float flip, VertexConsumer consumer,
                             int packedLight, int packedOverlay,
                             float tintRed, float tintGreen, float tintBlue, float alpha) {
        // Embeddium / Sodium があれば頂点を塊で渡す。シェーダーパック使用中にいちばん効く（BulkVertexWriter）
        if (BulkVertexWriter.write(consumer, vertices, faces, FACE_FLOATS, STRIDE, ALPHA_OFFSET, matrix, normal,
                flip, packedLight, packedOverlay, tintRed, tintGreen, tintBlue, alpha)) {
            return;
        }

        // 1.21 の VertexConsumer には Matrix3f で法線を回す口が無いので、ここで回して渡す
        Vector3f n = new Vector3f();
        for (int face : faces) {
            int base = face * FACE_FLOATS;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                normal.transform(n.set(flip * vertices[offset + 3], vertices[offset + 4], vertices[offset + 5]));
                consumer.addVertex(matrix, flip * vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .setColor(vertices[offset + 8] * tintRed,
                                vertices[offset + 9] * tintGreen,
                                vertices[offset + 10] * tintBlue,
                                vertices[offset + ALPHA_OFFSET] * alpha)
                        .setUv(vertices[offset + 6], vertices[offset + 7])
                        .setOverlay(packedOverlay)
                        .setLight(packedLight)
                        .setNormal(n.x, n.y, n.z);
            }
        }
    }

    /**
     * 鏡面を、映さずにただの面として描く（暗いガラス）。
     *
     * <p>反射面だけを描くこと。<b>名前で丸ごと描くと筐体まで暗く塗ってしまう</b>——
     * ガワは車体として既に描かれているので二重になる。</p>
     */
    public void renderMirrorPlain(PoseStack.Pose pose, VertexConsumer consumer, MirrorFace face,
                                  int packedLight, float red, float green, float blue) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        Vector3f n = new Vector3f();
        for (int index : face.faces()) {
            int base = index * FACE_FLOATS;
            for (int corner = 0; corner < 4; corner++) {
                int offset = base + corner * STRIDE;
                normal.transform(n.set(vertices[offset + 3], vertices[offset + 4], vertices[offset + 5]));
                consumer.addVertex(matrix, vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .setColor(red, green, blue, 1.0F)
                        .setUv(vertices[offset + 6], vertices[offset + 7])
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                        .setLight(packedLight)
                        .setNormal(n.x, n.y, n.z);
            }
        }
    }

    /**
     * ガラスの名前か。
     *
     * <p>{@code glass} そのものと、区切り（{@code _} / {@code .}）が続くものだけを拾う。
     * Blender は同じ名前が重なると {@code glass.001} を作るので、そこも当てておく。</p>
     */
    private static boolean isGlassName(String name) {
        if (name == null || !name.startsWith(GLASS_PREFIX)) {
            return false;
        }
        String rest = name.substring(GLASS_PREFIX.length());
        return rest.isEmpty() || rest.charAt(0) == '_' || rest.charAt(0) == '.';
    }

    /**
     * その面の不透明度。
     *
     * <p>MTL が {@code d} を書いていればそれが正。書いていないガラスにだけ既定値を当てる
     * （マテリアルを割り当てていないメッシュでも名前だけで透けるように）。</p>
     */
    private static float faceAlpha(float materialAlpha, String groupName) {
        if (materialAlpha < OPAQUE_THRESHOLD) {
            return materialAlpha;
        }
        return isGlassName(groupName) ? GLASS_DEFAULT_ALPHA : 1.0F;
    }

    // ------------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------------

    private static ObjModel load(ResourceLocation location) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Optional<Resource> resource = resources.getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("OBJ モデルが見つかりません: {}", location);
            return EMPTY;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            ObjModel model = parse(reader, location, resources);
            LOGGER.info("OBJ モデルを読み込みました: {}（{} 面・{} 頂点）", location, model.faceCount,
                    model.faceCount * 4);
            return model;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("OBJ モデルの読み込みに失敗しました: {}", location, e);
            return EMPTY;
        }
    }

    private static ObjModel parse(BufferedReader reader, ResourceLocation location, ResourceManager resources)
            throws IOException {
        // OBJ のインデックスは 1 始まりでファイル全体に通し番号。オブジェクトが分かれていても連番になる
        List<float[]> positions = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        Map<String, float[]> materials = new HashMap<>();

        List<Float> out = new ArrayList<>();
        // 拡散色 3 ＋ 不透明度 1。マテリアルが無ければ白の不透明
        float[] color = {1.0F, 1.0F, 1.0F, 1.0F};

        // tools/blender_gauge.py が置く基準の枠。書き出しから外し忘れても描かないよう、
        // 名前で捨てる。頂点は読み飛ばさないこと——OBJ のインデックスはファイル全体の
        // 通し番号なので、飛ばすと以降の面がずれる。捨てるのは面だけ
        boolean skipping = false;

        List<Group> groups = new ArrayList<>();
        String groupName = null;
        int groupStart = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] token = line.split("\\s+");
            switch (token[0]) {
                case "v" -> // Blender 標準の向きから、エンティティ空間へ。X と Z を反転する
                        positions.add(new float[]{
                                -parseFloat(token[1]), parseFloat(token[2]), -parseFloat(token[3])});
                case "vn" -> normals.add(new float[]{
                        -parseFloat(token[1]), parseFloat(token[2]), -parseFloat(token[3])});
                // OBJ の V は下が 0、Minecraft は上が 0
                case "vt" -> uvs.add(new float[]{parseFloat(token[1]), 1.0F - parseFloat(token[2])});
                case "mtllib" -> materials.putAll(loadMaterials(location, line.substring(7).trim(), resources));
                case "usemtl" -> color = materials.getOrDefault(line.substring(7).trim(),
                        new float[]{1.0F, 1.0F, 1.0F, 1.0F});
                case "o", "g" -> {
                    // 直前のオブジェクトをここで閉じる。面は名前の後ろに並ぶので、
                    // 区切りが来た時点までが 1 つ
                    if (groupName != null && out.size() > groupStart) {
                        groups.add(new Group(groupName, groupStart / FACE_FLOATS, out.size() / FACE_FLOATS));
                    }
                    groupName = token.length > 1 ? token[1] : "";
                    groupStart = out.size();
                    // マテリアルは前のオブジェクトから引き継がない。Blender はマテリアルを持つ
                    // オブジェクトには必ず usemtl を書くので、書かれていないのは「無し」の意味。
                    // 引き継ぐと、マテリアルの無いガラスが直前のバネの赤に染まる
                    color = new float[]{1.0F, 1.0F, 1.0F, 1.0F};
                    skipping = groupName.startsWith(GAUGE_PREFIX);
                }
                case "f" -> {
                    if (!skipping) {
                        // 不透明度は「マテリアルの d、無ければガラスの既定値」。
                        // 名前を見るのはここだけで、描画中は頂点の α しか見ない
                        appendFace(out, token, positions, normals, uvs, color,
                                faceAlpha(color[3], groupName));
                    }
                }
                default -> {
                    // s / その他は読み飛ばす
                }
            }
        }

        if (groupName != null && out.size() > groupStart) {
            groups.add(new Group(groupName, groupStart / FACE_FLOATS, out.size() / FACE_FLOATS));
        }

        float[] vertices = new float[out.size()];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = out.get(i);
        }
        return new ObjModel(vertices, groups);
    }

    /**
     * 面を積む。<b>四角形は四角形のまま、それ以外は三角形に割って</b>縮退四角形で積む。
     *
     * <p>割り方は {@link PolygonTriangulator}（耳切り法）に任せる。<b>凹んだ n-gon も
     * 正しく割れる</b>ので、Blender 側で「三角形化」して書き出す必要はない——むしろ
     * <b>三角形化しないほうが軽い</b>（下記）。</p>
     *
     * <p>以前は最初の頂点からの扇状分割だったが、これは凸多角形でしか正しくない。実際に
     * 書き出された車体は 52 個の n-gon のうち 44 個が凹んでいて、面がはみ出していた。</p>
     *
     * <h2>四角形をそのまま積む</h2>
     *
     * <p>書き出された車体はほとんどが四角形（s15 で 2772 面中 2502）で、以前はこれを 2 つの
     * 三角形に割り、それぞれ縮退四角形で流していた——<b>四角形 1 枚に 8 頂点、半分は潰れた頂点</b>。
     * QUADS は GPU 側で 2 つの三角形に割られるので、そのまま 4 頂点で渡せば同じものが描ける
     * （頂点が約 44% 減る。影 MOD の影パスがあればそちらでも減る）。</p>
     *
     * <p><b>ただし割る対角線を揃えること。</b>QUADS は頂点 0-2 の対角線で割られる
     * （添字 0,1,2 / 2,3,0）。一方耳切り法は巻き方向しだいで 1-3 を選ぶことがあり、
     * ねじれた四角形ではどちらで割るかで形も陰影も変わる。そこで耳切り法の選んだ対角線の
     * 端から積み始め、<b>今まで描いていたのとまったく同じ 2 つの三角形</b>にする。
     * 凹んだ四角形も、耳切り法は必ず内側を通る対角線を選ぶので同じ扱いで正しく描ける。</p>
     */
    private static void appendFace(List<Float> out, String[] token, List<float[]> positions,
                                   List<float[]> normals, List<float[]> uvs, float[] color,
                                   float alpha) {
        int corners = token.length - 1;
        if (corners < 3) {
            return;
        }

        List<float[]> polygon = new ArrayList<>(corners);
        for (int i = 0; i < corners; i++) {
            polygon.add(pick(positions, token[i + 1].split("/", -1)[0]));
        }
        List<int[]> triangles = PolygonTriangulator.triangulate(polygon);

        if (corners == 4 && triangles.size() == 2) {
            int start = diagonalStart(triangles.get(0), triangles.get(1));
            if (start >= 0) {
                // 元の巻き方向のまま、対角線の端から回す。GPU の割る対角線 0-2 が
                // 耳切り法の選んだ対角線（start と start+2）に一致する
                for (int i = 0; i < 4; i++) {
                    appendVertex(out, token[(start + i) % 4 + 1], positions, normals, uvs, color, alpha);
                }
                return;
            }
        }

        for (int[] triangle : triangles) {
            appendVertex(out, token[triangle[0] + 1], positions, normals, uvs, color, alpha);
            appendVertex(out, token[triangle[1] + 1], positions, normals, uvs, color, alpha);
            appendVertex(out, token[triangle[2] + 1], positions, normals, uvs, color, alpha);
            // 3 番目の頂点を 2 回。四角形の 1 辺が潰れた形になる
            appendVertex(out, token[triangle[2] + 1], positions, normals, uvs, color, alpha);
        }
    }

    /**
     * 四角形を割った 2 つの三角形が共有する対角線の、片方の端（0..3）。
     *
     * <p>2 つが「向かい合う 2 頂点を共有し、合わせて 4 頂点を覆う」形になっていなければ -1
     * （割り方が崩れた四角形。呼ぶ側は三角形のまま積む）。</p>
     */
    private static int diagonalStart(int[] first, int[] second) {
        int shared = 0;
        int mask = 0;
        int end = -1;
        for (int a : first) {
            mask |= 1 << a;
            for (int b : second) {
                if (a == b) {
                    shared++;
                    end = a;
                }
            }
        }
        for (int b : second) {
            mask |= 1 << b;
        }
        if (shared != 2 || mask != 0b1111) {
            return -1;
        }
        // 共有する 2 頂点が隣り合っていれば対角線ではない（辺を共有しているだけ）
        int other = -1;
        for (int a : first) {
            for (int b : second) {
                if (a == b && a != end) {
                    other = a;
                }
            }
        }
        return (end + 2) % 4 == other ? end : -1;
    }

    /** {@code 位置/UV/法線} の組を 1 頂点ぶん積む。UV も法線も省略されうる。 */
    private static void appendVertex(List<Float> out, String token, List<float[]> positions,
                                     List<float[]> normals, List<float[]> uvs, float[] color,
                                     float alpha) {
        String[] index = token.split("/", -1);

        float[] position = pick(positions, index[0]);
        out.add(position[0]);
        out.add(position[1]);
        out.add(position[2]);

        float[] normal = index.length > 2 ? pick(normals, index[2]) : null;
        // 法線が無ければ上向きにしておく。真っ黒になるより平坦に見える方がまし
        out.add(normal == null ? 0.0F : normal[0]);
        out.add(normal == null ? 1.0F : normal[1]);
        out.add(normal == null ? 0.0F : normal[2]);

        float[] uv = index.length > 1 ? pick(uvs, index[1]) : null;
        out.add(uv == null ? 0.0F : uv[0]);
        out.add(uv == null ? 0.0F : uv[1]);

        out.add(color[0]);
        out.add(color[1]);
        out.add(color[2]);
        out.add(alpha);
    }

    /**
     * インデックスを引く。
     *
     * <p>OBJ のインデックスは 1 始まりで、<b>負の値は末尾からの相対</b>（-1 が直前の頂点）。
     * Blender は正の絶対値しか書かないが、他のツールを通した OBJ で出てくる。</p>
     */
    private static float[] pick(List<float[]> list, String token) {
        if (token.isEmpty()) {
            return null;
        }
        int index = Integer.parseInt(token);
        return list.get(index > 0 ? index - 1 : list.size() + index);
    }

    /**
     * MTL を読んで、マテリアル名 → 拡散色の表を作る。
     *
     * <p>UV テクスチャができるまでのつなぎとして、<b>拡散色（Kd）を頂点カラーとして掛ける</b>。
     * テクスチャに色が入ったら Blender 側でマテリアルのベースカラーを白にすること
     * （Blender の既定は 0.8 のグレーなので、そのままだとテクスチャが暗くなる）。</p>
     *
     * <p>MTL が無くても構わない。その場合は全体が白（テクスチャそのまま）になる。</p>
     *
     * <p>あわせて<b>不透明度（{@code d}、または裏返しの {@code Tr}）</b>も読む。Blender では
     * マテリアルの <b>Alpha</b> がそのまま {@code d} として書き出されるので、ガラスの濃さは
     * Blender 側で決められる（コードもテクスチャも触らずに済む）。</p>
     */
    private static Map<String, float[]> loadMaterials(ResourceLocation objLocation, String fileName,
                                                      ResourceManager resources) {
        String directory = objLocation.getPath().substring(0, objLocation.getPath().lastIndexOf('/') + 1);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(objLocation.getNamespace(), directory + fileName);

        Optional<Resource> resource = resources.getResource(location);
        if (resource.isEmpty()) {
            LOGGER.warn("MTL が見つかりません（色は白になります）: {}", location);
            return Map.of();
        }

        Map<String, float[]> materials = new HashMap<>();
        try (BufferedReader reader = resource.get().openAsReader()) {
            String name = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                String[] token = line.split("\\s+");
                if (token[0].equals("newmtl") && token.length > 1) {
                    name = line.substring(7).trim();
                } else if (token[0].equals("Kd") && token.length > 3 && name != null) {
                    float[] existing = materials.get(name);
                    materials.put(name, new float[]{
                            parseFloat(token[1]), parseFloat(token[2]), parseFloat(token[3]),
                            existing == null ? 1.0F : existing[3]});
                } else if (token[0].equals("d") && token.length > 1 && name != null) {
                    // Kd と d はどちらが先に来るか決まっていないので、揃うまで互いの値を残す
                    setAlpha(materials, name, parseFloat(token[1]));
                } else if (token[0].equals("Tr") && token.length > 1 && name != null) {
                    // Tr は透明度（d の裏返し）。両方書く MTL もあるが、意味が同じなので上書きでよい
                    setAlpha(materials, name, 1.0F - parseFloat(token[1]));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("MTL の読み込みに失敗しました: {}", location, e);
        }
        return materials;
    }

    /** そのマテリアルの不透明度だけを差し替える。まだ Kd を読んでいなければ白で作る。 */
    private static void setAlpha(Map<String, float[]> materials, String name, float alpha) {
        float[] existing = materials.get(name);
        materials.put(name, existing == null
                ? new float[]{1.0F, 1.0F, 1.0F, alpha}
                : new float[]{existing[0], existing[1], existing[2], alpha});
    }

    private static float parseFloat(String token) {
        return Float.parseFloat(token);
    }
}
