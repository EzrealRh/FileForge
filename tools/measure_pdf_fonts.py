#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""量一件事：真 PDF 里"字号与粗细"长什么样，好把 PdfDoc 那几个阈值定在实测值上。

只读字号、位置与字体名（不读内容），每份文件按字号档位出一行统计：
  占比（按字）、这一档的行有多长（中位/最长）、字体名里有没有 heavy/bold 字样。
挑阈值要同时看这三样：正文里夹的拉丁文常比中文大 0.5pt（占比不小），
真标题是"稀有的大档 + 短行"；而 /Flags 里的 Bold 位在中文文件里基本不可信（宋体会被标粗）。

跑法：python tools/measure_pdf_fonts.py <pdf ...>
"""
import io
import re
import statistics
import sys
from collections import Counter, defaultdict

import fitz

HEAVY = re.compile(r"(bold|black|heavy|hei|yahei|黑|粗)", re.I)


def bucket(size):
    return round(size / 0.5) * 0.5


def report(path):
    doc = fitz.open(path)
    chars = Counter()
    lines_by_bucket = defaultdict(list)
    heavy_by_bucket = Counter()
    for page in doc:
        for block in page.get_text("dict").get("blocks", []):
            for line in block.get("lines", []):
                spans = [s for s in line.get("spans", []) if s.get("text", "").strip()]
                if not spans:
                    continue
                size = max(bucket(s["size"]) for s in spans)
                text = "".join(s["text"] for s in spans).strip()
                chars[size] += len(text)
                lines_by_bucket[size].append(len(text))
                if any(HEAVY.search(s.get("font", "")) for s in spans):
                    heavy_by_bucket[size] += len(text)
    total = sum(chars.values()) or 1
    print("=== %s  %d 字" % (path.split("/")[-1][:44], total))
    for size in sorted(chars, reverse=True):
        share = chars[size] * 100.0 / total
        lengths = lines_by_bucket[size]
        print("   %5.1fpt  %5.1f%%  行中位 %3d 最长 %3d  名里带粗 %4.1f%%" % (
            size, share, int(statistics.median(lengths)), max(lengths),
            heavy_by_bucket[size] * 100.0 / max(1, chars[size])))
    doc.close()


def main():
    for path in sys.argv[1:]:
        try:
            report(path)
        except Exception as bad:  # noqa: BLE001 - 一份读不动别把整批带走
            print("=== %s 读不动：%s" % (path.split("/")[-1][:44], bad))
    return 0


if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.exit(main())
