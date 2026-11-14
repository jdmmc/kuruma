package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import com.jdmmc.kurumamod.client.input.CarDriver;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 運転の入力（core の {@link CarDriver}）に、Minecraft 1.20.1 のキーとプレイヤーを渡す。
 *
 * <p>判断は {@link CarDriver} にだけある。ここはキーの読み方と、車のエンティティを渡すだけ。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarClientDriver {

    private CarClientDriver() {
    }

    /** クライアントの起動時に 1 回。 */
    public static void install() {
        CarDriver.install(new Host());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        CarDriver.tick();
    }

    /** いま運転している車。運転していなければ null。 */
    @Nullable
    public static CarEntity driving() {
        return (CarEntity) CarDriver.driving();
    }

    /** 乗っている車（運転席でも助手席でも）。乗っていなければ null。 */
    public static CarEntity ridingCar(Minecraft minecraft) {
        return minecraft.player != null && minecraft.player.getVehicle() instanceof CarEntity car ? car : null;
    }

    /** 画面を開いたまま運転するときに車へ渡すキーか。画面側がこれらを握りつぶすのに使う。 */
    public static boolean isDrivingKey(int keyCode, int scanCode) {
        for (CarDriver.Held key : CarDriver.Held.values()) {
            if (mapping(key).matches(keyCode, scanCode)) {
                return true;
            }
        }
        return false;
    }

    /** 割り当てられたキー（またはマウスボタン）がいま物理的に押されているか。 */
    private static boolean isKeyDown(Minecraft minecraft, KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        long window = minecraft.getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(window, key.getValue());
    }

    private static KeyMapping mapping(CarDriver.Press key) {
        return switch (key) {
            case OPEN_MENU -> KurumaKeys.OPEN_MENU;
            case OPEN_RACE -> KurumaKeys.OPEN_RACE;
            case OPEN_HEADLIGHT_SETTINGS -> KurumaKeys.OPEN_HEADLIGHT_SETTINGS;
            case OPEN_SEAT_DEBUG -> KurumaKeys.OPEN_SEAT_DEBUG;
            case OPEN_SOUND_DEBUG -> KurumaKeys.OPEN_SOUND_DEBUG;
            case OPEN_TUNING -> KurumaKeys.OPEN_TUNING;
            case OPEN_CONTROLLER -> KurumaKeys.OPEN_CONTROLLER;
            case TOGGLE_CAMERA -> KurumaKeys.TOGGLE_CAMERA;
            case TOGGLE_HEADLIGHTS -> KurumaKeys.TOGGLE_HEADLIGHTS;
            case CAMERA_CLOSER -> KurumaKeys.CAMERA_CLOSER;
            case CAMERA_FARTHER -> KurumaKeys.CAMERA_FARTHER;
            case SHIFT_UP -> KurumaKeys.SHIFT_UP;
            case SHIFT_DOWN -> KurumaKeys.SHIFT_DOWN;
        };
    }

    private static KeyMapping mapping(CarDriver.Held key) {
        var options = Minecraft.getInstance().options;
        return switch (key) {
            case UP -> options.keyUp;
            case DOWN -> options.keyDown;
            case LEFT -> options.keyLeft;
            case RIGHT -> options.keyRight;
            case JUMP -> options.keyJump;
            case SHIFT_UP -> KurumaKeys.SHIFT_UP;
            case SHIFT_DOWN -> KurumaKeys.SHIFT_DOWN;
        };
    }

    private static final class Host implements CarDriver.Host {
        @Override
        public int clicks(CarDriver.Press key) {
            KeyMapping mapping = mapping(key);
            int count = 0;
            while (mapping.consumeClick()) {
                count++;
            }
            return count;
        }

        @Override
        public boolean held(CarDriver.Held key) {
            return isKeyDown(Minecraft.getInstance(), mapping(key));
        }

        @Override
        public CarDriver.Movement movement() {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return null;
            }
            var in = player.input;
            return new CarDriver.Movement(in.up, in.down, in.left, in.right, in.jumping);
        }

        @Override
        public CarDriver.Car riding() {
            return ridingCar(Minecraft.getInstance());
        }

        @Override
        public boolean drives(CarDriver.Car car) {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.player != null && car instanceof CarEntity entity
                    && entity.getControllingPassenger() == minecraft.player;
        }

        @Override
        public void cycleCamera() {
            CarCamera.cycleMode();
        }

        @Override
        public void stepCameraDistance(int steps) {
            CarChaseCamera.stepDistance(steps);
        }
    }
}
