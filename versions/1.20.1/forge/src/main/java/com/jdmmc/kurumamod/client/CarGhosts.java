package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.part.PartFitments;
import com.jdmmc.kurumamod.tuning.CarSpecNbt;
import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CarStatePacket;
import com.jdmmc.kurumamod.network.GhostRidePacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import com.jdmmc.kurumamod.client.race.GhostClient;
import com.jdmmc.kurumamod.client.race.GhostEngine;
import com.jdmmc.kurumamod.client.race.GhostFrame;

/**
 * タイムアタックのゴーストの入口。<b>中身は core の {@link GhostEngine}</b>（周の切り出し・保存の場所・読み書き）。
 *
 * <p>Forge のイベントは {@link RaceClientEvents} が受けてここを呼ぶ。</p>
 *
 * <p>ここは Minecraft に触るところだけ: 自分の画面にだけいる車（{@link CarEntity#becomeGhost}）を出して
 * 1 コマずつ流し込む、状態（{@link CarStatePacket}）とコマ（{@link GhostFrame}）の詰め替え、NBT でのファイルの読み書き、
 * 助手席へ送る {@link GhostRidePacket}。</p>
 */
public final class CarGhosts implements GhostClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    static {
        GhostClient.set(new CarGhosts());
    }

    private CarGhosts() {
    }

    /** core へ口を置く（クラスを読み込むだけ。{@link McRaceClient#install} が呼ぶ）。 */
    static void install() {
    }

    /** 記録の見た目（車種・諸元・装着）。 */
    private record Look(CarId carId, CarSpec spec, PartFitment parts) {
    }

    // ------------------------------------------------------------------
    // 入口（呼ぶ側は前と同じ名前）
    // ------------------------------------------------------------------

    public static void onClientTickPre() {
        GhostEngine.onClientTickPre();
    }

    public static void onClientTickPost() {
        GhostEngine.onClientTickPost();
    }

    public static void onLoggingOut() {
        GhostEngine.onLoggingOut();
    }

    public static void onRaceEvent(RaceEventPacket packet) {
        GhostEngine.onRaceEvent(packet);
    }

    /** 助手席の側。運転者の画面で走っているゴーストの 1 コマが届いた（{@code CarClientPackets#applyGhostRide}）。 */
    public static void applyRide(GhostRidePacket packet) {
        GhostEngine.RideKind kind = switch (packet.kind()) {
            case GhostRidePacket.LOOK -> GhostEngine.RideKind.LOOK;
            case GhostRidePacket.FRAME -> GhostEngine.RideKind.FRAME;
            case GhostRidePacket.STOP -> GhostEngine.RideKind.STOP;
            default -> null;
        };
        if (kind == null) {
            return;
        }
        GhostFrame frame = kind == GhostEngine.RideKind.FRAME
                ? frame(packet.x(), packet.y(), packet.z(), packet.state()) : null;
        GhostEngine.applyRide(kind, packet.carEntityId(), packet.look(), frame);
    }

    /** いまいる次元の名前。ワールドに入っていなければ null。 */
    static String currentDimension(Minecraft minecraft) {
        return minecraft.level != null ? minecraft.level.dimension().location().toString() : null;
    }

    // ------------------------------------------------------------------
    // 詰め替え
    // ------------------------------------------------------------------

    static GhostFrame frame(double x, double y, double z, CarStatePacket s) {
        return new GhostFrame(x, y, z, s.steerAngle(), s.speed(), s.yaw(), s.pitch(), s.roll(),
                s.suspension(), s.wheelSpeed(), s.friction(), s.rpm(), s.gear(), s.throttle(), s.slipAngle(),
                s.surface(), s.lights(), s.boost());
    }

    static CarStatePacket state(int entityId, GhostFrame f) {
        return new CarStatePacket(entityId, f.steerAngle(), f.speed(), f.yaw(), f.pitch(), f.roll(),
                f.suspension(), f.wheelSpeed(), f.friction(), f.rpm(), f.gear(), f.throttle(), f.slipAngle(),
                f.surface(), f.lights(), f.boost());
    }

    private static CompoundTag lookTag(Look look) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Car", look.carId().toString());
        tag.put("Spec", CarSpecNbt.save(look.spec()));
        tag.put("Parts", PartFitments.save(look.parts()));
        return tag;
    }

    // ------------------------------------------------------------------
    // GhostClient
    // ------------------------------------------------------------------

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public boolean isPaused() {
        return mc().isPaused();
    }

    @Override
    public boolean inWorld() {
        return mc().level != null;
    }

    @Override
    public boolean ghostsEnabled() {
        return ClientConfig.timeAttackGhost;
    }

    @Override
    public Car drivenCar() {
        Minecraft minecraft = mc();
        if (minecraft.player == null) {
            return null;
        }
        Entity vehicle = minecraft.player.getVehicle();
        if (!(vehicle instanceof CarEntity car) || car.getControllingPassenger() != minecraft.player) {
            return null;
        }
        return new Car() {
            @Override
            public Object identity() {
                return car;
            }

            @Override
            public int entityId() {
                return car.getId();
            }

            @Override
            public int riders() {
                return car.getPassengers().size() - 1;
            }

            @Override
            public GhostFrame lastSentFrame() {
                CarStatePacket state = car.lastSentState();
                return state == null ? null : frame(car.getX(), car.getY(), car.getZ(), state);
            }

            @Override
            public Object look() {
                return new Look(car.getCarId(), car.getSpec(), car.getFitment());
            }

            @Override
            public String dimension() {
                return car.level().dimension().location().toString();
            }
        };
    }

    @Override
    public int vehicleId() {
        Minecraft minecraft = mc();
        return minecraft.player != null && minecraft.player.getVehicle() != null
                ? minecraft.player.getVehicle().getId() : -1;
    }

    /** ゴーストの車。消されたか、別のワールドへ移ったら終わり。 */
    private record GhostCar(CarEntity entity, ClientLevel level) implements Ghost {
        @Override
        public boolean alive() {
            return !entity.isRemoved() && entity.level() == level && mc().level == level;
        }

        @Override
        public void play(GhostFrame frame) {
            entity.playGhostFrame(frame.x(), frame.y(), frame.z(), state(entity.getId(), frame));
        }

        @Override
        public void discard() {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
    }

    @Override
    public Ghost spawn(Object look, int entityId, GhostFrame first) {
        Look car = (Look) look;
        ClientLevel level = mc().level;
        CarEntity entity = new CarEntity(Kurumamod.CAR.get(), level);
        entity.becomeGhost(car.carId(), car.spec(), car.parts());
        entity.setId(entityId);
        entity.moveTo(first.x(), first.y(), first.z(), first.yaw(), 0.0F);
        entity.playGhostFrame(first.x(), first.y(), first.z(), state(entityId, first));
        level.putNonPlayerEntity(entityId, entity);
        return new GhostCar(entity, level);
    }

    @Override
    public Object rideLook(Object look) {
        return lookTag((Look) look);
    }

    @Override
    public Ghost spawnFromRideLook(Object rideLook, int entityId, GhostFrame first) {
        CompoundTag tag = (CompoundTag) rideLook;
        CarId carId = CarId.parse(tag.getString("Car"));
        if (carId == null) {
            return null;
        }
        ClientLevel level = mc().level;
        CarEntity entity = new CarEntity(Kurumamod.CAR.get(), level);
        try {
            entity.becomeGhost(carId, CarSpecNbt.load(tag.getCompound("Spec")),
                    PartFitments.load(tag.getCompound("Parts")));
        } catch (RuntimeException e) {
            LOGGER.warn("同乗のゴーストの見た目を読めないので出しません", e);
            return null;
        }
        entity.setId(entityId);
        entity.moveTo(first.x(), first.y(), first.z(), first.yaw(), 0.0F);
        level.putNonPlayerEntity(entityId, entity);
        return new GhostCar(entity, level);
    }

    @Override
    public void sendRideLook(int carEntityId, Object rideLook) {
        KurumaNetwork.CHANNEL.sendToServer(GhostRidePacket.look(carEntityId, (CompoundTag) rideLook));
    }

    @Override
    public void sendRideFrame(int carEntityId, GhostFrame frame) {
        KurumaNetwork.CHANNEL.sendToServer(GhostRidePacket.frame(carEntityId, frame.x(), frame.y(), frame.z(),
                state(GhostEngine.GHOST_ENTITY_ID, frame)));
    }

    @Override
    public void sendRideStop(int carEntityId) {
        KurumaNetwork.CHANNEL.sendToServer(GhostRidePacket.stop(carEntityId));
    }

    @Override
    public boolean connected() {
        return mc().getConnection() != null;
    }

    @Override
    public String currentDimension() {
        return currentDimension(mc());
    }

    @Override
    public Path singleplayerWorld() {
        return mc().getSingleplayerServer() != null
                ? mc().getSingleplayerServer().getWorldPath(LevelResource.ROOT) : null;
    }

    @Override
    public String serverAddress() {
        return mc().getCurrentServer() != null ? mc().getCurrentServer().ip : null;
    }

    @Override
    public String playerId() {
        return mc().player != null ? mc().player.getUUID().toString() : null;
    }

    @Override
    public Path configDir() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean save(Path path, Object look, String dimension, long lapMillis, int frames, byte[] data) {
        Look car = (Look) look;
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", GhostEngine.fileVersion());
        tag.putString("Car", car.carId().toString());
        tag.put("Spec", CarSpecNbt.save(car.spec()));
        tag.put("Parts", PartFitments.save(car.parts()));
        tag.putString("Dimension", dimension);
        tag.putLong("LapMillis", lapMillis);
        tag.putInt("Frames", frames);
        tag.putByteArray("Data", data);
        try (OutputStream out = Files.newOutputStream(path)) {
            NbtIo.writeCompressed(tag, out);
            return true;
        } catch (IOException e) {
            LOGGER.warn("ゴーストを保存できませんでした: {}", path, e);
            return false;
        }
    }

    @Override
    public Stored load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            CompoundTag tag = NbtIo.readCompressed(in);
            if (tag.getInt("Version") != GhostEngine.fileVersion()) {
                // 知らない版。中身は読まない（core が版を見て捨てる）
                return new Stored(null, "", 0L, 0, new byte[0], tag.getInt("Version"));
            }
            ResourceLocation car = ResourceLocation.tryParse(tag.getString("Car"));
            Look look = new Look(car != null ? McIds.of(car) : com.jdmmc.kurumamod.car.CarTypes.DEFAULT_ID,
                    CarSpecNbt.load(tag.getCompound("Spec")), PartFitments.load(tag.getCompound("Parts")));
            return new Stored(look, tag.getString("Dimension"), tag.getLong("LapMillis"), tag.getInt("Frames"),
                    tag.getByteArray("Data"), tag.getInt("Version"));
        }
    }

    @Override
    public void message(RaceText text) {
        if (mc().player != null) {
            mc().player.displayClientMessage(RaceTexts.of(text), false);
        }
    }
}
