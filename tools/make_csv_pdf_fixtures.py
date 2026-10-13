#!/usr/bin/env python3
r"""
产「CSV 认成一张表」的夹具：九份分隔符文本 + 一份 truth.json。

真值由 **Python 的 csv 模块**读回来生成（写完再读一遍），不是脚本里手抄的期望 ——
我们那个 Kotlin 读法若把列切错，而期望值也来自同一套理解，判据就成了自己圆自己。
"文件里是哪几行哪几列"只问 csv 模块，"纸上是哪一格"只问 pdfplumber 的坐标。

每份都钉住一条主张：
    orders.csv   中文三列带表头 —— 最普通的那种
    quoted.csv   引号里的逗号与换行（RFC 4180），UTF-8 BOM 收尾
    people.tsv   制表符
    euro.csv     分号（Excel 欧区默认写法）
    logs.csv     竖线（日志导出常见）
    prose.txt    散文：每行逗号数不一样，**不许**认成表
    ragged.csv   列数不齐：不许认成表，且要说出第几行
    wide.csv     26 列：一张表比一整页还宽，要照实报超出纸边多少而不是裁字
    huge.csv     MAX_ROWS + 8 行：只排前若干行并写明共多少行
跑法：python tools/make_csv_pdf_fixtures.py
"""
from __future__ import annotations

import csv
import io
import json
import os

OUT = os.path.join("core", "build", "csvpdf")

ORDERS = [
    ["单号", "品名", "数量", "到货日"],
    ["A-001", "机械键盘", "38.5", "2023-05-01"],
    ["A-002", "显示器支架", "120", "2023-05-02"],
    ["A-003", "桌面台灯", "9.9", "2023-05-03"],
]
QUOTED = [
    ["项目", "说明"],
    ["一,二", "引号里的逗号不算分隔"],
    ["三\n四", "引号里的换行仍是一个字段"],
    ["末行", ""],
]
PEOPLE = [["姓名", "电话", "城市"], ["张三", "13800000000", "杭州"], ["李四", "13900000000", "成都"]]
EURO = [["Artikel", "preis"], ["Brot", "2,50"], ["Milch", "1,20"]]
LOGS = [["时间", "级别", "消息"], ["10:01", "INFO", "启动完成"], ["10:02", "WARN", "磁盘空间不足"]]
PROSE = [
    ["今天去了市场, 买了三样东西, 一路走着回来"],
    ["半上下起了雨"],
    ["这次的经历让我想到, 生活就是这样过的"],
]
RAGGED = [["甲", "乙", "丙"], ["1", "2", "3"], ["4", "5"], ["6", "7", "8"]]
WIDE = [["列%02d" % i for i in range(1, 27)]] + [["值%d-%d" % (r, c) for c in range(1, 27)] for r in range(1, 5)]
HUGE_ROWS = 5001


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    bom = "\ufeff"
    plan = [
        ("orders.csv", ORDERS, ",", ""),
        ("quoted.csv", QUOTED, ",", ""),
        ("people.tsv", PEOPLE, "\t", ""),
        ("euro.csv", EURO, ";", ""),
        ("logs.csv", LOGS, "|", ""),
        ("prose.txt", PROSE, ",", ""),
        ("ragged.csv", RAGGED, ",", ""),
        ("wide.csv", WIDE, ",", ""),
        ("huge.csv", [["序号", "内容"]] + [["%d" % i, "第 %d 行的内容" % i] for i in range(1, HUGE_ROWS + 1)], ",", ""),
    ]
    truth = {}
    for name, rows, delimiter, ending in plan:
        target = os.path.join(OUT, name)
        with io.open(target, "w", encoding="utf-8-sig" if name == "quoted.csv" else "utf-8", newline="") as handle:
            csv.writer(handle, delimiter=delimiter, lineterminator="\r\n" if name == "euro.csv" else "\n").writerows(rows)
        # 真值：写完再让 Python 的 csv 模块读回来（引号、跨行字段都按 RFC 4180 归它解释）
        with io.open(target, "r", encoding="utf-8-sig", newline="") as handle:
            back = [row for row in csv.reader(handle, delimiter=delimiter) if any(cell.strip() for cell in row)]
        truth[name] = {
            "delimiter": delimiter,
            "rows": len(back),
            "columns": max(len(row) for row in back),
            "header": back[0],
            "last": back[-1],
            "cells": back,
            "crlf": name == "euro.csv",
            "bom": name == "quoted.csv",
        }
    with io.open(os.path.join(OUT, "truth.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(truth, handle, ensure_ascii=False, indent=1)
    for name in sorted(truth):
        item = truth[name]
        print("%-12s %d 行 × %d 列（分隔符 %s）" % (name, item["rows"], item["columns"], repr(item["delimiter"])))
    print("夹具落在", OUT)


if __name__ == "__main__":
    main()
