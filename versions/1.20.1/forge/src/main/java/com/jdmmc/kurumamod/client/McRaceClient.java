package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.McIds;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Path;
import com.jdmmc.kurumamod.client.input.ControllerInput;
import com.jdmmc.kurumamod.client.race.GhostEngine;
import com.jdmmc.kurumamod.client.model.MeshShape;
import com.jdmmc.kurumamod.client.race.RaceClient;

/**
 * core の {@link RaceClient} の Minecraft 1.20.1 版。{@link #install} でクライアントの起動時に置く
 * （{@code RaceClientEvents} を読み込んだとき）。
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
        // 車の見た目の定義（core）がリソースパックとメッシュを引く口
        Assets.set(new McAssets());
        MeshShape.set(ObjModel::get);
        // 車まわりの画面（core）が設定と版の状態を引く口
        ClientOptions.set(new ConfigClientOptions());
        ControllerInput.setDevice(new ControllerDevice(), net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
        CarClient.set(new McCarClient());
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
        return mc().options.hideGui;
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
        return mc().player != null ? mc().player.getGameProfile().getName() : "";
    }

    @Override
    public boolean screenOpen() {
        return mc().screen != null;
    }

    @Override
    public UiScreen raceScreen() {
        return mc().screen instanceof McUiScreen screen ? screen.model() : null;
    }

    @Override
    public boolean pauseScreenOpen() {
        return mc().screen instanceof PauseScreen;
    }

    @Override
    public void setScreen(UiScreen screen) {
        mc().setScreen(screen == null ? null : new McUiScreen(screen));
    }

    @Override
    public void sendToServer(RacePayload packet) {
        KurumaNetwork.CHANNEL.sendToServer(packet);
    }

    @Override
    public void overlayMessage(RaceText text) {
        if (mc().player != null) {
            mc().player.displayClientMessage(RaceTexts.of(text), true);
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
        ResourceLocation id = ResourceLocation.tryParse(carId);
        return id == null ? RaceText.literal(carId) : CarTypes.get(McIds.of(id)).displayName();
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
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
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
