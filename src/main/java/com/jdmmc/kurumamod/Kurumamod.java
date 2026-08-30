package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.client.CarModel;
import com.jdmmc.kurumamod.client.CarObjRenderer;
import com.jdmmc.kurumamod.client.CarPartModel;
import com.jdmmc.kurumamod.client.CarPresets;
import com.jdmmc.kurumamod.client.DustParticle;
import com.jdmmc.kurumamod.client.KurumaMenuScreen;
import com.jdmmc.kurumamod.client.ObjModel;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.network.PacketDistributor;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypeLoader;
import com.jdmmc.kurumamod.part.CarPartLoader;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.network.CarRulesPacket;
import com.jdmmc.kurumamod.network.CarTypesPacket;
import com.jdmmc.kurumamod.network.CarPartTypesPacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

// ここの値は META-INF/mods.toml のエントリと一致していなければならない
@Mod(Kurumamod.MODID)
public class Kurumamod {

    // MOD ID は全体から参照できるよう 1 箇所にまとめて定義する
    public static final String MODID = "kurumamod";
    // slf4j のロガーを直接参照する
    private static final Logger LOGGER = LogUtils.getLogger();

    // "kurumamod" 名前空間で登録するアイテムを保持する DeferredRegister
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    // "kurumamod" 名前空間で登録するエンティティタイプを保持する DeferredRegister
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    // "kurumamod" 名前空間で登録するクリエイティブタブを保持する DeferredRegister
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // 車両エンティティ "kurumamod:car"
    public static final RegistryObject<EntityType<CarEntity>> CAR = ENTITY_TYPES.register("car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    // AABB はシャシー基準面から上、つまり車体だけを覆う。
                    // タイヤは下にぶら下がっていて、地面との接触はサスペンションが受け持つ
                    .sized(1.8F, 0.7F)
                    .clientTrackingRange(10)
                    .updateInterval(1) // 運転中は毎ティック位置を配る
                    .build("car"));

    // 車を出すアイテム "kurumamod:car"
    public static final RegistryObject<Item> CAR_ITEM = ITEMS.register("car",
            () -> new CarSpawnItem(new Item.Properties().stacksTo(1)));

    // この MOD 用のクリエイティブタブ "kurumamod:kurumamod"
    public static final RegistryObject<CreativeModeTab> KURUMA_TAB = CREATIVE_MODE_TABS.register("kurumamod",
            () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.kurumamod"))
                    .icon(() -> CAR_ITEM.get().getDefaultInstance())
                    // 読み込まれている車種ぶんを並べる。アイテムは 1 種類で、
                    // どの車を出すかは NBT に入っている（車種はデータパック由来なので、
                    // 車種ごとに Item を登録することはできない＝レジストリは凍結済み）。
                    //
                    // タブの中身はログインより前に作られていることがあるので、
                    // 車種が届いた時点で CarClientPackets が組み直しを掛けている
                    .displayItems((parameters, output) -> {
                        for (CarType type : CarTypes.all()) {
                            output.accept(CarSpawnItem.stackFor(type.id()));
                        }
                    })
                    .build());

    public Kurumamod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // MOD ロード時に commonSetup を呼ぶよう登録する
        modEventBus.addListener(this::commonSetup);

        // 各レジストリが登録されるよう DeferredRegister を MOD イベントバスへ登録する
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        KurumaSounds.SOUNDS.register(modEventBus);
        KurumaParticles.PARTICLE_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // サーバーイベントなど、購読したいゲーム内イベントのために自身を登録する
        MinecraftForge.EVENT_BUS.register(this);

        // Forge が設定ファイルを生成・読み込みできるよう、この MOD の ForgeConfigSpec を登録する。
        // CLIENT は画面の好みなので別ファイル（kurumamod-client.toml）。サーバーには置かれない
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // パケットの登録はスレッドセーフではないのでメインスレッドへ回す
        event.enqueueWork(KurumaNetwork::register);
    }

    // SubscribeEvent を付けておくと、イベントバスが呼び出すメソッドを自動的に見つけてくれる
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("サーバー起動処理を実行します");
        // 走行のルールはサーバーの設定が正。ここで一度移しておき、以降は
        // ログイン時とコマンドで配る（クライアントは自分の toml を見ない）
        CarRules.setCarCollision(Config.carCollision);
    }

    /**
     * データパックから車種を読む係を足す。
     *
     * <p>カーパックの諸元は {@code data/<ns>/cars/*.json}。<b>MOD イベントバスではなく
     * ゲーム内イベント（{@code MinecraftForge.EVENT_BUS}）側</b>で、リロードのたびに呼ばれる。</p>
     */
    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new CarTypeLoader());
        // 部品も同じ扱い。素性は data/<ns>/car_parts/*.json、見た目は assets/ 側
        event.addListener(new CarPartLoader());
    }

    /**
     * 読み込んだ車種をクライアントへ配る。
     *
     * <p>{@code OnDatapackSyncEvent} はログイン時（player が非 null）と
     * {@code /reload} 時（player が null ＝ 全員へ）の両方で飛ぶ。</p>
     *
     * <p><b>クライアントに自分でデータパックを読ませない。</b>シングルプレイでは読めて
     * しまうが、マルチプレイで車種を決めるのはサーバーであって手元のカーパックではない。
     * 配られたものだけを見る、で揃えておけば、サーバーに無い車がクリエイティブタブに
     * 並ぶということが起きない。</p>
     */
    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent event) {
        CarTypesPacket packet = CarTypesPacket.current();
        CarPartTypesPacket parts = CarPartTypesPacket.current();
        CarRulesPacket rules = CarRulesPacket.current();
        if (event.getPlayer() != null) {
            KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), packet);
            KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), parts);
            KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), rules);
        } else {
            KurumaNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
            KurumaNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), parts);
            KurumaNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), rules);
        }
    }

    // EventBusSubscriber を付けると、そのクラス内の SubscribeEvent 付き static メソッドが自動で登録される
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(CAR.get(), CarObjRenderer::new);
        }

        /**
         * 砂塵の粒を実際に描く係を結びつける。
         *
         * <p>種類そのものは両側で登録してある（{@link KurumaParticles}）。
         * <b>描く係だけがクライアント側</b>で、絵は
         * {@code assets/kurumamod/particles/dust.json} が指すスプライトの束。</p>
         */
        @SubscribeEvent
        public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(KurumaParticles.DUST.get(), DustParticle.Provider::new);
        }

        /**
         * Mod 一覧の「Config」ボタンから設定画面を開けるようにする。
         *
         * <p>開くのは H キーと同じ {@link KurumaMenuScreen}。<b>設定の置き場所は 1 つに保つ</b>
         * ——2 か所にあると、どちらが効いているのか分からなくなる。
         * <b>クライアント専用</b>なので、この内部クラス（{@code Dist.CLIENT}）の中に置く。</p>
         */
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new KurumaMenuScreen(parent)));
        }

        /**
         * リソースを読み直したら OBJ と見た目の定義も読み直す。
         *
         * <p>これがあると、Blender から書き出し直して<b>ゲーム内で F3+T を押すだけ</b>で
         * 新しいモデルが反映される。モデルを詰めている間はこれが効く。</p>
         */
        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
                ObjModel.clearCache();
                CarModel.clearCache();
                CarPartModel.clearCache();
                CarPresets.clearCache();
            });
        }
    }
}
