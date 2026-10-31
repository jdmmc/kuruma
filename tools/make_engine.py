# -*- coding: utf-8 -*-
"""エンジンの排気音を合成する。種類ごと（ENGINES）に、回転数ごとの素材を作る。

    python tools/make_engine.py                       # すべての種類を書き出し、sounds.json を揃える
    python tools/make_engine.py preview <種類> <wav>   # 試聴用に、空ぶかしをなぞった wav も書く

書き出すもの（core/src/main/resources/assets/kurumamod/sounds/）:

- engine_<種類>_<rpm>_lift.ogg … その回転数で、アクセルを抜いているとき（とアイドル）
- engine_<種類>_<rpm>_load.ogg … その回転数で、踏んでいるとき

種類（カーパックの {@code sounds.engine.type} に書く名前。KurumaSounds.ENGINE_TYPES と揃える）:

- i4_turbo … 直 4 ターボ（SR20DET を手本）。既定
- i4       … 直 4 NA。タービンが無く高回転で澄む
- i6       … 直 6 ターボ（RB・JZ のような滑らかなストレート 6）
- flat6    … 水平対向 6 NA（911）。左右のバンクが交互に吹いて 1.5 次が立つ
- v8       … クロスプレーン V8。バンクごとに不等間隔になって「ドロドロ」する
- diesel4  … ディーゼルターボ（プラド）。バスやトラックのような低い「ゴロゴロ」と「ガラガラ」

<b>回転数ごとに素材を分けるのは、1 本の素材の音程を上げ下げすると、排気管やマフラーの
響き（フォルマント）まで一緒に動くから。</b>実際のエンジンは、回転数で爆発の間隔が変わっても
排気管の響きの高さは変わらない。以前の排気音（exhaust.ogg を 0.65〜3.9 倍で鳴らす）は
これが崩れていて作り物っぽく、しかもピッチの壁（0.5〜2.0）のせいでレブでも 144Hz までしか
上がらなかった（直 4 の 7000rpm は 233Hz）。ここでは回転数の近い素材 2 本を、音程の
差が 1.8 倍以内に収まるようにして入れ替える（CarSound）。

音の組み立て（すべて 1 周を周期として作るので、継ぎ目が出ない）:

1. 爆発 1 回ごとに排気の脈動を 1 つ置く（音程 = rpm × 気筒数 / 120 Hz）。気筒ごとに少しずつ
   違う強さと時刻を持たせ（0.5・1・1.5 次の成分になる）、1 回ごとにもわずかに揺らす
   （同じ波形を並べるだけだと単調になる）。V 型・水平対向はバンクごとに管の長さが違うので、
   バンクごとの遅れと強さも持たせる
2. 脈動の立ち上がりの鋭さで高域の荒さが決まる。踏んでいるときは鋭く、抜いているとき
   （燃えていない。ポンプとして空気を押し出すだけ）は弱く鈍い
3. 脈動に乗った乱流の雑音（ざらつき・「fizz」）を足す。踏んでいるほど多い。ディーゼルは
   燃焼の打撃（カラカラ）も足す
4. 排気系の響きを周波数領域で掛ける。回転数によらず同じ高さ:
   タービン（脈動を丸める。NA には無い）、排気管の共鳴、マフラーの抜け
5. A 特性の大きさを揃え、尖ったピークを丸める。どれだけ前に出るかはコード側（CarSound）が決める
"""
import json
import sys

import numpy as np

sys.path.insert(0, "tools")
import make_sounds as ms  # noqa: E402

SR = ms.SR

# 素材を作る回転数。隣どうしの比が 1.8 倍以内（ピッチの壁 0.5〜2.0 の内側で入れ替えられる）。
# KurumaSounds.ENGINE_BANDS と揃える
BANDS = (900, 1600, 2800, 4500, 7000)
# ループの長さの目安 [s]。この中に 2 回転がちょうど整数回入るよう詰める
LOOP_SECONDS = 1.2
# A 特性の大きさ。低音の多い音は A 特性では小さく出るので、ピークの上限（SOFT_CEILING）に
# 当たらないぎりぎりまで下げてある（-23.5dB では 900rpm が 8dB 届かなかった）
LOUDNESS_DB = -26.0
# これを超えるピークは tanh で丸める。脈動は尖っていて（波高率 4〜5）、そのままでは大きさを稼げない
SOFT_CEILING = 0.92

