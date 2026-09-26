#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「EPUB」这套判据有没有牙：挨个把实现改坏，看对应那条会不会红。

绿了不算数 —— 只有改坏它确实变红，这条判据才在守东西。两段分开跑：
 第一段改源码，每轮都重跑落盘测试（产物必须对应改坏后的代码）；单元测试先红的也算拦住（那是第一层）；
 第二段只把落盘的产物改坏 —— EPUB 那几条外部判据得真的在读这些文件，不能因为第一层先红就显不出牙。
有条判据没牙就退出码非 0。

跑法：python tools/reverse_epub.py
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
CORE = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core")
EPUB = os.path.join(CORE, "book", "Epub.kt")
HTML = os.path.join(CORE, "doc", "Html.kt")
DOCX = os.path.join(CORE, "office", "DocxWrite.kt")
STAMP = os.path.join(ROOT, "core", "build", "epub", "book.md")
OUT_DIR = os.path.join(ROOT, "core", "build", "epub")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --rerun --tests "com.fileforge.core.EpubFixtureTest"'

# (改哪个文件, 说明, 原样, 改成, 期望红的那条判据)
BREAKS = [
    (EPUB, "章节按文件名排，不按 spine（读出来顺序全错）",
     "order.forEach { id ->", "order.sorted().forEach { id ->", "章序与章名"),
    (EPUB, "href 里的片段不去掉（NCX 指的名字对不上部件）",
     "val clean = href.substringBefore('#').substringBefore('?')",
     "val clean = href.substringBefore('?')", "章名来源"),
    (EPUB, "清单里的部件一律当正文（附录与没排进顺序的混进来）",
     "order.forEach { id ->", "parts.keys.toList().forEach { id ->", "不混进正文"),
    (EPUB, "线性为 no 的项也排进去（同一章出现两遍）",
     'if (attrs["linear"].equals("no", ignoreCase = true)) return@forEach',
     'if (attrs["linear"].equals("yes", ignoreCase = true)) return@forEach', "章序与章名"),
    (EPUB, "图片不再计数（说漏了「有图片没搬」）",
     '} else if (type.startsWith("image/")) {', '} else if (type.startsWith("image/gif")) {',
     "说明里的数"),
    (EPUB, "带 BOM 的 UTF-16 不按 BOM 认（整章变乱码）",
     "b0 == 0xFF && b1 == 0xFE -> Charsets.UTF_16LE",
     "b0 == 0xFF && b1 == 0xFE -> Charsets.ISO_8859_1", "UTF-16 的整章字"),
    (EPUB, "xhtml 不当成正文类型（一章都排不出来）",
     'private val DOCUMENT_TYPES = setOf("application/xhtml+xml", "text/html", "application/xml")',
     'private val DOCUMENT_TYPES = setOf("text/html", "application/xml")', "一个字都没丢"),
    (HTML, "页内锚点照样写成链接（摊平成一篇后点了没反应，也不交代）",
     'href.isBlank() || href.startsWith("#") -> { ctx.counted.anchors++; into.append(label) }',
     'href.isBlank() -> { ctx.counted.anchors++; into.append(label) }', "页内锚点"),
    (HTML, "斜体不加记号（*强调* 变成普通文字）",
     '"em", "i" -> "*$body*"', '"em", "i" -> "$body"', "Markdown 的块序列"),
    (HTML, "代码块写成普通段（围栏没了）",
     'return "```$language\\n$body\\n```"', 'return "$body"', "块类型逐个"),
    (DOCX, "外部链接的 rel 不标 External（Word 里当成本机路径）",
     '.append("\\" TargetMode=\\"External\\"/>")', '.append("\\"/>")', "Word 里的外链"),
]

