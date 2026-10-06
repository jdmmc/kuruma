#version 150

// タイヤ痕（シェーダーパック使用中）。路面の色に掛けて暗くする（足し方は呼ぶ側の ZERO, ONE_MINUS_SRC_ALPHA）。
// 前後は粒を描く前の深度で見ているので、煙の後ろでも消えない。代わりに、粒を描いた後の深度
// （FinalDepthSampler）で煙が手前にあると分かる画素は ThroughSmoke まで薄める。
// そのままだと煙の中で跡だけがくっきり浮いた
uniform sampler2D FinalDepthSampler;
uniform vec2 ScreenSize;
uniform float ThroughSmoke;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float alpha = vertexColor.a;
    float front = texture(FinalDepthSampler, gl_FragCoord.xy / ScreenSize).r;
    if (front < gl_FragCoord.z - 1.0e-6) {
        alpha *= ThroughSmoke;
    }
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, alpha);
}
