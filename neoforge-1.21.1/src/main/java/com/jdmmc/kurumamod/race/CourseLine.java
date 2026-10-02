package com.jdmmc.kurumamod.race;

import net.minecraft.nbt.CompoundTag;

/**
 * コース上に張られた 1 本の線。スタートライン、あるいは途中のチェックポイント。
 *
 * <p><b>点が線に近いかで判定してはいけない。</b>最高速では 1 ティックに 2.75 ブロック進むので、
 * 細い線なら簡単に飛び越す。前ティックの位置から今の位置までを<b>線分</b>と見て、
 * この線分との交差を調べる。こうすれば何ブロック飛んでも取りこぼさない。</p>
 *
 * <p>向き（{@code forwardX} / {@code forwardZ}）を持つのは、<b>線の上で前後して周回数を
 * 稼げないようにする</b>ため。8 の字コースでは、接点を北ループから通るときと南ループから
 * 通るときで方向が正反対になるので、片方向だけ数えればちょうど 1 周ぶんになる。</p>
 *
 * @param ax       端点 A の X
 * @param az       端点 A の Z
 * @param bx       端点 B の X
 * @param bz       端点 B の Z
 * @param yMin     判定する高さの下限。立体交差の上下を誤検出しないために要る
 * @param yMax     判定する高さの上限
 * @param forwardX 正しい通過方向の X 成分
 * @param forwardZ 正しい通過方向の Z 成分
 */
public record CourseLine(double ax, double az, double bx, double bz,
                         double yMin, double yMax,
                         double forwardX, double forwardZ) {

    /**
     * 端 2 点と、通過方向を向いたときの視線から線を作る。
     *
     * <p>2 点だけでは<b>どちら向きに跨いだら 1 周なのかが決まらない</b>（線はどちらからでも
     * 跨げる）ので、向きは別に与える。</p>
     *
     * @param yawDegrees 走る向きを向いたときのヨー角
     */
    public static CourseLine between(double ax, double az, double bx, double bz,
                                     double y, double height, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new CourseLine(ax, az, bx, bz, y - 2.0, y + height,
                -Math.sin(yaw), Math.cos(yaw));
    }

    /** 通過方向だけ引き直す。 */
    public CourseLine withDirection(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new CourseLine(ax, az, bx, bz, yMin, yMax, -Math.sin(yaw), Math.cos(yaw));
    }

    /** 線の長さ [ブロック]。 */
    public double length() {
        return Math.hypot(bx - ax, bz - az);
    }

    /**
     * プレイヤーの立ち位置と視線から線を作る。
     *
     * <p>視線に<b>垂直</b>な線を、立ち位置を中心に幅ぶん張る。視線がそのまま通過方向になるので、
     * 「コースを走る向きを向いて置く」だけで済む。</p>
     */
    public static CourseLine from(double x, double y, double z, float yawDegrees,
                                  double width, double height) {
        double yaw = Math.toRadians(yawDegrees);
        // Minecraft のヨー角 0 は +Z を向く
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        // 進行方向に垂直な向き
        double sideX = forwardZ;
        double sideZ = -forwardX;
        double half = width / 2.0;
        return new CourseLine(
                x + sideX * half, z + sideZ * half,
                x - sideX * half, z - sideZ * half,
                y - 2.0, y + height,
                forwardX, forwardZ);
    }

    /**
     * 前ティックの位置から今の位置までの移動が、この線を正しい向きに横切ったか。
     *
     * @return 横切っていれば移動線分上の位置（0〜1）、していなければ負の値。
     *         この比率でティックの中を補間できるので、50ms 刻みより細かく計れる
     */
    public double crossingFraction(double fromX, double fromZ, double toX, double toZ, double y) {
        if (y < yMin || y > yMax) {
            return -1.0;
        }
        double moveX = toX - fromX;
        double moveZ = toZ - fromZ;
        // 逆走で線を跨いでも数えない
        if (moveX * forwardX + moveZ * forwardZ <= 0.0) {
            return -1.0;
        }

        double lineX = bx - ax;
        double lineZ = bz - az;
        // 線分 A-B から見た、移動の始点と終点の側
        double d1 = lineX * (fromZ - az) - lineZ * (fromX - ax);
        double d2 = lineX * (toZ - az) - lineZ * (toX - ax);
        if (d1 * d2 >= 0.0) {
            return -1.0; // 線をまたいでいない
        }
        // 移動線分から見た、線の両端の側
        double d3 = moveX * (az - fromZ) - moveZ * (ax - fromX);
        double d4 = moveX * (bz - fromZ) - moveZ * (bx - fromX);
        if (d3 * d4 >= 0.0) {
            return -1.0; // 線の延長線上でまたいだだけ
        }
        return d1 / (d1 - d2);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("AX", ax);
        tag.putDouble("AZ", az);
        tag.putDouble("BX", bx);
        tag.putDouble("BZ", bz);
        tag.putDouble("YMin", yMin);
        tag.putDouble("YMax", yMax);
        tag.putDouble("FX", forwardX);
        tag.putDouble("FZ", forwardZ);
        return tag;
    }

    public static CourseLine load(CompoundTag tag) {
        return new CourseLine(
                tag.getDouble("AX"), tag.getDouble("AZ"),
                tag.getDouble("BX"), tag.getDouble("BZ"),
                tag.getDouble("YMin"), tag.getDouble("YMax"),
                tag.getDouble("FX"), tag.getDouble("FZ"));
    }
}
