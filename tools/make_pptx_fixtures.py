#!/usr/bin/env python3
"""
生成 pptx 结构读法这一侧的夹具：两份由 pandoc 的 pptx 写者产出，一份手写。

为什么这么配：pandoc 的 pptx 写者与读者都是我们之外的实现（占位符怎么标 `p:ph type`、
列表记号写成 `buChar` / `buAutoNum` / 什么都不写、超链接挂在 `a:hlinkClick` 的 r:id 上，
都是它自己决定的写法），这些不用我们猜；而 LibreOffice 与 PowerPoint 真会写、pandoc 写不出来的
形状（跨列格 `gridSpan`、跨行格 `rowSpan`、页码域 `a:fld` 没算过、段内制表、
指向本页的段内链接），只能手写。

每份都要求 pandoc 读得动：读不动的夹具不是夹具，是坑（当场报错）。
产物落在 core/src/test/resources/pptxread/；JVM 那边读这些 .pptx 出四条产物，
tools/verify_pptx_read.py 再拿 pandoc 自己的 pptx 读法逐块比对。
"""
from __future__ import annotations

import io
import json
import os
import re
import subprocess
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

OUT = os.path.join("core", "src", "test", "resources", "pptxread")

DECK_MD = """---
title: "简报标题"
---

# 第一页 甲 & 乙

段落里有**粗体**、*斜体*、`行内代码`，还有[一个链接](https://example.com/a?x=1&y=2)。

段落里换行\
接着写。

- 圆点一
- 圆点二
  - 嵌套的圆点

## 只有小标题的一页

1. 编号一
2. 编号二

| 名称 | 数量 |
|------|------|
| 甲   | 1    |
| 乙   | 22   |

> 引用的一句话

~~旧说法~~。

# 第二页

- 带[链接的点](https://example.com/b)
- 最后一项
"""

NOTES_MD = """# 只有字的一页

一句普通的话。

# 又一页

- 只有一条
"""

SLIDE_NS = (
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"'
)
DECL = '<?xml version="1.0" encoding="UTF-8"?>'

# 手写那一页：跨列格两种写法各一处（只写 gridSpan 的 / gridSpan 与 hMerge 续格一起写的）、
# 跨行格、没算过的页码域、段内制表、只写在本页的段内链接、一张没写表头的表、
# 一处图片与一处图表占位符（占位符里没有字：图上的字在别的部件里，两边都不该搬）
HAND_SLIDE = DECL + """<p:sld """ + SLIDE_NS + """><p:cSld><p:spTree>
 <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
 <p:sp><p:nvSpPr><p:cNvPr id="2" name="T"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/>
  <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr lvl="0"><a:buNone/></a:pPr><a:r><a:t>形状清单</a:t></a:r></a:p></p:txBody></p:sp>
 <p:sp><p:nvSpPr><p:cNvPr id="3" name="B"/><p:cNvSpPr/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/>
  <p:txBody><a:bodyPr/><a:lstStyle/>
   <a:p><a:pPr lvl="0"><a:buNone/></a:pPr>
    <a:r><a:t>制表在这里：</a:t></a:r><a:tab/><a:r><a:rPr><a:hlinkClick r:id="rId2"/></a:rPr><a:t>外部地址</a:t></a:r>
    <a:r><a:t>，段内跳</a:t></a:r><a:r><a:rPr><a:hlinkClick r:id="rId3"/></a:rPr><a:t>到这里</a:t></a:r>
    <a:r><a:t>。页码：</a:t></a:r><a:fld id="{X}" type="slidenum"></a:fld>
   </a:p>
   <a:p><a:pPr lvl="0"><a:buChar char="•"/></a:pPr><a:r><a:rPr u="sng"/><a:t>带下划线的一条</a:t></a:r></a:p>
  </p:txBody></p:sp>
 <p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="4" name="F"/><p:cNvGraphicFramePr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvGraphicFramePr>
  <p:xfrm/>
  <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>
   <a:tblPr firstRow="1"/>
   <a:tblGrid><a:gridCol w="100"/><a:gridCol w="100"/><a:gridCol w="100"/><a:gridCol w="100"/></a:tblGrid>
   <a:tr h="100">
    <a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:p><a:r><a:t>甲</a:t></a:r></a:p></a:txBody></a:tc>
    <a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:p><a:r><a:t>跨两列</a:t></a:r></a:p></a:txBody><a:tcPr><a:hMerge/></a:tcPr></a:tc>
    <a:tc hMerge="1"><a:txBody><a:bodyPr/><a:p><a:r><a:t/></a:r></a:p></a:txBody></a:tc>
   </a:tr>
   <a:tr h="100">
    <a:tc rowSpan="2"><a:txBody><a:bodyPr/><a:p><a:r><a:t>竖合并</a:t></a:r></a:p></a:txBody><a:tcPr><a:vMerge/></a:tcPr></a:tc>
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>二</a:t></a:r></a:p></a:txBody></a:tc>
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>三</a:t></a:r></a:p></a:txBody></a:tc>
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>四</a:t></a:r></a:p></a:txBody></a:tc>
   </a:tr>
   <a:tr h="100">
    <a:tc vMerge="1"><a:txBody><a:bodyPr/><a:p><a:r><a:t/></a:r></a:p></a:txBody></a:tc>
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>五</a:t></a:r></a:p></a:txBody></a:tc>
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>六</a:t></a:r></a:p></a:txBody></a:tc>
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>七</a:t></a:r></a:p></a:txBody></a:tc>
   </a:tr>
  </a:tbl></a:graphicData></a:graphic></p:graphicFrame>
 <p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="6" name="G"/><p:cNvGraphicFramePr/><p:nvPr><p:ph idx="5"/></p:nvPr></p:nvGraphicFramePr>
  <p:xfrm/>
  <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>
   <a:tblGrid><a:gridCol w="100"/><a:gridCol w="100"/></a:tblGrid>
   <a:tr h="100">
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>没有表头</a:t></a:r></a:p></a:txBody></a:tc>
    <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>甲</a:t></a:r></a:p></a:txBody></a:tc>
   </a:tr>
  </a:tbl></a:graphicData></a:graphic></p:graphicFrame>
 <p:pic><p:nvPicPr><p:cNvPr id="8" name="P"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:spPr/></p:pic>
 <p:sp><p:nvSpPr><p:cNvPr id="9" name="C"/><p:cNvSpPr/><p:nvPr><p:ph type="chart" idx="3"/></p:nvPr></p:nvSpPr><p:spPr/>
  <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr/></a:p></p:txBody></p:sp>
</p:spTree></p:cSld></p:sld>
"""

