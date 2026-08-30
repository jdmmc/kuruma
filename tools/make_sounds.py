# -*- coding: utf-8 -*-
"""衝突音を合成し、低回転用の排気音と、繰り返しの目立たないスキール音を作る。

- crash.ogg       … 金属がひしゃげる一撃。広帯域のノイズ＋減衰する金属の倍音
- exhaust_high.ogg … exhaust.ogg を 1 オクターブ上へ縮めたもの（レブ側を伸ばすため）
- slip.ogg        … sample/slip.mp3 の芯 0.45 秒を、揺らしながら 3.15 秒へ伸ばしたもの
- race_light.ogg  … スタート信号が 1 つ点く音。低い短音
- race_start.ogg  … 消灯＝号砲。1 オクターブ上を長めに
- race_split.ogg  … チェックポイント通過。ごく短い高音
- race_finish.ogg … ゴール。上がっていく 3 音

Minecraft の制約に合わせて **48kHz モノラルの OGG Vorbis** で書く
（ステレオだと 3D 定位が掛からない）。
"""
import sys

import numpy as np
import soundfile as sf

SR = 48000
OUT = "src/main/resources/assets/kurumamod/sounds/"
SAMPLE = "sample/"


def write_ogg(name, data, chunk_seconds=1.0):
    """OGG Vorbis で書き出す。**1 回で全部渡さず、刻んで書くこと。**

    `sf.write` に長い配列を一度に渡すと、<b>libsndfile の中でスタックが溢れて
    Python ごと落ちる</b>（Windows fatal exception: stack overflow。終了コード 127 だけが
    返り、例外もトレースバックも出ないので原因が分かりにくい）。実測では 48kHz モノラルで
    <b>10 秒までは通り、11 秒で落ちる</b>。既存の音がどれも数秒だったので表に出ていなかった。

    `SoundFile` を開いて 1 秒ずつ書けば 25 秒でも問題ない。長さに依らずこちらを通す。
    """
    data = np.asarray(data, dtype=np.float32)
    step = max(1, int(SR * chunk_seconds))
    with sf.SoundFile(OUT + name, "w", samplerate=SR, channels=1,
                      format="OGG", subtype="VORBIS") as out:
        for i in range(0, len(data), step):
            out.write(data[i:i + step])
    return len(data) / SR


def crash():
    dur = 0.85
    n = int(SR * dur)
    t = np.arange(n) / SR
    rng = np.random.default_rng(20260807)

    # 広帯域のノイズ。立ち上がり 2ms、減衰 0.18s。板金がひしゃげる音の芯
    noise = rng.standard_normal(n)
    # 軽く低域寄りにする（1 次のローパスを 2 回）
    for _ in range(2):
        a = 0.35
        filtered = np.empty_like(noise)
        acc = 0.0
        for i in range(n):
            acc = a * noise[i] + (1 - a) * acc
            filtered[i] = acc
        noise = filtered
    noise /= np.max(np.abs(noise))
    attack = np.clip(t / 0.002, 0, 1)
    body = noise * attack * np.exp(-t / 0.18)

    # 金属の倍音。減衰の速さを変えて重ねると「カン」ではなく「ガシャン」になる
    partials = [(147.0, 0.55, 0.9), (311.0, 0.40, 0.6), (622.0, 0.28, 0.45),
                (1043.0, 0.18, 0.3), (1867.0, 0.12, 0.2)]
    ring = np.zeros(n)
    for freq, decay, amp in partials:
        phase = rng.uniform(0, 2 * np.pi)
        ring += amp * np.sin(2 * np.pi * freq * t + phase) * np.exp(-t / decay)
    ring *= attack
    ring /= np.max(np.abs(ring))

    # ガラスが散る細かい成分。少し遅れて始める
    glass = rng.standard_normal(n) * np.exp(-np.maximum(t - 0.03, 0) / 0.25)
    glass *= np.sin(2 * np.pi * 5200 * t) * 0.15
    glass[t < 0.03] = 0.0

    mix = 0.75 * body + 0.5 * ring + 0.35 * glass
    mix /= np.max(np.abs(mix)) * 1.05
    # 末尾を落として切れ目を消す
    tail = int(SR * 0.05)
    mix[-tail:] *= np.linspace(1, 0, tail)
    write_ogg("crash.ogg", mix)
    return dur


