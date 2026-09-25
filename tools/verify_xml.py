#!/usr/bin/env python3
"""用 ElementTree 复核 Kotlin 写出来的 XML。

判据是"另一家解析器打不打得开、读出来是不是同一棵树"。自家 reader 读自家 writer
读开的东西不算证据 —— 用户拿产物是给别的工具用的。

先跑测试（XmlTest 会把渲染产物写到 core/build/data/）：

    ./gradlew :core:test

再跑本脚本：

    python tools/verify_xml.py
"""
import json
import os
import sys
import xml.etree.ElementTree as ET

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


def xml_to_obj(elem):
    """与 make_xml_fixtures.py 里同一套约定：子元素成数组、属性加 @、文字进 #text。"""
    kids = [child for child in elem if isinstance(child.tag, str)]
    out = dict(("@" + key, value) for key, value in elem.attrib.items())
    text = (elem.text or "").strip()
    if not out and not kids:
        return text or None
    if text:
        out["#text"] = text
    groups = {}
    for child in kids:
        groups.setdefault(child.tag, []).append(xml_to_obj(child))
    out.update(groups)
    return out


def main():
    if not os.path.isdir(BUILD):
        print("找不到 %s，先跑 ./gradlew :core:test" % os.path.normpath(BUILD))
        return 3

    print("Kotlin 渲染出的 XML")
    for name, truth_name in (("order.round.xml", "order.xml.truth"), ("book.round.xml", "book.xml.truth")):
        path = os.path.join(BUILD, name)
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as bad:
            check("%s 能被 ElementTree 解析" % name, False, str(bad)[:120])
            continue
        check("%s 能被 ElementTree 解析" % name, True)
        with open(os.path.join(FIXTURES, truth_name), encoding="utf-8") as handle:
            expected = json.loads(handle.read())
        # 参照值带着根元素名那一层，而 ET.parse 给的已经是根元素本身，所以补上同一层再比
        got = {root.tag: xml_to_obj(root)}
        check("%s 读出来的树与原始参照一致" % name, got == expected,
              "" if got == expected else json.dumps(got, ensure_ascii=False)[:180])

    print("转义与安全")
    rendered = open(os.path.join(BUILD, "book.round.xml"), encoding="utf-8").read()
    # 反例判据：夹具里本来就带 & 与 <>，转出来的文本必须一个都不剩地以实体形式存在，
    # 少转一处就是产出一份解析不了的 XML（ElementTree 会直接报错，所以这条得配合上面的解析一起看）
    check("特殊字符全都以实体形式写出", rendered.count("&amp;") >= 2 and "&lt;" in rendered,
          "&amp;×%d" % rendered.count("&amp;"))
    check("没有未转义的裸尖括号残留在文字里", "<note>实体 &amp; 与 &lt;尖括号&gt;</note>" in rendered,
          "原文里那个 <note> 的内容要照搬回去")
    with open(os.path.join(BUILD, "order.parsed.json"), encoding="utf-8") as handle:
        parsed = json.loads(handle.read())
    check("解析产物是合法 JSON 且顶层只有根元素名一个键", len(parsed) == 1, str(list(parsed))[:60])

    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\nElementTree 认可：Kotlin 写的 XML 别人打得开，读回来是同一棵树。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
