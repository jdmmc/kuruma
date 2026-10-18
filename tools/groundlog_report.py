"""接地の記録（GroundLog の CSV）を読んで、どこで・なぜ輪が浮いた／跳ねたかをまとめる。

使い方:
    python tools/groundlog_report.py run/kuruma-groundlog/groundlog-20261018-120000.csv
    python tools/groundlog_report.py run/kuruma-groundlog            # フォルダなら一番新しいもの

記録はゲーム内で運転中に Ctrl+H（キーは変えられる）で始めて、もう一度押すと止まる。
地面の補間は Ctrl+J で切り替えられる（切 → 入・車体の向き → 入・進む向き）。記録の途中で切り替えたら、段ごとに分けてまとめる。

浮いた区間（荷重 0 が続いた区間）ごとに、直前 0.2 秒と区間の中を見て原因を 1 つに決める。
上から順に当てはめる:

    地面なし       … 真下に地面が見つからない（崖・穴・横転）
    地面の跳び     … 渡した地面の高さが、その刻みだけ 3cm 以上跳んだ         → コード
    底付き         … 直前にストロークを使いきってバンプストップに乗った     → セッティング
    先読みの突き上げ … 先読みの斜面が地面を速くせり上げた                    → コード
    坂の突き上げ   … 真下の地面か階段の線がせり上がり、ダンパーが押し返した → 坂の形（＋縮み側の減衰）
    細かい凸凹     … 3cm 未満の跳びが続き、ダンパーがそれに反応した          → コード
    伸び側の減衰   … タイヤは地面に届く位置にあるのに荷重が 0                → セッティング
    地面が離れた   … タイヤがストロークの外まで離れた（頂上・段を降りる）    → 物理として自然
"""

import csv
import glob
import math
import os
import sys
from collections import defaultdict

DT = 1.0 / 80.0
WHEELS = ["fl", "fr", "rl", "rr"]
WHEEL_NAMES = {"fl": "左前", "fr": "右前", "rl": "左後", "rr": "右後"}
SOURCE_NAMES = {
    "FLAT": "真下の地面", "STAIR": "階段の線", "AHEAD": "先読みの斜面", "RISE_LIMIT": "上昇制限",
    "WALL_HOLD": "壁で据え置き", "BURIED": "埋まり戻し", "DEEP_STAIR": "1 段下の階段", "MISS": "地面なし",
    "TILTED": "傾きすぎ", "": "?",
}
SMOOTH_NAMES = {"2": "入（階段の線は進む向き）", "1": "入（階段の線は車体の向き）", "0": "切", "?": "不明（古い記録）"}
# 浮いた区間の前を何刻み見るか（0.2 秒）
LOOK_BACK = 16
# 地面が 1 刻みでこれ以上動いたら「跳び」 [m]
GROUND_JUMP = 0.03
# 地面がこれより速くせり上がったら「突き上げ」 [m/s]
KICK_SPEED = 1.5
# 直前 0.1 秒で地面の動く速さがこれ以上揺れていたら「細かい凸凹」 [m/s]
BUMPY_SPREAD = 1.0
# 荷重が停車時のこの倍を超えたら「跳ね」
SPIKE_FACTOR = 4.0
# これより短い浮きはちらつきとして別に数える [刻み]
MIN_EVENT = 2

CAUSES = [
    ("no_ground", "地面なし", "崖・穴・横転。正しい挙動のことが多い"),
    ("ground_jump", "地面の跳び", "コード: 地面の作り方（出どころの切り替わり）"),
    ("bottom_out", "底付き", "セッティング: ストローク・車高・縮み側の減衰"),
    ("kick_ahead", "先読みの突き上げ", "コード: 先読みの斜面（3.3m/s で地面を上げる作り）"),
    ("kick_slope", "坂の突き上げ", "坂の折れ目。縮み側の減衰を弱めると和らぐ"),
    ("bumpy", "細かい凸凹", "コード: 地面のならし方（3cm 未満の跳びにダンパーが反応した）"),
    ("rebound", "伸び側の減衰", "セッティング: 伸び側の減衰比を下げる"),
    ("flight", "地面が離れた", "頂上・段を降りる。物理として自然（ストロークを伸ばすと少し粘る）"),
]


def num(value):
    if value == "" or value is None:
        return math.nan
    return float(value)


def load(path):
    meta = {}
    rows = []
    with open(path, encoding="utf-8") as f:
        lines = []
        for line in f:
            if line.startswith("#"):
                for part in line[1:].split():
                    if "=" in part:
                        key, value = part.split("=", 1)
                        meta[key] = value
            else:
                lines.append(line)
    for row in csv.DictReader(lines):
        rows.append(row)
    return meta, rows


