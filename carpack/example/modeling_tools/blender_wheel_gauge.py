"""ホイール（リム）のモデルを作るときの基準（ゲージ）を置くスクリプト。

車体用は blender_gauge.py、タイヤ用は blender_tire_gauge.py。
<b>ホイールは車体ともタイヤとも別ファイルで作る</b>ので、こちらを使う。

使い方:
    Blender の Scripting タブ → 新規テキスト → このファイルを貼る → 実行
    （何度でも実行してよい。前に置いたゲージは消してから置き直す）

寸法は同梱の TE37（wheel-te37.obj）から測った値。これに収めれば、
同梱のタイヤやタイヤ部品がそのまま嵌まる。

置くもの:
    - リムの外形（円柱）… 半径 0.352m・幅 0.32m。<b>リムはこの中に収める</b>
    - 表面の限界（輪）… X = +0.173m。<b>デザイン面はここより外へ出さない</b>
    - ビードの輪 … 半径 0.334m。タイヤの内径がここへ来る（参考）
    - タイヤの断面（線）… 嵌まるタイヤの輪郭。ホイールとの隙間を見る用
    - 車軸の線 … 原点を通る左右方向の線。<b>回転軸はこれ</b>
    - 地面 … タイヤが接する高さ（原点からタイヤ半径ぶん下）
    - 表を示す矢印 … <b>ホイールの表はこちら（+X）へ向ける</b>

ホイールに求められること:
    - <b>原点は車軸の中心。</b>ホイールの底ではない
    - <b>回転軸は左右方向（X）。</b>Blender の既定の円柱は軸が Z なので、
      Y まわりに 90 度回してから作ること
    - <b>左側用を 1 つだけ作る。</b>右側は MOD 側が鏡像にして描く
      （pose.scale(-1, 1, 1)。だから RenderType はカリング無し）
    - Blender で前方を -Y に向けると <b>+X が車体の左</b>になる。
      ホイールの表（デザイン面）を +X へ向けるのがこれにあたる
    - <b>リムの一番外（半径の最大）がタイヤの内径を決める。</b>タイヤの内径は
      リムの外縁の 95%（0.352m → 0.334m）に置かれる。リムを大きくすれば
      サイドウォールが薄くなる（インチアップ）
    - <b>オブジェクト名を tire で始めない。</b>その名前は「ホイールに付属のタイヤ」として扱われ、
      タイヤ部品を履いたときに隠される

書き出すときの注意:
    - ゲージは "kuruma_wheel_gauge" コレクションに入るので、<b>「Selection Only」で
      ホイールだけを書き出す</b>こと（ObjModel は gauge_ で始まる名前を捨てるので、
      混ざっても描かれはしないが、要らないデータが jar に入る）
    - <b>「Triangulated Mesh」は入れない。</b>四角形のままのほうが軽く（頂点が 4 割ほど減る）、
      凹んだ n-gon も ObjModel が耳切り法で正しく割る
    - 向きとエクスポート設定は標準のまま（前方 -Y・上 +Z、-Z Forward / Y Up）
    - 部品の JSON の designRadius（車のホイールなら vehicles/car.json の designWheelRadius）には、
      下で表示される「タイヤ半径」を書く
"""

import bpy
from math import radians

# --- 倍率。TE37 と同じ大きさで作るなら 1.0 のまま ----------------------
SCALE = 1.0
# ---------------------------------------------------------------------

# TE37 の実寸 [m]
TYRE_RADIUS = 0.450 * SCALE     # タイヤの外径。designRadius に書く値
RIM_RADIUS = 0.352 * SCALE      # リムの外縁
BEAD_RADIUS = 0.334 * SCALE     # タイヤの内径（リムの外縁の 95%）
RIM_WIDTH = 0.32 * SCALE        # リムの幅（ビードからビードまで）
FACE_LIMIT_X = 0.173 * SCALE    # デザイン面の一番外

# タイヤの断面（TE37 のタイヤ）。(車軸方向, 半径) をビードから踏面へ。左右対称
TYRE_PROFILE = [(0.161, 0.334), (0.174, 0.352), (0.190, 0.390),
                (0.180, 0.425), (0.153, 0.444), (0.106, 0.450)]

COLLECTION = "kuruma_wheel_gauge"


