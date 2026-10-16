#!/usr/bin/env python3
r"""
给 `tools/verify_pdf_font.py` 的四条判据配改坏法（判据 3 有两个：整份不像字体 / 只摘重音字母）：
改坏 → 重出 release 包 → 判据必须点名到该点的那条 → 还原。

    python tools/reverse_pdf_font.py            # 五个改坏法全跑（每个都要重出一次包，慢）
    python tools/reverse_pdf_font.py --only path

三种改坏法：
  * 文本：改 `PdfEngine.kt` 里 `BUNDLED_FONT` 那一行（指向不存在的 / 指向非字体的包内条目）；
  * 改名：把 `assets/fonts/OFL.txt` 挪开（许可文本不在包里），跑完按 sha1 还原；
  * 换字体：把 assets 里那份字体的 cmap 中 Latin-1 那一片摘掉再放回去（临时件在包外），
    逼判据第 3 条的「重音字母与符号」那一句 —— 摘完按 sha1 还原，比对的是开工前那份字节。

每轮**先重出 release 包**再看判据：判据读的是 APK，不重出就是拿上一轮的好包说"全绿"。
"""
from __future__ import annotations

import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
ENGINE = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "fileforge", "converter", "engine", "PdfEngine.kt")
OFL = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "OFL.txt")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "lxgw-wenkai-subset.ttf")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
REBUILD = "GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :app:assembleRelease -q"
VERIFY = ["python", "tools/verify_pdf_font.py"]

DECL = 'const val BUNDLED_FONT = "fonts/lxgw-wenkai-subset.ttf"'


def strip_accents_into(src: str, dst: str) -> int:
    """把 cmap 里 Latin-1 补充那一片摘掉，写出一份"别的都有、就是没重音字母"的字体。

    反例要咬的是**这一条新增的覆盖**，不是整条判据：直接换一份别家的字体，
    红可能来自标点或 GB2312 抽样，那就等于什么都没证。摘掉的码点数就是判据里 LATIN1 的长度。
    """
    sys.path.insert(0, HERE)
    from fontTools.ttLib import TTFont
    from verify_pdf_font import LATIN1
    absent = {ord(ch) for ch in LATIN1}
    with TTFont(src) as font:
        removed = 0
        for table in font["cmap"].tables:
            for code in list(table.cmap):
                if code in absent:
                    del table.cmap[code]
                    removed += 1
        font.save(dst)
    return removed


CASES = [
    {"判据": 1, "名字": "声明的字体没进包 path", "怎么改": "文本", "原样": DECL,
     "改成": 'const val BUNDLED_FONT = "fonts/根本没有这份.ttf"'},
    {"判据": 2, "名字": "包里那个条目不是字体 flavor", "怎么改": "文本", "原样": DECL,
     "改成": 'const val BUNDLED_FONT = "AndroidManifest.xml"'},
    {"判据": 3, "名字": "字覆盖不成样子 cover", "怎么改": "文本", "原样": DECL,
     "改成": 'const val BUNDLED_FONT = "resources.arsc"'},
    {"判据": 3, "名字": "重音字母被摘掉 accent", "怎么改": "换字体", "文件": ASSET},
    {"判据": 4, "名字": "许可文本不在包里 license", "怎么改": "改名", "文件": OFL, "挪到": OFL + ".bak"},
]


