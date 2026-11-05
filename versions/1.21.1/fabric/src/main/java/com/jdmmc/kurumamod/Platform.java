package com.jdmmc.kurumamod;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * ローダーに聞かないと分からないこと（Fabric 版）。NeoForge 版（versions/1.21.1/neoforge/）にも同じ名前・同じメソッドの
 * このクラスがある。メソッドを足したら両方に足すこと。
 */
public final class Platform {

    private Platform() {
    }

    /** {@code config/} フォルダ。 */
    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** その MOD が入っているか。 */
    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }
}
