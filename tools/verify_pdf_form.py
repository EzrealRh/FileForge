#!/usr/bin/env python3
"""用 PyMuPDF 复核 PDF 填表单的产物。

产物由 app 测试 `PdfNewOpsTest` 写到 app/build/pdf-form/：
  filled-flat.pdf      填 name/date/agree 并压平
  filled-editable.pdf  填 name/agree，保留可编辑

判据：
 1. 压平后没有控件，文字层能看到 Alice 与 2026-10-27
 2. 压平后的复选框区域比空白源文件更黑（真的画了勾）
 3. 可编辑模式字段值写回：name=Bob，agree=Yes
"""
import os
import sys

import fitz

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "app", "build", "pdf-form")
SOURCE = os.path.join(ROOT, "app", "src", "test", "resources", "pdf", "form.pdf")
failures = []


def check(label, ok, detail=""):
    print(("  通过  " if ok else "  失败  ") + label + (("：" + detail) if detail else ""))
    if not ok:
        failures.append(label)


def dark_pixels(page, rect):
    pix = page.get_pixmap(clip=rect, dpi=144, colorspace=fitz.csRGB)
    sample = pix.samples
    dark = 0
    for i in range(0, len(sample), 3):
        if sample[i] * 0.299 + sample[i + 1] * 0.587 + sample[i + 2] * 0.114 < 128:
            dark += 1
    return dark


def main():
    if not os.path.isdir(OUT):
        print("找不到 %s，先跑 :app:testDebugUnitTest" % os.path.normpath(OUT))
        return 3
    source = fitz.open(SOURCE)
    flat = fitz.open(os.path.join(OUT, "filled-flat.pdf"))
    editable = fitz.open(os.path.join(OUT, "filled-editable.pdf"))

    sp, fp, ep = source[0], flat[0], editable[0]
    check("源文件本来有控件（否则这条是空转）", len(list(sp.widgets())) == 3, "%d 个" % len(list(sp.widgets())))
    check("压平后没有控件", len(list(fp.widgets())) == 0, "%d 个" % len(list(fp.widgets())))
    text = fp.get_text()
    check("压平后文字层有 name", "Alice" in text, text.replace("\n", " | ")[:80])
    check("压平后文字层有 date", "2026-10-27" in text, text.replace("\n", " | ")[:80])

    agree_rect = next(w.rect for w in sp.widgets() if w.field_name == "agree")
    before = dark_pixels(sp, agree_rect)
    after = dark_pixels(fp, agree_rect)
    check("复选框区域比空白源更黑", after > before + 10, "dark %d → %d" % (before, after))

    values = {w.field_name: w.field_value for w in ep.widgets()}
    check("可编辑 name=Bob", values.get("name") == "Bob", str(values))
    check("可编辑 agree=Yes", values.get("agree") == "Yes", str(values))

    source.close()
    flat.close()
    editable.close()
    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\nPyMuPDF 认可：压平与可编辑两种填表结果都对。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
