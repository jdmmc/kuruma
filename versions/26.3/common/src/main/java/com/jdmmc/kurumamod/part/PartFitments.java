package com.jdmmc.kurumamod.part;

import com.jdmmc.kurumamod.platform.FriendlyRaceBuf;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.race.NbtRaceTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * core の {@link PartFitment} を NBT（{@link CompoundTag}）とパケット（{@link FriendlyByteBuf}）に包む口。
 * 中身と書き方は core のまま（キーと並びは前と同じ）。
 */
public final class PartFitments {

    private PartFitments() {
    }

    public static CompoundTag save(PartFitment fitment) {
        CompoundTag tag = new CompoundTag();
        fitment.save(new NbtRaceTag(tag));
        return tag;
    }

    public static PartFitment load(CompoundTag tag) {
        return PartFitment.load(new NbtRaceTag(tag));
    }

    public static void write(FriendlyByteBuf buf, PartFitment fitment) {
        fitment.write(new FriendlyRaceBuf(buf));
    }

    public static PartFitment read(FriendlyByteBuf buf) {
        return PartFitment.read(new FriendlyRaceBuf(buf));
    }

    public static void writePlate(FriendlyByteBuf buf, PlateLook plate) {
        PartFitment.writePlate(new FriendlyRaceBuf(buf), plate);
    }

    public static PlateLook readPlate(FriendlyByteBuf buf) {
        return PartFitment.readPlate(new FriendlyRaceBuf(buf));
    }

    public static void writeSetting(FriendlyByteBuf buf, PartFitment.Setting setting) {
        PartFitment.writeSetting(new FriendlyRaceBuf(buf), setting);
    }

    public static PartFitment.Setting readSetting(FriendlyByteBuf buf) {
        return PartFitment.readSetting(new FriendlyRaceBuf(buf));
    }
}
