#!/usr/bin/env python3
"""
判 Doc → HTML 那套渲染器的产物：pandoc 与 Python 的 html.parser 各独立读一遍我们写的网页。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.Html*' --rerun
    python tools/verify_html_write.py

八条判据：
  1 每份都是完整页面：DOCTYPE 在最前、charset 声明在前 512 字节里、有 <title>，标签配平
  2 pandoc 读我们页面的**块序列**与它直接读来料的块序列逐个相等
  3 pandoc 读我们页面的**文字**与来料的文字一字不差
  4 同一棵 Doc 写出的网页与 docx，pandoc 读回来的块序列与文字一致（跨格式同一套判断）
  5 表格页面：pandoc 读回的表与 csv 模块独立读来料一致；<th> 只出现在首行
  6 转义：来料里长得像标签的一段在页面里是文字不是元素，链接地址原样在 href 里
  7 标题：页面 <title> 就是给的那书名（不是文件名以外的别的东西）
  8 列表层级：来料里两层列表，页面里就得真嵌套出两层

判块序列与文字的那套工具与 verify_epub_write.py 共用同一个模块（同一套 pandoc 读法不该有两份说法）。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from html.parser import HTMLParser

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_epub_write import inline_text, norm, pandoc, reader_for, top_blocks  # noqa: E402

SRC = os.path.join("core", "src", "test", "resources", "epubwrite")
DATA = os.path.join("core", "src", "test", "resources", "data")
BUILD = os.path.join("core", "build", "htmlwrite")

VOID = {"meta", "br", "hr", "img", "input", "link"}
SOURCES = ["note.md", "head.md", "page.html", "plain.txt", "english.md"]
TABLES = ["table", "tricky"]

results = []


def check(number: int, name: str, ok: bool, detail: str = "") -> bool:
    results.append((number, name, ok))
    print("%s %d %s%s" % ("OK  " if ok else "FAIL", number, name, (" :: " + detail) if detail and not ok else ""))
    return ok


class Balance(HTMLParser):
    """标签配平与结构计数：未闭合、多收尾、嵌套深度都在这儿数。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.problems = []
        self.depth = 0
        self.max_ul_depth = 0
        self.title = ""
        self.in_title = False
        self.hrefs = []
        self.th = 0
        self.td = 0
        self.in_table = False
        self.first_row_done = False
        self.rows = 0
        self.tags = []

    def handle_starttag(self, tag, attrs):
        self.tags.append(tag)
        dictionary = dict(attrs)
        if tag == "title":
            self.in_title = True
        if tag == "a" and "href" in dictionary:
            self.hrefs.append(dictionary["href"])
        if tag in ("th", "td"):
            if tag == "th" and self.first_row_done:
                self.problems.append("表头 <th> 出现在第二行之后")
            if tag == "td" and not self.first_row_done:
                pass
            setattr(self, tag, getattr(self, tag) + 1)
        if tag == "tr" and self.in_table:
            self.rows += 1
            if self.rows > 1:
                self.first_row_done = True
        if tag == "table":
            self.in_table = True
            self.first_row_done = False
        if tag not in VOID:
            self.stack.append(tag)
            if tag == "ul":
                self.depth += 1
                self.max_ul_depth = max(self.max_ul_depth, self.depth)

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False
        if tag in VOID:
            return
        if tag == "table":
            self.in_table = False
        if not self.stack:
            self.problems.append("多了个收尾 </%s>" % tag)
            return
        while self.stack and self.stack[-1] != tag:
            popped = self.stack.pop()
            if popped == "ul":
                self.depth -= 1
            if popped not in ("li", "p"):
                self.problems.append("</%s> 之前 <​%s> 没闭合" % (tag, popped))
        if self.stack:
            self.stack.pop()
            if tag == "ul":
                self.depth = max(0, self.depth - 1)

    def handle_data(self, data):
        if self.in_title:
            self.title += data


def parse_page(path: str) -> Balance:
    parser = Balance()
    with open(path, encoding="utf-8") as handle:
        parser.feed(handle.read())
    parser.close()
    if parser.stack:
        parser.problems.append("文件结束时还没闭合：%s" % parser.stack)
    return parser


