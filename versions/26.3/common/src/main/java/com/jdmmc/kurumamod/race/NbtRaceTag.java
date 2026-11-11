package com.jdmmc.kurumamod.race;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@link RaceTag} を NBT の {@link CompoundTag} で実装したもの。26.3 は無いキーの既定値を自分で渡す書き方。 */
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
        tag.store(key, UUIDUtil.CODEC, value);
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
        return tag.getStringOr(key, "");
    }

    @Override
    public int getInt(String key) {
        return tag.getIntOr(key, 0);
    }

    @Override
    public long getLong(String key) {
        return tag.getLongOr(key, 0L);
    }

    @Override
    public double getDouble(String key) {
        return tag.getDoubleOr(key, 0.0);
    }

    @Override
    public long[] getLongArray(String key) {
        return tag.getLongArray(key).orElse(new long[0]);
    }

    @Override
    public UUID getUUID(String key) {
        return tag.read(key, UUIDUtil.CODEC).orElse(null);
    }

    @Override
    public List<RaceTag> getList(String key) {
        ListTag list = tag.getListOrEmpty(key);
        List<RaceTag> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(new NbtRaceTag(list.getCompoundOrEmpty(i)));
        }
        return out;
    }
}
