#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <kurumamod:tire_smoke_info.glsl>

// 画面いっぱいの三角形 1 枚（バニラの core/screenquad と同じ）。頂点の属性は使わない。
// 画面から世界へ戻す行列はここで 1 度だけ作って flat で渡す（射影は Projection のブロックから。tire_smoke.vsh と同じ理由）
layout(location = 0) flat out vec4 vInv0;
layout(location = 1) flat out vec4 vInv1;
layout(location = 2) flat out vec4 vInv2;
layout(location = 3) flat out vec4 vInv3;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    mat4 inv = inverse(ProjMat * SmokeView);
    vInv0 = inv[0];
    vInv1 = inv[1];
    vInv2 = inv[2];
    vInv3 = inv[3];
}
