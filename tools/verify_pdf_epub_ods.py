"""EPUB 与 ODS 印成 PDF 的判据：抽出来那棵文档树在纸上还立得住。

真值来自**标准库**，不经过我们任何一份 Kotlin 读法：
  EPUB —— zipfile + ElementTree 自己解 container.xml → OPF → spine → 每章 XHTML（ElementTree 认
          XML 声明里的编码，UTF-16 那一章就是这么读的），按 spine 顺序收标题、正文与表格行；
  ODS  —— zipfile + ElementTree 解 content.xml 的 table:table / table-row / table-cell，
          自己展开重复计数；首行算不算表头看文件自己有没有用 <table:table-header-rows> 圈出来。

纸上的字由 **PyMuPDF** 读（带字号与位置），poppler 的 **pdftotext** 再数一遍非空白字符；
两个引擎的数差得多就当判据不成立（一个引擎的解码偏差不能当证据）。

字体本来就画不出来的字符（simhei 没有 Ç 这类）用 **fontTools 查 cmap** 独立算出来，从真值里剔掉再比 ——
应用会照实说"有 N 个字被跳过"，判据不该拿一个字体缺字形去判产品丢内容。

网格几何本身不在这里核：那一条由 tools/verify_pdf_table.py 对着 xlsx 那条路钉着，
EPUB / ODS 进的是同一个 PrintPlanner，这里只核"进得去、树没抽错、顺序对、空的不占节"。

跑法（工程根目录）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar
    python tools/verify_pdf_epub_ods.py
"""

import io
import os
import re
import subprocess
import sys
import urllib.parse
import zipfile
import xml.etree.ElementTree as ET

import fitz

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EPUB_DIR = os.path.join(ROOT, "core", "src", "test", "resources", "epub")
ODS_DIR = os.path.join(ROOT, "core", "src", "test", "resources", "odsread")
BUILD = os.path.join(ROOT, "core", "build", "epubods")
FONT = os.environ.get("PDF_TABLE_FONT", "C:/Windows/Fonts/simhei.ttf")
RUN = os.path.join(ROOT, "tools", "pdfprobe", "run.sh")
POPPLER = os.environ.get("PDFTOTEXT", r"C:/Program Files/Git/mingw64/bin/pdftotext.exe")
RESULTS = []

NS = {
    "c": "urn:oasis:names:tc:opendocument:xmlns:container",
    "opf": "http://www.idpf.org/2007/opf",
    "xhtml": "http://www.w3.org/1999/xhtml",
    "table": "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
    "text": "urn:oasis:names:tc:opendocument:xmlns:text:1.0",
    "office": "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
}


def report(name, ok, detail=""):
    RESULTS.append((name, ok))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, "" if not detail else "\n       " + detail))


