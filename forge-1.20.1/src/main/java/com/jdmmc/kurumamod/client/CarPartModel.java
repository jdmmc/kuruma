package com.jdmmc.kurumamod.client;

import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 部品の<b>見た目</b>の定義。{@code assets/<ns>/car_parts/<path>.json} に書く。
 *
 * <p>{@link CarModel} と同じ制約——<b>見た目だけ。挙動に効く値は入れない。</b>
 * {@code assets/} はサーバーが読まないので、ここに諸元を置くと無人の車をサーバーが解く結果が
 * クライアントのリソースパック次第になる。部品の素性は {@code data/<ns>/car_parts/} 側にある。</p>
 *
 * <h2>書かなかった項目は「車種の既定」になる</h2>
 *
 * <p>{@link CarModel} が「書かなかった項目は MOD 既定」なのに対し、こちらは<b>その車種の
 * {@code wheel} の定義</b>へ落ちる。キャンバーだけ変えたい部品に、モデルとテクスチャの
 * パスを書き写させないため。</p>
 *
 * <pre>
 * {
 *   "wheel": {
 *     "model": "mypack:models/entity/te37.obj",
 *     "texture": "mypack:textures/entity/te37.png",
 *     "offset": [0.02, 0.0, 0.0],
 *     "scale": [1.3, 1.0, 1.0],
 *     "camber": -4.0,
 *     "designRadius": 0.45
 *   }
 * }
 * </pre>
 *
 * <p><b>{@code designRadius} は「このメッシュが何 m の半径で作られているか」。</b>
 * {@code CarModel.designWheelRadius} と同じ役目で、書いておけば描画側が
 * 諸元のタイヤ半径へ正規化する（＝調整画面でタイヤ半径を変えても部品が追従する）。
 * 車のホイールと同じ半径で作ったなら書かなくてよい。</p>
 *
 * <p><b>{@code scale} の Y・Z（直径）を触ると接地が崩れる</b>のも車のホイールと同じ。
 * 接地点は物理のタイヤ半径から決まっていて、この補正を物理は知らないため。太さ（X）だけなら安全。</p>
 */
