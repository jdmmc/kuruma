package com.jdmmc.kurumamod.client.sound;

import com.jdmmc.kurumamod.car.CarId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.sound.EngineTypes;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import com.jdmmc.kurumamod.client.model.CarModel;
import com.jdmmc.kurumamod.client.model.CarPartModel;

/**
 * 車種ごとの音の差し替え。{@code vehicles/<car>.json} の {@code sounds} に書く。
 *
 * <pre>{@code
 * "sounds": {
 *   "exhaust": "mypack:kei_exhaust",
 *   "exhaust_high": "mypack:kei_exhaust_high"
 * }
 * }</pre>
 *
 * <p>値は<b>サウンドイベントの名前</b>（カーパック自身の {@code assets/<pack>/sounds.json} に
 * 書いた項目）であって、ogg のパスではない。{@code sounds.json} はどの名前空間のものも
 * バニラが読むので、<b>レジストリに登録しなくても手元で鳴らせる</b>——車の音はすべて
 * クライアントが手元で鳴らしているので、サーバーはこの音を知らなくてよい。届く距離
 * （{@code attenuation_distance}）も、パック側の {@code sounds.json} が決める。</p>
 *
 * <p><b>書かなかった音は KurumaMod の音のまま。</b>排気音だけ変えたい車に、風切り音まで
 * 用意させないため。</p>
 *
 * <h2>{@code exhaust_high} は {@code exhaust} の 1 オクターブ上</h2>
 *
 * <p>ピッチには 0.5〜2.0 の壁があるので、排気音は 2 枚をクロスフェードして音程の幅を稼いでいる
 * （{@link CarSound}）。高回転側は<b>同じ音源を 1 オクターブ上へ縮めたもの</b>という前提で、
 * 半分のピッチで鳴らす。</p>
 *
 * <p><b>{@code exhaust} だけ書いて {@code exhaust_high} を書かなかったら、高回転側にも
 * 同じ音源を使い、半分にはしない</b>（{@link #exhaustHighIsOctaveUp()} が false）。
 * 既定の高回転側（KurumaMod のエンジン）を残すと、回すと別の車の音に化ける。代わりに
 * 音程が 2.0 で頭打ちになり、レブ付近では上がりきらない（アイドルの約 3 倍まで）。</p>
 *
 * <h2>音源を用意しなくても、数字で変えられる</h2>
 *
 * <p>値には文字列（音源の差し替え）の代わりにオブジェクトも書ける。<b>既定の音の高さを変えるだけ</b>
 * なら、音源を作らなくてよい。</p>
 *
 * <pre>{@code
 * "sounds": {
 *   "engine": { "type": "flat6", "pitch": 0.95, "load": 0.8 },
 *   "turbo":  { "pitch": 1.2 },
 *   "shift":  { "sound": "mypack:dog_box", "pitch": 0.9 }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code pitch} … 高さの倍率（0.5〜2.0）。エンジンなら回転数に対する高さをまるごとずらす</li>
 *   <li>{@code sound} … 音源の差し替え（文字列で書くのと同じ）</li>
 *   <li>{@code engine} だけ: {@code type}（合成したエンジン音の種類。{@link EngineTypes#ENGINE_TYPES}）と
 *       {@code load}（踏んだときの荒い音がどれだけ前に出るか。0〜1.5、既定 1）。<b>音源の差し替えは
 *       {@code exhaust} の役目</b>で、{@code exhaust} を書くと合成したエンジン音は鳴らない</li>
 * </ul>
 *
 * <p><b>大きさは書けない。</b>音量は聞く人が決めるもの（H の画面の「サウンド」で種類ごとに変える）で、
 * パックが車ごとに大きくしたり小さくしたりすると、その好みが車によって狂う。{@code volume} を書いても
 * 読まずにログへ残す。</p>
 *
 * <p>{@code slip_chatter} と {@code exhaust_high} は、書かなければ {@code slip}・{@code exhaust} の
 * 高さを引き継ぐ（同じ音の 2 本なので、片方だけ変わると継ぎ目で音が跳ねる）。</p>
 *
 * <h2>無い名前を書いても黙って無音にはしない</h2>
 *
 * <p>バニラは知らないサウンドイベントを鳴らそうとすると何も鳴らさない。それでは
 * <b>「パックの音が鳴らない」のではなく「車が無音になる」</b>ので、見つからなければ
 * 既定の音に落としてログに 1 回だけ残す。</p>
 *
 * @param overrides  役割の名前（{@link #KEYS}）→ サウンドイベントの名前
 * @param tuning     役割の名前 → 高さの倍率など（書いたものだけ）
 * @param engineType 合成したエンジン音の種類（{@link EngineTypes#ENGINE_TYPES}）
 */
