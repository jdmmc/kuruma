#version 150

// 車体・ホイール・タイヤを GPU に置いたまま描く（CarMeshes）。中身はバニラの
// rendertype_entity_cutout_no_cull（1.20.1）とほぼ同じで、違うのは 3 つだけ:
//   - 頂点はメッシュの座標のまま。CPU で掛けていた PoseStack を MeshMat / MeshNormalMat で掛ける
//   - 明るさ（ライトマップの座標）は頂点ではなく MeshLight で 1 台ぶんまとめて渡す
//   - ダメージの赤み（オーバーレイ）は使わない（車は生き物ではないので常に無し）

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

uniform mat4 MeshMat;
uniform mat3 MeshNormalMat;
uniform vec2 MeshLight;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord0;

void main() {
    // CPU の描き方なら頂点バッファに入っていた座標
    vec4 pos = MeshMat * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * pos;

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * pos.xyz, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normalize(MeshNormalMat * Normal), Color);
    lightMapColor = texelFetch(Sampler2, ivec2(MeshLight) / 16, 0);
    texCoord0 = UV0;
}
