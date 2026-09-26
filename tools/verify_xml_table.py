#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 **ElementTree、openpyxl 与 pandas** 复核「XML → CSV / Excel」这一步。

被复核的产物（先跑）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.XmlTableFixtureTest'

这一步没有标准答案可抄（各家 xml→csv 挑哪一处当行、列怎么命名都不一样），
所以判据不是"跟别人一模一样"，而是这三件：
 1) 挑的是同一处 —— 用条数最多的那处重复元素，ElementTree 自己数一遍
 2) 列名与格子内容一致 —— 按同一套公开规则（属性带 @、单值嵌套摊成 父.子、
    元素文字进 文本、真嵌套留作一格文本）在 Python 里独立走一遍
 3) 产物别人读得动 —— openpyxl 读我们的 xlsx 与 CSV 逐格相同；pandas.read_xml
    当第三意见（不比列名，比行数与每行的值集合）
"""
import csv
import io
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
FIXTURE = os.path.join(ROOT, "core", "src", "test", "resources", "data", "catalog.xml")
BUILD = os.path.join(ROOT, "core", "build", "xmldata")

results = []


def check(name, ok, detail=""):
    results.append((bool(ok), name, detail))


def local(tag):
    return tag.split("}")[-1]


def as_number(text):
    """能当数看就看，不能就 None：用于区分"值不同"与"只是写法不同"。"""
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def arrays(node, trail, depth):
    """列出每个元素下"同名孩子的成组出现"：(路径, 条数, 深度, 孩子列表)。"""
    out = []
    kids = [c for c in node if isinstance(c.tag, str)]
    by_name = {}
    for child in kids:
        by_name.setdefault(local(child.tag), []).append(child)
    for name, group in by_name.items():
        out.append((".".join(trail + [name]), depth, group))
    for child in kids:
        out += arrays(child, trail + [local(child.tag)], depth + 1)
    return out


def expected_table():
    """按我们自己宣布的那套规则，用 ElementTree 独立推一遍列与格。

    挑哪一处：条数最多优先、字段数次之、浅的优先（与 XmlTable 的打分同一条规则）。
    """
    root = ET.parse(FIXTURE).getroot()
    groups = arrays(root, [local(root.tag)], 0)
    scored = []
    for path, depth, group in groups:
        rows = [flatten(child)[0] for child in group]
        scored.append((len(group) * 100 + max(len(row) for row in rows) * 10 - depth, path, rows))
    _, best_path, rows = max(scored, key=lambda item: item[0])
    columns = []
    for row in rows:
        for key in row:
            if key not in columns:
                columns.append(key)
    return best_path, columns, [[row.get(key, "") for key in columns] for row in rows]


def flatten(node):
    """一个元素摊成一行：(有序字典, 被压成文本的那几列)。规则与 XmlTable 头部注释一致。"""
    out, packed = {}, []
    for name, value in sorted(node.attrib.items()):
        out["@" + name] = value
    kids = [c for c in node if isinstance(c.tag, str)]
    # ElementTree 把元素之间的文字挂在孩子的 tail 上，而"这个元素自己的文字"是
    # 首文本 + 各段间隔文本的合写（要跟解析器怎么切无关），所以 tail 也得收进来
    own = ((node.text or "") + "".join(child.tail or "" for child in kids)).strip()
    if own:
        out["文本"] = own
    by_name = {}
    for child in kids:
        by_name.setdefault(local(child.tag), []).append(child)
    for name, group in by_name.items():
        if len(group) > 1:                    # 一个条目里那处有多条：一行摆不下，压成一格文本
            out[name] = json.dumps([single(child) for child in group], ensure_ascii=False, separators=(",", ":"))
            packed.append(name)
        elif any(isinstance(c.tag, str) for c in group[0]):    # 里面还有元素：摊成 父.子
            nested, inner_packed = flatten(group[0])
            for key, value in nested.items():
                out["{}.{}".format(name, key)] = value
            packed += ["{}.{}".format(name, key) for key in inner_packed]
        else:                                 # 纯文字或空元素：直接落一格
            out[name] = (group[0].text or "").strip()
    return out, packed


def single(node):
    """多条嵌套里那一条自己压成的文本。"""
    if not any(isinstance(c.tag, str) for c in node):
        return (node.text or "").strip()
    return json.dumps(flatten(node)[0], ensure_ascii=False, separators=(",", ":"))


def our_csv():
    with io.open(os.path.join(BUILD, "catalog.csv"), encoding="utf-8", newline="") as handle:
        return list(csv.reader(handle))


def main():
    path, columns, rows = expected_table()
    grid = our_csv()

    # 1) 挑的是同一处
    notes = io.open(os.path.join(BUILD, "notes.txt"), encoding="utf-8").read()
    check("ElementTree 数出来重复最多的那处，也正是我们取的那处", path in notes and ("行取自 " + path) in notes,
          "ElementTree 说 %s · 我们的说明：%s" % (path, notes.splitlines()[:1]))

    # 2) 列一致
    check("列名与列数与 ElementTree 独立推的一致", grid and grid[0] == columns,
          "我们 %s\n      期望 %s" % (grid[0] if grid else None, columns))

    # 3) 每格内容一致（按列名索引比，缺的格子必须是空）
    wide = [dict(zip(grid[0], row)) for row in grid[1:]] if grid else []
    want = [dict(zip(columns, row)) for row in rows]
    diff = []
    for index, (mine, expected) in enumerate(zip(wide, want)):
        for key in sorted(set(mine) | set(expected)):
            if mine.get(key, "") != expected.get(key, ""):
                diff.append("第 %d 行 %s：我们[%s] 期望[%s]" % (index + 1, key, mine.get(key), expected.get(key)))
    check("每一格的内容与 ElementTree 独立摊出来的一致", len(wide) == len(want) and not diff, "\n      ".join(diff[:5]))
    check("数字写法不被改：12.50 还是 12.50", any(row.get("price") == "12.50" for row in wide),
          str([row.get("price") for row in wide]))

    # 4) 真嵌套那一格还是能解回去的 JSON 文本
    packed_cells = [row.get("tags.tag", "") for row in wide if row.get("tags.tag")]
    ok_packed = bool(packed_cells)
    for cell in packed_cells:
        try:
            json.loads(cell)
        except ValueError:
            ok_packed = False
    check("真嵌套压成的那一格是合法 JSON 文本（不是被截断的残骸）", ok_packed and packed_cells, str(packed_cells)[:120])

    # 5) openpyxl 读我们的 xlsx
    try:
        from openpyxl import load_workbook
    except ImportError:
        load_workbook = None
    if load_workbook is None:
        check("openpyxl 可用（缺它就少一层复核）", False, "pip 里没有 openpyxl")
    else:
        book = load_workbook(os.path.join(BUILD, "catalog.xlsx"))
        sheet = book.active
        cells = [[("" if c.value is None else str(c.value)) for c in row] for row in sheet.iter_rows()]
        check("openpyxl 读我们的 xlsx：与那份 CSV 逐格相同", cells == grid,
              "xlsx %s\n      csv %s" % (cells[:2], grid[:2]))

    # 6) pandas.read_xml 当第三意见（不比列名，比行数与每行的值集合）
    try:
        import pandas
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            frame = pandas.read_xml(FIXTURE, xpath="//book")
    except Exception as bad:                      # pandas 缺 lxml 时会直接抛，这里不算判据失败
        check("pandas.read_xml 这一层可用", False, "%s" % type(bad).__name__)
    else:
        theirs = [sorted(str(v) for v in row.values() if str(v) != "nan") for row in frame.to_dict("records")]
        ours = [sorted(v for v in row.values() if v) for row in wide]
        loose, coerced = [], 0
        for expected, mine in zip(theirs, ours):
            for value in expected:
                if value in mine:
                    continue
                number = as_number(value)
                twin = next((w for w in mine if number is not None and as_number(w) == number), None) if number is not None else None
                if twin is None:
                    loose.append(value)
                else:
                    coerced += 1        # pandas 把 12.50 读成 12.5：我们那格必须还是源文件里的写法
        check(
            "pandas 读出的每条书都在我们这行里对得上，且它改写法的地方我们没改",
            len(frame) == len(wide) and not loose and coerced > 0,
            "pandas %d 行 / 我们 %d 行 · 对不上：%s · 改了写法 %d 处" % (len(frame), len(wide), loose[:3], coerced),
        )

    failed = 0
    for ok, name, detail in results:
        print("%s %s%s" % ("PASS" if ok else "FAIL", name, "" if ok or not detail else "\n      " + detail))
        failed += 0 if ok else 1
    print("XML 转表判据 %d 条 · 红 %d 条" % (len(results), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
