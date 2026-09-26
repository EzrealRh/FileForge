#!/usr/bin/env python3
"""
判 docx 的结构读法（core/office/DocxRead.kt）与两条产物路：
同一份 .docx，**pandoc 自己读**的结构，要和我们读出来再写成的 Markdown / HTML 一致。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.DocxReadTest' --rerun
    python tools/verify_docx_read.py

八条判据（每份样本各跑一遍）：
  1 那份 docx 真能被 pandoc 读动（读不动就是写侧坏了一份文件），三份产物都在
  2 块序列：pandoc 读 docx == pandoc 读我们的 Markdown
  3 块序列：pandoc 读 docx == pandoc 读我们的网页
  4 文字：三份一字不差（不多字、不少字、不改字）—— 读 Markdown 时关掉 smart：
     pandoc 默认会把直引号折成弯引号，那是排版口味不是我们的转换改了字
  5 标题层级：Header 的 LevelN 序列三份一致
  6 列表：圆点表 / 编号表各有几张、最深几层，三份一致
  7 表格：每张表的行×列，三份一致
  8 链接：出现的地址集合一致（含 `&` 那种要转义的）
  9 修订（revision 那份）：接受下来的字在产物里、被划掉的字不在 —— 用 ElementTree 独立读 OOXML，
     因为 pandoc 的 docx 读者压根不看 w:ins / w:del（三种 --track-changes 实测输出一模一样）
 10 同一份的编号列表被认成编号，不是圆点

为什么拿 pandoc 当裁判：它是自己从 docx 里读结构的第三方实现，
不会因为我们"读的时候把样式当标题"这种约定而跟着错。
"""
from __future__ import annotations

import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_epub_write import diff_where, inline_text, norm, pandoc, top_blocks  # noqa: E402

BUILD = os.path.join("core", "build", "docxread")
STEMS = ["note", "head", "page", "plain", "english"]

results = []


def check(number: int, name: str, ok: bool, detail: str = "") -> bool:
    results.append((number, name, ok))
    print("%s %d %s%s" % ("OK  " if ok else "FAIL", number, name, (" :: " + detail) if detail and not ok else ""))
    return ok


def unwrap(node):
    """pandoc 的 JSON 里有的字段是位置数组，有的带 t/c，统一成能下钻的样子。"""
    if isinstance(node, dict):
        return node.get("c", node)
    return node


def heading_levels(ast) -> list:
    out = []
    for block in ast["blocks"]:
        if block.get("t") == "Header":
            content = unwrap(block)
            level = next((re.sub("\\D", "", item) for item in unwrap(content[1]) if str(item).startswith("Level")), "")
            out.append((level, norm(inline_text(content[2]))))
    return out


def list_shape(ast) -> tuple:
    bullets = 0
    ordered = 0
    deepest = 0

    def walk(node, depth):
        nonlocal bullets, ordered, deepest
        if isinstance(node, dict):
            kind = node.get("t")
            if kind == "BulletList":
                bullets += 1
            elif kind == "OrderedList":
                ordered += 1
            if kind in ("BulletList", "OrderedList"):
                deepest = max(deepest, depth + 1)
            walk(node.get("c"), depth + (1 if kind in ("BulletList", "OrderedList") else 0))
        elif isinstance(node, list):
            for item in node:
                walk(item, depth)

    walk(ast["blocks"], 0)
    return bullets, ordered, deepest


def tables(ast) -> list:
    out = []
    for block in ast["blocks"]:
        if block.get("t") != "Table":
            continue
        content = unwrap(block)
        head = unwrap(content[3]) or []
        body = unwrap(content[4]) or []
        rows = [row for row in head] + [row for row in body]
        widths = [len(unwrap(row)) for row in rows if isinstance(unwrap(row), list)]
        out.append((len(rows), max(widths) if widths else 0))
    return out


def links(ast) -> list:
    """Link 的 JSON 是 [Attr, [Inline]]：地址在第二项的第一个位置（不是第二个！）。"""
    out = []

    def walk(node):
        if isinstance(node, dict):
            if node.get("t") == "Link":
                content = unwrap(node)
                if isinstance(content, list) and len(content) > 1 and isinstance(content[1], list) and content[1]:
                    target = content[1][0]
                    out.append(target if isinstance(target, str) else "")
            walk(node.get("c"))
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(ast["blocks"])
    return sorted(set(out))


