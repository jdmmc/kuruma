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
 * 描画側が諸元のタイヤ半径へ正規化する
 * （＝調整画面でタイヤ半径を変えても部品が追従する）。<b>ゲージどおりに作ったなら書かなくてよい</b>
 * （{@link CarModel#GAUGE_WHEEL_RADIUS}）。メッシュを持たない部品は車のメッシュを描くので、車の値を引き継ぐ。</p>
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

    /**
     * ホイールの純正。<b>車種が {@code default_parts.wheel} を書かなかったときはこれが付いているものとして描く。</b>
     * 車の見た目の JSON がホイールのメッシュを直接書いている（以前の書き方）なら、そちらが純正。
     */
    public static final ResourceLocation STOCK_WHEEL = new ResourceLocation(com.jdmmc.kurumamod.Kurumamod.MODID, "te37");

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
    public static Wheel wheelOf(ResourceLocation carId, CarModel car, PartFitment parts) {
        Layers layers = layers(carId, car, parts);
        CarPartModel mesh = layers.mesh();
        return new Wheel(
                mesh != null ? mesh.model : car.wheelModel(),
                mesh != null && mesh.texture != null ? mesh.texture : car.wheelTexture(),
                offsetOf(car, layers, parts.getOffset(PartSlot.WHEEL)),
                scaleOf(car, layers, parts.getWidth(PartSlot.WHEEL)),
                // 重ねる順は「プレイヤーの上書き → 履いた部品 → 純正の部品 → 車種の指定」。
                // パックが角度を書いていればそれが既定値になり、上書きを消せばそこへ戻る
                camberOf(car, layers, parts.getCamber(PartSlot.WHEEL)),
                // 大きさとリムの半径はメッシュに付いた値なので、描くメッシュの持ち主の値だけを見る
                mesh != null ? designRadiusOf(mesh) : car.wheelDesignRadius(),
                mesh != null ? positive(mesh.rimRadius) : positive(car.wheelRimRadius()));
    }

    /**
     * ホイールの見た目の重なり。<b>履いた部品 → 純正の部品 → 車</b>の順に、書いてある値が勝つ。
     *
     * <p>純正の部品を挟むのは、<b>メッシュを持たない部品（角度と太さだけの {@code deep_dish}）が
     * 純正のメッシュを描く</b>ため。車の値まで落とすと、{@code default_parts.wheel} で自分のホイールを
     * 純正にした車でも TE37 が描かれる。</p>
     *
     * @param fitted 履いた部品。何も履いていない・純正と同じなら空
     * @param stock  純正の部品。無ければ（ホイールを直接書いた以前の車）空
     */
    private record Layers(CarPartModel fitted, CarPartModel stock, @Nullable ResourceLocation meshOwner) {

        /** 描くメッシュを持っている定義。どちらも持っていなければ null（車の JSON のメッシュを描く）。 */
        @Nullable
        CarPartModel mesh() {
            return fitted.model != null ? fitted : stock.model != null ? stock : null;
        }
    }

    private static Layers layers(ResourceLocation carId, CarModel car, PartFitment parts) {
        ResourceLocation stockId = stockWheel(carId, car);
        CarPartModel stock = stockId == null ? EMPTY : get(stockId);
        ResourceLocation fittedId = parts.getPart(PartSlot.WHEEL);
        // 初期装備は装着状態へ解決済みで来るので、純正と同じなら重ねない
        boolean ownFitted = fittedId != null && !fittedId.equals(stockId) && !PartFitment.isNone(fittedId);
        CarPartModel fitted = ownFitted ? get(fittedId) : EMPTY;
        ResourceLocation owner = fitted.model != null ? fittedId : stock.model != null ? stockId : null;
        return new Layers(fitted, stock, owner);
    }

    /**
     * その車の純正ホイール。諸元の {@code default_parts.wheel}、無ければ {@link #STOCK_WHEEL}。
     * 車の見た目の JSON がメッシュを直接書いている（以前の書き方）なら、そちらが純正なので null。
     */
    @Nullable
    public static ResourceLocation stockWheel(ResourceLocation carId, CarModel car) {
        com.jdmmc.kurumamod.car.CarType.DefaultPart defaults =
                com.jdmmc.kurumamod.car.CarTypes.get(carId).defaultPart(PartSlot.WHEEL);
        if (defaults != null) {
            return defaults.part();
        }
        return usesStockWheel(car) ? STOCK_WHEEL : null;
    }

    /**
     * いま描いているホイールのメッシュを持つ部品（座席の調整画面が大きさとリムの半径を動かす相手）。
     * 車の JSON のメッシュを描いているなら null。
     */
    @Nullable
    public static ResourceLocation meshOwner(ResourceLocation carId, CarModel car, PartFitment parts) {
        return layers(carId, car, parts).meshOwner();
    }

    /**
     * 描くメッシュが何 m で作られているか。<b>大きさはメッシュに付いた値</b>なので、メッシュの持ち主の値だけを見る
     * （書いていなければゲージの値。車の値は車のメッシュの大きさで、部品のメッシュとは関係ない）。
     * 0 や負を書かれると拡大率が壊れる（見えなくなる／裏返る）ので書かなかったことにする。
     */
    private static double designRadiusOf(CarPartModel mesh) {
        Double written = positive(mesh.designRadius);
        return written != null ? written : CarModel.GAUGE_WHEEL_RADIUS;
    }

    /** 車の見た目の JSON がホイールのメッシュを直接書いていない（＝純正は {@link #STOCK_WHEEL}）か。 */
    public static boolean usesStockWheel(CarModel car) {
        return CarModel.DEFAULT_WHEEL_MODEL.equals(car.wheelModel());
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
    private static double camberOf(CarModel car, Layers layers, @Nullable Double override) {
        if (override != null) {
            return override;
        }
        return layers.fitted().camber != null ? layers.fitted().camber
                : layers.stock().camber != null ? layers.stock().camber : car.camber();
    }

    /**
     * 実際に描く取り付け位置。<b>X（外向き）だけがオフセットで動く。</b>
     *
     * <p>上書きは<b>実車と同じ向きの mm</b>（小さいほど外）で来るので、ここで
     * 「外向きに何 m」へ直す。向きを裏返す場所をこの 1 か所に閉じ込めてある。</p>
     */
    private static CarModel.Vec3 offsetOf(CarModel car, Layers layers, @Nullable Double override) {
        CarModel.Vec3 base = baseOffset(car, layers);
        return override == null ? base
                : new CarModel.Vec3(-override / 1000.0, base.y(), base.z());
    }

    /**
     * 実際に描く拡大率。<b>X（太さ）だけが上書きで動く。</b>
     *
     * <p>Y・Z（直径）には触らない——接地点は物理のタイヤ半径から決まっていて、
     * 見た目だけ大きくしても物理は知らないため。</p>
     */
    private static CarModel.Vec3 scaleOf(CarModel car, Layers layers, @Nullable Double override) {
        CarModel.Vec3 base = baseScale(car, layers);
        return override == null ? base : new CarModel.Vec3(override, base.y(), base.z());
    }

    private static CarModel.Vec3 baseOffset(CarModel car, Layers layers) {
        return layers.fitted().offset != null ? layers.fitted().offset
                : layers.stock().offset != null ? layers.stock().offset : car.wheelOffset();
    }

    private static CarModel.Vec3 baseScale(CarModel car, Layers layers) {
        return layers.fitted().scale != null ? layers.fitted().scale
                : layers.stock().scale != null ? layers.stock().scale : car.wheelScale();
    }

    /** 部品が指定している太さの倍率。指定していなければ車種の値。<b>上書きの戻り先。</b> */
    public static double defaultWidth(ResourceLocation carId, CarModel car, PartFitment parts) {
        return baseScale(car, layers(carId, car, parts)).x();
    }

    /** 部品が指定しているキャンバー角。指定していなければ車種の値。<b>上書きの戻り先。</b> */
    public static double defaultCamber(ResourceLocation carId, CarModel car, PartFitment parts) {
        return camberOf(car, layers(carId, car, parts), null);
    }

    /**
     * その部品が指定しているオフセット [mm]。指定していなければ車種の値。<b>上書きの戻り先。</b>
     *
     * <p>JSON は「外向きに何 m」で書くので、<b>実車と同じ向きの mm</b>へ直して返す。</p>
     */
    public static double defaultOffset(ResourceLocation carId, CarModel car, PartFitment parts) {
        return -baseOffset(car, layers(carId, car, parts)).x() * 1000.0;
    }

    /** その部品の見た目の定義。まだ読んでいなければ読む。座席の調整画面で動かした大きさがあれば被せる。 */
    public static CarPartModel get(ResourceLocation partId) {
        CarPartModel model = fromJson(partId);
        MeshOverride mesh = MESH_OVERRIDES.get(partId);
        if (mesh == null) {
            return model;
        }
        // 太さは X だけ。Y・Z（直径）は接地が崩れるので書いてあったまま
        CarModel.Vec3 scale = mesh.width() == null ? model.scale
                : new CarModel.Vec3(mesh.width(), model.scale != null ? model.scale.y() : 1.0,
                        model.scale != null ? model.scale.z() : 1.0);
        return new CarPartModel(model.model, model.texture, model.offset, scale,
                mesh.camber(), mesh.designRadius(), mesh.rimRadius(), model.tire, model.exhaust);
    }

    /** JSON に書かれたままの定義（座席の調整画面で動かした値を被せる前）。 */
    public static CarPartModel fromJson(ResourceLocation partId) {
        return CACHE.computeIfAbsent(partId, CarPartModel::load);
    }

    /**
     * ホイールの JSON の値の上書き（座席の調整画面の 3 ページ目）。
     *
     * @param camber    キャンバー角の既定値 [度]。null なら書いていない（車の値へ落ちる）
     * @param width     太さの既定値（{@code scale} の X）。null なら書いていない
     * @param rimRadius null なら測る
     */
    public record MeshOverride(@Nullable Double camber, @Nullable Double width,
                               double designRadius, @Nullable Double rimRadius) {
    }

    /**
     * 部品ごとの上書き。<b>このクライアントだけ・ゲームを閉じるまで</b>（座席の上書きと同じ。決まった値は JSON へ書く）。
     * F3+T で JSON を読み直しても残る。
     */
    private static final Map<ResourceLocation, MeshOverride> MESH_OVERRIDES = new HashMap<>();

    public static void setMeshOverride(ResourceLocation partId, MeshOverride mesh) {
        MESH_OVERRIDES.put(partId, mesh);
    }

    /** JSON の値へ戻す。 */
    public static void clearMeshOverride(ResourceLocation partId) {
        MESH_OVERRIDES.remove(partId);
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