def static_load(meta, wheel):
    return float(meta["static_front"] if wheel.startswith("f") else meta["static_rear"])


def speed_kmh(row):
    return math.hypot(num(row["v_fwd"]), num(row["v_lat"])) * 3.6


def ground_jump(rows, i, wheel):
    """刻み i で地面がその刻みだけ跳んだ量 [m]（符号つき）。跳んでいなければ 0。

    坂を登れば地面は毎刻み同じだけ上がり、坂の入口では上がり方が途中から変わる。どちらも跳びでは
    ない。前の刻みとも次の刻みとも動き方が違う（その刻みだけ飛び出している）ものを跳びと見る。
    """
    if i < 2 or i + 1 >= len(rows):
        return 0.0
    g = [num(rows[k][f"ground_{wheel}"]) for k in (i - 2, i - 1, i, i + 1)]
    if any(math.isnan(v) for v in g):
        return 0.0
    before = g[1] - g[0]
    now = g[2] - g[1]
    after = g[3] - g[2]
    # 前とも後ろとも違う量のうち、小さいほう
    lead = now - before
    trail = now - after
    if lead * trail <= 0.0:
        return 0.0
    return lead if abs(lead) < abs(trail) else trail


def classify(rows, start, end, wheel, stroke):
    """浮いた区間 [start, end) の原因。(原因の鍵, 補足)。"""
    first = rows[start]
    src = first[f"src_{wheel}"]
    if src in ("MISS", "TILTED"):
        return "no_ground", SOURCE_NAMES[src]
    lo = max(1, start - LOOK_BACK)
    # 地面の跳び。いちばん大きく動いた刻みと、そのときの出どころの切り替わり
    worst = 0.0
    worst_text = ""
    worst_src = ""
    for i in range(lo, min(end, len(rows))):
        jump = ground_jump(rows, i, wheel)
        if abs(jump) > abs(worst):
            worst = jump
            s0 = SOURCE_NAMES.get(rows[i - 1][f"src_{wheel}"], "?")
            s1 = SOURCE_NAMES.get(rows[i][f"src_{wheel}"], "?")
            worst_text = f"{jump * 100:+.1f}cm（{s0} → {s1}）"
            worst_src = rows[i][f"src_{wheel}"]
    if abs(worst) >= GROUND_JUMP:
        # 先読みの斜面は 0.25m おきに調べるので、上がったり止まったりしながら上がる。跳びというより突き上げ
        if worst_src == "AHEAD":
            return "kick_ahead", worst_text
        return "ground_jump", worst_text
    if any(num(rows[i][f"gap_{wheel}"]) < 0.0 for i in range(lo, start + 1)):
        return "bottom_out", "直前にバンプストップ"
    # 突き上げ。地面がいちばん速くせり上がった刻みと、その出どころ
    kick = 0.0
    kick_src = ""
    for i in range(lo, start + 1):
        gs = num(rows[i][f"gspeed_{wheel}"])
        if not math.isnan(gs) and gs > kick:
            kick = gs
            kick_src = rows[i][f"src_{wheel}"]
    if kick >= KICK_SPEED:
        key = "kick_ahead" if kick_src == "AHEAD" else "kick_slope"
        return key, f"地面が {kick:.1f}m/s でせり上がった（{SOURCE_NAMES.get(kick_src, '?')}）"
    # 細かい凸凹。直前 0.1 秒で地面の動く速さが大きく揺れていたら、ダンパーはそれに反応している
    recent = [num(rows[i][f"gspeed_{wheel}"]) for i in range(max(1, start - 8), start + 1)]
    recent = [g for g in recent if not math.isnan(g)]
    if recent and max(recent) - min(recent) >= BUMPY_SPREAD:
        return "bumpy", f"直前 0.1 秒で地面の速さが {max(recent) - min(recent):.1f}m/s 揺れた"
    gaps = [num(rows[i][f"gap_{wheel}"]) for i in range(start, end)]
    gaps = [g for g in gaps if not math.isnan(g)]
    if gaps and max(gaps) <= stroke:
        return "rebound", f"タイヤは届く位置（隙間 最大 {max(gaps) * 100:.1f}cm / ストローク {stroke * 100:.0f}cm）"
    far = max(gaps) if gaps else math.nan
    return "flight", f"ストロークの外 {((far - stroke) * 100) if not math.isnan(far) else math.nan:.1f}cm まで離れた"


