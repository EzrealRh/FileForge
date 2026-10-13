#!/usr/bin/env python3
r"""
给 tools/verify_pdf_table.py 喂反例：把排版器（或"空表不占一节"那条判定）改坏一处，
断言判据点名到该点的那一条。

跑法：
    python tools/reverse_pdf_table.py                # 逐个改坏 → 重编 :core:jar → 重跑探针 → 跑判据 → 还原
    python tools/reverse_pdf_table.py --only 顶上的线
    python tools/reverse_pdf_table.py --list

每轮都要重编 jar 再重跑探针：判据读的是 PDF，jar 不重编就是拿上一轮的好产物说"全绿"。
产物先删掉再重跑，并用 mtime 确认这一轮真写了盘 —— 只按 gradle 退出码判会把"判据没意见"
错报成"没产物"（反例本来就要把别的断言改红，退出码必然非 0）。
改坏的是 Kotlin 源文件，开工先留原字节，每轮跑完按字节写回，最后 sha1 核对；
不用 git checkout 撤销（PrintPlan.kt 这轮才新增，HEAD 里没有它，取回来会是空的）。

判据 2（行序）与 3（逐格列位置）、3 与 12（字越边）用的是同一批坐标，改坏列归属必然同时红到
邻居；每条反例只要求"该点的那条红"，另外红了什么会一并打印出来核看。
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
BUILD = os.path.join(ROOT, "core", "build", "pdftable")
PLAN = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "pdf", "PrintPlan.kt")
XLSX = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "office", "XlsxSheets.kt")
DOCX = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "office", "DocxRead.kt")
RTF = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "office", "RtfRead.kt")
ODT = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "office", "OdtRead.kt")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
# 探针那一步换成 /d/ 写法：run.sh 拿它 find kotlin-stdlib，D:/gradle-home 里那个冒号会被
# classpath 的冒号分隔符切成两截（表现是"kotlin.jvm.functions 这个包不存在"）
REBUILD = ("GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:jar -q && "
           "python tools/make_pdf_table_fixtures.py && python tools/make_pdf_doc_fixtures.py && "
           "for f in book wide long; do "
           "GRADLE_USER_HOME=/d/gradle-home PDFPROBE_OUT=/d/fflogs/pdfprobe "
           "bash tools/pdfprobe/run.sh pdftable C:/Windows/Fonts/simhei.ttf "
           "core/build/pdftable/$f.plan.tsv core/build/pdftable/$f.pdf || exit 1; "
           "GRADLE_USER_HOME=/d/gradle-home PDFPROBE_OUT=/d/fflogs/pdfprobe "
           "bash tools/pdfprobe/run.sh xlsxpdf C:/Windows/Fonts/simhei.ttf "
           "core/build/pdftable/$f.xlsx core/build/pdftable/$f-app.pdf || exit 1; done && "
           "for f in docx odt pptx rtf md html; do "
           "GRADLE_USER_HOME=/d/gradle-home PDFPROBE_OUT=/d/fflogs/pdfprobe "
           "bash tools/pdfprobe/run.sh docpdf C:/Windows/Fonts/simhei.ttf "
           "core/build/pdfdocs/brief.$f core/build/pdfdocs/brief-$f.pdf || exit 1; done")
VERIFY = ["python", "tools/verify_pdf_table.py"]

# 每条：判据号、名字、文件、原样、改成
CASES = [
    {
        "判据": 1, "名字": "永不翻页（60 行的表印成一整页）", "文件": PLAN,
        "原样": "                if (y + heights[index] > bottom) {",
        "改成": "                if (y + heights[index] > bottom * 9f) {",
    },
    {
        "判据": 2, "名字": "一张表的行倒着摆", "文件": PLAN,
        "原样": "            val cells = table.rows.map { row -> rowCells(row, fit.widths, padX) }",
        "改成": "            val cells = table.rows.reversed().map { row -> rowCells(row, fit.widths, padX) }",
    },
    {
        "判据": 3, "名字": "每格都画在第一列的位置上", "文件": PLAN,
        "原样": "                val left = edges[index] + padX",
        "改成": "                val left = edges[0] + padX",
    },
    {
        "判据": 4, "名字": "这段表顶上的线只画一小截", "文件": PLAN,
        "原样": "            rules += PrintRule(edges.first(), top, edges.last(), top, RULE_THIN)",
        "改成": "            rules += PrintRule(edges.first(), top, edges.first() + 40f, top, RULE_THIN)",
    },
    {
        "判据": 5, "名字": "表头下面那条线不加粗", "文件": PLAN,
        "原样": "                    if (index == 0 && table.header) RULE_HEAD else RULE_THIN,",
        "改成": "                    if (false && index == 0 && table.header) RULE_HEAD else RULE_THIN,",
    },
    {
        "判据": 6, "名字": "翻页后不重复表头", "文件": PLAN,
        "原样": "                    if (table.header && index > 0) {",
        "改成": "                    if (false && table.header && index > 0) {",
    },
    {
        "判据": 7, "名字": "压窄的说明多报 30pt", "文件": PLAN,
        "原样": 'pt 压到 ${"%.0f".format(widths[index])}pt"',
        "改成": 'pt 压到 ${"%.0f".format(widths[index] + 30f)}pt"',
    },
    {
        "判据": 8, "名字": "格子里的硬换行当空格吃", "文件": PLAN,
        "原样": "private fun splitLines(text: String): List<String> = text.split('\\n')",
        "改成": "private fun splitLines(text: String): List<String> = listOf(text.replace('\\n', ' '))",
    },
    {
        "判据": 9, "名字": "二级标题不抬字号", "文件": PLAN,
        "原样": "                2 -> 1.4f",
        "改成": "                2 -> 1f",
    },
    {
        "判据": 10, "名字": "空表也占一节（标题照样印出去）", "文件": XLSX,
        "原样": "            if (sheet.rows.isEmpty()) return@forEach",
        "改成": "            if (false && sheet.rows.isEmpty()) return@forEach",
    },
    {
        "判据": 11, "名字": "最后一列的字干脆不画", "文件": PLAN,
        "原样": "            for (index in cells.indices) {",
        "改成": "            for (index in 0 until cells.size - 1) {",
    },
    {
        "判据": 12, "名字": "压窄不设保底（列宽只剩一条缝）", "文件": PLAN,
        "原样": "            floor += (if (widestChar > 0f) widestChar else size).coerceAtLeast(size * 0.6f) + padX * 2",
        "改成": "            floor += padX * 0.3f",
    },
    {
        "判据": 13, "名字": "交卷前多封一页空的", "文件": PLAN,
        "原样": "        fun finish(): PrintPlan {\n            seal()",
        "改成": "        fun finish(): PrintPlan {\n            pages += PrintPage(emptyList(), emptyList())\n            seal()",
    },
    {
        "判据": 14, "名字": "应用那条链子少读一行（每张表的第一行没了）", "文件": XLSX,
        "原样": "            out += DocTable(header = false, sheet.rows)",
        "改成": "            out += DocTable(header = false, sheet.rows.drop(1))",
    },
    {
        "判据": 16, "名字": "docx 的表少读一格（每行丢掉第一格）", "文件": DOCX,
        "原样": "            rows += cells",
        "改成": "            rows += cells.drop(1)",
    },
    {
        "判据": 17, "名字": "odt 的标题不抬层级", "文件": ODT,
        "原样": '        return DocParagraph(DocPara(merge(flat), "Heading${level.coerceIn(1, 6)}"))',
        "改成": '        return DocParagraph(DocPara(merge(flat), "Body"))',
    },
    {
        "判据": 18, "名字": "圆点列表不画记号", "文件": PLAN,
        "原样": '                bullet                                       // 这一笔由调用方按字体给：字体没有 U+2022 时',
        "改成": '                ""                                         // 圆点不画：列表就跟正文没区别了',
    },
    {
        "判据": 19, "名字": "记号那一列不留位置（文字压上去）", "文件": PLAN,
        "原样": "else widthOf(marker, sizeNow) + sizeNow * 0.5f",
        "改成": "else 0f",
    },
    {
        "判据": 20, "名字": "引用不往里缩", "文件": PLAN,
        "原样": '            if (para.style == "Quote") indent += size * 1.5f',
        "改成": '            if (para.style == "Quote") indent += 0f',
    },
    {
        # 名字里留一个 ASCII 标记（margin）：Windows 的 Python 收命令行按 cp936 解，
        # 中文的 --only 过滤词会变形，只剩 ASCII 片段可靠
        "判据": 21, "名字": "页边距没算进位置（字贴到纸边）margin", "文件": PLAN,
        "原样": "                        val at = margin + indent + markerWidth",
        "改成": "                        val at = indent + markerWidth",
    },
    {
        # 名字里带 ASCII 标记 rtflevel：Windows 的 Python 收命令行按 cp936 解，中文过滤词会变形
        "判据": 22, "名字": "段落上写的层级不认了 rtflevel", "文件": RTF,
        "原样": '            "outlinelevel" -> if (argument != null && argument in 0..7) inlineLevel = argument',
        "改成": '            "outlinelevel" -> Unit',
    },
    {
        "判据": 15, "名字": "说明里多报一张表", "文件": XLSX,
        "原样": '        out += "${made.size} 张表 · ${made.sumOf { it.rows.size }} 行"',
        "改成": '        out += "${made.size + 1} 张表 · ${made.sumOf { it.rows.size }} 行"',
    },
]

PRODUCTS = ["book.pdf", "wide.pdf", "long.pdf", "book-app.pdf", "wide-app.pdf", "long-app.pdf"]
DOCS = os.path.join(ROOT, "core", "build", "pdfdocs")
DOC_PRODUCTS = ["brief-%s.pdf" % kind for kind in ("docx", "odt", "pptx", "rtf", "md", "html")]


def sha1_of(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha1(handle.read()).hexdigest()


def wipe() -> None:
    """只删 PDF 与说明，夹具（.xlsx / .plan.tsv / pandoc 写的四种文档）留着当来料。"""
    for folder in (BUILD, DOCS):
        if not os.path.isdir(folder):
            continue
        for name in os.listdir(folder):
            if name.endswith(".pdf") or name.endswith(".notes.txt"):
                os.remove(os.path.join(folder, name))


def rebuilt() -> tuple:
    """重编 jar + 重落夹具 + 重跑探针。返回（编译或探针红了吗, 这一轮真写盘了吗, 线索）。

    每轮开头先把 PDF 全删了，所以"产物在不在"就是"这一轮有没有真写盘"的证据 ——
    比只比 mtime 更硬（Gradle 认定 UP-TO-DATE 跳过探针时，旧文件还在会把没跑说成跑过）。
    """
    run = subprocess.run(["bash", "-c", REBUILD], cwd=ROOT, capture_output=True, env=ENV)
    out = (run.stdout or b"").decode("utf-8", "replace") + (run.stderr or b"").decode("utf-8", "replace")
    missing = [os.path.join(BUILD, name) for name in PRODUCTS if not os.path.isfile(os.path.join(BUILD, name))]
    missing += [os.path.join(DOCS, name) for name in DOC_PRODUCTS if not os.path.isfile(os.path.join(DOCS, name))]
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
    print("开工：%s" % "、".join("%s sha1 %s…" % (os.path.basename(path), digests[path][:10]) for path in files))
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
        _broken, made, clue = rebuilt()          # 产物必须对应还原后的代码
        print("\n还原核对：%s；干净代码重落产物=%s" % (
            "、".join("%s sha1 %s（%s）" % (os.path.basename(path), sha1_of(path)[:10],
                                     "与开工前一致" if sha1_of(path) == digests[path] else "不一致")
                      for path in files), made))
        if not made:
            print("  警告：还原后没能重落产物，%s" % clue)
    print("\n共 %d 个反例跑到判定，%d 个没红到该点的条" % (passed + len(bad), len(bad)))
    for kind, case, detail in bad:
        print("  %s：%s（判据 %s）%s" % (kind, case["名字"], case["判据"], detail))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
