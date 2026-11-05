#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <kurumamod:tire_smoke_info.glsl>

// タイヤの煙の塊（パフ）1 つを、それを包む板 1 枚（三角形 2 枚）で描く。頂点の属性は使わない。
// 塊の中身は PuffSampler（1 塊 4 画素）にあり、gl_VertexIndex から何番目の塊のどの角かを決める。
//
// 塊が受けている光（ライトマップ・日の当たり・ヘッドライト）はここで塊ごとに 1 回だけ求め、
// 画素の側（tire_smoke.fsh）では形と影だけを解く。
//
// <b>射影は Projection のブロック（ProjMat）から取る。</b>Java 側の CameraRenderState#projectionMatrix には
// 画面の揺れ（歩くときの揺れ・被弾の傾き）が入っておらず、ワールドはそれを掛けた行列で描かれている。
// 深度から面を組み直すときに揺れたぶんずれないよう、画面から世界へ戻す行列もここで作って flat で渡す。

// 1 塊 = 4 画素。1 行に PUFFS_PER_ROW 塊（TireSmokeVolume と同じ）
//   0: 中心（カメラ基準・世界の向き）、半径
//   1: 消散係数 [1/m]、年齢 [秒]、種（0..1）、空の明るさの陰り（帯全体。0.5..1）
//   2: 色（反射率）、縦の潰れ（0..0.35。古い煙ほど横へ広がる）
//   3: ブロックの明るさ（0..15）、空の明るさ（0..15）、日の当たり（0..1。帯全体の陰りも込み）
uniform sampler2D PuffSampler;
const int PUFFS_PER_ROW = 256;

// バニラのライトマップ（16x16）。世界の明るさ（時刻・ガンマ・暗視など）にそのまま揃う
uniform sampler2D LightMapSampler;

// ---- ヘッドライト ----
// 塊の真ん中に届くヘッドライトの光（路面・光線と同じ配光と影の地図。1.21.1 の bindLampsForSmoke）。
// ランプがあるフレームだけ、KURUMAMOD_SMOKE_LAMPS を定義したパイプライン（TireSmokeVolume の PUFF_LAMP_PIPELINE）で描く。
// ランプが 0 灯なら定義しないパイプラインで描き、vLamp は 0。
// 配光の定数と距離の地図の読み方は headlight_volume.fsh と同じ（3 か所目。片方だけ変えないこと）
#ifdef KURUMAMOD_SMOKE_LAMPS
#include <kurumamod:headlight_info.glsl>
// ランプから見た、最初にブロックに当たるまでの距離（headlight_shadow.fsh が描く）
uniform sampler2D ShadowSampler;

// 配光（headlight_volume.fsh・CarLights と同じ値。片方だけ変えないこと）
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
// 「煙の照らされ方」に掛ける割合（1.21.1 の LampGain = headlightSmoke × 0.75）
const float LAMP_GAIN_SCALE = 0.75;
const float SHADOW_BIAS = 0.12;
const float SHADOW_BIAS_PER_METER = 0.02;

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

