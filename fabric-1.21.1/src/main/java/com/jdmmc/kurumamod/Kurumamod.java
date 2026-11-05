package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypeLoader;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.hook.CommonHooks;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.part.CarPartLoader;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Fabric 版の入り口（{@code fabric.mod.json} の {@code main}）と、登録したものの置き場所。
 *
 * <p><b>NeoForge 版（neoforge-1.21.1/）にも同じ名前のこのクラスがあり、mc-1.21.1/ の共有コードは
 * {@link #MODID}・{@link #CAR}・{@link #CAR_ITEM} を見る。</b>欄の名前と型（{@code get()} で中身が取れること）は
 * 両方で揃えること。場面ごとの処理の中身は共有側（{@link CommonHooks}）にあり、ここは Fabric API の
 * 受け口から呼ぶだけ。</p>
 */
public class Kurumamod implements ModInitializer {

    public static final String MODID = "kurumamod";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 車両エンティティ "kurumamod:car"。{@link #onInitialize()} で登録する（それまでは null を返さないよう、読むのはその後）。 */
    public static Supplier<EntityType<CarEntity>> CAR;
    /** 車を出すアイテム "kurumamod:car"。 */
    public static Supplier<Item> CAR_ITEM;
    /** この MOD 用のクリエイティブタブ "kurumamod:kurumamod"。 */
    public static Supplier<CreativeModeTab> KURUMA_TAB;

    @Override
    public void onInitialize() {
        // 設定は NeoForge 版と同じ自前の読み書き（config/ConfigSpec）。ファイル名も同じ
        Config.load();

        CAR = Registrar.register(Registries.ENTITY_TYPE, "car",
                () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                        // 最初の大きさだけ。実際の箱は CarEntity#makeBoundingBox が車体の箱
                        // （CarHull）の内側に作り直す。地形との衝突は車体の箱が受け持つ
                        .sized(1.8F, 0.7F)
                        .clientTrackingRange(10)
                        .updateInterval(1) // 運転中は毎ティック位置を配る
                        .build("car"));
        CAR_ITEM = Registrar.register(Registries.ITEM, "car",
                () -> new CarSpawnItem(new Item.Properties().stacksTo(1)));
        KURUMA_TAB = Registrar.register(Registries.CREATIVE_MODE_TAB, "kurumamod",
                // 並びの位置は Fabric API が決める（NeoForge 版の withTabsBefore は NeoForge の追加）
                () -> FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.kurumamod"))
                        .icon(() -> CAR_ITEM.get().getDefaultInstance())
                        // 読み込まれている車種ぶんを並べる（NeoForge 版の Kurumamod と同じ）
                        .displayItems((parameters, output) -> {
                            for (CarType type : CarTypes.all()) {
                                output.accept(CarSpawnItem.stackFor(type.id()));
                            }
                        })
                        .build());
        KurumaSounds.init();
        KurumaParticles.init();

        KurumaNetwork.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("サーバー起動処理を実行します");
            CommonHooks.serverStarting();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CommonHooks.serverStopped());
        ServerTickEvents.END_SERVER_TICK.register(CommonHooks::serverTickPost);
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                CommonHooks.registerCommands(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CommonHooks.playerLoggedIn(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CommonHooks.playerLoggedOut(handler.getPlayer()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                CommonHooks.playerChangedDimension(player, origin.dimension()));
        // ログイン時は本人へ、/reload 時は全員へ（NeoForge の OnDatapackSyncEvent と同じ場面）
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> CommonHooks.syncDataPack(player));

        // データパックから車種と部品を読む。Fabric は読み直しの係に名前（ID）を要求するので包む
        ResourceManagerHelper serverData = ResourceManagerHelper.get(PackType.SERVER_DATA);
        serverData.registerReloadListener(identified("cars", new CarTypeLoader()));
        serverData.registerReloadListener(identified("car_parts", new CarPartLoader()));
    }

    /** Fabric の読み直しの係は名前（ID）を持つ必要があるので、名前を足して包む。 */
    static IdentifiableResourceReloadListener identified(String name, PreparableReloadListener listener) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MODID, name);
        return new IdentifiableResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return id;
            }

            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                                                  ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
                                                  Executor backgroundExecutor, Executor gameExecutor) {
                return listener.reload(barrier, manager, prepareProfiler, applyProfiler, backgroundExecutor, gameExecutor);
            }

            @Override
            public String getName() {
                return listener.getName();
            }
        };
    }
}