# 種類ごとの組み立て。
#
# gain / phase … 気筒ごとの強さと爆発の時刻のずれ（間隔に対する割合）。0.5・1・1.5 次の成分になり、
#   「ドロドロ」した重み（burble）を作る。<b>大きくしすぎると「パタパタ」する</b>（直 4 ターボで
#   0.80〜1.16・時刻 ±5% にしたら 1 発ずつ粒立って聞こえた）
# banks … 爆発の順にどちらのバンクから吹くか。bank_delay / bank_gain がバンクごとの遅れと強さ
# resonances … 排気系の響き (Hz, 強さ, Q)。下 3 つが深みの元: 排気管全体の 1/4 波長
#   （L = c/4f、高温のガスで c ≈ 500m/s）とその 3 倍、サイレンサーの胴（ヘルムホルツ）
# turbine … タービンが鋭さを丸める上の端 [Hz]。NA は None
# muffler … マフラーの抜け [Hz]（踏み, 抜き）
# fizz … 乱流の雑音の量（踏み, 抜き）
# rise / decay … 脈動の立ち上がり [s] と、減衰の長さ（爆発の間隔に対する割合）（踏み, 抜き）。
#   減衰は間隔の半分以上にして次の脈動と重ねる。短いと 1 発ずつ途切れて「パタパタ」する
# knock … ディーゼルの燃焼の打撃の量（踏み, 抜き）。0 なら無し
ENGINES = {
    # SR20DET を手本にした直 4 ターボ。排気管 3.2m で 39Hz
    "i4_turbo": dict(
        cylinders=4,
        gain=(1.00, 0.91, 1.08, 0.95), phase=(0.0, 0.02, -0.012, 0.025),
        banks=(0,), bank_delay=(0.0,), bank_gain=(1.0,),
        resonances=((39.0, 1.8, 1.4), (70.0, 2.4, 1.8), (117.0, 1.8, 2.2),
                    (300.0, 0.7, 2.5), (780.0, 0.35, 3.0), (1900.0, 0.2, 2.2)),
        turbine=2200.0, muffler=(3800.0, 1800.0), fizz=(0.28, 0.10),
        rise=(0.0009, 0.0020), decay=(0.55, 0.65), knock=(0.0, 0.0), seed=100),
    # 直 4 NA。タービンが無いぶん鋭さが残り、管も短く（2.6m で 48Hz）響きが上へ寄る
    "i4": dict(
        cylinders=4,
        gain=(1.00, 0.94, 1.06, 0.96), phase=(0.0, 0.015, -0.01, 0.02),
        banks=(0,), bank_delay=(0.0,), bank_gain=(1.0,),
        resonances=((48.0, 1.5, 1.4), (85.0, 1.8, 1.8), (144.0, 1.7, 2.2),
                    (380.0, 1.0, 2.5), (950.0, 0.55, 3.0), (2300.0, 0.3, 2.2)),
        turbine=None, muffler=(5200.0, 2400.0), fizz=(0.34, 0.12),
        rise=(0.0006, 0.0016), decay=(0.50, 0.60), knock=(0.0, 0.0), seed=200),
    # 直 6 ターボ。等間隔で気筒の差も小さく、滑らか。管は長め（3.4m で 37Hz）
    "i6": dict(
        cylinders=6,
        gain=(1.00, 0.97, 1.03, 0.98, 1.02, 0.99), phase=(0.0, 0.01, -0.006, 0.008, -0.01, 0.005),
        banks=(0,), bank_delay=(0.0,), bank_gain=(1.0,),
        resonances=((37.0, 1.6, 1.4), (66.0, 2.0, 1.8), (110.0, 1.6, 2.2),
                    (280.0, 0.8, 2.5), (700.0, 0.4, 3.0), (1700.0, 0.25, 2.2)),
        turbine=2600.0, muffler=(4200.0, 2000.0), fizz=(0.25, 0.10),
        rise=(0.0008, 0.0018), decay=(0.60, 0.70), knock=(0.0, 0.0), seed=300),
    # 水平対向 6 NA。爆発順 1-6-2-4-3-5 で左右のバンクが交互に吹き、右の管が少し長い
    # ——1 回転に 1.5 回の揺れ（1.5 次）が立つ。管は 3.0m（42Hz）
    "flat6": dict(
        cylinders=6,
        gain=(1.00, 0.96, 1.04, 0.97, 1.03, 0.98), phase=(0.0, 0.01, -0.008, 0.012, -0.01, 0.006),
        banks=(0, 1), bank_delay=(0.0, 0.03), bank_gain=(1.0, 0.90),
        resonances=((42.0, 1.5, 1.4), (78.0, 1.8, 1.8), (126.0, 1.5, 2.2),
                    (330.0, 1.0, 2.5), (880.0, 0.6, 3.0), (2100.0, 0.35, 2.2)),
        turbine=None, muffler=(5500.0, 2500.0), fizz=(0.32, 0.12),
        rise=(0.0006, 0.0016), decay=(0.50, 0.60), knock=(0.0, 0.0), seed=400),
    # クロスプレーン V8 NA。クランクでは 90 度の等間隔でも、バンクごとに見ると L R L L R L R R と
    # 不等間隔になり、0.5・1 次の「ドロドロ」が強く出る。管は 3.8m（33Hz）
    "v8": dict(
        cylinders=8,
        gain=(1.00, 0.95, 1.05, 0.97, 1.02, 0.96, 1.04, 0.98),
        phase=(0.0, 0.01, -0.008, 0.012, -0.01, 0.006, 0.008, -0.006),
        banks=(0, 1, 0, 0, 1, 0, 1, 1), bank_delay=(0.0, 0.05), bank_gain=(1.0, 0.86),
        resonances=((33.0, 2.0, 1.3), (60.0, 2.4, 1.7), (100.0, 1.8, 2.2),
                    (250.0, 0.8, 2.5), (650.0, 0.4, 3.0), (1600.0, 0.2, 2.2)),
        turbine=None, muffler=(3500.0, 1500.0), fizz=(0.25, 0.10),
        rise=(0.0008, 0.0018), decay=(0.60, 0.70), knock=(0.0, 0.0), seed=500),
    # ディーゼルターボ。バスやトラックのような、低く太い「ゴロゴロ」に低めの「ガラガラ」が乗る音。
    # 排気量が大きく管も長い（5m で 26Hz、サイレンサーの胴 48Hz）。タービンとマフラーで上はかなり丸い。
    # 気筒のばらつきは少し強めにして、重くばらついた拍を出す。
    # <b>打撃を強く・高くすると不快</b>——0.55・1.5〜6kHz・減衰 0.8ms にしたら耳に刺さった（重心 372〜592Hz）。
    # 0.8〜3kHz・減衰 1.5ms の「コロコロ」は乗用車の直 4 ターボ寄りで軽かったので、
    # 0.4〜1.8kHz・立ち上がり 0.4ms・減衰 3ms の低い「ガラガラ」にした
    "diesel4": dict(
        cylinders=4,
        gain=(1.00, 0.88, 1.10, 0.93), phase=(0.0, 0.025, -0.018, 0.03),
        banks=(0,), bank_delay=(0.0,), bank_gain=(1.0,),
        resonances=((26.0, 2.2, 1.3), (48.0, 2.6, 1.6), (80.0, 2.0, 2.0),
                    (210.0, 0.8, 2.5), (520.0, 0.35, 3.0), (1300.0, 0.15, 2.2)),
        turbine=1400.0, muffler=(1600.0, 1100.0), fizz=(0.10, 0.05),
        rise=(0.0012, 0.0024), decay=(0.60, 0.70), knock=(0.30, 0.12),
        knock_shape=(0.0004, 0.003), knock_band=(400.0, 1800.0), seed=600),
}
DEFAULT_TYPE = "i4_turbo"


