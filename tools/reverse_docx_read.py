#!/usr/bin/env python3
"""
给 tools/verify_docx_read.py 喂反例：把 docx 的结构读法改坏，断言点名到该点的那条。

跑法：
    python tools/reverse_docx_read.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_docx_read.py --only 句中链接被挪到句尾
    python tools/reverse_docx_read.py --list

改坏的是 Kotlin 源文件（core/office/DocxRead.kt）：原字节先留下，跑完原样写回，
最后按 sha1 核对并用 git status 证明还原干净 —— 不用 git checkout 撤销。
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

READER = "core/src/main/kotlin/com/fileforge/core/office/DocxRead.kt"
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.DocxReadTest" --rerun'

TAMPERS = [
    {
        "名字": "句中链接被挪到句尾",
        "判据": 4,
        "原样": "        val runs = inlineRuns(element, null, links, tally)",
        "改成": "        val runs = inlineRuns(element, null, links, tally).reversed()",
    },
    {
        "名字": "样式名一律当正文（层级全丢）",
        "判据": 5,
        "原样": '?.let { attr(it, "val") }?.let { styles[it] } ?: "Body"',
        "改成": '?.let { attr(it, "val") }?.let { "Body" } ?: "Body"',
    },
    {
        "名字": "列表一律按圆点排",
        "判据": 6,
        "原样": "                bullet = !formats.getOrElse(indent) { formats.last() }",
        "改成": "                bullet = true",
    },
    {
        "名字": "链接地址不当回事",
        "判据": 8,
        "原样": "        tally.bump(\"link\")\n        return target",
        "改成": "        tally.bump(\"link\")\n        return null",
    },
    {
        "名字": "pStyle 当成父元素上的属性读",
        "判据": 5,
        "原样": 'properties?.let { childrenOf(it, "pStyle").firstOrNull() }?.let { attr(it, "val") }',
        "改成": 'properties?.let { attr(it, "pStyle") }',
    },
    {
        # 一处改动就得真把删掉的话搬进结果：只让 inlineRuns 下钻 w:del 是不够的 ——
        # 被删的字写在 w:delText 里，runs() 本来就不读那个标签。这里直接取整棵子树的文字。
        "名字": "修订里删掉的字也留下",
        "判据": 9,
        "原样": '                "del" -> tally.bump("del")',
        "改成": '                "del" -> { out += DocRun(child.textContent); tally.bump("del") }',
    },
    {
        "名字": "表格整块不读",
        "判据": 7,
        "原样": '                "tbl" -> table(element, styles, numbering, links, tally)?.let { parts += it }',
        "改成": '                "tbl" -> Unit',
    },
]


def read(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def run(cmd: str) -> int:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace", env=ENV).returncode


def wipe_artifacts() -> None:
    """每轮先把上一次的产物删掉：留着的旧文件会让判据在"改坏了实现"之后仍然全绿。"""
    shutil.rmtree(os.path.join("core", "build", "docxread"), ignore_errors=True)


def run_referee() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_docx_read.py")],
                          capture_output=True, text=True, encoding="utf-8")
    return done.returncode, re.findall(r"^FAIL (\d+) ", done.stdout, re.M), done.stdout + done.stderr


def main(argv: list) -> int:
    if "--list" in argv:
        for case in TAMPERS:
            print("%d  %s" % (case["判据"], case["名字"]))
        return 0
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    original = read(READER)
    baseline_hash = hashlib.sha1(original).hexdigest()
    text = original.decode("utf-8")

    def restore():
        with open(READER, "wb") as handle:
            handle.write(original)

    wipe_artifacts()
    if run(DUMP) != 0:
        print("基线就没绿：先修好 :core:test 再谈反例")
        return 1
    code, reds, output = run_referee()
    if code != 0:
        print("判据基线就红，反例还没测：\n%s" % output[-1200:])
        return 1
    if "--comply" in argv:
        print("合规面：判据全绿（照规格做出来的三份产物被收）")
        return 0

    misses = []
    tried = 0
    for case in TAMPERS:
        if only and only != case["名字"]:
            continue
        tried += 1
        if case["原样"] not in text:
            print("反例失效 %-22s 源文件里找不到锚点（实现改过，这条得跟着改）" % case["名字"])
            misses.append((case["名字"], "锚点没了"))
            continue
        with open(READER, "w", encoding="utf-8", newline="\n") as handle:
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
            note = "单元测试拦了，判据没红（红的是 %s）" % (sorted(set(reds)) or "无") if not tests_green \
                else "判据红了 %s，没点到 %d" % (sorted(set(reds)), case["判据"])
            print("点错名 %-24s %s" % (case["名字"], note))
            misses.append((case["名字"], note))
        else:
            print("有牙   %-24s 判据 %d 红了（单元测试%s；共红 %s）"
                  % (case["名字"], case["判据"], "也拦了一道" if not tests_green else "没拦住", sorted(set(reds))))

    if hashlib.sha1(read(READER)).hexdigest() != baseline_hash:
        print("源文件没还原干净")
        return 1
    status = subprocess.run(["git", "status", "--short", "--", READER], capture_output=True, text=True,
                            encoding="utf-8").stdout.strip()
    print("\n试了 %d 个反例，问题 %d 个；源文件按 sha1 还原 ✓  git status：%s"
          % (tried, len(misses), status if status else "干净"))
    for name, why in misses:
        print("  - %s：%s" % (name, why))
    return 1 if misses else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
