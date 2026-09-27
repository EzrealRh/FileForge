#!/usr/bin/env python3
"""
给 tools/verify_ods.py 喂反例：把 ODS 的读法或写法改坏，断言判据点名到该点的那条。

跑法：
    python tools/reverse_ods.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_ods.py --only 尾巴整排空行不剪
    python tools/reverse_ods.py --list

每轮先删掉上一次的产物再重跑落盘：留着旧文件时，判据会拿着上一轮的好产物说"全绿"（真发生过）。
源文件按 sha1 还原，跑完用 git status 证明没留改动 —— 不用 git checkout 撤销。
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

READER = "core/src/main/kotlin/com/fileforge/core/office/OdsRead.kt"
WRITER = "core/src/main/kotlin/com/fileforge/core/office/OdsWrite.kt"
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.OdsTest" --rerun'

CASES = [
    {
        "名字": "重复列当一格",
        "判据": 2,
        "文件": READER,
        "原样": '        val spanned = (attr(element, "table:number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)',
        "改成": "        val spanned = 1",
    },
    {
        "名字": "合并的两种写法双计一次",
        "判据": 2,
        "文件": READER,
        '原样': '                "covered-table-cell" -> if (coveredLeft-- > 0) Unit else cells += ""',
        "改成": '                "covered-table-cell" -> { coveredLeft = 0; cells += "" }',
    },
    {
        "名字": "尾巴整排空行不剪",
        "判据": 7,
        "文件": READER,
        "原样": "        while (end > 0 && rows[end - 1].all { it.isEmpty() }) end--",
        "改成": "        while (false && end > 0) end--",
    },
    {
        "名字": "连中间真空的行也一起剪掉",
        "判据": 8,
        "文件": READER,
        "原样": "        return rows.subList(0, end).map { row -> row.stripTrailingBlanks() }",
        "改成": "        return rows.subList(0, end).filter { row -> row.any { it.isNotEmpty() } }.map { it.stripTrailingBlanks() }",
    },
    {
        "名字": "不看渲染文字直接用存的数值",
        "判据": 2,
        "文件": READER,
        "原样": "        val rendered = paragraphs.joinToString(\"\\n\")\n        if (rendered.isNotEmpty()) return rendered",
        "改成": "        val rendered = paragraphs.joinToString(\"\\n\")\n        if (rendered.isEmpty()) return rendered",
    },
    {
        "名字": "公式不计数（交代与实际不符）",
        "判据": 9,
        "文件": READER,
        "原样": '        if (attr(element, "table:formula")?.isNotBlank() == true) tally.bump("formula")',
        "改成": "        Unit",
    },
    {
        "名字": "退回原文这件事不说",
        "判据": 9,
        "文件": READER,
        "原样": '        tally.bump("unrendered")\n        return stored',
        "改成": "        return stored",
    },
    {
        "名字": "重名的表互相盖掉",
        "判据": 2,
        "文件": READER,
        "原样": "        if (used.add(name)) return name",
        "改成": "        used.add(name); return name",
    },
    {
        "名字": "text:s 的个数丢掉",
        "判据": 2,
        "文件": READER,
        '原样': '                "s" -> out.append(" ".repeat((attr(child, "text:c")?.toIntOrNull() ?: 1).coerceIn(1, 999)))',
        "改成": '                "s" -> out.append(" ")',
    },
    {
        "名字": "写出时看着像数字就写成数值",
        "判据": 12,
        "文件": WRITER,
        "原样": "        val number = XlsxWrite.keepsItsMeaning(value)",
        "改成": "        val number = value.isNotEmpty() && value.all { it in '0'..'9' || it == '.' }",
    },
    {
        "名字": "mimetype 被压缩",
        "判据": 10,
        "文件": WRITER,
        "原样": "        items += com.fileforge.core.archive.ZipItem(\"mimetype\", MIME.length.toLong(), modifiedAt, stored = true) {",
        "改成": "        items += com.fileforge.core.archive.ZipItem(\"mimetype\", MIME.length.toLong(), modifiedAt) {",
    },
    {
        "名字": "空格写成裸空白而不是 text:s",
        "判据": 13,
        "文件": WRITER,
        '原样': '                out.append(if (count == 1) "<text:s/>" else "<text:s text:c=\\"$count\\"/>")',
        "改成": '                out.append(" ".repeat(count))',
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def run(cmd: str) -> int:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=ENV).returncode


def wipe_artifacts() -> None:
    shutil.rmtree(os.path.join("core", "build", "odsread"), ignore_errors=True)


def run_referee() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_ods.py")],
                          capture_output=True, text=True, encoding="utf-8")
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
        print("合规面：判据全绿（照规格写的产物被收），反例还没跑")
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
