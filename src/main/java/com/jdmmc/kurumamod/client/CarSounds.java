package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 車がクライアントに現れたら、ループ音を張り付ける。
 *
 * <p>止めるのは音の側の仕事（{@link CarSound#tick()} が車の消滅を見て自分で止まる）。
 * ここでは開始だけを受け持つ。</p>
 *
 * <p>車はエンティティの追跡範囲（10 チャンク）に入ったときだけクライアントに現れるので、
 * 遠くの車にまで音を作ってしまう心配はない。距離による減衰は音響エンジンが面倒を見る。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarSounds {

    private CarSounds() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide || !(event.getEntity() instanceof CarEntity car)) {
            return;
        }
        for (CarSound.Role role : CarSound.Role.values()) {
            Minecraft.getInstance().getSoundManager().play(new CarSound(car, role));
        }
    }
}
