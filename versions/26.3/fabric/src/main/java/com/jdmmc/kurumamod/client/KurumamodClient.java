package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.Platform;
import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.GuiRender;
import com.jdmmc.kurumamod.network.KurumaNetworkClient;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;

/**
 * Fabric 版のクライアントの入り口（{@code fabric.mod.json} の {@code client}）。
 *
 * <p>どの場面でどのクラスをどの順に呼ぶかは共有側の {@link ClientHooks} が決める。ここは Fabric API の受け口を
 * つなぐだけ。Fabric API に受け口が無い場面（カメラの向き・視野・手・フレームの始まり・音のエンジン・
 * アイテムの特別な描画の登録）は {@code fabric.mixin} の各 mixin が呼ぶ。ワールドの描画の段は common の mixin が受け持つ。
 * NeoForge 版の同じ役目は {@code NeoForgeClientEvents} と {@code Kurumamod.ClientModEvents}、Forge 版は {@code ForgeClientEvents}。</p>
 */
public final class KurumamodClient implements ClientModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitializeClient() {
        // 画面の好み（kurumamod-client.toml）。NeoForge 版と同じファイル・同じ書き方
        ClientConfig.load(Platform.configDir());

        KurumaKeys.register();
        KurumaNetworkClient.register();

        EntityRendererRegistry.register(Kurumamod.CAR.get(), CarObjRenderer::new);

        // 粒の種類は両側で登録してある（KurumaParticles）。描く係だけがクライアント側
        ParticleProviderRegistry particles = ParticleProviderRegistry.getInstance();
        particles.register(KurumaParticles.DUST.get(), sprites -> new DustParticle.Provider(sprites, DustParticle.DUST));
        particles.register(KurumaParticles.TIRE_SMOKE.get(), sprites -> new DustParticle.Provider(sprites, DustParticle.TIRE_SMOKE));
        particles.register(KurumaParticles.SPARK.get(), SparkParticle.Provider::new);

        // 車のアイテムの見た目（アイテムの特別な描画）は fabric.mixin.SpecialModelRenderersMixin が登録する。
        // 描画のパイプラインは Fabric に先に登録する口が無いので、初めて使うときにコンパイルされる

        // リソースを読み直したら OBJ と見た目の定義も読み直す（F3+T でモデルが変わる）
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath(Kurumamod.MODID, "client_models"),
                (ResourceManagerReloadListener) manager -> ClientHooks.resourcesReloaded());

        ClientTickEvents.START_CLIENT_TICK.register(client -> ClientHooks.clientTickPre());
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientHooks.clientTickPost());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientHooks.loggingIn());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientHooks.loggingOut());
        ClientEntityEvents.ENTITY_LOAD.register(ClientHooks::entityJoin);
        // レースの表示はホットバーの直後（チャットより下）。メーターと操作ヘルプはいちばん最後（チャットより後。
        // 26.3 の GUI は描いた順で重なる）
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath(Kurumamod.MODID, "race_overlay"),
                (graphics, delta) -> ClientHooks.renderHotbarPost(new GuiRender(graphics, delta)));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Kurumamod.MODID, "hud"),
                (graphics, delta) -> ClientHooks.renderGuiPost(new GuiRender(graphics, delta)));

        LOGGER.info("KurumaMod（Fabric）のクライアント側を準備した");
    }
}
