#!/usr/bin/env python3
"""用 Python 标准库 json / csv 造数据格式转换的夹具与参照值。

为什么要有这个脚本：JSON 的转义细节和 CSV 的引号规则都不是我自己说了算的东西。
参照值一律取"**Python 读同一份文件之后又写出来的样子**"，而不是我以为写进去什么：

  nested.json / nested.json.truth     带中文键、嵌套、多种数字形态 → json.dumps 紧凑形
  escapes.json / escapes.json.truth   转义最刁的一份（引号、反斜杠、控制字符、代理对）
  tricky.csv / tricky.csv.truth       引号里有逗号、双写引号、引号里有换行、结尾空字段
  table.csv / table.csv.truth         带前导零与 1.50 的表 → DictReader 出来全是字符串

用法：python tools/make_data_fixtures.py
产物写到 core/src/test/resources/data/。
"""
import csv
import io
import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")        # Windows 控制台默认 GBK，中文提示会糊

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "data")

# 故意写成源文本形态：前导零、大整数、科学计数、负零，看渲染会不会把它们改写掉
NESTED = (
    '{"名称":"季度报表","数量":1,"比值":1.50,"大数":9007199254740993,'
    '"科学":1e20,"负零":-0,"嵌套":{"表":["甲","乙"],"空":[],"对象":{"k":null}},'
    '"布尔":[true,false],"清单":[{"编号":"007","备注":"含,逗号"}]}'
)

# 反斜杠、引号、控制字符、代理对（emoji）、DEL、斜杠：转义只在这几处出错
ESCAPES = (
    '{"引号":"他说\\"你好\\"","反斜杠":"C:\\\\temp\\\\a.exe","换行":"一\\n二\\r\\n三",'
    '"制表":"a\\tb","控制":"\\u0001\\u001f","删除符":"\\u007f","斜杠":"a\\/b",'
    '"表情":"\\ud83d\\ude00","中文":"已经在这里了"}'
)

TRICKY_CSV = (
    "名称,数量,备注\r\n"
    '"含,逗号","他说""你好""","第三列"\r\n'
    "多行,\"第一行\n第二行\",结束\r\n"
    "尾巴,有值,\r\n"
)

TABLE_CSV = "编号,数量,比值,标记,文本\n007,42,1.50,true,N/A\nA-1,7,0.25,false,\"含,号\"\n"


def compact(obj):
    """紧凑 JSON：与 Kotlin 那边的默认渲染对齐 —— 不转非 ASCII、分隔符不带空格。"""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"), sort_keys=False)


def read_json(path, text):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text + "\n")
    return compact(json.loads(text))


def read_csv(path, text):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    with open(path, encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.reader(handle))
    return rows


def main():
    os.makedirs(OUT, exist_ok=True)

    nested_truth = read_json(os.path.join(OUT, "nested.json"), NESTED)
    write("nested.json.truth", nested_truth + "\n")

    escapes_truth = read_json(os.path.join(OUT, "escapes.json"), ESCAPES)
    write("escapes.json.truth", escapes_truth + "\n")

    tricky_rows = read_csv(os.path.join(OUT, "tricky.csv"), TRICKY_CSV)
    write("tricky.csv.truth", compact(tricky_rows) + "\n")
    print("tricky.csv 行数", len(tricky_rows), "各行列数", [len(r) for r in tricky_rows])

    with open(os.path.join(OUT, "table.csv"), "w", encoding="utf-8", newline="") as handle:
        handle.write(TABLE_CSV)
    with open(os.path.join(OUT, "table.csv"), encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    write("table.csv.truth", compact(rows) + "\n")

    print("写到", os.path.normpath(OUT))
    print("嵌套紧凑形前 60 字：", nested_truth[:60])


def write(name, payload):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="") as handle:
        handle.write(payload)


if __name__ == "__main__":
    main()
