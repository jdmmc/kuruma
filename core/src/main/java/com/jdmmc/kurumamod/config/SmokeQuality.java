package com.jdmmc.kurumamod.config;

/**
 * 体積で描く煙の品質（H メニュー）。重さを決める 3 つをまとめて切り替える。
 *
 * <ul>
 *   <li>{@link #divisor} … 煙を描く画面の縮小率（一辺）。2 なら画素数は 4 分の 1。煙はぼやけた
 *       ものなので、縮小しても深度を見て拡大すれば輪郭は崩れない。重さはほぼこれで決まる</li>
 *   <li>{@link #steps} … 塊 1 つを視線に沿って何歩で刻むか。少ないと塊の中が層状に見える</li>
 *   <li>{@link #maxPuffs} … 同時に残しておける塊の数。超えると消えかけの塊から置き換わる</li>
 * </ul>
 *
 * <p>{@code client} パッケージに置けない理由は {@link SmokeStyle} と同じ。</p>
 */
public enum SmokeQuality {

    LOW("low", 3, 4, 500),
    MEDIUM("medium", 2, 6, 1000),
    HIGH("high", 2, 9, 1600),
    ULTRA("ultra", 1, 12, 2400);

    private final String id;
    private final int divisor;
    private final int steps;
    private final int maxPuffs;

    SmokeQuality(String id, int divisor, int steps, int maxPuffs) {
        this.id = id;
        this.divisor = divisor;
        this.steps = steps;
        this.maxPuffs = maxPuffs;
    }

    public int divisor() {
        return divisor;
    }

    public int steps() {
        return steps;
    }

    public int maxPuffs() {
        return maxPuffs;
    }

    public String translationKey() {
        return "config.kurumamod.smoke_quality." + id;
    }
}
