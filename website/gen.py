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
# コミュニティのカーパック（トップの「車を追加する」に載せる）
CF_DREAM_DRIFT = "https://www.curseforge.com/minecraft/mc-mods/dream-drift-pack"
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


def SHOT(name, alt, note, top=32, align=None, whole=False):
    """スクリーンショットを差し込む。img/<name>.jpg があれば出し、<name>-ja.jpg もあれば言語で切り替える。
    <b>まだ無ければプレースホルダー</b>（点線の枠に、置くファイル名と写すもの）。撮って置けば、作り直すだけで入れ替わる。
    枠（.media）は 16:10 で、16:9 のスクリーンショットは左右が切れる。画面の端に見せたいものがあるときは
    align に "right" か "left" を渡して、そちらの端に寄せる（object-position）。
    両端とも見せたい画面（左右いっぱいに中身がある GUI）は whole=True で、枠を 16:9 にして切らない。"""
    style = f'margin-top:{top}px' + (';aspect-ratio:16/9' if whole else '')
    pos = f' style="object-position:{align} center"' if align else ''
    en = HERE / "img" / f"{name}.jpg"
    ja = HERE / "img" / f"{name}-ja.jpg"
    if en.exists():
        if ja.exists():
            body = T(f'<img src="img/{name}.jpg" alt="{alt[0]}"{pos}>', f'<img src="img/{name}-ja.jpg" alt="{alt[1]}"{pos}>')
        else:
            body = f'<img src="img/{name}.jpg" {ALT(*alt)}{pos}>'
        return f'<div class="media" style="{style}">{body}</div>'
    return (f'<div class="media" style="{style};aspect-ratio:16/9;display:flex;align-items:center;justify-content:center;'
            f'border:2px dashed #b9bec6;border-radius:12px;background:#f3f4f6;color:#6b7280;text-align:center;padding:24px">'
            f'<div><div style="font:600 15px/1.4 monospace">img/{name}.jpg</div>'
            f'<div style="margin-top:6px;font-size:14px">{T(*note)}</div></div></div>')


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
      <div><h4>KurumaMod</h4><ul><li><a href="index.html">{T("Home", "トップ")}</a></li><li><a href="start.html">{T("Get started", "はじめかた")}</a></li><li><a href="features.html">{T("Features", "特徴")}</a></li><li><a href="race.html">{T("Racing", "レース")}</a></li><li><a href="shaders.html">{T("Shaders", "シェーダー")}</a></li></ul></div>
      <div><h4>{T("Car Pack Guide", "作成ガイド")}</h4><ul><li><a href="guide.html">{T("Overview", "はじめに")}</a></li><li><a href="guide-prep.html">{T("Setup", "準備")}</a></li><li><a href="guide-car.html">{T("Car", "車")}</a></li><li><a href="guide-parts.html">{T("Parts", "パーツ")}</a></li><li><a href="guide-wheel.html">{T("Wheel", "ホイール")}</a></li><li><a href="guide-tire.html">{T("Tyre", "タイヤ")}</a></li><li><a href="guide-ship.html">{T("Shipping", "配布")}</a></li></ul></div>
      <div><h4>{T("Download", "ダウンロード")}</h4><ul><li><a href="{CF}">CurseForge</a></li><li><a href="{MR}">Modrinth</a></li><li><a href="{CF_BASIC}">Basic Car Pack</a></li><li><a href="{CF_EXAMPLE}">Example Car Pack</a></li></ul></div>
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
YOUTUBE_ID = "JU7b6dO_U5s"


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

