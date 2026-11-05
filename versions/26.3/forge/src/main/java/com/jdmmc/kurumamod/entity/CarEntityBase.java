package com.jdmmc.kurumamod.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;

/**
 * {@link CarEntity} の親（Forge 版）。車の中身は versions/26.3/common/ の {@link CarEntity} にあり、ここは
 * Forge の {@link IEntityAdditionalSpawnData}（現れるときに追加のデータを運ぶ口）をつなぐためだけにある。
 *
 * <p>Forge の口は素の {@link FriendlyByteBuf} を渡してくるが、車は部品や諸元をレジストリ付きの
 * {@link RegistryFriendlyByteBuf} で読み書きする（NeoForge・Fabric 版と同じ形）。同じバッファをレジストリで包んで渡す。
 * {@code IEntityAdditionalSpawnData} を持つ実体は、Forge が自分で専用の出現パケットに切り替える。</p>
 */
public abstract class CarEntityBase extends Entity implements IEntityAdditionalSpawnData {

    protected CarEntityBase(EntityType<?> type, Level level) {
        super(type, level);
    }

    /** 現れるときに一緒に送るデータを書く。 */
    public abstract void writeSpawnData(RegistryFriendlyByteBuf buf);

    /** 現れるときに一緒に届いたデータを読む（実体はもうワールドにいる）。 */
    public abstract void readSpawnData(RegistryFriendlyByteBuf buf);

    @Override
    public final void writeSpawnData(FriendlyByteBuf buffer) {
        writeSpawnData(new RegistryFriendlyByteBuf(buffer, registryAccess()));
    }

    @Override
    public final void readSpawnData(FriendlyByteBuf additionalData) {
        readSpawnData(new RegistryFriendlyByteBuf(additionalData, registryAccess()));
    }
}
