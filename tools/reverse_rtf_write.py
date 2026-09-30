#!/usr/bin/env python3
"""
给 tools/verify_rtf_write.py 喂反例：把 .rtf 的写法改坏，断言判据点名到该点的那条。

跑法：
    python tools/reverse_rtf_write.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_rtf_write.py --only 表少写最后一行
    python tools/reverse_rtf_write.py --list

改坏的是 core/office/RtfWrite.kt（一处一处改，锚点必须唯一）。原字节先留下，跑完原样写回，
最后按 sha1 核对并用 git status 证明还原干净 —— 不用 git checkout 撤销。
判据没跑起来（产物没落盘 / 判据崩了）单独报，不混进"改不坏"。

第 1 条（产物在）没配反例：它判的是落盘测试本身在不在跑，改坏写法不会让它红 ——
落盘测试坏了 gradle 直接红，反例轮的"没产物"分支接的就是这个。
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

WRITER = "core/src/main/kotlin/com/fileforge/core/office/RtfWrite.kt"
BUILD = os.path.join("core", "build", "rtfwrite")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.RtfWriteTest" --rerun'

CASES = [
    {
        "名字": "魔数写成 rtf0",
        "判据": 2,
        "原样": 'out.append("{\\\\rtf1\\\\ansi\\\\ansicpg1252\\\\uc1\\\\deff0\\n")',
        "改成": 'out.append("{\\\\rtf0\\\\ansi\\\\ansicpg1252\\\\uc1\\\\deff0\\n")',
    },
    {
        "名字": "文件收口的括号不写（别人读不动）",
        "判据": 3,
        "原样": 'out.append("}")',
        "改成": 'out.append("")',
    },
    {
        "名字": "记号变化不清回去",
        "判据": 4,
        "原样": "if (target != marks.current) {",
        "改成": "if (false) {",
    },
    {
        "名字": "样式表里不写层级",
        "判据": 5,
        "原样": 'out.append("{\\\\s$level\\\\outlinelevel${level - 1}\\\\sb240\\\\sa60\\\\b Heading $level;}\\n")',
        "改成": 'out.append("{\\\\s$level\\\\sb240\\\\sa60\\\\b Heading $level;}\\n")',
    },
    {
        "名字": "代码段不用等宽字体",
        "判据": 6,
        "原样": 'if (target.mono) body.append("\\\\f$MONO_FONT ")',
        "改成": 'if (false) body.append("\\\\f$MONO_FONT ")',
    },
    {
        "名字": "链接地址不写出去",
        "判据": 7,
        "原样": 'body.append("{\\\\field{\\\\*\\\\fldinst{HYPERLINK \\"")',
        "改成": 'body.append("{\\\\field{\\\\*\\\\fldinst{PAGEREF \\"")',
    },
    {
        "名字": "表少写最后一行",
        "判据": 8,
        "原样": "part.rows.forEachIndexed { index, row ->",
        "改成": "part.rows.dropLast(1).forEachIndexed { index, row ->",
    },
    {
        "名字": "每一行都当表头",
        "判据": 9,
        "原样": 'if (part.header && index == 0) body.append("\\\\trhdr")',
        "改成": 'if (part.header) body.append("\\\\trhdr")',
    },
    {
        "名字": "圆点写成编号",
        "判据": 10,
        "原样": 'val kind = if (bullet) "\\\\pnlvlblt" else "\\\\pndec"',
        "改成": 'val kind = "\\\\pndec"',
    },
    {
        "名字": "段落收尾的 par 丢掉",
        "判据": 11,
        "原样": 'body.append("\\\\par\\n")',
        "改成": 'body.append("\\n")',
    },
    {
        "名字": "u 后面的兜底问号不写",
        "判据": 12,
        "原样": 'out.append("\\\\u").append(signed16(code)).append(" ?")',
        "改成": 'out.append("\\\\u").append(signed16(code))',
    },
    {
        "名字": "字面花括号不转义",
        "判据": 13,
        "原样": "ch == '{' -> out.append(\"\\\\{\")",
        "改成": "ch == '{' -> out.append(\"(\")",
    },
    {
        "名字": "书名不写进 info",
        "判据": 14,
        "原样": "if (title.isNotBlank()) {",
        "改成": "if (false) {",
    },
    {
        "名字": "分隔线的交代没了",
        "判据": 15,
        "原样": 'if (tally.rules > 0) notes += "${tally.rules} 根分隔线写成带下边线的空段（RTF 没有横线这个块，按段落读就没了）"',
        "改成": 'if (tally.rules > 0) notes += ""',
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def run_and_log(cmd: str) -> tuple:
    done = subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=ENV)
    return done.returncode, (done.stdout or "") + (done.stderr or "")


def run(cmd: str) -> int:
    code, _ = run_and_log(cmd)
    return code


def wipe_artifacts() -> None:
    shutil.rmtree(BUILD, ignore_errors=True)


def run_referee() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_rtf_write.py")],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    return done.returncode, re.findall(r"^FAIL\s+(\d+) ", done.stdout, re.M), done.stdout + done.stderr


def main(argv: list) -> int:
    if "--list" in argv:
        for case in CASES:
            print("%2d  %s" % (case["判据"], case["名字"]))
        return 0
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    files = [WRITER]
    originals = {path: read_bytes(path) for path in files}
    texts = {path: originals[path].decode("utf-8") for path in files}

    def restore():
        for path, blob in originals.items():
            with open(path, "wb") as handle:
                handle.write(blob)
            # 当场核对：留到最后一轮才报"没还原干净"，中间那些轮的判词就都不可信了
            if read_bytes(path) != blob:
                print("还原没到位 %s —— 停下来，别再往下判" % path)
                raise SystemExit(1)

    wipe_artifacts()
    if run(DUMP) != 0:
        print("基线就没绿：先修好 :core:test 的 RtfWriteTest 再谈反例")
        return 1
    code, reds, output = run_referee()
    if code != 0:
        print("判据基线就红，反例还没测：\n%s" % output[-1500:])
        return 1
    if "--comply" in argv:
        print("合规面：判据全绿（照 RTF 的规矩写出去的文件 pandoc 收），反例还没跑")
        return 0

    misses = []
    tried = 0
    for case in CASES:
        if only and only != case["名字"]:
            continue
        tried += 1
        text = texts[WRITER]
        if text.count(case["原样"]) != 1:
            print("反例失效 %-22s 锚点出现 %d 次（实现改过，这条得跟着改）" % (case["名字"], text.count(case["原样"])))
            misses.append((case["名字"], "锚点不唯一或没了"))
            continue
        with open(WRITER, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text.replace(case["原样"], case["改成"], 1))
        wipe_artifacts()
        tests_rc, dump_out = run_and_log(DUMP)
        tests_green = tests_rc == 0
        dumped = os.path.exists(os.path.join(BUILD, "book.rtf"))
        code, reds, output = run_referee()
        restore()
        wipe_artifacts()
        run(DUMP)
        if not dumped:
            clues = [line.strip()[:110] for line in dump_out.splitlines()
                     if line.startswith("e: ") or "FAILED" in line or "What went wrong" in line]
            print("没产物 %-22s 落盘测试没写出 %s；gradle 线索：%s"
                  % (case["名字"], BUILD, clues or dump_out.strip().splitlines()[-3:]))
            misses.append((case["名字"], "判据没跑起来（没有产物）"))
            continue
        if code != 0 and not reds:
            print("判据崩了 %-23s 没有一条 FAIL，输出尾部：%s" % (case["名字"], output[-300:].strip()))
            misses.append((case["名字"], "判据崩了，没点到名"))
            continue
        if code == 0 and tests_green:
            print("没牙   %-24s 单元测试与判据都没红" % case["名字"])
            misses.append((case["名字"], "全绿"))
        elif str(case["判据"]) not in reds:
            note = ("单元测试拦了，判据没红（红的是 %s）" % (sorted(set(reds)) or "无")) if not tests_green \
                else "判据红了 %s，没点到 %d" % (sorted(set(reds)), case["判据"])
            print("点错名 %-24s %s" % (case["名字"], note))
            misses.append((case["名字"], note))
        else:
            print("有牙   %-24s 判据 %2d 红了（单元测试%s；共红 %s）"
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
