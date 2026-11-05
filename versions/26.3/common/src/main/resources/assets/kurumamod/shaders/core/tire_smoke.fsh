#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <kurumamod:tire_smoke_info.glsl>

// タイヤの煙の塊（パフ）1 つを、体積として解く。
//
// 視線が塊（球）を通る区間を数歩で刻み、濃さ（球の形 × 3D ノイズで崩した綿の形）を積んで
// 透けぐあいと、各点がカメラへ散らす光を足していく。描く先は低い解像度の写し（前掛けの α
// ＝色に α を掛けた形）で、塊は奥から手前の順に「上へ重ねる」形で積まれる。
//
// 光は 3 つ:
//  - 周りの明るさ … バニラのライトマップ（塊ごと。tire_smoke.vsh）。上ほど明るく、下は少し暗い
//  - 日（月）の光 … 前方散乱（日の方を向くほど明るい・逆光で縁が光る）と、日の方向へ塊を
//    抜けるまでの濃さで暗くする自己影。多重散乱の近似として、薄く伸ばした影も足す
//  - ヘッドライト … 塊ごと（tire_smoke.vsh。26.3 ではまだつないでいないので 0）
//
// 地面や車と交わるところは、深度から求めた面までで刻むのをやめる。煙が地面に柔らかく沈む
//
// 26.3 で変わったところ:
//  - 3D テクスチャが無い（renderpearl が扱わない）ので、形のノイズ 64³ は 64 枚の断面を 8×8 に並べた
//    2D の絵（NoiseSampler）で持ち、隣り合う 2 枚を混ぜて引く（noise3）
//  - 深度は逆向き（reversed-Z）。空が 0
//  - 霧はバニラの Fog のブロック（地形と同じ霧）

uniform sampler2D DepthSampler;
uniform sampler2D NoiseSampler;

layout(location = 0) flat in vec4 vCenter;
layout(location = 1) flat in vec4 vMedium;
layout(location = 2) flat in vec4 vAlbedo;
layout(location = 3) flat in vec4 vAmbient;
layout(location = 4) flat in vec3 vSky;
layout(location = 5) flat in vec3 vLamp;
layout(location = 6) flat in vec4 vInv0;
layout(location = 7) flat in vec4 vInv1;
layout(location = 8) flat in vec4 vInv2;
layout(location = 9) flat in vec4 vInv3;

layout(location = 0) out vec4 fragColor;

#define InvViewProj (mat4(vInv0, vInv1, vInv2, vInv3))

// 周りの明るさのうち、どれだけを素直に乗せるか（残りは日の光の向きで決まる）
const float AMBIENT_SHARE = 0.68;
// 日の光の強さ。真横から見て、日の当たる側がおよそ周りの明るさと同じになる量
const float SUN_SHARE = 0.75;
// 前方・後方散乱（Henyey-Greenstein）の g と混ぜる割合
const float FORWARD_G = 0.45;
const float BACK_G = -0.2;
const float FORWARD_WEIGHT = 0.6;
// 影の中にも光が回り込むぶん（多重散乱の近似）。濃さを薄めた影をこれだけ足す
const float MULTI_SCATTER = 0.35;
const float MULTI_SCATTER_SOFTEN = 0.25;
// 綿の形を作るノイズの細かさ（塊の半径あたり）
const float NOISE_SCALE = 0.8;
const float DETAIL_SCALE = 2.3;
// 輪郭がほどけきるまでの秒数と、ほどける割合
const float SOFTEN_SECONDS = 4.5;
const float SOFTEN_MAX = 0.7;
// ドメインワープの強さ（ノイズの座標をどれだけゆがめるか）
const float WARP = 0.35;
// 細かい泡で削る強さ
const float EROSION = 0.5;
// ノイズで削ったぶん薄くなるので、平均の濃さを戻す
const float DENSITY_GAIN = 1.6;

// ---- 形のノイズ（TireSmokeVolume#noiseAtlas と同じ並び） ----
// 一辺 64 の立方体を、z の断面 64 枚として 8×8 に並べる。1 枚は縁に 1 画素ずつ反対側を写した 66×66 で、
// 断面の中の線形補間が縁で途切れない（1.21.1 の GL_REPEAT と同じに繋がる）
const float NOISE_SIZE = 64.0;
const float NOISE_TILE = 66.0;
const float NOISE_ATLAS = 528.0;
const int NOISE_TILES_PER_ROW = 8;

