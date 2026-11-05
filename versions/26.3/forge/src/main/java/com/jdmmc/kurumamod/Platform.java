package com.jdmmc.kurumamod;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * ローダーに聞かないと分からないこと（Forge 版）。
 *
 * <p><b>NeoForge 版・Fabric 版（versions/26.3/neoforge/・fabric/）にも同じ名前・同じメソッドのこのクラスがある。</b>
 * versions/26.3/common/ の共有コードはどれにも同じように呼ぶので、メソッドを足したら 3 つに足すこと。</p>
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
        return ModList.isLoaded(modId);
    }
}
