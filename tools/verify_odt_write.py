#!/usr/bin/env python3
"""
判 Kotlin 写出去的 .odt（core/office/OdtWrite.kt）。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests "com.fileforge.core.OdtWriteTest" --rerun
    python tools/verify_odt_write.py

思路：同一棵文档树，我们从两条路写出去 —— `.odt`（这条要判的）与 `.md`（那条早被 pandoc 逐块对过）。
让 **pandoc 的 ODT 读者**读我们的 .odt、**pandoc 的 Markdown 读者**读我们的 .md，
两边读出来的结构必须是同一个东西。pandoc 不读我们的 Kotlin，两边独立，
所以"只有自家读得懂"的写法（样式名自己编、层级只写在名字里、列表把圆点写进文字里）在这条判据下过不去。

十二条判据：
  1 包本身成立：mimetype 第一条且原样不压缩、media-type 对、清单里列的文件都在包里
  2 pandoc 读得动这份 .odt（读不动就是不合规的 ODF，红要指名）；它读我们的包崩掉也算这一条红
  3 块序列：pandoc 读 .odt == pandoc 读 .md
  4 标题层级序列一致（几级标题、标题文字）
  5 列表形状一致：圆点表几张、编号表几张、最深几层 —— 编号读成圆点在这里会红
  6 整张格子一致：参照是**包里自己声明的位置**（Python 独立摆一遍 spanned / covered / repeated）——
    pandoc 的 ODT 读者看不见 `table:table-body` 里的行，它在这一条上不能当参照
 12 表头那行：躺在 `table:table-header-rows` 里的那几行，内容就是 pandoc 读 .md 认出的表头行
  7 链接地址集合一致，带 query 的不被改写
  8 文字一字不差（表里的字由第 6 条判）
  9 引用与代码：那份是 BlockQuote、那份是 CodeBlock，且代码的两行还在
 10 自家读路读回来的块清单里，分隔线在（pandoc 的 ODT 读者不认这根线，由我们自己判）
 11 传了书名就有 `meta.xml`，`dc:title` 照写的字
"""
from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as Tree
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from verify_epub_write import norm  # noqa: E402
from verify_odt_read import (  # noqa: E402
    cell_blocks,
    blocks_of,
    check,
    head_levels,
    link_urls,
    list_shape,
    pandoc_read,
    plain,
    table_rows,
    text_blocks,
)

BUILD = os.path.join("core", "build", "odtwrite")
ODT = os.path.join(BUILD, "book.odt")
MD = os.path.join(BUILD, "book.md")
SHAPE = os.path.join(BUILD, "book.shape.txt")
MIME = "application/vnd.oasis.opendocument.text"


def cell_text(cell) -> str:
    pieces = []
    for node in cell.iter():
        tag = str(node.tag).split("}")[-1]
        if tag in ("s", "tab", "line-break"):
            if tag == "s":
                pieces.append(" " * int(node.get("{urn:oasis:names:tc:opendocument:xmlns:text:1.0}c") or 1))
            elif tag == "tab":
                pieces.append("\t")
            else:
                pieces.append("\n")
        elif node.text:
            pieces.append(node.text)
    return "".join(pieces).strip()


def odt_tables() -> list:
    """独立把 .odt 里每张表按位置摆一遍（表头行在 `header-rows` 里，正文行在 `table-body` 里）。

    这条不拿 pandoc 读 .odt 的结果当参照：实测它的 ODT 读者只认 `table:table` 底下的裸行，
    钻进 `table:table-body` 的那几行它看不见 —— 而规范与 LibreOffice 都把正文行写在 body 里，
    拿它当参照等于让我们写一份不合规的文件去迎合它。
    """
    text_ns = "{urn:oasis:names:tc:opendocument:xmlns:text:1.0}"
    tables = []
    with zipfile.ZipFile(ODT) as pack:
        root = Tree.fromstring(pack.read("content.xml"))
        for table in root.iter("{urn:oasis:names:tc:opendocument:xmlns:table:1.0}table"):
            rows = []
            head_rows = []
            for node in table:
                tag = str(node.tag).split("}")[-1]
                if tag in ("table-column", "table-columns", "table-header-columns"):
                    continue
                for row in (node if tag in ("table-body", "table-header-rows", "table-rows", "table-footer-rows") else [node]):
                    if str(row.tag).split("}")[-1] != "table-row":
                        continue
                    cells = []
                    for cell in row:
                        kind = str(cell.tag).split("}")[-1]
                        if kind == "covered-table-cell":
                            cells.append("")
                            continue
                        if kind != "table-cell":
                            continue
                        spanned = int(cell.get("{urn:oasis:names:tc:opendocument:xmlns:table:1.0}number-columns-spanned") or 1)
                        repeated = int(cell.get("{urn:oasis:names:tc:opendocument:xmlns:table:1.0}number-columns-repeated") or 1)
                        value = cell_text(cell)
                        for _ in range(repeated):
                            cells.append(value)
                            cells.extend([""] * (spanned - 1))
                    rows.append(cells)
                    if tag == "table-header-rows":
                        head_rows.append(cells)
            tables.append({"rows": rows, "head": head_rows})
    return tables


