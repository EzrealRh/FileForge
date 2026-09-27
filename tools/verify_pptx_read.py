#!/usr/bin/env python3
"""
判 pptx 的结构读法（core/office/PptxRead.kt）与四条产物路。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.PptxReadTest' --rerun
    python tools/verify_pptx_read.py

同一份 .pptx，**pandoc 自己读**（它有另一套 pptx 解析器）的结构，要和我们读出来再写成的
Markdown / 网页 / Word / 纯文本一致。

十五条判据（每份样本各跑一遍）：
  1 包本身成立：有 [Content_Types].xml 与至少一页 slide
  2 pandoc 读得动这份 pptx（读不动就是夹具或包规矩坏了，红要指名）
  3 四条产物都在且非空
  4 块序列：pandoc 读 pptx == pandoc 读我们的 Markdown
  5 块序列：pandoc 读 pptx == pandoc 读我们的网页
  6 块序列：pandoc 读 pptx == pandoc 读我们的 Word
    （4/5/6 两边都做过两处归一：剔掉 pandoc 自己编出来的 "SlideN" 空标题 —— 那串字在包里
     根本不存在；把列表摊平成段落 —— 它的 pptx 读者认得出 buChar 却认不出 buAutoNum）
  7 页界：我们补的"第 N 页"条数 == 包里的页数，且 pandoc 那条没数进去
  8 标题层级序列一致
  9 列表：圆点表 / 编号表各有几张、最深几层 —— 参照是**包里的 XML**（ElementTree 自己数
    每页 a:pPr 上的 buChar / buAutoNum 与 lvl），三份产物与它一致
 10 表格：整张格子逐格比 —— 参照是**包里自己声明的位置**（a:tblGrid 的列数 + gridSpan / rowSpan 摆出来的
    位置模型，续格不双计）。pandoc 的 pptx 读者把只写 gridSpan 的那格塌成一格（4 列的表给出 3 格的行），
    它在这一条上不能当参照；顺带判"有没有哪一行的格子超出声明的列数"
 11 链接地址以**这份包里被 a:hlinkClick 用到的关系**为准（pandoc 的 pptx 读者会把 run 上的链接整个丢掉，
    它在这一条上比我们弱，不能当参照）；pandoc 那侧读出来的地址也不许多于包里的
 12 段内跳转（目标是包里的另一个部件）不落进任何一条产物，且 notes 报了这笔
 13 文字：三份与 pptx 一字不差（表里的字由第 10 条判；没算过的域不编字）
 14 报数对得上：ElementTree 独立数这份包里的图片 / 图表占位符 / 空域 / 备注页，
    与我们 notes 里写的数字比；且**备注与母版的文字没混进产物**
 15 表头行：包里每张表写的 firstRow（有 / 没有）与三份产物读回来的表头行数一致

与 docx / odt 那两份判据共用同一批取数函数（verify_odt_read 里的纯函数），三族比的是同一种东西。
换参照的几条都在这个文件里写明：块序列（4/5/6）听"pandoc 读 pptx 归一化后的结果"，列表（9）、链接（11）、
表格（10）三条改听**包里自己写的东西** —— `a:pPr` 的记号、被 `a:hlinkClick` 用到的关系、`a:tblGrid` 与跨度。
理由逐条写在各自函数上：pandoc 的 pptx 读者在编号表、run 上的链接、跨度补位三处都比我们弱
（它自己写出来的文件，它自己也读不全）。
"""
from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as Tree
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_epub_write import norm  # noqa: E402
from verify_odt_read import (  # noqa: E402
    attempt,
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

BUILD = os.path.join("core", "build", "pptxread")
STEMS = ["deck", "notes", "shapes"]
PAGE = re.compile(r"^第\s*\d+\s*页$")


def results_reset() -> None:
    import verify_odt_read

    verify_odt_read.results.clear()


def package_ok(path: str) -> bool:
    with zipfile.ZipFile(path) as pack:
        names = pack.namelist()
        if "[Content_Types].xml" not in names:
            raise RuntimeError("没有 [Content_Types].xml，这不是 OOXML 包")
        slides = [n for n in names if re.match(r"ppt/slides/slide\d+\.xml$", n)]
        if not slides:
            raise RuntimeError("一页 slide 都没有")
    return True


def slide_parts(path: str) -> list:
    with zipfile.ZipFile(path) as pack:
        return sorted(n for n in pack.namelist() if re.match(r"ppt/slides/slide\d+\.xml$", n))


def counted(path: str) -> dict:
    """独立数一遍这份包里的东西：图片、图表占位符、没算过的域、备注页。"""
    with zipfile.ZipFile(path) as pack:
        names = pack.namelist()
        pictures = 0
        charts = 0
        empty_fields = 0
        for name in [n for n in names if re.match(r"ppt/slides/slide\d+\.xml$", n)]:
            root = Tree.fromstring(pack.read(name))
            for node in root.iter():
                tag = str(node.tag).split("}")[-1]
                if tag == "pic":
                    pictures += 1
                elif tag == "ph" and (node.get("type") or "") in ("chart",):
                    charts += 1
                elif tag == "fld" and not (node.find("{http://schemas.openxmlformats.org/drawingml/2006/main}t") is not None
                                           and (node.find("{http://schemas.openxmlformats.org/drawingml/2006/main}t").text or "")):
                    empty_fields += 1
        notes = len([n for n in names if n.startswith("ppt/notesSlides/") and n.endswith(".xml")])
        return {"图片": pictures, "图表": charts, "空域": empty_fields, "备注页": notes}


def note_texts(path: str) -> list:
    """备注页与母版里的文字：这些是我们声明不搬的，出现在产物里就是漏了。"""
    out = []
    with zipfile.ZipFile(path) as pack:
        for name in pack.namelist():
            if not (name.startswith("ppt/notesSlides/") or name.startswith("ppt/slideMasters/")):
                continue
            if not name.endswith(".xml"):
                continue
            root = Tree.fromstring(pack.read(name))
            for node in root.iter("{http://schemas.openxmlformats.org/drawingml/2006/main}t"):
                piece = (node.text or "").strip()
                if len(piece) > 2:
                    out.append(piece)
    return out


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def strip_pages(ast) -> dict:
    """把"第 N 页"那些块剔掉再比：那是我们补的页界，pandoc 读 pptx 不会补。"""
    blocks = []
    for block in ast["blocks"]:
        if PAGE.match(norm_to_text(block)):
            continue
        blocks.append(block)
    return dict(ast, blocks=blocks)


def norm_to_text(block) -> str:
    return re.sub(r"\s+", "", plain(block))


def main() -> int:
    if not os.path.isdir(BUILD):
        print("FAIL 0 没有 %s（先跑 :core:test 的落盘那条）" % BUILD)
        return 1
    for stem in STEMS:
        source = os.path.join(BUILD, "%s.pptx" % stem)
        if not attempt(1, "%s 的包先成立" % stem, lambda s=source: package_ok(s)):
            continue
        check(1, "%s 的包先成立" % stem, True)
        pages = slide_parts(source)
        products = {
            "md": (os.path.join(BUILD, "%s.md" % stem), "markdown-smart"),
            "html": (os.path.join(BUILD, "%s.html" % stem), "html"),
            "docx": (os.path.join(BUILD, "%s.docx" % stem), "docx"),
        }
        missing = [key for key, (path, _) in products.items() if not os.path.exists(path) or os.path.getsize(path) == 0]
        text_path = os.path.join(BUILD, "%s.text.txt" % stem)
        if not os.path.exists(text_path) or not read(text_path).strip():
            missing.append("txt")
        check(3, "%s 的四条产物都在" % stem, not missing, "缺 %s" % missing)
        if missing:
            continue
        raw = attempt(2, "%s 能被 pandoc 读动" % stem, lambda s=source: pandoc_read("pptx", s))
        if not isinstance(raw, dict):
            continue
        check(2, "%s 能被 pandoc 读动" % stem, True)
        marked = [key for key in range(1, len(pages) + 1) if ("第 %d 页" % key) not in read(text_path)]
        check(7, "%s 的页界补得齐（%d 页）" % (stem, len(pages)), not marked, "产物里没有的页：%s" % marked)
        titles = slide_texts(source)
        want = flatten_lists(strip_pages(drop_invented(raw, titles)))
        readings = {}
        flat = {}
        broke = False
        for key, (path, fmt) in products.items():
            made = attempt(4, "%s 的 %s 能被 pandoc 读动" % (stem, key), lambda p=path, f=fmt: pandoc_read(f, p))
            if not isinstance(made, dict):
                broke = True
            stripped = strip_pages(drop_invented(made, titles)) if isinstance(made, dict) else made
            readings[key] = stripped
            flat[key] = flatten_lists(stripped) if isinstance(stripped, dict) else stripped
        if broke:
            continue
        want_blocks = blocks_of(want)
        check(4, "%s 块序列：pptx == Markdown" % stem, blocks_of(flat["md"]) == want_blocks,
              "%s vs %s" % (want_blocks, blocks_of(flat["md"])))
        check(5, "%s 块序列：pptx == 网页" % stem, blocks_of(flat["html"]) == want_blocks,
              "%s vs %s" % (want_blocks, blocks_of(flat["html"])))
        check(6, "%s 块序列：pptx == Word" % stem, blocks_of(flat["docx"]) == want_blocks,
              "%s vs %s" % (want_blocks, blocks_of(flat["docx"])))
        levels = head_levels(want)
        check(8, "%s 标题层级一致" % stem,
              all(head_levels(readings[key]) == levels for key in readings),
              "%s vs %s" % (levels, {key: head_levels(readings[key]) for key in readings}))
        shape = file_lists(source)
        check(9, "%s 列表形状与包里写的记号一致（圆点/编号/深度）" % stem,
              all(list_shape(readings[key]) == shape for key in readings),
              "包里 %s vs %s" % (str(shape), {key: list_shape(readings[key]) for key in readings}))
        declared, overflow = file_tables(source)
        ours = {key: table_rows(readings[key]) for key in readings}
        off = {key: value for key, value in ours.items() if value != declared}
        check(10, "%s 表格整张格子与包里声明的位置一致" % stem, not off and not overflow,
              "包里 %s（有 %d 处格子超出声明列数）vs %s" % (declared, overflow, {key: off[key] for key in list(off)[:1]}))
        declared = file_first_rows(source)
        heads = {key: table_heads(readings[key]) for key in readings}
        check(15, "%s 表头行按包里写的 firstRow 来" % stem,
              all(value == declared for value in heads.values()),
              "包里 %s vs %s" % (declared, {key: value for key, value in heads.items() if value != declared}))
        rels = hyperlinks(source)
        got = {key: set(link_urls(readings[key])) for key in readings}
        off = {key: sorted(value) for key, value in got.items() if value != rels["external"]}
        invented = sorted(set(url for url in link_urls(raw) if not url.startswith("#")) - rels["external"])
        check(11, "%s 链接地址以这页的关系表为准（不多不少）" % stem, not off and not invented,
              "包里有 %s；产物里 %s；pandoc 那侧读出了 %s" % (sorted(rels["external"]), off, invented))
        anchors = sorted(rels["internal"])
        notes_path = os.path.join(BUILD, "%s.notes.txt" % stem)
        notes = read(notes_path) if os.path.exists(notes_path) else ""
        everywhere = "".join(read(products["md"][0]) + read(products["html"][0]) + read(text_path))
        leaked = [hit for hit in anchors if hit in norm_keep(everywhere)]
        check(12, "%s 段内跳转不落进产物且报了数" % stem,
              not leaked and (not anchors or "段内跳转" in notes or "找不到目标" in notes),
              "跳出来的：%s；notes=%s" % (leaked, notes.replace("\n", " / ")[:150]))
        words = norm(text_blocks(want))
        texts = {key: norm(text_blocks(ast)) for key, ast in readings.items()}
        bad = {key: len(value) for key, value in texts.items() if value != words}
        check(13, "%s 文字一字不差（表由第 10 条判，空域不编字）" % stem, not bad,
              "pptx 侧 %d 字，不对的：%s" % (len(words), sorted(bad)))
        tally = counted(source)
        told = {
            "图片": number_in(notes, "图片"),
            "图表": number_in(notes, "图表"),
            "空域": number_in(notes, "域"),
        }
        wrong = {key: (tally[key], told[key]) for key in told if told[key] >= 0 and tally[key] != told[key]}
        silent = {key: tally[key] for key in told if tally[key] > 0 and told[key] < 0}
        hidden = [piece for piece in note_texts(source) if piece in norm_keep(everywhere)]
        check(14, "%s 报数与独立数出来的一致，备注与母版没混进产物" % stem,
              not wrong and not silent and not hidden,
              "应有 %s，写了 %s；没报的：%s；混进来的备注：%s" % (tally, told, silent, hidden[:2]))
    import verify_odt_read

    failed = [item for item in verify_odt_read.results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(verify_odt_read.results), len(failed)))
    return 1 if failed else 0


