#!/usr/bin/env python3
r"""
判 RTF 的"一份文件 → 文档树"那一步与它的四条产物（转文字 / 转 Markdown / 转网页 / 写成 Word，外加写成 ODT）。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests "com.fileforge.core.RtfReadTest" --rerun
    python tools/verify_rtf_read.py

参照物是 **pandoc 的 RTF 读者**（`-f rtf`）：它有独立的读者、没有写者，所以只能拿"它读原件
读出的结构"与"我们读原件读出的结构"对。它在 RTF 上有几处明确读不出来的东西（列表结构、
`\trhdr` 表头、936 那类多字节代码页），那几处判据按量出来的事实写，并把差距说出来。
代码页那一条另配一个独立裁判：Python 自己的 gbk 编解码。

十九条判据：
  1  四份夹具的八件产物都在且非空
  2  标题层级序列与 pandoc 一致（含"样式表里没写层级就不算标题"）
  3  块数与 pandoc 一致（段落与表的先后不多不少；表外 `\cell` 那条除外，见第 14 条）
  4  粗体 / 斜体 / 下划线 / 删除线四种记号两边都在同一段落里
  5  等宽字体认成 Code（pandoc）与 mono（我们）
  6  段内换行的个数两边一致
  7  链接地址原样：pandoc 的 target == 我们网页里的 href == 我们 Word 里的关联表
  8  表的张数与前两张的列数与 pandoc 一致
  9  整张格子逐格对（第一张表 4 行 × 3 列）
 10  两处补齐都判得到：行内少写的格子补到自己声明的列数、整张表补到最宽的行 ——
     看的是**写出去的网页里有几格**，不是我们自己报的格数（两层各补一次会互相掩盖）
 11  表头来自 `\trhdr`：pandoc 读不出表头（0 行），我们读得出；没写的两张两边都没表头
 12  列表结构是我们独有的：pandoc 一个列表块都读不出，我们 6 段且圆点 / 编号与层号各就各位
 13  列表记号没混进正文（`•` 与 `1.` 都不该出现在文字里）
 14  表外的 `\cell` 不当成一张表（pandoc 当成表，我们不）
 15  936 代码页按 Python 的 gbk 独立解一遍，解出来的字要在我们产物里；pandoc 自己报读不动
 16  丢掉的东西都有交代（图片 / 脚注 / 二进制 / 认不出的目的群 / 域代码）
 17  写出去的 ODT 用 pandoc 读回来还在：标题层级与列表项都认得出
 18  套在表里的表：里面的格并成外层那一格的文字，字不丢（pandoc 把这些字丢了）
 19  每行只写一格而声明三列的表：我们补到声明的三列，pandoc 只给一格（这处它比我们弱）
"""
from __future__ import annotations

import codecs
import os
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from verify_epub_write import norm  # noqa: E402
from verify_odt_read import (  # noqa: E402
    check,
    attempt,
    pandoc_read,
    plain,
    results,
    table_rows,
)

BUILD = os.path.join("core", "build", "rtfread")
FIXTURES = os.path.join("core", "src", "test", "resources", "rtfread")
STEMS = ("basic", "table", "nested", "codepage")
SUFFIXES = ("rtf", "shapes.txt", "text.txt", "md", "html", "docx", "odt", "notes.txt")
MARKS = {"Strong": "b", "Emph": "i", "Underline": "u", "Strikeout": "s", "Code": "c"}


def read(name: str) -> str:
    with open(os.path.join(BUILD, name), encoding="utf-8") as handle:
        return handle.read()


def shape_list(stem: str) -> list:
    out = []
    for line in read("%s.shapes.txt" % stem).splitlines():
        if not line.strip():
            continue
        if line.startswith("表 "):
            made = re.match(r"^表 (\d+)行×(\d+)列 表头=(true|false)$", line)
            if made is None:
                raise ValueError("形状文件里认不出一张表：%s" % line)
            out.append({"kind": "table", "rows": int(made.group(1)), "cols": int(made.group(2)),
                        "header": made.group(3) == "true"})
            continue
        made = re.match(r"^(\S+)( 层(\d+) (圆点|编号))?\[(.*)\]$", line)
        if made is None:
            raise ValueError("形状文件里认不出一个段落：%s" % line)
        out.append({
            "kind": "para",
            "style": made.group(1),
            "indent": int(made.group(3)) if made.group(3) else None,
            "bullet": None if not made.group(4) else made.group(4) == "圆点",
            "marks": made.group(5),
        })
    return out


