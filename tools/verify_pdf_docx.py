#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿三个不相干的实现复核「PDF → Word」：pdfminer 量字号、pandoc 读结构、pdftotext 数文宇。

被复核的产物由这两步落盘（都要先跑）：
    python tools/make_pdf_fixtures.py                     # fitz 排版一份结构已知的夹具 + manifest
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar
    PDFPROBE_OUT=/d/fflogs/pdfprobe bash tools/pdfprobe/run.sh pdfdoc \\
        core/build/pdfdoc/structure.pdf core/build/pdfdoc  # 桌面版 PDFBox 读它，:core 判，落 docx

四条判据各自的分工：
 1) 量到的字号与位置是真的      —— pdfminer.six 自己按字符量，与我们行行对（差 >0.25pt 就红）
 2) 认出的块序列与声明一致      —— pandoc 读我们的 docx，AST 的块类型/层级/项数/开头逐块对
 3) 一个字都没丢               —— pandoc 的 plain 输出与 manifest 声明的正文按字符序列对
 4) 第三方也读得到这些字        —— pdftotext（poppler）出的文字里能找到每一块
"""
import io
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OUT = os.path.join(ROOT, "core", "build", "pdfdoc")
PDF = os.path.join(OUT, "structure.pdf")
DOCX = os.path.join(OUT, "structure.docx")
LINES = os.path.join(OUT, "lines.tsv")
MANIFEST = os.path.join(OUT, "manifest.json")
PDFTOTEXT = os.environ.get("PDFTOTEXT", "pdftotext")

results = []


def check(name, ok, detail=""):
    results.append((bool(ok), name, detail))


def run(cmd):
    return subprocess.run(cmd, capture_output=True, shell=True)


def scrub(text):
    """两家数文字时不算差异的排版噪音：空白、两种横线、以及被 Word 画掉的列表记号。"""
    return re.sub(r"[\s ­–—•·‣▪、-]", "", text)


def read_lines_tsv():
    rows = []
    for line in io.open(LINES, encoding="utf-8").read().splitlines():
        if not line.strip():
            continue
        page, top, left, size, bold, *rest = line.split("\t")
        rows.append({"page": int(page), "top": float(top), "left": float(left),
                     "size": float(size), "bold": bold == "1", "text": "\t".join(rest)})
    return rows


def _chars(element):
    """把 pdfminer 的布局树摊平成字符（各版本的行/框类名与取法都不一样，字符层是稳的）。"""
    from pdfminer.layout import LTChar

    for child in getattr(element, "_objs", []) or []:
        if isinstance(child, LTChar):
            yield child
        else:
            yield from _chars(child)


def pdfminer_lines():
    """pdfminer 自己按字符合成行：同一页、纵向距离 3pt 内算一行，取这行里最大的字号。"""
    from pdfminer.high_level import extract_pages

    boxes = defaultdict(lambda: [-1e9, -1e9, 0.0, []])
    for pno, layout in enumerate(extract_pages(PDF)):
        height = layout.height
        for char in _chars(layout):
            # pdfminer 的 y 从纸底往上量，PDFBox 的 yDirAdj 从纸顶往下量
            key = (pno, round(height - char.bbox[3], 1) // 3)
            box = boxes[key]
            box[0] = min(box[0], char.bbox[0])
            box[1] = max(box[1], height - char.bbox[3])
            box[2] = max(box[2], char.size)
            box[3].append(char.get_text())
    out = []
    for (page, _), (left, top, size, chars) in boxes.items():
        text = "".join(chars).strip()
        if text:
            out.append({"page": page, "top": top, "left": left, "size": size, "text": text})
    return sorted(out, key=lambda r: (r["page"], r["top"]))


def pandoc_ast(path, from_format):
    done = run('pandoc -f %s -t json "%s"' % (from_format, path))
    if done.returncode != 0:
        raise SystemExit("pandoc 读不动 %s：%s" % (path, done.stderr.decode("utf-8", "replace")[:300]))
    return json.loads(done.stdout.decode("utf-8"))


def block_of(node):
    """把一个 pandoc 块折成 (类型, 级别, 项数, 开头的字)。

    pandoc 3.x 里两种列表的摆法不同：BulletList 的 c 直接就是各项的列表，
    OrderedList 的 c 是 [编号起始与样式, 各项]。每项又是一个块列表。
    """
    kind = node["t"]
    if kind == "Header":
        return ("Header", node["c"][0], 1, "".join(glyphs(node["c"][2]))[:6])
    if kind in ("BulletList", "OrderedList"):
        items = node["c"] if kind == "BulletList" else node["c"][-1]
        first = items[0][0] if items and items[0] else {"t": "Plain", "c": []}
        return (kind, 0, len(items), "".join(glyphs(first.get("c", [])))[:6])
    content = node.get("c", [])
    return (kind, 0, 1, "".join(glyphs(content))[:6] if isinstance(content, list) else "")


def glyphs(value):
    """从 pandoc 的 AST 片段里抠出所有文字。

    Strong / Emphasis 的 c 是"块列表"，Para 的 c 是"行内列表"，Str 的 c 又是裸字符串 ——
    只按一种形状走会把加粗的字整个漏掉（第一次就漏在"2"那一段上）。
    """
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        kind = value.get("t")
        if kind in ("Str", "Code", "RawInline"):
            c = value["c"]
            yield c if isinstance(c, str) else c[-1]
        elif kind not in ("Img",):                       # Img 的 c 里带着 URL，别当正文
            for item in value.get("c", []):
                yield from glyphs(item)
    elif isinstance(value, list):
        for item in value:
            yield from glyphs(item)


def main():
    for path in (PDF, DOCX, LINES, MANIFEST):
        if not os.path.isfile(path):
            raise SystemExit("缺 %s —— 按脚本头部的三步先跑一遍" % path)
    newest_source = max(os.path.getmtime(p) for p in (
        PDF, os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "pdf", "PdfDoc.kt"),
        os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "fileforge", "converter", "engine",
                     "PdfLineScribe.kt"), os.path.join(HERE, "pdfprobe", "PdfProbe.java")))
    if os.path.getmtime(DOCX) < newest_source or os.path.getmtime(LINES) < newest_source:
        raise SystemExit("产物比源码还旧：这轮没测到（重跑 :core:jar 与 pdfprobe 的 pdfdoc 子命令）")

    manifest = json.loads(io.open(MANIFEST, encoding="utf-8").read())
    ours = read_lines_tsv()

    # ---- 1) 字号与位置量得对不对（pdfminer 独立量一遍同一份文件） --------------------------
    # 配行按"同一页 + 开头几个字相同"，位置只当辅助：两家的 y 一个从纸底量、一个从纸顶量，
    # 差的就是字形的高度，拿它当硬判据会把每一条都判成对不上
    theirs = pdfminer_lines()
    worst = None
    matched = 0
    for line in ours:
        key = scrub(line["text"])[:6]
        best = None
        for other in theirs:
            if other["page"] != line["page"] or scrub(other["text"])[:6] != key:
                continue
            drift = abs(other["top"] - line["top"])
            if best is None or drift < best[0]:
                best = (drift, other)
        if best is None:
            worst = worst or ("这一行 pdfminer 那边找不到：%s" % line["text"][:24])
            break
        matched += 1
        gap = abs(best[1]["size"] - line["size"])
        if gap > 0.25:
            worst = "字号对不上（%s：我们 %s pt，pdfminer %s pt）" % (
                line["text"][:16], line["size"], best[1]["size"])
            break
    check("每行的字号与位置与 pdfminer 独立量的一致（%d 行）" % len(ours),
          matched == len(ours) and worst is None, worst or "只配上 %d/%d 行" % (matched, len(ours)))
    check("量到的行数与声明的行的字数相符",
          abs(sum(len(l["text"]) for l in ours) - sum(len(l["text"]) for l in manifest["lines"])) < 20,
          "我们 %d 字，声明 %d 字" % (sum(len(l["text"]) for l in ours),
                                     sum(len(l["text"]) for l in manifest["lines"])))

    # ---- 2) pandoc 读回来的块序列 = 声明的那一份 -----------------------------------------
    ast = pandoc_ast(DOCX, "docx")["blocks"]
    mine = [block_of(node) for node in ast]
    want = [(b["kind"], b.get("level", 0), b["items"], b["head"]) for b in manifest["blocks"]]
    # 两边都取"去掉空白与记号后的前 6 个字符"：各家的块首排版不同，比前缀才比得下去
    mine_norm = [(k, lv if k == "Header" else 0, n, scrub(h)[:6]) for k, lv, n, h in mine]
    want_norm = [(k, lv if k == "Header" else 0, n, scrub(h)[:6]) for k, lv, n, h in want]
    diff = None
    if len(mine_norm) != len(want_norm):
        diff = "块数不同：pandoc 读出 %d 块，声明 %d 块" % (len(mine_norm), len(want_norm))
    else:
        bad = [(i, a, b) for i, (a, b) in enumerate(zip(mine_norm, want_norm)) if a != b]
        if bad:
            i, a, b = bad[0]
            diff = "第 %d 块不同：pandoc 读出 %s，声明要 %s" % (i + 1, a, b)
    check("pandoc 读回来的块序列与声明相同（%d 块）" % len(want_norm), diff is None, diff or "")

    # ---- 3) 一个字都没丢（按字符序列比）--------------------------------------------------
    plain = run('pandoc -f docx -t plain --wrap=none "%s"' % DOCX).stdout.decode("utf-8")
    # pandoc 的 plain 输出会给列表项补上 "- " 与 "1.  " 这样的排版记号 —— 那是它的写法不是我们的字
    plain = re.sub(r"(?m)^\s*(?:[-•·‣▪]\s+|\d{1,3}[.)、]\s+)", "", plain)
    expected = scrub("".join(b["expect"] for b in manifest["blocks"]))
    got = scrub(plain)
    at = next((i for i in range(min(len(expected), len(got))) if expected[i] != got[i]),
              min(len(expected), len(got)))
    check("成品里的字与声明逐序相同（%d 字）" % len(expected), expected == got,
          "第 %d 字起不同：期望 %s / 实得 %s" % (at, expected[at:at + 18], got[at:at + 18]))

    # ---- 6) 拉丁文的词与词之间该有空格、断词的横线该去掉（前五条都把空白抹平了，看不见这个） ----
    words = re.findall(r"[A-Za-z]{2,}", plain)
    want_words = re.findall(r"[A-Za-z]{2,}", "".join(b["expect"] for b in manifest["blocks"]))
    check("拉丁文的词形与声明相同（%d 个词，含断词拼回）" % len(want_words), words == want_words,
          "不同：实得 %s 期望 %s" % ([w for w in words if w not in want_words][:3],
                                    [w for w in want_words if w not in words][:3]))

    # ---- 5) poppler 也读得到这些字 ------------------------------------------------------
    txt = os.path.join(OUT, "poppler.txt")
    done = run('%s -enc UTF-8 "%s" "%s"' % (PDFTOTEXT, PDF, txt))
    if done.returncode != 0:
        check("pdftotext 读这份夹具", False, done.stderr.decode("utf-8", "replace")[:200])
    else:
        # poppler 会把相邻两行的列表项并成一行（"• 甲 • 乙"），所以按**声明的每一行**逐个找，
        # 不按整块找 —— 块里跨项的那部分文字在两边都不连续
        stripped = re.sub(r"(?m)^\s*(?:[-•·‣▪]\s*|\d{1,3}[.)、]\s*)", "", io.open(txt, encoding="utf-8").read())
        other = scrub(stripped)
        rows = [re.sub(r"^\s*(?:[-•·‣▪]\s*|\d{1,3}[.)、]\s*)", "", l["text"]) for l in manifest["lines"]]
        absent = [l["text"] for l, row in zip(manifest["lines"], rows) if scrub(row) not in other]
        check("每一行的文字在 pdftotext 的输出里也找得到（%d 行）" % len(manifest["lines"]),
              not absent, "找不到：%s" % absent[:3])

    failed = [item for item in results if not item[0]]
    for ok, name, detail in results:
        print("%s %s%s" % ("通过：" if ok else "未过：", name, ("  -> " + detail) if detail and not ok else ""))
    print("\n通过 %d 条，失败 %d 条" % (len(results) - len(failed), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
