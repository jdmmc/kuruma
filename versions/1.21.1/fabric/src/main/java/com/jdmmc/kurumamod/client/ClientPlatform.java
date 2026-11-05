package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.impl.client.rendering.FabricShaderProgram;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;

/**
 * 描画まわりで、ローダーによって書き方が違うところ（Fabric 版）。NeoForge 版（versions/1.21.1/neoforge/）にも同じ名前・
 * 同じメソッドのこのクラスがある。
 */
public final class ClientPlatform {

    private ClientPlatform() {
    }

    /**
     * {@code assets/<id の名前空間>/shaders/core/<id のパス>.json} のシェーダーを作る。
     *
     * <p><b>Fabric API の内部クラス {@link FabricShaderProgram} を使っている。</b>素の Minecraft は {@code minecraft} の
     * 名前空間しか読めず、Fabric API はこのクラスのときだけ名前空間付きで読めるようにしている
     * （{@code CoreShaderRegistrationCallback} の中身も同じ）。公開の口ではないので、Fabric API の版を上げたら
     * まだあるか確かめること。</p>
     */
    public static ShaderInstance shader(ResourceProvider resources, ResourceLocation id, VertexFormat format) throws IOException {
        return new FabricShaderProgram(resources, id, format);
    }

    /** 素の Minecraft の描き先はステンシルを持たないので、揃えることが無い。 */
    public static void matchStencil(RenderTarget main, RenderTarget copy) {
    }
}
