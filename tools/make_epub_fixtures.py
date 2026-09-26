#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""造 EPUB 夹具：用标准库 zipfile 压一份**结构与顺序已知**的书，并写下 manifest。

为什么不用现成的 EPUB 库：这一份要给 Kotlin 侧读、又要给 pandoc 与 ElementTree 各读一遍当裁判，
三方都得认它 —— 那就用最原始的方式按规范拼（mimetype 必须是第一个条目且不压缩，
这是 EPUB 唯一的硬规定）。

陷阱是特意放进去的，每一个都对应一条判据：
  spine 顺序与文件名顺序相反、href 带 %20 与片段、NCX 的章名写在 content 之前、
  非线性的 itemref 不排进正文、清单里有但 spine 没用到的部件、图片与 CSS 只数不搬、
  外链与站内链接、段内的 br、一章没有 NCX 名字（该退回文档自己的 title）
book.epub 每一章都是合规的 XHTML：pandoc 遇到对不上的 spine idref 直接报错退出（parseSpine），
ElementTree 更是只认良构 —— 标签写坏了要能补全这件事由单元测试与 HTML 那几份判据管。

UTF-16 的那一章单开一份 utf16.epub：EPUB 3.3 写明「XHTML content documents MUST be encoded in
UTF-8」，但 EPUB 2 时代允许 UTF-16，老工具做的书里确实有 —— pandoc 读到 UTF-16 的正文整本解不动
（Invalid UTF-8 stream），所以这一份的裁判换成 ElementTree（它按 XML 声明与 BOM 认编码）。
我们照 BOM 认：认出来是白捡一章，认不出来才是一章没了。