MEASURED = ("Figures are calculated with KurumaMod's own physics.",
            "性能は KurumaMod の物理演算で計算しています。")


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
        </div>
      </article>"""


def tile(cls, anchor, img, alt, small, h, p):
    return f"""<a class="tile {cls}" href="features.html#{anchor}">
        <img src="img/{img}" {ALT(*alt)}>
        <div class="in"><small>{T(*small)}</small><h3>{h}</h3><p>{T(*p)}</p></div>
      </a>"""


NEWS = [
    (("Races from the K screen", "レースは K の画面から"), ("Host, join, start and end races from one screen. An invite shows up when someone opens a race.", "主催・参加・開始・終了を 1 つの画面で。誰かがレースを開くと招待が届きます。")),
    (("Regulations and classes", "レギュレーションとクラス"), ("Cap power, weight, grip and downforce, save the rules as a class, and race under them.", "出力・車重・グリップ・ダウンフォースの上限を決め、クラスとして保存して、その条件で走れます。")),
    (("Time attack in classes", "クラスごとのタイムアタック"), ("Best laps are kept per course and class, so laps run under the same rules compete.", "ベストラップをコースとクラスごとに記録。同じ条件で走ったラップどうしで競えます。")),
    (("Broadcast-style race HUD", "中継風のレース HUD"), ("Start lights, a live tower, your gap at every checkpoint, results, and spectating.", "スタートシグナル、リアルタイムの順位、チェックポイントごとの差、結果、観戦も。")),
    (("Braking while turning", "曲がりながらのブレーキ"), ("ABS keeps grip for steering, so full braking with full lock turns about twice as far.", "ABS が舵のぶんのグリップを残すので、フルブレーキでいっぱいに切ると 2 倍ほど曲がります。")),
    (("km/h or mph", "km/h か mph か"), ("Choose the speed unit for the speedometer and the setup screens.", "速度計とセッティング画面の速度の単位を選べます。")),
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
    <h2 class="title">{T("More than just looks.", "見た目だけの車じゃない。")}</h2>
    <p class="lead">{T("Get in and feel how it moves for yourself.", "車の動きを、実際に乗って体感してください。")}</p>
    <div class="tiles">
      {tile("wide", "physics", "hud.jpg", ("Silvia S15 with gauges on screen", "回転計と速度計を表示して走る S15"), ("Physics", "物理演算"), T("You feel the limit before you reach it.", "限界の手前が、手に伝わる。"), ("Steering follows the slide, so there's always enough opposite lock to catch it.", "ハンドルは滑りに合わせて切れるので、流れた分だけカウンターを当てられます。"))}
      {tile("two", "lighting", "eyecatch.jpg", ("Headlights on a night track", "夜のサーキットをヘッドライトで照らして走る"), ("Lighting", "ライティング"), key_l + T("Headlights that really light the road", "路面を本当に照らすヘッドライト"), ("A proper low beam, shadows from trees and walls, beams in fog and rain. Works with shader packs.", "カットオフの付いたロービーム、木や壁の影、霧や雨の中の光の筋。シェーダーパックにも対応。"))}
      {tile("third", "surface", "gravel.jpg", ("A car leaving a smoke trail on loose ground", "未舗装路に煙の跡を残して走る車"), ("Surfaces", "路面"), T("Six surfaces and rain", "6 種類の路面と雨"), ("Tarmac, dirt, gravel, sand, snow and ice each drive differently.", "舗装、土、砂利、砂、雪、氷で、走りがまったく変わります。"))}
      {tile("third", "custom", "camber.jpg", ("Wheels with negative camber", "キャンバーを付けたホイール"), ("Customise", "カスタマイズ"), T("Aero, mufflers, wheels", "エアロ、マフラー、ホイール"), ("Camber, offset and width, plus wings that make real downforce.", "キャンバー、オフセット、幅。ウイングは本当にダウンフォースを生みます。"))}
      {tile("two", "racing", "race-hud.jpg", ("A race in progress with the standings and lap clock", "順位表とタイムが出ているレース中の画面"), ("Racing", "レース"), T("Race your friends", "友だちとレースを"), ("Start lights, live standings and split times, in multiplayer.", "スタートシグナル、リアルタイムの順位、区間タイム。マルチプレイで。"))}
    </div>
  </div>
</section>

<section id="cars">
  <div class="wrap">
    <h2 class="title">{T("Cars included", "収録車種")}</h2>
    <p class="lead">{T("The S15 comes with KurumaMod; the 911 and the Prado come with the free Basic Car Pack. All three run on the same physics, so weight, balance and drivetrain are what make them drive differently.", "S15 は KurumaMod に、911 とプラドは無料の Basic Car Pack に入っています。3 台とも同じ物理で走るので、重さ、重心の位置、駆動方式の違いが、そのまま走りの違いになります。")}</p>
    <div class="models">{cards}</div>
    <p class="note">{T(*MEASURED)}</p>
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
    <div class="cta-row"><a class="btn arrow" href="start.html#download">{T("Download", "ダウンロード")}</a></div>
  </div>
</section>

<section>
  <div class="wrap">
    <h2 class="title">{T("Add more cars", "車を追加する")}</h2>
    <p class="lead">{T("A car pack is a single jar. Put it in mods/ and the cars are there.", "カーパックは jar ひとつ。mods に入れるだけで車が増えます。")}</p>
    <div class="packs four">
      <a class="pack" href="{CF_BASIC}"><small>{T("Official", "公式")}</small><h3>Basic Car Pack</h3><p>{T("Adds the Porsche 911 and the Toyota Land Cruiser Prado.", "ポルシェ 911 とトヨタ ランドクルーザー プラドを追加します。")}</p><span class="go">CurseForge</span></a>
      <a class="pack" href="start.html#carpacks"><small>{T("Community", "コミュニティ")}</small><h3>{T("Community car packs", "コミュニティのカーパック")}</h3><p>{T("Car packs made by players.", "プレイヤーが作ったカーパック。")}</p><span class="go">{T("See car packs", "カーパックを見る")}</span></a>
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
        <h2 class="title">KurumaMod 2.1.0</h2>
        <p>{T("Racing rebuilt around the K key: host and join from a screen, regulations, classes, class-based time attack and spectating.", "レースを K キー中心に作り直しました。画面から主催と参加、レギュレーション、クラス、クラスごとのタイムアタック、観戦。")}</p>
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
def feature(id_, cls, rev, img, alt, eyebrow, title, text, link=None):
    """link は (href, (英語, 日本語)) で、本文の下にボタンを置く（詳しいページがある項目）"""
    rev_cls = " rev" if rev else ""
    cta = (f'\n        <div class="cta-row"><a class="btn arrow" href="{link[0]}">{T(*link[1])}</a></div>'
           if link else "")
    return f"""<section class="{cls}" id="{id_}">
  <div class="wrap">
    <div class="split{rev_cls}">
      <div>
        <div class="eyebrow">{T(*eyebrow)}</div>
        <h2 class="title">{title}</h2>
        <p>{T(*text)}</p>{cta}
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
        <h2 class="title">{T("Change the surface, change how it drives.", "路面が変われば、走りも変わる。")}</h2>
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
{feature("racing", "", True, "race-hud.jpg", ("A race in progress with the standings and lap clock", "順位表とタイムが出ているレース中の画面"), ("Racing", "レース"), T("Race your friends.", "友だちとレースを。"),
         ("Place a start line and checkpoints anywhere in your world and hold a proper race. Host and join with one key, set a regulation for power, weight and grip, and race with start lights, a live tower and your gap to your best lap at every checkpoint. Multiplayer, timed to the millisecond.",
          "ワールドのどこにでもスタートラインとチェックポイントを置いて、本格的なレースを開けます。主催も参加もキー 1 つ。出力・車重・グリップなどのレギュレーションを決めて、スタートシグナル、リアルタイムの順位、チェックポイントごとのベストラップとの差。マルチプレイ対応、計測はミリ秒単位。"),
         link=("race.html", ("More about racing", "レースについて詳しく")))}
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
     ("Locks the settings that only make a car faster when changed (size, tyre traits, drag and so on) to the car's stock values.",
      "変えると車がただ速くなるだけの項目（車の大きさ、タイヤの性質、空気抵抗など）を、車種の決まった値に固定できます。")),
    (("Max power", "最高出力の上限"),
     ("Peak power in PS, including turbo and parts.",
      "車の最高出力（PS）。ターボやパーツの分も含みます。")),
    (("Min weight", "車重の下限"),
     ("Car weight in kg.", "車重（kg）。")),
    (("Min power-to-weight", "パワーウエイトレシオの下限"),
     ("Weight ÷ power (kg/PS). Lower is quicker.",
      "車重 ÷ 最高出力（kg/PS）。小さいほど速い車です。")),
    (("Max grip", "グリップの上限"),
     ("Tyre grip, including tyre parts.", "タイヤのグリップ。タイヤのパーツの分も含みます。")),
    (("Max downforce", "ダウンフォースの上限"),
     ("Downforce at 200 km/h, including aero parts.",
      "200 km/h でのダウンフォース。空力パーツの分も含みます。")),
    (("Drivetrain", "駆動方式"), ("Which of FF, FR and 4WD may race.", "FF・FR・4WD のどれで出られるか。")),
    (("Forced induction", "過給機"), ("Whether a turbo may be fitted.", "ターボを付けてよいか。")),
    (("Driving aids", "運転補助"), ("Whether TCS and ABS are allowed.", "TCS と ABS を使ってよいか。")),
]
race_rules = "".join(f"<tr><th scope=\"row\">{T(*n)}</th><td>{T(*d)}</td></tr>" for n, d in RACE_RULES)

