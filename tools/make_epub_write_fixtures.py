#!/usr/bin/env python3
"""
生成"写 EPUB"这一侧的夹具：五份来料 + 一份 manifest.json（期望值）。

放在这里的理由与 epub / tar 那两份生成器一样：**期望值由另一套实现算出来**。
manifest 里的书号是按 RFC 4122 §4.3 的名字算法（SHA-1 + 命名空间）用 hashlib 折的，
语言是按字符数出来的，章名是从 Markdown / HTML 的一级标题里读的 ——
和 Kotlin 那边一行代码都不共用。两边对不上就是一边错了。

产物落在 core/src/test/resources/epubwrite/；JVM 那边读这些来料打成 .epub，
tools/verify_epub_write.py 再拿 pandoc 与 ElementTree 判那些字节。
"""
from __future__ import annotations

import hashlib
import html
import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

OUT = os.path.join("core", "src", "test", "resources", "epubwrite")

# 与 Kotlin 侧 EpubWrite.NAMESPACE 同一个常量：两边各自写死一份，对不上就报错
NAMESPACE = "0e7a2b1c-3f4d-5a6b-7c8d-9e0f1a2b3c4d"

NOTE_MD = """先说两句开场白，这部分在一章的标题之前。

# 第一章 甲 & 乙

这一段里有 <尖括号> 与 "引号"，还有 3 < 5 这样的比较。

一个链接在 [这里](https://example.com/a?x=1&y=2)，加粗的 **重点**、斜体的 *语气*、
删去的 ~~旧说法~~ 与 `行内代码` 都该带上。

- 点一
- 点二
  - 更深的一点
- 点三

1. 编号一
2. 编号二

> 引用一段话，说的是别人的意思。

```
代码块里的 <标签> 原样留着
```

| 表头 & 尖 | 乙 |
| --- | --- |
| <值> | 2 |

---

# 第二章 收尾

这一章只有一段。
"""

HEAD_MD = """# 只有标题开头

开头就是标题的那种，没有"开篇"那一章。

## 小节不另起一章

小节跟着上一章走。
"""

PAGE_HTML = """<!DOCTYPE html>
<html lang="zh">
<head><meta charset="utf-8"><title>网页的一本书</title></head>
<body>
<p>正文之前的一句话。</p>
<h1>第一章 &amp; 甲</h1>
<p>段里有 <code>行内代码</code>、<strong>加重</strong>、<em>强调</em>、<del>删掉</del>。</p>
<p>地址是 <a href="https://example.com/p?x=1&amp;y=2">这里</a>，下一行<br>还在同一段。</p>
<ul>
  <li>点一</li>
  <li>点二<ul><li>更深的一点</li></ul></li>
</ul>
<ol><li>编号一</li><li>编号二</li></ol>
<blockquote><p>引用里的话。</p></blockquote>
<pre><code>代码块 &lt;tag&gt;</code></pre>
<table>
  <thead><tr><th>表头 &amp; 尖</th><th>乙</th></tr></thead>
  <tbody><tr><td>&lt;值&gt;</td><td>2</td></tr></tbody>
</table>
<hr>
<h1>第二章 收尾</h1>
<p>这一章只有一段。</p>
</body>
</html>
"""

PLAIN_TXT = """第一段的话，中间不空行就还是一段
连着写下去。

第二段独立，因为上面空了一行。

缩进的这一行照原文留着，纯文本没有代码块那种约定。
"""

EN_MD = """# First chapter

This one is written in English, so the language tag should be en.

- a bullet
- another one
"""


def name_based_id(namespace: str, name: str) -> str:
    """RFC 4122 §4.3 的名字算法（SHA-1 那一档，俗称 UUIDv5）。"""
    digest = hashlib.sha1(bytes.fromhex(namespace.replace("-", "")) + name.encode("utf-8")).digest()
    raw = bytearray(digest[:16])
    raw[6] = (raw[6] & 0x0F) | 0x50
    raw[8] = (raw[8] & 0x3F) | 0x80
    hexed = raw.hex()
    return "urn:uuid:" + "-".join([hexed[0:8], hexed[8:12], hexed[12:16], hexed[16:20], hexed[20:32]])


