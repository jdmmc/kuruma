package com.jdmmc.kurumamod.surface;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.Map;

/**
 * ブロックから路面の種類を引く。
 *
 * <p><b>タグ 1 段だけで、見つからなければ舗装路。</b>音や `getFriction()` から推測する
 * フォールバックは置いていない。知らないブロックはアスファルト扱いでよいと決めたので、
 * <b>他 MOD の道路ブロックはタグを足さなくてもそのまま走れる</b>。</p>
 *
 * <p>タグの中身はバニラや Forge のタグを取り込む形にしてある（`#minecraft:sand` など）ので、
 * きちんとタグ付けされた他 MOD の砂や砂利は自動で拾える。拾えなくても舗装路に落ちるだけ。</p>
 */
public final class SurfaceLookup {

    private static final Map<RoadSurface, TagKey<Block>> TAGS = new EnumMap<>(RoadSurface.class);

    static {
        for (RoadSurface surface : RoadSurface.values()) {
            if (surface != RoadSurface.PAVED) {
                TAGS.put(surface, BlockTags.create(
                        new ResourceLocation(Kurumamod.MODID, "surface/" + surface.surfaceName())));
            }
        }
    }

    private SurfaceLookup() {
    }

    /**
     * そのブロックがどの路面か。
     *
     * <p>{@link RoadSurface} の宣言順に見て、最初に当たったものを返す。細かいものから
     * 並べてあるので、氷が雪より先に当たる。</p>
     */
    public static RoadSurface of(BlockState state) {
        for (Map.Entry<RoadSurface, TagKey<Block>> entry : TAGS.entrySet()) {
            if (state.is(entry.getValue())) {
                return entry.getKey();
            }
        }
        return RoadSurface.PAVED;
    }
}
