package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

import java.util.List;
import java.util.function.Function;

/**
 * 押すたびに値が巡るボタン。バニラの {@code CycleButton} の代わり（作り方も {@link #builder} で同じ）。
 * 見た目はバニラのボタンのまま、文字は「名前: 値」（{@code displayOnlyValue} なら値だけ）。
 */
public final class UiCycleButton<T> extends UiButton {

    /** 値が変わった。 */
    public interface OnValueChange<T> {
        void onValueChange(UiCycleButton<T> button, T value);
    }

    private final RaceText name;
    private final List<T> values;
    private final Function<T, RaceText> text;
    private final Function<T, UiTooltip> tooltips;
    private final boolean onlyValue;
    private final OnValueChange<T> onChange;
    private int index;

    private UiCycleButton(int x, int y, int width, int height, RaceText name, List<T> values, int index,
                          Function<T, RaceText> text, Function<T, UiTooltip> tooltips, boolean onlyValue,
                          OnValueChange<T> onChange) {
        super(x, y, width, height, RaceText.empty(), button -> {
        });
        this.name = name;
        this.values = values;
        this.index = index;
        this.text = text;
        this.tooltips = tooltips;
        this.onlyValue = onlyValue;
        this.onChange = onChange;
        refresh();
    }

    @Override
    public void onPress() {
        index = (index + 1) % values.size();
        refresh();
        onChange.onValueChange(this, getValue());
    }

    public T getValue() {
        return values.get(index);
    }

    public void setValue(T value) {
        int found = values.indexOf(value);
        if (found >= 0) {
            index = found;
            refresh();
        }
    }

    private void refresh() {
        RaceText shown = text.apply(getValue());
        setMessage(onlyValue ? shown : RaceText.translatable("options.generic_value", name, shown));
        setTooltip(tooltips == null ? null : tooltips.apply(getValue()));
    }

    public static <T> Builder<T> builder(Function<T, RaceText> text) {
        return new Builder<>(text);
    }

    /** オン・オフ（{@code options.on}・{@code options.off}）。 */
    public static Builder<Boolean> onOffBuilder(boolean initial) {
        return booleanBuilder(RaceText.translatable("options.on"), RaceText.translatable("options.off"))
                .withInitialValue(initial);
    }

    public static Builder<Boolean> booleanBuilder(RaceText on, RaceText off) {
        return new Builder<Boolean>(value -> value ? on : off).withValues(List.of(Boolean.TRUE, Boolean.FALSE));
    }

    public static final class Builder<T> {
        private final Function<T, RaceText> text;
        private List<T> values = List.of();
        private T initial;
        private Function<T, UiTooltip> tooltips;
        private boolean onlyValue;

        private Builder(Function<T, RaceText> text) {
            this.text = text;
        }

        @SafeVarargs
        public final Builder<T> withValues(T... values) {
            return withValues(List.of(values));
        }

        public Builder<T> withValues(List<T> values) {
            this.values = List.copyOf(values);
            return this;
        }

        public Builder<T> withInitialValue(T value) {
            this.initial = value;
            return this;
        }

        public Builder<T> withTooltip(Function<T, UiTooltip> tooltips) {
            this.tooltips = tooltips;
            return this;
        }

        public Builder<T> displayOnlyValue() {
            this.onlyValue = true;
            return this;
        }

        public UiCycleButton<T> create(int x, int y, int width, int height, RaceText name) {
            return create(x, y, width, height, name, (button, value) -> {
            });
        }

        public UiCycleButton<T> create(int x, int y, int width, int height, RaceText name, OnValueChange<T> onChange) {
            int index = initial == null ? 0 : Math.max(0, values.indexOf(initial));
            return new UiCycleButton<>(x, y, width, height, name, values, index, text, tooltips, onlyValue, onChange);
        }
    }
}
