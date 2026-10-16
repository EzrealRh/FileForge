package com.fileforge.core.office

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable

/**
 * ODS 读回来的一张一张表 → 文档树里的"一张表一节"，以及那一节要说清的话。
 *
 * 与 xlsx 那侧的 `XlsxSheets` 是同一件事，但**表头这一条不一样**：ODS 文件自己在
 * `table:table` 上写了 `table:use-atr... ` 那类声明，读的一刻就定了首行算不算表头（见 `OdsRead`），
 * 所以这里照它给，不替文件下判断；xlsx 那份没声明，就一律不当表头。
 */
object OdsSheets {

    /** 每行补到最宽的列数：网页与 PDF 的表格都按整齐的网格排，短行不往左挤。 */
    fun padded(rows: List<List<String>>): List<List<String>> {
        val width = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { row -> row + List(width - row.size) { "" } }
    }

    /** 表名当小标题，表跟着它；真空表不占一节（占了就是一张只有标题的白纸）。 */
    fun parts(sheets: List<OdsSheet>): List<DocPart> {
        val out = ArrayList<DocPart>()
        sheets.forEach { sheet ->
            if (sheet.rows.isEmpty() || sheet.rows.all { row -> row.all { it.isEmpty() } }) return@forEach
            out += DocParagraph(DocPara(listOf(DocRun(sheet.name)), "Heading2"))
            out += DocTable(sheet.header, padded(sheet.rows))
        }
        return out
    }

    /** 几张表、每张几行几列：产物说明与判据看的是同一句，谁也不重新数一遍。 */
    fun summary(sheets: List<OdsSheet>): List<String> {
        val made = sheets.filter { it.rows.isNotEmpty() && it.rows.any { row -> row.any { it.isNotEmpty() } } }
        if (made.isEmpty()) return emptyList()
        return listOf("${made.size} 张表 · ${made.sumOf { it.rows.size }} 行") +
            made.map { sheet -> "${sheet.name}：${sheet.rows.size} 行 × ${sheet.rows.maxOf { it.size }} 列" }
    }

    /** 哪几张表本来空的、没占一节 —— 少一张而不说，用户看不出是文件空还是我们弄丢了。 */
    fun notes(sheets: List<OdsSheet>): List<String> {
        val empty = sheets.filter { it.rows.isEmpty() || it.rows.all { row -> row.all { cell -> cell.isEmpty() } } }
        return if (empty.isEmpty()) emptyList()
        else listOf("${empty.size} 张表是空的（${empty.joinToString("、") { it.name }}），没占一节")
    }
}
