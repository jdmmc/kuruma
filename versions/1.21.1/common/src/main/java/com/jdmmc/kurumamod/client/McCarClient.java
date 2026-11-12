package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.plate.PlateLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import com.jdmmc.kurumamod.client.model.CarPartModel;
import com.jdmmc.kurumamod.client.sound.CarSoundSet;
import com.jdmmc.kurumamod.client.hud.HudCar;

/** core の {@link CarClient} の Minecraft 1.21.1 版。 */
final class McCarClient implements CarClient {

    @Override
    public boolean shaderPackInUse() {
        return HeadlightVolume.ShaderPacks.inUse();
    }

    @Override
    public boolean headlightSuppressedByShaderPack() {
        return HeadlightVolume.suppressedByShaderPack();
    }

    private static CarEntity ridingEntity() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getVehicle() instanceof CarEntity car ? car : null;
    }

    @Override
    public List<HelpLine> helpLines() {
        List<HelpLine> lines = new ArrayList<>();
        lines.add(new HelpLine(key(Minecraft.getInstance().options.keyUp) + "/" + key(Minecraft.getInstance().options.keyDown),
                "help.kurumamod.pedals"));
        lines.add(new HelpLine(key(Minecraft.getInstance().options.keyLeft) + "/" + key(Minecraft.getInstance().options.keyRight),
                "help.kurumamod.steer"));
        lines.add(new HelpLine(key(Minecraft.getInstance().options.keyJump), "help.kurumamod.handbrake"));
        lines.add(new HelpLine(key(KurumaKeys.SHIFT_UP) + "/" + key(KurumaKeys.SHIFT_DOWN),
                "help.kurumamod.shift"));
        lines.add(new HelpLine(key(Minecraft.getInstance().options.keyShift), "help.kurumamod.dismount"));
        lines.add(new HelpLine(key(KurumaKeys.OPEN_TUNING), "help.kurumamod.tuning"));
        lines.add(new HelpLine(key(KurumaKeys.OPEN_MENU), "help.kurumamod.menu"));
        lines.add(new HelpLine(key(KurumaKeys.OPEN_RACE), "help.kurumamod.race"));
        lines.add(new HelpLine(key(KurumaKeys.TOGGLE_HEADLIGHTS), "help.kurumamod.headlights"));
        lines.add(new HelpLine(key(KurumaKeys.OPEN_HEADLIGHT_SETTINGS), "help.kurumamod.headlight_settings"));
        lines.add(new HelpLine(key(KurumaKeys.TOGGLE_CAMERA), "help.kurumamod.camera"));
        lines.add(new HelpLine(key(KurumaKeys.CAMERA_CLOSER) + "/" + key(KurumaKeys.CAMERA_FARTHER),
                "help.kurumamod.camera_distance"));
        lines.add(new HelpLine(key(KurumaKeys.OPEN_CONTROLLER), "help.kurumamod.controller"));
        return lines;
    }

    /** キーの表示名。割り当てを変えたらそのまま追従する。 */
    private static String key(net.minecraft.client.KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    @Override
    public HudCar ridingHud() {
        return ridingEntity();
    }

    @Override
    public double turboBoost() {
        CarEntity car = ridingEntity();
        return car == null ? 0.0 : CarTurbo.boost(car, Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
    }

    @Override
    public com.jdmmc.kurumamod.config.CameraMode cameraMode() {
        return CarCamera.currentMode();
    }

    @Override
    public String cameraKeyName() {
        return key(KurumaKeys.TOGGLE_CAMERA);
    }

    @Override
    public CarId ridingCarId() {
        CarEntity car = ridingEntity();
        return car == null ? null : car.getCarId();
    }

    @Override
    public CarSoundSet ridingCarSounds() {
        CarEntity car = ridingEntity();
        return car == null ? null : CarSound.soundsOf(car);
    }

    @Override
    public boolean ridingCarHasTurbo() {
        CarEntity car = ridingEntity();
        return car != null && car.hasTurbo();
    }

    @Override
    public boolean ridingCarHasBlowoff() {
        CarEntity car = ridingEntity();
        return car != null && car.hasBlowoff();
    }

    @Override
    public void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    /** 車を core の {@link DrivenCar} として包む。 */
    static DrivenCar wrap(CarEntity car) {
        return car == null ? null : new DrivenCar() {
            @Override
            public int getId() {
                return car.getId();
            }

            @Override
            public CarId getCarId() {
                return car.getCarId();
            }

            @Override
            public CarSpec getSpec() {
                return car.getSpec();
            }

            @Override
            public PartFitment getFitment() {
                return car.getFitment();
            }

            @Override
            public PartFitment getEffectiveFitment() {
                return car.getEffectiveFitment();
            }

            @Override
            public void setFitment(PartFitment fitment) {
                car.setFitment(fitment);
            }

            @Override
            public boolean hasTurbo() {
                return car.hasTurbo();
            }

            @Override
            public boolean hasBlowoff() {
                return car.hasBlowoff();
            }

            @Override
            public boolean same(DrivenCar other) {
                return other != null && other.getId() == car.getId();
            }
        };
    }

    @Override
    public DrivenCar riding() {
        return wrap(CarClientDriver.ridingCar(Minecraft.getInstance()));
    }

    @Override
    public DrivenCar driving() {
        return wrap(CarClientDriver.driving());
    }

    @Override
    public boolean isDriver() {
        return CarClientDriver.isDriver(Minecraft.getInstance());
    }

    @Override
    public void requestPart(PartSlot slot, PartFitment.Setting setting) {
        CarClientDriver.requestPart(slot, setting);
    }

    @Override
    public void requestPaint(String paint) {
        CarClientDriver.requestPaint(paint);
    }

    @Override
    public void requestPlate(PlateLook plate) {
        CarClientDriver.requestPlate(plate);
    }

    @Override
    public void sendTuningToServer() {
        CarClientDriver.sendTuningToServer();
    }

    @Override
    public void viewPassengerCar(DrivenCar car) {
        CarEntity entity = CarClientDriver.ridingCar(Minecraft.getInstance());
        if (entity != null && car != null && entity.getId() == car.getId()) {
            CarClientDriver.viewPassengerCar(entity);
        }
    }

    @Override
    public void orbitCamera(double dragX, double dragY) {
        CarChaseCamera.orbit(dragX, dragY);
    }

    @Override
    public void zoomCamera(double delta) {
        CarChaseCamera.zoom(delta);
    }

    @Override
    public float[] partAnchor(PartSlot slot) {
        return PartAnchors.point(slot);
    }

    @Override
    public float[] paintAnchor() {
        return PartAnchors.paint();
    }

    @Override
    public float[] centerAnchor() {
        return PartAnchors.center();
    }

    @Override
    public double beadRadius(CarPartModel.Wheel look) {
        return CarObjRenderer.beadRadius(look, ObjModel.get(look.model()));
    }

    @Override
    public boolean shiftDown() {
        return net.minecraft.client.gui.screens.Screen.hasShiftDown();
    }

    @Override
    public boolean firstPerson() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.options.getCameraType().isFirstPerson();
    }

    @Override
    public void turnPlayer(double yaw, double pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.turn(yaw, pitch);
        }
    }

    @Override
    public double eyeAboveVehicle() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.getVehicle() == null) {
            return Double.NaN;
        }
        return minecraft.player.getEyeY() - minecraft.player.getVehicle().getY();
    }

    @Override
    public void returnTo(Object parent) {
        Minecraft.getInstance().setScreen(parent instanceof com.jdmmc.kurumamod.client.ui.UiScreen ui
                ? new McUiScreen(ui) : (Screen) parent);
    }
}
