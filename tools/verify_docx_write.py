#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 **pandoc**（另一套独立的 docx 读取器）复核 Kotlin 写出的 .docx。

自家读路（OfficeText）钉过"写进去读回来字不少"，但它跟写入侧是同一套假设 —— xlsx 那一轮已经
证明包结构写错自家照样读得出（引用漏行号、命名空间串台两处都这么漏的）。所以这里让 pandoc 把
成品读回它自己的内部表示，与它**直接读源文件**得到的表示逐项比对。

比对方式：块级一行一个节点；行内一行一个词，词前带上"包住它的记号集合"（`b|粗`）。
这样 `***粗又斜***` 是 Strong 包 Emph 还是反过来就不参与比对（两家的 reader 嵌套习惯本来不同：
pandoc 的 markdown reader 给 Strong{Emph}，CommonMark 给 `<em><strong>`），
而"这几个字又粗又斜"这件事两种摆法都说清了。少一个词会让后面整条错位，掩不住。

三处声明过的不比对：
  - 行内代码：认回来靠 `VerbatimChar` 字符样式（写成直接格式会被 Word 与 pandoc 当成同一种字合并，
    代码字与两边的顿号会粘成一坨），比的是 `c|码` 这一项
  - 下划线：我们的链接带下划线，源文件那边没有这个概念
  - 图片对象：docx 的图片要嵌图片本体，这条路上只有文字；**图注文字**照常参与比对

