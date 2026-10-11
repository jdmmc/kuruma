"""KurumaMod の紹介サイトを書き出す。全ページのヘッダー・フッター・言語切り替えを揃えるため。

使い方: python website/gen.py
書き出し先はこのスクリプトのあるフォルダ（website/）。カーパック作成ガイドは
website/guide-src/PACK_CREATE_GUIDE.html を読む（ガイドはサイトにだけ置く。見本パックには入れない）。どちらも引数で差し替えられる（gen.py <出力> <ガイド>）。
英語が既定。日本語は <span data-l="ja"> で並べて持ち、site.js が切り替える。
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE

CF = "https://www.curseforge.com/minecraft/mc-mods/kuruma"
MR = "https://modrinth.com/mod/kuruma"
CF_BASIC = "https://www.curseforge.com/minecraft/mc-mods/kurumabasiccarpack"
MR_BASIC = "https://modrinth.com/mod/kurumabasiccarpack"
CF_EXAMPLE = "https://www.curseforge.com/minecraft/mc-mods/kurumaexamplecarpack"
GUIDE = "guide.html"
DISCORD = "https://discord.gg/q3YNqaveRd"
X = "https://x.com/kurumamod"


def T(en, ja=None):
    """英日を並べる。ja を省くか同じなら 1 つだけ。"""
    if ja is None or ja == en:
        return en
    return f'<span data-l="en">{en}</span><span data-l="ja">{ja}</span>'


def ALT(en, ja):
    return f'alt="{en}" data-alt-ja="{ja}"'


HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<script>
// 描く前に言語を決める（英語が既定。?lang=ja か前回の選択で日本語）
(function () {{
  var lang = 'en';
  try {{ lang = localStorage.getItem('kuruma-lang') || lang; }} catch (e) {{}}
  var q = /[?&]lang=(en|ja)/.exec(location.search);
  if (q) lang = q[1];
  document.documentElement.setAttribute('data-lang', lang);
  document.documentElement.lang = lang;
}})();
</script>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wdth,wght@62..125,300..800&family=Noto+Sans+JP:wght@300;400;500;700&display=swap">
<link rel="stylesheet" href="style.css">
<link rel="stylesheet" href="pages.css">
<link rel="stylesheet" href="guide.css">
</head>
<body data-title-en="{title}" data-title-ja="{title_ja}">
"""

HEADER = f"""<header class="bar">
  <div class="wrap">
    <nav class="menu" aria-label="Main">
      <a href="index.html#cars">{T("Cars", "車種")}</a>
      <a href="features.html">{T("Features", "特徴")}</a>
      <a href="compare.html">{T("Compare", "比較")}</a>
      <a href="start.html">{T("Get started", "はじめかた")}</a>
      <a href="guide.html">{T("Car Pack Guide", "作成ガイド")}</a>
    </nav>
    <a class="wordmark" href="index.html" aria-label="KurumaMod">KURUMA</a>
    <div class="tools">
      <button class="lang" type="button" id="lang-toggle" aria-label="Switch language / 言語を切り替え">
        <span data-l="en">日本語</span><span data-l="ja">English</span>
      </button>
      <a class="dl" href="start.html#download">{T("Download", "ダウンロード")}</a>
    </div>
  </div>
</header>
"""

FOOTER = f"""<footer>
  <div class="wrap">
    <a class="wordmark" href="index.html">KURUMA</a>
    <div class="cols">
      <div><h4>{T("Cars", "車種")}</h4><ul><li><a href="s15.html">Silvia S15</a></li><li><a href="911.html">911</a></li><li><a href="prado.html">Land Cruiser Prado</a></li><li><a href="compare.html">{T("Compare cars", "車種を比較")}</a></li></ul></div>
      <div><h4>{T("Features", "特徴")}</h4><ul><li><a href="features.html#physics">{T("Physics", "物理演算")}</a></li><li><a href="features.html#setup">{T("Setup", "セッティング")}</a></li><li><a href="features.html#lighting">{T("Headlights", "ヘッドライト")}</a></li><li><a href="features.html#racing">{T("Racing", "レース")}</a></li></ul></div>
      <div><h4>{T("Download", "ダウンロード")}</h4><ul><li><a href="{CF}">CurseForge</a></li><li><a href="{MR}">Modrinth</a></li><li><a href="{CF_BASIC}">Basic Car Pack</a></li><li><a href="{GUIDE}">{T("Car pack guide", "カーパック作成ガイド")}</a></li></ul></div>
      <div><h4>{T("Community", "コミュニティ")}</h4><ul><li><a href="{DISCORD}">Discord</a></li><li><a href="{X}">X @kurumamod</a></li></ul></div>
    </div>
    <div class="legal">
      <p>{T("KurumaMod is a free Minecraft mod licensed under GPL-3.0-only. Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
            "KurumaMod は GPL-3.0-only の無料の Minecraft MOD です。Minecraft 公式の製品ではなく、Mojang・Microsoft の承認を受けたものでも、関係するものでもありません。")}</p>
      <p>{T("Car names refer to the in-game models. This site is not affiliated with any car manufacturer.",
            "車名はゲーム内の車種名です。このサイトは自動車メーカーとは関係ありません。")}</p>
    </div>
  </div>
</footer>
<script src="site.js"></script>
</body>
</html>
"""


def page(name, title, title_ja, body):
    (OUT / name).write_text(HEAD.format(title=title, title_ja=title_ja) + HEADER + body + FOOTER, encoding="utf-8")


def visual(m):
    if m.get("img"):
        return f'<img src="img/{m["img"]}" {ALT(*m["alt"])}>'
    return f'<div class="plate"><span>{m["plate"]}</span></div>'


def figs(items, light=False):
    cls = "figures light" if light else "figures"
    out = []
    for v, u, l in items:
        unit = f"<small>{u}</small>" if u else ""
        out.append(f"<div><b>{v}{unit}</b><span>{T(*l)}</span></div>")
    return f'<div class="{cls}">{"".join(out)}</div>'


def dl_buttons(m, light=False):
    first = "btn light arrow" if light else "btn arrow"
    second = "btn outline on-dark" if light else "btn outline"
    if m["pack"] == "core":
        return (f'<a class="{first}" href="{CF}">{T("Download on CurseForge", "CurseForge でダウンロード")}</a>'
                f'<a class="{second}" href="{MR}">Modrinth</a>')
    return (f'<a class="{first}" href="{CF_BASIC}">{T("Get the Basic Car Pack", "Basic Car Pack を入手")}</a>'
            f'<a class="{second}" href="{MR_BASIC}">Modrinth</a>')


