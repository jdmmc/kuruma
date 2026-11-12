package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.platform.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import com.jdmmc.kurumamod.client.sound.CarSoundSet;

/** core の {@link CarSoundSet}（車種ごとの音の差し替え）から、鳴らすサウンドイベントを引く。 */
public final class CarSoundEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<CarId> REPORTED_MISSING = new HashSet<>();

    private CarSoundEvents() {
    }

    /** その役割で鳴らす音。差し替えが無いか、書かれた名前が {@code sounds.json} に無ければ既定の音。 */
    public static SoundEvent resolve(CarSoundSet sounds, String key, Supplier<SoundEvent> fallback) {
        CarId location = sounds.override(key);
        if (location == null) {
            return fallback.get();
        }
        if (Minecraft.getInstance().getSoundManager().getSoundEvent(McIds.mc(location)) == null) {
            if (REPORTED_MISSING.add(location)) {
                LOGGER.warn("車の音 {} に指定されたサウンドイベントが sounds.json にありません（既定の音を使います）: {}",
                        key, location);
            }
            return fallback.get();
        }
        // 範囲はサーバーが送る先を決めるためのもので、手元で鳴らす音には使われない
        return SoundEvent.createVariableRangeEvent(McIds.mc(location));
    }

    /** 変速音。段が変わった瞬間に 1 回。 */
    public static SoundEvent shift(CarSoundSet sounds) {
        return resolve(sounds, CarSoundSet.SHIFT, KurumaSounds.CAR_SHIFT);
    }

    /** 衝突音。 */
    public static SoundEvent crash(CarSoundSet sounds) {
        return resolve(sounds, CarSoundSet.CRASH, KurumaSounds.CAR_CRASH);
    }

    /** ブローオフバルブの音。 */
    public static SoundEvent blowoff(CarSoundSet sounds) {
        return resolve(sounds, CarSoundSet.BLOWOFF, KurumaSounds.CAR_BLOWOFF);
    }

    /** F3+T で音響エンジンが読み直されたら、見つからなかった名前も数え直す。 */
    public static void clearReports() {
        REPORTED_MISSING.clear();
    }
}
