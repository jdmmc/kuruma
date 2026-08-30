package com.jdmmc.kurumamod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
         * 運転席の位置 [m]。<b>{@code body.offset} と同じ向き</b>（+X 右・+Y 上・-Z 前）で、
         * シャシー基準面から見た座り位置。助手席は左右反転で置く。
         *
         * <p><b>これは見た目であって物理ではない。</b>座面の高さは車体の形で決まるものなので、
         * 車ごとに変わる。物理は一切読まないので {@code assets/} にあってよい
         * （サーバー側は既定値のまま。乗員の位置は各クライアントが自分で計算するので実害はない）。</p>
         */
        Vec3 seatOffset,
        float shadowRadius) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 車種ごとに読んだ定義。リソースの再読み込み（F3+T）で捨てる。 */
    private static final Map<ResourceLocation, CarModel> CACHE = new HashMap<>();

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
                new ResourceLocation(Kurumamod.MODID, "textures/entity/car.png"),
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
                new Vec3(0.5, -0.55, 0.2),
                1.4F);
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
            CarModel model = parse(GsonHelper.parse(reader));
            LOGGER.info("車の見た目の定義を読み込みました: {}", location);
            return model;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("車の見た目の定義の読み込みに失敗しました（既定の見た目を使います）: {}", location, e);
            return defaults();
        }
    }

    /** 書かれていない項目は既定値のまま。全部書かなくてよい。 */
    private static CarModel parse(JsonObject root) {
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
                vector(wheel, "offset", Vec3.ZERO),
                vector(wheel, "scale", Vec3.ONE),
                number(wheel, "camber", base.camber()),
                vector(root, "seat", base.seatOffset()),
                (float) number(root, "shadowRadius", base.shadowRadius()));
    }

    /*
     * 以下の読み取りは package-private。部品の見た目（CarPartModel）が同じ書き方の JSON を
     * 読むので、そちらと共有する。書き方が 2 つに割れると、車の JSON では書ける形が
     * 部品の JSON では書けない、ということが起きる。
     */

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
