package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * ヘッドライトの影。<b>ブロックに当たった光はその先へ届かない</b>ようにする。
 *
 * <h2>2 段構え</h2>
 *
 * <ol>
 *   <li><b>ブロックの格子</b> … カメラの周り 96×24×96 ブロックの「光を遮るか」を
 *       テクスチャに持つ。1 ブロックにつき下端と上端の高さ（0..1）の 2 バイトで、
 *       ハーフブロックや道路の薄い層も高さどおりに遮る。<b>毎フレーム一部ずつ</b>
 *       読み直すので、全体が新しくなるまで 12 フレームほど掛かる（ブロックを置いた・壊したが
 *       影に出るまでの遅れ）</li>
 *   <li><b>ランプから見た距離の地図</b> … ランプごとに、光の出る向き 512×128 方向へ格子の中を
 *       光線で辿り（DDA）、最初に当たるブロックまでの距離を書く（{@code headlight_shadow.fsh}）。
 *       照らす側（{@code headlight_volume.fsh}）は、その点までの距離がこの地図より遠ければ影</li>
 * </ol>
 *
 * <p>以前は光軸に沿って 1 本だけ光線を飛ばし、当たった距離で光線全体を切っていた。
 * それでは光軸から外れた木の幹が影を落とさず、<b>幹の奥の地面まで照らされていた</b>。</p>
 *
 * <h2>格子は世界の座標で折り返して持つ</h2>
 *
 * <p>テクスチャ上の位置は「世界の座標 mod 格子の大きさ」なので、カメラが動いても格子を
 * ずらし直す必要がない。新しく範囲に入った列は、読み直しが回ってくるまで古い中身のまま
 * （＝影が一瞬欠ける）。範囲の端はカメラから 48 ブロック先なので目立たない。</p>
 *
 * <p>ガラスと板ガラス（鉄格子の仲間）は光を通す。水も通す（水面は照らす側が面として扱う）。</p>
 */
final class HeadlightShadows {

    /** 格子の水平の大きさ（カメラから ±48）と高さ（±12）。 */
    static final int GRID = 96;
    static final int GRID_Y = 24;
    /** 1 フレームに読み直す行（z）の数。GRID / これ ＝ 全体が新しくなるまでのフレーム数。 */
    private static final int ROWS_PER_FRAME = 8;
    /** カメラがこれより大きく飛んだら（テレポートなど）全部読み直す。 */
    private static final int JUMP = 24;

    /** 距離の地図。横が光の左右、縦がランプ 8 灯ぶんの段。 */
    static final int MAP_WIDTH = 512;
    static final int MAP_TILE_HEIGHT = 128;

    private static final int WIDTH = GRID * GRID_Y;
    private static final int GL_RG = 0x8227;
    private static final int GL_RG8 = 0x822B;

    private static int voxelTexture = -1;
    private static ByteBuffer voxels;
    private static int cursor;
    private static BlockPos lastCenter;
    private static TextureTarget map;

    private HeadlightShadows() {
    }

    /** 格子のテクスチャ。 */
    static int voxelTexture() {
        return voxelTexture;
    }

    /** 距離の地図のテクスチャ。 */
    static int mapTexture() {
        return map.getColorTextureId();
    }

    static int mapHeight(int lamps) {
        return MAP_TILE_HEIGHT * lamps;
    }

    /** 格子を読み直す。新しく範囲に入った列はすぐ、それ以外は少しずつ。初回とテレポートの後は全部。 */
    static void update(Level level, Vec3 camera) {
        BlockPos center = BlockPos.containing(camera);
        if (voxelTexture < 0) {
            voxels = MemoryUtil.memAlloc(WIDTH * GRID * 2);
            MemoryUtil.memSet(voxels, 0);
            voxelTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(voxelTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            resetUnpack();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL_RG8, WIDTH, GRID, 0, GL_RG, GL11.GL_UNSIGNED_BYTE, voxels);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = center.getX() - GRID / 2;
        int minY = center.getY() - GRID_Y / 2;
        int minZ = center.getZ() - GRID / 2;
        GlStateManager._bindTexture(voxelTexture);
        resetUnpack();

        if (lastCenter == null || lastCenter.distManhattan(center) > JUMP) {
            // 初回とテレポートの後は全部
            for (int z = minZ; z < minZ + GRID; z++) {
                fillRow(level, pos, z, minX, minY);
            }
            uploadAll();
            lastCenter = center;
            return;
        }

        // <b>新しく範囲に入った列は、その場で読む。</b>格子は折り返して持っているので、
        // 範囲に入ったばかりの列には<b>反対側（96 ブロック後ろ）の中身</b>が残っている。
        // 読み直しの順番を待つと、そのあいだ後ろの地面が前の壁として扱われ、
        // <b>走っていると遠くの路面がときどき黒くなった</b>。1 ブロック進むごとに
        // 列 1 本（2304 ブロック）なので安い
        int oldMinX = lastCenter.getX() - GRID / 2;
        int oldMinY = lastCenter.getY() - GRID_Y / 2;
        int oldMinZ = lastCenter.getZ() - GRID / 2;
        lastCenter = center;
        boolean whole = false;
        for (int x : entered(oldMinX, minX, GRID)) {
            for (int z = minZ; z < minZ + GRID; z++) {
                for (int y = minY; y < minY + GRID_Y; y++) {
                    fillCell(level, pos, x, y, z);
                }
            }
            whole = true;
        }
        for (int y : entered(oldMinY, minY, GRID_Y)) {
            for (int z = minZ; z < minZ + GRID; z++) {
                for (int x = minX; x < minX + GRID; x++) {
                    fillCell(level, pos, x, y, z);
                }
            }
            whole = true;
        }
        java.util.List<Integer> rows = new java.util.ArrayList<>();
        for (int z : entered(oldMinZ, minZ, GRID)) {
            fillRow(level, pos, z, minX, minY);
            rows.add(Math.floorMod(z, GRID));
        }

        // 置いた・壊したブロックを拾うための読み直し。少しずつ回す
        for (int r = 0; r < ROWS_PER_FRAME; r++) {
            int row = cursor;
            cursor = (cursor + 1) % GRID;
            // この行（z mod GRID == row）に当たる、いまの範囲の中の世界の z
            int z = minZ + Math.floorMod(row - minZ, GRID);
            fillRow(level, pos, z, minX, minY);
            rows.add(row);
        }

        if (whole) {
            uploadAll();
        } else {
            for (int row : rows) {
                uploadRow(row);
            }
        }
    }

    /** 前の範囲の始まり {@code from} から今の始まり {@code to} へずれたとき、新しく入った座標。 */
    private static int[] entered(int from, int to, int size) {
        int shift = to - from;
        if (shift == 0) {
            return new int[0];
        }
        int count = Math.min(Math.abs(shift), size);
        int[] out = new int[count];
        // 増えたなら範囲の奥側、減ったなら手前側が新しい
        int start = shift > 0 ? to + size - count : to;
        for (int i = 0; i < count; i++) {
            out[i] = start + i;
        }
        return out;
    }

    private static void fillRow(Level level, BlockPos.MutableBlockPos pos, int z, int minX, int minY) {
        for (int y = minY; y < minY + GRID_Y; y++) {
            for (int x = minX; x < minX + GRID; x++) {
                fillCell(level, pos, x, y, z);
            }
        }
    }

    private static void fillCell(Level level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        int offset = (Math.floorMod(z, GRID) * WIDTH + Math.floorMod(x, GRID) + Math.floorMod(y, GRID_Y) * GRID) * 2;
        pos.set(x, y, z);
        long range = occlusion(level, pos);
        voxels.put(offset, (byte) (range >> 8));
        voxels.put(offset + 1, (byte) range);
    }

    private static void uploadRow(int row) {
        int rowOffset = row * WIDTH * 2;
        voxels.position(rowOffset).limit(rowOffset + WIDTH * 2);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, row, WIDTH, 1, GL_RG, GL11.GL_UNSIGNED_BYTE, voxels);
        voxels.clear();
    }

