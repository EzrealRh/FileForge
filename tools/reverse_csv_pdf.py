#!/usr/bin/env python3
r"""
每条判据配一个反例：把实现改坏 → 重编 :core:jar → 删掉产物重跑探针 → 跑 verify_csv_pdf.py →
断言判据点名到该点的那一条，然后把源文件按字节还原。

    python tools/reverse_csv_pdf.py                 # 全跑一遍
    python tools/reverse_csv_pdf.py --only detect   # 只跑名字里带 ASCII 标记的那条

两个坑这里都防了：
  - **每轮先删产物**再重跑，并用"产物在不在"当这一轮真写盘的证据。Gradle 或探针被 UP-TO-DATE
    跳过时旧文件还在，只比 mtime 会把"没跑"说成"跑过"，判据于是读着上一轮的好产物说全绿。
  - 还原按 sha1 核对：改坏的那份必须与开工前一个字节都不差，否则立刻按原字节写回并停下。
名字里带 ASCII 标记（detect / margin / ragged…）：Windows 上 Python 收命令行按 cp936 解，
中文过滤词会静默变成 0 条命中。
"""
from __future__ import annotations

import hashlib
import io
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
BUILD = os.path.join(ROOT, "core", "build", "csvpdf")
TREE = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "doc", "CsvTree.kt")
DOC = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "doc", "Doc.kt")
PLAN = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "pdf", "PrintPlan.kt")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")

STEMS = ["orders", "quoted", "people", "euro", "logs", "prose", "ragged", "wide", "huge"]
SOURCES = ["orders.csv", "quoted.csv", "people.tsv", "euro.csv", "logs.csv", "prose.txt",
           "ragged.csv", "wide.csv", "huge.csv"]
PRODUCTS = ["%s.pdf" % stem for stem in STEMS]

REBUILD = ("GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:jar -q && "
           "python tools/make_csv_pdf_fixtures.py && "
           + " && ".join(
               "GRADLE_USER_HOME=/d/gradle-home PDFPROBE_OUT=/d/fflogs/pdfprobe "
               "bash tools/pdfprobe/run.sh docpdf C:/Windows/Fonts/simhei.ttf "
               "core/build/csvpdf/%s core/build/csvpdf/%s.pdf" % (src, stem)
               for src, stem in zip(SOURCES, STEMS)))
VERIFY = ["python", "tools/verify_csv_pdf.py"]

# 每条：判据号、名字（带 ASCII 标记）、文件、原样、改成
CASES = [
    {
        "判据": 1, "名字": "什么表都不认了 allrejection", "文件": TREE,
        "原样": "        if (doc.widest < 2) return null to \"每行只有 ${doc.widest} 列\"",
        "改成": "        if (doc.widest < 100) return null to \"每行只有 ${doc.widest} 列\"",
    },
    {
        "判据": 2, "名字": "只留第一列 onecol", "文件": TREE,
        "原样": "            parts = listOf(DocTable(true, rows)),",
        "改成": "            parts = listOf(DocTable(true, rows.map { row -> row.take(1) })),",
    },
    {
        "判据": 3, "名字": "每行的格子倒过来摆 reversed", "文件": TREE,
        "原样": '        val rows = kept.map { row -> row + List(columns - row.size) { "" } }',
        "改成": '        val rows = kept.map { row -> row.reversed() + List(columns - row.size) { "" } }',
    },
    {
        "判据": 4, "名字": "表头下面那条线不加粗", "文件": PLAN,
        "原样": "                    if (index == 0 && table.header) RULE_HEAD else RULE_THIN,",
        "改成": "                    if (false && index == 0 && table.header) RULE_HEAD else RULE_THIN,",
    },
    {
        "判据": 5, "名字": "引号状态不管了按行硬切 splitlines", "文件": TREE,
        "原样": "        val doc = runCatching { Csv.parse(text, delimiter) }.getOrNull()",
        "改成": "        val doc = runCatching {\n"
                "            CsvDoc(text.trimStart(Csv.BOM).split('\\n').map { line -> line.split(delimiter.char) },\n"
                "                delimiter, com.fileforge.core.text.LineEnding.Lf)\n"
                "        }.getOrNull()",
    },
    {
        "判据": 6, "名字": "分隔符不猜了写死逗号 detect", "文件": TREE,
        "原样": "        val delimiter = Csv.detect(text)",
        "改成": "        val delimiter = com.fileforge.core.data.Delimiter.Comma",
    },
    {
        "判据": 7, "名字": "列数不齐也照样认 ragged", "文件": TREE,
        "原样": "        if (doc.ragged.isNotEmpty()) {",
        "改成": "        if (false && doc.ragged.isNotEmpty()) {",
    },
    {
        "判据": 8, "名字": "说明里的行数多报一行 count", "文件": TREE,
        "原样": '        notes += "按${parsed.delimiter.label}分成 $columns 列、${kept.size} 行',
        "改成": '        notes += "按${parsed.delimiter.label}分成 $columns 列、${kept.size + 1} 行',
    },
    {
        "判据": 9, "名字": "纯文本那条的说明串了台", "文件": DOC,
        "原样": '        Plain("按空行分段排（文本里没有网页标签或 Markdown 记号）"),',
        "改成": '        Plain("按分隔符分成一张表"),',
    },
    {
        "判据": 10, "名字": "压窄了哪一列不写进说明", "文件": PLAN,
        "原样": "        return ColumnFit(widths, 0f, pressed(natural, widths))",
        "改成": "        return ColumnFit(widths, 0f, emptyList())",
    },
    {
        "判据": 11, "名字": "超长不截断全排出去 truncate", "文件": TREE,
        "原样": "            parsed.records.take(MAX_ROWS)",
        "改成": "            parsed.records",
    },
    {
        "判据": 12, "名字": "字一个都不画上去 notext", "文件": PLAN,
        "原样": "        return PrintPlan(pages, notes)",
        "改成": "        return PrintPlan(pages.map { PrintPage(emptyList(), it.rules) }, notes)",
    },
    {
        "判据": 13, "名字": "页边距没算进位置（字贴到纸边）margin", "文件": PLAN,
        "原样": "                        val at = margin + indent + markerWidth",
        "改成": "                        val at = indent + markerWidth",
    },
]


