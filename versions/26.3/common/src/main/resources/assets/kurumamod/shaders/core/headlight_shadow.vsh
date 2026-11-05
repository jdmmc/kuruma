#version 330
#extension GL_ARB_separate_shader_objects : require

// 画面いっぱいの三角形 1 枚（バニラの core/screenquad と同じ）。影の地図を描く
layout(location = 0) out vec2 texCoord;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    texCoord = uv;
}
