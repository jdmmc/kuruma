package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Platform;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RacePayload;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.race.McRaceServer;
import com.jdmmc.kurumamod.race.RaceCar;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Path;

/**
 * core の {@link RaceClient} の Minecraft 26.3 版。{@link #install} でクライアントの起動時に置く
 * （{@code ClientHooks} を読み込んだとき）。
 */
public final class McRaceClient implements RaceClient {

    private static boolean installed;

    private McRaceClient() {
    }

    /** 何度呼んでもよい。 */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        RaceClient.set(new McRaceClient());
        CarGhosts.install();
        RaceText.setResolver(text -> RaceTexts.of(text).getString());
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public UiFont font() {
        return McUiFont.INSTANCE;
    }

    @Override
    public int guiWidth() {
        return mc().getWindow().getGuiScaledWidth();
    }

    @Override
    public int guiHeight() {
        return mc().getWindow().getGuiScaledHeight();
    }

    @Override
    public double guiScale() {
        return mc().getWindow().getGuiScale();
    }

    @Override
    public boolean isPaused() {
        return mc().isPaused();
    }

    @Override
    public boolean hideGui() {
        return mc().gui.hud.isHidden();
    }

    @Override
    public boolean hasPlayer() {
        return mc().player != null;
    }

    @Override
    public boolean inWorld() {
        return mc().level != null;
    }

    @Override
    public String playerName() {
        return mc().player != null ? mc().player.getGameProfile().name() : "";
    }

    @Override
    public boolean screenOpen() {
        return mc().gui.screen() != null;
    }

    @Override
    public UiScreen raceScreen() {
        return mc().gui.screen() instanceof McUiScreen screen ? screen.model() : null;
    }

    @Override
    public boolean pauseScreenOpen() {
        return mc().gui.screen() instanceof PauseScreen;
    }

    @Override
    public void setScreen(UiScreen screen) {
        mc().gui.setScreen(screen == null ? null : new McUiScreen(screen));
    }

    @Override
    public void sendToServer(RacePayload packet) {
        KurumaNetwork.sendToServer(packet);
    }

    @Override
    public void overlayMessage(RaceText text) {
        if (mc().player != null) {
            mc().player.sendOverlayMessage(RaceTexts.of(text));
        }
    }

    @Override
    public RaceText openRaceKey() {
        return RaceText.ofNative(KurumaKeys.OPEN_RACE.getTranslatedKeyMessage());
    }

    @Override
    public RaceCar ridingCar() {
        CarEntity car = CarClientDriver.ridingCar(mc());
        return car == null ? null : McRaceServer.car(car);
    }

    @Override
    public RaceText carTypeName(String carId) {
        Identifier id = Identifier.tryParse(carId);
        return id == null ? RaceText.literal(carId) : RaceText.ofNative(CarTypes.get(id).displayName());
    }

    @Override
    public CarSpec tuningBase() {
        return CarTuning.base();
    }

    @Override
    public String currentDimension() {
        String dimension = CarGhosts.currentDimension(mc());
        return dimension == null ? "" : dimension;
    }

    @Override
    public String worldKey() {
        return GhostEngine.worldKey();
    }

    @Override
    public Path configDir() {
        return Platform.configDir();
    }

    @Override
    public void playSound(Sound sound, float pitch, float volume) {
        SoundEvent event = switch (sound) {
            case RACE_LIGHT -> KurumaSounds.RACE_LIGHT.get();
            case RACE_START -> KurumaSounds.RACE_START.get();
            case RACE_FINISH -> KurumaSounds.RACE_FINISH.get();
            case RACE_SPLIT -> KurumaSounds.RACE_SPLIT.get();
            case UI_CLICK -> SoundEvents.UI_BUTTON_CLICK.value();
        };
        mc().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }
}
