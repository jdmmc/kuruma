package com.jdmmc.kurumamod.client;

import org.lwjgl.glfw.GLFW;

/**
 * コントローラに割り当てられる操作。
 *
 * <p>既定は GLFW の標準ゲームパッド配置（Xbox 系の並び）。GLFW が持っている対応表で
 * ほとんどのコントローラがこの並びに正規化されるので、多くの場合そのまま使える。</p>
 */
public enum ControllerAction {

    /** アクセル。既定は右トリガー。 */
    THROTTLE("throttle", ControllerBinding.axis(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, false, false)),
    /** ブレーキ。既定は左トリガー。 */
    BRAKE("brake", ControllerBinding.axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER, false, false)),
    /** ステア。既定は左スティックの横。 */
    STEER("steer", ControllerBinding.axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X, false, true)),
    /** シフトアップ。既定は R1。 */
    SHIFT_UP("shift_up", ControllerBinding.button(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER)),
    /** シフトダウン。既定は L1。 */
    SHIFT_DOWN("shift_down", ControllerBinding.button(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER)),
    /** サイドブレーキ。既定は A（○）。 */
    HANDBRAKE("handbrake", ControllerBinding.button(GLFW.GLFW_GAMEPAD_BUTTON_A));

    private final String key;
    private final ControllerBinding defaultBinding;

    ControllerAction(String key, ControllerBinding defaultBinding) {
        this.key = key;
        this.defaultBinding = defaultBinding;
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
        return this == THROTTLE || this == BRAKE || this == STEER;
    }

    /** 押した瞬間の 1 回として扱う操作か。 */
    public boolean isPulse() {
        return this == SHIFT_UP || this == SHIFT_DOWN;
    }
}
