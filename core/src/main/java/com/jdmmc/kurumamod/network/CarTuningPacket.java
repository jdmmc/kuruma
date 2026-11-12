package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;

import java.util.List;

/**
 * 運転クライアント → サーバー。調整画面でいじった諸元をサーバー側の車へも反映させる。
 *
 * <p>運転中の挙動はクライアントが解くのでこれが無くても変わらないが、降りた後は
 * サーバーが物理を引き継ぐため、送っておかないと降りた瞬間に車が跳ねる。</p>
 *
 * <p>中身は {@link Tunables#ALL} の順に並べた値だけ。項目を増やしても
 * このクラスは触らなくてよい代わりに、<b>両側で項目の並びが一致している前提</b>に立っている。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param values   {@link Tunables#ALL} と同じ順に並べた内部単位の値
 */
public record CarTuningPacket(int entityId, double[] values) implements RacePayload {

    public static CarTuningPacket of(int entityId, CarSpec spec) {
        List<TunableParameter> parameters = Tunables.ALL;
        double[] values = new double[parameters.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = parameters.get(i).internalValue(spec);
        }
        return new CarTuningPacket(entityId, values);
    }

    public CarTuningPacket(RaceBuf buf) {
        this(buf.readVarInt(), readValues(buf));
    }

    private static double[] readValues(RaceBuf buf) {
        int count = buf.readVarInt();
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readDouble();
        }
        return values;
    }

    public void encode(RaceBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(values.length);
        for (double value : values) {
            buf.writeDouble(value);
        }
    }

}
