"""日本のナンバー（普通車・軽とも同じ大きさ）のモデルを作るときの基準（ゲージ）を置くスクリプト。

ほかの様式は blender_plate_gauge_eu.py, blender_plate_gauge_uk.py, blender_plate_gauge_us.py, blender_plate_gauge_custom.py。
車体用は blender_gauge.py、ホイール用は blender_wheel_gauge.py。
<b>プレートは車体とは別ファイルで作る。</b>どの車に付けるかはプレートの側では決めず、
車の JSON（vehicles/<car>.json の "plates"）が「どこに付けるか」を書く。

使い方:
    Blender の Scripting タブ → 新規テキスト → このファイルを貼る → 実行
    （何度でも実行してよい。前に置いたゲージは消してから置き直す）

<b>大きさは実寸で作る。</b>プレートは車に付けるときに伸び縮みしない（JP のプレートを
EU 形のくぼみに付ければ、実車と同じように少しはみ出す）。
<b>形もテクスチャも作者が決めてよい。</b>外形のゲージは寸法の目安で、形の制約ではない
（角を丸めた板・枠付きもそのまま部品になる）。守るのは原点・向き・厚みの限界だけ。

欄:
    - region … 地名（品川・なにわ・相模原）。上の段の左
    - class  … 分類番号（330・500・50A）。上の段の右
    - kana   … かな（さ）。下の段の左
    - number … 一連番号（12-34・・・・1）。下の段の右、いちばん大きな字
    地名と分類番号は、短ければ字の間を広げ、長ければ横に詰めて枠いっぱいに並べる（MOD が揃える）

テクスチャ:
    自家用は白地・緑字、軽は黄地・黒字、事業用は緑地・白字。<b>地の色だけを描き、字は描かない</b>
    （字の色は部品の JSON の "fields" に書く）。縁の線とボルト穴の座金を描いてよい
    縦横比は 2:1（例 1024 × 512）

置くもの:
    - プレートの外形（板）… 規格の幅 × 高さ。<b>板の外周をこれに合わせる</b>
    - 取り付け面（Y = 0）… 車体に当たる面。<b>プレートの裏はここに付ける</b>
    - 厚みの限界（Y = -DEPTH_LIMIT）… 縁取りや枠を付けても、<b>ここより前へは出さない</b>
    - 字の枠の目安（gauge_field_<欄>）… その様式の標準的な字の位置
    - 表を示す矢印 … <b>プレートの表はこちら（-Y ＝ 車の前）へ向ける</b>
    - 上を示す矢印 … <b>字の上は +Z</b>

字を書く枠（text_<欄>）:
    初めて実行したときだけ、字の枠の目安と同じ位置に text_<欄> の板を置く
    （"kuruma_plate_fields" コレクション。<b>これはゲージではなく、プレートの一部として書き出すもの</b>）。
    2 回目からは、すでにある text_<欄> には触らない——動かした位置を上書きしないため。
    - <b>板そのものは描かれない。</b>MOD が読むのは位置・大きさ・向きだけで、その上に字を描く
    - <b>欄の名前は、部品の JSON の "fields" の id と揃える</b>（text_number なら "number"）
    - 字が枠に収まらないときは、MOD が横だけ縮める（実物のナンバーも詰めて打つ）
    - 枠は表の面に重ねて置く。少し前へ出す必要はない（MOD が浮かせて描く）
    - <b>字はメッシュで作らない。</b>浮き出し（JP・US の型押し）も MOD が描く。
      テクスチャに描くのは地の色・縁・帯（EU の青帯など）だけ
    - 字を打たない飾りのプレートなら、text_ の板を消して書き出せばよい

プレートに求められること:
    - <b>原点は取り付け面の中心。</b>板の表の中心でも、底でもない。
      車の JSON の "pos" はこの点を置く位置になる
    - <b>表を -Y（車の前）へ向けて作る。</b>後ろに付けるときは MOD が 180 度回す。
      前後で作り分けない（色だけ違うなら、部品の JSON の "texture_rear" で替える）
    - Blender の正面図（テンキー 1）で、字が普通に読める向きになっていればよい
    - <b>影はプレートを囲む箱で描く。</b>メッシュの形も字も影には出さない。厚みがほぼ無ければ
      板 1 枚（頂点 4）、縁取りや枠で厚みがあれば箱（頂点 8）。どちらになるかは MOD がメッシュの
      外形から決めるので、作者が書く項目は無い。角の丸みやテクスチャの抜きは影に出ないが、
      プレートの大きさでは見分けられない
    - <b>UV はプレートの表の全体に 0〜1 で張る。</b>テクスチャの縦横比はプレートに合わせる
      （JP・US は 2:1、EU・UK はおよそ 4.7:1）

書き出すときの注意:
    - ゲージは "kuruma_plate_gauge" コレクションに入るので、<b>「Selection Only」で
      プレートと text_ の板だけを書き出す</b>こと（ObjModel は gauge_ で始まる名前を捨てるので、
      混ざっても描かれはしないが、要らないデータが jar に入る）
    - <b>「Triangulated Mesh」は入れない。</b>四角形のままのほうが軽い
    - 向きとエクスポート設定は標準のまま（前方 -Y・上 +Z、-Z Forward / Y Up）

（ナンバーは設計中で、MOD はまだこのモデルを読まない。決まりごとは設計どおりに書いてある）
"""

