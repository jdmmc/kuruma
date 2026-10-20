"""タイヤのモデルを作るときの基準（ゲージ）を置くスクリプト。

車体用は blender_gauge.py、ホイール用は blender_wheel_gauge.py。
<b>タイヤは車体ともホイールとも別ファイルで作る</b>ので、こちらを使う。

使い方:
    Blender の Scripting タブ → 新規テキスト → このファイルを貼る → 実行
    （何度でも実行してよい。前に置いたゲージは消してから置き直す）

寸法は同梱の TE37 のタイヤ（models/entity/tire.obj。KurumaMod 標準のタイヤ）から測った値。

置くもの:
    - タイヤの外形（円柱）… 外径 0.450m・幅 0.38m。<b>タイヤはこの中に収める</b>
    - 内径の円柱 … 半径 0.334m・幅 0.32m（ビードからビードまで）。ホイールのリムが入るところ。
      <b>この幅はホイールのゲージのリムの幅と同じ規格。</b>ビードを円柱の両端に合わせれば、
      どのホイールにも嵌まる
    - 踏面の輪 … 幅 0.21m。踏面（地面に当たる平らなところ）の目安
    - TE37 のタイヤの断面（線）… 形の目安
    - 車軸の線 … 原点を通る左右方向の線。<b>回転軸はこれ</b>
    - 地面 … タイヤが接する高さ（原点から外径ぶん下）

タイヤに求められること:
    - <b>原点は車軸の中心。</b>タイヤの底ではない
    - <b>回転軸は左右方向（X）。</b>Blender の既定の円柱・トーラスは軸が Z なので、
      Y まわりに 90 度回してから作ること
    - <b>タイヤだけを作る。</b>ホイールは入れない
    - <b>太さはメッシュで決めない。</b>細いタイヤ・太いタイヤを作り分けず、ビード間をこの幅で作る。
      太さは換装画面の「太さ」のスライダーがホイールとタイヤにまとめて掛ける
    - <b>内径と外径はメッシュから測る</b>（半径の最小が内径、最大が外径）。描くときに
      内径がホイールのリムへ、外径が車のタイヤ半径へ来るよう半径方向に伸び縮みし、
      幅は外径の比で拡大される。だから<b>大きさは比だけが効く</b>が、このゲージに合わせておけば
      TE37 と並べたときの見た目がそのまま再現される
    - 内径より内側（ビードの縁より中）に頂点を置かない。内径の測り間違いになる
    - ほぼ左右対称に作る（読み込み時に X が反転する）

書き出すときの注意:
    - ゲージは "kuruma_tire_gauge" コレクションに入るので、<b>「Selection Only」で
      タイヤだけを書き出す</b>こと（ObjModel は gauge_ で始まる名前を捨てるので、
      混ざっても描かれはしないが、要らないデータが jar に入る）
    - <b>「Triangulated Mesh」は入れない。</b>四角形のままのほうが軽く（頂点が 4 割ほど減る）、
      凹んだ n-gon も ObjModel が耳切り法で正しく割る
    - 向きとエクスポート設定は標準のまま（前方 -Y・上 +Z、-Z Forward / Y Up）
    - 部品の JSON（"tire" ブロック）に内径・外径は書かなくてよい
"""

import bpy
from math import radians

# --- 倍率。TE37 と同じ大きさで作るなら 1.0 のまま ----------------------
SCALE = 1.0
# ---------------------------------------------------------------------

# TE37 のタイヤの実寸 [m]
OUTER_RADIUS = 0.450 * SCALE    # 外径
INNER_RADIUS = 0.334 * SCALE    # 内径（ビード）
TYRE_WIDTH = 0.38 * SCALE       # 一番膨らんだところの幅
BEAD_WIDTH = 0.32 * SCALE       # ビードからビードまで（＝リムの幅）。規格なので変えない
TREAD_WIDTH = 0.21 * SCALE      # 踏面の幅

# 断面。(車軸方向, 半径) をビードから踏面へ。左右対称
TYRE_PROFILE = [(0.161, 0.334), (0.174, 0.352), (0.190, 0.390),
                (0.180, 0.425), (0.153, 0.444), (0.106, 0.450)]

COLLECTION = "kuruma_tire_gauge"


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


def _cylinder(collection, name, radius, depth, vertices=48):
    # 既定の円柱は軸が Z なので、Y まわりに 90 度回して軸を左右（X）へ向ける
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=vertices, radius=radius, depth=depth,
        rotation=(0.0, radians(90.0), 0.0), location=(0.0, 0.0, 0.0))
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

    # タイヤの外形。この中へ収める
    _cylinder(collection, "gauge_tyre_envelope", OUTER_RADIUS, TYRE_WIDTH)

    # 内径。ホイールのリムが入るところ
    _cylinder(collection, "gauge_bead", INNER_RADIUS, BEAD_WIDTH)

    # 踏面の幅
    _ring(collection, "gauge_tread_outer", OUTER_RADIUS, TREAD_WIDTH * 0.5)
    _ring(collection, "gauge_tread_inner", OUTER_RADIUS, -TREAD_WIDTH * 0.5)

    # TE37 のタイヤの断面
    _tyre_section(collection, "gauge_tyre_section")

    # 車軸。原点を通る左右方向の線で、これがタイヤの回転軸になる
    _cylinder(collection, "gauge_axle", OUTER_RADIUS * 0.03, TYRE_WIDTH * 2.5, vertices=8)

    # 地面。原点から外径ぶん下。ここに接する
    bpy.ops.mesh.primitive_plane_add(
        size=OUTER_RADIUS * 3.0, location=(0.0, 0.0, -OUTER_RADIUS))
    _move_to(bpy.context.object, collection, "gauge_ground")

    print("タイヤのゲージを置きました（TE37 の {:.2f} 倍）".format(SCALE))
    print("  外径 {:.3f} m・内径 {:.3f} m".format(OUTER_RADIUS, INNER_RADIUS))
    print("  幅 {:.3f} m（ビード間 {:.3f} m・踏面 {:.3f} m）"
          .format(TYRE_WIDTH, BEAD_WIDTH, TREAD_WIDTH))
    print("  原点は車軸の中心。回転軸は左右（X）")


build()
