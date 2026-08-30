"""タイヤのモデルを作るときの基準（ゲージ）を置くスクリプト。

車体用は blender_gauge.py。<b>タイヤは車体とは別ファイルで作る</b>ので、こちらを使う。

使い方:
    Blender の Scripting タブ → 新規テキスト → このファイルを貼る → 実行
    （何度でも実行してよい。前に置いたゲージは消してから置き直す）

置くもの:
    - タイヤの外径の輪（ワイヤーフレーム）… この円の中に収まるように作る
    - 車軸の線 … 原点を通る左右方向の線。<b>回転軸はこれ</b>
    - 地面 … タイヤが接する高さ（原点から半径ぶん下）
    - 表を示す矢印 … <b>ホイールの表はこちら（+X）へ向ける</b>

タイヤに求められること:
    - <b>原点は車軸の中心。</b>タイヤの底ではない
    - <b>回転軸は左右方向（X）。</b>Blender の既定の円柱は軸が Z なので、
      Y まわりに 90 度回してから作ること
    - <b>左側用を 1 つだけ作る。</b>右側は MOD 側が鏡像にして描く
      （pose.scale(-1, 1, 1)。だから RenderType はカリング無し）
    - Blender で前方を -Y に向けると <b>+X が車体の左</b>になる。
      ホイールの表（デザイン面）を +X へ向けるのがこれにあたる

書き出すときの注意:
    - ゲージは "kuruma_wheel_gauge" コレクションに入るので、<b>「Selection Only」で
      タイヤだけを書き出す</b>こと（ObjModel は gauge_ で始まる名前を捨てるので、
      混ざっても描かれはしないが、要らないデータが jar に入る）
    - <b>「Triangulated Mesh」にチェックを入れる。</b>凹んだ n-gon は扇状分割で破綻する
    - 向きとエクスポート設定は標準のまま（前方 -Y・上 +Z、-Z Forward / Y Up）

寸法は CarSpec の既定値と揃えてある。変えたいときは下の定数を直す。
"""

import bpy
from math import radians

# --- 車格の倍率。blender_gauge.py の SCALE と揃えること -----------------
SCALE = 1.2
# ---------------------------------------------------------------------

BASE_WHEEL_RADIUS = 0.375  # CarSpec の既定値 [m]
BASE_WHEEL_WIDTH = 0.20    # タイヤの太さ [m]。物理には効かない見た目だけの値

WHEEL_RADIUS = BASE_WHEEL_RADIUS * SCALE
WHEEL_WIDTH = BASE_WHEEL_WIDTH * SCALE

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


def build():
    collection = _fresh_collection(COLLECTION)

    # タイヤの外形。太さぶんの長さがある円柱で、この中へ収める。
    # 既定の円柱は軸が Z なので、Y まわりに 90 度回して軸を左右（X）へ向ける
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=48,
        radius=WHEEL_RADIUS,
        depth=WHEEL_WIDTH,
        rotation=(0.0, radians(90.0), 0.0),
        location=(0.0, 0.0, 0.0))
    _move_to(bpy.context.object, collection, "gauge_tyre_envelope")

    # 車軸。原点を通る左右方向の線で、これがタイヤの回転軸になる
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=8,
        radius=WHEEL_RADIUS * 0.03,
        depth=WHEEL_WIDTH * 2.5,
        rotation=(0.0, radians(90.0), 0.0),
        location=(0.0, 0.0, 0.0))
    _move_to(bpy.context.object, collection, "gauge_axle")

    # 地面。原点から半径ぶん下。ここに接する
    bpy.ops.mesh.primitive_plane_add(
        size=WHEEL_RADIUS * 3.0, location=(0.0, 0.0, -WHEEL_RADIUS))
    _move_to(bpy.context.object, collection, "gauge_ground")

    # 表の向き。ホイールのデザイン面はこちら（+X ＝ 車体の左）へ向ける。
    # 円錐の既定の向きは +Z なので、Y まわりに 90 度回して +X を指させる
    bpy.ops.mesh.primitive_cone_add(
        vertices=8,
        radius1=WHEEL_RADIUS * 0.12,
        depth=WHEEL_RADIUS * 0.3,
        rotation=(0.0, radians(90.0), 0.0),
        location=(WHEEL_WIDTH * 0.5 + WHEEL_RADIUS * 0.25, 0.0, 0.0))
    _move_to(bpy.context.object, collection, "gauge_outer_face")

    print("タイヤのゲージを置きました（車格 {:.2f} 倍）".format(SCALE))
    print("  半径 {:.4f} m（直径 {:.3f} m）".format(WHEEL_RADIUS, WHEEL_RADIUS * 2.0))
    print("  太さ {:.3f} m".format(WHEEL_WIDTH))
    print("  原点は車軸の中心。回転軸は左右（X）。矢印の向き（+X）が表")
    print("  vehicles/car.json の designWheelRadius に {:.4f} と書くこと"
          .format(WHEEL_RADIUS))


build()