import bpy
from math import radians

NAME = "日本のナンバー（普通車・軽とも同じ大きさ）"

# 外形 [mm]（幅, 高さ）
SIZE_MM = (330.0, 165.0)

# 字の枠の目安 (欄の id, 左, 下, 右, 上) [mm]。プレートの中心が原点、右（+X）・上（+Z）が正
FIELDS = [
    ("region", -118.0, 28.0, -4.0, 72.0),    # 地名（品川）
    ("class", 4.0, 28.0, 118.0, 72.0),       # 分類番号（330）
    ("kana", -152.0, -66.0, -110.0, 4.0),    # かな（さ）
    ("number", -100.0, -72.0, 152.0, 18.0),  # 一連番号（12-34）
]

# 左端の青帯の幅 [mm]（テクスチャに描く範囲の目安）。帯の無い様式は None
BAND_MM = None

# 厚みの限界 [m]。縁取り・枠を付けても取り付け面からここまで
DEPTH_LIMIT = 0.015
# 板そのものの厚みの目安 [m]（字の枠を置く表の面）
PLATE_THICKNESS = 0.0015

MM = 0.001

COLLECTION = "kuruma_plate_gauge"
FIELD_COLLECTION = "kuruma_plate_fields"


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


def _keep_collection(name):
    # 中身を消さずに取ってくる（text_ の板はプレートの一部なので消さない）
    collection = bpy.data.collections.get(name)
    if collection is None:
        collection = bpy.data.collections.new(name)
    if name not in bpy.context.scene.collection.children:
        bpy.context.scene.collection.children.link(collection)
    return collection


def _move_to(obj, collection, name, wire=True):
    obj.name = name
    if wire:
        obj.display_type = 'WIRE'
        obj.hide_select = True
    for current in list(obj.users_collection):
        current.objects.unlink(obj)
    collection.objects.link(obj)


def _rect(collection, name, left, bottom, right, top, y, wire=True):
    """X-Z 面に立てた長方形の板（表は -Y）。寸法は m。"""
    verts = [(left, y, bottom), (right, y, bottom), (right, y, top), (left, y, top)]
    mesh = bpy.data.meshes.new(name)
    # 表が -Y を向く巻き方（-Y から見て反時計回り）
    mesh.from_pydata(verts, [], [(0, 1, 2, 3)])
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    _move_to(obj, collection, name, wire)
    return obj


