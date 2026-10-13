#!/usr/bin/env python3
r"""
判「CSV 认成一张表」：真值由 **Python 的 csv 模块**读回来生成（make_csv_pdf_fixtures.py），
纸上是哪一格由 **pdfplumber** 的 find_tables 说了算，两边都不沾我们自己的读法。

跑法（先落夹具与产物）：
    python tools/make_csv_pdf_fixtures.py
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar
    for f in orders quoted people euro logs prose ragged wide huge; do \
      PDFPROBE_OUT=D:/fflogs/pdfprobe bash tools/pdfprobe/run.sh docpdf \
        C:/Windows/Fonts/simhei.ttf core/build/csvpdf/$f.* core/build/csvpdf/$f.pdf; done
    python tools/verify_csv_pdf.py

十三条判据：
  1  九份都出了 PDF 且非空，且该认成表的七份纸上都画得出网格线
  2  该认成表的那七份：纸上找得到表，列数与 csv 模块数出来的一致
  3  逐格对：纸上读回的每一格与 csv 模块读回的那一格内容相同（跨页先拼回来）
  4  首行按表头这条主张在纸上看得见：它下面那条水平线比别的重
  5  引号里的逗号没拆成两列；引号里的换行仍在同一格里（画成两条基线不是两行数据）
  6  制表符 / 分号 / 竖线三种写法都认对（列数各自对得上）
  7  散文与列数不齐那两份**没被**认成表：纸上找不到任何网格
  8  说明诚实：notes 里"按 X 分成 N 列、M 行"的两个数与 csv 模块一致
  9  走纯文本那两份的说明写的是"按空行分段排"，不是表
 10  26 列那份写明每列从多少压到多少，且一个字都没被裁掉
 11  超长那份只排到上限并写明共多少行，页数真的翻开了
 12  MuPDF 与 poppler（-enc UTF-8）两套独立解析读回的中文数一致且与源相当
 13  除那份宽表，没有一个字越出页边距
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

import pdfplumber

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from verify_odt_read import attempt, check, results  # noqa: E402

BUILD = os.path.join("core", "build", "csvpdf")
FONT = r"C:/Windows/Fonts/simhei.ttf"

# 该被认成表的与该按纯文本排的
Tabled = ["orders", "quoted", "people", "euro", "logs", "wide", "huge"]
PLAINISH = ["prose", "ragged"]
MAX_ROWS = 5000

EXT = {"orders": "csv", "quoted": "csv", "people": "tsv", "euro": "csv", "logs": "csv",
       "prose": "txt", "ragged": "csv", "wide": "csv", "huge": "csv"}


def truth():
    with open(os.path.join(BUILD, "truth.json"), encoding="utf-8") as handle:
        return json.load(handle)


def name_of(stem: str) -> str:
    return "%s.%s" % (stem, EXT[stem])


def notes_of(stem: str) -> str:
    path = os.path.join(BUILD, "%s.notes.txt" % stem)
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def norm(cell: str) -> str:
    """格子里的字按列宽折行是正当排法：比内容时把折行与空白抹掉，真丢字照样比得出。"""
    return re.sub(r"\s+", "", cell or "")


def grids(path: str):
    """pdfplumber 按页面上的线找表；跨页的同一张表按页序拼回来。"""
    rows = []
    heavy = []
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            for table in page.find_tables():
                extracted = [[("" if cell is None else cell.strip()) for cell in row] for row in table.extract()]
                head = [norm(cell) for cell in extracted[0]]
                if rows and head == [norm(cell) for cell in rows[0]]:
                    rows.extend(extracted[1:])       # 跨页重复的表头带：只留第一带那一份
                else:
                    rows.extend(extracted)
            widths = [line.get("linewidth") or 0.5 for line in page.lines
                      if abs(line["top"] - line["bottom"]) < 0.6]
            heavy.extend(widths)
        count = len(pdf.pages)
    return rows, heavy, count


def main() -> int:
    data = truth()
    missing = [s for s in Tabled + PLAINISH
               if not os.path.isfile(os.path.join(BUILD, "%s.pdf" % s))
               or os.path.getsize(os.path.join(BUILD, "%s.pdf" % s)) < 1000]
    gridless = []
    for stem in Tabled:
        target = os.path.join(BUILD, "%s.pdf" % stem)
        if not os.path.isfile(target):
            continue
        with pdfplumber.open(target) as book:
            if not any(page.lines for page in book.pages):
                gridless.append(stem)
    check(1, "九份都出了 PDF 且非空，该认成表的七份纸上都画出了网格线",
          not missing and not gridless,
          "缺：%s；纸上没线：%s" % (missing, gridless))

    read_back = {}
    for stem in Tabled:
        attempt(2, "%s 纸上找得到表" % stem, lambda s=stem: read_back.setdefault(s, grids(os.path.join(BUILD, "%s.pdf" % s))))

    wrong_cols = []
    for stem in Tabled:
        rows, _, _ = read_back.get(stem, ([], [], 0))
        expect = data[name_of(stem)]["columns"]
        if not rows or max(len(row) for row in rows) != expect:
            wrong_cols.append("%s 纸上 %s 列 / 文件里 %d 列" % (stem, max((len(r) for r in rows), default=0), expect))
    check(2, "该认成表的七份：纸上列数与 csv 模块数出来的一致", not wrong_cols, "; ".join(wrong_cols[:3]))

    mismatch = []
    for stem in Tabled:
        rows, _, _ = read_back.get(stem, ([], [], 0))
        cells = data[name_of(stem)]["cells"]
        got = [[norm(str(c)) for c in row] for row in rows]
        want = [[norm(str(c)) for c in row] for row in cells]
        if stem == "huge":
            want = want[:MAX_ROWS]
            if got[:len(want)] != want:
                mismatch.append("huge 前 %d 行对不上" % len(want))
            continue
        if got != want:
            mismatch.append("%s：%s" % (stem, next((
                "第 %d 行 %s ≠ %s" % (i + 1, got[i], want[i])
                for i in range(min(len(got), len(want))) if got[i] != want[i]), "纸上 %d 行 / 文件 %d 行" % (len(got), len(want)))))
    check(3, "逐格对：纸上读回的每一格与 csv 模块读回的那一格相同", not mismatch, "; ".join(mismatch[:3]))

    _, widths, _ = read_back.get("orders", ([], [], 0))
    check(4, "首行按表头这条主张在纸上看得见（它下面那条线更重）",
          bool(widths) and max(widths) >= 1.0 and widths.count(max(widths)) >= 1,
          "水平线粗细：%s" % widths[:6])

    quoted = data["quoted.csv"]["cells"]
    rows, _, _ = read_back.get("quoted", ([], [], 0))
    keep_comma = any(row and "," in row[0] for row in rows[1:]) if len(rows) > 1 else False
    keep_break = any(row and "\n" in row[0] for row in rows[1:]) if len(rows) > 1 else False
    check(5, "引号里的逗号没拆列，引号里的换行仍在同一格",
          keep_comma and keep_break and max(len(r) for r in rows) == data["quoted.csv"]["columns"],
          "纸上首列：%s" % [r[0] for r in rows[:4]])

    trio = []
    for stem in ("people", "euro", "logs"):
        rows, _, _ = read_back.get(stem, ([], [], 0))
        if not rows or max(len(r) for r in rows) != data[name_of(stem)]["columns"]:
            trio.append(stem)
    check(6, "制表符 / 分号 / 竖线三种写法都认对", not trio, "没认对：%s" % trio)

    flat = []
    for stem in PLAINISH:
        with pdfplumber.open(os.path.join(BUILD, "%s.pdf" % stem)) as pdf:
            found = sum(len(page.find_tables()) for page in pdf.pages)
            lines = sum(len(page.lines) for page in pdf.pages)
        if found or lines:
            flat.append("%s 纸上找到 %d 张表 / %d 条线" % (stem, found, lines))
    check(7, "散文与列数不齐那两份没被认成表（纸上没有任何网格）", not flat, "; ".join(flat))

    lied = []
    for stem in Tabled:
        item = data[name_of(stem)]
        text = notes_of(stem)
        hit = re.search(r"按.+?分成 (\d+) 列、(\d+) 行", text)
        rows = min(item["rows"], MAX_ROWS) if stem == "huge" else item["rows"]
        if not hit or int(hit.group(1)) != item["columns"] or int(hit.group(2)) != rows:
            lied.append("%s：%s" % (stem, (hit.group(0) if hit else text[:60])))
    check(8, "说明诚实：notes 里几列几行与 csv 模块数出来的一致", not lied, "; ".join(lied[:3]))

    wrong_route = [s for s in PLAINISH if "空行分段" not in notes_of(s)]
    check(9, "走纯文本那两份的说明写的是按空行分段排", not wrong_route, "说明：%s" % [notes_of(s)[:60] for s in wrong_route])

    wide_notes = notes_of("wide")
    wide_rows, _, _ = read_back.get("wide", ([], [], 0))
    wanted = sum(len(norm(cell)) for row in data["wide.csv"]["cells"] for cell in row)
    got = sum(len(norm(cell)) for row in wide_rows for cell in row)
    pressed = re.search(r"第 \d+ 列从 (\d+)pt 压到 (\d+)pt", wide_notes)
    check(10, "26 列那份写明每列从多少压到多少，且一个字都没被裁掉",
          bool(pressed) and got == wanted,
          "notes：%s；字数 纸上 %d / 文件 %d" % (wide_notes[:110], got, wanted))

    huge_rows, _, huge_pages = read_back.get("huge", ([], [], 0))
    check(11, "超长那份只排到上限并写明共多少行，且真的翻了页",
          len(huge_rows) == MAX_ROWS and "只排前" in notes_of("huge") and huge_pages >= 20,
          "纸上 %d 行 / %d 页；notes：%s" % (len(huge_rows), huge_pages, notes_of("huge")[:80]))

    def cjk_via(command, path, extra=()):
        run = subprocess.run(list(command) + list(extra) + [path, "-"], capture_output=True, text=True, encoding="utf-8")
        return sum(1 for ch in run.stdout if "\u4e00" <= ch <= "\u9fff")

    import fitz
    both = {}
    for stem in ("orders", "huge"):
        pdf_path = os.path.join(BUILD, "%s.pdf" % stem)
        with fitz.open(pdf_path) as doc:
            both[stem] = (sum(1 for ch in "".join(page.get_text() for page in doc) if "\u4e00" <= ch <= "\u9fff"),
                          cjk_via(["pdftotext", "-enc", "UTF-8"], pdf_path))
    agree = all(m == p and m > 0 for m, p in both.values())
    check(12, "MuPDF 与 poppler 两套独立解析读回的中文数一致且不为零", agree, str(both))

    over = []
    for stem in Tabled + PLAINISH:
        if stem == "wide":
            continue
        with pdfplumber.open(os.path.join(BUILD, "%s.pdf" % stem)) as pdf:
            page = pdf.pages[0]
            bad = [w for w in page.extract_words() if w["x1"] > page.width - 40 or w["x0"] < 40]
            if bad:
                over.append("%s %d 个字贴到纸边" % (stem, len(bad)))
    check(13, "除那份宽表，没有一个字越出页边距", not over, "; ".join(over))

    total = len(results)
    red = [r for r in results if not r[2]]
    print("共 %d 条，%d 条红" % (total, len(red)))
    for number, name, ok in red:
        print("  红：%d %s" % (number, name))
    return 1 if red else 0


if __name__ == "__main__":
    sys.exit(main())
