package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.car.CarId;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

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

    void writeFloat(float value);

    /** 下位 16 ビットを書く（バニラの {@code writeShort}）。 */
    void writeShort(int value);

    /** varint の数と、その数の varint。 */
    void writeVarIntArray(int[] values);

    float readFloat();

    int readUnsignedByte();

    int readUnsignedShort();

    /** {@link #writeVarIntArray} で書いたもの。上限は残りのバイト数（バニラと同じ）。 */
    int[] readVarIntArray();

    /** バニラの {@code writeCollection} と同じ。varint の数のあとに要素を順に。 */
    default <T> void writeCollection(Collection<T> values, BiConsumer<RaceBuf, T> writer) {
        writeVarInt(values.size());
        for (T value : values) {
            writer.accept(this, value);
        }
    }

    /** バニラの {@code readCollection} と同じ。 */
    default <T, C extends Collection<T>> C readCollection(IntFunction<C> factory, Function<RaceBuf, T> reader) {
        int count = readVarInt();
        C values = factory.apply(count);
        for (int i = 0; i < count; i++) {
            values.add(reader.apply(this));
        }
        return values;
    }

    /** バニラの {@code writeMap} と同じ。varint の数のあとに鍵・値の順に。 */
    default <K, V> void writeMap(Map<K, V> map, BiConsumer<RaceBuf, K> keyWriter, BiConsumer<RaceBuf, V> valueWriter) {
        writeVarInt(map.size());
        map.forEach((key, value) -> {
            keyWriter.accept(this, key);
            valueWriter.accept(this, value);
        });
    }

    /** バニラの {@code readMap} と同じ（{@code HashMap}）。 */
    default <K, V> Map<K, V> readMap(Function<RaceBuf, K> keyReader, Function<RaceBuf, V> valueReader) {
        int count = readVarInt();
        Map<K, V> map = new HashMap<>();
        for (int i = 0; i < count; i++) {
            K key = keyReader.apply(this);
            map.put(key, valueReader.apply(this));
        }
        return map;
    }

    /** 車種・部品の id。バニラの {@code writeResourceLocation} と同じバイト（{@code ns:path} の文字列）。 */
    default void writeCarId(CarId id) {
        writeUtf(id.toString());
    }

    /** {@link #writeCarId} で書いたもの。id として読めなければ {@link #invalid}。 */
    default CarId readCarId() {
        String text = readUtf();
        CarId id = CarId.parse(text);
        if (id == null) {
            throw invalid("id として読めません: " + text);
        }
        return id;
    }

    /**
     * 読めない形だったときに投げる例外（{@code throw buf.invalid(...)}）。版ではネットワークの層と同じ
     * {@code DecoderException} を返すので、壊れたパケットを送ってきた人だけが切断される。
     */
    RuntimeException invalid(String message);
}