def find_events(rows, wheel):
    """荷重 0 が続いた区間の (start, end) の並び。"""
    events = []
    start = None
    for i, row in enumerate(rows):
        airborne = num(row[f"load_{wheel}"]) <= 0.0
        if airborne and start is None:
            start = i
        elif not airborne and start is not None:
            events.append((start, i))
            start = None
    if start is not None:
        events.append((start, len(rows)))
    return events


def main():
    # Windows のコンソールでも化けないように
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = sys.argv[1]
    if os.path.isdir(path):
        files = sorted(glob.glob(os.path.join(path, "groundlog-*.csv")))
        if not files:
            print(f"{path} に groundlog-*.csv がありません")
            sys.exit(1)
        path = files[-1]
    meta, rows = load(path)
    if len(rows) < 2:
        print("記録が短すぎます")
        sys.exit(1)
    # 地面の補間（Ctrl+J）を記録の途中で切り替えていたら、入り切りごとに分けてまとめる
    groups = defaultdict(list)
    for r in rows:
        groups[r.get("smooth") or meta.get("smooth", "?")].append(r)
    for mode in sorted(groups, reverse=True):
        label = SMOOTH_NAMES.get(mode, mode)
        if len(groups) > 1:
            print(f"# ==== 地面の補間: {label} ====")
            print()
        report(path, meta, groups[mode], label)
        print()


def slope_roughness(rows):
    """地面の傾き（地面の動く速さ ÷ 車の速さ）が 1 刻みでどれだけ変わったか。ダンパーが感じる凸凹の大きさ。

    速さで割るので、違う速さで走った記録どうしでも比べられる。18km/h 未満と、刻みが続いていないところは除く。
    """
    changes = []
    for i in range(1, len(rows)):
        a, b = rows[i - 1], rows[i]
        if int(b["tick"]) * 4 + int(b["sub"]) != int(a["tick"]) * 4 + int(a["sub"]) + 1:
            continue
        va, vb = speed_kmh(a) / 3.6, speed_kmh(b) / 3.6
        if va < 5.0 or vb < 5.0:
            continue
        for wheel in WHEELS:
            ga, gb = num(a[f"gspeed_{wheel}"]), num(b[f"gspeed_{wheel}"])
            if math.isnan(ga) or math.isnan(gb):
                continue
            changes.append(abs(gb / vb - ga / va))
    if not changes:
        return
    changes.sort()
    mean = sum(changes) / len(changes)
    p95 = changes[int(0.95 * (len(changes) - 1))]
    print(f"地面の傾きの刻みごとの変化（18km/h 以上・4 輪）: 平均 {mean * 100:.2f}% / 95% 点 {p95 * 100:.1f}%")
    print()