def loop_length(rpm):
    """ループの長さ [サンプル] と、その長さに合わせた実際の回転数。

    2 回転がちょうど整数回入る長さにする。サンプル数に丸めたぶん回転数がわずかにずれるので、
    実際の値を返す（CarSound はこの回転数を基準に音程を決める）。
    """
    cycle = 120.0 / rpm                      # 2 回転 [s]
    cycles = max(1, round(LOOP_SECONDS / cycle))
    n = int(round(cycles * cycle * SR))
    return n, cycles * 120.0 * SR / n


def pulse_train(n, rpm, load, rng, spec):
    """爆発ごとの排気の脈動を並べた波形（周期的）と、その包絡と、燃焼の打撃。"""
    cylinders = spec["cylinders"]
    interval = 120.0 / rpm / cylinders       # 爆発の間隔 [s]
    firings = int(round(n / SR / interval))
    t = np.arange(n) / SR
    out = np.zeros(n)
    envelope = np.zeros(n)
    knock = np.zeros(n)
    mode = 0 if load else 1
    rise = spec["rise"][mode]
    decay = np.clip(spec["decay"][mode] * interval, 0.003, 0.035)
    jitter = 0.03 if load else 0.05
    banks = spec["banks"]
    for k in range(firings):
        cylinder = k % cylinders
        bank = banks[k % len(banks)]
        gain = (spec["gain"][cylinder] * spec["bank_gain"][bank]
                * (1.0 + jitter * rng.standard_normal()))
        # 爆発の時刻もわずかに揺らす（間隔の 1%）。バンクの管の長さのぶん遅れて届く
        start = (k + spec["phase"][cylinder] + spec["bank_delay"][bank]
                 + 0.01 * rng.standard_normal()) * interval
        # ループの端をまたぐ脈動は先頭へ回り込ませる
        dt = (t - start) % (n / SR)
        shape = (1.0 - np.exp(-dt / rise)) * np.exp(-dt / decay)
        shape[dt > 8 * decay] = 0.0
        out += gain * shape
        envelope += gain * np.exp(-dt / (decay * 1.6)) * (dt < 10 * decay)
        if spec["knock"][mode] > 0:
            # 燃焼の打撃。短い衝撃（鋭くすると耳に刺さる）。立ち上がりと減衰は種類ごと
            knock_rise, knock_decay = spec["knock_shape"]
            hit = (1.0 - np.exp(-dt / knock_rise)) * np.exp(-dt / knock_decay)
            hit[dt > 10 * knock_decay] = 0.0
            knock += gain * hit
    out -= out.mean()
    return out, envelope, knock


