"""同梱の S15 の塗装（色違いのテクスチャ）を作る。

    python tools/make_paints.py

元の s15.png の車体色（ブルー）の画素だけを別の色に置き換える。UV の配置は元のままなので、
vehicles/car.json の paints に並べればそのまま貼れる。窓・灯火・内装などの色は変えない。

車体色はちょうど BODY の 1 色で塗ってあるので、完全に一致する画素だけを塗り替える
（近い色まで巻き込むと、窓の縁などの混ざった画素の色がずれる）。

Pillow が要る（pip install pillow）。
"""

from pathlib import Path

from PIL import Image

TEXTURES = (Path(__file__).resolve().parent.parent / "core" / "src" / "main" / "resources"
            / "assets" / "kurumamod" / "textures" / "entity")
SOURCE = TEXTURES / "s15.png"

# 元のテクスチャの車体色
BODY = (5, 31, 140)

# 塗装の id → 色。id は vehicles/car.json の paints[].id と同じ。swatch にも同じ色を書く
PAINTS = {
    "red": (180, 16, 24),
    "black": (14, 14, 16),
    "white": (236, 236, 232),
    "silver": (150, 153, 158),
    "yellow": (240, 190, 16),
}


def main():
    source = Image.open(SOURCE).convert("RGBA")
    pixels = source.load()
    width, height = source.size
    body = [(x, y) for y in range(height) for x in range(width) if pixels[x, y][:3] == BODY]
    print(f"車体色の画素: {len(body)} / {width * height}")
    for paint, color in PAINTS.items():
        image = source.copy()
        out = image.load()
        for x, y in body:
            out[x, y] = (*color, pixels[x, y][3])
        target = TEXTURES / f"s15_{paint}.png"
        image.save(target, optimize=True)
        print(f"書き出し: {target.name}  #{color[0]:02X}{color[1]:02X}{color[2]:02X}")


if __name__ == "__main__":
    main()
