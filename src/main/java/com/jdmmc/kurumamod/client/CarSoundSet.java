package com.jdmmc.kurumamod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

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
 * <h2>無い名前を書いても黙って無音にはしない</h2>
 *
 * <p>バニラは知らないサウンドイベントを鳴らそうとすると何も鳴らさない。それでは
 * <b>「パックの音が鳴らない」のではなく「車が無音になる」</b>ので、見つからなければ
 * 既定の音に落としてログに 1 回だけ残す。</p>
 *
 * @param overrides 役割の名前（{@link #KEYS}）→ サウンドイベントの名前
 */
public record CarSoundSet(Map<String, ResourceLocation> overrides) {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String EXHAUST = "exhaust";
    public static final String EXHAUST_HIGH = "exhaust_high";
    public static final String SHIFT = "shift";
    public static final String CRASH = "crash";

    /**
     * 書ける名前の一覧。ループ音は {@link CarSound.Role} の名前の小文字、
     * 1 回だけ鳴る音が {@link #SHIFT} と {@link #CRASH}。
     */
    public static final Set<String> KEYS = Set.of(
            EXHAUST, EXHAUST_HIGH, "road", "wind", "slip", "slip_chatter", "gravel", "sand",
            SHIFT, CRASH);

    /** 何も差し替えない。 */
    public static final CarSoundSet NONE = new CarSoundSet(Map.of());

    /** 見つからなかったと一度報告した名前。毎ティック同じ警告を出さないため。 */
    private static final Set<ResourceLocation> REPORTED_MISSING = new HashSet<>();

    public CarSoundSet {
        overrides = Map.copyOf(overrides);
    }

    /**
     * その役割で鳴らす音。差し替えが無いか、書かれた名前が {@code sounds.json} に無ければ既定の音。
     */
    public SoundEvent resolve(String key, Supplier<SoundEvent> fallback) {
        ResourceLocation location = overrides.get(key);
        if (location == null) {
            return fallback.get();
        }
        if (Minecraft.getInstance().getSoundManager().getSoundEvent(location) == null) {
            if (REPORTED_MISSING.add(location)) {
                LOGGER.warn("車の音 {} に指定されたサウンドイベントが sounds.json にありません（既定の音を使います）: {}",
                        key, location);
            }
            return fallback.get();
        }
        // 範囲はサーバーが送る先を決めるためのもので、手元で鳴らす音には使われない
        return SoundEvent.createVariableRangeEvent(location);
    }

    /** 変速音。段が変わった瞬間に 1 回。 */
    public SoundEvent shift() {
        return resolve(SHIFT, KurumaSounds.CAR_SHIFT);
    }

    /** 衝突音。 */
    public SoundEvent crash() {
        return resolve(CRASH, KurumaSounds.CAR_CRASH);
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

    /** F3+T で音響エンジンが読み直されたら、見つからなかった名前も数え直す。 */
    public static void clearReports() {
        REPORTED_MISSING.clear();
    }

    /** {@code sounds} の中身を読む。知らない名前は警告して読み飛ばす。 */
    static CarSoundSet parse(JsonObject json) {
        if (json.size() == 0) {
            return NONE;
        }
        Map<String, ResourceLocation> overrides = new TreeMap<>();
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
            overrides.put(key, new ResourceLocation(entry.getValue().getAsString()));
        }
        return new CarSoundSet(overrides);
    }
}
