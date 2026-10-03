package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * 車体・ホイール・タイヤの不透明な面を<b>GPU に置いたまま</b>描く。
 *
 * <h2>なぜ</h2>
 *
 * <p>以前は毎フレーム、すべての頂点を Java で 1 つずつ変換して書き込んでいた（{@link ObjModel#render}）。
 * 車 1 台で約 7,700 面・3 万頂点あり、台数が増えると CPU がこれに食われる。GPU から見れば
 * 大した量ではないので、<b>頂点は最初に 1 度だけ GPU のバッファへ送り</b>、毎フレームは
 * 変換の行列と明るさだけを渡して描く。</p>
 *
 * <h2>明るさは外から渡す</h2>
 *
 * <p>バニラの車体用シェーダーは明るさ（ライトマップの座標）を<b>頂点ごとに</b>読むが、置いたままの
 * 頂点には焼き込めない（車が動けば変わる）。そこで自前のシェーダー（{@code kurumamod:car_mesh}。
 * 中身は {@code rendertype_entity_cutout_no_cull} の写し）で、1 台ぶんの明るさを {@code MeshLight} で渡す。
 * PoseStack も同じく {@code MeshMat} / {@code MeshNormalMat} で渡す。</p>
 *
 * <h2>使わない場面</h2>
 *
 * <ul>
 *   <li><b>シェーダーパック（Oculus / Iris）を使っている間。</b>パックは車体のシェーダーを自分のものに
 *       差し替えて陰影や影を付けるので、自前のシェーダーで描くとそこから外れる。以前の描き方に戻す</li>
 *   <li>設定 {@code gpuCarMeshes} を切ったとき（見比べるため・何かあったときに戻すため）</li>
 *   <li>シェーダーを読めなかったとき</li>
 * </ul>
 *
 * <p>ガラス・灯火のレンズ・ミラーの面は数が少なく、描き方も別なので以前のまま。</p>
 */
public final class CarMeshes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation SHADER = new ResourceLocation(Kurumamod.MODID, "car_mesh");

    private static ShaderInstance shader;
    private static boolean loadAttempted;

    private CarMeshes() {
    }

    static ShaderInstance shader() {
        return shader;
    }

    /** GPU に置いた描き方を使えるか。使えなければ呼ぶ側が以前の描き方で描く。 */
    static boolean enabled() {
        if (!ClientConfig.gpuCarMeshes || HeadlightVolume.ShaderPacks.inUse()) {
            return false;
        }
        ensureShader();
        return shader != null;
    }

    /**
     * 不透明な面を 1 つ描く。
     *
     * @param withoutTire ホイールの付属のタイヤを除くか（タイヤを別に描くとき）
     * @param mirrorX     左右を反転するか（右側のタイヤ）。反転した頂点を別に置いてある
     */
    static void draw(ObjModel model, boolean withoutTire, boolean mirrorX, ResourceLocation texture,
                     PoseStack.Pose pose, int packedLight) {
        VertexBuffer mesh = model.gpuMesh(withoutTire, mirrorX);
        if (mesh == null) {
            return;
        }
        RenderType type = KurumaRenderTypes.carMesh(texture);
        type.setupRenderState();
        shader.safeGetUniform("MeshMat").set(pose.pose());
        shader.safeGetUniform("MeshNormalMat").set(pose.normal());
        // ライトマップの座標（ブロックの明るさ, 空の明るさ）。どちらも 0〜240
        shader.safeGetUniform("MeshLight").set((float) (packedLight & 0xFFFF), (float) (packedLight >>> 16 & 0xFFFF));
        mesh.bind();
        mesh.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), shader);
        VertexBuffer.unbind();
        type.clearRenderState();
    }

    /**
     * シェーダーが無ければ自分で読む。シェーダーパックを有効にしたまま起動すると
     * {@code RegisterShadersEvent} が飛ばないため（{@link HeadlightVolume#ensureShaders} と同じ理由）。
     */
    private static void ensureShader() {
        if (shader != null || loadAttempted) {
            return;
        }
        loadAttempted = true;
        try {
            shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), SHADER,
                    DefaultVertexFormat.NEW_ENTITY);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("車のメッシュのシェーダーを読めなかった。以前の描き方で描く", e);
        }
    }

    @Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            loadAttempted = false;
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), SHADER,
                        DefaultVertexFormat.NEW_ENTITY), loaded -> shader = loaded);
            } catch (IOException e) {
                LOGGER.error("車のメッシュのシェーダーを読めなかった。以前の描き方で描く", e);
                shader = null;
            }
        }
    }
}
