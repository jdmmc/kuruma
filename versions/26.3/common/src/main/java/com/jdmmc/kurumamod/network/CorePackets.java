package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.race.RaceManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import com.jdmmc.kurumamod.platform.FriendlyRaceBuf;

/**
 * core に置いたパケットの、名前と届いたときの処理。ローダーの {@code KurumaNetwork} はここの
 * {@link Kind#type()}・{@link Kind#codec()} で登録する。
 *
 * <p><b>名前は core へ移す前と同じ</b>なので、通信の形は変わっていない（プロトコルの版も上げていない）。</p>
 */
public final class CorePackets {

    /** パケット 1 種類。 */
    public static final class Kind<T extends RacePayload> {
        private final Class<T> payloadClass;
        private final CustomPacketPayload.Type<CorePayload> type;
        private final StreamCodec<FriendlyByteBuf, CorePayload> codec;
        private final BiConsumer<T, PacketContext> handler;

        private Kind(String name, Class<T> payloadClass, Function<RaceBuf, T> reader,
                     BiConsumer<T, PacketContext> handler) {
            this.payloadClass = payloadClass;
            this.type = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Kurumamod.MODID, name));
            this.handler = handler;
            this.codec = StreamCodec.of(
                    (buf, payload) -> payload.body().encode(new FriendlyRaceBuf(buf)),
                    buf -> new CorePayload(this, reader.apply(new FriendlyRaceBuf(buf))));
        }

        public CustomPacketPayload.Type<CorePayload> type() {
            return type;
        }

        public StreamCodec<FriendlyByteBuf, CorePayload> codec() {
            return codec;
        }

        void handle(RacePayload body, PacketContext context) {
            handler.accept(payloadClass.cast(body), context);
        }
    }

    private static final Map<Class<?>, Kind<?>> KINDS = new HashMap<>();

    private static <T extends RacePayload> Kind<T> kind(String name, Class<T> payloadClass, Function<RaceBuf, T> reader,
                                                        BiConsumer<T, PacketContext> handler) {
        Kind<T> kind = new Kind<>(name, payloadClass, reader, handler);
        KINDS.put(payloadClass, kind);
        return kind;
    }

    // サーバー → クライアント
    public static final Kind<RaceEventPacket> RACE_EVENT = kind("race_event", RaceEventPacket.class,
            RaceEventPacket::new, (p, c) -> c.enqueueWork(() -> CarClientPackets.applyRaceEvent(p)));
    public static final Kind<CourseLinesPacket> COURSE_LINES = kind("course_lines", CourseLinesPacket.class,
            CourseLinesPacket::new, (p, c) -> c.enqueueWork(() -> CarClientPackets.applyCourseLines(p)));
    public static final Kind<RaceStandingsPacket> RACE_STANDINGS = kind("race_standings", RaceStandingsPacket.class,
            RaceStandingsPacket::new, (p, c) -> c.enqueueWork(() -> CarClientPackets.applyStandings(p)));
    public static final Kind<RaceLobbyPacket> RACE_LOBBY = kind("race_lobby", RaceLobbyPacket.class,
            RaceLobbyPacket::new, (p, c) -> c.enqueueWork(() -> CarClientPackets.applyRaceLobby(p)));
    public static final Kind<RaceClassesPacket> RACE_CLASSES = kind("race_classes", RaceClassesPacket.class,
            RaceClassesPacket::new, (p, c) -> c.enqueueWork(() -> CarClientPackets.applyRaceClasses(p)));
    public static final Kind<TimeAttackRecordsPacket> TIME_ATTACK_RECORDS = kind("time_attack_records",
            TimeAttackRecordsPacket.class, TimeAttackRecordsPacket::new,
            (p, c) -> c.enqueueWork(() -> CarClientPackets.applyTimeAttackRecords(p)));

    // クライアント → サーバー
    public static final Kind<RaceActionPacket> RACE_ACTION = kind("race_action", RaceActionPacket.class,
            RaceActionPacket::new, (p, c) -> c.enqueueWork(() -> {
                if (c.player() instanceof ServerPlayer player) {
                    RaceManager.handleAction(player, p);
                }
            }));

    // 車のパケット。中身は core、届いたときの処理は CarPacketHandlers。名前を変えると通信が変わる
    public static final Kind<CarImpactPacket> CAR_IMPACT = kind("car_impact", CarImpactPacket.class,
            CarImpactPacket::new, CarPacketHandlers::handleCarImpact);
    public static final Kind<CarPaintRequestPacket> CAR_PAINT_REQUEST = kind("car_paint_request", CarPaintRequestPacket.class,
            CarPaintRequestPacket::new, CarPacketHandlers::handleCarPaintRequest);
    public static final Kind<CarPartRequestPacket> CAR_PART_REQUEST = kind("car_part_request", CarPartRequestPacket.class,
            CarPartRequestPacket::new, CarPacketHandlers::handleCarPartRequest);
    public static final Kind<CarPartTypesPacket> CAR_PART_TYPES = kind("car_part_types", CarPartTypesPacket.class,
            CarPartTypesPacket::new, CarPacketHandlers::handleCarPartTypes);
    public static final Kind<CarPartsPacket> CAR_PARTS = kind("car_parts", CarPartsPacket.class,
            CarPartsPacket::new, CarPacketHandlers::handleCarParts);
    public static final Kind<CarPlateRequestPacket> CAR_PLATE_REQUEST = kind("car_plate_request", CarPlateRequestPacket.class,
            CarPlateRequestPacket::new, CarPacketHandlers::handleCarPlateRequest);
    public static final Kind<CarPushPacket> CAR_PUSH = kind("car_push", CarPushPacket.class,
            CarPushPacket::new, CarPacketHandlers::handleCarPush);
    public static final Kind<CarRulesPacket> CAR_RULES = kind("car_rules", CarRulesPacket.class,
            CarRulesPacket::new, CarPacketHandlers::handleCarRules);
    public static final Kind<CarScrapePacket> CAR_SCRAPE = kind("car_scrape", CarScrapePacket.class,
            CarScrapePacket::new, CarPacketHandlers::handleCarScrape);
    public static final Kind<CarSpecPacket> CAR_SPEC = kind("car_spec", CarSpecPacket.class,
            CarSpecPacket::new, CarPacketHandlers::handleCarSpec);
    public static final Kind<CarStatePacket> CAR_STATE = kind("car_state", CarStatePacket.class,
            CarStatePacket::new, CarPacketHandlers::handleCarState);
    public static final Kind<CarTuningPacket> CAR_TUNING = kind("car_tuning", CarTuningPacket.class,
            CarTuningPacket::new, CarPacketHandlers::handleCarTuning);
    public static final Kind<CarTypesPacket> CAR_TYPES = kind("car_types", CarTypesPacket.class,
            CarTypesPacket::new, CarPacketHandlers::handleCarTypes);

    private CorePackets() {
    }

    /** 送るときの包み。 */
    public static CorePayload wrap(RacePayload body) {
        Kind<?> kind = KINDS.get(body.getClass());
        if (kind == null) {
            throw new IllegalArgumentException("CorePackets に登録していないパケットです: " + body.getClass().getName());
        }
        return new CorePayload(kind, body);
    }
}
