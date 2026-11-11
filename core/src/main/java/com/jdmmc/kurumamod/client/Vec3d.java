package com.jdmmc.kurumamod.client;

/**
 * 3 次元のベクトル。メッシュの寸法を測るところ（{@link ObjMesh}）が使う。バニラの {@code Vec3} と同じ名前のメソッドと
 * フィールド（{@code x}・{@code y}・{@code z}）を持つ（写した計算をそのまま読めるように）。不変。
 */
public final class Vec3d {

    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    public final double x;
    public final double y;
    public final double z;

    public Vec3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public Vec3d add(Vec3d other) {
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    public Vec3d scale(double factor) {
        return new Vec3d(x * factor, y * factor, z * factor);
    }

    public Vec3d cross(Vec3d other) {
        return new Vec3d(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
    }

    public double dot(Vec3d other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSqr());
    }

    /** 長さ 1 に。長さが 1e-4 未満ならゼロ（バニラと同じ）。 */
    public Vec3d normalize() {
        double length = length();
        return length < 1.0e-4 ? ZERO : new Vec3d(x / length, y / length, z / length);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
