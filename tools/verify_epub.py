#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿两个不相干的实现复核「EPUB → 文字 / Markdown / Word」：ElementTree 认结构与编码，pandoc 认 AST。

被复核的产物（按顺序先跑）：
    python tools/make_epub_fixtures.py
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.Epub*'

五条判据各自的分工：
 1) 章序与章名      —— ElementTree 自己按 container → OPF → spine 走一遍（href 解百分号、去片段、
                       相对 OPF、线性为 no 不算），与我们落的 order.txt 逐行对；名字按「NCX 优先」独立算
 2) 一个字都没丢    —— 每章 XHTML 里的块文字（ElementTree 按文档顺序取）在我们的纯文本里按序出现，
                       而且别章与没排进 spine 的附录不能混进来
 3) Markdown 的结构 —— 我们的 .md 与 pandoc 自己读 EPUB，两边都过一遍 pandoc 的 AST，逐块比
                       类型/层级/文字/强调标记数（章名那几段是 pandoc 自己插的，不参与比较）
 4) Word 也读得回   —— pandoc 读我们的 docx，与同一套块序列再对一遍；外链要落在 rels 里且 TargetMode 是外部的
 5) UTF-16 的那本   —— ElementTree 按 XML 声明解出来的整章文字，我们的 .txt 与 .md 里逐句在
