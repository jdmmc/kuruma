"""KurumaMod の紹介サイトを書き出す。全ページのヘッダー・フッター・言語切り替えを揃えるため。

使い方: python website/gen.py
書き出し先はこのスクリプトのあるフォルダ（website/）。カーパック作成ガイドは
website/guide-src/PACK_CREATE_GUIDE.html を読む（ガイドはサイトにだけ置く。見本パックには入れない）。どちらも引数で差し替えられる（gen.py <出力> <ガイド>）。
英語が既定。日本語は <span data-l="ja"> で並べて持ち、site.js が切り替える。
"""
import html
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE

CF = "https://www.curseforge.com/minecraft/mc-mods/kuruma"
MR = "https://modrinth.com/mod/kuruma"

# ボタンの中のロゴ（Simple Icons）。色は文字色に合わせる
ICON_CHECK = '<svg class="check" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>'
ICON_CF = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18.326 9.2145S23.2261 8.4418 24 6.1882h-7.5066V4.4H0l2.0318 2.3576V9.173s5.1267-.2665 7.1098 1.2372c2.7146 2.516-3.053 5.917-3.053 5.917L5.0995 19.6c1.5465-1.4726 4.494-3.3775 9.8983-3.2857-2.0565.65-4.1245 1.6651-5.7344 3.2857h10.9248l-1.0288-3.2726s-7.918-4.6688-.8336-7.1127z"/></svg>'
ICON_MR = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12.252.004a11.78 11.768 0 0 0-8.92 3.73 11 10.999 0 0 0-2.17 3.11 11.37 11.359 0 0 0-1.16 5.169c0 1.42.17 2.5.6 3.77.24.759.77 1.899 1.17 2.529a12.3 12.298 0 0 0 8.85 5.639c.44.05 2.54.07 2.76.02.2-.04.22.1-.26-1.7l-.36-1.37-1.01-.06a8.5 8.489 0 0 1-5.18-1.8 5.34 5.34 0 0 1-1.3-1.26c0-.05.34-.28.74-.5a37.572 37.545 0 0 1 2.88-1.629c.03 0 .5.45 1.06.98l1 .97 2.07-.43 2.06-.43 1.47-1.47c.8-.8 1.48-1.5 1.48-1.52 0-.09-.42-1.63-.46-1.7-.04-.06-.2-.03-1.02.18-.53.13-1.2.3-1.45.4l-.48.15-.53.53-.53.53-.93.1-.93.07-.52-.5a2.7 2.7 0 0 1-.96-1.7l-.13-.6.43-.57c.68-.9.68-.9 1.46-1.1.4-.1.65-.2.83-.33.13-.099.65-.579 1.14-1.069l.9-.9-.7-.7-.7-.7-1.95.54c-1.07.3-1.96.53-1.97.53-.03 0-2.23 2.48-2.63 2.97l-.29.35.28 1.03c.16.56.3 1.16.31 1.34l.03.3-.34.23c-.37.23-2.22 1.3-2.84 1.63-.36.2-.37.2-.44.1-.08-.1-.23-.6-.32-1.03-.18-.86-.17-2.75.02-3.73a8.84 8.839 0 0 1 7.9-6.93c.43-.03.77-.08.78-.1.06-.17.5-2.999.47-3.039-.01-.02-.1-.02-.2-.03Zm3.68.67c-.2 0-.3.1-.37.38-.06.23-.46 2.42-.46 2.52 0 .04.1.11.22.16a8.51 8.499 0 0 1 2.99 2 8.38 8.379 0 0 1 2.16 3.449 6.9 6.9 0 0 1 .4 2.8c0 1.07 0 1.27-.1 1.73a9.37 9.369 0 0 1-1.76 3.769c-.32.4-.98 1.06-1.37 1.38-.38.32-1.54 1.1-1.7 1.14-.1.03-.1.06-.07.26.03.18.64 2.56.7 2.78l.06.06a12.07 12.058 0 0 0 7.27-9.4c.13-.77.13-2.58 0-3.4a11.96 11.948 0 0 0-5.73-8.578c-.7-.42-2.05-1.06-2.25-1.06Z"/></svg>'
CF_BASIC = "https://www.curseforge.com/minecraft/mc-mods/kurumabasiccarpack"
MR_BASIC = "https://modrinth.com/mod/kurumabasiccarpack"
CF_EXAMPLE = "https://www.curseforge.com/minecraft/mc-mods/kurumaexamplecarpack"
GUIDE = "guide.html"
DISCORD = "https://discord.gg/q3YNqaveRd"
X = "https://x.com/kurumamod"
# 公開先。OGP の画像とページの URL は絶対 URL でないと X や Discord が読めない
SITE = "https://kuruma.jdm-mc.com/"
DEFAULT_DESC = "A driving mod for Minecraft with real car physics: suspension, weight transfer, tyre grip, engine and gearbox. Minecraft 1.20.1, 1.21.1 and 26.3 on Forge, NeoForge and Fabric."


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
{meta}
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
      <button class="menu-btn" type="button" aria-expanded="false" aria-controls="menu-links" aria-label="Menu / メニュー"><span></span></button>
      <div class="menu-links" id="menu-links">
        <a href="index.html">{T("Home", "トップ")}</a>
        <a href="start.html">{T("Get started", "はじめかた")}</a>
        <a href="features.html">{T("Features", "特徴")}</a>
        <a href="race.html">{T("Racing", "レース")}</a>
        <a href="compare.html">{T("Compare", "比較")}</a>
        <a href="shaders.html">{T("Shaders", "シェーダー")}</a>
        <a href="guide.html">{T("Car Pack Guide", "作成ガイド")}</a>
      </div>
    </nav>
    <a class="wordmark" href="index.html" aria-label="KurumaMod">KURUMA</a>
    <div class="tools">
      <button class="lang" type="button" id="lang-toggle" aria-label="Switch language / 言語を切り替え">
        <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.6 2.6 3.9 5.6 3.9 9s-1.3 6.4-3.9 9c-2.6-2.6-3.9-5.6-3.9-9s1.3-6.4 3.9-9z"/></svg>
        <span class="lang-t" data-l="en">日本語</span><span class="lang-t" data-l="ja">English</span>
      </button>
      <a class="dl" href="start.html#download" aria-label="Download / ダウンロード"><svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg><span class="dl-t">{T("Download", "ダウンロード")}</span></a>
    </div>
  </div>
</header>
"""

FOOTER = f"""<footer>
  <div class="wrap">
    <a class="wordmark" href="index.html">KURUMA</a>
    <div class="cols">
      <div><h4>{T("Cars", "車種")}</h4><ul><li><a href="s15.html">Silvia S15</a></li><li><a href="911.html">911</a></li><li><a href="prado.html">Land Cruiser Prado</a></li><li><a href="compare.html">{T("Compare cars", "車種を比較")}</a></li></ul></div>
      <div><h4>{T("Features", "特徴")}</h4><ul><li><a href="features.html#physics">{T("Physics", "物理演算")}</a></li><li><a href="features.html#setup">{T("Setup", "セッティング")}</a></li><li><a href="features.html#lighting">{T("Headlights", "ヘッドライト")}</a></li><li><a href="race.html">{T("Racing", "レース")}</a></li></ul></div>
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


# Google アナリティクス（GA4）。本番のホストで開いたときだけ読み込む。
# 手元でファイルを開いたときや localhost で確認しているときに計測が混ざらないよう、
# gtag.js そのものをホスト名を見てから差し込む（タグを書かないビルドを分けると、上げ忘れが起きる）
GA_ID = "G-4NCQ47HCMC"
GA_HOST = "kuruma.jdm-mc.com"
GA_TAG = f"""<!-- Google tag (gtag.js)。本番（{GA_HOST}）でだけ読み込む -->
<script>
if (location.hostname === '{GA_HOST}') {{
  var ga = document.createElement('script');
  ga.async = true;
  ga.src = 'https://www.googletagmanager.com/gtag/js?id={GA_ID}';
  document.head.appendChild(ga);
  window.dataLayer = window.dataLayer || [];
  function gtag(){{dataLayer.push(arguments);}}
  gtag('js', new Date());
  gtag('config', '{GA_ID}');
}}
</script>"""

# トップページの紹介動画
YOUTUBE_ID = "3Hfrvz-qgxc"


