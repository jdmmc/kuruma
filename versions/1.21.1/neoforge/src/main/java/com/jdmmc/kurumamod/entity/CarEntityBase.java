package com.jdmmc.kurumamod.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

/**
 * {@link CarEntity} の親（NeoForge 版）。車の中身は versions/1.21.1/common/ の {@link CarEntity} にあり、ここは
 * NeoForge の {@link IEntityWithComplexSpawn}（現れるときに追加のデータを運ぶ口）を付けるためだけにある。
 *
 * <p>Fabric 版（versions/1.21.1/fabric/）の同じ名前のクラスは、{@code writeSpawnData} / {@code readSpawnData} を
 * 抽象メソッドとして持ち、運ぶのは mixin（{@code ServerEntityMixin}）が足すパケット。どちらも、
 * 実体を足すパケットのすぐ後ろ（同じ束の中）で届き、クライアントは実体をワールドに足してから読む。</p>
 */
public abstract class CarEntityBase extends Entity implements IEntityWithComplexSpawn {

    protected CarEntityBase(EntityType<?> type, Level level) {
        super(type, level);
    }
}