产出：core/src/test/resources/epub/{book.epub,utf16.epub,manifest.json}（跟着仓库走）
跑法：python tools/make_epub_fixtures.py
"""
import io
import json
import os
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OUT = os.path.join(ROOT, "core", "src", "test", "resources", "epub")

XML_HEAD = '<?xml version="1.0" encoding="UTF-8"?>\n'

CONTAINER = XML_HEAD + (
    '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
    '<rootfiles><rootfile full-path="OEBPS/content.opf" '
    'media-type="application/oebps-package+xml"/></rootfiles></container>'
)

# 章节故意按 ch1 / ch2 / ch3 的名字排，但 spine 要 2 → 3 → 1 → 4。
# 键是清单里写的 href（带 %20），落到包里的是解开的文件名（真空格）—— 规范里 href 是 URI。
CHAPTERS = {
    "text/first%20part.xhtml": (
        XML_HEAD + '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章的文件名标题</title></head>'
        '<body><h2>章首是二级标题</h2><p>第一章有 <b>加粗</b> 与 <i>斜体</i>，'
        '段里还有一次 <br/>换行。</p></body></html>'
    ),
    "text/ch2.xhtml": (
        XML_HEAD + '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head><body>'
        '<h1>第二章 带实体 &amp; 符号 &#199;</h1>'
        '<ul><li>点一</li><li>点二</li></ul>'
        '<p>一个<a href="https://example.com/epub">外面的地址</a>与一个'
        '<a href="#here">站内的地址</a>。</p>'
        '<table><tr><th>甲</th><th>乙</th></tr><tr><td>1</td><td>2</td></tr></table></body></html>'
    ),
    "text/ch3.xhtml": (
        XML_HEAD + '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第三章</title></head><body>'
        '<h1>第三章 名字要从 NCX 来</h1><blockquote>引用的话</blockquote>'
        '<pre><code>一行代码</code></pre></body></html>'
    ),
    "text/ch4.xhtml": (
        XML_HEAD + '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第四章</title></head><body>'
        '<h1>第四章按有序列表排</h1><ol><li>第一</li><li>第二</li></ol></body></html>'
    ),
    # 清单里有、spine 里没用：有的书把附录/封面页留在包里但不排进阅读顺序
    "text/appendix.xhtml": (
        XML_HEAD + '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>附录</title></head><body>'
        '<h1>附录不该出现在正文里</h1></body></html>'
    ),
}

NCX = XML_HEAD + (
    '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head>'
    '<meta name="dtb:uid" content="urn:uuid-fileforge-demo"/></head>'
    '<docTitle><text>演示书</text></docTitle><navMap>'
    '<navPoint id="n3" playOrder="1"><navLabel><text>第三章 · 目录里叫这个</text></navLabel>'
    '<content src="text/ch3.xhtml"/></navPoint>'
    '<navPoint id="n1" playOrder="2"><navLabel><text>第一章 · 目录里叫这个 &amp; 那样</text></navLabel>'
    '<content src="text/first%20part.xhtml#top"/></navPoint>'
    '</navMap></ncx>'
)

OPF = XML_HEAD + (
    '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
    '<dc:identifier id="id">urn:uuid-fileforge-demo</dc:identifier>'
    '<dc:title>演示书</dc:title><dc:creator>编的作者</dc:creator>'
    '<dc:language>zh</dc:language></metadata>'
    '<manifest>'
    '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
    '<item id="c1" href="text/first%20part.xhtml" media-type="application/xhtml+xml"/>'
    '<item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>'
    '<item id="c3" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>'
    '<item id="c4" href="text/ch4.xhtml" media-type="application/xhtml+xml"/>'
    '<item id="extra" href="text/appendix.xhtml" media-type="application/xhtml+xml"/>'
    '<item id="css" href="style/book.css" media-type="text/css"/>'
    '<item id="cover" href="img/cover.png" media-type="image/png"/>'
    '</manifest>'
    '<spine toc="ncx">'
    '<itemref idref="c2"/>'
    '<itemref idref="c3"/>'
    '<itemref idref="c1"/>'
    '<itemref idref="c4"/>'
    '<itemref idref="c2" linear="no"/>'
    '</spine></package>'
)

# UTF-16 的那一本：一章，编码是唯一要验的事。
UTF16_CHAPTER = (
    '<?xml version="1.0" encoding="UTF-16"?>'
    '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>十六位的一章</title></head><body>'
    '<h1>按错的编码读会得到整章乱码</h1><p>这一章是 UTF-16 编的，正文里也有汉字。</p></body></html>'
)
UTF16_OPF = XML_HEAD + (
    '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid-u16</dc:identifier>'
    '<dc:title>十六位的书</dc:title></metadata>'
    '<manifest><item id="u1" href="text/utf16.xhtml" media-type="application/xhtml+xml"/></manifest>'
    '<spine><itemref idref="u1"/></spine></package>'
)

# path 是"从包根算起、href 解开百分号并去掉片段"之后的那份部件 —— 与清单里怎么写的分开记，
# 这样判据可以自己独立算一遍再比。
EXPECTED = [
    {"id": "c2", "href": "text/ch2.xhtml", "path": "OEBPS/text/ch2.xhtml", "title": "第二章",
     "title_from": "文档的 title"},
    {"id": "c3", "href": "text/ch3.xhtml", "path": "OEBPS/text/ch3.xhtml", "title": "第三章 · 目录里叫这个",
     "title_from": "NCX"},
    {"id": "c1", "href": "text/first%20part.xhtml", "path": "OEBPS/text/first part.xhtml",
     "title": "第一章 · 目录里叫这个 & 那样", "title_from": "NCX"},
    {"id": "c4", "href": "text/ch4.xhtml", "path": "OEBPS/text/ch4.xhtml", "title": "第四章",
     "title_from": "文档的 title"},
]


def local(tag):
    return tag.split('}')[-1]


def resolve(path, opf_name):
    """独立算一遍 spine 顺序：container → OPF → manifest → spine，只用 ElementTree。"""
    with zipfile.ZipFile(path) as book:
        opf_bytes = book.read(opf_name)
    dirn = opf_name.rsplit("/", 1)[0] if "/" in opf_name else ""
    root = ET.fromstring(opf_bytes)
    items, order = {}, []
    for node in root.iter():
        tag = local(node.tag)
        if tag == "item":
            items[node.get("id")] = node.get("href")
        elif tag == "itemref" and (node.get("linear") or "yes") != "no":
            order.append(node.get("idref"))
    out = []
    for idref in order:
        href = urllib.parse.unquote(items[idref]).split("#")[0]
        out.append("%s/%s" % (dirn, href) if dirn else href)
    return out


def build_book():
    target = os.path.join(OUT, "book.epub")
    with zipfile.ZipFile(target, "w") as pack:
        info = zipfile.ZipInfo("mimetype")
        info.compress_type = zipfile.ZIP_STORED
        pack.writestr(info, "application/epub+zip")
        pack.writestr("META-INF/container.xml", CONTAINER)
        pack.writestr("OEBPS/content.opf", OPF)
        pack.writestr("OEBPS/toc.ncx", NCX)
        pack.writestr("OEBPS/style/book.css", "body { margin: 0 }")
        pack.writestr("OEBPS/img/cover.png", bytes.fromhex("89504e470d0a1a0a0000000d49484452"))
        for href, body in CHAPTERS.items():
            pack.writestr("OEBPS/" + urllib.parse.unquote(href), body)
    return target


def build_utf16():
    target = os.path.join(OUT, "utf16.epub")
    with zipfile.ZipFile(target, "w") as pack:
        info = zipfile.ZipInfo("mimetype")
        info.compress_type = zipfile.ZIP_STORED
        pack.writestr(info, "application/epub+zip")
        pack.writestr("META-INF/container.xml", CONTAINER)
        pack.writestr("OEBPS/content.opf", UTF16_OPF)
        pack.writestr("OEBPS/text/utf16.xhtml", UTF16_CHAPTER.encode("utf-16"))
    return target


def main():
    os.makedirs(OUT, exist_ok=True)
    book = build_book()
    u16 = build_utf16()
    manifest = {
        "chapters": EXPECTED,
        "linear_no": ["c2"],
        "not_in_spine": ["OEBPS/text/appendix.xhtml"],
        "not_text": {"css": 1, "image": 1},
        "title": "演示书",
        "author": "编的作者",
        "utf16": {
            "file": "utf16.epub",
            "path": "OEBPS/text/utf16.xhtml",
            "title": "十六位的一章",
            "texts": ["按错的编码读会得到整章乱码", "这一章是 UTF-16 编的，正文里也有汉字。"],
        },
    }
    io.open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(manifest, ensure_ascii=False, indent=1))

    with zipfile.ZipFile(book) as check:
        names = check.namelist()
        assert names[0] == "mimetype", "mimetype 必须是第一个条目"
        assert check.getinfo("mimetype").compress_type == zipfile.ZIP_STORED, "mimetype 不能压缩"
        assert check.read("mimetype") == b"application/epub+zip"
        for item in EXPECTED + [{"path": p} for p in manifest["not_in_spine"]]:
            assert item["path"] in names, "声明的部件不在包里: %s" % item["path"]
    got = resolve(book, "OEBPS/content.opf")
    assert got == [c["path"] for c in EXPECTED], "ElementTree 自己算的顺序与声明不符: %s" % got
    with zipfile.ZipFile(u16) as check:
        raw = check.read("OEBPS/text/utf16.xhtml")
        assert raw[:2] == b"\xff\xfe", "UTF-16 夹具要带 BOM"
        root = ET.fromstring(raw)          # 按声明/BOM 解编码，能解出汉字才算这夹具成立
        assert local(root.tag) == "html"
        texts = [t.strip() for t in root.itertext() if t.strip()]
        for want in manifest["utf16"]["texts"]:
            assert want in texts, "UTF-16 夹具里的 %s 连 ElementTree 都没解出来" % want
    print("EPUB 夹具: book %d 条目 / %d 章, utf16 %d 条目" % (
        len(names), len(EXPECTED), len(zipfile.ZipFile(u16).namelist())))


if __name__ == "__main__":
    main()