def sha1_of(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha1(handle.read()).hexdigest()


def wipe() -> None:
    """只删 PDF 与说明，九份夹具留着当来料（.csv / .tsv / .txt 都不碰）。"""
    if not os.path.isdir(BUILD):
        return
    for name in os.listdir(BUILD):
        if name.endswith(".pdf") or name.endswith(".notes.txt"):
            os.remove(os.path.join(BUILD, name))


def rebuilt() -> tuple:
    run = subprocess.run(["bash", "-c", REBUILD], cwd=ROOT, capture_output=True, env=ENV)
    out = (run.stdout or b"").decode("utf-8", "replace") + (run.stderr or b"").decode("utf-8", "replace")
    missing = [os.path.join(BUILD, name) for name in PRODUCTS if not os.path.isfile(os.path.join(BUILD, name))]
    lines = [line for line in out.splitlines() if line.startswith("e: ") or "FAILED" in line]
    clue = " / ".join(lines[:3]) or ("缺产物：" + "、".join(os.path.basename(x) for x in missing)
                                    if missing else "退出码 %s" % run.returncode)
    return run.returncode != 0, not missing, clue


def verifier() -> tuple:
    run = subprocess.run(VERIFY, cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    out = (run.stdout or "") + (run.stderr or "")
    reds = [int(n) for n in re.findall(r"^FAIL\s+(\d+)", out, re.M)]
    return reds, ("共 " not in out or "条红" not in out), out


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        for case in CASES:
            print("%-40s 判据 %2d  %s" % (case["名字"], case["判据"], os.path.basename(case["文件"])))
        return 0
    only = args[args.index("--only") + 1] if "--only" in args else None
    files = sorted({case["文件"] for case in CASES})
    originals = {path: open(path, "rb").read() for path in files}
    digests = {path: hashlib.sha1(data).hexdigest() for path, data in originals.items()}
    print("开工：%s" % "、".join("%s sha1 %s…" % (os.path.basename(p), digests[p][:10]) for p in files))
    bad = []
    passed = 0
    try:
        for case in CASES:
            if only and only not in case["名字"]:
                continue
            path = case["文件"]
            source = originals[path].decode("utf-8")
            hits = source.count(case["原样"])
            if hits != 1:
                bad.append(("锚点不唯一", case, "找到 %d 处" % hits))
                print("跳过 %-38s 锚点找到 %d 处（应当正好 1 处）" % (case["名字"], hits))
                continue
            wipe()
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source.replace(case["原样"], case["改成"], 1))
            broken, made, clue = rebuilt()
            reds, crashed, out = verifier()
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source)
            if sha1_of(path) != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(originals[path])
                print("停下：%s 还原后 sha1 不一致（已按原字节写回）" % os.path.basename(path))
                return 1
            if broken:
                bad.append(("改不动（编译或探针先红了）", case, clue[:150]))
                print("%-40s 这一处改不动：编译/探针就红了，判据没机会看产物" % case["名字"])
            elif crashed:
                bad.append(("判据崩了", case, (out.strip().splitlines() or ["无输出"])[-1][:150]))
                print("%-40s 判据崩了（不是红，是没跑成）" % case["名字"])
            elif not made:
                bad.append(("没产物", case, clue or "这一轮没重写 PDF"))
                print("%-40s 没有产物（或被改坏那份没落盘）：%s" % (case["名字"], clue[:110] or "未知"))
            elif case["判据"] in reds:
                passed += 1
                others = [n for n in reds if n != case["判据"]]
                print("%-40s 红的是第 %2d 条 ✔%s" % (
                    case["名字"], case["判据"], "（另外还红了 %s）" % others if others else ""))
            else:
                bad.append(("改不坏", case, "实际红了 %s" % (reds or "无")))
                print("%-40s 没红到第 %2d 条（实际红：%s）" % (case["名字"], case["判据"], reds or "无"))
    finally:
        for path in files:
            if sha1_of(path) != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(originals[path])
        wipe()
        _broken, made, clue = rebuilt()
        print("\n还原核对：%s；干净代码重落产物=%s" % (
            "、".join("%s sha1 %s（%s）" % (os.path.basename(p), sha1_of(p)[:10],
                                     "与开工前一致" if sha1_of(p) == digests[p] else "不一致") for p in files), made))
        if not made:
            print("  警告：还原后没能重落产物，%s" % clue)
    skipped = len([b for b in bad if b[0] == "锚点不唯一"])
    print("\n共 %d 个反例跑到判定，%d 个没红到该点的条，%d 个锚点没落上" % (passed + len(bad) - skipped, len(bad), skipped))
    for kind, case, detail in bad:
        print("  %s：%s（判据 %s）%s" % (kind, case["名字"], case["判据"], detail))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
