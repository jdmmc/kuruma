package com.jdmmc.kurumamod;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * ローダーに聞かないと分からないこと（NeoForge 版）。
 *
 * <p><b>Fabric 版（versions/26.3/fabric/）にも同じ名前・同じメソッドのこのクラスがある。</b>versions/26.3/common/ の共有コードは
 * どちらにも同じように呼ぶので、メソッドを足したら両方に足すこと。</p>
 */
public final class Platform {

    private Platform() {
    }

    /** {@code config/} フォルダ。 */
    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** その MOD が入っているか。起動の早い段階（MOD の一覧ができる前）には呼ばないこと。 */
    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}
