package com.jdmmc.kurumamod.race;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@link RaceTag} を NBT の {@link CompoundTag} で実装したもの。 */
public final class NbtRaceTag implements RaceTag {

    private final CompoundTag tag;

    public NbtRaceTag(CompoundTag tag) {
        this.tag = tag;
    }

    public CompoundTag tag() {
        return tag;
    }

    @Override
    public RaceTag child() {
        return new NbtRaceTag(new CompoundTag());
    }

    @Override
    public void putString(String key, String value) {
        tag.putString(key, value);
    }

    @Override
    public void putInt(String key, int value) {
        tag.putInt(key, value);
    }

    @Override
    public void putLong(String key, long value) {
        tag.putLong(key, value);
    }

    @Override
    public void putDouble(String key, double value) {
        tag.putDouble(key, value);
    }

    @Override
    public void putLongArray(String key, long[] value) {
        tag.putLongArray(key, value);
    }

    @Override
    public void putUUID(String key, UUID value) {
        tag.putUUID(key, value);
    }

    @Override
    public void putList(String key, List<RaceTag> value) {
        ListTag list = new ListTag();
        for (RaceTag entry : value) {
            list.add(((NbtRaceTag) entry).tag);
        }
        tag.put(key, list);
    }

    @Override
    public String getString(String key) {
        return tag.getString(key);
    }

    @Override
    public int getInt(String key) {
        return tag.getInt(key);
    }

    @Override
    public long getLong(String key) {
        return tag.getLong(key);
    }

    @Override
    public double getDouble(String key) {
        return tag.getDouble(key);
    }

    @Override
    public long[] getLongArray(String key) {
        return tag.getLongArray(key);
    }

    @Override
    public UUID getUUID(String key) {
        return tag.hasUUID(key) ? tag.getUUID(key) : null;
    }

    @Override
    public List<RaceTag> getList(String key) {
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        List<RaceTag> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(new NbtRaceTag(list.getCompound(i)));
        }
        return out;
    }
}
