#!/usr/bin/env python3
"""
给 tools/verify_odt_read.py 喂反例：把 ODT 的读法（或渲染层）改坏，断言判据点名到该点的那条。

跑法：
    python tools/reverse_odt_read.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_odt_read.py --only 合并列不补空
    python tools/reverse_odt_read.py --list

改坏的是 Kotlin 源文件：原字节先留下，跑完原样写回，最后按 sha1 核对并用 git status 证明还原干净
—— 不用 git checkout 撤销（那会连同一轮别的改动一起吞掉）。
每条都记一下是单元测试先拦住、还是外部判据拦住的：两边都拦才说明这条既进得了自家测试也进得了别人眼睛。
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

READER = "core/src/main/kotlin/com/fileforge/core/office/OdtRead.kt"
RENDER = "core/src/main/kotlin/com/fileforge/core/doc/HtmlWrite.kt"
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.OdtReadTest" --rerun'

CASES = [
    {
        "名字": "段落样式不顺父链看",
        "判据": 4,
        "文件": READER,
        "原样": "            val kind = canonical(current)",
        "改成": "            val kind = canonical(current)\n            return kind",
    },
    {
        "名字": "样式名的下划线编码不还原",
        "判据": 4,
        "文件": READER,
        "原样": "        if (!name.contains('_')) return name",
        "改成": "        if (name.isNotEmpty()) return name",
    },
    {
        "名字": "合并列不补空列",
        "判据": 10,
        "文件": READER,
        "原样": '        val spanned = (attr(element, "table:number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)',
        "改成": "        val spanned = 1",
    },
    {
        "名字": "重复列当一格",
        "判据": 10,
        "文件": READER,
        "原样": '        var repeated = attr(element, "table:number-columns-repeated")?.toIntOrNull() ?: 1',
        "改成": "        var repeated = 1",
    },
    {
        "名字": "表格整块不读",
        "判据": 4,
        "文件": READER,
        "原样": '                "table" -> table(element, ctx)?.let { out += it }',
        "改成": '                "table" -> Unit',
    },
    {
        "名字": "链接地址不当回事",
        "判据": 11,
        "文件": READER,
        "原样": '        ctx.tally.bump("link")\n        out += inline(link, target, marks, ctx)',
        "改成": '        ctx.tally.bump("link")\n        out += inline(link, null, marks, ctx)',
    },
    {
        "名字": "段内跳转被当成外部地址",
        "判据": 12,
        "文件": READER,
        "原样": '        if (target.startsWith("#")) {',
        "改成": "        if (target.isEmpty()) {",
    },
    {
        "名字": "脚注正文混进正文",
        "判据": 13,
        "文件": READER,
        "原样": '                "note" -> ctx.tally.bump("note")',
        "改成": '                "note" -> { out += inline(child, link, marks, ctx); ctx.tally.bump("note") }',
    },
    {
        "名字": "批注正文混进正文",
        "判据": 13,
        "文件": READER,
        "原样": '                "note" -> ctx.tally.bump("note")\n                "annotation" -> ctx.tally.bump("annotation")',
        "改成": '                "note" -> ctx.tally.bump("note")\n                "annotation" -> { out += inline(child, link, marks, ctx); ctx.tally.bump("annotation") }',
    },
    {
        "名字": "图形不计数（交代与实际不符）",
        "判据": 14,
        "文件": READER,
        "原样": '                "frame", "image", "object" -> ctx.tally.bump("drawing")',
        "改成": '                "frame", "image", "object" -> Unit',
    },
    {
        "名字": "圆点与编号不分",
        "判据": 9,
        "文件": READER,
        "原样": "        return !numbered",
        "改成": "        return numbered",
    },
    {
        "名字": "列表层级不按嵌套算",
        "判据": 9,
        "文件": READER,
        "原样": "        val depth = parentDepth + 1",
        "改成": "        val depth = 0",
    },
    {
        "名字": "标题层级一律当一级",
        "判据": 8,
        "文件": READER,
        "原样": '        val level = attr(element, "text:outline-level")?.toIntOrNull()\n'
                '            ?: Regex("^Heading([1-6])$").find(named)?.groupValues?.get(1)?.toInt()\n'
                "            ?: 1",
        "改成": "        val level = 1",
    },
    {
        "名字": "正文第一处文字丢掉",
        "判据": 7,
        "文件": READER,
        "原样": "        return DocParagraph(DocPara(merge(runs), style))",
        "改成": "        return DocParagraph(DocPara(merge(runs).drop(1), style))",
    },
    {
        "名字": "text:s 的个数丢掉",
        "判据": 15,
        "文件": READER,
        "原样": '                "s" -> append(out, " ".repeat((attr(child, "text:c")?.toIntOrNull() ?: 1).coerceIn(1, 999)), link, marks)',
        "改成": '                "s" -> append(out, " ", link, marks)',
    },
    {
        "名字": "代码块不并成一整块",
        "判据": 4,
        "文件": RENDER,
        "原样": '            if (para != null && para.style == "SourceCode") {',
        "改成": '            if (para != null && para.style == "SourceCode" && false) {',
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def run(cmd: str) -> int:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=ENV).returncode


def wipe_artifacts() -> None:
    """每轮先把上一次的产物删掉：留着的旧文件会让判据在"改坏了实现"之后仍然全绿。"""
    shutil.rmtree(os.path.join("core", "build", "odtread"), ignore_errors=True)


def run_referee() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_odt_read.py")],
                          capture_output=True, text=True, encoding="utf-8")
    return done.returncode, re.findall(r"^FAIL (\d+) ", done.stdout, re.M), done.stdout + done.stderr


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
        print("合规面：判据全绿（%d 条），反例还没跑" % len(output.splitlines()))
        return 0

    misses = []
    tried = 0
    for case in CASES:
        if only and only != case["名字"]:
            continue
        tried += 1
        path = case["文件"]
        text = texts[path]
        if case["原样"] not in text:
            print("反例失效 %-22s %s 里找不到锚点（实现改过，这条得跟着改）" % (case["名字"], os.path.basename(path)))
            misses.append((case["名字"], "锚点没了"))
            continue
        if text.count(case["原样"]) != 1:
            print("反例失效 %-22s 锚点出现 %d 次，改的会不是要改的那处"
                  % (case["名字"], text.count(case["原样"])))
            misses.append((case["名字"], "锚点不唯一"))
            continue
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text.replace(case["原样"], case["改成"], 1))
        wipe_artifacts()
        tests_green = run(DUMP) == 0
        code, reds, _ = run_referee()
        restore()
        wipe_artifacts()
        run(DUMP)                                     # 产物也回到没改坏时的那一份
        if code == 0 and tests_green:
            print("没牙   %-24s 单元测试与判据都没红" % case["名字"])
            misses.append((case["名字"], "全绿"))
        elif str(case["判据"]) not in reds:
            note = ("单元测试拦了，判据没红（红的是 %s）" % (sorted(set(reds)) or "无")) if not tests_green \
                else "判据红了 %s，没点到 %d" % (sorted(set(reds)), case["判据"])
            print("点错名 %-24s %s" % (case["名字"], note))
            misses.append((case["名字"], note))
        else:
            print("有牙   %-24s 判据 %d 红了（单元测试%s；共红 %s）"
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