def exhaust_high(octaves=-1.0):
    """排気音を 1 オクターブ上へ。長さは半分になる。

    ピッチの倍率には 0.5〜2.0 の壁があるので、音源が 1 つだとレブの伸びが足りない。
    **伸ばすのは上側**であって下側ではない——1 オクターブ下の音源を足すと、
    アイドルが元音源の 4 分の 1 の音程になって<b>聞こえなくなる</b>（実際にそうなった）。
    アイドルは音源 1 枚だった頃と同じ高さに置いたまま、レブ側だけを継ぎ足す。
    元がループ済みなら、伸縮してもループのままなのでつなぎ直しは要らない。
    """
    data, sr = sf.read(OUT + "exhaust.ogg", dtype="float64", always_2d=False)
    ratio = 2.0 ** octaves
    n = int(len(data) * ratio)
    src = np.arange(n) / ratio
    # 端をまたぐ補間はループの先頭へ回り込ませる（継ぎ目を作らない）
    i0 = np.floor(src).astype(int)
    frac = src - i0
    out = data[i0 % len(data)] * (1 - frac) + data[(i0 + 1) % len(data)] * frac
    assert sr == SR, sr
    return write_ogg("exhaust_high.ogg", out)


def read_loop(src, pitch):
    """ループ音を可変ピッチで読む。末尾は先頭へ回り込む。"""
    phase = np.cumsum(pitch)
    idx = phase % len(src)
    i0 = np.floor(idx).astype(np.int64)
    frac = idx - i0
    return src[i0] * (1 - frac) + src[(i0 + 1) % len(src)] * frac


def slip(core_seconds=0.45, laps=7):
    """スキール音。短い芯を、ゆっくり揺らしながら繰り返しの見えない長さへ伸ばす。

    元の素材（sample/slip.mp3）は 1 秒しかなく、後半は減衰しているので使えるのは
    頭の 0.45 秒だけ。これをそのままループにすると **1 秒に 2.2 回**の周期になり、
    人の耳が振幅変調にいちばん敏感な 2〜8Hz のど真ん中に入る。限界旋回では 8.7 秒
    鳴りっぱなしになるので、同じ 0.45 秒が 21 周して「ワンワンワン」と聞こえる。

    <b>ただ長いファイルにしても効かない。</b>同じ 0.45 秒を並べただけでは、耳が拾う
    周期は 0.45 秒のままで、8 秒のファイルにしても「聴いたことのある音」の割合は
    1% も動かなかった。**中でゆっくり揺らして同じ場所が二度と来ないようにして初めて
    長さが効く**（測定は scratchpad の novelty.py / pulse.py）。

    長さは芯のちょうど {laps} 倍にしてある。こうすると読み進めた総量が芯の長さの
    整数倍にぴったり収まるので、**クロスフェードで誤魔化さずに継ぎ目が消える**。
    """
    data, sr = sf.read(SAMPLE + "slip.mp3", dtype="float64", always_2d=True)
    assert sr == SR, sr
    core = data.mean(axis=1)[:int(SR * core_seconds)]        # モノラルに落として芯だけ
    core = crossfade_loop(core, 0.05)

    n = len(core) * laps
    t = np.arange(n) / SR
    seconds = n / SR

    def lfo(cycles, weights, phases):
        """ループ長のちょうど整数分の 1 の周期で揺らす。揺らぎ自体が継ぎ目を作らない。"""
        return sum(w * np.sin(2 * np.pi * c * t / seconds + p)
                   for c, w, p in zip(cycles, weights, phases))

    # 音程の揺らぎ。実際のタイヤの鳴きも一定の高さでは鳴らない
    pitch = 1.0 + 0.10 * lfo([1, 2, 3], [0.55, 0.30, 0.15], [0.0, 2.1, 4.7])
    # 読み進めた総量を芯の長さの整数倍へ合わせる。ここが合えば末尾と先頭が繋がる
    pitch *= n / pitch.sum()
    amp = 1.0 + 0.40 * lfo([1, 3, 5], [0.55, 0.30, 0.15], [1.3, 5.2, 0.4])

    out = read_loop(core, pitch) * amp
    out /= np.max(np.abs(out)) / 0.81
    write_ogg("slip.ogg", out)
    return seconds


