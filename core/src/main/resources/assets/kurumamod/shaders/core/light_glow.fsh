#version 150

// 色をそのまま出す。足し方（加算）は呼ぶ側が決める（SRC_ALPHA, ONE）
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a <= 0.0) {
        discard;
    }
    fragColor = vertexColor;
}
