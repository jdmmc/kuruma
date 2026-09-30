#version 150

// ヘッドライトの光を画面の画素ごとに解く。
//
// 1. 路面の照り返し … 深度から画素の位置と面の向きを組み直し、配光から照度を求めて
//    その画素の色をそのまま明るくする。<b>見えている面にしか乗らない</b>ので、壁を透けたり
//    宙に浮いたりしない。車や人など、ブロック以外にも乗る
// 2. 霧の中の光線 … カメラから見えている面までの視線を刻んで進み、光の円錐の中にいる
//    ところで「霧がカメラへどれだけ光を散らすか」を足していく。前方散乱なので
//    ランプの方を向くほど明るい。霧は光を吸うので、奥ほど・ランプから遠いほど薄れる
//
// 配光の定数は CarLights と同じ値。片方だけ変えないこと。

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
// ランプ 1 灯ぶん。列 0 = 位置（カメラ基準）と届く距離、列 1 = 光軸と強さ、列 2 = 右、列 3 = 上
uniform mat4 Lamp0;
uniform mat4 Lamp1;
uniform mat4 Lamp2;
uniform mat4 Lamp3;
uniform mat4 Lamp4;
uniform mat4 Lamp5;
uniform mat4 Lamp6;
uniform mat4 Lamp7;
uniform int LampCount;
uniform float FogDensity;
uniform vec2 ScreenSize;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LIGHT_COLOR = vec3(1.0, 0.94, 0.82);

// ---- 配光（CarLights と同じ） ----
const float CUTOFF = 0.010472;       // tan 0.6°
const float CUTOFF_SOFT = 0.008727;  // tan 0.5°
const float KICK_SLOPE = 0.267949;   // tan 15°
const float KICK_MAX = 0.043661;     // tan 2.5°
const float WIDE_X = 0.445229;       // tan 24°
const float WIDE_Y = 0.140541;       // tan 8°
const float WIDE_WEIGHT = 0.45;
const float SPOT_X = 0.122785;       // tan 7°
const float SPOT_Y = 0.038416;       // tan 2.2°
const float SPOT_CENTER = -0.013964; // -tan 0.8°
const float SPOT_WEIGHT = 0.80;
const float EXPOSURE_GAIN = 8600.0;
const float EXPOSURE_GAMMA = 0.6;

// ---- 路面 ----
// 照らされた画素を何倍まで明るくするか。夜の地面は昼の数 % しかないので大きく要る
const float SURFACE_GAIN = 5.0;
// 真っ黒な面にも少しだけ光を乗せる
const float SURFACE_FLOOR = 0.03;

// ---- 霧 ----
const int VOLUME_STEPS = 20;
const float VOLUME_LENGTH = 40.0;
// 前方散乱の強さ（Henyey-Greenstein の g）。0 で全方向に均等、1 に近いほど前へ集まる
const float PHASE_G = 0.6;
const float VOLUME_GAIN = 120.0;
// 光線に入れるカットオフのぼかし。空気中の光は面の上ほど縁がはっきりしない
const float VOLUME_SOFTEN = 3.0;
// ランプのすぐ近くで距離² が 0 へ落ちて発散しないように
const float MIN_DIST2 = 0.6;
// 空（深度 1）の方向へ進む距離
const float SKY_DISTANCE = 96.0;

mat4 lamp(int i) {
    if (i == 0) return Lamp0;
    if (i == 1) return Lamp1;
    if (i == 2) return Lamp2;
    if (i == 3) return Lamp3;
    if (i == 4) return Lamp4;
    if (i == 5) return Lamp5;
    if (i == 6) return Lamp6;
    return Lamp7;
}

