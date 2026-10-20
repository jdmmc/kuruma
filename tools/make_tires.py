"""同梱のタイヤ（ダート・オフロード）のメッシュとテクスチャを作る。

    python tools/make_tires.py

タイヤは回転体なので、Blender で作るより式で作るほうが寸法を揃えやすい。
寸法はノーマルタイヤ（models/entity/tire.obj）と同じ: 内径 0.334m・外径 0.450m・幅 約 0.38m。
内径と外径は描くときにホイールへ合わせ直される（ObjModel#fitRadially）ので、比だけが効く。

座標は OBJ のまま（車軸が X、半径が Y・Z）。ObjModel が読み込み時に X と Z を反転するが、
タイヤはほぼ左右対称なので気にしなくてよい。
"""

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "core" / "src" / "main" / "resources" / "assets" / "kurumamod"
MODELS = ROOT / "models" / "entity"
TEXTURES = ROOT / "textures" / "entity"

INNER = 0.334
OUTER = 0.450
SEGMENTS = 72

# テクスチャは 4x4 の色見本。列ごとに 1 色で、UV は列の中央を指す
SIDEWALL, CROWN, BLOCK_TOP, BLOCK_SIDE = 0, 1, 2, 3
COLORS = [(0x26, 0x26, 0x26), (0x1c, 0x1c, 0x1c), (0x14, 0x14, 0x14), (0x1f, 0x1f, 0x1f)]


def uv(column):
    return ((column + 0.5) / 4.0, 0.5)


class Mesh:
    def __init__(self):
        self.v = []
        self.vt = []
        self.vn = []
        self.faces = []  # [(v, vt, vn), ...]
        self.index = {}  # 同じ値は使い回す（ファイルを小さくするため）

    def _shared(self, kind, values, value):
        key = (kind, tuple(round(c, 6) for c in value))
        if key not in self.index:
            values.append(key[1])
            self.index[key] = len(values)
        return self.index[key]

    def vertex(self, p):
        return self._shared("v", self.v, p)

    def texcoord(self, t):
        return self._shared("vt", self.vt, t)

    def normal(self, n):
        length = math.sqrt(sum(c * c for c in n)) or 1.0
        return self._shared("vn", self.vn, tuple(c / length for c in n))

    def quad(self, corners, normals, column):
        t = self.texcoord(uv(column))
        self.faces.append([(self.vertex(p), t, self.normal(n)) for p, n in zip(corners, normals)])

    def write(self, path, name):
        lines = ["# KurumaMod tools/make_tires.py が生成", "mtllib tire_block.mtl", f"o {name}"]
        lines += [f"v {x:.6f} {y:.6f} {z:.6f}" for x, y, z in self.v]
        lines += [f"vt {u:.6f} {w:.6f}" for u, w in self.vt]
        lines += [f"vn {x:.6f} {y:.6f} {z:.6f}" for x, y, z in self.vn]
        lines += ["s 0", "usemtl Tire_Rubber"]
        lines += ["f " + " ".join(f"{a}/{b}/{c}" for a, b, c in face) for face in self.faces]
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def polar(x, r, theta):
    return (x, r * math.cos(theta), r * math.sin(theta))


def carcass(mesh, crown):
    """タイヤの胴体。断面（x, 半径）を車軸まわりに回す。片側のビードから反対側のビードまで。"""
    half = [(0.170, INNER), (0.184, 0.352), (0.192, 0.376), (0.190, 0.398),
            (0.180, crown - 0.010), (0.160, crown), (0.080, crown)]
    profile = half + [(0.0, crown)] + [(-x, r) for x, r in reversed(half)]
    # 断面の外向きの法線（x, r 平面）。隣の点との接線を 90 度回す
    normals = []
    for i, (x, r) in enumerate(profile):
        x0, r0 = profile[max(0, i - 1)]
        x1, r1 = profile[min(len(profile) - 1, i + 1)]
        tx, tr = x1 - x0, r1 - r0
        # +x のビードから -x のビードへたどるので、接線を時計回りに 90 度回すと常に外向きになる
        normals.append((tr, -tx))
    for s in range(SEGMENTS):
        a0 = 2 * math.pi * s / SEGMENTS
        a1 = 2 * math.pi * (s + 1) / SEGMENTS
        for i in range(len(profile) - 1):
            (x0, r0), (x1, r1) = profile[i], profile[i + 1]
            (nx0, nr0), (nx1, nr1) = normals[i], normals[i + 1]
            corners = [polar(x0, r0, a0), polar(x1, r1, a0), polar(x1, r1, a1), polar(x0, r0, a1)]
            ns = [polar(nx0, nr0, a0), polar(nx1, nr1, a0), polar(nx1, nr1, a1), polar(nx0, nr0, a1)]
            column = CROWN if min(r0, r1) >= crown - 0.001 else SIDEWALL
            mesh.quad(corners, ns, column)


