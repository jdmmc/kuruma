"""同梱のナンバープレートのメッシュ（OBJ）とテクスチャ（PNG）を作る。

使い方:
    python tools/make_plates.py

書き出し先（core/src/main/resources/assets/kurumamod/）:
    models/plate/<様式>.obj         プレートの板と字の枠（text_<欄>）
    textures/plate/<様式>.png       地の色・縁・帯。字は描かない（MOD が焼き込む）

寸法と字の枠は tools/blender_plate_gauge_<様式>.py と同じ値。<b>ゲージを直したらここも直す。</b>
実在の州・国の図柄は写さない。汎用の意匠にしてある。

OBJ の座標:
    MOD（ObjModel）は読み込みで X と Z を反転する（OBJ の (x, y, z) がエンティティの (-x, y, -z)）。
    プレートは表を前（エンティティの -Z）へ向けるので、OBJ では表が +Z、取り付け面が z = 0。
    UV はプレートの表の全体に 0〜1（正面から見て左が u = 0、下が v = 0）。
"""

import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "core/src/main/resources/assets/kurumamod"
MM = 0.001
THICKNESS = 1.5 * MM
# テクスチャの横の画素数。字は MOD がこの解像度に合わせて焼くので、ここは地の絵の細かさだけ
TEXTURE_WIDTH = 1024

# 様式ごとの外形 [mm] と字の枠 (欄, 左, 下, 右, 上) [mm]。プレートの中心が原点、右・上が正
LAYOUTS = {
    "jp": ((330.0, 165.0), [
        ("region", -118.0, 28.0, -4.0, 72.0),
        ("class", 4.0, 28.0, 118.0, 72.0),
        ("kana", -152.0, -66.0, -110.0, 4.0),
        ("number", -100.0, -72.0, 152.0, 18.0),
    ]),
    "eu": ((520.0, 110.0), [
        ("country", -255.0, -48.0, -220.0, -14.0),
        ("text", -205.0, -40.0, 250.0, 40.0),
    ]),
    "uk": ((520.0, 111.0), [
        ("text", -240.0, -40.0, 240.0, 40.0),
    ]),
    "us": ((304.8, 152.4), [
        ("top", -120.0, 50.0, 120.0, 70.0),
        ("text", -140.0, -38.0, 140.0, 40.0),
        ("bottom", -120.0, -70.0, 120.0, -50.0),
    ]),
}


def write_obj(name, size, fields):
    width, height = size[0] * MM, size[1] * MM
    hw, hh = width / 2, height / 2
    lines = ["# KurumaMod のナンバープレート（tools/make_plates.py が書き出す）", f"o plate_{name}"]
    verts = []
    uvs = []

    def v(x, y, z):
        verts.append((x, y, z))
        return len(verts)

    def vt(u, w):
        uvs.append((u, w))
        return len(uvs)

    def uv_of(x, y):
        return vt((x + hw) / width, (y + hh) / height)

    # 表（+Z）。UV は表の全体
    front = [v(-hw, -hh, THICKNESS), v(hw, -hh, THICKNESS), v(hw, hh, THICKNESS), v(-hw, hh, THICKNESS)]
    front_uv = [uv_of(-hw, -hh), uv_of(hw, -hh), uv_of(hw, hh), uv_of(-hw, hh)]
    back = [v(-hw, -hh, 0.0), v(hw, -hh, 0.0), v(hw, hh, 0.0), v(-hw, hh, 0.0)]
    # 裏と縁は縁の色（左上の隅の画素）を写す
    edge_uv = vt(0.004, 0.996)

    lines += [f"v {x:.6f} {y:.6f} {z:.6f}" for x, y, z in verts]
    lines += [f"vt {u:.6f} {w:.6f}" for u, w in uvs]
    lines += ["vn 0 0 1", "vn 0 0 -1", "vn 0 -1 0", "vn 1 0 0", "vn 0 1 0", "vn -1 0 0"]
    lines.append("s off")
    lines.append("f " + " ".join(f"{a}/{t}/1" for a, t in zip(front, front_uv)))
    lines.append("f " + " ".join(f"{a}/{edge_uv}/2" for a in reversed(back)))
    for (a, b, normal) in ((0, 1, 3), (1, 2, 4), (2, 3, 5), (3, 0, 6)):
        lines.append(f"f {back[a]}/{edge_uv}/{normal} {back[b]}/{edge_uv}/{normal} "
                     f"{front[b]}/{edge_uv}/{normal} {front[a]}/{edge_uv}/{normal}")

    # 字の枠。表に重ねる（描かれない。MOD が位置と大きさだけ読む）
    for field, left, bottom, right, top in fields:
        base = len(verts)
        z = THICKNESS
        quad = [(left * MM, bottom * MM), (right * MM, bottom * MM), (right * MM, top * MM), (left * MM, top * MM)]
        lines.append(f"o text_{field}")
        for x, y in quad:
            verts.append((x, y, z))
            lines.append(f"v {x:.6f} {y:.6f} {z:.6f}")
        lines.append("f " + " ".join(f"{base + i + 1}/{edge_uv}/1" for i in range(4)))

    path = ASSETS / "models/plate" / f"{name}.obj"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return path