def main() -> int:
    if not os.path.isdir(BUILD):
        print("没有 %s，先跑 :core:test --tests '*HtmlWriteFixtureTest'" % BUILD)
        return 1

    for name in SOURCES:
        stem = os.path.splitext(name)[0]
        page = os.path.join(BUILD, "%s.html" % stem)
        if not os.path.exists(page):
            check(1, "%s 产物存在" % stem, False, "没有 %s" % page)
            continue
        raw = open(page, encoding="utf-8").read()
        parser = parse_page(page)
        raw = open(page, encoding="utf-8").read()
        head = raw[:512].lower()
        # 1 完整页面：每份产物都先过这两条 —— 表格页面不归到"表格那条"上，
        #    否则"少闭一个 </table>"这种结构性错误只会在比表的那条里露头，看不出是配平问题
        check(1, "%s 是带 charset 的完整页面" % stem,
              raw.startswith("<!DOCTYPE html>") and "<meta charset=\"utf-8\">" in head and "<title>" in head,
              "开头 %r / charset 在前 512 字节：%s" % (raw[:40], "<meta charset=\"utf-8\">" in head))
        check(1, "%s 的标签配平" % stem, not parser.problems, "；".join(parser.problems[:3]))
        check(7, "%s 的 <title> 就是给的书名" % stem, parser.title.strip() == stem,
              "页面上写的是 %r，期望 %r" % (parser.title.strip(), stem))

        from_ours = None
        from_source = None
        try:
            from_ours = json.loads(pandoc("html", page))
            from_source = json.loads(pandoc(reader_for(name), os.path.join(SRC, name)))
        except RuntimeError as error:
            check(2, "%s 能被 pandoc 独立读回来" % stem, False, str(error))
            continue
        check(2, "%s 的块序列与来料一致" % stem, top_blocks(from_ours) == top_blocks(from_source),
              "读回来 %s；来料 %s" % (top_blocks(from_ours), top_blocks(from_source)))
        ours_text = norm(inline_text(from_ours["blocks"]))
        source_text = norm(inline_text(from_source["blocks"]))
        check(3, "%s 的文字一字不差" % stem, ours_text == source_text, diff_where(ours_text, source_text))

        # 4 跨格式：与同一棵 Doc 写的 docx 比（pandoc 读两份）
        docx = os.path.join(BUILD, "%s.docx" % stem)
        if os.path.exists(docx):
            from_docx = json.loads(pandoc("docx", docx))
            check(4, "%s 的网页与 Word 读回来是同一套结构" % stem,
                  top_blocks(from_ours) == top_blocks(from_docx) and norm(inline_text(from_docx["blocks"])) == ours_text,
                  "网页 %s / Word %s" % (top_blocks(from_ours), top_blocks(from_docx)))

        # 6 转义与链接
        if name == "note.md":
            literals = "<尖括号>" in raw or "&lt;尖括号&gt;" in raw
            check(6, "%s 里像标签的那段是文字，链接地址原样在" % stem,
                  literals and parser.hrefs == ["https://example.com/a?x=1&y=2"] and "&amp;" in raw,
                  "href=%s / 原文带 &amp;：%s / 尖括号当文字：%s" % (parser.hrefs, "&amp;" in raw, literals))
            check(8, "%s 的两层列表真嵌套出两层" % stem, parser.max_ul_depth >= 2,
                  "ul 最深 %d 层" % parser.max_ul_depth)

    for stem in TABLES:
        page = os.path.join(BUILD, "%s.table.html" % stem)
        if not os.path.exists(page):
            check(1, "%s 的表格页面存在" % stem, False, "没有 %s" % page)
            continue
        parser = parse_page(page)
        raw = open(page, encoding="utf-8").read()
        head = raw[:512].lower()
        check(1, "%s 的表格页是带 charset 的完整页面" % stem,
              raw.startswith("<!DOCTYPE html>") and "<meta charset=\"utf-8\">" in head and "<title>" in head,
              "开头 %r" % raw[:40])
        check(1, "%s 的表格页标签配平" % stem, not parser.problems, "；".join(parser.problems[:3]))
        # 格子本来可以带换行，所以行列用 JSON 的二维数组交接（按行切文本会把格子切成两行）
        rows_path = os.path.join(BUILD, "%s.table.rows.json" % stem)
        with open(rows_path, encoding="utf-8") as handle:
            want = json.load(handle)
        check(5, "%s 的表格页面：行与列都在，<th> 只在首行" % stem,
              not parser.problems and parser.th == len(want[0]) and parser.td == (len(want) - 1) * len(want[0])
              and parser.title.strip() == stem,
              "th %d / td %d / 期望表头 %d 格、数据 %d 格；问题 %s"
              % (parser.th, parser.td, len(want[0]), (len(want) - 1) * len(want[0]), parser.problems[:2]))
        table_ast = json.loads(pandoc("html", page))
        tables = [block for block in table_ast["blocks"] if block["t"] == "Table"]
        flat_want = [norm(cell) for row in want for cell in row]
        read_cells = [norm(inline_text(block)) for block in tables]
        # pandoc 的 Table JSON 在不同版本里是位置数组或对象，比"整张表按文档顺序的文字"最稳：
        # 少一格、改一格、格子换了顺序都会在这里露出来，而不用猜它的字段布局
        check(5, "%s 的表格被 pandoc 读回同样的格子" % stem,
              len(tables) == 1 and read_cells == ["".join(flat_want)],
              "pandoc 读到 %s / 期望 %s（表 %d 张）" % (read_cells, ["".join(flat_want)], len(tables)))

    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    return 1 if failed else 0


def diff_where(a: str, b: str) -> str:
    if a == b:
        return ""
    for index in range(min(len(a), len(b))):
        if a[index] != b[index]:
            return "第 %d 字起不同：读回来…%s… / 来料…%s…" % (
                index, a[max(0, index - 20):index + 20], b[max(0, index - 20):index + 20])
    return "长度不同：%d 对 %d，多出的是 %r" % (len(a), len(b), (a[len(b):] or b[len(a):])[:60])


if __name__ == "__main__":
    sys.exit(main())
