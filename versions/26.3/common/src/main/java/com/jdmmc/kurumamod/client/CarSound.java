package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import com.jdmmc.kurumamod.surface.RoadSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import com.jdmmc.kurumamod.client.model.CarPartModel;
import com.jdmmc.kurumamod.client.sound.CarSoundSet;
import com.jdmmc.kurumamod.client.sound.CarSoundVoice;

/**
 * 車から出るループ音 1 本ぶん。<b>音量と音程を決める中身は core の {@link CarSoundVoice}</b>（ピッチの壁と
 * 2 枚のクロスフェード、路面ごとの音、悲鳴の刻みと伸ばし、などの理由もそちらに書いてある）。
 *
 * <p>ここはバニラの音として鳴らすところだけ: 音源（{@link SoundEvent}）を選ぶ、車の位置へ置く、
 * 車の状態を core へ渡す、変速音を鳴らす。</p>
 */
public class CarSound extends AbstractTickableSoundInstance {

    private final CarEntity car;
    private final CarSoundVoice voice;

    public CarSound(CarEntity car, CarSoundVoice.Role role) {
        this(car, role, soundsOf(car));
    }

    private CarSound(CarEntity car, CarSoundVoice.Role role, CarSoundSet sounds) {
        super(soundOf(sounds, role), SoundSource.NEUTRAL, car.level().getRandom());
        this.car = car;
        this.voice = new CarSoundVoice(role, sounds, car.getRenderGear());
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.pitch = 1.0F;
        updatePosition();
    }

    /**
     * その車で鳴らす音の組。車種の差し替えに、付けているマフラーの音源を重ねたもの。
     *
     * <p>{@link CarSounds} も同じものを見て、マフラーの音源が変わったら排気音を鳴らし直す。</p>
     */
    static CarSoundSet soundsOf(CarEntity car) {
        return CarSoundSet.of(car.getCarId()).withExhaust(CarPartModel.exhaustOf(car.getEffectiveFitment()));
    }

    static boolean isExhaust(CarSoundVoice.Role role) {
        return CarSoundVoice.isExhaust(role);
    }

    static boolean isUsed(CarSoundVoice.Role role, CarSoundSet sounds) {
        return CarSoundVoice.isUsed(role, sounds);
    }

    /**
     * 1 回だけ鳴る音の大きさに、好みの音量（種類ごとと全体）を掛ける。1 を超えると音の届く距離まで
     * 伸びるので抑える。
     */
    static float volume(float base, double gain) {
        return CarSoundVoice.volume(base, gain, ClientConfig.soundMaster);
    }

    private static SoundEvent soundOf(CarSoundSet sounds, CarSoundVoice.Role role) {
        if (role.isEngine()) {
            return KurumaSounds.engine(sounds.engineType(), role.band, role.load);
        }
        CarSoundVoice.Role source = CarSoundVoice.sourceOf(role, sounds);
        return CarSoundEvents.resolve(sounds, source.name().toLowerCase(java.util.Locale.ROOT),
                () -> defaultSoundOf(source));
    }

    private static SoundEvent defaultSoundOf(CarSoundVoice.Role role) {
        if (role.isEngine()) {
            return KurumaSounds.engine(KurumaSounds.DEFAULT_ENGINE_TYPE, role.band, role.load);
        }
        return switch (role) {
            case EXHAUST -> KurumaSounds.CAR_EXHAUST.get();
            case EXHAUST_HIGH -> KurumaSounds.CAR_EXHAUST_HIGH.get();
            case ROAD -> KurumaSounds.CAR_ROAD.get();
            case WIND -> KurumaSounds.CAR_WIND.get();
            case SLIP -> KurumaSounds.CAR_SLIP.get();
            case SLIP_CHATTER -> KurumaSounds.CAR_SLIP_CHATTER.get();
            case GRAVEL -> KurumaSounds.CAR_GRAVEL.get();
            case SAND -> KurumaSounds.CAR_SAND.get();
            case TURBO -> KurumaSounds.CAR_TURBO.get();
            default -> throw new IllegalStateException(role.name());
        };
    }

    /**
     * 音量 0 で始まっても再生を続けさせる。
     *
     * <p>これを true にしないと、鳴り始めが無音のループ（停車中のロードノイズなど）は
     * 音響エンジンに弾かれ、二度と鳴らない。</p>
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (car.isRemoved() || Minecraft.getInstance().isPaused()) {
            if (car.isRemoved()) {
                stop();
            }
            return;
        }
        updatePosition();
        boolean keep = voice.tick(INPUT.of(car), GAINS, (shiftVolume, shiftPitch) ->
                car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                        CarSoundEvents.shift(voice.sounds()), SoundSource.NEUTRAL, shiftVolume, shiftPitch, false));
        if (!keep) {
            stop();
            return;
        }
        volume = voice.volume();
        pitch = voice.pitch();
    }

    private void updatePosition() {
        this.x = (float) car.getX();
        this.y = (float) car.getY();
        this.z = (float) car.getZ();
    }

    /** H の画面の「サウンド」の値。 */
    private static final CarSoundVoice.Gains GAINS = new CarSoundVoice.Gains() {
        @Override
        public double master() {
            return ClientConfig.soundMaster;
        }

        @Override
        public double engine() {
            return ClientConfig.soundEngine;
        }

        @Override
        public double road() {
            return ClientConfig.soundRoad;
        }

        @Override
        public double wind() {
            return ClientConfig.soundWind;
        }

        @Override
        public double tires() {
            return ClientConfig.soundTires;
        }

        @Override
        public double surface() {
            return ClientConfig.soundSurface;
        }

        @Override
        public double turbo() {
            return ClientConfig.soundTurbo;
        }

        @Override
        public double shift() {
            return ClientConfig.soundShift;
        }
    };

    /** 車を core の {@link CarSoundVoice.Car} として包む。 */
    private interface Input {
        CarSoundVoice.Car of(CarEntity car);
    }

    private static final Input INPUT = car -> new CarSoundVoice.Car() {
        @Override
        public CarSpec spec() {
            return car.getSpec();
        }

        @Override
        public double rpm() {
            return car.getRenderRpm();
        }

        @Override
        public double throttle() {
            return car.getRenderThrottle();
        }

        @Override
        public double speed() {
            return car.getRenderSpeed();
        }

        @Override
        public int gear() {
            return car.getRenderGear();
        }

        @Override
        public RoadSurface surface() {
            return car.getRenderSurface();
        }

        @Override
        public RoadSurface surface(Wheel wheel) {
            return car.getRenderSurface(wheel);
        }

        @Override
        public double slideSpeed(Wheel wheel) {
            return car.getRenderSlideSpeed(wheel);
        }

        @Override
        public double scrubSpeed(Wheel wheel) {
            return car.getRenderScrubSpeed(wheel);
        }

        @Override
        public boolean hasTurbo() {
            return car.hasTurbo();
        }

        @Override
        public double turboBoost() {
            return CarTurbo.boost(car);
        }

        @Override
        public PartFitment fitment() {
            return car.getEffectiveFitment();
        }

        @Override
        public CarSoundSet sounds() {
            return soundsOf(car);
        }
    };
}
