#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「文本 / 网页 → docx」这套判据有没有牙：挨个把写入侧改坏，看对应那条会不会红。

绿了不算数 —— 只有改坏它确实变红，这条判据才在守东西。
跑法：先 ./gradlew :core:test，再 python tools/reverse_docx_write.py
（跑完自动还原并重新落盘；有条判据没牙就退出码非 0）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OFFICE = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "office")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --rerun --tests "com.fileforge.core.DocxWriteFixtureTest"'

# (说明, 文件, 原样, 改成, 期望红的那条)
BREAKS = [
    ("样式名不用 Word 认的内置名", "DocxWrite.kt",
     'paragraphStyle(\n                "Heading$level", "Heading $level",',
     'paragraphStyle(\n                "Heading$level", "自定义标题 $level",',
     "块结构与词上的记号"),
    ("等宽退回直接格式（pandoc 会把同格式的相邻文字并成一串）", "DocxWrite.kt",
     'if (r.mono) flags.append("<w:rStyle w:val=\\"VerbatimChar\\"/>")',
     'if (r.mono) flags.append("<w:rFonts w:ascii=\\"Consolas\\"/>")',
     "块结构与词上的记号"),
    ("列表引了一个不存在的编号（等于没有 numPr）", "DocxWrite.kt",
     ".append(if (bullet) 1 else 2)",
     ".append(if (bullet) 9 else 9)",
     "还是列表"),
    ("正文引了关联表里没有的链接（rId 那一路）", "DocxWrite.kt",
     '        links.forEachIndexed { index, target ->',
     '        links.take(0).forEachIndexed { index, target ->',
     "部件清单与实际内容对得上"),
    ("numbering 部件不进包", "DocxWrite.kt",
     "        parts[NUMBERING] = numberingXml()",
     "",
     "部件清单与实际内容对得上"),
]


def run(command):
    return subprocess.run(command, cwd=ROOT, shell=True, capture_output=True, env=ENV)


def product_stamp():
    folder = os.path.join(ROOT, "core", "build", "docx")
    return tuple(
        os.path.getmtime(os.path.join(folder, n)) if os.path.isfile(os.path.join(folder, n)) else 0
        for n in ("clean.docx", "common.docx", "plain.docx")
    )


def main():
    originals = {name: io.open(os.path.join(OFFICE, name), encoding="utf-8").read() for name in os.listdir(OFFICE)
                 if name.endswith(".kt")}
    backup = {name: text for name, text in originals.items()}
    verdicts = []
    try:
        for label, filename, needle, replacement, expect in BREAKS:
            source = backup[filename]
            if needle not in source:
                verdicts.append("跳过（找不到要改坏的那行）：%s" % label)
                continue
            io.open(os.path.join(OFFICE, filename), "w", encoding="utf-8", newline="\n").write(
                source.replace(needle, replacement, 1))
            stamp = product_stamp()
            build = run(DUMP)
            text = build.stdout.decode("utf-8", "replace") + build.stderr.decode("utf-8", "replace")
            if build.returncode != 0:
                with io.open(os.path.join("D:", os.sep, "fflogs", "revd-%d.log" % len(verdicts)), "w",
                             encoding="utf-8") as handle:
                    handle.write(text)
                why = "编译就红了" if any(l.startswith("e: ") for l in text.splitlines()) else "落盘测试先红了"
                verdicts.append("%s -> %s（pandoc 这套没测到）" % (label, why))
            elif product_stamp() == stamp:
                verdicts.append("%s -> 产物没被重写（Gradle 判成无需重跑），外部这套没测到" % label)
            else:
                result = run("python tools/verify_docx_write.py")
                out = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
                red = [line.strip()[3:] for line in out.splitlines() if line.strip().startswith("未过：")]
                if result.returncode != 0 and not red:      # 判据脚本整个崩了也算红
                    red = ["判据脚本非 0 退出（见 %s）" % ("revd-%d.log" % len(verdicts))]
                    with io.open(os.path.join("D:", os.sep, "fflogs", "revd-%d.log" % len(verdicts)), "w",
                                 encoding="utf-8") as handle:
                        handle.write(out)
                hit = [item for item in red if expect in item]
                verdicts.append("%s -> pandoc 侧红了 %d 条 %s，本该红的「%s」%s" % (
                    label, len(red), red, expect, "红了" if hit else "没红（看上面哪条红）"))
            io.open(os.path.join(OFFICE, filename), "w", encoding="utf-8", newline="\n").write(source)
    finally:
        for filename, text in backup.items():
            io.open(os.path.join(OFFICE, filename), "w", encoding="utf-8", newline="\n").write(text)
        run(DUMP)                                  # 产物必须对应还原后的代码
    print("\n".join(verdicts))
    toothless = [line for line in verdicts if "没红" in line or "跳过" in line]
    if toothless:
        print("\n这些判据没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