def crossfade_loop(data, fade_seconds):
    """末尾を先頭へクロスフェードしてループにする。**切ってから掛ける**こと。"""
    fade = int(SR * fade_seconds)
    out = data.copy()
    ramp = np.linspace(0.0, 1.0, fade)
    out[-fade:] = out[-fade:] * (1 - ramp) + out[:fade] * ramp
    return out


def rms_envelope(data, window):
    """滑る窓の RMS。窓の長さぶんの二乗和を累積和で出すので長さに依らず速い。"""
    w = max(1, int(SR * window))
    pad = w // 2
    padded = np.pad(data ** 2, (pad, w - pad), mode="reflect")
    acc = np.concatenate(([0.0], np.cumsum(padded)))
    return np.sqrt(np.maximum(acc[w:w + len(data)] - acc[:len(data)], 0.0) / w)


def a_weighting(freq):
    """A 特性の重み。人の耳の感度に合わせて、低域と超高域を落として測るための係数。"""
    f2 = np.maximum(freq, 1e-6) ** 2
    num = (12194.0 ** 2) * f2 * f2
    den = ((f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2))
           * (f2 + 12194.0 ** 2))
    return num / den / 0.7943282347            # 1kHz でちょうど 1 になるよう揃える


def loudness(data):
    """A 特性で重みを付けた RMS。<b>耳で感じる大きさの物差し。</b>

    <b>ピークで揃えても、RMS で揃えても、聞いた大きさは揃わない。</b>実測（どちらも
    ピーク 0.8 に正規化したもの）:

    ============= ====== ======= ========
    音            ピーク RMS     A-RMS
    ============= ====== ======= ========
    gravel.ogg    0.82   0.2233  -22.4 dB
    sand.ogg      0.80   0.1632  -16.4 dB
    ============= ====== ======= ========

    <b>RMS では砂のほうが小さいのに、耳では 6dB 大きい。</b>砂は成分が 7.7kHz あたりに
    寄っていて（砂利は 4.0kHz）、そこが耳のいちばん敏感なところだから。素材ごとに
    音色が違う以上、揃えるならここで揃えるしかない。
    """
    n = len(data)
    spec = np.abs(np.fft.rfft(data)) / n
    weight = a_weighting(np.fft.rfftfreq(n, 1 / SR))
    weighted = spec * weight
    # 片側スペクトルなので中身を 2 倍する（DC と Nyquist は 1 本しかないのでそのまま）
    return float(np.sqrt(2 * np.sum(weighted[1:-1] ** 2)
                         + weighted[0] ** 2 + weighted[-1] ** 2))


def flatten(data, window=0.25, floor=0.35):
    """滑る窓の RMS で割って振幅を平らにする。

    <b>窓を短くしすぎないこと。</b>「ゆっくりしたうねりだけを取り除く」ための処理なので、
    窓が石の当たる間隔より短くなると<b>一粒ごとの立ち上がりまで潰して</b>、砂利がただの
    ホワイトノイズになる。実測の膝は 0.25 秒（`import_loop` の表）。

    `floor` は「静かなところを無理に持ち上げない」ための下限で、RMS の中央値に対する割合。
    これが無いと、素材に残った無音がフルゲインまで増幅されて<b>そこだけノイズの塊になる</b>。
    """
    env = rms_envelope(data, window)
    return data / np.maximum(env, np.median(env) * floor)


