#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证：把刚立的每条检查对应的那处实现改坏，看检查是不是真的会红。

绿了不算数 —— 只有改坏它确实变红，这条检查才在守东西。
跑法：先 ./gradlew :core:test，再 python tools/reverse_html.py（跑完自动还原 Html.kt，退出码非 0 表示有条检查没牙）
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
# 只跑落盘那个夹具测试：HtmlTest 里的断言会先挡住，测不到外部这套检查有没有牙
DUMP = "gradlew.bat --offline :core:test --tests \"com.fileforge.core.HtmlFixtureTest\""

BREAKS = [
    ("标题的 # 没了", 'if (ctx.markdown) "#".repeat(node.name.removePrefix("h").toInt()) + " " + body else body',
     "body", "结构与它直接读"),
    ("表格的列粘在一起", 'return rows.joinToString("\\n") { it.joinToString("\\t") }',
     'return rows.joinToString("\\n") { it.joinToString("") }', "列在纯文本里是分开的"),
    ("源文件缩进照搬进正文", 'val collapsed = value.replace(WHITESPACE_RUN, " ")',
     "val collapsed = value", "并成了一个空格"),
    ("Markdown 转义整个关掉", "return if (ctx.markdown) escape(collapsed) else collapsed",
     "return collapsed", "字与标点按序"),
]


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
            build = run(DUMP)
            if build.returncode != 0:
                verdicts.append("%s -> 核心单测先红了（Kotlin 这一层就守住）" % name)
            else:
                out = run("python tools/verify_html.py").stdout.decode("utf-8", "replace")
                red = [line.strip()[3:] for line in out.split("\n") if line.strip().startswith("未过：")]
                hit = [item for item in red if expect in item]
                verdicts.append("%s -> 外部红了 %d 条%s，本该红的那条「%s」%s" % (
                    name, len(red), red, expect, "红了" if hit else "没红（这条检查是摆设！）"))
            io.open(TARGET, "w", encoding="utf-8", newline="\n").write(original)
    finally:
        shutil.copyfile(backup, TARGET)
        os.remove(backup)
    print("\n".join(verdicts))
    print("已还原 Html.kt；还原后落盘测试：%s" % ("绿" if run(DUMP).returncode == 0 else "红"))
    toothless = [line for line in verdicts if "没红" in line or "跳过" in line]
    if toothless:
        print("\n这些检查没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
