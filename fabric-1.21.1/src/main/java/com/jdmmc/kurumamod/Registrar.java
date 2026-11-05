package com.jdmmc.kurumamod;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
 * レジストリへの登録の口（Fabric 版）。<b>その場で {@link Registry#register} する</b>ので、
 * {@code onInitialize} の中（レジストリが凍結される前）で呼ばれること。
 *
 * <p>NeoForge 版（neoforge-1.21.1/）にも同じ名前・同じメソッドのこのクラスがある（そちらは DeferredRegister）。</p>
 */
public final class Registrar {

    private Registrar() {
    }

    /** {@code kurumamod:<name>} として登録する。 */
    @SuppressWarnings("unchecked")
    public static <T, V extends T> Supplier<V> register(ResourceKey<? extends Registry<T>> registry, String name,
                                                        Supplier<V> factory) {
        Registry<T> target = (Registry<T>) BuiltInRegistries.REGISTRY.get(registry.location());
        if (target == null) {
            throw new IllegalArgumentException("レジストリが見つからない: " + registry.location());
        }
        V value = Registry.register(target, ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, name), factory.get());
        return () -> value;
    }

    /** 中身の無い粒の種類。素の Minecraft では {@code SimpleParticleType} のコンストラクタが protected なので、Fabric API の口を使う。 */
    public static SimpleParticleType simpleParticle(boolean overrideLimiter) {
        return FabricParticleTypes.simple(overrideLimiter);
    }
}
