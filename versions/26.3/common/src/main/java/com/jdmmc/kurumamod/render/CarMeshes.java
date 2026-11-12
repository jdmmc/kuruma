package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.hook.ShaderRegistrar;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 車体・ホイール・タイヤの不透明な面を<b>GPU に置いたまま</b>描く。
 *
 * <h2>なぜ</h2>
 *
 * <p>頂点を積む描き方（{@link ObjModel#render} を {@code submitCustomGeometry} の中で）は、毎フレームすべての頂点を
 * Java で変換して書き込む。車 1 台で約 7,700 面・3 万頂点あり、台数が増えると CPU がこれに食われる。そこで
 * <b>頂点は最初に 1 度だけ GPU のバッファへ送り</b>、毎フレームは変換の行列と明るさだけを渡す。</p>
 *
 * <h2>26.3 での作り</h2>
 *
 * <p>26.3 の描画は「集めてから段ごとに流す」形なので、その場では描けない。車のレンダラーは
 * {@link #draw} で<b>預けるだけ</b>にし、不透明の段の終わり（{@code mixin/CarMeshFeatureMixin}。
 * {@code FeatureRenderDispatcher.PreparedFrame#executeSolid}）で {@link #flush} がまとめて描く。
 * 同じ {@code RenderPass} の中なので、描き先・深度・霧・光の向きはバニラのエンティティと同じになる。</p>
 *
 * <p>行列と明るさは {@code DynamicTransforms} の空いている枠（{@code TextureMat}・{@code ModelOffset}）で渡す
 * （{@code shaders/core/car_mesh.vsh}）。預ける時点で書くのは、バニラの {@code RenderType#prepare} と同じ順番にするため
 * （パスの中でリングバッファへ書かない）。</p>
 *
 * <h2>シェーダーパック（Iris）使用中</h2>
 *
 * <p>自前のシェーダーではパックの陰影や影から外れるので、別の道で描く（{@link #drawForPack}）。パイプラインは
 * バニラの {@code entity_cutout} のまま（Iris がパックのプログラムへ置き換える）で、頂点は Iris の長い形式で焼き、
 * 姿勢はモデルビューに畳み込む。影の段も同じ道を通る（サスペンション抜きの面を別に焼き、リムは描かない）。</p>
 *
 * <h2>使わない場面</h2>
 *
 * <ul>
 *   <li>シェーダーパック使用中の鏡（鏡の描き先はパックの gbuffer の外）</li>
 *   <li>設定 {@code gpuCarMeshes} を切ったとき（見比べるため・何かあったときに戻すため）</li>
 *   <li>ワールドの描画の外（アイテムのアイコン）と、ゴースト（α を掛けられるのは頂点を積むほうだけ）</li>
 *   <li>パイプラインを作れなかったとき、描くのに失敗したとき（再起動まで頂点を積む描き方）</li>
 * </ul>
 *
 * <p>ガラス・灯火のレンズ・ミラーの面・部品の形・ハンドルは数が少なく、描き方も別なので頂点を積むまま。</p>
 */
public final class CarMeshes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(Kurumamod.MODID, "pipeline/car_mesh"))
            .withVertexShader(Identifier.fromNamespaceAndPath(Kurumamod.MODID, "core/car_mesh"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withShaderDefine("NO_OVERLAY")
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);

    private record Draw(GpuBufferSlice transforms, ObjModel.GpuMesh mesh, GpuTextureView texture, GpuSampler sampler,
                        PreparedRenderType pack, Matrix4f modelView) {
    }

    /** この回のワールドの描画で預かった面。鏡の 1 回も本編の 1 回も、それぞれの {@link #flush} で空になる。 */
    private static final List<Draw> QUEUE = new ArrayList<>();
    /** シェーダーパックの影の段で預かった面。 */
    private static final List<Draw> SHADOW_QUEUE = new ArrayList<>();
    private static final int SHADOW_LIGHT = 0xF000F0;

    private static CompiledRenderPipeline pipeline;
    private static boolean failed;
    /** ワールドの描画の最中か。外（アイテムのアイコンなど）では預かっても流す段が来ない。 */
    private static boolean inLevel;
    private static Matrix4f view;

    private CarMeshes() {
    }

    /** ワールドを描き始める（{@code LevelStageHooks#levelStart}）。 */
    public static void beginLevel(CameraRenderState camera) {
        QUEUE.clear();
        SHADOW_QUEUE.clear();
        inLevel = true;
        view = new Matrix4f(camera.viewRotationMatrix);
    }

    /** ワールドを描き終えた（{@code LevelStageHooks#levelEnd}）。 */
    public static void endLevel() {
        QUEUE.clear();
        SHADOW_QUEUE.clear();
        inLevel = false;
    }

    /** GPU に置いた描き方を使えるか。使えなければ呼ぶ側が頂点を積んで描く。 */
    static boolean enabled() {
        if (!ClientConfig.gpuCarMeshes || !inLevel || failed) {
            return false;
        }
        if (HeadlightVolume.ShaderPacks.inUse()) {
            // 鏡は頂点を積む（鏡の描き先はパックの gbuffer の外）
            return !MirrorRenderer.isRendering();
        }
        // <b>毎回引き直す。</b>資源の読み直し（F3+T）で Minecraft はパイプラインを全部作り直して古いものを捨てるので、
        // 握ったままだと捨てられたシェーダーで描き、車体が崩れる（実際に崩れた。GL は Program handle を返していた）。
        // コンパイル済みのものは Minecraft の置き場が覚えているので、引き直しは軽い
        pipeline = RenderSystem.getCompiledPipelineNullable(PIPELINE);
        if (pipeline == null) {
            failed = true;
            LOGGER.error("車のメッシュのパイプラインを作れなかった（直前のシェーダーのログを参照）。頂点を積む描き方で描く");
            return false;
        }
        return true;
    }

    /**
     * 不透明な面を 1 つ預ける。
     *
     * @param withoutTire ホイールの付属のタイヤを除くか（タイヤを別に描くとき）
     * @param mirrorX     左右を反転するか（右側のタイヤ）。反転した頂点を別に置いてある
     */
    static void draw(ObjModel model, boolean withoutTire, boolean mirrorX, Identifier texture,
                     PoseStack.Pose pose, int packedLight) {
        if (HeadlightVolume.ShaderPacks.inUse()) {
            drawForPack(model, withoutTire, false, mirrorX, texture, pose, packedLight);
            return;
        }
        ObjModel.GpuMesh mesh = model.gpuMesh(withoutTire, mirrorX);
        if (mesh == null) {
            return;
        }
        AbstractTexture loaded = Minecraft.getInstance().getTextureManager().getTexture(texture);
        // ライトマップの座標（ブロックの明るさ, 空の明るさ）
        Vector3f light = new Vector3f(packedLight & 0xFFFF, packedLight >>> 16 & 0xFFFF, 0.0F);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(new Matrix4f(view), WHITE, light, new Matrix4f(pose.pose()));
        QUEUE.add(new Draw(transforms, mesh, loaded.getTextureView(), loaded.getSampler(), null, null));
    }

    /**
     * シェーダーパック使用中。バニラの {@code entity_cutout} をそのまま使うので、Iris がパックのプログラムへ置き換える。
     * パックの頂点シェーダーへは自前の行列を渡せないので、<b>姿勢はモデルビューに畳み込む</b>。法線の行列
     * （{@code iris_NormalMat}）は Iris が描くたびに {@code RenderSystem} のモデルビューから作るので、
     * {@link #flush} で描く間だけそこへも入れる。
     */
    private static void drawForPack(ObjModel model, boolean withoutTire, boolean shadowCaster, boolean mirrorX,
                                    Identifier texture, PoseStack.Pose pose, int packedLight) {
        boolean shadow = HeadlightVolume.ShaderPacks.renderingShadowPass();
        // 影の面に明るさは関係ない。1 通りにしておけば明るさごとの複製が増えない
        ObjModel.GpuMesh mesh = model.packMesh(withoutTire, shadowCaster, mirrorX, shadow ? SHADOW_LIGHT : packedLight);
        if (mesh == null) {
            return;
        }
        // 影の段のカメラは太陽から見たもの。Iris が RenderSystem のモデルビューへ入れている
        Matrix4f modelView = (shadow ? RenderSystem.getModelViewMatrixCopy() : new Matrix4f(view)).mul(pose.pose());
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView);
        (shadow ? SHADOW_QUEUE : QUEUE).add(
                new Draw(transforms, mesh, null, null, RenderTypes.entityCutout(texture).prepare(), modelView));
    }

    /** シェーダーパックの影の段で、サスペンションを除いた車体を預ける。 */
    static void drawShadowCaster(ObjModel model, Identifier texture, PoseStack.Pose pose) {
        drawForPack(model, false, true, false, texture, pose, SHADOW_LIGHT);
    }

    /** 預かった面をまとめて描く。<b>例外は外へ出さない</b>（バニラの描画の最中）。 */
    public static void flush(RenderPass pass) {
        // パックの影の段もこの段を通る。影の面と本描画の面は別に預かる
        if (HeadlightVolume.ShaderPacks.renderingShadowPass()) {
            if (!SHADOW_QUEUE.isEmpty()) {
                flushForPack(pass, SHADOW_QUEUE);
            }
            return;
        }
        if (QUEUE.isEmpty()) {
            return;
        }
        if (QUEUE.get(0).pack() != null) {
            flushForPack(pass, QUEUE);
            return;
        }
        try {
            int indexCount = 0;
            for (Draw draw : QUEUE) {
                indexCount = Math.max(indexCount, draw.mesh().indexCount());
            }
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(indexCount);
            GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

            pass.pushDebugGroup(() -> "kurumamod car meshes");
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(), lightmapSampler);
            pass.setIndexBuffer(indexBuffer, indices.type());
            for (Draw draw : QUEUE) {
                pass.setUniform("DynamicTransforms", draw.transforms());
                pass.setUniform("Sampler0", draw.texture(), draw.sampler());
                pass.setVertexBuffer(0, draw.mesh().vertices().slice());
                pass.drawIndexed(draw.mesh().indexCount(), 1, 0, 0, 0);
            }
            pass.popDebugGroup();
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("車のメッシュを描けなかった。以後は頂点を積む描き方で描く", e);
        } finally {
            QUEUE.clear();
        }
    }

    /**
     * シェーダーパック用に預かった面を描く。<b>パック使用中は OpenGL しか通らないので、ここだけは GL を直接呼ぶ。</b>
     *
     * <p>Iris は法線の行列（{@code iris_NormalMat}）と逆行列（{@code iris_ModelViewMatInverse}）をパイプラインを
     * 結ぶときにだけ書く。同じパイプラインが続くと結び直さないので、そのままだと車の向きが法線に入らず、
     * 視点の回転だけになって側面が暗くなる。そこで別のパイプラインを挟み、インスタンス数 0 の描画で
     * 結び直させてプログラムと状態を用意してもらい、あとは描くたびに自分で書く。結び直しを毎回させるより軽い。</p>
     */
    private static void flushForPack(RenderPass pass, List<Draw> queue) {
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            int indexCount = 0;
            for (Draw draw : queue) {
                indexCount = Math.max(indexCount, draw.mesh().indexCount());
            }
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(indexCount);
            pass.pushDebugGroup(() -> "kurumamod car meshes (pack)");
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.ENTITY_SOLID));
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.ENTITY_CUTOUT));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setIndexBuffer(indexBuffer, indices.type());

            // 何も描かずにプログラムを結ばせる
            Draw head = queue.get(0);
            pass.setUniform("DynamicTransforms", head.transforms());
            pass.setVertexBuffer(0, head.mesh().vertices().slice());
            for (PreparedRenderType.Texture texture : head.pack().textures()) {
                pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
            }
            pass.drawIndexed(head.mesh().indexCount(), 0, 0, 0, 0);
            int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int normalLocation = GL20C.glGetUniformLocation(program, "iris_NormalMat");
            int inverseLocation = GL20C.glGetUniformLocation(program, "iris_ModelViewMatInverse");

            Matrix4f inverse = new Matrix4f();
            Matrix3f normal = new Matrix3f();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer normalValues = stack.mallocFloat(9);
                FloatBuffer inverseValues = stack.mallocFloat(16);
                for (Draw draw : queue) {
                    pass.setUniform("DynamicTransforms", draw.transforms());
                    pass.setVertexBuffer(0, draw.mesh().vertices().slice());
                    for (PreparedRenderType.Texture texture : draw.pack().textures()) {
                        pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
                    }
                    draw.modelView().invert(inverse);
                    if (normalLocation >= 0) {
                        GL20C.glUniformMatrix3fv(normalLocation, false, inverse.transpose3x3(normal).get(normalValues));
                    }
                    if (inverseLocation >= 0) {
                        GL20C.glUniformMatrix4fv(inverseLocation, false, inverse.get(inverseValues));
                    }
                    pass.drawIndexed(draw.mesh().indexCount(), 1, 0, 0, 0);
                }
            }
            pass.popDebugGroup();
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("シェーダーパック用の車のメッシュを描けなかった。以後は頂点を積む描き方で描く", e);
        } finally {
            modelView.popMatrix();
            queue.clear();
        }
    }

    public static final class Registration {
        private Registration() {
        }

        public static void onRegisterShaders(ShaderRegistrar registrar) {
            registrar.registerPipeline(PIPELINE);
        }
    }
}
