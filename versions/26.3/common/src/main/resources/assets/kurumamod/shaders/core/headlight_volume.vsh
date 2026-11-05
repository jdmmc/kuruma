#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#define HEADLIGHT_INFO_BLOCK_ONLY
#include <kurumamod:headlight_info.glsl>

// 画面いっぱいの三角形 1 枚（バニラの core/screenquad と同じ）。
//
// 画面から世界へ戻す行列（射影と視点の回転の逆）はここで 1 度だけ作って、画素へは flat で渡す。
// <b>射影は Projection のブロック（ProjMat）から取る。</b>Java 側の CameraRenderState#projectionMatrix には
// 画面の揺れ（歩くときの揺れ・被弾の傾き）が入っておらず、ワールドはそれを掛けた行列で描かれている
// （GameRenderer#renderLevel）。こちらを使わないと、揺れたぶんだけ照らす場所がずれる
layout(location = 0) out vec2 texCoord;
layout(location = 1) flat out vec4 invCol0;
layout(location = 2) flat out vec4 invCol1;
layout(location = 3) flat out vec4 invCol2;
layout(location = 4) flat out vec4 invCol3;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    texCoord = uv;
    mat4 inv = ViewInverse * inverse(ProjMat);
    invCol0 = inv[0];
    invCol1 = inv[1];
    invCol2 = inv[2];
    invCol3 = inv[3];
}