def probe(source, out):
    env = dict(os.environ, PDFPROBE_OUT=os.environ.get("PDFPROBE_OUT", "D:/fflogs/pdfprobe"),
               GRADLE_USER_HOME=os.environ.get("GRADLE_USER_HOME", "D:/gradle-home"))
    run = subprocess.run(["bash", RUN, "docpdf", FONT, source, out], cwd=ROOT,
                         capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
    if run.returncode != 0:
        raise RuntimeError("探针跑不动 %s：%s" % (os.path.basename(source), (run.stderr or run.stdout)[-500:]))
    return run.stdout


def local(tag):
    return tag.split("}")[-1] if "}" in tag else tag


def strip_all(value):
    return re.sub(r"\s+", "", value or "")


def undrawable(text):
    """这份字体 cmap 里没有的字符 —— 独立用 fontTools 查，不套应用自己的说法。"""
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        return set()
    with TTFont(FONT, fontNumber=0, lazy=True) as font:
        cmap = font.getBestCmap() or {}
    return {ch for ch in set(text) if ord(ch) not in cmap}


def minus(value, absent):
    return strip_all("".join(ch for ch in value if ch not in absent))


def pdf_spans(pdf):
    """[(页, 上, 左, 字号, 文字)]，按纸上位置排。"""
    out = []
    with fitz.open(pdf) as doc:
        for page in doc:
            for block in page.get_text("dict")["blocks"]:
                for line in block.get("lines", []):
                    for span in line["spans"]:
                        if strip_all(span["text"]):
                            out.append((page.number, round(span["bbox"][1], 1), round(span["bbox"][0], 1),
                                        round(span["size"], 2), span["text"]))
    out.sort(key=lambda item: (item[0], item[1], item[2]))
    return out


def pdf_text(pdf):
    with fitz.open(pdf) as doc:
        return "".join(page.get_text() for page in doc)


def poppler_stripped(pdf):
    """poppler 数出来的非空白字符数；引擎不在就返回 None（那条判据自己会说不成立）。"""
    if not os.path.exists(POPPLER):
        return None
    txt = os.path.join(os.path.dirname(pdf), os.path.basename(pdf).replace(".pdf", ".pop.txt"))
    subprocess.run([POPPLER, "-enc", "UTF-8", pdf, txt], capture_output=True)
    if not os.path.exists(txt):
        return None
    with io.open(txt, encoding="utf-8", errors="replace") as handle:
        return len(strip_all(handle.read()))


def heavy_lines(pdf):
    """纸上线宽 >0.9pt 的横线数（PrintPlanner 把表头那条画 1.2pt，其余 0.5pt）。"""
    count = 0
    with fitz.open(pdf) as doc:
        for page in doc:
            for draw in page.get_drawings():
                width = draw.get("width") or 1
                if width <= 0.9:
                    continue
                for item in draw["items"]:
                    if item[0] == "l":
                        p1, p2 = item[1], item[2]
                        if abs(p1.y - p2.y) < 0.6:
                            count += 1
                    elif item[0] == "re":
                        count += 1
    return count


# ---------- EPUB 的真值：标准库解容器 + OPF + spine ----------

def epub_truth(path):
    with zipfile.ZipFile(path) as book:
        container = ET.fromstring(book.read("META-INF/container.xml"))
        opf_path = container.find(".//c:rootfile", NS).get("full-path")
        base = opf_path.rsplit("/", 1)[0] + "/" if "/" in opf_path else ""
        opf = ET.fromstring(book.read(opf_path))
        manifest = {}
        for item in opf.findall(".//opf:manifest/opf:item", NS):
            href = urllib.parse.unquote(item.get("href", ""))
            while href.startswith("../"):
                href = href[3:]
            manifest[item.get("id")] = base + href
        blocks = []
        for ref in opf.findall(".//opf:spine/opf:itemref", NS):
            name = manifest.get(ref.get("idref"))
            if not name:
                continue
            # linear="no"：EPUB 明说这一份不在主要阅读顺序里（这份夹具就拿它测第二遍引用同一章）
            if (ref.get("linear") or "yes").lower() == "no":
                continue
            root = ET.fromstring(book.read(name))   # ElementTree 按 XML 声明的编码解（UTF-16 也在行）
            walk(root.find("xhtml:body", NS), blocks)
    return blocks


def walk(node, blocks):
    if node is None:
        return
    for child in list(node):
        tag = local(child.tag)
        text = "".join(child.itertext()).strip()
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            blocks.append(("head" + tag[-1], text))
        elif tag == "table":
            for row in child.iter("{http://www.w3.org/1999/xhtml}tr"):
                cells = ["".join(cell.itertext()).strip() for cell in row if local(cell.tag) in ("td", "th")]
                blocks.append(("row", cells))
        elif tag in ("p", "blockquote", "li"):
            # ol / ul 自己不是一块：话在 li 里，跟着 itertext 会把两项并成一句"点一点二"
            if text:
                blocks.append(("body", text))
        elif len(child):
            walk(child, blocks)


# ---------- ODS 的真值：标准库解 content.xml 的表 ----------

def ods_truth(path):
    sheets = []
    with zipfile.ZipFile(path) as pack:
        root = ET.fromstring(pack.read("content.xml"))
        for table in root.iter("{%s}table" % NS["table"]):
            name = table.get("{%s}name" % NS["table"])
            # 首行算不算表头看文件自己怎么圈：ODF 用 <table:table-header-rows> 包住那几行
            header = table.find("{%s}table-header-rows" % NS["table"]) is not None
            values = []
            for row in table.iter("{%s}table-row" % NS["table"]):
                cells = []
                for cell in list(row):
                    if local(cell.tag) not in ("table-cell", "covered-table-cell"):
                        continue
                    repeat = int(cell.get("{%s}number-columns-repeated" % NS["office"], "1") or 1)
                    text = " ".join(filter(None, ("".join(p.itertext()).strip()
                                                  for p in cell.iter("{%s}p" % NS["text"]))))
                    cells.extend([text] * min(repeat, 200))
                while cells and not cells[-1]:
                    cells.pop()
                if cells:
                    values.append(cells)
            sheets.append({"name": name, "rows": values,
                           "cells": [cell for row in values for cell in row if cell], "header": header})
    return sheets


def write_empty_sheet_ods(path):
    """自己造一份"两张表，其中一张真空"的 ODS：仓库里那四份都没有空表，
    而"空表不占一节"正是要核的那条 —— 用标准库写，不借我们任何一份写侧。"""
    content = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<office:document-content xmlns:office="%(office)s" xmlns:table="%(table)s" xmlns:text="%(text)s">'
        '<office:body><office:spreadsheet>'
        '<table:table table:name="有字的一页"><table:table-row><table:table-cell>'
        '<text:p>甲</text:p></table:table-cell><table:table-cell><text:p>乙</text:p></table:table-cell>'
        '</table:table-row></table:table>'
        '<table:table table:name="一张空表"/></office:spreadsheet></office:body>'
        '</office:document-content>' % {"office": NS["office"], "table": NS["table"], "text": NS["text"]}
    )
    manifest = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">'
        '<manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>'
        '<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>'
        '</manifest:manifest>'
    )
    with zipfile.ZipFile(path, "w") as pack:
        info = zipfile.ZipInfo("mimetype")
        info.compress_type = zipfile.ZIP_STORED
        pack.writestr(info, "application/vnd.oasis.opendocument.spreadsheet")
        pack.writestr("META-INF/manifest.xml", manifest)
        pack.writestr("content.xml", content)


