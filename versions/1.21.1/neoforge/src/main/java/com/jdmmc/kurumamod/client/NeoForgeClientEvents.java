package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.hook.CameraAngles;
import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.FovModifier;
import com.jdmmc.kurumamod.client.hook.FrameRender;
import com.jdmmc.kurumamod.client.hook.GuiRender;
import com.jdmmc.kurumamod.client.hook.HandRender;
import com.jdmmc.kurumamod.client.hook.LevelRenderStage;
import com.jdmmc.kurumamod.client.hook.LivingRender;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * NeoForge のクライアントのイベントを受けて、共有側（{@link ClientHooks}）へ渡すだけのつなぎ。
 *
 * <p>どのクラスをどの順に呼ぶかは {@link ClientHooks} が決める。ここでは NeoForge のイベントを
 * 共有側の形（{@code client/hook/}）に包み、書き換えられた値をイベントへ戻す。<b>優先度は元のハンドラと
 * 同じにしてある</b>（ほかの MOD との前後を変えないため）。Fabric 版の同じ役目は versions/1.21.1/fabric/ の
 * {@code FabricClientEvents} と mixin。</p>
 */
@EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 同じ段のハンドラに同じ PoseStack を渡すため、直前に包んだイベントを覚えておく（優先度ごとに別の受け口なので）。 */
    private static RenderLevelStageEvent lastStageEvent;
    private static LevelRenderStage lastStage;

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
        HandRender hand = new HandRender();
        ClientHooks.renderHand(hand);
        if (hand.isCanceled()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelNormal(RenderLevelStageEvent event) {
        LevelRenderStage stage = wrap(event);
        if (stage != null) {
            ClientHooks.renderLevelStageNormal(stage);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderLevelLow(RenderLevelStageEvent event) {
        LevelRenderStage stage = wrap(event);
        if (stage != null) {
            ClientHooks.renderLevelStageLow(stage);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLevelLowest(RenderLevelStageEvent event) {
        LevelRenderStage stage = wrap(event);
        if (stage != null) {
            ClientHooks.renderLevelStageLowest(stage);
        }
    }

    /** 共有側が使う 4 つの段だけを包む。それ以外の段は誰も見ていないので null。 */
    private static LevelRenderStage wrap(RenderLevelStageEvent event) {
        if (event == lastStageEvent) {
            return lastStage;
        }
        LevelRenderStage.Stage stage;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            stage = LevelRenderStage.Stage.AFTER_SKY;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            stage = LevelRenderStage.Stage.AFTER_TRANSLUCENT_BLOCKS;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            stage = LevelRenderStage.Stage.AFTER_TRIPWIRE_BLOCKS;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            stage = LevelRenderStage.Stage.AFTER_LEVEL;
        } else {
            stage = null;
        }
        lastStageEvent = event;
        lastStage = stage == null ? null : new LevelRenderStage(stage, event.getModelViewMatrix(),
                event.getProjectionMatrix(), event.getPartialTick(), event.getCamera());
        return lastStage;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        ClientHooks.renderLivingPreLowest(new LivingRender(event.getEntity(), event.getPartialTick(), event.getPoseStack()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        ClientHooks.renderLivingPostHighest(new LivingRender(event.getEntity(), event.getPartialTick(), event.getPoseStack()));
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        ClientHooks.renderFramePre(new FrameRender(event.getPartialTick()));
    }

    /** MOD のイベントバスに飛んでくるもの。 */
    @EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {
        }

        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            try {
                ClientHooks.registerShaders((id, format, onLoad) ->
                        event.registerShader(new ShaderInstance(event.getResourceProvider(), id, format), onLoad));
            } catch (IOException e) {
                LOGGER.error("シェーダーを登録できなかった", e);
            }
        }

        @SubscribeEvent
        public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
            ClientHooks.soundEngineLoad();
        }
    }
}
