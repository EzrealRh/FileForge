#!/usr/bin/env python3
"""
生成 ODS 读入这一侧的夹具：三份手写真 .ods。

为什么手写而不是找个库生成：本机没有 LibreOffice，pandoc 也不读 .ods，
唯一能当"第三方实现"用的是 odfpy —— 而 release 的判据链不该挂在一个可有可无的包上。
所以分工是：
  - **夹具由本脚本按 ODF 的规矩手写**（LibreOffice 真会写的那些形状：整页的重复空行、
    跨列格与 covered 占位、公式存着的结果、`text:s` 的多个空格、只存数值没存渲染文字）
  - **每份写完立刻用 odfpy 读回来逐格对一遍**：它读不出、或读出的值与预期不符，
    就说明这份夹具不是真 ODF —— 当场报错，不把"只有自己读得懂的文件"留给判据
  - 判据（tools/verify_ods.py）不依赖 odfpy：用标准库 ElementTree 独立展开网格

需要 odfpy（只在这一步）：
    python -m pip install --target D:/tmp/pylibs odfpy
    ODFPY_PATH=D:/tmp/pylibs python tools/make_ods_fixtures.py

产物落在 core/src/test/resources/odsread/。
"""
from __future__ import annotations

import io
import os
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

OUT = os.path.join("core", "src", "test", "resources", "odsread")
MIME = "application/vnd.oasis.opendocument.spreadsheet"
DECL = '<?xml version="1.0" encoding="UTF-8"?>'
NS = (
    'xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
    'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
    'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"'
)
MANIFEST = (
    DECL
    + '<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">'
    + '<manifest:file-entry manifest:full-path="/" manifest:media-type="' + MIME + '"/>'
    + '<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>'
    + "</manifest:manifest>"
)

# 账目表：表头 + 六种值类型 + 跨列格 + covered 占位 + 空格与制表 + 公式 + 一张重名的第二表
LEDGER = """<table:table table:name="工资/月度">
 <table:table-column table:number-columns-repeated="4"/>
 <table:table-header-rows>
  <table:table-row>
   <table:table-cell office:value-type="string"><text:p>项目</text:p></table:table-cell>
   <table:table-cell office:value-type="string"><text:p>金额</text:p></table:table-cell>
   <table:table-cell office:value-type="string"><text:p>占比</text:p></table:table-cell>
   <table:table-cell office:value-type="string"><text:p>备注</text:p></table:table-cell>
  </table:table-row>
 </table:table-header-rows>
 <table:table-row>
  <table:table-cell office:value-type="string"><text:p>房租</text:p></table:table-cell>
  <table:table-cell office:value-type="float" office:value="1500"><text:p>1500</text:p></table:table-cell>
  <table:table-cell office:value-type="percentage" office:value="0.12345"><text:p>12.345%</text:p></table:table-cell>
  <table:table-cell office:value-type="string"><text:p>月初<text:s/><text:s text:c="3"/>付<text:tab/>现金</text:p></table:table-cell>
 </table:table-row>
 <table:table-row>
  <table:table-cell office:value-type="string"><text:p>水费</text:p></table:table-cell>
  <table:table-cell office:value-type="currency" office:currency="CNY" office:value="88.50"><text:p>￥88.50</text:p></table:table-cell>
  <table:table-cell office:value-type="date" office:date-value="2026-03-01"><text:p>2026-03-01</text:p></table:table-cell>
  <table:table-cell office:value-type="time" office:time-value="PT00H05M30S"><text:p>00:05:30</text:p></table:table-cell>
 </table:table-row>
 <table:table-row>
  <table:table-cell office:value-type="boolean" office:boolean-value="true"><text:p>TRUE</text:p></table:table-cell>
  <table:table-cell table:formula="of:=SUM([.B2:.B3])" office:value-type="float" office:value="1588.5"><text:p>1588.5</text:p></table:table-cell>
  <table:table-cell><text:p>跨两列的一格</text:p></table:table-cell>
  <table:covered-table-cell/>
 </table:table-row>
 <table:table-row>
  <table:table-cell office:value-type="string"><text:p>补一行短的</text:p></table:table-cell>
 </table:table-row>
</table:table>
<table:table table:name="工资/月度">
 <table:table-column/>
 <table:table-row><table:table-cell office:value-type="string"><text:p>只有甲</text:p></table:table-cell></table:table-row>
 <table:table-row><table:table-cell office:value-type="string"><text:p>只有乙</text:p></table:table-cell></table:table-row>
</table:table>
"""

