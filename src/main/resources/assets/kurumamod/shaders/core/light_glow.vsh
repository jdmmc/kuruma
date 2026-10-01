#version 150

// 光芒。頂点は車を描いた時点の行列で変換済み（カメラ基準）なので、ここでは射影だけ掛ける
in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
}
