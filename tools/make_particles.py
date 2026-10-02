# -*- coding: utf-8 -*-
"""タイヤの白煙の粒の絵（tire_smoke_0.png 〜 tire_smoke_11.png）を描く。

バニラの big_smoke_* と同じ流儀（16px のドット絵・3 階調・膨らんでから崩れて消える
12 コマ）で、**色だけ白い**もの。big_smoke は可視部の RGB が 0.5 前後の灰色なので、
粒の色は掛け算で半分の明るさにしか出ず、白い煙が作れない。白い generic_*（CLOUD）は
形がドット絵の丸い塊で煙に見えなかった。

**バニラの絵を塗り直して同梱してはいけない**（Mojang の絵の改変を配ることになる）。
ここでは形をゼロから描いている: 固定の配置の小さな円（メタボール）をいくつか重ねて
輪郭を作り、コマが進むほど半径を変え、後半は決まった雑音で虫食いにして崩す。
乱数の種を固定してあるので、何度実行しても同じ絵になる。

色の濃淡は左上が明るく右下が暗い 3 階調。明るさの本体は粒の頂点色（DustParticle）が
決めるので、絵のほうはほぼ白に留めてある。

    python tools/make_particles.py
"""
import math
import random

from PIL import Image

OUT = "core/src/main/resources/assets/kurumamod/textures/particle/"
SIZE = 16
FRAMES = 12
# 3 階調（明・中・暗）。暗い側も 0.86 までしか落とさない
TONES = [(255, 255, 255), (238, 237, 235), (220, 218, 215)]

# コマごとの大きさ（1 が最大）。big_smoke と同じく途中で最大になり、そこから縮む
GROWTH = [0.42, 0.55, 0.68, 0.80, 0.90, 0.97, 1.00, 0.96, 0.86, 0.72, 0.56, 0.40]
# コマごとの虫食いの割合。後半ほど崩れてちぎれる
EROSION = [0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.03, 0.08, 0.16, 0.28, 0.42, 0.60]


def lobes(rng):
    """輪郭を作る円の配置。中心の大きな円のまわりに小さな瘤を並べる。"""
    result = [(0.0, 0.0, 3.6)]
    count = 6
    for i in range(count):
        angle = 2.0 * math.pi * i / count + rng.uniform(-0.35, 0.35)
        distance = rng.uniform(2.6, 3.6)
        # 下側は平たく（煙の塊は下が潰れて見える）
        dy = math.sin(angle) * distance * (0.7 if math.sin(angle) > 0 else 1.0)
        result.append((math.cos(angle) * distance, dy, rng.uniform(1.8, 2.8)))
    return result


def frame(index, layout, noise):
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    scale = GROWTH[index] * 1.05
    center = (SIZE - 1) / 2.0
    for y in range(SIZE):
        for x in range(SIZE):
            px = (x - center) / scale
            py = (y - center - 0.5) / scale
            # メタボール。1 を超えれば中
            field = 0.0
            for lx, ly, radius in layout:
                d2 = (px - lx) ** 2 + (py - ly) ** 2
                field += radius * radius / max(d2, 0.01)
            # 輪郭をぎざぎざにする
            field *= 0.85 + 0.3 * noise[y][x][0]
            if field < 1.6:
                continue
            # 虫食い。縁から欠ける（中に穴を散らすと網のように見える）
            edge = 1.6 / field
            if noise[y][x][1] < EROSION[index] * 2.2 * edge * edge:
                continue
            # 左上が明るく、右下が暗い。雑音でまだらにする
            shade = (px + py) / 9.0 + (noise[y][x][2] - 0.5) * 0.7
            tone = 0 if shade < -0.15 else (1 if shade < 0.35 else 2)
            image.putpixel((x, y), TONES[tone] + (255,))
    return image


def main():
    rng = random.Random(20260929)
    layout = lobes(rng)
    noise = [[(rng.random(), rng.random(), rng.random()) for _ in range(SIZE)] for _ in range(SIZE)]
    for i in range(FRAMES):
        frame(i, layout, noise).save(f"{OUT}tire_smoke_{i}.png")
    print(f"wrote {FRAMES} frames to {OUT}")


if __name__ == "__main__":
    main()
