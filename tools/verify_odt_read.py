#!/usr/bin/env python3
"""
判 ODT 的结构读法（core/office/OdtRead.kt）与四条产物路。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.OdtReadTest' --rerun
    python tools/verify_odt_read.py

同一份 .odt，**pandoc 自己读**的结构，要和我们读出来再写成的 Markdown / 网页 / Word / 纯文本一致。
pandoc 的 ODT 读者是另一套实现，不会跟着我们"样式名怎么映射"的约定走。

十四条判据（每份样本各跑一遍）：
  1 包本身成立：mimetype 是第一条、不压缩、写着 opendocument.text —— FileKind 就按这条认 ODT
  2 pandoc 读得动这份 .odt（读不动 = 夹具或我们的包规矩坏了，红要指名）
  3 四条产物都在且非空
 4  块序列：pandoc 读 odt == pandoc 读我们的 Markdown
 5  块序列：pandoc 读 odt == pandoc 读我们的网页
 6  块序列：pandoc 读 odt == pandoc 读我们的 Word
 7  文字：Markdown / 网页 / Word 三份与 odt 一字不差（不多字、不少字、不改字）；
    表里的字不在这里比 —— 实测 pandoc 的 ODT 读者会把"跨格"挪到行尾，字序比不出对错，
    表由第 10 条逐格判
 8  标题层级序列一致
 9  列表：圆点表 / 编号表各有几张、最深几层，一致
 10 表格：整张格子逐格比 —— 期望值由 Python 这边**独立按 ODF 的网格规矩展开**
    （合并列补空、重复列再来一份、covered 占一格），不看 pandoc 读 odt 的结果：
    实测它把合并格与重复格都算一格，拿它对判等于让我们也少几格才算对
 11 链接地址集合一致（段内跳转除外，那条由 12 判）
 12 段内跳转（href 以 # 开头）不出现在任何产物里，且 notes 里报了这笔
 13 脚注与批注的正文不出现在任何产物里（它们是声明过要丢的）
 14 报数对得上：用 ElementTree 独立数一遍这份 odt 里有几处图形 / 脚注 / 批注 / 表，
    与我们 notes 里写的那个数字比
 15 空格与制表按文件里写的来：`text:s` 的 `text:c` 是几个空格、`text:tab` 有几个 ——
    这一条只能拿纯文本产物判，网页与 Markdown 会把连续空白折掉（那是浏览器的排版，不是内容）
 16 纯文本产物里每一块文字都在（含表里每一格）：它没有块类型可看，比不了字序，就逐块查

为什么不拿我们的 .shapes.txt 当判据：那是我们自己对结构的说法，拿它跟自己对上等于没判。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as Tree
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_epub_write import inline_text, norm, top_blocks  # noqa: E402

BUILD = os.path.join("core", "build", "odtread")
STEMS = ["letter", "kinds", "spans"]
ODF_TEXT = "application/vnd.oasis.opendocument.text"

NS = {
    "draw": "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0",
    "office": "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
    "table": "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
    "text": "urn:oasis:names:tc:opendocument:xmlns:text:1.0",
}

results = []


def check(number: int, name: str, ok: bool, detail: str = "") -> bool:
    results.append((number, name, ok))
    print("%s %d %s%s" % ("OK  " if ok else "FAIL", number, name, (" :: " + detail) if detail and not ok else ""))
    return ok


def attempt(number: int, name: str, action):
    """把"读不动"变成一条指名的红，而不是让 traceback 把整轮判据带走。"""
    try:
        return action()
    except Exception as error:  # noqa: BLE001 —— 判据要的是"这一条红"，不是栈
        return check(number, name, False, "读的时候炸了：%s" % (str(error)[:220],))


def pandoc_read(fmt: str, path: str) -> dict:
    run = subprocess.run(
        ["pandoc", "-f", fmt, "-t", "json", "--wrap=none", path],
        capture_output=True, text=True, encoding="utf-8",
    )
    if run.returncode != 0:
        raise RuntimeError("pandoc 读 %s（-%s）失败：%s" % (path, fmt, run.stderr.strip()[:200]))
    return json.loads(run.stdout)


def plain(node) -> str:
    """把 AST 里的字串起来，但**跳过脚注/尾注的正文**（那是声明要丢的东西）。

    自己走一遍而不是复用 inline_text：note 埋在段落中间，委托出去的那一层
    不认识 Note，会把注文一起串进来 —— 那样这条判据永远判不到"注文有没有混进正文"。
    """
    if isinstance(node, dict):
        kind = node.get("t")
        content = node.get("c")
        if kind == "Note":
            return ""
        if kind == "Str" and isinstance(content, str):
            return content
        if kind in ("Code", "CodeBlock"):
            if isinstance(content, list) and len(content) > 1:
                inner = content[1]
                return inner if isinstance(inner, str) else plain(inner)
            return plain(content)
        if kind in ("RawInline", "RawBlock"):
            if isinstance(content, list) and len(content) > 1 and isinstance(content[1], str):
                return content[1]
            return ""
        return plain(content)
    if isinstance(node, list):
        return "".join(plain(item) for item in node)
    return ""


def blocks_of(ast) -> list:
    """顶层块类型序列；空块与分隔线不算。

    pandoc 的 ODT 读者看不到分隔线（ODF 里那就是一条带样式的空段），所以两边都剔掉再比：
    分隔线自己有没有写出去，由第 3 条与 JVM 那边的断言判。
    """
    return [name for name in top_blocks(ast) if name != "HorizontalRule"]


def head_levels(ast) -> list:
    """(层级, 标题文字) 序列。

    pandoc 3.x 的 Header 是 [层级, [id, 类, 属性], [内容]] —— 层级是第一个位置的那个**整数**。
    早先按"类里找 LevelN"取，取不到就 everyone 一样，这条判据等于只数了标题有几条。
    """
    out = []
    for block in ast["blocks"]:
        if block.get("t") != "Header":
            continue
        content = block["c"]
        level = content[0] if isinstance(content, list) and content and isinstance(content[0], int) else "?"
        out.append(("Level%s" % level, norm(inline_text(block))))
    return out


def list_shape(ast) -> tuple:
    bullets = 0
    ordered = 0
    deepest = 0

    def walk(node, depth):
        nonlocal bullets, ordered, deepest
        if isinstance(node, dict):
            kind = node.get("t")
            hit = kind in ("BulletList", "OrderedList")
            if kind == "BulletList":
                bullets += 1
            elif kind == "OrderedList":
                ordered += 1
            if hit:
                deepest = max(deepest, depth + 1)
            walk(node.get("c"), depth + (1 if hit else 0))
        elif isinstance(node, list):
            for item in node:
                walk(item, depth)

    walk(ast["blocks"], 0)
    return bullets, ordered, deepest


def table_grids(ast) -> list:
    """每张表：(行数, 每一行的格数, ODF 声明的列数)。"""
    out = []
    for block in ast["blocks"]:
        if block.get("t") != "Table":
            continue
        content = block["c"]
        declared = len(content[2] or [])
        rows = list((content[3] or [[], []])[1] or [])
        for body in content[4] or []:
            # TableBody 的 JSON 是 [attr, rowHeadColumns, headerRows, bodyRows, ...]
            rows += list(body[3] or []) if len(body) > 3 else []
        if len(content) > 5 and content[5]:
            rows += list(content[5][1] or [])
        widths = [len(row[1] or []) for row in rows]
        out.append((len(rows), widths, declared))
    return out


def link_urls(ast) -> list:
    """链接地址集合。

    pandoc 3.x 的 Link 是 [Attr, [内容], [地址, 标题]] —— 地址在**第三项**。
    按更早的写法去第二项里取，拿到的是一个 dict，取不到就当成"没有链接"，
    于是"两边链接一致"永远成立（这条曾经就是空的）。
    """
    out = []

    def walk(node):
        if isinstance(node, dict):
            if node.get("t") == "Link":
                content = node.get("c")
                if isinstance(content, list) and len(content) > 2 and isinstance(content[2], list) and content[2]:
                    target = content[2][0]
                    if isinstance(target, str):
                        out.append(target)
            walk(node.get("c"))
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(ast["blocks"])
    return sorted(set(out))


def table_rows(ast) -> list:
    """每张表的**整张格子**（按行按列，空格子也占位）。"""
    out = []
    for block in ast["blocks"]:
        if block.get("t") != "Table":
            continue
        content = block["c"]
        rows = list((content[3] or [[], []])[1] or [])
        for body in content[4] or []:
            rows += list(body[3] or []) if len(body) > 3 else []
        if len(content) > 5 and content[5]:
            rows += list(content[5][1] or [])
        out.append([[norm(plain(cell_blocks(row_cell))) for row_cell in (row[1] or [])] for row in rows])
    return out


def cell_blocks(cell) -> list:
    """pandoc 3.x 的 Cell 是 [Attr, 对齐, 宽, 宽, [Block]]：块在最后一项。"""
    return cell[-1] if isinstance(cell, list) and isinstance(cell[-1], list) else []


def text_blocks(ast) -> str:
    """整篇文字，但**表里的不算**（表由第 10 条逐格判位置与内容）。

    为什么不连着表一起比字序：实测 pandoc 的 ODT 读者会把它眼里的"跨格"挪到行尾，
    同一张表它读出来的字序与 ODF 自己写的顺序不一样 —— 拿它比字序会把对的判成错。
    """
    return "".join(plain(block) for block in ast["blocks"] if block.get("t") != "Table")


def expected_tables(path: str) -> list:
    """每张表的整张格子，按 ODF 自己的声明在 Python 这边独立展开一遍。

    三条展开规矩（ODF 的表格里格子占的是"位置"，跨度只是写法）：
      - `table:number-columns-spanned="N"`：这一格占 N 列 → 后面补 N-1 个空格子
      - `table:number-columns-repeated="N"`：这一格（连着它自己的跨度）再来 N 份
      - `table:covered-table-cell`：被合并掉的格子，本身就是一格空的
    行上的 `number-rows-repeated` 同样要展开。

    为什么不拿 pandoc 读 .odt 的结果当参照：实测它把合并格与重复格都算一格，
    拿它对判等于让我们也少几格才算对。
    """
    with zipfile.ZipFile(path) as pack:
        root = Tree.fromstring(pack.read("content.xml"))
    tag_of = lambda node: node.tag.split("}")[-1]
    span = "{%s}" % NS["table"]

    def expand_row(row) -> list:
        cells = []
        for cell in row:
            kind = tag_of(cell)
            if kind == "covered-table-cell":
                cells.append("")
                continue
            if kind != "table-cell":
                continue
            text = norm("".join(cell.itertext()))
            spanned = max(int(cell.get(span + "number-columns-spanned", "1")), 1)
            repeated = max(int(cell.get(span + "number-columns-repeated", "1")), 1)
            for _ in range(repeated):
                cells.append(text)
                cells.extend([""] * (spanned - 1))
        return cells

    out = []
    for table in root.iter(span + "table"):
        rows = []
        for child in table:
            kind = tag_of(child)
            if kind in ("table-header-rows", "table-rows"):
                rows += [expand_row(row) for row in child if tag_of(row) == "table-row"]
            elif kind == "table-row":
                one = expand_row(child)
                rows += [one] * max(int(child.get(span + "number-rows-repeated", "1")), 1)
        out.append(rows)
    return out


def spacing_declared(path: str) -> tuple:
    """ODF 里写明的空格与制表：`text:s`（`text:c` 给个数，不写就是一个）与 `text:tab`。

    只看正文段落里的（脚注与批注本来就被丢掉，拿它们判会判到自己头上）。
    """
    with zipfile.ZipFile(path) as pack:
        root = Tree.fromstring(pack.read("content.xml"))
    dropped = set()
    for patch in ("text:note-body", "office:annotation"):
        for node in root.findall(".//" + patch, NS):
            dropped.add(id(node))
            for kid in node.iter():
                dropped.add(id(kid))
    biggest = 1
    tabs = 0
    for node in root.iter():
        if id(node) in dropped:
            continue
        tag = node.tag.split("}")[-1]
        if tag == "s":
            biggest = max(biggest, int(node.get("{%s}c" % NS["text"], "1")))
        elif tag == "tab":
            tabs += 1
    return biggest, tabs


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def odt_counts(path: str) -> dict:
    """独立数一遍这份 ODF 里有什么：只走 XML，不看我们的读法。

    图形按 `draw:frame` 数（我们读到 frame 就不往里钻，里面的 image 不再数第二笔）。
    """
    with zipfile.ZipFile(path) as pack:
        content = pack.read("content.xml")
    root = Tree.fromstring(content)
    body = root.find("office:body/office:text", NS)
    if body is None:
        body = root

    def how(patch: str) -> int:
        return len(body.findall(".//" + patch, NS))

    return {
        "图形": how("draw:frame"),
        "脚注": how("text:note"),
        "批注": how("office:annotation"),
        "表": how("table:table"),
    }


def pack_text(path: str) -> str:
    with zipfile.ZipFile(path) as pack:
        return pack.read("content.xml").decode("utf-8")


def reported(notes: str, keyword: str) -> int:
    """从我们写的交代里取出那个数字（每条都是"N 处/张 ……"开头）。"""
    for line in notes.splitlines():
        if keyword in line:
            found = re.match(r"(\d+)", line.strip())
            if found:
                return int(found.group(1))
            found = re.search(r"(\d+)\s*(处|张|个|段)", line)
            if found:
                return int(found.group(1))
    return -1


def main() -> int:
    if not os.path.isdir(BUILD):
        print("FAIL 0 没有 %s（先跑 :core:test 的落盘那条）" % BUILD)
        return 1
    for stem in STEMS:
        odt = os.path.join(BUILD, "%s.odt" % stem)
        if not attempt(1, "%s 的包先成立（mimetype 第一条不压缩）" % stem, lambda: package_ok(odt)):
            continue
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
        source = attempt(2, "%s 能被 pandoc 读动" % stem, lambda: pandoc_read("odt", odt))
        if not isinstance(source, dict):
            continue
        check(2, "%s 能被 pandoc 读动" % stem, True)
        readings = {}
        broke = False
        for key, (path, fmt) in products.items():
            made = attempt(4, "%s 的 %s 能被 pandoc 读动" % (stem, key), lambda path=path, fmt=fmt: pandoc_read(fmt, path))
            if not isinstance(made, dict):
                broke = True
            readings[key] = made
        if broke:
            continue
        want = blocks_of(source)
        check(4, "%s 块序列：odt == Markdown" % stem, blocks_of(readings["md"]) == want,
              "%s vs %s" % (want, blocks_of(readings["md"])))
        check(5, "%s 块序列：odt == 网页" % stem, blocks_of(readings["html"]) == want,
              "%s vs %s" % (want, blocks_of(readings["html"])))
        check(6, "%s 块序列：odt == Word" % stem, blocks_of(readings["docx"]) == want,
              "%s vs %s" % (want, blocks_of(readings["docx"])))
        words = norm(text_blocks(source))
        texts = {key: norm(text_blocks(ast)) for key, ast in readings.items()}
        bad = {key: len(value) for key, value in texts.items() if value != words}
        check(7, "%s 文字一字不差（表由第 10 条判，脚注与批注按声明丢掉）" % stem, not bad,
              "odt 侧 %d 字，不对的：%s" % (len(words), diff_where(words, texts, bad)))
        levels = head_levels(source)
        check(8, "%s 标题层级一致" % stem,
              all(head_levels(readings[key]) == levels for key in readings),
              "%s vs %s" % (levels, {key: head_levels(ast) for key, ast in readings.items()}))
        shape = list_shape(source)
        check(9, "%s 列表形状一致（圆点/编号/深度）" % stem,
              all(list_shape(readings[key]) == shape for key in readings),
              "%s vs %s" % (shape, {key: list_shape(readings[key]) for key in readings}))
        want = expected_tables(odt)
        ours = {key: table_rows(ast) for key, ast in readings.items()}
        off = {key: value for key, value in ours.items() if value != want}
        check(10, "%s 表格：每一格的位置与内容都按 ODF 的网格展开" % stem, not off,
              "应有 %s；不一致的产物：%s" % (want, {key: off[key] for key in list(off)[:1]}))
        # 段内跳转（# 开头）由第 12 条单独判"不许落进产物"，这里只比要搬的那些地址
        urls = [url for url in link_urls(source) if not url.startswith("#")]
        check(11, "%s 链接地址集合一致" % stem,
              all(link_urls(readings[key]) == urls for key in readings),
              "odt=%s vs %s" % (urls, {key: link_urls(readings[key]) for key in readings}))
        anchors = [hit for hit in re.findall(r'xlink:href="([^"]*)"', pack_text(odt)) if hit.startswith("#")]
        notes = read(os.path.join(BUILD, "%s.notes.txt" % stem))
        leaked = [hit for hit in anchors if ('"%s"' % hit in read(products["html"][0]) or "(%s)" % hit in read(products["md"][0]))]
        check(12, "%s 段内跳转不落进产物且报了数" % stem,
              not leaked and (not anchors or "段内跳转" in notes),
              "跳出来的地址：%s；notes=%s" % (leaked, notes.replace("\n", " / ")[:160]))
        hidden = note_bodies(odt)
        everywhere = "".join(read(products["md"][0]) + read(products["html"][0]) + read(text_path))
        check(13, "%s 脚注与批注的正文不混进正文" % stem,
              not [piece for piece in hidden if piece in everywhere],
              "混进来的：%s" % [piece for piece in hidden if piece in everywhere])
        counted = odt_counts(odt)
        told = {
            "图形": reported(notes, "图片/图形"),
            "脚注": reported(notes, "脚注"),
            "批注": reported(notes, "批注"),
            "表": reported(notes, "张表"),
        }
        wrong = {key: (counted[key], told[key]) for key in counted if told.get(key, -1) >= 0 and counted[key] != told[key]}
        silent = [key for key in ("图形", "脚注", "批注") if counted[key] > 0 and told.get(key, -1) < 0]
        check(14, "%s 丢了什么报的数与独立数出来的一致" % stem, not wrong and not silent,
              "应有 %s，写了 %s；没报的：%s" % (counted, told, silent))
        spaces, tabs = spacing_declared(odt)
        plain_out = read(text_path)
        longest = max((len(run) for run in re.findall(r" +", plain_out)), default=0)
        check(15, "%s 空格个数与制表按文件里写的来" % stem,
              longest >= spaces and plain_out.count("\t") >= tabs,
              "文件里写着 %d 个连续空格、%d 个制表；产物里最长 %d 个空格、%d 个制表"
              % (spaces, tabs, longest, plain_out.count("\t")))
        # 纯文本这一条比不了字序（它没有块类型可看），改成逐块包含：
        # 表里的每一格也要在 —— 之前有过"表格整块不搬"的缺陷，只在有表的那份上才露出来
        pieces = [norm(plain(block)) for block in source["blocks"] if block.get("t") != "Table"]
        pieces += [cell for rows in want for row in rows for cell in row if cell]
        gone = [piece for piece in pieces if piece and piece not in norm(plain_out)]
        check(16, "%s 纯文本产物每块文字都在" % stem, not gone, "少了：%s" % gone[:3])
    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    return 1 if failed else 0


def package_ok(path: str) -> bool:
    with zipfile.ZipFile(path) as pack:
        first = pack.infolist()[0]
        if first.filename != "mimetype" or first.compress_type != zipfile.ZIP_STORED:
            raise RuntimeError("mimetype 不是第一条或不压缩：%s" % first.filename)
        declared = pack.read("mimetype").decode("utf-8").strip()
        if declared != ODF_TEXT:
            raise RuntimeError("mimetype 写的是 %r，不是文字文档" % declared)
    return True


def note_bodies(path: str) -> list:
    """脚注与批注里的正文：这些是我们声明要丢的，出现在产物里就是没丢干净。"""
    with zipfile.ZipFile(path) as pack:
        root = Tree.fromstring(pack.read("content.xml"))
    out = []
    for patch in ("text:note-body", "office:annotation"):
        for node in root.findall(".//" + patch, NS):
            piece = norm("".join(node.itertext()))
            if len(piece) > 3:
                out.append(piece)
    return out


def diff_where(words: str, texts: dict, bad: dict) -> str:
    """指出第一处差在哪，别只说"长度不一样"。"""
    out = []
    for key in bad:
        other = texts[key]
        at = next((i for i in range(min(len(words), len(other))) if words[i] != other[i]), min(len(words), len(other)))
        out.append("%s 从「%s」起不一致" % (key, other[max(0, at - 8):at + 12]))
    return "；".join(out)


if __name__ == "__main__":
    sys.exit(main())
