package com.jdmmc.kurumamod.sound;

/** 合成したエンジン音の種類（カーパックの {@code sounds.engine.type} に書く名前）。音そのものは版の {@code KurumaSounds}。 */
public final class EngineTypes {

    public static final String[] ENGINE_TYPES = {"i4_turbo", "i4", "i6", "flat6", "v8", "diesel4"};

    public static final String DEFAULT_ENGINE_TYPE = "i4_turbo";

    /** 合成したエンジン音を作った回転数 [rpm]。この順に {@code ENGINE_0_*} 〜 {@code ENGINE_4_*}。 */
    public static final int[] ENGINE_BANDS = {900, 1600, 2800, 4500, 7000};

    private EngineTypes() {
    }
}
