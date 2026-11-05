#version 330
#extension GL_ARB_separate_shader_objects : require

#include <kurumamod:headlight_info.glsl>

// ランプから見た距離の地図。1 画素がランプから出る 1 方向で、その向きへブロックの格子を
// 光線で辿り（Amanatides-Woo の DDA）、最初に光を遮るブロックまでの距離を書く。
//
// 縦はランプの数だけの段に分かれる（1 段 = 1 灯。地図の高さは灯数に合わせて Java 側が変える）。横と段の中の縦が、光軸からの正接
// （横 tx・縦 ty）に対応する。範囲は headlight_info.glsl の TX_MIN.. で、headlight_volume.fsh と共有。

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// ランプのすぐ近くは見ない（ランプが車体の一部のブロックに埋まっていても自分を影にしない）
const float START = 0.3;
// 辿る歩数の上限。格子が最小（届く距離 96m 以下）なら 160、広げたら 420
int maxSteps() {
    return GridSize.x > 96.5 ? 420 : 160;
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
        if (t > min(shadowMaxDistance(), LightRange)) {
            return shadowMaxDistance();
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
    return shadowMaxDistance();
}

vec4 encode(float distance) {
    // 8 ビット 2 つに分けて 16 ビットで持つ（48m を 0.7mm 刻み）
    float n = clamp(distance / shadowMaxDistance(), 0.0, 1.0) * 255.0;
    float high = floor(n);
    return vec4(high / 255.0, n - high, 0.0, 1.0);
}

void main() {
    int count = LampCount;
    float row = texCoord.y * float(max(count, 1));
    int index = int(row);
    if (index >= count) {
        fragColor = encode(shadowMaxDistance());
        return;
    }
    float tx = mix(TX_MIN, TX_MAX, texCoord.x);
    float ty = mix(TY_MIN, TY_MAX, fract(row));
    mat4 L = lamp(index);
    vec3 dir = normalize(L[1].xyz + L[2].xyz * tx + L[3].xyz * ty);
    vec3 origin = CameraFrac + L[0].xyz;
    fragColor = encode(march(origin, dir));
}