def walk_inline(node, sink: dict) -> None:
    """把 pandoc 的行内结构收成"出现过哪些记号 / 链接 / 换行"。"""
    if isinstance(node, list):
        for item in node:
            walk_inline(item, sink)
        return
    if not isinstance(node, dict):
        return
    kind = node.get("t")
    if kind in MARKS:
        sink["marks"][MARKS[kind]] = sink["marks"].get(MARKS[kind], 0) + 1
    if kind == "LineBreak":
        sink["breaks"] += 1
    if kind == "Link":
        sink["links"].append(node["c"][2][0])
    if kind == "Note":
        sink["notes"] += 1
    if kind != "Str":
        walk_inline(node.get("c"), sink)


def block_kinds(ast) -> list:
    out = []
    for block in ast["blocks"]:
        kind = block.get("t")
        if kind == "Header":
            out.append(("header", block["c"][0]))
        elif kind in ("BulletList", "OrderedList", "Table", "Para", "Plain"):
            out.append((kind.lower(), None))
        else:
            out.append((str(kind).lower(), None))
    return out


def column_counts(ast) -> list:
    return [len(block["c"][2]) for block in ast["blocks"] if block.get("t") == "Table"]


def head_row_counts(ast) -> list:
    return [len((block["c"][3] or [[], []])[1] or []) for block in ast["blocks"] if block.get("t") == "Table"]


def ansi_runs(text: str) -> list:
    return [re.findall(r"\\'([0-9a-fA-F]{2})", line) for line in text.splitlines()]


def html_tables(path: str) -> list:
    """网页产物里的每一张表：行 → 格子的文字（`<th>` 与 `<td>` 都算格，顺序照文件）。

    判"补齐"要看的是**写出去的格数**，而不是我们自己 reports 的格数 —— 自家形状文件
    跟实现一起错的话，判据就看不出差别。
    """
    from html.parser import HTMLParser

    class Grab(HTMLParser):
        def __init__(self):
            super().__init__(convert_charrefs=True)
            self.tables = []
            self.rows = None
            self.cell = None

        def handle_starttag(self, tag, attrs):
            if tag == "table":
                self.rows = []
            elif tag == "tr" and self.rows is not None:
                self.rows.append([])
            elif tag in ("td", "th") and self.rows:
                self.cell = []

        def handle_endtag(self, tag):
            if tag == "table" and self.rows is not None:
                self.tables.append(self.rows)
                self.rows = None
            elif tag in ("td", "th") and self.cell is not None:
                self.rows[-1].append(norm("".join(self.cell)))
                self.cell = None

        def handle_data(self, data):
            if self.cell is not None:
                self.cell.append(data)

    made = Grab()
    with open(path, encoding="utf-8") as handle:
        made.feed(handle.read())
    return made.tables


def external_targets(path: str) -> list:
    with zipfile.ZipFile(path) as package:
        rels = package.read("word/_rels/document.xml.rels").decode("utf-8")
    return re.findall(r'TargetMode="External"[^>]*Target="([^"]+)"', rels) + \
        re.findall(r'Target="([^"]+)"[^>]*TargetMode="External"', rels)


def docx_styles(path: str) -> list:
    namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    with zipfile.ZipFile(path) as package:
        root = ET.fromstring(package.read("word/document.xml"))
    out = []
    for para in root.iter(namespace + "p"):
        made = para.find(namespace + "pPr/" + namespace + "pStyle")
        numbering = para.find(namespace + "pPr/" + namespace + "numPr")
        out.append((made.get(namespace + "val") if made is not None else "") + ("#num" if numbering is not None else ""))
    return out