# 第二段不动源码，只把落盘的产物改坏：外部那几条判据得真的在读这些文件，
# 而不是因为单元测试先红才显得"没事"。
TAMPERS = [
    ("order.txt", lambda t: swap(t), "章序与章名"),
    ("notes.txt", lambda t: cut(t, "图片"), "说明里的数"),
    ("chapter-1.txt", lambda t: cut(t, "点二"), "一个字都没丢"),
    ("book.md", lambda t: t.replace("https://example.com/epub", "https://example.com/WRONG"),
     "外部链接的地址"),
    ("utf16.txt", lambda t: t.replace("乱码", "XXXX"), "UTF-16 的整章字"),
]


def swap(text):
    lines = [line for line in text.splitlines() if line.strip()]
    lines[0], lines[1] = lines[1], lines[0]
    return "\n".join(lines) + "\n"


def cut(text, needle):
    return "\n".join(line for line in text.splitlines() if needle not in line) + "\n"


def reds(text):
    """判据脚本的输出里挑出红的那几条。"""
    return [line[5:].strip() for line in text.splitlines() if line.startswith("FAIL ")]


def main():
    originals = {path: io.open(path, encoding="utf-8").read() for path in {b[0] for b in BREAKS}}
    verdicts = []
    try:
        for path, label, needle, replacement, expect in BREAKS:
            source = originals[path]
            if source.count(needle) != 1:
                verdicts.append("跳过（要改坏的那行没找到或有重，count=%d）：%s" % (source.count(needle), label))
                continue
            io.open(path, "w", encoding="utf-8", newline="\n").write(source.replace(needle, replacement, 1))
            build = subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)
            out = build.stdout.decode("utf-8", "replace") + build.stderr.decode("utf-8", "replace")
            if build.returncode != 0:
                log = os.path.join("D:", os.sep, "fflogs", "reverse_epub.log")
                with io.open(log, "w", encoding="utf-8") as handle:
                    handle.write(out)
                why = "编译就红了" if any(line.startswith("e: ") for line in out.splitlines()) else "单元测试先红了"
                verdicts.append("%s -> %s（外部判据没跑到，少一层算多层保险）" % (label, why))
            elif not os.path.exists(STAMP):
                verdicts.append("%s -> 产物没落盘，这轮没测到" % label)
            else:
                result = subprocess.run("python tools/verify_epub.py", cwd=ROOT, shell=True,
                                        capture_output=True, env=ENV)
                text = result.stdout.decode("utf-8", "replace")
                red = reds(text)
                if result.returncode != 0 and not red:
                    red = ["判据脚本整个崩了"]
                hit = [item for item in red if expect in item]
                verdicts.append("%s -> 外部红了 %d 条 %s，本该红的「%s」%s" % (
                    label, len(red), [item[:26] for item in red], expect,
                    "红了" if hit else "没红（看上面哪条红）"))
            io.open(path, "w", encoding="utf-8", newline="\n").write(source)
    finally:
        for path, source in originals.items():
            io.open(path, "w", encoding="utf-8", newline="\n").write(source)
        subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)

    # 第二段：源码已经还原、产物已经重落，这一轮只改坏产物，看外部判据读不读它们
    for name, break_it, expect in TAMPERS:
        artifact = os.path.join(OUT_DIR, name)
        original = io.open(artifact, encoding="utf-8").read()
        io.open(artifact, "w", encoding="utf-8", newline="\n").write(break_it(original))
        result = subprocess.run("python tools/verify_epub.py", cwd=ROOT, shell=True,
                                capture_output=True, env=ENV)
        text = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
        red = reds(text)
        if result.returncode != 0 and not red:
            red = ["判据脚本整个崩了"]
        hit = [item for item in red if expect in item]
        verdicts.append("产物改坏（%s）-> 外部红了 %d 条 %s，本该红的「%s」%s" % (
            name, len(red), [item[:26] for item in red], expect,
            "红了" if hit else "没红（看上面哪条红）"))
        io.open(artifact, "w", encoding="utf-8", newline="\n").write(original)
    print("\n".join(verdicts))
    toothless = [line for line in verdicts if "没红" in line or "跳过" in line or "没测到" in line]
    if toothless:
        print("\n这些判据没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
