package com.jdmmc.kurumamod.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link KeyMapping} に今割り当てられているキー。素の Minecraft には読む口が無い（{@code getKey()} は NeoForge の追加、
 * Fabric API では {@code KeyBindingHelper.getBoundKeyOf}）ので、両方で同じに読めるよう mixin で取る。
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("key")
    InputConstants.Key kuruma$getKey();
}
