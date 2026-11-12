package com.jdmmc.kurumamod.platform;

import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RacePayload;
import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.jdmmc.kurumamod.race.RaceCar;
import com.jdmmc.kurumamod.race.RaceClassBook;
import com.jdmmc.kurumamod.race.RaceClasses;
import com.jdmmc.kurumamod.race.RaceData;
import com.jdmmc.kurumamod.race.RaceLevel;
import com.jdmmc.kurumamod.race.RacePlayer;
import com.jdmmc.kurumamod.race.RaceRecords;
import com.jdmmc.kurumamod.race.RaceServer;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;

/**
 * core の {@link RaceEngine} から見たサーバー（{@link RaceServer}）を、{@link MinecraftServer} で実装したもの。
 * 次元・プレイヤー・車の包みも持つ。<b>Minecraft の版で違う呼び方は、このファイルにだけ書く。</b>
 */
public final class McRaceServer implements RaceServer {

    private static McRaceServer current;

    private final MinecraftServer server;

    private McRaceServer(MinecraftServer server) {
        this.server = server;
    }

    /** そのサーバーの包み。サーバーが替わったら作り直す。 */
    public static McRaceServer of(MinecraftServer server) {
        McRaceServer wrapped = current;
        if (wrapped == null || wrapped.server != server) {
            wrapped = new McRaceServer(server);
            current = wrapped;
        }
        return wrapped;
    }

    /** サーバーが止まったら捨てる。 */
    public static void clear() {
        current = null;
    }

    public static RaceLevel level(ServerLevel level) {
        return of(level.getServer()).wrap(level);
    }

    public static RacePlayer player(ServerPlayer player) {
        return of(player.getServer()).wrap(player);
    }

    /** 次元の名前。ゴーストのフォルダ名にもなるので、版が変わっても同じ文字列にする。 */
    public static String dimension(ResourceKey<Level> key) {
        return key.location().toString();
    }

    private RaceLevel wrap(ServerLevel level) {
        return new McLevel(level);
    }

    private RacePlayer wrap(ServerPlayer player) {
        return new McPlayer(player);
    }

    @Override
    public int getTickCount() {
        return server.getTickCount();
    }

    @Override
    public List<? extends RaceLevel> levels() {
        List<RaceLevel> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.add(wrap(level));
        }
        return out;
    }

    @Override
    public RaceLevel level(String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (dimension(level.dimension()).equals(dimension)) {
                return wrap(level);
            }
        }
        return null;
    }

    @Override
    public RacePlayer player(UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        return player == null ? null : wrap(player);
    }

    @Override
    public List<? extends RacePlayer> players() {
        List<RacePlayer> out = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            out.add(wrap(player));
        }
        return out;
    }

    @Override
    public void broadcastSystemMessage(RaceText message) {
        server.getPlayerList().broadcastSystemMessage(RaceTexts.of(message), false);
    }

    @Override
    public RaceClassBook classes() {
        return RaceClasses.book(server);
    }

    @Override
    public boolean isSingleplayerOwner(RacePlayer player) {
        return server.isSingleplayerOwner(((McPlayer) player).handle.getGameProfile());
    }

    @Override
    public String cachedName(UUID id) {
        return server.getProfileCache() == null ? "?" : server.getProfileCache().get(id)
                .map(com.mojang.authlib.GameProfile::getName).orElse("?");
    }

    private final class McLevel implements RaceLevel {
        private final ServerLevel handle;

        private McLevel(ServerLevel handle) {
            this.handle = handle;
        }

        @Override
        public String dimension() {
            return McRaceServer.dimension(handle.dimension());
        }

        @Override
        public RaceServer getServer() {
            return McRaceServer.this;
        }

        @Override
        public List<? extends RacePlayer> players() {
            List<RacePlayer> out = new ArrayList<>();
            for (ServerPlayer player : handle.players()) {
                out.add(wrap(player));
            }
            return out;
        }

        @Override
        public RaceRecords records() {
            return RaceData.get(handle);
        }

        @Override
        public int nextInt(int bound) {
            return handle.random.nextInt(bound);
        }
    }

    private final class McPlayer implements RacePlayer {
        private final ServerPlayer handle;

        private McPlayer(ServerPlayer handle) {
            this.handle = handle;
        }

        @Override
        public UUID getUUID() {
            return handle.getUUID();
        }

        @Override
        public String name() {
            return handle.getGameProfile().getName();
        }

        @Override
        public RaceText getDisplayName() {
            return RaceText.ofNative(handle.getDisplayName());
        }

        @Override
        public RaceLevel level() {
            return wrap((ServerLevel) handle.level());
        }

        @Override
        public RaceServer getServer() {
            return McRaceServer.this;
        }

        @Override
        public boolean hasGamemasterPermission() {
            return handle.hasPermissions(2);
        }

        @Override
        public RaceCar drivenCar() {
            return handle.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == handle
                    ? new McCar(car) : null;
        }

        @Override
        public void sendSystemMessage(RaceText message) {
            handle.sendSystemMessage(RaceTexts.of(message));
        }

        @Override
        public void sendOverlay(RaceText message) {
            handle.displayClientMessage(RaceTexts.of(message), true);
        }

        @Override
        public void send(RacePayload payload) {
            KurumaNetwork.sendToPlayer(handle, payload);
        }
    }

    /** 車を core の {@link RaceCar} として包む（クライアントの {@code McRaceClient} も使う）。 */
    public static RaceCar car(CarEntity car) {
        return new McCar(car);
    }

    private record McCar(CarEntity car) implements RaceCar {
        @Override
        public double getX() {
            return car.getX();
        }

        @Override
        public double getY() {
            return car.getY();
        }

        @Override
        public double getZ() {
            return car.getZ();
        }

        @Override
        public String carId() {
            return car.getCarId().toString();
        }

        @Override
        public CarSpec getSpec() {
            return car.getSpec();
        }

        @Override
        public CarSpec baseSpec() {
            return CarTypes.get(car.getCarId()).spec();
        }
    }
}
