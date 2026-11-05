package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.entity.CarEntityBase;
import com.jdmmc.kurumamod.network.CarSpawnDataPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 車が現れるときに、追加のデータ（{@link CarSpawnDataPacket}）を一緒に送る。NeoForge の
 * {@code IEntityWithComplexSpawn} と同じく、<b>実体を足すパケットのすぐ後ろ</b>（同じ束の中）に入れる。
 * クライアントは実体をワールドに足してから、ほかのパケットより先にこれを読む。
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "sendPairingData", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0, shift = At.Shift.AFTER))
    private void kuruma$sendSpawnData(ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> sender, CallbackInfo ci) {
        if (entity instanceof CarEntityBase car) {
            // 中身はクライアント共通の custom payload。束の受け手は ClientGamePacketListener なので型だけ合わせる
            sender.accept((Packet) ServerPlayNetworking.createClientboundPacket(CarSpawnDataPacket.of(car)));
        }
    }
}
