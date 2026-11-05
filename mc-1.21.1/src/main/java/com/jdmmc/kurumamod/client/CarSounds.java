package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

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
public final class CarSounds {

    /** 音響エンジンが読み直されたので、次のティックで鳴らし直す。 */
    private static volatile boolean replayPending;

    private CarSounds() {
    }

    /** 実体がクライアントのワールドに現れた。NeoForge は {@code EntityJoinLevelEvent}、Fabric は {@code ClientEntityEvents.ENTITY_LOAD}。 */
    public static void onEntityJoin(Entity entity, Level level) {
        if (!level.isClientSide || !(entity instanceof CarEntity car) || car.isGhost()) {
            return;
        }
        play(car);
    }

    public static void onClientTick() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current != null) {
            replayChangedExhausts(current);
        }
        if (!replayPending) {
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
            if (entity instanceof CarEntity car && !car.isRemoved() && !car.isGhost()) {
                play(car);
            }
        }
    }

    private static void play(CarEntity car) {
        CarSoundSet sounds = CarSound.soundsOf(car);
        for (CarSound.Role role : CarSound.Role.values()) {
            if (CarSound.isUsed(role, sounds)) {
                Minecraft.getInstance().getSoundManager().play(new CarSound(car, role));
            }
        }
        EXHAUST_SOUNDS.put(car, sounds);
    }

    /**
     * 車ごとの、排気音を鳴らし始めたときの音の組。マフラーを付け替えて音源が変わったら、古い排気音は
     * 自分で止まる（{@link CarSound#tick()}）ので、こちらで新しい音源の排気音を鳴らす。
     */
    private static final java.util.Map<CarEntity, CarSoundSet> EXHAUST_SOUNDS = new java.util.WeakHashMap<>();

    /** マフラーの音源が変わった車に、排気音だけ鳴らし直す。 */
    private static void replayChangedExhausts(ClientLevel level) {
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof CarEntity car) || car.isRemoved() || car.isGhost()) {
                continue;
            }
            CarSoundSet before = EXHAUST_SOUNDS.get(car);
            if (before == null) {
                continue; // まだ鳴らしていない車。現れたときに鳴らす
            }
            CarSoundSet now = CarSound.soundsOf(car);
            // 音源が変わったときだけ鳴らし直す。高さと大きさは鳴っている音が毎ティック読み直す
            if (!now.sameSources(before)) {
                EXHAUST_SOUNDS.put(car, now);
                // 合成したエンジン音とパックの排気音が入れ替わることもあるので、鳴らす方を選び直す
                for (CarSound.Role role : CarSound.Role.values()) {
                    if (CarSound.isExhaust(role) && CarSound.isUsed(role, now)) {
                        Minecraft.getInstance().getSoundManager().play(new CarSound(car, role));
                    }
                }
            }
        }
    }

    /**
     * 読み直しの知らせは <b>mod のイベントバス</b>に飛ぶので、別のクラスで受ける。
     *
     * <p>知らせを受けたその場では鳴らさない。読み直しの途中から呼ばれるので、
     * 次のティックまで待ってから鳴らす。</p>
     */
    public static final class EngineReload {

        private EngineReload() {
        }

        /** 音のエンジンを作った・読み直した直後（{@code SoundEngine} のコンストラクタの終わりと {@code reload} の終わり）。 */
        public static void onSoundEngineLoad() {
            replayPending = true;
        }
    }
}