page("race.html", "Racing | KurumaMod", "レース | KurumaMod", f"""
<section class="hero page" style="padding:0">
  <img src="img/race-hud.jpg" {ALT("A race in progress with the standings and lap clock", "順位表とタイムが出ているレース中の画面")}>
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
    </nav>
    <a class="btn" href="start.html#download">{T("Download", "ダウンロード")}</a>
  </div>
</div>

<section id="flow">
  <div class="wrap">
    <div class="eyebrow">{T("How it works", "レースの流れ")}</div>
    <h2 class="title">{T("Set up a course and hold a race.", "コースを設定して、レースを開催しよう。")}</h2>
    <p class="lead">{T("Build a course once, then open and run races from the K screen.", "コースを作れば、あとは K の画面からレースを開いて走れます。")}</p>
    <ol class="steps">
      <li><h3>{T("Build a course", "コースを作る")}</h3><p>{T("Place a start line and checkpoints anywhere in your world.", "ワールドのどこにでも、スタートラインとチェックポイントを置けます。")}</p></li>
      <li><h3>{T("Open a race", "レースを開く")}</h3><p>{T("Choose the track, laps and regulation.", "トラック・周回・レギュレーションを決めて開きます。")}</p></li>
      <li><h3>{T("Join", "参加する")}</h3><p>{T("Everyone in the world gets an invite and joins from K.", "ワールドの全員に案内が届き、K から参加できます。")}</p></li>
      <li><h3>{T("Start", "スタート")}</h3><p>{T("Start lights, then everyone goes on GO.", "スタートシグナルが消えたら、一斉にスタート。")}</p></li>
    </ol>
    {SHOT("race-invite", ("The race invite at the right edge while driving", "走行中に右端に出た参加の案内"),
          ("Driving with the race invite or the ENTERED panel at the right edge", "走行中、右端に参加の案内か ENTERED の欄が出ているところ"),
          align="right")}
  </div>
</section>

<section class="mist" id="regulation">
  <div class="wrap">
    <div class="eyebrow">{T("Regulation", "レギュレーション")}</div>
    <h2 class="title">{T("Same rules. Pure driving.", "同じ条件で、腕を競おう。")}</h2>
    <p class="lead">{T("Even out the cars with limits on power, weight, grip and more.", "出力・車重・グリップなどの条件で、車の性能をそろえます。")}</p>
    <div class="compare-wrap">
      <table class="compare fit">
        <thead><tr><th scope="col">{T("Rule", "条件")}</th><th scope="col">{T("What it measures", "何を測るか")}</th></tr></thead>
        <tbody>{race_rules}</tbody>
      </table>
    </div>
    {SHOT("race-host", ("The host screen with the regulation and each entrant's check", "決まりと参加者の判定が並ぶ主催者画面"),
          ("Host screen: regulation on the left, entrants with blue OK and red breaches on the right", "主催者画面。左に決まり、右に参加者と判定（適合は青、違反は赤）"),
          whole=True)}
    <div class="packs">
      <div class="pack static"><small>{T("Check", "判定")}</small><h3>{T("Blue OK, red breach", "青が適合、赤が違反")}</h3><p>{T("Every entrant's car is checked automatically.", "参加者全員の車を自動で判定します。")}</p></div>
      <div class="pack static"><small>{T("At the start", "スタート")}</small><h3>{T("Breach means spectating", "違反なら観戦")}</h3><p>{T("Cars outside the rules watch instead of racing.", "条件を外れた車は観戦に回ります。")}</p></div>
      <div class="pack static"><small>{T("Classes", "クラス")}</small><h3>{T("Saved on the server", "サーバーに保存")}</h3><p>{T("Save your favourite rules as named classes for races and time attack.", "よく使う条件をクラスとして保存し、レースにもタイムアタックにも使えます。")}</p></div>
    </div>
  </div>
</section>

<section id="setup">
  <div class="wrap">
    <div class="eyebrow">{T("Setup limits", "セッティングの制限")}</div>
    <h2 class="title">{T("G knows the rules.", "G 画面もレギュレーションに従います。")}</h2>
    <p class="lead">{T("In a race or a class, the setup screen only lets you tune within the rules.", "レース中もクラスで走るときも、セッティング画面は条件の範囲内でしか動かせません。")}</p>
    {SHOT("race-setup", ("The setup screen with hatched out-of-range areas", "範囲外が斜線になったセッティング画面"),
          ("Setup screen (G): out-of-range parts hatched in orange, breaching items and tab in red", "セッティング画面（G）。範囲外が橙の斜線、違反の項目とタブが赤"),
          whole=True)}
  </div>
</section>

<section class="mist" id="hud">
  <div class="wrap">
    <div class="eyebrow">{T("On track", "走行中")}</div>
    <h2 class="title">{T("Graphics like a real racing game.", "本格的なレーシングゲームのような画面。")}</h2>
    {SHOT("race-hud", ("A race in progress with the tower, clock and checkpoint delta", "順位の塔・時計・チェックポイントの差が出ているレース中の画面"),
          ("Mid-race with other cars: tower top left, clock and CLASS top right, checkpoint delta top centre", "ほかの車もいるレース中。左上に順位の塔、右上に時計と CLASS、上の真ん中にチェックポイントの差"),
          whole=True)}
    <div class="packs">
      <div class="pack static"><small>{T("Top left", "左上")}</small><h3>{T("The tower", "順位表")}</h3><p>{T("Everyone's position and gap to the leader, live.", "全員の順位とトップとの差をリアルタイムで。")}</p></div>
      <div class="pack static"><small>{T("Top right", "右上")}</small><h3>{T("Your clock", "ラップタイム")}</h3><p>{T("Lap time and your gap to your best.", "ラップタイムと、自己ベストとの差。")}</p></div>
      <div class="pack static"><small>{T("Top centre", "上の真ん中")}</small><h3>{T("Call-outs", "通知")}</h3><p>{T("Checkpoint deltas, personal bests and the final lap.", "チェックポイントの差、自己ベスト更新、ファイナルラップ。")}</p></div>
    </div>
    {SHOT("race-results", ("The results after the finish", "ゴール後のリザルト"),
          ("Results after the finish (a retired row makes it even better)", "ゴール後のリザルト（リタイアの行があるとなお良い）"))}
  </div>
</section>

<section id="timeattack">
  <div class="wrap">
    <div class="eyebrow">{T("Time attack", "タイムアタック")}</div>
    <h2 class="title">{T("Just drive across the line.", "スタートラインを越えるだけ。")}</h2>
    <p class="lead">{T("Cross a start line and timing begins. Sector times, your gap to your best, and your best lap as a ghost.", "スタートラインを越えれば計測開始。区間タイム、自己ベストとの差、ベストの走りをゴーストで。")}</p>
    {SHOT("race-ghost", ("Time attack beside your ghost", "ゴーストと並んで走るタイムアタック"),
          ("Time attack beside the ghost: sector tower top left, CLASS and BEST top right", "ゴーストと並んで走るタイムアタック。左上に区間の塔、右上に CLASS と BEST"),
          whole=True)}
    <dl class="news-list wide">
      <div><dt>{T("Run in a class", "クラスで走る")}</dt><dd>{T("Pick a class with K and set laps against drivers in the same class.", "K でクラスを選べば、同じクラスの車どうしでタイムを競えます。")}</dd></div>
      <div><dt>{T("Records and rankings", "記録とランキング")}</dt><dd>{T("Rankings for each class and for no class on every track, right on the K screen.", "トラックごとに、クラスなしとクラス別のランキングを K の画面で。")}</dd></div>
    </dl>
    {SHOT("race-timeattack", ("The time attack screen with classes and rankings", "クラスとランキングが並ぶタイムアタックの画面"),
          ("K, Time attack tab: class dropdown on the left, no-class and class rankings on the right (your row highlighted)", "K のタイムアタックのタブ。左にクラスのドロップダウン、右にクラスなしとクラスのランキング（自分の行が光っている）"),
          whole=True)}
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
""", desc="Host and join races with one key. Set a regulation for power, weight, grip and more, and race with broadcast-style graphics.", image="img/race-hud.jpg")

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

