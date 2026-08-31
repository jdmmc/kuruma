"""Blender で車のモデルを作るときの基準（ゲージ）を置くスクリプト。

使い方:
    Blender の Scripting タブ → 新規テキスト → このファイルを貼る → 実行
    （何度でも実行してよい。前に置いたゲージは消してから置き直す）

置くもの:
    - タイヤ 4 つ（ワイヤーフレーム）… ここに来るように車体のフェンダーを切る
    - 地面（Z = 0）… 車体の原点はここ。<b>前後は前後輪の中点</b>（重心ではない）
    - シャシー基準面（Z = 車高）… ゲーム内でエンティティの原点が来る高さ

<b>ゲージに合わせて車体を作れば、JSON で拡大率をいじる必要がなくなる。</b>
JSON の bodyScale と designWheelBase は微調整用として 1.0 / 2.6 のまま置いておく。

書き出すときの注意:
    - ゲージは "kuruma_gauge" コレクションに入るので、<b>非表示にするか
      「Selection Only」で車体だけを書き出す</b>こと
    - 向きは Blender 標準のまま（前方 -Y・上 +Z）、エクスポート設定も標準のまま
      （-Z Forward / Y Up）。読み替えは MOD 側の ObjModel が済ませる
    - 三角形化してから書き出す（凹んだ n-gon は破綻する）

寸法は CarSpec の既定値と揃えてある。変えたいときは下の定数を直す。
"""

import bpy
from math import radians

# --- 車格の倍率 -------------------------------------------------------
# 既定の諸元（実車とほぼ同じ寸法）に対する倍率。Minecraft のプレイヤーは
# ずんぐりしているので、実寸どおりだと車が小さく見える。1.0 が実車相当。
SCALE = 1.2
# ---------------------------------------------------------------------

# --- 既定の諸元（CarSpec の Builder と揃えること）---------------------
BASE_WHEEL_BASE = 2.6      # 前後輪の距離 [m]
BASE_TRACK = 1.6667        # 左右輪の距離 [m]。SCALE を掛けて 2.0m になる
BASE_WHEEL_RADIUS = 0.375  # タイヤ半径 [m]。直径は 0.75
BASE_WHEEL_WIDTH = 0.20    # タイヤの太さ [m]。見た目の目安なので厳密でなくてよい

# 停車時のサス長 [m]。タイヤの大きさとは無関係に決まるので、SCALE では変えない。
# CarSpec の既定は「ストローク 30cm − 停車時の沈み込み 11.8cm」
STATIC_SUSPENSION = 0.182
# ---------------------------------------------------------------------

WHEEL_BASE = BASE_WHEEL_BASE * SCALE
TRACK = BASE_TRACK * SCALE
WHEEL_RADIUS = BASE_WHEEL_RADIUS * SCALE
WHEEL_WIDTH = BASE_WHEEL_WIDTH * SCALE

# 停車時のシャシー基準面の高さ。ゲーム側と同じ式（サス長 + タイヤ半径）で出す。
# タイヤを大きくすると車体が持ち上がるのがここに出る
RIDE_HEIGHT = STATIC_SUSPENSION + WHEEL_RADIUS

COLLECTION = "kuruma_gauge"


def _fresh_collection(name):
    """同じ名前のコレクションを作り直す。中身は消える。"""
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
    """作った直後のオブジェクトをゲージ用コレクションへ移し、線だけの表示にする。"""
    obj.name = name
    obj.display_type = 'WIRE'
    obj.hide_select = True  # 作業中に間違って掴まないように
    for current in list(obj.users_collection):
        current.objects.unlink(obj)
    collection.objects.link(obj)


def build():
    collection = _fresh_collection(COLLECTION)

    # タイヤ。Blender の前方は -Y なので、前輪は y が負。
    # 既定の円柱は軸が Z なので、Y まわりに 90 度回して軸を左右（X）へ向ける
    for front in (True, False):
        for left in (True, False):
            bpy.ops.mesh.primitive_cylinder_add(
                vertices=32,
                radius=WHEEL_RADIUS,
                depth=WHEEL_WIDTH,
                rotation=(0.0, radians(90.0), 0.0),
                location=((-TRACK / 2.0) if left else (TRACK / 2.0),
                          (-WHEEL_BASE / 2.0) if front else (WHEEL_BASE / 2.0),
                          WHEEL_RADIUS))
            _move_to(bpy.context.object, collection,
                     "gauge_wheel_{}{}".format('F' if front else 'R', 'L' if left else 'R'))

    # 地面（Z = 0）。車体の原点はこの高さ。
    # 大きさは車格に追従させる。固定にすると、倍率を上げたとき車が板からはみ出す
    bpy.ops.mesh.primitive_plane_add(size=WHEEL_BASE * 2.5, location=(0.0, 0.0, 0.0))
    _move_to(bpy.context.object, collection, "gauge_ground")

    # シャシー基準面。ゲーム内でエンティティの原点が来る高さで、車体はここから下へ
    # designRideHeight だけ下げて描かれる。
    # 4 隅がちょうど車軸の位置に来る長方形にしてある（正方形の板を固定の大きさで置くと、
    # 倍率を上げたときトレッドの方が板より広くなり、タイヤが枠の内側に食い込んで見える）
    bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0.0, 0.0, RIDE_HEIGHT))
    chassis = bpy.context.object
    chassis.scale = (TRACK, WHEEL_BASE, 1.0)
    _move_to(chassis, collection, "gauge_chassis_plane")

    print("ゲージを置きました（車格 {:.2f} 倍）".format(SCALE))
    print("  ホイールベース {:.3f} m".format(WHEEL_BASE))
    print("  トレッド       {:.3f} m".format(TRACK))
    print("  タイヤ直径     {:.3f} m（半径 {:.1f} cm）".format(WHEEL_RADIUS * 2.0, WHEEL_RADIUS * 100.0))
    print("  シャシー基準面 {:.3f} m".format(RIDE_HEIGHT))
    print("")
    print("  ゲーム側もこの寸法に合わせないと、タイヤだけ元の位置に出る。")
    print("  CarSpec の既定値（wheelBase / trackWidth / wheelRadius）を上の値にすること。")


build()
