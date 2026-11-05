package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;

/**
 * 描画まわりで、NeoForge が素の Minecraft に足したものを使うところ（NeoForge 版）。
 *
 * <p><b>Fabric 版（fabric-1.21.1/）にも同じ名前・同じメソッドのこのクラスがある。</b>メソッドを足したら両方に足すこと。</p>
 */
public final class ClientPlatform {

    private ClientPlatform() {
    }

    /**
     * {@code assets/<id の名前空間>/shaders/core/<id のパス>.json} のシェーダーを作る。名前空間付きで読めるのは
     * NeoForge が足したコンストラクタ（素の Minecraft は {@code minecraft} の名前空間しか読めない）。
     */
    public static ShaderInstance shader(ResourceProvider resources, ResourceLocation id, VertexFormat format) throws IOException {
        return new ShaderInstance(resources, id, format);
    }

    /**
     * {@code main} がステンシルを持っていれば {@code copy} にも持たせる。形式が一致しないと深度を写せない
     * （blit が失敗する）。ステンシルは NeoForge が足したもの。
     */
    public static void matchStencil(RenderTarget main, RenderTarget copy) {
        if (main.isStencilEnabled() && !copy.isStencilEnabled()) {
            copy.enableStencil();
        }
    }
}
