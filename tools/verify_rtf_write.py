#!/usr/bin/env python3
r"""
判 Kotlin 写出去的 .rtf（core/office/RtfWrite.kt）。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests "com.fileforge.core.RtfWriteTest" --rerun
    python tools/verify_rtf_write.py

思路与 verify_odt_write 同一族：同一棵文档树，两条路写出去 —— `.rtf`（这条要判的）与 `.md`
（那条早被 pandoc 逐块对过）。让 **pandoc 的 RTF 读者**读我们的 .rtf、**pandoc 的 Markdown 读者**
读我们的 .md，两边读出来的结构必须是同一个东西。pandoc 不读我们的 Kotlin，两边独立，
所以"只有自家读得懂"的写法（记号写进文字、层级凭样式号猜、地址丢了）在这条判据下过不去。

pandoc 的 RTF 读者有三处读不出来（读入那轮量出来的，写出这轮同样成立），那几处另配判据：
  - 列表结构（一个列表块都不建）→ 由自家读法读回来判（第 10 条）
  - `\trhdr` 表头（表头行永远不算 head）→ 由写出的字节与自家读法判（第 9 条）
  - 代理对（两个 \u 拼不回一个字）→ 由写出的字节与交代判（第 14 条，基本平面以外的字不进对照夹具）

十五条判据：
  1  三份夹具的产物都在且非空
  2  字节全 ASCII，且以 {\rtf1 开头（自家 FileKind 认得出的那一手）
  3  pandoc 读得动三份 .rtf（读崩就是不合规的 RTF）
  4  块与逐字记号：pandoc 读 .rtf == pandoc 读 .md —— 归一化三处已声明的差（列表摊平、
     引用摊平、分隔线不比），其余块与每个字上的记号必须一致
  5  标题层级一致（几级、标题文字）
  6  代码块两边都是 CodeBlock，两行都在
  7  链接地址集合一致，带 & 与 = 的不被改写
  8  表整张格子逐格对（短了的行按声明的列数补齐，pandoc 读回来的就是补过的）
  9  表头：\trhdr 恰好写一次、自家读法认出表头、pandoc 那边表头恒空（量出来的短板）
 10  列表：自家读法读回来圆点/编号与层号各就各位，pandoc 的正文里没有记号字符
 11  自家读法一圈：读回来的树 == 原树减去三处声明的差（引用→普通段、代码段样式→等宽记号、分隔线不收）
 12  中文与特殊字读得回（\uN 解码是对的）
 13  字面 { } \ ~ 照字面（转义写得对，两家的读者都读回原字）
 14  书名写进 {\info{\title}}，pandoc 读 meta 读得回
 15  交代：分隔线、引用、等宽、补齐这些去处在说明里都有
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from verify_epub_write import norm  # noqa: E402
from verify_odt_read import (  # noqa: E402
    check,
    plain,
    results,
    table_rows,
)

BUILD = os.path.join("core", "build", "rtfwrite")
STEMS = ("book", "grid", "east")
MARK = {"Strong": "b", "Emph": "i", "Strikeout": "s", "Underline": "u", "Code": "c", "Link": "l"}


def load(name: str) -> bytes:
    with open(os.path.join(BUILD, name), "rb") as handle:
        return handle.read()


def read(name: str) -> str:
    with open(os.path.join(BUILD, name), encoding="utf-8") as handle:
        return handle.read()


def ast_of(fmt: str, name: str) -> dict:
    run = subprocess.run(
        ["pandoc", "-f", fmt, "-t", "json", "--wrap=none", os.path.join(BUILD, name)],
        capture_output=True, text=True, encoding="utf-8",
    )
    if run.returncode != 0:
        raise RuntimeError("pandoc 读 %s 失败：%s" % (name, run.stderr.strip()[:200]))
    return json.loads(run.stdout)


def kids(node) -> list:
    """pandoc 3.x 的 c 里夹着 attr 与宽度这些非节点字段，只留节点。"""
    out = []
    content = node.get("c")
    if isinstance(content, list):
        for item in content:
            if isinstance(item, dict):
                out.append(item)
            elif isinstance(item, list):
                out.extend([x for x in item if isinstance(x, dict)])
    return out


def inline(chars: list, marks: set, sink: list) -> None:
    """每个字一行，前缀是包住它的记号集合 —— 两家合并相邻同格式文字的习惯不同，按字切最稳。"""
    for node in chars:
        if not isinstance(node, dict):
            continue
        kind = node.get("t")
        if kind == "Str":
            value = node.get("c")
            value = value if isinstance(value, str) else value[0]
            for ch in value:
                sink.append("%s|%s" % ("".join(sorted(marks)), ch))
        elif kind in ("Space", "SoftBreak", "LineBreak"):
            sink.append("·|%s" % "".join(sorted(marks)))
        elif kind in MARK:
            inline(kids(node), marks | {MARK[kind]}, sink)
        else:
            inline(kids(node), marks, sink)


def shape_of(ast: dict) -> list:
    """归一化后的块序列 + 每个字一行。

    三处**量出来的**差在这里抹平（都是 pandoc 的 RTF 读者表达不了、写出侧也表达不了的东西）：
    列表与引用摊平成里面的段、分隔线不比。表只出块名，格子由第 8 条逐格判。
    """
    out = []

    def walk(blocks: list) -> None:
        for block in blocks:
            if not isinstance(block, dict):
                continue
            kind = block.get("t")
            if kind == "Header":
                out.append("Header%s" % block["c"][0])
                inline([x for x in block["c"][2] if isinstance(x, dict)], set(), out)
            elif kind in ("Para", "Plain"):
                out.append("Para")
                inline(block.get("c") if isinstance(block.get("c"), list) else [], set(), out)
            elif kind == "BlockQuote":
                walk(kids(block))
            elif kind in ("BulletList", "OrderedList"):
                payload = block.get("c", [])
                # pandoc 3.x：BulletList 的 c 直接是"项"（每项一块列表）；
                # OrderedList 的 c 前面多一个 [起始, 样式, 分隔] 的属性（头一位是整数）
                if payload and isinstance(payload[0], list) and payload[0] and isinstance(payload[0][0], int):
                    items = payload[1] if len(payload) > 1 else []
                else:
                    items = payload
                for item in items:
                    walk(item)
            elif kind == "CodeBlock":
                out.append("CodeBlock")
                body = block.get("c")
                text = body[1] if isinstance(body, list) and len(body) > 1 and isinstance(body[1], str) else ""
                for ch in text:
                    if ch == "\n":
                        out.append("·|")
                    else:
                        out.append("c|%s" % ch)
            elif kind == "Table":
                out.append("Table")
            elif kind == "HorizontalRule":
                continue

    walk(ast.get("blocks", []))
    return out


def head_row_counts(ast: dict) -> list:
    out = []
    for block in ast.get("blocks", []):
        if block.get("t") != "Table":
            continue
        content = block["c"]
        head = (content[3] or [[], []])[1] or [] if len(content) > 3 else []
        out.append(len(head))
    return out


def mapped_shape(lines: list) -> list:
    """原树形状 → 写读两边都认账的期望：引用→普通段、代码段样式→等宽记号、分隔线不收；
    表的短行按最宽补齐（写出侧就是这么声明的）。"""
    out = []
    for line in lines:
        if line == "分隔线":
            continue
        made = re.match(r"^表 (\d+)行×(\d+)列 表头=(true|false) (.*)$", line)
        if made:
            width = int(made.group(2))
            rows = [row.split(",") for row in made.group(4).split("/")]
            rows = [row + [""] * (width - len(row)) for row in rows]
            out.append("表 %s行×%s列 表头=%s %s" % (
                made.group(1), made.group(2), made.group(3),
                "/".join(",".join(row) for row in rows),
            ))
            continue
        if line.startswith("SourceCode"):
            line = "Body[c]" + line[len("SourceCode[]"):] if "SourceCode[]" in line else "Body" + line[len("SourceCode"):]
        elif line.startswith("Quote"):
            line = "Body" + line[len("Quote"):]
        out.append(line)
    return out


def safe_ast(fmt: str, name: str):
    try:
        return ast_of(fmt, name)
    except Exception as error:  # noqa: BLE001
        print("     pandoc 读 %s 崩了：%s" % (name, str(error)[:160]))
        return None


def main() -> int:
    missing = [name for stem in STEMS for name in (
        "%s.rtf" % stem, "%s.md" % stem, "%s.shape.txt" % stem, "%s.back.txt" % stem, "%s.notes.txt" % stem)
        if not os.path.isfile(os.path.join(BUILD, name)) or os.path.getsize(os.path.join(BUILD, name)) == 0]
    check(1, "三份夹具的产物都在且非空", not missing, "缺：%s" % missing)
    if missing:
        print("先跑：GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests com.fileforge.core.RtfWriteTest --rerun")
        return finish()

    # 2 字节全 ASCII 且认得出魔数
    for stem in STEMS:
        raw = load("%s.rtf" % stem)
        head = raw[:6].decode("ascii", "replace")
        check(2, "%s.rtf：字节全 ASCII（非 ASCII 一律 \\uN）且以 {\\rtf1 开头" % stem,
              all(byte <= 0x7F for byte in raw) and head == "{\\rtf1",
              "头六个字节 %r；超出 ASCII 的字节 %d 个" % (head, sum(1 for byte in raw if byte > 0x7F)))

    # 3 pandoc 读得动
    rtf_asts, md_asts = {}, {}
    for stem in STEMS:
        rtf_asts[stem] = safe_ast("rtf", "%s.rtf" % stem)
        md_asts[stem] = safe_ast("markdown", "%s.md" % stem)
    check(3, "pandoc 读得动三份 .rtf（也读得动同内容的 .md）",
          all(rtf_asts[stem] and md_asts[stem] for stem in STEMS))
    if not all(rtf_asts[stem] for stem in STEMS):
        return finish()

    # 4 块与逐字记号
    for stem in STEMS:
        theirs, ours = shape_of(md_asts[stem]), shape_of(rtf_asts[stem])
        diff = next((i for i in range(max(len(theirs), len(ours)))
                     if (theirs[i] if i < len(theirs) else None) != (ours[i] if i < len(ours) else None)), None)
        check(4, "%s：pandoc 读 .rtf 与读 .md 块与逐字记号一致（%d 行）" % (stem, len(theirs)),
              diff is None,
              "" if diff is None else "第 %d 行起不同：md %r vs rtf %r\n  附近 md：%s\n  附近 rtf：%s" % (
                  diff,
                  theirs[diff] if diff < len(theirs) else "-", ours[diff] if diff < len(ours) else "-",
                  theirs[max(0, diff - 2):diff + 4], ours[max(0, diff - 2):diff + 4]))

    # 5 标题层级
    theirs, ours = head_levels(md_asts["book"]), head_levels(rtf_asts["book"])
    check(5, "标题层级一致（几级、标题文字）", theirs == ours and theirs,
          "md %s vs rtf %s" % (theirs, ours))

    # 6 代码块
    def code_blocks(ast: dict) -> list:
        return [norm(plain(block)) for block in ast["blocks"] if block.get("t") == "CodeBlock"]

    theirs, ours = code_blocks(md_asts["book"]), code_blocks(rtf_asts["book"])
    check(6, "代码两边都是 CodeBlock 且两行都在", theirs == ours and len(ours) == 1 and
          "代码第一行" in ours[0] and "代码第二行" in ours[0],
          "md %s vs rtf %s" % (theirs, ours))

    # 7 链接
    theirs, ours = link_urls(md_asts["book"]), link_urls(rtf_asts["book"])
    check(7, "链接地址集合一致且不被改写", theirs == ours and
          "https://example.com/x?a=1&b=2" in ours,
          "md %s vs rtf %s" % (theirs, ours))

    # 8 表整张格子（含补齐）
    theirs, ours = table_rows(md_asts["grid"]), table_rows(rtf_asts["grid"])
    padded = ours and len(ours[0]) == 3 and ours[0][2] == ["11", "", ""]
    check(8, "表整张格子逐格对（短了的行按声明的列数补齐）", theirs == ours and bool(ours) and padded,
          "md %s vs rtf %s" % (theirs, ours))

    # 9 表头
    book_raw = load("book.rtf").decode("ascii")
    grid_raw = load("grid.rtf").decode("ascii")
    back = read("book.back.txt").splitlines()
    check(9, "表头：\\trhdr 恰好一次、自家读法认出、pandoc 的 RTF 表头恒空（量出来的短板）",
          book_raw.count("\\trhdr") == 1 and grid_raw.count("\\trhdr") == 1 and
          "表头=true" in " ".join(back) and
          head_row_counts(rtf_asts["book"]) == [0] and head_row_counts(md_asts["book"]) == [1],
          "字节里 trhdr：%d/%d；pandoc 的表头行 rtf %s vs md %s" % (
              book_raw.count("\\trhdr"), grid_raw.count("\\trhdr"),
              head_row_counts(rtf_asts["book"]), head_row_counts(md_asts["book"])))

    # 10 列表（pandoc 建不出列表块 —— 由自家读法判结构，pandoc 只判"记号没混进正文"）
    items = [line for line in back if line.startswith("ListParagraph")]
    prose = plain(rtf_asts["book"]["blocks"])
    list_ok = (len(items) == 5 and
               [("层0", "圆点"), ("层0", "圆点"), ("层1", "圆点"), ("层0", "编号"), ("层0", "编号")] ==
               [(line.split(" ")[1] if " " in line else "", "圆点" if "圆点" in line else "编号") for line in items] and
               "•" not in prose and not re.search(r"^\s*[0-9]+[.)] ", prose, re.M))
    check(10, "列表：自家读法读回圆点/编号与层号各就各位（5 条），记号没混进 pandoc 的正文",
          list_ok, "列表段：%s；记号在正文里：%s" % (items, "•" in prose))

    # 11 自家读法一圈
    for stem in STEMS:
        want = mapped_shape(read("%s.shape.txt" % stem).splitlines())
        got = read("%s.back.txt" % stem).splitlines()
        diff = next((i for i in range(max(len(want), len(got)))
                     if (want[i] if i < len(want) else None) != (got[i] if i < len(got) else None)), None)
        check(11, "%s：自家读法读回来的树 == 原树减三处声明的差（%d 块）" % (stem, len(want)),
              diff is None,
              "" if diff is None else "第 %d 行起不同：原树 %r vs 读回 %r" % (
                  diff, want[diff] if diff < len(want) else "-", got[diff] if diff < len(got) else "-"))

    # 12 中文读回（两家读者都要读得回：pandoc 那份，自家读法读回来的这份）
    east = plain(rtf_asts["east"]["blocks"])
    east_back = read("east.back.txt")
    check(12, "中文与特殊字读得回（\\uN 解码是对的：pandoc 与自家读法都在）",
          all(word in east for word in ("你好，世界", "“引号”", "—破折号…省略号")) and
          "你好，世界" in east_back,
          "pandoc 读到的：%s；自家读法读到的里有你好吗：%s" % (east[:80], "你好，世界" in east_back))

    # 13 字面 { } \ ~
    book_text = plain(rtf_asts["book"]["blocks"])
    check(13, "字面 { } \\ ~ 照字面（转义写得对，pandoc 读回原字）",
          "\\{" in book_raw and "\\\\" in book_raw and "\\u126 ?" in book_raw and
          "花括号{与反斜杠\\与波浪~都照字面" in norm(book_text),
          "字节里有转义：%s/%s；pandoc 读到：%s" % (
              "\\{" in book_raw, "\\\\" in book_raw,
              norm(book_text)[norm(book_text).find("花括号"):norm(book_text).find("花括号") + 16]))

    # 14 书名
    meta = rtf_asts["book"].get("meta", {}).get("title", {})
    title = norm(plain(meta.get("c", []))) if isinstance(meta, dict) else ""
    check(14, "书名写进 {\\info{\\title}}，pandoc 读 meta 读得回", title == "简报", "读到 %r" % title)

    # 15 交代
    notes = "\n".join(read("%s.notes.txt" % stem) for stem in STEMS)
    need = ["分隔线", "引用", "等宽", "补空格子"]
    check(15, "交代：分隔线、引用、等宽、补齐的去处在说明里都有",
          all(word in notes for word in need), "缺：%s" % [word for word in need if word not in notes])

    return finish()


def head_levels(ast: dict) -> list:
    out = []
    for block in ast.get("blocks", []):
        if block.get("t") != "Header":
            continue
        content = block["c"]
        out.append((content[0], norm(plain(content[2] if len(content) > 2 else []))))
    return out


def link_urls(ast: dict) -> list:
    out = []

    def walk(node):
        if isinstance(node, dict):
            if node.get("t") == "Link":
                content = node.get("c")
                if isinstance(content, list) and len(content) > 2 and isinstance(content[2], list) and content[2]:
                    target = content[2][0]
                    if isinstance(target, str):
                        out.append(target)
            walk(node.get("c"))
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(ast.get("blocks", []))
    return sorted(set(out))


def finish() -> int:
    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    for number, name, _ in failed:
        print("  红的是第 %d 条：%s" % (number, name))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
