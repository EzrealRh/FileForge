#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 openpyxl 复核 Kotlin 写的 xlsx —— 判"别人眼里的这张表"。

自家读路（Xlsx 那一路）已经钉过"写进去读回来一格不差"，但自己当自己的裁判没有意义：
一格是数字还是文字，只在**别人怎么读**这件事上有区别。这里管四件事：

1. **包打得开**：openpyxl 严格读 OOXML，包结构或部件内容不对它直接抛错。
2. **每格的值与源 CSV 相同**：源数据用 Python 标准库 `csv` 独立解析（不借 Kotlin 那只 parser），
   所以这条同时过了三道手：Python 的 csv → 我们的写入 → openpyxl 的读取。
3. **每格的类型与我们声明的相同**：`.cells` 是 Kotlin 那边自己写的账（"这格按数字存"），
   openpyxl 说不是，就是判据写错了 —— `007` 变成 7 这种事只有这条能逮住。
4. **包自己说的与包里有的对得上**：`[Content_Types].xml` 里每个 Override、每个 rels Target
   都要真有一份部件（openpyxl 对这些宽松，Excel 不宽松）。

外加一条参照：同一批数据让 openpyxl 自己写一份，两边都用 openpyxl 读回来比对。

跑法：先 `./gradlew :core:test`（产物落到 core/build/xlsx-write/），再 python tools/verify_xlsx_write.py
"""
import csv
import io
import os
import sys
import zipfile
import xml.etree.ElementTree as ET

from openpyxl import Workbook, load_workbook

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
FIXTURE = os.path.join(ROOT, "core", "src", "test", "resources", "xlsxwrite", "table.csv")
PRODUCTS = os.path.join(ROOT, "core", "build", "xlsx-write")
CT_NS = "{http://schemas.openxmlformats.org/package/2006/content-types}"
REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"

PASSED = []
FAILED = []


def check(what, ok, detail=""):
    print(("  通过：" if ok else "  失败：") + what)
    if not ok and detail:
        for line in detail.split("\n")[:8]:
            print("      " + line)
    (PASSED if ok else FAILED).append(what)
    return ok


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def source_rows():
    """Python 的标准库解析同一份夹具 —— 源数据的真值不经过 Kotlin 的手。"""
    with io.open(FIXTURE, encoding="utf-8", newline="") as handle:
        rows = [row for row in csv.reader(handle) if row]
    width = max(len(row) for row in rows)
    return [row + [""] * (width - len(row)) for row in rows]


def claims():
    """Kotlin 自己声明的账：{第几张表: {格子引用: (类型, 原文)}}。"""
    out = {}
    for line in read(os.path.join(PRODUCTS, "table.cells")).split("\n"):
        if not line.strip():
            continue
        where, kind, text = line.split("\t", 2)
        sheet, ref = where.split("!", 1)
        out.setdefault(int(sheet), {})[ref] = (kind, text.replace("\u2424", "\n"))
    return out


def number_text(value):
    """openpyxl 读回来的数字，写成文字该是什么样（我们那把判据的镜像）。"""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return str(value)
    return repr(value)


def main():
    xlsx = os.path.join(PRODUCTS, "table.xlsx")
    if not os.path.isfile(xlsx):
        raise SystemExit("没有 %s，先跑 ./gradlew :core:test" % xlsx)
    rows = source_rows()
    by_sheet = claims()
    claim1 = by_sheet[1]

    # ---- 1. 打得开 ------------------------------------------------------------------
    try:
        book = load_workbook(xlsx)
        opened = True
        problem = ""
    except Exception as bad:                                   # noqa: BLE001 —— 报错本身就是判据
        opened, problem = False, "%s: %s" % (type(bad).__name__, bad)
    check("openpyxl 打得开我们写的包", opened, problem)
    if not opened:
        return finish()

    # ---- 2. 表名收敛成 Excel 认的样子 -------------------------------------------------
    names = book.sheetnames
    long = "这张表的名字长得超过了 Excel 允许的三十一个字限制所以必须被掐断"
    expect = ["table", "第一季 度", "第一季 度(2)", long[:31]]
    check("四张表都在，表名按 Excel 的规矩收住了（非法字符、重名加序号、超长掐断）", names == expect,
          "实际：%s" % names)

    # ---- 3. 每格的值与源 CSV 相同 ------------------------------------------------------
    sheet = book[expect[0]]
    bad_value = []
    for y, row in enumerate(rows, start=1):
        for x, want in enumerate(row, start=1):
            got = sheet.cell(row=y, column=x).value
            if want == "":
                if got not in (None, ""):
                    bad_value.append("%s%d 该是空格子，读回 %r" % (col(x), y, got))
                continue
            if got is None:
                bad_value.append("%s%d 丢了：该是 %r" % (col(x), y, want))
            elif isinstance(got, str):
                if got != want:
                    bad_value.append("%s%d 文字变了：%r ≠ %r" % (col(x), y, got, want))
            else:
                if number_text(got) != want:
                    bad_value.append("%s%d 数字读不回来：%r ≠ %r" % (col(x), y, got, want))
    check("每格的值与源 CSV 一字不差（含前导空格、引号里的逗号与换行、全角数字）", not bad_value,
          "\n".join(bad_value))

    # ---- 4. 类型与我们声明的一致 --------------------------------------------------------
    bad_type = []
    for index, sheet_name in enumerate(expect, start=1):
        for ref, (kind, want) in by_sheet[index].items():
            cell = book[sheet_name][ref]
            if cell.data_type != kind:
                bad_type.append("%s!%s 声明按 %s 存，读成 %s" % (sheet_name, ref, kind, cell.data_type))
            elif kind == "s" and cell.value != want:
                bad_type.append("%s!%s 文字变了：%r ≠ %r" % (sheet_name, ref, cell.value, want))
            elif kind == "n" and number_text(cell.value) != want:
                bad_type.append("%s!%s 数字不是原样：%r ≠ %s" % (sheet_name, ref, cell.value, want))
    check("四张表里每格的数字/文字属性都与我们自己声明的一致（%d 格 × 4）" % len(claim1), not bad_type,
          "\n".join(bad_type))

    # ---- 5. 有含义的写法一个都没被"猜"成数字 ---------------------------------------------
    guarded = {
        "C3": "007", "D2": "12.50", "G2": "12345678901234567", "G3": "1234567890123456",
        "H2": "=1+1", "H3": "@SUM(1,2)", "H4": "+8613800000000", "C4": "1e5", "F4": "１２",
        "G4": "-0", "D5": "-0", "E4": "尾随空格 ", "B3": " 梨带前导空格",
    }
    wrong = {ref: sheet[ref].value for ref in guarded if sheet[ref].data_type != "s"}
    leaked = {ref: sheet[ref].value for ref in guarded if sheet[ref].data_type == "s" and sheet[ref].value != guarded[ref]}
    check("007 / 1.50 / 17 位长号 / =1+1 / 全角数字 这些全部还是文字且一字未变",
          not wrong and not leaked, "变成数字的格子：%s；文字被改动的：%s" % (wrong, leaked))

    # ---- 6. 包自己说的与包里有的对得上 ----------------------------------------------------
    with zipfile.ZipFile(xlsx) as package:
        present = set(package.namelist())
        types = ET.fromstring(package.read("[Content_Types].xml"))
        declared = {node.get("PartName").lstrip("/") for node in types if node.tag == CT_NS + "Override"}
        default_ext = {node.get("Extension").lower() for node in types if node.tag == CT_NS + "Default"}
        missing = sorted(declared - present)
        dangling = []
        for rels in [n for n in present if n.endswith(".rels")]:
            base = os.path.dirname(os.path.dirname(rels))
            for node in ET.fromstring(package.read(rels)):
                target = node.get("Target")
                if target.startswith("http"):
                    continue
                resolved = os.path.normpath(os.path.join(base, target)).replace("\\", "/")
                if resolved not in present:
                    dangling.append("%s 指向不存在的部件 %s" % (rels, resolved))
        uncovered = sorted(
            n for n in present
            if n.endswith(".xml") and n != "[Content_Types].xml"
            and os.path.splitext(n)[1].lstrip(".") not in default_ext and n not in declared
        )
    check("[Content_Types] 声明的部件包里都有，没声明的部件也都被 Default 覆盖",
          not missing and not uncovered, "缺：%s；没声明：%s" % (missing, uncovered))
    check("每个 rels 的 Target 都落在真部件上", not dangling, "\n".join(dangling))

    # ---- 7. 与 openpyxl 自己写的同一批数据比个样 -------------------------------------------
    probe = Workbook()
    probe.remove(probe.active)
    probe_sheet = probe.create_sheet("table")
    # 参照物按我们声明的类型给值：数字给 int/float、文字给 str，这样比的才是"同一份数据"
    for y, row in enumerate(rows, start=1):
        line = []
        for x, want in enumerate(row, start=1):
            claim = claim1.get("%s%d" % (col(x), y))
            line.append(None if want == "" else (float(want) if "." in want else int(want))
                        if claim and claim[0] == "n" else want)
        probe_sheet.append(line)
    buffer = io.BytesIO()
    probe.save(buffer)
    other = load_workbook(io.BytesIO(buffer.getvalue()))["table"]
    diff = []
    for y, row in enumerate(rows, start=1):
        for x, want in enumerate(row, start=1):
            mine, theirs = sheet.cell(row=y, column=x), other.cell(row=y, column=x)
            if (mine.value or "") != (theirs.value or ""):
                diff.append("%s%d：%r vs openpyxl 自己写的 %r" % (col(x), y, mine.value, theirs.value))
    check("同一批数据交给 openpyxl 自己写，两边读回来的值全同（%d 格）" % (len(rows) * len(rows[0])),
          not diff, "\n".join(diff))

    # ---- 8. 声明过的话都对得上 -------------------------------------------------------------
    notes = read(os.path.join(PRODUCTS, "table.notes"))
    all_claims = [kind for sheet_claims in by_sheet.values() for kind, _ in sheet_claims.values()]
    numbers = all_claims.count("n")
    texts = all_claims.count("s")
    check("结果说明里的数字/文字格子数与实际一致",
          ("%d 格按原样写成数字" % numbers) in notes and ("%d 格保持文字" % texts) in notes, notes)
    check("以 = + @ 开头的格子在说明里交代了不会被当公式执行", "公式" in notes, notes)
    check("改了表名在说明里交代了几张", "表名" in notes and "3 张" in notes, notes)
    return finish()


def col(index):
    out = ""
    while index:
        index, rest = divmod(index - 1, 26)
        out = chr(ord("A") + rest) + out
    return out


def finish():
    print("\n通过 %d 条，失败 %d 条" % (len(PASSED), len(FAILED)))
    if FAILED:
        for item in FAILED:
            print("  未过：" + item)
        raise SystemExit(1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
