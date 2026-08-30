package com.jdmmc.kurumamod.particle;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * この MOD が足す粒。
 *
 * <p><b>登録はクライアント専用にしてはいけない。</b>粒の種類はレジストリなので、
 * 片側だけに存在すると Forge のレジストリ照合でマルチプレイの接続が弾かれる。
 * ここ（両側で走る）で種類だけを登録し、<b>実際に描く係（{@code DustParticle.Provider}）は
 * クライアント側で結びつける</b>（{@code Kurumamod.ClientModEvents}）。</p>
 *
 * <p>絵は {@code assets/kurumamod/particles/dust.json} が指す。
 * <b>バニラのスプライトをそのまま借りている</b>ので、この MOD 側にテクスチャは要らない。</p>
 */
public final class KurumaParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, Kurumamod.MODID);

    /**
     * 未舗装路で車輪が掻き飛ばした砂塵。
     *
     * <p>引数の {@code false} は「粒の量」の設定（動画設定）を<b>尊重する</b>という意味。
     * ラリー中はいちばん数が出るものなので、減らしたい人の設定を無視してはいけない。</p>
     */
    public static final RegistryObject<SimpleParticleType> DUST =
            PARTICLE_TYPES.register("dust", () -> new SimpleParticleType(false));

    private KurumaParticles() {
    }
}
