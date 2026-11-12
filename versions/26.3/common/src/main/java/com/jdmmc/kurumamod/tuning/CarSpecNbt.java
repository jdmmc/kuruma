package com.jdmmc.kurumamod.tuning;

import com.jdmmc.kurumamod.platform.FriendlyRaceBuf;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.race.NbtRaceTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/** core の {@link CarSpecCodec} を NBT とパケットに包む口。書き方は core のまま（項目名と並びは前と同じ）。 */
public final class CarSpecNbt {

    private CarSpecNbt() {
    }

    public static CompoundTag save(CarSpec spec) {
        CompoundTag tag = new CompoundTag();
        CarSpecCodec.save(new NbtRaceTag(tag), spec);
        return tag;
    }

    public static CarSpec load(CompoundTag tag) {
        return CarSpecCodec.load(new NbtRaceTag(tag));
    }

    public static void write(FriendlyByteBuf buf, CarSpec spec) {
        CarSpecCodec.write(new FriendlyRaceBuf(buf), spec);
    }

    public static CarSpec read(FriendlyByteBuf buf) {
        return CarSpecCodec.read(new FriendlyRaceBuf(buf));
    }
}
