#!/usr/bin/env python3
"""用 PyMuPDF 复核「PDF 改纸张」的产物。

产物由 app 测试 `PdfNewOpsTest` 写到 app/build/pdf-resize/：
  portrait-a5.pdf   A4 三页 → A5，等比留白
  landscape-a4.pdf  旋转 90 的页 → 横向 A4（页框仍是未旋转的 595x842，显示 842x595）

判据：
 1. 页框按目标纸张走（显示尺寸 A5 420x595、横向 A4 842x595）
 2. 文字层还在，不是把页面拍成图片
 3. 同一块文字的宽度按 420/595 缩放（不是只改了页框、内容没动）
"""
import os
import sys

import fitz

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "build", "pdf-resize")
failures = []


def check(label, ok, detail=""):
    print(("  通过  " if ok else "  失败  ") + label + (("：" + detail) if detail else ""))
    if not ok:
        failures.append(label)


def first_block(page):
    blocks = [b for b in page.get_text("blocks") if b[4].strip()]
    return blocks[0] if blocks else None


def main():
    if not os.path.isdir(DIR):
        print("找不到 %s，先跑 :app:testDebugUnitTest" % os.path.normpath(DIR))
        return 3
    portrait = fitz.open(os.path.join(DIR, "portrait-a5.pdf"))
    landscape = fitz.open(os.path.join(DIR, "landscape-a4.pdf"))

    p = portrait[0]
    check("A5 页框宽高", abs(p.rect.width - 420) < 2 and abs(p.rect.height - 595) < 2, str(p.rect))
    p_text = p.get_text().strip()
    check("A5 文字层还在", bool(p_text), p_text[:40])
    p_block = first_block(p)

    l = landscape[0]
    check("横向 A4 显示尺寸", abs(l.rect.width - 842) < 2 and abs(l.rect.height - 595) < 2, str(l.rect))
    l_text = l.get_text().strip()
    check("横向 A4 文字层还在", bool(l_text), l_text[:40])
    l_block = first_block(l)

    if p_block and l_block:
        pw = p_block[2] - p_block[0]
        lw = l_block[2] - l_block[0]
        ratio = pw / lw if lw else 0
        check("同一块文字按 420/595 缩放", abs(ratio - 420 / 595) < 0.02, "%.4f vs %.4f" % (ratio, 420 / 595))
        check("缩放后仍在页内", p_block[0] >= -1 and p_block[2] <= p.rect.width + 1, str(p_block[:4]))
    else:
        check("找得到文字块", False)

    portrait.close()
    landscape.close()
    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\nPyMuPDF 认可：页框、文字层与缩放比例都对。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