<section id="carpacks">
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
    <h3 class="sub-title" style="margin-top:56px;font-size:24px;font-weight:500">{T("Community car packs", "コミュニティのカーパック")}</h3>
    <p class="lead">{T("Car packs made by players. Check each page for supported versions.", "プレイヤーが作ったカーパックです。対応する版は、それぞれのページで確かめてください。")}</p>
    <div class="packs" style="margin-top:24px">
      <a class="pack" href="{CF_DREAM_DRIFT}" target="_blank" rel="noopener"><small>{T("By LexusIS300", "作者 LexusIS300")}</small><h3>Dream Drift Pack</h3><p>{T("Adds the Mitsubishi Lancer, with bumpers, spoilers and exhausts to swap.", "三菱 ランサーを追加。バンパー、スポイラー、マフラーを換装できます。")}</p><span class="go">CurseForge</span></a>
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
        <div><dt>{T("Time attack", "タイムアタック")}</dt><dd>{T("Drive across a start line for a warm-up lap. Timing starts the next time you cross it. Pick a class with K if you like.", "スタートラインを越えるとウォームアップの 1 周、次に越えたところから計測。クラスで走るなら K で選びます。")}</dd></div>
        <div><dt>{T("Race", "レース")}</dt><dd>{T("Press K and open a race from the Host a race tab. Others press K when the invite appears and press Join. The host presses Start. <a href='race.html'>How racing works</a>",
                                                   "K の「レースを開く」のタブでレースを開く → 参加の案内が出たら参加者も K を押して「参加する」 → 主催者が「スタート」。<a href='race.html'>レースの詳しい説明</a>")}</dd></div>
        <div><dt>{T("Records", "記録")}</dt><dd>{T("Press K. The Time attack tab shows the rankings for each track.", "K のタイムアタックのタブで、トラックごとのランキングを見られます。")}</dd></div>
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
