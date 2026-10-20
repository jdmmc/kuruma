package com.jdmmc.kurumamod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 車の<b>見た目</b>の定義。{@code assets/kurumamod/vehicles/car.json} に書く。
 *
 * <h2>ここに入れてよいもの</h2>
 *
 * <p><b>見た目だけ。物理に効く値は入れない。</b>{@code assets/} はクライアント専用で
 * サーバーは読まないので、ここに諸元を置くと<b>無人の車をサーバーが解くときの挙動が
 * クライアントのリソースパック次第</b>になってしまう。挙動を決める値は
 * {@link CarSpec} 側の一本道（調整画面 → NBT 保存 → {@code CarSpecPacket} で同期）に任せる。</p>
 *
 * <h2>タイヤは諸元に追従し、車体はしない</h2>
 *
 * <p><b>ホイールベースとトレッドはタイヤの位置の定義そのもの</b>なので、
 * {@link CarObjRenderer} はタイヤを {@link CarSpec} の値どおりに置く。タイヤの大きさも
 * {@link CarSpec#wheelRadius()} に追従する。つまり<b>調整画面で寸法を動かすと
 * タイヤだけが動く</b>。</p>
 *
 * <p>一方<b>車体はメッシュの形が正</b>で、諸元には追従しない（ホイールベースに合わせて
 * 伸ばすとキャビンごと伸びてしまうため）。大きさを変えたいときは {@code bodyScale} で
 * 明示する。<b>寸法を大きく動かせばタイヤはフェンダーからはみ出す</b>が、これは
 * そういう車を作ったことの正直な表示として受け入れている。</p>
 *
 * <h2>{@code designWheelBase} は「メッシュのホイールアーチが何 m 離れているか」</h2>
 *
 * <p><b>比べる相手は {@link CarSpec#DEFAULT} であって、今の諸元ではない。</b>ここを間違えると
 * ホイールベースのスライダーで車体まで伸び縮みする（＝キャビンごと伸びる、却下した挙動）。</p>
 *
 * <p>役目は<b>メッシュの作りの大きさを直すことだけ</b>。5.2m で作ってしまったモデルに
 * {@code 5.2} と書けば半分に縮んで既定の車格になり、アーチがタイヤの位置に合う。定数なので
 * スライダーには一切追従しない。</p>
 *
 * <p><b>「大きい車にしたい」ときはこれではない。</b>それは物理的にもホイールベースの長い車なので、
 * 調整画面のホイールベースとトレッドを動かし、{@code bodyScale} を同じ倍率にする。
 * 見た目（{@code assets/}）から諸元を動かすことはできない——できるようにすると、車の寸法が
 * リソースパック次第になってしまう。</p>
 *
 * <h2>{@code designWheelRadius} は「メッシュが何 m で作られているか」</h2>
 *
 * <p>{@link CarSpec#wheelRadius()} と名前が似ているが<b>別の数字</b>。</p>
 *
 * <ul>
 *   <li>{@code CarSpec.wheelRadius} … この車のタイヤ半径は 0.375m である（<b>物理</b>）</li>
 *   <li>{@code designWheelRadius} … <b>このメッシュは半径 0.375m で作られている</b>（描画の基準）</li>
 * </ul>
 *
 * <p>前者を後者で割ったものが拡大率になる。おかげで<b>タイヤを特定の大きさちょうどで
 * 作る必要がない</b>——作った寸法をここに書けば、描画側がその比で正規化する。</p>
 *
 * <p>その<b>上に</b>手で決める {@code wheelScale} が乗るが、<b>こちらで直径（Y・Z）を
 * 変えると接地が崩れる</b>。接地点は物理のタイヤ半径から決まっていて、見た目だけ大きく
 * しても物理は知らないため。太さ（X）だけなら安全。</p>
 *
 * @param designWheelBase   メッシュのホイールアーチの間隔 [m]。<b>既定の 2.6m との比</b>で正規化する
 * @param designWheelRadius メッシュが作られたときのタイヤ半径 [m]。直径ではない
 * @param designRideHeight  メッシュが作られたときの車高 [m]。車体をシャシー基準面から下げる量
 * @param bodyOffset        車体の平行移動 [m]。エンティティ空間（+X 右・+Y 上・-Z 前）
 * @param bodyScale         車体の拡大率。原点が地面なので、上げると<b>上へ伸びる</b>
 * @param wheelOffset       タイヤの平行移動 [m]。<b>X は外向き</b>（左右で符号が反転する）、Y は上、Z は後ろ
 * @param wheelScale        タイヤの拡大率。<b>X はタイヤの太さ、Y・Z は直径</b>（直径は接地に効く）
 * @param camber            キャンバー角 [度]。自動車の慣習どおり<b>負で「上が内側」</b>（ネガティブキャンバー）
 * @param shadowRadius      影の大きさ。車の大きさを変えたら一緒に見直す
 * @param sounds            音の差し替え（{@code sounds}）。書かなかった音は KurumaMod の音のまま
 * @param partObjects       部品 id → 付けたときに車体の OBJ で出す・消すオブジェクト（{@code parts}）。
 *                          書かなかった部品は名前の決まり {@code part_<部品のパス>} で探す
 * @param icon              アイテムのアイコンの画像（{@code icon}。テクスチャのパスを拡張子込みで書く）。
 *                          null なら車のモデルから焼いた絵を使う（{@code CarIcons}）
 * @param paints            塗装の一覧（{@code paints}）。<b>先頭が新車の色</b>。空なら {@code body.texture} だけ
 */
public record CarModel(
        ResourceLocation bodyModel,
        ResourceLocation bodyTexture,
        ResourceLocation wheelModel,
        ResourceLocation wheelTexture,
        double designWheelBase,
        double designWheelRadius,
        double designRideHeight,
        Vec3 bodyOffset,
        Vec3 bodyScale,
        Vec3 wheelOffset,
        Vec3 wheelScale,
        double camber,
        /**
         * ホイールのメッシュで、タイヤの内径（ビード）が乗る半径 [m]（メッシュの寸法）。null なら測る
         * （付属のタイヤがあればその内径、無ければリムの外縁の {@code CarPartModel#BEAD_RATIO} 倍）。
         */
        @org.jetbrains.annotations.Nullable Double wheelRimRadius,
        /**
         * 運転席の位置 [m]。<b>{@code body.offset} と同じ向き</b>（+X 右・+Y 上・-Z 前）で、
         * シャシー基準面から見た座り位置。助手席は左右反転で置く。
         *
         * <p><b>これは見た目であって物理ではない。</b>座面の高さは車体の形で決まるものなので、
         * 車ごとに変わる。物理は一切読まないので {@code assets/} にあってよい
         * （サーバー側は既定値のまま。乗員の位置は各クライアントが自分で計算するので実害はない）。</p>
         */
        Vec3 seatOffset,
        /**
         * ハンドルの平行移動 [m]（{@code steering.offset}）。<b>{@code seat} と同じ向き・同じ縮尺</b>
         * （+X 右・+Y 上・-Z 前。{@code body.scale} や {@code designWheelBase} の拡大を掛ける前の m）。
         *
         * <p>回す中心と軸はメッシュから読む（{@code SteeringWheelAxis}）ので、ここで動かすのは位置だけ。
         * 中心も一緒に動くので、ずらしても振れずに回る。座席の調整画面（Ctrl+G）で決めて貼る値。</p>
         */
        Vec3 steeringOffset,
        /**
         * ハンドルのコラムの傾きの足し分 [度]（{@code steering.tilt}）。<b>正でコラムが起きる</b>
         * （ハンドルの面が寝る）。メッシュの中心を通る左右の軸のまわりに傾けるので、回す軸も一緒に傾く。
         */
        double steeringTilt,
        float shadowRadius,
        CarSoundSet sounds,
        Map<ResourceLocation, PartObjects> partObjects,
        @org.jetbrains.annotations.Nullable ResourceLocation icon,
        List<Paint> paints) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 車種ごとに読んだ定義。リソースの再読み込み（F3+T）で捨てる。 */
    private static final Map<ResourceLocation, CarModel> CACHE = new HashMap<>();

    /**
     * 部品を付けたときに出す・消す車体のオブジェクト。{@code vehicles/<car>.json} の {@code parts}。
     *
     * <pre>
     * "parts": {
     *   "kurumamod:gt_wing": { "show": ["part_gt_wing", "part_gt_wing_stay"] }
     * }
     * </pre>
     *
     * <p>名前の決まり（{@code part_<部品のパス>}）と違う名前にしたいとき、1 つの部品で複数の
     * オブジェクトを出したいときに書く。出せるのは {@code part_} で始まるオブジェクトだけ
     * （{@link ObjModel#PART_PREFIX}）。</p>
     */
    public record PartObjects(List<String> show) {

        public PartObjects {
            show = List.copyOf(show);
        }
    }

    /**
     * 塗装 1 つ。{@code vehicles/<car>.json} の {@code paints}。
     *
     * <pre>
     * "paints": [
     *   { "id": "blue", "name": "paint.kurumamod.blue", "texture": "kurumamod:textures/entity/s15.png", "swatch": "#051F8C" },
     *   { "id": "red",  "name": "Red", "texture": "kurumamod:textures/entity/s15_red.png", "swatch": "#B4141E" }
     * ]
     * </pre>
     *
     * <p><b>塗り替えるのは車体のテクスチャまるごと。</b>単色だけでなく、ステッカーやラッピングも
     * 同じ書き方で足せる。UV は車体の OBJ のものをそのまま使うので、元のテクスチャと同じ配置で描くこと。
     * ガラス・灯火・鏡も同じテクスチャから色を取るので、塗る場所以外は元のまま残す。</p>
     *
     * @param id      塗装の id。車ごとに一意。<b>小文字・数字・{@code _ - .} だけ</b>（{@link PartFitment#isValidPaint}）
     * @param name    画面に出す名前。翻訳キーでもそのままの文字でもよい（キーが無ければそのまま出る）。
     *                書かなければ id
     * @param texture 車体のテクスチャ
     * @param swatch  換装画面の色見本（0xRRGGBB）。null なら灰色の見本に「?」
     */
    public record Paint(String id, String name, ResourceLocation texture,
                        @org.jetbrains.annotations.Nullable Integer swatch) {
    }

    /**
     * その装着状態で使う塗装。<b>指定が無い・知らない id なら先頭（新車の色）</b>。塗装が 1 つも無ければ null。
     *
     * <p>知らない id は、カーパックを更新して消えた色や、手元のパックが古くて知らない色。
     * 落とさずに先頭へ読み替えるだけなので、入れ直せば元の色に戻る。</p>
     */
    @org.jetbrains.annotations.Nullable
    public Paint paintOf(PartFitment fitment) {
        if (paints.isEmpty()) {
            return null;
        }
        String id = fitment.paint();
        if (id != null) {
            for (Paint paint : paints) {
                if (paint.id().equals(id)) {
                    return paint;
                }
            }
        }
        return paints.get(0);
    }

    /** その装着状態で車体に貼るテクスチャ。塗装が無い車は {@code body.texture}。 */
    public ResourceLocation bodyTexture(PartFitment fitment) {
        Paint paint = paintOf(fitment);
        return paint != null ? paint.texture() : bodyTexture;
    }

    /** いま出す部品の形。 */
    public record BodyParts(Set<String> shown) {

        public static final BodyParts NONE = new BodyParts(Set.of());
    }

    /**
     * 装着状態から、出す部品の形を決める。<b>初期装備を足した状態（{@code CarEntity#getEffectiveFitment}）を渡すこと。</b>
     *
     * <p>{@code parts} に書かれた部品はその指定どおり。書かれていなければ名前の決まり
     * （{@link #conventionalNames}）で探し、車体の OBJ にあればそれを出す。どの場所の部品でも効く
     * （空力部品に限らない）。</p>
     *
     * @param available 車体の OBJ が持っている、出し入れする形の名前（{@link ObjModel#optionalNames}）
     */
    public BodyParts bodyParts(PartFitment fitment, Set<String> available) {
        if (fitment.isEmpty() || available.isEmpty()) {
            return BodyParts.NONE;
        }
        Set<String> shown = new HashSet<>();
        for (PartSlot slot : PartSlot.VALUES) {
            ResourceLocation id = fitment.getPart(slot);
            if (id == null) {
                continue;
            }
            PartObjects objects = partObjects.get(id);
            if (objects != null) {
                shown.addAll(objects.show());
            } else {
                for (String name : conventionalNames(id, slot)) {
                    if (available.contains(name)) {
                        shown.add(name);
                    }
                }
            }
        }
        return new BodyParts(shown);
    }

    /**
     * その部品の形をこの車が持っているか。<b>車体の形で見せる部品（空力部品）は、持っている車にしか並べない。</b>
     *
     * <p>見た目の無い部品を履けると、他の人には何も見えないのにグリップだけ上がった車が走ることになる。</p>
     */
    public boolean hasPartObjects(ResourceLocation partId, PartSlot slot, Set<String> available) {
        PartObjects objects = partObjects.get(partId);
        if (objects != null) {
            return objects.show().stream().anyMatch(available::contains);
        }
        return conventionalNames(partId, slot).stream().anyMatch(available::contains);
    }

    /**
     * {@code parts} に書かなかった部品の形の名前。<b>どちらで作ってもよい。</b>
     *
     * <ul>
     *   <li>{@code part_<部品のパス>} … {@code part_gt_wing}</li>
     *   <li>{@code part_<場所>-<部品のパス>} … {@code part_rear_wing-gt_wing}。Blender の一覧で
     *       どの場所の部品か分かるように、場所の名前を前に付けた形</li>
     * </ul>
     */
    static List<String> conventionalNames(ResourceLocation partId, PartSlot slot) {
        return List.of(ObjModel.PART_PREFIX + partId.getPath(),
                ObjModel.PART_PREFIX + slot.getSerializedName() + "-" + partId.getPath());
    }

    /** 3 つ組。平行移動にも拡大率にも使う。 */
    public record Vec3(double x, double y, double z) {

        static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);
        static final Vec3 ONE = new Vec3(1.0, 1.0, 1.0);
    }

    /**
     * その車種の見た目の定義。まだ読んでいなければ読む。
     *
     * <p>読み込みに失敗しても例外は投げず、既定の定義を返してログに残す。JSON が 1 つ
     * 壊れているだけでゲームが落ちるより、既定の見た目で走れる方がましなため。
     * <b>カーパックを外したセーブデータでも、既定の見た目で走れる。</b></p>
     */
    public static CarModel get(ResourceLocation carId) {
        return CACHE.computeIfAbsent(carId, CarModel::load);
    }

    /**
     * その車種の見た目の定義の置き場所。
     *
     * <p>{@code mypack:ae86} → {@code mypack:vehicles/ae86.json}。諸元の
     * {@code data/mypack/cars/ae86.json} と同じ id から引けるので、
     * カーパック側は 2 つのファイル名を揃えるだけでよい。</p>
     */
    public static ResourceLocation locationOf(ResourceLocation carId) {
        return new ResourceLocation(carId.getNamespace(), "vehicles/" + carId.getPath() + ".json");
    }

    /** リソースの再読み込みで呼ぶ。JSON を書き換えて F3+T を押せば反映される。 */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 既定の定義。JSON が無い・壊れている場合と、書かれていない項目に使う。
     *
     * <p>寸法を {@link CarSpec#DEFAULT} から取るのは、<b>今あるモデルが既定の諸元ちょうどで
     * 作られている</b>ため。JSON を置かなければ従来どおりの見た目になる。</p>
     */
    public static CarModel defaults() {
        return new CarModel(
                new ResourceLocation(Kurumamod.MODID, "models/entity/s15.obj"),
                new ResourceLocation(Kurumamod.MODID, "textures/entity/s15.png"),
                new ResourceLocation(Kurumamod.MODID, "models/entity/wheel.obj"),
                new ResourceLocation(Kurumamod.MODID, "textures/entity/wheel.png"),
                CarSpec.DEFAULT.wheelBase(),
                CarSpec.DEFAULT.wheelRadius(),
                CarSpec.DEFAULT.staticRideHeight(),
                Vec3.ZERO,
                Vec3.ONE,
                Vec3.ZERO,
                Vec3.ONE,
                0.0,
                null,
                new Vec3(0.5, -0.55, 0.2),
                Vec3.ZERO,
                0.0,
                1.4F,
                CarSoundSet.NONE,
                Map.of(),
                null,
                List.of());
    }

    // ------------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------------

    private static CarModel load(ResourceLocation carId) {
        ResourceLocation location = locationOf(carId);
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.warn("車の見た目の定義が見つかりません（既定の見た目を使います）: {}", location);
            return defaults();
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            CarModel model = parse(GsonHelper.parse(reader), location);
            LOGGER.info("車の見た目の定義を読み込みました: {}", location);
            return model;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("車の見た目の定義の読み込みに失敗しました（既定の見た目を使います）: {}", location, e);
            return defaults();
        }
    }

    /** 書かれていない項目は既定値のまま。全部書かなくてよい。 */
    private static CarModel parse(JsonObject root, ResourceLocation location) {
        CarModel base = defaults();
        JsonObject body = child(root, "body");
        JsonObject wheel = child(root, "wheel");

        return new CarModel(
                location(body, "model", base.bodyModel()),
                location(body, "texture", base.bodyTexture()),
                location(wheel, "model", base.wheelModel()),
                location(wheel, "texture", base.wheelTexture()),
                number(root, "designWheelBase", base.designWheelBase()),
                number(root, "designWheelRadius", base.designWheelRadius()),
                number(root, "designRideHeight", base.designRideHeight()),
                vector(body, "offset", Vec3.ZERO),
                vector(body, "scale", Vec3.ONE),
                wheelOffsetInRange(location, vector(wheel, "offset", Vec3.ZERO)),
                wheelScaleInRange(location, vector(wheel, "scale", Vec3.ONE)),
                wheelCamberInRange(location, number(wheel, "camber", base.camber())),
                wheel.has("rimRadius") ? number(wheel, "rimRadius", 0.0) : null,
                vector(root, "seat", base.seatOffset()),
                vector(child(root, "steering"), "offset", Vec3.ZERO),
                number(child(root, "steering"), "tilt", 0.0),
                (float) number(root, "shadowRadius", base.shadowRadius()),
                CarSoundSet.parse(child(root, "sounds")),
                parsePartObjects(child(root, "parts"), location),
                icon(root, location),
                parsePaints(root, location));
    }

    /**
     * {@code paints}。<b>読めない塗装はその 1 つだけ捨ててログに残す</b>（車の見た目まで既定へ落とさない）。
     * id の重なりは先に書いたほうを使う。
     */
    private static List<Paint> parsePaints(JsonObject root, ResourceLocation location) {
        if (!root.has("paints")) {
            return List.of();
        }
        if (!root.get("paints").isJsonArray()) {
            LOGGER.warn("{} の paints は配列で書いてください（塗装を無視します）", location);
            return List.of();
        }
        List<Paint> paints = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray array = root.getAsJsonArray("paints");
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                LOGGER.warn("{} の paints[{}] を読めません（その塗装だけ無視します）", location, i);
                continue;
            }
            JsonObject entry = array.get(i).getAsJsonObject();
            String id = entry.has("id") && entry.get("id").isJsonPrimitive() ? entry.get("id").getAsString() : null;
            if (!PartFitment.isValidPaint(id)) {
                LOGGER.warn("{} の paints[{}].id は小文字・数字・_ - . の {} 字以内で書いてください（その塗装だけ無視します）: {}",
                        location, i, PartFitment.PAINT_MAX_LENGTH, entry.get("id"));
                continue;
            }
            if (!seen.add(id)) {
                LOGGER.warn("{} の paints に id \"{}\" が 2 つあります（後のほうを無視します）", location, id);
                continue;
            }
            ResourceLocation texture = entry.has("texture") && entry.get("texture").isJsonPrimitive()
                    ? ResourceLocation.tryParse(entry.get("texture").getAsString()) : null;
            if (texture == null) {
                LOGGER.warn("{} の paints[{}]（{}）に texture がありません（その塗装だけ無視します）", location, i, id);
                continue;
            }
            String name = entry.has("name") && entry.get("name").isJsonPrimitive()
                    ? entry.get("name").getAsString() : id;
            paints.add(new Paint(id, name, texture, swatch(entry, location, id)));
        }
        return List.copyOf(paints);
    }

    /** {@code swatch}（{@code "#RRGGBB"}）。読めなければ見本無し。 */
    @org.jetbrains.annotations.Nullable
    private static Integer swatch(JsonObject entry, ResourceLocation location, String id) {
        if (!entry.has("swatch")) {
            return null;
        }
        String raw = entry.get("swatch").isJsonPrimitive() ? entry.get("swatch").getAsString().trim() : "";
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        if (raw.length() == 6) {
            try {
                return Integer.parseInt(raw, 16);
            } catch (NumberFormatException ignored) {
                // 下で知らせる
            }
        }
        LOGGER.warn("{} の塗装 {} の swatch は \"#RRGGBB\" で書いてください（色見本を出しません）: {}",
                location, id, entry.get("swatch"));
        return null;
    }

    /**
     * {@code icon}。読めない値はアイコンだけ捨ててログに残す（車の見た目まで既定へ落とさない）。
     * 画像が実際にあるかは、使うときに {@code CarIcons} が確かめる。
     */
    @org.jetbrains.annotations.Nullable
    private static ResourceLocation icon(JsonObject root, ResourceLocation location) {
        if (!root.has("icon")) {
            return null;
        }
        ResourceLocation icon = root.get("icon").isJsonPrimitive()
                ? ResourceLocation.tryParse(root.get("icon").getAsString()) : null;
        if (icon == null) {
            LOGGER.warn("{} の icon を読めません（車のモデルから作ったアイコンを使います）: {}", location, root.get("icon"));
        }
        return icon;
    }

    /** {@code parts}。読めない id・名前の頭が違うオブジェクトは、その値だけ捨ててログに残す。 */
    private static Map<ResourceLocation, PartObjects> parsePartObjects(JsonObject json, ResourceLocation location) {
        Map<ResourceLocation, PartObjects> result = new HashMap<>();
        for (String key : json.keySet()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null || !json.get(key).isJsonObject()) {
                LOGGER.warn("{} の parts.{} を読めません（その部品だけ無視します）", location, key);
                continue;
            }
            JsonObject entry = json.getAsJsonObject(key);
            result.put(id, new PartObjects(names(entry, "show", ObjModel.PART_PREFIX, location, key)));
        }
        return result;
    }

    private static List<String> names(JsonObject entry, String key, String prefix, ResourceLocation location, String part) {
        List<String> names = new ArrayList<>();
        if (!entry.has(key) || !entry.get(key).isJsonArray()) {
            return names;
        }
        for (var element : entry.getAsJsonArray(key)) {
            String name = element.getAsString();
            if (!name.startsWith(prefix)) {
                LOGGER.warn("{} の parts.{}.{} に書けるのは {} で始まる名前だけです（無視します）: {}",
                        location, part, key, prefix, name);
                continue;
            }
            names.add(name);
        }
        return names;
    }

    /*
     * 以下の読み取りは package-private。部品の見た目（CarPartModel）が同じ書き方の JSON を
     * 読むので、そちらと共有する。書き方が 2 つに割れると、車の JSON では書ける形が
     * 部品の JSON では書けない、ということが起きる。
     */

    /*
     * ホイールの見た目のうち、プレイヤーが換装画面で動かせる 3 つ（キャンバー・オフセット・太さ）は、
     * パックが書いた値も<b>その範囲（PartFitment）へ丸める</b>。上に外れたら上限、下に外れたら下限。
     * パックの値は換装画面の「R」の戻り先になるので、範囲の外にあるとスライダーでは二度と
     * 戻れない値になる。車の JSON と部品の JSON が同じものを使う。
     */

    /** キャンバー角 [度]。 */
    static double wheelCamberInRange(ResourceLocation location, double camber) {
        return clampLogged(location, "camber", camber, PartFitment.CAMBER_MIN, PartFitment.CAMBER_MAX);
    }

    /**
     * 取り付け位置。<b>X（外向き [m]）だけが範囲を持つ。</b>範囲は実車と同じ向きの mm
     * （小さいほど外）で決めてあるので、外向きの m へ裏返して比べる。
     */
    static Vec3 wheelOffsetInRange(ResourceLocation location, Vec3 offset) {
        double x = clampLogged(location, "offset[0]", offset.x(),
                -PartFitment.OFFSET_MAX / 1000.0, -PartFitment.OFFSET_MIN / 1000.0);
        return x == offset.x() ? offset : new Vec3(x, offset.y(), offset.z());
    }

    /** 拡大率。<b>X（太さ）だけが範囲を持つ。</b>Y・Z（直径）には触らない。 */
    static Vec3 wheelScaleInRange(ResourceLocation location, Vec3 scale) {
        double x = clampLogged(location, "scale[0]", scale.x(), PartFitment.WIDTH_MIN, PartFitment.WIDTH_MAX);
        return x == scale.x() ? scale : new Vec3(x, scale.y(), scale.z());
    }

    private static double clampLogged(ResourceLocation location, String key, double value, double min, double max) {
        double clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            LOGGER.warn("{} の {} = {} は範囲（{} 〜 {}）の外なので {} に丸めました",
                    location, key, value, min, max, clamped);
        }
        return clamped;
    }

    static JsonObject child(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : new JsonObject();
    }

    static double number(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }

    static ResourceLocation location(JsonObject json, String key, ResourceLocation fallback) {
        return json.has(key) ? new ResourceLocation(json.get(key).getAsString()) : fallback;
    }

    /**
     * 3 つ組。<b>数字 1 つ（3 軸とも同じ値）でも {@code [x, y, z]} でも書ける。</b>
     *
     * <p>拡大率は 1 つの数字で書きたいことがほとんどで、そこで 3 つ並べさせると
     * 書き間違いが増える。個数が違えば既定値に落とす（半端に読むと原因が分かりにくい）。</p>
     */
    static Vec3 vector(JsonObject json, String key, Vec3 fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        if (json.get(key).isJsonArray()) {
            JsonArray array = json.getAsJsonArray(key);
            if (array.size() != 3) {
                LOGGER.warn("{} は数字 1 つか [x, y, z] の 3 つで書いてください（既定値を使います）", key);
                return fallback;
            }
            return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
        }
        double uniform = json.get(key).getAsDouble();
        return new Vec3(uniform, uniform, uniform);
    }
}
