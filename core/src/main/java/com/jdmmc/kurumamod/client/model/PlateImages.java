package com.jdmmc.kurumamod.client.model;

import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.plate.PlateFormat;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlatePainter;
import com.jdmmc.kurumamod.plate.PlateRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.jdmmc.kurumamod.client.Assets;

/**
 * 字を焼き込んだプレートの絵。<b>地の絵と字の組み合わせごとに 1 枚作って覚えておく。</b>
 *
 * <p>描くたびに焼くと重いので、同じ組み合わせ（部品・前後・出す字）なら作ったものを使い回す。
 * 字を打ち替えれば新しい組み合わせになり、使われなくなった古いものから捨てる（{@link #CAPACITY}）。</p>
 *
 * <p>字の並べ方は {@link PlatePainter} が決める。ここはフォントと地の絵をリソースから読み（{@link Assets}）、
 * 焼いた絵を版のテクスチャ（{@link Textures}）にするところ。</p>
 */
public final class PlateImages {

    /** 焼いた絵をテクスチャにする口。版の {@code PlateTextures} が置く。 */
    public interface Textures {
        /** 焼いた絵をテクスチャとして登録し、その名前（版の id）を返す。 */
        Object register(BufferedImage image);

        /** 登録したテクスチャを捨てる。 */
        void release(Object texture);

        /** 焼かないプレート（字の枠が無い）の地の絵の名前。 */
        Object plain(CarId texture);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(PlateImages.class);

    /** 覚えておく枚数。1 枚 2〜8MB なので、多すぎると VRAM を食う。 */
    private static final int CAPACITY = 48;

    /** 日本語のフォント。英字のフォントに無い字（かな・漢字）はこれで代わりに描く。 */
    private static final CarId JAPANESE_FONT = CarId.of("kurumamod", "plate_fonts/kurumaplate_jp.ttf");

    private record Key(CarId partId, boolean front, Map<String, String> text) {
    }

    /** 古い順に並ぶ（アクセス順）。あふれたものはテクスチャを解放して捨てる。 */
    private static final LinkedHashMap<Key, Object> BAKED = new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<CarId, Font> FONTS = new HashMap<>();

    private PlateImages() {
    }

    /**
     * そのプレートの、字を焼いたテクスチャ（版の id）。
     *
     * @param mesh 字の枠（{@code text_<欄>}）を持つプレートのメッシュ
     */
    public static Object get(Textures textures, CarId partId, PlateModel model, ObjMesh mesh, PlateRules rules,
                             PlateLook look, boolean front) {
        Map<String, ObjMesh.TextFrame> frames = mesh.textFrames();
        if (frames.isEmpty()) {
            return textures.plain(model.texture(front));
        }
        Map<String, String> shown = new LinkedHashMap<>();
        for (String field : frames.keySet()) {
            shown.put(field, PlateFormat.apply(model.field(field).format(), rules.display(look, field)));
        }
        Key key = new Key(partId, front, shown);
        Object baked = BAKED.get(key);
        if (baked != null) {
            return baked;
        }
        baked = textures.register(bake(model, mesh, frames, shown, front));
        BAKED.put(key, baked);
        while (BAKED.size() > CAPACITY) {
            var oldest = BAKED.entrySet().iterator().next();
            textures.release(oldest.getValue());
            BAKED.remove(oldest.getKey());
        }
        return baked;
    }

    private static BufferedImage bake(PlateModel model, ObjMesh mesh, Map<String, ObjMesh.TextFrame> frames,
                                      Map<String, String> shown, boolean front) {
        BufferedImage base = readImage(model.texture(front));
        double[] box = mesh.bounds();
        double width = box[3] - box[0];
        double height = box[4] - box[1];
        List<PlatePainter.Field> fields = new ArrayList<>();
        Font japanese = font(JAPANESE_FONT);
        Font system = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        for (Map.Entry<String, ObjMesh.TextFrame> entry : frames.entrySet()) {
            ObjMesh.TextFrame frame = entry.getValue();
            PlateModel.FieldLook look = model.field(entry.getKey());
            Font font = font(look.font());
            // 表の UV は正面から見て左が 0。正面から見た右はエンティティの -X なので、X の大きい側が u = 0
            fields.add(new PlatePainter.Field(shown.get(entry.getKey()), font, font == japanese ? system : japanese,
                    look.argb(), look.fit(), look.emboss(), look.mono(),
                    (box[3] - frame.maxX()) / width, (box[4] - frame.maxY()) / height,
                    (box[3] - frame.minX()) / width, (box[4] - frame.minY()) / height));
        }
        return PlatePainter.paint(base, fields);
    }

    /** リソースの再読み込みで呼ぶ。地の絵やフォントが替わっているかもしれないので、焼いたものを全部捨てる。 */
    public static void clearCache(Textures textures) {
        for (Object texture : BAKED.values()) {
            textures.release(texture);
        }
        BAKED.clear();
        FONTS.clear();
    }

    private static BufferedImage readImage(CarId location) {
        Optional<Assets.Asset> resource = Assets.get().getResource(location);
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
    private static Font font(CarId location) {
        return FONTS.computeIfAbsent(location, PlateImages::loadFont);
    }

    private static Font loadFont(CarId location) {
        Optional<Assets.Asset> resource = Assets.get().getResource(location);
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
