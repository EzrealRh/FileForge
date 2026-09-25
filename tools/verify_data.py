#!/usr/bin/env python3
"""用 Python 标准库 json / csv 复核 Kotlin 的数据格式产物。

为什么要有这一步：JSON 与 CSV 的判据是"别人的解析器读出来是不是同一份意思"。
自家 writer 写、自家 reader 读永远一致，那只能证明我没写崩，证明不了产物能用。
所以这里全部交给 json.loads / csv.reader 去看。

先跑测试（CsvJsonTest 会把产物写到 core/build/data/）：

    ./gradlew :core:test

再跑本脚本：

    python tools/verify_data.py

判据：
 1. 三份 JSON 产物都 `json.loads` 得开，且与源夹具**语义相同**（值相等，不要求字节相同）
 2. 数字原文不许被改写：`1.50` 还是 `1.50`、`9007199254740993` 不丢位、`1e20` 不变成 `1e+20`
 3. 转义那份与 Python 自己的 dumps **逐字节相同**（引号/反斜杠/控制字符/DEL/代理对）
 4. reparsed.csv 用 csv.reader 读回来，与 Python 读原始 tricky.csv 的结果**完全相同**
 5. 表转 JSON：默认那条全是字符串；开类型识别的那条 `007` 与 `1.50` 仍是字符串而 `42` 成了整数
"""
import csv
import io
import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")        # Windows 控制台默认 GBK，中文提示会糊

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
BUILD = os.path.join(ROOT, "core", "build", "data")
FIXTURES = os.path.join(ROOT, "core", "src", "test", "resources", "data")

failures = []


def check(label, ok, detail=""):
    print(("  通过  " if ok else "  失败  ") + label + (("：" + detail) if detail else ""))
    if not ok:
        failures.append(label)


def fixture(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as handle:
        return handle.read()


def product(name):
    with open(os.path.join(BUILD, name), encoding="utf-8") as handle:
        return handle.read()


def main():
    if not os.path.isdir(BUILD):
        print("找不到 %s，先跑 ./gradlew :core:test" % os.path.normpath(BUILD))
        return 3
    print("复核目录", os.path.normpath(BUILD))

    print("JSON 渲染产物")
    for name in ("nested.compact.json", "nested.indent2.json", "escapes.round.json"):
        try:
            mine = json.loads(product(name))
            check("%s 能 json.loads" % name, True)
        except Exception as bad:                     # noqa: BLE001 —— 判据就是"能不能开"
            check("%s 能 json.loads" % name, False, str(bad)[:120])
            continue
        source = json.loads(fixture("nested.json" if name.startswith("nested") else "escapes.json"))
        check("%s 与源夹具语义相同" % name, mine == source)

    compact = product("nested.compact.json")
    check("1.50 没被改写成 1.5", '"比值":1.50' in compact or '"比值": 1.50' in compact, compact[:80])
    check("1e20 没被改写成 1e+20", "1e20" in compact and "1e+20" not in compact)
    check("大整数 2^53+1 一位没丢", "9007199254740993" in compact)

    check(
        "转义那份与 Python 的 dumps 逐字节相同",
        product("escapes.round.json").strip() == fixture("escapes.json.truth").strip(),
    )

    print("CSV 往返")
    with io.StringIO(product("reparsed.csv"), newline="") as handle:
        mine = list(csv.reader(handle))
    with io.StringIO(fixture("tricky.csv"), newline="") as handle:
        theirs = list(csv.reader(handle))
    check("reparsed.csv 读回来与 Python 读原始文件相同", mine == theirs, "%r vs %r" % (mine[:2], theirs[:2]))
    check("行数一致", len(mine) == len(theirs), "%d vs %d" % (len(mine), len(theirs)))

    print("表 → JSON")
    strings = json.loads(product("table.as-strings.json"))
    with open(os.path.join(FIXTURES, "table.csv"), encoding="utf-8", newline="") as handle:
        dict_rows = list(csv.DictReader(handle))
    check("默认不猜类型时与 DictReader 完全相同", strings == dict_rows, json.dumps(strings, ensure_ascii=False)[:100])

    typed = json.loads(product("table.typed.json"))
    check("007 仍是字符串（猜成 7 就回不去了）", typed[0]["编号"] == "007", repr(typed[0]["编号"]))
    # 开着识别时 1.50 按 JSON 语法确实是数字，于是"尾零"这个写法在 JSON 里没有容身之处：
    # 渲染出的文本仍写着 1.50，可任何 json 库读回去都是 1.5。这一条要如实报给用户，别假装无损
    check("渲染文本里数字写法没被改写", '"比值":1.50' in product("table.typed.json"),
          product("table.typed.json")[:80])
    check("读回去 1.50 就成了 1.5（JSON 数字没有格式可言）", typed[0]["比值"] == 1.5, repr(typed[0]["比值"]))
    check("42 认成了整数", typed[0]["数量"] == 42, repr(typed[0]["数量"]))
    check("true 认成了布尔", typed[0]["标记"] is True, repr(typed[0]["标记"]))
    check("N/A 保持字符串", typed[0]["文本"] == "N/A")
    with open(os.path.join(FIXTURES, "table.csv"), encoding="utf-8", newline="") as handle:
        arrays = list(csv.reader(handle))
    check("不当表头时与 csv.reader 的二维数组相同", json.loads(product("csv-as-arrays.json")) == arrays)

    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\njson / csv 认可：产物打得开、语义相同、数字与转义都没被改写。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
