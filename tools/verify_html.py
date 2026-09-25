#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿两个第三方实现复核 Kotlin 的 HTML 读取那一路。

1. **词法** 对比 Python 标准库的 `html.parser`：另一套容错 HTML 词法（浏览器之外最容易拿到的独立实现）。
   两边都归一成 S/T/E 三种事件流逐行比 —— 标签切分一旦不同，后面的结构全会歪，
   而"结构歪了"在成品文字上常常看不出来（少收一个 </p> 就是把两段并成一段）。
2. **语义** 对比 pandoc：同一份 HTML 抽出的纯文本，中文按字、拉丁按词，切出来的序列要与对方相同；
   再把我们写的 Markdown 交给 pandoc 转回纯文本，跟原文件的序列对照（一次真往返）。

跑法：先 `./gradlew :core:test`（产物落到 core/build/html/），再 python tools/verify_html.py
"""
import io
import json
import os
import re
import subprocess
import sys
from html.parser import HTMLParser

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
FIXTURES = os.path.join(ROOT, "core", "src", "test", "resources", "html")
PRODUCTS = os.path.join(ROOT, "core", "build", "html")

PASSED = []
FAILED = []


def check(what, ok):
    print(("  通过：" if ok else "  失败：") + what)
    (PASSED if ok else FAILED).append(what)
    return ok


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


class Trace(HTMLParser):
    """Python 那边的 token 流，整成与 Kotlin 轨迹同一个形状（S/E/T，注释与声明丢掉）。"""

    def __init__(self):
        HTMLParser.__init__(self, convert_charrefs=True)
        self.out = []
        self.raw = None

    def _text(self, data):
        # HTML 规范里的"空白"只算 ASCII 那几个（&nbsp; 解出来的 \xa0 不算，Java 的 trim/\s 也不把它当空白），
        # 所以这里不用 Python 的 split()——它比规范贪心，会把 &nbsp; 吃掉
        text = re.sub(r"[ \t\n\r\f\x0b]+", " ", data).strip(" \t\n\r\f\x0b")
        if not text:
            return
        if self.out and self.out[-1].startswith("T "):
            self.out[-1] += " " + text
        else:
            self.out.append("T " + text)

    def handle_starttag(self, tag, attrs):
        self.out.append("S " + tag)
        if tag in ("script", "style", "textarea", "title", "iframe", "noscript"):
            self.raw = tag

    def handle_startendtag(self, tag, attrs):
        self.out.append("S " + tag)

    def handle_endtag(self, tag):
        self.out.append("E " + tag)
        self.raw = None

    def handle_data(self, data):
        self._text(data)

    def handle_comment(self, data):
        pass

    def handle_decl(self, decl):
        pass

    def unknown_decl(self, data):
        pass


def python_trace(path):
    parser = Trace()
    parser.feed(read(path))
    parser.close()
    return parser.out


def mine_trace(name):
    path = os.path.join(PRODUCTS, name + ".tokens")
    if not os.path.isfile(path):
        raise SystemExit("没有 %s，先跑 ./gradlew :core:test" % path)
    return [line for line in read(path).split("\n") if line]


def first_diff(a, b):
    for index in range(max(len(a), len(b))):
        left = a[index] if index < len(a) else None
        right = b[index] if index < len(b) else None
        if left != right:
            return index, left, right
    return None


def pandoc(text, source_format, to):
    # --wrap=none：别让 pandoc 为了排版折行（它会把长串空白与破折号塞进输出，比对的是内容不是样子）
    result = subprocess.run(
        ["pandoc", "--from=" + source_format, "--to=" + to, "--wrap=none"],
        input=text.encode("utf-8"), capture_output=True,
    )
    if result.returncode != 0:
        raise SystemExit("pandoc 失败：" + result.stderr.decode("utf-8", "replace")[:300])
    return result.stdout.decode("utf-8")


def words(text):
    """中文按字切、拉丁按词切。

    两家在行内元素边界上加不加空格是排版选择（`…与` + `图注`），不是内容差异；
    切成字之后比的是**字有没有少、先后有没有乱** —— 那才是这轮要守的东西。
    """
    return re.findall(r"[A-Za-z0-9]+|[一-鿿]", text)


def cjk(text):
    return set(ch for ch in text if "一" <= ch <= "鿿")


def glyphs(text):
    """只留非空白字符：两家换行与对齐的空格摆法不同，字与标点本身才该一致。"""
    return [ch for ch in text if not ch.isspace()]


# pandoc 的内部表示里这些不算"结构"，比形状前先滤掉：
# - Str / Space / SoftBreak 是字与空白（源文件里的换行在 markdown 那边读成 Space、在 html 那边读成
#   SoftBreak，同一个空格换了个名字而已），内容差异由上面那条查
# - 再往后一堆是有序列表的编号样式、表格列宽这类**属性枚举**，两家给法不同，不影响读回来的样子
SHAPE_SKIP = (
    "Str", "Space", "SoftBreak",
    "Decimal", "Example", "LowerAlpha", "UpperAlpha", "LowerRoman", "UpperRoman",
    "OneParen", "Period", "DefaultStyle", "DefaultDelim",
    "AlignLeft", "AlignRight", "AlignCenter", "AlignDefault", "ColWidthDefault",
    "Meta", "MetaMap", "MetaInlines", "MetaString", "MetaBool", "MetaList",
)


def shape(text, source_format):
    """把 pandoc 的 AST 摊成一行一个节点名（带层叠缩进），只留结构。"""

    def walk(node, out, depth):
        if isinstance(node, dict) and "t" in node:
            name = node["t"]
            if name not in SHAPE_SKIP:
                out.append("  " * depth + name)
            below = depth if name in SHAPE_SKIP else depth + 1
            for child in node.get("c", []):
                walk(child, out, below)
        elif isinstance(node, dict):                        # 顶层的 {"meta":…,"blocks":…} 这类包装
            for child in node.values():
                walk(child, out, depth)
        elif isinstance(node, list):
            for child in node:
                walk(child, out, depth)

    out = []
    walk(json.loads(pandoc(text, source_format, "json")), out, 0)
    return out


def main():
    # ---- 词法：与 html.parser 逐事件相同 -------------------------------------------
    for name, note in (("clean.html", "结构正常的页"), ("dirty.html", "标签不闭合的脏页")):
        mine, theirs = mine_trace(name), python_trace(os.path.join(FIXTURES, name))
        diff = first_diff(mine, theirs)
        if diff is None:
            check("%s：词法轨迹与 html.parser 逐事件相同（%d 个事件，%s）" % (name, len(mine), note), True)
        else:
            at, left, right = diff
            check("%s：词法轨迹与 html.parser 相同" % name, False)
            print("      第 %d 个事件起不同：我们 %r vs html.parser %r" % (at, left, right))
            print("      我们共 %d 个事件，对方共 %d 个" % (len(mine), len(theirs)))
            for probe in range(max(0, at - 3), min(max(len(mine), len(theirs)), at + 6)):
                print("        %-3d M:%-28s P:%s" % (probe, mine[probe] if probe < len(mine) else "-",
                                                     theirs[probe] if probe < len(theirs) else "-"))

    # ---- 语义：与 pandoc 抽的纯文本同序 ------------------------------------------------
    source = read(os.path.join(FIXTURES, "clean.html"))
    plain = read(os.path.join(PRODUCTS, "clean.html.txt"))
    markdown_product = read(os.path.join(PRODUCTS, "clean.html.md"))
    ours = words(plain)
    ref_plain = pandoc(source, "html", "plain")
    theirs = words(ref_plain)
    check("clean.html：抽出的字与先后跟 pandoc 相同（%d 个）" % len(theirs), ours == theirs)
    if ours != theirs:
        only_ours = [w for w in ours if w not in theirs]
        only_theirs = [w for w in theirs if w not in ours]
        print("      只有我们有的：%s" % only_ours[:16])
        print("      只有 pandoc 有的：%s" % only_theirs[:16])
        for index in range(min(len(ours), len(theirs))):
            if ours[index] != theirs[index]:
                print("      第 %d 个词起不同：%s vs %s" % (index, ours[index - 2:index + 3], theirs[index - 2:index + 3]))
                break

    # 按字比之后看不见"列与列粘在一起"了（粘不粘字序都一样），表格与缩进各单独立一条
    rows = [("名称", "数量"), ("苹果", "3"), ("梨", "12")]
    glued = [row for row in rows if not re.search(row[0] + r"\s+" + row[1], plain)]
    check("clean.html：表格的列在纯文本里是分开的", not glued)
    if glued:
        print("      这几对单元格粘在了一起（中间没有空白）：%s" % glued)
    # 源文件里"换行 + 缩进"是写给编辑器看的，浏览器并成一个空格；不并就会把一排空格搬进正文
    collapsed = "， 还有" in plain and "， 还有" in markdown_product
    check("clean.html：源文件的换行加缩进并成了一个空格", collapsed)
    if not collapsed:
        print("      正文里没找到并好的「， 还有」：%s" % re.findall(r".{0,3}还有.{0,3}", plain)[:3])

    # ---- 往返：我们写的 Markdown 交给 pandoc 读回来，字与标点一个都不该多不该少 ----------
    # 这条是**转义**唯一的裁判：`*星号*` 没转义就会被读成着重标记，两个星号静悄悄消失。
    # 读的时候关掉 smart —— 把 `"` 换成弯引号是 pandoc 自己的排版动作，不是我们丢了字
    back = pandoc(markdown_product, "markdown-smart", "plain")
    check("clean.html：写成 Markdown 再读回来，字与标点按序完全相同（%d 个）" % len(glyphs(ref_plain)),
          glyphs(ref_plain) == glyphs(back))
    if glyphs(ref_plain) != glyphs(back):
        at = first_diff(glyphs(ref_plain), glyphs(back))
        print("      第 %d 个字起不同：pandoc 读 HTML 是 %s，读我们的 Markdown 是 %s" % (
            at[0], glyphs(ref_plain)[max(0, at[0] - 8):at[0] + 8], glyphs(back)[max(0, at[0] - 8):at[0] + 8]))

    # ---- 结构：我们写的 Markdown 与 pandoc 直接读 HTML，块与行内的形状要一样 -------------
    # 光比字序看不出"表格被拍平成一行字""标题掉级成段落""列表记号没了"—— 形状单独查一遍
    mine_shape = shape(markdown_product, "markdown")
    their_shape = shape(source, "html")
    diff = first_diff(mine_shape, their_shape)
    check("clean.html：写成 Markdown 后 pandoc 读出的结构与它直接读 HTML 相同（%d 个节点）" % len(their_shape),
          diff is None)
    if diff is not None:
        at = diff[0]
        print("      第 %d 个节点起不同：" % at)
        for probe in range(max(0, at - 2), min(max(len(mine_shape), len(their_shape)), at + 8)):
            print("        %-3d 我们 %-20s pandoc 读 HTML %s" % (
                probe,
                repr(mine_shape[probe]) if probe < len(mine_shape) else "-",
                repr(their_shape[probe]) if probe < len(their_shape) else "-"))

    # ---- 脏页：不丢字、该丢的说清楚 ----------------------------------------------------
    # 注释整段本来就该丢（下面单独查它没混进正文），比字之前先摘掉
    dirty_source = re.sub(r"<!--[\s\S]*?-->", "", read(os.path.join(FIXTURES, "dirty.html")))
    dirty_text = read(os.path.join(PRODUCTS, "dirty.html.txt"))
    dirty_notes = read(os.path.join(PRODUCTS, "dirty.html.notes"))
    check("dirty.html：正文汉字一个不少", cjk(dirty_source) - cjk(dirty_text) == set())
    check("dirty.html：不闭合被补了并说出来", "没按规矩闭合" in dirty_notes)
    check("dirty.html：未知的实体照字面留下并说出来",
          "&reallyunknown;" in dirty_text and "实体" in dirty_notes)
    check("dirty.html：样式表内容没混进正文", "color:red" not in dirty_text)
    check("dirty.html：textarea 里的原文留着", "草稿箱里的原文" in dirty_text)
    check("dirty.html：属性里的 & 没被当标签切断", "https://example.com/x?a=1&b=2" in read(os.path.join(PRODUCTS, "dirty.html.md")))

    print("\n通过 %d 条，失败 %d 条" % (len(PASSED), len(FAILED)))
    if FAILED:
        for item in FAILED:
            print("  未过：" + item)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