def import_loop(src, name, window=0.25, trim=0.15, floor=0.35, fade=0.25, peak=0.85):
    """外で作ったループ素材（ElevenLabs など）を、そのまま鳴らせる形へ均して書き出す。

    コード側が音量を 0 から 1 まで動かすので、**素材の音量は最初から最後まで
    一定でなければならない**。素材の中で強弱が動くと、こちらの envelope と
    二重に掛かって「鳴っているのに揺れる」音になる。生成 AI にいくら
    「constant intensity」と書いても必ず多少は波打つので、**最後は測って均す**。

    やること:

    1. **モノラルに落とす**（ステレオだと 3D 定位が掛からない）
    2. 前後を {trim} 秒切る。生成物の頭と尻には必ずフェードが付いている
    3. **滑る窓の RMS で割って振幅を平らにする**
    4. 末尾を先頭へクロスフェードしてループにする

    <b>窓（{window}）を短くしすぎないこと。</b>これは「ゆっくりした音量のうねりだけを
    取り除く」ための処理で、窓が石の当たる間隔より短くなると<b>一粒ごとの立ち上がりまで
    潰して</b>、砂利がただのホワイトノイズになる。0.2〜0.4 秒あたりが、うねりは取れて
    粒は残る範囲。

    {floor} は「静かなところを無理に持ち上げない」ための下限で、全体の RMS の中央値に
    対する割合。これが無いと、素材に残った無音がフルゲインまで増幅されて<b>そこだけ
    ノイズの塊になる</b>。

    合成した「うねる砂利音」（振幅の幅 14.4dB）で実測した効き:

    ====== ============= ==================================
    窓     うねりの残り  粒立ち（5ms 窓の 95%/50%）
    ====== ============= ==================================
    処理前  14.4 dB       2.33
    0.005s  1.7 dB        1.10  ← 粒が潰れてただのノイズ
    0.020s  1.6 dB        1.48
    0.100s  1.7 dB        1.76
    0.250s  1.7 dB        1.84  ← 既定。ここが膝
    0.400s  2.4 dB        1.86  ← 窓が長すぎてうねりが取り切れない
    ====== ============= ==================================
    """
    data, sr = sf.read(src, dtype="float64", always_2d=True)
    mono = data.mean(axis=1)

    if sr != SR:
        # 直線補間で 48kHz へ。素材はノイズ質なので折り返しは聞こえない
        n = int(len(mono) * SR / sr)
        pos = np.arange(n) * (len(mono) - 1) / (n - 1)
        i0 = np.floor(pos).astype(np.int64)
        frac = pos - i0
        mono = mono[i0] * (1 - frac) + mono[np.minimum(i0 + 1, len(mono) - 1)] * frac

    cut = int(SR * trim)
    if len(mono) > 2 * cut + SR:
        mono = mono[cut:len(mono) - cut]

    mono = flatten(mono, window, floor)

    mono = crossfade_loop(mono, fade)
    mono /= np.max(np.abs(mono)) / peak
    write_ogg(name, mono)
    return len(mono) / SR


#: 未舗装の路面音を揃える大きさ（A 特性の RMS）。ロードノイズ（-22.1dB）と同じあたり。
#: <b>これらは走っている間ずっと鳴る</b>ので、一瞬だけ鳴るスキール音（-14.1dB）より
#: ずっと下でよい。
LOOSE_LOUDNESS = 0.0757


