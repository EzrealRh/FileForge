"""写"表格印成 PDF"要用的夹具：一份真 .xlsx，外加一份由 openpyxl 自己读出来的排片单。

为什么要两份：判据要核的是"格子从文件里到纸上的位置对不对"，而这份格子该长什么样**不能由
我们自己的 Kotlin 读法说了算** —— 排片单（.plan.tsv）由 openpyxl 读原件生成，`:core` 的
排版器只负责把它排成页，桌面 PDFBox 画出来；读回来用 pdfplumber / pdfminer / pdftotext
三个独立读路。四边都不沾，才算核过。

排片单的形状（制表符分隔，一行一条）：
    #SHEET <表名>\theader=<1|0>     一张表开始（表名在纸上是它上面的一行小标题）
    <格>\t<格>\t…                    一行；空串就是那一格真空着，格子里的硬换行写成 \\n
                                     （不转义的话一行会被它劈成两行，读的人分不开）
    #TEXT <一句话>                   表外的一段正文（夹在两张表中间，验先后顺序）
    #EMPTY <表名>                    一张真空的表（不该占一节，也不该画出一个框）

产物落在 core/build/pdftable/：三份 .xlsx 与三份 .plan.tsv。
"""

import os

import openpyxl

OUT = os.path.join("core", "build", "pdftable")

# 每项：("表", 表名, 首行是不是表头, 行) 或 ("文", 一句话) 或 ("空", 表名)
BOOK = [
    ("表", "费用", True, [
        ["项目", "金额", "发生日", "备注"],
        ["打车", "38.5", "2023-05-01", "周一早高峰"],
        ["", "", "2024-02-29 08:30:15", ""],
        ["地铁充值", "100", "2023-06-02", "卡片押金不含在内"],
        ["城际高铁往返上海", "726.00", "2023-07-15", "二等座，含改签费"],
        ["误餐", "12", "", ""],
        ["合计", "876.5", "", "与报销单一致"],
    ]),
    ("表", "说明", False, [
        ["编号", "说明"],
        ["1", "文件工坊把图片、文档、音频都放在手机上处理，一个字都不上传，所以即便断行要能在字之间断、"
              "收尾标点不许顶到行首，也都是在本地算的"],
        ["2", "第二行短"],
        ["3", "第三行带一个硬换行：\n这是换行后面的那句"],
    ]),
    ("文", "两张表之间的一句话，用来核先后顺序没乱。"),
    ("空", "空的"),
]

WIDE = [
    # 26 列的宽表：要么按比例压、要么连保底都放不下而超出纸边，两种都要写明
    ("表", "宽表", True, [
        ["列" + str(index) for index in range(1, 27)],
        ["值" + str(index) for index in range(1, 27)],
        [("很长的一格放在这里看看会不会把整张表挤出去" if index == 3 else str(index))
         for index in range(1, 27)],
    ]),
]

LONG = [
    # 60 行的表：一定要翻页，翻过去表头要重复
    ("表", "长表", True, [["表头甲", "表头乙", "表头丙"]] + [
        ["第 %d 行" % (index + 1), "内容" + str(index % 7), "备注文字" if index % 5 == 0 else ""]
        for index in range(60)
    ]),
]

SHEETS = {"book.xlsx": BOOK, "wide.xlsx": WIDE, "long.xlsx": LONG}


def plan_of(items: list) -> list:
    out = []
    for item in items:
        if item[0] == "文":
            out.append("#TEXT\t" + item[1])
        elif item[0] == "空":
            out.append("#EMPTY\t" + item[1])
        else:
            _, title, header, rows = item
            out.append("#SHEET\t%s\theader=%d" % (title, 1 if header else 0))
            for row in rows:
                out.append("\t".join(
                    "" if cell is None else str(cell).replace("\n", "\\n") for cell in row))
    return out


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    for name, items in SHEETS.items():
        workbook = openpyxl.Workbook()
        first = True
        for item in items:
            if item[0] == "文":
                continue
            if item[0] == "空":
                sheet = workbook.active if first else workbook.create_sheet()
                first = False
                sheet.title = item[1]
                continue
            _, title, _, rows = item
            sheet = workbook.active if first else workbook.create_sheet()
            first = False
            sheet.title = title
            for row in rows:
                sheet.append(row)
        target = os.path.join(OUT, name)
        workbook.save(target)
        lines = plan_of(items)
        with open(os.path.join(OUT, name.replace(".xlsx", ".plan.tsv")), "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines) + "\n")
        back = openpyxl.load_workbook(target)
        print("%s：%d 张表（%s），排片单 %d 行" % (name, len(back.sheetnames), "、".join(back.sheetnames), len(lines)))


if __name__ == "__main__":
    main()