def package_ok() -> tuple:
    """包本身的规矩：mimetype 排第一且不压缩、清单点到的文件都在、media-type 是文字文档。"""
    with zipfile.ZipFile(ODT) as pack:
        entries = pack.infolist()
        problems = []
        if not entries or entries[0].filename != "mimetype":
            problems.append("mimetype 不是第一条")
        elif entries[0].compress_type != zipfile.ZIP_STORED:
            problems.append("mimetype 被压缩了")
        if entries and entries[0].filename == "mimetype" and pack.read("mimetype").decode("utf-8") != MIME:
            problems.append("mimetype 写的是 %s" % pack.read("mimetype").decode("utf-8"))
        names = set(pack.namelist())
        manifest = Tree.fromstring(pack.read("META-INF/manifest.xml"))
        listed = []
        for entry in manifest:
            path = entry.get("{urn:oasis:names:tc:opendocument:xmlns:manifest:1.0}full-path") or ""
            if path and not path.endswith("/"):
                listed.append(path)
        missing = [path for path in listed if path not in names]
        if missing:
            problems.append("清单点了但包里没这些文件：%s" % missing)
        for part in ("content.xml", "styles.xml"):
            if part not in names:
                problems.append("少了 %s" % part)
        if "content.xml" in names:
            body = pack.read("content.xml").decode("utf-8")
            if "office:automatic-styles" in body and body.index("<office:automatic-styles") > body.index("<office:body"):
                problems.append("automatic-styles 要在 body 之前（ODF 规定的元素顺序）")
        return problems


def meta_title() -> str:
    with zipfile.ZipFile(ODT) as pack:
        if "meta.xml" not in pack.namelist():
            return ""
        root = Tree.fromstring(pack.read("meta.xml"))
        node = root.find(".//{http://purl.org/dc/elements/1.1/}title")
        return (node.text or "") if node is not None else ""


def safe_read(fmt: str, path: str):
    """pandoc 读不动就给 None：它读我们写的包崩了，也是"这份包不合规"的一种，得红在第 2 条。"""
    try:
        return pandoc_read(fmt, path)
    except Exception as error:                      # noqa: BLE001
        print("     pandoc %s 读 %s 崩了：%s" % (fmt, os.path.basename(path), str(error)[:160]))
        return None


def main() -> int:
    if not os.path.isdir(BUILD):
        print("FAIL 0 没有 %s（先跑 :core:test 的 OdtWriteTest 落盘那条）" % BUILD)
        return 1
    problems = package_ok()
    check(1, "包的规矩都守（mimetype 第一且不压缩、清单对得上）", not problems, str(problems))
    want = safe_read("odt", ODT)
    from_md = safe_read("markdown", MD)
    if not check(2, "pandoc 读得动这份 .odt（也读得动同内容的 .md）",
                 isinstance(want, dict) and isinstance(from_md, dict)):
        return finish()
    check(3, "块序列：pandoc 读 .odt == pandoc 读 .md", blocks_of(want) == blocks_of(from_md),
          "%s vs %s" % (blocks_of(want), blocks_of(from_md)))
    check(4, "标题层级一致", head_levels(want) == head_levels(from_md),
          "%s vs %s" % (head_levels(want), head_levels(from_md)))
    check(5, "列表形状一致（圆点/编号/深度）", list_shape(want) == list_shape(from_md),
          "%s vs %s" % (list_shape(want), list_shape(from_md)))
    declared = [[norm(cell) for cell in row] for table in odt_tables() for row in table["rows"]]
    from_md_grids = [row for table in table_rows(from_md) for row in table]
    check(6, "整张格子与包里自己声明的位置一致", bool(declared) and declared == from_md_grids,
          "包里 %s vs pandoc 读 md %s" % (declared, from_md_grids))
    def head_rows_of(ast) -> list:
        out = []
        for block in ast["blocks"]:
            if block.get("t") != "Table":
                continue
            rows = []
            for row in (block["c"][3] or [[], []])[1] or []:
                rows.append([norm(re.sub(r"\s+", "", plain(cell_blocks(cell)))) for cell in (row[1] or [])])
            out.append(rows)
        return out

    odt_heads = [[[norm(cell) for cell in row] for row in table["head"]] for table in odt_tables()]
    md_heads = head_rows_of(from_md)
    check(12, "表头那行躺在 header-rows 里，且就是 pandoc 读 md 认出的那一行",
          odt_heads == md_heads and all(rows for rows in odt_heads),
          "包里 %s vs md %s" % (odt_heads, md_heads))
    urls = link_urls(want)
    check(7, "链接地址集合一致且不被改写", urls == link_urls(from_md) and urls,
          "%s vs %s" % (urls, link_urls(from_md)))
    ours, theirs = norm(text_blocks(want)), norm(text_blocks(from_md))
    check(8, "文字一字不差（表除外）", ours == theirs, "odt %d 字 vs md %d 字" % (len(ours), len(theirs)))
    kinds = blocks_of(want)
    code = [block for block in want["blocks"] if block.get("t") == "CodeBlock"]
    check(9, "引用是 BlockQuote、代码是 CodeBlock 且两行还在",
          "BlockQuote" in kinds and len(code) == 1 and "代码第一行" in plain(code[0]) and "代码第二行" in plain(code[0]),
          "%s / 代码块 %d 份" % (kinds, len(code)))
    shape = open(SHAPE, encoding="utf-8").read() if os.path.exists(SHAPE) else ""
    check(10, "分隔线由自家读路认得（pandoc 的 ODT 读者不认这根线）", "分隔线" in shape,
          "shape 里没有分隔线：%s" % shape.replace("\n", " / ")[:150])
    check(11, "书名写进了 meta.xml", meta_title() == "简报", "读到 %r" % meta_title())
    return finish()


def finish() -> int:
    import verify_odt_read

    failed = [item for item in verify_odt_read.results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(verify_odt_read.results), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
