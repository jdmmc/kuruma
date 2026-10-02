package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ヘッドライトの光を<b>画面の画素ごとに</b>解く（{@code shaders/core/headlight_volume.fsh}）。
 *
 * <h2>何をしているか</h2>
 *
 * <p>世界を描き終えたところで画面の色と深度を写し取り、画面いっぱいの四角形を 1 枚描く。
 * シェーダーは画素ごとに:</p>
 * <ul>
 *   <li><b>路面</b> … 深度から位置と面の向きを組み直し、配光から照度を求めてその画素を明るくする。
 *       見えている面にしか乗らないので、ブロックでも車でも人でも、形どおりに照らされる</li>
 *   <li><b>霧の中の光線</b> … カメラから見えている面までの視線を刻み、光の円錐の中で
 *       霧がカメラへ散らす光を足す。前方散乱なのでランプの方を見るほど明るく、
 *       霧に吸われるので奥ほど薄れる。視線は面で止まるので、光線は地面や壁に当たったところで終わる</li>
 * </ul>
 *
 * <p>形を積んで描く {@link CarLights} の路面と光の筋は、これが使えるときは描かない
 * （二重に明るくなる）。光芒（レンズの滲み）はどちらの場合も {@link CarLights} が描く。</p>
 *
 * <h2>使えないとき</h2>
 *
 * <p>シェーダーパック（Iris / Oculus）が入っているとき。あちらは世界の描画そのものを
 * 差し替えるので、ここで写し取る画面が別物になる。シェーダーの読み込みに失敗したときも
 * {@link CarLights} の代用に落ちる。</p>
 *
 * <h2>描くのは <b>AFTER_LEVEL</b></h2>
 *
 * <p>半透明のブロック・天候まで描き終えた後。最高（Fabulous）でも、ここでは合成が済んで
 * 主画面に戻っている。<b>ミラーの描画中は描かない</b>——{@link MirrorRenderer} も
 * {@code renderLevel} を呼ぶのでこのイベントが飛んでくるが、写し取る画面が主画面ではない。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class HeadlightVolume {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * ランプを集める入れ物の大きさ。<b>シェーダー側に灯数の決め打ちは無い</b>（ランプはテクスチャで渡す。
     * {@link HeadlightShadows#uploadLamps}）。実際に描く台数は設定
     * （{@link com.jdmmc.kurumamod.ClientConfig#headlightCars}、無制限あり）で絞り、天井は GPU の
     * テクスチャの大きさ（{@link HeadlightShadows#maxLamps}）。シェーダーのループは {@code LampCount}
     * までしか回らないので、絞ったぶんだけ軽くなる。
     */
    private static final int MAX_LAMPS = HeadlightShadows.LAMP_CAP;
    /** これより遠い車の光は計算しない。 */
    private static final double MAX_DISTANCE = 128.0;

    /**
     * 霧の濃さ（1m あたりに吸われ・散らされる割合）。
     *
     * <p><b>晴れた夜でもうっすら光線が見える</b>程度を基準にして、雨で濃くする。
     * 濃いほど光線ははっきりするが、そのぶん奥まで届かなくなる。</p>
     */
    private static final float CLEAR_FOG = 0.025F;
    private static final float RAIN_FOG = 0.05F;
    private static final float THUNDER_FOG = 0.03F;

    private static ShaderInstance shader;
    /** ランプから見た距離の地図を描く（{@link HeadlightShadows}）。 */
    private static ShaderInstance shadowShader;
    /** 主画面の色と深度の写し。シェーダーはこれを読み、主画面へ書く。 */
    private static TextureTarget copy;

    private static final Matrix4f[] LAMPS = new Matrix4f[MAX_LAMPS];

    private HeadlightVolume() {
    }

    /** 画素ごとの光が使えるか。使えないなら {@link CarLights} が形を積んで代用する。 */
    public static boolean isActive() {
        ensureShaders();
        return shader != null && shadowShader != null && !suppressedByShaderPack();
    }

    /** {@link #ensureShaders} で自分で読もうとしたか。読み直しの合図が来たら戻す。 */
    private static boolean loadAttempted;

    /**
     * シェーダーが無ければ、自分で読む。
     *
     * <p><b>シェーダーパックを有効にしたまま起動すると、{@code RegisterShadersEvent} が飛ばない。</b>
     * Oculus が Minecraft 標準のシェーダーの読み直しを止めるため。合図を待つだけだと、KurumaMod の
     * シェーダーは空のままで、<b>パックを切った後も代用の描き方（ブロック単位のギザギザ）に落ちたままになった</b>。
     * 合図が来たときはそちらで読み直す。描画のスレッドで呼ぶこと。</p>
     */
    static void ensureShaders() {
        if ((shader != null && shadowShader != null) || loadAttempted) {
            return;
        }
        loadAttempted = true;
        try {
            var resources = Minecraft.getInstance().getResourceManager();
            if (shader == null) {
                shader = new ShaderInstance(resources, new ResourceLocation(Kurumamod.MODID, "headlight_volume"),
                        DefaultVertexFormat.POSITION);
            }
            if (shadowShader == null) {
                shadowShader = new ShaderInstance(resources, new ResourceLocation(Kurumamod.MODID, "headlight_shadow"),
                        DefaultVertexFormat.POSITION);
            }
            LOGGER.info("ヘッドライトのシェーダーを自分で読んだ（読み直しの合図が来なかった。シェーダーパックを有効にしたまま起動したとき）");
        } catch (IOException | RuntimeException e) {
            LOGGER.error("ヘッドライトのシェーダーを読めなかった。形を積む描き方で代用する", e);
        }
    }

    /**
     * シェーダーパックのために光を止めているか。<b>止めるのは、パックが使われていて、かつ設定で
     * 「使用中も光を描く」を切っているときだけ</b>（{@link com.jdmmc.kurumamod.ClientConfig#headlightWithShaderPacks}）。
     *
     * <p>以前はパックが使われていれば無条件に止めていた（Oculus で描画がおかしくなったため）。
     * 2026-10-01 に Oculus 1.8.0 ＋ Embeddium で試すと、路面の照り返しも光芒も深度どおりに乗ったので、
     * 既定で描くようにした。合わないパックのときだけプレイヤーが切れる。パックが無ければ何も変わらない。</p>
     */
    public static boolean suppressedByShaderPack() {
        return !com.jdmmc.kurumamod.ClientConfig.headlightWithShaderPacks && ShaderPacks.inUse();
    }

    /**
     * シェーダーパックが<b>いま実際に使われているか</b>。
     *
     * <p>Oculus / Iris / OptiFine のシェーダーは世界の描画そのものを差し替えるので、そこへ
     * 画面を写し取って重ねると壊れる（Oculus で実際に描画がおかしくなった）。<b>MOD が
     * 入っているかではなく、パックが有効か</b>で見る——入っているだけで止めると、シェーダーを
     * 切って遊んでいる人まで光を失う。パックは遊んでいる途中に切り替えられるので毎回聞く。</p>
     *
     * <p>どれもコンパイル時には依存しないのでリフレクションで聞く。<b>聞けなかったら
     * 「使われている」とみなす</b>（壊れた画面より、控えめな代用の方がまし）。</p>
     */
    static final class ShaderPacks {
        private static boolean resolved;
        private static java.lang.reflect.Method irisInUse;
        private static java.lang.reflect.Method irisShadowPass;
        private static Object irisApi;
        private static java.lang.reflect.Method optifineInUse;
        /** 聞き方が分からなかった（API が変わった）。安全側に倒す。 */
        private static boolean unknown;

        private ShaderPacks() {
        }

        static boolean inUse() {
            if (!resolved) {
                resolve();
            }
            if (unknown) {
                return true;
            }
            boolean inUse;
            try {
                inUse = (irisInUse != null && (Boolean) irisInUse.invoke(irisApi))
                        || (optifineInUse != null && (Boolean) optifineInUse.invoke(null));
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("シェーダーパックが使われているか聞けなかった。以後は使われているものとして扱う", e);
                unknown = true;
                return true;
            }
            if (last == null || last != inUse) {
                // 切り替わったときだけ残す。光が出ない・おかしいときに、どちらの描き方かを切り分けるため
                LOGGER.info(inUse
                        ? "シェーダーパックが使われている（光の効果を描くかは設定 headlightWithShaderPacks しだい）"
                        : "シェーダーパックは使われていない。ヘッドライトを画素ごとに描く");
                last = inUse;
            }
            return inUse;
        }

        private static Boolean last;

        /**
         * Oculus / Iris が<b>影を描いている最中</b>か。パックは影のために光源の視点からもエンティティを
         * 描くので、その間に光芒を記録すると、光源の視点の座標が混ざって変な場所に描かれる。
         * 聞けなければ false（影のパスが無い OptiFine やパック無しと同じ扱い）。
         */
        static boolean renderingShadowPass() {
            if (!resolved) {
                resolve();
            }
            if (irisShadowPass == null) {
                return false;
            }
            try {
                return (Boolean) irisShadowPass.invoke(irisApi);
            } catch (ReflectiveOperationException | RuntimeException e) {
                irisShadowPass = null;
                return false;
            }
        }

        private static void resolve() {
            resolved = true;
            boolean iris = ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
            if (iris) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = api.getMethod("getInstance").invoke(null);
                    irisInUse = api.getMethod("isShaderPackInUse");
                    irisShadowPass = api.getMethod("isRenderingShadowPass");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOGGER.warn("Oculus / Iris の API が見つからない。シェーダーパックが使われているものとして扱う", e);
                    unknown = true;
                }
            }
            try {
                // OptiFine は Forge の MOD 一覧に載らないので、クラスがあるかで見る
                Class<?> config = Class.forName("net.optifine.Config");
                optifineInUse = config.getMethod("isShaders");
            } catch (ClassNotFoundException e) {
                // OptiFine は入っていない
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("OptiFine のシェーダーの状態が聞けない。使われているものとして扱う", e);
                unknown = true;
            }
        }
    }

    /** シェーダーの登録。<b>mod のイベントバス</b>に飛んでくる。 */
    @Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            loadAttempted = false;
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                                new ResourceLocation(Kurumamod.MODID, "headlight_volume"),
                                DefaultVertexFormat.POSITION),
                        loaded -> shader = loaded);
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                                new ResourceLocation(Kurumamod.MODID, "headlight_shadow"),
                                DefaultVertexFormat.POSITION),
                        loaded -> shadowShader = loaded);
            } catch (IOException e) {
                // 光が描けないだけで遊べなくはない。形を積む代用に落ちる
                LOGGER.error("ヘッドライトのシェーダーを読めなかった。形を積む描き方で代用する", e);
                shader = null;
                shadowShader = null;
            }
        }
    }

    /**
     * このフレームのカメラの向き。{@code AFTER_SKY} で覚えておく。
     *
     * <p><b>{@code AFTER_LEVEL} の {@code getPoseStack()} にはカメラの向きが入っていない。</b>
     * あの段は {@code LevelRenderer#renderLevel} の外（{@code GameRenderer}）から投げられ、
     * 渡ってくるのは世界の描画に使ったものとは別の PoseStack（バイトコードで確認）。
     * そのまま使うと画面から世界へ戻す行列がカメラの向きを失い、<b>光がどこにも出なかった</b>。
     * {@code AFTER_SKY} は {@code renderLevel} の中なので、世界の描画と同じ向きが入っている。</p>
     */
    private static final Matrix4f VIEW = new Matrix4f();
    private static boolean viewCaptured;
    private static boolean announced;

    /**
     * 粒（タイヤの煙・土煙）を描く<b>前</b>の画面の色と深度。
     *
     * <p><b>粒は深度を書く。</b>描き終えた画面の深度だけで光を解くと、煙が壁として扱われ、
     * 光線は煙のところで止まり、その奥の光がまるごと消える（煙越しに見ると光が打ち消されて
     * 見えた）。粒の前の深度で光線と路面を解き、粒は「描く前と後の差」＝その粒が画素に
     * 足した色として別に照らす。</p>
     */
    private static TextureTarget solid;
    private static boolean solidCaptured;
    private static int lampCount;

    /** 粒の直前（AFTER_TRIPWIRE_BLOCKS）で覚えた、このフレームの射影・カメラ・補間の割合。 */
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static Vec3 frameCamera = Vec3.ZERO;
    private static float framePartialTick;
    /** このフレームの光をもう描いたか（シェーダーパックの仕上げ前か、AFTER_LEVEL のどちらか一方で描く）。 */
    private static boolean renderedThisFrame;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // ミラーの描画中は鏡の視点が入ってくるので覚えない
            if (!MirrorRenderer.isRendering()) {
                VIEW.set(event.getPoseStack().last().pose());
                viewCaptured = true;
                solidCaptured = false;
                renderedThisFrame = false;
            }
            return;
        }
        if (!isActive() || MirrorRenderer.isRendering() || !viewCaptured) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();

        if (stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            // 粒の直前。ランプが無ければ写さない（写すぶんだけ無駄）
            lampCount = collect(level, event.getCamera().getPosition(), event.getPartialTick());
            PROJECTION.set(event.getProjectionMatrix());
            frameCamera = event.getCamera().getPosition();
            framePartialTick = event.getPartialTick();
            if (lampCount > 0) {
                solid = snapshot(solid, main);
                solidCaptured = true;
            }
            return;
        }
        if (stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        viewCaptured = false;
        // シェーダーパックの仕上げの前に描けていれば、ここでは描かない
        if (!renderedThisFrame) {
            render(level, main, -1);
        }
    }

    /**
     * シェーダーパック（Oculus / Iris）が仕上げ（composite・final）を流す<b>直前</b>に呼ばれる
     * （{@code IrisPipelineMixin}）。ここで仕上げ前の絵（colortex0）に光を入れれば、パックが霧・ブルーム・
     * トーンマップを一度だけ掛けてくれる。
     *
     * <p><b>Forge の {@code AFTER_LEVEL} は仕上げの後に飛ぶ</b>（{@code GameRenderer} から投げられ、
     * Iris の仕上げは {@code renderLevel} の最後）。そこで描いていた頃は、仕上がった絵（月明かり・霧・
     * トーンマップ済み）を明るくするしかなく、暗い所のブロックの色が残っていないので色褪せた。</p>
     *
     * <p>パックの絵が読めなければ何もしない（{@code AFTER_LEVEL} で今までどおり描かれる）。
     * GL の状態は Iris がこのあと自分の枠で描くので、書き込み先と描く範囲だけ元に戻す。</p>
     */
    public static void renderBeforePackComposite() {
        if (renderedThisFrame || !viewCaptured || !isActive() || MirrorRenderer.isRendering()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int packScene = IrisScene.colorTexture();
        if (packScene < 0) {
            return;
        }
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            render(mc.level, mc.getMainRenderTarget(), packScene);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /**
     * 光を描く。{@code packScene} が 0 以上なら、シェーダーパックの仕上げ前の絵を読んでそこへ書き戻す。
     * 負なら主画面を写して主画面へ描く（パック無し、またはパックの絵が読めないとき）。
     */
    private static void render(ClientLevel level, RenderTarget main, int packScene) {
        int count = lampCount;
        if (count == 0 || !solidCaptured) {
            return;
        }
        renderedThisFrame = true;
        float partialTick = framePartialTick;
        int width = main.width;
        int height = main.height;
        // 深度はパックを使っていても主画面のもの（Iris は Minecraft の深度をそのまま使う）
        copy = snapshot(copy, main);
        int packColor = packScene >= 0 ? IrisScene.copy(packScene) : -1;
        main.bindWrite(true);

        // 影。ブロックの格子を少し読み直してから、ランプから見た距離の地図を描く
        Vec3 camera = frameCamera;
        HeadlightShadows.update(level, camera);
        HeadlightShadows.uploadLamps(LAMPS, count);
        HeadlightShadows.renderMap(shadowShader, count, camera, main);

        // 画面の座標から、カメラを原点にした世界の向きの座標へ戻す行列
        Matrix4f inverse = new Matrix4f(PROJECTION)
                .mul(VIEW)
                .invert();

        shader.setSampler("SceneSampler", packColor >= 0 ? packColor : copy.getColorTextureId());
        shader.setSampler("FinalDepthSampler", copy.getDepthTextureId());
        shader.setSampler("SolidSampler", packColor >= 0 ? packColor : solid.getColorTextureId());
        shader.setSampler("DepthSampler", solid.getDepthTextureId());
        shader.setSampler("ShadowSampler", HeadlightShadows.mapTexture());
        shader.setSampler("VoxelSampler", HeadlightShadows.voxelTexture());
        HeadlightShadows.setGridUniforms(shader, camera);
        shader.safeGetUniform("ShadowSize").set((float) HeadlightShadows.MAP_WIDTH,
                (float) HeadlightShadows.mapHeight(count));
        shader.safeGetUniform("InvViewProj").set(inverse);
        shader.setSampler("LampSampler", HeadlightShadows.lampTexture());
        shader.safeGetUniform("LampCount").set(count);
        shader.safeGetUniform("LightRange").set((float) com.jdmmc.kurumamod.ClientConfig.headlightRange);
        shader.safeGetUniform("FogDensity").set(fogDensity(level, partialTick));
        shader.safeGetUniform("ScreenSize").set((float) width, (float) height);
        shader.safeGetUniform("SurfaceGain").set((float) com.jdmmc.kurumamod.ClientConfig.headlightSurface);
        shader.safeGetUniform("VolumeGain").set((float) com.jdmmc.kurumamod.ClientConfig.headlightBeam);
        shader.safeGetUniform("SmokeGain").set((float) com.jdmmc.kurumamod.ClientConfig.headlightSmoke);
        // シェーダーパックの仕上げ前の絵に描くときは明るくし方を変える
        shader.safeGetUniform("PackMode").set(packScene >= 0 ? 1.0F : 0.0F);
        shader.safeGetUniform("PackSurface").set((float) com.jdmmc.kurumamod.ClientConfig.headlightPackSurface);
        shader.safeGetUniform("PackContrast").set((float) com.jdmmc.kurumamod.ClientConfig.headlightPackContrast);
        shader.safeGetUniform("PackTint").set((float) com.jdmmc.kurumamod.ClientConfig.headlightPackTint);
        shader.safeGetUniform("PackBeam").set((float) com.jdmmc.kurumamod.ClientConfig.headlightPackBeam);
        shader.safeGetUniform("PackSmoke").set((float) com.jdmmc.kurumamod.ClientConfig.headlightPackSmoke);
        shader.safeGetUniform("PackAdd").set((float) com.jdmmc.kurumamod.ClientConfig.headlightPackAdd);
        float[] lightColor = com.jdmmc.kurumamod.ClientConfig.headlightColor.rgb();
        shader.safeGetUniform("LightColor").set(lightColor[0], lightColor[1], lightColor[2]);

        // 画面いっぱいに 1 枚。色はシェーダーが全部決めるので混色も深度も要らない
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        if (packScene >= 0) {
            IrisScene.bindForWrite(packScene);
        }
        RenderSystem.setShader(() -> shader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.vertex(-1.0, -1.0, 0.0).endVertex();
        builder.vertex(1.0, -1.0, 0.0).endVertex();
        builder.vertex(1.0, 1.0, 0.0).endVertex();
        builder.vertex(-1.0, 1.0, 0.0).endVertex();
        BufferUploader.drawWithShader(builder.end());
        if (packScene >= 0) {
            main.bindWrite(true);
        }
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        if (!announced) {
            // 光が見えないときに「描いていない」のか「描いているが外れている」のかを切り分けるため
            announced = true;
            LOGGER.info("ヘッドライトを画素ごとに描き始めた（{} 灯、{}x{}）", count, width, height);
        }
    }

    /**
     * 主画面の色と深度を {@code target} へ写す（大きさが違えば作り直す）。
     *
     * <p>書いている最中の画面を同時に読むことはできないので、読むのは写しのほう。</p>
     */
    private static TextureTarget snapshot(TextureTarget target, RenderTarget main) {
        int width = main.width;
        int height = main.height;
        if (target == null) {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        // ステンシルの有無が違うと深度を写せない（形式が一致しないと blit が失敗する）
        if (main.isStencilEnabled() && !target.isStencilEnabled()) {
            target.enableStencil();
        }
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
                GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        main.bindWrite(true);
        return target;
    }

    /** 近い車から順に、ヘッドライトを点けている車のランプを詰める。 */
    private static int collect(ClientLevel level, Vec3 camera, float partialTick) {
        List<CarEntity> cars = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car && car.isHeadlightOn()
                    && car.position().distanceToSqr(camera) < MAX_DISTANCE * MAX_DISTANCE) {
                cars.add(car);
            }
        }
        cars.sort(Comparator.comparingDouble(car -> car.position().distanceToSqr(camera)));
        int wanted = com.jdmmc.kurumamod.ClientConfig.headlightCars;
        int limit = wanted >= com.jdmmc.kurumamod.ClientConfig.UNLIMITED_HEADLIGHT_CARS
                ? HeadlightShadows.maxLamps()
                : Math.min(HeadlightShadows.maxLamps(), Math.max(1, wanted) * 2);
        int count = 0;
        int taken = 0;
        for (CarEntity car : cars) {
            if (count >= limit || taken >= limit / 2) {
                break;
            }
            count += CarLights.collectLamps(car, partialTick, camera, LAMPS, count);
            taken++;
        }
        return Math.min(count, limit);
    }

    private static float fogDensity(ClientLevel level, float partialTick) {
        // 晴れの濃さは調整画面（Ctrl+L、HeadlightScreen）で動かせる。既定は CLEAR_FOG
        return (float) com.jdmmc.kurumamod.ClientConfig.headlightFog + RAIN_FOG * level.getRainLevel(partialTick)
                + THUNDER_FOG * level.getThunderLevel(partialTick);
    }

    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;
    private static final int GL_DEPTH_BUFFER_BIT = 0x0100;
    private static final int GL_NEAREST = 0x2600;
}
