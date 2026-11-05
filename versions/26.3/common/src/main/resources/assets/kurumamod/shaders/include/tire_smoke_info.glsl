#ifndef KURUMAMOD_TIRE_SMOKE_INFO_GLSL
#define KURUMAMOD_TIRE_SMOKE_INFO_GLSL

// タイヤの煙（tire_smoke・tire_smoke_composite）が共有する値。Java 側は TireSmokeVolume#writeInfo が
// 同じ並びで詰める。<b>並びを変えたら両方を直すこと。</b>
// 26.3 は OpenGL でも Vulkan でも SPIR-V を経由するので、ばらの uniform は書けない（全部このブロックに入れる）。
layout(std140) uniform SmokeInfo {
    // カメラの向きだけ（平行移動なし）。座標はカメラ基準・世界の向き
    mat4 SmokeView;
    // xy = 煙を描く範囲の大きさ [px]（縮小した枠の左下）、z = 1 塊を刻む歩数、w = 形のノイズが出来ているか
    vec4 SmokeTarget;
    // xyz = 日（夜は月）の来る向き、w = バニラの霧を掛けるか（パック使用中は 0）
    vec4 SmokeSun;
    // rgb = 日（月）の光の色、w = 重ねるときの明るさの倍率（パック使用中だけ 1 以外）
    vec4 SmokeSunColor;
    // xy = 縮小した枠そのものの大きさ [px]、zw = 重ね先の大きさ [px]
    vec4 SmokeSizes;
    // xyz = カメラのいるブロック、w = 未使用（ヘッドライトのランプの位置をこのフレームのカメラ基準へ直すため）
    vec4 SmokeCameraBlock;
    // xyz = そのブロックの中でのカメラの位置、w = 未使用
    vec4 SmokeCameraFrac;
};

#define TargetSize (SmokeTarget.xy)
#define Steps (int(SmokeTarget.z + 0.5))
#define NoiseReady (SmokeTarget.w)
#define SunDir (SmokeSun.xyz)
#define FogEnabled (SmokeSun.w)
#define SunColor (SmokeSunColor.rgb)
#define Brightness (SmokeSunColor.w)
#define LowSize (SmokeSizes.xy)
#define OutSize (SmokeSizes.zw)

// 深度の値を、射影の逆行列に掛けられる正規化座標の z へ。26.3 の深度は逆向き（手前ほど大きく、空が 0）
float smokeDeviceZ(float depth) {
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    return depth;
#else
    return depth * 2.0 - 1.0;
#endif
}

bool smokeIsSky(float depth) {
    return depth <= 0.0;
}

#endif
