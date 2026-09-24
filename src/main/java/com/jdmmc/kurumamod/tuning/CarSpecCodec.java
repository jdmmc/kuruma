package com.jdmmc.kurumamod.tuning;

import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 諸元の書き出しと読み込み。
 *
 * <p>中身は {@link Tunables#ALL} の値だけで、載っていない諸元（{@code wheelRadius} など）は
 * 既定値のままになる。調整画面で変えられない値なので、これで足りる。</p>
 *
 * <p><b>ネットワークは並び順、保存は項目名。</b>通信は送り手と受け手が必ず同じ版なので
 * 並べるだけでよいが、保存したデータは後の版で読むことになるので、項目を増やしても
 * 壊れないよう名前で引く。知らない名前は捨て、無い名前は既定値のままにする。</p>
 */
public final class CarSpecCodec {

    private CarSpecCodec() {
    }

    // ------------------------------------------------------------------
    // ネットワーク（並び順）
    // ------------------------------------------------------------------

    public static void write(FriendlyByteBuf buf, CarSpec spec) {
        List<TunableParameter> parameters = Tunables.ALL;
        buf.writeVarInt(parameters.size());
        for (TunableParameter parameter : parameters) {
            buf.writeDouble(parameter.internalValue(spec));
        }
    }

    public static CarSpec read(FriendlyByteBuf buf) {
        List<TunableParameter> parameters = Tunables.ALL;
        int count = buf.readVarInt();
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readDouble();
        }
        return apply(values);
    }

    /**
     * {@link Tunables#ALL} と同じ順に並んだ値を諸元へ組み立てる。
     *
     * <p>個数が合わなければ既定の諸元を返す。中途半端に適用すると、ずれた値が
     * 別の項目へ入って挙動が壊れるため。</p>
     */
    public static CarSpec apply(double[] values) {
        List<TunableParameter> parameters = Tunables.ALL;
        if (values.length != parameters.size()) {
            return CarSpec.DEFAULT;
        }
        CarSpec.Builder builder = CarSpec.DEFAULT.toBuilder();
        for (int i = 0; i < values.length; i++) {
            parameters.get(i).applyInternal(builder, values[i]);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------
    // 保存（項目名）
    // ------------------------------------------------------------------

    public static CompoundTag save(CarSpec spec) {
        CompoundTag tag = new CompoundTag();
        for (TunableParameter parameter : Tunables.ALL) {
            tag.putDouble(parameter.name(), parameter.internalValue(spec));
        }
        return tag;
    }

    public static CarSpec load(CompoundTag tag) {
        CarSpec.Builder builder = CarSpec.DEFAULT.toBuilder();
        for (TunableParameter parameter : Tunables.ALL) {
            // 無い項目は既定値のまま。項目を増やした後で古いデータを読んでもここで吸収される
            if (tag.contains(parameter.name())) {
                parameter.applyInternal(builder, tag.getDouble(parameter.name()));
            }
        }
        return Tunables.migrateLegacy(builder.build(),
                name -> tag.contains(name) ? tag.getDouble(name) : null);
    }

    // ------------------------------------------------------------------
    // プリセット（項目名 → 内部単位の値）
    // ------------------------------------------------------------------

    /**
     * 項目名をキーにした表へ書き出す。プリセットの保存に使う。
     *
     * <p>NBT と同じく<b>名前で引く</b>。保存したプリセットは項目を増やした後の版でも
     * 読むことになるため。</p>
     */
    public static Map<String, Double> toMap(CarSpec spec) {
        Map<String, Double> values = new TreeMap<>();
        for (TunableParameter parameter : Tunables.ALL) {
            values.put(parameter.name(), parameter.internalValue(spec));
        }
        return values;
    }

    /**
     * 項目名をキーにした表から諸元を組み立てる。
     *
     * <p><b>載っていない項目は既定値のまま。</b>項目を増やした後で古いプリセットを
     * 読んでもここで吸収される（{@code wheel_radius} を足す前に保存されたプリセットは、
     * タイヤ半径が既定値になる）。知らない名前は捨てる。</p>
     */
    public static CarSpec fromMap(Map<String, Double> values) {
        CarSpec.Builder builder = CarSpec.DEFAULT.toBuilder();
        for (TunableParameter parameter : Tunables.ALL) {
            Double value = values.get(parameter.name());
            if (value != null) {
                parameter.applyInternal(builder, value);
            }
        }
        return Tunables.migrateLegacy(builder.build(), values::get);
    }
}
