package com.jdmmc.kurumamod.race;

import java.util.List;
import java.util.UUID;

/**
 * 保存する入れ物（NBT の {@code CompoundTag}）の薄い包み。版ごとの {@code NbtRaceTag} が実装する。
 *
 * <p><b>キー名と書き方は core にだけ置く</b>（{@link Course#save}・{@code RaceRecords#save}）。版で違うのは
 * 「無いキーを読んだときの書き方」（1.21.1 の {@code getString} と 26.3 の {@code getStringOr}）だけなので、
 * それをこの包みの中へ閉じ込める。キー名を版ごとに持つと、片方だけ変えたときにワールドの記録が読めなくなる。</p>
 *
 * <p>読む側は、<b>無いキーなら 0・空文字・空の並び</b>を返す（バニラの 1.21.1 と同じ）。UUID だけは null。</p>
 */
public interface RaceTag {

    /** 同じ種類の、空の入れ物を作る（並びの要素や入れ子に使う）。 */
    RaceTag child();

    void putString(String key, String value);

    void putInt(String key, int value);

    void putLong(String key, long value);

    void putDouble(String key, double value);

    void putLongArray(String key, long[] value);

    /** バニラと同じ int 4 つの並びで書く。 */
    void putUUID(String key, UUID value);

    /** 入れ物の並び。要素は {@link #child()} で作ったもの。 */
    void putList(String key, List<RaceTag> value);

    String getString(String key);

    int getInt(String key);

    long getLong(String key);

    double getDouble(String key);

    long[] getLongArray(String key);

    /** 無いか、読めなければ null。 */
    UUID getUUID(String key);

    /** 入れ物の並び。無ければ空。 */
    List<RaceTag> getList(String key);

    // ----- 入れ子（部品の装着状態など。2026-11-12 に足した） -----

    /** 鍵の一覧。 */
    java.util.Set<String> keys();

    /** その鍵があるか（型を問わない）。 */
    boolean contains(String key);

    /** その鍵が文字列か。 */
    boolean isString(String key);

    /** その鍵が入れ子（compound）か。 */
    boolean isCompound(String key);

    /** 入れ子を読む。無い・入れ子でなければ空のもの（書き込んでも元には残らない）。 */
    RaceTag getCompound(String key);

    /** 入れ子を書く（{@link #child()} で作ったもの）。 */
    void put(String key, RaceTag value);
}