def report(path, meta, rows, label):
    stroke = float(meta["stroke"])
    total = len(rows) * DT
    moving = [r for r in rows if speed_kmh(r) > 5.0]

    print(f"# 接地の記録: {os.path.basename(path)}（地面の補間: {label}）")
    print(f"車種 {meta.get('car', '?')} / {total:.1f} 秒（5km/h 以上で {len(moving) * DT:.1f} 秒）"
          f" / ストローク {stroke * 100:.0f}cm"
          f" / 減衰比 前 縮み{meta.get('damp_bump_front')} 伸び{meta.get('damp_rebound_front')}"
          f" 後 縮み{meta.get('damp_bump_rear')} 伸び{meta.get('damp_rebound_rear')}")
    print()

    # 輪ごとの浮いていた割合と、そのとき地面がどこから来ていたか
    print("## 浮いていた時間（5km/h 以上）")
    by_source = defaultdict(lambda: [0, 0])
    for wheel in WHEELS:
        air = sum(1 for r in moving if num(r[f"load_{wheel}"]) <= 0.0)
        share = 100.0 * air / max(1, len(moving))
        print(f"- {WHEEL_NAMES[wheel]}: {share:.1f}%")
        for r in moving:
            s = r[f"src_{wheel}"]
            by_source[s][0] += 1
            if num(r[f"load_{wheel}"]) <= 0.0:
                by_source[s][1] += 1
    print()
    slope_roughness(rows)
    print("地面の出どころ別（4 輪ぶんの刻みを合算）:")
    print()
    print("| 出どころ | 走っていた割合 | そのうち浮いていた割合 |")
    print("|---|---:|---:|")
    all_samples = sum(v[0] for v in by_source.values())
    for s, (n, air) in sorted(by_source.items(), key=lambda kv: -kv[1][0]):
        print(f"| {SOURCE_NAMES.get(s, s)} | {100.0 * n / max(1, all_samples):.1f}% | {100.0 * air / max(1, n):.1f}% |")
    print()

    # 浮いた区間を原因ごとに
    events = []
    flicker = 0
    for wheel in WHEELS:
        for start, end in find_events(rows, wheel):
            if speed_kmh(rows[start]) <= 5.0:
                continue
            if end - start < MIN_EVENT:
                flicker += 1
                continue
            cause, detail = classify(rows, start, end, wheel, stroke)
            events.append((wheel, start, end, cause, detail))

    print("## 浮いた原因")
    print()
    print("| 原因 | 回数 | 合計 | どこを直すか |")
    print("|---|---:|---:|---|")
    for key, name, hint in CAUSES:
        mine = [e for e in events if e[3] == key]
        if not mine:
            continue
        seconds = sum(e[2] - e[1] for e in mine) * DT
        print(f"| {name} | {len(mine)} | {seconds:.2f} 秒 | {hint} |")
    print()
    print(f"1 刻みだけのちらつき（{MIN_EVENT} 刻み未満）: {flicker} 回")
    print()

    # 跳ね（荷重が停車時の 4 倍を超えた）
    spikes = defaultdict(int)
    spike_count = 0
    for wheel in WHEELS:
        limit = static_load(meta, wheel) * SPIKE_FACTOR
        for i in range(1, len(rows)):
            if num(rows[i][f"load_{wheel}"]) > limit and num(rows[i - 1][f"load_{wheel}"]) <= limit:
                spike_count += 1
                spikes[SOURCE_NAMES.get(rows[i][f"src_{wheel}"], "?")] += 1
    print(f"## 跳ね（荷重が停車時の {SPIKE_FACTOR:.0f} 倍を超えた）: {spike_count} 回")
    if spikes:
        print()
        print("そのときの地面の出どころ: " + "、".join(f"{k} {v} 回" for k, v in sorted(spikes.items(), key=lambda kv: -kv[1])))
    print()

    # 地面の跳び（浮いたかどうかに関係なく、地面の作り方の問題を全部拾う）
    jumps = defaultdict(lambda: [0, 0.0])
    for wheel in WHEELS:
        for i in range(1, len(rows)):
            jump = ground_jump(rows, i, wheel)
            if abs(jump) < GROUND_JUMP:
                continue
            key = f"{SOURCE_NAMES.get(rows[i - 1][f'src_{wheel}'], '?')} → {SOURCE_NAMES.get(rows[i][f'src_{wheel}'], '?')}"
            jumps[key][0] += 1
            jumps[key][1] = max(jumps[key][1], abs(jump))
    print(f"## 地面がその刻みだけ {GROUND_JUMP * 100:.0f}cm 以上跳んだ: {sum(v[0] for v in jumps.values())} 回")
    if jumps:
        print()
        print("| 出どころの切り替わり | 回数 | 最大 |")
        print("|---|---:|---:|")
        for key, (n, worst) in sorted(jumps.items(), key=lambda kv: -kv[1][0]):
            print(f"| {key} | {n} | {worst * 100:.1f}cm |")
    print()

    # 長かった浮きを場所つきで
    print("## 長かった浮き（上位 15）")
    print()
    print("| 時刻 | 輪 | 長さ | 速さ | 横滑り | 場所 (x, y, z) | 原因 | 詳しく |")
    print("|---:|---|---:|---:|---:|---|---|---|")
    names = {key: name for key, name, _ in CAUSES}
    for wheel, start, end, cause, detail in sorted(events, key=lambda e: -(e[2] - e[1]))[:15]:
        r = rows[start]
        t = (start) * DT
        where = f"{float(r['x']):.0f}, {float(r['y']):.0f}, {float(r['z']):.0f}"
        drift = num(r["slip"])
        print(f"| {t:.2f}s | {WHEEL_NAMES[wheel]} | {(end - start) * DT:.2f}s | {speed_kmh(r):.0f}km/h"
              f" | {drift:+.0f}° | {where} | {names[cause]} | {detail} |")
    print()

    # ドリフト中かどうかで分ける（先読みが重心の速度を使っている影響を見る）
    drifting = [e for e in events if abs(num(rows[e[1]]["slip"])) > 10.0]
    if events:
        print(f"横滑り 10° を超えていた浮き: {len(drifting)} / {len(events)} 回")



if __name__ == "__main__":
    main()
