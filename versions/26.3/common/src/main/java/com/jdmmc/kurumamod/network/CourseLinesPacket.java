package com.jdmmc.kurumamod.network;

import net.minecraft.resources.Identifier;
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
 * <p>線は動かないので、変わったときだけ送れば足りる（ログイン時、次元を移ったとき、コースをいじったとき）。</p>
 *
 * <p><b>送るのはコースの形だけで、レースのことは載せない。</b>どのゲートを描くかはクライアントが自分の状態
 * （走っているコース・観戦しているレース・車に乗っているか）で決める（{@code CourseRenderer}）。以前は
 * サーバーがレースの状態から人ごとに中身を変えて送っていたので、レースに加わる・抜けるたびに送り直す必要があり、
 * 送り直しの漏れで、抜けた人の画面にレースのコースのゲートしか残らなかった。</p>
 *
 * @param gates その次元の全部のコースのゲート
 */
public record CourseLinesPacket(List<Gate> gates) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CourseLinesPacket> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Kurumamod.MODID, "course_lines"));

    public static final StreamCodec<FriendlyByteBuf, CourseLinesPacket> STREAM_CODEC =
            StreamCodec.ofMember(CourseLinesPacket::encode, CourseLinesPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


    /**
     * ゲート 1 つ。
     *
     * @param course  どのコースのゲートか
     * @param start   スタート／ゴールなら true、チェックポイントなら false
     * @param yBottom 足元の高さ
     * @param yTop    ゲートの上端
     */
    public record Gate(String course, boolean start, double ax, double az, double bx, double bz,
                       double yBottom, double yTop) {
    }

    public CourseLinesPacket(FriendlyByteBuf buf) {
        this(readGates(buf));
    }

    /**
     * ゲートの数の上限。<b>書く側も同じ数で切る</b>（読む側だけで切ると、残りのバイトを読み違えて切断される）。
     * 届いた数をそのまま配列の大きさにすると、壊れた数で巨大な配列を作ろうとして落ちる。
     */
    private static final int MAX_GATES = 4096;

    private static List<Gate> readGates(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_GATES) {
            throw new io.netty.handler.codec.DecoderException("コースのゲートの数が範囲外です: " + count);
        }
        List<Gate> gates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            gates.add(new Gate(buf.readUtf(256), buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return gates;
    }

    public void encode(FriendlyByteBuf buf) {
        List<Gate> sent = gates.subList(0, Math.min(MAX_GATES, gates.size()));
        buf.writeVarInt(sent.size());
        for (Gate gate : sent) {
            buf.writeUtf(gate.course(), 256);
            buf.writeBoolean(gate.start());
            buf.writeDouble(gate.ax());
            buf.writeDouble(gate.az());
            buf.writeDouble(gate.bx());
            buf.writeDouble(gate.bz());
            buf.writeDouble(gate.yBottom());
            buf.writeDouble(gate.yTop());
        }
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> CarClientPackets.applyCourseLines(this));
    }
}
