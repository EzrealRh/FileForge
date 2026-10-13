"""产「文档树印成 PDF」要用的夹具：一份 Markdown 由 **pandoc 写成四种格式**，纸上该长什么样按这份来源算。

为什么不手拼包：这四种文件由 pandoc 的写者产出（Word 认的 OOXML、LibreOffice 认的 ODF、
演示稿的 PresentationML、WordPad 认的 RTF），我们的读法读的是**别人写的**文件 ——
自己拼的夹具只会测到"我们写的我们能读"，测不到真文件里那些我们不认识的写法。
纸上的内容、列数、记号、字号由 `brief.md` 那份来源说了算，读回来用 pdfplumber 的坐标。

产物落在 core/build/pdfdocs/：brief.md / brief.docx / brief.odt / brief.pptx / brief.rtf
外加 truth.json（来源里定义好的那批期望，判据脚本读它而不是重新猜一遍）。
"""

from __future__ import annotations

import io
import json
import os
import subprocess

OUT = os.path.join("core", "build", "pdfdocs")

# 判据按来源分组：表格与字号六类都判；列表记号只判"文件里声明了列表"的那几类
STRUCTURED = ["docx", "odt", "pptx", "rtf", "md", "html"]
LINED = ["docx", "odt", "md", "html"]

# 来源：标题两级、正文、圆点列表、编号列表（带一层子项）、引用、一张带表头的表、一段代码
BRIEF = """# 一级标题

## 二级标题

正文一段，用来量字号与位置。

- 圆点甲
- 圆点乙
- 圆点丙

1. 编号一
2. 编号二
    1. 子项一
    2. 子项二
3. 编号三

> 引用一句话

| 列甲 | 列乙 | 列丙 |
|---|---|---|
| 格子一 | 格子二 | 格子三 |
| 格子四 | 格子五 | 格子六 |

```text
代码一行
```
"""

TRUTH = {
    "headings": ["一级标题", "二级标题"],
    "body": "正文一段，用来量字号与位置。",
    "bullets": ["圆点甲", "圆点乙", "圆点丙"],
    "numbered": ["编号一", "编号二", "子项一", "子项二", "编号三"],
    # 编号按层各数各的：父项进到 2 时子层从 1 重起，回到父层接着数
    "number_marks": ["1.", "2.", "1.", "2.", "3."],
    "quote": "引用一句话",
    "code": "代码一行",
    "table": {
        "columns": 3,
        "rows": [
            ["列甲", "列乙", "列丙"],
            ["格子一", "格子二", "格子三"],
            ["格子四", "格子五", "格子六"],
        ],
    },
    # 纸上没有的东西：来源里的记号（#、-、>、|、```）不许漏到纸上
    "forbidden": ["#", "|", "```", ">"],
    "sources": ["docx", "odt", "pptx", "rtf", "md", "html"],
    "with_markers": LINED,
    "structured": STRUCTURED,
}


def io_write(target, text):
    with io.open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    md = os.path.join(OUT, "brief.md")
    with open(md, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(BRIEF)
    for kind in TRUTH["sources"]:
        target = os.path.join(OUT, "brief.%s" % kind)
        if kind == "md":
            io_write(target, BRIEF)
            continue
        run = subprocess.run(["pandoc", md, "-o", target], capture_output=True, text=True)
        if run.returncode != 0 or not os.path.isfile(target):
            raise SystemExit("pandoc 写 %s 失败：%s" % (kind, (run.stderr or "").strip()[:200]))
        print("brief.%s：%d 字节（pandoc 写的）" % (kind, os.path.getsize(target)))
    with open(os.path.join(OUT, "truth.json"), "w", encoding="utf-8") as handle:
        json.dump(TRUTH, handle, ensure_ascii=False, indent=1)
    print("truth.json 写好了：%d 项期望" % len(TRUTH))


if __name__ == "__main__":
    main()
