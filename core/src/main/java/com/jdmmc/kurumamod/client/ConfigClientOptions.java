package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.config.ConfigSpec;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * core の {@link ClientOptions} を {@link ClientConfig}（{@link ConfigSpec}）で実装する。
 *
 * <p>項目は {@link ClientConfig} の {@code public static final} の名前で引く。<b>リフレクションで引くのはこの MOD の
 * クラスだけ</b>（Minecraft の名前ではないので、Fabric の配布環境でも名前は変わらない）。3 版とも同じものを使う。</p>
 */
public final class ConfigClientOptions implements ClientOptions {

    private final Map<String, Object> specs = new HashMap<>();

    public ConfigClientOptions() {
        for (Field field : ClientConfig.class.getFields()) {
            if (ConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
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
        ConfigSpec.BooleanValue value = (ConfigSpec.BooleanValue) spec(name);
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
        ConfigSpec.DoubleValue value = (ConfigSpec.DoubleValue) spec(name);
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
                return value.getRange().getMin();
            }

            @Override
            public double max() {
                return value.getRange().getMax();
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> Value<E> choice(String name, Class<E> type) {
        ConfigSpec.EnumValue<E> value = (ConfigSpec.EnumValue<E>) spec(name);
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

    @Override
    public void refresh() {
        ClientConfig.refresh();
    }

    @Override
    public void save() {
        ClientConfig.SPEC.save();
    }
}
