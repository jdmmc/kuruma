package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import com.jdmmc.kurumamod.surface.RoadSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * 車から出るループ音 1 本ぶん。
 *
 * <p>毎ティック車の状態を読んで音量とピッチを決め直す。<b>音源は 1 秒ぶんしかないので、
 * 音の表情はほぼここで作っている。</b></p>
 *
 * <p><b>ピッチには 0.5〜2.0 の壁がある</b>（{@code Channel#setPitch} が clamp する）。
 * 1 枚では 4 倍ぶんの音程しか作れないが、アイドル 800rpm からレッドライン 7000rpm は
 * 8.75 倍ある。そこで<b>排気音だけは 1 オクターブ違う 2 枚を鳴らしてクロスフェード</b>する。</p>
 *
 * <p><b>継ぎ足すのは上側であって下側ではない。</b>1 オクターブ<b>下</b>の音源を足して
 * アイドルをそこへ置くと、元音源の 4 分の 1 の音程になり<b>アイドリング音が聞こえなくなる</b>
 * （実際にそうなった）。アイドルの高さは音源 1 枚だった頃（元音源の 0.65 倍）のまま、
 * レブ側だけを 1 オクターブ上の音源で継ぐ。これで 0.65〜3.9 倍、約 6 倍まで伸びる。</p>
 */
public class CarSound extends AbstractTickableSoundInstance {

    /** 音の役割。何を読んで音量とピッチを決めるかがこれで決まる。 */
    public enum Role {
        /** 排気音。アイドルから中回転までを受け持つ。回転数でピッチ、アクセルで音量。 */
        EXHAUST,
        /** 排気音の高回転側。同じ音源を 1 オクターブ上へ縮めたもの。レブ側を継ぐ。 */
        EXHAUST_HIGH,
        /** ロードノイズ。車速と路面。 */
        ROAD,
        /** 風切り音。車速の 2 乗。 */
        WIND,
        /** タイヤの悲鳴。滑り率とスリップ角。 */
        SLIP
    }

    /**
     * アイドルでの音程（元音源に対する倍率）。
     *
     * <p>音源 1 枚だった頃と同じ値。ここを下げると低く太くなるが、<b>下げすぎると
     * 耳に届かなくなる</b>ので動かさないこと。</p>
     */
    private static final double EXHAUST_IDLE_PITCH = 0.65;
    /**
     * レブでの音程がアイドルの何倍か。
     *
     * <p>2 枚で作れる上限は「高回転側の音源をピッチ 2.0 で鳴らしたとき」＝元音源の 4 倍なので、
     * アイドル 0.65 倍から見て約 6.15 倍。少し余裕を見て 6 倍にしてある。</p>
     */
    private static final double EXHAUST_PITCH_SPAN = 6.0;
    /** クロスフェードする区間（アイドルからの倍率）。この間はどちらの音源も壁の内側にいる。 */
    private static final double CROSSFADE_FROM = 1.6;
    private static final double CROSSFADE_TO = 3.0;

    /** ロードノイズと風切り音が最大になる車速 [m/s]。 */
    private static final double ROAD_FULL_SPEED = 30.0;
    private static final double WIND_FULL_SPEED = 45.0;

    /** これ以下の滑りでは鳴らさない。直進中の僅かな滑りで鳴りっぱなしになるのを防ぐ。 */
    private static final double SLIP_RATIO_THRESHOLD = 0.15;
    private static final double SLIP_ANGLE_THRESHOLD = 6.0;

    /** 音量の変化にひと呼吸置く。ティックごとに飛ぶと不自然に聞こえる。 */
    private static final float SMOOTHING = 0.35F;

    /**
     * スキール音の揺らぎの周期 [s]。<b>互いに割り切れない 3 本</b>にしてあるので、
     * 重ね合わせた形は事実上二度と戻ってこない（3 本の最小公倍数は 100 秒を超える）。
     *
     * <p>音源側も 3.15 秒の中で揺らしてあるが、それだけでは 3.15 秒ごとに同じ表情が戻る。
     * 鳴らす側でも揺らすと、そこがさらにばらける。</p>
     */
    private static final double[] SLIP_AMP_PERIODS = {0.83, 1.93, 4.27};
    private static final double[] SLIP_PITCH_PERIODS = {1.37, 2.11, 3.71};
    /** 揺らぎの深さ。<b>下へだけ振る</b>ので、いちばん強く鳴る瞬間の音量は変わらない。 */
    private static final float SLIP_AMP_DEPTH = 0.38F;
    private static final float SLIP_PITCH_DEPTH = 0.08F;

    private final CarEntity car;
    private final Role role;

    /**
     * 揺らぎの位相のずれ [s]。車ごとに変えて、複数台が同時に波打たないようにする。
     *
     * <p>黄金比の小数部を掛けているのは、id が連番でも位相が均等にばらけるため。</p>
     */
    private final double phaseOffset;

    /**
     * 平滑化した音量とピッチ。<b>揺らぎを掛ける前の値</b>。
     *
     * <p>{@code volume} / {@code pitch} へ直に積むと、揺らした結果が次のティックの
     * 出発点になって揺らぎが自分自身に掛かっていく。平滑化の状態は別に持つこと。</p>
     */
    private float smoothVolume;
    private float smoothPitch = 1.0F;

    /** 経過ティック。揺らぎの時間軸。 */
    private int age;

    /** 前ティックの段。変わった瞬間に変速音を鳴らす。 */
    private int lastGear;

    public CarSound(CarEntity car, Role role) {
        super(soundOf(role), SoundSource.NEUTRAL, car.level().random);
        this.car = car;
        this.role = role;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.pitch = 1.0F;
        this.phaseOffset = (car.getId() * 0.6180339887) % 1.0 * 10.0;
        this.lastGear = car.getRenderGear();
        updatePosition();
    }

    /**
     * 揺らぎ 1 本ぶん。周期の違う 3 本のサインを重ねて -1〜1 あたりの値を返す。
     *
     * <p>実際のタイヤの鳴きは一定の高さでも一定の大きさでも鳴らない。20Hz の物理から
     * この速さの表情は取り出せないので、ここで作っている。</p>
     */
    private double wobble(double[] periods) {
        double t = age * 0.05 + phaseOffset;
        return 0.55 * Math.sin(2.0 * Math.PI * t / periods[0])
                + 0.30 * Math.sin(2.0 * Math.PI * t / periods[1] + 1.7)
                + 0.15 * Math.sin(2.0 * Math.PI * t / periods[2] + 4.2);
    }

    private static SoundEvent soundOf(Role role) {
        return switch (role) {
            case EXHAUST -> KurumaSounds.CAR_EXHAUST.get();
            case EXHAUST_HIGH -> KurumaSounds.CAR_EXHAUST_HIGH.get();
            case ROAD -> KurumaSounds.CAR_ROAD.get();
            case WIND -> KurumaSounds.CAR_WIND.get();
            case SLIP -> KurumaSounds.CAR_SLIP.get();
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

        float targetVolume;
        float targetPitch;
        switch (role) {
            case EXHAUST, EXHAUST_HIGH -> {
                CarSpec spec = car.getSpec();
                double rpm = car.getRenderRpm();
                double span = Math.max(1.0, spec.redlineRpm() - spec.idleRpm());
                double t = Mth.clamp((rpm - spec.idleRpm()) / span, 0.0, 1.0);
                // アイドルで 1 倍、レブで 6 倍。これが「出したい音程」（アイドル基準）
                double ratio = 1.0 + (EXHAUST_PITCH_SPAN - 1.0) * t;
                // 元音源に対する音程。高回転側の音源は 1 オクターブ上なのでピッチは半分で済む
                double wanted = EXHAUST_IDLE_PITCH * ratio;
                boolean high = role == Role.EXHAUST_HIGH;
                targetPitch = (float) Mth.clamp(high ? wanted / 2.0 : wanted, 0.5, 2.0);
                // アイドルでも鳴っているが、踏むと前に出る
                float loudness = 0.30F + 0.50F * (float) car.getRenderThrottle();
                double blend = Mth.clamp((ratio - CROSSFADE_FROM) / (CROSSFADE_TO - CROSSFADE_FROM),
                        0.0, 1.0);
                targetVolume = loudness * (float) (high ? blend : 1.0 - blend);
            }
            case ROAD -> {
                double speed = Math.abs(car.getRenderSpeed());
                float t = (float) Mth.clamp(speed / ROAD_FULL_SPEED, 0.0, 1.0);
                // 荒れた路面ほど大きく鳴る。グリップの低さをそのまま荒さとして使う
                RoadSurface surface = car.getRenderSurface();
                float roughness = 1.0F + 0.8F * (1.0F - (float) surface.gripScale());
                targetVolume = 0.55F * t * roughness;
                targetPitch = 0.8F + 0.5F * t;
            }
            case WIND -> {
                double speed = Math.abs(car.getRenderSpeed());
                float t = (float) Mth.clamp(speed / WIND_FULL_SPEED, 0.0, 1.0);
                targetVolume = 0.6F * t * t;
                targetPitch = 0.9F + 0.5F * t;
            }
            case SLIP -> {
                double worst = 0.0;
                for (Wheel wheel : Wheel.VALUES) {
                    worst = Math.max(worst, Math.abs(car.getRenderSlipRatio(wheel)));
                }
                double longitudinal = Math.max(0.0, worst - SLIP_RATIO_THRESHOLD);
                double lateral = Math.max(0.0,
                        Math.abs(car.getRenderSlipAngleDegrees()) - SLIP_ANGLE_THRESHOLD) / 20.0;
                // 止まっているときは鳴らさない。停止寸前はスリップ角が跳ね上がるため
                double speed = Math.abs(car.getRenderSpeed());
                float gate = (float) Mth.clamp(speed / 3.0, 0.0, 1.0);
                targetVolume = (float) Mth.clamp((longitudinal + lateral) * 0.7, 0.0, 1.0) * gate;
                targetPitch = 1.0F + 0.3F * (float) Math.min(1.0, longitudinal);
            }
            default -> {
                targetVolume = 0.0F;
                targetPitch = 1.0F;
            }
        }

        age++;
        smoothVolume += (targetVolume - smoothVolume) * SMOOTHING;
        smoothPitch += (targetPitch - smoothPitch) * SMOOTHING;

        if (role == Role.SLIP) {
            // 下へだけ振る。上へも振ると音量が 1 を超えるうえ、鳴きの山が動いてしまう
            volume = smoothVolume
                    * (1.0F - SLIP_AMP_DEPTH * (float) (1.0 - wobble(SLIP_AMP_PERIODS)) * 0.5F);
            pitch = smoothPitch * (1.0F + SLIP_PITCH_DEPTH * (float) wobble(SLIP_PITCH_PERIODS));
        } else {
            volume = smoothVolume;
            pitch = smoothPitch;
        }

        // 変速音はどれか 1 本が受け持てばよい。毎ティック回っている排気音に任せる
        if (role == Role.EXHAUST) {
            int gear = car.getRenderGear();
            if (gear != lastGear && gear != 0 && lastGear != 0) {
                car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                        KurumaSounds.CAR_SHIFT.get(), SoundSource.NEUTRAL, 0.5F, 1.0F, false);
            }
            lastGear = gear;
        }
    }

    private void updatePosition() {
        this.x = (float) car.getX();
        this.y = (float) car.getY();
        this.z = (float) car.getZ();
    }
}
