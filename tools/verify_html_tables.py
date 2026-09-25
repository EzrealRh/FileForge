#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 **pandas.read_html** 复核 Kotlin 从网页里抽表格的位置对不对。

pandas 自带一整套"网页表格 → 二维表"（自己的解析器 + 自己的跨度处理），是真正的第二双手。
这里刻意**不比整格文字** —— 两家的格内排版本来就不一样（`<br>` 我们换成行、pandas 并成空格；
列表我们带记号、pandas 直接连读），比文字会把一堆无害差异报成错。

比的是这轮唯一真正会错、错了又看不出来的东西：**某个格子落在第几行第几列**。
做法是每个格子埋一个唯一标记（c31 之类），两边各自把标记的位置数出来再对：

- pandas 遇到 `colspan=2` 是把那格的内容**重复**到被盖住的位置上；我们是**留空格子**。
  两种摆法都合理，所以要判的是"我们那格的位置必须是 pandas 那几个位置里最靠左上的那个"，
  以及"没跨度的格子两边位置一模一样"
- 形状（几行几列）也要一致，但我们**保留整行空行**、pandas 丢掉，比之前两边都去掉全空行
- 只有散 `<td>`（没写 `<tr>`）的那张表 pandas 直接不看 —— 我们认它，这条作为声明过的差异钉住
"""
import csv
import io
import os
import re
import sys

import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SOURCE = os.path.join(ROOT, "core", "src", "test", "resources", "html", "tables.html")
PRODUCTS = os.path.join(ROOT, "core", "build", "html-tables")
MARKER = re.compile(r"[ctxy]\d{1,2}[a-z]?")

PASSED = []
FAILED = []


def check(what, ok, detail=""):
    print(("  通过：" if ok else "  失败：") + what)
    if not ok and detail:
        for line in str(detail).split("\n")[:8]:
            print("      " + line)
    (PASSED if ok else FAILED).append(what)
    return ok


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def our_tables():
    """产物 CSV 用 Python 标准库读回来 —— 连"我们的 CSV 写法"也一起过了一遍独立读者。"""
    manifest = []
    for line in read(os.path.join(PRODUCTS, "tables.manifest")).split("\n"):
        if line.strip():
            index, name, shape, notes = line.split("\t", 3)
            manifest.append((int(index), name, shape, notes))
    out = []
    for index, name, shape, notes in manifest:
        with io.open(os.path.join(PRODUCTS, "t%d.csv" % index), encoding="utf-8", newline="") as handle:
            rows = [row for row in csv.reader(handle) if row]
        out.append({"n": index, "name": name, "rows": rows, "shape": shape, "notes": notes})
    return out


def pandas_tables():
    with io.open(SOURCE, encoding="utf-8") as handle:
        frames = pd.read_html(handle, header=None)
    out = []
    for frame in frames:
        columns = [str(c) for c in frame.columns]
        body = [[("" if pd.isna(v) else str(v)) for v in row] for row in frame.values.tolist()]
        # pandas 会把 <thead> 那行当表头吃掉（还原成整表的一部分再比）
        grid = ([columns] if not all(re.fullmatch(r"\d+", c) for c in columns) else []) + body
        out.append([[cell for cell in row] for row in grid if any(cell.strip() for cell in row)])
    return out


def positions(grid, marker):
    return [
        (row, column)
        for row, line in enumerate(grid)
        for column, cell in enumerate(line)
        if marker in MARKER.findall(cell)
    ]


def markers_of(grid):
    found = set()
    for line in grid:
        for cell in line:
            found.update(MARKER.findall(cell))
    return found


def main():
    if not os.path.isfile(os.path.join(PRODUCTS, "tables.manifest")):
        raise SystemExit("没有 %s/tables.manifest，先跑 ./gradlew :core:test" % PRODUCTS)
    source = read(SOURCE)
    all_markers = sorted(set(MARKER.findall(source)))
    ours = our_tables()
    theirs = pandas_tables()

    # ---- 1. 每个标记都进了产物 ----------------------------------------------------------
    in_products = set()
    for table in ours:
        in_products |= markers_of(table["rows"])
    lost = [m for m in all_markers if m not in in_products]
    check("源文件里 %d 个格子标记一个不丢，全在产物里" % len(all_markers), not lost, "丢了的：%s" % lost)

    # ---- 2. 我们自己写的 CSV，标准库读回来形状与清单一致 -----------------------------------
    wrong_shape = [
        "%d 号表：清单写 %s，读回来 %dx%d" % (t["n"], t["shape"], len(t["rows"]), max(len(r) for r in t["rows"]))
        for t in ours
        if t["shape"] != "%dx%d" % (len(t["rows"]), max(len(r) for r in t["rows"]))
    ]
    check("清单里写的行列数与 CSV 读回来的一致（写与读自洽）", not wrong_shape, "\n".join(wrong_shape))

    # ---- 3. 标记位置与 pandas 相同 --------------------------------------------------------
    def pair(table):
        """配对外表：标记集合完全相同的那张优先 —— 内嵌表的标记在外层那张里也出现（压成了文字），
        只按重合数配会把内层配到外层去。"""
        mine = markers_of(table["rows"])
        exact = [c for c in theirs if markers_of(c) == mine]
        best = exact[0] if len(exact) == 1 else None
        if best is None:
            best, _ = max(
                ((c, len(mine & markers_of(c))) for c in theirs), key=lambda item: item[1], default=(None, 0),
            )
        return best, len(mine & markers_of(best)) if best is not None else 0

    misplaced = []
    spans = 0
    matched = 0
    for table in ours:
        other, share = pair(table)
        if share == 0:
            continue
        matched += 1
        mine = markers_of(table["rows"]) & markers_of(other)
        our_shape = (len(table["rows"]), max(len(r) for r in table["rows"]))
        their_shape = (len(other), max(len(r) for r in other))
        if our_shape != their_shape:
            misplaced.append("%d 号表形状不同：我们 %s，pandas %s" % (table["n"], our_shape, their_shape))
        for marker in sorted(mine):
            here = positions(table["rows"], marker)
            there = positions(other, marker)
            if not here or not there:
                misplaced.append("%s：我们 %s / pandas %s（一边没有）" % (marker, here, there))
            elif len(there) == 1:
                if here != there:
                    misplaced.append("%s：我们放在 %s，pandas 放在 %s" % (marker, here, there))
            else:
                # 跨过的格子：pandas 把内容重复到盖住的位置，我们留空 —— 要落在最靠左上那一格
                spans += 1
                if len(here) != 1 or here[0] != min(there):
                    misplaced.append(
                        "%s 是跨格：该只有我们那一个位置 %s 且是 pandas 那几个 %s 的最左上" % (marker, here, there)
                    )
        # 没跨度的标记不能两边数量不一致（我们多摆或少摆都说明对位算错）
        for marker in sorted(markers_of(other) - mine):
            misplaced.append("pandas 认得的 %s 在我们这张表里找不到" % marker)
    check("两边都认出的 %d 张表：每个标记落在同一格（跨格落在最左上）" % matched, not misplaced, "\n".join(misplaced))

    # ---- 4. 跨度确实被测到了（不然上面那条是空跑） --------------------------------------------
    t1 = ours[0]
    check("跨格的标记有 %d 个（判据不是空跑）" % spans, spans >= 2, "spans=%d" % spans)
    check("跨格数量在结果说明里报了，且与实际一致",
          ("%d 格带 colspan/rowspan" % spans) in t1["notes"], t1["notes"])

    # ---- 5. 声明过的差异：散 td 那张 pandas 不看，我们看 ---------------------------------------
    loose = [t for t in ours if "x1" in markers_of(t["rows"])]
    check("只有散 td（没写 tr）的那张表我们认、pandas 不认 —— 这条差异还在（不再一样就该重看判据）",
          len(loose) == 1 and not any("x1" in markers_of(g) for g in theirs),
          "我们：%s；pandas：%s" % ([t["name"] for t in loose], [len(g) for g in theirs]))

    # ---- 6. 空表不落文件但要说清楚 ----------------------------------------------------------
    skipped = read(os.path.join(PRODUCTS, "tables.skipped"))
    check("没有格子的那张表没出文件，但写进了说明", "没有格子" in skipped and "第 6 张表" in skipped, skipped)
    return finish()


def finish():
    print("\n通过 %d 条，失败 %d 条" % (len(PASSED), len(FAILED)))
    if FAILED:
        for item in FAILED:
            print("  未过：" + item)
        raise SystemExit(1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
