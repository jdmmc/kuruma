package com.jdmmc.kurumamod.client;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * ブロックのモデルから、ある向きの面を集める（Fabric 版）。NeoForge 版（neoforge-1.21.1/）にも同じ名前・
 * 同じメソッドのこのクラスがある（そちらは NeoForge のモデルデータと描画の層を通す）。
 *
 * <p>Fabric には描画の層ごとの面もモデルデータも無いので、素の {@code getQuads} を引く。面の一覧は
 * 描画と同じ乱数の種で引く。</p>
 */
public final class BlockQuads {

    private BlockQuads() {
    }

    public static void collect(BakedModel model, Level level, BlockPos pos, BlockState state,
                               Direction side, RandomSource random, Consumer<BakedQuad> sink) {
        long seed = state.getSeed(pos);
        random.setSeed(seed);
        for (BakedQuad quad : model.getQuads(state, side, random)) {
            sink.accept(quad);
        }
        random.setSeed(seed);
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            if (quad.getDirection() == side) {
                sink.accept(quad);
            }
        }
    }
}
