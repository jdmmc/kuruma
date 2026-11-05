#ifndef KURUMAMOD_HEADLIGHT_INFO_GLSL
#define KURUMAMOD_HEADLIGHT_INFO_GLSL

// ヘッドライトの画素ごとの光（headlight_volume）と影の地図（headlight_shadow）が共有する値。
// Java 側は HeadlightVolume#writeInfo が同じ並びで詰める。<b>並びを変えたら両方を直すこと。</b>
// 26.3 は OpenGL でも Vulkan でも SPIR-V を経由するので、ばらの uniform は書けない（全部このブロックに入れる）。
// タイヤの煙のシェーダーも、ランプと影を読むときはこのファイルを include する。
layout(std140) uniform HeadlightInfo {
    // 表示している視点の回転の逆（カメラを原点にした世界の向きへ戻す）
    mat4 ViewInverse;
    // xyz = カメラのいるブロック、w = 灯数
    vec4 InfoCamera;
    // xyz = そのブロックの中でのカメラの位置、w = 光の届く距離 [m]
    vec4 InfoFrac;
    // xy = 影の格子の大きさ（水平、高さ）、zw = 距離の地図の大きさ
    vec4 InfoGrid;
    // xy = 画面の大きさ、z = 霧の濃さ、w = PackMode
    vec4 InfoScreen;
    // 路面の倍率、ビームの倍率、煙の倍率、パック使用中の輝度のコントラスト
    vec4 InfoGain;
    // パック使用中の色かぶりの取り方、パック使用中に足す光の量、未使用 2 つ
    vec4 InfoPack;
    // rgb = 光の色
    vec4 InfoLight;
};

#define LampCount (int(InfoCamera.w + 0.5))
#define CameraBlock (InfoCamera.xyz)
#define CameraFrac (InfoFrac.xyz)
#define LightRange (InfoFrac.w)
#define GridSize (InfoGrid.xy)
#define ShadowSize (InfoGrid.zw)
#define ScreenSizePx (InfoScreen.xy)
#define FogDensity (InfoScreen.z)
#define PackMode (InfoScreen.w)
#define SurfaceGain (InfoGain.x)
#define VolumeGain (InfoGain.y)
#define SmokeGain (InfoGain.z)
#define PackContrast (InfoGain.w)
#define PackTint (InfoPack.x)
#define PackAdd (InfoPack.y)
#define LightColor (InfoLight.rgb)

// 頂点シェーダーはブロックだけを使う（HEADLIGHT_INFO_BLOCK_ONLY を定義してから include する）。
// 使わないサンプラーを頂点の段に出さないため
#ifndef HEADLIGHT_INFO_BLOCK_ONLY

// ランプは LampSampler の 1 行 = 1 灯（4 画素 = 行列の 4 列）。数の上限はテクスチャの高さだけ。
// 列 0 = 位置（カメラ基準）と届く距離、列 1 = 光軸と強さ、列 2 = 右、列 3 = 上（w が 1 ならナンバー灯）
uniform sampler2D LampSampler;

mat4 lamp(int i) {
    return mat4(texelFetch(LampSampler, ivec2(0, i), 0),
                texelFetch(LampSampler, ivec2(1, i), 0),
                texelFetch(LampSampler, ivec2(2, i), 0),
                texelFetch(LampSampler, ivec2(3, i), 0));
}

// ---- 影の格子（HeadlightShadows） ----
// 範囲（正接で横 ±1.3・縦 -0.45〜0.15）は距離の地図の描き方と読み方で同じ値
const float TX_MIN = -1.3;
const float TX_MAX = 1.3;
const float TY_MIN = -0.45;
const float TY_MAX = 0.15;

uniform sampler2D VoxelSampler;

int wrapGrid(int v, int n) {
    int m = v - (v / n) * n;
    return m < 0 ? m + n : m;
}

// ブロックが光を遮る箱の、ブロック内での下端（xyz）と上端（xyz）。遮らなければ上端 ≦ 下端。
// 詰め方は HeadlightShadows#occlusion
void occluderBox(ivec3 local, out vec3 lo, out vec3 hi) {
    lo = vec3(0.0);
    hi = vec3(0.0);
    int n = int(GridSize.x);
    int ny = int(GridSize.y);
    if (local.x < -n / 2 || local.x >= n / 2 || local.z < -n / 2 || local.z >= n / 2
            || local.y < -ny / 2 || local.y >= ny / 2) {
        return;
    }
    ivec3 world = local + ivec3(CameraBlock);
    ivec2 texel = ivec2(wrapGrid(world.x, n) + wrapGrid(world.y, ny) * n, wrapGrid(world.z, n));
    vec4 v = texelFetch(VoxelSampler, texel, 0);
    int bx = int(v.b * 255.0 + 0.5);
    int bz = int(v.a * 255.0 + 0.5);
    lo = vec3(float(bx / 16) / 16.0, v.r, float(bz / 16) / 16.0);
    hi = vec3(float(bx - (bx / 16) * 16 + 1) / 16.0, v.g, float(bz - (bz / 16) * 16 + 1) / 16.0);
}

// 光線が t0..t1 でブロック cell を通るとき、遮る箱に入る距離。入らなければ -1
float boxHit(ivec3 cell, vec3 origin, vec3 invDir, float t0, float t1) {
    vec3 lo;
    vec3 hi;
    occluderBox(cell, lo, hi);
    if (hi.y <= lo.y) {
        return -1.0;
    }
    vec3 a = (vec3(cell) + lo - origin) * invDir;
    vec3 b = (vec3(cell) + hi - origin) * invDir;
    vec3 tNear = min(a, b);
    vec3 tFar = max(a, b);
    float enter = max(max(tNear.x, tNear.y), max(tNear.z, t0));
    float exit = min(min(tFar.x, tFar.y), min(tFar.z, t1));
    return enter <= exit ? enter : -1.0;
}

// 向きの逆数（成分が 0 でも割り算で飛ばさない）
vec3 inverseDir(vec3 dir) {
    return vec3(
            1.0 / (abs(dir.x) > 1.0e-6 ? dir.x : 1.0e-6),
            1.0 / (abs(dir.y) > 1.0e-6 ? dir.y : 1.0e-6),
            1.0 / (abs(dir.z) > 1.0e-6 ? dir.z : 1.0e-6));
}

// 距離の地図の最大の距離 = 格子の半分（96m 以下の届く距離では 48m）
float shadowMaxDistance() {
    return GridSize.x * 0.5;
}

#endif // HEADLIGHT_INFO_BLOCK_ONLY

#endif
