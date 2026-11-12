package com.jdmmc.kurumamod.client.camera;

/**
 * ぶつかったときの手応えの数字。音の大きさと高さ、画面の揺れ。Minecraft に依存しない
 * （音を鳴らし、カメラに角度を足すのは版の {@code CarImpact}）。
 *
 * <p><b>揺らせるのは角度だけ。</b>{@code Camera#setPosition} が protected なのでカメラの位置は
 * 動かせないが、カメラの角度の段でヨー・ピッチ・ロールには触れる。
 * 首を振られたように見えれば衝撃は十分伝わる。</p>
 *
 * <p>揺れは<b>減衰する振動</b>として作る。単なるランダムだとノイズにしか見えず、
 * 減衰しないと揺れ続ける。3 軸で周期をずらすと機械的な往復に見えない。</p>
 *
 * <p>揺れの状態は static。揺れるのはこのクライアントの運転者の画面だけなので 1 つで足りる。</p>
 */
public final class ImpactShake {

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

    private ImpactShake() {
    }

    /** 衝撃の強さ 0..1。 */
    public static float strength(double impactSpeed) {
        double t = (impactSpeed - MIN_IMPACT_SPEED) / (FULL_IMPACT_SPEED - MIN_IMPACT_SPEED);
        return (float) Math.max(0.0, Math.min(1.0, t));
    }

    /** 衝突音の大きさ（設定の倍率を掛ける前）。 */
    public static float volume(float power) {
        return 0.35F + 0.65F * power;
    }

    /** 衝突音の高さ。強く当たるほど低く鳴る。軽い接触は高く短く聞こえる。 */
    public static float pitch(float power) {
        return 1.25F - 0.45F * power;
    }

    /**
     * 画面を揺らし始める。<b>その車に乗っているときだけ呼ぶこと</b>
     * （離れて見ている人まで揺らすと、自分がぶつかったように錯覚する）。
     */
    public static void shake(float power) {
        shake = Math.max(shake, power);
        shakeAge = 0;
    }

    /** 揺れを減衰させる。ゲームが止まっている間は呼ばない。 */
    public static void tick() {
        if (shake <= 0.0F) {
            return;
        }
        shakeAge++;
        if (shakeAge >= SHAKE_TICKS) {
            shake = 0.0F;
        }
    }

    /**
     * 視線に足す角度を {@code out} に入れる（ヨー・ピッチ・ロールの順、度）。
     *
     * <p>プレイヤーの向きそのものは書き換えないこと（書き換えると、追従カメラが
     * 「自分でマウスを動かした」と誤解して追従を止める）。足す角度は<b>描画にだけ効かせる</b>。</p>
     *
     * @return 揺れていれば true。false のとき {@code out} は触らない
     */
    public static boolean angles(double partialTick, float[] out) {
        if (shake <= 0.0F) {
            return false;
        }
        double time = (shakeAge + partialTick) / SHAKE_TICKS;
        if (time >= 1.0) {
            return false;
        }
        // 残り時間で減衰する振動。3 軸で周期をずらして機械的な往復に見えないようにする
        float decay = (float) ((1.0 - time) * (1.0 - time));
        float amount = shake * decay * MAX_SHAKE_DEGREES;
        double phase = time * Math.PI * 2.0;
        out[0] = amount * 0.4F * (float) Math.sin(phase * 2.7 + 2.3);
        out[1] = amount * 0.6F * (float) Math.sin(phase * 4.3 + 1.1);
        out[2] = amount * (float) Math.sin(phase * 3.0);
        return true;
    }
}