def _box(collection, name, width, height, y_front, y_back):
    """プレートの外形から厚みの限界までの箱（ワイヤーで見る）。"""
    half_w = width * 0.5
    half_h = height * 0.5
    verts = []
    for y in (y_back, y_front):
        verts += [(-half_w, y, -half_h), (half_w, y, -half_h), (half_w, y, half_h), (-half_w, y, half_h)]
    edges = [(0, 1), (1, 2), (2, 3), (3, 0), (4, 5), (5, 6), (6, 7), (7, 4),
             (0, 4), (1, 5), (2, 6), (3, 7)]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, edges, [])
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    _move_to(obj, collection, name)


def _arrow(collection, name, location, rotation, size):
    bpy.ops.mesh.primitive_cone_add(
        vertices=8, radius1=size * 0.25, depth=size,
        rotation=rotation, location=location)
    _move_to(bpy.context.object, collection, name)


def build():
    width = SIZE_MM[0] * MM
    height = SIZE_MM[1] * MM
    half_w = width * 0.5
    half_h = height * 0.5

    collection = _fresh_collection(COLLECTION)

    # プレートの外形（表の面）。板の外周をこれに合わせる
    _rect(collection, "gauge_plate_outline", -half_w, -half_h, half_w, half_h, -PLATE_THICKNESS)

    # 取り付け面。原点を通る Y = 0 の面で、プレートの裏をここに付ける。外形より一回り大きく描く
    margin = height * 0.25
    _rect(collection, "gauge_mount_surface", -half_w - margin, -half_h - margin,
          half_w + margin, half_h + margin, 0.0)

    # 厚みの限界。縁取り・枠を付けても、この箱の前の面より前へは出さない
    _box(collection, "gauge_depth_limit", width, height, -DEPTH_LIMIT, 0.0)

    # 左端の青帯（テクスチャに描く範囲の目安）
    if BAND_MM is not None:
        _rect(collection, "gauge_eu_band", -half_w, -half_h, -half_w + BAND_MM * MM, half_h, -PLATE_THICKNESS)

    # 字の枠の目安
    for field, left, bottom, right, top in FIELDS:
        _rect(collection, "gauge_field_" + field, left * MM, bottom * MM, right * MM, top * MM,
              -PLATE_THICKNESS)

    # 表の向き（-Y ＝ 車の前）。円錐の既定の向きは +Z なので、X まわりに 90 度回して -Y を指させる
    _arrow(collection, "gauge_front_face", (0.0, -height * 0.45, 0.0),
           (radians(90.0), 0.0, 0.0), height * 0.3)

    # 上の向き（+Z）。字の上はこちら
    _arrow(collection, "gauge_up", (half_w + height * 0.25, 0.0, 0.0), (0.0, 0.0, 0.0), height * 0.3)

    # 字を書く枠。初回だけ置き、すでにあるものは動かさない
    fields = _keep_collection(FIELD_COLLECTION)
    placed = []
    for field, left, bottom, right, top in FIELDS:
        name = "text_" + field
        if bpy.data.objects.get(name) is not None:
            continue
        _rect(fields, name, left * MM, bottom * MM, right * MM, top * MM, -PLATE_THICKNESS, wire=False)
        placed.append(name)

    print("ナンバーのゲージを置きました（{}）".format(NAME))
    print("  外形 {:.1f} × {:.1f} mm。実寸で作る（車に付けるときに伸び縮みしない）".format(*SIZE_MM))
    print("  原点は取り付け面の中心。表は -Y（車の前）、字の上は +Z")
    print("  縁取り・枠は取り付け面から {:.0f} mm まで".format(DEPTH_LIMIT / MM))
    if FIELDS:
        print("  欄: {}".format(", ".join(field for field, *_ in FIELDS)))
    else:
        print("  欄なし（字を打たない飾りのプレート）")
    if placed:
        print("  字の枠を置きました: {}（プレートの一部として書き出す）".format(", ".join(placed)))
    elif FIELDS:
        print("  字の枠（text_）はすでにあるので動かしていません")


build()