# 整页空行：LibreOffice 把没用到的行列写成 huge 的 repeated，尾巴那排必须剪掉
PADDING = """<table:table table:name="空页">
 <table:table-column table:number-columns-repeated="1024"/>
 <table:table-row>
  <table:table-cell office:value-type="string"><text:p>第一格</text:p></table:table-cell>
  <table:table-cell office:value-type="string"><text:p>最后一格</text:p></table:table-cell>
 </table:table-row>
 <table:table-row table:number-rows-repeated="4096">
  <table:table-cell table:number-columns-repeated="2" office:value-type="string"><text:p/></table:table-cell>
 </table:table-row>
</table:table>
<table:table table:name="中间空一行">
 <table:table-column table:number-columns-repeated="2"/>
 <table:table-row><table:table-cell office:value-type="string"><text:p>上</text:p></table:table-cell><table:table-cell office:value-type="string"><text:p/></table:table-cell></table:table-row>
 <table:table-row><table:table-cell office:value-type="string"><text:p/></table:table-cell><table:table-cell office:value-type="string"><text:p/></table:table-cell></table:table-row>
 <table:table-row><table:table-cell office:value-type="string"><text:p>下</text:p></table:table-cell><table:table-cell office:value-type="string"><text:p>右</text:p></table:table-cell></table:table-row>
</table:table>
"""

# 只存数值没存渲染文字：别的工具刚写完、没在 LibreOffice 里打开过的形状
BARE = """<table:table table:name="裸值">
 <table:table-column table:number-columns-repeated="3"/>
 <table:table-row>
  <table:table-cell office:value-type="float" office:value="2.50"/>
  <table:table-cell office:value-type="date" office:date-value="2026-07-15"/>
  <table:table-cell office:value-type="boolean" office:boolean-value="false"/>
 </table:table-row>
 <table:table-row>
  <table:table-cell office:value-type="float"/>
  <table:table-cell office:value-type="string"><text:p>正常的一格</text:p></table:table-cell>
  <table:table-cell/>
 </table:table-row>
</table:table>
"""

# 合并格子的三种写法各一份表：只写跨度（pandoc 那类）、只放 covered（LibreOffice）、两种都写
MERGED = """<table:table table:name="只写跨度">
 <table:table-column table:number-columns-repeated="4"/>
 <table:table-row>
  <table:table-cell table:number-columns-spanned="2" office:value-type="string"><text:p>跨两列</text:p></table:table-cell>
  <table:table-cell office:value-type="string"><text:p>第三格</text:p></table:table-cell>
  <table:table-cell office:value-type="string"><text:p>第四格</text:p></table:table-cell>
 </table:table-row>
 <table:table-row>
  <table:table-cell office:value-type="string"><text:p>一</text:p></table:table-cell>
  <table:table-cell office:value-type="string"><text:p>二</text:p></table:table-cell>
  <table:table-cell office:value-type="string"><text:p>三</text:p></table:table-cell>
  <table:table-cell office:value-type="string"><text:p>四</text:p></table:table-cell>
 </table:table-row>
</table:table>
<table:table table:name="只放占位格">
 <table:table-column table:number-columns-repeated="3"/>
 <table:table-row>
  <table:table-cell office:value-type="string"><text:p>左边</text:p></table:table-cell>
  <table:covered-table-cell/>
  <table:table-cell office:value-type="string"><text:p>右边</text:p></table:table-cell>
 </table:table-row>
</table:table>
<table:table table:name="两种写法都写">
 <table:table-column table:number-columns-repeated="4"/>
 <table:table-row>
  <table:table-cell office:value-type="string"><text:p>甲</text:p></table:table-cell>
  <table:table-cell table:number-columns-spanned="2" office:value-type="string"><text:p>跨两列</text:p></table:table-cell>
  <table:covered-table-cell/>
  <table:table-cell office:value-type="string"><text:p>丁</text:p></table:table-cell>
 </table:table-row>
</table:table>
"""

# 每份夹具的期望格子（odfpy 读回来必须与这些对得上；判据那边由 ElementTree 另数一遍）
EXPECTED = {
    "ledger.ods": [
        [
            ["项目", "金额", "占比", "备注"],
            ["房租", "1500", "12.345%", "月初    付\t现金"],
            ["水费", "￥88.50", "2026-03-01", "00:05:30"],
            ["TRUE", "1588.5", "跨两列的一格", ""],
            ["补一行短的"],
        ],
        [["只有甲"], ["只有乙"]],
    ],
    "padding.ods": [
        # odfpy 不展开 number-rows-repeated，所以那 4096 行在它眼里是一行空的；
        # 我们这边展开完再把尾巴整排空的剪掉，两边对得上的是"有字的那一行"
        [["第一格", "最后一格"], ["", ""]],
        [["上", ""], ["", ""], ["下", "右"]],
    ],
    # 这一份的"渲染文字"本来就是空的：期望值是退回的那个原文
    "bare.ods": [
        [["2.50", "2026-07-15", "false"], ["", "正常的一格", ""]],
    ],
    "merged.ods": [
        [["跨两列", "", "第三格", "第四格"], ["一", "二", "三", "四"]],
        [["左边", "", "右边"]],
        # 第三张表（两种写法都写）不给 odfpy 比：它把跨度与占位格各数一遍，
        # 一次合并算成两格 —— 那是它的实现限制，不是这份夹具的形状
    ],
}

# 给 odfpy 比的表数（后面的表按上面注释的理由跳过）
VALIDATED = {"ledger.ods": 2, "padding.ods": 2, "bare.ods": 1, "merged.ods": 2}


