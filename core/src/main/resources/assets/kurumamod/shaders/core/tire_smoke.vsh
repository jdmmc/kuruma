#version 150

// タイヤの煙の塊（パフ）1 つを、それを包む板 1 枚（三角形 2 枚）で描く。頂点の属性は使わない。
// 塊の中身は PuffSampler（1 塊 4 画素）にあり、gl_VertexID から何番目の塊のどの角かを決める。
//
// 塊が受けている光（ライトマップ・日の当たり・ヘッドライト）はここで塊ごとに 1 回だけ求め、
// 画素の側（tire_smoke.fsh）では形と影だけを解く。
//
// ヘッドライトの配光と影の読み方は headlight_volume.fsh と同じ。片方だけ変えないこと

// 1 塊 = 4 画素。1 行に PUFFS_PER_ROW 塊
//   0: 中心（カメラ基準・世界の向き）、半径
//   1: 消散係数 [1/m]、年齢 [秒]、種（0..1）、空の明るさの陰り（帯全体。0.5..1）
//   2: 色（反射率）、縦の潰れ（0..0.35。古い煙ほど横へ広がる）
//   3: ブロックの明るさ（0..15）、空の明るさ（0..15）、日の当たり（0..1。帯全体の陰りも込み）
uniform sampler2D PuffSampler;
const int PUFFS_PER_ROW = 256;

uniform mat4 ProjMat;
// カメラの向きだけ（平行移動なし）。座標はカメラ基準
uniform mat4 ViewMat;

// バニラのライトマップ（16x16）。世界の明るさ（時刻・ガンマ・暗視など）にそのまま揃う
uniform sampler2D LightMapSampler;

// ---- ヘッドライト（headlight_volume.fsh と同じ） ----
uniform sampler2D LampSampler;
uniform int LampCount;
uniform float LightRange;
uniform sampler2D ShadowSampler;
uniform vec2 ShadowSize;
uniform vec2 GridSize;
uniform vec3 LightColor;
// 煙に届いた光をどれだけの明るさに見せるか（Ctrl+L の「煙の照らされ方」から）
uniform float LampGain;

flat out vec4 vCenter;
flat out vec4 vMedium;
flat out vec3 vAlbedo;
flat out float vFlatten;
flat out vec3 vAmbient;
// 空の明るさだけのライトマップ。日の光はこれに掛ける（たいまつの明かりで日が強くならないように）
flat out vec3 vSky;
flat out float vSunVis;
flat out vec3 vLamp;

const float CUTOFF = 0.010472;
const float CUTOFF_SOFT = 0.008727;
const float KICK_SLOPE = 0.267949;
const float KICK_MAX = 0.043661;
const float WIDE_X = 0.445229;
const float WIDE_Y = 0.140541;
const float WIDE_WEIGHT = 0.45;
const float SPOT_X = 0.122785;
const float SPOT_Y = 0.038416;
const float SPOT_CENTER = -0.013964;
const float SPOT_WEIGHT = 0.80;
const float VOLUME_SOFTEN = 3.0;
const float MIN_DIST2 = 0.6;
const float PHASE_G = 0.6;
const float SMOKE_EXPOSURE = 100.0;
// 照らされた煙の色をどれだけ白へ寄せるか
const float LAMP_WHITEN = 0.65;
// ランプの光の散乱のうち、向きに偏る（前方散乱）割合。残りは向きによらず散る
const float LAMP_PHASE_SHARE = 0.4;
#define SHADOW_DISTANCE (GridSize.x * 0.5)
const float TX_MIN = -1.3;
const float TX_MAX = 1.3;
const float TY_MIN = -0.45;
const float TY_MAX = 0.15;
const float SHADOW_BIAS = 0.12;
const float SHADOW_BIAS_PER_METER = 0.02;

mat4 lamp(int i) {
    return mat4(texelFetch(LampSampler, ivec2(0, i), 0),
                texelFetch(LampSampler, ivec2(1, i), 0),
                texelFetch(LampSampler, ivec2(2, i), 0),
                texelFetch(LampSampler, ivec2(3, i), 0));
}

float pattern(float tx, float ty, float soften) {
    float cut = CUTOFF + min(KICK_MAX, max(0.0, -tx) * KICK_SLOPE);
    float soft = CUTOFF_SOFT * soften;
    float gate = 1.0 - smoothstep(-soft, soft, ty - cut);
    if (gate <= 0.0) {
        return 0.0;
    }
    float wx = tx / WIDE_X;
    float wy = ty / WIDE_Y;
    float sx = tx / SPOT_X;
    float sy = (ty - SPOT_CENTER) / SPOT_Y;
    return gate * (WIDE_WEIGHT * exp(-(wx * wx + wy * wy))
            + SPOT_WEIGHT * exp(-(sx * sx + sy * sy)));
}

float emitted(mat4 L, vec3 v, float soften) {
    // ナンバー灯（L[3].w が 1）は煙を照らさない（プレートのまわりだけの弱い光）
    if (L[3].w > 0.5) {
        return 0.0;
    }
    float along = dot(v, L[1].xyz);
    if (along <= 0.05) {
        return 0.0;
    }
    float distance = length(v);
    if (distance >= LightRange) {
        return 0.0;
    }
    float rangeFade = 1.0 - smoothstep(LightRange * 0.7, LightRange, distance);
    float reach = L[0].w;
    float blocked = (1.0 - smoothstep(reach - 0.3, reach + 0.8, along)) * rangeFade;
    float tx = dot(v, L[2].xyz) / along;
    float ty = dot(v, L[3].xyz) / along;
    return pattern(tx, ty, soften) * blocked * L[1].w;
}