跑法：先 ./gradlew :core:test（产物落到 core/build/docx/），再 python tools/verify_docx_write.py
"""
import io
import json
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RES = os.path.join(ROOT, "core", "src", "test", "resources")
PRODUCTS = os.path.join(ROOT, "core", "build", "docx")

# Word 里没这个概念，见文件头
IGNORE = ("Underline", "Image", "RawInline", "Citation")
MARK = {"Strong": "b", "Emph": "i", "Strikeout": "s", "Link": "l"}
PASSED = []
FAILED = []


def check(what, ok, detail=""):
    print(("  通过：" if ok else "  失败：") + what)
    if not ok and detail:
        for line in str(detail).split("\n")[:10]:
            print("      " + line)
    (PASSED if ok else FAILED).append(what)
    return ok


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def load(path):
    with open(path, "rb") as handle:
        return handle.read()


def pandoc(args, data):
    payload = data if isinstance(data, bytes) else data.encode("utf-8")
    result = subprocess.run(["pandoc"] + args, input=payload, capture_output=True)
    if result.returncode != 0:
        raise SystemExit("pandoc 失败：" + result.stderr.decode("utf-8", "replace")[:300])
    return result.stdout.decode("utf-8")


def ast(data, source_format):
    return json.loads(pandoc(["--from=" + source_format, "--to=json"], data))["blocks"]


def text_of(node):
    """Str 的 c 在 pandoc 3.x 里直接就是字符串（不是 [字符串]），别再取 [0]。"""
    value = node["c"]
    return value if isinstance(value, str) else value[0]


def kids(node):
    out = []
    for child in node.get("c", []):
        if isinstance(child, list):
            out.extend([x for x in child if isinstance(x, dict)])
        elif isinstance(child, dict):
            out.append(child)
    return out


def shape(blocks):
    """块级结构 + 每个词一行（前缀是包住它的记号集合）。"""
    out = []

    def inline(nodes, marks, sink):
        for node in nodes:
            if not isinstance(node, dict):
                continue                                # pandoc 的 c 里夹着 attrs 与宽度这些非节点字段
            name = node.get("t")
            if name == "Str":
                # 一个字一行：两家的 reader 合并相邻同格式文字的习惯不同（docx 会把"与图注。"并成一串），
                # 按字切就不会被合并方式带跑，而少一个字会让后面整条错位
                for ch in text_of(node):
                    sink.append("%s|%s" % ("".join(sorted(marks)), ch))
            elif name in ("Space", "SoftBreak"):
                sink.append("·")                        # 源文件里的换行就是空格：docx 里它已经是空格
            elif name == "LineBreak":
                sink.append("⏎⏎")                      # 硬换行（<br>）是结构，要比
            elif name in MARK:
                inline(kids(node), marks | {MARK[name]}, sink)
            elif name in ("Code", "RawInline"):
                for ch in node["c"][-1]:
                    sink.append("c|%s" % ch)           # 行内代码：靠 VerbatimChar 字符样式认回来
            elif name in IGNORE:
                inline(kids(node), marks, sink)
            else:
                sink.append("?%s" % name)

    def walk(nodes, depth, sink):
        for node in nodes:
            if not isinstance(node, dict):
                continue
            name = node.get("t")
            if name == "Header":
                sink.append("  " * depth + "Header%s" % node["c"][0])
                inline(node["c"][2], set(), sink)
            elif name in ("Para", "Plain"):
                # docx 表达不了"紧凑列表"（markdown reader 给 Plain、docx reader 给 Para），
                # 两者都只是"一段行内内容"，统一成 Para 再比
                sink.append("  " * depth + "Para")
                inline(node["c"], set(), sink)         # pandoc 3.x：Para 的 c 直接就是行内列表
            elif name == "LineBlock":
                sink.append("  " * depth + name)
                walk(node["c"][0], depth + 1, sink)
            elif name == "BlockQuote":
                # Word 里没有第二层引用：嵌套引用我们压成"多缩一层的引用段"，
                # 两边都比不了引用层数，这里把 BlockQuote 当透明容器（有一条单独判据仍要求它是引用）
                walk_container(kids(node), depth, sink)
            elif name in ("Table", "Row", "CodeBlock"):
                sink.append("  " * depth + name)
                walk(kids(node), depth + 1, sink)
            elif name in ("BulletList", "OrderedList"):
                sink.append("  " * depth + name)
                payload = node.get("c", [])
                # OrderedList 的 c 第一位是编号属性，项列表在末尾
                items = payload[-1] if payload and isinstance(payload[-1], list) else payload
                for item in items:
                    if isinstance(item, list):
                        sink.append("  " * (depth + 1) + "Item")
                        walk_container(item, depth + 2, sink)
            elif name == "Cell":
                sink.append("  " * depth + name)
                walk_container(node["c"][-1] if isinstance(node["c"][-1], list) else kids(node), depth + 1, sink)
            elif name == "HorizontalRule":
                sink.append("  " * depth + name)

    def walk_container(nodes, depth, sink):
        """一个容器（列表项 / 表格格子）里的块：段数不比，字与记号照比。

        markdown 与 html 的读法会把"格子里的换行"拆成第二段，Word 里就是一格一段 ——
        这条差异两家都会犯，比它只会把判据弄成噪音。少字仍会让后面整条错位。
        """
        inner = []
        walk(nodes, depth, inner)
        seen_para = False
        for line in inner:
            if line.strip() == "Para":
                if seen_para:
                    continue
                seen_para = True
            sink.append(line)

    walk(blocks, 0, out)
    return out


def glyphs(text):
    # 方括号是 pandoc 的 plain 输出给图片加的排版（`[图注]`），不是源文件里的字
    return [ch for ch in text if not ch.isspace() and ch not in "[]"]


def first_diff(a, b):
    for index in range(max(len(a), len(b))):
        left = a[index] if index < len(a) else None
        right = b[index] if index < len(b) else None
        if left != right:
            return index, left, right
    return None


def show(a, b, at):
    return "\n".join(
        "  %-3d %-22s %s" % (i, a[i] if i < len(a) else "-", b[i] if i < len(b) else "-")
        for i in range(max(0, at - 2), min(max(len(a), len(b)), at + 8)))


def package_lies(path):
    """包自己说的（部件清单、关联指向）与包里真有的对得上吗。openpyxl/pandoc 对这都宽松，得自己查。"""
    import zipfile
    import xml.etree.ElementTree as ET
    problems = []
    with zipfile.ZipFile(path) as package:
        present = set(package.namelist())
        types = ET.fromstring(package.read("[Content_Types].xml"))
        ns = "{http://schemas.openxmlformats.org/package/2006/content-types}"
        declared = {node.get("PartName").lstrip("/") for node in types if node.tag == ns + "Override"}
        missing = sorted(declared - present)
        if missing:
            problems.append("清单报了但包里没：%s" % missing)
        uncovered = sorted(
            part for part in present
            if part.endswith(".xml") and part != "[Content_Types].xml" and part not in declared
            and os.path.splitext(part)[1].lstrip(".") not in {"rels", "xml"}
        )
        if uncovered:
            problems.append("包里有部件没进清单：%s" % uncovered)
        for rels in [part for part in present if part.endswith(".rels")]:
            base = os.path.dirname(os.path.dirname(rels))
            for node in ET.fromstring(package.read(rels)):
                target = node.get("Target")
                if target.startswith("http") or node.get("TargetMode") == "External":
                    continue
                resolved = os.path.normpath(os.path.join(base, target)).replace("\\", "/")
                if resolved not in present:
                    problems.append("%s 指向不存在的部件 %s" % (rels, resolved))
        used = set(re.findall(r'r:id="([^"]+)"', package.read("word/document.xml").decode("utf-8")))
        declared_ids = set(re.findall(r'Id="([^"]+)"', package.read("word/_rels/document.xml.rels").decode("utf-8")))
        if used - declared_ids:
            problems.append("正文引了关联表没有的 rId：%s" % sorted(used - declared_ids))
    return problems


def main():
    if not os.path.isfile(os.path.join(PRODUCTS, "clean.docx")):
        raise SystemExit("没有 %s/clean.docx，先跑 ./gradlew :core:test" % PRODUCTS)

    for name, source, fmt in (("clean", "html/clean.html", "html"), ("common", "markdown/common.md", "markdown")):
        path = os.path.join(PRODUCTS, "%s.docx" % name)
        bad = package_lies(path)
        check("%s.docx：包自己报的部件清单与实际内容对得上" % name, not bad, "；".join(bad))
        ours = shape(ast(load(path), "docx"))
        theirs = shape(ast(read(os.path.join(RES, source)), fmt))
        diff = first_diff(ours, theirs)
        check("%s.docx：pandoc 读回来的块结构与词上的记号，和它直接读源文件相同（%d 行）" % (name, len(theirs)),
              diff is None, "" if diff is None else "第 %d 行起不同：我们 %r vs 直接读源 %r\n%s" % (
                  diff[0], ours[diff[0]] if diff[0] < len(ours) else "-", theirs[diff[0]], show(ours, theirs, diff[0])))
        ours_text = pandoc(["--from=docx", "--to=plain", "--wrap=none"], load(path))
        theirs_text = pandoc(["--from=" + fmt, "--to=plain", "--wrap=none"], read(os.path.join(RES, source)))
        lost = set(glyphs(theirs_text)) - set(glyphs(ours_text))
        gained = set(glyphs(ours_text)) - set(glyphs(theirs_text))
        check("%s.docx：字与标点对得上（两边各跑一次 pandoc 的 plain 输出）" % name, not lost and not gained,
              "少了 %s；多了 %s" % (sorted(lost)[:12], sorted(gained)[:12]))

    # 普通文字那份：没有记号可言，钉"段落怎么分"与"字没少"
    plain = ast(load(os.path.join(PRODUCTS, "plain.docx")), "docx")
    kinds = [block["t"] for block in plain]
    source = read(os.path.join(RES, "docx/plain.txt"))
    expected = [b for b in re.split(r"\n{2,}", source.replace("\r\n", "\n")) if b.strip()]
    check("普通文字按空行分段（%d 段，全是不带记号的段落）" % len(expected),
          len(kinds) == len(expected) and all(k in ("Para", "Plain") for k in kinds),
          "读回来 %s（%d 段）" % (kinds, len(kinds)))
    lost = set(glyphs(source)) - set(glyphs(pandoc(["--from=docx", "--to=plain"], load(os.path.join(PRODUCTS, "plain.docx")))))
    check("普通文字一个字都没丢", not lost, "少了：%s" % sorted(lost)[:12])

    # 几件最容易露馅的事单独钉：级别、记号、列表与代码块的身份
    doc = ast(load(os.path.join(PRODUCTS, "clean.docx")), "docx")
    heads = [b["c"][0] for b in doc if b["t"] == "Header"]
    check("标题带着自己的级别进 Word（%s）" % heads, heads == [1, 3], "实际：%s" % heads)
    marked = [line for line in shape(doc) if "|" in line and line.split("|")[0]]
    check("加粗/斜体/删除线/链接都落成了 Word 的记号（%d 个词带记号）" % len(marked),
          {"b", "i", "s", "l"} <= {line.split("|")[0] for line in marked},
          "实际：%s" % sorted({line.split("|")[0] for line in marked}))
    md = ast(load(os.path.join(PRODUCTS, "common.docx")), "docx")
    kinds = {b["t"] for b in md}
    check("列表、代码块、引用与表在 docx 里还是列表、代码块、引用与表",
          {"BulletList", "OrderedList", "CodeBlock", "BlockQuote", "Table"} <= kinds, "实际块：%s" % sorted(kinds))
    return finish()


def finish():
    print("\n通过 %d 条，失败 %d 条" % (len(PASSED), len(FAILED)))
    if FAILED:
        for item in FAILED:
            print("  未过：" + item)
        raise SystemExit(1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
