#!/usr/bin/env python3
"""
判数据族补齐的四条边（XML⇄YAML、CSV→XML、YAML→Excel）与已有的边是不是**同一套判断**。

这一族的卖点不是"能转"，而是"从哪条路转过去得到的东西一样"。所以每条判据都要拿
一个第三方实现读我们写的产物，再和它独立读来料的结果比：

  1 Python 的 ElementTree 独立摊 catalog.xml 成树，PyYAML 读我们的 catalog.yaml —— 两边同一棵树
  2 同一份 catalog.xml，XML→YAML 与 XML→JSON 是同一棵树（两边都在 Python 侧比，不靠我们自说）
  3 我们写的三份 XML 都良构，且**只有一个根元素**（顶层数组不包就会写出两个并排根）
  4 CSV→XML：行数与列名与 csv 模块独立读 table.csv 一致，格子的字一字不差
  5 YAML→Excel 的格子与 Python 独立读来料的那张表一致（参照物不能是我们自己从同一棵树写的 CSV）
  6 tricky.csv（含逗号、引号、换行、空值）经 CSV→YAML→PyYAML 读回来还是那些字
  7 YAML→XML：config.yaml 的键与 config.xml 的元素一一对得上（@ 前缀成属性、列表成重复元素、
    #text 成元素自己的文字），按 core/data/Xml.kt 头部写明的约定独立摊
  8 "看着像数字"的写法不被改：table.xml 里 >007< 与 >1.50< 原样在，pandas 读回来还是文字

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.DataMatrixTest' --rerun
    python tools/verify_data_matrix.py
"""
from __future__ import annotations

import csv
import json
import os
import sys
import xml.etree.ElementTree as ElementTree

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

SRC = os.path.join("core", "src", "test", "resources")
BUILD = os.path.join("core", "build", "datamatrix")

results = []


def check(number: int, name: str, ok: bool, detail: str = "") -> bool:
    results.append((number, name, ok))
    print("%s %d %s%s" % ("OK  " if ok else "FAIL", number, name, (" :: " + detail) if detail and not ok else ""))
    return ok


def attempt(function):
    """跑一段读产物的代码，任何异常都变成"这条红"而不是整份判据崩掉。

    第三方读不动我们写的文件本身就是结论（比如 YAML 少了引号会解析失败），
    那必须点名到具体哪一条 —— 让它抛出来，反而看不出是判据管的还是没管的。
    """
    try:
        return function(), ""
    except Exception as error:  # 参照实现报什么就照它报什么
        return None, "%s: %s" % (type(error).__name__, str(error).splitlines()[0] if str(error) else error)