def sha1(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha1(handle.read()).hexdigest()


def rebuilt() -> tuple:
    run = subprocess.run(["bash", "-c", REBUILD], cwd=ROOT, capture_output=True, env=ENV)
    out = (run.stdout or b"").decode("utf-8", "replace") + (run.stderr or b"").decode("utf-8", "replace")
    lines = [line for line in out.splitlines() if line.startswith("e: ") or "FAILED" in line or "error:" in line]
    return run.returncode != 0, " / ".join(lines[:3])[-150:]


def verdicts() -> tuple:
    run = subprocess.run(VERIFY, cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    out = (run.stdout or "") + (run.stderr or "")
    reds = [int(n) for n in re.findall(r"^FAIL\s+(\d+)", out, re.M)]
    return reds, ("共 " not in out or "条红" not in out), out


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        for case in CASES:
            print("%-34s 判据 %d  %s" % (case["名字"], case["判据"], case["怎么改"]))
        return 0
    only = args[args.index("--only") + 1] if "--only" in args else None
    texts = {ENGINE: open(ENGINE, "rb").read()}
    moved = {}
    swapped = {}
    bad = []
    passed = 0
    try:
        for case in CASES:
            if only and only not in case["名字"]:
                continue
            if case["怎么改"] == "文本":
                source = texts[ENGINE].decode("utf-8")
                if source.count(case["原样"]) != 1:
                    bad.append(("锚点不唯一", case, "找到 %d 处" % source.count(case["原样"])))
                    print("跳过 %-32s 锚点 %d 处" % (case["名字"], source.count(case["原样"])))
                    continue
                io.open(ENGINE, "w", encoding="utf-8", newline="").write(source.replace(case["原样"], case["改成"], 1))
            elif case["怎么改"] == "换字体":
                path = case["文件"]
                folder = tempfile.mkdtemp(prefix="ff-font-")
                backup = os.path.join(folder, "original.ttf")
                shutil.copyfile(path, backup)
                stripped = os.path.join(folder, "stripped.ttf")
                gone = strip_accents_into(path, stripped)
                swapped[path] = (backup, sha1(backup))
                print("  摘掉 %d 处 Latin-1 映射：%d 字节 → %d 字节（临时件在包外）"
                      % (gone, os.path.getsize(backup), os.path.getsize(stripped)))
                shutil.copyfile(stripped, path)
            else:
                path, aside = case["文件"], case["挪到"]
                moved[path] = (aside, sha1(path))
                shutil.move(path, aside)
            broken, clue = rebuilt()
            reds, crashed, out = verdicts()
            if case["怎么改"] == "文本":
                io.open(ENGINE, "w", encoding="utf-8", newline="").write(texts[ENGINE].decode("utf-8"))
            elif case["怎么改"] == "换字体":
                path = case["文件"]
                backup, digest = swapped.pop(path)
                shutil.copyfile(backup, path)
                shutil.rmtree(os.path.dirname(backup), ignore_errors=True)
                if sha1(path) != digest:
                    print("停下：%s 还原后 sha1 不一致" % os.path.basename(path))
                    return 1
            else:
                path, aside = case["文件"], case["挪到"]
                shutil.move(aside, path)
                if sha1(path) != moved[path][1]:
                    print("停下：%s 还原后 sha1 不一致" % os.path.basename(path))
                    return 1
            if broken:
                bad.append(("改不动（重出包就红了）", case, clue))
                print("%-36s 重出包就红了，判据没机会看产物" % case["名字"])
            elif crashed:
                bad.append(("判据崩了", case, (out.strip().splitlines() or ["无输出"])[-1][:150]))
                print("%-36s 判据崩了（不是红，是没跑成）" % case["名字"])
            elif case["判据"] in reds:
                passed += 1
                others = [n for n in reds if n != case["判据"]]
                print("%-36s 红的是第 %d 条 ✔%s" % (case["名字"], case["判据"], "（另外还红了 %s）" % others if others else ""))
            else:
                bad.append(("改不坏", case, "实际红了 %s" % (reds or "无")))
                print("%-36s 没红到第 %d 条（实际红：%s）" % (case["名字"], case["判据"], reds or "无"))
    finally:
        io.open(ENGINE, "w", encoding="utf-8", newline="").write(texts[ENGINE].decode("utf-8"))
        for path, (aside, digest) in moved.items():
            if os.path.isfile(aside) and not os.path.isfile(path):
                shutil.move(aside, path)
            if sha1(path) != digest:
                print("  警告：%s 与开工前不一致" % os.path.basename(path))
        for path, (backup, digest) in list(swapped.items()):
            shutil.copyfile(backup, path)
            shutil.rmtree(os.path.dirname(backup), ignore_errors=True)
            if sha1(path) != digest:
                print("  警告：%s 与开工前不一致" % os.path.basename(path))
        broken, clue = rebuilt()
        print("\n还原核对：%s（干净代码重出包=%s）" % (
            "、".join("%s sha1 %s" % (os.path.basename(p), sha1(p)[:10]) for p in [ENGINE, OFL, ASSET]), not broken))
        if broken:
            print("  警告：还原后重出包失败，%s" % clue)
    print("\n共 %d 个反例跑到判定，%d 个没红到该点的条" % (passed + len(bad), len(bad)))
    for kind, case, detail in bad:
        print("  %s：%s（判据 %s）%s" % (kind, case["名字"], case["判据"], detail))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
