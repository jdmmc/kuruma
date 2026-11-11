package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * core の {@link ClientOptions} を {@link ClientConfig}（{@link ForgeConfigSpec}）で実装する。
 *
 * <p>項目は {@link ClientConfig} の {@code public static final} の名前で引く。<b>リフレクションで引くのはこの MOD の
 * クラスだけ</b>（Minecraft の名前ではないので、Fabric の配布環境でも名前は変わらない）。</p>
 */
final class McClientOptions implements ClientOptions {

    private final Map<String, Object> specs = new HashMap<>();

    McClientOptions() {
        for (Field field : ClientConfig.class.getFields()) {
            if (ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                try {
                    specs.put(field.getName(), field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private Object spec(String name) {
        Object spec = specs.get(name);
        if (spec == null) {
            throw new IllegalArgumentException("ClientConfig に無い項目です: " + name);
        }
        return spec;
    }

    @Override
    public Value<Boolean> bool(String name) {
        ForgeConfigSpec.BooleanValue value = (ForgeConfigSpec.BooleanValue) spec(name);
        return new Value<>() {
            @Override
            public Boolean get() {
                return value.get();
            }

            @Override
            public void set(Boolean v) {
                value.set(v);
            }

            @Override
            public Boolean getDefault() {
                return value.getDefault();
            }
        };
    }

    @Override
    public Number number(String name) {
        ForgeConfigSpec.DoubleValue value = (ForgeConfigSpec.DoubleValue) spec(name);
        return new Number() {
            @Override
            public Double get() {
                return value.get();
            }

            @Override
            public void set(Double v) {
                value.set(v);
            }

            @Override
            public Double getDefault() {
                return value.getDefault();
            }

            @Override
            public double min() {
                return (Double) range(value).getMin();
            }

            @Override
            public double max() {
                return (Double) range(value).getMax();
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> Value<E> choice(String name, Class<E> type) {
        ForgeConfigSpec.EnumValue<E> value = (ForgeConfigSpec.EnumValue<E>) spec(name);
        return new Value<>() {
            @Override
            public E get() {
                return value.get();
            }

            @Override
            public void set(E v) {
                value.set(v);
            }

            @Override
            public E getDefault() {
                return value.getDefault();
            }
        };
    }

    /** 1.20.1 の Forge は範囲を値ではなく spec の側に持つ。 */
    private static ForgeConfigSpec.Range<?> range(ForgeConfigSpec.ConfigValue<?> value) {
        return ((ForgeConfigSpec.ValueSpec) ClientConfig.SPEC.getSpec().get(value.getPath())).getRange();
    }

    @Override
    public void refresh() {
        ClientConfig.refresh();
    }

    @Override
    public void save() {
        ClientConfig.SPEC.save();
    }
}
