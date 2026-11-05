package com.jdmmc.kurumamod.client.hook;

import net.minecraft.client.Camera;

/**
 * カメラの向きを決める場面。NeoForge の {@code ViewportEvent.ComputeCameraAngles} と同じ名前のメソッドを持つ。
 *
 * <p>{@code Camera#setup} の中で、乗っている実体の向きでカメラを回したあと、位置を決める前に作られる
 * （NeoForge と同じ位置）。ハンドラが書き換えた角度がそのままカメラの向きになる。</p>
 *
 * <p><b>roll（画面の傾き）は素の Minecraft 1.21.1 のカメラには無い。</b>NeoForge が足したもので、
 * Fabric 版は mixin で同じ計算をカメラの回転に足す。</p>
 */
public final class CameraAngles {

    private final Camera camera;
    private final double partialTick;
    private float yaw;
    private float pitch;
    private float roll;

    public CameraAngles(Camera camera, double partialTick, float yaw, float pitch, float roll) {
        this.camera = camera;
        this.partialTick = partialTick;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
    }

    public Camera getCamera() {
        return camera;
    }

    public double getPartialTick() {
        return partialTick;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public float getRoll() {
        return roll;
    }

    public void setRoll(float roll) {
        this.roll = roll;
    }
}
