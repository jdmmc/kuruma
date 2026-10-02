#version 150

// ランプから見た距離の地図。1 画素がランプから出る 1 方向で、その向きへブロックの格子を
// 光線で辿り（Amanatides-Woo の DDA）、最初に光を遮るブロックまでの距離を書く。
//
// 縦はランプの数だけの段に分かれる（1 段 = 1 灯。地図の高さは灯数に合わせて Java 側が変える）。横と段の中の縦が、光軸からの正接
// （横 tx・縦 ty）に対応する。範囲は headlight_volume.fsh と同じ値。片方だけ変えないこと。

uniform sampler2D VoxelSampler;

uniform sampler2D LampSampler;
uniform int LampCount;
// 光の届く距離 [m]。これより先は辿らない（遮りなしとして書く。届く距離の外は光が 0 なので見えない）
uniform float LightRange;
// カメラのいるブロックの座標と、その中での位置。ランプの位置はカメラ基準で来る
uniform vec3 CameraBlock;
uniform vec3 CameraFrac;
// 格子の大きさ（水平、高さ）
uniform vec2 GridSize;

in vec2 texCoord;

out vec4 fragColor;

// 距離の地図に書く最大の距離 = 格子の半分（96m 以下の届く距離では 48m、以前と同じ）
float maxDistance() {
    return GridSize.x * 0.5;
}
const float TX_MIN = -1.3;
const float TX_MAX = 1.3;
const float TY_MIN = -0.45;
const float TY_MAX = 0.15;
// ランプのすぐ近くは見ない（ランプが車体の一部のブロックに埋まっていても自分を影にしない）
const float START = 0.3;
// 辿る歩数の上限。格子が最小（届く距離 96m 以下）なら以前と同じ 160、広げたら 420
int maxSteps() {
    return GridSize.x > 96.5 ? 420 : 160;
}

// ランプは LampSampler の 1 行 = 1 灯（4 画素 = 行列の 4 列）。数の上限はテクスチャの高さだけ
mat4 lamp(int i) {
    return mat4(texelFetch(LampSampler, ivec2(0, i), 0),
                texelFetch(LampSampler, ivec2(1, i), 0),
                texelFetch(LampSampler, ivec2(2, i), 0),
                texelFetch(LampSampler, ivec2(3, i), 0));
}

int wrap(int v, int n) {
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
    ivec2 texel = ivec2(wrap(world.x, n) + wrap(world.y, ny) * n, wrap(world.z, n));
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
    vec3 near = min(a, b);
    vec3 far = max(a, b);
    float enter = max(max(near.x, near.y), max(near.z, t0));
    float exit = min(min(far.x, far.y), min(far.z, t1));
    return enter <= exit ? enter : -1.0;
}

// 向きの逆数（成分が 0 でも割り算で飛ばさない）
vec3 inverseDir(vec3 dir) {
    return vec3(
            1.0 / (abs(dir.x) > 1.0e-6 ? dir.x : 1.0e-6),
            1.0 / (abs(dir.y) > 1.0e-6 ? dir.y : 1.0e-6),
            1.0 / (abs(dir.z) > 1.0e-6 ? dir.z : 1.0e-6));
}

float march(vec3 origin, vec3 dir) {
    ivec3 cell = ivec3(floor(origin));
    ivec3 stepDir = ivec3(sign(dir));
    vec3 inv = 1.0 / max(abs(dir), vec3(1.0e-6));
    vec3 invDir = inverseDir(dir);
    vec3 next = vec3(
            (dir.x > 0.0 ? float(cell.x) + 1.0 - origin.x : origin.x - float(cell.x)) * inv.x,
            (dir.y > 0.0 ? float(cell.y) + 1.0 - origin.y : origin.y - float(cell.y)) * inv.y,
            (dir.z > 0.0 ? float(cell.z) + 1.0 - origin.z : origin.z - float(cell.z)) * inv.z);
    float t = 0.0;
    int steps = maxSteps();
    for (int i = 0; i < steps; i++) {
        float tExit = min(next.x, min(next.y, next.z));
        if (tExit > START) {
            float h = boxHit(cell, origin, invDir, max(t, START), tExit);
            if (h >= 0.0) {
                return h;
            }
        }
        t = tExit;
        if (t > min(maxDistance(), LightRange)) {
            return maxDistance();
        }
        if (next.x <= next.y && next.x <= next.z) {
            cell.x += stepDir.x;
            next.x += inv.x;
        } else if (next.y <= next.z) {
            cell.y += stepDir.y;
            next.y += inv.y;
        } else {
            cell.z += stepDir.z;
            next.z += inv.z;
        }
    }
    return maxDistance();
}

vec4 encode(float distance) {
    // 8 ビット 2 つに分けて 16 ビットで持つ（48m を 0.7mm 刻み）
    float n = clamp(distance / maxDistance(), 0.0, 1.0) * 255.0;
    float high = floor(n);
    return vec4(high / 255.0, n - high, 0.0, 1.0);
}

void main() {
    float row = texCoord.y * float(max(LampCount, 1));
    int index = int(row);
    if (index >= LampCount) {
        fragColor = encode(maxDistance());
        return;
    }
    float tx = mix(TX_MIN, TX_MAX, texCoord.x);
    float ty = mix(TY_MIN, TY_MAX, fract(row));
    mat4 L = lamp(index);
    vec3 dir = normalize(L[1].xyz + L[2].xyz * tx + L[3].xyz * ty);
    vec3 origin = CameraFrac + L[0].xyz;
    fragColor = encode(march(origin, dir));
}