def content(body: str) -> str:
    return (
        DECL + "<office:document-content " + NS + ' office:version="1.2">'
        + "<office:body><office:spreadsheet>" + body + "</office:spreadsheet></office:body>"
        + "</office:document-content>"
    )


def write_odt(path: str, body: str) -> None:
    """mimetype 第一条且不压缩：ODF 的硬规矩，别的实现靠它认类型。"""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as pack:
        pack.writestr(zipfile.ZipInfo("mimetype"), MIME, compress_type=zipfile.ZIP_STORED)
        pack.writestr("META-INF/manifest.xml", MANIFEST)
        pack.writestr("content.xml", content(body))
    with open(path, "wb") as handle:
        handle.write(buffer.getvalue())


def odfpy_grid(path: str) -> list:
    """用 odfpy（另一套实现）把这本工作簿读成格子；读不动就报错。"""
    lib = os.environ.get("ODFPY_PATH", "")
    if lib and lib not in sys.path:
        sys.path.insert(0, lib)
    try:
        from odf.namespaces import OFFICENS, TABLENS, TEXTNS
        from odf.opendocument import load
    except Exception as error:  # noqa: BLE001
        raise SystemExit(
            "读不了 odfpy（%s）：装一次再跑本脚本 —— 没有第三方验过的夹具不成立\n"
            "    python -m pip install --target <目录> odfpy && ODFPY_PATH=<目录> python tools/make_ods_fixtures.py"
            % error
        )
    from odf.element import Element

    def paragraph(node) -> str:
        out = []
        for child in node.childNodes:
            tag = getattr(child, "tagName", None)
            if tag in (None, "", "Text"):
                # odfpy 的文字节点是自己的 Text 类（tagName 就叫 "Text"），内容要靠 str() 取
                out.append(str(child))
                continue
            if tag == "text:s":
                count = "1"
                for key, value in getattr(child, "attributes", {}).items():
                    if key[1:] == ("c",):
                        count = str(value)
                out.append(" " * int(count or 1))
            elif tag == "text:tab":
                out.append("\t")
            elif tag == "text:line-break":
                out.append("\n")
            else:
                out.append(paragraph(child))
        return "".join(out)

    def cell_text(cell) -> str:
        lines = [paragraph(node) for node in cell.childNodes if getattr(node, "tagName", "") == "text:p"]
        return "\n".join(lines)

    def get(node, ns, name, default=""):
        """绕开 odfpy 的属性名白名单：直接读它解析出来的 attributes 字典。"""
        for key, value in getattr(node, "attributes", {}).items():
            if key == (ns, name) or key == name:
                return str(value)
        return default

    def cell_value(cell) -> str:
        if getattr(cell, "tagName", "") == "table:covered-table-cell":
            return ""
        shown = cell_text(cell)
        if shown:
            return shown
        kind = get(cell, OFFICENS, "value-type")
        key = {
            "float": "value", "double": "value",
            "percentage": "value", "currency": "value",
            "date": "date-value", "time": "time-value",
            "boolean": "boolean-value", "string": "string-value",
        }.get(kind)
        return get(cell, OFFICENS, key) if key else ""

    def expand(row) -> list:
        cells = []
        for cell in row.childNodes:
            tag = getattr(cell, "tagName", "")
            if tag not in ("table:table-cell", "table:covered-table-cell"):
                continue
            repeat = int(get(cell, TABLENS, "number-columns-repeated", "1") or 1)
            spanned = int(get(cell, TABLENS, "number-columns-spanned", "1") or 1)
            for _ in range(max(repeat, 1)):
                cells.append(cell_value(cell))
                cells.extend([""] * max(spanned - 1, 0))
        return cells

    def rows_of(node) -> list:
        out = []
        for child in node.childNodes:
            tag = getattr(child, "tagName", "")
            if tag == "table:table-row":
                out.append(expand(child))
            elif tag in ("table:table-header-rows", "table:table-rows"):
                out += rows_of(child)
        return out

    doc = load(path)
    sheets = []
    for table in doc.spreadsheet.childNodes:
        if getattr(table, "tagName", "") != "table:table":
            continue
        sheets.append(rows_of(table))
    _ = Element
    return sheets


def compare(got: list, want: list, path: str, limit: int) -> None:
    """只比前 `limit` 张表：剩下的那几张是特意留给 ElementTree 判的（见 VALIDATED 的注释）。"""
    if len(got) < limit:
        raise SystemExit("%s：odfpy 只读到 %d 张表，预期至少 %d 张" % (path, len(got), limit))
    for index in range(limit):
        if got[index] != want[index]:
            raise SystemExit(
                "%s 第 %d 张表与预期不符：\n  odfpy=%s\n  预期 =%s" % (path, index + 1, got[index], want[index])
            )


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    for name, body in (("ledger.ods", LEDGER), ("padding.ods", PADDING),
                       ("bare.ods", BARE), ("merged.ods", MERGED)):
        path = os.path.join(OUT, name)
        write_odt(path, body)
        compare(odfpy_grid(path), EXPECTED[name], name, VALIDATED[name])
        print("写好 %s（%d 字节）· odfpy 读回来前 %d 张表逐格相同" % (path, os.path.getsize(path), VALIDATED[name]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
