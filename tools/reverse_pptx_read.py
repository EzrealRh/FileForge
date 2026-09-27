#!/usr/bin/env python3
"""
给 tools/verify_pptx_read.py 喂反例：把 pptx 的结构读法改坏，断言判据点名到该点的那条。

跑法：
    python tools/reverse_pptx_read.py                # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_pptx_read.py --only 跨列当一格
    python tools/reverse_pptx_read.py --list

改坏的是 Kotlin 源文件（core/office/PptxRead.kt 与 core/doc/Html.kt 的表头那一行）：
原字节先留下，跑完原样写回，最后按 sha1 核对并用 git status 证明还原干净 —— 不用 git checkout 撤销。
每条都记一下是单元测试先拦住、还是外部判据拦住的：两边都拦才说明这条既进得了自家测试也进得了别人眼睛。

段内制表（`a:tab`）这里没有反例：判据比文字时把空白并掉了（pandoc 自己读 pptx 也把制表吞成空白），
那种丢法只有自家单元测试判得出来 —— 见 PptxReadTest 的「段内换行与制表在字里」。
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

READER = "core/src/main/kotlin/com/fileforge/core/office/PptxRead.kt"
RENDER = "core/src/main/kotlin/com/fileforge/core/doc/Html.kt"
BUILD = os.path.join("core", "build", "pptxread")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.PptxReadTest" --rerun'

CASES = [
    {
        "名字": "页界那一行不补",
        "判据": 7,
        "文件": READER,
        "原样": '            parts += DocParagraph(DocPara(listOf(DocRun("第 ${index + 1} 页")), "Heading1"))',
        "改成": "            Unit",
    },
    {
        "名字": "标题占位符当正文（层级丢了）",
        "判据": 8,
        "文件": READER,
        "原样": '        val title = type.equals("title", true) || type.equals("ctrTitle", true) ||',
        "改成": '        val title = type.equals("ctrTitle", true) ||',
    },
    {
        "名字": "表格整块不读",
        "判据": 4,
        "文件": READER,
        "原样": '            OoxmlStructure.findAll(frame, "tbl").forEach { table ->\n'
                "                table(table, links, tally, out)\n"
                "            }",
        "改成": '            OoxmlStructure.findAll(frame, "tbl").forEach { table -> Unit }',
    },
    {
        "名字": "编号记号当圆点",
        "判据": 9,
        "文件": READER,
        "原样": '        if (childrenOf(properties, "buAutoNum").isNotEmpty()) return false',
        "改成": '        if (childrenOf(properties, "buAutoNum").isNotEmpty()) return true',
    },
    {
        "名字": "没写记号推成圆点",
        "判据": 9,
        "文件": READER,
        "原样": '        tally.bump("noMarker")\n        return null',
        "改成": '        tally.bump("noMarker")\n        return true',
    },
    {
        "名字": "跨列当一格",
        "判据": 10,
        "文件": READER,
        "原样": '        val spanned = (intAttr(cell, "gridSpan") ?: 1).coerceAtLeast(1)',
        "改成": "        val spanned = 1",
    },
    {
        "名字": "跨列与续格双计一次",
        "判据": 10,
        "文件": READER,
        "原样": "            if (claimed > 0 && continuesMerge(cell)) {\n"
                "                claimed--\n"
                "                return@children\n"
                "            }",
        "改成": "            if (claimed > 0 && continuesMerge(cell)) {\n"
                "                claimed--\n"
                "            }",
    },
    {
        "名字": "表头一律当有（没写 firstRow 也当表头）",
        "判据": 15,
        "文件": READER,
        "原样": '        val header = properties?.let { flag(it, "firstRow") } == true',
        "改成": "        val header = true",
    },
    {
        "名字": "写 Markdown 时不看 th，首行总是表头",
        "判据": 15,
        "文件": RENDER,
        "原样": '        val header = tableRows(node).firstOrNull()?.children?.any { it.name == "th" } == true\n'
                '        out += line(if (header) rows.first() else List(width) { "" })',
        "改成": '        val header = true\n'
                '        out += line(rows.first())',
    },
    {
        "名字": "链接地址不当回事",
        "判据": 11,
        "文件": READER,
        "原样": '        tally.bump("link")\n        return rel.target',
        "改成": "        tally.bump(\"link\")\n        return null",
    },
    {
        "名字": "段内跳转当成外部地址搬走",
        "判据": 12,
        "文件": READER,
        "原样": '            tally.bump("linkInternal")\n            return null',
        "改成": '            tally.bump("linkInternal")\n            return rel.target',
    },
    {
        "名字": "没算过的域编一个字出来",
        "判据": 13,
        "文件": READER,
        "原样": '                    if (made.isEmpty()) tally.bump("field") else out += DocRun(made, link = link)',
        "改成": '                    if (made.isEmpty()) { tally.bump("field"); out += DocRun("1", link = link) }'
                " else out += DocRun(made, link = link)",
    },
    {
        "名字": "图片不数笔（交代与实际不符）",
        "判据": 14,
        "文件": READER,
        "原样": '        OoxmlStructure.childrenOf(tree, "pic").forEach { _ -> tally.bump("pic") }',
        "改成": '        OoxmlStructure.childrenOf(tree, "pic").forEach { _ -> Unit }',
    },
    {
        "名字": "空域不数笔",
        "判据": 14,
        "文件": READER,
        "原样": '                    if (made.isEmpty()) tally.bump("field") else out += DocRun(made, link = link)',
        "改成": '                    if (made.isEmpty()) Unit else out += DocRun(made, link = link)',
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def run(cmd: str) -> int:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=ENV).returncode


def wipe_artifacts() -> None:
    """每轮先删掉上一次的产物：留着的旧文件会让判据拿着好产物说"全绿"。"""
    shutil.rmtree(BUILD, ignore_errors=True)


def run_referee() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_pptx_read.py")],
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
            print("反例失效 %-24s 锚点出现 %d 次（实现改过，这条得跟着改）"
                  % (case["名字"], text.count(case["原样"])))
            misses.append((case["名字"], "锚点不唯一或没了"))
            continue
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text.replace(case["原样"], case["改成"], 1))
        wipe_artifacts()
        tests_green = run(DUMP) == 0
        dumped = os.path.exists(os.path.join(BUILD, "deck.md"))
        code, reds, output = run_referee()
        restore()
        wipe_artifacts()
        run(DUMP)                                     # 产物也回到没改坏时的那一份
        if not dumped:
            # 判据一条都没红不是因为实现改不坏，而是它根本没跑：这两种情况不能混着报
            print("没产物 %-24s 落盘测试没写出 %s，判据无从跑起" % (case["名字"], BUILD))
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
