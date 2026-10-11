package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * 車のアイテムの描画。アイテムのモデル（{@code models/item/car.json}）が {@code builtin/entity} なので、
 * バニラはここを呼ぶ。渡ってくる座標は 0〜1 の立方体（向きと大きさは JSON の {@code display}）。
 *
 * <p><b>どこでも絵を 1 枚貼るだけ</b>（持ち物・手・地面に落ちたアイテム・額縁）。絵はカーパックが
 * 指定した画像か、車のモデルから焼いたもの（{@link CarIcons}）。<b>車を 3D で描くことはしない</b>——
 * 1 万頂点の車を、落ちたアイテムや額縁の数だけ描くことになるため。</p>
 */
public final class CarItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static CarItemRenderer instance;

    private CarItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static CarItemRenderer get() {
        if (instance == null) {
            instance = new CarItemRenderer();
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        ResourceLocation texture = CarIcons.iconFor(stack);
        if (texture == null) {
            return;
        }
        // 持ち物の中は明るさ最大（陰影は焼いたときに付いている）。ワールドの中では
        // 周りの明るさに従う（夜道に落ちたアイテムだけ光って見えないように）
        drawFlat(texture, pose, buffer, context == ItemDisplayContext.GUI ? LightTexture.FULL_BRIGHT : packedLight);
    }

    /**
     * 焼いた絵を立方体いっぱいに貼る。陰影は焼いたときに付いているので、面の向きによる
     * 光の計算をしない描き方（文字と同じ {@code RenderType}。明るさだけは効く）で貼る。
     */
    private static void drawFlat(ResourceLocation texture, PoseStack pose, MultiBufferSource buffer, int light) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.text(texture));
        Matrix4f matrix = pose.last().pose();
        // 立方体の奥行きの真ん中に置く。絵は上が v = 0。
        // GUI は Y を裏返して描くので面の巻き方向が逆になる。この RenderType は裏を捨てるので、
        // どちらから見ても映るよう表と裏の 2 枚を積む
        corner(consumer, matrix, 0.0F, 1.0F, light);
        corner(consumer, matrix, 0.0F, 0.0F, light);
        corner(consumer, matrix, 1.0F, 0.0F, light);
        corner(consumer, matrix, 1.0F, 1.0F, light);

        corner(consumer, matrix, 1.0F, 1.0F, light);
        corner(consumer, matrix, 1.0F, 0.0F, light);
        corner(consumer, matrix, 0.0F, 0.0F, light);
        corner(consumer, matrix, 0.0F, 1.0F, light);
    }

    private static void corner(VertexConsumer consumer, Matrix4f matrix, float x, float y, int light) {
        consumer.vertex(matrix, x, y, 0.5F).color(255, 255, 255, 255).uv(x, 1.0F - y).uv2(light).endVertex();
    }
}
