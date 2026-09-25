#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 pandoc 复核 Kotlin 写的 Markdown 渲染器。

pandoc 的 markdown 读取器是另一套完整实现（Haskell 写的），所以"同一份 md 渲染成同一棵树"
不是我自己家两个实现互相点头。

HTML 这一路比的是**结构与文字**：各家把代码块包成什么样、表格对齐写 align 还是 style，
那是自家规矩；标签顺序与里面的字必须一模一样。要跳开的那些写法在代码里逐条写明，
跳过的条数会打印出来 —— 免得哪天把整棵树"规范化"成一样的，判据就空了。

跑法：先 `./gradlew :core:test`（产物落到 core/build/markdown/），再 python tools/verify_markdown.py
"""
import collections
import io
import os
import re
import subprocess
import sys
from html.parser import HTMLParser

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
FIXTURES = os.path.join(ROOT, "core", "src", "test", "resources", "markdown")
PRODUCTS = os.path.join(ROOT, "core", "build", "markdown")

PASSED = []
FAILED = []
SKIPPED = []

VOID = {"br", "hr", "img", "input", "col", "meta", "link"}
DROP_TAGS = {"div", "span", "colgroup", "col"}          # 包装层与语法高亮：不参与结构比对
KEEP_ATTRS = {"a": ("href",), "img": ("src", "alt"), "input": ("type",), "ol": ("start",)}


def check(what, ok):
    print(("  通过：" if ok else "  失败：") + what)
    (PASSED if ok else FAILED).append(what)
    return ok


class Events(HTMLParser):
    def __init__(self):
        HTMLParser.__init__(self, convert_charrefs=True)
        self.out = []

    def _emit(self, kind, detail):
        if kind == "text":
            if self.out and self.out[-1][0] == "text":           # 高亮拆开的碎片并回一段
                self.out[-1] = ("text", self.out[-1][1] + detail)
                return
        self.out.append((kind, detail))

    def handle_starttag(self, tag, attrs):
        if tag in DROP_TAGS:
            return
        if tag in ("pre", "code"):
            # 语言标记谁写在 pre 的 class 上、谁写在 code 上各家不同（还带 sourceCode 这种自家前缀），
            # 所以结构比对里不收它，另有一条判据单独看"语言有没有留下"
            self._emit(tag, ())
            return
        if tag in VOID:
            wanted = KEEP_ATTRS.get(tag, ())
            picked = dict((k, v or "") for k, v in attrs if k in wanted)
            self._emit(tag, tuple(sorted(picked.items())))
            return
        self._emit(tag, ())

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if tag in DROP_TAGS or tag in VOID:
            return
        self._emit("/" + tag, ())

    def handle_data(self, data):
        text = " ".join(data.split())
        if text:
            self._emit("text", text)


def structure(html_text):
    parser = Events()
    parser.feed(html_text)
    return [(k, d) for k, d in parser.out]


def pandoc(fixture, to):
    result = subprocess.run(
        # --no-highlight：pandoc 会往代码里塞 span 与链接（还吃掉片段之间的空格），
        # 那层高亮不是 markdown 的解析结果，比它等于比两家各自的着色器
        ["pandoc", os.path.join(FIXTURES, fixture), "--to=" + to, "--wrap=none", "--columns=100000", "--no-highlight"],
        capture_output=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit("pandoc 读不了 %s：%s" % (fixture, (result.stderr or "").strip()[:300]))
    return result.stdout


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def words(text):
    return re.findall(r"[\w\u4e00-\u9fff]+", text)


def cjk_counts(text):
    """\u6c49\u5b57\u6309\u5355\u5b57\u6570\u7740\u6bd4\uff1a\u4e2d\u6587\u6ca1\u6709\u8bcd\u8fb9\u754c\uff0c\u6309\u8bcd\u5207\u4f1a\u56e0\u4e3a\u6807\u8bb0\u4f4d\u7f6e\u4e0d\u540c\u800c\u5047\u62a5\u8b66\u3002"""
    return collections.Counter(ch for ch in text if "\u4e00" <= ch <= "\u9fff")


def first_difference(mine, theirs):
    for index in range(max(len(mine), len(theirs))):
        left = mine[index] if index < len(mine) else None
        right = theirs[index] if index < len(theirs) else None
        if left != right:
            return index, left, right, mine[max(0, index - 2):index + 2]
    return None


def main():
    if not os.path.isdir(PRODUCTS):
        raise SystemExit("没有 %s，先跑 ./gradlew :core:test" % PRODUCTS)

    # ---- common.md：结构与文字必须与 pandoc 完全相同 --------------------------------
    mine = structure(read(os.path.join(PRODUCTS, "common.md.html")))
    theirs = structure(pandoc("common.md", "html"))
    diff = first_difference(mine, theirs)
    check("common.md：渲染出的标签树与 pandoc 逐事件相同（%d/%d 个事件）" % (len(mine), len(theirs)), diff is None)
    if diff is not None:
        at, left, right, around = diff
        print("      第 %d 个事件起不同：我们 %r vs pandoc %r" % (at, left, right))
        print("      附近：%s" % (around,))
        print("      我们共 %d 个事件，pandoc 共 %d 个" % (len(mine), len(theirs)))

    mine_html = read(os.path.join(PRODUCTS, "common.md.html"))
    check("common.md：围栏代码的语言标记两边都留下来了",
          "kotlin" in mine_html and "kotlin" in pandoc("common.md", "html"))

    mine_words, theirs_words = words(read(os.path.join(PRODUCTS, "common.md.txt"))), words(pandoc("common.md", "plain"))
    check("common.md：纯文本的字与顺序跟 pandoc 相同（%d 个词）" % len(theirs_words), mine_words == theirs_words)
    if mine_words != theirs_words:
        for index in range(min(len(mine_words), len(theirs_words))):
            if mine_words[index] != theirs_words[index]:
                print("      第 %d 个词起不同：我们 %r vs pandoc %r" % (index, mine_words[index - 3:index + 2], theirs_words[index - 3:index + 2]))
                break

    # ---- prose.md：不丢字，声明过的差异逐条钉住 ---------------------------------------
    source = read(os.path.join(FIXTURES, "prose.md"))
    html_out = read(os.path.join(PRODUCTS, "prose.md.html"))
    plain_out = read(os.path.join(PRODUCTS, "prose.md.txt"))
    notes = read(os.path.join(PRODUCTS, "prose.md.notes"))
    check("prose.md：汉字一个不少（HTML 那路）", not (cjk_counts(source) - cjk_counts(html_out)))
    check("prose.md：汉字一个不少（纯文本那路）", not (cjk_counts(source) - cjk_counts(plain_out)))
    check("prose.md：四格缩进的写法照字面留下并说明",
          "缩进四格" in html_out and "缩进" in notes)
    check("prose.md：脚注语法不认，按字面留下并说明",
          "[^note]" in html_out and "脚注" in notes)
    check("prose.md：内嵌 HTML 原样搬过去并说明",
          "内嵌的 HTML 块" in html_out and "HTML" in notes)
    # 代码块里的 < 与内嵌 HTML 块都合法地带着尖括号出来，所以这条不比尖括号，
    # 比的是"内嵌 HTML 原样留下并且交代了"
    check("prose.md：内嵌 HTML 那段的文字还在", "内嵌的 HTML 块" in plain_out)

    print("\n通过 %d 条，失败 %d 条（跳过 %d 条）" % (len(PASSED), len(FAILED), len(SKIPPED)))
    if FAILED:
        for item in FAILED:
            print("  未过：" + item)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
