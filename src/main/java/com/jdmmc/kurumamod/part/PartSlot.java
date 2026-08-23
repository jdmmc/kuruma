package com.jdmmc.kurumamod.part;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 部品を付ける場所。<b>1 つの場所には 1 つしか付かない。</b>
 *
 * <p>車種（{@code CarType}）が「どの車か」を表すのに対し、こちらは「その車に何を履かせたか」。
 * 装着状態は {@link PartFitment} が持ち、選べる部品の一覧は {@link CarParts} が持つ。</p>
 *
 * <p><b>保存にも通信にも {@link #getSerializedName()} を使う（序数ではない）。</b>
 * 列挙子を並べ替えたり間に足したりしたときに、保存済みの車の装着が別の場所へずれないため。</p>
 */
public enum PartSlot {

    /** ホイール。タイヤの見た目そのもの。 */
    WHEEL("wheel");

    public static final PartSlot[] VALUES = values();

    private final String name;

    PartSlot(String name) {
        this.name = name;
    }

    public String getSerializedName() {
        return name;
    }

    /** 画面に出す名前。 */
    public Component displayName() {
        return Component.translatable("part_slot.kurumamod." + name);
    }

    /** 知らない名前なら null。<b>捨てるか無視するかは呼ぶ側が決める。</b> */
    @Nullable
    public static PartSlot byName(String name) {
        for (PartSlot slot : VALUES) {
            if (slot.name.equals(name)) {
                return slot;
            }
        }
        return null;
    }
}
