# Kuruma - Example Car Pack

### Adds a car to Kuruma.

> ### ⚠️ This is a car add-on pack. It also needs **[Kuruma](https://modrinth.com/mod/kuruma)**.
> On its own it does nothing. Install Kuruma first and run the two together.

A tall, boxy van — and a set of deep dish wheels to put on it.  
It can be tuned in-game, so the numbers are a starting point, not something fixed.

## 🚗 Cars in this pack

| Car | What it's like |
|---|---|
| **Sample Car** | A tall, boxy van. 1500 kg, torque low down, and it leans. Not fast — but hustling that weight through a corner is its own kind of fun. |

## 📦 How to install

1. Minecraft **1.20.1** with **Forge 47.4.22+**, or Minecraft **1.21.1** with **NeoForge 21.1+** — the same jar works on both
2. **[Kuruma](https://modrinth.com/mod/kuruma)** — install it first (1.7.0 or later)
3. This pack's `.jar` into your `mods/` folder (the same place as Kuruma)

That's it. No extra libraries, no config files to edit.

**Playing on a server?** Put this pack on **both the server and every player's client.**  
The server decides which cars exist; each client renders them.

## 🎮 How to drive them

The cars in this pack sit in the creative inventory alongside the **standard car item**.
Right-click the ground to place one, right-click the car to get in.

**Press G to change the setup.** They come tuned the way the author liked them, but nothing is
locked — springs, gears, diff and tyres can all be moved. If you don't like the way it turns in,
change it.

## 🛠 Make your own car pack

This pack is also the **template for making your own**. Unzip the jar (it is a zip) and everything
you need is inside:

| | |
|---|---|
| `PACK_CREATE_GUIDE.html` | **[Car Pack Creation Guide](https://kuruma.jdm-mc.com/guide.html)** — separate pages for the car, wheels and parts, each built around the minimum steps: the mesh in Blender, lights and glass, **its own engine sound**, the handling specs, swappable wheels, **aero parts and mufflers** shaped right in your car model, and packing the jar, with every key you can write. Written for people who have never opened Blender |
| `modeling_tools/` | Blender scripts that drop in a size gauge for the body and the wheel |
| `sound_tools/` | `make_octave_up.py` — makes the high-rpm engine sound from your engine loop |

No coding needed — a car pack is only JSON, models, textures and sounds.

## ❓ If something isn't working

| What you're seeing | Why |
|---|---|
| Nothing new in the creative inventory | Kuruma isn't installed, or this jar isn't in `mods/` |
| The cars are there, but they all look like the standard car | The pack is only on the server. Install it on your client too |
| You can't join a server | The server doesn't have this pack. Ask the owner to add it |

## 📄 License

All rights reserved. Do not redistribute.

[Kuruma](https://modrinth.com/mod/kuruma) itself is a separate project with a separate license (nothing in this pack changes it).

---

# Kuruma - エグザンプルカーパック（日本語）

### Kuruma に車を 1 台追加します。

> ### ⚠️ これは車追加パックです。**[Kuruma](https://modrinth.com/mod/kuruma)** が別に必要です。
> これだけ入れても何も起きません。先に Kuruma を入れて、一緒に動作させる必要があります。

背の高い箱形のバンと、それに履かせる深皿ホイールが 1 組。  
ゲーム内でいじれるので、数字は「決められたもの」ではなく「初期値」です。

## 🚗 入っている車

| 車 | どんな車か |
|---|---|
| **サンプルカー** | 背の高い箱形のバン。1500kg、低回転から太く、よく傾きます。速くはありませんが、重い車体をコーナーへねじ込むのはこれはこれで楽しい。 |

## 📦 導入方法

1. Minecraft **1.20.1** ＋ **Forge 47.4.22 以降**、または Minecraft **1.21.1** ＋ **NeoForge 21.1 以降**（同じ jar でどちらにも入ります）
2. **[Kuruma](https://modrinth.com/mod/kuruma)** を先に入れる（1.7.0 以降）
3. このパックの `.jar` を `mods/` フォルダへ（Kuruma と同じ場所）

以上です。追加のライブラリも、設定ファイルの編集も要りません。

**サーバーで遊ぶときは、サーバーと参加者全員のクライアントの両方に入れてください。**  
どの車が存在するかはサーバーが決め、その姿をレンダリングするのは各クライアントだからです。

## 🎮 乗りかた

クリエイティブの**車アイテム**の中に、このパックの車も並んでいます。地面を右クリックで
車を出し、車を右クリックで乗車。

**G キーでセッティングを変えられます。** 作者が詰めた状態で入っていますが、固定ではありません。
バネもギアもデフもタイヤも全部動かせるので、曲がり方が気に入らなければ変えてください。

## 🛠 自分でカーパックを作る

このパックは**自作パックのひな型**も兼ねています。jar を展開すると（中身は zip です）、
必要なものが全部入っています。

| | |
|---|---|
| `PACK_CREATE_GUIDE.html` | **[カーパック作成ガイド](https://kuruma.jdm-mc.com/guide.html)** — 車・ホイール・パーツのページに分け、どれも最低限の手順から書いてあります。Blender でのメッシュ作り、灯火とガラス、**自前のエンジン音**、諸元（走り）、換装できるホイール、車のモデルの中で作る**エアロパーツとマフラー**、jar への固め方、書ける項目の全一覧まで。Blender を触ったことがない人向け |
| `modeling_tools/` | 車体とホイールの寸法合わせのゲージを置く Blender 用スクリプト |
| `sound_tools/` | `make_octave_up.py` — エンジン音のループから高回転側の音を作る |

コードは不要です。カーパックの中身は JSON・モデル・テクスチャ・音だけです。

## ❓ うまくいかないとき

| 症状 | 原因 |
|---|---|
| クリエイティブに何も増えていない | Kuruma が入っていないか、この jar が `mods/` に入っていない |
| 車は増えたが、全部いつもの車の姿で出てくる | パックがサーバーにしか入っていません。クライアントにも入れてください |
| サーバーに入れない | サーバーにこのパックが入っていません。管理者に入れてもらってください |

## 📄 ライセンス

無断転載を禁じます。

[Kuruma](https://modrinth.com/mod/kuruma) 本体は別のプロジェクトで、ライセンスも別です（このパックが変えるものではありません）。　　