"""
import io
import json
import os
import re
import subprocess
import sys
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RES = os.path.join(ROOT, "core", "src", "test", "resources", "epub")
OUT = os.path.join(ROOT, "core", "build", "epub")
BOOK = os.path.join(RES, "book.epub")
U16 = os.path.join(RES, "utf16.epub")
MANIFEST = os.path.join(RES, "manifest.json")

results = []


def check(name, ok, detail=""):
    results.append((bool(ok), name, detail))


def run(cmd):
    return subprocess.run(cmd, capture_output=True, shell=True)


def read(path):
    return io.open(path, encoding="utf-8").read()


def norm(text):
    """比文字时把空白压成单个空格：排版差异不是内容差异。"""
    return re.sub(r"\s+", " ", text).strip()


def local(tag):
    return tag.split("}")[-1]


def manifest():
    return json.loads(read(MANIFEST))


# --- 1) spine：只用 ElementTree 从包里再算一遍 --------------------------------------------------

def resolve_spine(path):
    """返回 [(包内路径, 章名)]，章名按「NCX 目录名 > 文档自己的 title」这条优先级独立算。"""
    with zipfile.ZipFile(path) as book:
        names = book.namelist()
        container = ET.fromstring(book.read("META-INF/container.xml"))
        opf_name = [n.get("full-path") for n in container.iter() if local(n.tag) == "rootfile"][0]
        opf_name = urllib.parse.unquote(opf_name).lstrip("/")
        opf = ET.fromstring(book.read(opf_name))
        dirn = opf_name.rsplit("/", 1)[0] if "/" in opf_name else ""

        def part_of(href):
            clean = urllib.parse.unquote(href).split("#")[0].split("?")[0]
            return clean if not dirn else "%s/%s" % (dirn, clean)

        items, ncx, spine = {}, None, []
        for node in opf.iter():
            tag = local(node.tag)
            if tag == "item":
                href = node.get("href") or ""
                if (node.get("media-type") or "") == "application/x-dtbncx+xml":
                    ncx = part_of(href)
                elif (node.get("media-type") or "").startswith("application/xhtml"):
                    items[node.get("id")] = part_of(href)
            elif tag == "itemref" and (node.get("linear") or "yes") != "no":
                spine.append(node.get("idref"))
        labels = {}
        if ncx and ncx in names:
            label, src = None, None
            for node in ET.fromstring(book.read(ncx)).iter():
                tag = local(node.tag)
                if tag == "navPoint":
                    if src and label:
                        labels.setdefault(src, label)
                    label, src = None, None
                elif tag == "text" and label is None and (node.text or "").strip():
                    label = node.text.strip()
                elif tag == "content" and src is None:
                    src = part_of(node.get("src"))
            if src and label:
                labels.setdefault(src, label)
        out = []
        for idref in spine:
            part = items[idref]
            title = labels.get(part)
            if not title:
                root = ET.fromstring(book.read(part))
                title = next((n.text.strip() for n in root.iter()
                              if local(n.tag) == "title" and (n.text or "").strip()), part)
            out.append((part, title))
        return out


def our_order():
    rows = []
    for line in read(os.path.join(OUT, "order.txt")).splitlines():
        if line.strip():
            part, title = line.split("\t", 1)
            rows.append((part, title))
    return rows


# --- 2) 块文字：每章 XHTML 里的成块文字，按文档顺序 ---------------------------------------------

BLOCKS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "th", "td", "caption"}


def block_texts(xhtml_bytes):
    root = ET.fromstring(xhtml_bytes)
    out = []
    for node in root.iter():
        if local(node.tag) in BLOCKS:
            text = norm("".join(node.itertext()))
            if text:
                out.append(text)
    return out


def chapter_bytes(path, part):
    with zipfile.ZipFile(path) as book:
        return book.read(part)


def expected_kinds(xhtml_bytes):
    """body 的直接子元素逐个折成块类型名：列表与表格带项/行数。这是独立于我们的第二套判断。"""
    body = next(n for n in ET.fromstring(xhtml_bytes).iter() if local(n.tag) == "body")
    kinds = []
    for node in body:
        tag = local(node.tag)
        if not norm("".join(node.itertext())):
            continue
        if tag.startswith("h") and tag[1:].isdigit():
            kinds.append("H" + tag[1])
        elif tag in ("ul", "ol"):
            head = "OL" if tag == "ol" else "UL"
            kinds.append(head + str(len([c for c in node if local(c.tag) == "li"])))
        elif tag == "pre":
            kinds.append("CODE")
        elif tag == "blockquote":
            kinds.append("BQ")
        elif tag == "table":
            kinds.append("TABLE%d" % len([c for c in node.iter() if local(c.tag) == "tr"]))
        else:
            kinds.append("P")
    return kinds


# --- 3) pandoc 的 AST：两边都折成同一种"块形状"再比 ---------------------------------------------

MARKS = {"Emph": "i", "Strong": "b", "Superscript": "sup", "Subscript": "sub",
         "Strikeout": "s", "SmallCaps": "sc"}
# Underline 不参与比较：Word 里链接带下划线是排版选择，pandoc 读 docx 时会把它当成一种标记报回来。


def gather_inlines(inlines, text, marks):
    for node in inlines:
        kind, c = node["t"], node.get("c")
        if kind == "Str":
            text.append(c)
        elif kind in ("Space", "SoftBreak", "LineBreak"):
            text.append(" ")
        elif kind in MARKS:
            marks[MARKS[kind]] += 1
            gather_inlines(c, text, marks)
        elif kind == "Code":
            marks["c"] += 1
            text.append(norm(c[1]))
        elif kind == "Link":
            url = c[2][0]
            if re.match(r"^[a-z]+:", url):
                marks["link"] += 1
                gather_inlines(c[1], text, marks)
                text.append("«%s»" % url)
            else:
                # 页内锚点：整篇摊平之后没有落点，我们只留文字。期望侧照同一条规则折一遍，
                # 锚点的"没丢"由 count_links 那条单独判（比这里更严）。
                gather_inlines(c[1], text, marks)
        elif kind == "Image":
            marks["img"] += 1
            gather_inlines(c[1], text, marks)
        elif kind == "Underline":
            # Word 给链接的下划线（我们写进去的）：不比排版，但字要收下
            gather_inlines(c, text, marks)
        elif kind == "Span":
            gather_inlines(c[1], text, marks)      # Span 的 c 是 [attrs, 内容]
        elif kind == "Quoted":
            gather_inlines(c[1], text, marks)


def count_links(xhtml_bytes):
    """从 XHTML 里直接数链接：外部地址与页内锚点各多少个 —— 链没链上、丢没丢，都以这里为准。"""
    external, anchor = [], []
    for node in ET.fromstring(xhtml_bytes).iter():
        if local(node.tag) != "a":
            continue
        href = (node.get("href") or "").strip()
        (external if re.match(r"^[a-z]+:", href) else anchor).append(href)
    return external, anchor


def unwrap(node):
    """pandoc 的 json 有两种写法：带 t/c 的对象与直接的位置数组。表格内部是后者，取最后一段就是行/格/块。"""
    if isinstance(node, dict) and "c" in node:
        return node["c"]
    return node


def block_shape(blocks, markers=None):
    """把一串块折成 [(类型, 文字, 标记计数)]：列表带项数、表带行数、标题带层级。

    pandoc 的 EPUB 读取器会在每章开头插一段"只有一个 span、span 的 identifier 是章文件名"的
    空段落 —— 那是它自己的分章记账，没有字，不参与比较；有 markers 时把它收下来当顺序裁判用。
    """
    out = []
    for b in blocks:
        kind, c = b["t"], b.get("c")
        text, marks = [], defaultdict(int)
        if kind in ("Para", "Plain") and len(c) == 1 and c[0]["t"] == "Span" and not c[0]["c"][1]:
            if markers is not None:
                markers.append(unwrap(unwrap(c[0]["c"][0]))[0])
            continue
        if kind in ("Para", "Plain"):
            label = "P"
            gather_inlines(c, text, marks)
        elif kind == "Header":
            label = "H%d" % c[0]
            gather_inlines(c[2], text, marks)
        elif kind == "BlockQuote":
            label = "BQ"
            text.append(" ".join(s[1] for s in block_shape(c)))
        elif kind == "CodeBlock":
            label = "CODE"
            text.append(norm(c[1]))
        elif kind in ("BulletList", "OrderedList"):
            items = c[1] if kind == "OrderedList" else c
            label = ("OL" if kind == "OrderedList" else "UL") + str(len(items))
            text.append(" / ".join(
                " ".join(s[1] for s in block_shape(item)) for item in items))
        elif kind == "Table":
            rows = list(unwrap(c[3])[-1])
            for body in c[4]:
                rows += unwrap(unwrap(body))[-1]
            rows += unwrap(unwrap(c[5]))[-1]
            label = "TABLE%d" % len(rows)
            cells = []
            for row in rows:
                for cell in unwrap(row)[-1]:
                    cells.append(" ".join(s[1] for s in block_shape(unwrap(unwrap(cell))[-1])))
            text.append(" | ".join(cells))
        else:
            label = kind
        joined = norm("".join(text))
        if not joined:
            continue          # 一个字都没有的块不比（各家工具的记账块长这样）
        marks = {k: v for k, v in marks.items() if v}
        out.append((label, joined, tuple(sorted(marks.items()))))
    return out


def pandoc(path, from_fmt):
    got = run('pandoc "%s" -f %s -t json' % (path, from_fmt))
    if got.returncode != 0:
        raise RuntimeError("pandoc -f %s 读 %s 失败: %s" % (
            from_fmt, os.path.basename(path), got.stderr.decode("utf-8", "replace")[:200]))
    return json.loads(got.stdout.decode("utf-8"))["blocks"]


def show(shape):
    return " ;; ".join("%s[%s]{%s}" % row for row in shape)


def first_diff(want, got):
    for index, (a, b) in enumerate(zip(want, got)):
        if a != b:
            return "第 %d 块不同：\n    期望 %s\n    我们 %s" % (index + 1, a, b)
    if len(want) != len(got):
        return "块数不同：%d 对 %d" % (len(want), len(got))
    return ""


# --- 4) docx 里的超链接 -------------------------------------------------------------------------------

def docx_rel_targets(docx_path):
    """从 docx 的 rels 里取外部链接：地址与 TargetMode 都要在，否则 Word 里点不动。"""
    with zipfile.ZipFile(docx_path) as pack:
        xml = pack.read("word/_rels/document.xml.rels").decode("utf-8")
    return re.findall(r'Target="([^"]+)"[^>]*TargetMode="([^"]+)"', xml)


# --- 主流程 -----------------------------------------------------------------------------------------------

def main():
    man = manifest()
    want_parts = [c["path"] for c in man["chapters"]]
    want_titles = [c["title"] for c in man["chapters"]]

    # 1) 章序与章名
    spine = resolve_spine(BOOK)
    got = our_order()
    check("章序与章名：ElementTree 独立算的 spine 与我们落的一致",
          [p for p, _ in spine] == want_parts and got == list(spine),
          "包里的 spine %s / 我们 %s" % ([p for p, _ in spine], [p for p, _ in got]))
    check("章名来源：NCX 有的用 NCX，没有的退回文档 title",
          [t for _, t in spine] == want_titles,
          "独立算 %s / 声明 %s" % ([t for _, t in spine], want_titles))
    notes = read(os.path.join(OUT, "notes.txt"))
    check("说明里的数对得上包：图片 1 张、没排进 spine 的部件 1 个",
          "1 张内嵌图片" in notes and "1 个清单里的部件没被 spine 用到" in notes,
          notes.replace("\n", " · "))

    # 2) 每章的字一句都不丢，别章的字一句都不多
    lost, mixed = [], []
    appendix = block_texts(chapter_bytes(BOOK, man["not_in_spine"][0]))[0]
    for index, (part, _) in enumerate(spine):
        wants = block_texts(chapter_bytes(BOOK, part))
        mine = norm(read(os.path.join(OUT, "chapter-%d.txt" % (index + 1))))
        at = -1
        for text in wants:
            found = mine.find(text, at + 1)
            if found < 0:
                lost.append("%s 里没按序找到「%s」" % (part, text[:24]))
            else:
                at = found
    whole = norm(read(os.path.join(OUT, "book.txt")))
    if appendix in whole:
        mixed.append("没排进 spine 的附录混进正文了")
    check("每章的块文字按文档顺序全在（一个字都没丢）", not lost, " ;; ".join(lost))
    check("没排进 spine 的部件不混进正文", not mixed, " ;; ".join(mixed))

    # 3) Markdown 的结构与 pandoc 读 EPUB 得到的一致
    markers = []
    epub_shape = block_shape(pandoc(BOOK, "epub"), markers)
    md_shape = block_shape(pandoc(os.path.join(OUT, "book.md"), "markdown"))
    check("Markdown 的块序列与 pandoc 读 EPUB 一致（含层级/列表项数/强调）",
          md_shape == epub_shape, first_diff(epub_shape, md_shape) or show(md_shape))
    check("pandoc 自己分章的顺序也与 spine 一致",
          markers == [c["href"].rsplit("/", 1)[-1] for c in man["chapters"]], str(markers))
    want_kinds = [k for part, _ in spine for k in expected_kinds(chapter_bytes(BOOK, part))]
    md_kinds = [row[0] for row in md_shape]
    check("块类型逐个对得上 XHTML 的结构（ElementTree 自己数的）",
          md_kinds == want_kinds, "%s 对 %s" % (md_kinds, want_kinds))

    # 3b) 链接：外部地址一个都不少，页内锚点少了要交代
    counted = [count_links(chapter_bytes(BOOK, part)) for part, _ in spine]
    want_ext = [u for ext, _ in counted for u in ext]
    want_anchor = sum(len(a) for _, a in counted)
    our_links = sum(dict(row[2]).get("link", 0) for row in md_shape)
    md_text = read(os.path.join(OUT, "book.md"))
    md_notes = read(os.path.join(OUT, "notes-md.txt"))
    gone = [u for u in want_ext if u not in md_text]
    check("外部链接的地址原样在，条数与 XHTML 里的对得上",
          not gone and our_links == len(want_ext),
          "少地址 %s · 我们 %d 条 对 包里 %d 条" % (gone, our_links, len(want_ext)))
    check("页内锚点没有落点这件事写进说明了",
          ("页内锚点" in md_notes) == bool(want_anchor),
          "包里 %d 处锚点 · 说明：%s" % (want_anchor, md_notes.strip().replace("\n", " · ")))

    # 4) Word 读得回同一套结构，外链在 rels 里
    docx_path = os.path.join(OUT, "structure.docx")
    docx_shape = block_shape(pandoc(docx_path, "docx"))
    check("Word 的块序列与 EPUB 一致（pandoc 读我们的 docx）",
          docx_shape == epub_shape, first_diff(epub_shape, docx_shape) or show(docx_shape))
    rels = docx_rel_targets(docx_path)
    check("Word 里的外链带着地址与 External 标记",
          ("https://example.com/epub", "External") in rels, str(rels))

    # 5) UTF-16 的那本：编码不能把字读坏
    u16 = man["utf16"]
    raw = chapter_bytes(U16, u16["path"])
    want_text = [norm(t) for t in block_texts(raw)]
    u16_txt = norm(read(os.path.join(OUT, "utf16.txt")))
    u16_md = read(os.path.join(OUT, "utf16.md"))
    missing = [t for t in want_text if t not in u16_txt or t not in norm(u16_md)]
    want_kinds_u16 = expected_kinds(raw)
    u16_kinds = [row[0] for row in block_shape(pandoc(os.path.join(OUT, "utf16.md"), "markdown"))]
    check("UTF-16 的整章字都读出来了（不是乱码）", not missing, "读不到 %s" % missing)
    check("UTF-16 那本的结构也认对了",
          u16_kinds == want_kinds_u16, "%s 对 %s" % (u16_kinds, want_kinds_u16))

    failed = 0
    for ok, name, detail in results:
        print("%s %s%s" % ("PASS" if ok else "FAIL", name, "" if ok or not detail else "\n      " + detail))
        failed += 0 if ok else 1
    print("EPUB 判据 %d 条 · 红 %d 条" % (len(results), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