public record CarSoundSet(Map<String, CarId> overrides, Map<String, Tuning> tuning, String engineType) {

    /**
     * 既定の音の高さの倍率と、踏んだときの荒い音の出方（エンジンだけ）。大きさは持たない（聞く人が決める）。
     *
     * @param pitch 高さの倍率
     * @param load  踏んだときの荒い音の出方（エンジンだけ）
     */
    public record Tuning(double pitch, double load) {
        public static final Tuning DEFAULT = new Tuning(1.0, 1.0);
    }

    /** 合成したエンジン音の種類・高さ・踏んだときの出方を書くキー。 */
    public static final String ENGINE = "engine";

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CarSoundSet.class);

    public static final String EXHAUST = "exhaust";
    public static final String EXHAUST_HIGH = "exhaust_high";
    public static final String SHIFT = "shift";
    public static final String CRASH = "crash";
    /**
     * ターボの過給音の差し替え。<b>ターボが付いているかどうかは音ではなく部品</b>
     * （{@code car_parts} の slot {@code "turbo"}）で決まる。
     */
    public static final String TURBO = "turbo";
    /** ブローオフバルブの音（1 回だけ鳴る）。 */
    public static final String BLOWOFF = "blowoff";

    /**
     * 書ける名前の一覧。ループ音は {@link CarSound.Role} の名前の小文字、
     * 1 回だけ鳴る音が {@link #SHIFT} と {@link #CRASH}。
     */
    public static final Set<String> KEYS = Set.of(
            ENGINE, EXHAUST, EXHAUST_HIGH, "road", "wind", "slip", "slip_chatter", "gravel", "sand",
            TURBO, SHIFT, CRASH, BLOWOFF);

    /** 書かなかったときの高さを引き継ぐ相手。同じ音の 2 本なので揃える。 */
    private static final Map<String, String> TUNING_PARENT = Map.of(
            "slip_chatter", "slip",
            EXHAUST_HIGH, EXHAUST);

    /** 何も差し替えない。 */
    public static final CarSoundSet NONE = new CarSoundSet(Map.of(), Map.of(), EngineTypes.DEFAULT_ENGINE_TYPE);

    /** 見つからなかったと一度報告した名前。毎ティック同じ警告を出さないため。 */

    public CarSoundSet {
        overrides = Map.copyOf(overrides);
        tuning = Map.copyOf(tuning);
    }

    /** その役割の高さの倍率など。書いていなければ引き継ぐ相手（{@link #TUNING_PARENT}）か 1 倍。 */
    public Tuning tuningOf(String key) {
        Tuning own = tuning.get(key);
        if (own != null) {
            return own;
        }
        String parent = TUNING_PARENT.get(key);
        return parent != null ? tuning.getOrDefault(parent, Tuning.DEFAULT) : Tuning.DEFAULT;
    }

    /** その役割の差し替えの音（サウンドイベントの名前）。無ければ null。鳴らすのは版の {@code CarSoundEvents}。 */
    public CarId override(String key) {
        return overrides.get(key);
    }

    /**
     * マフラーの音源を重ねた音の組。マフラーが音源を差し替えていなければ自分のまま。
     *
     * <p>高回転側だけ書かれていない場合は、高回転側の差し替えを消す（{@link #exhaustHighIsOctaveUp()} が
     * false になり、低回転側の音源を同じ音程で鳴らす）。車の高回転側が残ると、回したときに別の車の
     * 音に化ける。</p>
     */
    public CarSoundSet withExhaust(@org.jetbrains.annotations.Nullable CarPartModel.Exhaust exhaust) {
        if (exhaust == null || exhaust.sound() == null) {
            return this;
        }
        Map<String, CarId> merged = new java.util.HashMap<>(overrides);
        merged.put(EXHAUST, exhaust.sound());
        if (exhaust.soundHigh() != null) {
            merged.put(EXHAUST_HIGH, exhaust.soundHigh());
        } else {
            merged.remove(EXHAUST_HIGH);
        }
        return new CarSoundSet(merged, tuning, engineType);
    }

    /**
     * 高回転側の音源が低回転側の 1 オクターブ上か。
     *
     * <p>どちらも既定のまま、または両方書かれていれば true。排気音だけ差し替えた
     * ときだけ false になり、高回転側も同じ音源を同じ音程で鳴らす。</p>
     */
    public boolean exhaustHighIsOctaveUp() {
        return !overrides.containsKey(EXHAUST) || overrides.containsKey(EXHAUST_HIGH);
    }

    /**
     * 合成したエンジン音（{@code CarSound.Role#ENGINE_0_LIFT} など）を鳴らすか。
     *
     * <p>パックかマフラーの部品が {@link #EXHAUST} を書いていればその音を鳴らし（以前と同じ 2 本の
     * クロスフェード）、書いていなければ合成したエンジン音を鳴らす。{@link #EXHAUST_HIGH} だけ書いても
     * 低回転側が無いので使わない。</p>
     */
    public boolean usesEngine() {
        return !overrides.containsKey(EXHAUST);
    }

    /**
     * 音源が同じか（差し替えと、合成したエンジン音の種類）。<b>高さと大きさは見ない</b>——
     * そちらは鳴っている音のまま毎ティック読み直せるので、変わっても鳴らし直さなくてよい。
     */
    public boolean sameSources(CarSoundSet other) {
        return overrides.equals(other.overrides) && engineType.equals(other.engineType);
    }

    /** その役割の高さと大きさだけを差し替えた組。 */
    public CarSoundSet withTuning(String key, Tuning value) {
        Map<String, Tuning> next = new TreeMap<>(tuning);
        next.put(key, value);
        return new CarSoundSet(overrides, next, engineType);
    }

    /** 合成したエンジン音の種類だけを差し替えた組。 */
    public CarSoundSet withEngineType(String type) {
        return new CarSoundSet(overrides, tuning, type);
    }

    /**
     * デバッグ画面（{@code SoundDebugScreen}）で動かしている値。車種ごと・<b>このクライアントだけ・
     * ゲームを閉じるまで</b>（座席の {@code CarSeat#setOverride} と同じ）。
     */
    private static final Map<CarId, CarSoundSet> DEBUG_OVERRIDES = new java.util.HashMap<>();

    /** その車種で鳴らす音の組。デバッグ画面で動かしていればそちら、でなければカーパックの定義。 */
    public static CarSoundSet of(CarId carId) {
        CarSoundSet override = DEBUG_OVERRIDES.get(carId);
        return override != null ? override : CarModel.get(carId).sounds();
    }

    public static void setDebugOverride(CarId carId, CarSoundSet sounds) {
        DEBUG_OVERRIDES.put(carId, sounds);
    }

    public static void clearDebugOverride(CarId carId) {
        DEBUG_OVERRIDES.remove(carId);
    }

    /**
     * カーパックの {@code vehicles/<車>.json} へそのまま貼れる {@code "sounds"} の中身。
     * 既定のままの値は書かない。
     */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        Set<String> keys = new TreeSet<>(overrides.keySet());
        keys.addAll(tuning.keySet());
        if (!EngineTypes.DEFAULT_ENGINE_TYPE.equals(engineType)) {
            keys.add(ENGINE);
        }
        for (String key : keys) {
            CarId sound = overrides.get(key);
            Tuning own = tuning.get(key);
            boolean engineKey = ENGINE.equals(key);
            if (!engineKey && sound != null && (own == null || own.equals(Tuning.DEFAULT))) {
                out.addProperty(key, sound.toString());
                continue;
            }
            JsonObject entry = new JsonObject();
            if (engineKey && !EngineTypes.DEFAULT_ENGINE_TYPE.equals(engineType)) {
                entry.addProperty("type", engineType);
            }
            if (sound != null) {
                entry.addProperty("sound", sound.toString());
            }
            if (own != null) {
                putIfChanged(entry, "pitch", own.pitch());
                if (engineKey) {
                    putIfChanged(entry, "load", own.load());
                }
            }
            if (entry.size() > 0) {
                out.add(key, entry);
            }
        }
        return out;
    }

    private static void putIfChanged(JsonObject entry, String name, double value) {
        if (Math.abs(value - 1.0) > 1e-9) {
            entry.addProperty(name, Math.round(value * 100.0) / 100.0);
        }
    }

    /** {@code sounds} の中身を読む。知らない名前は警告して読み飛ばす。 */
    public static CarSoundSet parse(JsonObject json) {
        if (json.size() == 0) {
            return NONE;
        }
        Map<String, CarId> overrides = new TreeMap<>();
        Map<String, Tuning> tuning = new TreeMap<>();
        String engineType = EngineTypes.DEFAULT_ENGINE_TYPE;
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")) {
                continue;
            }
            if (!KEYS.contains(key)) {
                LOGGER.warn("sounds に知らない名前があります（読み飛ばします）: {}。書けるのは {}",
                        key, new TreeSet<>(KEYS));
                continue;
            }
            JsonElement value = entry.getValue();
            // 文字列なら音源の差し替え（engine だけは種類の名前）
            if (!value.isJsonObject()) {
                if (ENGINE.equals(key)) {
                    engineType = engineTypeOf(value.getAsString());
                } else {
                    overrides.put(key, CarId.parseStrict(value.getAsString()));
                }
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            if (object.has("sound")) {
                if (ENGINE.equals(key)) {
                    LOGGER.warn("sounds.engine に sound は書けません（音源の差し替えは exhaust に書きます）");
                } else {
                    overrides.put(key, CarId.parseStrict(object.get("sound").getAsString()));
                }
            }
            if (ENGINE.equals(key) && object.has("type")) {
                engineType = engineTypeOf(object.get("type").getAsString());
            }
            if (object.has("volume")) {
                LOGGER.warn("sounds.{} の volume は書けません（音量は各プレイヤーが H の画面で決めます）。読み飛ばします", key);
            }
            tuning.put(key, new Tuning(
                    number(object, "pitch", 0.5, 2.0),
                    number(object, "load", 0.0, 1.5)));
        }
        return new CarSoundSet(overrides, tuning, engineType);
    }

    /** 数を読んで範囲の端へ丸める。書いていなければ 1 倍。 */
    private static double number(JsonObject object, String name, double min, double max) {
        if (!object.has(name)) {
            return 1.0;
        }
        double value = object.get(name).getAsDouble();
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        double clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            LOGGER.warn("sounds の {} が範囲（{}〜{}）の外です。{} に丸めます", name, min, max, clamped);
        }
        return clamped;
    }

    /** 知らない種類なら既定の種類へ落として知らせる（黙って無音にはしない）。 */
    private static String engineTypeOf(String name) {
        for (String type : EngineTypes.ENGINE_TYPES) {
            if (type.equals(name)) {
                return type;
            }
        }
        LOGGER.warn("sounds.engine の type に知らない種類があります（{} を使います）: {}。書けるのは {}",
                EngineTypes.DEFAULT_ENGINE_TYPE, name, java.util.Arrays.toString(EngineTypes.ENGINE_TYPES));
        return EngineTypes.DEFAULT_ENGINE_TYPE;
    }
}