HAND_RELS = DECL + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
 <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/hand?x=1&amp;y=2" TargetMode="External"/>
 <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slide1.xml"/>
</Relationships>
"""


def run(args: list) -> str:
    done = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if done.returncode != 0:
        raise SystemExit("跑不动：%s\n%s" % (" ".join(args), (done.stderr or done.stdout)[:600]))
    return done.stdout


def readable(path: str) -> str:
    """每份夹具都得让 pandoc 读得动 —— 读不动的夹具不成立。"""
    return run(["pandoc", "-f", "pptx", "-t", "json", "--wrap=none", path])


def write_pptx(path: str, parts: dict) -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as pack:
        pack.writestr("[Content_Types].xml", parts["[Content_Types].xml"])
        pack.writestr("_rels/.rels", parts["_rels/.rels"])
        pack.writestr("ppt/presentation.xml", parts["ppt/presentation.xml"])
        pack.writestr("ppt/_rels/presentation.xml.rels", parts["ppt/_rels/presentation.xml.rels"])
        pack.writestr("ppt/slides/slide1.xml", parts["ppt/slides/slide1.xml"])
        pack.writestr("ppt/slides/_rels/slide1.xml.rels", parts["ppt/slides/_rels/slide1.xml.rels"])
    with open(path, "wb") as handle:
        handle.write(buffer.getvalue())


def hand_parts() -> dict:
    return {
        "[Content_Types].xml": DECL + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="xml" ContentType="application/xml"/>
 <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
 <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
</Types>""",
        "_rels/.rels": DECL + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""",
        "ppt/presentation.xml": DECL + """<p:presentation """ + SLIDE_NS + """><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>""",
        "ppt/_rels/presentation.xml.rels": DECL + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
</Relationships>""",
        "ppt/slides/slide1.xml": HAND_SLIDE,
        "ppt/slides/_rels/slide1.xml.rels": HAND_RELS,
    }


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    for name, text in (("deck.md", DECK_MD), ("notes.md", NOTES_MD)):
        source = os.path.join("build", name)
        os.makedirs("build", exist_ok=True)
        with io.open(source, "w", encoding="utf-8") as handle:
            handle.write(text)
        target = os.path.join(OUT, name.replace(".md", ".pptx"))
        run(["pandoc", source, "-t", "pptx", "-o", target])
        readable(target)
        print("写好 %s（%d 字节）" % (target, os.path.getsize(target)))
    hand = os.path.join(OUT, "shapes.pptx")
    write_pptx(hand, hand_parts())
    got = readable(hand)
    for needle in ("形状清单", "跨两列", "竖合并", "带下划线的一条", "外部地址"):
        if needle not in got:
            raise SystemExit("手写夹具里 %r 没能被 pandoc 读出来，这份夹具不成立" % needle)
    print("写好 %s（%d 字节）" % (hand, os.path.getsize(hand)))
    tables = json.loads(got)["blocks"]
    kinds = [block["t"] for block in tables]
    if "Table" not in kinds:
        raise SystemExit("手写夹具里那张表没能被 pandoc 认成表，形状不像真文件：%s" % kinds)
    print("  pandoc 认出手写夹具里的块类型：%s" % sorted(set(kinds)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
