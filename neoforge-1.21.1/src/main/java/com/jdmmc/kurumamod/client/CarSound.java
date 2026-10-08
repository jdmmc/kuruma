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
 * <p><b>ピッチには 0.5〜2.0 の壁がある</b>（{@code SoundEngine#calculatePitch} が clamp する）。
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
        /** タイヤの悲鳴の伸ばし（キィー）。滑り率とスリップ角。<b>舗装路の音。</b> */
        SLIP,
        /** タイヤの悲鳴の刻み（キキキキ）。{@link #SLIP} と同じ量で鳴り、<b>滑り始めだけ</b>こちらが受け持つ。 */
        SLIP_CHATTER,
        /** 砂利を弾く音。砂利と土の上で、スキール音の代わりに鳴る。 */
        GRAVEL,
        /** 砂を掻く音。砂と雪の上で、スキール音の代わりに鳴る。 */
        SAND,
        /**
         * ターボの過給音（ヒュイーン）。ブーストで音程と音量が上がる。ブーストそのものと
         * ブローオフ（プシュッ）は {@link CarTurbo}。ターボの部品が付いていなければ鳴らない。
         */
        TURBO
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

    /**
     * タイヤが鳴きはじめる／いちばん大きく鳴る滑る速さ [m/s]（{@code CarEntity#getRenderSlideSpeed}）。
     *
     * <p>下は、限界で曲がっているだけ（30m/s・スリップ角 5 度で約 2.6m/s）や普通の発進では
     * ほとんど鳴らない位置。上は、60km/h・45 度のドリフトで届く位置。以前は滑り率と
     * スリップ角で決めていたので、後輪が空転した時点で最大に張り付き、角度にも速さにも
     * 応じなかった。</p>
     */
    private static final double SQUEAL_MIN_SLIDE = 2.5;
    private static final double SQUEAL_FULL_SLIDE = 17.0;

    /**
     * 未舗装の路面音が鳴りはじめる／いちばん大きくなる撫で速さ [m/s]。
     *
     * <p><b>土煙（{@code CarDust} の 4.5m/s）より低いところから鳴らす。</b>砂利道は
     * 歩く速さでもジャリジャリ鳴るのに対し、煙が立つのはもっと速くなってから。
     * 同じ量から決めているが、しきい値は別でよい。</p>
     */
    private static final double LOOSE_MIN_SCRUB = 1.5;
    private static final double LOOSE_FULL_SCRUB = 20.0;
    /** 未舗装の路面音のいちばん大きいときの音量。{@link #looseOf} の路面ごとの差が掛かる。 */
    private static final float LOOSE_VOLUME = 0.85F;
    private static final float LOOSE_PITCH_BASE = 0.85F;
    private static final float LOOSE_PITCH_SPAN = 0.40F;

    /** 音量の変化にひと呼吸置く。ティックごとに飛ぶと不自然に聞こえる。 */
    private static final float SMOOTHING = 0.35F;

    /**
     * 滑り始めてから刻み（キキキキ）が伸ばし（キィー）へ移りきるまで [tick]。0.6 秒。
     *
     * <p><b>「キキキキキィー！」は 1 本のループに焼き込めない。</b>ループなので、焼き込むと
     * 刻みが繰り返す。刻みと伸ばしを別の音源にして、ここで受け渡す。</p>
     */
    private static final int CHATTER_TICKS = 12;
    /** これを超えたら「滑っている」と数える（悲鳴の音量、0〜1）。 */
    private static final double SLIP_ONSET_LEVEL = 0.05;
    /**
     * 滑りが収まってからこれだけ続いたら、次の滑り始めでまた刻みを鳴らす [tick]。
     *
     * <p>すぐ数え直すと、しきい値のまわりで音量が細かく揺れるたびに刻みが出直して
     * キキキが止まらなくなる。</p>
     */
    private static final int SLIP_REARM_TICKS = 4;

    private final CarEntity car;
    private final Role role;
    /**
     * この車種の音の差し替え。<b>鳴らし始めたときのもので固定する。</b>音源は作るときにしか
     * 選べないので、途中で読み直しても鳴っているループは変わらない（F3+T は音響エンジンごと
     * 読み直して {@link CarSounds} が鳴らし直すので、そこで新しい定義が効く）。
     */
    private final CarSoundSet sounds;

    /** 平滑化した音量とピッチ。 */
    private float smoothVolume;
    private float smoothPitch = 1.0F;

    /**
     * 滑り続けているティック数と、滑りが収まっているティック数（悲鳴の 2 本だけが使う）。
     *
     * <p>伸ばしと刻みは別の {@code CarSound} だが、同じ車の同じ値から同じ手順で数えるので、
     * 状態を共有しなくても受け渡しの位置は揃う。</p>
     */
    private int slipTicks;
    private int quietTicks;

    /** 前ティックの段。変わった瞬間に変速音を鳴らす。 */
    private int lastGear;

    public CarSound(CarEntity car, Role role) {
        this(car, role, soundsOf(car));
    }

    /**
     * その車で鳴らす音の組。車種の差し替えに、付けているマフラーの音源を重ねたもの。
     *
     * <p>{@link CarSounds} も同じものを見て、マフラーの音源が変わったら排気音を鳴らし直す。</p>
     */
    static CarSoundSet soundsOf(CarEntity car) {
        return CarModel.get(car.getCarId()).sounds().withExhaust(CarPartModel.exhaustOf(car.getEffectiveFitment()));
    }

    /** 排気音か（マフラーで音程・音量・音源が変わる）。 */
    static boolean isExhaust(Role role) {
        return role == Role.EXHAUST || role == Role.EXHAUST_HIGH;
    }

    private CarSound(CarEntity car, Role role, CarSoundSet sounds) {
        super(soundOf(sounds, role), SoundSource.NEUTRAL, car.level().random);
        this.car = car;
        this.role = role;
        this.sounds = sounds;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.pitch = 1.0F;
        this.lastGear = car.getRenderGear();
        updatePosition();
    }

    /** その路面で鳴る未舗装の音と、その大きさ。 */
    private record Loose(Role role, float gain) {
    }

    /**
     * その路面で鳴る「未舗装の音」。舗装路と氷では鳴らない（null）。
     *
     * <p><b>粒の大きさで音源を分ける。</b>砂利と土は石が弾ける打撃音、砂と雪は連続した
     * 衣擦れで、音としてはまるで別物。<b>ここは {@code RoadSurface} には置かない</b>
     * ——どの音源を当てるかは音の側の都合で、路面そのものの性質ではない（グリップや
     * 舞い上がりやすさと違って、これは物理にも見た目にも一切効かない）。</p>
     *
     * <p><b>大きさに {@code dustScale} を使ってはいけない。</b>あれは「どれだけ舞うか」
     * であって「どれだけうるさいか」ではない——<b>雪は 0.9 とよく舞うが、いちばん静かな
     * 路面</b>（音を吸う）。うるさい順は石が鉄板を叩く砂利が頭で、雪が最後。</p>
     *
     * <p>音源そのものの大きさは書き出しの段で揃えてある（{@code make_sounds.py} の
     * {@code LOOSE_LOUDNESS}）ので、<b>ここの数字は路面どうしの差だけを表す</b>。</p>
     *
     * <p>切り替えに時定数は掛けない。氷のブロックへ乗り移るのと同じで<b>路面が変わるのは
     * 実際に不連続な出来事</b>であり、音量の平滑化（{@link #SMOOTHING}）がひと呼吸ぶんの
     * クロスフェードを作ってくれる。</p>
     */
    /**
     * その路面でタイヤがどれだけ鳴くか。0 で鳴かない。
     *
     * <p><b>未舗装では 0 にすること。グリップを掛けるだけでは足りない。</b>土は 0.75、砂でも 0.55
     * あるので、グリップを掛けるだけだと土や砂の上でも 7 割・5 割の大きさで鳴いていた。
     * 掴む相手のほうが持っていかれる路面では、代わりに GRAVEL / SAND が鳴る。
     * 氷は掻き飛ばすものも無いので、グリップを掛けてほぼ無音にする。</p>
     */
    private static float bite(RoadSurface surface) {
        return looseOf(surface) != null ? 0.0F : (float) surface.gripScale();
    }

    private static Loose looseOf(RoadSurface surface) {
        return switch (surface) {
            case GRAVEL -> new Loose(Role.GRAVEL, 1.00F);
            case DIRT -> new Loose(Role.GRAVEL, 0.80F);
            case SAND -> new Loose(Role.SAND, 0.85F);
            case SNOW -> new Loose(Role.SAND, 0.55F);
            case PAVED, ICE -> null;
        };
    }

    /** 差し替えのキーは役割の名前の小文字（{@code EXHAUST_HIGH} → {@code exhaust_high}）。 */
    private static SoundEvent soundOf(CarSoundSet sounds, Role role) {
        return sounds.resolve(role.name().toLowerCase(java.util.Locale.ROOT), () -> defaultSoundOf(role));
    }

    private static SoundEvent defaultSoundOf(Role role) {
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

        // マフラーを付け替えて音源が変わったら、この音は止める。新しい音源で鳴らし直すのは CarSounds
        // （鳴っている音の中から別の音を始めると、音響エンジンが一覧を回している最中に書き換えることになる）
        if (isExhaust(role) && !soundsOf(car).equals(sounds)) {
            stop();
            return;
        }

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
                // 高回転側が 1 オクターブ上の音源でなければ（パックが排気音だけ差し替えた）、
                // 同じ音程で鳴らす。2.0 の壁で頭打ちになるが、別の車の音に化けるよりよい
                boolean halve = high && sounds.exhaustHighIsOctaveUp();
                // マフラーの音程。下げるのは効くが、上げるのは 2.0 の壁ですぐ頭打ちになる
                var fitment = car.getEffectiveFitment();
                double muffler = CarPartModel.exhaustPitch(fitment);
                targetPitch = (float) Mth.clamp((halve ? wanted / 2.0 : wanted) * muffler, 0.5, 2.0);
                // アイドルでも鳴っているが、踏むと前に出る。マフラーの音量を掛けるが 1 を超えない
                // （1 を超えると音の届く距離まで伸びる）
                CarPartModel.Exhaust exhaust = CarPartModel.exhaustOf(fitment);
                float gain = exhaust == null ? 1.0F : (float) exhaust.volume();
                float loudness = Math.min(1.0F, (0.30F + 0.50F * (float) car.getRenderThrottle()) * gain);
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
            case SLIP, SLIP_CHATTER -> {
                // 輪ごとに、接地面が滑っている速さから鳴きを出して、いちばん大きい輪で鳴らす。
                // 角度が深いほど、速いほど、回すほど滑る速さが上がって大きく鳴る。
                // ゴムが鳴くのは路面を掴んだまま滑るとき。掴む相手のほうが持っていかれる
                // 未舗装では鳴らず、代わりに GRAVEL / SAND が出る。氷も掴めないので鳴らない。
                // 路面は<b>その輪の足元</b>で見ること。車全体（いちばん滑りやすい輪）で見ると、
                // 前輪が土に乗った瞬間に、コンクリートで滑っている後輪の鳴きまで弱まる
                float squeal = 0.0F;
                double sliding = 0.0;
                for (Wheel wheel : Wheel.VALUES) {
                    double t = Mth.clamp((car.getRenderSlideSpeed(wheel) - SQUEAL_MIN_SLIDE)
                            / (SQUEAL_FULL_SLIDE - SQUEAL_MIN_SLIDE), 0.0, 1.0);
                    // 平方根で持ち上げる。線形だと中くらいのドリフトが小さく聞こえすぎる
                    float wheelSqueal = (float) Math.sqrt(t) * bite(car.getRenderSurface(wheel));
                    if (wheelSqueal > squeal) {
                        squeal = wheelSqueal;
                        sliding = t;
                    }
                }
                // 滑り始めは刻み（キキキキ）、続けば伸ばし（キィー）。等パワーで受け渡すので
                // 移っている途中でも大きさは変わらない
                if (squeal > SLIP_ONSET_LEVEL) {
                    slipTicks++;
                    quietTicks = 0;
                } else if (++quietTicks >= SLIP_REARM_TICKS) {
                    slipTicks = 0;
                }
                double chatter = Mth.clamp(1.0 - slipTicks / (double) CHATTER_TICKS, 0.0, 1.0);
                double share = role == Role.SLIP_CHATTER ? chatter : 1.0 - chatter;
                targetVolume = squeal * (float) Math.sqrt(share);
                // 激しく滑るほど少し高く
                targetPitch = 0.95F + 0.2F * (float) sliding;
            }
            case GRAVEL, SAND -> {
                // 輪ごとに、その輪の足元の路面と掻いている速さで鳴らし、いちばん大きい輪を取る。
                // 車全体で見ると、前輪が砂利に乗った瞬間に、コンクリートで空転している
                // 後輪の擦れ具合でジャリジャリ鳴ってしまう。
                // 掻いている速さは土煙とまったく同じ量から取る。片方だけ直すと
                // 見えている土煙と聞こえている音が食い違う
                float loudest = 0.0F;
                float scrubbing = 0.0F;
                for (Wheel wheel : Wheel.VALUES) {
                    Loose loose = looseOf(car.getRenderSurface(wheel));
                    if (loose == null || loose.role() != role) {
                        continue;
                    }
                    double scrub = car.getRenderScrubSpeed(wheel);
                    float t = (float) Mth.clamp(
                            (scrub - LOOSE_MIN_SCRUB) / (LOOSE_FULL_SCRUB - LOOSE_MIN_SCRUB),
                            0.0, 1.0);
                    float wheelVolume = LOOSE_VOLUME * t * loose.gain();
                    if (wheelVolume > loudest) {
                        loudest = wheelVolume;
                        scrubbing = t;
                    }
                }
                targetVolume = loudest;
                targetPitch = LOOSE_PITCH_BASE + LOOSE_PITCH_SPAN * scrubbing;
            }
            case TURBO -> {
                float[] out = turbo();
                targetVolume = out[0];
                targetPitch = out[1];
            }
            default -> {
                targetVolume = 0.0F;
                targetPitch = 1.0F;
            }
        }

        smoothVolume += (targetVolume - smoothVolume) * SMOOTHING;
        smoothPitch += (targetPitch - smoothPitch) * SMOOTHING;

        // スキール音にも揺らぎは掛けない。以前は音量を最大 38%・音程を 8% 揺らして
        // 繰り返しを隠そうとしていたが、音源（slip.ogg）の波打ちと重なって
        // 「ワンワン」と耳障りになっていた。繰り返しは音源の側で均して消してある
        // （tools/make_sounds.py の slip()）。しかも 20Hz の階段で振っていたので、
        // 揺らぎそのものも滑らかではなかった
        volume = smoothVolume;
        pitch = smoothPitch;

        // 変速音はどれか 1 本が受け持てばよい。毎ティック回っている排気音に任せる
        if (role == Role.EXHAUST) {
            int gear = car.getRenderGear();
            if (gear != lastGear && gear != 0 && lastGear != 0) {
                car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                        sounds.shift(), SoundSource.NEUTRAL, 0.5F, 1.0F, false);
            }
            lastGear = gear;
        }
    }

    /**
     * 過給音の {音量, 音程}。ブーストは {@link CarTurbo} が持つ（メーターも同じ値を読む）。
     * 翼の回転＝ブーストで音程が上がり、回転数も少し乗せる（排気が当たっているぶん）。
     */
    private float[] turbo() {
        if (!car.hasTurbo()) {
            return new float[]{0.0F, 1.0F};
        }
        CarSpec spec = car.getSpec();
        double span = Math.max(1.0, spec.redlineRpm() - spec.idleRpm());
        double rev = Mth.clamp((car.getRenderRpm() - spec.idleRpm()) / span, 0.0, 1.0);
        double boost = CarTurbo.boost(car);
        float volume = (float) (0.55 * boost);
        float tone = (float) Mth.clamp(0.55 + 1.0 * boost + 0.15 * rev, 0.5, 2.0);
        return new float[]{volume, tone};
    }

    private void updatePosition() {
        this.x = (float) car.getX();
        this.y = (float) car.getY();
        this.z = (float) car.getZ();
    }
}
