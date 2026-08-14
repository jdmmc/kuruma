package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * ぶつかったときの手応え。音を鳴らし、画面を揺らす。
 *
 * <p><b>揺らせるのは角度だけ。</b>{@code Camera#setPosition} が protected なのでカメラの位置は
 * 動かせないが、{@code ViewportEvent.ComputeCameraAngles} でヨー・ピッチ・ロールには触れる。
 * 首を振られたように見えれば衝撃は十分伝わる。</p>
 *
 * <p>揺れは<b>減衰する振動</b>として作る。単なるランダムだとノイズにしか見えず、
 * 減衰しないと揺れ続ける。3 軸で周期をずらすと機械的な往復に見えない。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarImpact {

    /** これ以下の速度差では鳴らさない。縁石を擦った程度で毎回鳴ると安っぽい。 */
    public static final double MIN_IMPACT_SPEED = 2.5;
    /** 音量とピッチ、揺れが最大になる速度差 [m/s]。 */
    private static final double FULL_IMPACT_SPEED = 22.0;

    /** 最大の衝撃で振れる角度 [度]。大きすぎると何が起きたか分からなくなる。 */
    private static final float MAX_SHAKE_DEGREES = 6.0F;
    /** 揺れが収まるまでのティック数。 */
    private static final int SHAKE_TICKS = 12;

    /** 残っている揺れの強さ 0..1。 */
    private static float shake;
    /** 揺れ始めてからのティック数。位相をここから作る。 */
    private static int shakeAge;

    private CarImpact() {
    }

    /** 衝撃の強さ 0..1。 */
    private static float strength(double impactSpeed) {
        return (float) Mth.clamp(
                (impactSpeed - MIN_IMPACT_SPEED) / (FULL_IMPACT_SPEED - MIN_IMPACT_SPEED), 0.0, 1.0);
    }

    /**
     * 衝突を受け取る。音は必ず鳴らし、揺れはその車に乗っているときだけ。
     *
     * <p>離れて見ている人まで揺らすと、自分がぶつかったように錯覚する。</p>
     */
    public static void onImpact(CarEntity car, double impactSpeed) {
        if (impactSpeed < MIN_IMPACT_SPEED) {
            return;
        }
        float power = strength(impactSpeed);
        Minecraft minecraft = Minecraft.getInstance();
        // 強く当たるほど低く鳴る。軽い接触は高く短く聞こえる
        float pitch = 1.25F - 0.45F * power;
        car.level().playLocalSound(car.getX(), car.getY(), car.getZ(),
                KurumaSounds.CAR_CRASH.get(), SoundSource.NEUTRAL,
                0.35F + 0.65F * power, pitch, false);

        if (minecraft.player != null && minecraft.player.getVehicle() == car) {
            shake = Math.max(shake, power);
            shakeAge = 0;
        }
    }

    /** 揺れを減衰させる。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || shake <= 0.0F) {
            return;
        }
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        shakeAge++;
        if (shakeAge >= SHAKE_TICKS) {
            shake = 0.0F;
        }
    }

    /**
     * 視線を振る。
     *
     * <p>プレイヤーの向きそのものは書き換えない（書き換えると
     * {@link CarCamera} が「自分でマウスを動かした」と誤解して追従を止める）。
     * ここで返す角度は<b>描画にだけ効く</b>。</p>
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (shake <= 0.0F) {
            return;
        }
        double time = (shakeAge + event.getPartialTick()) / SHAKE_TICKS;
        if (time >= 1.0) {
            return;
        }
        // 残り時間で減衰する振動。3 軸で周期をずらして機械的な往復に見えないようにする
        float decay = (float) ((1.0 - time) * (1.0 - time));
        float amount = shake * decay * MAX_SHAKE_DEGREES;
        double phase = time * Math.PI * 2.0;
        event.setRoll(event.getRoll() + amount * (float) Math.sin(phase * 3.0));
        event.setPitch(event.getPitch() + amount * 0.6F * (float) Math.sin(phase * 4.3 + 1.1));
        event.setYaw(event.getYaw() + amount * 0.4F * (float) Math.sin(phase * 2.7 + 2.3));
    }
}
