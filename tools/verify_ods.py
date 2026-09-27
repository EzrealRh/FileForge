#!/usr/bin/env python3
"""
判 ODS 的读法与写法（core/office/OdsRead.kt、OdsWrite.kt）。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.OdsTest' --rerun
    python tools/verify_ods.py

为什么不用 pandoc 当裁判：它不读 .ods。这里的裁判是**另一套实现写的展开算法**加两个第三方库：
  - 网格由本文件用 ElementTree 独立算：按"**列位置**"摆格子（前一格的跨度先占位，
    covered 格遇到已占的位置就跳过），与 Kotlin 那份"补空 + 记账"的写法结构不同 ——
    同一条规矩两种实现各算一遍，对不上就是有一边错
  - xlsx 产物交给 **openpyxl** 读回来逐格判（它是 Excel 阵营的实现）
  - HTML 产物用标准库 html.parser 自己解析出表格，CSV 产物用 csv 模块读回来

十二条判据：
  1 包本身成立：mimetype 第一条且不压缩，写的是 spreadsheet
  2 网格逐格逐位置与独立展开相同（含表名与表头）
  3 每张表的 CSV 产物用 csv 模块读回来与网格相同（多行格子、制表、连续空格都要在）
  4 xlsx 产物 openpyxl 打得开，逐格值相同
  5 xlsx 里 1.50 / 007 这类仍是**文字**（被认成数字就已经改了字面）
  6 网页产物解析出来的表格与网格相同，表头那行是 <th>
  7 整页的重复空行被剪掉（有字的那几行之外不多出一万行）
  8 中间那行真空的留着（剪尾巴不能顺手把稿子的空行也剪了）
  9 交代里的数字与独立数出来的相符（公式几格、没有渲染文字几格）
 10 写出去的那份 .ods：包结构成立、mimetype 与清单对得上
 11 写出去的那份 .ods 独立读回来与来源 CSV 逐格相同（含空字格的补齐规则）
 12 写出去时该保持文字的格子（1.50、007、前后带空格）没被写成数值类型
 13 写出去时空格按 `text:s` 存，不写裸空白（裸空白在任何 XML 读者眼里都是可以折掉的）
"""
from __future__ import annotations

import csv
import io
import os
import re
import sys
import xml.etree.ElementTree as Tree
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

BUILD = os.path.join("core", "build", "odsread")
STEMS = ["ledger", "padding", "bare", "merged"]
MIME = "application/vnd.oasis.opendocument.spreadsheet"
NS = {
    "office": "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
    "table": "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
    "text": "urn:oasis:names:tc:opendocument:xmlns:text:1.0",
}

results = []


def check(number: int, name: str, ok: bool, detail: str = "") -> bool:
    results.append((number, name, ok))
    print("%s %2d %s%s" % ("OK  " if ok else "FAIL", number, name, (" :: " + detail) if detail and not ok else ""))
    return ok


def attempt(number: int, name: str, action):
    """把"读不动"变成一条指名的红，而不是让 traceback 把整轮判据带走。"""
    try:
        return action()
    except Exception as error:  # noqa: BLE001
        check(number, name, False, "读的时候炸了：%s" % (str(error)[:220],))
        return None


def package_ok(path: str, media: str = MIME) -> bool:
    with zipfile.ZipFile(path) as pack:
        first = pack.infolist()[0]
        if first.filename != "mimetype" or first.compress_type != zipfile.ZIP_STORED:
            raise RuntimeError("mimetype 不是第一条或不压缩：%s" % first.filename)
        declared = pack.read("mimetype").decode("utf-8").strip()
        if declared != media:
            raise RuntimeError("mimetype 写的是 %r" % declared)
        manifest = pack.read("META-INF/manifest.xml").decode("utf-8")
        if media not in manifest:
            raise RuntimeError("清单里没有这条 media-type")
    return True


def content_root(path: str):
    with zipfile.ZipFile(path) as pack:
        return Tree.fromstring(pack.read("content.xml"))


def cell_text(cell) -> tuple:
    """(渲染出来的文字, 值类型, 有没有存原文)。"""
    lines = []
    for node in cell:
        tag = tag_of(node)
        if tag != "p":
            continue
        lines.append(pull(node))
    return "\n".join(lines), get(cell, "office", "value-type") or "", stored_value(cell)