def norm_keep(text: str) -> str:
    return re.sub(r"\s+", "", text)


def number_in(notes: str, keyword: str) -> int:
    for line in notes.splitlines():
        if keyword in line:
            found = re.match(r"(\d+)", line.strip())
            if found:
                return int(found.group(1))
            found = re.search(r"(\d+)\s*(处|张|个|段|页)", line)
            if found:
                return int(found.group(1))
    return -1


PML = "{http://schemas.openxmlformats.org/presentationml/2006/main}"
DML = "{http://schemas.openxmlformats.org/drawingml/2006/main}"


def slide_texts(path: str) -> set:
    """包里的幻灯片里写着的文字（去掉空白）：用来认出裁判自己编出来的东西。"""
    out = set()
    with zipfile.ZipFile(path) as pack:
        for name in pack.namelist():
            if not re.match(r"ppt/slides/slide\d+\.xml$", name):
                continue
            for node in Tree.fromstring(pack.read(name)).iter(DML + "t"):
                piece = re.sub(r"\s+", "", node.text or "")
                if piece:
                    out.add(piece)
    return out


def drop_invented(ast, titles: set) -> dict:
    """剔掉 pandoc 的 pptx 读者**自己编出来**的标题块。

    一页没有标题占位符时，它会补一个 `Slide<部件号>` 的 Header —— 那串字在包里根本不存在
    （这里就是：slide3 只有一个正文占位符，它给出一级标题 "Slide3"）。
    我们这边不编字，所以参照要把这种块剔掉；剔之前先确认它真是编的（字不在包里）。
    """
    blocks = []
    for block in ast["blocks"]:
        made = re.fullmatch(r"Slide\d+", norm(re.sub(r"\s+", "", plain(block))))
        if made and made.group(0) not in titles:
            continue
        blocks.append(block)
    return dict(ast, blocks=blocks)