vec2 noiseTileOrigin(int slice) {
    return vec2(float(slice - (slice / NOISE_TILES_PER_ROW) * NOISE_TILES_PER_ROW),
                float(slice / NOISE_TILES_PER_ROW)) * NOISE_TILE;
}

// p は 1 で一周する座標（1.21.1 の 3D テクスチャの座標と同じ）
vec4 noise3(vec3 p) {
    vec3 f = fract(p) * NOISE_SIZE;
    float zf = f.z - 0.5;
    float z0 = floor(zf);
    float w = zf - z0;
    int s0 = int(z0) < 0 ? int(z0) + 64 : int(z0);
    int s1 = s0 + 1 >= 64 ? 0 : s0 + 1;
    vec2 inTile = f.xy + 1.0;
    vec4 a = textureLod(NoiseSampler, (noiseTileOrigin(s0) + inTile) / NOISE_ATLAS, 0.0);
    vec4 b = textureLod(NoiseSampler, (noiseTileOrigin(s1) + inTile) / NOISE_ATLAS, 0.0);
    return mix(a, b, w);
}

float interleavedGradientNoise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float hg(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-4), 1.5);
}

// 球の中の点 q（半径 1 に揃えた座標）から、向き dir へ球を出るまでの長さ（半径 1 あたり）
float exitLength(vec3 q, vec3 dir) {
    float b = dot(q, dir);
    float c = dot(q, q) - 1.0;
    return max(-b + sqrt(max(b * b - c, 0.0)), 0.0);
}

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    vec2 ndc = uv * 2.0 - 1.0;
    // 視線の向き。視線上のどこか 1 点（深度 0.5）を戻せばよい
    vec4 farPoint = InvViewProj * vec4(ndc, smokeDeviceZ(0.5), 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);

    vec3 c = vCenter.xyz;
    float r = vCenter.w;
    float b = dot(c, dir);
    float miss = dot(c, c) - b * b;
    if (miss >= r * r) {
        discard;
    }
    float chord = sqrt(r * r - miss);
    float t0 = max(b - chord, 0.0);
    float t1 = b + chord;
    float depth = textureLod(DepthSampler, uv, 0.0).r;
    if (!smokeIsSky(depth)) {
        vec4 surface = InvViewProj * vec4(ndc, smokeDeviceZ(depth), 1.0);
        t1 = min(t1, length(surface.xyz / surface.w));
    }
    if (t1 <= t0) {
        discard;
    }

    float sigma = vMedium.x;
    float age = vMedium.y;
    float seed = vMedium.z;
    vec3 noiseOffset = vec3(seed * 17.31, seed * 5.13, seed * 11.77);
    // 塊ごとにノイズの向きを縦軸まわりに回す（同じ模様の塊が並ばないように）
    float turn = seed * 6.2831853;
    mat2 spin = mat2(cos(turn), sin(turn), -sin(turn), cos(turn));
    // 塊の中で綿がゆっくり湧き上がる（ノイズの座標を下へずらす = 模様が昇る）。速さも塊ごとに違う
    float boilSpeed = 0.05 + 0.07 * fract(seed * 7.13);
    vec3 boil = vec3(0.0, -age * boilSpeed, age * 0.03);
    float squash = 1.0 + vAlbedo.a;
    // 出たては綿のようにもこもこ、SOFTEN_SECONDS かけて輪郭のない霞へ
    float soften = smoothstep(0.3, SOFTEN_SECONDS, age);

    float cosSun = dot(dir, SunDir);
    float phaseSun = mix(hg(cosSun, BACK_G), hg(cosSun, FORWARD_G), FORWARD_WEIGHT);
    vec3 sunLight = SunColor * (vAmbient.a * SUN_SHARE * phaseSun);

    // 歩数は「視線が塊を通る長さ」と「塊の濃さ」に合わせて減らす。縁をかすめる視線や、薄くなった古い塊は
    // 少ない歩数で足りる（重さは画面を覆う塊の数 × 歩数で決まる）
    float through = (t1 - t0) / (2.0 * r);
    float thickness = clamp(sigma * r * 1.2, 0.35, 1.0);
    int steps = clamp(int(ceil(float(Steps) * through * thickness)), 2, max(Steps, 2));
    float dt = (t1 - t0) / float(steps);
    // 刻みの出だしを画素ごとにずらす。塊ごとにずらし方も変える（同じ模様だと、何十枚も重なった帯の中で模様が
    // 強め合い、斜めの網目が見えた）
    float jitter = fract(interleavedGradientNoise(gl_FragCoord.xy) + seed * 0.618034 * 13.0);
    // 歩数を落としたとき（煙の中・重ね塗りの予算超え）は、ずらしが粗い網目に見えるので真ん中へ寄せる。
    // 煙の中は一様な霧なので、ずらさなくても縞は出ない
    jitter = mix(0.5, jitter, clamp(float(steps - 2) / 3.0, 0.0, 1.0));
    float transmittance = 1.0;
    vec3 light = vec3(0.0);
    for (int s = 0; s < steps; s++) {
        float t = t0 + (float(s) + jitter) * dt;
        vec3 q = (dir * t - c) / r;
        // 古い煙ほど縦に潰れて横へ広がる
        vec3 qs = vec3(q.x, q.y * squash, q.z);
        float d2 = dot(qs, qs);
        if (d2 >= 1.0) {
            continue;
        }
        float shape = 1.0 - d2;
        shape *= shape;
        float density = shape;
        if (NoiseReady > 0.5) {
            vec3 qn = vec3(spin * qs.xz, qs.y).xzy;
            vec4 m = noise3(qn * (NOISE_SCALE * DETAIL_SCALE) + noiseOffset.zxy - boil * 1.7);
            // 細かいノイズで大きな塊の座標をゆがめる（ドメインワープ）。渦を巻いたような、ちぎれた形になる
            vec3 warp = (m.gba - 0.5) * WARP;
            vec4 n = noise3(qn * NOISE_SCALE + noiseOffset + boil + warp);
            // 雲の作り方の定番。球の形を「被覆率」として大きな塊（R）を下から削り、細かい泡（G・B・A）で
            // さらに縁を削る。芯でもノイズの濃淡が残るので、中に陰影のある綿になる（ノイズを足すだけだと
            // 芯が濃さ 1 に張り付いて、のっぺりした玉になった）
            float billow = n.r;
            float erosion = dot(m.gba, vec3(0.5, 0.3, 0.2));
            float cloud = clamp((billow - (1.0 - shape)) / max(shape, 0.05), 0.0, 1.0);
            float erode = erosion * EROSION * (1.0 - 0.6 * soften);
            cloud = clamp((cloud - erode) / (1.0 - erode), 0.0, 1.0);
            // 時間がたつほど輪郭がほどけて霞になる（ノイズで削った形から、なめらかな球の濃さへ寄せる）
            cloud = mix(cloud, shape * (0.55 + 0.45 * billow), soften * SOFTEN_MAX);
            density = cloud * (0.45 + 0.55 * shape) * DENSITY_GAIN;
        }
        if (density <= 0.001) {
            continue;
        }
        float extinction = sigma * density;
        // 日の方へ塊を出るまでの濃さ（中心ほど濃い球として見積もる）
        float toSun = exitLength(q, SunDir) * r * sigma * 0.55;
        float sunTransmit = exp(-toSun) + MULTI_SCATTER * exp(-toSun * MULTI_SCATTER_SOFTEN);
        // vMedium.w は帯全体の陰り（下・奥ほど空の明るさが届かない。SmokePuffs#shadeTrail）
        vec3 ambient = vAmbient.rgb * (AMBIENT_SHARE * vMedium.w * (0.78 + 0.22 * q.y));
        vec3 inScatter = (ambient + vSky * sunLight * sunTransmit + vLamp) * vAlbedo.rgb;
        float stepTransmit = exp(-extinction * dt);
        light += transmittance * (1.0 - stepTransmit) * inScatter;
        transmittance *= stepTransmit;
        if (transmittance < 0.01) {
            break;
        }
    }
    float alpha = 1.0 - transmittance;
    if (alpha <= 0.002) {
        discard;
    }
    // バニラの霧（地形と同じ。パック使用中は切ってある。パックが自分で霧を掛ける）
    if (FogEnabled > 0.5) {
        float distance = 0.5 * (t0 + t1);
        vec3 p = dir * distance;
        float fog = total_fog_value(fog_spherical_distance(p), fog_cylindrical_distance(p),
                FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
        light = mix(light, FogColor.rgb * alpha, fog * FogColor.a);
    }
    fragColor = vec4(light, alpha);
}
