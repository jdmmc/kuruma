#version 330
#extension GL_ARB_separate_shader_objects : require

#include <kurumamod:tire_smoke_info.glsl>

// 低い解像度で描いた煙（色に α を掛けた形）を、画面の解像度へ戻して重ねる。
// 重ね方はパイプラインの ONE, ONE_MINUS_SRC_ALPHA（書き込み先の α はそのまま）。
//
// <b>そのまま引き伸ばすと、車や柱の輪郭で煙がにじむ</b>（輪郭の手前と奥の煙が混ざる）。
// 周りの 4 点を、深度が自分に近いものほど重く混ぜる（深度を見た拡大）。どれも遠ければ、
// いちばん近い深度の点だけを使う
//
// 煙を描いた範囲は縮小した枠の左下 TargetSize（重ね塗りが多いフレームは枠より小さい）。

uniform sampler2D SmokeSampler;
uniform sampler2D DepthSampler;

layout(location = 0) flat in vec4 vInv0;
layout(location = 1) flat in vec4 vInv1;
layout(location = 2) flat in vec4 vInv2;
layout(location = 3) flat in vec4 vInv3;

layout(location = 0) out vec4 fragColor;

#define InvViewProj (mat4(vInv0, vInv1, vInv2, vInv3))

float distanceAt(vec2 uv) {
    float depth = textureLod(DepthSampler, uv, 0.0).r;
    if (smokeIsSky(depth)) {
        return 4096.0;
    }
    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, smokeDeviceZ(depth), 1.0);
    return length(p.xyz / p.w);
}

void main() {
    vec2 uv = gl_FragCoord.xy / OutSize;
    vec2 smokeSize = TargetSize;
    vec4 smoke;
    if (smokeSize.x >= OutSize.x - 0.5) {
        // 等倍（縮小していない）。枠と画面の画素が 1 対 1
        smoke = texelFetch(SmokeSampler, ivec2(gl_FragCoord.xy), 0);
    } else {
        float own = distanceAt(uv);
        vec2 f = uv * smokeSize - 0.5;
        vec2 base = floor(f);
        vec2 w = f - base;
        vec4 sum = vec4(0.0);
        float weightSum = 0.0;
        vec4 nearest = vec4(0.0);
        float nearestGap = 1.0e9;
        for (int i = 0; i < 4; i++) {
            vec2 offset = vec2(float(i & 1), float(i >> 1));
            vec2 texel = clamp(base + offset, vec2(0.0), smokeSize - 1.0);
            vec2 tapUv = (texel + 0.5) / smokeSize;
            vec4 value = texelFetch(SmokeSampler, ivec2(texel), 0);
            float gap = abs(distanceAt(tapUv) - own);
            float bilinear = (offset.x > 0.5 ? w.x : 1.0 - w.x) * (offset.y > 0.5 ? w.y : 1.0 - w.y);
            float weight = bilinear / (1.0 + gap / (0.04 * own + 0.15));
            sum += value * weight;
            weightSum += weight;
            if (gap < nearestGap) {
                nearestGap = gap;
                nearest = value;
            }
        }
        smoke = weightSum > 1.0e-4 ? sum / weightSum : nearest;
    }
    if (smoke.a <= 0.001) {
        discard;
    }
    fragColor = vec4(smoke.rgb * Brightness, clamp(smoke.a, 0.0, 1.0));
}