def main() -> int:
    missing = [os.path.join(BUILD, "%s.%s" % (stem, suffix)) for stem in STEMS for suffix in SUFFIXES
               if not os.path.isfile(os.path.join(BUILD, "%s.%s" % (stem, suffix)))
               or os.path.getsize(os.path.join(BUILD, "%s.%s" % (stem, suffix))) == 0]
    if missing:
        print("红的是产物不在：%s" % ", ".join(os.path.basename(item) for item in missing))
        print("先跑：GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests com.fileforge.core.RtfReadTest --rerun")
        return 1
    check(1, "三份夹具的八件产物都在且非空", True, "")

    asts, sinks = {}, {}
    for stem in STEMS:
        sink = {"marks": {}, "breaks": 0, "links": [], "notes": 0}
        ast = pandoc_read("rtf", os.path.join(FIXTURES, "%s.rtf" % stem))
        for block in ast["blocks"]:
            if block.get("t") == "Table":
                for body in block["c"][4] or []:
                    for row in body[3] or []:
                        for cell in row[1] or []:
                            walk_inline(cell[-1] if isinstance(cell, list) else [], sink)
            else:
                walk_inline(block.get("c"), sink)
        asts[stem], sinks[stem] = ast, sink

    ours = {stem: shape_list(stem) for stem in STEMS}

    # 2 标题层级
    for stem in STEMS:
        theirs = [level for kind, level in block_kinds(asts[stem]) if kind == "header"]
        mine = [int(item["style"].removeprefix("Heading")) for item in ours[stem]
                if item["kind"] == "para" and item["style"].startswith("Heading")]
        check(2, "标题层级序列与 pandoc 一致（%s）" % stem, theirs == mine,
              "pandoc：%s；我们：%s" % (theirs, mine))
    # 样式表里没写层级的那一段：pandoc 与我们都不该给它标题
    body_style = ours["basic"][6]["style"]
    their_headers = sum(1 for kind, _ in block_kinds(asts["basic"]) if kind == "header")
    check(2, "样式表里没写层级的样式不算标题（两边都不给）",
          body_style == "Body" and their_headers == 3,
          "我们那一段的样式：%s；pandoc 的标题数：%d" % (body_style, their_headers))

    # 3 块数
    for stem in ("basic", "table", "nested"):
        theirs = len(asts[stem]["blocks"])
        mine = len(ours[stem])
        check(3, "块数与 pandoc 一致（%s）" % stem, theirs == mine,
              "pandoc：%d；我们：%d" % (theirs, mine))

    # 4 四种记号
    paragraph = ours["basic"][2]
    theirs = "".join(sorted(mark for mark, count in sinks["basic"]["marks"].items() if mark in "bius" and count))
    mine = "".join(sorted(set(ch for ch in paragraph["marks"] if ch in "bius")))
    check(4, "粗体 / 斜体 / 下划线 / 删除线四种记号两边都在", theirs == mine and len(mine) == 4,
          "pandoc：%s；我们那一段：%s" % (theirs or "无", paragraph["marks"]))

    # 5 等宽
    check(5, "字体表里标了 fmodern 的那段认成等宽（pandoc 也认成 Code）",
          sinks["basic"]["marks"].get("c", 0) > 0 and "c" in ours["basic"][5]["marks"]
          and all("c" not in item.get("marks", "") for item in ours["basic"] if item is not ours["basic"][5]),
          "pandoc Code：%d；我们的记号序列：%s" % (
              sinks["basic"]["marks"].get("c", 0), [item["marks"] for item in ours["basic"] if "c" in item["marks"]]))

    # 6 段内换行
    theirs = sinks["basic"]["breaks"]
    soft = [line for line in read("basic.text.txt").split("\n\n") if "段内换行" in line]
    mine = sum(line.count("\n") for line in soft)
    check(6, "段内换行（\\line）在两边都是这一段的换行数", theirs == mine == 1 and len(soft) == 1,
          "pandoc LineBreak：%d；我们那一段里的换行：%d（段数 %d）" % (theirs, mine, len(soft)))

    # 7 链接地址原样
    url = "https://example.com/report"
    in_html = url in read("basic.html")
    in_docx = url in external_targets(os.path.join(BUILD, "basic.docx"))
    theirs = set(sinks["basic"]["links"])
    check(7, "链接地址原样进网页与 Word 的关联表（pandoc 读到的就是这一个）",
          theirs == {url} and in_html and in_docx,
          "pandoc：%s；网页里有：%s；Word 关联表：%s" % (sorted(theirs), in_html, in_docx))

    # 8 表的张数与前两张的列数
    their_counts = column_counts(asts["table"])
    our_tables = [item for item in ours["table"] if item["kind"] == "table"]
    check(8, "表的张数与前两张的列数与 pandoc 一致",
          len(our_tables) == 3 and len(their_counts) == 3 and
          their_counts[:2] == [item["cols"] for item in our_tables][:2],
          "pandoc：%s；我们：%s" % (their_counts, [item["cols"] for item in our_tables]))

    # 9 / 10 整张格子与两处补齐
    their_rows = table_rows(asts["table"])[0]
    our_first = our_tables[0]
    flat = read("table.text.txt").split("\n\n")[1].split("\n")
    cells = [line.split("\t") for line in flat]
    web = html_tables(os.path.join(BUILD, "table.html"))
    check(9, "第一张表逐格对得上（4 行 × 3 列）",
          len(cells) == 4 and len(their_rows) == 4 and our_first["rows"] == 4 and our_first["cols"] == 3 and
          [[norm(cell) for cell in row] for row in their_rows] == [[norm(cell) for cell in row] for row in cells],
          "pandoc：%s；我们：%s" % (their_rows, cells))
    check(10, "两处补齐都判得到（行内少写的补到自己声明的列数、整张表补到最宽的行）：看写出去的网页里有几格",
          [len(row) for row in web[0]] == [3, 3, 3, 3] and web[0][2][2] == "" and web[0][3][1:] == ["", ""] and
          [len(row) for row in web[2]] == [3, 3] and all(row[1:] == ["", ""] for row in web[2]),
          "网页里第一张每行格数：%s，尾巴两行：%s；第三张每行格数：%s" % (
              [len(row) for row in web[0]], web[0][2:], [len(row) for row in web[2]]))
    check(19, "每行只写一格而声明三列的那张表：我们补到声明的三列，pandoc 只给一格（这处它比我们弱）",
          their_counts[2] == 1 and our_tables[2]["cols"] == 3 and
          [[norm(cell) for cell in row] for row in table_rows(asts["table"])[2]] ==
          [["三列只写了一格"], ["又一格"]],
          "pandoc 的列数：%s；我们的：%s" % (their_counts[2], our_tables[2]["cols"]))

    # 11 表头来自 \trhdr
    check(11, "表头来自 \\trhdr：pandoc 读不出表头（我们读得出），没写的两边都没表头",
          head_row_counts(asts["table"]) == [0, 0, 0] and
          [item["header"] for item in ours["table"] if item["kind"] == "table"] == [True, False, False],
          "pandoc 的表头行数：%s；我们的：%s" % (
              head_row_counts(asts["table"]),
              [item["header"] for item in ours["table"] if item["kind"] == "table"]))

    # 12 列表结构
    their_lists = sum(1 for block in asts["basic"]["blocks"] if block.get("t") in ("BulletList", "OrderedList"))
    items = [item for item in ours["basic"] if item.get("style") == "ListParagraph"]
    check(12, "列表结构是我们独有的：pandoc 一个列表块都读不出，我们 6 段且圆点 / 编号与层号各就各位",
          their_lists == 0 and len(items) == 6 and
          [(item["bullet"], item["indent"]) for item in items] ==
          [(True, 0), (True, 0), (False, 0), (False, 0), (True, 1), (False, 1)],
          "pandoc 列表块：%d；我们的列表段：%s" % (
              their_lists, [(item["bullet"], item["indent"]) for item in items]))

    # 13 记号没混进正文
    prose = read("basic.text.txt")
    marker_lines = [line for line in prose.splitlines() if re.match(r"^\s*(•|[0-9]+[.)、]|[a-zA-Z][.)])\s", line)]
    check(13, "列表记号没混进正文（• 与 1. 都不在文字里）",
          "•" not in prose and not marker_lines,
          "带着记号的行：%s" % marker_lines[:4])

    # 18 套在表里的表：里面的字我们留着，pandoc 把它丢了
    nested_cells = [item for item in ours["nested"] if item["kind"] == "table"][0]
    their_nested = plain(asts["nested"]["blocks"])
    our_nested = read("nested.text.txt")
    check(18, "套在表里的表：里面的格并成外层那一格的文字，字不丢（pandoc 把这些字丢了）",
          nested_cells["rows"] == 1 and nested_cells["cols"] == 2 and
          all(word in our_nested for word in ("外格开头", "内A", "内B", "外格第二列")) and
          "内A" not in their_nested and "套了" in read("nested.notes.txt"),
          "我们的形状：%s；pandoc 那边含内层的字：%s" % (
              [nested_cells["rows"], nested_cells["cols"]], "内A" in their_nested))

    # 14 表外的 \cell
    our_tables = sum(1 for item in ours["codepage"] if item["kind"] == "table")
    check(14, "表外的 \\cell 不当成一张表（pandoc 当成表，我们不），并说出来",
          our_tables == 0 and len(column_counts(asts["codepage"])) == 1 and
          "不在表里" in read("codepage.notes.txt"),
          "pandoc 表数：%d；我们表数：%d" % (len(column_counts(asts["codepage"])), our_tables))

    # 15 936 代码页独立解一遍
    with open(os.path.join(FIXTURES, "codepage.rtf"), "rb") as handle:
        raw = handle.read().decode("latin-1")
    line = next((item for item in raw.splitlines() if "\\'c4\\'e3" in item), "")
    groups = re.findall(r"(?:\\'[0-9a-fA-F]{2})+", line)
    decoded = [codecs.decode(bytes(int(pair, 16) for pair in re.findall(r"[0-9a-fA-F]{2}", group)), "gbk")
               for group in groups]
    warn = subprocess.run(
        ["pandoc", "-f", "rtf", "-t", "plain", os.path.join(FIXTURES, "codepage.rtf")],
        capture_output=True, text=True, encoding="utf-8",
    )
    text = read("codepage.text.txt")
    inside = [item for item in decoded if item in text]
    check(15, "936 代码页按 Python 的 gbk 独立解一遍能对上；pandoc 自己报读不动（差距记在这儿）",
          "你好世界" in decoded and len(inside) == len(decoded) and "code page" in warn.stderr.lower(),
          "gbk 解出来：%s；在我们产物里的：%s；pandoc 警告：%s" % (
              decoded, inside, warn.stderr.strip()[:120]))

    # 16 丢的都有交代
    notes = read("basic.notes.txt") + "\n" + read("table.notes.txt") + "\n" + read("codepage.notes.txt")
    need = ["图片", "脚注", "二进制", "目的群", "域代码"]
    check(16, "丢掉的东西都有交代（图片 / 脚注 / 二进制 / 认不出的目的群 / 域代码）",
          all(word in notes for word in need), "缺：%s" % [word for word in need if word not in notes])

    # 17 写出去的 ODT 别人读得动
    odt = attempt(17, "写出去的 ODT 用 pandoc 读回来", lambda: pandoc_read("odt", os.path.join(BUILD, "basic.odt")))
    if isinstance(odt, dict):
        levels = [block["c"][0] for block in odt["blocks"] if block.get("t") == "Header"]
        body = plain(odt["blocks"])
        check(17, "写出去的 ODT 用 pandoc 读回来：标题层级与列表项都还在",
              levels[:3] == [1, 2, 1] and "圆点第一项" in body and "季度报告" in body and "•" not in body,
              "层级：%s；正文含列表项：%s" % (levels[:3], "圆点第一项" in body))

    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    for number, name, _ in failed:
        print("  红的是第 %d 条：%s" % (number, name))
    if "code page" in warn.stderr:
        print("  参照物的短板：pandoc 读不动 936 代码页（%s）" % warn.stderr.strip().splitlines()[0][:90])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
