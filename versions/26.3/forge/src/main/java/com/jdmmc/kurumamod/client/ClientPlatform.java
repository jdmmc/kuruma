package com.jdmmc.kurumamod.client;

/**
 * 描画まわりで、ローダーによって書き方が違うところ（Forge 版）。
 *
 * <p><b>ほかのローダーの版にも同じ名前・同じメソッドのこのクラスがある。</b>
 * メソッドを足したら 3 つに足すこと。</p>
 *
 * <p>1.21.1 まであった 2 つは 26.3 で要らなくなったので消した。</p>
 * <ul>
 *   <li>{@code shader}（名前空間付きのシェーダーを作る）… 26.3 のシェーダーは {@code RenderPipeline} がコード側で持ち、
 *       {@code withVertexShader(Identifier)} で素の Minecraft のまま名前空間付きで読める</li>
 *   <li>{@code matchStencil} … renderpearl の描き先にはローダーごとの違い（NeoForge のステンシル）が無い</li>
 * </ul>
 */
public final class ClientPlatform {

    private ClientPlatform() {
    }
}