def flatten_lists(ast) -> dict:
    """把列表块摊平成一段一段：块序列那三条不判列表身份，列表由第 9 条对着包里的 XML 判。

    必须两边都摊：pandoc 的 pptx 读者认得出 `buChar` 却认不出 `buAutoNum`
    （编号表在文件里明写着 arabicPeriod，它也只当普通段落），拿它的块序列当参照，
    就会把我们读对的那张编号表判成"多读了列表"。
    """

    def expand(node, out: list) -> None:
        if isinstance(node, list):
            for item in node:
                expand(item, out)
        elif node.get("t") == "OrderedList":
            expand(node["c"][1], out)  # 编号表的 c 是 [属性, 条目]，不是条目本身
        elif node.get("t") in ("BulletList", "ListItem"):
            expand(node.get("c") or [], out)
        elif node.get("t") == "Plain":
            out.append({"t": "Para", "c": node.get("c") or []})  # 列表摊出来的段与正文段同一种块
        else:
            out.append(node)

    blocks: list = []
    expand(ast["blocks"], blocks)
    return dict(ast, blocks=blocks)


def file_lists(path: str) -> tuple:
    """按包里的 XML 独立数一遍列表：连续的带记号段落算一张表，深度看这一串里的 lvl 跨度。

    这是第 9 条的参照（不用 pandoc 读 pptx 的结果，理由见 [flatten_lists]）。
    """
    bullets = 0
    ordered = 0
    deepest = 0
    with zipfile.ZipFile(path) as pack:
        names = sorted(n for n in pack.namelist() if re.match(r"ppt/slides/slide\d+\.xml$", n))
        for name in names:
            root = Tree.fromstring(pack.read(name))
            run = []

            def flush(items: list) -> None:
                nonlocal bullets, ordered, deepest
                if not items:
                    return
                levels = [lvl for _, lvl in items]
                if items[0][0] == "bullet":
                    bullets += 1
                else:
                    ordered += 1
                # 深度按这一串里出现过的层号跨度算（层号能从 0 跳到 1，也能整串只有一层）
                deepest = max(deepest, max(levels) - min(levels) + 1)

            for shape in root.iter(PML + "sp"):
                body = shape.find("./" + PML + "txBody")
                if body is None:
                    continue
                for paragraph in body.findall(DML + "p"):
                    properties = paragraph.find(DML + "pPr")
                    kind = None
                    if properties is not None:
                        if properties.find(DML + "buChar") is not None:
                            kind = "bullet"
                        elif properties.find(DML + "buAutoNum") is not None:
                            kind = "number"
                    if kind is None or (run and run[-1][0] != kind):
                        flush(run)
                        run = []
                    if kind is not None:
                        run.append((kind, int(properties.get("lvl") or 0)))
            flush(run)
    return bullets, ordered, deepest