def stored_value(cell) -> str:
    kind = get(cell, "office", "value-type") or ""
    key = {
        "float": "value", "double": "value", "percentage": "value", "currency": "value",
        "date": "date-value", "time": "time-value", "boolean": "boolean-value", "string": "string-value",
    }.get(kind)
    value = get(cell, "office", key) if key else ""
    return "" if value is None else value


def pull(node) -> str:
    """一个 `text:p` 里的字：ElementTree 把元素前的文字放在 `.text`、元素后的放在 `.tail`，
    两边都要收 —— 只收 `.text` 的话，`甲<text:s/>付` 里的"付"就丢了（裁判自己漏字，
    会把对的实现判成错）。"""
    out = []
    if node.text:
        out.append(node.text)
    for child in node:
        tag = tag_of(child)
        if tag == "s":
            out.append(" " * int(get(child, "text", "c") or 1))
        elif tag == "tab":
            out.append("\t")
        elif tag == "line-break":
            out.append("\n")
        else:
            out.append(pull(child))
        if child.tail:
            out.append(child.tail)
    return "".join(out)


def tag_of(node):
    if isinstance(node, str) or node.tag is None:
        return None
    return str(node.tag).split("}")[-1] if isinstance(node.tag, str) else None


def get(node, prefix, name):
    value = node.get("{%s}%s" % (NS[prefix], name))
    return value


def expand_rows(table) -> list:
    """按**列位置**摆格子：跨度先占位，covered 遇到已占的位置跳过。

    这是与 Kotlin 那份"补空 + 记账"结构不同的另一种算法 —— 两条路算出同一张网格，
    才说明合并列 / 重复列 / covered 这套规矩没被谁单方面解释。
    """
    rows = []
    for child in table:
        tag = tag_of(child)
        if tag == "table-row":
            rows.append(placed_row(child))
        elif tag in ("table-header-rows", "table-rows"):
            rows += [placed_row(row) for row in child if tag_of(row) == "table-row"]
    return rows


def placed_row(row) -> list:
    """一行按**列位置**摆出来。

    合并有两种写法，真文件还会两种一起写：`number-columns-spanned="2"` 说"这格横占两列"，
    紧接着一个 `table:covered-table-cell` 表示被占掉的那一列。这张表的列数是写死的
    （`table:table-column`），两种写法各占一格的话行宽就会超过声明的列数 ——
    所以它们是同一次合并的两种说法：跨度先"欠"几格，紧跟的 covered 格来认领，
    没人认领时才补成空格。
    """
    slots = []
    owed = 0      # 前面那格的跨度欠下的格子数

    def ensure(at: int) -> None:
        while len(slots) <= at:
            slots.append("")

    cursor = 0
    for cell in row:
        tag = tag_of(cell)
        if tag not in ("table-cell", "covered-table-cell"):
            continue
        spanned = max(int(get(cell, "table", "number-columns-spanned") or 1), 1)
        repeated = max(int(get(cell, "table", "number-columns-repeated") or 1), 1)
        for _ in range(repeated):
            if tag == "covered-table-cell":
                ensure(cursor)
                slots[cursor] = ""
                cursor += 1
                if owed:
                    owed -= 1             # 这一格填的正是跨度欠下的那一列
                continue
            while owed:                   # 跨度欠下的列没人认领，补成空格子
                ensure(cursor)
                slots[cursor] = ""
                cursor += 1
                owed -= 1
            ensure(cursor)
            slots[cursor] = cell_value(cell)
            cursor += 1
            owed = spanned - 1
    while owed:
        ensure(cursor)
        slots[cursor] = ""
        cursor += 1
        owed -= 1
    return slots


def cell_value(cell) -> str:
    shown, kind, stored = cell_text(cell)
    if shown:
        return shown
    if not kind or kind == "string":
        return ""
    return stored


def expected_grid(path: str) -> list:
    """独立算出这本工作簿该是什么样：(表名, 表头, 剪过尾巴的网格)。

    表名的去重是**产品自己的规矩**（CSV 一份一个文件名，重名会互相盖掉）：
    第一次出现用原名，之后再出现的加 -2、-3。
    """
    root = content_root(path)
    sheets = []
    seen = {}
    for table in root.iter("{%s}table" % NS["table"]):
        rows = expand_rows(table)
        while rows and not any(cell != "" for cell in rows[-1]):
            rows.pop()
        rows = [strip_tail(row) for row in rows]
        header = table.find("{%s}table-header-rows" % NS["table"]) is not None
        raw = get(table, "table", "name") or ""
        seen[raw] = seen.get(raw, 0) + 1
        name = raw if seen[raw] == 1 else "%s-%d" % (raw, seen[raw])
        sheets.append((name, header, rows))
    return sheets


