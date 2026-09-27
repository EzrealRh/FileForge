#!/usr/bin/env python3
"""
生成 ODT 读入这一侧的夹具：两份由 pandoc 写出的 .odt + 一份手写的"Writer 那样"的 .odt。

为什么这么配：pandoc 的 ODT 写者是我们之外的实现，它怎么摆样式表、怎么编号、
`_20_` 怎么编码样式名，都不用我们猜；而 LibreOffice 爱写的重复空列、跨列格、
批注与脚注这些 pandoc 写不出来的形状，只能手写 —— 手写的这份要过 `mimetype` 与
`manifest.xml` 两道包规矩，且**必须能被 pandoc 读动**（读不动就是这份夹具不合法，
当场报错，不留给判据去猜）。

产物落在 core/src/test/resources/odtread/；JVM 那边读这些 .odt 出四条产物，
tools/verify_odt_read.py 再拿 pandoc 自己的 ODT 读法逐块比对。
"""
from __future__ import annotations

import io
import os
import subprocess
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

OUT = os.path.join("core", "src", "test", "resources", "odtread")

LETTER_MD = """# 一封短信

第一段有**粗体**、*斜体*、`行内代码`，还有一个 [链接](https://example.com/a?x=1&y=2)。

第二段里换行\
连着写。

## 要点

- 点一
- 点二

  点二的续段还在同一个条目里
- 点三

### 更小的标题

1. 编号一
2. 编号二
   1.  deeper 一
   2.  deeper 二

| 名称 | 数量 | 备注 |
|------|------|------|
| 甲   | 1    | 空的 |
| 乙   | 22   |      |

> 引用的一句话
> 第二行也在这条引用里

```python
def f(x):
    return x + 1
```

---

收尾的一段，旧说法用 ~~划掉~~ 表示。
"""

KINDS_MD = """# Kinds

A plain paragraph with <u>underline</u> and *emphasis*.

## Nested lists

- alpha
  - beta
    - gamma
- delta

1. one
   1. one.one
2. two

## Table with an empty middle

| a | b | c |
|---|---|---|
| 1 |   | 3 |
| 只有一格 |

最后一段：数字 1.50 与 007 都不该被改成别的写法。
"""

OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
STYLE_NS = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
DRAW_NS = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
FO_NS = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
SVG_NS = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
XLINK_NS = "http://www.w3.org/1999/xlink"
DC_NS = "http://purl.org/dc/elements/1.1/"
META_NS = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
NUM_NS = "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"

MANIFEST = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
 <manifest:file-entry manifest:full-path="/" manifest:version="1.2" manifest:media-type="application/vnd.oasis.opendocument.text"/>
 <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
 <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
</manifest:manifest>
"""

STYLES_XML = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="%(office)s" xmlns:style="%(style)s" xmlns:text="%(text)s"
 xmlns:fo="%(fo)s" xmlns:svg="%(svg)s" office:version="1.2">
 <office:font-face-decls>
  <style:font-face style:name="Courier New" style:font-family-generic="modern" style:font-pitch="fixed" svg:font-family="'Courier New'"/>
  <style:font-face style:name="Arial" style:font-family-generic="swiss" style:font-pitch="variable" svg:font-family="Arial"/>
 </office:font-face-decls>
 <office:styles>
  <style:default-style style:family="paragraph"><style:paragraph-properties fo:margin-top="0.1in" fo:margin-bottom="0.1in"/></style:default-style>
  <style:style style:name="Standard" style:family="paragraph" style:class="text"/>
  <style:style style:name="Heading" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Text_20_body" style:class="text">
   <style:paragraph-properties fo:margin-top="0.16in" fo:margin-bottom="0.08in"/>
   <style:text-properties fo:font-size="120%%" fo:font-weight="bold"/>
  </style:style>
  <style:style style:name="Heading_20_2" style:display-name="Heading 2" style:family="paragraph" style:parent-style-name="Heading" style:default-outline-level="2" style:class="text"/>
  <style:style style:name="Text_20_body" style:display-name="Text body" style:family="paragraph" style:parent-style-name="Standard" style:class="text"/>
  <style:style style:name="Quotations" style:family="paragraph" style:parent-style-name="Standard" style:class="html"/>
  <style:style style:name="Footnote" style:family="paragraph" style:parent-style-name="Standard" style:class="extra"/>
  <style:style style:name="Horizontal_20_Line" style:display-name="Horizontal Line" style:family="paragraph" style:parent-style-name="Standard" style:class="extra"/>
 </office:styles>
</office:document-styles>
""" % {"office": OFFICE_NS, "style": STYLE_NS, "text": TEXT_NS, "fo": FO_NS, "svg": SVG_NS}

