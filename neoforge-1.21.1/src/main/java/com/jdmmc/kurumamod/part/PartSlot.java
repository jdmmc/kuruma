package com.jdmmc.kurumamod.part;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 部品を付ける場所。<b>1 つの場所には 1 つしか付かない。</b>
 *
 * <p>車種（{@code CarType}）が「どの車か」を表すのに対し、こちらは「その車に何を履かせたか」。
 * 装着状態は {@link PartFitment} が持ち、選べる部品の一覧は {@link CarParts} が持つ。</p>
 *
 * <p><b>保存にも通信にも {@link #getSerializedName()} を使う（序数ではない）。</b>
 * 列挙子を並べ替えたり間に足したりしたときに、保存済みの車の装着が別の場所へずれないため。</p>
 */
public enum PartSlot {

    /** ホイール。見た目だけ。メッシュにタイヤが付属していれば、タイヤを選ばない限りそれが出る。 */
    WHEEL("wheel", null),
    /**
     * タイヤ。<b>走りが変わる</b>（部品の {@code tire} が剛性と路面ごとのグリップを決める）。
     * 何も付けていなければノーマル（すべて 1 倍）で、見た目はホイールに付属のタイヤ。
     */
    TIRE("tire", null),
    /**
     * ターボ。<b>走りが変わる</b>（部品の {@code turbo} がトルクと立ち上がりを決める）。
     * 何も付けていなければ自然吸気。
     */
    TURBO("turbo", null),
    /**
     * ブローオフバルブ。<b>音だけ</b>（アクセルを抜いたときの「プシュッ」）。
     * ターボが付いているときだけ意味があるので、換装画面もそのときだけ出す。
     */
    BLOWOFF("blowoff", TURBO),
    /*
     * ここから下は<b>車体の形を変える部品</b>（{@link #isBodyPart()}）。見た目は別のメッシュではなく
     * 車体の OBJ の中のオブジェクト（{@code part_<部品のパス>}）を出し入れするので、
     * <b>その形を持っている車にしか並ばない</b>（タブごと出ない）。部品の {@code aero} を書けば
     * ダウンフォースが足され、書かなければ見た目だけの部品になる。
     */
    /** フロントバンパー、リップ、スプリッター、カナード。ふつう前軸に効く。 */
    FRONT_BUMPER("front_bumper", null),
    /** サイドスカート。 */
    SIDE_SKIRT("side_skirt", null),
    /** オーバーフェンダー、ワイドフェンダー。ふつうは見た目だけ。 */
    FENDER("fender", null),
    /** リアスポイラー、ウイング、ダックテール。ふつう後軸に効く。 */
    REAR_WING("rear_wing", null),
    /** リアバンパー、ディフューザー。ふつう後軸に効く。 */
    REAR_DIFFUSER("rear_diffuser", null);

    public static final PartSlot[] VALUES = values();

    private final String name;
    /** この場所が意味を持つのに要る場所。ブローオフはターボが付いていないと鳴らない。 */
    @Nullable
    private final PartSlot requires;

    PartSlot(String name, @Nullable PartSlot requires) {
        this.name = name;
        this.requires = requires;
    }

    /** この場所が意味を持つのに付いていなければならない場所。無ければ null。 */
    @Nullable
    public PartSlot requires() {
        return requires;
    }

    /**
     * 何も付けていないときの呼び名の翻訳キー。
     *
     * <p>ホイールは「純正」（車種の既定の見た目）、タイヤは「ノーマル」（既定の性能）だが、
     * ターボとブローオフは「なし」——付いていないのが素の状態だから。</p>
     */
    public String emptyKey() {
        return switch (this) {
            // 車体の部品は、外すと純正の形（初期装備があればそれ）に戻る
            case WHEEL, FRONT_BUMPER, SIDE_SKIRT, FENDER, REAR_WING, REAR_DIFFUSER -> "screen.kurumamod.part.stock";
            case TIRE -> "screen.kurumamod.part.tire_normal";
            default -> "screen.kurumamod.part.none";
        };
    }

    /** 見た目を車体の OBJ の中のオブジェクトで持つ場所か（別のメッシュを持たない）。 */
    public boolean isBodyPart() {
        return switch (this) {
            case FRONT_BUMPER, SIDE_SKIRT, FENDER, REAR_WING, REAR_DIFFUSER -> true;
            default -> false;
        };
    }

    public String getSerializedName() {
        return name;
    }

    /** 画面に出す名前。 */
    public Component displayName() {
        return Component.translatable("part_slot.kurumamod." + name);
    }

    /** 知らない名前なら null。<b>捨てるか無視するかは呼ぶ側が決める。</b> */
    @Nullable
    public static PartSlot byName(String name) {
        for (PartSlot slot : VALUES) {
            if (slot.name.equals(name)) {
                return slot;
            }
        }
        return null;
    }
}
