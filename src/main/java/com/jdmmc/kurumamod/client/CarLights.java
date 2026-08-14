package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 灯火の描画。
 *
 * <p>2 つに分かれる:</p>
 * <ul>
 *   <li><b>レンズ</b> … {@code light_*} のオブジェクトを<b>明るさ最大</b>で描く。
 *       消灯時は車体と同じ明るさで、色だけ薄く残す</li>
 *   <li><b>光の筋</b> … ヘッドライトから前方へ伸びる半透明のコーン。
 *       <b>Minecraft には動的ライトが無い</b>ので、地面が実際に明るくなるわけではない。
 *       世界を一切書き換えないぶん軽く、走っても遅れない</li>
 * </ul>
 *
 * <p>光の筋は<b>周りが暗いときだけ濃くする</b>。昼間に出しっぱなしにすると
 * 白い板が浮いて見えるだけで、ヘッドライトには見えない。</p>
 */
public final class CarLights {

    /** レンズの名前。Blender 側は役割ごとに 1 オブジェクトでよい（左右はミラーのままでよい）。 */
    public static final String HEAD = ObjModel.LIGHT_PREFIX + "head";
    public static final String TAIL = ObjModel.LIGHT_PREFIX + "tail";
    public static final String REVERSE = ObjModel.LIGHT_PREFIX + "reverse";

    /**
     * 点灯時のレンズの色。
     *
     * <p><b>テクスチャがレンズの色を持っていないときだけ</b>掛ける。UV が展開されている
     * レンズには {@link #TEXTURED_COLOR}（白）を掛けて、描かれた色をそのまま出す。</p>
     */
    private static final float[] HEAD_COLOR = {1.0F, 0.97F, 0.88F};
    private static final float[] TAIL_COLOR = {1.0F, 0.15F, 0.10F};
    private static final float[] REVERSE_COLOR = {1.0F, 1.0F, 1.0F};

    /** テクスチャに色が描かれているレンズへ掛ける色。明暗だけを残す。 */
    private static final float[] TEXTURED_COLOR = {1.0F, 1.0F, 1.0F};

    /** 消灯時にレンズへ掛ける暗さ。0 だと真っ黒になって穴に見える。 */
    private static final float OFF_DIM = 0.35F;
    /** テールランプは常時うっすら点いている。ブレーキで最大になる。 */
    private static final float TAIL_IDLE = 0.35F;

    /** 光の筋の長さ [m] と、先端の半径 [m]。 */
    private static final double BEAM_LENGTH = 14.0;
    private static final double BEAM_END_RADIUS = 4.5;
    /** 根元の半径 [m]。レンズより少し大きくしておくと繋がって見える。 */
    private static final double BEAM_START_RADIUS = 0.35;
    /** コーンの分割数。 */
    private static final int BEAM_SEGMENTS = 14;
    /** 根元の濃さ。先端では 0 まで落ちる。 */
    private static final float BEAM_ALPHA = 0.30F;
    /**
     * 明るい場所でも残す濃さの割合。
     *
     * <p>0 にすると昼間はまったく出なくなる。<b>出ていないのか、出ていて見えないのかが
     * 区別できなくなる</b>ので、薄くても常に出す。実車のヘッドライトも昼間はうっすら見える。</p>
     */
    private static final float BEAM_DAYLIGHT = 0.35F;

    private CarLights() {
    }

