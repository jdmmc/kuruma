package com.jdmmc.kurumamod.client.input;

/**
 * コントローラに割り当てられる操作。
 *
 * <p>既定は標準ゲームパッド配置（Xbox 系の並び）。SDL が持っている対応表で
 * ほとんどのコントローラがこの並びに正規化されるので、多くの場合そのまま使える。
 * 番号は保存形式を変えないため GLFW の並びのまま（{@link ControllerInput#AXIS_LEFT_X} など）。</p>
 *
 * <p><b>H シフターは段ごとに 1 つの操作</b>（{@link #GEAR_REVERSE}・{@link #GEAR_1}〜{@link #GEAR_7}）。
 * H シフターは段に入っている間そのボタンを押しっぱなしにする機器で、どれも押されていなければニュートラル。
 * 既定は未割り当て。</p>
 */
public enum ControllerAction {

    /** アクセル。既定は右トリガー。 */
    THROTTLE("throttle", ControllerBinding.axis(ControllerInput.AXIS_RIGHT_TRIGGER, false, false)),
    /** ブレーキ。既定は左トリガー。 */
    BRAKE("brake", ControllerBinding.axis(ControllerInput.AXIS_LEFT_TRIGGER, false, false)),
    /**
     * クラッチ。既定は未割り当て（割り当てなければクラッチは自動）。ハンコンのクラッチペダル向け。
     * 割り当てるとマニュアルの車で、ペダルの踏み込みがそのままクラッチの切れ具合になる。
     */
    CLUTCH("clutch", ControllerBinding.UNBOUND),
    /** ステア。既定は左スティックの横。 */
    STEER("steer", ControllerBinding.axis(ControllerInput.AXIS_LEFT_X, false, true)),
    /** シフトアップ。既定は R1。 */
    SHIFT_UP("shift_up", ControllerBinding.button(ControllerInput.BUTTON_RIGHT_BUMPER)),
    /** シフトダウン。既定は L1。 */
    SHIFT_DOWN("shift_down", ControllerBinding.button(ControllerInput.BUTTON_LEFT_BUMPER)),
    /** サイドブレーキ。既定は A（○）。 */
    HANDBRAKE("handbrake", ControllerBinding.button(ControllerInput.BUTTON_A)),

    GEAR_REVERSE("gear_reverse", -1),
    GEAR_1("gear_1", 1),
    GEAR_2("gear_2", 2),
    GEAR_3("gear_3", 3),
    GEAR_4("gear_4", 4),
    GEAR_5("gear_5", 5),
    GEAR_6("gear_6", 6),
    GEAR_7("gear_7", 7);

    /** 段の操作でないもの。 */
    private static final int NOT_GEAR = 0;

    private final String key;
    private final ControllerBinding defaultBinding;
    private final int gear;

    ControllerAction(String key, ControllerBinding defaultBinding) {
        this.key = key;
        this.defaultBinding = defaultBinding;
        this.gear = NOT_GEAR;
    }

    /** H シフターの段。既定は未割り当て。 */
    ControllerAction(String key, int gear) {
        this.key = key;
        this.defaultBinding = ControllerBinding.UNBOUND;
        this.gear = gear;
    }

    /** 保存と翻訳に使う識別子。 */
    public String key() {
        return key;
    }

    public ControllerBinding defaultBinding() {
        return defaultBinding;
    }

    public String translationKey() {
        return "controller.kurumamod." + key;
    }

    /** 軸として使う操作か。ボタンに割り当てても動くが、既定は軸。 */
    public boolean isAxis() {
        return this == THROTTLE || this == BRAKE || this == CLUTCH || this == STEER;
    }

    /** 押した瞬間の 1 回として扱う操作か。 */
    public boolean isPulse() {
        return this == SHIFT_UP || this == SHIFT_DOWN;
    }

    /** H シフターの段か。 */
    public boolean isGear() {
        return gear != NOT_GEAR;
    }

    /** H シフターの段（バックは -1）。段の操作でなければ 0。 */
    public int gear() {
        return gear;
    }
}
