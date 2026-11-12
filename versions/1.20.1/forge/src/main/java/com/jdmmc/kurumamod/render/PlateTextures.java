package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.platform.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlateRules;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import com.jdmmc.kurumamod.client.model.PlateImages;
import com.jdmmc.kurumamod.client.model.PlateModel;

/**
 * 字を焼き込んだプレートのテクスチャ。焼く・覚えておく・捨てるのは core の {@link PlateImages}。
 * ここは焼いた絵を {@link DynamicTexture} として登録し、捨てるだけ。
 */
public final class PlateTextures {

    private static int nextId;

    /** 焼いた絵をテクスチャにする口（{@link PlateImages.Textures}）。 */
    private static final PlateImages.Textures TEXTURES = new PlateImages.Textures() {
        @Override
        public Object register(BufferedImage painted) {
            NativeImage image = new NativeImage(painted.getWidth(), painted.getHeight(), false);
            for (int y = 0; y < painted.getHeight(); y++) {
                for (int x = 0; x < painted.getWidth(); x++) {
                    int argb = painted.getRGB(x, y);
                    // NativeImage は ABGR
                    image.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
                }
            }
            ResourceLocation location = new ResourceLocation(Kurumamod.MODID, "plate_baked/" + (nextId++));
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            return location;
        }

        @Override
        public void release(Object texture) {
            Minecraft.getInstance().getTextureManager().release((ResourceLocation) texture);
        }

        @Override
        public Object plain(CarId texture) {
            return McIds.mc(texture);
        }
    };

    private PlateTextures() {
    }

    /**
     * そのプレートの、字を焼いたテクスチャ（{@link PlateImages#get}）。
     *
     * @param mesh 字の枠（{@code text_<欄>}）を持つプレートのメッシュ
     */
    public static ResourceLocation get(CarId partId, PlateModel model, ObjModel mesh, PlateRules rules,
                                       PlateLook look, boolean front) {
        return (ResourceLocation) PlateImages.get(TEXTURES, partId, model, mesh, rules, look, front);
    }

    /** リソースの再読み込みで呼ぶ。焼いたものを全部捨てる。 */
    public static void clearCache() {
        PlateImages.clearCache(TEXTURES);
    }
}
