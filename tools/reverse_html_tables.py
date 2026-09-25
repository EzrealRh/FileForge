#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「网页表格 → CSV」这套判据有没有牙：把跨度对位、散格子、说明计数挨个改坏，看对应那条会不会红。

绿了不算数 —— 只有改坏它确实变红，这条判据才在守东西。
跑法：先 ./gradlew :core:test，再 python tools/reverse_html_tables.py
（跑完自动还原 Html.kt；有条判据没牙就退出码非 0）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
TARGET = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "doc", "Html.kt")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
# 只跑落盘那个夹具测试：HtmlTableTest 里的断言会先挡住，测不到 pandas 这套有没有牙
DUMP = 'gradlew.bat --offline :core:test --rerun --tests "com.fileforge.core.HtmlTableFixtureTest"'

BREAKS = [
    # 跨度对位有两条路各自都能兜住（游标按跨度走 + 被占位置跳过），只断一条不会变样 ——
    # 要断就整个断掉：让 colspan / rowspan 完全不参与对位
    ("colspan 完全不参与对位",
     '                val spanX = span(cell.attrs["colspan"]) { clamped++ }',
     "                val spanX = 1",
     "落在同一格"),
    ("rowspan 完全不参与对位",
     '                val spanY = span(cell.attrs["rowspan"]) { clamped++ }',
     "                val spanY = 1",
     "落在同一格"),
    ("散 td（没写 tr）不并成一行",
     "        if (out.isEmpty() && loose.isNotEmpty()) out += HtmlNode(\"tr\").apply { children += loose }",
     "        if (false) out += HtmlNode(\"tr\")",
     "标记一个不丢"),
    ("跨格数量乱报（每格都算成跨格）",
     "                if (spanX > 1 || spanY > 1) merged++",
     "                merged++",
     "跨格数量在结果说明里报了"),
]


def product_stamp():
    folder = os.path.join(ROOT, "core", "build", "html-tables")
    names = ("t1.csv", "tables.manifest", "tables.skipped")
    return tuple(
        os.path.getmtime(os.path.join(folder, name)) if os.path.isfile(os.path.join(folder, name)) else 0
        for name in names
    )


def run(command):
    return subprocess.run(command, cwd=ROOT, shell=True, capture_output=True, env=ENV)


def main():
    backup = TARGET + ".reverse-backup"
    shutil.copyfile(TARGET, backup)
    original = io.open(backup, encoding="utf-8").read()
    verdicts = []
    try:
        for name, needle, replacement, expect in BREAKS:
            if needle not in original:
                verdicts.append("跳过（找不到要改坏的那行）：" + name)
                continue
            io.open(TARGET, "w", encoding="utf-8", newline="\n").write(original.replace(needle, replacement, 1))
            stamp = product_stamp()
            dump = run(DUMP)
            text = dump.stdout.decode("utf-8", "replace") + dump.stderr.decode("utf-8", "replace")
            if dump.returncode != 0:
                with io.open(os.path.join("D:", os.sep, "fflogs", "revt-%d-dump.log" % len(verdicts)), "w",
                             encoding="utf-8") as handle:
                    handle.write(text)
                why = "编译就红了" if any(l.startswith("e: ") for l in text.splitlines()) else "落盘测试先红了"
                verdicts.append("%s -> %s（pandas 这套没测到）" % (name, why))
            elif product_stamp() == stamp:
                verdicts.append("%s -> 产物没被重写（Gradle 判成无需重跑），外部这套没测到" % name)
            else:
                result = run("python tools/verify_html_tables.py")
                out = result.stdout.decode("utf-8", "replace")
                with io.open(os.path.join("D:", os.sep, "fflogs", "revt-%d-verify.log" % len(verdicts)), "w",
                             encoding="utf-8") as handle:
                    handle.write(out)
                red = [line.strip()[3:] for line in out.splitlines() if line.strip().startswith("未过：")]
                hit = [item for item in red if expect in item]
                verdicts.append("%s -> pandas 侧红了 %d 条 %s，本该红的「%s」%s" % (
                    name, len(red), red, expect, "红了" if hit else "没红（看上面哪条红）"))
            io.open(TARGET, "w", encoding="utf-8", newline="\n").write(original)
    finally:
        shutil.copyfile(backup, TARGET)
        os.remove(backup)
        run(DUMP)                                  # 产物要对应还原后的代码，不然下一轮判据读到坏产物
    print("\n".join(verdicts))
    toothless = [line for line in verdicts if "没红" in line or "跳过" in line]
    if toothless:
        print("\n这些判据没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