def expand_loop(src, name, laps=7, trim=0.12, window=0.25, depth=0.18,
                target=LOOSE_LOUDNESS, peak=0.90):
    """短い素材を、繰り返しの見えない長さのループへ伸ばす（砂利・砂の路面音）。

    素材（ElevenLabs 生成）は <b>1.50 秒</b>しかない。そのままループにすると
    <b>0.67Hz の周期</b>ができ、砂利区間を走っている間ずっと「ウォンウォン」と
    同じ 1.5 秒が戻ってくる。7 倍へ伸ばして中でゆっくり音程を揺らし、
    同じ場所が二度と来ないようにする（`slip()` と同じ手）。

    <b>ただし `slip()` と違って振幅は揺らさない。</b>あちらは音量の LFO も掛けて
    繰り返しを隠しているが、この音は<b>音量をコード側が動かす</b>ので、素材の中で
    強弱が動くと二重に掛かって「鳴っているのに揺れる」音になる。周期を崩すのは
    音程の揺らぎだけに任せ、最後に `flatten()` で振幅を平らに均す。

    長さは芯のちょうど {laps} 倍。読み進めた総量が芯の長さの整数倍に収まるので、
    <b>クロスフェードで誤魔化さずに継ぎ目が消える</b>。

    揺らぎの深さ {depth} と長さ {laps} の効き（芯の長さぶんずらしたときの似かた。
    1.00 が「同じ音がそのまま戻る」、0 に近いほど別物）:

    ======= ===== ======= =======
    深さ    laps  砂利    砂
    ======= ===== ======= =======
    0.00    7     0.676   0.815
    0.10    7     0.222   0.272
    0.18    7     0.126   0.111   ← 既定
    0.28    7     0.044   0.090
    0.18    11    0.143   0.219
    ======= ===== ======= =======

    <b>長くしても良くならない。</b>揺らぎはファイル全体でちょうど 1・2・3 周する
    ように掛けてあるので、{laps} を増やすと<b>1 周ぶんの揺れ幅が薄まって</b>かえって
    似てくる（11 倍のほうが悪い）。伸ばしたいなら深さも一緒に上げること。

    深さ 0.28 まで上げると数字はさらに下がるが、<b>関係ないずらしかたでの似かたまで
    上がってくる</b>（-0.066 → +0.010）——全体がのっぺりして、どこを切っても同じ音に
    近づいている合図なので、そこまでは上げない。
    """
    data, sr = sf.read(src, dtype="float64", always_2d=True)
    assert sr == SR, sr
    mono = data.mean(axis=1)

    # 生成物の頭には必ずフェードが付いている（実測でこの素材は頭 0.1 秒が中央値の 65%）
    cut = int(SR * trim)
    core = flatten(mono[cut:len(mono) - cut], window)
    core = crossfade_loop(core, 0.05)

    n = len(core) * laps
    t = np.arange(n) / SR
    seconds = n / SR

    def lfo(cycles, weights, phases):
        """ループ長のちょうど整数分の 1 の周期で揺らす。揺らぎ自体が継ぎ目を作らない。"""
        return sum(w * np.sin(2 * np.pi * c * t / seconds + p)
                   for c, w, p in zip(cycles, weights, phases))

    pitch = 1.0 + depth * lfo([1, 2, 3], [0.55, 0.30, 0.15], [0.0, 2.1, 4.7])
    # 読み進めた総量を芯の長さの整数倍へ合わせる。ここが合えば末尾と先頭が繋がる
    pitch *= n / pitch.sum()

    out = flatten(read_loop(core, pitch), window)
    # ピークではなく耳で感じる大きさで揃える。ピークで揃えると、
    # 明るい素材（砂）が暗い素材（砂利）より 6dB 大きく聞こえる（loudness() の表）
    out *= target / loudness(out)
    ceiling = np.max(np.abs(out))
    if ceiling > peak:
        out *= peak / ceiling                  # 割れないための保険。ふつうは通らない
    return write_ogg(name, out)