// 距離の地図の読み方は headlight_volume.fsh と同じにすること（詰め方が変わったらここも直す）
float shadowDistance(int index, ivec2 texel) {
    int tile = int(ShadowSize.y) / max(LampCount, 1);
    texel = clamp(texel, ivec2(0), ivec2(int(ShadowSize.x) - 1, tile - 1));
    vec2 encoded = texelFetch(ShadowSampler, ivec2(texel.x, texel.y + index * tile), 0).rg;
    return (encoded.r + encoded.g / 255.0) * shadowMaxDistance();
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
    if (distance >= shadowMaxDistance() - 0.5) {
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

// 塊の真ん中に届くヘッドライトの光。c はこのフレームのカメラ基準
vec3 lampLight(vec3 c, vec3 toPuff) {
    // ランプの位置は HeadlightInfo を詰めたときのカメラ基準（ふつうは同じフレームだが、1 フレーム前のこともある）。
    // 煙の中心をそちらの基準へ直す（ブロックと端数に分けて引き、遠くでも桁が落ちないように）
    vec3 shift = (SmokeCameraBlock.xyz - CameraBlock) + (SmokeCameraFrac.xyz - CameraFrac);
    vec3 cl = c + shift;
    float received = 0.0;
    for (int i = 0; i < LampCount; i++) {
        mat4 L = lamp(i);
        vec3 v = cl - L[0].xyz;
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
    // 砂塵は反射率（vAlbedo）が砂の色なので、照らされても砂の色のまま。
    // 強さは「煙の照らされ方」（Ctrl+L）× 0.75（1.21.1 の LampGain と同じ）
    return mix(LightColor, vec3(1.0), LAMP_WHITEN) * (SmokeGain * LAMP_GAIN_SCALE) * (1.0 - exp(-received * SMOKE_EXPOSURE));
}
#endif

layout(location = 0) flat out vec4 vCenter;
layout(location = 1) flat out vec4 vMedium;
// rgb = 色（反射率）、a = 縦の潰れ
layout(location = 2) flat out vec4 vAlbedo;
// rgb = 周りの明るさ（ライトマップ）、a = 日の当たり
layout(location = 3) flat out vec4 vAmbient;
// 空の明るさだけのライトマップ。日の光はこれに掛ける（たいまつの明かりで日が強くならないように）
layout(location = 4) flat out vec3 vSky;
layout(location = 5) flat out vec3 vLamp;
// 画面から世界へ戻す行列（射影と視点の回転の逆）の 4 列
layout(location = 6) flat out vec4 vInv0;
layout(location = 7) flat out vec4 vInv1;
layout(location = 8) flat out vec4 vInv2;
layout(location = 9) flat out vec4 vInv3;

const vec2 CORNERS[6] = vec2[6](
        vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
        vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

vec3 lightmap(vec2 blockSky) {
    // バニラと同じ引き方（ブロック、空）。端の画素の真ん中を読む
    return textureLod(LightMapSampler, clamp((blockSky + 0.5) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)), 0.0).rgb;
}

// 煙を描く範囲（縮小した枠の左下 TargetSize）へ寄せる。26.3 のバニラの RenderPass にはビューポートを
// 変える口が無い（setViewport は NeoForge が足したもの）ので、正規化座標の側で縮める
vec4 toDrawArea(vec4 clip) {
    vec2 scale = TargetSize / LowSize;
    clip.xy = (clip.xy + clip.w) * scale - clip.w;
    return clip;
}

void main() {
    int puff = gl_VertexIndex / 6;
    vec2 corner = CORNERS[gl_VertexIndex - puff * 6];
    ivec2 base = ivec2((puff - (puff / PUFFS_PER_ROW) * PUFFS_PER_ROW) * 4, puff / PUFFS_PER_ROW);
    vec4 t0 = texelFetch(PuffSampler, base, 0);
    vec4 t1 = texelFetch(PuffSampler, base + ivec2(1, 0), 0);
    vec4 t2 = texelFetch(PuffSampler, base + ivec2(2, 0), 0);
    vec4 t3 = texelFetch(PuffSampler, base + ivec2(3, 0), 0);

    mat4 viewProj = ProjMat * SmokeView;
    mat4 inv = inverse(viewProj);
    vInv0 = inv[0];
    vInv1 = inv[1];
    vInv2 = inv[2];
    vInv3 = inv[3];

    vec3 c = t0.xyz;
    float r = t0.w;
    vCenter = t0;
    vMedium = t1;
    vAlbedo = t2;
    vAmbient = vec4(lightmap(t3.xy), t3.z);
    vSky = lightmap(vec2(0.0, t3.y));

    float d = length(c);
    vec3 toPuff = d > 1.0e-4 ? c / d : vec3(0.0, 0.0, -1.0);
#ifdef KURUMAMOD_SMOKE_LAMPS
    vLamp = lampLight(c, toPuff);
#else
    vLamp = vec3(0.0);
#endif

    // カメラが塊の中（か、すぐそば）にいれば画面いっぱいに描く。板を置く場所が無い
    if (d < r + 0.3) {
        gl_Position = toDrawArea(vec4(corner, 0.0, 1.0));
        return;
    }
    // 塊を包む円錐の、塊のいちばん手前の距離での断面に板を置く
    vec3 up = abs(toPuff.y) > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(toPuff, up));
    vec3 upward = cross(right, toPuff);
    float planeDistance = max(d - r, 0.1);
    float size = planeDistance * r / sqrt(max(d * d - r * r, 1.0e-4)) * 1.05;
    vec3 position = toPuff * planeDistance + (right * corner.x + upward * corner.y) * size;
    gl_Position = toDrawArea(viewProj * vec4(position, 1.0));
}
