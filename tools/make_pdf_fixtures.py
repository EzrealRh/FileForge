#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""造「PDF 转 Word」的夹具：用 PyMuPDF 把一份**结构已知**的文件排到纸上。

为什么用 PyMuPDF 而不是 PDFBox 造：安卓侧读 PDF 走 pdfbox-android，夹具要是同一家写的，
"读出来跟声明一样"只能证明我们自洽。这里造文件与判答案都不碰 PDFBox
（fitz 排版 → 探针与 pdfminer / pdftotext 各读一遍 → 与这份 manifest 对）。

下面 SOURCE 就是那份"作者心里的稿子"：块类型 + 每块几行 + 每行几个片段（片段决定字体与字号）。
排版只按它摆，所以 manifest 不会和画面各说一套。
每块之间的空档比每行长（30pt 对 14pt）—— 这正是判断层要认的"段落空档"信号。

产出（默认 core/build/pdfdoc/）：
  structure.pdf   夹具
  manifest.json   声明：该认出哪些块（pandoc 的块名）、每页每行的字号、哪些行是页眉（该被删）
并自检：拿 fitz 把造好的文件再读一遍，声明的每行必须真在纸上（字体缺字就会读不出那个字）。

跑法：python tools/make_pdf_fixtures.py [输出目录]
"""
import io
import json
import os
import re
import sys

import fitz

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
FONTS = {
    "song": "C:/Windows/Fonts/simsun.ttc",       # 正文：宋体，名字里不带粗（真文件里它常被 /Flags 标成粗）
    "hei": "C:/Windows/Fonts/simhei.ttf",        # 标题：黑体，靠名字认重面
    "bold": "C:/Windows/Fonts/arialbd.ttf",      # 拉丁文的粗体（中文用它会被画成缺字）
    "times": "C:/Windows/Fonts/times.ttf",       # 正文里夹的拉丁文，比中文大 1pt
}
PAGE = (595, 842)
LEFT = 56.0
LINE_GAP = 14.0
BLOCK_GAP = 30.0

BODY = "本次验收覆盖三个模块的全部交付项，测试用例通过率与上一版持平，遗留问题均已登记在附表里。"

# (页, 块类型, [行])；行 = [(文字, 字体, 字号)]。块类型用的是 pandoc 的块名，"items" 表示列表里有几项
SOURCE = [
    (1, "Header 1", [[("项目验收报告", "hei", 20.0)]]),
    (1, "Header 2", [[("总体情况说明", "hei", 14.0)]]),
    (1, "Para", [[(BODY[:28], "song", 11.0)], [("后半句接着上一行的意思写下来，这两行在纸上是同一段。", "song", 11.0)]]),
    (1, "Header 3", [[("注意事项", "hei", 11.0)]]),                      # 只粗没变大
    # 两条圆点项在 pandoc 里是一张列表的两个项
    (1, "BulletList:2", [[("• 第一要点由 Word 自己画记号", "song", 11.0)],
                         [("• 第二要点也是同样的一行", "song", 11.0)]]),
    (1, "OrderedList:2", [[("1. 交付清单已在系统里勾选", "song", 11.0)],
                          [("2. 遗留问题已指派到人", "song", 11.0)]]),
    (1, "Para", [[("This report describes exam-", "times", 12.0)],                 # 真断词
                 [("ples of hyphenated line breaking inside a PDF file.", "times", 12.0)]]),
    # 中文 11pt 里夹 12pt 的拉丁文：这一档占字不少，不该被判成标题
    (1, "Para", [[("附表登记情况见附件 ", "song", 11.0), ("Appendix B table 3", "times", 12.0),
                  (" 与说明文档两处内容一致。", "song", 11.0)]]),
    (1, "Para", [[("2", "hei", 18.0)]]),                                    # 单个大字符：不是标题
    (1, "Para", [[(BODY, "song", 11.0)], [(BODY, "song", 11.0)], [(BODY, "song", 11.0)]]),
    (2, "Header 2", [[("第二节 Detailed testing", "hei", 14.0)]]),
    (2, "Para", [[(BODY, "song", 11.0)], [("这一段的第二行离上一行很近，是同一段被折开的。", "song", 11.0)]]),
    (2, "Para", [[("空了一档，这里开始是另一段。", "song", 11.0)], [(BODY[:36], "song", 11.0)]]),
    (3, "Para", [[(BODY, "song", 11.0)], [("第二页的第一段有两行。", "song", 11.0)]]),
    (3, "Para", [[("第三段离上一段隔了一档。", "song", 11.0)]]),
    (4, "Para", [[(BODY, "song", 11.0)], [("第四页的第一段有两行。", "song", 11.0)]]),
    (4, "Para", [[("收尾的一段，也是最后一行。", "song", 11.0)]]),
]


def header_line(page_no):
    return [("内部资料 · 请勿外传 第 %d 页" % page_no, "song", 9.0)]


def line_text(runs):
    return "".join(run[0] for run in runs)


def block_text(rows):
    return "".join(line_text(row) for row in rows)


MARKER = re.compile(r"^\s*(?:[•·‣▪]\s*|\d{1,3}[.)、]\s*)")


def expect_text(rows, listy):
    """这一块**该**被认成什么文字：记号被 Word 画掉，行尾的真断词与下一行拼回去。

    与 Kotlin 那份判断层各写一遍，两边都错到一块儿去的概率比抄一份低 —— 抄一份就永远测不出漂移。
    """
    out = ""
    for runs in rows:
        piece = line_text(runs)
        if listy:
            piece = MARKER.sub("", piece)
        if out and out[-1] == "-" and piece[:1].islower():
            out = out[:-1]
        elif out and out[-1].isascii() and out[-1].isalnum() and piece[:1].isascii() and piece[:1].isalnum():
            out += " "
        out += piece
    return out


def build(out_dir):
    doc = fitz.open()
    pages = {}
    lines = []
    blocks = []
    furniture = []
    y = {}
    for page_no, kind, rows in SOURCE:
        listy = not kind.startswith("Header") and kind.split(":")[0].endswith("List")
        head = re.sub(r"\s+", "", expect_text(rows[:1], listy))[:6]
        if kind.startswith("Header"):
            blocks.append({"kind": "Header", "level": int(kind.split()[1]), "items": 1,
                           "head": head, "text": block_text(rows), "expect": expect_text(rows, False)})
        else:
            name, _, items = kind.partition(":")
            blocks.append({"kind": name, "items": int(items) if items else (len(rows) if listy else 1),
                           "head": head, "text": block_text(rows), "expect": expect_text(rows, listy)})
        for index, runs in enumerate(rows):
            page = pages.get(page_no)
            if page is None:
                page = doc.new_page(width=PAGE[0], height=PAGE[1])
                pages[page_no] = page
                y[page_no] = 90.0
                furniture.append("".join(r[0] for r in header_line(page_no)))
                draw(page, header_line(page_no), LEFT, y[page_no], page_no, lines)
            y[page_no] += BLOCK_GAP if index == 0 else LINE_GAP
            draw(page, runs, LEFT, y[page_no], page_no, lines)
            y[page_no] += max(r[2] for r in runs) * 0.2
    doc.save(os.path.join(out_dir, "structure.pdf"))
    doc.close()
    check(out_dir, lines, furniture)
    manifest = {"blocks": blocks, "furniture": furniture, "lines": lines}
    io.open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=1))
    print("夹具 %d 页 · 声明 %d 块 · %d 行（含页眉 %d 行）" % (
        len(pages), len(blocks), len(lines), len(furniture)))


def draw(page, runs, x, baseline, page_no, lines):
    for text, font, size in runs:
        page.insert_text((x, baseline), text, fontname=font, fontfile=FONTS[font],
                         fontsize=size, color=(0, 0, 0))
        x += fitz.Font(fontfile=FONTS[font]).text_length(text, fontsize=size)
    lines.append({"page": page_no - 1, "y": round(baseline, 1),
                  "size": max(r[2] for r in runs), "text": "".join(r[0] for r in runs)})


def check(out_dir, lines, furniture):
    """仪器先自检：声明的每行必须真在造好的文件里（字体缺那个字就读不出来）。"""
    doc = fitz.open(os.path.join(out_dir, "structure.pdf"))
    flat = "".join("".join(p.get_text().replace("­", "-").split()) for p in doc)
    doc.close()
    missing = [l["text"] for l in lines if "".join(l["text"].split()) not in flat]
    if missing:
        raise SystemExit("造出来的 PDF 里读不到这些行（字体缺字？排版没落上？）：%s" % missing[:3])
    gone = [f for f in furniture if "".join(f.split()) not in flat]
    if gone:
        raise SystemExit("页眉没画上去，判据会被架空：%s" % gone[:2])


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "core", "build", "pdfdoc")
    os.makedirs(out, exist_ok=True)
    build(out)
    return 0


if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.exit(main())