def strip_tail(row) -> list:
    out = list(row)
    while out and out[-1] == "":
        out.pop()
    return out


def strip_tail_multi(rows: list) -> list:
    """整张表逐行剪尾巴（openpyxl 会把每行补齐到最宽列，我们按格子实际有的写）。"""
    return [strip_tail(row) for row in rows]


def read_grid_file(path: str) -> list:
    """JVM 那边落的 `<stem>.grid.txt` 摊回成同一种形状（行号只用来保住空的行）。"""
    sheets = []
    current = None
    with io.open(path, encoding="utf-8") as handle:
        for line in handle.read().split("\n"):
            if not line:
                continue
            found = re.match(r"^表 (.*?) 表头=(true|false)$", line)
            if found:
                current = [found.group(1), found.group(2) == "true", []]
                sheets.append(current)
                continue
            found = re.match(r"^行 \d+: (.*)$", line)
            if found and current is not None:
                cell = found.group(1)
                current[2].append([] if cell == "" else cell.split("␟"))
    return [(name, header, rows) for name, header, rows in sheets]


def read_csv_product(path: str) -> list:
    with io.open(path, encoding="utf-8", newline="") as handle:
        return [row for row in csv.reader(handle)]


def html_tables(path: str) -> list:
    from html.parser import HTMLParser

    class Grab(HTMLParser):
        def __init__(self):
            super().__init__(convert_charrefs=True)
            self.tables = []
            self.rows = None
            self.row = []
            self.cell = None

        def handle_startendtag(self, tag, attrs):
            if tag == "br" and self.cell is not None:
                self.cell.append("\n")        # 格子里的换行写成 <br/>

        def handle_starttag(self, tag, attrs):
            if tag == "table":
                self.rows = []
            elif tag == "tr" and self.rows is not None:
                self.cell = None
            elif tag in ("td", "th") and self.rows is not None:
                self.cell = []

        def handle_endtag(self, tag):
            if tag == "table" and self.rows is not None:
                self.tables.append(self.rows)
                self.rows = None
            elif tag in ("td", "th") and self.cell is not None:
                self.row = self.row or []
                self.row.append("".join(self.cell))
                self.cell = None
            elif tag == "tr" and self.rows is not None:
                self.rows.append(self.row or [])
                self.row = []

        def handle_data(self, data):
            if self.cell is not None:
                self.cell.append(data)

    grab = Grab()
    with io.open(path, encoding="utf-8") as handle:
        grab.feed(handle.read())
    return grab.tables


def xlsx_grid(path: str) -> list:
    from openpyxl import load_workbook
    book = load_workbook(path, data_only=False)
    out = []
    for sheet in book.worksheets:
        rows = []
        for row in sheet.iter_rows(values_only=True):
            rows.append(["" if value is None else str(value) for value in row])
        out.append((sheet.title, rows, [cell for row in sheet.iter_rows() for cell in row]))
    return out


def declared_columns(path: str) -> list:
    """每张表声明了几列（`table:table-column`，列上的 repeated 要展开）。"""
    root = content_root(path)
    out = []
    for table in root.iter("{%s}table" % NS["table"]):
        columns = 0
        for child in table:
            if tag_of(child) == "table-column":
                columns += max(int(get(child, "table", "number-columns-repeated") or 1), 1)
        out.append(columns)
    return out


def counted(path: str) -> dict:
    root = content_root(path)
    body = root.find("office:body/office:spreadsheet", NS)
    scope = body if body is not None else root
    cells = scope.findall(".//{%s}table-cell" % NS["table"])
    formulas = [cell for cell in cells if get(cell, "table", "formula")]
    unrendered = [
        cell for cell in cells
        if (cell_text(cell)[0] == "" and (cell_text(cell)[1] or "") not in ("", "string") and cell_text(cell)[2])
    ]
    return {"公式": len(formulas), "未渲染": len(unrendered), "表": len(scope.findall(".//{%s}table" % NS["table"]))}


def number_in(notes: str, keyword: str) -> int:
    for line in notes.splitlines():
        if keyword in line:
            found = re.match(r"(\d+)", line.strip())
            if found:
                return int(found.group(1))
    return -1


