package com.jdmmc.kurumamod.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * {@link CarEntity} の親（Fabric 版）。車の中身は mc-1.21.1/ の {@link CarEntity} にある。
 *
 * <p>NeoForge 版は NeoForge の {@code IEntityWithComplexSpawn} で現れるときに追加のデータを運ぶ。Fabric には
 * それが無いので、ここで同じ 2 つのメソッドを抽象として持たせ、運ぶのは mixin
 * （{@code fabric.mixin.ServerEntityMixin}）が足すパケット（{@code CarSpawnDataPacket}）にしてある。
 * NeoForge と同じく、実体を足すパケットのすぐ後ろ（同じ束の中）で届き、クライアントは実体をワールドに
 * 足してから読む。</p>
 */
public abstract class CarEntityBase extends Entity {

    protected CarEntityBase(EntityType<?> type, Level level) {
        super(type, level);
    }

    /** 現れるときに一緒に送るデータを書く。 */
    public abstract void writeSpawnData(RegistryFriendlyByteBuf buf);

    /** 現れるときに一緒に届いたデータを読む（実体はもうワールドにいる）。 */
    public abstract void readSpawnData(RegistryFriendlyByteBuf buf);
}
