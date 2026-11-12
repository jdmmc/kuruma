package com.jdmmc.kurumamod.config;

import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.tuning.TunableParameter;

import java.util.Locale;

/**
 * 速さを画面に出す単位（H メニューの「速さの単位」。既定は km/h）。
 *
 * <p>走っている速さ（速度計・最高速）も、性能を比べる基準の速さ（「200 km/h で 85 kg」のダウンフォースなど）も、
 * この単位で出す。<b>測る速さそのもの（物理の条件）は変えない</b>——mph にすると基準の 200 km/h は「124 mph」と出る。</p>
 */
public enum SpeedUnit {
    KMH(3.6, "hud.kurumamod.speed", "hud.kurumamod.kmh"),
    MPH(3.6 / 1.609344, "hud.kurumamod.speed_mph", "hud.kurumamod.mph");

    /** m/s に掛ける倍率。 */
    private final double perMetersPerSecond;
    /** 「%s km/h」の形の文言。 */
    private final String valueKey;
    /** 単位だけの文言（速度計の文字盤に出す）。 */
    private final String unitKey;

    SpeedUnit(double perMetersPerSecond, String valueKey, String unitKey) {
        this.perMetersPerSecond = perMetersPerSecond;
        this.valueKey = valueKey;
        this.unitKey = unitKey;
    }

    /** いま選ばれている単位。 */
    public static SpeedUnit current() {
        return ClientConfig.speedUnit;
    }

    /** m/s をこの単位の数へ。 */
    public double fromMetersPerSecond(double metersPerSecond) {
        return metersPerSecond * perMetersPerSecond;
    }

    /** 単位だけ（「km/h」「mph」）。 */
    public RaceText unit() {
        return RaceText.translatable(unitKey);
    }

    /** 設定の選択肢の名前。 */
    public String translationKey() {
        return "config.kurumamod.speed_unit." + name().toLowerCase(Locale.ROOT);
    }

    private String number(double metersPerSecond) {
        return String.format("%.0f", fromMetersPerSecond(metersPerSecond));
    }

    /** いまの単位で「255 km/h」「158 mph」。 */
    public static RaceText text(double metersPerSecond) {
        SpeedUnit unit = current();
        return RaceText.translatable(unit.valueKey, unit.number(metersPerSecond));
    }

    /** 同じものを、調整画面の補足（{@link TunableParameter.Text}）の引数として。 */
    public static TunableParameter.Text detail(double metersPerSecond) {
        SpeedUnit unit = current();
        return new TunableParameter.Text(unit.valueKey, unit.number(metersPerSecond));
    }

    /** km/h で決めてある基準の速さ（100 km/h・200 km/h）を、いまの単位で。 */
    public static RaceText reference(double kmh) {
        return text(kmh / 3.6);
    }
}
