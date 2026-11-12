package com.jdmmc.kurumamod.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * ヘッドライトの影。<b>ブロックに当たった光はその先へ届かない</b>ようにする（26.3 版。renderpearl で書き直したもの）。
 *
 * <h2>2 段構え</h2>
 *
 * <ol>
 *   <li><b>ブロックの格子</b> … カメラの周り 96×24×96 ブロックの「光を遮る箱」を
 *       テクスチャ（RGBA8）に持つ。<b>毎フレーム一部ずつ</b>読み直すので、全体が新しくなるまで
 *       12 フレームほど掛かる（ブロックを置いた・壊したが影に出るまでの遅れ）</li>
 *   <li><b>ランプから見た距離の地図</b> … ランプごとに、光の出る向き 512×128 方向へ格子の中を
 *       光線で辿り（DDA）、最初に当たるブロックまでの距離を書く（{@code headlight_shadow.fsh}）。
 *       照らす側（{@code headlight_volume.fsh}）は、空中の点（光線・煙）までの距離がこの地図より遠ければ影。
 *       路面は地図を使わず、画素ごとに点からランプまで格子を辿る</li>
 * </ol>
 *
 * <p>1.21.1 版との違いはテクスチャの作り方と送り方だけ（OpenGL を直接呼ばず、{@link GpuDevice} と
 * {@link CommandEncoder#writeToTexture} を使う。OpenGL と Vulkan のどちらでも動く）。格子の中身・折り返し・
 * 読み直しの順番・地図の範囲は 1.21.1 と同じ。</p>
 *
 * <h2>格子は世界の座標で折り返して持つ</h2>
 *
 * <p>テクスチャ上の位置は「世界の座標 mod 格子の大きさ」なので、カメラが動いても格子を
 * ずらし直す必要がない。<b>新しく範囲に入った列はその場で読む</b>（範囲に入ったばかりの列には
 * 反対側の中身が残っていて、待つと後ろの地面が前の壁として扱われる）。</p>
 *
 * <p>ガラスと板ガラス（鉄格子の仲間）は光を通す。水も通す（水面は照らす側が面として扱う）。</p>
 */
final class HeadlightShadows {

    /**
     * 格子の水平の大きさの最小（カメラから ±48）と高さ（±12）。
     *
     * <p><b>水平の大きさは光の届く距離（Ctrl+L）で変わる。</b>96m 以下では 96 のまま。
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

    private static GpuTexture voxelTexture;
    private static GpuTextureView voxelView;
    private static ByteBuffer voxels;
    private static int cursor;
    private static BlockPos lastCenter;

    private static GpuTexture mapTexture;
    private static GpuTextureView mapView;

    /**
     * 画素ごとの光で扱えるランプの数の天井。<b>実際の天井はテクスチャの最大の高さ</b>
     * （影の地図が 1 灯 {@link #MAP_TILE_HEIGHT} 行）で、普通の GPU（16384）なら 128 灯＝64 台。
     */
    static final int LAMP_CAP = 128;
    /** ランプの行列を詰めたテクスチャ（RGBA 32 ビット浮動小数、横 4 画素＝4 列 × 縦 1 灯 1 行）。 */
    private static GpuTexture lampTexture;
    private static GpuTextureView lampView;
    private static ByteBuffer lampData;
    private static int maxLamps = -1;

    private HeadlightShadows() {
    }

    /** 格子を詰めたテクスチャの横幅（高さの段を横に並べる）。 */
    private static int width() {
        return grid * GRID_Y;
    }

    /** 光の届く距離から決まる、格子の水平の大きさ。96m 以下は {@link #MIN_GRID}。 */
    private static int wantedGrid() {
        double range = com.jdmmc.kurumamod.config.ClientConfig.headlightRange;
        if (range <= MIN_GRID) {
            return MIN_GRID;
        }
        int size = (int) Math.ceil(range * 2.0 / 16.0) * 16;
        return Math.min(MAX_GRID, Math.max(MIN_GRID, size));
    }

    /** 今の格子の水平の大きさ（シェーダーの GridSize.x）。 */
    static int grid() {
        return grid;
    }

    static int mapHeight(int lamps) {
        return MAP_TILE_HEIGHT * Math.max(1, lamps);
    }

    /** 格子のテクスチャ。まだ作っていなければ null。 */
    static GpuTextureView voxelView() {
        return voxelView;
    }

    /** 距離の地図。まだ描いていなければ null。 */
    static GpuTextureView mapView() {
        return mapView;
    }

    /** ランプの行列のテクスチャ。まだ作っていなければ null。 */
    static GpuTextureView lampView() {
        return lampView;
    }

    /** どのテクスチャも 1 画素ずつ読むので、補間しないサンプラー。 */
    static GpuSampler nearest() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    /** この GPU で扱えるランプの数。影の地図の高さがテクスチャの最大を超えない範囲。 */
    static int maxLamps() {
        if (maxLamps < 0) {
            int size = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
            maxLamps = Math.max(1, Math.min(LAMP_CAP, size / MAP_TILE_HEIGHT));
        }
        return maxLamps;
    }

    /**
     * ランプの行列をテクスチャへ送る。1 灯が 1 行で、4 画素が行列の 4 列
     * （シェーダーの {@code mat4} の列と同じ並び。列 0 位置と届く距離・列 1 光軸と強さ・列 2 右・列 3 上）。
     */
    static void uploadLamps(CommandEncoder encoder, Matrix4f[] lamps, int count) {
        int rows = maxLamps();
        if (lampTexture == null) {
            GpuDevice device = RenderSystem.getDevice();
            lampTexture = device.createTexture("kurumamod headlight lamps",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, 4, rows, 1, 1);
            lampView = device.createTextureView(lampTexture);
            lampData = MemoryUtil.memAlloc(4 * 4 * 4 * rows);
        }
        int n = Math.min(count, rows);
        if (n <= 0) {
            return;
        }
        lampData.clear();
        Vector4f column = new Vector4f();
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 4; c++) {
                lamps[i].getColumn(c, column);
                lampData.putFloat(column.x).putFloat(column.y).putFloat(column.z).putFloat(column.w);
            }
        }
        lampData.flip();
        encoder.writeToTexture(lampTexture, lampData, 0, 0, 0, 0, 4, n);
    }

    /** 格子を読み直す。新しく範囲に入った列はすぐ、それ以外は少しずつ。初回とテレポートの後は全部。 */
    static void update(CommandEncoder encoder, Level level, Vec3 camera) {
        BlockPos center = BlockPos.containing(camera);
        int wanted = wantedGrid();
        if (wanted != grid && voxelTexture != null) {
            // 届く距離が変わって格子の大きさが変わった。作り直して全部読み直す
            voxelView.close();
            voxelTexture.close();
            MemoryUtil.memFree(voxels);
            voxelTexture = null;
            voxelView = null;
            voxels = null;
            lastCenter = null;
            cursor = 0;
        }
        grid = wanted;
        if (voxelTexture == null) {
            voxels = MemoryUtil.memAlloc(width() * grid * BYTES);
            MemoryUtil.memSet(voxels, 0);
            GpuDevice device = RenderSystem.getDevice();
            voxelTexture = device.createTexture("kurumamod headlight voxels",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width(), grid, 1, 1);
            voxelView = device.createTextureView(voxelTexture);
            lastCenter = null;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = center.getX() - grid / 2;
        int minY = center.getY() - GRID_Y / 2;
        int minZ = center.getZ() - grid / 2;

        if (lastCenter == null || lastCenter.distManhattan(center) > JUMP) {
            // 初回とテレポートの後は全部
            for (int z = minZ; z < minZ + grid; z++) {
                fillRow(level, pos, z, minX, minY);
            }
            uploadAll(encoder);
            lastCenter = center;
            return;
        }

        // <b>新しく範囲に入った列は、その場で読む。</b>格子は折り返して持っているので、
        // 範囲に入ったばかりの列には<b>反対側（96 ブロック後ろ）の中身</b>が残っている。
        // 読み直しの順番を待つと、そのあいだ後ろの地面が前の壁として扱われ、
        // <b>走っていると遠くの路面がときどき黒くなった</b>（1.21.1）
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
            uploadAll(encoder);
        } else {
            for (int row : rows) {
                uploadRow(encoder, row);
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

    /** 1 行（z mod grid == row）ぶんを送る。{@code writeToTexture} はバッファの今の位置から読むので切り出して渡す。 */
    private static void uploadRow(CommandEncoder encoder, int row) {
        int rowBytes = width() * BYTES;
        encoder.writeToTexture(voxelTexture, MemoryUtil.memSlice(voxels, row * rowBytes, rowBytes), 0, 0, 0, row, width(), 1);
    }

    private static void uploadAll(CommandEncoder encoder) {
        voxels.clear();
        encoder.writeToTexture(voxelTexture, voxels, 0, 0, 0, 0, width(), grid);
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
        if (state.getBlock() instanceof TransparentBlock || state.getBlock() instanceof IronBarsBlock) {
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
     * ランプから見た距離の地図を描く。高さは灯数に合わせる（使わない段まで描くと、灯数を絞った意味が薄れる）。
     *
     * @param info このフレームの {@code HeadlightInfo}（{@link HeadlightVolume#writeInfo} が詰めたもの）
     */
    static void renderMap(CommandEncoder encoder, CompiledRenderPipeline pipeline, GpuBuffer info, int count) {
        int height = mapHeight(count);
        if (mapTexture == null || mapTexture.getHeight(0) != height) {
            if (mapTexture != null) {
                mapView.close();
                mapTexture.close();
            }
            GpuDevice device = RenderSystem.getDevice();
            mapTexture = device.createTexture("kurumamod headlight shadow map",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM,
                    MAP_WIDTH, height, 1, 1);
            mapView = device.createTextureView(mapTexture);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "kurumamod headlight shadow map", mapView,
                Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("HeadlightInfo", info);
            pass.setUniform("LampSampler", lampView, nearest());
            pass.setUniform("VoxelSampler", voxelView, nearest());
            pass.draw(3, 1, 0, 0);
        }
    }
}
