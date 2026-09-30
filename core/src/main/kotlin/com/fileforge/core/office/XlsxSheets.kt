package com.fileforge.core.office

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable

/**
 * 一批工作表 → 文档树：一张表一节，表名当小标题，格子成一张表。
 *
 * 转网页 / 转 Markdown 与「写成 Word」「写成电子书」共用这一份判定，
 * 免得同一份工作簿在不同产物里长得不一样。
 *
 * 表头那一行**空着**：普通区域里 xlsx 没有"首行是表头"这件事 —— 那是被"格式化为表格"时
 * 由 `xl/tables` 那份部件另外声明的（列名与 `headerRowCount`），没声明就不抬。
 * Markdown 那边跟着留空表头，读回来仍然是"没有表头的表"。
 */
object XlsxSheets {

    /** 一本工作簿：读成的表 + 哪张没读成为什么（不静悄悄少一张）。 */
    class Book(val sheets: List<Sheet>, val reasons: List<String>)

    /**
     * 整本读进来：表序照工作簿自己声明的，共享字符串 / 日期样式 / 1904 纪元都只问一次。
     *
     * 放在这里而不是各条出路自己读一遍：转 CSV、转网页、转 Markdown 与写成 Word 用的必须是同一批格子。
     */
    fun book(load: (String) -> ByteArray?): Book {
        val refs = Xlsx.sheets(load)
        val strings = Xlsx.sharedStrings(load(Xlsx.SHARED_STRINGS))
        val dates = Xlsx.dateStyles(load(Xlsx.STYLES))
        val epoch = Xlsx.uses1904(load(OoxmlStructure.WORKBOOK))
        val sheets = ArrayList<Sheet>()
        val reasons = ArrayList<String>()
        refs.forEach { ref ->
            val bytes = ref.part?.let(load)
            if (bytes == null) {
                reasons += "${ref.name}：${ref.part ?: "包里找不到对应部件"} 读不出来"
            } else {
                sheets += Xlsx.sheet(ref.name, bytes, strings, dates, epoch)
            }
        }
        return Book(sheets, reasons)
    }

    /**
     * 空的表不占一节（工作簿里那种"只有一张空表"的常见情况）。
     *
     * 行列的整齐网格在 `Xlsx.sheet` 那一步就按引用对位补好了，这里不再补第二次 —— 两处各补一次，
     * 反例就测不出"到底靠谁补的"。
     */
    fun parts(sheets: List<Sheet>): List<DocPart> {
        val out = ArrayList<DocPart>()
        sheets.forEach { sheet ->
            if (sheet.rows.isEmpty()) return@forEach
            out += DocParagraph(DocPara(listOf(DocRun(sheet.name)), "Heading2"))
            out += DocTable(header = false, sheet.rows)
        }
        return out
    }

    /**
     * 这批表要说清的话：每张表自己那些没搬的东西（合并格、超上限、公式没存结果…），
     * 加上"哪几张表空的、没占一节"—— 少一张表而不说，用户看不出来是文件本来就空还是我们弄丢了。
     */
    fun notes(sheets: List<Sheet>): List<String> {
        val out = ArrayList(sheets.flatMap { it.notes }.distinct())
        val empty = sheets.filter { it.rows.isEmpty() || it.rows.all { row -> row.all { it.isEmpty() } } }
        if (empty.isNotEmpty()) out += "${empty.size} 张表是空的（${empty.joinToString("、") { it.name }}），没占一节"
        return out
    }

    /** 几张表、每张几行几列：产物说明与判据看的是同一句，谁也不重新数一遍。 */
    fun summary(sheets: List<Sheet>): List<String> {
        val made = sheets.filter { it.rows.isNotEmpty() }
        if (made.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        out += "${made.size} 张表 · ${made.sumOf { it.rows.size }} 行"
        made.forEach { sheet -> out += "${sheet.name}：${sheet.rows.size} 行 × ${sheet.rows.maxOf { row -> row.size }} 列" }
        return out
    }
}
