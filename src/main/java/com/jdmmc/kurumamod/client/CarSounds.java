package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.event.TickEvent;
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
 *
 * <h2>音響エンジンが読み直されたら鳴らし直す</h2>
 *
 * <p><b>F3+T（リソースの再読み込み）は音響エンジンごと読み直し、鳴っている音をすべて止める。</b>
 * 車の音は車が現れたときにしか鳴らし始めないので、そのままだと<b>再読み込みの後は
 * 車が無音になる</b>（音を差し替えて確かめようとしたら消えた）。読み直しの知らせ
 * （{@link SoundEngineLoadEvent}）を受けて、次のティックでいる車すべてに鳴らし直す。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarSounds {

    /** 音響エンジンが読み直されたので、次のティックで鳴らし直す。 */
    private static volatile boolean replayPending;

    private CarSounds() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide || !(event.getEntity() instanceof CarEntity car)) {
            return;
        }
        play(car);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !replayPending) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // タイトル画面で読み直した。ワールドに入れば車は現れ直すので何もしなくてよい
            replayPending = false;
            return;
        }
        replayPending = false;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof CarEntity car && !car.isRemoved()) {
                play(car);
            }
        }
    }

    private static void play(CarEntity car) {
        for (CarSound.Role role : CarSound.Role.values()) {
            Minecraft.getInstance().getSoundManager().play(new CarSound(car, role));
        }
    }

    /**
     * 読み直しの知らせは <b>mod のイベントバス</b>に飛ぶので、別のクラスで受ける。
     *
     * <p>知らせを受けたその場では鳴らさない。読み直しの途中から呼ばれるので、
     * 次のティックまで待ってから鳴らす。</p>
     */
    @Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class EngineReload {

        private EngineReload() {
        }

        @SubscribeEvent
        public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
            replayPending = true;
        }
    }
}
