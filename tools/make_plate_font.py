"""ナンバーの日本語フォントを作る。Noto Sans JP（SIL OFL 1.1）を間引いて別の名前で書き出す。

使い方:
    pip install fonttools
    python tools/make_plate_font.py <NotoSansJP[wght].ttf のパス>

元のフォントは google/fonts の ofl/notosansjp/NotoSansJP[wght].ttf（可変フォント）。
書き出し先は core/src/main/resources/assets/kurumamod/plate_fonts/kurumaplate_jp.ttf。

<b>ライセンスで守ること（OFL 1.1）:</b>
    - 間引きは改変にあたる。Noto Sans JP の OFL は Reserved Font Name "Source" を持つので、
      改変したものは "Source" を名乗れない。取り違えを避けるため、元のフォント名も名乗らず
      <b>"KurumaPlate JP"</b> にする（名前の表の 1・3・4・6・16・17 を書き換える）
    - 著作権表示（名前の表の 0）とライセンス（13・14）は<b>消さない</b>
    - 同じフォルダに OFL の全文（OFL.txt）を置いて一緒に配る
    - フォント単体で売らない（MOD に入れて配るのは、MOD を有料にしても構わない）

残す字:
    - ASCII（英数字と記号）
    - ひらがな・カタカナ・長音
    - JIS 第 1・第 2 水準の漢字（Shift_JIS で表せる CJK 統合漢字）と 々
    - ナンバーの記号（・ ー など、全角の約物）。全角英数は入れない（打った字は半角のまま描く）
    地名は自由入力なので漢字を絞り込めない。第 1・第 2 水準で常用の地名はほぼ足りる。
    それより外の字は、描く側（PlateTextures）がシステムのフォントで代わりに描く。
"""

import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "core/src/main/resources/assets/kurumamod/plate_fonts/kurumaplate_jp.ttf"
FAMILY = "KurumaPlate JP"
# ナンバーの字は太め。実物の書体より少し細いが、縮めて詰めたときに潰れない太さ
WEIGHT = 600


def jis_kanji():
    """Shift_JIS で表せる CJK 統合漢字（JIS X 0208 の第 1・第 2 水準）。

    EUC-JP で判定してはいけない。Python の euc_jp は補助漢字（JIS X 0212）まで表せるので、
    字の数が倍近くになる。"""
    chars = []
    for code in range(0x4E00, 0xA000):
        try:
            chr(code).encode("shift_jis")
        except UnicodeEncodeError:
            continue
        chars.append(code)
    return chars


def unicodes():
    codes = set(range(0x20, 0x7F))           # ASCII
    codes |= set(range(0x3041, 0x3097))      # ひらがな
    codes |= set(range(0x309D, 0x30A0))      # ゝゞゟ
    codes |= set(range(0x30A1, 0x30FF))      # カタカナ・中黒・長音
    codes |= set(range(0x3000, 0x3020))      # 全角の空白と約物（、。「」）
    codes |= {0x3005}                        # 々
    codes |= set(jis_kanji())
    return codes


def rename(font):
    names = font["name"]
    postscript = FAMILY.replace(" ", "") + "-SemiBold"
    for record in list(names.names):
        if record.nameID in (1, 16):
            names.setName(FAMILY, record.nameID, record.platformID, record.platEncID, record.langID)
        elif record.nameID in (2, 17):
            names.setName("Regular" if record.nameID == 2 else "SemiBold",
                          record.nameID, record.platformID, record.platEncID, record.langID)
        elif record.nameID == 3:
            names.setName(postscript + ";KurumaMod", 3, record.platformID, record.platEncID, record.langID)
        elif record.nameID == 4:
            names.setName(FAMILY + " SemiBold", 4, record.platformID, record.platEncID, record.langID)
        elif record.nameID == 6:
            names.setName(postscript, 6, record.platformID, record.platEncID, record.langID)
    # 可変フォントの名前（25 など）や、元の名前を指す説明は落とす。著作権（0）とライセンス（13・14）は残す
    keep = {0, 1, 2, 3, 4, 5, 6, 13, 14, 16, 17}
    names.names = [record for record in names.names if record.nameID in keep]


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    font = TTFont(sys.argv[1])
    # 可変フォントを 1 つの太さに固定する（Java のフォントは可変の軸を扱えない）
    font = instancer.instantiateVariableFont(font, {"wght": WEIGHT})

    options = subset.Options()
    # 縦書き・字幅の切り替えなどの字形は要らない（ナンバーは横書きの 1 行）。入れると字形が倍近くになる
    options.layout_features = ["kern"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.hinting = False
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=unicodes())
    subsetter.subset(font)

    rename(font)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    font.save(OUT)
    print("書き出しました: {}（{:.1f} MB・{} 字）".format(
        OUT.relative_to(ROOT), OUT.stat().st_size / 1e6, len(font.getBestCmap())))


main()
