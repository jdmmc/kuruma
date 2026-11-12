package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.hook.CameraAngles;
import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.FovModifier;
import com.jdmmc.kurumamod.client.hook.FrameRender;
import com.jdmmc.kurumamod.client.hook.GuiRender;
import com.jdmmc.kurumamod.client.hook.HandRender;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.function.Consumer;
import com.jdmmc.kurumamod.client.screen.KurumaMenuScreen;
import com.jdmmc.kurumamod.render.CarObjRenderer;
import com.jdmmc.kurumamod.render.DustParticle;
import com.jdmmc.kurumamod.platform.McUiScreen;
import com.jdmmc.kurumamod.render.SparkParticle;

/**
 * Forge のクライアントのイベントを受けて、共有側（{@link ClientHooks}）へ渡すだけのつなぎ。
 *
 * <p>どのクラスをどの順に呼ぶかは {@link ClientHooks} が決める。ここでは Forge のイベントを共有側の形
 * （{@code client/hook/}）に包み、書き換えられた値をイベントへ戻す。<b>優先度は NeoForge 版と同じにしてある。</b>
 * NeoForge 版の同じ役目は {@code NeoForgeClientEvents}、Fabric 版は {@code KurumamodClient} と mixin。
 * ワールドの描画の段はここでは受けない（common の mixin が受け持つ）。</p>
 *
 * <p>Forge に受け口が無いもの（アイテムの特別な描画の登録）は
 * {@code forge.mixin} の mixin が呼ぶ。</p>
 */
public final class ForgeClientEvents {

    private static final Identifier HUD = Identifier.fromNamespaceAndPath(Kurumamod.MODID, "hud");
    private static final Identifier RACE_OVERLAY = Identifier.fromNamespaceAndPath(Kurumamod.MODID, "race_overlay");

    private ForgeClientEvents() {
    }

    /** クライアントで 1 回だけ呼ぶ（{@code Kurumamod} のコンストラクタ）。 */
    public static void register(FMLJavaModLoadingContext context) {
        KurumaKeys.register();

        // Mod 一覧の「Config」ボタンから H キーと同じ設定画面を開く（設定の置き場所は 1 つに保つ）
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(parent -> new McUiScreen(new KurumaMenuScreen(parent))));

        EntityRenderersEvent.RegisterRenderers.BUS.addListener(event ->
                event.registerEntityRenderer(Kurumamod.CAR.get(), CarObjRenderer::new));

        // 粒の種類は両側で登録してある（KurumaParticles）。描く係だけがクライアント側
        RegisterParticleProvidersEvent.BUS.addListener(event -> {
            event.registerSpriteSet(KurumaParticles.DUST.get(),
                    sprites -> new DustParticle.Provider(sprites, DustParticle.DUST));
            event.registerSpriteSet(KurumaParticles.TIRE_SMOKE.get(),
                    sprites -> new DustParticle.Provider(sprites, DustParticle.TIRE_SMOKE));
            event.registerSpriteSet(KurumaParticles.SPARK.get(), SparkParticle.Provider::new);
        });

        // リソースを読み直したら OBJ と見た目の定義も読み直す（F3+T でモデルが変わる）
        RegisterClientReloadListenersEvent.BUS.addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) resourceManager -> ClientHooks.resourcesReloaded()));

        SoundEngineLoadEvent.BUS.addListener(event -> ClientHooks.soundEngineLoad());

        TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> ClientHooks.clientTickPre());
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> ClientHooks.clientTickPost());
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> ClientHooks.renderFramePre(new FrameRender(event.timer())));

        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(event -> ClientHooks.loggingIn());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> ClientHooks.loggingOut());
        // 取り消せるイベントなので、Consumer だと明示する（Predicate の受け口と区別がつかないため）
        Consumer<EntityJoinLevelEvent> onJoin = event -> ClientHooks.entityJoin(event.getEntity(), event.getLevel());
        EntityJoinLevelEvent.BUS.addListener(onJoin);

        // カメラの向き。衝突の揺れ（普通）を先に足し、そのあと視点ごとの向き（低）で上書きする
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            CameraAngles angles = wrap(event);
            ClientHooks.computeCameraAnglesNormal(angles);
            unwrap(angles, event);
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(Priority.LOW, event -> {
            CameraAngles angles = wrap(event);
            ClientHooks.computeCameraAnglesLow(angles);
            unwrap(angles, event);
        });

        ComputeFovModifierEvent.BUS.addListener(event -> {
            FovModifier fov = new FovModifier(event.getPlayer(), event.getFovModifier());
            fov.setNewFovModifier(event.getNewFovModifier());
            ClientHooks.computeFov(fov);
            event.setNewFovModifier(fov.getNewFovModifier());
        });

        // 手を描かないことにしたら true を返す（取り消し）
        RenderHandEvent.BUS.addListener((RenderHandEvent event) -> {
            HandRender hand = new HandRender(event.getHand());
            ClientHooks.renderHand(hand);
            return hand.isCanceled();
        });

        // HUD。26.3 の GUI は描いた順で重なるので、メーターと操作ヘルプはいちばん最後（チャットより後）に足す。
        // レースの表示はホットバーの直後（チャットより下）
        AddGuiOverlayLayersEvent.BUS.addListener(event -> {
            ForgeLayeredDraw root = event.getLayeredDraw();
            root.addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, RACE_OVERLAY, ForgeLayeredDraw.HOTBAR_AND_DECOS,
                    (graphics, delta) -> ClientHooks.renderHotbarPost(new GuiRender(graphics, delta)));
            root.add(HUD, (graphics, delta) -> ClientHooks.renderGuiPost(new GuiRender(graphics, delta)));
        });
    }

    private static CameraAngles wrap(ViewportEvent.ComputeCameraAngles event) {
        return new CameraAngles(event.getCamera(), event.getPartialTick(), event.getYaw(), event.getPitch(), event.getRoll());
    }

    private static void unwrap(CameraAngles angles, ViewportEvent.ComputeCameraAngles event) {
        event.setYaw(angles.getYaw());
        event.setPitch(angles.getPitch());
        event.setRoll(angles.getRoll());
    }
}