CONTENT_XML = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="%(office)s" xmlns:style="%(style)s" xmlns:text="%(text)s"
 xmlns:table="%(table)s" xmlns:draw="%(draw)s" xmlns:fo="%(fo)s" xmlns:svg="%(svg)s"
 xmlns:xlink="%(xlink)s" xmlns:dc="%(dc)s" xmlns:meta="%(meta)s" xmlns:num="%(num)s" office:version="1.2">
 <office:automatic-styles>
  <style:style style:name="T1" style:family="text"><style:text-properties fo:font-weight="bold" style:font-weight-asian="bold"/></style:style>
  <style:style style:name="T2" style:family="text"><style:text-properties fo:font-style="italic"/></style:style>
  <style:style style:name="T7" style:family="text"><style:text-properties style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color"/></style:style>
  <style:style style:name="T5" style:family="text"><style:text-properties fo:font-family="'Courier New'" style:font-family-generic="modern" style:font-pitch="fixed"/></style:style>
  <style:style style:name="T8" style:family="text" style:parent-style-name="T1"><style:text-properties fo:font-weight="normal" fo:font-style="oblique"/></style:style>
  <style:style style:name="P1" style:family="paragraph" style:parent-style-name="Text_20_body" style:list-style-name="L1"><style:paragraph-properties fo:margin-top="0in" fo:margin-bottom="0in"/></style:style>
  <style:style style:name="P2" style:family="paragraph" style:parent-style-name="Quotations"><style:paragraph-properties fo:margin-left="0.5in"/></style:style>
  <style:style style:name="P3" style:family="paragraph" style:parent-style-name="Text_20_body"><style:text-properties fo:font-weight="normal"/></style:style>
  <text:list-style style:name="L1">
   <text:list-level-style-bullet text:level="1" text:style-name="Bullet_20_Symbols" style:num-suffix="." text:bullet-char="•"><style:list-level-properties text:list-level-position-and-space-mode="label-alignment"/></text:list-level-style-bullet>
   <text:list-level-style-number text:level="2" text:style-name="Numbering_20_Symbols" style:num-format="1" text:start-value="1" style:num-suffix="."><style:list-level-properties text:list-level-position-and-space-mode="label-alignment"/></text:list-level-style-number>
  </text:list-style>
 </office:automatic-styles>
 <office:body>
  <office:text>
   <text:p text:style-name="Standard">段首之后是<text:s/><text:s text:c="3"/>四个空格，再来一个<text:tab/>制表。</text:p>
   <text:p text:style-name="Standard"><text:span text:style-name="T1">粗</text:span><text:span text:style-name="T2">斜</text:span><text:span text:style-name="T7">下划线</text:span><text:span text:style-name="T5">等宽</text:span>与<text:span text:style-name="T8">关掉粗体留下斜体</text:span></text:p>
   <text:h text:style-name="Heading_20_2" text:outline-level="2">小节标题<text:line-break/>第二行</text:h>
   <text:p text:style-name="P2">引用的一段，样式名是自动生成的 P2，"这是引用"写在它的父样式上。</text:p>
   <text:p text:style-name="Standard">跳到<text:a xlink:type="simple" xlink:href="#_Toc123456">这里</text:a>，真链接在<text:a xlink:type="simple" xlink:href="https://example.com/x?a=1&amp;b=2">那里</text:a>。</text:p>
   <text:list text:style-name="L1">
    <text:list-item><text:p text:style-name="P1">圆点一</text:p></text:list-item>
    <text:list-item><text:p text:style-name="P1">圆点二</text:p><text:p text:style-name="P1">圆点二的续段</text:p>
     <text:list><text:list-item><text:p text:style-name="P1">这一层是编号</text:p></text:list-item></text:list>
    </text:list-item>
   </text:list>
   <text:p text:style-name="Horizontal_20_Line"/>
   <table:table table:name="Table1" table:style-name="Table1">
    <table:table-column table:style-name="Table1.A"/>
    <table:table-column table:style-name="Table1.B"/>
    <table:table-column table:style-name="Table1.C"/>
    <table:table-header-rows>
     <table:table-row>
      <table:table-cell table:number-columns-spanned="2" table:type="string" office:value-type="string"><text:p>跨两列</text:p></table:table-cell>
      <table:table-cell table:type="string" office:value-type="string"><text:p>甲</text:p></table:table-cell>
     </table:table-row>
    </table:table-header-rows>
    <table:table-row>
     <table:table-cell office:value-type="string"><text:p>一</text:p></table:table-cell>
     <table:covered-table-cell/>
     <table:table-cell office:value-type="string"><text:p>三</text:p></table:table-cell>
    </table:table-row>
    <table:table-row>
     <table:table-cell table:number-columns-repeated="2" office:value-type="string"><text:p/></table:table-cell>
     <table:table-cell office:value-type="string"><text:p>带<text:soft-page-break/>软分页的一格</text:p></table:table-cell>
    </table:table-row>
   </table:table>
   <text:p text:style-name="Standard">带脚注的一句<text:note text:id="ftn1" text:note-class="footnote"><text:note-citation>1</text:note-citation><text:note-body><text:p text:style-name="Footnote">注文里的话不该混进正文</text:p></text:note-body></text:note>。</text:p>
   <text:p text:style-name="Standard">带批注的一句<office:annotation dc:date="2026-01-01T00:00:00"><dc:creator>someone</dc:creator><text:p>批注里的话不该混进正文</text:p></office:annotation>。</text:p>
   <text:p text:style-name="Standard">一个图形：<draw:frame draw:name="Frame1"><draw:image xlink:href="pic.png" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>后面还有字。</text:p>
   <text:p text:style-name="P3">整段样式写着粗体=normal，字不该变粗。</text:p>
   <text:p text:style-name="Standard"/>
  </office:text>
 </office:body>
