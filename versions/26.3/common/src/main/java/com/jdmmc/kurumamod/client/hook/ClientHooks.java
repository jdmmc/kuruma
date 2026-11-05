package com.jdmmc.kurumamod.client.hook;

import com.jdmmc.kurumamod.client.CarCamera;
import com.jdmmc.kurumamod.client.CarChaseCamera;
import com.jdmmc.kurumamod.client.CarClientDriver;
import com.jdmmc.kurumamod.client.CarCockpitCamera;
import com.jdmmc.kurumamod.client.CarDust;
import com.jdmmc.kurumamod.client.CarGaugeHud;
import com.jdmmc.kurumamod.client.CarGhosts;
import com.jdmmc.kurumamod.client.CarHelp;
import com.jdmmc.kurumamod.client.CarHud;
import com.jdmmc.kurumamod.client.CarIcons;
import com.jdmmc.kurumamod.client.CarImpact;
import com.jdmmc.kurumamod.client.CarItemRenderer;
import com.jdmmc.kurumamod.client.CarModel;
import com.jdmmc.kurumamod.client.CarPartModel;
import com.jdmmc.kurumamod.client.CarPresets;
import com.jdmmc.kurumamod.client.CarSoundSet;
import com.jdmmc.kurumamod.client.ObjModel;
import com.jdmmc.kurumamod.client.PlateModel;
import com.jdmmc.kurumamod.client.PlateMounts;
import com.jdmmc.kurumamod.client.PlateTextures;
import com.jdmmc.kurumamod.client.CarSkidMarks;
import com.jdmmc.kurumamod.client.CarSmoke;
import com.jdmmc.kurumamod.client.CarSounds;
import com.jdmmc.kurumamod.client.CarTurbo;
import com.jdmmc.kurumamod.client.CourseRenderer;
import com.jdmmc.kurumamod.client.EffectRenderTypes;
import com.jdmmc.kurumamod.client.MirrorRenderer;
import com.jdmmc.kurumamod.client.RaceHud;
import com.jdmmc.kurumamod.client.SteeringHands;
import com.jdmmc.kurumamod.client.TireSmokeVolume;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;

import java.util.function.BiConsumer;

