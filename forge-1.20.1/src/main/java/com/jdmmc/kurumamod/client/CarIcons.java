package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.part.CarParts;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 車のアイテムのアイコン。<b>車のモデルを画面の外で 1 度だけ描いて画像に焼き、あとはその画像を貼る。</b>
 *
 * <p>カーパックが {@code vehicles/<car>.json} の {@code icon} に画像を書いていれば、焼かずにそれを使う。
 * 書いていなければ、何も用意しなくてもその車の形のアイコンになる。</p>
 *
 * <h2>なぜ焼くのか</h2>
 *
 * <p>車体は 1 台で 1 万頂点を超える。持ち物やクリエイティブタブに並ぶたびに 3D で描くと、
 * 並べた台数ぶんを毎フレーム描くことになる。焼いてしまえば普通のアイテムと同じく画像 1 枚で済む。
 * 焼くのは<b>初めて見えたとき</b>だけ。1 台 15〜50ms（実測。その車のモデルを初めて読むときはさらに長い）。</p>
 *
 * <h2>いつ焼くか</h2>
 *
 * <p>持ち物を描いている最中には焼かない（描き先を持ち替えることになる）。描く側は
 * {@link #iconFor} で頼むだけで、実際に焼くのは次のフレームの頭（{@code RenderTickEvent} の START。
 * {@link MirrorRenderer} と同じ場所）。それまでの 1 フレームは何も描かない。
 * 1 フレームに焼くのは {@link #BAKES_PER_FRAME} 台まで。車種の多いタブを開いた瞬間に、まとめて焼いて
 * 画面が止まらないようにする（2 台ずつだった頃は 1 フレームが 80ms 近くになった）。</p>
 *
 * <h2>何ごとに焼くか</h2>
 *
 * <p>アイテムの NBT（車種・諸元・装着）と大きさの組ごと。回収した車は履いているホイールや
 * タイヤ半径まで絵に出る。大きさは GUI スケールから決める（{@link #iconSize}）ので、
 * 画面の 1 画素とテクスチャの 1 画素がちょうど重なる。中では {@link #SUPERSAMPLE} 倍で描いてから
 * 縮めて、縁のギザギザを消す。</p>
 *
 * <p>F3+T（モデルの読み直し）と、車種・部品の一覧が入れ替わったとき（サーバーへ入ったときなど）は
 * 全部捨てて焼き直す。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarIcons {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 1 フレームに焼く数。 */
    private static final int BAKES_PER_FRAME = 1;
    /** 焼いた絵をいくつまで覚えておくか。超えたら使っていないものから捨てる。 */
    private static final int MAX_ICONS = 96;
    /** 何倍の大きさで描いてから縮めるか。 */
    private static final int SUPERSAMPLE = 4;
    /** 絵の大きさ [画素] の範囲。 */
    private static final int MIN_SIZE = 16;
    private static final int MAX_SIZE = 128;
    /** 絵の縁に空ける幅（絵の一辺に対する割合）。 */
    private static final float MARGIN = 0.04F;

    /**
     * ほぼ正面から、わずかに見下ろす向き。<b>顔つき（ライトとグリル）で車種を見分けられる</b>よう、
     * 前をほとんど手前へ向け、左へ少しだけ振って左の横腹を覗かせる。
     * ヨーは車の前（-Z）を手前へ回す角度（180 で真正面）、ピッチは見下ろす角度 [度]。
     * 値は検証ページで 4 台を並べて決めた。
     */
    private static final float VIEW_YAW = 167.0F;
    private static final float VIEW_PITCH = 11.0F;

    /**
     * 光の向き（画面から見て +X 右・+Y 上・+Z 手前。光の来る側を指す）。
     *
     * <p><b>バニラの {@code Lighting#setupForEntityInInventory} は使えない。</b>あちらは GUI が
     * 上下を裏返して描く前提の向き（Y が負）なので、裏返さずに焼くと<b>光が下から当たって暗くなる</b>。
     * 強さは変えられない（シェーダの式で環境光 0.4・光 0.6 に決まっている）ので、明るさは
     * 焼いた後の {@link #EXPOSURE} と {@link #GAMMA} で足す。</p>
     */
    private static final Vector3f KEY_LIGHT = new Vector3f(-0.329F, 0.819F, 0.470F);
    private static final Vector3f FILL_LIGHT = new Vector3f(0.837F, 0.259F, 0.483F);
    /** 焼いた後に色へ掛ける倍率と、持ち上げるガンマ（{@code (色 × 露出)^(1/ガンマ)}）。 */
    private static final float EXPOSURE = 1.10F;
    private static final float GAMMA = 1.10F;

    /** 焼いた絵。null は「焼けなかった」（同じものを焼き直し続けないため）。 */
    private static final Map<Key, Baked> ICONS = new LinkedHashMap<>(16, 0.75F, true);
    /** 焼くのを待っているもの。 */
    private static final Map<Key, ItemStack> PENDING = new LinkedHashMap<>();
    /** カーパックが指定したアイコンの画像が実際にあるか。F3+T で捨てる。 */
    private static final Map<ResourceLocation, Boolean> PROVIDED = new java.util.HashMap<>();

    /** 焼くときに使う描き先。大きさが変われば作り直す。 */
    @Nullable
    private static TextureTarget target;
    private static int nextTextureId;

    /** 一覧が入れ替わったかを見るための、前回の値。 */
    @Nullable
    private static Object seenTypes;
    private static int seenParts = -1;

    private CarIcons() {
    }

    private record Key(@Nullable CompoundTag tag, int size) {
    }

    private record Baked(ResourceLocation texture) {
    }

    /** 失敗したことを覚えておく印。 */
    private static final Baked FAILED = new Baked(new ResourceLocation(Kurumamod.MODID, "textures/item/car.png"));

    /**
     * そのアイテムのアイコンの絵。
     *
     * <p>カーパックが {@code vehicles/<car>.json} の {@code icon} に画像を書いていればそれ。
     * 無ければ車のモデルから焼いた絵で、<b>まだ焼いていなければ頼んで null を返す</b>
     * （次のフレームに焼ける）。焼けなかったときは、以前の平たいアイコンの絵を返す。</p>
     */
    @Nullable
    public static ResourceLocation iconFor(ItemStack stack) {
        refresh();
        ResourceLocation provided = providedIcon(stack);
        if (provided != null) {
            return provided;
        }
        Key key = new Key(tagOf(stack), iconSize());
        Baked baked = ICONS.get(key);
        if (baked != null) {
            return baked.texture();
        }
        if (!PENDING.containsKey(key)) {
            // 鍵の NBT は写しを持つ。アイテムのものをそのまま持つと、後から書き換えられたときに鍵が壊れる
            ItemStack copy = stack.copy();
            PENDING.put(new Key(tagOf(copy), key.size()), copy);
        }
        return null;
    }

    /**
     * カーパックが指定したアイコンの画像。指定が無いか、画像が見つからなければ null。
     *
     * <p>見つからないときは<b>焼いた絵へ戻す</b>（紫と黒の「テクスチャが無い」模様を出さない）。
     * 書き間違いに気づけるよう、ログには画像ごとに 1 回だけ残す。</p>
     */
    @Nullable
    private static ResourceLocation providedIcon(ItemStack stack) {
        ResourceLocation icon = CarModel.get(CarSpawnItem.carTypeOf(stack).id()).icon();
        if (icon == null) {
            return null;
        }
        boolean exists = PROVIDED.computeIfAbsent(icon, location -> {
            boolean found = Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
            if (!found) {
                LOGGER.warn("車のアイコンの画像が見つかりません（車のモデルから作ったアイコンを使います）: {}", location);
            }
            return found;
        });
        return exists ? icon : null;
    }

    @Nullable
    private static CompoundTag tagOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null || tag.isEmpty() ? null : tag;
    }

    /** 絵の大きさ [画素]。持ち物のマス（GUI の 16 画素）が画面で何画素になるか。 */
    private static int iconSize() {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return Mth.clamp((int) Math.ceil(16.0 * scale), MIN_SIZE, MAX_SIZE);
    }

    private static CarEntity createCar(net.minecraft.world.level.Level level, ItemStack stack) {
        CarEntity car = new CarEntity(Kurumamod.CAR.get(), level);
        CarSpawnItem.applyTo(car, stack);
        car.becomeIcon();
        return car;
    }

    /** F3+T で呼ぶ。 */
    public static void clearCache() {
        RenderSystem.recordRenderCall(() -> {
            PROVIDED.clear();
            releaseAll();
        });
    }

    /** 一覧が変わっていたら全部捨てる。 */
    private static void refresh() {
        Object types = CarTypes.all();
        int parts = CarParts.generation();
        if (types != seenTypes || parts != seenParts) {
            seenTypes = types;
            seenParts = parts;
            releaseAll();
        }
    }

    private static void releaseAll() {
        Minecraft mc = Minecraft.getInstance();
        for (Baked baked : ICONS.values()) {
            if (baked != FAILED) {
                mc.getTextureManager().release(baked.texture());
            }
        }
        ICONS.clear();
        PENDING.clear();
    }

    // ------------------------------------------------------------------
    // 焼く
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || PENDING.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            PENDING.clear();
            return;
        }
        refresh();
        List<Map.Entry<Key, ItemStack>> batch = new ArrayList<>();
        for (Map.Entry<Key, ItemStack> entry : PENDING.entrySet()) {
            if (batch.size() >= BAKES_PER_FRAME) {
                break;
            }
            batch.add(entry);
        }
        for (Map.Entry<Key, ItemStack> entry : batch) {
            PENDING.remove(entry.getKey());
            Baked baked;
            try {
                long start = System.nanoTime();
                baked = bake(mc, entry.getKey(), entry.getValue());
                LOGGER.debug("車のアイコンを焼いた: {} 画素・{} ms", entry.getKey().size(),
                        String.format(java.util.Locale.ROOT, "%.2f", (System.nanoTime() - start) / 1.0e6));
            } catch (RuntimeException e) {
                LOGGER.error("車のアイコンを焼けなかった。このアイテムは平たいアイコンで描く", e);
                baked = FAILED;
            } finally {
                // 失敗しても必ず画面へ戻す。戻さないと以降の描画が焼くための描き先へ流れ込む
                mc.getMainRenderTarget().bindWrite(true);
            }
            ICONS.put(entry.getKey(), baked);
            while (ICONS.size() > MAX_ICONS) {
                Map.Entry<Key, Baked> eldest = ICONS.entrySet().iterator().next();
                if (eldest.getValue() != FAILED) {
                    mc.getTextureManager().release(eldest.getValue().texture());
                }
                ICONS.remove(eldest.getKey());
            }
        }
    }

    private static Baked bake(Minecraft mc, Key key, ItemStack stack) {
        CarEntity car = createCar(mc.level, stack);
        int size = key.size();
        int canvas = size * SUPERSAMPLE;

        // 向き。頂点にはヨー → ピッチの順に掛かる
        Matrix3f view = new Matrix3f().rotateX(VIEW_PITCH * Mth.DEG_TO_RAD).rotateY(VIEW_YAW * Mth.DEG_TO_RAD);

        // 車を囲む箱を向けて、画面に写る範囲を測る。ここで枠を決め、最後に透けていない画素で詰める
        double[] box = CarObjRenderer.iconBounds(car);
        float centerX = (float) ((box[0] + box[3]) * 0.5);
        float centerY = (float) ((box[1] + box[4]) * 0.5);
        float centerZ = (float) ((box[2] + box[5]) * 0.5);
        float half = 0.0F;
        float depth = 0.0F;
        for (int corner = 0; corner < 8; corner++) {
            Vector3f point = new Vector3f(
                    (float) box[(corner & 1) == 0 ? 0 : 3] - centerX,
                    (float) box[(corner & 2) == 0 ? 1 : 4] - centerY,
                    (float) box[(corner & 4) == 0 ? 2 : 5] - centerZ);
            view.transform(point);
            half = Math.max(half, Math.max(Math.abs(point.x()), Math.abs(point.y())));
            depth = Math.max(depth, Math.abs(point.z()));
        }
        half = Math.max(half, 0.01F);
        depth = depth + 1.0F;

        if (target == null) {
            target = new TextureTarget(canvas, canvas, true, Minecraft.ON_OSX);
        } else if (target.width != canvas || target.height != canvas) {
            target.resize(canvas, canvas, Minecraft.ON_OSX);
        }
        target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        float savedFogStart = RenderSystem.getShaderFogStart();
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            modelView.setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-half, half, -half, half, -depth, depth),
                    VertexSorting.ORTHOGRAPHIC_Z);
            // 前のフレームの霧が残っていると、水中などで車が霧の色に塗られる
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShaderLights(KEY_LIGHT, FILL_LIGHT);

            PoseStack pose = new PoseStack();
            pose.mulPose(Axis.XP.rotationDegrees(VIEW_PITCH));
            pose.mulPose(Axis.YP.rotationDegrees(VIEW_YAW));
            pose.translate(-centerX, -centerY, -centerZ);

            MultiBufferSource.BufferSource buffer = MultiBufferSource.immediate(new BufferBuilder(256));
            CarObjRenderer renderer = (CarObjRenderer) mc.getEntityRenderDispatcher().getRenderer(car);
            renderer.renderIcon(car, pose, buffer, LightTexture.FULL_BRIGHT);
            buffer.endBatch();
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            RenderSystem.setShaderFogStart(savedFogStart);
            Lighting.setupFor3DItems();
        }

        NativeImage drawn = new NativeImage(canvas, canvas, false);
        try {
            RenderSystem.bindTexture(target.getColorTextureId());
            drawn.downloadTexture(0, false);
            drawn.flipY();
            NativeImage icon = shrink(drawn, size);
            ResourceLocation location = new ResourceLocation(Kurumamod.MODID, "car_icon/" + (nextTextureId++));
            mc.getTextureManager().register(location, new DynamicTexture(icon));
            return new Baked(location);
        } finally {
            drawn.close();
        }
    }

    /** 露出とガンマを掛ける。{@code value} は 0〜255。 */
    private static int tone(double value) {
        double lifted = Math.pow(Math.min(1.0, value / 255.0 * EXPOSURE), 1.0 / GAMMA);
        return Mth.clamp((int) Math.round(lifted * 255.0), 0, 255);
    }

    /**
     * 大きく描いた絵を、透けていない部分で詰めてから {@code size} 四方へ縮める。
     *
     * <p>縮めるときは出す 1 画素に入る元の画素を平均し（縁が滑らかになる）、最後に露出とガンマを掛ける。
     * 色は不透明度で重みを付ける——付けないと、透けた画素の黒が混ざって縁が黒ずむ。</p>
     */
    private static NativeImage shrink(NativeImage source, int size) {
        int width = source.getWidth();
        int height = source.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((source.getPixelRGBA(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        NativeImage out = new NativeImage(size, size, true);
        if (maxX < 0) {
            return out;
        }
        // 詰めた範囲を正方形に広げ、縁の余白を足す
        double side = Math.max(maxX - minX + 1, maxY - minY + 1) / (1.0 - 2.0 * MARGIN);
        double left = (minX + maxX + 1) * 0.5 - side * 0.5;
        double top = (minY + maxY + 1) * 0.5 - side * 0.5;
        double step = side / size;
        int samples = Math.max(1, (int) Math.ceil(step));
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double red = 0.0;
                double green = 0.0;
                double blue = 0.0;
                double alpha = 0.0;
                for (int sy = 0; sy < samples; sy++) {
                    int py = (int) Math.floor(top + (y + (sy + 0.5) / samples) * step);
                    for (int sx = 0; sx < samples; sx++) {
                        int px = (int) Math.floor(left + (x + (sx + 0.5) / samples) * step);
                        if (px < 0 || py < 0 || px >= width || py >= height) {
                            continue;
                        }
                        // NativeImage の画素は ABGR の順に詰まっている
                        int pixel = source.getPixelRGBA(px, py);
                        double a = (pixel >>> 24) / 255.0;
                        alpha += a;
                        red += (pixel & 0xFF) * a;
                        green += ((pixel >> 8) & 0xFF) * a;
                        blue += ((pixel >> 16) & 0xFF) * a;
                    }
                }
                int count = samples * samples;
                if (alpha <= 0.0) {
                    out.setPixelRGBA(x, y, 0);
                    continue;
                }
                int r = tone(red / alpha);
                int g = tone(green / alpha);
                int b = tone(blue / alpha);
                int a = (int) Math.round(alpha / count * 255.0);
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

}