</office:document-content>
""" % {
    "office": OFFICE_NS, "style": STYLE_NS, "text": TEXT_NS, "table": TABLE_NS, "draw": DRAW_NS,
    "fo": FO_NS, "svg": SVG_NS, "xlink": XLINK_NS, "dc": DC_NS, "meta": META_NS, "num": NUM_NS,
}


def run(args: list) -> str:
    done = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if done.returncode != 0:
        raise SystemExit("跑不动：%s\n%s" % (" ".join(args), (done.stderr or done.stdout)[:600]))
    return done.stdout


def write_odt(path: str, parts: dict, mimetype: str) -> None:
    """mimetype 第一条且不压缩：ODF 的硬规矩，别的实现靠它认类型。"""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as pack:
        pack.writestr(zipfile.ZipInfo("mimetype"), mimetype, compress_type=zipfile.ZIP_STORED)
        pack.writestr("META-INF/manifest.xml", parts["manifest"])
        pack.writestr("styles.xml", parts["styles.xml"])
        pack.writestr("content.xml", parts["content.xml"])
    with open(path, "wb") as handle:
        handle.write(buffer.getvalue())


def readable(path: str) -> str:
    """每份夹具都得让 pandoc 读得动 —— 读不动的夹具不是夹具，是坑。

    用 JSON 不用 native：`-t native` 会把非 ASCII 折成 `\\uNNNN`，
    那样"某句话在不在"这种自查会永远查不出来。
    """
    return run(["pandoc", "-f", "odt", "-t", "json", "--wrap=none", path])


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    scratch = "build"
    os.makedirs(scratch, exist_ok=True)
    for name, text in (("letter.md", LETTER_MD), ("kinds.md", KINDS_MD)):
        source = os.path.join(scratch, name)
        with io.open(source, "w", encoding="utf-8") as handle:
            handle.write(text)
        target = os.path.join(OUT, name.replace(".md", ".odt"))
        run(["pandoc", source, "-t", "odt", "-o", target])
        readable(target)
        print("写好 %s（%d 字节）" % (target, os.path.getsize(target)))
    spans = os.path.join(OUT, "spans.odt")
    write_odt(
        spans,
        {"manifest": MANIFEST, "styles.xml": STYLES_XML, "content.xml": CONTENT_XML},
        "application/vnd.oasis.opendocument.text",
    )
    native = readable(spans)
    # 这份夹具要真的带着那些形状：pandoc 读出来少了什么，判据就是空的
    for needle in ("圆点一", "跨两列", "等宽", "带软分页的一格", "四个空格", "关掉粗体留下斜体"):
        if needle not in native:
            raise SystemExit("手写夹具里 %r 没能被 pandoc 读出来，这份夹具不成立" % needle)
    # 批注的文字两边都不该出现：pandoc 也把它当批注丢掉。它还出现就说明这份夹具位置放错了
    if "批注里的话不该混进正文" in native:
        raise SystemExit("手写夹具里的批注被当成了正文，位置放错了")
    print("写好 %s（%d 字节）" % (spans, os.path.getsize(spans)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
