package com.jdmmc.kurumamod.surface;

/**
 * 路面の種類と、そこから決まる走りへの効き。
 *
 * <p><b>舗装路（{@link #PAVED}）が基準の 1.0。</b>他の路面はそこから下げる形で表す。
 * ブロックの種類を調べても該当しなければ舗装路として扱うので、<b>他 MOD の道路ブロックは
 * 何の設定もなしにアスファルトとして走れる</b>。</p>
 *
 * <p>グリップと転がり抵抗を分けてあるのは、「滑りやすい」と「進めづらい」が別の感覚だから。
 * 砂は両方悪いが、氷は<b>グリップが極端に低いのに転がり抵抗は舗装より低い</b>（だから
 * 滑って止まらない）。1 つの数字では表せない。</p>
 *
 * <p>Minecraft に依存しない。ブロックとの対応づけは {@link SurfaceLookup} の仕事。</p>
 */
public enum RoadSurface {

    // 判定はこの順に行う。先に書いたものが優先されるので、細かいものから並べる
    /** 氷。滑るが、転がり抵抗は低いので止まらない。 */
    ICE("ice", 0.15, 0.5, 1.0),
    /** 雪。 */
    SNOW("snow", 0.45, 6.0, 1.0),
    /**
     * 砂。乾いているとサラサラで沈むが、<b>濡れると締まって走りやすくなる</b>
     * （海岸の波打ち際が走れるのと同じ）ので、濡れ倍率だけ 1 を超える。
     */
    SAND("sand", 0.55, 10.0, 1.10),
    /** 砂利。 */
    GRAVEL("gravel", 0.65, 3.0, 0.85),
    /** 土・草。濡れると泥になる。 */
    DIRT("dirt", 0.75, 4.0, 0.55),
    /** 舗装路。既定であり基準。タグは引かない。 */
    PAVED("paved", 1.0, 1.0, 0.70);

    private final String name;
    private final double gripScale;
    private final double rollingScale;
    private final double wetGripScale;

    RoadSurface(String name, double gripScale, double rollingScale, double wetGripScale) {
        this.name = name;
        this.gripScale = gripScale;
        this.rollingScale = rollingScale;
        this.wetGripScale = wetGripScale;
    }

    private static final RoadSurface[] VALUES = values();

    /** 並び順から引く。同期では 1 バイトで送るため。範囲外は舗装路。 */
    public static RoadSurface byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : PAVED;
    }

    /** タグ名と翻訳キーに使う識別子。 */
    public String surfaceName() {
        return name;
    }

    /** 乾いているときのグリップ倍率。 */
    public double gripScale() {
        return gripScale;
    }

    /** 転がり抵抗の倍率。濡れても変えていない。 */
    public double rollingScale() {
        return rollingScale;
    }

    /**
     * 濡れ具合を織り込んだグリップ倍率。
     *
     * @param wetness 0 で乾いている、1 で濡れきっている
     */
    public double gripScale(double wetness) {
        double clamped = Math.max(0.0, Math.min(1.0, wetness));
        return gripScale * (1.0 + (wetGripScale - 1.0) * clamped);
    }

    /** 表示用の翻訳キー。 */
    public String translationKey() {
        return "surface.kurumamod." + name;
    }
}
