#version 330
#extension GL_ARB_separate_shader_objects : require

// 鏡面に景色を貼る（MirrorRenderer）。バニラの position_tex_color.fsh と同じ入力で、
// 不透明度を見て捨てない・いつも不透明で出す、の 2 点だけが違う。
// 鏡用の描き先は不透明度 0 の霧の色で塗ってから世界を描くので、空の画素は不透明度 0 のまま残る。
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 color = texture(Sampler0, texCoord0).rgb * vertexColor.rgb * ColorModulator.rgb;
    fragColor = vec4(color, 1.0);
}
