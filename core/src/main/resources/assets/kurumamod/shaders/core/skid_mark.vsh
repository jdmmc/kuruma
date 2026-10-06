#version 150

// タイヤ痕（シェーダーパック使用中）。頂点はカメラ基準の座標で、ここで残りの行列を掛ける
in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
}
