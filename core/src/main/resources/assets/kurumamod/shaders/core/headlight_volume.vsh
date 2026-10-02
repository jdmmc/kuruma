#version 150

// 画面いっぱいの四角形。頂点はそのまま正規化デバイス座標（-1..1）で渡してくる
in vec3 Position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
