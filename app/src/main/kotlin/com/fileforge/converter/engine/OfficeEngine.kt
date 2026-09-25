package com.fileforge.converter.engine

import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.core.data.Csv
import com.fileforge.core.data.Delimiter
import com.fileforge.core.text.LineEnding
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.office.OoxmlStructure
import com.fileforge.core.office.Sheet
import com.fileforge.core.office.Xlsx
import java.io.File

/**
 * Office 文档的产出：docx / pptx 抽正文文字，xlsx 每张表出一份 CSV。
 *
 * 判断全在 `:core/office`（那边能脱机单测），这里只管按部件读、写盘、把"丢了什么"拼成一句说明。
 */
class OfficeEngine(private val workspace: Workspace) {

    private companion object {
        /** 正文超过这个长度基本就是读歪了（把整包当一份文档读），宁可报错。 */
        const val MAX_TEXT_CHARS = 8 * 1024 * 1024

        /** 表名要进文件名：Excel 允许 31 字，再长的截掉。 */
        const val MAX_SHEET_TAG = 24
    }

    /** docx / pptx 抽正文，存成 UTF-8（无 BOM）的 txt。 */
    fun toText(item: WorkItem): EngineOutput {
        val extracted = OfficeSource.text(item.file, item.kind)
        require(extracted.text.length <= MAX_TEXT_CHARS) { "这份文档抽出的文字有 ${extracted.text.length} 字，多得不像正文" }
        val notes = ArrayList<String>()
        notes += "${extracted.text.length} 字 · ${extracted.text.count { it == '\n' }} 行"
        notes += extracted.losses
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "txt"),
            write("txt", extracted.text),
            notes.joinToString(" · "),
        )
    }

    /** xlsx 每张表一份 CSV。多张表时表名进文件名，只有一张就不加后缀。 */
    fun toCsv(item: WorkItem, delimiter: Delimiter, ending: LineEnding): List<EngineOutput> {
        val read = readSheets(item.file)
        val sheets = read.sheets.filter { it.rows.isNotEmpty() }
        if (sheets.isEmpty()) {
            throw IllegalArgumentException(
                read.reasons.ifEmpty { listOf("每张表都是空的") }.joinToString(" · "),
            )
        }
        return sheets.map { sheet ->
            val notes = ArrayList<String>()
            notes += "${sheet.rows.size} 行 × ${sheet.rows.maxOf { it.size }} 列"
            notes += sheet.notes
            if (read.reasons.isNotEmpty()) notes += "另有 ${read.reasons.size} 张表没转出来：${read.reasons.joinToString("、")}"
            EngineOutput(
                OutputNaming.tagged(item.name, if (read.sheets.size == 1) "" else tag(sheet.name), "csv"),
                write("csv", Csv.render(sheet.rows, delimiter, ending)),
                notes.joinToString(" · "),
            )
        }
    }

    /** 一次读入：哪张表读成了、哪张读不成为什么，一起带回去。 */
    private class Readout(val sheets: List<Sheet>, val reasons: List<String>)

    private fun readSheets(file: File): Readout = OoxmlFile(file).use { pack ->
        require(pack.kind == FileKind.Xlsx) { "这份包里没找到 xl/workbook.xml，它不是 xlsx" }
        val strings = Xlsx.sharedStrings(pack.bytesOf(Xlsx.SHARED_STRINGS))
        val styles = Xlsx.dateStyles(pack.bytesOf(Xlsx.STYLES))
        val epoch = Xlsx.uses1904(pack.bytesOf(OoxmlStructure.WORKBOOK))
        val sheets = ArrayList<Sheet>()
        val reasons = ArrayList<String>()
        pack.sheetRefs().forEach { ref ->
            val bytes = ref.part?.let { pack.bytesOf(it) }
            if (bytes == null) reasons += "${ref.name}：${ref.part ?: "包里找不到对应部件"} 读不出来"
            else sheets += Xlsx.sheet(ref.name, bytes, strings, styles, epoch)
        }
        Readout(sheets, reasons)
    }

    private fun tag(sheetName: String): String = OutputNaming.sanitize(sheetName).take(MAX_SHEET_TAG)

    /** 成品一律 UTF-8 无 BOM：BOM 会让第一列的列名在前面的工具里多出一个看不见的字符。 */
    private fun write(extension: String, text: String): File =
        workspace.newStagingFile(extension).apply { writeText(text, Charsets.UTF_8) }
}