float pattern(float tx, float ty, float soften) {
    // 歩道側（左 = tx が負）はカットオフが持ち上がる
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

// ランプ L から点 p へ出る光の強さ（距離で割る前）。v は L から p へのベクトル
float emitted(mat4 L, vec3 v, float soften) {
    float along = dot(v, L[1].xyz);
    if (along <= 0.05) {
        return 0.0;
    }
    // 光軸の先で壁に当たっていれば、そこから先へは届かない
    float reach = L[0].w;
    float blocked = 1.0 - smoothstep(reach - 0.3, reach + 0.8, along);
    float tx = dot(v, L[2].xyz) / along;
    float ty = dot(v, L[3].xyz) / along;
    return pattern(tx, ty, soften) * blocked * L[1].w;
}

vec3 reconstruct(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = InvViewProj * clip;
    return world.xyz / world.w;
}

// 面の向き。隣の画素のうち<b>近い方</b>と差を取るので、物の縁で向きが壊れにくい
vec3 surfaceNormal(vec3 p, vec2 uv) {
    vec2 px = 1.0 / ScreenSize;
    vec3 right = reconstruct(uv + vec2(px.x, 0.0)) - p;
    vec3 left = p - reconstruct(uv - vec2(px.x, 0.0));
    vec3 up = reconstruct(uv + vec2(0.0, px.y)) - p;
    vec3 down = p - reconstruct(uv - vec2(0.0, px.y));
    vec3 dx = dot(right, right) < dot(left, left) ? right : left;
    vec3 dy = dot(up, up) < dot(down, down) ? up : down;
    vec3 n = normalize(cross(dx, dy));
    // カメラの方を向かせる
    return dot(n, p) > 0.0 ? -n : n;
}

float phase(float cosTheta) {
    float g2 = PHASE_G * PHASE_G;
    return (1.0 - g2) / pow(1.0 + g2 - 2.0 * PHASE_G * cosTheta, 1.5);
}

// 画素ごとにずらす刻みの出だし。刻みの縞をざらつきに変える
float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec3 scene = texture(SceneSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    vec3 color = scene;

    vec3 surface = reconstruct(texCoord);
    bool sky = depth >= 1.0;
    vec3 rayDir = normalize(surface);
    float surfaceDistance = sky ? SKY_DISTANCE : length(surface);

    // ---- 路面の照り返し ----
    if (!sky) {
        vec3 n = surfaceNormal(surface, texCoord);
        float illuminance = 0.0;
        for (int i = 0; i < LampCount; i++) {
            mat4 L = lamp(i);
            vec3 v = surface - L[0].xyz;
            float dist2 = dot(v, v);
            if (dist2 < 0.01) {
                continue;
            }
            float cosIncidence = -dot(v, n) * inversesqrt(dist2);
            if (cosIncidence <= 0.0) {
                continue;
            }
            illuminance += emitted(L, v, 1.0) * cosIncidence / dist2;
        }
        if (illuminance > 0.0) {
            float lit = 1.0 - exp(-pow(illuminance * EXPOSURE_GAIN, EXPOSURE_GAMMA));
            // 元の色のまま明るくする（模様が残る）。明るいところほど伸びしろを小さく
            vec3 added = scene * SURFACE_GAIN * lit * LIGHT_COLOR + SURFACE_FLOOR * lit * LIGHT_COLOR;
            color = scene + added * (1.0 - scene);
        }
    }

    // ---- 霧の中の光線 ----
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    vec3 scattered = vec3(0.0);
    for (int i = 0; i < LampCount; i++) {
        mat4 L = lamp(i);
        vec3 origin = L[0].xyz;
        vec3 axis = L[1].xyz;
        // 光の届く範囲を包む球と視線が交わる区間だけ刻む。刻みがそこへ集まるので、
        // 少ない歩数でも光線の中が粗くならない
        float reach = min(VOLUME_LENGTH, L[0].w + 0.8);
        vec3 center = origin + axis * (reach * 0.5);
        float radius = reach * 0.5 + 1.5;
        float mid = dot(center, rayDir);
        float miss = dot(center, center) - mid * mid;
        if (miss > radius * radius) {
            continue;
        }
        float chord = sqrt(radius * radius - miss);
        float t0 = max(mid - chord, 0.0);
        float t1 = min(mid + chord, surfaceDistance);
        if (t1 <= t0) {
            continue;
        }
        float dt = (t1 - t0) / float(VOLUME_STEPS);
        float sum = 0.0;
        for (int s = 0; s < VOLUME_STEPS; s++) {
            float t = t0 + (float(s) + jitter) * dt;
            vec3 p = rayDir * t;
            vec3 v = p - origin;
            float strength = emitted(L, v, VOLUME_SOFTEN);
            if (strength <= 0.0) {
                continue;
            }
            float dist2 = max(dot(v, v), MIN_DIST2);
            float fromLamp = sqrt(dist2);
            // 光はランプからここまで、散った光はここからカメラまで、どちらも霧に吸われる
            float transmittance = exp(-FogDensity * (t + fromLamp));
            // 光の進む向き（ランプ → ここ）とカメラへの向きがそろうほど強く散る
            float cosTheta = dot(v / fromLamp, -rayDir);
            sum += strength * phase(cosTheta) * transmittance / dist2 * dt;
        }
        scattered += vec3(sum);
    }
    scattered *= FogDensity * VOLUME_GAIN;
    color += LIGHT_COLOR * (1.0 - exp(-scattered));

    fragColor = vec4(color, 1.0);
}
