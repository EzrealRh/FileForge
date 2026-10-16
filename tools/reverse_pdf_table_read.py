#!/usr/bin/env python3
r"""
PDF 表格读回这一族判据的反例：把 :core 那一层改坏 → 重编 jar → 删掉读回的产物 → 跑
tools/verify_pdf_table_read.py → 断言它点名到该红的那一条，然后把源文件按字节还原。

    python tools/reverse_pdf_table_read.py                # 全跑
    python tools/reverse_pdf_table_read.py --only kerning # 只跑名字里带这个 ASCII 标记的

防的坑（与 tools/reverse_csv_pdf.py 同一套）：
  - 每轮先删产物（core/build/pdftable-read 与三份 *-app.pdf），用"产物在不在"当这一轮真跑过的证据；
  - 还原按 sha1 核对，不一致就按原字节写回并停下；
  - 改坏的那一处如果让编译先红，那它证明不了任何事 —— 这种轮次单独记为"改不动"，不算过。

一条没有反例的判据，交底在这儿：**"整页正文不产表"配不出单点改坏的反例**。它靠的是三层各自独立的
结构：每行的**首词不当列起点**（一行字的话永远不会自己给自己分列）、列要在**≥3 行**里对齐、
段内还得**没人跨过**这条线。把任意一层改坏都还有另外两层拦着（实测：把行高比例那层设成 0、
把最少行数改成 1，这一页依旧读不出表）。所以它是"结构上守住的"，不是"被一个数调出来的"。
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
TABLE = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "pdf", "PdfTable.kt")
BUILD = os.path.join(ROOT, "core", "build", "pdftable")
WORK = os.path.join(ROOT, "core", "build", "pdftable-read")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")

REBUILD = "GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:jar -q"
VERIFY = ["python", "tools/verify_pdf_table_read.py"]

# 每条：应当红的判据（名字里的片段）、名字（带 ASCII 标记）、原样、改成
CASES = [
    {
        "该红": "book：印一遍再读回来", "名字": "整页有人跨线就全页否决 globalveto", "文件": TABLE,
        "原样": "count >= minRows && rows.count { it.crosses(at, tolerance) } < count",
        "改成": "count >= minRows && rows.none { it.crosses(at, tolerance) }",
    },
    {
        "该红": "挨得太近的空隙是字距不是列距", "名字": "字距当列距 kerning", "文件": TABLE,
        "跑法": "jvm",
        "原样": "private const val MIN_GAP_RATIO = 0.35f",
        "改成": "private const val MIN_GAP_RATIO = 0f",
    },
    {
        "该红": "book：印一遍再读回来", "名字": "同格换行不并回 wrapmerge", "文件": TABLE,
        "原样": "val grid = mergeWrappedCells(body, gridOf(body, cuts, tolerance))",
        "改成": "val grid = gridOf(body, cuts, tolerance)",
    },
    {
        "该红": "long：排片单那条", "名字": "跨页重印的表头照抄 headdup", "文件": TABLE,
        "原样": "val repeatedHeader = candidate.rows.first() == rows.first()",
        "改成": "val repeatedHeader = false",
    },
    {
        "该红": "book：印一遍再读回来", "名字": "空格子多的行踢出表 rowrule", "文件": TABLE,
        "原样": "return row.words.any { word -> cuts.any { abs(it - word.left) <= tolerance } }",
        "改成": "return false",
    },
    {
        "该红": "book：印一遍再读回来", "名字": "一行也算表 minrows1", "文件": TABLE,
        "原样": "private const val MIN_TABLE_ROWS = 2",
        "改成": "private const val MIN_TABLE_ROWS = 1",
    },
    {
        "该红": "不产表时把原因说出来", "名字": "认不出来说空话 silent", "文件": TABLE,
        "原样": "notes += \"第 ${page + 1} 页没找到对齐的空隙（${rows.size} 行文字），没当成表\"",
        "改成": "notes += \"第 ${page + 1} 页看过了\"",
    },
]


def wipe() -> None:
    if os.path.isdir(WORK):
        shutil.rmtree(WORK)
    for name in os.listdir(BUILD) if os.path.isdir(BUILD) else []:
        if name.endswith("-app.pdf"):
            os.remove(os.path.join(BUILD, name))


def rebuilt() -> tuple:
    run = subprocess.run(["bash", "-c", REBUILD], cwd=ROOT, capture_output=True, env=ENV)
    out = (run.stdout or b"").decode("utf-8", "replace") + (run.stderr or b"").decode("utf-8", "replace")
    lines = [line for line in out.splitlines() if line.startswith("e: ") or "FAILED" in line]
    return run.returncode != 0, " / ".join(lines[:3]) or "退出码 %s" % run.returncode


def verifier() -> tuple:
    run = subprocess.run(VERIFY, cwd=ROOT, capture_output=True, text=True,
                         encoding="utf-8", errors="replace")
    out = (run.stdout or "") + (run.stderr or "")
    reds = re.findall(r"^FAIL (.+)$", out, re.M)
    finished = "条红" in out
    return reds, finished, out


TESTXML = os.path.join(ROOT, "core", "build", "test-results", "test",
                       "TEST-com.fileforge.core.PdfTableDetectTest.xml")


def jvm_referee() -> tuple:
    """桌面判据咬不动的那几条改跑 :core 单测，并按名字点名要红的测试。

    为什么要换裁判：MIN_GAP_RATIO（字距不算列距）在我们自己印的纸上根本没有反例 ——
    实测六份表格 PDF 的列距最小也有 0.7 个字宽，把比例设成 0 也照样读对，桌面那条链子内
    没东西可坏。这一常量的牙口只在按坐标摆的夹具里才现得出来，所以那一轮直接跑单测。
    """
    run = subprocess.run(["bash", "-c",
                          "GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:test "
                          "--tests '*PdfTableDetectTest*' --rerun -q"],
                         cwd=ROOT, capture_output=True, env=ENV)
    failed = []
    if os.path.exists(TESTXML):
        import xml.etree.ElementTree as ET
        for case in ET.parse(TESTXML).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(case.get("name") or "")
    return failed, run.returncode != 0 and bool(failed), " / ".join(failed[:3]) or "没有测试红"


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
            if case.get("跑法") == "jvm":
                reds, finished, out = jvm_referee()
            else:
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
                print("%-42s 改不动：编译就红了，判据没机会看产物 —— %s" % (case["名字"], clue[:110]))
            elif not finished:
                bad.append(("判据没跑完", case, (out.strip().splitlines() or ["无输出"])[-1][:160]))
                print("%-42s 判据没跑完（崩了或没起来）" % case["名字"])
            elif not any(case["该红"] in line for line in reds):
                bad.append(("判据没点名", case, "红了：%s" % "、".join(reds[:3])))
                print("%-42s 判据没点到 %r（实际红：%s）" % (case["名字"], case["该红"], "、".join(reds[:3]) or "无"))
            else:
                passed += 1
                print("%-42s 红对了：%s" % (case["名字"], next(l for l in reds if case["该红"] in l)[:70]))
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
    # 收尾：全绿要复现（源文件已还原），并且产物真的又长出来了
    reds, finished, out = verifier()
    print("收尾复跑：%s（红：%s）" % ("全绿" if finished and not reds else "不是全绿",
                                    "、".join(reds[:3]) or "无"))
    return 0 if finished and not reds else 1


def sha1_of(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha1(handle.read()).hexdigest()


if __name__ == "__main__":
    sys.exit(main())
