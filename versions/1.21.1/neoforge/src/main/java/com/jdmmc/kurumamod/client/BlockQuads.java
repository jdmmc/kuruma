package com.jdmmc.kurumamod.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.function.Consumer;

/**
 * ブロックのモデルから、ある向きの面を集める（NeoForge 版）。ヘッドライトが照らす面を作るのに使う（{@code CarLights}）。
 *
 * <p>NeoForge のモデルデータと、描画の層ごとの面を通す。通さないと、接続して形が変わるブロック
 * （他 MOD の道路など）で面が取れないことがある。<b>Fabric 版（versions/1.21.1/fabric/）にも同じ名前・同じメソッドの
 * このクラスがある</b>（そちらは素の {@code getQuads}）。</p>
 */
public final class BlockQuads {

    private BlockQuads() {
    }

    /**
     * {@code side} を向いた面を {@code sink} へ渡す。{@code side} を向いていない面（向きを持たない面のうち
     * 法線が {@code side} のもの）も含む。面の一覧は描画と同じ乱数の種で引く。
     */
    public static void collect(BakedModel model, Level level, BlockPos pos, BlockState state,
                               Direction side, RandomSource random, Consumer<BakedQuad> sink) {
        ModelData data = level.getModelDataManager().getAt(pos);
        data = model.getModelData(level, pos, state, data == null ? ModelData.EMPTY : data);
        long seed = state.getSeed(pos);
        random.setSeed(seed);
        for (RenderType layer : model.getRenderTypes(state, random, data)) {
            random.setSeed(seed);
            for (BakedQuad quad : model.getQuads(state, side, random, data, layer)) {
                sink.accept(quad);
            }
            random.setSeed(seed);
            for (BakedQuad quad : model.getQuads(state, null, random, data, layer)) {
                if (quad.getDirection() == side) {
                    sink.accept(quad);
                }
            }
        }
    }
}