    /** レンズを描く。点いていれば明るさ最大、消えていれば車体と同じ明るさで暗く。 */
    public static void renderLenses(CarEntity car, ObjModel model, PoseStack pose,
                                    MultiBufferSource buffer, int packedLight,
                                    net.minecraft.resources.ResourceLocation texture) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        lens(model, pose, consumer, packedLight, HEAD, HEAD_COLOR,
                car.isHeadlightOn() ? 1.0F : 0.0F);
        lens(model, pose, consumer, packedLight, TAIL, TAIL_COLOR,
                car.isBrakeLightOn() ? 1.0F : (car.isHeadlightOn() ? TAIL_IDLE : 0.0F));
        lens(model, pose, consumer, packedLight, REVERSE, REVERSE_COLOR,
                car.isReverseLightOn() ? 1.0F : 0.0F);
    }

    /**
     * レンズ 1 つ。
     *
     * <p><b>色を掛けるのは、テクスチャがレンズの色を持っていないときだけ。</b>UV が
     * 1 点に潰れているレンズはテクスチャの 1 画素を全面に写しているだけなので、赤いテール
     * ランプにするにはコード側で色を掛けるしかない。一方、Blender で展開してテクスチャに
     * 色を描いたなら、そこへさらに赤を掛けると<b>二重に掛かって暗い赤茶になる</b>。
     * 展開されているかで自動的に切り替える。</p>
     *
     * <p>どちらの場合も明暗（{@code tint} と明るさ最大）はそのまま効かせる。ここまで
     * 手放すと点いているのか消えているのか見て分からなくなる。</p>
     */
    private static void lens(ObjModel model, PoseStack pose, VertexConsumer consumer,
                             int packedLight, String name, float[] color, float power) {
        if (!model.hasGroup(name)) {
            return;
        }
        float[] hue = model.isUvMapped(name) ? TEXTURED_COLOR : color;
        // 点いている部分だけ明るさを最大にする。0xF000F0 は「空も松明も最大」
        int light = power > 0.0F ? LightTexture.FULL_BRIGHT : packedLight;
        float tint = OFF_DIM + (1.0F - OFF_DIM) * power;
        model.render(pose.last(), consumer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                hue[0] * tint, hue[1] * tint, hue[2] * tint, 1.0F, name);
    }

    /**
     * ヘッドライトの光の筋。
     *
     * <p>レンズの重心から、レンズの平均法線の向きへコーンを伸ばす。<b>位置も向きも
     * メッシュから読む</b>ので、Blender 側で座標を書く必要はない。</p>
     */
    public static void renderBeams(CarEntity car, ObjModel model, PoseStack pose,
                                   MultiBufferSource buffer) {
        if (!car.isHeadlightOn() || !model.hasGroup(HEAD)) {
            return;
        }
        // 暗いほど濃く、明るいところでも薄く残す
        float strength = BEAM_DAYLIGHT + (1.0F - BEAM_DAYLIGHT) * darkness(car);

        List<Vec3> origins = model.groupSideCenters(HEAD);
        // 向きは<b>真っ直ぐ前</b>で固定する。レンズの平均法線から取ろうとしたが、
        // 箱で作ると法線が打ち消し合って向きが定まらない（合計がほぼ 0 になる）。
        // 下向きに振りたくなったらここに角度を足す
        Vec3 direction = new Vec3(0.0, 0.0, -1.0);

        // POSITION_COLOR・半透明・カリング無し。コーンは<b>内側から見る</b>ので、
        // カリングのある RenderType（lightning など）だと巻き方次第で丸ごと消える。
        //
        // バニラの debugQuads() ではなく自前のものを使う。あちらは半透明なのに<b>深度を書く</b>ので、
        // この筋が 14m ぶんの深度を埋めてしまい、後から描かれるコースのゲートが筋と重なった
        // 部分だけ消える（コーン自身も手前の面が奥の面を弾いて形が崩れる）
        VertexConsumer consumer = buffer.getBuffer(KurumaRenderTypes.TRANSLUCENT_OVERLAY);
        for (Vec3 origin : origins) {
            beam(pose.last().pose(), consumer, origin, direction, strength);
        }
    }

    /** 周りの暗さ 0..1。松明の下や昼間は 0 に近づく。 */
    private static float darkness(CarEntity car) {
        BlockPos pos = car.blockPosition();
        int block = car.level().getBrightness(LightLayer.BLOCK, pos);
        // 空の明るさは時刻で減る。夜は getSkyDarken() が 11 まで上がる
        int sky = car.level().getBrightness(LightLayer.SKY, pos) - car.level().getSkyDarken();
        int ambient = Math.max(block, Math.max(0, sky));
        return Mth.clamp(1.0F - ambient / 12.0F, 0.0F, 1.0F);
    }

    /** 円錐を三角形の帯で描く。先端へ向かって透明になる。 */
    private static void beam(Matrix4f matrix, VertexConsumer consumer, Vec3 origin, Vec3 direction,
                             float strength) {
        // 進行方向に垂直な 2 軸を作る
        Vec3 up = Math.abs(direction.y) > 0.9 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = direction.cross(up).normalize();
        up = right.cross(direction).normalize();

        Vec3 tip = origin.add(direction.scale(BEAM_LENGTH));
        float alpha = BEAM_ALPHA * strength;

        for (int i = 0; i < BEAM_SEGMENTS; i++) {
            double a0 = 2.0 * Math.PI * i / BEAM_SEGMENTS;
            double a1 = 2.0 * Math.PI * (i + 1) / BEAM_SEGMENTS;
            Vec3 near0 = ring(origin, right, up, a0, BEAM_START_RADIUS);
            Vec3 near1 = ring(origin, right, up, a1, BEAM_START_RADIUS);
            Vec3 far0 = ring(tip, right, up, a0, BEAM_END_RADIUS);
            Vec3 far1 = ring(tip, right, up, a1, BEAM_END_RADIUS);
            // 四角形 1 枚。根元が明るく、先端は透明
            vertex(matrix, consumer, near0, alpha);
            vertex(matrix, consumer, near1, alpha);
            vertex(matrix, consumer, far1, 0.0F);
            vertex(matrix, consumer, far0, 0.0F);
        }
    }

    private static Vec3 ring(Vec3 center, Vec3 right, Vec3 up, double angle, double radius) {
        return center.add(right.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius));
    }

    private static void vertex(Matrix4f matrix, VertexConsumer consumer, Vec3 at, float alpha) {
        consumer.vertex(matrix, (float) at.x, (float) at.y, (float) at.z)
                .color(1.0F, 0.98F, 0.85F, alpha)
                .endVertex();
    }
}
