package com.jdmmc.kurumamod.client.light;

import com.jdmmc.kurumamod.car.CarCore;
import com.jdmmc.kurumamod.client.Vec3d;
import com.jdmmc.kurumamod.physics.Wheel;

import java.util.List;

/**
 * ヘッドライト 2 灯ぶんの位置と光軸、それに面が受ける照度。Minecraft に依存しない
 * （描くのは版の {@code CarLights}。あちらの {@code Beam} がこれを包んで、描画用に {@code Vec3} で持つ）。
 *
 * <p>光軸は<b>車体の前方を {@link HeadlightBeam#AIM_DOWN} だけ下げたもの</b>。レンズの平均法線から
 * 取ろうとしたことがあるが、箱で作ると法線が打ち消し合って向きが定まらない。</p>
 *
 * <p>計算はバニラの {@code Vec3} と同じ式（{@link Vec3d} はバニラと同じ動きに写してある）。</p>
 */
public final class HeadlightAim {

    public final List<Vec3d> lamps;
    public final Vec3d mid;
    public final Vec3d axis;
    public final Vec3d right;
    public final Vec3d up;

    private HeadlightAim(List<Vec3d> lamps, Vec3d axis, Vec3d right, Vec3d up) {
        this.lamps = lamps;
        Vec3d sum = Vec3d.ZERO;
        for (Vec3d lamp : lamps) {
            sum = sum.add(lamp);
        }
        this.mid = lamps.isEmpty() ? Vec3d.ZERO : sum.scale(1.0 / lamps.size());
        this.axis = axis;
        this.right = right;
        this.up = up;
    }

    /**
     * @param suspensionPitch サスの沈み込みで生じた前後の傾き [rad]。正で鼻上げ。
     *                        {@link HeadlightBeam#LEVELING} の割合だけ打ち消す（{@link #suspensionPitch}）
     */
    public static HeadlightAim of(List<Vec3d> lamps, Vec3d forward, Vec3d bodyUp, double suspensionPitch) {
        Vec3d right = forward.cross(bodyUp).normalize();
        Vec3d up = right.cross(forward).normalize();
        // 下げる角度 = 取り付けの下向き + 沈み込みの戻し（鼻下げなら上へ戻す）
        // 調整画面（Ctrl+L、HeadlightScreen）で動かせる。既定は AIM_DOWN と LEVELING と同じ値
        double aim = HeadlightBeam.aimDown(suspensionPitch);
        double cos = Math.cos(aim);
        double sin = Math.sin(aim);
        Vec3d axis = forward.scale(cos).subtract(up.scale(sin)).normalize();
        Vec3d beamUp = up.scale(cos).add(forward.scale(sin)).normalize();
        return new HeadlightAim(lamps, axis, right, beamUp);
    }

    /**
     * 点 (x, y, z) の面（法線 n）が受ける照度。2 灯の合計。
     *
     * <p>配光 × 入射角の余弦 ÷ 距離²。<b>カットオフより上は照らさない</b>ので、
     * 壁には光の上端の線が出て、遠くの路面は光軸が当たる手前で自然に途切れる。</p>
     */
    public double illuminance(double x, double y, double z, double nx, double ny, double nz) {
        double total = 0.0;
        for (Vec3d lamp : lamps) {
            double vx = x - lamp.x();
            double vy = y - lamp.y();
            double vz = z - lamp.z();
            double dist2 = vx * vx + vy * vy + vz * vz;
            if (dist2 < 0.01) {
                continue;
            }
            double along = vx * axis.x() + vy * axis.y() + vz * axis.z();
            if (along <= 0.05) {
                continue;
            }
            double cosIncidence = -(vx * nx + vy * ny + vz * nz) / Math.sqrt(dist2);
            if (cosIncidence <= 0.0) {
                continue;
            }
            double tx = (vx * right.x() + vy * right.y() + vz * right.z()) / along;
            double ty = (vx * up.x() + vy * up.y() + vz * up.z()) / along;
            total += HeadlightBeam.pattern(tx, ty) * cosIncidence / dist2;
        }
        return total;
    }

    /**
     * サスペンションの沈み込みで生じた前後の傾き [rad]。正で鼻上げ。
     *
     * <p>車体の姿勢（ピッチ）をそのまま使ってはいけない。あちらは坂の傾きも含むので、
     * 打ち消すと坂道で光が路面に沿わなくなる。前後のサスの長さの差なら、路面に対する
     * 車体の傾きだけが取れる——実車のオートレベライザーが車高センサーで測っているのと同じ量。</p>
     */
    public static double suspensionPitch(CarCore car, float partialTick) {
        double front = 0.0;
        double rear = 0.0;
        for (Wheel wheel : Wheel.VALUES) {
            double length = car.getRenderSuspensionLength(wheel, partialTick);
            if (wheel.isFront()) {
                front += length * 0.5;
            } else {
                rear += length * 0.5;
            }
        }
        return Math.atan2(front - rear, car.spec().wheelBase());
    }
}