public record CarPartModel(
        @Nullable ResourceLocation model,
        @Nullable ResourceLocation texture,
        @Nullable CarModel.Vec3 offset,
        @Nullable CarModel.Vec3 scale,
        @Nullable Double camber,
        @Nullable Double designRadius,
        @Nullable Double rimRadius,
        @Nullable Tire tire,
        @Nullable Exhaust exhaust) {

    /**
     * リムの外縁に対して、タイヤの内径（ビード）をどこに置くか。
     *
     * <p>実際のタイヤはリムの縁の内側へ少し潜り込んで嵌まっている。同梱のホイールは
     * リムの外縁 0.352m に対してタイヤの内径が 0.334m（95%）なので、その比を使う。</p>
     */
    public static final double BEAD_RATIO = 0.95;

    /**
     * タイヤ部品の見た目。{@code assets/<ns>/car_parts/<id>.json} の {@code tire} ブロック。
     *
     * <p>メッシュは<b>タイヤだけ</b>で、原点は車軸の中心・回転軸は左右方向（ホイールと同じ）。
     * 内径と外径はメッシュから測るので書かなくてよい。描くときに、内径がホイールのリムへ、
     * 外径が物理のタイヤ半径へ来るよう半径方向に伸び縮みさせる（{@code ObjModel#fitRadially}）。</p>
     */
    public record Tire(ResourceLocation model, ResourceLocation texture,
                       @Nullable Double innerRadius, @Nullable Double outerRadius) {
    }

    /** 何も付けていないとき（とリムだけのホイール）に描く、MOD 標準のタイヤ。 */
    public static final Tire DEFAULT_TIRE = new Tire(
            new ResourceLocation(com.jdmmc.kurumamod.Kurumamod.MODID, "models/entity/tire.obj"),
            new ResourceLocation(com.jdmmc.kurumamod.Kurumamod.MODID, "textures/entity/wheel.png"),
            null, null);

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 何も書かれていない定義。＝すべて車種の既定のまま。 */
    private static final CarPartModel EMPTY =
            new CarPartModel(null, null, null, null, null, null, null, null, null);

    /**
     * マフラーの音。{@code assets/<ns>/car_parts/<id>.json} の {@code exhaust} ブロック。
     *
     * <pre>
     * "exhaust": {
     *   "pitch": [0.80, 1.00],               // 音程の倍率。範囲なら換装画面の「音の低さ」で動かせる
     *   "volume": 1.3,                        // 音量の倍率
     *   "sound": "mypack:titanium_exhaust",   // 排気音の差し替え（省略でその車の音のまま）
     *   "sound_high": "mypack:titanium_high", // 高回転側（1 オクターブ上）。省略すると sound を同じ音程で鳴らす
     *   "tone": 0.3                           // 調整していないときの低さ（0 で高い、1 で低い）。省略で 0
     * }
     * </pre>
     *
     * <p><b>上げられる音程は少しだけ。</b>Minecraft の音程は 0.5〜2.0 の壁があり、排気音はレブ付近で
     * もう 1.95 を使っている。下げるのは 0.77 倍くらいまで効く（アイドルが壁に当たるところ）。
     * 甲高いマフラーにしたいなら、高い音の音源を {@code sound} で差し替える。</p>
     *
     * <p><b>走りは変わらない。</b>音は各クライアントが鳴らすので、ここ（{@code assets/}）にあってよい。</p>
     */
    public record Exhaust(double pitchMin, double pitchMax, double volume,
                          @Nullable ResourceLocation sound, @Nullable ResourceLocation soundHigh,
                          double defaultTone) {

        /** 範囲で書かれていて、音の低さを動かせるか。 */
        public boolean adjustable() {
            return pitchMin != pitchMax;
        }

        /** その低さ（0 で高い、1 で低い）での音程の倍率。 */
        public double pitchAt(double tone) {
            double t = Math.max(0.0, Math.min(1.0, tone));
            return pitchMax - (pitchMax - pitchMin) * t;
        }
    }

    /**
     * いま付けているマフラーの音。付けていない・見た目の定義が無い・音を書いていなければ null
     * （＝その車の排気音のまま）。<b>初期装備を足した状態（{@code CarEntity#getEffectiveFitment}）を渡すこと。</b>
     */
    @Nullable
    public static Exhaust exhaustOf(PartFitment parts) {
        ResourceLocation partId = parts.getPart(PartSlot.EXHAUST);
        return partId == null ? null : get(partId).exhaust;
    }

    /** いまのマフラーの音程の倍率。音の低さの上書きが無ければ部品の既定。マフラーが無ければ 1。 */
    public static double exhaustPitch(PartFitment parts) {
        Exhaust exhaust = exhaustOf(parts);
        if (exhaust == null) {
            return 1.0;
        }
        Double tone = parts.getTone(PartSlot.EXHAUST);
        return exhaust.pitchAt(tone != null ? tone : exhaust.defaultTone());
    }

    /** 部品ごとに読んだ定義。リソースの再読み込み（F3+T）で捨てる。 */
    private static final Map<ResourceLocation, CarPartModel> CACHE = new HashMap<>();

    /**
     * 実際に描くホイールの値。<b>部品の定義と車種の定義を重ねた結果。</b>
     *
     * <p>描画側でいちいち「部品があれば部品、無ければ車種」と書き分けずに済むよう、
     * 重ね合わせはここで済ませる。</p>
     */
    public record Wheel(
            ResourceLocation model,
            ResourceLocation texture,
            CarModel.Vec3 offset,
            CarModel.Vec3 scale,
            double camber,
            double designRadius,
            @Nullable Double rimRadius) {
    }

    /**
     * その車が履いているホイールの見た目。何も履いていなければ車種の既定がそのまま返る。
     *
     * <p><b>知らない部品も既定へ落とす。</b>カーパックを外しても既定のホイールで走れて、
     * 入れ直せば元に戻る（装着状態は id を覚えたままなので）。</p>
     */
    public static Wheel wheelOf(CarModel car, PartFitment parts) {
        ResourceLocation partId = parts.getPart(PartSlot.WHEEL);
        CarPartModel part = partId == null ? EMPTY : get(partId);
        return new Wheel(
                part.model != null ? part.model : car.wheelModel(),
                part.texture != null ? part.texture : car.wheelTexture(),
                offsetOf(car, part, parts.getOffset(PartSlot.WHEEL)),
                scaleOf(car, part, parts.getWidth(PartSlot.WHEEL)),
                // 重ねる順は「プレイヤーの上書き → 部品（カーパック）の指定 → 車種の指定」。
                // パックが角度を書いていればそれが既定値になり、上書きを消せばそこへ戻る
                camberOf(car, part, parts.getCamber(PartSlot.WHEEL)),
                // 0 や負を書かれると拡大率が壊れる（見えなくなる／裏返る）ので既定へ落とす
                part.designRadius != null && part.designRadius > 0.0
                        ? part.designRadius : car.designWheelRadius(),
                // リムの半径はホイールのメッシュに付いた値なので、メッシュを差し替えた部品なら部品の値だけを見る
                part.model != null ? positive(part.rimRadius) : positive(car.wheelRimRadius()));
    }

    private static @Nullable Double positive(@Nullable Double value) {
        return value != null && value > 0.0 ? value : null;
    }

    /**
     * その車が履いているタイヤの見た目。<b>タイヤ部品を履いていなければ null</b>
     * （ホイールに付属のタイヤを描く。付属していなければ {@link #DEFAULT_TIRE}）。
     *
     * <p>知らない部品・見た目の定義が無い部品は {@link #DEFAULT_TIRE} にする。性能は部品の素性で
     * 決まっているので、見た目だけ付属のタイヤに戻すと「ダートタイヤなのに見た目はノーマル」になる。</p>
     */
    public static @Nullable Tire tireOf(PartFitment parts) {
        ResourceLocation partId = parts.getPart(PartSlot.TIRE);
        if (partId == null) {
            return null;
        }
        Tire tire = get(partId).tire;
        return tire != null ? tire : DEFAULT_TIRE;
    }

    /**
     * 実際に描くキャンバー角 [度]。
     *
     * <p><b>プレイヤーの上書きがいちばん強い。</b>無ければ部品の指定、それも無ければ車種の指定。
     * 上書きを消せばパックの値へ戻る、という関係をここ 1 か所で決めている。</p>
     */
    private static double camberOf(CarModel car, CarPartModel part, @Nullable Double override) {
        if (override != null) {
            return override;
        }
        return part.camber != null ? part.camber : car.camber();
    }

    /**
     * 実際に描く取り付け位置。<b>X（外向き）だけがオフセットで動く。</b>
     *
     * <p>上書きは<b>実車と同じ向きの mm</b>（小さいほど外）で来るので、ここで
     * 「外向きに何 m」へ直す。向きを裏返す場所をこの 1 か所に閉じ込めてある。</p>
     */
    private static CarModel.Vec3 offsetOf(CarModel car, CarPartModel part, @Nullable Double override) {
        CarModel.Vec3 base = part.offset != null ? part.offset : car.wheelOffset();
        return override == null ? base
                : new CarModel.Vec3(-override / 1000.0, base.y(), base.z());
    }

    /**
     * 実際に描く拡大率。<b>X（太さ）だけが上書きで動く。</b>
     *
     * <p>Y・Z（直径）には触らない——接地点は物理のタイヤ半径から決まっていて、
     * 見た目だけ大きくしても物理は知らないため。</p>
     */
    private static CarModel.Vec3 scaleOf(CarModel car, CarPartModel part, @Nullable Double override) {
        CarModel.Vec3 base = part.scale != null ? part.scale : car.wheelScale();
        return override == null ? base : new CarModel.Vec3(override, base.y(), base.z());
    }

    /** その部品が指定している太さの倍率。指定していなければ車種の値。<b>上書きの戻り先。</b> */
    public static double defaultWidth(CarModel car, PartFitment parts) {
        CarPartModel part = partOf(parts);
        return part.scale != null ? part.scale.x() : car.wheelScale().x();
    }

    /** その部品が指定しているキャンバー角。指定していなければ車種の値。<b>上書きの戻り先。</b> */
    public static double defaultCamber(CarModel car, PartFitment parts) {
        CarPartModel part = partOf(parts);
        return part.camber != null ? part.camber : car.camber();
    }

    /**
     * その部品が指定しているオフセット [mm]。指定していなければ車種の値。<b>上書きの戻り先。</b>
     *
     * <p>JSON は「外向きに何 m」で書くので、<b>実車と同じ向きの mm</b>へ直して返す。</p>
     */
    public static double defaultOffset(CarModel car, PartFitment parts) {
        CarPartModel part = partOf(parts);
        CarModel.Vec3 base = part.offset != null ? part.offset : car.wheelOffset();
        return -base.x() * 1000.0;
    }

    /** いま履いている部品の見た目の定義。履いていなければ「何も書かれていない」定義。 */
    private static CarPartModel partOf(PartFitment parts) {
        ResourceLocation partId = parts.getPart(PartSlot.WHEEL);
        return partId == null ? EMPTY : get(partId);
    }

    /** その部品の見た目の定義。まだ読んでいなければ読む。 */
    public static CarPartModel get(ResourceLocation partId) {
        return CACHE.computeIfAbsent(partId, CarPartModel::load);
    }

    /**
     * その部品の見た目の定義の置き場所。
     *
     * <p>{@code mypack:te37} → {@code mypack:car_parts/te37.json}。素性の
     * {@code data/mypack/car_parts/te37.json} と同じ id から引けるので、
     * カーパック側は 2 つのファイル名を揃えるだけでよい（車種とまったく同じ規約）。</p>
     */
    public static ResourceLocation locationOf(ResourceLocation partId) {
        return new ResourceLocation(partId.getNamespace(), "car_parts/" + partId.getPath() + ".json");
    }

    /** リソースの再読み込みで呼ぶ。 */
    public static void clearCache() {
        CACHE.clear();
    }

    // ------------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------------

    /**
     * 見た目の定義が無くても落とさない。
     *
     * <p><b>素性（{@code data/}）だけあって見た目（{@code assets/}）が無い部品</b>は、
     * 換装画面には並ぶが見た目が変わらない、という形で出る。片方だけ置いた
     * カーパックを黙って壊すよりログに残す方がよい。</p>
     */
    private static CarPartModel load(ResourceLocation partId) {
        ResourceLocation location = locationOf(partId);
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.warn("部品の見た目の定義が見つかりません（見た目は変わりません）: {}", location);
            return EMPTY;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            CarPartModel part = parse(GsonHelper.parse(reader), location);
            LOGGER.info("部品の見た目の定義を読み込みました: {}", location);
            return part;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("部品の見た目の定義の読み込みに失敗しました（見た目は変わりません）: {}",
                    location, e);
            return EMPTY;
        }
    }

    /**
     * 読み方は {@link CarModel} と共有する。書き方が 2 つに割れると、車の JSON では書ける形が
     * 部品の JSON では書けない、ということが起きる。
     *
     * <p>ブロック名は付ける場所と同じ（{@code "wheel"}）。<b>無ければ根をそのまま読む</b>ので、
     * 1 か所にしか付かない部品は入れ子を省ける（{@code CarTypeLoader} の {@code values} と同じ扱い）。</p>
     */
    private static CarPartModel parse(JsonObject root, ResourceLocation location) {
        // タイヤ部品は tire ブロックに書く（ホイールと違って根には書けない。根の model は
        // ホイールのメッシュとして読まれてきたので、意味を変えると既存のパックが壊れる）
        JsonObject tireBlock = CarModel.child(root, PartSlot.TIRE.getSerializedName());
        if (tireBlock.size() > 0) {
            JsonObject block = tireBlock;
            Tire tire = new Tire(
                    CarModel.location(block, "model", DEFAULT_TIRE.model()),
                    CarModel.location(block, "texture", DEFAULT_TIRE.texture()),
                    block.has("innerRadius") ? CarModel.number(block, "innerRadius", 0.0) : null,
                    block.has("outerRadius") ? CarModel.number(block, "outerRadius", 0.0) : null);
            return new CarPartModel(null, null, null, null, null, null, null, tire, null);
        }
        // マフラーは exhaust ブロックに書く（タイヤと同じく根には書けない）
        JsonObject exhaustBlock = CarModel.child(root, PartSlot.EXHAUST.getSerializedName());
        if (exhaustBlock.size() > 0) {
            return new CarPartModel(null, null, null, null, null, null, null, null,
                    parseExhaust(exhaustBlock, location));
        }
        JsonObject wheel = CarModel.child(root, PartSlot.WHEEL.getSerializedName());
        JsonObject block = wheel.size() > 0 ? wheel : root;

        return new CarPartModel(
                CarModel.location(block, "model", null),
                CarModel.location(block, "texture", null),
                // 換装画面で動かせる 3 つは、その範囲へ丸める（CarModel と同じ）
                block.has("offset")
                        ? CarModel.wheelOffsetInRange(location, CarModel.vector(block, "offset", CarModel.Vec3.ZERO))
                        : null,
                block.has("scale")
                        ? CarModel.wheelScaleInRange(location, CarModel.vector(block, "scale", CarModel.Vec3.ONE))
                        : null,
                block.has("camber")
                        ? CarModel.wheelCamberInRange(location, CarModel.number(block, "camber", 0.0))
                        : null,
                block.has("designRadius") ? CarModel.number(block, "designRadius", 0.0) : null,
                block.has("rimRadius") ? CarModel.number(block, "rimRadius", 0.0) : null,
                null, null);
    }

    /** 音程の倍率として読める範囲。外は丸めてログに残す。 */
    private static final double PITCH_MIN = 0.5;
    private static final double PITCH_MAX = 1.5;

    private static Exhaust parseExhaust(JsonObject block, ResourceLocation location) {
        double low = 1.0;
        double high = 1.0;
        if (block.has("pitch")) {
            var element = block.get("pitch");
            if (element.isJsonArray() && element.getAsJsonArray().size() == 2) {
                low = element.getAsJsonArray().get(0).getAsDouble();
                high = element.getAsJsonArray().get(1).getAsDouble();
            } else if (element.isJsonPrimitive()) {
                low = high = element.getAsDouble();
            } else {
                LOGGER.warn("{} の exhaust.pitch は数字か [最小, 最大] で書いてください（1 にします）", location);
            }
        }
        double min = clampPitch(location, Math.min(low, high));
        double max = clampPitch(location, Math.max(low, high));
        double volume = Math.max(0.0, Math.min(2.0, CarModel.number(block, "volume", 1.0)));
        double tone = Math.max(0.0, Math.min(1.0, CarModel.number(block, "tone", 0.0)));
        return new Exhaust(min, max, volume,
                CarModel.location(block, "sound", null), CarModel.location(block, "sound_high", null), tone);
    }

    private static double clampPitch(ResourceLocation location, double pitch) {
        double clamped = Math.max(PITCH_MIN, Math.min(PITCH_MAX, pitch));
        if (clamped != pitch) {
            LOGGER.warn("{} の exhaust.pitch = {} は範囲（{} 〜 {}）の外なので {} に丸めました",
                    location, pitch, PITCH_MIN, PITCH_MAX, clamped);
        }
        return clamped;
    }

}
