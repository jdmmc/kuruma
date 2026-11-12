package com.jdmmc.kurumamod.config;

/**
 * タイヤの煙の描き方（H メニュー）。
 *
 * <p><b>{@code client} パッケージには置けない。</b>{@code ClientConfig} が参照する以上、spec を登録する
 * 専用サーバー側でもこのクラスが読み込まれるため（{@code GaugeTheme} と同じ事情）。設定ファイルには
 * 名前で書かれるので、並べ替えても保存済みの設定は壊れない。</p>
 */
public enum SmokeStyle {

    /** 煙の塊を体積として画素ごとに解く（{@code TireSmokeVolume}）。既定。 */
    VOLUMETRIC("volumetric"),
    /** 以前の粒（{@code DustParticle#TIRE_SMOKE}）。軽い。シェーダーが使えないときもこれに落ちる。 */
    PARTICLES("particles");

    private final String id;

    SmokeStyle(String id) {
        this.id = id;
    }

    public String translationKey() {
        return "config.kurumamod.smoke_style." + id;
    }
}