def block(mesh, theta, half_angle, x0, x1, base, top):
    """トレッドのブロック 1 つ。円周方向に theta±half_angle、車軸方向に x0..x1、半径 base..top の箱。底は作らない。"""
    a0, a1 = theta - half_angle, theta + half_angle
    am = theta
    # 天面
    mesh.quad([polar(x0, top, a0), polar(x1, top, a0), polar(x1, top, a1), polar(x0, top, a1)],
              [polar(0, 1, am)] * 4, BLOCK_TOP)
    # 円周方向の前後
    for a, sign in ((a0, -1), (a1, 1)):
        n = (0.0, -math.sin(a) * sign, math.cos(a) * sign)
        mesh.quad([polar(x0, base, a), polar(x1, base, a), polar(x1, top, a), polar(x0, top, a)],
                  [n] * 4, BLOCK_SIDE)
    # 車軸方向の両脇
    for x, sign in ((x0, -1), (x1, 1)):
        mesh.quad([polar(x, base, a0), polar(x, top, a0), polar(x, top, a1), polar(x, base, a1)],
                  [(sign, 0.0, 0.0)] * 4, BLOCK_SIDE)


def dirt():
    """ダート（グラベル）タイヤ。細かいブロックを 3 列、互い違いに。溝が多いので砂利を掴む。"""
    mesh = Mesh()
    crown = OUTER - 0.018
    carcass(mesh, crown)
    slots = 36
    for i in range(slots):
        theta = 2 * math.pi * i / slots
        half = math.pi / slots * 0.62
        block(mesh, theta, half, -0.042, 0.042, crown - 0.004, OUTER)
        shifted = theta + math.pi / slots
        block(mesh, shifted, half, 0.068, 0.150, crown - 0.004, OUTER)
        block(mesh, shifted, half, -0.150, -0.068, crown - 0.004, OUTER)
    return mesh


def offroad():
    """オフロード（マッド）タイヤ。肩まで回り込む大きなラグを左右から互い違いに、反対側に短いラグ。溝が深く広い。"""
    mesh = Mesh()
    crown = OUTER - 0.030
    carcass(mesh, crown)
    slots = 24
    for i in range(slots):
        theta = 2 * math.pi * i / slots
        half = math.pi / slots * 0.58
        side = 1 if i % 2 == 0 else -1
        # 長いラグ（中央を越えて反対側へ）
        x0, x1 = sorted((-0.035 * side, 0.165 * side))
        block(mesh, theta, half, x0, x1, crown - 0.006, OUTER)
        # 反対側の短いラグ。半スロットずらして、長いラグの間を埋める
        shifted = theta + math.pi / slots
        sx0, sx1 = sorted((-0.075 * side, -0.165 * side))
        block(mesh, shifted, half * 0.85, sx0, sx1, crown - 0.006, OUTER)
        # 肩のラグ（サイドウォールへ回り込む）
        for x_side, angle in ((side, theta), (-side, shifted)):
            bx0, bx1 = sorted((0.160 * x_side, 0.194 * x_side))
            block(mesh, angle, half * 0.8, bx0, bx1, 0.392, OUTER - 0.010)
    return mesh


def write_png(path, pixels, width, height):
    raw = b"".join(b"\x00" + bytes(c for px in pixels[y * width:(y + 1) * width] for c in px)
                   for y in range(height))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def main():
    MODELS.mkdir(parents=True, exist_ok=True)
    TEXTURES.mkdir(parents=True, exist_ok=True)
    (MODELS / "tire_block.mtl").write_text(
        "# KurumaMod tools/make_tires.py が生成\n"
        "newmtl Tire_Rubber\nKd 1.000000 1.000000 1.000000\nd 1.000000\n", encoding="utf-8")
    for name, build in (("tire_dirt", dirt), ("tire_offroad", offroad)):
        mesh = build()
        mesh.write(MODELS / f"{name}.obj", "Tire")
        print(f"{name}: 頂点 {len(mesh.v)}・面 {len(mesh.faces)}")
    write_png(TEXTURES / "tire_block.png", [COLORS[x] for y in range(4) for x in range(4)], 4, 4)


if __name__ == "__main__":
    main()
