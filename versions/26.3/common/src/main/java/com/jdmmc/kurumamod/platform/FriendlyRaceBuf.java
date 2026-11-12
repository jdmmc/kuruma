package com.jdmmc.kurumamod.platform;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;
import com.jdmmc.kurumamod.network.RaceBuf;

/** {@link RaceBuf} を {@link FriendlyByteBuf} で実装したもの。 */
public final class FriendlyRaceBuf implements RaceBuf {

    private final FriendlyByteBuf buf;

    public FriendlyRaceBuf(FriendlyByteBuf buf) {
        this.buf = buf;
    }

    @Override
    public void writeByte(int value) {
        buf.writeByte(value);
    }

    @Override
    public void writeVarInt(int value) {
        buf.writeVarInt(value);
    }

    @Override
    public void writeUtf(String value, int maxLength) {
        buf.writeUtf(value, maxLength);
    }

    @Override
    public void writeLong(long value) {
        buf.writeLong(value);
    }

    @Override
    public void writeDouble(double value) {
        buf.writeDouble(value);
    }

    @Override
    public void writeBoolean(boolean value) {
        buf.writeBoolean(value);
    }

    @Override
    public void writeUUID(UUID value) {
        buf.writeUUID(value);
    }

    @Override
    public byte readByte() {
        return buf.readByte();
    }

    @Override
    public int readVarInt() {
        return buf.readVarInt();
    }

    @Override
    public String readUtf(int maxLength) {
        return buf.readUtf(maxLength);
    }

    @Override
    public long readLong() {
        return buf.readLong();
    }

    @Override
    public double readDouble() {
        return buf.readDouble();
    }

    @Override
    public boolean readBoolean() {
        return buf.readBoolean();
    }

    @Override
    public UUID readUUID() {
        return buf.readUUID();
    }

    @Override
    public void writeFloat(float value) {
        buf.writeFloat(value);
    }

    @Override
    public void writeShort(int value) {
        buf.writeShort(value);
    }

    @Override
    public void writeVarIntArray(int[] values) {
        buf.writeVarIntArray(values);
    }

    @Override
    public float readFloat() {
        return buf.readFloat();
    }

    @Override
    public int readUnsignedByte() {
        return buf.readUnsignedByte();
    }

    @Override
    public int readUnsignedShort() {
        return buf.readUnsignedShort();
    }

    @Override
    public int[] readVarIntArray() {
        return buf.readVarIntArray();
    }

    @Override
    public RuntimeException invalid(String message) {
        return new DecoderException(message);
    }
}
