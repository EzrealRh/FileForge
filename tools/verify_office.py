#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 pandoc 复核 Kotlin 抽出来的 OOXML 文本。

pandoc 自带一套完全独立的 docx / pptx 读取器（Haskell 写的），所以"同一份文件抽出同一批字"
这条判据不是我自己家的两套实现互相点头。

比对的单位是行。跟 pandoc 比的时候把行内空白整个去掉（**字**必须一字不差，
但段落里的制表符怎么落、块之间空几行是各家的排版规矩，那是声明过的差异，不是事故）；
跟自己家的期望比则连制表符一起对，因为那是界面与下游工具要吃掉的格式。

跑法：先 `./gradlew :core:test`（产物落到 core/build/office/），再 python tools/verify_office.py
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
FIXTURES = os.path.join(ROOT, "core", "src", "test", "resources", "office")
PRODUCTS = os.path.join(ROOT, "core", "build", "office")

PASSED = []
FAILED = []


def check(what, ok):
    print(("  通过：" if ok else "  失败：") + what)
    (PASSED if ok else FAILED).append(what)
    return ok


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def lines_of(text):
    """按行切，丢掉空行，行内空白折成单个空格。自家期望用这个。"""
    return [" ".join(line.split()) for line in text.replace("\r", "").split("\n") if line.strip()]


def chars_of(text):
    """按行切，丢掉空行，并把行内空白整个去掉 —— 与第三方比对只看字的先后。"""
    return ["".join(line.split()) for line in text.replace("\r", "").split("\n") if line.strip()]


def pandoc(fixture):
    result = subprocess.run(
        ["pandoc", os.path.join(FIXTURES, fixture), "--to=plain", "--columns=100000"],
        capture_output=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit("pandoc 读不了 %s：%s" % (fixture, (result.stderr or "").strip()[:300]))
    return result.stdout


def kotlin(name):
    return read(os.path.join(PRODUCTS, name + ".txt"))


def expect(name):
    return read(os.path.join(FIXTURES, name + ".expect"))


def order_of(lines, wanted):
    """wanted 这批字在这一侧的先后；缺任何一条就算失败。"""
    hits = []
    for text in wanted:
        at = [i for i, line in enumerate(lines) if text in line]
        if not at:
            return None
        hits.append((at[0], text))
    return [text for _, text in sorted(hits)]


def main():
    if not os.path.isdir(PRODUCTS):
        raise SystemExit("没有 %s，先跑 ./gradlew :core:test" % PRODUCTS)

    # ---- prose：段落必须三方一字不差（先后次序也算在内）-----------------------------
    mine, theirs = chars_of(kotlin("prose.docx")), chars_of(pandoc("prose.docx"))
    want = chars_of(expect("prose.docx"))
    check("prose.docx：Kotlin 与手写期望逐行相同（%d 段）" % len(want),
          lines_of(expect("prose.docx")) == lines_of(kotlin("prose.docx")))
    check("prose.docx：pandoc 与手写期望同一批字（%d 段）" % len(theirs), want == theirs)
    check("prose.docx：Kotlin 与 pandoc 抽出的字逐段相同（%d/%d 段）" % (len(mine), len(theirs)), mine == theirs)
    check("prose.docx：修订里删掉的那句没混进来（pandoc 同样没抽到）",
          all("删掉不该出现" not in line for line in mine + theirs))
    check("prose.docx：域代码本身没混进来，只留下算出来的 7",
          all("PAGE" not in line for line in mine + theirs) and "7" in mine and "7" in theirs)
    check("prose.docx：段内制表符 Kotlin 留住了（pandoc 的纯文本写法把它并掉了）",
          "第一批：甲\t乙" in kotlin("prose.docx") and "第一批：甲乙" in "".join(theirs))

    # ---- tables：自家规矩自己认，公共部分必须与 pandoc 一致 --------------------------
    mine, theirs = lines_of(kotlin("tables.docx")), lines_of(pandoc("tables.docx"))
    check("tables.docx：Kotlin 与手写期望逐行相同（%d 段）" % len(mine), lines_of(expect("tables.docx")) == mine)
    shared = ["前言", "带图的", "结尾"]
    check("tables.docx：两边都该收得到的段落逐条相同 %s" % shared,
          all(any(line == text for line in theirs) for text in shared) and all(text in mine for text in shared))
    check("tables.docx：表格 Kotlin 拍平成制表符、pandoc 画了网格线（两家各自成文）",
          "名称\t数量\t备注" in kotlin("tables.docx")
          and any(line and set(line) <= set("- ") for line in theirs))
    check("tables.docx：文本框的字 Kotlin 收了（pandoc 走 mc:Choice 的备用写法，不收它）",
          any("文本框里的字" in line for line in mine) and not any("文本框里的字" in line for line in theirs))

    # ---- deck：两页按页序，公共部分与 pandoc 一致 ------------------------------------
    mine, theirs = lines_of(kotlin("deck.pptx")), lines_of(pandoc("deck.pptx"))
    check("deck.pptx：Kotlin 与手写期望逐行相同（%d 段）" % len(mine), lines_of(expect("deck.pptx")) == mine)
    three = ["第一页标题", "副标题", "右下角备注"]
    check("deck.pptx：同一批字在两边的先后一致 %s" % three,
          order_of(mine, three) == three and order_of(theirs, three) == three)
    check("deck.pptx：页码域抽出来的 2 两边都在", "2" in mine and any("2" in line for line in theirs))
    check("deck.pptx：幻灯片里的表格 Kotlin 收得到（pandoc 的 pptx 读取器不认 a:tbl）",
          any("列一" in line for line in mine) and not any("列一" in line for line in theirs))

    print("\n通过 %d 条，失败 %d 条" % (len(PASSED), len(FAILED)))
    if FAILED:
        for item in FAILED:
            print("  未过：" + item)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