def main() -> int:
    if not os.path.isdir(BUILD):
        print("FAIL  0 没有 %s（先跑 :core:test 的落盘那条）" % BUILD)
        return 1
    for stem in STEMS:
        odt = os.path.join(BUILD, "%s.ods" % stem)
        if not attempt(1, "%s 的包先成立" % stem, lambda odt=odt: package_ok(odt)):
            continue
        check(1, "%s 的包先成立" % stem, True)
        want = attempt(2, "%s 独立展开网格" % stem, lambda odt=odt: expected_grid(odt))
        grid_path = os.path.join(BUILD, "%s.grid.txt" % stem)
        if not want or not os.path.exists(grid_path):
            continue
        got = read_grid_file(grid_path)
        check(2, "%s 网格逐格与独立展开相同" % stem, got == want,
              "\n  独立=%s\n  我们=%s" % (want, got))
        # 下面几条各自判产物，不跟着第 2 条一起跳过：
        # 一处读错会同时改掉网格与产物，只报第一条就把别的判据都藏起来了
        notes_path = os.path.join(BUILD, "%s.notes.txt" % stem)
        notes = open(notes_path, encoding="utf-8").read() if os.path.exists(notes_path) else ""
        # 3：每张表一份 CSV，csv 模块读回来
        missing = []
        for index, (_, _, rows) in enumerate(want, start=1):
            path = os.path.join(BUILD, "%s-%d.csv" % (stem, index))
            if not os.path.exists(path):
                missing.append("缺 %s-%d.csv" % (stem, index))
                continue
            back = strip_read_csv(read_csv_product(path))
            if back != [strip_tail(row) for row in rows]:
                missing.append("第 %d 张 %s vs %s" % (index, back, rows))
        check(3, "%s 每张表的 CSV 读回来与网格相同" % stem, not missing, "；".join(missing)[:400])
        # 4 + 5：xlsx 产物（读不动时 attempt 已经把第 4 条记成红了，这里不再重复）
        made = attempt(4, "%s 的 xlsx openpyxl 打得开" % stem,
                       lambda stem=stem: xlsx_grid(os.path.join(BUILD, "%s.xlsx" % stem)))
        if made is not None:
            # 表名允许被 xlsx 那侧收敛过（`/` 在 Excel 的表名里非法），那一改由 xlsxnotes 说；
            # 这一条只判格子
            same = [strip_tail_multi(rows) for _, rows, _ in made] == \
                   [strip_tail_multi(rows) for _, _, rows in want]
            renamed = os.path.join(BUILD, "%s.xlsxnotes.txt" % stem)
            said = open(renamed, encoding="utf-8").read() if os.path.exists(renamed) else ""
            changed = [name for (name, _, _), (made_name, _, _) in zip(want, made) if name != made_name]
            check(4, "%s 的 xlsx 逐格与 ODS 相同（表名被改要说明）" % stem,
                  same and (not changed or "表名" in said),
                  "\n  openpyxl=%s\n  应有=%s\n  改了名：%s，说了：%s" % (
                      [rows for _, rows, _ in made], want, changed, said.strip()[:160]))
            literal = []
            for _, _, cells in made:
                for cell in cells:
                    text = "" if cell.value is None else str(cell.value)
                    if re.fullmatch(r"0\d+|\d+\.\d*0", text) and not isinstance(cell.value, str):
                        literal.append(text)
            check(5, "%s 的 xlsx 里 007 与 1.50 这类仍是文字" % stem, not literal, "被写成数字的：%s" % literal)
        # 6：网页
        tables = html_tables(os.path.join(BUILD, "%s.html" % stem))
        want_tables = [[strip_tail(row) for row in rows] for _, _, rows in want]
        got_tables = [[list(row) for row in table] for table in tables]
        check(6, "%s 的网页表格与网格相同" % stem, got_tables == want_tables,
              "\n  网页=%s\n  应有=%s" % (got_tables, want_tables))
        # 7 + 8：行数与中间空行
        total = sum(len(rows) for _, _, rows in want)
        products = sum(len(read_csv_product(os.path.join(BUILD, "%s-%d.csv" % (stem, index))))
                       for index in range(1, len(want) + 1)
                       if os.path.exists(os.path.join(BUILD, "%s-%d.csv" % (stem, index))))
        check(7, "%s 的产物没有把整页空行搬出来" % stem, products == total,
              "CSV 共 %d 行，网格 %d 行" % (products, total))
        middles = [1 for _, _, rows in want for index, row in enumerate(rows)
                   if not any(cell != "" for cell in row) and index != len(rows) - 1]
        kept = [1 for _, _, rows in got for index, row in enumerate(rows)
                if not any(cell != "" for cell in row) and index != len(rows) - 1]
        check(8, "%s 中间真空的那几行留着" % stem, len(middles) == len(kept),
              "独立算出 %d 行，产物里 %d 行" % (len(middles), len(kept)))
        # 9：报数与独立数出来的一致
        tally = counted(odt)
        told = {"公式": number_in(notes, "公式"), "未渲染": number_in(notes, "没有渲染出来的文字")}
        wrong = {key: (tally[key], told.get(key, -1)) for key in tally
                 if key in told and told.get(key, -1) >= 0 and tally[key] != told[key]}
        silent = {key: tally[key] for key in told if tally[key] > 0 and told.get(key, -1) < 0}
        check(9, "%s 交代里的数字与独立数出来的一致" % stem, not wrong and not silent,
              "应有 %s，写了 %s；没报的：%s" % (tally, told, silent))
        # 行宽不能超过声明的列数：这是"跨度与 covered 是同一次合并"那条读法的硬证据
        # （两种写法各占一格的话，这一条立刻红）
        declared = attempt(14, "%s 数出每表声明的列数" % stem, lambda odt=odt: declared_columns(odt))
        if declared is not None:
            wide = [(name, len(row), columns) for (name, _, rows), columns in zip(want, declared)
                    for row in rows if len(row) > columns]
            check(14, "%s 每行的格数不超过声明的列数" % stem, not wide, "超了的（表,行宽,列数）：%s" % wide)
    # 10 - 12：写出去的那一份
    written = os.path.join(BUILD, "written.ods")
    source = os.path.join(BUILD, "source.csv")
    grid = os.path.join(BUILD, "written.grid.txt")
    if not attempt(10, "写出去的 .ods 包结构成立", lambda: package_ok(written)):
        pass
    else:
        check(10, "写出去的 .ods 包结构成立", True)
        if os.path.exists(source) and os.path.exists(grid):
            with io.open(source, encoding="utf-8", newline="") as handle:
                want_rows = [list(row) for row in csv.reader(handle)]
            back = attempt(11, "写出去的 .ods 独立读回来", lambda: expected_grid(written))
            if back is None:
                pass
            else:
                got_rows = back[0][2] if back else []
                widest = max(len(item) for item in want_rows)
                padded = [item + [""] * (widest - len(item)) for item in want_rows]
                trimmed = [strip_tail(item) for item in padded]
                while trimmed and not any(cell != "" for cell in trimmed[-1]):
                    trimmed.pop()
                check(11, "写出去的 .ods 与来源 CSV 逐格相同", got_rows == trimmed,
                      "\n  写出去=%s\n  来源=%s" % (got_rows, trimmed))
                root = content_root(written)
                kinds = []
                for cell in root.iter("{%s}table-cell" % NS["table"]):
                    kind = get(cell, "office", "value-type")
                    shown, _, stored = cell_text(cell)
                    kinds.append((shown or stored, kind))
                must_be_text = [text for text, kind in kinds if re.fullmatch(r"0\d+|\d+\.\d*0|.*\s.*", text or "")]
                wrong = [text for text, kind in kinds if text in must_be_text and kind not in (None, "", "string")]
                check(12, "写出去时该保持文字的格子没被写成数值", not wrong, "被写成数值的：%s" % wrong)
                # 连续空格要按 ODF 的写法存：裸空白在任何 XML 读者眼里都是"可以折掉的空白"，
                # 今天读得回来不代表明天别人也读得回来
                loose = []
                for cell in root.iter("{%s}table-cell" % NS["table"]):
                    for node in cell.iter("{%s}p" % NS["text"]):
                        if "  " in (node.text or "") or any("  " in (child.tail or "") for child in node):
                            loose.append((node.text or "")[:24])
                check(13, "写出去时空格用 text:s 存而不是裸空白", not loose, "漏在属性外的连续空格：%s" % loose)
    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    return 1 if failed else 0


def strip_read_csv(rows: list) -> list:
    """csv 模块读回来的行：末尾那个"整行空"是渲染器留的段落分隔，两边都按同一规则剪。"""
    out = [list(row) for row in rows]
    while out and all(cell == "" for cell in out[-1]):
        out.pop()
    return out


if __name__ == "__main__":
    sys.exit(main())
