package com.jdmmc.kurumamod.sound;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.Registrar;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;

/**
 * 車の音。
 *
 * <p>音源は {@code assets/kurumamod/sounds/*.ogg}。<b>Minecraft は OGG Vorbis しか再生せず、
 * さらにステレオ音源には 3D 定位を掛けない</b>（距離減衰もパンも効かず、どこにいても同じ音量で
 * 鳴ってしまう）ので、すべてモノラルに落としてある。</p>
 *
 * <p>ループさせる 4 つは末尾から先頭へクロスフェードを掛けて継ぎ目を消してある。</p>
 */
public final class KurumaSounds {

    /**
     * サーバーがこの音を<b>どこまでのプレイヤーへ送るか</b> [ブロック]。
     *
     * <p><b>聞こえる範囲はここでは決まらない。</b>車の音はすべてクライアントが手元で鳴らして
     * いるので、この値はどこにも使われていない（サーバーから鳴らすことになったときのために、
     * いちばん遠くまで届く音に揃えてある）。実際の範囲は {@code SoundEngine#play} が
     * {@code max(音量, 1) × attenuation_distance} で決めていて、後者は {@code sounds.json} の
     * 項目（書かなければ 16）。音量は 1 を超えないので、<b>範囲を変えるなら sounds.json</b>。
     * 以前はここを 32 にして「32 ブロック届く」つもりでいたが、実際には 16 だった。</p>
     */
    private static final float RANGE = 64.0F;


    /**
     * 排気音（高回転側）。回転数でピッチ、アクセルで音量が変わる。
     *
     * <p><b>高回転側と 2 枚でクロスフェードする。</b>ピッチの倍率には 0.5〜2.0 の壁があるので、
     * 1 枚では 4 倍ぶんの音程しか作れない。<b>継ぎ足すのは上側</b>——1 オクターブ下の音源を
     * 足すとアイドルが元音源の 4 分の 1 の音程になって聞こえなくなる（実際にそうなった）。
     * アイドルの高さは 1 枚だった頃のまま、レブ側だけを伸ばす。</p>
     */
    public static final Supplier<SoundEvent> CAR_EXHAUST = register("car_exhaust");
    /** 排気音（高回転側）。{@code exhaust.ogg} を 1 オクターブ上へ縮めたもの。 */
    public static final Supplier<SoundEvent> CAR_EXHAUST_HIGH = register("car_exhaust_high");
    /**
     * 合成したエンジン音を作った回転数 [rpm]（{@code tools/make_engine.py} の {@code BANDS}）。
     *
     * <p>回転数ごとに素材を分けてあるのは、1 本の素材の音程を上げ下げすると排気管の響きまで
     * 一緒に動いて作り物っぽくなるから。隣どうしの比は 1.8 倍以内で、ピッチの壁（0.5〜2.0）の
     * 内側で入れ替えられる。</p>
     */
    public static final int[] ENGINE_BANDS = com.jdmmc.kurumamod.sound.EngineTypes.ENGINE_BANDS;
    /**
     * 合成したエンジン音の種類（{@code tools/make_engine.py} の {@code ENGINES}）。カーパックが
     * {@code sounds.engine.type} で選ぶ。
     *
     * <p>i4_turbo 直 4 ターボ（SR20DET を手本）、i4 直 4 NA、i6 直 6 ターボ、flat6 水平対向 6 NA、
     * v8 クロスプレーン V8、diesel4 直 4 ディーゼルターボ。</p>
     */
    public static final String[] ENGINE_TYPES = com.jdmmc.kurumamod.sound.EngineTypes.ENGINE_TYPES;
    /** 種類を書いていない車の音。 */
    public static final String DEFAULT_ENGINE_TYPE = com.jdmmc.kurumamod.sound.EngineTypes.DEFAULT_ENGINE_TYPE;
    /**
     * 合成したエンジン音。{@link #ENGINE_TYPES} ごとに、{@link #ENGINE_BANDS} の順に、抜いているとき・
     * 踏んでいるときの 2 本ずつ（{@link #engine}）。カーパックが {@code exhaust} を書いていない車はこちらを鳴らす。
     */
    private static final java.util.List<Supplier<SoundEvent>> CAR_ENGINE = registerEngine();
    /** ロードノイズ。車速と路面で変わる。 */
    public static final Supplier<SoundEvent> CAR_ROAD = register("car_road");
    /** 風切り音。車速で変わる。 */
    public static final Supplier<SoundEvent> CAR_WIND = register("car_wind");
    /** タイヤの悲鳴。滑っているときだけ鳴る。<b>舗装路の音</b>で、グリップに比例して鳴る。 */
    public static final Supplier<SoundEvent> CAR_SLIP = register("car_slip");
    /**
     * タイヤの悲鳴の刻み（キキキキ）。<b>滑り始めだけ</b>鳴らし、すぐ {@link #CAR_SLIP} の
     * 伸ばし（キィー）へ移す。ループなので 1 本に焼き込むと刻みが繰り返してしまう。
     */
    public static final Supplier<SoundEvent> CAR_SLIP_CHATTER = register("car_slip_chatter");
    /**
     * 砂利道の音。未舗装を走っている間ずっと鳴る。
     *
     * <p><b>スキール音の代わり</b>。ゴムが鳴くのは路面を掴んだまま滑るときなので、
     * 掴む相手のほうが持っていかれる未舗装では鳴らない。代わりに小石が弾かれる音が出る。</p>
     */
    public static final Supplier<SoundEvent> CAR_GRAVEL = register("car_gravel");
    /**
     * 砂の音。砂と雪の上で鳴る。
     *
     * <p>砂利と分けてあるのは、<b>粒の大きさで音がまるで違う</b>から——砂利は石が弾ける
     * 打撃音（重心 3973Hz・粒立ち 1.90）、砂は連続した衣擦れ（7727Hz・1.21）。
     * 1 枚で兼ねるとどちらでもない音になる。</p>
     */
    public static final Supplier<SoundEvent> CAR_SAND = register("car_sand");
    /** 変速音。段が変わった瞬間に 1 回。 */
    public static final Supplier<SoundEvent> CAR_SHIFT = register("car_shift");
    /** ターボの過給音（ヒュイーン）。ブーストで音程と音量が上がるループ。 */
    public static final Supplier<SoundEvent> CAR_TURBO = register("car_turbo");
    /** ブローオフバルブ（プシュッ）。過給しているときにアクセルを抜いた瞬間と、シフトアップで 1 回。 */
    public static final Supplier<SoundEvent> CAR_BLOWOFF = register("car_blowoff");
    /** 衝突音。ぶつかった速度差で音量とピッチが変わる。 */
    public static final Supplier<SoundEvent> CAR_CRASH = register("car_crash");

