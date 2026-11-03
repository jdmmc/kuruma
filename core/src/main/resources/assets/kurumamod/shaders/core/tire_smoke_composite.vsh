#version 150

// 画面いっぱいの三角形 1 枚。頂点の属性は使わない（gl_VertexID から作る）
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