# 性能は KurumaMod の物理コアで測った値（平坦な舗装路・初期セッティング・パーツ無し・全開）
MODELS = {
    "s15": dict(
        file="s15.html", name="Silvia S15", maker=("Nissan Silvia S15", "日産 シルビア S15"), pack="core",
        kind=("Sports coupe · AWD", "スポーツクーペ・4WD"), filters="sport awd",
        source=("Included with KurumaMod", "KurumaMod に標準搭載"),
        img="car-s15.jpg", alt=("Blue Silvia S15 parked on a track", "コースに止まる青いシルビア S15"),
        hero="car-s15.jpg", hero_alt=("Blue Silvia S15 parked on a track", "コースに止まる青いシルビア S15"),
        photo="drift.jpg", photo_alt=("Silvia S15 drifting", "ドリフトするシルビア S15"),
        catch=("The car you start with. And the most honest one.", "最初の 1 台は、いちばん素直な 1 台。"),
        intro=("Install KurumaMod and it's there, on TE37s. A 56% front weight bias and an even all-wheel-drive split make it turn in cleanly right up to the limit, and tell you clearly when you go past it. Cruise in automatic, or shift for yourself in manual.",
               "KurumaMod を入れたその日から乗れる標準車。TE37 を履いています。前 56% の荷重配分と前後等分の 4WD で、限界まで素直に曲がり、限界を越えればそれがはっきり伝わります。AT でのんびり流しても、MT で自分で回しても。"),
        figs=[("281", "PS", ("Max power", "最高出力")), ("5.3", "s", ("0-100 km/h", "0-100 km/h")), ("255", "km/h", ("Top speed", "最高速度")), ("1,200", "kg", ("Weight", "車両重量"))],
        perf=[("5.25", "s", ("0-100 km/h", "0-100 km/h")), ("13.37", "s", ("0-400 m", "0-400 m")), ("255", "km/h", ("Top speed", "最高速度")), ("4.27", "kg/PS", ("Power-to-weight", "パワーウエイトレシオ"))],
        tq=350, rt=4200, rp=6676, red=7000,
        engine=("Naturally aspirated. Peak torque at 4,200 rpm, power building to 6,680 rpm, and a limiter at 7,000. The automatic shifts where the next gear starts pulling harder, so at full throttle it holds each gear almost to the redline.",
                "自然吸気。最大トルクは 4,200rpm、出力は 6,680rpm まで伸び、7,000rpm で吹け止まります。AT は次の段のほうが強く押せる回転数で変速するので、全開ならレブの手前まできれいに引っ張ります。"),
        front=56, layout=("AWD (50 : 50)", "4WD（前後 50 : 50）"),
        chassis=("The weight sits slightly forward, so setup changes do what you expect: stiffen the front anti-roll bar for understeer, the rear for oversteer.",
                 "重心は少し前寄り。だからセッティングが素直に効きます。前のスタビを固めればアンダー、後ろを固めればオーバーへ。"),
        facts=[(("Wheelbase", "ホイールベース"), "3.12 m"), (("Track", "トレッド"), "1.80 m"), (("Suspension travel", "サスペンションストローク"), "28 cm"),
               (("Tyre diameter", "タイヤ外径"), "80 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "2,500 / 1,500 N/m"), (("Max steering angle", "最大切れ角"), "35°")],
        chassis_img="hud.jpg", chassis_alt=("Silvia S15 with gauges on screen", "回転計と速度計を表示して走る S15"),
        parts=[
            (("Aero", "エアロ"), ("GT wing / duck tail", "GT ウイング／ダックテール"), ("The wing makes real downforce, and you set its angle on the parts screen.", "ウイングは本当にダウンフォースを生みます。角度は換装画面で調整。"), ("Also shifts the aero balance", "前後バランスも変わる"), ""),
            (("Exhaust", "マフラー"), ("Stock / twin", "純正／2 本出し"), ("Changes the exhaust note, with a slider for how deep it sounds. Everyone nearby hears the same note.", "排気音が変わります。音の太さはスライダーで。周りの人にも同じ音で聞こえます。"), ("Effect on performance", "性能への影響"), "0"),
            (("Turbo", "タービン"), ("Stock / big turbo", "純正／ビッグタービン"), ("Boost builds with throttle and revs, adding torque on top.", "踏み込みと回転に合わせて過給が立ち上がり、トルクが上乗せされます。"), ("0-160 km/h", "0-160 km/h"), "11.3 → 8.0 s"),
            (("Tyres", "タイヤ"), ("Dirt / off-road", "ダート／オフロード"), ("Tyres for loose ground. They stop and turn better on dirt and sand.", "未舗装で強いタイヤ。土や砂の上でよく止まり、よく曲がります。"), ("Max lateral G on sand", "砂の上の最大横 G"), "0.48 → 0.68"),
        ],
        parts_img="camber.jpg", parts_alt=("Wheels with negative camber", "キャンバーを付けたホイール"),
        specs=[
            (("Dimensions and weight", "寸法・重量"), [(("Wheelbase", "ホイールベース"), "3.12 m"), (("Track", "トレッド"), "1.80 m"), (("Weight", "車両重量"), "1,200 kg"), (("Weight distribution F : R", "前後重量配分"), "56 : 44"), (("Drag area (CdA)", "空気抵抗（CdA）"), "0.78 m²")]),
            (("Engine", "エンジン"), [(("Max power", "最高出力"), "281 PS / 6,680 rpm"), (("Max torque", "最大トルク"), "350 N·m / 4,200 rpm"), (("Idle", "アイドル回転数"), "800 rpm"), (("Rev limit", "レブリミット"), "7,000 rpm"), (("Forced induction", "過給"), T("None (turbo available)", "なし（タービン装着可）"))]),
            (("Drivetrain", "駆動系"), [(("Layout", "駆動方式"), T("AWD (50 : 50)", "4WD（前後 50 : 50）")), (("Gearbox", "変速機"), T("5-speed, auto or manual", "5 速 AT／MT 切替")), (("1st / 5th", "1 速／5 速"), "4.20 / 0.85"), (("Final drive", "最終減速比"), "4.37"), (("Differential", "デフ"), T("Open", "オープン"))]),
            (("Chassis", "足まわり"), [(("Suspension travel", "サスペンションストローク"), "28 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "2,500 / 1,500 N/m"), (("Tyre diameter", "タイヤ外径"), "80 cm"), (("Max steering angle", "最大切れ角"), "35°"), (("ABS / TCS", "ABS／TCS"), T("Standard", "標準装備"))]),
            (("Performance", "性能"), [(("0-100 km/h", "0-100 km/h"), "5.25 s"), (("0-400 m", "0-400 m"), "13.37 s"), (("Top speed", "最高速度"), "255 km/h"), (("Power-to-weight", "パワーウエイトレシオ"), "4.27 kg/PS")]),
        ],
    ),
    "911": dict(
        file="911.html", name="911", maker=("Porsche 911", "ポルシェ 911"), pack="basic",
        kind=("Rear-engined · RWD", "リアエンジン・後輪駆動"), filters="sport",
        source=("Basic Car Pack", "Basic Car Pack"), new=True,
        img="car-porche911.jpg", alt=("Yellow 911 on a track with skid marks", "タイヤ痕の残るコースに止まる黄色い 911"),
        hero="car-porche911.jpg", hero_alt=("Yellow 911 on a track with skid marks", "タイヤ痕の残るコースに止まる黄色い 911"),
        catch=("The weight is at the back. You drive it with your right foot.", "重さは後ろに。操るのは右足で。"),
        intro=("The engine sits behind the rear axle, so 60% of the weight is on the rear tyres. Get on the throttle and it digs in and launches hard; lift and the tail starts to come round. A stiffer front anti-roll bar keeps it calmer at the limit.",
               "エンジンを後軸より後ろに積んだ RR。荷重の 60% が後輪に掛かるので、踏めば強く蹴り出し、抜けば尻が回り込みます。前のスタビを厚くして、限界での巻き込みは和らげてあります。"),
        figs=[("335", "PS", ("Max power", "最高出力")), ("5.4", "s", ("0-100 km/h", "0-100 km/h")), ("276", "km/h", ("Top speed", "最高速度")), ("40:60", "", ("Weight distribution", "前後重量配分"))],
        perf=[("5.40", "s", ("0-100 km/h", "0-100 km/h")), ("13.42", "s", ("0-400 m", "0-400 m")), ("276", "km/h", ("Top speed", "最高速度")), ("4.33", "kg/PS", ("Power-to-weight", "パワーウエイトレシオ"))],
        tq=420, rt=4400, rp=6500, red=7400,
        engine=("A high-revving engine in the spirit of a flat six: peak torque at 4,400 rpm, peak power at 6,500, and it pulls to 7,400. Low and slippery, it has the highest top speed of the three.",
                "水平対向 6 気筒をイメージした、高回転まで回るエンジン。4,400rpm で最大トルク、6,500rpm で最高出力、7,400rpm まで回ります。背が低く空気抵抗が小さいので、最高速は 3 台でいちばん伸びます。"),
        front=40, layout=("RWD", "RR（後輪駆動）"),
        chassis_plate="RR",
        chassis=("With the weight at the back, the rear tyres get loaded under acceleration and it puts power down well. Lift mid-corner, though, and the tail wants to step out. The front anti-roll bar is three times the rear to keep that in check.",
                 "後ろが重いぶん、加速では後輪に荷重が乗ってよく蹴ります。その代わり、コーナーでアクセルを抜けば尻が出やすい。前のスタビを後ろの 3 倍にして、それを抑えています。"),
        facts=[(("Wheelbase", "ホイールベース"), "3.13 m"), (("Track", "トレッド"), "2.00 m"), (("Suspension travel", "サスペンションストローク"), "30 cm"),
               (("Tyre diameter", "タイヤ外径"), "90 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "4,500 / 1,500 N/m"), (("Max steering angle", "最大切れ角"), "35°")],
        parts=[
            (("Turbo", "タービン"), ("Stock / big turbo", "純正／ビッグタービン"), ("Boost comes in with the revs. The big turbo adds up to 70% more torque at the top end.", "回転が上がるほど過給が立ち上がります。ビッグタービンは高回転で最大 1.7 倍のトルク。"), ("Torque gain", "トルクの上乗せ"), "×1.7"),
            (("Intake", "吸気"), ("Blow-off valve", "ブローオフバルブ"), ("With a turbo fitted, it vents when you lift or shift up.", "タービン装着時に。アクセルを抜いた瞬間とシフトアップで鳴ります。"), ("Effect on performance", "性能への影響"), "0"),
            (("Tyres", "タイヤ"), ("Dirt", "ダート"), ("Grips on dirt and gravel; gives up a little on tarmac.", "土と砂利で強いタイヤ。舗装では少しグリップが落ちます。"), ("Grip on gravel", "砂利でのグリップ"), "×1.25"),
            (("Tyres", "タイヤ"), ("Off-road", "オフロード"), ("Best on sand and snow. On tarmac, the steering responds a little slower.", "砂と雪でいちばん強いタイヤ。舗装では舵の効き始めが鈍くなります。"), ("Grip on snow", "雪でのグリップ"), "×1.25"),
        ],
        specs=[
            (("Dimensions and weight", "寸法・重量"), [(("Wheelbase", "ホイールベース"), "3.13 m"), (("Track", "トレッド"), "2.00 m"), (("Weight", "車両重量"), "1,450 kg"), (("Weight distribution F : R", "前後重量配分"), "40 : 60"), (("Drag area (CdA)", "空気抵抗（CdA）"), "0.72 m²")]),
            (("Engine", "エンジン"), [(("Max power", "最高出力"), "335 PS / 6,500 rpm"), (("Max torque", "最大トルク"), "420 N·m / 4,400 rpm"), (("Idle", "アイドル回転数"), "800 rpm"), (("Rev limit", "レブリミット"), "7,400 rpm"), (("Forced induction", "過給"), T("None (turbo available)", "なし（タービン装着可）"))]),
            (("Drivetrain", "駆動系"), [(("Layout", "駆動方式"), T("RWD, rear engine", "RR（後輪駆動）")), (("Gearbox", "変速機"), T("5-speed, auto or manual", "5 速 AT／MT 切替")), (("1st / 5th", "1 速／5 速"), "4.20 / 0.85"), (("Final drive", "最終減速比"), "4.92"), (("Differential", "デフ"), T("Open", "オープン"))]),
            (("Chassis", "足まわり"), [(("Suspension travel", "サスペンションストローク"), "30 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "4,500 / 1,500 N/m"), (("Tyre diameter", "タイヤ外径"), "90 cm"), (("Max steering angle", "最大切れ角"), "35°"), (("ABS / TCS", "ABS／TCS"), T("Standard", "標準装備"))]),
            (("Performance", "性能"), [(("0-100 km/h", "0-100 km/h"), "5.40 s"), (("0-400 m", "0-400 m"), "13.42 s"), (("Top speed", "最高速度"), "276 km/h"), (("Power-to-weight", "パワーウエイトレシオ"), "4.33 kg/PS")]),
        ],
    ),
    "prado": dict(
        file="prado.html", name="Land Cruiser Prado", maker=("Toyota Land Cruiser Prado", "トヨタ ランドクルーザー プラド"), pack="basic",
        kind=("Off-road 4x4", "クロスカントリー 4WD"), filters="awd",
        source=("Basic Car Pack", "Basic Car Pack"),
        img="car-prado.jpg", alt=("Silver Land Cruiser Prado on a track", "コースに止まる銀色のランドクルーザー プラド"),
        hero="car-prado.jpg", hero_alt=("Silver Land Cruiser Prado on a track", "コースに止まる銀色のランドクルーザー プラド"),
        photo="offroad.jpg", photo_alt=("Land Cruiser Prado climbing a rocky trail", "山道の段差を登るランドクルーザー プラド"),
        catch=("Where the road ends, it keeps going.", "道が終わっても、走りは終わらない。"),
        intro=("A heavy, tall, proper 4x4. Low-rev torque shoves 2,100 kg forward. It won't keep up with the S15, but it climbs gravel and ledges without a second thought, and nothing else here can do that.",
               "重くて背が高い本格 4WD。2,100kg の車体を、低い回転から太いトルクで押し出します。速さでは S15 に敵いませんが、砂利も段差も気にせず登っていく頼もしさは、この 1 台だけのものです。"),
        figs=[("420", "N·m", ("Max torque", "最大トルク")), ("11.0", "s", ("0-100 km/h", "0-100 km/h")), ("2,100", "kg", ("Weight", "車両重量")), ("63", "cm", ("Ride height", "車高"))],
        perf=[("11.00", "s", ("0-100 km/h", "0-100 km/h")), ("17.74", "s", ("0-400 m", "0-400 m")), ("143", "km/h", ("Top speed", "最高速度")), ("11.3", "kg/PS", ("Power-to-weight", "パワーウエイトレシオ"))],
        tq=420, rt=2400, rp=3632.5, red=4600,
        engine=("Like a diesel: thick torque low down, and done by 4,600 rpm. Its 420 N·m at 2,400 rpm beats the S15. The boxy body has 3.5 times the drag, so top speed settles at 143 km/h.",
                "ディーゼルのように低回転で太く、4,600rpm で頭打ちになるエンジン。最大トルクは 2,400rpm で S15 を上回る 420N·m。箱形の車体は空気抵抗が S15 の 3.5 倍あり、最高速は 143km/h で釣り合います。"),
        front=54, layout=("AWD (50 : 50)", "4WD（前後 50 : 50）"),
        chassis=("Long suspension and a tall body. The high centre of gravity means more roll and more weight transfer, so the front anti-roll bar is stiffer and it trades eagerness for stability. Steering lock is 32°, less than a road car.",
                 "ストロークの長い足と、背の高い車体。重心が高いぶんロールも荷重移動も大きく出るので、前のスタビを厚くして、曲がりやすさより安定を取っています。切れ角は乗用車より浅い 32°。"),
        facts=[(("Wheelbase", "ホイールベース"), "3.00 m"), (("Track", "トレッド"), "1.90 m"), (("Suspension travel", "サスペンションストローク"), "28 cm"),
               (("Tyre diameter", "タイヤ外径"), "92 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "5,500 / 2,600 N/m"), (("Max steering angle", "最大切れ角"), "32°")],
        chassis_img="gravel.jpg", chassis_alt=("A car leaving a smoke trail on loose ground", "未舗装路に煙の跡を残して走る車"),
        parts=[
            (("Tyres", "タイヤ"), ("Off-road", "オフロード"), ("Best on sand and snow. This is where the Prado comes alive.", "砂と雪でいちばん強いタイヤ。この車の本領が出ます。"), ("Grip on sand", "砂でのグリップ"), "×1.35"),
            (("Tyres", "タイヤ"), ("Dirt", "ダート"), ("Grips on dirt and gravel, for when you want to go fast on forest tracks.", "土と砂利で強いタイヤ。林道を速く走りたいときに。"), ("Grip on dirt", "土でのグリップ"), "×1.20"),
            (("Turbo", "タービン"), ("Stock turbo", "純正タービン"), ("Boost arrives early, for more pull on hills.", "低回転から過給が立ち上がり、坂道でも余裕が出ます。"), ("Torque gain", "トルクの上乗せ"), "×1.35"),
            (("Intake", "吸気"), ("Blow-off valve", "ブローオフバルブ"), ("With a turbo fitted, it vents when you lift off.", "タービン装着時に。アクセルを抜いた瞬間に鳴ります。"), ("Effect on performance", "性能への影響"), "0"),
        ],
        specs=[
            (("Dimensions and weight", "寸法・重量"), [(("Wheelbase", "ホイールベース"), "3.00 m"), (("Track", "トレッド"), "1.90 m"), (("Weight", "車両重量"), "2,100 kg"), (("Weight distribution F : R", "前後重量配分"), "54 : 46"), (("Drag area (CdA)", "空気抵抗（CdA）"), "2.74 m²")]),
            (("Engine", "エンジン"), [(("Max power", "最高出力"), "186 PS / 3,630 rpm"), (("Max torque", "最大トルク"), "420 N·m / 2,400 rpm"), (("Idle", "アイドル回転数"), "800 rpm"), (("Rev limit", "レブリミット"), "4,600 rpm"), (("Forced induction", "過給"), T("None (turbo available)", "なし（タービン装着可）"))]),
            (("Drivetrain", "駆動系"), [(("Layout", "駆動方式"), T("AWD (50 : 50)", "4WD（前後 50 : 50）")), (("Gearbox", "変速機"), T("5-speed, auto or manual", "5 速 AT／MT 切替")), (("1st / 5th", "1 速／5 速"), "4.20 / 0.85"), (("Final drive", "最終減速比"), "5.03"), (("Differential", "デフ"), T("Open", "オープン"))]),
            (("Chassis", "足まわり"), [(("Suspension travel", "サスペンションストローク"), "28 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "5,500 / 2,600 N/m"), (("Tyre diameter", "タイヤ外径"), "92 cm"), (("Max steering angle", "最大切れ角"), "32°"), (("Rolling resistance", "転がり抵抗"), T("1.3× the S15", "S15 の 1.3 倍"))]),
            (("Performance", "性能"), [(("0-100 km/h", "0-100 km/h"), "11.00 s"), (("0-400 m", "0-400 m"), "17.74 s"), (("Top speed", "最高速度"), "143 km/h"), (("Power-to-weight", "パワーウエイトレシオ"), "11.3 kg/PS")]),
        ],
    ),
}

MEASURED = ("Measured with KurumaMod's own physics on flat tarmac, default setup, no parts, full throttle. Max power is calculated from the torque curve. Sizes are in Minecraft blocks (1 block = 1 m), about 1.2× a real car.",
            "性能は KurumaMod の物理演算で、平坦な舗装路・初期セッティング・パーツ無し・全開で測った値です。最高出力はトルク曲線から計算しています。寸法は 1 ブロック = 1m で、実車の約 1.2 倍の大きさです。")


# ---------- 車種ページ ----------
def model_page(key):
    m = MODELS[key]
    if m.get("hero"):
        hero_visual = f'<img src="img/{m["hero"]}" {ALT(*m["hero_alt"])}>'
    else:
        hero_visual = f'<div class="plate"><span>{m["plate"]}</span></div>'
    if m.get("chassis_img"):
        chassis_media = f'<div class="media"><img src="img/{m["chassis_img"]}" {ALT(*m["chassis_alt"])}></div>'
    elif m.get("chassis_plate"):
        chassis_media = f'<div class="media"><div class="plate"><span>{m["chassis_plate"]}</span></div></div>'
    else:
        chassis_media = f'<div class="media">{visual(m)}</div>'
    parts = ""
    for c, n, d, vl, v in m["parts"]:
        val = f"<b>{v}</b>" if v else ""
        parts += (f'<div class="part"><small>{T(*c)}</small><h3>{T(*n)}</h3><p>{T(*d)}</p>'
                  f'<div class="val">{T(*vl)}{val}</div></div>')
    parts_media = ""
    if m.get("parts_img"):
        parts_media = f'<div class="media" style="margin-top:40px;aspect-ratio:21/9"><img src="img/{m["parts_img"]}" {ALT(*m["parts_alt"])}></div>'
    specs = ""
    for cap, rows in m["specs"]:
        specs += f'<table class="specs"><caption>{T(*cap)}</caption>'
        specs += "".join(f"<tr><th>{T(*k)}</th><td>{v}</td></tr>" for k, v in rows) + "</table>"
    others = ""
    for k, o in MODELS.items():
        if k == key:
            continue
        others += (f'<a class="other" href="{o["file"]}">{visual(o)}<div class="in"><div><small>{T(*o["kind"])}</small>'
                   f'<h3>{o["name"]}</h3></div><span class="btn light arrow" style="min-height:44px">{T("Explore", "詳しく見る")}</span></div></a>')
    facts = "".join(f"<div><dt>{T(*k)}</dt><dd>{v}</dd></div>" for k, v in m["facts"])
    rear = 100 - m["front"]
    front = m["front"]
    if m["pack"] == "core":
        get = T("Comes with KurumaMod. Install it and the car is in the creative tab.", "KurumaMod に入っています。入れればクリエイティブタブに並びます。")
    else:
        get = T("Install KurumaMod and the Basic Car Pack. Both are free, and the pack is a single jar.", "KurumaMod と Basic Car Pack を入れるだけ。どちらも無料で、カーパックは jar 1 つです。")
    name = m["name"]
    if m.get("photo"):
        overview_media = f'<img src="img/{m["photo"]}" {ALT(*m["photo_alt"])}>'
    else:
        overview_media = visual(m)

    body = f"""
<section class="hero page" style="padding:0">
  {hero_visual}
  <div class="copy">
    <div class="wrap">
      <div class="crumb"><a href="index.html#cars">{T("Cars", "車種")}</a> / {T(*m["maker"])}</div>
      <h1>{name}</h1>
      <p>{T(*m["catch"])}</p>
      <div class="cta">{dl_buttons(m, light=True)}</div>
    </div>
  </div>
</section>

<div class="subnav">
  <div class="wrap">
    <span class="name">{name}</span>
    <nav aria-label="On this page">
      <a href="#overview">{T("Overview", "概要")}</a>
      <a href="#performance">{T("Performance", "パフォーマンス")}</a>
      <a href="#engine">{T("Engine", "エンジン")}</a>
      <a href="#chassis">{T("Chassis", "シャシー")}</a>
      <a href="#parts">{T("Parts", "パーツ")}</a>
      <a href="#specs">{T("Specs", "主要諸元")}</a>
    </nav>
    <a class="btn" href="#get">{T("Get it", "入手する")}</a>
  </div>
</div>

<section id="overview">
  <div class="wrap">
    {figs(m["figs"], light=True)}
    <div class="split" style="margin-top:clamp(56px,8vw,112px)">
      <div>
        <div class="eyebrow">{T(*m["kind"])} · {T(*m["source"])}</div>
        <h2 class="title">{T(*m["catch"])}</h2>
        <p>{T(*m["intro"])}</p>
      </div>
      <div class="media">{overview_media}</div>
    </div>
  </div>
</section>

<section class="dark" id="performance">
  <div class="wrap">
    <div class="eyebrow">{T("Performance", "パフォーマンス")}</div>
    <h2 class="title">{T("We didn't copy these numbers. We drove them.", "数字は、走らせて測りました。")}</h2>
    <p class="lead">{T("Every figure below comes from running KurumaMod's own physics: flat tarmac, default setup, full throttle.", "下の数字はすべて、KurumaMod の物理演算で平坦な舗装路を初期セッティングのまま全開で走らせた結果です。")}</p>
    {figs(m["perf"])}
  </div>
</section>

<section class="mist" id="engine">
  <div class="wrap">
    <div class="split rev">
      <div>
        <div class="eyebrow">{T("Engine", "エンジン")}</div>
        <h2 class="title">{m["tq"]} N·m / {m["rt"]:,} rpm</h2>
        <p>{T(*m["engine"])}</p>
      </div>
      <div class="chart" data-tq="{m["tq"]}" data-rt="{m["rt"]}" data-rp="{m["rp"]}" data-red="{m["red"]}">
        <div class="legend"><span><i></i>{T("Torque (N·m)", "トルク（N·m）")}</span><span><i class="pw"></i>{T("Power (PS)", "出力（PS）")}</span></div>
        <svg viewBox="0 0 720 380" role="img" aria-label="{name} torque and power curves"></svg>
      </div>
    </div>
  </div>
</section>

<section class="dark" id="chassis">
  <div class="wrap">
    <div class="split">
      <div>
        <div class="eyebrow">{T("Chassis", "シャシー")}</div>
        <h2 class="title">{T(f"Front {front} : Rear {rear}", f"前 {front} ： 後 {rear}")}</h2>
        <p>{T(*m["chassis"])}</p>
        <div class="wd">
          <div class="wdbar"><div class="f" style="width:{front}%">{front}%</div><div class="r">{rear}%</div></div>
          <div class="cap"><span>{T("Front axle", "前軸")}</span><span>{T(*m["layout"])}</span><span>{T("Rear axle", "後軸")}</span></div>
        </div>
        <dl class="facts">{facts}</dl>
      </div>
      {chassis_media}
    </div>
  </div>
</section>

<section class="mist" id="parts">
  <div class="wrap">
    <div class="eyebrow">{T("Parts", "パーツ")}</div>
    <h2 class="title">{T("Swap parts and see them on your car.", "換装画面で、付けたまま見比べる。")}</h2>
    <p class="lead">{T("Parts change while you sit in the car. Turbos, tyres and aero change how it drives; mufflers and blow-off valves only change the sound.", "パーツは車に乗ったまま付け替えられます。走りが変わるのはタービン・タイヤ・エアロで、マフラーとブローオフは音だけが変わります。")}</p>
    {parts_media}
    <div class="parts">{parts}</div>
  </div>
</section>

<section id="specs">
  <div class="wrap">
    <h2 class="title">{T("Specifications", "主要諸元")}</h2>
    <div class="specs-grid">{specs}</div>
    <p class="note">{T(*MEASURED)} {T("Change the setup with G and these values change too.", "G キーのセッティングで変えると、これらの値も変わります。")}</p>
  </div>
</section>

<section class="mist cta-band" id="get">
  <div class="wrap">
    <div>
      <h2 class="title">{T(f"Drive the {name}", f"{name} に乗る")}</h2>
      <p class="lead">{get}</p>
    </div>
    <div class="acts">{dl_buttons(m)}</div>
  </div>
</section>

<section>
  <div class="wrap">
    <h2 class="title">{T("More cars", "他の車種")}</h2>
    <div class="others">{others}</div>
  </div>
</section>
"""
    page(m["file"], f"{name} | KurumaMod", f"{name} | KurumaMod", body)


for k in MODELS:
    model_page(k)


# ---------- トップ ----------
def model_card(m):
    tag = "<b>NEW</b>" if m.get("new") else ""
    dl = "".join(f"<div><dt>{T(*l)}</dt><dd>{v} {u}</dd></div>" for v, u, l in m["figs"][:3])
    return f"""<article class="model" data-kind="{m["filters"]}">
        <div class="shot">{visual(m)}</div>
        <div class="txt">
          <div class="tag">{tag}<span>{T(*m["kind"])}</span></div>
          <h3 class="latin">{m["name"]}</h3>
          <dl>{dl}</dl>
          <div class="price">{T(*m["source"])}</div>
          <div class="acts">
            <a class="btn" href="{m["file"]}">{T("Explore", "詳しく見る")}</a>
            <a class="btn outline" href="compare.html">{T("Compare", "比較する")}</a>
          </div>
        </div>
      </article>"""


def tile(cls, img, alt, small, h, p):
    return f"""<a class="tile {cls}" href="features.html">
        <img src="img/{img}" {ALT(*alt)}>
        <div class="in"><small>{T(*small)}</small><h3>{h}</h3><p>{T(*p)}</p></div>
      </a>"""


NEWS = [
    (("Aero parts", "エアロパーツ"), ("Bumpers, side skirts, fenders, rear wings and diffusers. Wings and diffusers make real downforce, and you set the wing angle.", "バンパー、サイドスカート、フェンダー、リアウイング、ディフューザー。ウイングとディフューザーは本当にダウンフォースを生み、角度も決められます。")),
    (("Mufflers", "マフラー"), ("Change the exhaust note, with a slider for the tone. Everyone nearby hears the same sound.", "排気音が変わり、音色はスライダーで調整。周りの人にも同じ音で聞こえます。")),
    (("Easier countersteer", "カウンターが当てやすく"), ("The steering limit now follows the slide, so you can catch the rear when it steps out.", "切れ角の上限が滑りに合わせて動くので、リアが出ても当て舵で拾えます。")),
    (("Analog handbrake", "アナログのサイドブレーキ"), ("Map a wheel's handbrake lever, and how far you pull sets how hard it bites.", "ハンコンのサイドレバーを割り当てると、引いた量で効きが変わります。")),
    (("Camera modes", "カメラモード"), ("V cycles follow, horizon-level and free views. The first-person view now tilts with the body.", "V で追従・水平維持・自由を切り替え。一人称の視点は車体と一緒に傾くように。")),
    (("Porsche 911 and a new Prado", "ポルシェ 911 と新しいプラド"), ("Basic Car Pack 1.2.0 adds the 911 and a remade Land Cruiser Prado.", "Basic Car Pack 1.2.0 で 911 を追加し、プラドを作り直しました。")),
]
news = "".join(f"<div><dt>{T(*h)}</dt><dd>{T(*d)}</dd></div>" for h, d in NEWS)
cards = "".join(model_card(m) for m in MODELS.values())
key_l = '<span class="key">L</span>'

page("index.html", "KurumaMod", "KurumaMod", f"""
<section class="hero" style="padding:0">
  <img src="img/eyecatch.jpg" {ALT("Silvia S15 on track by day and night", "昼と夜のサーキットを走るシルビア S15")}>
  <div class="copy">
    <div class="wrap">
      <div class="kicker">{T("A driving mod for Minecraft · Forge 1.20.1 / NeoForge 1.21.1", "Minecraft のドライブ MOD · Forge 1.20.1 / NeoForge 1.21.1")}</div>
      <h1 class="brand" aria-label="KurumaMod">KURUMA</h1>
      <p>{T("Gran Turismo, in Minecraft. The front dips when you brake, the rear squats when you accelerate, and if you get greedy with the throttle it steps sideways.",
            "Minecraft で、グランツーリスモ。ブレーキでフロントが沈み、アクセルでリアが沈み、踏みすぎればタイヤを鳴らして横を向く。")}</p>
      <div class="cta">
        <a class="btn light arrow" href="{CF}">{T("Download on CurseForge", "CurseForge でダウンロード")}</a>
        <a class="btn outline on-dark" href="{MR}">Modrinth</a>
      </div>
    </div>
  </div>
</section>

<div class="badges">
  <div class="wrap">
    <div><b>{T("No dependencies", "前提 MOD なし")}</b><span>{T("Just drop it in mods/", "mods に入れるだけ")}</span></div>
    <div><b>{T("Multiplayer", "マルチプレイ")}</b><span>{T("Race your friends", "友だちとレース")}</span></div>
    <div><b>{T("Wheel and gamepad", "ハンコン・パッド")}</b><span>{T("Fully analog input", "フルアナログ入力")}</span></div>
    <div><b>{T("Free and open source", "無料・オープンソース")}</b><span>GPL-3.0</span></div>
  </div>
</div>

<section id="cars">
  <div class="wrap">
    <h2 class="title">{T("Choose your car", "車を選ぶ")}</h2>
    <p class="lead">{T("All three run on the same physics. Weight, balance and drivetrain are what make them drive differently.", "3 台とも同じ物理で走ります。重さ、重心の位置、駆動方式の違いが、そのまま走りの違いになります。")}</p>
    <div class="filters" role="group" aria-label="Filter">
      <button type="button" aria-pressed="true" data-filter="all">{T("All", "すべて")}</button>
      <button type="button" aria-pressed="false" data-filter="sport">{T("Sports cars", "スポーツカー")}</button>
      <button type="button" aria-pressed="false" data-filter="awd">{T("All-wheel drive", "4WD")}</button>
    </div>
    <div class="models">{cards}</div>
  </div>
</section>

<section class="dark">
  <div class="wrap">
    <div class="split">
      <div>
        <div class="eyebrow">{T("Why KurumaMod", "なぜ KurumaMod なのか")}</div>
        <h2 class="title">{T("Built around the driving itself.", "作り込んだのは、運転そのもの。")}</h2>
        <p>{T("Suspension, weight transfer, tyre grip, engine and gearbox, differential. Grip fades gradually instead of vanishing, so you feel the limit coming before you reach it. Pull the handbrake and the clutch drops for you, the rear locks, and you can hold it in a drift.",
              "サスペンション、荷重移動、タイヤのグリップ、エンジンとギア、デフ。グリップはいきなり消えずに少しずつ抜けるので、限界が近づいてくるのが分かります。サイドブレーキを引けばクラッチが自動で切れ、リアがロックして、そのままドリフトに持ち込めます。")}</p>
        <div class="cta-row"><a class="btn light arrow" href="features.html">{T("See all features", "特徴をすべて見る")}</a></div>
      </div>
      <div class="media"><img src="img/drift.jpg" {ALT("Silvia S15 drifting", "ドリフトするシルビア S15")}></div>
    </div>
    {figs([("80", "Hz", ("Physics updates per second", "物理の計算回数（毎秒）")), ("40+", "", ("Setup options", "セッティング項目")), ("6", "", ("Road surfaces", "路面の種類")), ("0", "", ("Required mods", "必要な前提 MOD"))])}
  </div>
</section>

<section id="features">
  <div class="wrap">
    <h2 class="title">{T("More than a car-shaped block", "車の形をしたブロックではありません")}</h2>
    <div class="tiles">
      {tile("wide", "hud.jpg", ("Silvia S15 with gauges on screen", "回転計と速度計を表示して走る S15"), ("Physics", "物理演算"), T("You feel the limit before you reach it.", "限界の手前が、手に伝わる。"), ("Steering follows the slide, so there's always enough opposite lock to catch it.", "ハンドルは滑りに合わせて切れるので、流れた分だけカウンターを当てられます。"))}
      {tile("two", "eyecatch.jpg", ("Headlights on a night track", "夜のサーキットをヘッドライトで照らして走る"), ("Lighting", "ライティング"), key_l + T("Headlights that really light the road", "路面を本当に照らすヘッドライト"), ("A proper low beam, shadows from trees and walls, beams in fog and rain. Works with shader packs.", "カットオフの付いたロービーム、木や壁の影、霧や雨の中の光の筋。シェーダーパックにも対応。"))}
      {tile("third", "gravel.jpg", ("A car leaving a smoke trail on loose ground", "未舗装路に煙の跡を残して走る車"), ("Surfaces", "路面"), T("Six surfaces and rain", "6 種類の路面と雨"), ("Tarmac, dirt, gravel, sand, snow and ice each drive differently.", "舗装、土、砂利、砂、雪、氷で、走りがまったく変わります。"))}
      {tile("third", "camber.jpg", ("Wheels with negative camber", "キャンバーを付けたホイール"), ("Customise", "カスタマイズ"), T("Aero, mufflers, wheels", "エアロ、マフラー、ホイール"), ("Camber, offset and width, plus wings that make real downforce.", "キャンバー、オフセット、幅。ウイングは本当にダウンフォースを生みます。"))}
      {tile("two", "race.jpg", ("Start lights on a race track", "スタートシグナルの点いたコース"), ("Racing", "レース"), T("Race your friends", "友だちとレースを"), ("Start lights, live standings and split times, in multiplayer.", "スタートシグナル、リアルタイムの順位、区間タイム。マルチプレイで。"))}
    </div>
  </div>
</section>

<section class="mist" id="news">
  <div class="wrap">
    <div class="split" style="align-items:start">
      <div>
        <div class="eyebrow">{T("What's new", "新着")}</div>
        <h2 class="title">KurumaMod 1.7.0</h2>
        <p>{T("Aero parts, mufflers, easier countersteer and the Porsche 911.", "エアロパーツ、マフラー、当てやすくなったカウンター、そしてポルシェ 911。")}</p>
        <div class="cta-row"><a class="btn arrow" href="{CF}">{T("Download 1.7.0", "1.7.0 をダウンロード")}</a></div>
      </div>
      <dl class="news-list">{news}</dl>
    </div>
  </div>
</section>

<section>
  <div class="wrap">
    <h2 class="title">{T("Add more cars", "車を追加する")}</h2>
    <p class="lead">{T("A car pack is a single jar. Put it in mods/ and the cars are there.", "カーパックは jar ひとつ。mods に入れるだけで車が増えます。")}</p>
    <div class="packs">
      <a class="pack" href="{CF_BASIC}"><small>{T("Official", "公式")}</small><h3>Basic Car Pack</h3><p>{T("Adds the Porsche 911 and the Toyota Land Cruiser Prado.", "ポルシェ 911 とトヨタ ランドクルーザー プラドを追加します。")}</p><span class="go">CurseForge</span></a>
      <a class="pack" href="{GUIDE}"><small>{T("Create", "作る")}</small><h3>{T("Car pack guide", "カーパック作成ガイド")}</h3><p>{T("Build your own car in Blender. No coding, and no Blender experience needed.", "Blender で自分の車を作る。コードは不要、Blender 未経験でも大丈夫。")}</p><span class="go">{T("Read the guide", "ガイドを読む")}</span></a>
      <a class="pack" href="{CF_EXAMPLE}"><small>{T("Template", "ひな型")}</small><h3>Example Car Pack</h3><p>{T("A sample car and wheels to copy as your starting point. No credit required.", "土台にできるサンプルの車とホイール。クレジット表記も不要です。")}</p><span class="go">CurseForge</span></a>
    </div>
  </div>
</section>

<section class="dark cta-band">
  <div class="wrap">
    <div>
      <h2 class="title">{T("Join the community", "コミュニティに参加")}</h2>
      <p class="lead">{T("Share setups, show off your builds, and hear about new cars first.", "セッティングを共有したり、作った車を見せ合ったり。新しい車の情報もいち早く。")}</p>
    </div>
    <div class="acts">
      <a class="btn light arrow" href="{DISCORD}">Discord</a>
      <a class="btn outline on-dark" href="{X}">X @kurumamod</a>
    </div>
  </div>
</section>
""")


# ---------- 特徴 ----------
def feature(id_, cls, rev, img, alt, eyebrow, title, text):
    rev_cls = " rev" if rev else ""
    return f"""<section class="{cls}" id="{id_}">
  <div class="wrap">
    <div class="split{rev_cls}">
      <div>
        <div class="eyebrow">{T(*eyebrow)}</div>
        <h2 class="title">{title}</h2>
        <p>{T(*text)}</p>
      </div>
      <div class="media"><img src="img/{img}" {ALT(*alt)}></div>
    </div>
  </div>
</section>
"""


KEYS = [
    ("W / S", ("Throttle / brake", "アクセル／ブレーキ")),
    ("A / D", ("Steer", "ステアリング")),
    ("Space", ("Handbrake", "サイドブレーキ")),
    ("R / F", ("Shift up / down (manual)", "シフトアップ／ダウン（MT）")),
    ("Shift", ("Get out", "降りる")),
    ("G", ("Setup, presets and parts", "セッティング・プリセット・パーツ")),
    ("H", ("Mod menu (display and camera)", "MOD メニュー（表示とカメラ）")),
    ("V", ("Camera mode", "カメラモード")),
    ("PgUp / PgDn", ("Camera distance", "カメラの距離")),
    ("L", ("Headlights", "ヘッドライト")),
    ("Ctrl+L", ("Headlight settings", "ヘッドライトの調整")),
    ("J", ("Controller setup", "コントローラ設定")),
]


def keys_html():
    out = []
    for ks, d in KEYS:
        caps = "".join(f'<span class="key">{k}</span>' for k in ks.split(" / "))
        out.append(f'<div>{caps}<span class="desc">{T(*d)}</span></div>')
    return "".join(out)


SURF = [(("Tarmac", "舗装"), "1.00", "5.34", "19.2"), (("Dirt, grass", "土・草"), "0.75", "5.89", "23.4"), (("Gravel", "砂利"), "0.65", "6.03", "27.1"),
        (("Sand", "砂"), "0.55", "7.24", "28.1"), (("Snow", "雪"), "0.45", "7.45", "36.0"), (("Ice", "氷"), "0.15", "21.11", "112.1")]
surf_rows = "".join(f"<tr><th>{T(*n)}</th><td>{g}</td><td>{a} s</td><td>{b} m</td></tr>" for n, g, a, b in SURF)
key_g = '<span class="key" style="border-color:var(--black)">G</span>'

page("features.html", "Features | KurumaMod", "特徴 | KurumaMod", f"""
<section class="hero page" style="padding:0">
  <img src="img/hud.jpg" {ALT("Silvia S15 on a race track with gauges", "回転計と速度計を表示してサーキットを走る S15")}>
  <div class="copy">
    <div class="wrap">
      <div class="crumb"><a href="index.html">KurumaMod</a> / {T("Features", "特徴")}</div>
      <h1>Features</h1>
      <p>{T("Suspension compresses, weight shifts, tyres slide. KurumaMod works all of it out 80 times a second.", "サスペンションが縮み、荷重が移り、タイヤが滑る。KurumaMod はそのすべてを毎秒 80 回計算しています。")}</p>
    </div>
  </div>
</section>

<div class="subnav">
  <div class="wrap">
    <span class="name">Features</span>
    <nav aria-label="On this page">
      <a href="#physics">{T("Physics", "物理演算")}</a>
      <a href="#setup">{T("Setup", "セッティング")}</a>
      <a href="#lighting">{T("Headlights", "ヘッドライト")}</a>
      <a href="#surface">{T("Surfaces", "路面と天候")}</a>
      <a href="#custom">{T("Customise", "カスタマイズ")}</a>
      <a href="#racing">{T("Racing", "レース")}</a>
      <a href="#offroad">{T("Off-road", "オフロード")}</a>
      <a href="#controls">{T("Controls", "操作")}</a>
    </nav>
    <a class="btn" href="start.html#download">{T("Download", "ダウンロード")}</a>
  </div>
</div>

<section id="physics">
  <div class="wrap">
    <div class="eyebrow">{T("Physics", "物理演算")}</div>
    <h2 class="title">{T("Drives like the real thing.", "実車のような走り。")}</h2>
    <p class="lead">{T("You can't throw it into a tight corner at full speed. Brake before the turn, scrub off the speed, and it goes round cleanly. Grip fades gradually, so you feel the front start to run wide.",
                       "スピードを出したまま急なカーブに飛び込んでも曲がりきれません。手前でブレーキを踏んで速度を落とせば、きれいに曲がれます。グリップは少しずつ抜けるので、フロントが外へ逃げ始めればそれが分かります。")}</p>
    {figs([("80", "Hz", ("Physics rate (4 steps per tick)", "物理の計算（1 ティックを 4 分割）")), ("2.2", "°/G", ("Body roll of the S15", "S15 のロール")), ("0.93", "G", ("Max lateral G on tarmac", "舗装での最大横 G")), ("6", "", ("Road surfaces", "路面の種類"))], light=True)}
    <div class="split" style="margin-top:clamp(56px,8vw,112px)">
      <div>
        <h3 class="title" style="font-size:clamp(24px,2.6vw,34px)">{T("Catch the slide.", "流れても、拾える。")}</h3>
        <p>{T("The steering limit is measured from where the front wheels are actually going, so when the rear steps out you have the lock to catch it. Pull the handbrake and the clutch drops for you: the rear locks and the car swings round.",
              "切れ角の上限は、前輪が実際に進んでいる向きから測ります。リアが出て車が横を向いても、そのぶんハンドルが切れるのでカウンターで拾えます。サイドブレーキを引けばクラッチが自動で切れ、後輪がロックして向きを変えます。")}</p>
      </div>
      <div class="media"><img src="img/drift.jpg" {ALT("Silvia S15 drifting", "ドリフトするシルビア S15")}></div>
    </div>
  </div>
</section>

{feature("setup", "mist", True, "custom.jpg", ("The setup screen", "セッティング画面"), ("Setup", "セッティング"), key_g + T("Tune as you drive.", "走りながらセッティング。"),
         ("Press G and the setup screen opens without pausing the game. Springs, anti-roll bars, tyres, torque curve, gear ratios, LSD, brakes, weight distribution and downforce: move a slider and the car changes from the next corner. Start from the Drift or Off-road preset, and save your own setups to share with friends.",
          "G を押すと、ゲームを止めずにセッティング画面が開きます。バネ、スタビ、タイヤ、トルクカーブ、ギア比、LSD、ブレーキ、重量配分、ダウンフォース。スライダーを動かせば、次のコーナーから挙動が変わります。ドリフト／オフロードのプリセットから始めて、自分のセッティングを保存して友達と共有できます。"))}
{feature("lighting", "dark", False, "eyecatch.jpg", ("Silvia S15 by day and night", "昼と夜のサーキットを走る S15"), ("Headlights", "ヘッドライト"), key_l + T("Night driving.", "夜のドライブ。"),
         ("Switch the headlights on and they really light the road: a low beam with a proper cut-off, beams glowing in fog and rain, and shadows cast by trees and walls. Drift through your own tyre smoke and it lights up in the beam. Every car's lights work, and they work with shader packs (Iris / Oculus) too. Tune the aim, brightness and reach with Ctrl+L.",
          "ヘッドライトを点けると、本当に路面が照らされます。カットオフの付いたロービーム、霧や雨の中に浮かぶ光の筋、木や塀が落とす影。ドリフトの煙も光を受けて浮かび上がります。全車のライトが路面を照らし、シェーダーパック（Iris / Oculus）とも一緒に使えます。光軸・明るさ・届く距離は Ctrl+L で調整できます。"))}

<section id="surface">
  <div class="wrap">
    <div class="split rev">
      <div>
        <div class="eyebrow">{T("Surfaces and weather", "路面と天候")}</div>
        <h2 class="title">{T("Change the surface, change the car.", "路面が変われば、別の車になる。")}</h2>
        <p>{T("Tarmac, dirt, gravel, sand, snow, ice. Rain makes the road slippery, while tunnels and bridges stay dry. Roads from other mods work as they are.",
              "舗装・土・砂利・砂・雪・氷。雨が降れば滑りやすくなり、トンネルや橋の下は乾いたまま。他 MOD の道路ブロックの上でも、そのまま走れます。")}</p>
      </div>
      <div class="media"><img src="img/gravel.jpg" {ALT("A car leaving a smoke trail on loose ground", "未舗装路に煙の跡を残して走る車")}></div>
    </div>
    <div class="compare-wrap">
      <table class="compare">
        <thead><tr><th scope="col">{T("Surface", "路面")}</th><th scope="col">{T("Grip", "グリップ")}</th><th scope="col">0-100 km/h</th><th scope="col">{T("Braking from 72 km/h", "72km/h からの制動距離")}</th></tr></thead>
        <tbody>{surf_rows}</tbody>
      </table>
    </div>
    <p class="note">{T("Measured with the mod's reference car (AWD, 350 N·m).", "MOD の基準車（4WD・350N·m）で測った値です。")}</p>
  </div>
</section>

{feature("custom", "mist", False, "camber.jpg", ("Wheels with negative camber", "キャンバーを付けたホイール"), ("Customise", "カスタマイズ"), T("Make it yours.", "見た目を自分好みに。"),
         ("Set camber, offset and wheel width until it sits just right: flush, poke, or full oni-kyan. Bolt on bumpers, side skirts, fenders, a rear wing and a diffuser. Wings and diffusers make real downforce. Mufflers change the exhaust note, and everyone nearby hears it. Gauges come in 90s JDM factory or modern style.",
          "キャンバー・オフセット・ホイールの太さで、好きな姿に。ツライチも、ハミタイも、鬼キャンも。バンパー、サイドスカート、フェンダー、リアウイング、ディフューザーも付けられ、ウイングとディフューザーは本当にダウンフォースを生みます。マフラーで排気音が変わり、周りの人にも同じ音で聞こえます。メーターは 90 年代の国産純正風とモダンの 2 種類。"))}
{feature("racing", "", True, "race.jpg", ("Start lights on a race track", "スタートシグナルの点いたコース"), ("Racing", "レース"), T("Race your friends.", "友だちとレースを。"),
         ("Place a start line and checkpoints anywhere in your world and hold a proper race: start lights, live standings, and your gap to your best lap at every checkpoint. Multiplayer, timed to the millisecond. Cars can collide with each other, and servers can turn that off.",
          "ワールドのどこにでもスタートラインとチェックポイントを置いて、本格的なレースを開けます。スタートシグナル、リアルタイムの順位、チェックポイントごとのベストラップとの差。マルチプレイ対応、計測はミリ秒単位。車同士の接触もあります（サーバー側で切れます）。"))}
{feature("offroad", "mist", False, "offroad.jpg", ("Land Cruiser Prado climbing a rocky trail", "山道の段差を登るランドクルーザー プラド"), ("Off-road", "オフロード"), T("Head for the hills.", "土煙を上げて、山へ。"),
         ("Load the Off-road preset for four-wheel drive, a locking diff, long suspension travel and big tyres. Climb one-block ledges, let the diff pull you out of a ditch with a wheel in the air, and head into the hills with dust trailing behind you.",
          "オフロードプリセットを読み込めば、四駆・ロックするデフ・長いストローク・大径タイヤの仕様に。1 ブロックの段差をよじ登り、片輪が浮いた溝もデフの力で抜け出して、土煙を上げながら山へ。"))}

<section class="dark" id="controls">
  <div class="wrap">
    <div class="eyebrow">{T("Controls", "操作")}</div>
    <h2 class="title">{T("Keyboard, wheel or gamepad.", "キーボードでも、ハンコンでも。")}</h2>
    <p class="lead">{T("Throttle, brake, steering and handbrake are all fully analog. Plug in a controller, press J, and move the axis you want to assign. Every key can be changed in Options → Controls.",
                       "アクセル・ブレーキ・ステアリング・サイドブレーキはすべてアナログ対応。つないで J を押し、割り当てたい軸を動かすだけ。キーはすべて「設定 → 操作設定」で変更できます。")}</p>
    <div class="keys">{keys_html()}</div>
  </div>
</section>
""")


# ---------- 比較 ----------
ROWS = [
    (("Performance", "性能"), None),
    (("0-100 km/h", "0-100 km/h"), ("5.25 s", "5.40 s", "11.00 s"), "min"),
    (("0-400 m", "0-400 m"), ("13.37 s", "13.42 s", "17.74 s"), "min"),
    (("Top speed", "最高速度"), ("255 km/h", "276 km/h", "143 km/h"), "max"),
    (("Power-to-weight", "パワーウエイトレシオ"), ("4.27 kg/PS", "4.33 kg/PS", "11.3 kg/PS"), "min"),
    (("Engine", "エンジン"), None),
    (("Max power", "最高出力"), ("281 PS / 6,680 rpm", "335 PS / 6,500 rpm", "186 PS / 3,630 rpm"), "max"),
    (("Max torque", "最大トルク"), ("350 N·m / 4,200 rpm", "420 N·m / 4,400 rpm", "420 N·m / 2,400 rpm"), None),
    (("Rev limit", "レブリミット"), ("7,000 rpm", "7,400 rpm", "4,600 rpm"), None),
    (("Idle", "アイドル回転数"), ("800 rpm", "800 rpm", "800 rpm"), None),
    (("Drivetrain", "駆動系"), None),
    (("Layout", "駆動方式"), (T("AWD", "4WD"), T("RWD, rear engine", "RR"), T("AWD", "4WD")), None),
    (("Gearbox", "変速機"), (T("5-speed AT / MT", "5 速 AT／MT"),) * 3, None),
    (("Final drive", "最終減速比"), ("4.37", "4.92", "5.03"), None),
    (("Body and chassis", "ボディ・足まわり"), None),
    (("Weight", "車両重量"), ("1,200 kg", "1,450 kg", "2,100 kg"), None),
    (("Weight distribution F : R", "前後重量配分"), ("56 : 44", "40 : 60", "54 : 46"), None),
    (("Wheelbase", "ホイールベース"), ("3.12 m", "3.13 m", "3.00 m"), None),
    (("Track", "トレッド"), ("1.80 m", "2.00 m", "1.90 m"), None),
    (("Suspension travel", "サスペンションストローク"), ("28 cm", "30 cm", "28 cm"), None),
    (("Tyre diameter", "タイヤ外径"), ("80 cm", "90 cm", "92 cm"), None),
    (("Max steering angle", "最大切れ角"), ("35°", "35°", "32°"), None),
    (("Drag area (CdA)", "空気抵抗（CdA）"), ("0.78 m²", "0.72 m²", "2.74 m²"), "min"),
    (("Get it", "入手"), None),
    (("Included in", "収録"), ("KurumaMod", "Basic Car Pack", "Basic Car Pack"), None),
]


def num(s):
    return float(re.match(r"[\d,.]+", s).group().replace(",", ""))


rows_html = ""
for row in ROWS:
    label, vals = row[0], row[1]
    if vals is None:
        rows_html += f'<tr class="sec"><th colspan="4">{T(*label)}</th></tr>'
        continue
    best = row[2]
    pick = None
    if best:
        ns = [num(v) for v in vals]
        pick = ns.index(min(ns) if best == "min" else max(ns))
    badge = '<span class="best">BEST</span>'
    cells = "".join(f"<td>{v}{badge if i == pick else ''}</td>" for i, v in enumerate(vals))
    tr = '<tr class="same">' if len(set(vals)) == 1 else "<tr>"
    rows_html += f"{tr}<th>{T(*label)}</th>{cells}</tr>"

heads = "".join(f'<th scope="col"><a href="{m["file"]}"><span class="thumb">{visual(m)}</span>{m["name"]}</a></th>' for m in MODELS.values())

page("compare.html", "Compare cars | KurumaMod", "車種を比較 | KurumaMod", f"""
<section class="hero page" style="padding:0">
  <img src="img/race.jpg" {ALT("Start lights on a race track", "スタートシグナルの点いたコース")}>
  <div class="copy">
    <div class="wrap">
      <div class="crumb"><a href="index.html">KurumaMod</a> / {T("Compare", "比較")}</div>
      <h1>Compare</h1>
      <p>{T("Three cars, one set of physics, measured the same way.", "同じ物理で走る 3 台を、同じ条件で測りました。")}</p>
    </div>
  </div>
</section>

<section>
  <div class="wrap">
    <h2 class="title">{T("Compare cars", "車種を比較")}</h2>
    <p class="lead">{T("BEST marks the strongest of the three.", "BEST は 3 台の中でいちばん良い値です。")}</p>
    <label class="toggle" for="show-same"><input type="checkbox" id="show-same"> {T("Show rows that are the same for all three", "3 台とも同じ項目も表示する")}</label>
    <div class="compare-wrap">
      <table class="compare">
        <thead><tr><th scope="col"></th>{heads}</tr></thead>
        <tbody>{rows_html}</tbody>
      </table>
    </div>
    <p class="note">{T(*MEASURED)}</p>
  </div>
</section>

<section class="mist cta-band">
  <div class="wrap">
    <div>
      <h2 class="title">{T("Can't decide? Drive all three.", "迷ったら、全部乗る。")}</h2>
      <p class="lead">{T("KurumaMod and the Basic Car Pack are both free.", "KurumaMod も Basic Car Pack も無料です。")}</p>
    </div>
    <div class="acts"><a class="btn arrow" href="start.html#download">{T("Download", "ダウンロード")}</a></div>
  </div>
</section>
""")


# ---------- はじめかた ----------
guide_en = f'Follow the <a href="{GUIDE}">car pack guide</a> and start from the <a href="{CF_EXAMPLE}">Example Car Pack</a>. No coding, and no credit required.'
guide_ja = f'<a href="{GUIDE}">カーパック作成ガイド</a>を見ながら、<a href="{CF_EXAMPLE}">Example Car Pack</a> をひな型に。コードは不要、クレジット表記も不要です。'

page("start.html", "Get started | KurumaMod", "はじめかた | KurumaMod", f"""
<section class="hero page" style="padding:0">
  <img src="img/hud.jpg" {ALT("Silvia S15 on the start line", "スタートラインに止まる S15")}>
  <div class="copy">
    <div class="wrap">
      <div class="crumb"><a href="index.html">KurumaMod</a> / {T("Get started", "はじめかた")}</div>
      <h1>Get started</h1>
      <p>{T("From download to driving in four steps.", "ダウンロードから走り出すまで、4 ステップ。")}</p>
      <div class="cta"><a class="btn light arrow" href="#download">{T("Download", "ダウンロード")}</a></div>
    </div>
  </div>
</section>

<section class="mist" id="download">
  <div class="wrap">
    <div class="eyebrow">{T("Download", "ダウンロード")}</div>
    <h2 class="title">{T("Pick your loader.", "ローダーを選ぶ。")}</h2>
    <p class="lead">{T("Both versions are on the same page. No other mods required.", "どちらも同じページから。前提 MOD はいりません。")}</p>
    <div class="packs">
      <div class="pack static"><small>Forge</small><h3>Minecraft 1.20.1</h3><p>{T("Forge 47.4.22 or later.", "Forge 47.4.22 以上。")}</p></div>
      <div class="pack static"><small>NeoForge</small><h3>Minecraft 1.21.1</h3><p>{T("NeoForge 21.1 or later.", "NeoForge 21.1 以上。")}</p></div>
      <div class="pack static"><small>{T("Car packs", "カーパック")}</small><h3>{T("One jar for both", "1 つの jar で両対応")}</h3><p>{T("Car packs work on Forge and NeoForge alike.", "カーパックは Forge でも NeoForge でも動きます。")}</p></div>
    </div>
    <div class="cta-row">
      <a class="btn arrow" href="{CF}">{T("KurumaMod on CurseForge", "KurumaMod（CurseForge）")}</a>
      <a class="btn outline" href="{MR}">{T("KurumaMod on Modrinth", "KurumaMod（Modrinth）")}</a>
      <a class="btn outline" href="{CF_BASIC}">Basic Car Pack</a>
    </div>
  </div>
</section>

<section id="install">
  <div class="wrap">
    <div class="eyebrow">{T("Install", "インストール")}</div>
    <h2 class="title">{T("Four steps to the driver's seat.", "4 ステップで、運転席へ。")}</h2>
    <ol class="steps">
      <li><h3>{T("Put KurumaMod in mods/", "KurumaMod を mods に入れる")}</h3><p>{T("Add the Basic Car Pack too if you want the 911 and the Prado.", "911 とプラドに乗るなら、Basic Car Pack も一緒に。")}</p></li>
      <li><h3>{T("Open the KurumaMod tab", "KurumaMod タブを開く")}</h3><p>{T("Each car has its own item in the creative inventory.", "クリエイティブインベントリに、車種ごとのアイテムが並びます。")}</p></li>
      <li><h3>{T("Place it and get in", "置いて、乗る")}</h3><p>{T("Right-click the ground to place it, then right-click the car to get in.", "地面を右クリックで置き、車を右クリックで乗ります。")}</p></li>
      <li><h3>{T("Drive!", "走る！")}</h3><p>{T("Seats two, and whoever gets in first drives. Shift + attack picks the car up with its setup.", "2 人乗りで、先に乗った方が運転。Shift ＋ 殴るで、セッティングごと回収できます。")}</p></li>
    </ol>
  </div>
</section>

<section class="dark" id="controls">
  <div class="wrap">
    <div class="eyebrow">{T("Controls", "操作")}</div>
    <h2 class="title">{T("The keys you need.", "覚えるキーはこれだけ。")}</h2>
    <div class="keys">{keys_html()}</div>
  </div>
</section>

<section id="racing">
  <div class="wrap">
    <div class="split" style="align-items:start">
      <div>
        <div class="eyebrow">{T("Racing", "レース")}</div>
        <h2 class="title">{T("Racing: the basics", "レースの基本")}</h2>
        <p class="lead">{T("Server settings are in config/kurumamod-common.toml (abandoned car cleanup, car-to-car collisions).", "サーバーの設定は config/kurumamod-common.toml にあります（放置車両の削除、車同士の当たり判定）。")}</p>
      </div>
      <dl class="news-list">
        <div><dt>{T("Build a course (OP)", "コースを作る（OP）")}</dt><dd>{T("Mark both ends of each line with <code>/kuruma-admin point</code>. Face the racing direction and run <code>/kuruma-admin course create &lt;name&gt;</code>, then add checkpoints in order with <code>/kuruma-admin course checkpoint &lt;name&gt;</code>.",
                                                                     "各ラインの両端を <code>/kuruma-admin point</code> で指定し、走る向きを向いて <code>/kuruma-admin course create &lt;名前&gt;</code>。続けてチェックポイントを通る順に <code>/kuruma-admin course checkpoint &lt;名前&gt;</code>。")}</dd></div>
        <div><dt>{T("Time attack", "タイムアタック")}</dt><dd>{T("Just drive across a start line.", "スタートラインを通過するだけ。")}</dd></div>
        <div><dt>{T("Race", "レース")}</dt><dd>{T("<code>/kuruma race open &lt;course&gt; [laps]</code>, then others <code>/kuruma race join</code>, line up behind the start line, and <code>/kuruma race start</code>.",
                                                   "<code>/kuruma race open &lt;コース&gt; [周回数]</code> → 参加者は <code>/kuruma race join</code> → ラインの手前に並んで <code>/kuruma race start</code>。")}</dd></div>
        <div><dt>{T("Records", "記録")}</dt><dd><code>/kuruma best</code>, <code>/kuruma best top &lt;course&gt;</code></dd></div>
      </dl>
    </div>
  </div>
</section>

<section class="mist" id="faq">
  <div class="wrap">
    <div class="eyebrow">{T("Videos, modpacks and license", "動画・モッドパック・ライセンス")}</div>
    <h2 class="title">{T("Go ahead and share it.", "どんどん使ってください。")}</h2>
    <dl class="news-list wide">
      <div><dt>{T("Videos and streams", "動画・配信")}</dt><dd>{T("Feel free. Just put a link to the mod page in the description. No need to ask.", "ご自由にどうぞ。概要欄に MOD のページへのリンクを貼ってもらえれば、許可は不要です。")}</dd></div>
      <div><dt>{T("Modpacks", "モッドパック")}</dt><dd>{T("Welcome. Please add it as a CurseForge / Modrinth dependency instead of bundling the jar, so players always get the latest version.", "歓迎です。jar を同梱するより CurseForge／Modrinth の依存として追加してもらえると、いつも最新版が届きます。")}</dd></div>
      <div><dt>{T("License", "ライセンス")}</dt><dd>{T("Free software under the GNU General Public License v3.0 only (GPL-3.0-only).", "GNU General Public License v3.0 only（GPL-3.0-only）のフリーソフトウェアです。")}</dd></div>
      <div><dt>{T("Make your own cars", "自分の車を作る")}</dt><dd>{T(guide_en, guide_ja)}</dd></div>
    </dl>
  </div>
</section>

<section class="dark cta-band">
  <div class="wrap">
    <div>
      <h2 class="title">{T("Questions? Ask on Discord.", "困ったら Discord へ。")}</h2>
      <p class="lead">{T("Bug reports, setups and new car packs all land there first.", "不具合の報告も、セッティングの共有も、新しいカーパックの話題も、まずはここで。")}</p>
    </div>
    <div class="acts"><a class="btn light arrow" href="{DISCORD}">Discord</a><a class="btn outline on-dark" href="{X}">X @kurumamod</a></div>
  </div>
</section>
""")

print("ok")


# ---------- カーパック作成ガイド ----------
# guide-src/PACK_CREATE_GUIDE.html を正として、章をページごとに振り分けてサイトの見た目で書き出す。
# ガイドを直したら、こちらは作り直すだけでよい。
import base64

GUIDE_SRC = Path(sys.argv[2]) if len(sys.argv) > 2 else HERE / "guide-src" / "PACK_CREATE_GUIDE.html"
GPAGES = [
    ("home", "guide.html", ("Home", "入口")),
    ("car", "guide-car.html", ("Car", "車")),
    ("wheel", "guide-wheel.html", ("Wheel", "ホイール")),
    ("parts", "guide-parts.html", ("Parts", "パーツ")),
    ("common", "guide-common.html", ("Common", "共通")),
]
GFILE = {k: f for k, f, _ in GPAGES}
GTITLE = {
    "home": (("Car Pack Guide", "カーパック作成ガイド"), ("From a mesh in Blender to a car you can drive in Minecraft. No Java, and no Blender experience needed.", "Blender で作った車を、Minecraft の中で走らせるまで。Java も Blender の経験も要りません。")),
    "car": (("Make a car", "車を作る"), ("Body model, lights, glass and mirrors, file layout and JSON, sounds, and the spec.", "車体のモデル、灯火・ガラス・ミラー、置き場所と JSON、音、諸元（走り）。")),
    "wheel": (("Make a wheel", "ホイールを作る"), ("The wheel model, and turning wheels and tyres into parts players can pick.", "ホイールのモデルと、換装画面で選べるホイール・タイヤの部品にする方法。")),
    "parts": (("Make parts", "パーツを作る"), ("Turbos and blow-off valves, body parts such as aero and fenders, and exhausts.", "ターボとブローオフバルブ、エアロやフェンダーなどの外装パーツ、マフラー。")),
    "common": (("Common", "共通"), ("Blender basics, packing a jar, checking in game, troubleshooting, checklists and glossary.", "Blender の基本、jar に固めて配る、確かめかた、症状別の直しかた、チェックリスト、用語。")),
}


def build_guide():
    if not GUIDE_SRC.exists():
        print(f"ガイドが見つからないので飛ばします: {GUIDE_SRC}")
        return
    src = GUIDE_SRC.read_text(encoding="utf-8")

    # 埋め込みの画像はファイルへ出す（ページごとに 900KB を抱えないため）
    count = [0]

    def unembed(mo):
        count[0] += 1
        ext = "png" if mo.group(1) == "png" else "jpg"
        name = f"img/guide-{count[0]}.{ext}"
        (OUT / name).write_bytes(base64.b64decode(mo.group(2)))
        return f'src="{name}" loading="lazy"'
    src = re.sub(r'src="data:image/(png|jpeg);base64,([A-Za-z0-9+/=\s]+)"', unembed, src)

    sections = re.findall(r'<section id="([^"]+)" data-guide="([^"]+)">(.*?)</section>', src, re.S)
    sec_pages = {sid: pages.split() for sid, pages, _ in sections}
    # 章の中の見出しの id も、その章のページへ結びつける
    id_pages = dict(sec_pages)
    for sid, pages, body in sections:
        for inner in re.findall(r'\bid="([^"]+)"', body):
            id_pages.setdefault(inner, pages.split())

    toc = []
    for mo in re.finditer(r'<a href="#([^"]+)" data-guide="([^"]+)" class="ja">(.*?)</a><a href="#\1" data-guide="\2" class="en">(.*?)</a>', src):
        toc.append((mo.group(1), mo.group(2).split(), mo.group(3), mo.group(4)))
    foot = re.search(r"<footer>(.*?)</footer>", src, re.S).group(1)

    def relink(html, page):
        def fix(mo):
            target = mo.group(1)
            if target in GFILE:
                return f'href="{GFILE[target]}"'
            pages = id_pages.get(target)
            if not pages or page in pages:
                return mo.group(0)
            return f'href="{GFILE[pages[0]]}#{target}"'
        return re.sub(r'href="#([^"]+)"', fix, html)

    def tabs_for(page):
        cur = ' aria-current="page"'
        return "".join(f'<a href="{f}"{cur if k == page else ""}>{T(*label)}</a>' for k, f, label in GPAGES)

    for key, file, label in GPAGES:
        (title, sub) = GTITLE[key]
        body_secs = "".join(
            f'<section class="gsec" id="{sid}">{relink(body, key)}</section>'
            for sid, pages, body in sections if key in pages.split())
        if key == "home":
            side = ""
            layout_cls = "glayout solo"
        else:
            items = "".join(
                f'<a href="#{sid}">{T(en, ja)}</a>' for sid, pages, ja, en in toc if key in pages)
            side = f'<nav class="gtoc" aria-label="Contents"><div class="tt">{T("Contents", "目次")}</div>{items}</nav>'
            layout_cls = "glayout"
            body_secs += f'<div class="gfoot">{relink(foot, key)}</div>'
        extra = ""
        if key == "home":
            extra = f"""
<section class="mist">
  <div class="wrap">
    <h2 class="title">{T("Start from the example", "見本から始める")}</h2>
    <p class="lead">{T("The Example Car Pack has a sample car and two wheels. Copy it, rename it, and make it yours. No credit required.", "Example Car Pack には、サンプルの車とホイールが 2 つ入っています。コピーして名前を変えれば、あなたのパックの土台になります。クレジット表記は不要です。")}</p>
    <div class="cta-row"><a class="btn arrow" href="{CF_EXAMPLE}">{T("Example Car Pack on CurseForge", "Example Car Pack（CurseForge）")}</a><a class="btn outline" href="{DISCORD}">{T("Ask on Discord", "Discord で質問する")}</a></div>
  </div>
</section>"""
        body = f"""
<section class="ghero">
  <div class="wrap">
    <div class="crumb"><a href="index.html">KurumaMod</a> / {T("Car Pack Guide", "カーパック作成ガイド")}{"" if key == "home" else " / " + T(*label)}</div>
    <h1>{T(*title)}</h1>
    <p>{T(*sub)}</p>
    <nav class="gtabs" aria-label="Guide pages">{tabs_for(key)}</nav>
  </div>
</section>

<div class="wrap {layout_cls}">
  {side}
  <div class="gdoc">{body_secs}</div>
</div>
{extra}
"""
        en_title = ("Car Pack Guide" if key == "home" else f"{title[0]} | Car Pack Guide") + " | KurumaMod"
        ja_title = ("カーパック作成ガイド" if key == "home" else f"{title[1]} | カーパック作成ガイド") + " | KurumaMod"
        page(file, en_title, ja_title, body)
    print(f"guide ok ({len(sections)} sections, {count[0]} images)")


build_guide()
