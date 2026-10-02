package com.jdmmc.kurumamod.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.CarClientPackets;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。コースの線をゲートとして描くための形。
 *
 * <p>線は動かないので、変わったときだけ送れば足りる（ログイン時、コースをいじったとき、
 * レースの開始・終了時）。</p>
 *
 * @param raceActive レース中か。レース中は全員に見せる
 * @param gates      描く線
 */
public record CourseLinesPacket(boolean raceActive, List<Gate> gates) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CourseLinesPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Kurumamod.MODID, "course_lines"));

    public static final StreamCodec<FriendlyByteBuf, CourseLinesPacket> STREAM_CODEC =
            StreamCodec.ofMember(CourseLinesPacket::encode, CourseLinesPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


    /**
     * ゲート 1 つ。
     *
     * @param start   スタート／ゴールなら true、チェックポイントなら false
     * @param yBottom 足元の高さ
     * @param yTop    ゲートの上端
     */
    public record Gate(boolean start, double ax, double az, double bx, double bz,
                       double yBottom, double yTop) {
    }

    public CourseLinesPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), readGates(buf));
    }

    private static List<Gate> readGates(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Gate> gates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            gates.add(new Gate(buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return gates;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(raceActive);
        buf.writeVarInt(gates.size());
        for (Gate gate : gates) {
            buf.writeBoolean(gate.start());
            buf.writeDouble(gate.ax());
            buf.writeDouble(gate.az());
            buf.writeDouble(gate.bx());
            buf.writeDouble(gate.bz());
            buf.writeDouble(gate.yBottom());
            buf.writeDouble(gate.yTop());
        }
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> CarClientPackets.applyCourseLines(this));
    }
}
