package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.plate.PlateFormat;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlatePainter;
import com.jdmmc.kurumamod.plate.PlateRules;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 字を焼き込んだプレートのテクスチャ。<b>地の絵と字の組み合わせごとに 1 枚作って覚えておく。</b>
 *
 * <p>描くたびに焼くと重いので、同じ組み合わせ（部品・前後・出す字）なら作ったものを使い回す。
 * 字を打ち替えれば新しい組み合わせになり、使われなくなった古いものから捨てる（{@link #CAPACITY}）。</p>
 *
 * <p>字の並べ方は Minecraft に依存しない {@link PlatePainter} が決める。ここはフォントと地の絵を
 * リソースから読み、焼いた絵を {@link DynamicTexture} として登録するだけ。</p>
 */
public final class PlateTextures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 覚えておく枚数。1 枚 2〜8MB なので、多すぎると VRAM を食う。 */
    private static final int CAPACITY = 48;

    /** 日本語のフォント。英字のフォントに無い字（かな・漢字）はこれで代わりに描く。 */
    private static final ResourceLocation JAPANESE_FONT =
            new ResourceLocation(Kurumamod.MODID, "plate_fonts/kurumaplate_jp.ttf");

    private record Key(ResourceLocation partId, boolean front, Map<String, String> text) {
    }

    /** 古い順に並ぶ（アクセス順）。あふれたものはテクスチャを解放して捨てる。 */
    private static final LinkedHashMap<Key, ResourceLocation> BAKED = new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<ResourceLocation, Font> FONTS = new HashMap<>();
    private static int nextId;

    private PlateTextures() {
    }

    /**
     * そのプレートの、字を焼いたテクスチャ。
     *
     * @param mesh 字の枠（{@code text_<欄>}）を持つプレートのメッシュ
     */
    public static ResourceLocation get(ResourceLocation partId, PlateModel model, ObjModel mesh, PlateRules rules,
                                       PlateLook look, boolean front) {
        Map<String, ObjModel.TextFrame> frames = mesh.textFrames();
        if (frames.isEmpty()) {
            return model.texture(front);
        }
        Map<String, String> shown = new LinkedHashMap<>();
        for (String field : frames.keySet()) {
            shown.put(field, PlateFormat.apply(model.field(field).format(), rules.display(look, field)));
        }
        Key key = new Key(partId, front, shown);
        ResourceLocation baked = BAKED.get(key);
        if (baked != null) {
            return baked;
        }
        baked = bake(model, mesh, frames, shown, front);
        BAKED.put(key, baked);
        evict();
        return baked;
    }

    private static ResourceLocation bake(PlateModel model, ObjModel mesh, Map<String, ObjModel.TextFrame> frames,
                                         Map<String, String> shown, boolean front) {
        BufferedImage base = readImage(model.texture(front));
        double[] box = mesh.bounds();
        double width = box[3] - box[0];
        double height = box[4] - box[1];
        List<PlatePainter.Field> fields = new ArrayList<>();
        Font japanese = font(JAPANESE_FONT);
        Font system = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        for (Map.Entry<String, ObjModel.TextFrame> entry : frames.entrySet()) {
            ObjModel.TextFrame frame = entry.getValue();
            PlateModel.FieldLook look = model.field(entry.getKey());
            Font font = font(look.font());
            // 表の UV は正面から見て左が 0。正面から見た右はエンティティの -X なので、X の大きい側が u = 0
            fields.add(new PlatePainter.Field(shown.get(entry.getKey()), font, font == japanese ? system : japanese,
                    look.argb(), look.fit(), look.emboss(), look.mono(),
                    (box[3] - frame.maxX()) / width, (box[4] - frame.maxY()) / height,
                    (box[3] - frame.minX()) / width, (box[4] - frame.minY()) / height));
        }
        BufferedImage painted = PlatePainter.paint(base, fields);
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

    private static void evict() {
        while (BAKED.size() > CAPACITY) {
            var oldest = BAKED.entrySet().iterator().next();
            Minecraft.getInstance().getTextureManager().release(oldest.getValue());
            BAKED.remove(oldest.getKey());
        }
    }

    /** リソースの再読み込みで呼ぶ。地の絵やフォントが替わっているかもしれないので、焼いたものを全部捨てる。 */
    public static void clearCache() {
        for (ResourceLocation location : BAKED.values()) {
            Minecraft.getInstance().getTextureManager().release(location);
        }
        BAKED.clear();
        FONTS.clear();
    }

    private static BufferedImage readImage(ResourceLocation location) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isPresent()) {
            try (InputStream stream = resource.get().open()) {
                BufferedImage image = ImageIO.read(stream);
                if (image != null) {
                    return image;
                }
            } catch (IOException e) {
                LOGGER.error("ナンバーの地の絵を読めませんでした（白い板にします）: {}", location, e);
            }
        } else {
            LOGGER.warn("ナンバーの地の絵が見つかりません（白い板にします）: {}", location);
        }
        BufferedImage blank = new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = blank.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, 512, 256);
        graphics.dispose();
        return blank;
    }

    /** リソースの TTF を読む。読めなければシステムのフォント。 */
    private static Font font(ResourceLocation location) {
        return FONTS.computeIfAbsent(location, PlateTextures::loadFont);
    }

    private static Font loadFont(ResourceLocation location) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.warn("ナンバーのフォントが見つかりません（システムのフォントで描きます）: {}", location);
            return new Font(Font.SANS_SERIF, Font.BOLD, 10);
        }
        try (InputStream stream = resource.get().open()) {
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (IOException | FontFormatException e) {
            LOGGER.error("ナンバーのフォントを読めませんでした（システムのフォントで描きます）: {}", location, e);
            return new Font(Font.SANS_SERIF, Font.BOLD, 10);
        }
    }
}