def load(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def own_text(element) -> str:
    """元素自己的文字：DOM 里就是它直接挂着的文本节点，夹在孩子之间的也算进来。"""
    pieces = [node.data for node in element.childNodes if node.nodeType == node.TEXT_NODE]
    return "".join(pieces).strip()


def xml_tree(element):
    """按 core/data/Xml.kt 头部约定的独立摊法：孩子一律成列表，属性前面带 @，自己的文字进 #text。

    用 **minidom** 而不是 ElementTree：后者的解析器默认按命名空间处理，会把 `xmlns:xsi="…"`
    这类声明当簿记吃掉；我们的约定写明了"前缀原样留在名字里、xmlns 声明当普通属性"，
    minidom 照字面给属性名，才判得了那一条（也是另一套独立实现）。
    """
    children = {}
    for child in element.childNodes:
        if child.nodeType != child.ELEMENT_NODE:
            continue
        children.setdefault(child.tagName, []).append(xml_tree(child))
    text = own_text(element)
    attributes = sorted((name, value) for name, value in element.attributes.items())
    if not children and not attributes:
        return text if text else None
    out = {}
    for name, value in attributes:
        out["@" + name] = value
    if text:
        out["#text"] = text
    for key, value in children.items():
        out[key] = value
    return out


def xml_paths(element, prefix=()):
    """一份 XML 里的元素路径集合：路径 -> 出现过（不比次数，见 main 里那条说明）。"""
    found = set()
    for child in element.childNodes:
        if child.nodeType != child.ELEMENT_NODE:
            continue
        path = prefix + (child.tagName,)
        found.add("/".join(path))
        found |= xml_paths(child, path)
    return found


def yaml_paths(value, prefix=()):
    """同一件事从 YAML 那侧算：列表的各项都挂在同一个键下。"""
    found = set()
    if isinstance(value, dict):
        for key, item in value.items():
            found.add("/".join(prefix + (key,)))
            found |= yaml_paths(item, prefix + (key,))
    elif isinstance(value, list):
        for item in value:
            found |= yaml_paths(item, prefix)
    return found


def main() -> int:
    if not os.path.isdir(BUILD):
        print("没有 %s，先跑 :core:test --tests '*DataMatrixTest'" % BUILD)
        return 1

    import yaml  # PyYAML：判 YAML 的参照实现
    import openpyxl
    from xml.dom import minidom

    def source_yaml(path):
        return attempt(lambda: yaml.safe_load(load(path)))

    def dom(path):
        return attempt(lambda: minidom.parse(path).documentElement)

    catalog_dom, catalog_error = dom(os.path.join(SRC, "data", "catalog.xml"))
    want_tree = {catalog_dom.tagName: xml_tree(catalog_dom)} if catalog_error == "" else {}

    our_yaml, yaml_error = source_yaml(os.path.join(BUILD, "catalog.yaml"))
    check(1, "PyYAML 读我们写的 YAML 与 DOM 独立摊的树一致",
          catalog_error == "" and yaml_error == "" and our_yaml == want_tree,
          yaml_error or catalog_error or diff_brief(our_yaml, want_tree))

    our_json, json_error = attempt(lambda: json.loads(load(os.path.join(BUILD, "catalog.json"))))
    check(2, "XML→YAML 与 XML→JSON 是同一棵树",
          json_error == "" and yaml_error == "" and our_json == our_yaml,
          json_error or "两边在 Python 侧就不是同一个东西：%s" % diff_brief(our_json, our_yaml))

    bad_roots = []
    for name in ("catalog.xml", "config.xml", "table.xml"):
        error = attempt(lambda: ElementTree.fromstring(load(os.path.join(BUILD, name))))[1]
        if error:
            # "只有一个根元素"不用自己数：两个并排的根，解析器直接报 junk after document element
            bad_roots.append("%s：%s" % (name, error))
    check(3, "写出的 XML 都良构且只有一个根元素", not bad_roots, "；".join(bad_roots))

    rows = read_csv(os.path.join(SRC, "data", "table.csv"))
    table_dom, table_error = dom(os.path.join(BUILD, "table.xml"))
    xml_rows = elements(table_dom) if table_dom is not None else []
    names = [node.tagName for node in elements(xml_rows[0])] if xml_rows else []
    values = [[text_of(cell) for cell in elements(row)] for row in xml_rows]
    check(4, "CSV→XML 的行、列名与格子与 csv 模块独立读来料一致",
          table_error == "" and len(xml_rows) == len(rows) - 1 and names == rows[0] and values == rows[1:],
          table_error or "行 %d 对 %d，列名 %s 对 %s，值 %s 对 %s"
          % (len(xml_rows), len(rows) - 1, names, rows[0], values, rows[1:]))

    cells, xlsx_error = attempt(lambda: [
        [("" if cell.value is None else str(cell.value)) for cell in row]
        for row in openpyxl.load_workbook(os.path.join(BUILD, "fromyaml.xlsx")).worksheets[0].iter_rows()
    ])
    # 参照物是"Python 自己读来料那份表"，不是我们从同一棵树写出去的 CSV ——
    # 两边都走同一个摊表函数的话，摊表少一行之类的问题自己跟自己对齐，判据等于没判
    source_rows = read_csv(os.path.join(SRC, "data", "table.csv"))
    check(5, "YAML→Excel 的格子与 Python 独立读来料的行列一致",
          xlsx_error == "" and cells == source_rows,
          xlsx_error or "xlsx %s 对 来料 %s" % (cells, source_rows))

    tricky = read_csv(os.path.join(SRC, "data", "tricky.csv"))
    back, tricky_error = source_yaml(os.path.join(BUILD, "tricky.yaml"))
    expect = [dict(zip(tricky[0], row)) for row in tricky[1:]]
    check(6, "含逗号、引号、换行、空值的格子转 YAML 再读回来还是那些字",
          tricky_error == "" and back == expect,
          tricky_error or "读回 %s 期望 %s" % (back, expect))

    config_source, config_error = source_yaml(os.path.join(SRC, "yaml", "config.yaml"))
    config_dom, config_dom_error = dom(os.path.join(BUILD, "config.xml"))
    # 比的是"路径集合"而不是每个路径的次数：我们的约定把孩子一律收成列表，
    # 一个元素的 <hosts> 与两个 <hosts> 读回来都是列表，次数在这种约定下本来就不可辨。
    paths_xml = (xml_paths(config_dom, (config_dom.tagName,)) | {config_dom.tagName}) if config_dom is not None else set()
    paths_yaml = yaml_paths({"config": config_source}) if config_error == "" else set()
    check(7, "YAML→XML 的键与元素一一对得上（列表成重复元素、@ 成属性）",
          config_error == "" and config_dom_error == "" and config_dom is not None
          and config_dom.tagName == "config" and paths_xml == paths_yaml,
          (config_error or config_dom_error) or "只在 XML 有 %s；只在 YAML 有 %s" % (
              sorted(paths_xml - paths_yaml)[:6], sorted(paths_yaml - paths_xml)[:6]))

    literals = [text_of(cell) for row in xml_rows for cell in elements(row)]
    untouched = all(raw in literals for raw in ("007", "1.50", "0.25", "true", "false", "N/A"))
    check(8, "看着像数字的写法原样进元素（不是被谁改了字面）", table_error == "" and untouched,
          table_error or "元素里的字：%s" % literals[:8])

    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    return 1 if failed else 0


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as handle:
        return list(csv.reader(handle))


def elements(node):
    return [child for child in node.childNodes if child.nodeType == child.ELEMENT_NODE] if node else []


def text_of(node):
    return "".join(child.data for child in node.childNodes if child.nodeType == child.TEXT_NODE)


def diff_brief(a, b) -> str:
    """指出第一处不同的路径，别只说"不相等"。"""
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            if key not in a:
                return "缺键 %r（对方有：%r）" % (key, b[key])
            if key not in b:
                return "多键 %r（我方：%r）" % (key, a[key])
            found = diff_brief(a[key], b[key])
            if found:
                return "%r.%s" % (key, found)
        return ""
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return "长度 %d 对 %d" % (len(a), len(b))
        for index, (left, right) in enumerate(zip(a, b)):
            found = diff_brief(left, right)
            if found:
                return "[%d].%s" % (index, found)
        return ""
    if a != b:
        return "%r 对 %r" % (a, b)
    return ""


if __name__ == "__main__":
    sys.exit(main())
