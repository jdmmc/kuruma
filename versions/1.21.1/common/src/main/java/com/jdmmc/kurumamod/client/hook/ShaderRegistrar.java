package com.jdmmc.kurumamod.client.hook;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * コアシェーダーの登録口。NeoForge 版は {@code RegisterShadersEvent} を、Fabric 版は Fabric API の
 * {@code CoreShaderRegistrationCallback} を、この形に包んで渡す。
 *
 * <p><b>ID と頂点の形式を渡す形にしてある</b>（Fabric の形）。NeoForge の
 * {@code new ShaderInstance(provider, ResourceLocation, format)} は NeoForge が足したコンストラクタで、
 * Fabric には無いため。</p>
 */
@FunctionalInterface
public interface ShaderRegistrar {

    /** {@code assets/<id の名前空間>/shaders/core/<id のパス>.json} を読んで登録し、読めたら {@code onLoad} に渡す。 */
    void registerShader(ResourceLocation id, VertexFormat format, Consumer<ShaderInstance> onLoad) throws IOException;
}
