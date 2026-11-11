package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.car.CarId;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * core の {@link CarId} とバニラの {@link ResourceLocation} の行き来。
 *
 * <p>車種・部品の id は core が {@link CarId} で持つ。リソースを開く・音を引く・パケットに書くなど、
 * Minecraft の機能へ渡すところでだけバニラの型に直す。</p>
 */
public final class McIds {

    private McIds() {
    }

    public static ResourceLocation mc(CarId id) {
        return id == null ? null : ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }

    public static CarId of(ResourceLocation id) {
        return id == null ? null : CarId.of(id.getNamespace(), id.getPath());
    }

    /** パケットに書く（バニラの {@code writeResourceLocation} と同じ形）。 */
    public static void write(FriendlyByteBuf buf, CarId id) {
        buf.writeResourceLocation(mc(id));
    }

    /** パケットから読む（読めない id はバニラと同じくパケットごと捨てる）。 */
    public static CarId read(FriendlyByteBuf buf) {
        return of(buf.readResourceLocation());
    }
}
