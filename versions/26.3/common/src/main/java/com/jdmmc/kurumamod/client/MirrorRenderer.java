package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.hook.FrameRender;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * ルームミラーとドアミラーの鏡面。
 *
 * <h2>26.3 では後ろの景色を映していない</h2>
 *
 * <p>1.21.1 では、自前のカメラで<b>ワールドをもう 1 回描き</b>（{@code LevelRenderer#renderLevel}）、
 * その絵を鏡面に貼っていた。26.3 の {@code LevelRenderer#render} は、1 フレームの頭に集めた描画の状態
 * （{@code LevelRenderState}。カメラ・見えているチャンク・出したエンティティ）をそのまま使うフレームグラフで、
 * 別のカメラから描き直す口が無い。描き直すには集める段（{@code LevelExtractor}）から 2 回目を回す必要があり、
 * 本編のフレームの状態を壊さずに済む形がまだ見えていない。<b>そこで鏡面はいつも暗いガラスとして描く</b>
 * （1.21.1 でも、設定で切ったとき・最高の描画設定・シェーダーパックの間はこの描き方だった）。
 * H メニューの「ミラーに映す」は、いまは効かない。</p>
 *
 * <p>作り直すときの手本は {@code versions/1.21.1/common/.../client/MirrorRenderer.java}（狙いの決め方・
 * 1 フレームに 1 枚ずつ回す順番はそのまま使える）。</p>
 */
public final class MirrorRenderer {

    /** 同時に映せる鏡の枚数（1.21.1 と同じ。鏡の描き先を作り直すときのため残してある）。 */
    public static final int MAX_MIRRORS = 4;

    /** 映していないときの鏡面の色。真っ黒にすると穴に見えるので、少しだけ明るくしておく。 */
    private static final float DEAD_DIM = 0.16F;

    private MirrorRenderer() {
    }

    /** ミラーのためにワールドを描いている最中か。26.3 では描き直さないので常に false。 */
    public static boolean isRendering() {
        return false;
    }

    /** 1 フレームを描き始める前。26.3 では何もしない（鏡の絵を作らない）。 */
    public static void onRenderTick(FrameRender event) {
    }

    /**
     * 鏡面を暗いガラスとして描く。
     *
     * <p><b>鏡面は車体のパスから外してあるので、ここが必ず描くこと</b>——描かないと
     * その部分に穴が開く（{@code ObjModel} が読み込み時に車体のパスから外している）。
     * 姿勢は車体と同じ変換の中で呼ぶこと（{@code pose} をそのまま使う）。</p>
     */
    public static void renderSurfaces(CarEntity car, ObjModel body, PoseStack pose,
                                      SubmitNodeCollector collector, int packedLight, Identifier texture) {
        List<ObjModel.MirrorFace> faces = body.mirrors();
        if (faces.isEmpty()) {
            return;
        }
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (p, consumer) -> {
            for (ObjModel.MirrorFace face : faces) {
                // 反射面だけを暗く塗る。筐体は車体として既に描かれている
                body.renderMirrorPlain(p, consumer, face, packedLight, DEAD_DIM, DEAD_DIM, DEAD_DIM * 1.15F);
            }
        });
    }
}
