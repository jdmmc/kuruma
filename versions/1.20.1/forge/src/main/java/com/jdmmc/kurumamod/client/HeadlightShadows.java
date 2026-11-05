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

    /**
     * 格子の水平の大きさの最小（カメラから ±48）と高さ（±12）。
     *
     * <p><b>水平の大きさは光の届く距離（Ctrl+L）で変わる。</b>96m 以下では 96 のまま（以前とまったく同じ）。
     * それより遠くまで照らすときは、届く距離の 2 倍（カメラから ±届く距離）まで広げ、
     * {@link #MAX_GRID} で頭打ちにする。格子の外は「光を遮らない」扱いなので、狭いままだと
     * 遠くで光が壁や丘をすり抜ける。影の地図の届く距離も格子の半分に合わせる（シェーダーが GridSize から求める）。</p>
     */
    static final int MIN_GRID = 96;
    static final int GRID_Y = 24;
    /** 水平の大きさの上限（±128）。初回とテレポートの読み直しが 96 の約 7 倍になるので、これ以上は広げない。 */
    static final int MAX_GRID = 256;
    /** 今の水平の大きさ。 */
    private static int grid = MIN_GRID;
    /** 1 フレームに読み直す行（z）の数。grid / これ ＝ 全体が新しくなるまでのフレーム数。 */
    private static final int ROWS_PER_FRAME = 8;
    /** カメラがこれより大きく飛んだら（テレポートなど）全部読み直す。 */
    private static final int JUMP = 24;

    /** 距離の地図。横が光の左右、縦がランプの数だけの段（灯数に合わせて高さを変える）。 */
    static final int MAP_WIDTH = 512;
    static final int MAP_TILE_HEIGHT = 128;

    /** 1 ブロックのバイト数。下端・上端・X の範囲・Z の範囲。 */
    private static final int BYTES = 4;

    private static int voxelTexture = -1;
    private static ByteBuffer voxels;
    private static int cursor;
    private static BlockPos lastCenter;
    private static TextureTarget map;

    /**
     * 画素ごとの光で扱えるランプの数の天井。<b>実際の天井はテクスチャの最大の高さ</b>
     * （影の地図が 1 灯 {@link #MAP_TILE_HEIGHT} 行）で、普通の GPU（16384）なら 128 灯＝64 台。
     */
    static final int LAMP_CAP = 128;
    /** ランプの行列を詰めたテクスチャ（RGBA 浮動小数、横 4 画素＝4 列 × 縦 1 灯 1 行）。 */
    private static int lampTexture = -1;
    private static java.nio.FloatBuffer lampData;
    private static int maxLamps = -1;

    private HeadlightShadows() {
    }

    /** 格子を詰めたテクスチャの横幅（高さの段を横に並べる）。 */
    private static int width() {
        return grid * GRID_Y;
    }

    /** 光の届く距離から決まる、格子の水平の大きさ。96m 以下は {@link #MIN_GRID}。 */
    private static int wantedGrid() {
        double range = com.jdmmc.kurumamod.ClientConfig.headlightRange;
        if (range <= MIN_GRID) {
            return MIN_GRID;
        }
        int size = (int) Math.ceil(range * 2.0 / 16.0) * 16;
        return Math.min(MAX_GRID, Math.max(MIN_GRID, size));
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
        return MAP_TILE_HEIGHT * Math.max(1, lamps);
    }

    /** この GPU で扱えるランプの数。影の地図の高さがテクスチャの最大を超えない範囲。 */
    static int maxLamps() {
        if (maxLamps < 0) {
            int size = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            maxLamps = Math.max(1, Math.min(LAMP_CAP, size / MAP_TILE_HEIGHT));
        }
        return maxLamps;
    }

    /** ランプの行列のテクスチャ。 */
    static int lampTexture() {
        return lampTexture;
    }

    /**
     * ランプの行列をテクスチャへ送る。1 灯が 1 行で、4 画素が行列の 4 列
     * （シェーダーの {@code mat4} の列と同じ並び。列 0 位置と届く距離・列 1 光軸と強さ・列 2 右・列 3 上）。
     *
     * <p>以前は 1 灯ずつ uniform（{@code Lamp0}〜）で渡していたので、灯数がシェーダーに決め打ちだった。</p>
     */
    static void uploadLamps(Matrix4f[] lamps, int count) {
        int rows = maxLamps();
        if (lampTexture < 0) {
            lampData = MemoryUtil.memAllocFloat(4 * 4 * rows);
            lampTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(lampTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            resetUnpack();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30.GL_RGBA32F, 4, rows, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
        }
        int n = Math.min(count, rows);
        if (n <= 0) {
            return;
        }
        lampData.clear();
        org.joml.Vector4f column = new org.joml.Vector4f();
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 4; c++) {
                lamps[i].getColumn(c, column);
                lampData.put(column.x).put(column.y).put(column.z).put(column.w);
            }
        }
        lampData.flip();
        GlStateManager._bindTexture(lampTexture);
        resetUnpack();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 4, n, GL11.GL_RGBA, GL11.GL_FLOAT, lampData);
    }

    /** 格子を読み直す。新しく範囲に入った列はすぐ、それ以外は少しずつ。初回とテレポートの後は全部。 */
    static void update(Level level, Vec3 camera) {
        BlockPos center = BlockPos.containing(camera);
        int wanted = wantedGrid();
        if (wanted != grid && voxelTexture >= 0) {
            // 届く距離が変わって格子の大きさが変わった。作り直して全部読み直す
            GlStateManager._deleteTexture(voxelTexture);
            MemoryUtil.memFree(voxels);
            voxelTexture = -1;
            voxels = null;
            lastCenter = null;
            cursor = 0;
        }
        grid = wanted;
        if (voxelTexture < 0) {
            voxels = MemoryUtil.memAlloc(width() * grid * BYTES);
            MemoryUtil.memSet(voxels, 0);
            voxelTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(voxelTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            resetUnpack();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width(), grid, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, voxels);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = center.getX() - grid / 2;
        int minY = center.getY() - GRID_Y / 2;
        int minZ = center.getZ() - grid / 2;
        GlStateManager._bindTexture(voxelTexture);
        resetUnpack();

        if (lastCenter == null || lastCenter.distManhattan(center) > JUMP) {
            // 初回とテレポートの後は全部
            for (int z = minZ; z < minZ + grid; z++) {
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
        int oldMinX = lastCenter.getX() - grid / 2;
        int oldMinY = lastCenter.getY() - GRID_Y / 2;
        int oldMinZ = lastCenter.getZ() - grid / 2;
        lastCenter = center;
        boolean whole = false;
        for (int x : entered(oldMinX, minX, grid)) {
            for (int z = minZ; z < minZ + grid; z++) {
                for (int y = minY; y < minY + GRID_Y; y++) {
                    fillCell(level, pos, x, y, z);
                }
            }
            whole = true;
        }
        for (int y : entered(oldMinY, minY, GRID_Y)) {
            for (int z = minZ; z < minZ + grid; z++) {
                for (int x = minX; x < minX + grid; x++) {
                    fillCell(level, pos, x, y, z);
                }
            }
            whole = true;
        }
        java.util.List<Integer> rows = new java.util.ArrayList<>();
        for (int z : entered(oldMinZ, minZ, grid)) {
            fillRow(level, pos, z, minX, minY);
            rows.add(Math.floorMod(z, grid));
        }

        // 置いた・壊したブロックを拾うための読み直し。少しずつ回す
        for (int r = 0; r < ROWS_PER_FRAME; r++) {
            int row = cursor;
            cursor = (cursor + 1) % grid;
            // この行（z mod grid == row）に当たる、いまの範囲の中の世界の z
            int z = minZ + Math.floorMod(row - minZ, grid);
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
            for (int x = minX; x < minX + grid; x++) {
                fillCell(level, pos, x, y, z);
            }
        }
    }

    private static void fillCell(Level level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        int offset = (Math.floorMod(z, grid) * width() + Math.floorMod(x, grid) + Math.floorMod(y, GRID_Y) * grid) * BYTES;
        pos.set(x, y, z);
        voxels.putInt(offset, occlusion(level, pos));
    }

    private static void uploadRow(int row) {
        int rowOffset = row * width() * BYTES;
        voxels.position(rowOffset).limit(rowOffset + width() * BYTES);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, row, width(), 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, voxels);
        voxels.clear();
    }

    private static void uploadAll() {
        voxels.clear();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width(), grid, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, voxels);
    }

    /** 全部を遮るブロック（下端 0・上端 255・X と Z が 0..15）。 */
    private static final int FULL = pack(0, 255, 0x0F, 0x0F);

    /**
     * そのブロックが光を遮る箱。テクスチャの 1 画素（RGBA）に詰める:
     * R = 下端・G = 上端（0..255）、B = X の範囲・A = Z の範囲（上位 4 ビットが始まり、
     * 下位 4 ビットが終わり。1/16 ブロック刻み）。遮らなければ 0（上端が下端以下は「空」）。
     *
     * <p><b>横の範囲も持つこと。</b>高さだけで横を 1 ブロックいっぱいとして扱うと、塀の柱が
     * 光と平行に並んだとき、隣の柱のブロックを通る光線がすべて遮られ、<b>柱の横の面が
     * 暗くなり、柱と柱のあいだが縞になった</b>。当たり判定の外枠（{@code bounds}）なので、
     * 塀の柱と腕のような形は外枠の箱になる。</p>
     */
    private static int occlusion(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0;
        }
        // 光を通すもの
        if (state.getBlock() instanceof AbstractGlassBlock || state.getBlock() instanceof IronBarsBlock) {
            return 0;
        }
        if (state.isCollisionShapeFullBlock(level, pos)) {
            return FULL;
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
        AABB box = shape.bounds();
        int low = Mth.clamp((int) Math.floor(box.minY * 255.0), 0, 254);
        int high = Mth.clamp((int) Math.ceil(box.maxY * 255.0), low + 1, 255);
        return pack(low, high, sixteenths(box.minX, box.maxX), sixteenths(box.minZ, box.maxZ));
    }

    /** 0..1 の範囲を、始まりと終わりの 1/16 刻み（4 ビットずつ）に詰める。 */
    private static int sixteenths(double min, double max) {
        int from = Mth.clamp((int) Math.floor(min * 16.0 + 1.0e-4), 0, 15);
        int to = Mth.clamp((int) Math.ceil(max * 16.0 - 1.0e-4) - 1, from, 15);
        return (from << 4) | to;
    }

    /** {@code putInt} したときに R, G, B, A の順に並ぶよう詰める（memAlloc のバッファはネイティブ＝リトルエンディアン）。 */
    private static int pack(int r, int g, int b, int a) {
        return (r & 0xFF) | (g & 0xFF) << 8 | (b & 0xFF) << 16 | (a & 0xFF) << 24;
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
        shader.safeGetUniform("GridSize").set((float) grid, (float) GRID_Y);
    }

    /** ランプから見た距離の地図を描く。描き終えたら主画面へ戻す。 */
    static void renderMap(ShaderInstance shader, int count, Vec3 camera, RenderTarget main) {
        // 高さは灯数に合わせる。使わない段まで描くと、灯数を増やせるようにした意味が薄れる
        int height = mapHeight(count);
        if (map == null) {
            map = new TextureTarget(MAP_WIDTH, height, false, Minecraft.ON_OSX);
        } else if (map.height != height) {
            map.resize(MAP_WIDTH, height, Minecraft.ON_OSX);
        }
        shader.setSampler("VoxelSampler", voxelTexture);
        shader.setSampler("LampSampler", lampTexture);
        setGridUniforms(shader, camera);
        shader.safeGetUniform("LampCount").set(count);
        shader.safeGetUniform("LightRange").set((float) com.jdmmc.kurumamod.ClientConfig.headlightRange);

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