def youtube(video_id):
    # youtube-nocookie.com は再生するまで Cookie を置かない。loading="lazy" で、
    # スクロールして近づくまでプレイヤーを読み込まない（ページの表示を遅くしない）
    return (f'<iframe src="https://www.youtube-nocookie.com/embed/{video_id}" title="KurumaMod" loading="lazy" '
            'allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" '
            'referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>')


# 書き出したページの URL。最後に sitemap.xml へ並べる
PAGES = []


def external_links(html_text):
    """外部サイトへのリンクを新しいタブで開く（サイトの中のリンクはそのまま）。"""
    def link(m):
        tag = m.group(0)
        if 'target=' in tag or m.group(1).startswith(SITE):
            return tag
        return tag[:-1] + ' target="_blank" rel="noopener">'
    return re.sub(r'<a\s[^>]*href="(https?://[^"]*)"[^>]*>', link, html_text)


def fit_labels(html):
    """スマホで縦に積む表（table.compare.fit）の各セルへ、列の見出しを data-en / data-ja で持たせる。"""
    def table(m):
        body = m.group(0)
        heads = []
        for th in re.findall(r'<th scope="col">(.*?)</th>', body):
            pair = re.findall(r'<span data-l="(?:en|ja)">(.*?)</span>', th)
            heads.append((pair[0], pair[1]) if len(pair) == 2 else (th, th))

        def row(r):
            index = 0

            def cell(c):
                nonlocal index
                index += 1
                if 'colspan' in c.group(1) or index >= len(heads):
                    return c.group(0)
                en, ja = heads[index]
                return f'<td data-en="{en}" data-ja="{ja}"{c.group(1)}>'
            return re.sub(r'<td([^>]*)>', cell, r.group(0))
        return re.sub(r'<tr>.*?</tr>', row, body, flags=re.S)
    return re.sub(r'<table class="compare fit">.*?</table>', table, html, flags=re.S)


