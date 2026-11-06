#version 330
#extension GL_ARB_separate_shader_objects : require

// 車体・ホイール・タイヤを GPU に置いたまま描く（CarMeshes）。フラグメントはバニラの core/entity をそのまま使い、
// 頂点だけ core/entity.vsh（ALPHA_CUTOUT・PER_FACE_LIGHTING・NO_OVERLAY）の写しにしてある。違うのは 3 つだけ:
//   - 頂点はメッシュの座標のまま。CPU で掛けていた PoseStack を TextureMat で受けて掛ける
//   - 明るさ（ライトマップの座標）は頂点ではなく ModelOffset.xy で 1 回の描画ぶんまとめて受ける
//   - ダメージの赤み（オーバーレイ）は使わない（車は生き物ではないので常に無し）
// TextureMat と ModelOffset はバニラの entity では使っていない枠。UBO を増やさずに済むのでここを借りている

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 5) in vec3 Normal;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
layout(location = 4) out vec4 lightMapColor;
layout(location = 6) out vec2 texCoord0;

void main() {
    // CPU の描き方なら頂点バッファに入っていた座標（カメラを原点にした世界の向き）
    vec3 pos = (TextureMat * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    // ホイールは軸ごとに違う拡大が掛かるので、逆転置で回す（PoseStack の法線行列と同じもの）
    vec3 normal = normalize(transpose(inverse(mat3(TextureMat))) * Normal);
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);

    lightMapColor = sample_lightmap(Sampler2, ivec2(ModelOffset.xy));
    texCoord0 = UV0;
}
