package com.jdmmc.kurumamod.config;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 設定ファイル（TOML）の読み書き。NeoForge の {@code ModConfigSpec} のうち、この MOD が使っている分だけを
 * <b>同じ名前で</b>持たせてある（{@code define}・{@code defineInRange}・{@code defineEnum}・{@code get}・{@code set}・
 * {@code getDefault}・{@code save}）。NeoForge 版と Fabric 版の両方がこれを使う。
 *
 * <p><b>ローダーの設定の仕組みを使わない理由。</b>Fabric には設定の仕組みが無く、両方で同じ設定画面
 * （{@code KurumaMenuScreen}）を使うには、値の型を揃える必要があるため。ファイル名・キー名・書き方は
 * NeoForge が書いていたものと同じなので、それまでの {@code config/kurumamod-client.toml} がそのまま読める。</p>
 *
 * <p>読むのは平らな {@code key = value} だけ（真偽・数・文字列・文字列の配列）。表（{@code [section]}）は
 * 使っていないので読まない。読めない値・範囲を外れた値は、NeoForge と同じく既定値か範囲の端へ直し、
 * 直したらファイルを書き直す。<b>ゲーム中にファイルを書き換えても読み直さない</b>（読み直しを当てにすると、
 * 片側だけ値が変わったまま気づけない。値を変えたい側が {@code set} して {@code save} する）。</p>
 */
public final class ConfigSpec {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<ConfigValue<?>> values;
    private Path file;

    private ConfigSpec(List<ConfigValue<?>> values) {
        this.values = List.copyOf(values);
    }