    private static void uploadAll() {
        voxels.clear();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, WIDTH, GRID, GL_RG, GL11.GL_UNSIGNED_BYTE, voxels);
    }

    /**
     * そのブロックが光を遮る高さの範囲。上位 8 ビットが下端、下位 8 ビットが上端（0..255）。
     * 遮らなければ 0（下端 = 上端 = 0 は「空」として読まれる）。
     */
    private static long occlusion(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0;
        }
        // 光を通すもの
        if (state.getBlock() instanceof AbstractGlassBlock || state.getBlock() instanceof IronBarsBlock) {
            return 0;
        }
        if (state.isCollisionShapeFullBlock(level, pos)) {
            return 255;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            // 当たり判定の無い薄い層（道路・雪の 1 層）も、床いっぱいに広がっていれば遮る
            VoxelShape outline = state.getShape(level, pos);
            if (outline.isEmpty()) {
                return 0;
            }
            AABB box = outline.bounds();
            if (box.minX > 0.01 || box.maxX < 0.99 || box.minZ > 0.01 || box.maxZ < 0.99) {
                return 0;
            }
            shape = outline;
        }
        int low = Mth.clamp((int) Math.floor(shape.min(Direction.Axis.Y) * 255.0), 0, 254);
        int high = Mth.clamp((int) Math.ceil(shape.max(Direction.Axis.Y) * 255.0), low + 1, 255);
        return ((long) low << 8) | high;
    }

    /**
     * {@code NativeImage} の転送は行の長さや読み飛ばしを設定したまま戻さないので、
     * そのまま送ると別の場所から読まれる。
     */
    private static void resetUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
    }

    /** 格子を読むシェーダーへ、カメラの位置と格子の大きさを渡す。 */
    static void setGridUniforms(ShaderInstance shader, Vec3 camera) {
        BlockPos block = BlockPos.containing(camera);
        shader.safeGetUniform("CameraBlock").set((float) block.getX(), (float) block.getY(), (float) block.getZ());
        shader.safeGetUniform("CameraFrac").set((float) (camera.x - block.getX()),
                (float) (camera.y - block.getY()), (float) (camera.z - block.getZ()));
        shader.safeGetUniform("GridSize").set((float) GRID, (float) GRID_Y);
    }

    /** ランプから見た距離の地図を描く。描き終えたら主画面へ戻す。 */
    static void renderMap(ShaderInstance shader, Matrix4f[] lamps, int count, int maxLamps, Vec3 camera,
                          RenderTarget main) {
        int height = MAP_TILE_HEIGHT * maxLamps;
        if (map == null) {
            map = new TextureTarget(MAP_WIDTH, height, false, Minecraft.ON_OSX);
        }
        shader.setSampler("VoxelSampler", voxelTexture);
        setGridUniforms(shader, camera);
        for (int i = 0; i < maxLamps; i++) {
            shader.safeGetUniform("Lamp" + i).set(i < count ? lamps[i] : new Matrix4f().zero());
        }
        shader.safeGetUniform("LampCount").set(count);

        map.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> shader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.vertex(-1.0, -1.0, 0.0).endVertex();
        builder.vertex(1.0, -1.0, 0.0).endVertex();
        builder.vertex(1.0, 1.0, 0.0).endVertex();
        builder.vertex(-1.0, 1.0, 0.0).endVertex();
        BufferUploader.drawWithShader(builder.end());
        main.bindWrite(true);
    }
}
