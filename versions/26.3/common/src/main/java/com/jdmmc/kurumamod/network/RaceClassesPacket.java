package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.CarClientPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。サーバーに保存してあるクラス（名前・中身・記録の件数）。
 *
 * <p><b>変わったときだけ送る</b>（ログインしたとき、クラスの保存・削除・並べ替え、クラスの記録の件数が変わったとき）。
 * 以前は {@link RaceLobbyPacket} に入れて 0.5 秒ごとに全員へ送っていたので、送るたびに全員ぶん記録の件数を
 * 数え直していた。中身は全員同じなので、組むのは 1 回で、全員に同じものを送る。</p>
 *
 * @param classes 並び順（一覧・ランキングの順）
 */
public record RaceClassesPacket(List<RaceLobbyPacket.RaceClass> classes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RaceClassesPacket> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Kurumamod.MODID, "race_classes"));

    public static final StreamCodec<FriendlyByteBuf, RaceClassesPacket> STREAM_CODEC =
            StreamCodec.ofMember(RaceClassesPacket::encode, RaceClassesPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 読むときの上限。サーバーは {@code RaceClasses.MAX_CLASSES} までしか持たない。 */
    private static final int MAX_CLASSES = 256;

    public RaceClassesPacket(FriendlyByteBuf buf) {
        this(readClasses(buf));
    }

    private static List<RaceLobbyPacket.RaceClass> readClasses(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CLASSES) {
            throw new io.netty.handler.codec.DecoderException("クラスの数が範囲外です: " + count);
        }
        List<RaceLobbyPacket.RaceClass> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf(256);
            int length = buf.readVarInt();
            if (length < 0 || length > 64) {
                throw new io.netty.handler.codec.DecoderException("クラスの決まりの数が範囲外です: " + length);
            }
            double[] regulation = new double[length];
            for (int j = 0; j < length; j++) {
                regulation[j] = buf.readDouble();
            }
            list.add(new RaceLobbyPacket.RaceClass(name, regulation, buf.readVarInt()));
        }
        return list;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(classes.size());
        for (RaceLobbyPacket.RaceClass raceClass : classes) {
            buf.writeUtf(raceClass.name(), 256);
            buf.writeVarInt(raceClass.regulation().length);
            for (double value : raceClass.regulation()) {
                buf.writeDouble(value);
            }
            buf.writeVarInt(raceClass.records());
        }
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> CarClientPackets.applyRaceClasses(this));
    }
}
