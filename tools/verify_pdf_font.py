#!/usr/bin/env python3
r"""
判「随应用打包的那份中文字体能不能真用」——四条，全部对着**最终 APK** 判，不看仓库里的中间文件。

为什么要有这一族：真机上「印成 PDF」对中文整批失败过（v0.51.0 及以前）。根因不是排版而是字体来源 ——
AOSP / GMS 镜像的中文字体是 `NotoSansCJK-Regular.ttc`，**CFF(OTTO) 轮廓**，pdfbox-android 解析不了，
而应用只往 `/system/fonts` 里挑：系统挑不出就等于这台机器印不出中文。
桌面替身是自己 `PDType0Font.load(simhei.ttf)` 直接加载字体文件的，**这条路它一步都没走过** ——
35 条判据全绿也看不见这个洞。所以这一族判据核的是"代码要的字体，包里到底有没有、能不能用"。

    跑法：python tools/verify_pdf_font.py            （要先 ./gradlew :app:assembleRelease）
    反例：python tools/reverse_pdf_font.py          （四条各配一个改坏法，红到该点那条）

 1  源码里 `BUNDLED_FONT` 声明的那个路径，在最终 APK 里**真有这个条目**（改一行常量就会红）
 2  它是 TrueType 轮廓（sfnt version 0x00010000）—— CFF 的 pdfbox-android 用不了，装了等于没装
 3   cmap 覆盖：可打印 ASCII、中文标点、圆点 U+2022，外加从 GB2312 里抽样的 200 个汉字一个都不缺
 4  SIL 许可文本随包（`assets/fonts/OFL.txt`）—— OFL 字体可以再分发，条件是许可原文一起走
"""
from __future__ import annotations

import glob
import io
import os
import random
import re
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, HERE)
from verify_odt_read import check, results  # noqa: E402

ENGINE = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "fileforge", "converter", "engine", "PdfEngine.kt")
PUNCT = "，。、；：？！（）《》【】“”‘’—…··・``•"


def gb2312_hanzi():
    """GB2312 编得出来的非空白字符 —— 这份子集对外承诺的就是这一层。"""
    out = []
    for high in range(0xA1, 0xFF):
        for low in range(0xA1, 0xFF):
            try:
                ch = bytes((high, low)).decode("gb2312")
            except Exception:
                continue
            if not ch.isspace():
                out.append(ch)
    return sorted(set(out))


def apk_path() -> str:
    found = sorted(glob.glob(os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "*.apk")),
                   key=os.path.getmtime)
    return found[-1] if found else ""


def sfnt_flavor(blob: bytes) -> int:
    if blob[:4] == b"ttcf":
        offset = struct.unpack(">I", blob[12:16])[0]
        return struct.unpack(">I", blob[offset:offset + 4])[0]
    return struct.unpack(">I", blob[:4])[0]


def main() -> int:
    with io.open(ENGINE, encoding="utf-8") as handle:
        source = handle.read()
    declared = re.search(r'const val BUNDLED_FONT = "([^"]+)"', source)
    apk = apk_path()
    names = zipfile.ZipFile(apk).namelist() if apk and os.path.isfile(apk) else []
    want = declared.group(1) if declared else ""
    # assets.open("fonts/x.ttf") 读的是包里的 assets/fonts/x.ttf：两种写法都算数，
    # 但必须是**同一个路径**——声明与包里对不上就是"代码要的字体没进包"
    in_apk = bool(want) and (want in names or ("assets/" + want) in names)
    check(1, "源码声明的内置字体路径，在最终 APK 里真有条目",
          bool(declared) and bool(apk) and os.path.isfile(apk) and in_apk,
          "声明=%s；APK=%s" % (want or "没声明", os.path.basename(apk) or "没有 release 包"))

    if not declared or not apk or not os.path.isfile(apk):
        for number, name in ((2, "内置字体是 TrueType 轮廓"), (3, "内置字体的字够日常中文用"), (4, "SIL 许可文本随包一起发")):
            check(number, name, False, "前一条就没过，读不到字体")
        print("共 %d 条，%d 条红" % (len(results), len([r for r in results if not r[2]])))
        return 1

    book = zipfile.ZipFile(apk)
    entry = next((n for n in (want, "assets/" + want) if n in names), "")
    blob = book.read(entry) if entry else b""
    flavor = sfnt_flavor(blob) if len(blob) >= 16 else 0
    check(2, "内置字体是 TrueType 轮廓（CFF 的 pdfbox-android 用不了）",
          flavor in (0x00010000, 0x74727565), "条目=%s，sfnt version = 0x%08X" % (entry or "包里没有", flavor))

    # 包里的必须与仓库里那份**逐字节相同**：改过资产忘了重出包，测的就不是要发的东西
    disk = os.path.join(ROOT, "app", "src", "main", "assets", want)
    same = os.path.isfile(disk) and open(disk, "rb").read() == blob

    cmap = set()
    why = ""
    try:
        from fontTools.ttLib import TTFont
        tables = TTFont(io.BytesIO(blob)) if blob else None
        cmap = set(tables.getBestCmap()) if tables is not None else set()
    except Exception as error:  # noqa: BLE001 —— 读不动要变成点名的红，不能让 traceback 带走整轮
        why = "fontTools 读不动：%s" % str(error)[:120]
    latin = [chr(c) for c in range(0x21, 0x7F) if c not in cmap]
    punct = [ch for ch in PUNCT if ord(ch) not in cmap]
    # 抽样只能从**声明覆盖的那一层**里抽：这份子集承诺的是 GB2312 全量，
    # 从整个 CJK 区段（两万多字）里抽是把判据的口径写大了
    rng = random.Random(20261016)
    han = rng.sample(gb2312_hanzi(), 200)
    miss_han = [ch for ch in han if ord(ch) not in cmap]
    counts = "包内=%d 仓库=%d 同=%s；缺 拉丁 %d/标点 %d/汉字 %d；码点 %d%s" % (
        len(blob), os.path.getsize(disk) if os.path.isfile(disk) else -1, same,
        len(latin), len(punct), len(miss_han), len(cmap), ("；" + why) if why else "")
    check(3, "内置字体的字够日常中文用：包内与仓库那份是同一份、拉丁/中文标点/圆点、从 GB2312 抽 200 个汉字",
          bool(blob) and same and not latin and not punct and not miss_han and len(cmap) >= 7000,
          counts + "；例：" + "".join((latin + punct + miss_han)[:12]))

    ofl = [n for n in book.namelist() if n.startswith("assets/fonts/") and n.lower().endswith(".txt")]
    text = book.read(ofl[0]).decode("utf-8", "replace").lower() if ofl else ""
    check(4, "SIL 许可文本随包一起发", bool(ofl) and "open font license" in text,
          "包里的许可文件：%s" % (ofl or "没有"))

    total = len(results)
    red = [r for r in results if not r[2]]
    print("共 %d 条，%d 条红" % (total, len(red)))
    for number, name, ok in red:
        print("  红：%d %s" % (number, name))
    return 1 if red else 0


if __name__ == "__main__":
    sys.exit(main())
