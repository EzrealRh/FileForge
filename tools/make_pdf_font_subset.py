#!/usr/bin/env python3
r"""
从完整的中文字体切一份「够中文文档用」的子集出来，替掉 `app/src/main/assets/fonts/lxgw-wenkai-subset.ttf`。

为什么要有这个脚本：随应用打包的那份字体是**印不出中文时的兜底**（系统里那份 NotoSansCJK 是 CFF 轮廓，
pdfbox-android 解析不了），它覆盖哪些码点就等于"这台机器上中文文档能印成什么样"。
早先那份只到 ASCII + 中文标点 + GB2312，结果带重音拉丁字母的稿子（人名、单位、版权符号）
少字 —— 应用会把缺字数出来告诉用户，但字面上少一笔就是少一笔。所以子集的**承诺写在一处**
（tools/verify_pdf_font.py 里的 PUNCT / LATIN1 / gb2312_hanzi），判据照着它核，这里也照着它切：
要扩大覆盖就改那一处、跑这里重出一份、再跑判据。

    跑法（源字体不进仓库，从 lxgw/LxgwWenKai 的 Release 里取，SIL OFL 1.1）：
        python tools/make_pdf_font_subset.py D:/fflogs/wenkai.ttf
    然后 ./gradlew :app:assembleRelease && python tools/verify_pdf_font.py

产物只留 TrueType 轮廓（`head.glyphDataFormat == 0`）—— pdfbox 拿到 CFF 就加载失败，这条正是判据第 2 条核的。
在用的那一份子集的 cmap 是底线：新切的少一个码点就报红，不会把已经能印的字换没了。
"""
from __future__ import annotations

import os
import sys

try:
    from fontTools.subset import Options, Subsetter, load_font, save_font
    from fontTools.ttLib import TTFont
except ImportError:
    print("RED  要 fontTools（pip install fonttools），这份脚本是造字体的，不代跑")
    sys.exit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

sys.path.insert(0, HERE)
from verify_pdf_font import LATIN1, PUNCT, gb2312_hanzi  # noqa: E402  承诺的码点：判据与这里读同一处

ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "lxgw-wenkai-subset.ttf")


def promised():
    """这份子集对外承诺能画的字符。"""
    out = {chr(code) for code in range(0x20, 0x7F)}
    out.update(PUNCT)
    out.update(LATIN1)
    out.update(gb2312_hanzi())
    return sorted(out)


def cmap_of(path):
    with TTFont(path, fontNumber=0, lazy=True) as font:
        return {chr(code) for code in (font.getBestCmap() or {})}


def main(source: str, target: str) -> int:
    # 在用的那一份是底线：新切的东西少一个码点就是退化，判据再宽也拦不住已经印出来的缺字
    baseline = cmap_of(target) if os.path.exists(target) else set()
    chars = sorted(set(promised()) | baseline)
    options = Options()
    options.hinting = False               # 源字体带 TrueType 指令，pdfbox 不需要，去掉省体积
    options.notdef_outline = True
    options.recalc_bounds = False
    options.drop_tables += ("prep",)      # 与在用的那一份对齐：同一套表，别多别少
    subsetter = Subsetter(options=options)
    subsetter.populate(text="".join(chars))
    font = load_font(source, options)
    subsetter.subset(font)
    save_font(font, target, options)

    size = os.path.getsize(target)
    with TTFont(target, fontNumber=0, lazy=True) as probe:
        cmap = probe.getBestCmap() or {}
        glyph_data_format = probe["head"].glyphDataFormat
        glyphs = probe["maxp"].numGlyphs
    covered = {chr(code) for code in cmap}
    lost = [ch for ch in chars if ch not in covered]
    print("源字体 %s → 子集 %s：%d 字节，%d 个字形，轮廓 glyphDataFormat=%d（0 才是 TrueType）"
          % (source, target, size, glyphs, glyph_data_format))
    print("承诺 %d 个码点（含在用的那一份 %d 个），cmap 里有 %d 个，覆盖不到 %d 个"
          % (len(chars), len(baseline), len(covered), len(lost)))
    if lost:
        print("RED  这些字符源字体里就没有（或被子集掉了）：%r" % lost[:20])
        return 1
    if glyph_data_format != 0:
        print("RED  切出来不是 TrueType 轮廓，pdfbox 加载不了")
        return 1
    print("OK   子集覆盖齐，写到 %s" % target)
    return 0


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else ""
    dst = sys.argv[2] if len(sys.argv) > 2 else ASSET
    if not src or not os.path.exists(src):
        print("用法：python tools/make_pdf_font_subset.py <完整中文字体.ttf> [输出，默认覆盖 assets 里那份]")
        sys.exit(2)
    sys.exit(main(src, dst))
