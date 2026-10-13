"""产「PDF 元数据」要用的夹具：用 **PyMuPDF** 写一份带完整 /Info 的 PDF（不是 PDFBox 自己写的）。

为什么不让被检方自己造夹具：这一条判的是"PDFBox 改完 /Info 之后别人还读不读得对"，
如果连来料都是 PDFBox 写的，写侧与改侧同源，键怎么落的都没人核。
fitz 写的那份带 Title / Author / Subject / Keywords / Creator / Producer / 两个日期，
外加一个我们认不出的自定义键 Company —— 清除模式要连它一起删掉，这条最容易漏。

产物落在 core/build/pdfmeta/：meta.pdf（来料）与 cases.tsv（九份改写意图，判据按同样的意图驱动探针）。
"""

from __future__ import annotations

import os

import fitz

OUT = os.path.join("core", "build", "pdfmeta")

INFO = {
    "title": "季度报告",
    "author": "于某人",
    "subject": "内部资料",
    "keywords": "报表, 季度",
    "creator": "WordPad",
    "producer": "mupdf-1.27",
    "creationDate": "D:20230501083015+00'00'",
    "modDate": "D:20230602093000+00'00'",
}

# 每行：模式 / 留日期 / 盖时间 / 毫秒 / key=value...
CASES = [
    ["clear", "0", "0", "0"],
    ["clear", "1", "0", "0"],
    ["edit", "0", "0", "0", "Title=新标题"],
    ["edit", "0", "0", "0", "Author=", "Producer=换掉的"],
    ["edit", "0", "1", "1700000000000"],
    ["edit", "0", "0", "0"],
    ["clear", "0", "1", "1700000000000"],
    ["edit", "0", "0", "0", "Subject=" + "长" * 460],
    ["clear-standard", "0", "0", "0"],
]


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    doc = fitz.open()
    for page_index in range(2):
        page = doc.new_page()
        page.insert_text((72, 120), "元数据测试 第 %d 页 内容不参与改动" % (page_index + 1), fontname="china-s", fontsize=14)
    doc.set_metadata(INFO)
    # 自定义键：fitz 直接改 /Info 那个字典，PDFBox 那边不认识它，正好测"只删认识的"这种半成品
    info_xref = doc.pdf_catalog()  # catalog 的 xref，/Info 在 trailer 上，另找
    trailer_info = doc.xref_get_key(-1, "Info")
    if trailer_info[0] == "xref":
        handle = int(trailer_info[1].split()[0])
        doc.xref_set_key(handle, "Company", "(Acme 内部)")
    else:
        raise SystemExit("没找到 /Info 的引用：%s —— 夹具的写法要改" % (trailer_info,))
    doc.set_toc([[1, "第一章", 1]])
    doc.save(os.path.join(OUT, "meta.pdf"), deflate=True, garbage=0)
    doc.close()
    with open(os.path.join(OUT, "cases.tsv"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join("\t".join(case) for case in CASES) + "\n")
    back = fitz.open(os.path.join(OUT, "meta.pdf"))
    print("meta.pdf：%d 页，/Info 里 %d 项，自定义 Company=%r"
          % (back.page_count, len([k for k in back.metadata if back.metadata[k]]),
             back.xref_get_key(back.pdf_catalog(), "Info")[0]))
    back.close()


if __name__ == "__main__":
    main()
