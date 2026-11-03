package com.jdmmc.kurumamod.client;

/**
 * タイヤの煙の形を崩す 3D ノイズ（端がつながる立方体）。Minecraft に依存しない。
 *
 * <p>煙の塊（{@link SmokePuffs}）は球のままだと<b>丸い玉が並んでいるだけ</b>に見える。シェーダー
 * （{@code tire_smoke.fsh}）がこのノイズで縁を削り、綿のような形にする。毎画素ハッシュで
 * ノイズを作ると重いので、起動後に一度だけ作ってテクスチャで渡す。</p>
 *
 * <ul>
 *   <li>R … Perlin-Worley。大きな「もこもこ」（雲の作り方の定番。Perlin の連続した起伏を Worley の
 *       泡の形で引き締める）</li>
 *   <li>G・B・A … Worley の fbm を 3 つの細かさで。縁を泡の形に削るのに使う</li>
 * </ul>
 *
 * <p><b>端がつながっていること</b>（{@code GL_REPEAT} で敷き詰めても継ぎ目が出ない）。格子の番号を
 * 周期で折り返して作る。</p>
 */
public final class SmokeNoise {

    private SmokeNoise() {
    }

    /** 一辺 {@code size} の RGBA8 を、x が最も速く変わる順で返す。 */
    public static byte[] generate(int size) {
        byte[] out = new byte[size * size * size * 4];
        int o = 0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double u = (x + 0.5) / size;
                    double v = (y + 0.5) / size;
                    double w = (z + 0.5) / size;
                    double perlin = 0.0;
                    double amplitude = 1.0;
                    double total = 0.0;
                    for (int octave = 0; octave < 3; octave++) {
                        int period = 4 << octave;
                        perlin += amplitude * perlin(u * period, v * period, w * period, period);
                        total += amplitude;
                        amplitude *= 0.5;
                    }
                    // -1..1 をおよそ 0..1 へ
                    perlin = clamp(perlin / total * 0.75 + 0.5);
                    double w4 = worley(u, v, w, 4);
                    double w8 = worley(u, v, w, 8);
                    double w16 = worley(u, v, w, 16);
                    double w32 = worley(u, v, w, 32);
                    double w64 = worley(u, v, w, 64);
                    double fbm4 = w4 * 0.625 + w8 * 0.25 + w16 * 0.125;
                    double fbm8 = w8 * 0.625 + w16 * 0.25 + w32 * 0.125;
                    double fbm16 = w16 * 0.625 + w32 * 0.25 + w64 * 0.125;
                    // Perlin の起伏を、Worley の泡で下から持ち上げる（泡の縁で途切れる）
                    double perlinWorley = clamp(remap(perlin, fbm4 - 1.0, 1.0, 0.0, 1.0));
                    out[o++] = toByte(perlinWorley);
                    out[o++] = toByte(fbm4);
                    out[o++] = toByte(fbm8);
                    out[o++] = toByte(fbm16);
                }
            }
        }
        return out;
    }

    private static double remap(double value, double fromLow, double fromHigh, double toLow, double toHigh) {
        return toLow + (value - fromLow) / Math.max(fromHigh - fromLow, 1.0e-6) * (toHigh - toLow);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static byte toByte(double value) {
        return (byte) Math.round(clamp(value) * 255.0);
    }

    /** 格子点の番号から 0..1 の乱数（同じ番号なら同じ値）。 */
    private static double hash(int x, int y, int z, int salt) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177 + salt * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0x00FFFFFF) / (double) 0x01000000;
    }

    private static int wrap(int value, int period) {
        int m = value % period;
        return m < 0 ? m + period : m;
    }

    /** 端がつながる勾配ノイズ（-1..1 前後）。 */
    private static double perlin(double x, double y, double z, int period) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;
        double sx = fade(fx);
        double sy = fade(fy);
        double sz = fade(fz);
        double result = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            int cx = corner & 1;
            int cy = (corner >> 1) & 1;
            int cz = (corner >> 2) & 1;
            int gx = wrap(x0 + cx, period);
            int gy = wrap(y0 + cy, period);
            int gz = wrap(z0 + cz, period);
            // 単位球の上の勾配
            double theta = hash(gx, gy, gz, 1) * Math.PI * 2.0;
            double cosPhi = hash(gx, gy, gz, 2) * 2.0 - 1.0;
            double sinPhi = Math.sqrt(1.0 - cosPhi * cosPhi);
            double dx = fx - cx;
            double dy = fy - cy;
            double dz = fz - cz;
            double dot = (Math.cos(theta) * sinPhi) * dx + (Math.sin(theta) * sinPhi) * dy + cosPhi * dz;
            double weight = (cx == 1 ? sx : 1.0 - sx) * (cy == 1 ? sy : 1.0 - sy) * (cz == 1 ? sz : 1.0 - sz);
            result += dot * weight;
        }
        // 3 次元の勾配ノイズは ±0.87 程度なので広げる
        return result * 1.15;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    /** 端がつながる Worley（細胞）ノイズ。細胞の中心ほど 1、境目ほど 0。 */
    private static double worley(double u, double v, double w, int cells) {
        double x = u * cells;
        double y = v * cells;
        double z = w * cells;
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        double nearest = Double.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int gx = cx + dx;
                    int gy = cy + dy;
                    int gz = cz + dz;
                    int hx = wrap(gx, cells);
                    int hy = wrap(gy, cells);
                    int hz = wrap(gz, cells);
                    double px = gx + hash(hx, hy, hz, 11 + cells);
                    double py = gy + hash(hx, hy, hz, 23 + cells);
                    double pz = gz + hash(hx, hy, hz, 37 + cells);
                    double ex = px - x;
                    double ey = py - y;
                    double ez = pz - z;
                    double d2 = ex * ex + ey * ey + ez * ez;
                    if (d2 < nearest) {
                        nearest = d2;
                    }
                }
            }
        }
        return clamp(1.0 - Math.sqrt(nearest));
    }
}
