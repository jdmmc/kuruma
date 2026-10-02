package com.jdmmc.kurumamod;

import net.neoforged.neoforge.network.PacketDistributor;
import com.jdmmc.kurumamod.client.CarModel;
import com.jdmmc.kurumamod.client.CarObjRenderer;
import com.jdmmc.kurumamod.client.CarPartModel;
import com.jdmmc.kurumamod.client.CarPresets;
import com.jdmmc.kurumamod.client.CarSoundSet;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypeLoader;
import com.jdmmc.kurumamod.part.CarPartLoader;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.network.CarRulesPacket;
import com.jdmmc.kurumamod.network.CarTypesPacket;
import com.jdmmc.kurumamod.network.CarPartTypesPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.slf4j.Logger;

// ここの値は META-INF/neoforge.mods.toml のエントリと一致していなければならない
@Mod(Kurumamod.MODID)
public class Kurumamod {

    // MOD ID は全体から参照できるよう 1 箇所にまとめて定義する
    public static final String MODID = "kurumamod";
    // slf4j のロガーを直接参照する
    private static final Logger LOGGER = LogUtils.getLogger();

    // "kurumamod" 名前空間で登録するアイテムを保持する DeferredRegister
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MODID);
    // "kurumamod" 名前空間で登録するエンティティタイプを保持する DeferredRegister
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    // "kurumamod" 名前空間で登録するクリエイティブタブを保持する DeferredRegister
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // 車両エンティティ "kurumamod:car"
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> CAR = ENTITY_TYPES.register("car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    // AABB はシャシー基準面から上、つまり車体だけを覆う。
                    // タイヤは下にぶら下がっていて、地面との接触はサスペンションが受け持つ
                    .sized(1.8F, 0.7F)
                    .clientTrackingRange(10)
                    .updateInterval(1) // 運転中は毎ティック位置を配る
                    .build("car"));

    // 車を出すアイテム "kurumamod:car"
    public static final DeferredHolder<Item, Item> CAR_ITEM = ITEMS.register("car",
            () -> new CarSpawnItem(new Item.Properties().stacksTo(1)));

    // この MOD 用のクリエイティブタブ "kurumamod:kurumamod"
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> KURUMA_TAB = CREATIVE_MODE_TABS.register("kurumamod",
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

    /**
     * NeoForge は MOD イベントバスと MOD のコンテナをコンストラクタへ渡してくる
     * （Forge 版の {@code FMLJavaModLoadingContext.get()} にあたる）。
     */
    public Kurumamod(IEventBus modEventBus, ModContainer modContainer) {
        // パケットの登録。Forge 版と違って専用のイベントで行う
        modEventBus.addListener(KurumaNetwork::register);

        // 各レジストリが登録されるよう DeferredRegister を MOD イベントバスへ登録する
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        KurumaSounds.SOUNDS.register(modEventBus);
        KurumaParticles.PARTICLE_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // サーバーイベントなど、購読したいゲーム内イベントのために自身を登録する
        NeoForge.EVENT_BUS.register(this);

        // NeoForge が設定ファイルを生成・読み込みできるよう、この MOD の ModConfigSpec を登録する。
        // CLIENT は画面の好みなので別ファイル（kurumamod-client.toml）。サーバーには置かれない
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        // Mod 一覧の「Config」ボタンから設定画面を開けるようにする（クライアントだけ）
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientModEvents.registerConfigScreen(modContainer);
        }
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
     * ゲーム内イベント（{@code NeoForge.EVENT_BUS}）側</b>で、リロードのたびに呼ばれる。</p>
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
        // ログイン時は本人へ、/reload 時は全員へ（どちらの場合も NeoForge が相手を数え上げてくれる）
        event.getRelevantPlayers().forEach(player -> {
            PacketDistributor.sendToPlayer(player, packet);
            PacketDistributor.sendToPlayer(player, parts);
            PacketDistributor.sendToPlayer(player, rules);
        });
    }

    // EventBusSubscriber を付けると、そのクラス内の SubscribeEvent 付き static メソッドが自動で登録される
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(CAR.get(), CarObjRenderer::new);
        }

        /**
         * 砂塵とタイヤの白煙の粒を実際に描く係を結びつける。
         *
         * <p>種類そのものは両側で登録してある（{@link KurumaParticles}）。
         * <b>描く係だけがクライアント側</b>で、絵は
         * {@code assets/kurumamod/particles/dust.json} と {@code tire_smoke.json} が指すスプライトの束。</p>
         */
        @SubscribeEvent
        public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(KurumaParticles.DUST.get(),
                    sprites -> new DustParticle.Provider(sprites, DustParticle.DUST));
            event.registerSpriteSet(KurumaParticles.TIRE_SMOKE.get(),
                    sprites -> new DustParticle.Provider(sprites, DustParticle.TIRE_SMOKE));
        }

        /**
         * Mod 一覧の「Config」ボタンから設定画面を開けるようにする。
         *
         * <p>開くのは H キーと同じ {@link KurumaMenuScreen}。<b>設定の置き場所は 1 つに保つ</b>
         * ——2 か所にあると、どちらが効いているのか分からなくなる。
         * <b>クライアント専用</b>なので、この内部クラス（{@code Dist.CLIENT}）の中に置く。</p>
         */
        static void registerConfigScreen(ModContainer modContainer) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (container, parent) -> new KurumaMenuScreen(parent));
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
                CarSoundSet.clearReports();
                CarPartModel.clearCache();
                CarPresets.clearCache();
            });
        }
    }
}
