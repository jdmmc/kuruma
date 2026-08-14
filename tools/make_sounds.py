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
import numpy as np
import soundfile as sf

SR = 48000
OUT = "src/main/resources/assets/kurumamod/sounds/"
SAMPLE = "sample/"


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
    sf.write(OUT + "crash.ogg", mix.astype(np.float32), SR, format="OGG", subtype="VORBIS")
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
    sf.write(OUT + "exhaust_high.ogg", out.astype(np.float32), sr, format="OGG", subtype="VORBIS")
    return len(out) / sr


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
    sf.write(OUT + "slip.ogg", out.astype(np.float32), SR, format="OGG", subtype="VORBIS")
    return seconds


def crossfade_loop(data, fade_seconds):
    """末尾を先頭へクロスフェードしてループにする。**切ってから掛ける**こと。"""
    fade = int(SR * fade_seconds)
    out = data.copy()
    ramp = np.linspace(0.0, 1.0, fade)
    out[-fade:] = out[-fade:] * (1 - ramp) + out[:fade] * ramp
    return out


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
    sf.write(OUT + name, out.astype(np.float32), SR, format="OGG", subtype="VORBIS")
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
    sf.write(OUT + name, out.astype(np.float32), SR, format="OGG", subtype="VORBIS")
    return seconds


if __name__ == "__main__":
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
