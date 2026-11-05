package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.surface.RoadSurface;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * タイヤの煙を<b>体積として</b>描く仕組み（1.21.1 版）の、26.3 での受け口。
 *
 * <h2>26.3 では体積の煙を描いていない</h2>
 *
 * <p>1.21.1 では、煙の塊を球として持ち、縮小した画面へ自前のシェーダー（{@code tire_smoke.vsh/fsh}）で
 * 画素ごとに刻んで描き、主画面へ重ねていた（深度の写し・ヘッドライトの影の地図・自前のフレームバッファを
 * OpenGL で直接扱う）。26.3 の描画（{@code com.mojang.renderpearl}。OpenGL と Vulkan を切り替えられる）では
 * そのままでは動かないので、<b>作り直すまでは {@link #usable()} が常に false</b>。呼ぶ側
 * （{@link CarSmoke}・{@link CarDust}）は、そのとき粒の煙・土煙を出す（H メニューで「粒」を選んだときと同じ）。</p>
 *
 * <p>作り直すときの手本は {@code versions/1.21.1/common/.../client/TireSmokeVolume.java}（塊の動かし方・
 * タイヤの熱はそのまま使える）と {@code core/src/main/resources/assets/kurumamod/shaders/core/tire_smoke*}。</p>
 */
public final class TireSmokeVolume {

    private TireSmokeVolume() {
    }

    /** いま体積の煙で描けるか。<b>26.3 では常に false</b>（呼ぶ側は粒を出す）。 */
    static boolean usable() {
        return false;
    }

    /** 輪 1 つの煙の強さ。体積の煙を描かないので常に 0（出さない）。 */
    static double strength(int stream, double drive) {
        return 0.0;
    }

    /** 体積の煙を 1 ティックぶん出す。26.3 では何もしない（{@link #usable()} が false なので呼ばれない）。 */
    static void emit(ClientLevel level, int stream, double x, double y, double z,
                     double stepX, double stepY, double stepZ,
                     double kickX, double kickZ, double outwardX, double outwardZ, double intensity) {
    }

    /** 体積の砂塵を 1 ティックぶん出す。26.3 では何もしない。 */
    static void emitDust(ClientLevel level, int stream, RoadSurface surface, double x, double y, double z,
                         double stepX, double stepY, double stepZ, double kickX, double kickZ, double intensity) {
    }

    public static void onLoggingIn() {
    }

    public static void onLoggingOut() {
    }

    public static void onClientTick() {
    }

    /** シェーダーパックの仕上げの直前（{@code IrisPipelineMixin}）。26.3 では何も描かない。 */
    public static void renderBeforePackComposite() {
    }
}
