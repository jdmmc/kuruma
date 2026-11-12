package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * 車のアイテムの描画（26.3 の「アイテムの特別な描画」）。
 *
 * <p>アイテムの定義（{@code assets/kurumamod/items/car.json}）が {@code minecraft:special} で
 * {@code kurumamod:car}（{@link #ID}）を指すので、バニラはここを呼ぶ。渡ってくる座標は 0〜1 の立方体
 * （向きと大きさは土台のモデル {@code models/item/car_special.json} の {@code display}）。</p>
 *
 * <p><b>どこでも絵を 1 枚貼るだけ</b>（持ち物・手・地面に落ちたアイテム・額縁）。絵はカーパックが
 * 指定した画像か、車のモデルから焼いたもの（{@link CarIcons}）。<b>車を 3D で描くことはしない</b>——
 * 1 万頂点の車を、落ちたアイテムや額縁の数だけ描くことになるため。</p>
 *
 * <p>引数（{@code T}）は貼る絵の名前。アイテムから絵を決めるのは集める段（{@link #extractArgument}）。</p>
 */
public final class CarItemRenderer implements SpecialModelRenderer<Identifier> {

    /** アイテムの定義の {@code "type"} に書く名前。GLUE がこの名前で {@link Unbaked#MAP_CODEC} を登録する。 */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(Kurumamod.MODID, "car");

    private static final CarItemRenderer INSTANCE = new CarItemRenderer();

    private CarItemRenderer() {
    }

    @Override
    @Nullable
    public Identifier extractArgument(ItemStack stack) {
        return CarIcons.iconFor(stack);
    }

    /**
     * 焼いた絵を立方体いっぱいに貼る。陰影は焼いたときに付いているので、面の向きによる
     * 光の計算をしない描き方（文字と同じ {@code RenderType}。明るさだけは効く）で貼る。
     *
     * <p>26.3 では描く場面（持ち物か世界か）が渡ってこない。持ち物の中ではバニラが明るさ最大を渡してくるので、
     * 1.21.1 で場面を見て明るさを変えていたのと同じになる。</p>
     */
    @Override
    public void submit(@Nullable Identifier texture, PoseStack pose, SubmitNodeCollector collector,
                       int light, int overlay, boolean hasFoil, int outlineColor) {
        if (texture == null) {
            // まだ焼けていない（次のフレームに焼ける）
            return;
        }
        collector.submitCustomGeometry(pose, RenderTypes.text(texture), (entry, consumer) -> {
            Matrix4f matrix = entry.pose();
            // 立方体の奥行きの真ん中に置く。絵は上が v = 0。
            // どちらから見ても映るよう表と裏の 2 枚を積む（この RenderType は裏を捨てる）
            corner(consumer, matrix, 0.0F, 1.0F, light);
            corner(consumer, matrix, 0.0F, 0.0F, light);
            corner(consumer, matrix, 1.0F, 0.0F, light);
            corner(consumer, matrix, 1.0F, 1.0F, light);

            corner(consumer, matrix, 1.0F, 1.0F, light);
            corner(consumer, matrix, 1.0F, 0.0F, light);
            corner(consumer, matrix, 0.0F, 0.0F, light);
            corner(consumer, matrix, 0.0F, 1.0F, light);
        });
    }

    private static void corner(VertexConsumer consumer, Matrix4f matrix, float x, float y, int light) {
        consumer.addVertex(matrix, x, y, 0.5F).setColor(255, 255, 255, 255).setUv(x, 1.0F - y).setLight(light);
    }

    /** 描く範囲（GUI が大きさを合わせるのに使う）。立方体の真ん中の板。 */
    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        output.accept(new Vector3f(0.0F, 0.0F, 0.5F));
        output.accept(new Vector3f(1.0F, 0.0F, 0.5F));
        output.accept(new Vector3f(0.0F, 1.0F, 0.5F));
        output.accept(new Vector3f(1.0F, 1.0F, 0.5F));
    }

    /** アイテムの定義から読まれる形。中身は持たない。 */
    public record Unbaked() implements SpecialModelRenderer.Unbaked<Identifier> {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<Identifier> bake(SpecialModelRenderer.BakingContext context) {
            return INSTANCE;
        }
    }
}