def _fresh_collection(name):
    collection = bpy.data.collections.get(name)
    if collection is not None:
        for obj in list(collection.objects):
            bpy.data.objects.remove(obj, do_unlink=True)
    else:
        collection = bpy.data.collections.new(name)
    if name not in bpy.context.scene.collection.children:
        bpy.context.scene.collection.children.link(collection)
    return collection


def _move_to(obj, collection, name):
    obj.name = name
    obj.display_type = 'WIRE'
    obj.hide_select = True
    for current in list(obj.users_collection):
        current.objects.unlink(obj)
    collection.objects.link(obj)


def _cylinder(collection, name, radius, depth, x=0.0, vertices=48):
    # 既定の円柱は軸が Z なので、Y まわりに 90 度回して軸を左右（X）へ向ける
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=vertices, radius=radius, depth=depth,
        rotation=(0.0, radians(90.0), 0.0), location=(x, 0.0, 0.0))
    _move_to(bpy.context.object, collection, name)


def _ring(collection, name, radius, x):
    bpy.ops.mesh.primitive_circle_add(
        vertices=48, radius=radius, fill_type='NOTHING',
        rotation=(0.0, radians(90.0), 0.0), location=(x, 0.0, 0.0))
    _move_to(bpy.context.object, collection, name)


def _tyre_section(collection, name):
    # 断面を上と下の 2 か所に置く（Y = 0 の面）。ビード同士は直線で閉じる
    half = [(x * SCALE, r * SCALE) for x, r in TYRE_PROFILE]
    loop = half + [(-x, r) for x, r in reversed(half)]
    verts = []
    edges = []
    for sign in (1.0, -1.0):
        start = len(verts)
        verts += [(x, 0.0, sign * r) for x, r in loop]
        edges += [(start + i, start + (i + 1) % len(loop)) for i in range(len(loop))]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, edges, [])
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    _move_to(obj, collection, name)


def build():
    collection = _fresh_collection(COLLECTION)

    # リムの外形。ホイールはこの円柱の中へ収める
    _cylinder(collection, "gauge_rim_envelope", RIM_RADIUS, RIM_WIDTH)

    # デザイン面の限界。表（+X）はこの輪の面より外へ出さない
    _ring(collection, "gauge_face_limit", RIM_RADIUS, FACE_LIMIT_X)

    # ビード。タイヤの内径がここへ来る
    _ring(collection, "gauge_bead_outer", BEAD_RADIUS, RIM_WIDTH * 0.5)
    _ring(collection, "gauge_bead_inner", BEAD_RADIUS, -RIM_WIDTH * 0.5)

    # 嵌まるタイヤの断面
    _tyre_section(collection, "gauge_tyre_section")

    # 車軸。原点を通る左右方向の線で、これがホイールの回転軸になる
    _cylinder(collection, "gauge_axle", TYRE_RADIUS * 0.03, RIM_WIDTH * 3.0, vertices=8)

    # 地面。原点からタイヤ半径ぶん下。タイヤがここに接する
    bpy.ops.mesh.primitive_plane_add(
        size=TYRE_RADIUS * 3.0, location=(0.0, 0.0, -TYRE_RADIUS))
    _move_to(bpy.context.object, collection, "gauge_ground")

    # 表の向き。ホイールのデザイン面はこちら（+X ＝ 車体の左）へ向ける。
    # 円錐の既定の向きは +Z なので、Y まわりに 90 度回して +X を指させる
    bpy.ops.mesh.primitive_cone_add(
        vertices=8,
        radius1=TYRE_RADIUS * 0.12,
        depth=TYRE_RADIUS * 0.3,
        rotation=(0.0, radians(90.0), 0.0),
        location=(FACE_LIMIT_X + TYRE_RADIUS * 0.35, 0.0, 0.0))
    _move_to(bpy.context.object, collection, "gauge_outer_face")

    print("ホイールのゲージを置きました（TE37 の {:.2f} 倍）".format(SCALE))
    print("  リムの外縁 半径 {:.3f} m・幅 {:.3f} m".format(RIM_RADIUS, RIM_WIDTH))
    print("  デザイン面は X = {:.3f} m まで".format(FACE_LIMIT_X))
    print("  タイヤ半径 {:.3f} m（タイヤの内径 {:.3f} m）".format(TYRE_RADIUS, BEAD_RADIUS))
    print("  原点は車軸の中心。回転軸は左右（X）。矢印の向き（+X）が表")
    print("  部品の JSON の designRadius（車のホイールなら designWheelRadius）に {:.4f} と書くこと"
          .format(TYRE_RADIUS))


build()