def page(name, title, title_ja, body, desc=DEFAULT_DESC, image="img/ogp.jpg"):
    body = fit_labels(body)
    url = SITE + ("" if name == "index.html" else name)
    PAGES.append(url)
    meta = "\n".join([
        GA_TAG,
        f'<meta name="description" content="{html.escape(desc)}">',
        '<link rel="icon" href="favicon.ico" sizes="any">',
        '<link rel="icon" href="img/favicon.png" type="image/png">',
        '<link rel="apple-touch-icon" href="img/apple-touch-icon.png">',
        f'<link rel="canonical" href="{url}">',
        '<meta property="og:type" content="website">',
        '<meta property="og:site_name" content="KurumaMod">',
        f'<meta property="og:title" content="{html.escape(title)}">',
        f'<meta property="og:description" content="{html.escape(desc)}">',
        f'<meta property="og:url" content="{url}">',
        f'<meta property="og:image" content="{SITE + image}">',
        '<meta name="twitter:card" content="summary_large_image">',
        '<meta name="twitter:site" content="@kurumamod">',
    ])
    (OUT / name).write_text(HEAD.format(title=title, title_ja=title_ja, meta=meta) + external_links(HEADER + body + FOOTER), encoding="utf-8")


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
        return (f'<a class="{first}" href="{CF}">{ICON_CF}{T("Download on CurseForge", "CurseForge でダウンロード")}</a>'
                f'<a class="{second}" href="{MR}">{ICON_MR}Modrinth</a>')
    return (f'<a class="{first}" href="{CF_BASIC}">{ICON_CF}{T("Get the Basic Car Pack", "Basic Car Pack を入手")}</a>'
            f'<a class="{second}" href="{MR_BASIC}">{ICON_MR}Modrinth</a>')


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
        figs=[("281", "PS", ("Max power", "最高出力")), ("5.0", "s", ("0-100 km/h", "0-100 km/h")), ("255", "km/h", ("Top speed", "最高速度")), ("1,200", "kg", ("Weight", "車両重量"))],
        perf=[("5.01", "s", ("0-100 km/h", "0-100 km/h")), ("13.15", "s", ("0-400 m", "0-400 m")), ("255", "km/h", ("Top speed", "最高速度")), ("4.27", "kg/PS", ("Power-to-weight", "パワーウエイトレシオ"))],
        tq=350, rt=4200, rp=6676, red=7000,
        engine=("Naturally aspirated. Peak torque at 4,200 rpm, power building to 6,680 rpm, and a limiter at 7,000. The automatic shifts where the next gear starts pulling harder, so at full throttle it holds each gear almost to the redline.",
                "自然吸気。最大トルクは 4,200rpm、出力は 6,680rpm まで伸び、7,000rpm で吹け止まります。AT は次の段のほうが強く押せる回転数で変速するので、全開ならレブの手前まできれいに引っ張ります。"),
        front=56, layout=("AWD (50 : 50)", "4WD（前後 50 : 50）"),
        chassis=("The weight sits slightly forward, so setup changes do what you expect: stiffen the front anti-roll bar for understeer, the rear for oversteer.",
                 "重心は少し前寄り。だからセッティングが素直に効きます。前のスタビを固めればアンダー、後ろを固めればオーバーへ。"),
        facts=[(("Wheelbase", "ホイールベース"), "3.15 m"), (("Track", "トレッド"), "1.90 m"), (("Suspension travel", "サスペンションストローク"), "30 cm"),
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
            (("Dimensions and weight", "寸法・重量"), [(("Wheelbase", "ホイールベース"), "3.15 m"), (("Track", "トレッド"), "1.90 m"), (("Weight", "車両重量"), "1,200 kg"), (("Weight distribution F : R", "前後重量配分"), "56 : 44"), (("Drag area (CdA)", "空気抵抗（CdA）"), "0.78 m²")]),
            (("Engine", "エンジン"), [(("Max power", "最高出力"), "281 PS / 6,680 rpm"), (("Max torque", "最大トルク"), "350 N·m / 4,200 rpm"), (("Idle", "アイドル回転数"), "800 rpm"), (("Rev limit", "レブリミット"), "7,000 rpm"), (("Forced induction", "過給"), T("None (turbo available)", "なし（タービン装着可）"))]),
            (("Drivetrain", "駆動系"), [(("Layout", "駆動方式"), T("AWD (50 : 50)", "4WD（前後 50 : 50）")), (("Gearbox", "変速機"), T("5-speed, auto or manual", "5 速 AT／MT 切替")), (("1st / 5th", "1 速／5 速"), "4.20 / 0.85"), (("Final drive", "最終減速比"), "4.37"), (("Differential", "デフ"), T("Open", "オープン"))]),
            (("Chassis", "足まわり"), [(("Suspension travel", "サスペンションストローク"), "30 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "2,500 / 1,500 N/m"), (("Tyre diameter", "タイヤ外径"), "80 cm"), (("Max steering angle", "最大切れ角"), "35°"), (("ABS / TCS", "ABS／TCS"), T("Standard", "標準装備"))]),
            (("Performance", "性能"), [(("0-100 km/h", "0-100 km/h"), "5.01 s"), (("0-400 m", "0-400 m"), "13.15 s"), (("Top speed", "最高速度"), "255 km/h"), (("Power-to-weight", "パワーウエイトレシオ"), "4.27 kg/PS")]),
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
        figs=[("335", "PS", ("Max power", "最高出力")), ("5.3", "s", ("0-100 km/h", "0-100 km/h")), ("276", "km/h", ("Top speed", "最高速度")), ("40:60", "", ("Weight distribution", "前後重量配分"))],
        perf=[("5.25", "s", ("0-100 km/h", "0-100 km/h")), ("13.27", "s", ("0-400 m", "0-400 m")), ("276", "km/h", ("Top speed", "最高速度")), ("4.33", "kg/PS", ("Power-to-weight", "パワーウエイトレシオ"))],
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
            (("Performance", "性能"), [(("0-100 km/h", "0-100 km/h"), "5.25 s"), (("0-400 m", "0-400 m"), "13.27 s"), (("Top speed", "最高速度"), "276 km/h"), (("Power-to-weight", "パワーウエイトレシオ"), "4.33 kg/PS")]),
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
        figs=[("453", "N·m", ("Max torque", "最大トルク")), ("9.9", "s", ("0-100 km/h", "0-100 km/h")), ("2,100", "kg", ("Weight", "車両重量")), ("64", "cm", ("Ride height", "車高"))],
        perf=[("9.89", "s", ("0-100 km/h", "0-100 km/h")), ("17.07", "s", ("0-400 m", "0-400 m")), ("147", "km/h", ("Top speed", "最高速度")), ("10.5", "kg/PS", ("Power-to-weight", "パワーウエイトレシオ"))],
        tq=452.58, rt=2400, rp=3632.5, red=4600,
        engine=("Like a diesel: thick torque low down, and done by 4,600 rpm. Its 453 N·m at 2,400 rpm beats the S15. The boxy body has 3.5 times the drag, so top speed settles at 147 km/h.",
                "ディーゼルのように低回転で太く、4,600rpm で頭打ちになるエンジン。最大トルクは 2,400rpm で S15 を上回る 453N·m。箱形の車体は空気抵抗が S15 の 3.5 倍あり、最高速は 147km/h で釣り合います。"),
        front=54, layout=("AWD (50 : 50)", "4WD（前後 50 : 50）"),
        chassis=("60 cm of soft suspension travel and a solid rear axle. When one wheel climbs a ledge the whole rear axle tilts, so the diagonal wheel stays on the ground. The tall body rolls and shifts its weight more, so the front anti-roll bar is stiffer and it trades eagerness for stability. Steering lock is 32°, less than a road car.",
                 "60cm のストロークを持つ柔らかい足と、後ろのリジッドアクスル。片輪が段に乗ると後ろの車軸ごと傾くので、対角の輪が地面から離れにくい。背が高いぶんロールも荷重移動も大きく出るので、前のスタビを厚くして、曲がりやすさより安定を取っています。切れ角は乗用車より浅い 32°。"),
        facts=[(("Wheelbase", "ホイールベース"), "3.00 m"), (("Track", "トレッド"), "1.90 m"), (("Suspension travel", "サスペンションストローク"), "60 cm"),
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
            (("Engine", "エンジン"), [(("Max power", "最高出力"), "200 PS / 3,630 rpm"), (("Max torque", "最大トルク"), "453 N·m / 2,400 rpm"), (("Idle", "アイドル回転数"), "800 rpm"), (("Rev limit", "レブリミット"), "4,600 rpm"), (("Forced induction", "過給"), T("None (turbo available)", "なし（タービン装着可）"))]),
            (("Drivetrain", "駆動系"), [(("Layout", "駆動方式"), T("AWD (50 : 50)", "4WD（前後 50 : 50）")), (("Gearbox", "変速機"), T("5-speed, auto or manual", "5 速 AT／MT 切替")), (("1st / 5th", "1 速／5 速"), "4.20 / 0.85"), (("Final drive", "最終減速比"), "5.03"), (("Differential", "デフ"), T("Open", "オープン"))]),
            (("Chassis", "足まわり"), [(("Suspension F / R", "サス形式 前／後"), T("Independent / solid axle", "独立／リジッドアクスル")), (("Suspension travel", "サスペンションストローク"), "60 cm"), (("Anti-roll bars F / R", "スタビライザー 前／後"), "5,500 / 2,600 N/m"), (("Tyre diameter", "タイヤ外径"), "92 cm"), (("Max steering angle", "最大切れ角"), "32°"), (("Rolling resistance", "転がり抵抗"), T("1.3× the S15", "S15 の 1.3 倍"))]),
            (("Performance", "性能"), [(("0-100 km/h", "0-100 km/h"), "9.89 s"), (("0-400 m", "0-400 m"), "17.07 s"), (("Top speed", "最高速度"), "147 km/h"), (("Power-to-weight", "パワーウエイトレシオ"), "10.5 kg/PS")]),
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
    page(m["file"], f"{name} | KurumaMod", f"{name} | KurumaMod", body, desc=m["catch"][0], image="img/" + m["img"])


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


def tile(cls, anchor, img, alt, small, h, p):
    return f"""<a class="tile {cls}" href="features.html#{anchor}">
        <img src="img/{img}" {ALT(*alt)}>
        <div class="in"><small>{T(*small)}</small><h3>{h}</h3><p>{T(*p)}</p></div>
      </a>"""


NEWS = [
    (("Fabric and Minecraft 26.3", "Fabric と Minecraft 26.3"), ("Fabric joins on 1.21.1, and 26.3 runs on Forge, NeoForge and Fabric with OpenGL or Vulkan.", "1.21.1 に Fabric 版が加わり、26.3 は Forge・NeoForge・Fabric で動きます。OpenGL でも Vulkan でも。")),
    (("Volumetric tyre smoke", "体積で描くタイヤの煙"), ("Smoke and dust are drawn as volumes, shaded by the sun and lit by your headlights.", "煙と土煙を体積として描きます。太陽で陰影が付き、ヘッドライトに照らされます。")),
    (("Licence plates", "ナンバープレート"), ("Japanese, EU, UK and US plates with your own text. Choose where they mount.", "日本・EU・UK・US のプレートに好きな文字を。付ける位置も選べます。")),
    (("Much lighter with shader packs", "シェーダーパック使用中が大幅に軽く"), ("Cars stay on the GPU. 30 cars with Complementary Unbound: about 60 ms per frame down to 10-17 ms.", "車を GPU に置いたまま描きます。Complementary Unbound、30 台の実測で 1 フレーム約 60ms が 10〜17ms に。")),
    (("Front and rear track width", "トレッドを前後別々に"), ("Set each end on its own in the tuning screen, or lock them to move together.", "調整画面で前後を別々に。鍵を閉じれば一緒に動きます。")),
    (("One jar for every loader", "カーパックは 1 つの jar ですべてのローダーへ"), ("A car pack can load on Forge, NeoForge and Fabric, including 26.3.", "カーパックは Forge・NeoForge・Fabric、26.3 にも 1 つの jar で対応できます。")),
]
news = "".join(f"<div><dt>{T(*h)}</dt><dd>{T(*d)}</dd></div>" for h, d in NEWS)
cards = "".join(model_card(m) for m in MODELS.values())
key_l = '<span class="key">L</span>'

page("index.html", "KurumaMod", "KurumaMod", f"""
<section class="hero" style="padding:0">
  <img src="img/eyecatch.jpg" {ALT("Silvia S15 on track by day and night", "昼と夜のサーキットを走るシルビア S15")}>
  <div class="copy">
    <div class="wrap">
      <div class="kicker">{T("A driving mod for Minecraft · 1.20.1 / 1.21.1 / 26.3", "Minecraft のドライブ MOD · 1.20.1 / 1.21.1 / 26.3")}</div>
      <h1 class="brand" aria-label="KurumaMod">KURUMA</h1>
      <p>{T("Real car physics, in Minecraft.", "Minecraft で、本格物理のドライブを。")}</p>
      <div class="cta">
        <a class="btn light arrow" href="{CF}" target="_blank" rel="noopener">{ICON_CF}{T("Download on CurseForge", "CurseForge でダウンロード")}</a>
        <a class="btn light arrow" href="{MR}" target="_blank" rel="noopener">{ICON_MR}{T("Download on Modrinth", "Modrinth でダウンロード")}</a>
      </div>
    </div>
  </div>
</section>

<div class="badges">
  <div class="wrap">
    <div><b>{T("Shader ready", "シェーダー対応")}</b><span>{T("Works with Iris and Oculus", "Iris・Oculus で走れる")}</span></div>
    <div><b>{T("Multiplayer", "マルチプレイ")}</b><span>{T("Race your friends", "友だちとレース")}</span></div>
    <div><b>{T("Wheel and gamepad", "ハンコン・パッド")}</b><span>{T("Fully analog input", "フルアナログ入力")}</span></div>
    <div><b>{T("Free and open source", "無料・オープンソース")}</b><span>GPL-3.0</span></div>
  </div>
</div>

<section id="video">
  <div class="wrap">
    <div class="eyebrow">{T("Video", "動画")}</div>
    <h2 class="title">{T("See it drive.", "走りを見る。")}</h2>
    <div class="video">{youtube(YOUTUBE_ID)}</div>
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
    {figs([("80", "Hz", ("Physics updates per second", "物理の計算回数（毎秒）")), ("40+", "", ("Setup options", "セッティング項目")), ("6", "", ("Road surfaces", "路面の種類")), ("3", "", ("Minecraft versions", "対応する Minecraft の版"))])}
  </div>
</section>

<section class="mist" id="features">
  <div class="wrap">
    <h2 class="title">{T("More than a car-shaped block", "車の形をしたブロックではありません")}</h2>
    <div class="tiles">
      {tile("wide", "physics", "hud.jpg", ("Silvia S15 with gauges on screen", "回転計と速度計を表示して走る S15"), ("Physics", "物理演算"), T("You feel the limit before you reach it.", "限界の手前が、手に伝わる。"), ("Steering follows the slide, so there's always enough opposite lock to catch it.", "ハンドルは滑りに合わせて切れるので、流れた分だけカウンターを当てられます。"))}
      {tile("two", "lighting", "eyecatch.jpg", ("Headlights on a night track", "夜のサーキットをヘッドライトで照らして走る"), ("Lighting", "ライティング"), key_l + T("Headlights that really light the road", "路面を本当に照らすヘッドライト"), ("A proper low beam, shadows from trees and walls, beams in fog and rain. Works with shader packs.", "カットオフの付いたロービーム、木や壁の影、霧や雨の中の光の筋。シェーダーパックにも対応。"))}
      {tile("third", "surface", "gravel.jpg", ("A car leaving a smoke trail on loose ground", "未舗装路に煙の跡を残して走る車"), ("Surfaces", "路面"), T("Six surfaces and rain", "6 種類の路面と雨"), ("Tarmac, dirt, gravel, sand, snow and ice each drive differently.", "舗装、土、砂利、砂、雪、氷で、走りがまったく変わります。"))}
      {tile("third", "custom", "camber.jpg", ("Wheels with negative camber", "キャンバーを付けたホイール"), ("Customise", "カスタマイズ"), T("Aero, mufflers, wheels", "エアロ、マフラー、ホイール"), ("Camber, offset and width, plus wings that make real downforce.", "キャンバー、オフセット、幅。ウイングは本当にダウンフォースを生みます。"))}
      {tile("two", "racing", "race.jpg", ("Start lights on a race track", "スタートシグナルの点いたコース"), ("Racing", "レース"), T("Race your friends", "友だちとレースを"), ("Start lights, live standings and split times, in multiplayer.", "スタートシグナル、リアルタイムの順位、区間タイム。マルチプレイで。"))}
    </div>
  </div>
</section>

<section id="cars">
  <div class="wrap">
    <h2 class="title">{T("Cars included", "収録車種")}</h2>
    <p class="lead">{T("The S15 comes with KurumaMod; the 911 and the Prado come with the free Basic Car Pack. All three run on the same physics, so weight, balance and drivetrain are what make them drive differently.", "S15 は KurumaMod に、911 とプラドは無料の Basic Car Pack に入っています。3 台とも同じ物理で走るので、重さ、重心の位置、駆動方式の違いが、そのまま走りの違いになります。")}</p>
    <div class="models">{cards}</div>
  </div>
</section>

<section class="mist" id="start">
  <div class="wrap">
    <div class="eyebrow">{T("Get started", "はじめかた")}</div>
    <h2 class="title">{T("Three steps to the driver's seat.", "3 ステップで、運転席へ。")}</h2>
    <ol class="steps three">
      <li><h3>{T("Download", "ダウンロードする")}</h3><p>{T("From CurseForge or Modrinth. Minecraft 1.20.1, 1.21.1 and 26.3. Fabric needs Fabric API.", "CurseForge か Modrinth から。Minecraft 1.20.1・1.21.1・26.3 に対応。Fabric は Fabric API が必要です。")}</p></li>
      <li><h3>{T("Put it in mods/", "mods に入れる")}</h3><p>{T("Add the Basic Car Pack too if you want the 911 and the Prado.", "911 とプラドに乗るなら、Basic Car Pack も一緒に。")}</p></li>
      <li><h3>{T("Place a car and get in", "車を置いて、乗る")}</h3><p>{T("Take a car from the KurumaMod creative tab, right-click the ground to place it, then right-click the car.", "クリエイティブの KurumaMod タブから車を取り、地面を右クリックで置いて、車を右クリックで乗ります。")}</p></li>
    </ol>
    <div class="cta-row"><a class="btn arrow" href="start.html">{T("Controls and FAQ", "操作とよくある質問")}</a></div>
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

<section class="mist" id="news">
  <div class="wrap">
    <div class="split" style="align-items:start">
      <div>
        <div class="eyebrow">{T("What's new", "新着")}</div>
        <h2 class="title">KurumaMod 2.0.0</h2>
        <p>{T("Now on Fabric and Minecraft 26.3, with volumetric tyre smoke, licence plates and far better performance with shader packs.", "Fabric と Minecraft 26.3 に対応。体積で描くタイヤの煙、ナンバープレート、シェーダーパック使用中の大幅な軽量化も。")}</p>
        <div class="cta-row"><a class="btn arrow" href="{CF}" target="_blank" rel="noopener">{ICON_CF}{T("Download on CurseForge", "CurseForge でダウンロード")}</a><a class="btn arrow" href="{MR}" target="_blank" rel="noopener">{ICON_MR}{T("Download on Modrinth", "Modrinth でダウンロード")}</a></div>
      </div>
      <dl class="news-list">{news}</dl>
    </div>
  </div>
</section>

<section class="dark cta-band">
  <div class="wrap">
    <div>
      <h2 class="title">{T("Join the community", "コミュニティに参加")}</h2>
      <p class="lead">{T("Share setups, show off your builds, and hear about new cars first.", "セッティングを共有したり、作った車を見せ合ったり。新しい車の情報もいち早く。")}</p>
    </div>
    <a href="{DISCORD}" target="_blank" rel="noopener" style="display:block;flex:0 1 560px">{T('<img src="img/cta-discord-en.png" alt="Join the KurumaMod Discord" style="display:block;width:100%;height:auto;border-radius:12px">', '<img src="img/cta-discord-ja.png" alt="KurumaMod の Discord に参加" style="display:block;width:100%;height:auto;border-radius:12px">')}</a>
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
    ("K", ("Race: join, host, race info", "レース（参加・主催・レースの画面）")),
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
         ("Place a start line and checkpoints anywhere in your world and hold a proper race. Host and join with one key, set a regulation for power, weight and grip, and race with start lights, a live tower and your gap to your best lap at every checkpoint. Multiplayer, timed to the millisecond. <a href='race.html'>More about racing</a>",
          "ワールドのどこにでもスタートラインとチェックポイントを置いて、本格的なレースを開けます。主催も参加もキー 1 つ。出力・車重・グリップなどのレギュレーションを決めて、スタートシグナル、リアルタイムの順位、チェックポイントごとのベストラップとの差。マルチプレイ対応、計測はミリ秒単位。<a href='race.html'>レースについて詳しく</a>"))}
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
    (("0-100 km/h", "0-100 km/h"), ("5.01 s", "5.25 s", "9.89 s"), "min"),
    (("0-400 m", "0-400 m"), ("13.15 s", "13.27 s", "17.07 s"), "min"),
    (("Top speed", "最高速度"), ("255 km/h", "276 km/h", "147 km/h"), "max"),
    (("Power-to-weight", "パワーウエイトレシオ"), ("4.27 kg/PS", "4.33 kg/PS", "10.5 kg/PS"), "min"),
    (("Engine", "エンジン"), None),
    (("Max power", "最高出力"), ("281 PS / 6,680 rpm", "335 PS / 6,500 rpm", "200 PS / 3,630 rpm"), "max"),
    (("Max torque", "最大トルク"), ("350 N·m / 4,200 rpm", "420 N·m / 4,400 rpm", "453 N·m / 2,400 rpm"), None),
    (("Rev limit", "レブリミット"), ("7,000 rpm", "7,400 rpm", "4,600 rpm"), None),
    (("Idle", "アイドル回転数"), ("800 rpm", "800 rpm", "800 rpm"), None),
    (("Drivetrain", "駆動系"), None),
    (("Layout", "駆動方式"), (T("AWD", "4WD"), T("RWD, rear engine", "RR"), T("AWD", "4WD")), None),
    (("Gearbox", "変速機"), (T("5-speed AT / MT", "5 速 AT／MT"),) * 3, None),
    (("Final drive", "最終減速比"), ("4.37", "4.92", "5.03"), None),
    (("Body and chassis", "ボディ・足まわり"), None),
    (("Weight", "車両重量"), ("1,200 kg", "1,450 kg", "2,100 kg"), None),
    (("Weight distribution F : R", "前後重量配分"), ("56 : 44", "40 : 60", "54 : 46"), None),
    (("Wheelbase", "ホイールベース"), ("3.15 m", "3.13 m", "3.00 m"), None),
    (("Track", "トレッド"), ("1.90 m", "2.00 m", "1.90 m"), None),
    (("Suspension F / R", "サス形式 前／後"), (T("Independent / independent", "独立／独立"), T("Independent / independent", "独立／独立"), T("Independent / solid axle", "独立／リジッド")), None),
    (("Suspension travel", "サスペンションストローク"), ("30 cm", "30 cm", "60 cm"), None),
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
      <table class="compare cars">
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

page("shaders.html", "Shader packs | KurumaMod", "シェーダーパック | KurumaMod", f"""
<section class="hero page shade" style="padding:0">
  <img src="img/eyecatch.jpg" {ALT("A car on a night track with its headlights on", "夜のコースをヘッドライトで照らす車")}>
  <div class="copy">
    <div class="wrap">
      <div class="crumb"><a href="index.html">KurumaMod</a> / {T("Shaders", "シェーダー")}</div>
      <h1>Shaders</h1>
      <p>{T("Built to look great and stay smooth with shader packs.", "シェーダーパックで走るために作りました。")}</p>
    </div>
  </div>
</section>

<section>
  <div class="wrap">
    <div class="eyebrow">{T("Made for shader packs", "シェーダーのために")}</div>
    <h2 class="title">{T("Beautiful, and still smooth.", "きれいなまま、軽く走れる。")}</h2>
    <p class="lead">{T("KurumaMod is built so you can keep your shader pack on, even on a full grid.", "KurumaMod は、車がたくさん並ぶレースでもシェーダーを切らずに走れるように作っています。")}</p>
    <div class="packs">
      <div class="pack static"><small>{T("Performance", "軽さ")}</small><h3>{T("Up to 7x lighter", "最大 約 7 倍軽く")}</h3><p>{T("Optimised rendering keeps things comfortable even with a lot of cars on screen. Go racing with friends and keep your pack on.", "最適化処理により、車がたくさん並んでも快適に走れるようになりました。友達とのレースでも、シェーダーを入れたまま楽しめます。")}</p></div>
      <div class="pack static"><small>{T("Shadows", "影")}</small><h3>{T("Shadows that look right", "自然な影")}</h3><p>{T("The body and tyres cast clean shadows on the road, following the sun and moon.", "車体とタイヤの影が、太陽や月の向きに合わせて路面にきれいに落ちます。")}</p></div>
      <div class="pack static"><small>{T("Light and smoke", "光と煙")}</small><h3>{T("Part of the scene", "景色に溶け込む")}</h3><p>{T("Headlights, tyre smoke and skid marks sit naturally in the pack's light and colour.", "ヘッドライト、タイヤの煙、タイヤ痕が、パックの光や色合いに自然に馴染みます。")}</p></div>
    </div>
  </div>
</section>

<section class="mist">
  <div class="wrap">
    <div class="eyebrow">{T("Tested combinations", "確かめた組み合わせ")}</div>
    <h2 class="title">{T("What we have run.", "実際に動かした組み合わせ。")}</h2>
    <p class="lead">{T("Only what we ran ourselves is listed. Other versions and packs may work, but we have not checked them.", "手元で動かしたものだけを載せています。ほかの版やパックでも動くかもしれませんが、確かめていません。")}</p>
    <div class="compare-wrap">
      <table class="compare fit">
        <thead><tr><th scope="col">{T("Minecraft / loader", "Minecraft / ローダー")}</th><th scope="col">{T("Shader mod", "シェーダー MOD")}</th><th scope="col">{T("With", "一緒に入れた MOD")}</th><th scope="col">{T("Packs tried", "試したパック")}</th></tr></thead>
        <tbody>
          <tr><th scope="row">1.20.1 Forge</th><td>Oculus 1.8.0</td><td>Embeddium 0.3.31</td><td>Complementary Unbound r5.9.2</td></tr>
          <tr><th scope="row">1.21.1 NeoForge</th><td>Iris 1.8.12</td><td>Sodium 0.6.13</td><td>Complementary Unbound r5.9.3</td></tr>
          <tr><th scope="row">1.21.1 Fabric</th><td>Iris 1.8.8</td><td>Sodium 0.6.13</td><td>Complementary Unbound r5.9.3</td></tr>
          <tr><th scope="row">26.3 Fabric</th><td>Iris 1.11.7</td><td>Sodium 0.9.2</td><td>Complementary Unbound r5.9.3</td></tr>
          <tr><th scope="row">26.3 NeoForge</th><td colspan="3">{T("Shaders cannot be used yet because Iris and Sodium do not support this version.", "今は Iris / Sodium が対応していないため、シェーダーを使用できません。")}</td></tr>
          <tr><th scope="row">26.3 Forge</th><td colspan="3">{T("Shaders cannot be used yet because Iris and Sodium do not support this version.", "今は Iris / Sodium が対応していないため、シェーダーを使用できません。")}</td></tr>
        </tbody>
      </table>
    </div>
    <p class="lead">{T("OptiFine has not been tested.", "OptiFine は確かめていません。")}</p>
  </div>
</section>

<section>
  <div class="wrap">
    <div class="eyebrow">{T("Feature by feature", "機能ごとの違い")}</div>
    <h2 class="title">{T("What changes with a pack.", "シェーダーで変わるところ。")}</h2>
    <div class="compare-wrap">
      <table class="compare fit">
        <thead><tr><th scope="col">{T("Feature", "機能")}</th><th scope="col">{T("Without a pack", "シェーダーなし")}</th><th scope="col">{T("With a pack", "シェーダーあり")}</th></tr></thead>
        <tbody>
          <tr><th scope="row">{T("Car shadow", "車の影")}</th><td>{T("Soft shadow under the car", "車の下の丸い影")}</td><td>{T("Real shadows from the sun and moon", "太陽と月の光で落ちる影")}</td></tr>
          <tr><th scope="row">{T("Headlights", "ヘッドライト")}</th><td>{T("Yes", "対応")}</td><td>{T("Yes", "対応")}</td></tr>
          <tr><th scope="row">{T("Tyre smoke", "タイヤの煙")}</th><td>{T("Yes", "対応")}</td><td>{T("Yes", "対応")}</td></tr>
          <tr><th scope="row">{T("Skid marks", "タイヤ痕")}</th><td>{T("Yes", "対応")}</td><td>{T("Yes", "対応")}</td></tr>
          <tr><th scope="row">{T("Mirrors", "ミラー")}</th><td>{T("Live reflection", "景色が映る")}</td><td>{T("Not supported", "非対応")}</td></tr>
          <tr><th scope="row">{T("Lighter car rendering", "車の軽量描画")}</th><td>{T("Yes", "対応")}</td><td>{T("Yes (Oculus and Iris compatible)", "対応（Oculus互換・Iris互換）")}</td></tr>
        </tbody>
      </table>
    </div>
    <p class="lead">{T("If a pack blends the headlights badly, adjust them with Ctrl+L. On Minecraft 26.3, <strong>use the OpenGL graphics backend</strong> with shader packs.", "パックによってヘッドライトの合成が崩れるときは、Ctrl＋L で調整してください。Minecraft 26.3 でシェーダーパックを使うときは、<strong>描画を OpenGL にしてください。</strong>")}</p>
  </div>
</section>
""", desc="KurumaMod is built for shader packs: cars stay smooth, and light, shadows and smoke blend into the pack.")

RACE_RULES = [
    (("Base traits", "車の素性"),
     ("Locks size (wheelbase, track, tyre radius), drivetrain efficiency, rolling resistance, drag, shift time, engine inertia and tyre traits other than μ to the car's own values. Changing any of these only ever makes a car faster, so the host can fix them all at once or one by one.",
      "車の寸法（ホイールベース・トレッド・タイヤ半径）、駆動の効率、転がり抵抗、空気抵抗、変速の時間、エンジンの慣性、タイヤの性質（μ 以外）を、車種の値に固定します。どれも変えるほど速くなるだけの値なので、まとめて、あるいは 1 つずつ固定できます。")),
    (("Max power", "最高出力の上限"),
     ("Peak power in PS, measured with the turbo at full boost and parts included.",
      "最高出力（PS）。ターボはブーストが乗りきった値で、パーツ込みで測ります。")),
    (("Min weight", "車重の下限"),
     ("Car weight in kg, without the driver.", "車重（kg）。乗員は含みません。")),
    (("Min power-to-weight", "パワーウエイトレシオの下限"),
     ("Weight ÷ max power (kg/PS): how many kg each PS has to move. Lower is quicker. Use it to even cars out by the ratio alone. 1,200 kg with 240 PS is 5.0.",
      "車重 ÷ 最高出力（kg/PS）。1 PS あたり何 kg を運ぶかで、小さいほど加速が鋭くなります。出力と車重を別々に縛らず、比だけで揃えたいときに。1,200 kg・240 PS なら 5.0 です。")),
    (("Max grip", "グリップの上限"),
     ("Tyre grip (μ) on tarmac, tyre parts included.", "舗装路でのタイヤのグリップ（μ）。タイヤのパーツ込み。")),
    (("Max downforce", "ダウンフォースの上限"),
     ("Downforce at 200 km/h, front and rear added together, aero parts included.",
      "200 km/h のときのダウンフォース（前後の合計）。空力のパーツ込み。")),
    (("Drivetrain", "駆動方式"), ("Which of FF, FR and 4WD may enter.", "FF・FR・4WD のうち、どれで出られるか。")),
    (("Forced induction", "過給機"), ("Whether a turbo may be fitted.", "ターボを付けてよいか。")),
    (("Driving aids", "運転補助"), ("Whether traction control (TCS) and ABS may be used.", "トラクションコントロール（TCS）と ABS を使ってよいか。")),
]
race_rules = "".join(f"<tr><th scope=\"row\">{T(*n)}</th><td>{T(*d)}</td></tr>" for n, d in RACE_RULES)

RACE_COMMANDS = [
    ("/kuruma-admin point", ("Mark one end of a line where you stand (OP).", "立っている場所を線の端にする（OP）。")),
    ("/kuruma-admin course create &lt;name&gt;", ("Make a course from the last two points. Face the racing direction. Names are up to 32 characters.",
                                                  "最後に打った 2 点でコースを作る。走る向きを向いて打つ。名前は 32 文字まで。")),
    ("/kuruma-admin course checkpoint &lt;name&gt;", ("Add a checkpoint from the last two points, in driving order.", "最後に打った 2 点でチェックポイントを足す。通る順に。")),
    ("/kuruma-admin course laps &lt;name&gt; &lt;laps&gt;", ("Default number of laps.", "既定の周回数。")),
    ("/kuruma-admin collide [true|false]", ("Whether cars hit each other.", "車同士が当たるか。")),
    ("/kuruma race open|join|leave|start|stop", ("The same as the K screens, as commands.", "K の画面と同じことをコマンドで。")),
    ("/kuruma best, /kuruma best top &lt;course&gt;", ("Your bests and a course's ranking.", "自己ベストと、コースのランキング。")),
]
race_commands = "".join(f"<div><dt><code>{c}</code></dt><dd>{T(*d)}</dd></div>" for c, d in RACE_COMMANDS)

page("race.html", "Racing | KurumaMod", "レース | KurumaMod", f"""
<section class="hero page" style="padding:0">
  <img src="img/race.jpg" {ALT("Start lights on a race track", "スタートシグナルの点いたコース")}>
  <div class="copy">
    <div class="wrap">
      <div class="crumb"><a href="index.html">KurumaMod</a> / {T("Racing", "レース")}</div>
      <h1>Racing</h1>
      <p>{T("Host and join with one key. Set a regulation, line up, and go.", "主催も参加も、キー 1 つで。レギュレーションを決めて、並んで、スタート。")}</p>
    </div>
  </div>
</section>

<div class="subnav">
  <div class="wrap">
    <span class="name">Racing</span>
    <nav aria-label="On this page">
      <a href="#flow">{T("How it works", "レースの流れ")}</a>
      <a href="#regulation">{T("Regulation", "レギュレーション")}</a>
      <a href="#setup">{T("Setup limits", "セッティングの制限")}</a>
      <a href="#hud">{T("On track", "走行中")}</a>
      <a href="#timeattack">{T("Time attack", "タイムアタック")}</a>
      <a href="#commands">{T("Commands", "コマンド")}</a>
    </nav>
    <a class="btn" href="start.html#download">{T("Download", "ダウンロード")}</a>
  </div>
</div>

<section id="flow">
  <div class="wrap">
    <div class="eyebrow">{T("How it works", "レースの流れ")}</div>
    <h2 class="title">{T("From an empty track to the flag.", "コース作りからチェッカーフラッグまで。")}</h2>
    <p class="lead">{T("Everything after the course is done from the race screen, opened with <span class='key' style='border-color:var(--black)'>K</span>. No commands needed.",
                       "コースを作ったあとは、すべて <span class='key' style='border-color:var(--black)'>K</span> で開くレースの画面から。コマンドはいりません。")}</p>
    <ol class="steps">
      <li><h3>{T("Build a course", "コースを作る")}</h3><p>{T("An OP marks a start line and checkpoints with <code>/kuruma-admin</code>. Once per world.", "OP が <code>/kuruma-admin</code> でスタートラインとチェックポイントを置きます。ワールドごとに 1 回だけ。")}</p></li>
      <li><h3>{T("Open a race", "レースを開く")}</h3><p>{T("Press K with no race open. Pick the course and laps, set the regulation and press Open race. You are entered too.", "レースが無いときに K。コースと周回を選び、レギュレーションを決めて「レースを開く」。主催者も自動で参加します。")}</p></li>
      <li><h3>{T("Join", "参加する")}</h3><p>{T("An invite pops up at the right edge for everyone in the world. Press K to join. Press K again to see the race and who is in.", "ワールドにいる全員の画面の右端に参加の案内が出ます。K で参加。もう一度 K を押すと、レースの内容と参加者を確認できます。")}</p></li>
      <li><h3>{T("Start", "スタート")}</h3><p>{T("The host presses Start. Cars are held still under the lights (you can still steer). Lap 1 starts on GO.", "主催者が「スタート」を押します。シグナルの間は車が止まります（ハンドルは切れます）。GO と同時に 1 周目の計測が始まります。")}</p></li>
    </ol>
    <dl class="news-list wide">
      <div><dt>{T("While entered", "エントリー中")}</dt><dd>{T("A green ENTERED panel at the right edge lists everyone and their check result. Hosts see a red HOSTING panel and a sound when someone joins or leaves. Both stay up while you have G or H open.", "右端に緑の ENTERED の欄が出て、参加者と判定が並びます。主催者には赤い HOSTING の欄が出て、誰かが入る・抜けると音で知らせます。G や H を開いていても出たままです。")}</dd></div>
      <div><dt>{T("Leaving and kicking", "エントリーの取り消しとキック")}</dt><dd>{T("Leave from the race screen. The host can remove an entrant with the × on their row.", "エントリーの取り消しはレースの画面から。主催者は、参加者の行の × でキックできます。")}</dd></div>
      <div><dt>{T("One race per world", "レースはワールドに 1 つ")}</dt><dd>{T("Each world (dimension) runs one race at a time. Invites only reach players in that world.", "1 つのワールド（ディメンション）で開けるレースは 1 つです。参加の案内はそのワールドにいる人にだけ届きます。")}</dd></div>
    </dl>
  </div>
</section>

<section class="mist" id="regulation">
  <div class="wrap">
    <div class="eyebrow">{T("Regulation", "レギュレーション")}</div>
    <h2 class="title">{T("Same rules for every car.", "全員を同じ条件で。")}</h2>
    <p class="lead">{T("The host picks which limits apply. Unticked limits are ignored. Hover a rule on the host screen to see what it measures.", "使う条件を主催者が選びます。切った条件は見ません。主催者画面で条件の名前にカーソルを乗せると、何を測るかが出ます。")}</p>
    <div class="compare-wrap">
      <table class="compare fit">
        <thead><tr><th scope="col">{T("Rule", "条件")}</th><th scope="col">{T("What it measures", "何を測るか")}</th></tr></thead>
        <tbody>{race_rules}</tbody>
      </table>
    </div>
    <div class="packs">
      <div class="pack static"><small>{T("Check", "判定")}</small><h3>{T("Blue OK, red breach", "青が適合、赤が違反")}</h3><p>{T("The server checks every entrant's car with the same maths the client uses. The host sees each car's numbers, with the ones out of range in red.", "サーバーが全員の車を、クライアントと同じ式で判定します。主催者には各車の値が並び、外れている値は赤で出ます。")}</p></div>
      <div class="pack static"><small>{T("At the start", "スタート")}</small><h3>{T("Breach means spectating", "違反なら観戦")}</h3><p>{T("Anyone still in breach when the lights come on watches instead. Leave the regulation mid-race and you are disqualified.", "スタートシグナルが点いた時点で違反している人は観戦になります。レース中にレギュレーションを外れると失格です。")}</p></div>
      <div class="pack static"><small>{T("Presets", "プリセット")}</small><h3>{T("Saved on the server", "サーバーに保存")}</h3><p>{T("OPs save regulations as named presets on the server. Anyone can pick and load them on the host screen.", "OP はレギュレーションに名前を付けてサーバーに保存できます。選んで読み込むのは誰でもできます。")}</p></div>
    </div>
  </div>
</section>

<section id="setup">
  <div class="wrap">
    <div class="eyebrow">{T("Setup limits", "セッティングの制限")}</div>
    <h2 class="title">{T("G knows the rules.", "G 画面もレギュレーションに従う。")}</h2>
    <p class="lead">{T("From the moment you enter until you finish, the setup screen (G) will not let you leave the regulation.", "エントリーしてからゴールするまで、セッティング画面（G）ではレギュレーションを外れる値にできません。")}</p>
    <dl class="news-list wide">
      <div><dt>{T("Hatched sliders", "範囲外は斜線で表示")}</dt><dd>{T("The part of each slider that would break a rule is hatched in orange, and the slider stops at its edge.", "レギュレーションを外れる範囲は橙の斜線で示され、つまみはその手前で止まります。")}</dd></div>
      <div><dt>{T("Locked traits", "固定された項目")}</dt><dd>{T("Fixed base traits show a padlock and cannot be moved.", "固定された素性には錠前が付き、動かせません。")}</dd></div>
      <div><dt>{T("Red items and tabs", "違反は赤で表示")}</dt><dd>{T("Anything that puts your car in breach is red, and so is the tab it lives on, so you can find it quickly.", "違反の原因になっている項目と、その項目があるタブが赤く表示されるので、すぐに見つかります。")}</dd></div>
      <div><dt>{T("Fixing it yourself", "自動では直しません")}</dt><dd>{T("Nothing is moved for you. If you are already out of range, you can only move back towards it. Reset and R still work before the start.", "値を勝手に動かすことはしません。外れている車は、直す向きにだけ動かせます。スタート前なら「すべて戻す」と R も使えます。")}</dd></div>
      <div><dt>{T("Parts", "パーツ")}</dt><dd>{T("Parts that would break the regulation cannot be fitted while you are entered.", "エントリー中は、レギュレーションを外れる部品には換えられません。")}</dd></div>
    </dl>
  </div>
</section>

<section class="mist" id="hud">
  <div class="wrap">
    <div class="eyebrow">{T("On track", "走行中")}</div>
    <h2 class="title">{T("Broadcast-style graphics.", "テレビ中継のような表示。")}</h2>
    <div class="packs">
      <div class="pack static"><small>{T("Top left", "左上")}</small><h3>{T("The tower", "順位表")}</h3><p>{T("Position, name and gap to the leader for everyone, live.", "全員の順位と、トップとの差をリアルタイムで表示します。")}</p></div>
      <div class="pack static"><small>{T("Top right", "右上")}</small><h3>{T("Your clock", "ラップタイム")}</h3><p>{T("Lap time, gap to your best at the last checkpoint, checkpoint progress, last and best lap.", "現在のラップタイム、直前のチェックポイントでの自己ベストとの差、チェックポイントの通過状況、前周とベストラップ。")}</p></div>
      <div class="pack static"><small>{T("Centre", "中央")}</small><h3>{T("Call-outs", "画面中央の通知")}</h3><p>{T("Checkpoint deltas, personal best laps, final lap, and a clear reason when a lap does not count.", "チェックポイント通過時のタイム差、自己ベスト更新、ファイナルラップ、周回が無効になったときの理由。")}</p></div>
    </div>
    <dl class="news-list wide">
      <div><dt>{T("Finish", "ゴール")}</dt><dd>{T("A FINISH band with your position, then the results. Press Esc to close them, or wait 15 seconds.", "順位入りの FINISH 表示のあと、リザルトが出ます。Esc ですぐ閉じられます（15 秒で自動的に閉じます）。")}</dd></div>
      <div><dt>{T("Lap 1 starts on GO", "1 周目の計測はスタートの合図から")}</dt><dd>{T("Line up before or after the start line. Crossing it right after GO is not counted as a lap.", "グリッドはスタートラインの手前でも先でも構いません。スタート直後にラインを越えても、周回には数えません。")}</dd></div>
      <div><dt>{T("Missed checkpoints", "チェックポイントを通り損ねたら")}</dt><dd>{T("Miss one and the lap does not count. Pass it and cross the line again to complete the lap.", "1 つでも通り損ねると、その周は無効になります。通り損ねたチェックポイントを通ってからラインを越えれば、周回が成立します。")}</dd></div>
    </dl>
  </div>
</section>

<section id="timeattack">
  <div class="wrap">
    <div class="eyebrow">{T("Time attack", "タイムアタック")}</div>
    <h2 class="title">{T("Just drive across the line.", "スタートラインを越えるだけ。")}</h2>
    <p class="lead">{T("Cross any start line to start timing on your own. The tower turns into your sector times with the gap to your best in each, and your best lap runs beside you as a ghost.", "どのスタートラインでも、越えれば 1 人で計測が始まります。塔は区間タイムと区間ごとの自己ベストとの差に変わり、ベストの走りがゴーストとして隣を走ります。")}</p>
  </div>
</section>

<section class="mist" id="commands">
  <div class="wrap">
    <div class="eyebrow">{T("Commands", "コマンド")}</div>
    <h2 class="title">{T("For OPs and scripts.", "OP 向けのコマンド。")}</h2>
    <p class="lead">{T("Courses are built with commands. Everything else can also be done from the K screens.", "コースはコマンドで作ります。それ以外は K の画面でもできます。")}</p>
    <dl class="news-list wide">{race_commands}</dl>
  </div>
</section>

<section class="dark cta-band">
  <div class="wrap">
    <div>
      <h2 class="title">{T("Looking for people to race?", "一緒に走る仲間を探すなら。")}</h2>
      <p class="lead">{T("Find races and share regulations on Discord.", "Discord でレースの告知やレギュレーションを共有できます。")}</p>
    </div>
    <div class="acts"><a class="btn light arrow" href="{DISCORD}">Discord</a></div>
  </div>
</section>
""", desc="Host and join races with one key. Set a regulation for power, weight, grip and more, and race with broadcast-style graphics.", image="img/race.jpg")

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
    <p class="lead">{T("Every version is on the same page. Forge and NeoForge need no other mods; Fabric needs Fabric API.", "どの版も同じページから。Forge と NeoForge は前提 MOD がいりません。Fabric は Fabric API が必要です。")}</p>
    <div class="compare-wrap">
      <table class="compare icons">
        <thead><tr><th scope="col">Minecraft</th><th scope="col">Forge</th><th scope="col">NeoForge</th><th scope="col">Fabric</th></tr></thead>
        <tbody>
          <tr><th scope="row">1.20.1</th><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">{T("47.4.22 or later", "47.4.22 以上")}</small></td><td>—</td><td>—</td></tr>
          <tr><th scope="row">1.21.1</th><td>—</td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">{T("21.1 or later", "21.1 以上")}</small></td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">{T("Loader 0.16.14 or later + Fabric API", "Loader 0.16.14 以上 + Fabric API")}</small></td></tr>
          <tr><th scope="row">26.3</th><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">{T("66.1.5 or later", "66.1.5 以上")}</small></td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">{T("26.3.0.101 or later", "26.3.0.101 以上")}</small></td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">{T("Loader 0.20.1 or later + Fabric API", "Loader 0.20.1 以上 + Fabric API")}</small></td></tr>
        </tbody>
      </table>
    </div>
    <p class="lead">{T("Using shader packs? See the", "シェーダーパックを使う方は")} <a href="shaders.html">{T("shader pack page", "シェーダーのページ")}</a>{T(".", "へ。")}</p>
    <div class="cta-row">
      <a class="btn arrow" href="{CF}">{ICON_CF}{T("KurumaMod on CurseForge", "KurumaMod（CurseForge）")}</a>
      <a class="btn outline" href="{MR}">{ICON_MR}{T("KurumaMod on Modrinth", "KurumaMod（Modrinth）")}</a>
    </div>
  </div>
</section>

<section>
  <div class="wrap">
    <div class="eyebrow">{T("Car packs", "カーパック")}</div>
    <h2 class="title">{T("Add car packs.", "カーパックを追加する。")}</h2>
    <p class="lead">{T("One car pack jar works on every version and loader.", "カーパックは 1 つの jar で、どの版・どのローダーでも動きます。")}</p>
    <div class="compare-wrap">
      <table class="compare icons">
        <thead><tr><th scope="col">{T("Car pack", "カーパック")}</th><th scope="col">Forge</th><th scope="col">NeoForge</th><th scope="col">Fabric</th></tr></thead>
        <tbody>
          <tr><th scope="row">Basic Car Pack</th><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">1.20.1 / 26.3</small></td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">1.21.1 / 26.3</small></td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">1.21.1 / 26.3</small></td></tr>
          <tr><th scope="row">Example Car Pack</th><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">1.20.1 / 26.3</small></td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">1.21.1 / 26.3</small></td><td>{ICON_CHECK}{T("Supported", "対応")}<small class="ver">1.21.1 / 26.3</small></td></tr>
        </tbody>
      </table>
    </div>
    <div class="cta-row">
      <a class="btn outline" href="{CF_BASIC}" target="_blank" rel="noopener">{ICON_CF}Basic Car Pack</a>
      <a class="btn outline" href="{CF_EXAMPLE}" target="_blank" rel="noopener">{ICON_CF}Example Car Pack</a>
    </div>
  </div>
</section>

<section class="mist" id="install">
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
        <div><dt>{T("Race", "レース")}</dt><dd>{T("Press K to open a race and set the regulation. Others press K when the invite appears. The host presses Start. <a href='race.html'>How racing works</a>",
                                                   "K でレースを開いてレギュレーションを決める → 参加の案内が出たら参加者も K → 主催者が「スタート」。<a href='race.html'>レースの詳しい説明</a>")}</dd></div>
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
    <div class="acts"><a class="btn light arrow" href="{DISCORD}">Discord</a></div>
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
    ("prep", "guide-prep.html", ("Setup", "準備")),
    ("car", "guide-car.html", ("Car", "車")),
    ("parts", "guide-parts.html", ("Parts", "パーツ")),
    ("wheel", "guide-wheel.html", ("Wheel", "ホイール")),
    ("tire", "guide-tire.html", ("Tyre", "タイヤ")),
    ("ship", "guide-ship.html", ("Shipping", "配布")),
]
GFILE = {k: f for k, f, _ in GPAGES}
GTITLE = {
    "home": (("Car Pack Guide", "カーパック作成ガイド"), ("From a mesh in Blender to a car you can drive in Minecraft. No Java, and no Blender experience needed.", "Blender で作った車を、Minecraft の中で走らせるまで。Java も Blender の経験も要りません。")),
    "prep": (("Get set up", "制作の準備"), ("Copy the example pack and set up your own car pack folder.", "見本のパックをコピーして、自分のカーパックフォルダを用意する。")),
    "car": (("Make a car", "車を作る"), ("Body model, lights, glass and mirrors, file layout and JSON, sounds, and the spec.", "車体のモデル、灯火・ガラス・ミラー、置き場所と JSON、音、諸元（走り）。")),
    "wheel": (("Make a wheel", "ホイールを作る"), ("The wheel model, and turning it into a part players can pick.", "ホイールのモデルと、換装画面で選べる部品にする方法。")),
    "tire": (("Make a tyre", "タイヤを作る"), ("The tyre model, and tyres that change grip on each surface.", "タイヤのモデルと、路面ごとのグリップが変わるタイヤの部品。")),
    "parts": (("Make parts", "パーツを作る"), ("Body parts such as aero and fenders, exhausts, and turbos and blow-off valves.", "エアロやフェンダーなどの外装パーツ、マフラー、ターボとブローオフバルブ。")),
    "ship": (("Ship it", "配布する"), ("Pack your car pack folder into a jar and share it, and fix what does not work.", "カーパックフォルダを jar に固めて配る。うまく動かないときの直しかた。")),
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

    # 目次。章のリンクの組と、まとまり（<div class="toc-grp">「作る」「調整」など）の開き・閉じを順に拾う
    toc = []
    nav = re.search(r'<nav class="toc">(.*?)</nav>', src, re.S).group(1)
    grp_open = False
    for mo in re.finditer(r'<a href="#([^"]+)" data-guide="([^"]+)" class="ja">(.*?)</a><a href="#\1" data-guide="\2" class="en">(.*?)</a>'
                          r'|<div class="toc-grp" data-guide="([^"]+)"><span class="ja">(.*?)</span><span class="en">(.*?)</span>'
                          r'|</div>', nav):
        if mo.group(1):
            toc.append(("a", mo.group(1), mo.group(2).split(), mo.group(3), mo.group(4)))
        elif mo.group(5):
            toc.append(("open", None, mo.group(5).split(), mo.group(6), mo.group(7)))
            grp_open = True
        elif grp_open:
            toc.append(("close", None, [], None, None))
            grp_open = False
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
            items = ""
            inside = False
            for kind, sid, pages, ja, en in toc:
                if kind == "close":
                    if inside:
                        items += "</div>"
                    inside = False
                elif key not in pages:
                    continue
                elif kind == "open":
                    items += f'<div class="ggrp"><div class="gt">{T(en, ja)}</div>'
                    inside = True
                else:
                    items += f'<a href="#{sid}">{T(en, ja)}</a>'
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
    <div class="cta-row"><a class="btn arrow" href="{CF_EXAMPLE}">{ICON_CF}{T("Example Car Pack on CurseForge", "Example Car Pack（CurseForge）")}</a><a class="btn outline" href="{DISCORD}">{T("Ask on Discord", "Discord で質問する")}</a></div>
  </div>
</section>"""
        body = f"""
<section class="ghero">
  <div class="wrap">
    <div class="crumb"><a href="index.html">KurumaMod</a> / {T("Car Pack Guide", "カーパック作成ガイド")}{"" if key == "home" else " / " + T(*label)}</div>
    <h1>{T(*title)}</h1>
    <p>{T(*sub)}</p>
  </div>
</section>
<div class="gtabs-bar"><div class="wrap"><nav class="gtabs" aria-label="Guide pages">{tabs_for(key)}</nav></div></div>

<div class="wrap {layout_cls}">
  {side}
  <div class="gdoc">{body_secs}</div>
</div>
{extra}
"""
        en_title = ("Car Pack Guide" if key == "home" else f"{title[0]} | Car Pack Guide") + " | KurumaMod"
        ja_title = ("カーパック作成ガイド" if key == "home" else f"{title[1]} | カーパック作成ガイド") + " | KurumaMod"
        page(file, en_title, ja_title, body, desc=sub[0])
    print(f"guide ok ({len(sections)} sections, {count[0]} images)")


build_guide()


# サイトマップ。page() で書き出したページをそのまま並べるので、ページを足せば自動で載る。
# 英語と日本語は同じ URL（site.js が切り替える）なので、言語ごとの URL は持たない。
# lastmod は書かない——作り直すたびに全ページの日付が今日になり、かえって当てにならなくなる
def write_sitemap():
    # 順番に意味は無いが、読んだときに分かりやすいようトップを先頭に
    ordered = sorted(PAGES, key=lambda u: u != SITE)
    urls = "".join(f"  <url><loc>{html.escape(u)}</loc></url>\n" for u in ordered)
    (OUT / "sitemap.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        f"{urls}</urlset>\n", encoding="utf-8")
    # 検索エンジンがサイトマップを見つけられるよう、robots.txt から指す
    (OUT / "robots.txt").write_text(f"User-agent: *\nAllow: /\n\nSitemap: {SITE}sitemap.xml\n", encoding="utf-8")
    print(f"sitemap ok ({len(PAGES)} pages)")


write_sitemap()