def main() -> int:
    if not os.path.isdir(BUILD):
        print("没有 %s，先跑 :core:test --tests '*DocxReadTest'" % BUILD)
        return 1

    for stem in STEMS:
        docx = os.path.join(BUILD, "%s.docx" % stem)
        markdown = os.path.join(BUILD, "%s.md" % stem)
        html = os.path.join(BUILD, "%s.html" % stem)
        missing = [path for path in (docx, markdown, html) if not os.path.exists(path)]
        if missing:
            check(1, "%s 三份产物齐全" % stem, False, "缺 %s" % missing)
            continue
        try:
            from_docx = json.loads(pandoc("docx", docx))
            from_md = json.loads(pandoc("markdown-smart", markdown))
            from_html = json.loads(pandoc("html", html))
        except RuntimeError as error:
            check(1, "%s 能被外部实现读动" % stem, False, str(error))
            continue
        check(1, "%s 三份产物都读得动" % stem, True)
        check(2, "%s 的 Markdown 与 docx 同一种结构" % stem,
              top_blocks(from_md) == top_blocks(from_docx),
              "Markdown %s / docx %s" % (top_blocks(from_md), top_blocks(from_docx)))
        check(3, "%s 的网页与 docx 同一种结构" % stem,
              top_blocks(from_html) == top_blocks(from_docx),
              "网页 %s / docx %s" % (top_blocks(from_html), top_blocks(from_docx)))
        base = norm(inline_text(from_docx["blocks"]))
        md_text = norm(inline_text(from_md["blocks"]))
        html_text = norm(inline_text(from_html["blocks"]))
        check(4, "%s 三份的文字一字不差" % stem, md_text == base and html_text == base,
              "Markdown 比 docx：%s；网页比 docx：%s" % (diff_where(md_text, base), diff_where(html_text, base)))
        levels = heading_levels(from_docx)
        check(5, "%s 的标题层级一致" % stem,
              heading_levels(from_md) == levels and heading_levels(from_html) == levels,
              "docx %s / Markdown %s / 网页 %s" % (levels, heading_levels(from_md), heading_levels(from_html)))
        shape = list_shape(from_docx)
        check(6, "%s 的列表类型与深度一致" % stem,
              list_shape(from_md) == shape and list_shape(from_html) == shape,
              "docx %s / Markdown %s / 网页 %s" % (shape, list_shape(from_md), list_shape(from_html)))
        grid = tables(from_docx)
        check(7, "%s 的表格行列一致" % stem,
              tables(from_md) == grid and tables(from_html) == grid,
              "docx %s / Markdown %s / 网页 %s" % (grid, tables(from_md), tables(from_html)))
        urls = links(from_docx)
        check(8, "%s 的链接地址一致" % stem,
              links(from_md) == urls and links(from_html) == urls,
              "docx %s / Markdown %s / 网页 %s" % (urls, links(from_md), links(from_html)))

    # 修订这一份单独判：pandoc 的 docx 读者根本不看 w:ins / w:del（--track-changes 三种取值
    # 实测输出完全一样，插入的字都不在它眼里），拿它当裁判等于没判。
    # 所以这里换 **ElementTree 独立读 OOXML**：自己找出哪些字是插入的、哪些是被删的，
    # 再断言我们的两份产物里"插入的在、删掉的不在"。
    revised = os.path.join(BUILD, "revision.docx")
    if os.path.exists(revised):
        import xml.etree.ElementTree as Tree
        import zipfile
        with zipfile.ZipFile(revised) as pack:
            body = Tree.fromstring(pack.read("word/document.xml"))
        W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
        inserted = [t.text or "" for node in body.iter(W + "ins") for t in node.iter(W + "t")]
        # 被删的那截写字在 w:delText 里（Word 就是这么标的），不是 w:t
        deleted = [t.text or "" for node in body.iter(W + "del")
                   for t in list(node.iter(W + "t")) + list(node.iter(W + "delText"))]
        if not (inserted and deleted):
            check(9, "revision 样本里真有修订", False, "插入 %s / 删掉 %s —— 样本没做出来，反例就没牙了" % (inserted, deleted))
        else:
            outputs = ""
            for suffix in (".md", ".html"):
                with open(revised[: -len(".docx")] + suffix, encoding="utf-8") as handle:
                    outputs += handle.read()
            check(9, "revision 的修订：接受的字留下、划掉的字不出现",
                  all(word in outputs for word in inserted) and not any(word in outputs for word in deleted),
                  "插入 %s / 删掉 %s" % (inserted, deleted))
            check(10, "revision 的编号列表被认成编号（不是圆点）",
                  re.search(r"^\s*1[.、]", outputs, re.M) is not None or "<ol>" in outputs,
                  "两份产物里都没看到编号的写法")
    else:
        check(9, "revision 样本存在", False, "没有 %s（JVM 那边的落盘测试没跑？）" % revised)

    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
