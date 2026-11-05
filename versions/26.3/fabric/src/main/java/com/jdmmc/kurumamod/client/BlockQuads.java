package com.jdmmc.kurumamod.client;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ブロックのモデルから、ある向きの面を集める（Fabric 版）。ヘッドライトが照らす面を作るのに使う（{@code CarLights}）。
 *
 * <p>Fabric には場所ごとのモデルデータが無いので、素の {@code collectParts} を引く。<b>NeoForge 版・Forge 版にも
 * 同じ名前・同じメソッドのこのクラスがある。</b></p>
 */
public final class BlockQuads {

    private BlockQuads() {
    }

    /**
     * {@code side} を向いた面を {@code sink} へ渡す。{@code side} を向いていない面（向きを持たない面のうち
     * 向きが {@code side} のもの）も含む。部品の一覧は描画と同じ乱数の種で引く。
     */
    public static void collect(BlockStateModel model, Level level, BlockPos pos, BlockState state,
                               Direction side, RandomSource random, Consumer<BakedQuad> sink) {
        random.setSeed(state.getSeed(pos));
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(side)) {
                sink.accept(quad);
            }
            for (BakedQuad quad : part.getQuads(null)) {
                if (quad.direction() == side) {
                    sink.accept(quad);
                }
            }
        }
    }
}