def exhaust_response(freq, load, spec):
    """排気系（タービン・排気管・マフラー）の周波数特性。回転数によらず固定。"""
    f = np.maximum(freq, 1e-3)
    # 直流だけ落とす（1 次の 20Hz）。<b>低音は削りすぎない</b>——45Hz の 2 次で切っていた頃は、
    # アイドル〜中回転の基音（30〜90Hz）が消えて軽かった
    response = (f / 20.0) / np.sqrt(1 + (f / 20.0) ** 2)
    # タービンが脈動の鋭さを丸める
    if spec["turbine"]:
        response = response / np.sqrt(1 + (f / spec["turbine"]) ** 2)
    pipe = np.ones_like(f)
    for center, gain, q in spec["resonances"]:
        pipe += gain / (1 + q * q * (f / center - center / f) ** 2)
    cutoff = spec["muffler"][0 if load else 1]
    muffler = 1 / np.sqrt(1 + (f / cutoff) ** 4)
    return response * pipe * muffler


def engine(kind, rpm, load, seed):
    spec = ENGINES[kind]
    rng = np.random.default_rng(seed)
    n, actual = loop_length(rpm)
    pulses, envelope, knock = pulse_train(n, rpm, load, rng, spec)
    pulses /= np.std(pulses)
    # 脈動に乗った乱流の雑音（ざらつき）。踏んでいるほど多い
    noise = ms.periodic_noise(n, 500.0, 6000.0, rng)
    envelope /= np.max(envelope)
    mode = 0 if load else 1
    fizz = noise * envelope * spec["fizz"][mode]
    # 抜いているときは燃えていないので、脈動そのものが弱い
    raw = pulses * (1.0 if load else 0.45) + fizz
    freq = np.fft.rfftfreq(n, 1 / SR)
    spectrum = np.fft.rfft(raw) * exhaust_response(freq, load, spec)
    if spec["knock"][mode] > 0:
        # 打撃は種類ごとの帯だけ残す（排気系の響きは通さない。ブロックから直接出る音）
        knock_spec = np.fft.rfft(knock / max(np.std(knock), 1e-9))
        lo, hi = spec["knock_band"]
        band = (freq / lo) ** 2 / (1 + (freq / lo) ** 2) / (1 + (freq / hi) ** 4)
        spectrum = spectrum + knock_spec * band * spec["knock"][mode] * np.std(np.abs(spectrum)) / max(
            np.std(np.abs(knock_spec * band)), 1e-9)
    out = np.fft.irfft(spectrum, n)
    # 大きさを揃えてからピークを丸める。丸めると大きさが少し下がるので 3 回寄せ直す
    for _ in range(3):
        out *= 10 ** (LOUDNESS_DB / 20) / ms.loudness(out)
        out = SOFT_CEILING * np.tanh(out / SOFT_CEILING)
    return out, actual