def canvas(size, color):
    width = TEXTURE_WIDTH
    height = round(width * size[1] / size[0])
    image = Image.new("RGBA", (width, height), color)
    return image, ImageDraw.Draw(image), width / size[0]


def mm_box(px, size, left, bottom, right, top):
    """mm（中心原点・上が正）→ 画素（左上原点）。"""
    w, h = size
    return ((left + w / 2) * px, (h / 2 - top) * px, (right + w / 2) * px, (h / 2 - bottom) * px)


def jp_texture(name, background, line):
    size = LAYOUTS["jp"][0]
    image, draw, px = canvas(size, background)
    # 縁取り（実物は板の縁が折り返してある）
    inset = 6 * px
    draw.rounded_rectangle((inset, inset, image.width - inset, image.height - inset),
                           radius=10 * px, outline=line, width=round(3 * px))
    # 上のボルト穴の座金（左右）。字の枠に掛からない外側に置く
    for x_mm in (-142.0, 142.0):
        cx, cy = (x_mm + size[0] / 2) * px, (size[1] / 2 - 62.0) * px
        r = 8 * px
        draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(190, 192, 196, 255), outline=(120, 122, 128, 255),
                     width=round(1.2 * px))
    save(image, name)


def eu_texture():
    size = LAYOUTS["eu"][0]
    image, draw, px = canvas(size, (246, 246, 242, 255))
    w, h = image.width, image.height
    draw.rounded_rectangle((2, 2, w - 2, h - 2), radius=8 * px, outline=(20, 20, 20, 255), width=round(2.5 * px))
    # 左端の青帯と、星の輪（12 個）
    band = 45 * px
    draw.rounded_rectangle((5 * px, 5 * px, band, h - 5 * px), radius=5 * px, fill=(0, 51, 153, 255))
    cx, cy, ring = band / 2 + 2.5 * px, h * 0.30, 11 * px
    for i in range(12):
        angle = math.radians(i * 30)
        star(draw, cx + ring * math.cos(angle), cy + ring * math.sin(angle), 2.6 * px, (255, 204, 0, 255))
    save(image, "eu")


def star(draw, cx, cy, r, color):
    points = []
    for i in range(10):
        radius = r if i % 2 == 0 else r * 0.42
        angle = math.radians(-90 + i * 36)
        points.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    draw.polygon(points, fill=color)


def uk_texture(name, background):
    size = LAYOUTS["uk"][0]
    image, draw, px = canvas(size, background)
    w, h = image.width, image.height
    draw.rounded_rectangle((2, 2, w - 2, h - 2), radius=8 * px, outline=(20, 20, 20, 255), width=round(2.0 * px))
    save(image, name)


def us_texture():
    size = LAYOUTS["us"][0]
    image, draw, px = canvas(size, (250, 250, 248, 255))
    w, h = image.width, image.height
    # 上下に淡い色の帯（州の図柄の代わり。実在の州の絵柄は写さない）
    for y in range(h):
        t = y / (h - 1)
        edge = max(0.0, 1.0 - min(t, 1.0 - t) / 0.22)
        r = round(250 - 70 * edge)
        g = round(250 - 40 * edge)
        b = round(248 - 5 * edge)
        draw.line((0, y, w, y), fill=(r, g, b, 255))
    draw.rounded_rectangle((3, 3, w - 3, h - 3), radius=12 * px, outline=(30, 50, 110, 255), width=round(2.5 * px))
    for x_mm in (-88.9, 88.9):
        for y_mm in (60.0, -60.0):
            cx, cy = (x_mm + size[0] / 2) * px, (size[1] / 2 - y_mm) * px
            r = 4 * px
            draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(200, 202, 206, 255))
    save(image, "us")


def save(image, name):
    path = ASSETS / "textures/plate" / f"{name}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    print("書き出しました:", path.relative_to(ROOT))


def main():
    for name, (size, fields) in LAYOUTS.items():
        print("書き出しました:", write_obj(name, size, fields).relative_to(ROOT))
    jp_texture("jp_private", (244, 244, 240, 255), (30, 86, 49, 255))
    jp_texture("jp_kei", (243, 200, 0, 255), (20, 20, 20, 255))
    jp_texture("jp_commercial", (30, 86, 49, 255), (240, 240, 236, 255))
    eu_texture()
    uk_texture("uk_front", (246, 246, 242, 255))
    uk_texture("uk_rear", (247, 209, 23, 255))
    us_texture()


main()
