package com.jdmmc.kurumamod.tuning;

import com.jdmmc.kurumamod.physics.CarSpec;
import org.jetbrains.annotations.Nullable;

/**
 * 調整画面でいじれる諸元 1 項目の定義。
 *
 * <p>「どう読むか・どう書くか・どの範囲か・どう表示するか」を 1 か所にまとめてあるので、
 * 項目を増やすときは {@link Tunables} に 1 行足すだけで、画面もパケットも自動で追従する。
 * 画面（クライアント）とパケット（サーバー）の両方から使うため共通コードに置く。</p>
 *
 * <p>範囲と表示は<b>表示単位</b>（度、km/h、cm など）で持ち、{@code unitScale} を掛けて
 * 内部単位（ラジアン、m/s、m）に直す。</p>
 */
public record TunableParameter(
        String name,
        double min,
        double max,
        Scale scale,
        double unitScale,
        String format,
        Getter getter,
        Setter setter,
        @Nullable Detail detail) {

    /** 諸元から値を読む。 */
    public interface Getter {
        double get(CarSpec spec);
    }

    /** 諸元へ値を書く。値は内部単位。 */
    public interface Setter {
        void set(CarSpec.Builder builder, double internalValue);
    }

    /** 数値だけでは硬さの体感が分からない項目に添える補足（沈み込み量など）。 */
    public interface Detail {
        String of(CarSpec spec);
    }

    /** スライダーの目盛りの割り当て方。 */
    public enum Scale {
        /** 等間隔。 */
        LINEAR,
        /**
         * 対数。バネ定数のように「2 倍」が意味を持つ量に使う。
         * 線形にすると変化の分かりやすい側が目盛りの端に潰れてしまう。
         */
        LOG
    }

    public static TunableParameter linear(String name, double min, double max, String format,
                                          Getter getter, Setter setter) {
        return new TunableParameter(name, min, max, Scale.LINEAR, 1.0, format, getter, setter, null);
    }

    public static TunableParameter log(String name, double min, double max, String format,
                                       Getter getter, Setter setter) {
        return new TunableParameter(name, min, max, Scale.LOG, 1.0, format, getter, setter, null);
    }

    /** 表示単位から内部単位への係数を設定する（度→ラジアンなら PI/180）。 */
    public TunableParameter unit(double unitScale) {
        return new TunableParameter(name, min, max, scale, unitScale, format, getter, setter, detail);
    }

    public TunableParameter detail(Detail detail) {
        return new TunableParameter(name, min, max, scale, unitScale, format, getter, setter, detail);
    }

    /** 項目名の翻訳キー。 */
    public String translationKey() {
        return "tuning.kurumamod." + name;
    }

    /** 値の書式の翻訳キー。名前と値を別に置くことで、画面側で列を分けて並べられる。 */
    public String valueKey() {
        return "tuning.kurumamod." + name + ".value";
    }

    // ------------------------------------------------------------------
    // 値の読み書き
    // ------------------------------------------------------------------

    /** 内部単位の値。パケットで送るのはこちら。 */
    public double internalValue(CarSpec spec) {
        return getter.get(spec);
    }

    public void applyInternal(CarSpec.Builder builder, double internalValue) {
        setter.set(builder, internalValue);
    }

    /** 表示単位の値。 */
    public double displayValue(CarSpec spec) {
        return getter.get(spec) / unitScale;
    }

    public void applyDisplay(CarSpec.Builder builder, double displayValue) {
        setter.set(builder, clampDisplay(displayValue) * unitScale);
    }

    /**
     * その項目が素の値のままか。
     *
     * <p><b>比べる相手は「いま乗っている車種の諸元」であって {@link CarSpec#DEFAULT} ではない。</b>
     * カーパックの車に乗ったとき、その車がもともと持っている値まで「変更済み」の色で
     * 出てしまうと、自分がいじった項目が分からなくなる。</p>
     */
    public boolean isDefault(CarSpec spec, CarSpec base) {
        return getter.get(spec) == getter.get(base);
    }

    // ------------------------------------------------------------------
    // スライダーの位置（0〜1）との相互変換
    // ------------------------------------------------------------------

    public double displayFromSlider(double fraction) {
        return switch (scale) {
            case LINEAR -> min + (max - min) * fraction;
            case LOG -> min * Math.pow(max / min, fraction);
        };
    }

    public double sliderFromDisplay(double displayValue) {
        double v = clampDisplay(displayValue);
        return switch (scale) {
            case LINEAR -> (v - min) / (max - min);
            case LOG -> Math.log(v / min) / Math.log(max / min);
        };
    }

    public double sliderPosition(CarSpec spec) {
        return sliderFromDisplay(displayValue(spec));
    }

    private double clampDisplay(double value) {
        return Math.max(min, Math.min(max, value));
    }

    /** ラベルに差し込む値。補足がある項目は 2 つ返す。 */
    public Object[] labelArguments(CarSpec spec) {
        String value = String.format(format, displayValue(spec));
        return detail == null ? new Object[]{value} : new Object[]{value, detail.of(spec)};
    }
}
