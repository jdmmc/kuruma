package com.jdmmc.kurumamod.sound;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

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
     * 音が届く距離 [ブロック]。
     *
     * <p>既定の {@code createVariableRangeEvent} は 16 ブロックで、車には狭い。
     * 音量から距離を決める仕組みなので（{@code volume > 1 ? 16*volume : 16}）、
     * 音量が刻々と変わるこの用途では距離まで揺れてしまう。距離は固定で持つ。</p>
     */
    private static final float RANGE = 32.0F;

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Kurumamod.MODID);

    /**
     * 排気音（高回転側）。回転数でピッチ、アクセルで音量が変わる。
     *
     * <p><b>高回転側と 2 枚でクロスフェードする。</b>ピッチの倍率には 0.5〜2.0 の壁があるので、
     * 1 枚では 4 倍ぶんの音程しか作れない。<b>継ぎ足すのは上側</b>——1 オクターブ下の音源を
     * 足すとアイドルが元音源の 4 分の 1 の音程になって聞こえなくなる（実際にそうなった）。
     * アイドルの高さは 1 枚だった頃のまま、レブ側だけを伸ばす。</p>
     */
    public static final RegistryObject<SoundEvent> CAR_EXHAUST = register("car_exhaust");
    /** 排気音（高回転側）。{@code exhaust.ogg} を 1 オクターブ上へ縮めたもの。 */
    public static final RegistryObject<SoundEvent> CAR_EXHAUST_HIGH = register("car_exhaust_high");
    /** ロードノイズ。車速と路面で変わる。 */
    public static final RegistryObject<SoundEvent> CAR_ROAD = register("car_road");
    /** 風切り音。車速で変わる。 */
    public static final RegistryObject<SoundEvent> CAR_WIND = register("car_wind");
    /** タイヤの悲鳴。滑っているときだけ鳴る。 */
    public static final RegistryObject<SoundEvent> CAR_SLIP = register("car_slip");
    /** 変速音。段が変わった瞬間に 1 回。 */
    public static final RegistryObject<SoundEvent> CAR_SHIFT = register("car_shift");
    /** 衝突音。ぶつかった速度差で音量とピッチが変わる。 */
    public static final RegistryObject<SoundEvent> CAR_CRASH = register("car_crash");

    /**
     * スタート信号が 1 つ点く音。
     *
     * <p>レース系の 3 つは<b>画面の音</b>なので、位置を持たせずに鳴らす
     * （{@code SimpleSoundInstance.forUI}）。ここで指定している距離は使われないが、
     * 登録の仕方を車の音と揃えてある。</p>
     */
    public static final RegistryObject<SoundEvent> RACE_LIGHT = register("race_light");
    /** 消灯＝号砲。点灯音の 1 オクターブ上。 */
    public static final RegistryObject<SoundEvent> RACE_START = register("race_start");
    /** チェックポイント通過。ベストとの差でピッチが変わる。 */
    public static final RegistryObject<SoundEvent> RACE_SPLIT = register("race_split");
    /**
     * ゴール。上がっていく 3 音。
     *
     * <p><b>区間の通過音と同じにしてはいけない</b>——「もう 1 周あるのか終わったのか」が
     * 耳で区別できなくなる。</p>
     */
    public static final RegistryObject<SoundEvent> RACE_FINISH = register("race_finish");

    private KurumaSounds() {
    }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createFixedRangeEvent(new ResourceLocation(Kurumamod.MODID, name), RANGE));
    }
}
