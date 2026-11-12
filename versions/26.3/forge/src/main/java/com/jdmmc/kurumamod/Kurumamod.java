package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypeLoader;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.ForgeClientEvents;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.hook.CommonHooks;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.part.CarPartLoader;
import com.jdmmc.kurumamod.particle.KurumaParticles;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import com.jdmmc.kurumamod.config.ClientConfig;

/**
 * Forge 版の入り口（{@code META-INF/mods.toml} の {@code modId} と一致していなければならない）と、登録したものの置き場所。
 *
 * <p><b>NeoForge 版・Fabric 版（versions/26.3/neoforge/・fabric/）にも同じ名前のこのクラスがあり、versions/26.3/common/ の
 * 共有コードは {@link #MODID}・{@link #CAR}・{@link #CAR_ITEM} を見る。</b>欄の名前と型（{@code get()} で中身が取れること）は
 * 3 つで揃えること。場面ごとの処理の中身は共有側（{@link CommonHooks}・{@code ClientHooks}）にあり、ここはイベントから呼ぶだけ。</p>
 *
 * <p><b>Forge 26.3 のイベントは EventBus 7。</b>イベントの種類ごとに {@code BUS} を持ち、そこへ {@code addListener} する。
 * MOD のバスは {@link BusGroup}（{@link FMLJavaModLoadingContext#getModBusGroup()}）で、DeferredRegister はそこへ登録する。</p>
 */
@Mod(Kurumamod.MODID)
public class Kurumamod {

    public static final String MODID = "kurumamod";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // 車両エンティティ "kurumamod:car"。26.3 では EntityType も Item も、作るときに自分の ID（ResourceKey）を要る
    public static final RegistryObject<EntityType<CarEntity>> CAR = ENTITY_TYPES.register("car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    // 最初の大きさだけ。実際の箱は CarEntity#makeBoundingBox が車体の箱
                    // （CarHull）の内側に作り直す。地形との衝突は車体の箱が受け持つ
                    .sized(1.8F, 0.7F)
                    .clientTrackingRange(10)
                    .updateInterval(1) // 運転中は毎ティック位置を配る
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, id("car"))));

    // 車を出すアイテム "kurumamod:car"
    public static final RegistryObject<Item> CAR_ITEM = ITEMS.register("car",
            () -> new CarSpawnItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id("car"))).stacksTo(1)));

    // この MOD 用のクリエイティブタブ "kurumamod:kurumamod"
    public static final RegistryObject<CreativeModeTab> KURUMA_TAB = CREATIVE_MODE_TABS.register("kurumamod",
            () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .title(Component.translatable("itemGroup.kurumamod"))
                    .icon(() -> CAR_ITEM.get().getDefaultInstance())
                    // 読み込まれている車種ぶんを並べる（NeoForge 版の Kurumamod と同じ。車種はデータパック由来なので
                    // アイテムは 1 種類で、どの車を出すかはアイテムのデータに入っている）
                    .displayItems((parameters, output) -> {
                        for (CarType type : CarTypes.all()) {
                            output.accept(CarSpawnItem.stackFor(type.id()));
                        }
                    })
                    .build());

    public Kurumamod(FMLJavaModLoadingContext context) {
        BusGroup modBus = context.getModBusGroup();

        ITEMS.register(modBus);
        ENTITY_TYPES.register(modBus);
        // 音と粒は共有側のクラスが Registrar へ出す。読み込ませてから締め切る
        KurumaSounds.init();
        KurumaParticles.init();
        Registrar.registerAll(modBus);
        CREATIVE_MODE_TABS.register(modBus);

        KurumaNetwork.register();
        ForgeCommonEvents.register();

        // 設定は Forge の仕組みを使わず、自前で読む（config/ConfigSpec。ほかのローダーと同じ画面・同じファイル）
        Config.load();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientConfig.load(Platform.configDir());
            ForgeClientEvents.register(context);
        }
        LOGGER.info("KurumaMod（Forge）を準備した");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
