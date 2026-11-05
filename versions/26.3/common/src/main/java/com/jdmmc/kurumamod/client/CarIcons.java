package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.hook.FrameRender;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.part.CarParts;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

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
 * {@link #iconFor} で頼むだけで、実際に焼くのは次のフレームの頭（{@code RenderFrameEvent.Pre}。
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
    private static final Map<Identifier, Boolean> PROVIDED = new java.util.HashMap<>();

    private static int nextTextureId;

    /** 一覧が入れ替わったかを見るための、前回の値。 */
    @Nullable
    private static Object seenTypes;
    private static int seenParts = -1;

    private CarIcons() {
    }

    /** NBT は {@code minecraft:custom_data}（{@link CustomData} は書き換えられないので、そのまま鍵にできる）。 */
    private record Key(@Nullable CustomData tag, int size) {
    }

    private record Baked(Identifier texture) {
    }

    /** 失敗したことを覚えておく印。 */
    private static final Baked FAILED = new Baked(Identifier.fromNamespaceAndPath(Kurumamod.MODID, "textures/item/car.png"));

    /**
     * そのアイテムのアイコンの絵。
     *
     * <p>カーパックが {@code vehicles/<car>.json} の {@code icon} に画像を書いていればそれ。
     * 無ければ車のモデルから焼いた絵で、<b>まだ焼いていなければ頼んで null を返す</b>
     * （次のフレームに焼ける）。焼けなかったときは、以前の平たいアイコンの絵を返す。</p>
     */
    @Nullable
    public static Identifier iconFor(ItemStack stack) {
        refresh();
        Identifier provided = providedIcon(stack);
        if (provided != null) {
            return provided;
        }
        Key key = new Key(tagOf(stack), iconSize());
        Baked baked = ICONS.get(key);
        if (baked != null) {
            return baked.texture();
        }
        if (!PENDING.containsKey(key)) {
            PENDING.put(key, stack.copy());
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
    private static Identifier providedIcon(ItemStack stack) {
        Identifier icon = CarModel.get(CarSpawnItem.carTypeOf(stack).id()).icon();
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
    private static CustomData tagOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null || data.isEmpty() ? null : data;
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
        // F3+T は描画のスレッドから呼ばれる
        PROVIDED.clear();
        releaseAll();
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
        // 読み戻しを待っているものは、届いたときに古い一覧へ入るだけ（次に頼まれれば焼き直す）
        BAKING.clear();
    }

    // ------------------------------------------------------------------
    // 焼く
    // ------------------------------------------------------------------

    public static void onRenderTick(FrameRender event) {
        if (PENDING.isEmpty()) {
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
            Key key = entry.getKey();
            PENDING.remove(key);
            if (BAKING.contains(key)) {
                continue;
            }
            try {
                bake(mc, key, entry.getValue());
            } catch (RuntimeException e) {
                LOGGER.error("車のアイコンを焼けなかった。このアイテムは平たいアイコンで描く", e);
                store(mc, key, FAILED);
            }
        }
    }

    /** 読み戻しを待っているもの。2 度焼かないため。 */
    private static final java.util.Set<Key> BAKING = new java.util.HashSet<>();

    private static void store(Minecraft mc, Key key, Baked baked) {
        BAKING.remove(key);
        ICONS.put(key, baked);
        while (ICONS.size() > MAX_ICONS) {
            Map.Entry<Key, Baked> eldest = ICONS.entrySet().iterator().next();
            if (eldest.getValue() != FAILED) {
                mc.getTextureManager().release(eldest.getValue().texture());
            }
            ICONS.remove(eldest.getKey());
        }
    }

    // ---- 焼くための描き先（大きさが変われば作り直す） ----
    @Nullable
    private static GpuTexture colorTexture;
    @Nullable
    private static GpuTextureView colorView;
    @Nullable
    private static GpuTexture depthTexture;
    @Nullable
    private static GpuTextureView depthView;
    @Nullable
    private static ProjectionMatrixBuffer projectionBuffer;
    /** 焼くときの光の向き（{@link #KEY_LIGHT}・{@link #FILL_LIGHT}）と、霧無しの値。最初に 1 度だけ作る。 */
    @Nullable
    private static GpuBuffer lightsBuffer;
    @Nullable
    private static GpuBuffer noFogBuffer;
    /** 預け先。焼くたびに使い回す（描き終えると空になる）。 */
    private static final SubmitNodeStorage STORAGE = new SubmitNodeStorage();

    private static void ensureTargets(int canvas) {
        GpuDevice device = RenderSystem.getDevice();
        if (colorTexture != null && colorTexture.getWidth(0) != canvas) {
            colorView.close();
            colorTexture.close();
            depthView.close();
            depthTexture.close();
            colorTexture = null;
        }
        if (colorTexture == null) {
            colorTexture = device.createTexture(() -> "kurumamod car icon", GpuTexture.USAGE_RENDER_ATTACHMENT
                    | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, canvas, canvas, 1, 1);
            colorView = device.createTextureView(colorTexture);
            GpuFormat depthFormat = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture().getFormat();
            depthTexture = device.createTexture(() -> "kurumamod car icon depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, depthFormat, canvas, canvas, 1, 1);
            depthView = device.createTextureView(depthTexture);
        }
        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("kurumamod car icon");
        }
        if (lightsBuffer == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer lights = Std140Builder.onStack(stack, Lighting.UBO_SIZE)
                        .putVec3(KEY_LIGHT).putVec3(FILL_LIGHT).get();
                lightsBuffer = device.createBuffer(() -> "kurumamod car icon lights",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, lights);
            }
        }
        if (noFogBuffer == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 前のフレームの霧が残っていると、水中などで車が霧の色に塗られる
                ByteBuffer fog = Std140Builder.onStack(stack, FogRenderer.FOG_UBO_SIZE)
                        .putVec4(0.0F, 0.0F, 0.0F, 0.0F)
                        .putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE)
                        .putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE)
                        .get();
                noFogBuffer = device.createBuffer(() -> "kurumamod car icon fog",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, fog);
            }
        }
    }

    /**
     * 画面の外の描き先へ車を描き、読み戻しを頼む。<b>絵ができるのは読み戻しが届いてから</b>（数フレーム後）。
     *
     * <p>26.3 の描画は「預けてから流す」なので、GUI の「絵の中の絵」（{@code PictureInPictureRenderer}）と
     * 同じ手順で描く: 自前の預け先（{@code SubmitNodeStorage}）へ車を預け、{@code FeatureRenderDispatcher}
     * で自前の描き先へ流す。OpenGL も Vulkan も同じ口で動く。</p>
     */
    private static void bake(Minecraft mc, Key key, ItemStack stack) {
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

        ensureTargets(canvas);
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        // 深度は「大きいほど手前」（Reversed-Z）なので 0 で埋める
        encoder.clearColorAndDepthTextures(colorTexture, new Vector4f(0.0F, 0.0F, 0.0F, 0.0F), depthTexture, 0.0);

        // 正射影。26.3 は近い側と遠い側を入れ替えて渡す（Projection#getMatrix と同じ）
        Matrix4f ortho = new Matrix4f().setOrtho(-half, half, -half, half, depth, -depth,
                device.getDeviceInfo().isZZeroToOne());

        GpuBufferSlice savedFog = RenderSystem.getShaderFog();
        GpuBufferSlice savedLights = RenderSystem.getShaderLights();
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            modelView.identity();
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(ortho), ProjectionType.ORTHOGRAPHIC);
            RenderSystem.setShaderFog(noFogBuffer.slice(0L, FogRenderer.FOG_UBO_SIZE));
            RenderSystem.setShaderLights(lightsBuffer.slice(0L, Lighting.UBO_SIZE));

            PoseStack pose = new PoseStack();
            pose.rotate(Axis.XP.rotationDegrees(VIEW_PITCH));
            pose.rotate(Axis.YP.rotationDegrees(VIEW_YAW));
            pose.translate(-centerX, -centerY, -centerZ);

            CarObjRenderer renderer = (CarObjRenderer) mc.getEntityRenderDispatcher().getRenderer(car);
            renderer.renderIcon(car, pose, STORAGE, LightCoordsUtil.FULL_BRIGHT);

            FeatureRenderDispatcher dispatcher = mc.gameRenderer.featureRenderDispatcher();
            try (FeatureRenderDispatcher.PreparedFrame frame = dispatcher.prepareFrame(STORAGE);
                 RenderPass pass = encoder.createRenderPass(() -> "kurumamod car icon", colorView, Optional.empty(),
                         depthView, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                FeatureRenderDispatcher.renderAllFeatures(pass, frame);
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
            if (savedFog != null) {
                RenderSystem.setShaderFog(savedFog);
            }
            if (savedLights != null) {
                RenderSystem.setShaderLights(savedLights);
            }
        }

        // 読み戻し。届くのは後のフレーム（描画のスレッドで呼ばれる）
        int blockSize = colorTexture.getFormat().blockSize();
        GpuBuffer readback = device.createBuffer(() -> "kurumamod car icon readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) canvas * canvas * blockSize);
        BAKING.add(key);
        long start = System.nanoTime();
        encoder.copyTextureToBuffer(colorTexture, readback, 0L, () -> {
            try {
                NativeImage drawn = new NativeImage(canvas, canvas, false);
                try {
                    try (GpuBufferSlice.MappedView read = readback.map(true, false)) {
                        for (int y = 0; y < canvas; y++) {
                            for (int x = 0; x < canvas; x++) {
                                // 読み戻しは下の行から（スクリーンショットと同じ）。上下を返して置く
                                drawn.setPixelABGR(x, canvas - y - 1, read.data().getInt((x + y * canvas) * blockSize));
                            }
                        }
                    }
                    NativeImage icon = shrink(drawn, size);
                    Identifier location = Identifier.fromNamespaceAndPath(Kurumamod.MODID, "car_icon/" + (nextTextureId++));
                    mc.getTextureManager().register(location, new DynamicTexture(location::toString, icon));
                    store(mc, key, new Baked(location));
                    LOGGER.debug("車のアイコンを焼いた: {} 画素・{} ms", size,
                            String.format(java.util.Locale.ROOT, "%.2f", (System.nanoTime() - start) / 1.0e6));
                } finally {
                    drawn.close();
                }
            } catch (RuntimeException e) {
                LOGGER.error("車のアイコンを読み戻せなかった。このアイテムは平たいアイコンで描く", e);
                store(mc, key, FAILED);
            } finally {
                readback.close();
            }
        }, 0);
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
                if ((source.getPixel(x, y) >>> 24) != 0) {
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
                        int pixel = net.minecraft.util.ARGB.toABGR(source.getPixel(px, py));
                        double a = (pixel >>> 24) / 255.0;
                        alpha += a;
                        red += (pixel & 0xFF) * a;
                        green += ((pixel >> 8) & 0xFF) * a;
                        blue += ((pixel >> 16) & 0xFF) * a;
                    }
                }
                int count = samples * samples;
                if (alpha <= 0.0) {
                    out.setPixelABGR(x, y, 0);
                    continue;
                }
                int r = tone(red / alpha);
                int g = tone(green / alpha);
                int b = tone(blue / alpha);
                int a = (int) Math.round(alpha / count * 255.0);
                out.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

}
