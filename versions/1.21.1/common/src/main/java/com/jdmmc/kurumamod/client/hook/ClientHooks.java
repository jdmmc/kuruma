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
import com.jdmmc.kurumamod.client.CarMeshes;
import com.jdmmc.kurumamod.client.CarModel;
import com.jdmmc.kurumamod.client.CarPartModel;
import com.jdmmc.kurumamod.client.CarPresets;
import com.jdmmc.kurumamod.client.CarSoundEvents;
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
import com.jdmmc.kurumamod.client.DeferredGlows;
import com.jdmmc.kurumamod.client.HeadlightVolume;
import com.jdmmc.kurumamod.client.McRaceClient;
import com.jdmmc.kurumamod.client.McUiGraphics;
import com.jdmmc.kurumamod.client.MirrorRenderer;
import com.jdmmc.kurumamod.client.PartAnchors;
import com.jdmmc.kurumamod.client.RaceHud;
import com.jdmmc.kurumamod.client.RaceLobby;
import com.jdmmc.kurumamod.client.RiderPoseRenderer;
import com.jdmmc.kurumamod.client.SteeringHands;
import com.jdmmc.kurumamod.client.TireSmokeVolume;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.io.IOException;

/**
 * クライアント側の割り込み先の一覧。<b>どの場面で、どのクラスを、どの順に呼ぶかはここにだけ書く。</b>
 *
 * <p>各ローダーのつなぎ（NeoForge 版はイベント、Fabric 版は Fabric API の受け口と mixin）は、
 * 場面が来たらここを呼ぶだけ。順番を 2 か所に書くと、片方だけ直して食い違う。</p>
 *
 * <p><b>メソッド名の後ろの Normal / Low / Lowest / Highest は、NeoForge の {@code EventPriority}。</b>
 * ほかの MOD のハンドラとの前後を今のまま保つために分けてある（NeoForge 版はそれぞれをその優先度で
 * 受ける）。Fabric 版はこの順に続けて呼ぶ。同じ優先度の中の順番は NeoForge では決まっていなかったので、
 * ここではクラス名の順にしてある。</p>
 */
public final class ClientHooks {

    static {
        // レースのクライアント側（core）が Minecraft に触る口を置く。最初のティックより前に読み込まれる
        McRaceClient.install();
    }

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
        RaceLobby.onClientTick();
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
        RaceLobby.onLoggingIn();
        TireSmokeVolume.onLoggingIn();
    }

    /** サーバーから抜けた。 */
    public static void loggingOut() {
        CarGhosts.onLoggingOut();
        CarSkidMarks.onLoggingOut();
        CourseRenderer.onLoggingOut();
        RaceHud.onLoggingOut();
        RaceLobby.onLoggingOut();
        TireSmokeVolume.onLoggingOut();
    }

    /** コアシェーダーの登録（リソースを読み直すたび）。 */
    public static void registerShaders(ShaderRegistrar registrar) throws IOException {
        CarMeshes.Registration.onRegisterShaders(registrar);
        CarSkidMarks.Registration.onRegisterShaders(registrar);
        DeferredGlows.Registration.onRegisterShaders(registrar);
        HeadlightVolume.Registration.onRegisterShaders(registrar);
        TireSmokeVolume.Registration.onRegisterShaders(registrar);
    }

    /** HUD をすべて描き終えた後。 */
    public static void renderGuiPost(GuiRender event) {
        CarGaugeHud.onRenderGui(event);
        CarHelp.onRenderGui(event);
        CarHud.onRenderGui(event);
    }

    /** ホットバーの層を描き終えた直後（チャットなどより下）。 */
    public static void renderHotbarPost(GuiRender event) {
        McUiGraphics graphics = new McUiGraphics(event.getGuiGraphics());
        RaceHud.onRenderOverlay(graphics, event.getPartialTick().getGameTimeDeltaPartialTick(true));
        RaceLobby.onRenderOverlay(graphics);
    }

    /** 一人称の手を 1 本描く前。 */
    public static void renderHand(HandRender event) {
        SteeringHands.onRenderHand(event);
    }

    /** ワールドの描画の段（{@link LevelRenderStage}）。 */
    public static void renderLevelStageNormal(LevelRenderStage event) {
        HeadlightVolume.onRenderLevel(event);
        PartAnchors.onRenderLevel(event);
        RiderPoseRenderer.onRenderLevelStage(event);
    }

    public static void renderLevelStageLow(LevelRenderStage event) {
        CarSkidMarks.onRenderLevel(event);
        CourseRenderer.onRenderLevel(event);
        TireSmokeVolume.onRenderLevel(event);
    }

    /** 光芒・灯火の仕上げ。ほかのすべての描き込みの後。 */
    public static void renderLevelStageLowest(LevelRenderStage event) {
        DeferredGlows.onRenderLevel(event);
    }

    /** 生き物を描く前。ほかの MOD が姿勢をいじり終えた後に足すので最低。 */
    public static void renderLivingPreLowest(LivingRender event) {
        RiderPoseRenderer.onRenderLivingPre(event);
    }

    /** 生き物を描いた後。積んだ姿勢をほかの MOD より先に戻すので最高。 */
    public static void renderLivingPostHighest(LivingRender event) {
        RiderPoseRenderer.onRenderLivingPost(event);
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
        CarSoundEvents.clearReports();
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