def main():
    os.makedirs(BUILD, exist_ok=True)

    # --- EPUB
    book = os.path.join(EPUB_DIR, "book.epub")
    out = os.path.join(BUILD, "book.pdf")
    probe(book, out)
    blocks = epub_truth(book)
    report("0 仪器自证：标准库从 book.epub 抽到 %d 块（四章的标题、正文、表格行）" % len(blocks),
           len(blocks) >= 12, "抽到 %d 块：%r" % (len(blocks), blocks[:3]))

    absent = undrawable("".join(text for _, text in blocks if isinstance(text, str)))
    paper = strip_all(pdf_text(out))
    spans = pdf_spans(out)
    sizes = sorted(size for _, _, _, size, _ in spans)
    body_size = sizes[len(sizes) // 2]
    missing = [text for kind, text in blocks
               if kind != "row" and minus(text, absent) and minus(text, absent) not in paper]
    rows_missing = [cell for kind, cells in blocks if kind == "row" for cell in cells
                    if minus(cell, absent) and minus(cell, absent) not in paper]
    report("1 EPUB：spine 排出的每一块文字都在纸上（缺 %d 块）" % (len(missing) + len(rows_missing)),
           not missing and not rows_missing, "；".join((missing + rows_missing)[:3]))

    heads = [text for kind, text in blocks if kind.startswith("head")]
    located = []
    for head in heads:
        key = minus(head, absent)
        where = paper.find(key)
        hit = sorted((span for span in spans if key and minus(span[4], absent).startswith(key[:6])),
                     key=lambda span: (span[0], span[1]))
        if key and where >= 0:
            located.append((head, where, hit[0][3] if hit else 0.0))
    report("2 EPUB：%d 个章标题逐个在纸上找得到（找不到的就不许这条算过）" % len(located),
           len(located) == len(heads) > 0,
           "缺：%r" % ([h for h in heads if h not in [item[0] for item in located]],))
    # 顺序按"整本书的块"核，不只核标题：一块一块按 spine 给的顺序在纸上往后找，
    # 找不齐就是排错了序（只核标题会漏掉"标题对、正文串到下一章"这一类）
    cursor, out_of_order = 0, []
    for kind, text in blocks:
        key = minus("".join(text) if isinstance(text, list) else text, absent)
        if not key:
            continue
        where = paper.find(key, cursor)
        if where < 0:
            out_of_order.append("%s:%s（游标 %d 之后找不到）" % (kind, key[:16], cursor))
            break
        cursor = where + len(key)
    report("3 EPUB：块与块的先后 = spine 给的先后（标题对但正文串到下一章也算错）",
           not out_of_order, "；".join(out_of_order[:2]))
    small = [head for head, _, size in located if size <= body_size]
    report("4 EPUB：章标题字号都大于正文中位字号（%s）" % body_size, bool(located) and not small,
           "不大于正文的：%r" % (small[:3],))

    appendix = "附录不该出现在正文里"
    # 这份夹具的 spine 把第二章引用了两次，第二次写着 linear="no"（EPUB 明说那不在主要阅读顺序里）
    twice = minus(heads[0], absent)
    times = paper.count(twice) if twice else 0
    report("5a EPUB：spine 里 linear=no 的那次引用不重复排（%r 在纸上 %d 次）" % (heads[0][:12], times),
           bool(twice) and times == 1, "出现 %d 次，应当只有正读的那一遍" % times)
    report("5 EPUB：不在 spine 里的那一章没被搬进正文", appendix not in paper,
           "纸上找到了 %r" % appendix if appendix in paper else "")
    report("6 EPUB：书里的表格在纸上有横平竖直的线", heavy_lines(out) > 0,
           "加重线 %d 条" % heavy_lines(out))

    u16 = os.path.join(BUILD, "utf16.pdf")
    probe(os.path.join(EPUB_DIR, "utf16.epub"), u16)
    blocks16 = epub_truth(os.path.join(EPUB_DIR, "utf16.epub"))
    paper16 = strip_all(pdf_text(u16))
    lost16 = [text for kind, text in blocks16 if kind != "row" and strip_all(text) and strip_all(text) not in paper16]
    report("7 EPUB：XHTML 是 UTF-16 也印得出中文（%d 块，乱码或缺块都算没过）" % len(blocks16),
           bool(blocks16) and not lost16, "缺：%r" % (lost16[:2],))

    notes = open(out.replace(".pdf", ".notes.txt"), encoding="utf-8").read()
    report("8 EPUB：说明里写明按电子书自己的阅读顺序搬", "按电子书自己排的阅读顺序" in notes,
           notes.strip().replace("\n", " · ")[:200])
    # 那章附录没进正文，但要说出来是哪一份 —— 不说，用户分不清文件里本来没引用还是我们漏了
    report("8b EPUB：说明里点出那份没被 spine 用到的部件", "没被 spine 用到" in notes,
           notes.strip().replace("\n", " · ")[:200])

    # --- ODS：仓库里那四份 + 自造的一份（含一张真空表）
    synthetic = os.path.join(BUILD, "one-empty.ods")
    write_empty_sheet_ods(synthetic)
    cases = [(name, os.path.join(ODS_DIR, name + ".ods")) for name in ("ledger", "merged", "padding", "bare")]
    cases.append(("one-empty", synthetic))
    for name, source in cases:
        target = os.path.join(BUILD, name + ".pdf")
        probe(source, target)
        sheets = ods_truth(source)
        if not sheets:
            raise RuntimeError("%s 里标准库没抽到任何一张表，判据会被架空" % source)
        paper = strip_all(pdf_text(target))
        note_file = open(target.replace(".pdf", ".notes.txt"), encoding="utf-8").read()
        lost = [cell for sheet in sheets for cell in sheet["cells"] if strip_all(cell) and strip_all(cell) not in paper]
        report("9(%s) ODS：%d 张表的每个格子都印到纸上（缺 %d 格）" % (name, len(sheets), len(lost)),
               not lost, "缺：%r" % (lost[:3],))
        titled = [sheet["name"] for sheet in sheets if sheet["cells"]]
        absent_title = [title for title in titled if strip_all(title) not in paper]
        report("10(%s) ODS：非空表的名子当小标题在纸上（%d 个）" % (name, len(titled)),
               bool(titled) and not absent_title, "缺标题：%r" % absent_title)
        empty = [sheet["name"] for sheet in sheets if not sheet["cells"]]
        leaked = [title for title in empty if strip_all(title) in paper]
        report("11(%s) ODS：真空表不占一节（空的 %d 张：%r）" % (name, len(empty), empty),
               not leaked, "空表的名子却出现在纸上：%r" % leaked)
        report("12(%s) ODS：说明里写明按表格的格子排" % name, "按表格里写的格子排" in note_file,
               note_file.strip().replace("\n", " · ")[:180])
        if name == "one-empty":
            # 少一张表而不说，用户分不清是文件本来就空还是我们把表弄丢了
            report("12b ODS：那张真空表在说明里点出来（没占一节）", "没占一节" in note_file,
                   note_file.strip().replace("\n", " · ")[:180])

    # 13 表头那一条线：文件圈了表头的该有加重线，没圈的不该有（逐份判，不挑对自己有利的那份）
    wrong = []
    for name, source in cases:
        sheets = ods_truth(source)
        declares = any(sheet["header"] and len(sheet["rows"]) > 1 for sheet in sheets)
        heavy = heavy_lines(os.path.join(BUILD, name + ".pdf"))
        if (heavy > 0) != declares:
            wrong.append("%s：文件圈表头=%s，纸上有加重线 %d 条" % (name, declares, heavy))
    report("13 ODS：表头下面那条线只在文件圈了表头的那份上加重", not wrong, "；".join(wrong[:4]))

    # 14 双引擎：poppler 与 PyMuPDF 数的非空白字符要大致一致
    mismatch = []
    checked = 0
    for pdf in sorted(item for item in os.listdir(BUILD) if item.endswith(".pdf")):
        full = os.path.join(BUILD, pdf)
        ours = len(strip_all(pdf_text(full)))
        other = poppler_stripped(full)
        if other is None:
            continue
        checked += 1
        if abs(ours - other) > max(2, ours * 0.02):
            mismatch.append("%s：MuPDF %d 字，poppler %d 字" % (pdf, ours, other))
    report("14 双引擎数的字数一致（%d 份 PDF 比过；少一个引擎就不算数）" % checked,
           checked >= 6 and not mismatch,
           "；".join(mismatch[:3]) if mismatch else ("没找到 poppler：%s" % POPPLER if checked == 0 else ""))

    red = [name for name, ok in RESULTS if not ok]
    print("\n%d 条判据，%d 条红" % (len(RESULTS), len(red)))
    for name in red:
        print("  RED:", name)
    sys.exit(1 if red else 0)


if __name__ == "__main__":
    main()
