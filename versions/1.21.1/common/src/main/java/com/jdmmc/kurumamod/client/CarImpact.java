package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.hook.CameraAngles;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import com.jdmmc.kurumamod.client.camera.ImpactShake;
import com.jdmmc.kurumamod.client.sound.CarSoundSet;

/**
 * ぶつかったときの手応え。音を鳴らし、画面を揺らす。
 * 音の大きさと高さ、揺れの状態と角度は core の {@link ImpactShake}。ここは鳴らして、カメラに足すだけ。
 */
public final class CarImpact {

    /** 足す角度の入れ物（描画のスレッドだけが使う）。 */
    private static final float[] ANGLES = new float[3];

    private CarImpact() {
    }

    /**
     * 衝突を受け取る。音は必ず鳴らし、揺れはその車に乗っているときだけ。
     *
     * <p>離れて見ている人まで揺らすと、自分がぶつかったように錯覚する。</p>
     */
    public static void onImpact(CarEntity car, double impactSpeed) {
        if (impactSpeed < ImpactShake.MIN_IMPACT_SPEED) {
            return;
        }
        float power = ImpactShake.strength(impactSpeed);
        Minecraft minecraft = Minecraft.getInstance();
        float pitch = ImpactShake.pitch(power);
        CarSoundSet sounds = CarSoundSet.of(car.getCarId());
        CarSoundSet.Tuning tuning = sounds.tuningOf(CarSoundSet.CRASH);
        car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                CarSoundEvents.crash(sounds), SoundSource.NEUTRAL,
                CarSound.volume(ImpactShake.volume(power), ClientConfig.soundCrash),
                pitch * (float) tuning.pitch(), false);

        if (minecraft.player != null && minecraft.player.getVehicle() == car) {
            ImpactShake.shake(power);
        }
    }

    /** 揺れを減衰させる。 */
    public static void onClientTick() {
        if (!Minecraft.getInstance().isPaused()) {
            ImpactShake.tick();
        }
    }

    /**
     * 視線を振る。
     *
     * <p>プレイヤーの向きそのものは書き換えない（書き換えると
     * {@link CarCamera} が「自分でマウスを動かした」と誤解して追従を止める）。
     * ここで返す角度は<b>描画にだけ効く</b>。</p>
     */
    public static void onComputeCameraAngles(CameraAngles event) {
        if (!ImpactShake.angles(event.getPartialTick(), ANGLES)) {
            return;
        }
        event.setYaw(event.getYaw() + ANGLES[0]);
        event.setPitch(event.getPitch() + ANGLES[1]);
        event.setRoll(event.getRoll() + ANGLES[2]);
    }
}