def file_tables(path: str) -> tuple:
    """每张表按包里的声明**独立摆一遍格子**（返回格子与"哪一行超出声明列数"的笔数）。

    表格这一条不拿 pandoc 的 pptx 读法当参照：实测它把只写 `gridSpan="2"` 的那格塌成一格
    （一张声明 4 列的表它给出 3 格的行，与它自己读出的别的行都不齐），同一件事在 ODF 那边
    已经换过一次裁判。这里按位置摆：`gridSpan` / `rowSpan` 盖到的位置都算被占住，
    自己写着是续格的（`hMerge` / `vMerge`）不再摆一次，空出来的位置是空串。
    """
    tables = []
    overflow = 0
    with zipfile.ZipFile(path) as pack:
        slides = sorted(n for n in pack.namelist() if re.match(r"ppt/slides/slide\d+\.xml$", n))
        for name in slides:
            root = Tree.fromstring(pack.read(name))
            for tbl in root.iter(DML + "tbl"):
                grid = tbl.find(DML + "tblGrid")
                width = len(grid.findall(DML + "gridCol")) if grid is not None else 0
                rows = tbl.findall(DML + "tr")
                taken = {}
                for index, tr in enumerate(rows):
                    column = 0
                    for tc in tr.findall(DML + "tc"):
                        while taken.get((index, column)) is not None:
                            column += 1          # 这个位置被上面跨下来的一格占着
                        if merged_away(tc):
                            continue             # 它说的就是那些已占住的位置：不摆，也不前进
                        text = "".join(node.text or "" for node in tc.iter(DML + "t")).strip()
                        spans = max(1, int(tc.get("gridSpan") or 1))
                        down = max(1, int(tc.get("rowSpan") or 1))
                        if column + spans > width:
                            overflow += 1
                        for row_step in range(down):
                            for column_step in range(spans):
                                taken[(index + row_step, column + column_step)] = \
                                    text if (row_step == 0 and column_step == 0) else ""
                        column += spans
                tables.append([[taken.get((row, column), "") for column in range(width)] for row in range(len(rows))])
    return tables, overflow


