package com.fileforge.converter.engine

import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.core.data.Csv
import com.fileforge.core.data.Delimiter
import com.fileforge.core.data.TableBridge
import com.fileforge.core.data.Xml
import com.fileforge.core.data.XmlTable
import com.fileforge.core.doc.TextDoc
import com.fileforge.core.text.LineEnding
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.office.DocxWrite
import com.fileforge.core.office.OoxmlStructure
import com.fileforge.core.office.Sheet
import com.fileforge.core.office.SheetToWrite
import com.fileforge.core.office.Xlsx
import com.fileforge.core.office.XlsxWrite
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

    /**
     * CSV 写成一份 xlsx（一张表，表名用文件名）。
     *
     * 类型判定全在 `:core`（那边能脱机单测）：只有变成数字后还能一字不差读回来的写法才写成数字。
     */
    fun csvToXlsx(item: WorkItem): EngineOutput {
        val text = com.fileforge.core.text.TextCodecs.decodeForConversion(read(item), null).text
        val doc = Csv.parse(text)
        require(!doc.isEmpty) { "这份 CSV 里一行记录都没有" }
        val notes = ArrayList<String>()
        if (doc.ragged.isNotEmpty()) {
            notes += "第 ${doc.ragged.joinToString("、")} 行列数与最宽的 ${doc.widest} 列不齐，右边补了空格子"
        }
        val rows = doc.records.map { record -> record + List(doc.widest - record.size) { "" } }
        return workbook(item, listOf(SheetToWrite(OutputNaming.stem(item.name), rows)), notes)
    }

    /** JSON（对象数组）写成一份 xlsx：摊平用的是「JSON 转 CSV」同一套判据，两层结果对得上。 */
    fun jsonToXlsx(item: WorkItem): EngineOutput {
        val text = com.fileforge.core.text.TextCodecs.decodeForConversion(read(item), null).text
        val json = try {
            com.fileforge.core.json.Json.parse(text.trim())
        } catch (bad: com.fileforge.core.json.JsonException) {
            throw IllegalArgumentException("这不是合法 JSON：${bad.message}")
        }
        val table = TableBridge.toTable(json) ?: throw IllegalArgumentException(
            TableBridge.reasonWhyNotTable(json) ?: "这份 JSON 摊不成表：要的是对象数组（每项的键当列名）",
        )
        val notes = ArrayList(TableBridge.flatteningNotes(json))
        notes += "第一行是列名"
        return workbook(item, listOf(SheetToWrite(OutputNaming.stem(item.name), table.records)), notes)
    }

    /**
     * XML 写成一份 xlsx：与「XML 转 CSV」挑同一张表（同一套判断在 `:core` 的 XmlTable 里），
     * 只是落成工作簿。列名、行序、哪处没进表，两条路说的一样。
     */
    fun xmlToXlsx(item: WorkItem): EngineOutput {
        val tree = Xml.parse(com.fileforge.core.text.TextCodecs.decodeForConversion(read(item), null).text)
        val picked = XmlTable.pick(tree) ?: throw IllegalArgumentException(
            XmlTable.reasonWhyNot(tree) ?: "这份 XML 里没有可当行的重复元素",
        )
        val table = TableBridge.toTable(picked.rows) ?: throw IllegalArgumentException("挑出来的那处重复元素摊不成表")
        val notes = ArrayList(picked.notes)
        notes += "第一行是列名（属性列前面带 @）"
        return workbook(item, listOf(SheetToWrite(OutputNaming.stem(item.name), table.records)), notes)
    }

    /**
     * YAML 写成一份 xlsx：摊表用的是「YAML 转 CSV」同一套判据（都在 [TableBridge]），
     * 两条路出来的表行列一致，只是一份是 CSV、一份是能直接打开的工作簿。
     */
    fun yamlToXlsx(item: WorkItem): EngineOutput {
        val (tree, notes) = parseYamlTree(item, read(item))
        TableBridge.reasonWhyNotTable(tree)?.let { throw IllegalArgumentException("转不成表：$it") }
        val table = TableBridge.toTable(tree) ?: throw IllegalArgumentException("转不成表：这份 YAML 的形状没认出来")
        val lines = ArrayList(notes)
        lines += TableBridge.flatteningNotes(tree)
        lines += "第一行是列名"
        return workbook(item, listOf(SheetToWrite(OutputNaming.stem(item.name), table.records)), lines)
    }

    /**
     * 文本 / Markdown / 网页 / Word 演示正文写成一份 docx。
     *
     * 认哪条路、怎么排版全在 `:core`（那边能脱机单测，也拿 pandoc 逐块对过）；
     * 这里只管读进来（走 [SourceText]，与写成电子书同一条来源判定）、落盘、把交代拼进说明。
     */
    fun toDocx(item: WorkItem): EngineOutput {
        val reading = SourceText.of(item)
        val out = DocxWrite.document(reading.doc, modifiedAt = item.file.lastModified())
        val file = workspace.newStagingFile("docx").apply { writeBytes(out.bytes) }
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "docx"),
            file,
            (listOf("${reading.doc.parts.size} 块内容") + reading.notes + reading.doc.notes + out.notes).joinToString(" · "),
        )
    }

    private fun workbook(
        item: WorkItem,
        sheets: List<SheetToWrite>,
        notes: List<String>,
    ): EngineOutput {
        val out = XlsxWrite.workbook(sheets, modifiedAt = item.file.lastModified())
        val file = workspace.newStagingFile("xlsx").apply { writeBytes(out.bytes) }
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "xlsx"),
            file,
            (listOf("${sheets.size} 张表 · ${sheets.sumOf { it.rows.size }} 行 × ${sheets.maxOf { it.rows.maxOfOrNull { row -> row.size } ?: 0 }} 列") +
                notes + out.notes).joinToString(" · "),
        )
    }

    /** 输入按文本上限卡：CSV / JSON 再大也不该超出这个量级，超了多半是把二进制误选进来了。 */
    private fun read(item: WorkItem): ByteArray {
        require(item.file.length() <= MAX_TEXT_BYTES) {
            "这份文件 ${item.file.length() / 1024 / 1024} MB，超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB 上限"
        }
        return item.file.readBytes()
    }

    /** 成品一律 UTF-8 无 BOM：BOM 会让第一列的列名在前面的工具里多出一个看不见的字符。 */
    private fun write(extension: String, text: String): File =
        workspace.newStagingFile(extension).apply { writeText(text, Charsets.UTF_8) }
}