    /**
     * {@code file} を読む。無ければ既定値で書く。直した値があれば書き直す。以後の {@link #save()} もこのファイルへ。
     */
    public void load(Path file) {
        this.file = file;
        Map<String, String> raw = new LinkedHashMap<>();
        boolean needsSave = !Files.exists(file);
        if (!needsSave) {
            try {
                raw = TomlLite.read(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("設定ファイル {} を読めなかった。既定値で書き直す", file, e);
                needsSave = true;
            }
        }
        for (ConfigValue<?> value : values) {
            String text = raw.get(value.key);
            if (text == null) {
                value.reset();
                needsSave = true;
            } else if (!value.parse(text)) {
                LOGGER.warn("設定 {} の値 {} を読めない（{}）。直して書き直す", value.key, text, file.getFileName());
                needsSave = true;
            }
        }
        if (needsSave) {
            save();
        }
    }

    /** いまの値をファイルへ書く。{@link #load(Path)} より前に呼ばれたら何もしない。 */
    public void save() {
        if (file == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        for (ConfigValue<?> value : values) {
            for (String line : value.comment) {
                out.append('#').append(line).append('\n');
            }
            for (String line : value.hints()) {
                out.append(line).append('\n');
            }
            out.append(value.key).append(" = ").append(value.format()).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("設定ファイル {} を書けなかった", file, e);
        }
    }

    /** 設定の並びを作る。{@code ModConfigSpec.Builder} と同じ書き方。 */
    public static final class Builder {

        private final List<ConfigValue<?>> values = new ArrayList<>();
        private List<String> pendingComment = List.of();

        /** 次に定義する値の説明。複数行なら 1 行ずつ渡す。 */
        public Builder comment(String... lines) {
            pendingComment = List.of(lines);
            return this;
        }

        public BooleanValue define(String key, boolean defaultValue) {
            return add(new BooleanValue(key, takeComment(), defaultValue));
        }

        public ConfigValue<String> define(String key, String defaultValue) {
            return add(new StringValue(key, takeComment(), defaultValue));
        }

        public DoubleValue defineInRange(String key, double defaultValue, double min, double max) {
            return add(new DoubleValue(key, takeComment(), defaultValue, min, max));
        }

        public IntValue defineInRange(String key, int defaultValue, int min, int max) {
            return add(new IntValue(key, takeComment(), defaultValue, min, max));
        }

        public <E extends Enum<E>> EnumValue<E> defineEnum(String key, E defaultValue) {
            return add(new EnumValue<>(key, takeComment(), defaultValue,
                    List.of(defaultValue.getDeclaringClass().getEnumConstants())));
        }

        /** 選べる値を絞った列挙（{@code acceptable} に無い値は既定値へ直す）。 */
        @SafeVarargs
        public final <E extends Enum<E>> EnumValue<E> defineEnum(String key, E defaultValue, E... acceptable) {
            return add(new EnumValue<>(key, takeComment(), defaultValue, List.of(acceptable)));
        }

        /** 文字列の配列。{@code validator} を通らない要素は捨てる。 */
        public ConfigValue<List<? extends String>> defineListAllowEmpty(String key, List<? extends String> defaultValue,
                                                                     Predicate<Object> validator) {
            return add(new ListValue(key, takeComment(), defaultValue, validator));
        }

        public ConfigSpec build() {
            return new ConfigSpec(values);
        }

        private List<String> takeComment() {
            List<String> comment = pendingComment;
            pendingComment = List.of();
            return comment;
        }

        private <V extends ConfigValue<?>> V add(V value) {
            for (ConfigValue<?> existing : values) {
                if (existing.key.equals(value.key)) {
                    throw new IllegalArgumentException("設定のキーが重なっている: " + value.key);
                }
            }
            values.add(value);
            return value;
        }
    }

    /** 設定の値 1 つ。 */
    public abstract static class ConfigValue<T> {

        final String key;
        final List<String> comment;
        private final T defaultValue;
        private T value;

        ConfigValue(String key, List<String> comment, T defaultValue) {
            this.key = key;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        /** 値を変える。<b>ファイルへは書かない</b>（書くのは {@link ConfigSpec#save()}）。範囲の外なら端へ寄せる。 */
        public void set(T newValue) {
            this.value = correct(newValue);
        }

        public T getDefault() {
            return defaultValue;
        }

        /** NeoForge の {@code getPath()} と同じく、キーを 1 要素の一覧で返す。 */
        public List<String> getPath() {
            return List.of(key);
        }

        void reset() {
            value = defaultValue;
        }

        /** ファイルの文字列を読む。読めて直す必要が無ければ true（直したら false を返し、書き直してもらう）。 */
        abstract boolean parse(String text);

        abstract String format();

        /** 説明の後ろ・値の前に書く補足の行（既定値・範囲・選べる値）。NeoForge が書いていたのと同じ形。 */
        List<String> hints() {
            return List.of();
        }

        T correct(T candidate) {
            return candidate;
        }

        final boolean accept(T parsed) {
            T corrected = correct(parsed);
            value = corrected;
            return corrected.equals(parsed);
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {

        BooleanValue(String key, List<String> comment, boolean defaultValue) {
            super(key, comment, defaultValue);
        }

        @Override
        boolean parse(String text) {
            if (text.equals("true") || text.equals("false")) {
                return accept(Boolean.parseBoolean(text));
            }
            reset();
            return false;
        }

        @Override
        String format() {
            return get().toString();
        }
    }

    public static final class StringValue extends ConfigValue<String> {

        StringValue(String key, List<String> comment, String defaultValue) {
            super(key, comment, defaultValue);
        }

        @Override
        boolean parse(String text) {
            String parsed = TomlLite.parseString(text);
            if (parsed == null) {
                reset();
                return false;
            }
            return accept(parsed);
        }

        @Override
        String format() {
            return TomlLite.quote(get());
        }
    }

    /** 範囲の両端。{@code ModConfigSpec.Range} と同じ名前のメソッドを持つ。 */
    public record Range<T extends Comparable<T>>(T min, T max) {

        public T getMin() {
            return min;
        }

        public T getMax() {
            return max;
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {

        private final Range<Double> range;

        DoubleValue(String key, List<String> comment, double defaultValue, double min, double max) {
            super(key, comment, defaultValue);
            this.range = new Range<>(min, max);
        }

        public Range<Double> getRange() {
            return range;
        }

        @Override
        boolean parse(String text) {
            try {
                return accept(Double.parseDouble(text));
            } catch (NumberFormatException e) {
                reset();
                return false;
            }
        }

        @Override
        String format() {
            return get().toString();
        }

        @Override
        List<String> hints() {
            String upper = range.max() == Double.MAX_VALUE ? "> " + range.min() : range.min() + " ~ " + range.max();
            return List.of("# Default: " + getDefault(), "# Range: " + upper);
        }

        @Override
        Double correct(Double candidate) {
            if (candidate == null || candidate.isNaN()) {
                return getDefault();
            }
            return Math.max(range.min(), Math.min(range.max(), candidate));
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {

        private final Range<Integer> range;

        IntValue(String key, List<String> comment, int defaultValue, int min, int max) {
            super(key, comment, defaultValue);
            this.range = new Range<>(min, max);
        }

        public Range<Integer> getRange() {
            return range;
        }

        @Override
        boolean parse(String text) {
            try {
                return accept(Integer.parseInt(text));
            } catch (NumberFormatException e) {
                reset();
                return false;
            }
        }

        @Override
        String format() {
            return get().toString();
        }

        @Override
        List<String> hints() {
            String upper = range.max() == Integer.MAX_VALUE ? "> " + range.min() : range.min() + " ~ " + range.max();
            return List.of("# Default: " + getDefault(), "# Range: " + upper);
        }

        @Override
        Integer correct(Integer candidate) {
            if (candidate == null) {
                return getDefault();
            }
            return Math.max(range.min(), Math.min(range.max(), candidate));
        }
    }

    public static final class EnumValue<E extends Enum<E>> extends ConfigValue<E> {

        /** 選べる値。並びはファイルの「Allowed Values」に書く順。 */
        private final List<E> allowed;

        EnumValue(String key, List<String> comment, E defaultValue, List<E> allowed) {
            super(key, comment, defaultValue);
            this.allowed = List.copyOf(allowed);
        }

        @Override
        boolean parse(String text) {
            String name = TomlLite.parseString(text);
            if (name != null) {
                for (E constant : allowed) {
                    if (constant.name().equalsIgnoreCase(name)) {
                        return accept(constant) && constant.name().equals(name);
                    }
                }
            }
            reset();
            return false;
        }

        @Override
        String format() {
            return TomlLite.quote(get().name());
        }

        @Override
        List<String> hints() {
            StringBuilder hint = new StringBuilder("#Allowed Values: ");
            for (int i = 0; i < allowed.size(); i++) {
                hint.append(i == 0 ? "" : ", ").append(allowed.get(i).name());
            }
            return List.of(hint.toString());
        }

        @Override
        E correct(E candidate) {
            return candidate == null || !allowed.contains(candidate) ? getDefault() : candidate;
        }
    }

    public static final class ListValue extends ConfigValue<List<? extends String>> {

        private final Predicate<Object> validator;

        ListValue(String key, List<String> comment, List<? extends String> defaultValue, Predicate<Object> validator) {
            super(key, comment, List.copyOf(defaultValue));
            this.validator = validator;
        }

        @Override
        boolean parse(String text) {
            List<String> parsed = TomlLite.parseStringList(text);
            if (parsed == null) {
                reset();
                return false;
            }
            return accept(parsed);
        }

        @Override
        String format() {
            StringBuilder out = new StringBuilder("[");
            List<? extends String> list = get();
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(TomlLite.quote(list.get(i)));
            }
            return out.append(']').toString();
        }

        @Override
        List<? extends String> correct(List<? extends String> candidate) {
            if (candidate == null) {
                return getDefault();
            }
            List<String> kept = new ArrayList<>();
            for (String item : candidate) {
                if (validator.test(item)) {
                    kept.add(item);
                }
            }
            return List.copyOf(kept);
        }
    }
}
