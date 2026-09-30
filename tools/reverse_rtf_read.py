#!/usr/bin/env python3
r"""
给 tools/verify_rtf_read.py 喂反例：把 RTF 读法里的某一条规矩改坏，断言判据点名到该点的那条。

跑法：
    python tools/reverse_rtf_read.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_rtf_read.py --only 编号当圆点
    python tools/reverse_rtf_read.py --list

改坏的是 Kotlin 源文件（core/office/RtfRead.kt）：开工先留原字节，每轮跑完按字节写回，
最后用 sha1 与 git status 双重核对 —— 不用 git checkout 撤销，也不从 HEAD 取（这个文件
本轮才新增，HEAD 里没有它，取回来会是空的）。每轮先删掉上一次的产物再重跑落盘：
留着的旧文件会让判据拿着好产物说"全绿"。
判据一条没红但产物没落盘、或判据崩了，分开报，不混成"改不坏"。

第 17 条（写出去的 ODT 别人读得动）与第 2 条同源：层级与样式一旦不认，两边同时红 ——
那是同一次读取的两处出口，不假装它们能被分开改坏。
"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

TREE = "core/src/main/kotlin/com/fileforge/core/office/RtfRead.kt"
BUILD = os.path.join("core", "build", "rtfread")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.RtfReadTest" --rerun'
VERIFY = ["python", "tools/verify_rtf_read.py"]

CASES = [
    {
        "名字": "样式表里的层级不记（标题全没了）",
        "判据": 2,
        "原样": '                "outlinelevel" -> if (argument != null && argument in 0..7 && styleSlot >= 0) {',
        "改成": '                "outlinelevel" -> if (argument != null && argument in 8..9 && styleSlot >= 0) {',
    },
    {
        "名字": "样式号一律当标题（没写层级也抬）",
        "判据": 2,
        "原样": "        val level = if (style >= 0) outlineLevels[style] else null",
        "改成": "        val level: Int? = if (style >= 0) 0 else null",
    },
    {
        "名字": "空段落也占一块",
        "判据": 3,
        "原样": "        if (made.none { it.text.isNotBlank() }) {",
        "改成": "        if (false && made.none { it.text.isNotBlank() }) {",
    },
    {
        "名字": "下划线不认",
        "判据": 4,
        "原样": '            "ul" -> change(node) { it.underline = on }',
        "改成": '            "ul" -> change(node) { it.underline = false }',
    },
    {
        "名字": "字体表里的 fmodern 不当等宽",
        "判据": 5,
        "原样": '                "fmodern" -> if (fontSlot >= 0) monoFonts += fontSlot',
        "改成": '                "fmodern" -> Unit',
    },
    {
        "名字": "段内换行当普通字吃掉",
        "判据": 6,
        "原样": '            "line" -> text("\\n")',
        "改成": '            "line" -> Unit',
    },
    {
        "名字": "域指令里的地址不挂到结果文字上",
        "判据": 7,
        "原样": "                if (url != null) fieldUrl = url",
        "改成": "                if (url != null) fieldUrl = null",
    },
    {
        "名字": "cellx 数出双倍的列",
        "判据": 8,
        "原样": '            "cellx" -> if (!nested) columns++',
        "改成": '            "cellx" -> if (!nested) columns += 2',
    },
    {
        "名字": "一行的格子倒过来摆",
        "判据": 9,
        "原样": "        rows += cells + List(width - cells.size) { \"\" }",
        "改成": "        rows += cells.reversed() + List(width - cells.size) { \"\" }",
    },
    {
        "名字": "整张表不补到最宽的行",
        "判据": 10,
        "原样": 'out += DocTable(tableHeader, rows.map { row -> row + List(width - row.size) { "" } })',
        "改成": 'out += DocTable(tableHeader, rows.map { row -> row + List(width - row.size) { "补" } })',
    },
    {
        "名字": "行内少写的不补到自己声明的列数",
        "判据": 10,
        "原样": '        rows += cells + List(width - cells.size) { "" }',
        "改成": "        rows += cells",
    },
    {
        "名字": "表头标记不看（trhdr 白写）",
        "判据": 11,
        "原样": '            "trhdr" -> rowHeader = true',
        "改成": '            "trhdr" -> Unit',
    },
    {
        "名字": "编号列表当圆点列表",
        "判据": 12,
        "原样": "            NUMBERED.containsMatchIn(marker) -> false",
        "改成": "            NUMBERED.containsMatchIn(marker) -> true",
    },
    {
        "名字": "listtext 那一格当正文（记号混进文字）",
        "判据": 13,
        "原样": '        name == "listtext" -> GATHER',
        "改成": '        name == "listtext" -> BODY',
    },
    {
        "名字": "表外的 cell 也摆出一张表",
        "判据": 14,
        "原样": '            "cell" -> closeCell()',
        "改成": '            "cell" -> { inTable = true; closeCell() }',
    },
    {
        "名字": "半个字节就出一个字",
        "判据": 15,
        "原样": "                if (!ignoreText && node.mode in GATHERING) {\n                    if (ansi.isEmpty()) ansiFrom = node\n                    ansi += byte\n                }",
        "改成": "                if (!ignoreText && node.mode in GATHERING) {\n                    if (ansi.isEmpty()) ansiFrom = node\n                    ansi += byte\n                    flushAnsi()\n                }",
    },
    {
        "名字": "脚注与批注并进取不出的目的群（丢的种类说不清）",
        "判据": 16,
        "原样": '        name.contains("note") || name.startsWith("atn") || name == "annotation" -> "annotation"',
        "改成": '        name.contains("note") || name.startsWith("atn") || name == "annotation" -> "skippedGroup"',
    },
    {
        "名字": "段落样式一律 Body（写到 ODT 也没有层级）",
        "判据": 17,
        "原样": '            "s" -> style = argument ?: -1',
        "改成": '            "s" -> Unit',
    },
]


def digest_bytes(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


def wipe() -> None:
    if not os.path.isdir(BUILD):
        return
    for name in os.listdir(BUILD):
        made = os.path.join(BUILD, name)
        if os.path.isfile(made):
            os.remove(made)


def write_source(data: str) -> None:
    with open(TREE, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(data)


def dump_products() -> tuple:
    """跑落盘测试，返回（编译不过吗, 产物在不在了, 线索）。

    Gradle 的退出码在这里不能用：反例本来就是要把别的断言改红的，红了它照样把落盘那一条
    跑完并写出产物 —— 只按退出码判就会把"判据没意见"错报成"没产物"。
    """
    run = subprocess.run(DUMP, shell=True, capture_output=True, text=True, encoding="utf-8", env=ENV)
    out = (run.stdout or "") + (run.stderr or "")
    broken = "compileKotlin FAILED" in out or "compileTestKotlin FAILED" in out
    made = os.path.isdir(BUILD) and len(os.listdir(BUILD)) > 0
    lines = [line for line in out.splitlines() if line.startswith("e: ") or "FAILED" in line]
    return broken, made, " / ".join(lines[:3]) or ("gradle 退出码 %s" % run.returncode if not made else "")


def verifier_output() -> tuple:
    made = os.path.isdir(BUILD) and len(os.listdir(BUILD)) > 0
    run = subprocess.run(VERIFY, capture_output=True, text=True, encoding="utf-8")
    out = (run.stdout or "") + (run.stderr or "")
    reds = [int(n) for n in re.findall(r"^FAIL\s+(\d+)", out, re.M)]
    summary = "共 " in out and "条红" in out
    return reds, made, (not summary), out


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        for case in CASES:
            print("%-42s 判据 %s" % (case["名字"], case["判据"]))
        return 0
    only = args[args.index("--only") + 1] if "--only" in args else None
    with open(TREE, "rb") as handle:
        original = handle.read()
    before = digest_bytes(original)
    status = subprocess.run(["git", "status", "--porcelain", TREE], capture_output=True, text=True).stdout
    print("开工：%s sha1 %s…（git 里 %s）" % (
        TREE, before[:10], "未跟踪的新文件" if status.strip() else "已跟踪且干净"))
    bad = []
    passed = 0
    try:
        source = original.decode("utf-8")
        for case in CASES:
            if only and only not in case["名字"]:
                continue
            hits = source.count(case["原样"])
            if hits != 1:
                bad.append(("锚点不唯一", case, "找到 %d 处" % hits))
                print("跳过 %-40s 锚点找到 %d 处（应当正好 1 处）" % (case["名字"], hits))
                continue
            wipe()
            write_source(source.replace(case["原样"], case["改成"]))
            broken, made, clue = dump_products()
            reds, still_made, crashed, out = verifier_output()
            write_source(source)
            if digest_bytes(open(TREE, "rb").read()) != before:
                write_source(original.decode("utf-8"))
                bad.append(("还原失败", case, "sha1 与开工前不一致（已按原字节写回）"))
                print("停下：还原后 sha1 不一致（%s）" % case["名字"])
                return 1
            if broken:
                bad.append(("改不动（编译不过）", case, clue[:150]))
                print("%-42s 这一处改不动：编译就红了，判据没机会看产物" % case["名字"])
            elif crashed:
                bad.append(("判据崩了", case, (out.strip().splitlines() or ["无输出"])[-1][:150]))
                print("%-42s 判据崩了（不是红，是没跑成）" % case["名字"])
            elif not (made and still_made):
                bad.append(("没产物", case, clue or "改坏的那份没落盘"))
                print("%-42s 没有产物：%s" % (case["名字"], clue[:110] or "未知"))
            elif case["判据"] in reds:
                passed += 1
                others = [n for n in reds if n != case["判据"]]
                print("%-42s 红的是第 %d 条 ✔%s" % (case["名字"], case["判据"], "（另外还红了 %s）" % others if others else ""))
            else:
                bad.append(("改不坏", case, "实际红了 %s" % (reds or "无")))
                print("%-42s 没红到第 %d 条（实际红：%s）" % (case["名字"], case["判据"], reds or "无"))
    finally:
        if digest_bytes(open(TREE, "rb").read()) != before:
            write_source(original.decode("utf-8"))
        status = subprocess.run(["git", "status", "--porcelain", TREE], capture_output=True, text=True).stdout
        now = digest_bytes(open(TREE, "rb").read())
        print("\n还原核对：sha1 %s（%s）；git status %s" % (
            now[:10], "与开工前一致" if now == before else "不一致", "同上（未跟踪）" if status.strip() else "干净"))
    print("\n共 %d 个反例跑到判定，%d 个没红到该点的条" % (passed + len(bad), len(bad)))
    for kind, case, detail in bad:
        print("  %s：%s（判据 %s）%s" % (kind, case["名字"], case["判据"], detail))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
