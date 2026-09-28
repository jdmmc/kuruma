# -*- coding: utf-8 -*-
"""排気音のループから、1 オクターブ上の音源（exhaust_high 用）を作る。

Make the one-octave-up source for `exhaust_high` from an exhaust loop.

使い方 / Usage:
    pip install numpy soundfile
    python make_octave_up.py kei_exhaust.ogg
        -> kei_exhaust_high.ogg ができる / writes kei_exhaust_high.ogg
    python make_octave_up.py kei_exhaust.ogg -o somewhere/other_name.ogg

なぜ要るのか / Why:
    Minecraft は音程を 0.5〜2.0 倍に丸めるので、1 ファイルではレブまで音程が上がりきらない。
    KurumaMod は低回転を `exhaust`、高回転を「1 オクターブ上の同じ音」＝`exhaust_high` で鳴らし、
    間をクロスフェードする。ここで作るのはその高回転側。
    Minecraft clamps pitch to 0.5-2.0x, so one file cannot reach the redline. KurumaMod plays
    `exhaust` low and the same sound one octave up (`exhaust_high`) high, cross-fading between them.

やること / What it does:
    - 長さを半分にする（＝音程が 1 オクターブ上がる）。<b>帯域を切ってから</b>間引くので、
      単純に 1 つおきに捨てたときのような耳障りな折り返しが出ない
      Halves the length (= one octave up), band-limited so it does not alias
    - <b>ループはループのまま。</b>ループ 1 周ぶんを周期信号として扱うので、継ぎ目は増えない
      （入力が継ぎ目の無いループなら、出力もそう）
      A seamless loop in gives a seamless loop out
    - ステレオはモノラルに落とす（ステレオは Minecraft で距離減衰しない）
      Stereo is folded to mono (Minecraft does not attenuate stereo by distance)
    - OGG Vorbis で書く（Minecraft は ogg しか鳴らさない）
      Writes Ogg Vorbis (the only format Minecraft plays)
"""
import argparse
import os
import sys

import numpy as np
import soundfile as sf


def octave_up(data):
    """ループ 1 周を半分の長さへ。フーリエ領域で上半分の帯域を捨ててから縮める。"""
    n = len(data)
    m = n // 2
    spectrum = np.fft.rfft(data)
    # 新しい長さのナイキストより上は捨てる（ここを残すと折り返して濁る）
    # 振幅は長さの比で補正する（irfft は長さで割るため）
    return np.fft.irfft(spectrum[:m // 2 + 1], m) * (m / n)


def write_ogg(path, data, samplerate, chunk_seconds=1.0):
    """1 秒ずつ書く。長い配列を一度に渡すと libsndfile の中で落ちることがある（Windows）。"""
    data = np.asarray(data, dtype=np.float32)
    step = max(1, int(samplerate * chunk_seconds))
    with sf.SoundFile(path, "w", samplerate=samplerate, channels=1,
                      format="OGG", subtype="VORBIS") as out:
        for i in range(0, len(data), step):
            out.write(data[i:i + step])


def main():
    parser = argparse.ArgumentParser(
        description="排気音のループから 1 オクターブ上の音源を作る / "
                    "make the one-octave-up exhaust loop")
    parser.add_argument("input", help="元の排気音（ループ済み）/ source exhaust loop")
    parser.add_argument("-o", "--output",
                        help="書き出し先。省略すると <元の名前>_high.ogg / "
                             "output path (default: <input>_high.ogg)")
    args = parser.parse_args()

    stem, _ = os.path.splitext(args.input)
    output = args.output or stem + "_high.ogg"
    if os.path.abspath(output) == os.path.abspath(args.input):
        sys.exit("入力と同じファイルには書けません / output must differ from input")

    data, samplerate = sf.read(args.input, dtype="float64", always_2d=True)
    if data.shape[1] > 1:
        print("ステレオなのでモノラルに落とします / folding stereo to mono")
    mono = data.mean(axis=1)
    if len(mono) < 2:
        sys.exit("音源が短すぎます / input is too short")

    high = octave_up(mono)
    peak = float(np.max(np.abs(high)))
    if peak > 1.0:
        # 帯域を切ると波形の山がわずかに上がることがある。割れないよう戻す
        high /= peak
        print("クリップしないよう %.1f dB 下げました / lowered by %.1f dB to avoid clipping"
              % (20 * np.log10(peak), 20 * np.log10(peak)))

    write_ogg(output, high, samplerate)
    print("%s  %.2fs -> %s  %.2fs  (%d Hz, mono)"
          % (args.input, len(mono) / samplerate, output, len(high) / samplerate, samplerate))


if __name__ == "__main__":
    main()
