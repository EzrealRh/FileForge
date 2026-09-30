#!/usr/bin/env python3
"""
给 tools/verify_xlsx_read.py 喂反例：把"一批表 → 文档树"那一步与读格子的取值改坏，
断言判据点名到该点的那条。

跑法：
    python tools/reverse_xlsx_read.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_xlsx_read.py --only 格子不按引用对位
    python tools/reverse_xlsx_read.py --list

改坏的是 Kotlin 源文件（core/office/XlsxSheets.kt 与 core/office/Xlsx.kt）：原字节先留下，
跑完原样写回，最后按 sha1 核对并用 git status 证明还原干净 —— 不用 git checkout 撤销。
每轮先删掉上一次的产物再重跑落盘：留着的旧文件会让判据拿着好产物说"全绿"。
判据一条没红但产物没落盘、或判据崩了，分开报，不混成"改不坏"。
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

TREE = "core/src/main/kotlin/com/fileforge/core/office/XlsxSheets.kt"
GRID = "core/src/main/kotlin/com/fileforge/core/office/Xlsx.kt"
BUILD = os.path.join("core", "build", "xlsxread")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.XlsxTest" --rerun'

CASES = [
    {
        "名字": "空的表也占一节",
        "判据": 3,
        "文件": TREE,
        "原样": "            if (sheet.rows.isEmpty()) return@forEach",
        "改成": "            if (false) return@forEach",
    },
    {
        "名字": "表名不当小标题（层级没了）",
        "判据": 3,
        "文件": TREE,
        "原样": '            out += DocParagraph(DocPara(listOf(DocRun(sheet.name)), "Heading2"))',
        "改成": '            out += DocParagraph(DocPara(listOf(DocRun(sheet.name)), "Body"))',
    },
    {
        "名字": "表序按名字排而不是照工作簿声明",
        "判据": 3,
        "文件": TREE,
        "原样": "        refs.forEach { ref ->",
        "改成": "        refs.sortedBy { it.name }.forEach { ref ->",
    },
    {
        "名字": "首行一律抬成表头",
        "判据": 8,
        "文件": TREE,
        "原样": "            out += DocTable(header = false, sheet.rows)",
        "改成": "            out += DocTable(header = true, sheet.rows)",
    },
    {
        "名字": "窄的行不补到最宽的列数",
        "判据": 5,
        "文件": GRID,
        "原样": '        val square = body.map { row -> if (row.size == width) row else row + List(width - row.size) { "" } }',
        "改成": "        val square = body",
    },
    {
        "名字": "张数写死成一个",
        "判据": 10,
        "文件": TREE,
        "原样": '        out += "${made.size} 张表 · ${made.sumOf { it.rows.size }} 行"',
        "改成": '        out += "1 张表 · ${made.sumOf { it.rows.size }} 行"',
    },
    {
        "名字": "空表被跳过却不说",
        "判据": 9,
        "文件": TREE,
        "原样": '        if (empty.isNotEmpty()) out += "${empty.size} 张表是空的（${empty.joinToString("、") { it.name }}），没占一节"',
        "改成": "        if (false) out += \"空\"",
    },
    {
        "名字": "日期不转，留着序列号",
        "判据": 6,
        "文件": GRID,
        "原样": "        return withTime(date.format(DAY), seconds)",
        "改成": "        return raw",
    },
    {
        "名字": "数字被重新格式化（38.5 变成 38.50）",
        "判据": 7,
        "文件": GRID,
        "原样": '        val raw = OoxmlStructure.childrenOf(cell, "v").firstOrNull()?.textContent?.trim().orEmpty()',
        "改成": '        val raw = OoxmlStructure.childrenOf(cell, "v").firstOrNull()?.textContent?.trim()\n'
                '            ?.let { text -> text.toDoubleOrNull()?.let { number -> "%.2f".format(number) } ?: text }\n'
                "            .orEmpty()",
    },
    {
        "名字": "格子不按引用对位（按出现顺序排）",
        "判据": 4,
        "文件": GRID,
        "原样": '                val column = if (ref.isNotBlank()) columnOf(ref) else cells.size',
        "改成": "                val column = cells.size",
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def run(cmd: str) -> int:
    code, output = run_and_log(cmd)
    return code


def run_and_log(cmd: str) -> tuple:
    done = subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=ENV)
    return done.returncode, (done.stdout or "") + (done.stderr or "")


def wipe_artifacts() -> None:
    shutil.rmtree(BUILD, ignore_errors=True)


def run_referee() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_xlsx_read.py")],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    return done.returncode, re.findall(r"^FAIL\s+(\d+) ", done.stdout, re.M), done.stdout + done.stderr


def main(argv: list) -> int:
    if "--list" in argv:
        for case in CASES:
            print("%2d  %s" % (case["判据"], case["名字"]))
        return 0
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    files = sorted({case["文件"] for case in CASES})
    originals = {path: read_bytes(path) for path in files}
    texts = {path: originals[path].decode("utf-8") for path in files}

    def restore():
        for path, blob in originals.items():
            with open(path, "wb") as handle:
                handle.write(blob)

    wipe_artifacts()
    if run(DUMP) != 0:
        print("基线就没绿：先修好 :core:test 再谈反例")
        return 1
    code, reds, output = run_referee()
    if code != 0:
        print("判据基线就红，反例还没测：\n%s" % output[-1500:])
        return 1
    if "--comply" in argv:
        print("合规面：判据全绿（照规格做出来的产物被收），反例还没跑")
        return 0

    misses = []
    tried = 0
    for case in CASES:
        if only and only != case["名字"]:
            continue
        tried += 1
        path = case["文件"]
        text = texts[path]
        if text.count(case["原样"]) != 1:
            print("反例失效 %-22s 锚点出现 %d 次（实现改过，这条得跟着改）"
                  % (case["名字"], text.count(case["原样"])))
            misses.append((case["名字"], "锚点不唯一或没了"))
            continue
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text.replace(case["原样"], case["改成"], 1))
        wipe_artifacts()
        tests_green, dump_out = run_and_log(DUMP)
        tests_green = tests_green == 0
        dumped = os.path.exists(os.path.join(BUILD, "book.md"))
        code, reds, output = run_referee()
        restore()
        wipe_artifacts()
        run(DUMP)                                     # 产物也回到没改坏时的那一份
        if not dumped:
            clues = [line.strip()[:110] for line in dump_out.splitlines()
                     if line.startswith("e: ") or "FAILED" in line or "What went wrong" in line]
            print("没产物 %-24s 落盘测试没写出 %s；判据无从跑起。gradle 线索：%s"
                  % (case["名字"], BUILD, clues or dump_out.strip().splitlines()[-3:]))
            misses.append((case["名字"], "判据没跑起来（没有产物）"))
            continue
        if code != 0 and not reds:
            print("判据崩了 %-23s 没有一条 FAIL，输出尾部：%s" % (case["名字"], output[-300:].strip()))
            misses.append((case["名字"], "判据崩了，没点到名"))
            continue
        if code == 0 and tests_green:
            print("没牙   %-26s 单元测试与判据都没红" % case["名字"])
            misses.append((case["名字"], "全绿"))
        elif str(case["判据"]) not in reds:
            note = ("单元测试拦了，判据没红（红的是 %s）" % (sorted(set(reds)) or "无")) if not tests_green \
                else "判据红了 %s，没点到 %d" % (sorted(set(reds)), case["判据"])
            print("点错名 %-26s %s" % (case["名字"], note))
            misses.append((case["名字"], note))
        else:
            print("有牙   %-26s 判据 %2d 红了（单元测试%s；共红 %s）"
                  % (case["名字"], case["判据"], "也拦了一道" if not tests_green else "没拦住", sorted(set(reds))))

    dirty = [path for path in files
             if hashlib.sha1(read_bytes(path)).hexdigest() != hashlib.sha1(originals[path]).hexdigest()]
    if dirty:
        print("源文件没还原干净：%s" % dirty)
        return 1
    status = subprocess.run(["git", "status", "--short", "--"] + files,
                            capture_output=True, text=True, encoding="utf-8").stdout.strip()
    print("\n试了 %d 个反例，问题 %d 个；源文件按 sha1 还原 ✓  git status：%s"
          % (tried, len(misses), status if status else "干净"))
    for name, why in misses:
        print("  - %s：%s" % (name, why))
    return 1 if misses else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
