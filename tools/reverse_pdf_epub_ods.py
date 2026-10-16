#!/usr/bin/env python3
r"""
EPUB / ODS 印成 PDF 这一族判据的反例：改坏 :core 那一层 → 重编 jar → 删掉产物 →
跑 tools/verify_pdf_epub_ods.py → 断言它点名到该红的那一条 → 按 sha1 还原源文件。

    python tools/reverse_pdf_epub_ods.py                  # 全跑
    python tools/reverse_pdf_epub_ods.py --only linear     # 只跑名字里带这个 ASCII 标记的

一处交底：**"每行补到最宽的列数"这条在这里配不出反例** —— 本判据只核"格子有没有印到纸上"，
把补齐关掉，格子照样在（只是纸上的列会往左挤），所以那一轮的"红"要靠几何判据，而几何由
tools/verify_pdf_table.py 那条 xlsx 路钉着；这条常量的牙口在 :core 的 OdsSheetsTest
（`短行补到最宽而不是往左挤`、`补齐只补到最宽那一列`）里，不在桌面上。
"""
from __future__ import annotations

import hashlib
import io
import os
import re
import shutil
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
EPUB = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "book", "Epub.kt")
SHEETS = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "office", "OdsSheets.kt")
BUILD = os.path.join(ROOT, "core", "build", "epubods")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")

REBUILD = "GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:jar -q"
VERIFY = ["python", "tools/verify_pdf_epub_ods.py"]

CASES = [
    {
        "该红": "5a EPUB", "名字": "linear=no 的那次引用也排 linearno", "文件": EPUB,
        "原样": 'if (attrs["linear"].equals("no", ignoreCase = true)) return@forEach',
        "改成": 'if (false) return@forEach',
    },
    {
        "该红": "3 EPUB", "名字": "按 id 顺序排而不是 spine orderid", "文件": EPUB,
        "原样": "        order.forEach { id ->",
        "改成": "        order.sorted().forEach { id ->",
    },
    {
        "该红": "8b EPUB", "名字": "没人读的那份不说出来 silentparts", "文件": EPUB,
        "原样": 'if (unused > 0) notes += "$unused 个清单里的部件没被 spine 用到，没当正文"',
        "改成": 'if (unused < 0) notes += "x"',
    },
    {
        "该红": "11(one-empty) ODS", "名字": "空表也占一节 emptypart", "文件": SHEETS,
        "原样": "            if (sheet.rows.isEmpty() || sheet.rows.all { row -> row.all { it.isEmpty() } }) return@forEach",
        "改成": "            if (false) return@forEach",
    },
    {
        "该红": "10(one-empty) ODS", "名字": "表名不当小标题 notitle", "文件": SHEETS,
        "原样": 'out += DocParagraph(DocPara(listOf(DocRun(sheet.name)), "Heading2"))',
        "改成": 'out += DocParagraph(DocPara(listOf(DocRun("")), "Heading2"))',
    },
    {
        "该红": "13 ODS", "名字": "表头声明一律不理 hearno", "文件": SHEETS,
        "原样": "out += DocTable(sheet.header, padded(sheet.rows))",
        "改成": "out += DocTable(false, padded(sheet.rows))",
    },
    {
        "该红": "12b ODS", "名字": "空表不吭声 silentempty", "文件": SHEETS,
        "原样": '        return if (empty.isEmpty()) emptyList()\n        else listOf("${empty.size} 张表是空的（${empty.joinToString("、") { it.name }}），没占一节")',
        "改成": "        return emptyList()",
    },
]


def sha1_of(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha1(handle.read()).hexdigest()


def wipe() -> None:
    """删掉印出来的 PDF、说明与自造夹具：Gradle 或探针被 UP-TO-DATE 跳过时旧产物还在，
    只比时间戳会把"没跑"说成"跑过"。"""
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)


def rebuilt() -> tuple:
    run = subprocess.run(["bash", "-c", REBUILD], cwd=ROOT, capture_output=True, env=ENV)
    out = (run.stdout or b"").decode("utf-8", "replace") + (run.stderr or b"").decode("utf-8", "replace")
    lines = [line for line in out.splitlines() if line.startswith("e: ") or "FAILED" in line]
    return run.returncode != 0, " / ".join(lines[:3]) or "退出码 %s" % run.returncode


def verifier() -> tuple:
    run = subprocess.run(VERIFY, cwd=ROOT, capture_output=True, text=True,
                         encoding="utf-8", errors="replace")
    out = (run.stdout or "") + (run.stderr or "")
    return re.findall(r"^FAIL (.+)$", out, re.M), "条红" in out, out


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        for case in CASES:
            print("%-34s 该红：%s" % (case["名字"], case["该红"]))
        return 0
    only = args[args.index("--only") + 1] if "--only" in args else None
    files = sorted({case["文件"] for case in CASES})
    originals = {path: open(path, "rb").read() for path in files}
    digests = {path: hashlib.sha1(data).hexdigest() for path, data in originals.items()}
    print("开工：%s" % "、".join("%s sha1 %s…" % (os.path.basename(p), digests[p][:10]) for p in files))
    bad, passed = [], 0
    try:
        for case in CASES:
            if only and only not in case["名字"]:
                continue
            path = case["文件"]
            source = originals[path].decode("utf-8")
            hits = source.count(case["原样"])
            if hits != 1:
                bad.append(("锚点不唯一", case, "找到 %d 处" % hits))
                print("跳过 %-40s 锚点找到 %d 处（应当正好 1 处）" % (case["名字"], hits))
                continue
            wipe()
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source.replace(case["原样"], case["改成"], 1))
            broken, clue = rebuilt()
            reds, finished, out = verifier()
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source)
            if sha1_of(path) != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(originals[path])
                print("停下：%s 还原后 sha1 不一致（已按原字节写回）" % os.path.basename(path))
                return 1
            if broken:
                bad.append(("改不动（编译先红）", case, clue[:160]))
                print("%-42s 改不动：编译就红了 —— %s" % (case["名字"], clue[:110]))
            elif not finished:
                bad.append(("判据没跑完", case, (out.strip().splitlines() or ["无输出"])[-1][:160]))
                print("%-42s 判据没跑完（崩了或没起来）" % case["名字"])
            elif not any(case["该红"] in line for line in reds):
                bad.append(("判据没点名", case, "红了：%s" % "、".join(reds[:3])))
                print("%-42s 判据没点到 %r（实际红：%s）" % (case["名字"], case["该红"], "、".join(reds[:3]) or "无"))
            else:
                passed += 1
                print("%-42s 红对了：%s" % (case["名字"], next(line for line in reds if case["该红"] in line)[:66]))
    finally:
        for path, data in originals.items():
            if sha1_of(path) != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(data)
                print("收尾补还原：%s" % os.path.basename(path))
        rebuilt()
    print("\n%d 条反例按预期红，%d 条有问题" % (passed, len(bad)))
    for kind, case, clue in bad:
        print("  %s：%s —— %s" % (kind, case["名字"], clue))
    if bad:
        return 1
    reds, finished, out = verifier()
    print("收尾复跑：%s（红：%s）" % ("全绿" if finished and not reds else "不是全绿", "、".join(reds[:3]) or "无"))
    return 0 if finished and not reds else 1


if __name__ == "__main__":
    sys.exit(main())
