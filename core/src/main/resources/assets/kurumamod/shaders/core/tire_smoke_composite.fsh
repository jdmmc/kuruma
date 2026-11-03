#version 150

// 低い解像度で描いた煙（色に α を掛けた形）を、画面の解像度へ戻して重ねる。
// 重ね方は呼ぶ側の ONE, ONE_MINUS_SRC_ALPHA。
//
// <b>そのまま引き伸ばすと、車や柱の輪郭で煙がにじむ</b>（輪郭の手前と奥の煙が混ざる）。
// 周りの 4 点を、深度が自分に近いものほど重く混ぜる（深度を見た拡大）。どれも遠ければ、
// いちばん近い深度の点だけを使う

uniform sampler2D SmokeSampler;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform vec2 SmokeSize;
uniform vec2 OutSize;
// シェーダーパック使用中の明るさの倍率（パックによって絵の明るさの約束が違う）
uniform float Brightness;

out vec4 fragColor;

float distanceAt(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    if (depth >= 1.0) {
        return 4096.0;
    }
    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(p.xyz / p.w);
}

void main() {
    vec2 uv = gl_FragCoord.xy / OutSize;
    vec4 smoke;
    if (SmokeSize.x >= OutSize.x - 0.5) {
        smoke = texture(SmokeSampler, uv);
    } else {
        float own = distanceAt(uv);
        vec2 f = uv * SmokeSize - 0.5;
        vec2 base = floor(f);
        vec2 w = f - base;
        vec4 sum = vec4(0.0);
        float weightSum = 0.0;
        vec4 nearest = vec4(0.0);
        float nearestGap = 1.0e9;
        for (int i = 0; i < 4; i++) {
            vec2 offset = vec2(float(i & 1), float(i >> 1));
            vec2 texel = clamp(base + offset, vec2(0.0), SmokeSize - 1.0);
            vec2 tapUv = (texel + 0.5) / SmokeSize;
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