def file_name(kind, rpm, load):
    return "engine_%s_%d_%s" % (kind, rpm, "load" if load else "lift")


def build(kinds):
    rows = []
    for kind in kinds:
        seed = ENGINES[kind]["seed"]
        for i, rpm in enumerate(BANDS):
            for load in (False, True):
                data, actual = engine(kind, rpm, load, seed=seed + 2 * i + int(load))
                name = file_name(kind, rpm, load) + ".ogg"
                ms.write_ogg(name, data)
                rows.append((kind, rpm, load, name, actual, data))
    return rows


def update_sounds_json():
    """sounds.json の car_engine_* を、ENGINES と BANDS に合わせて書き直す（ほかの項目はそのまま）。"""
    path = "core/src/main/resources/assets/kurumamod/sounds.json"
    with open(path, encoding="utf-8") as f:
        sounds = json.load(f)
    kept = {k: v for k, v in sounds.items() if not k.startswith("car_engine_")}
    engines = {}
    for kind in ENGINES:
        for rpm in BANDS:
            for load in (False, True):
                name = file_name(kind, rpm, load)
                engines["car_" + name] = {
                    "category": "neutral",
                    "subtitle": "subtitles.kurumamod.car_exhaust",
                    "sounds": [{"name": "kurumamod:" + name, "stream": False, "attenuation_distance": 64}],
                }
    # 並びは、エンジンを先頭にまとめ、ほかは元の順
    merged = dict(engines)
    merged.update(kept)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(merged, f, ensure_ascii=False, indent=2)
        f.write("\n")


def preview(rows, kind, path):
    """空ぶかし（アイドル → 7000rpm → アイドル）を CarSound と同じ入れ替え方でなぞった wav。"""
    import soundfile as sf
    bands = {}
    for row_kind, rpm, load, _, actual, data in rows:
        if row_kind == kind:
            bands.setdefault(rpm, {})["load" if load else "lift"] = (actual, data)
    seconds = 6.0
    n = int(seconds * SR)
    t = np.arange(n) / SR
    # 0.5 秒アイドル → 1.2 秒で踏み上げ → 0.8 秒レブ付近 → 抜いて 1.5 秒で落ちる → アイドル
    rpm = np.interp(t, [0, 0.5, 1.7, 2.5, 2.6, 4.1, 6.0], [850, 850, 7000, 7000, 6900, 850, 850])
    throttle = np.interp(t, [0, 0.5, 0.6, 2.5, 2.6, 6.0], [0, 0, 1, 1, 0, 0])
    out = np.zeros(n)
    keys = sorted(bands)
    for i, band in enumerate(keys):
        lower = keys[i - 1] if i > 0 else None
        upper = keys[i + 1] if i + 1 < len(keys) else None
        # 対数で隣の素材と入れ替える（CarSound と同じ）
        w = np.ones(n)
        if lower is not None:
            w *= np.clip(np.log(rpm / lower) / np.log(band / lower), 0, 1)
        if upper is not None:
            w *= np.clip(np.log(upper / rpm) / np.log(upper / band), 0, 1)
        w = np.sqrt(w)
        for mode, mix in (("lift", np.sqrt(1 - throttle)), ("load", np.sqrt(throttle))):
            actual, data = bands[band][mode]
            phase = np.cumsum(rpm / actual) % len(data)
            i0 = phase.astype(int)
            frac = phase - i0
            voice = data[i0] * (1 - frac) + data[(i0 + 1) % len(data)] * frac
            out += voice * w * mix
    rev = (rpm - 800) / 6200
    level = 0.34 + 0.14 * rev + throttle * (0.36 + 0.10 * rev)
    out *= level
    sf.write(path, (out / max(1.0, np.max(np.abs(out)) / 0.95)).astype(np.float32), SR)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "preview":
        kind = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_TYPE
        preview(build([kind]), kind, sys.argv[3] if len(sys.argv) > 3 else "engine_preview.wav")
        sys.exit(0)
    rows = build(list(ENGINES))
    update_sounds_json()
    for kind, rpm, load, name, actual, data in rows:
        spec = np.abs(np.fft.rfft(data)) ** 2
        freq = np.fft.rfftfreq(len(data), 1 / SR)
        centroid = (freq * spec).sum() / spec.sum()
        print("%-28s rpm %.1f  重心 %4.0fHz  ピーク %.2f" % (name, actual, centroid, np.max(np.abs(data))))