def merged_away(cell) -> bool:
    """这一格自己是来续上一格的吗（`hMerge="1"` / `vMerge="1"`，两种写法都表示"同一个合并"）。"""
    for name in ("hMerge", "vMerge"):
        value = (cell.get(name) or "").strip().lower()
        if value and value not in ("0", "false", "none"):
            return True
    return False


def table_heads(ast) -> list:
    """每张表**有没有**表头行：pandoc 3.9 的 Table 第四项是表头（[属性, 行]），空就是没有。"""
    out = []
    for block in ast["blocks"]:
        if block.get("t") != "Table":
            continue
        head = block["c"][3] or [[], []]
        out.append(bool(head[1] or []))
    return out


def file_first_rows(path: str) -> list:
    """包里每张表自己声明的首行是不是表头（`a:tblPr firstRow`）。

    没写这个属性时按"不是表头"算 —— 这一条与读法取同一个默认，所以它只判**写着的**那些：
    一张写了 firstRow="1"、一张什么都没写。
    """
    out = []
    with zipfile.ZipFile(path) as pack:
        names = sorted(n for n in pack.namelist() if re.match(r"ppt/slides/slide\d+\.xml$", n))
        for name in names:
            root = Tree.fromstring(pack.read(name))
            for tbl in root.iter(DML + "tbl"):
                properties = tbl.find(DML + "tblPr")
                value = properties.get("firstRow") if properties is not None else None
                out.append(str(value).lower() in ("1", "true", "on"))
    return out


def hyperlinks(path: str) -> dict:
    """从包里独立读出**被 a:hlinkClick 用到的**那些关系，按 TargetMode 分成外部地址与段内跳转。

    只数 hlinkClick 真正引用到的 id：slideLayout / notesSlide 这类关系每条 slide 都有，
    它们不是链接，混进来会把"段内跳转"数成 Structure 里的每个部件。
    """
    external = []
    internal = []
    with zipfile.ZipFile(path) as pack:
        names = set(pack.namelist())
        for name in sorted(n for n in names if re.match(r"ppt/slides/slide\d+\.xml$", n)):
            used = []
            for node in Tree.fromstring(pack.read(name)).iter():
                if str(node.tag).split("}")[-1] != "hlinkClick":
                    continue
                used += [value for key, value in node.attrib.items() if key.split("}")[-1] == "id"]
            rels = "%s/_rels/%s.rels" % (name.rsplit("/", 1)[0], name.rsplit("/", 1)[1])
            if rels not in names:
                continue
            for rel in Tree.fromstring(pack.read(rels)):
                if (rel.get("Id") or "") not in used:
                    continue
                target = rel.get("Target") or ""
                if (rel.get("TargetMode") or "").lower() == "external":
                    external.append(target)
                else:
                    internal.append(target)
    return {"external": set(external), "internal": set(internal)}


if __name__ == "__main__":
    sys.exit(main())
