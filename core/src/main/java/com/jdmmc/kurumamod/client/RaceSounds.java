package com.jdmmc.kurumamod.client;

/**
 * レースの合図の音。クライアントだけで鳴らす。
 *
 * <p>車の音と違って<b>位置を持たせない</b>（版の {@link RaceClient#playSound} は {@code SimpleSoundInstance.forUI}）。合図は
 * 画面に出ているものと同じで、鳴っている場所というものが無いため。距離減衰が掛かると、
 * 走り出した瞬間にスタート信号が遠ざかることになる。</p>
 *
 * <p>鳴らすのは<b>状態が変わった瞬間だけ</b>。カウントダウンのパケットは毎ティック届くので、
 * 受け取るたびに鳴らすと 1 秒に 20 回鳴る。</p>
 */
public final class RaceSounds {

    /** 点灯音。エンジン音に埋もれず、かといって驚かない大きさ。 */
    private static final float LIGHT_VOLUME = 0.7F;
    /** 号砲。ここだけは聞き逃されては困るので大きく。 */
    private static final float START_VOLUME = 1.0F;
    /** 通過音。走っている最中に何度も鳴るので控えめに。 */
    private static final float SPLIT_VOLUME = 0.45F;

    /**
     * 通過音をベストとの差で振る幅。
     *
     * <p>速ければ高く、遅ければ低く鳴る。<b>画面を見なくても分かる</b>のがこの音の値打ちで、
     * 数字はコーナーの入り口では読めない。</p>
     */
    private static final float SPLIT_PITCH_STEP = 0.09F;

    private RaceSounds() {
    }

    /** スタート信号が 1 つ点いた。 */
    public static void light() {
        play(RaceClient.Sound.RACE_LIGHT, 1.0F, LIGHT_VOLUME);
    }

    /** 消灯＝スタート。 */
    public static void start() {
        play(RaceClient.Sound.RACE_START, 1.0F, START_VOLUME);
    }

    /** ゴール。ここだけは他と紛れないよう別の音形にしてある。 */
    public static void finish() {
        play(RaceClient.Sound.RACE_FINISH, 1.0F, START_VOLUME);
    }

    /** チェックポイント通過。比べる相手が無ければ振らない。 */
    public static void split(long delta, boolean hasDelta) {
        float pitch = 1.0F;
        if (hasDelta) {
            pitch += delta < 0L ? SPLIT_PITCH_STEP : -SPLIT_PITCH_STEP;
        }
        play(RaceClient.Sound.RACE_SPLIT, pitch, SPLIT_VOLUME);
    }

    /** エントリーした（主催者には、誰かが入った）。区間の音を高く鳴らす。 */
    public static void entered() {
        play(RaceClient.Sound.RACE_SPLIT, 1.5F, SPLIT_VOLUME);
    }

    /** エントリーから抜けた・外された（主催者には、誰かが抜けた）。区間の音を低く鳴らす。 */
    public static void left() {
        play(RaceClient.Sound.RACE_SPLIT, 0.75F, SPLIT_VOLUME);
    }

    private static void play(RaceClient.Sound sound, float pitch, float volume) {
        RaceClient.get().playSound(sound, pitch, volume);
    }
}
