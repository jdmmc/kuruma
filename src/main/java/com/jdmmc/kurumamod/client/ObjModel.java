package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
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

    /** 三角形の頂点が {@link #STRIDE} 個ずつ並んだもの。 */
    private final float[] vertices;
    private final int triangleCount;
    /** オブジェクトごとの範囲（float の添字）。名前で選んで描くために持つ。 */
    private final List<Group> groups;
    /** 透ける面を 1 つでも持っているか。無ければ 2 パス目を丸ごと省ける。 */
    private final boolean translucent;

    /** OBJ の {@code o} / {@code g} ひと区切り。 */
    private record Group(String name, int from, int to) {
    }

    private ObjModel(float[] vertices, List<Group> groups) {
        this.vertices = vertices;
        this.triangleCount = vertices.length / (STRIDE * 3);
        this.groups = groups;

        // 2 パス目が要るかは読み込みのときに数えておく。毎フレーム走査する値ではない。
        // 判定はそのまま accepts に聞く——ここだけ別の式で数えると、灯火のレンズが
        // 透けていたときに「何も描かない 2 パス目」が残るような食い違いが出る
        boolean any = false;
        for (int triangle = 0; triangle < triangleCount && !any; triangle++) {
            any = accepts(triangle * 3 * STRIDE, null, Pass.TRANSLUCENT);
        }
        this.translucent = any;
    }

    /** 透ける面を持っているか。false なら半透明のパスは呼ばなくてよい。 */
    public boolean hasTranslucent() {
        return translucent;
    }

    /** その名前のオブジェクトを持っているか。 */
    public boolean hasGroup(String name) {
        return groups.stream().anyMatch(group -> group.name().equals(name));
    }

    /**
     * そのオブジェクトの頂点を中心線の左右に振り分けた重心。ライトの位置に使う。
     *
     * <p>左右に分かれていれば 2 つ、中央に 1 つだけなら 1 つ返る。ミラーで作られた
     * 1 オブジェクトからそのまま左右のライトが取れる。</p>
     */
    public List<Vec3> groupSideCenters(String name) {
        double[] sum = new double[6];
        int[] count = new int[2];
        forEachVertex(name, (x, y, z) -> {
            int side = x < 0.0 ? 0 : 1;
            sum[side * 3] += x;
            sum[side * 3 + 1] += y;
            sum[side * 3 + 2] += z;
            count[side]++;
        });
        List<Vec3> centers = new ArrayList<>(2);
        for (int side = 0; side < 2; side++) {
            if (count[side] > 0) {
                centers.add(new Vec3(sum[side * 3] / count[side],
                        sum[side * 3 + 1] / count[side], sum[side * 3 + 2] / count[side]));
            }
        }
        return centers;
    }

    /** そのオブジェクトの平均法線。ライトの照射方向に使う。面が無ければ前方（-Z）。 */
    public Vec3 groupNormal(String name) {
        double[] sum = new double[3];
        for (Group group : groups) {
            if (!group.name().equals(name)) {
                continue;
            }
            for (int i = group.from(); i < group.to(); i += STRIDE) {
                sum[0] += vertices[i + 3];
                sum[1] += vertices[i + 4];
                sum[2] += vertices[i + 5];
            }
        }
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
        float minU = Float.MAX_VALUE;
        float maxU = -Float.MAX_VALUE;
        float minV = Float.MAX_VALUE;
        float maxV = -Float.MAX_VALUE;
        for (Group group : groups) {
            if (!group.name().equals(name)) {
                continue;
            }
            for (int i = group.from(); i < group.to(); i += STRIDE) {
                minU = Math.min(minU, vertices[i + 6]);
                maxU = Math.max(maxU, vertices[i + 6]);
                minV = Math.min(minV, vertices[i + 7]);
                maxV = Math.max(maxV, vertices[i + 7]);
            }
        }
        return maxU - minU > UV_MAPPED_EPSILON || maxV - minV > UV_MAPPED_EPSILON;
    }

    private interface VertexSink {
        void accept(double x, double y, double z);
    }

    private void forEachVertex(String name, VertexSink sink) {
        for (Group group : groups) {
            if (!group.name().equals(name)) {
                continue;
            }
            for (int i = group.from(); i < group.to(); i += STRIDE) {
                sink.accept(vertices[i], vertices[i + 1], vertices[i + 2]);
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
        CACHE.clear();
    }

    public boolean isEmpty() {
        return triangleCount == 0;
    }

    /**
     * 描く。
     *
     * <p>Minecraft のエンティティ用 {@code RenderType} はどれも QUADS なので、
     * <b>三角形は最後の頂点を 2 回積んで四角形として流す</b>（縮退四角形）。
     * 面積 0 の辺ができるだけで見た目には影響しない。</p>
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

        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int base = triangle * 3 * STRIDE;
            if (!accepts(base, only, pass)) {
                continue;
            }
            for (int corner = 0; corner < 4; corner++) {
                // 3 番目の頂点を 2 回。四角形の 1 辺が潰れた形になる
                int offset = base + Math.min(corner, 2) * STRIDE;
                consumer.vertex(matrix, flip * vertices[offset], vertices[offset + 1], vertices[offset + 2])
                        .color(vertices[offset + 8] * tintRed,
                                vertices[offset + 9] * tintGreen,
                                vertices[offset + 10] * tintBlue,
                                vertices[offset + ALPHA_OFFSET] * alpha)
                        .uv(vertices[offset + 6], vertices[offset + 7])
                        .overlayCoords(packedOverlay)
                        .uv2(packedLight)
                        .normal(normal, flip * vertices[offset + 3], vertices[offset + 4], vertices[offset + 5])
                        .endVertex();
            }
        }
    }

    /**
     * その三角形を描くか。
     *
     * <p>光る部分は車体の描画から外し、名指しされたときだけ描く。それ以外は<b>頂点の
     * 不透明度</b>でパスを振り分ける——透ける面は車体のパスから外れ、半透明のパスが拾う。</p>
     */
    private boolean accepts(int base, String only, Pass pass) {
        String name = groupNameAt(base);
        if (only != null) {
            // 名指し（灯火のレンズ）はパスを問わない。RenderType は呼ぶ側が選んでいる
            return name != null && name.equals(only);
        }
        if (name != null && name.startsWith(LIGHT_PREFIX)) {
            return false;
        }
        boolean transparent = vertices[base + ALPHA_OFFSET] < OPAQUE_THRESHOLD;
        return transparent == (pass == Pass.TRANSLUCENT);
    }

    /** その三角形が属するオブジェクトの名前。{@code o} も {@code g} も無い OBJ では null。 */
    private String groupNameAt(int base) {
        for (Group group : groups) {
            if (base >= group.from() && base < group.to()) {
                return group.name();
            }
        }
        return null;
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
            LOGGER.info("OBJ モデルを読み込みました: {}（{} 三角形）", location, model.triangleCount);
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
                        groups.add(new Group(groupName, groupStart, out.size()));
                    }
                    groupName = token.length > 1 ? token[1] : "";
                    groupStart = out.size();
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
            groups.add(new Group(groupName, groupStart, out.size()));
        }

        float[] vertices = new float[out.size()];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = out.get(i);
        }
        return new ObjModel(vertices, groups);
    }

    /**
     * 面を三角形に割って積む。
     *
     * <p>割り方は {@link PolygonTriangulator}（耳切り法）に任せる。<b>凹んだ n-gon も
     * 正しく割れる</b>ので、Blender 側で「三角形化」して書き出す必要はない。</p>
     *
     * <p>以前は最初の頂点からの扇状分割だったが、これは凸多角形でしか正しくない。実際に
     * 書き出された車体は 52 個の n-gon のうち 44 個が凹んでいて、面がはみ出していた。</p>
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

        for (int[] triangle : PolygonTriangulator.triangulate(polygon)) {
            appendVertex(out, token[triangle[0] + 1], positions, normals, uvs, color, alpha);
            appendVertex(out, token[triangle[1] + 1], positions, normals, uvs, color, alpha);
            appendVertex(out, token[triangle[2] + 1], positions, normals, uvs, color, alpha);
        }
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
        ResourceLocation location = new ResourceLocation(objLocation.getNamespace(), directory + fileName);

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