    /**
     * スタート信号が 1 つ点く音。
     *
     * <p>レース系の 3 つは<b>画面の音</b>なので、位置を持たせずに鳴らす
     * （{@code SimpleSoundInstance.forUI}）。ここで指定している距離は使われないが、
     * 登録の仕方を車の音と揃えてある。</p>
     */
    public static final Supplier<SoundEvent> RACE_LIGHT = register("race_light");
    /** 消灯＝号砲。点灯音の 1 オクターブ上。 */
    public static final Supplier<SoundEvent> RACE_START = register("race_start");
    /** チェックポイント通過。ベストとの差でピッチが変わる。 */
    public static final Supplier<SoundEvent> RACE_SPLIT = register("race_split");
    /**
     * ゴール。上がっていく 3 音。
     *
     * <p><b>区間の通過音と同じにしてはいけない</b>——「もう 1 周あるのか終わったのか」が
     * 耳で区別できなくなる。</p>
     */
    public static final Supplier<SoundEvent> RACE_FINISH = register("race_finish");

    private KurumaSounds() {
    }

    /**
     * このクラスを読み込ませて、上の音をすべて登録の受付へ出す。<b>登録が締め切られる前に呼ぶこと</b>
     * （NeoForge 版は {@code Kurumamod} のコンストラクタ、Fabric 版は {@code onInitialize}）。
     */
    public static void init() {
    }

    /**
     * 種類 {@code type} の、回転数 {@code ENGINE_BANDS[band]} の、抜いているとき（{@code load} が false）か
     * 踏んでいるときのエンジン音。知らない種類なら既定の種類。
     */
    public static SoundEvent engine(String type, int band, boolean load) {
        int kind = 0;
        for (int i = 0; i < ENGINE_TYPES.length; i++) {
            if (ENGINE_TYPES[i].equals(type)) {
                kind = i;
            }
        }
        return CAR_ENGINE.get((kind * ENGINE_BANDS.length + band) * 2 + (load ? 1 : 0)).get();
    }

    private static java.util.List<Supplier<SoundEvent>> registerEngine() {
        java.util.List<Supplier<SoundEvent>> sounds = new java.util.ArrayList<>();
        for (String type : ENGINE_TYPES) {
            for (int rpm : ENGINE_BANDS) {
                sounds.add(register("car_engine_" + type + "_" + rpm + "_lift"));
                sounds.add(register("car_engine_" + type + "_" + rpm + "_load"));
            }
        }
        return java.util.List.copyOf(sounds);
    }

    private static Supplier<SoundEvent> register(String name) {
        return Registrar.register(Registries.SOUND_EVENT, name,
                () -> SoundEvent.createFixedRangeEvent(ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, name), RANGE));
    }
}
