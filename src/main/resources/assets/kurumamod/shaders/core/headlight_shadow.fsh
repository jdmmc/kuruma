#version 150

// ランプから見た距離の地図。1 画素がランプから出る 1 方向で、その向きへブロックの格子を
// 光線で辿り（Amanatides-Woo の DDA）、最初に光を遮るブロックまでの距離を書く。
//
// 縦はランプ 8 灯ぶんの段に分かれる（1 段 = 1 灯）。横と段の中の縦が、光軸からの正接
// （横 tx・縦 ty）に対応する。範囲は headlight_volume.fsh と同じ値。片方だけ変えないこと。

uniform sampler2D VoxelSampler;

uniform mat4 Lamp0;
uniform mat4 Lamp1;
uniform mat4 Lamp2;
uniform mat4 Lamp3;
uniform mat4 Lamp4;
uniform mat4 Lamp5;
uniform mat4 Lamp6;
uniform mat4 Lamp7;
uniform int LampCount;
// カメラのいるブロックの座標と、その中での位置。ランプの位置はカメラ基準で来る
uniform vec3 CameraBlock;
uniform vec3 CameraFrac;
// 格子の大きさ（水平、高さ）
uniform vec2 GridSize;

in vec2 texCoord;

out vec4 fragColor;

const int MAX_LAMPS = 8;
const float MAX_DISTANCE = 48.0;
const float TX_MIN = -1.3;
const float TX_MAX = 1.3;
const float TY_MIN = -0.45;
const float TY_MAX = 0.15;
// ランプのすぐ近くは見ない（ランプが車体の一部のブロックに埋まっていても自分を影にしない）
const float START = 0.3;
const int MAX_STEPS = 160;

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

int wrap(int v, int n) {
    int m = v - (v / n) * n;
    return m < 0 ? m + n : m;
}

// そのブロックが光を遮る高さの範囲（0..1）。遮らなければ (0, 0)。
// local はカメラのいるブロックを原点にしたブロックの座標
vec2 occluder(ivec3 local) {
    int n = int(GridSize.x);
    int ny = int(GridSize.y);
    if (local.x < -n / 2 || local.x >= n / 2 || local.z < -n / 2 || local.z >= n / 2
            || local.y < -ny / 2 || local.y >= ny / 2) {
        return vec2(0.0);
    }
    ivec3 world = local + ivec3(CameraBlock);
    ivec2 texel = ivec2(wrap(world.x, n) + wrap(world.y, ny) * n, wrap(world.z, n));
    return texelFetch(VoxelSampler, texel, 0).rg;
}

// 光線がブロック cell の中を t0..t1 で通るとき、遮る範囲に入る距離。入らなければ -1
float hit(ivec3 cell, vec3 origin, vec3 dir, float t0, float t1) {
    vec2 range = occluder(cell);
    if (range.y <= range.x) {
        return -1.0;
    }
    float y0 = origin.y + dir.y * t0 - float(cell.y);
    float y1 = origin.y + dir.y * t1 - float(cell.y);
    if (max(y0, y1) < range.x || min(y0, y1) > range.y) {
        return -1.0;
    }
    if (y0 >= range.x && y0 <= range.y) {
        return t0;
    }
    // 上か下から範囲へ入る
    float edge = y0 > range.y ? range.y : range.x;
    return t0 + (y0 - edge) / (y0 - y1) * (t1 - t0);
}

float march(vec3 origin, vec3 dir) {
    ivec3 cell = ivec3(floor(origin));
    ivec3 stepDir = ivec3(sign(dir));
    vec3 inv = 1.0 / max(abs(dir), vec3(1.0e-6));
    vec3 next = vec3(
            (dir.x > 0.0 ? float(cell.x) + 1.0 - origin.x : origin.x - float(cell.x)) * inv.x,
            (dir.y > 0.0 ? float(cell.y) + 1.0 - origin.y : origin.y - float(cell.y)) * inv.y,
            (dir.z > 0.0 ? float(cell.z) + 1.0 - origin.z : origin.z - float(cell.z)) * inv.z);
    float t = 0.0;
    for (int i = 0; i < MAX_STEPS; i++) {
        float tExit = min(next.x, min(next.y, next.z));
        if (tExit > START) {
            float h = hit(cell, origin, dir, max(t, START), tExit);
            if (h >= 0.0) {
                return h;
            }
        }
        t = tExit;
        if (t > MAX_DISTANCE) {
            return MAX_DISTANCE;
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
    return MAX_DISTANCE;
}

vec4 encode(float distance) {
    // 8 ビット 2 つに分けて 16 ビットで持つ（48m を 0.7mm 刻み）
    float n = clamp(distance / MAX_DISTANCE, 0.0, 1.0) * 255.0;
    float high = floor(n);
    return vec4(high / 255.0, n - high, 0.0, 1.0);
}

void main() {
    float row = texCoord.y * float(MAX_LAMPS);
    int index = int(row);
    if (index >= LampCount) {
        fragColor = encode(MAX_DISTANCE);
        return;
    }
    float tx = mix(TX_MIN, TX_MAX, texCoord.x);
    float ty = mix(TY_MIN, TY_MAX, fract(row));
    mat4 L = lamp(index);
    vec3 dir = normalize(L[1].xyz + L[2].xyz * tx + L[3].xyz * ty);
    vec3 origin = CameraFrac + L[0].xyz;
    fragColor = encode(march(origin, dir));
}
