package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.input.ControllerInput;
import com.jdmmc.kurumamod.client.input.ForceFeedback;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarState;
import com.jdmmc.kurumamod.physics.SteeringTorque;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLHaptic;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDL_HapticEffect;
import org.slf4j.Logger;

/**
 * ハンコンへ力を出す（core の {@link ForceFeedback.Output}）。SDL3 の haptic の一定の力（{@code SDL_HAPTIC_CONSTANT}）を、
 * ハンドルの軸（{@code SDL_HAPTIC_STEERING_AXIS}）へ出す。
 *
 * <p><b>力は {@link #EFFECT_MILLIS} で切れる。</b>毎フレーム出し直すので、ゲームが固まったり落ちたりして出し直しが
 * 止まれば、力はひとりでに消える（安全策。{@link ForceFeedback} の冒頭）。無限の長さで出すと、固まった瞬間の力が
 * 残り続ける。</p>
 *
 * <p>力を出しているあいだは機器のオートセンター（ドライバーのバネ）を切る。ゲームの力と打ち消し合うため。
 * 低速のセンタリングは core の側で足している。</p>
 */
public final class ForceFeedbackDevice implements ForceFeedback.Output {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 出した力が切れるまでの時間 [ms]。出し直しが止まったら、長くてもこれだけで力が抜ける。 */
    private static final int EFFECT_MILLIS = 150;
    /** 出し直しの間隔の下限 [ns]（約 125 回/秒）。ドライバーに毎フレーム（数百回/秒）送らないため。 */
    private static final long MIN_INTERVAL_NANOS = 8_000_000L;
    /** 値が変わらなくても出し直す間隔 [ns]。{@link #EFFECT_MILLIS} より十分短くすること。 */
    private static final long REFRESH_NANOS = 50_000_000L;

    private final ControllerDevice devices;

    /** SDL の haptic のサブシステム。0 = まだ、1 = 使える、-1 = 立ち上げに失敗した。 */
    private int sdlState;
    /** 開いている機器の {@link ControllerInput.Pad#nativeId}。開いていなければ {@link Integer#MIN_VALUE}。 */
    private int openedFor = Integer.MIN_VALUE;
    private long haptic;
    private int effect = -1;
    /** 効果の中身。<b>1 度だけ作って使い回す</b>（GC 管理のバッファなので {@code free()} しない）。 */
    private SDL_HapticEffect effectStruct;
    private short lastLevel;
    private long lastSendNanos;

    public ForceFeedbackDevice(ControllerDevice devices) {
        this.devices = devices;
    }

    // ------------------------------------------------------------------
    // ゲームから呼ぶところ
    // ------------------------------------------------------------------

    /** 毎ティックの終わり（車の物理が進んだあと）。運転している車の力を core へ渡す。 */
    public static void onClientTick() {
        if (!ForceFeedback.available()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CarEntity car = CarClientDriver.ridingCar(minecraft);
        if (car == null || car.getControllingPassenger() != minecraft.player) {
            ForceFeedback.tick(Double.NaN, 0.0);
            return;
        }
        CarState state = car.carCore().state();
        ForceFeedback.tick(SteeringTorque.of(car.carCore().spec(), state),
                Math.hypot(state.forwardSpeed, state.lateralSpeed));
    }

    /** 毎フレームの頭。画面を開いている・ウィンドウが手前にないときは止める。 */
    public static void onFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        ForceFeedback.frame(minecraft.gui.screen() == null && minecraft.isWindowActive() && !minecraft.isPaused());
    }

    // ------------------------------------------------------------------
    // ForceFeedback.Output
    // ------------------------------------------------------------------

    @Override
    public void send(ControllerInput.Pad pad, double level) {
        if (!initSdl()) {
            return;
        }
        if (pad.nativeId() != openedFor) {
            close();
            open(pad.nativeId());
        }
        if (haptic == 0L || effect < 0) {
            return;
        }
        short value = (short) Math.round(Math.max(-1.0, Math.min(1.0, level)) * 32767.0);
        long now = System.nanoTime();
        long since = now - lastSendNanos;
        if (value == lastLevel ? since < REFRESH_NANOS : since < MIN_INTERVAL_NANOS) {
            return;
        }
        effectStruct.constant().level(value);
        if (!SDLHaptic.SDL_UpdateHapticEffect(haptic, effect, effectStruct)
                || !SDLHaptic.SDL_RunHapticEffect(haptic, effect, 1)) {
            LOGGER.warn("フォースフィードバックを出せませんでした（止めます）: {}", SDLError.SDL_GetError());
            close();
            return;
        }
        lastLevel = value;
        lastSendNanos = now;
    }

