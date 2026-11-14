package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.part.PartRequests;
import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.car.CarRules;
import com.jdmmc.kurumamod.car.CarSync;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.physics.Aero;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Tire;
import com.jdmmc.kurumamod.physics.Turbo;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.plate.PlateChars;
import com.jdmmc.kurumamod.plate.PlateField;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlateRules;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 車のパケット（中身は core の {@code network/}）が届いたときの処理。Minecraft に触るのはここだけ。
 *
 * <p>名前と包みは {@link CorePackets}（1.20.1 は {@link KurumaNetwork} の SimpleChannel）。</p>
 */
public final class CarPacketHandlers {

    private CarPacketHandlers() {
    }

    /** {@link CarImpactPacket}（"car_impact"）が届いたとき。 */
    public static void handleCarImpact(CarImpactPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CarClientPackets.applyImpact(packet.entityId(), packet.impact()));
                return;
            }
            Entity entity = sender.level().getEntity(packet.entityId());
            // 運転している本人からのものだけ中継する
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                broadcastImpact(car, packet.impact());
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * その車を追跡しているクライアントへ配る。
     *
     * <p>運転者本人にも届くが、<b>本人は既に自分の画面で鳴らしている</b>ので受信側で捨てる
     * （{@code CarClientPackets#applyImpact}）。送り先から除くよりこちらの方が単純で、
     * 無人の車をサーバーが解いているときも同じ道を通せる。</p>
     */
    public static void broadcastImpact(CarEntity car, float impact) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> car),
                new CarImpactPacket(car.getId(), impact));
    }

    /** {@link CarPaintRequestPacket}（"car_paint_request"）が届いたとき。 */
    public static void handleCarPaintRequest(CarPaintRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            Entity entity = sender.level().getEntity(packet.entityId());
            if (!(entity instanceof CarEntity car) || car.getControllingPassenger() != sender) {
                return;
            }
            // setFitment がそのまま周囲のクライアントへ配る
            car.setFitment(car.getFitment().withPaint(packet.paint()));
        });
        context.setPacketHandled(true);
    }

    /** {@link CarPartRequestPacket}（"car_part_request"）が届いたとき。 */
    public static void handleCarPartRequest(CarPartRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null || packet.slot() == null) {
                return; // slot が null なのは、送り手だけが知っている場所を指しているとき
            }

            Entity entity = sender.level().getEntity(packet.entityId());
            if (!(entity instanceof CarEntity car) || car.getControllingPassenger() != sender) {
                return;
            }

            // 何を付けてよいかは core の決まり。弾いたときだけ、どの部品かを知らせる
            PartRequests.Blocked blocked = PartRequests.apply(car, packet.slot(), packet.setting(),
                    com.jdmmc.kurumamod.race.RaceManager.restrictionFor(sender));
            if (blocked != null) {
                sender.displayClientMessage(partBlocked(blocked.partId()), true);
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * 決まりの外になるので付けなかった、の知らせ。<b>どの部品かを書く</b>——プリセットは何か所も一度に換えるので、
     * 名前が無いとどれが付かなかったのか分からない。名前はクライアントの言語で出す（サーバーは翻訳を持たない）。
     */
    private static net.minecraft.network.chat.Component partBlocked(com.jdmmc.kurumamod.car.CarId partId) {
        CarPart part = partId == null ? null : CarParts.get(partId);
        if (part == null) {
            return net.minecraft.network.chat.Component.translatable("race.kurumamod.part_blocked");
        }
        return net.minecraft.network.chat.Component.translatable("race.kurumamod.part_blocked_named",
                net.minecraft.network.chat.Component.translatableWithFallback(part.translationKey(), part.id().getPath()));
    }

    /** {@link CarPartTypesPacket}（"car_part_types"）が届いたとき。 */
    public static void handleCarPartTypes(CarPartTypesPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyCarParts(packet.parts())));
        context.setPacketHandled(true);
    }

    /** {@link CarPartsPacket}（"car_parts"）が届いたとき。 */
    public static void handleCarParts(CarPartsPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyParts(packet.entityId(), packet.parts())));
        context.setPacketHandled(true);
    }

    /** {@link CarPlateRequestPacket}（"car_plate_request"）が届いたとき。 */
    public static void handleCarPlateRequest(CarPlateRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null || !(sender.level().getEntity(packet.entityId()) instanceof CarEntity car)
                    || car.getControllingPassenger() != sender) {
                return;
            }
            Map<String, String> text = rulesOf(car).clean(packet.plate().text());
            List<String> keys = new ArrayList<>(text.keySet());
            List<String> values = keys.stream().map(text::get).toList();
            sender.getTextFilter().processMessageBundle(values).thenAcceptAsync(filtered -> {
                Entity entity = sender.level().getEntity(packet.entityId());
                if (!(entity instanceof CarEntity current) || current.getControllingPassenger() != sender) {
                    return;
                }
                Map<String, String> passed = new LinkedHashMap<>();
                for (int i = 0; i < keys.size() && i < filtered.size(); i++) {
                    FilteredText result = filtered.get(i);
                    passed.put(keys.get(i), result.filteredOrEmpty());
                }
                // 伏せ字（#）が欄の決まりに合わなければここで落ちる。空になった欄は既定の文字に戻る
                PlateLook cleaned = new PlateLook(rulesOf(current).clean(passed), packet.plate().front(), packet.plate().rear());
                // setFitment がそのまま周囲のクライアントへ配る
                current.setFitment(current.getFitment().withPlate(cleaned));
            }, sender.server);
        });
        context.setPacketHandled(true);
    }

    /** 付けているプレートの欄の決まり。付けていない・知らない部品なら決まり無し（最低限の検査だけ）。 */
    private static PlateRules rulesOf(CarEntity car) {
        return PartRequests.plateRules(car);
    }

    /** {@link CarPushPacket}（"car_push"）が届いたとき。 */
    public static void handleCarPush(CarPushPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CarClientPackets.applyPush(packet.entityId(), packet.dx(), packet.dz(), packet.dYawRate()));
                return;
            }
            // ぶつけた側が本当に車を運転しているか確かめる
            if (!(sender.getVehicle() instanceof CarEntity source)
                    || source.getControllingPassenger() != sender) {
                return;
            }
            Entity entity = sender.level().getEntity(packet.entityId());
            if (!(entity instanceof CarEntity target) || target == source) {
                return;
            }
            CarPushPacket clamped = packet.clamp();
            if (target.getControllingPassenger() instanceof Player driver) {
                if (driver instanceof ServerPlayer serverDriver) {
                    KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverDriver), clamped);
                }
            } else {
                // 無人の車はサーバーが解いているので、ここで足す
                target.applyPush(clamped.dx(), clamped.dz(), clamped.dYawRate());
            }
        });
        context.setPacketHandled(true);
    }

    /** {@link CarRulesPacket}（"car_rules"）が届いたとき。 */
    public static void handleCarRules(CarRulesPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> CarRules.setCarCollision(packet.carCollision()));
        context.get().setPacketHandled(true);
    }

    /** {@link CarScrapePacket}（"car_scrape"）が届いたとき。 */
    public static void handleCarScrape(CarScrapePacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CarClientPackets.applyScrape(packet.entityId(), packet.offset(), packet.normal(), packet.velocity(), packet.impact()));
                return;
            }
            Entity entity = sender.level().getEntity(packet.entityId());
            // 運転している本人からのものだけ中継する
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                broadcastScrape(car, packet.offset(), packet.normal(), packet.velocity(), packet.impact());
            }
        });
        context.setPacketHandled(true);
    }

    /** その車を追跡しているクライアントへ配る。運転者本人のぶんは受信側で捨てる。 */
    public static void broadcastScrape(CarEntity car, float[] offset, float[] normal, float[] velocity, float impact) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> car),
                new CarScrapePacket(car.getId(), offset, normal, velocity, impact));
    }

    /** {@link CarSpecPacket}（"car_spec"）が届いたとき。 */
    public static void handleCarSpec(CarSpecPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applySpec(packet.entityId(), packet.spec())));
        context.setPacketHandled(true);
    }

    /** {@link CarStatePacket}（"car_state"）が届いたとき。 */
    public static void handleCarState(CarStatePacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) return;

            Entity entity = sender.level().getEntity(packet.entityId());
            // 運転している本人からのパケットだけ受け付ける
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                car.applyDriverState(packet);
            }
        });
        context.setPacketHandled(true);
    }

    /** {@link CarTuningPacket}（"car_tuning"）が届いたとき。 */
    public static void handleCarTuning(CarTuningPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) return;

            List<TunableParameter> parameters = Tunables.ALL;
            if (packet.values().length != parameters.size()) {
                return; // 項目の並びが食い違っている。適用すると別の値になるので捨てる
            }

            Entity entity = sender.level().getEntity(packet.entityId());
            // 運転している本人からのパケットだけ受け付ける
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                // setSpec がそのまま周囲のクライアントへ配る（諸元は見た目に効くため）
                car.setSpec(CarSpecCodec.apply(packet.values()));
            }
        });
        context.setPacketHandled(true);
    }

    /** {@link CarTypesPacket}（"car_types"）が届いたとき。 */
    public static void handleCarTypes(CarTypesPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CarClientPackets.applyCarTypes(packet.types())));
        context.setPacketHandled(true);
    }
}