def tone(name, freq, seconds, partials=(1.0, 0.35, 0.12), decay=None, peak=0.85):
    """減衰する短音。**立ち上がりと終わりを必ず落とすこと**——矩形に切るとプチッと鳴る。

    倍音を少しだけ足してあるのは、正弦波 1 本だとエンジン音に埋もれて聞こえないため。
    """
    n = int(SR * seconds)
    t = np.arange(n) / SR
    wave = sum(amp * np.sin(2 * np.pi * freq * (i + 1) * t)
               for i, amp in enumerate(partials))
    # 立ち上がり 4ms。ここを 0 にするとクリックノイズが乗る
    attack = np.clip(t / 0.004, 0, 1)
    env = attack * np.exp(-t / (decay if decay else seconds / 2.5))
    # 末尾も 0 へ落とす。減衰の途中で切ると、そこがまたクリックになる
    tail = int(SR * 0.01)
    env[-tail:] *= np.linspace(1, 0, tail)
    out = wave * env
    out /= np.max(np.abs(out)) / peak
    write_ogg(name, out)
    return seconds


def fanfare(name="race_finish.ogg", freqs=(587.33, 740.00, 880.00),
            gap=0.16, seconds=1.25, peak=0.85):
    """上がっていく 3 音。ゴールの合図。

    <b>区間の通過音と同じ音にしてはいけない</b>——「もう 1 周あるのか終わったのか」が
    耳で区別できなくなる。上がる音形は終わりの合図として読み取りやすい。
    """
    n = int(SR * seconds)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for i, freq in enumerate(freqs):
        start = int(SR * gap * i)
        m = n - start
        tt = np.arange(m) / SR
        # 最後の音だけ長く残す。途中の音は次の音に譲る
        decay = 0.45 if i == len(freqs) - 1 else 0.13
        wave = np.sin(2 * np.pi * freq * tt) + 0.30 * np.sin(2 * np.pi * freq * 2 * tt)
        env = np.clip(tt / 0.004, 0, 1) * np.exp(-tt / decay)
        out[start:] += wave * env
    tail = int(SR * 0.02)
    out[-tail:] *= np.linspace(1, 0, tail)
    out /= np.max(np.abs(out)) / peak
    write_ogg(name, out)
    return seconds


if __name__ == "__main__":
    # 外で作った素材を取り込むとき:
    #   python tools/make_sounds.py import sample/gravel_scrub.mp3 gravel_scrub.ogg
    if len(sys.argv) > 1 and sys.argv[1] == "import":
        src, name = sys.argv[2], sys.argv[3]
        print("%-16s %.2fs" % (name, import_loop(src, name)))
        sys.exit(0)

    print("crash.ogg        %.2fs" % crash())
    print("exhaust_high.ogg %.2fs" % exhaust_high())
    print("slip.ogg         %.2fs" % slip())
    # スタート信号。点灯は低く短く、号砲はその 1 オクターブ上を長く。
    # <b>同じ高さにしてはいけない</b>——「あと 1 つ」と「消えた」が耳で区別できなくなる
    print("race_light.ogg   %.2fs" % tone("race_light.ogg", 440.0, 0.22, decay=0.09))
    print("race_start.ogg   %.2fs" % tone("race_start.ogg", 880.0, 0.75, decay=0.30))
    # 通過音は運転の邪魔をしない長さに。ピッチはコード側でベストとの差に応じて振る
    print("race_split.ogg   %.2fs" % tone("race_split.ogg", 1320.0, 0.10,
                                          partials=(1.0, 0.20), decay=0.035, peak=0.7))
    print("race_finish.ogg  %.2fs" % fanfare())
    # 未舗装の路面音。スキール音の代わりに鳴る。素材が 1.5 秒しかないので伸ばす
    print("gravel.ogg       %.2fs" % expand_loop(SAMPLE + "gravel.mp3", "gravel.ogg"))
    print("sand.ogg         %.2fs" % expand_loop(SAMPLE + "sand.mp3", "sand.ogg"))
