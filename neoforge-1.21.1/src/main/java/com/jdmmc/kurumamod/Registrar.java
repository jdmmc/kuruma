package com.jdmmc.kurumamod;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * レジストリへの登録の口（NeoForge 版）。レジストリごとに {@link DeferredRegister} を作り、
 * {@link Kurumamod} のコンストラクタで {@link #registerAll(IEventBus)} する。
 *
 * <p><b>Fabric 版（fabric-1.21.1/）にも同じ名前・同じメソッドのこのクラスがある</b>（そちらはその場で
 * {@code Registry.register} する）。mc-1.21.1/ の共有コード（{@code KurumaSounds} など）は
 * {@link #register} だけを呼ぶので、返ってくるのは {@link Supplier}。</p>
 */
public final class Registrar {

    private static final Map<ResourceKey<?>, DeferredRegister<?>> REGISTERS = new LinkedHashMap<>();
    private static boolean registered;

    private Registrar() {
    }

    /** {@code kurumamod:<name>} として登録する。{@link #registerAll} より前に呼ぶこと。 */
    @SuppressWarnings("unchecked")
    public static <T, V extends T> Supplier<V> register(ResourceKey<? extends Registry<T>> registry, String name,
                                                        Supplier<V> factory) {
        if (registered) {
            throw new IllegalStateException("登録の受付はもう締め切った: " + name);
        }
        DeferredRegister<T> deferred = (DeferredRegister<T>) REGISTERS.computeIfAbsent(registry,
                key -> DeferredRegister.create((ResourceKey<? extends Registry<T>>) key, Kurumamod.MODID));
        return deferred.register(name, factory);
    }

    /** 中身の無い粒の種類。素の Minecraft では {@code SimpleParticleType} のコンストラクタが protected のため、口を分けてある。 */
    public static SimpleParticleType simpleParticle(boolean overrideLimiter) {
        return new SimpleParticleType(overrideLimiter);
    }

    static void registerAll(IEventBus modEventBus) {
        registered = true;
        REGISTERS.values().forEach(deferred -> deferred.register(modEventBus));
    }
}