float shadowDistance(int index, ivec2 texel) {
    int tile = int(ShadowSize.y) / max(LampCount, 1);
    texel = clamp(texel, ivec2(0), ivec2(int(ShadowSize.x) - 1, tile - 1));
    vec2 encoded = texelFetch(ShadowSampler, ivec2(texel.x, texel.y + index * tile), 0).rg;
    return (encoded.r + encoded.g / 255.0) * SHADOW_DISTANCE;
}

float shadow(int index, mat4 L, vec3 v) {
    float along = dot(v, L[1].xyz);
    if (along <= 0.05) {
        return 1.0;
    }
    float tx = dot(v, L[2].xyz) / along;
    float ty = dot(v, L[3].xyz) / along;
    if (tx < TX_MIN || tx > TX_MAX || ty < TY_MIN || ty > TY_MAX) {
        return 1.0;
    }
    float distance = length(v);
    if (distance >= SHADOW_DISTANCE - 0.5) {
        return 1.0;
    }
    float bias = SHADOW_BIAS + SHADOW_BIAS_PER_METER * distance;
    float tile = ShadowSize.y / float(max(LampCount, 1));
    vec2 px = vec2((tx - TX_MIN) / (TX_MAX - TX_MIN) * ShadowSize.x,
                   (ty - TY_MIN) / (TY_MAX - TY_MIN) * tile);
    return shadowDistance(index, ivec2(px)) + bias >= distance ? 1.0 : 0.0;
}

float phase(float cosTheta) {
    float g2 = PHASE_G * PHASE_G;
    return (1.0 - g2) / pow(1.0 + g2 - 2.0 * PHASE_G * cosTheta, 1.5);
}

const vec2 CORNERS[6] = vec2[6](
        vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
        vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

void main() {
    int puff = gl_VertexID / 6;
    vec2 corner = CORNERS[gl_VertexID - puff * 6];
    ivec2 base = ivec2((puff - (puff / PUFFS_PER_ROW) * PUFFS_PER_ROW) * 4, puff / PUFFS_PER_ROW);
    vec4 t0 = texelFetch(PuffSampler, base, 0);
    vec4 t1 = texelFetch(PuffSampler, base + ivec2(1, 0), 0);
    vec4 t2 = texelFetch(PuffSampler, base + ivec2(2, 0), 0);
    vec4 t3 = texelFetch(PuffSampler, base + ivec2(3, 0), 0);

    vec3 c = t0.xyz;
    float r = t0.w;
    vCenter = t0;
    vMedium = t1;
    vAlbedo = t2.rgb;
    vFlatten = t2.w;
    vSunVis = t3.z;
    // バニラと同じ引き方（ブロック、空）。端の画素の真ん中を読む
    vAmbient = texture(LightMapSampler, clamp((t3.xy + 0.5) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
    vSky = texture(LightMapSampler, clamp((vec2(0.0, t3.y) + 0.5) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;

    // ヘッドライト。塊の真ん中に届く光を、霧の光線と同じ前方散乱で
    float d = length(c);
    vec3 toPuff = d > 1.0e-4 ? c / d : vec3(0.0, 0.0, -1.0);
    float received = 0.0;
    for (int i = 0; i < LampCount; i++) {
        mat4 L = lamp(i);
        vec3 v = c - L[0].xyz;
        float strength = emitted(L, v, VOLUME_SOFTEN);
        if (strength <= 0.0) {
            continue;
        }
        strength *= shadow(i, L, v);
        if (strength <= 0.0) {
            continue;
        }
        float dist2 = max(dot(v, v), MIN_DIST2);
        float cosTheta = dot(v * inversesqrt(dist2), -toPuff);
        // 濃い煙の中では光が何度も跳ね返って向きが均される（多重散乱）。霧の光線と同じ強い前方散乱のままだと、
        // 自分のライトで前の煙を照らす（光が後ろへ返る）場面で 16% しか返らず、照らした煙が灰色にしか見えなかった
        received += strength * mix(1.0, phase(cosTheta), LAMP_PHASE_SHARE) / dist2;
    }
    // ライトの中の煙は白く浮かび上がる（細かい粒が光を色を選ばずに散らす）。ランプの色をそのまま掛けると
    // ハロゲンの黄色みが乗って、照らされた煙が黄ばんで見えた。散らした光は白へ寄せる。
    // 砂塵は反射率（vAlbedo）が砂の色なので、照らされても砂の色のまま
    vLamp = mix(LightColor, vec3(1.0), LAMP_WHITEN) * LampGain * (1.0 - exp(-received * SMOKE_EXPOSURE));

    // カメラが塊の中（か、すぐそば）にいれば画面いっぱいに描く。板を置く場所が無い
    if (d < r + 0.3) {
        gl_Position = vec4(corner, 0.0, 1.0);
        return;
    }
    // 塊を包む円錐の、塊のいちばん手前の距離での断面に板を置く
    vec3 up = abs(toPuff.y) > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(toPuff, up));
    vec3 upward = cross(right, toPuff);
    float planeDistance = max(d - r, 0.1);
    float size = planeDistance * r / sqrt(max(d * d - r * r, 1.0e-4)) * 1.05;
    vec3 position = toPuff * planeDistance + (right * corner.x + upward * corner.y) * size;
    gl_Position = ProjMat * ViewMat * vec4(position, 1.0);
}
