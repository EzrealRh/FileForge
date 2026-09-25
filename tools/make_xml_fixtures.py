#!/usr/bin/env python3
"""用 Python 标准库 ElementTree 造 XML 测试夹具与参照结构。

判据为什么算独立：**解析是 ElementTree 自己做的**，只有"XML 长什么样映射成 JSON 的哪个形状"
这条约定共用 —— 而这正是要判的东西。同一份字节，另一家解析器按同规则走一遍，应该得到
同一棵树；对不上就说明我这边解析错了。

约定（与 core/data/Xml.kt 头部注释一致）：
  - 子元素名一律映射成**数组**，哪怕只有一个
  - 属性名前面加 @；元素自己的文字放 #text
  - 只有文字或空元素 → 那个字符串 / null
  - 注释与处理指令丢掉

命名空间前缀不在这里验：ElementTree 是命名空间感知的，未声明的前缀它直接判非法，
而我这边故意不感知（名字原样留着）。这条差异写在 XmlTest 里单独钉，不混进参照值。

输出到 core/src/test/resources/data/：book.xml(.truth) 与 order.xml(.truth)。
用法：python tools/make_xml_fixtures.py
"""
import json
import os
import xml.etree.ElementTree as ET

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "data")

BOOK_XML = """<?xml version="1.0" encoding="UTF-8"?>
<!-- 这份夹具带注释：约定是丢掉，所以参照结构里不该有它 -->
<book id="b-1" lang="zh-CN">
  <title>带子元素的标题<n>内嵌</n></title>
  <chapter no="1">
    <heading>开始</heading>
    <body><![CDATA[里面可以有 <标签> 与 & 号]]></body>
  </chapter>
  <chapter no="2">
    <heading>进阶</heading>
  </chapter>
  <author>甲</author>
  <author>乙</author>
  <empty/>
  <note>实体 &amp; 与 &lt;尖括号&gt;</note>
</book>
"""

ORDER_XML = """<order>
  <item sku="A1"><price>12.50</price></item>
  <item sku="B2"><price>7</price></item>
  <total currency="CNY">19.50</total>
  <remark/>
</order>
"""


def xml_to_obj(elem):
    """按上面那套约定把一棵元素树走成一个可比较的 Python 结构。"""
    kids = [child for child in elem if isinstance(child.tag, str)]
    attrs = dict(("@" + key, value) for key, value in elem.attrib.items())
    text = (elem.text or "").strip()
    if not attrs and not kids:
        return text if text else None
    out = dict(attrs)
    if text:
        out["#text"] = text
    groups = {}
    for child in kids:
        groups.setdefault(child.tag, []).append(xml_to_obj(child))
    out.update(groups)
    return out


def compact(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def emit(name, body):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(body)
    root = ET.parse(path).getroot()
    # 参照值也带上根元素名那层：解析契约就是「最外层是只有一个键的对象」（XML→JSON→XML 要转得回去）
    reference = {root.tag: xml_to_obj(root)}
    with open(os.path.join(OUT, name + ".truth"), "w", encoding="utf-8", newline="") as handle:
        handle.write(compact(reference) + "\n")
    print(name, len(body), "字节 · 参照顶层键", list(reference.keys()))
    return reference


def main():
    book = emit("book.xml", BOOK_XML)["book"]
    # 参照值自己也要体检：约定里"author 是数组"这种形状如果 Python 侧就写错了，
    # Kotlin 那边照抄一个错的参照等于没测
    assert isinstance(book["author"], list) and len(book["author"]) == 2, book["author"]
    assert book["@id"] == "b-1", book
    # 子元素一律成数组，所以"空元素"是 [None] 而不是 None —— 数组里那一项才是它本体的值
    assert book["empty"] == [None], book["empty"]
    assert book["note"] == ["实体 & 与 <尖括号>"], book["note"]
    assert book["chapter"][1]["heading"] == ["进阶"], book["chapter"]
    assert "注释" not in json.dumps(book, ensure_ascii=False), "注释没被丢掉"
    emit("order.xml", ORDER_XML)
    print("写到", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
