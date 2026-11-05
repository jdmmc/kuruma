package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.hook.CameraAngles;
import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.FovModifier;
import com.jdmmc.kurumamod.client.hook.FrameRender;
import com.jdmmc.kurumamod.client.hook.GuiRender;
import com.jdmmc.kurumamod.client.hook.HandRender;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;


/**
 * NeoForge のクライアントのイベントを受けて、共有側（{@link ClientHooks}）へ渡すだけのつなぎ。
 *
 * <p>どのクラスをどの順に呼ぶかは {@link ClientHooks} が決める。ここでは NeoForge のイベントを
 * 共有側の形（{@code client/hook/}）に包み、書き換えられた値をイベントへ戻す。<b>優先度は元のハンドラと
 * 同じにしてある</b>（ほかの MOD との前後を変えないため）。Fabric 版の同じ役目は versions/26.3/fabric/ の
 * {@code KurumamodClient} と mixin、Forge 版は {@code ForgeClientEvents}。
 * ワールドの描画の段はここでは受けない（common の mixin が受け持つ）。</p>
 */
@EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {

    private NeoForgeClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        ClientHooks.clientTickPre();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        ClientHooks.clientTickPost();
    }

    @SubscribeEvent
    public static void onComputeCameraAnglesNormal(ViewportEvent.ComputeCameraAngles event) {
        CameraAngles angles = wrap(event);
        ClientHooks.computeCameraAnglesNormal(angles);
        unwrap(angles, event);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onComputeCameraAnglesLow(ViewportEvent.ComputeCameraAngles event) {
        CameraAngles angles = wrap(event);
        ClientHooks.computeCameraAnglesLow(angles);
        unwrap(angles, event);
    }

    private static CameraAngles wrap(ViewportEvent.ComputeCameraAngles event) {
        return new CameraAngles(event.getCamera(), event.getPartialTick(), event.getYaw(), event.getPitch(), event.getRoll());
    }

    private static void unwrap(CameraAngles angles, ViewportEvent.ComputeCameraAngles event) {
        event.setYaw(angles.getYaw());
        event.setPitch(angles.getPitch());
        event.setRoll(angles.getRoll());
    }

    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        FovModifier fov = new FovModifier(event.getPlayer(), event.getFovModifier());
        fov.setNewFovModifier(event.getNewFovModifier());
        ClientHooks.computeFov(fov);
        event.setNewFovModifier(fov.getNewFovModifier());
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        ClientHooks.entityJoin(event.getEntity(), event.getLevel());
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientHooks.loggingIn();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientHooks.loggingOut();
    }

    /** HUD の層をすべて描いた後（チャットより後。26.3 の GUI は描いた順で重なる）。 */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        ClientHooks.renderGuiPost(new GuiRender(event.getGuiGraphics(), event.getPartialTick()));
    }

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Post event) {
        if (event.getName().equals(VanillaGuiLayers.HOTBAR)) {
            ClientHooks.renderHotbarPost(new GuiRender(event.getGuiGraphics(), event.getPartialTick()));
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        HandRender hand = new HandRender(event.getHand());
        ClientHooks.renderHand(hand);
        if (hand.isCanceled()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        ClientHooks.renderFramePre(new FrameRender(event.getPartialTick()));
    }

    /** MOD のイベントバスに飛んでくるもの（26.3 の NeoForge は自動で MOD バスへ振り分ける。分けてあるのは読みやすさのため）。 */
    @EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
    public static final class ModBus {

        private ModBus() {
        }

        /** 描画のパイプラインを先に登録する（読み込み中にまとめてコンパイルされる）。 */
        @SubscribeEvent
        public static void onRegisterPipelines(RegisterRenderPipelinesEvent event) {
            ClientHooks.registerShaders(event::registerPipeline);
        }

        @SubscribeEvent
        public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
            ClientHooks.soundEngineLoad();
        }
    }
}