    @Override
    public void stop() {
        if (haptic != 0L && effect >= 0) {
            SDLHaptic.SDL_StopHapticEffect(haptic, effect);
        }
        lastLevel = 0;
        lastSendNanos = 0L;
    }

    // ------------------------------------------------------------------
    // SDL
    // ------------------------------------------------------------------

    /** haptic のサブシステムを立ち上げる。メインスレッド（描画のスレッド）から呼ぶこと。失敗したら二度と試さない。 */
    private boolean initSdl() {
        if (sdlState == 0) {
            if ((SDLInit.SDL_WasInit(SDLInit.SDL_INIT_HAPTIC) & SDLInit.SDL_INIT_HAPTIC) != 0
                    || SDLInit.SDL_InitSubSystem(SDLInit.SDL_INIT_HAPTIC)) {
                sdlState = 1;
            } else {
                sdlState = -1;
                LOGGER.warn("SDL の haptic を使えませんでした（フォースフィードバック無しで続けます）: {}",
                        SDLError.SDL_GetError());
            }
        }
        return sdlState > 0;
    }

    /** 機器を開いて、一定の力の効果を作る。出せない機器なら開かずに覚えておく（毎フレーム試さない）。 */
    private void open(int id) {
        openedFor = id;
        long joystick = devices.joystickHandle(id);
        if (joystick == 0L) {
            // まだ開いていない（つないだ直後）。次のフレームでもう一度
            openedFor = Integer.MIN_VALUE;
            return;
        }
        if (!SDLHaptic.SDL_IsJoystickHaptic(joystick)) {
            LOGGER.info("この機器はフォースフィードバックに対応していません");
            return;
        }
        haptic = SDLHaptic.SDL_OpenHapticFromJoystick(joystick);
        if (haptic == 0L) {
            LOGGER.warn("フォースフィードバックの機器を開けませんでした: {}", SDLError.SDL_GetError());
            return;
        }
        int features = SDLHaptic.SDL_GetHapticFeatures(haptic);
        if ((features & SDLHaptic.SDL_HAPTIC_CONSTANT) == 0) {
            LOGGER.info("この機器は一定の力（constant）を出せません");
            close();
            openedFor = id;
            return;
        }
        if ((features & SDLHaptic.SDL_HAPTIC_AUTOCENTER) != 0) {
            SDLHaptic.SDL_SetHapticAutocenter(haptic, 0);
        }
        if ((features & SDLHaptic.SDL_HAPTIC_GAIN) != 0) {
            // 強さは core の側で掛けるので、機器の倍率は素通しにしておく
            SDLHaptic.SDL_SetHapticGain(haptic, 100);
        }
        if (effectStruct == null) {
            effectStruct = SDL_HapticEffect.create();
        }
        effectStruct.type((short) SDLHaptic.SDL_HAPTIC_CONSTANT);
        effectStruct.constant()
                .type((short) SDLHaptic.SDL_HAPTIC_CONSTANT)
                .length(EFFECT_MILLIS)
                .delay((short) 0)
                .level((short) 0)
                .attack_length((short) 0)
                .fade_length((short) 0);
        effectStruct.constant().direction().type((byte) SDLHaptic.SDL_HAPTIC_STEERING_AXIS).dir(0, 1);
        effect = SDLHaptic.SDL_CreateHapticEffect(haptic, effectStruct);
        if (effect < 0) {
            LOGGER.warn("フォースフィードバックの効果を作れませんでした: {}", SDLError.SDL_GetError());
            close();
            openedFor = id;
            return;
        }
        LOGGER.info("フォースフィードバックを使います: {}", SDLHaptic.SDL_GetHapticName(haptic));
    }

    private void close() {
        if (haptic != 0L) {
            if (effect >= 0) {
                SDLHaptic.SDL_StopHapticEffect(haptic, effect);
                SDLHaptic.SDL_DestroyHapticEffect(haptic, effect);
            }
            SDLHaptic.SDL_CloseHaptic(haptic);
        }
        haptic = 0L;
        effect = -1;
        openedFor = Integer.MIN_VALUE;
        lastLevel = 0;
        lastSendNanos = 0L;
    }
}