def body_text(name: str, text: str) -> str:
    """把来料摊成正文文字：标签名与 Markdown 记号不是正文，数语言时不能算进去。

    与 Kotlin 那边（从文档树数）是两套不同的做法，比的是"分出来同一档"：
    这边多删或少删几个记号，只要不改 zh / en / und 的归类就不算错。
    """
    if name.endswith(".html"):
        text = re.sub(r"<head>.*?</head>", " ", text, flags=re.S)
        # 先拆标签再解实体：&lt;b&gt; 这种写法拆标签时不会被误吃掉
        return html.unescape(re.sub(r"<[^>]+>", " ", text))
    lines = []
    for line in text.split("\n"):
        line = re.sub(r"^#{1,6}\s*", "", line)
        line = re.sub(r"^\s*[-*+]\s+", "", line)
        line = re.sub(r"^\s*\d+\.\s+", "", line)
        line = re.sub(r"^>\s*", "", line)
        line = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", line)
        line = re.sub(r"`+", "", line)
        line = line.replace("|", " ")
        line = re.sub(r"[*~_]{1,2}", "", line)
        lines.append(re.sub(r"^\s*[:-]{3,}\s*$", "", line))
    return "\n".join(lines)


def language_of(name: str, text: str) -> str:
    """中日韩字符与拉丁字母哪个多算哪个，两个都没有就是 und。"""
    body = body_text(name, text)
    cjk = sum(
        1 for ch in body
        if "\u4e00" <= ch <= "\u9fff" or "\u3040" <= ch <= "\u30ff" or "\uac00" <= ch <= "\ud7af"
    )
    latin = sum(1 for ch in body if ("a" <= ch <= "z") or ("A" <= ch <= "Z"))
    if cjk == 0 and latin == 0:
        return "und"
    return "zh" if cjk >= latin else "en"


def chapters_for(name: str, text: str) -> list[str]:
    """按来料种类读出一级标题的名单；第一个标题之前还有实义内容就多一章"开篇"。"""
    fallback = os.path.splitext(name)[0]
    if name.endswith(".html"):
        titles = [html.unescape(re.sub(r"<[^>]+>", "", hit)).strip() for hit in re.findall(r"<h1[^>]*>(.*?)</h1>", text, re.S)]
        head = text.split("<h1")[0].split("<body>")[-1]
        before = re.sub(r"<[^>]+>", "", head).strip()
    else:
        titles = [line[2:].strip() for line in text.split("\n") if line.startswith("# ") and not line.startswith("## ")]
        head = text.index("# ") if titles else len(text)
        before = text[:head].strip()
    if not titles:
        return [fallback]
    return (["开篇"] if before else []) + titles


def main() -> int:
    sources = {
        "note.md": NOTE_MD,
        "head.md": HEAD_MD,
        "page.html": PAGE_HTML,
        "plain.txt": PLAIN_TXT,
        "english.md": EN_MD,
    }
    titles = {
        "note.md": "读书笔记甲乙",
        "head.md": "",           # 空着用文件名当书名
        "page.html": "网页的一本书",
        "plain.txt": "随手记",
        "english.md": "An English note",
    }
    authors = {
        "note.md": "写书的人",
        "head.md": "",
        "page.html": "",         # 网页里没有作者信息：留空，不编一个
        "plain.txt": "某人",
        "english.md": "",
    }

    os.makedirs(OUT, exist_ok=True)
    cases = []
    for name, text in sources.items():
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        title = titles[name] or os.path.splitext(name)[0]
        author = authors[name]
        cases.append(
            {
                "source": name,
                "title": title,
                "author": author,
                "chapters": chapters_for(name, text),
                "language": language_of(name, text),
                # 书号的种子与 Kotlin 侧同一条式子：书名 + 原文
                "identifier": name_based_id(NAMESPACE, title + text),
            }
        )
    manifest = {
        "namespace": NAMESPACE,
        "modified": "2023-11-14T22:13:20Z",
        "modified_at": 1_700_000_000_000,
        "cases": cases,
    }
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("写了 %d 份来料与 manifest.json 到 %s" % (len(sources), OUT))
    for case in cases:
        print("  %-11s %d 章 · %s · %s" % (case["source"], len(case["chapters"]), case["language"], case["identifier"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
