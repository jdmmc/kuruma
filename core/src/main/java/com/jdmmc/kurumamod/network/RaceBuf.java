package com.jdmmc.kurumamod.network;

import java.util.UUID;

/**
 * パケットのバッファ（{@code FriendlyByteBuf}）の薄い包み。版ごとの {@code FriendlyRaceBuf} が実装する。
 *
 * <p>core に置いたパケット（{@link RacePayload}）は、読み書きの順番をこれを通して 1 か所にだけ持つ。
 * 書き方はバニラの同じ名前のメソッドとまったく同じ（{@code writeUtf} の上限、{@code readByte} の符号まで）。</p>
 */
public interface RaceBuf {

    void writeByte(int value);

    void writeVarInt(int value);

    void writeUtf(String value, int maxLength);

    /** バニラと同じく上限 32767 字。 */
    default void writeUtf(String value) {
        writeUtf(value, 32767);
    }

    void writeLong(long value);

    void writeDouble(double value);

    void writeBoolean(boolean value);

    void writeUUID(UUID value);

    byte readByte();

    int readVarInt();

    String readUtf(int maxLength);

    /** バニラと同じく上限 32767 字。 */
    default String readUtf() {
        return readUtf(32767);
    }

    long readLong();

    double readDouble();

    boolean readBoolean();

    UUID readUUID();

    /**
     * 読めない形だったときに投げる例外（{@code throw buf.invalid(...)}）。版ではネットワークの層と同じ
     * {@code DecoderException} を返すので、壊れたパケットを送ってきた人だけが切断される。
     */
    RuntimeException invalid(String message);
}