/**
 * クライアント側の割り込み先の一覧。<b>どの場面で、どのクラスを、どの順に呼ぶかはここにだけ書く。</b>
 *
 * <p>各ローダーのつなぎ（NeoForge 版はイベント、Fabric 版は Fabric API の受け口と mixin）は、
 * 場面が来たらここを呼ぶだけ。順番を 2 か所に書くと、片方だけ直して食い違う。</p>
 *
 * <p><b>生き物の描画の前後もここに無い。</b>乗員の姿勢は common の mixin（{@code RiderExtractMixin}・{@code RiderRenderMixin}）が
 * 受け持つので、ローダーの {@code RenderLivingEvent} は使わない。</p>
 *
 * <p><b>ワールドの描画の段（{@code LevelRenderStage}）はここに無い。</b>26.3 では 3 つのローダーで同じに動かすため、
 * common の mixin（{@code LevelStageHooks}）が {@code LevelRenderer} に割り込んで呼ぶ。ローダーのイベントは使わない。</p>
 *
 * <p><b>メソッド名の後ろの Normal / Low / Lowest / Highest は、NeoForge の {@code EventPriority}。</b>
 * ほかの MOD のハンドラとの前後を今のまま保つために分けてある（NeoForge 版はそれぞれをその優先度で
 * 受ける）。Fabric 版はこの順に続けて呼ぶ。同じ優先度の中の順番は NeoForge では決まっていなかったので、
 * ここではクラス名の順にしてある。</p>
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    /** {@code Minecraft#tick} の先頭。 */
    public static void clientTickPre() {
        CarClientDriver.onClientTick();
        CarGhosts.onClientTickPre();
    }

    /** {@code Minecraft#tick} の終わり。 */
    public static void clientTickPost() {
        CarCamera.onClientTick();
        CarDust.onClientTick();
        CarGhosts.onClientTickPost();
        CarImpact.onClientTick();
        CarSkidMarks.onClientTick();
        CarSmoke.onClientTick();
        CarSounds.onClientTick();
        CarTurbo.onClientTick();
        RaceHud.onClientTick();
        TireSmokeVolume.onClientTick();
    }

    /** カメラの向き。衝突の揺れ（普通）を先に足し、そのあと視点ごとの向き（低）で上書きする。 */
    public static void computeCameraAnglesNormal(CameraAngles event) {
        CarImpact.onComputeCameraAngles(event);
    }

    public static void computeCameraAnglesLow(CameraAngles event) {
        CarChaseCamera.onComputeCameraAngles(event);
        CarCockpitCamera.onComputeCameraAngles(event);
    }

    public static void computeFov(FovModifier event) {
        CarCamera.onComputeFov(event);
    }

    /** クライアントのワールドに実体が現れた。 */
    public static void entityJoin(Entity entity, Level level) {
        CarSounds.onEntityJoin(entity, level);
    }

    /** サーバーにつないだ（ワールドに入った）。 */
    public static void loggingIn() {
        CarSkidMarks.onLoggingIn();
        CourseRenderer.onLoggingIn();
        RaceHud.onLoggingIn();
        TireSmokeVolume.onLoggingIn();
    }

    /** サーバーから抜けた。 */
    public static void loggingOut() {
        CarGhosts.onLoggingOut();
        CarSkidMarks.onLoggingOut();
        CourseRenderer.onLoggingOut();
        RaceHud.onLoggingOut();
        TireSmokeVolume.onLoggingOut();
    }

    /**
     * 描画のパイプライン（{@code RenderPipeline}）を先に登録して、リソースの読み込み中にまとめてコンパイルさせる。
     *
     * <p>受け口があるのは NeoForge だけ（{@code RegisterRenderPipelinesEvent}）。Fabric 版・Forge 版は呼ばない
     * ——登録しなかったパイプラインは、初めて使うときにその場でコンパイルされる（少し引っかかるだけで動く）。</p>
     */
    public static void registerShaders(ShaderRegistrar registrar) {
        EffectRenderTypes.Registration.onRegisterShaders(registrar);
        TireSmokeVolume.Registration.onRegisterShaders(registrar);
    }

    /**
     * アイテムの特別な描画（{@code SpecialModelRenderer}）の種類を登録する。{@code sink} には
     * 「{@code items/*.json} の {@code "type"} に書く ID」と、その描画の素（{@code Unbaked}）の MapCodec を渡す。
     *
     * <p>NeoForge 版は {@code RegisterSpecialModelRendererEvent}、Fabric 版・Forge 版は
     * {@code SpecialModelRenderers#bootstrap} の終わりへの mixin が呼ぶ。</p>
     *
     * <p>車のアイテムの見た目は車のモデルから作る（{@link CarItemRenderer}）。カーパックがアイコンの絵を用意しなくても、
     * どの車種も自分の形で並ぶ。</p>
     */
    public static void registerSpecialModelRenderers(
            BiConsumer<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>> sink) {
        sink.accept(CarItemRenderer.ID, CarItemRenderer.Unbaked.MAP_CODEC);
    }

    /**
     * HUD をすべて描き終えた後（チャットより後）。
     *
     * <p><b>26.3 の GUI には奥行き（Z）が無く、描いた順で重なりが決まる。</b>メーターや操作ヘルプがチャットの
     * 下に潜らないよう、どのローダーもチャットを描いた後で呼ぶこと。</p>
     */
    public static void renderGuiPost(GuiRender event) {
        CarGaugeHud.onRenderGui(event);
        CarHelp.onRenderGui(event);
        CarHud.onRenderGui(event);
    }

    /** ホットバーの層を描き終えた直後（チャットなどより下）。 */
    public static void renderHotbarPost(GuiRender event) {
        RaceHud.onRenderOverlay(event);
    }

    /** 一人称の手を 1 本描く前。 */
    public static void renderHand(HandRender event) {
        SteeringHands.onRenderHand(event);
    }

    /** 1 フレームを描き始める前。 */
    public static void renderFramePre(FrameRender event) {
        CarIcons.onRenderTick(event);
        MirrorRenderer.onRenderTick(event);
    }

    /**
     * リソースを読み直した（F3+T など）。OBJ と見た目の定義も読み直す。
     *
     * <p>これがあると、Blender から書き出し直して<b>ゲーム内で F3+T を押すだけ</b>で
     * 新しいモデルが反映される。モデルを詰めている間はこれが効く。</p>
     */
    public static void resourcesReloaded() {
        ObjModel.clearCache();
        CarModel.clearCache();
        CarSoundSet.clearReports();
        CarPartModel.clearCache();
        CarPresets.clearCache();
        CarIcons.clearCache();
        PlateModel.clearCache();
        PlateMounts.clearCache();
        PlateTextures.clearCache();
    }

    /** 音のエンジンを作った・読み直した直後。 */
    public static void soundEngineLoad() {
        CarSounds.EngineReload.onSoundEngineLoad();
    }
}
