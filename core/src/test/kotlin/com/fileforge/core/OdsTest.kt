package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.data.Csv
import com.fileforge.core.data.Delimiter
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.office.OdsRead
import com.fileforge.core.office.OdsWrite
import com.fileforge.core.office.OoxmlParts
import com.fileforge.core.office.SheetToWriteOds
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ODS 的读法与写法（`:core` 的 OdsRead / OdsWrite）。
 *
 * 这里的样本是**照 ODF 的规矩手写**的：值类型、重复计数、跨列与 covered 两种合并写法
 * 都摆出来，才判得清"照字面展开"和"替别人猜一遍"的差别。
 *
 * 只比这些不够：真实夹具（`tools/make_ods_fixtures.py` 产的三份 .odt，都由 odfpy 读回验过）
 * 的四条产物落在 `build/odsread/`，由 `tools/verify_ods.py` 拿 ElementTree 独立展开网格与
 * openpyxl 读我们写的 xlsx 判 —— 判据与实现不共用一行代码。
 */
class OdsTest {

    private fun body(tables: String, load: (String) -> ByteArray? = { null }) = OdsRead.read { name ->
        if (name == OoxmlParts.ODT_CONTENT) {
            (XML_DECL + NAMESPACES + "<office:body><office:spreadsheet>" + tables +
                "</office:spreadsheet></office:body></office:document-content>").toByteArray(Charsets.UTF_8)
        } else {
            load(name)
        }
    }

    private fun cell(value: String, type: String = "string") =
        if (type == "string") {
            "<table:table-cell office:value-type=\"string\"><text:p>" + value + "</text:p></table:table-cell>"
        } else {
            "<table:table-cell office:value-type=\"" + type + "\"><text:p>" + value + "</text:p></table:table-cell>"
        }

    private fun one(table: String) = body(table).sheets.single()

    @Test
    fun `表名与表头照文件里说的来`() {
        val read = body(
            "<table:table table:name=\"一月\">" +
                "<table:table-header-rows><table:table-row>" + cell("项目") + cell("金额") + "</table:table-row></table:table-header-rows>" +
                "<table:table-row>" + cell("房租") + cell("1500", "float") + "</table:table-row>" +
                "</table:table>",
        )
        assertEquals(listOf("一月"), read.sheets.map { it.name })
        assertEquals(true, read.sheets.single().header)
        assertEquals(listOf(listOf("项目", "金额"), listOf("房租", "1500")), read.sheets.single().rows)
    }

    @Test
    fun `重名的表编号而不是互相盖掉`() {
        val read = body(
            "<table:table table:name=\"Sheet1\"><table:table-row>" + cell("甲") + "</table:table-row></table:table>" +
                "<table:table table:name=\"Sheet1\"><table:table-row>" + cell("乙") + "</table:table-row></table:table>",
        )
        assertEquals(listOf("Sheet1", "Sheet1-2"), read.sheets.map { it.name })
    }

    @Test
    fun `重复列与跨列都要补成空格子`() {
        val sheet = one(
            "<table:table table:name=\"甲\">" +
                "<table:table-row>" +
                "<table:table-cell table:number-columns-repeated=\"3\" office:value-type=\"string\"><text:p/></table:table-cell>" +
                cell("第一格") + "</table:table-row>" +
                "<table:table-row>" +
                "<table:table-cell table:number-columns-spanned=\"2\" office:value-type=\"string\"><text:p>跨两列</text:p></table:table-cell>" +
                cell("末列") + "</table:table-row>" +
                "</table:table>",
        )
        assertEquals(listOf(listOf("", "", "", "第一格"), listOf("跨两列", "", "末列")), sheet.rows)
    }

    @Test
    fun `合并的两种写法一起写时只占一次`() {
        // LibreOffice 在被并掉的位置放 covered 格，pandoc 那类写者只给前一格加 spanned；
        // 真文件会两种一起写 —— 两种都数一遍的话一次合并占两格，整行往右错位
        val sheet = one(
            "<table:table table:name=\"甲\"><table:table-row>" +
                cell("甲") +
                "<table:table-cell table:number-columns-spanned=\"2\"><text:p>跨两列</text:p></table:table-cell>" +
                "<table:covered-table-cell/>" +
                cell("丁") +
                "</table:table-row></table:table>",
        )
        assertEquals(listOf(listOf("甲", "跨两列", "", "丁")), sheet.rows)
    }

    @Test
    fun `整页的重复空行被剪掉而中间的空行留着`() {
        val read = body(
            "<table:table table:name=\"甲\">" +
                "<table:table-row>" + cell("上") + "</table:table-row>" +
                "<table:table-row>" + "<table:table-cell/>" + "</table:table-row>" +
                "<table:table-row>" + cell("下") + "</table:table-row>" +
                "<table:table-row table:number-rows-repeated=\"4096\"><table:table-cell table:number-columns-repeated=\"4\"/></table:table-row>" +
                "</table:table>",
        )
        assertEquals(listOf(listOf("上"), listOf(), listOf("下")), read.sheets.single().rows)
        assertTrue(read.notes.any { "整行空着" in it }, read.notes.toString())
    }

    @Test
    fun `值的写法用渲染出来的那份`() {
        val sheet = one(
            "<table:table table:name=\"甲\"><table:table-row>" +
                "<table:table-cell office:value-type=\"percentage\" office:value=\"0.12345\"><text:p>12.345%</text:p></table:table-cell>" +
                "<table:table-cell office:value-type=\"currency\" office:currency=\"CNY\" office:value=\"88.5\"><text:p>￥88.50</text:p></table:table-cell>" +
                "<table:table-cell office:value-type=\"date\" office:date-value=\"2026-03-01\"><text:p>2026年3月1日</text:p></table:table-cell>" +
                "</table:table-row></table:table>",
        )
        assertEquals(listOf(listOf("12.345%", "￥88.50", "2026年3月1日")), sheet.rows)
    }

    @Test
    fun `没有渲染文字时退回原文并说明`() {
        val read = body(
            "<table:table table:name=\"甲\"><table:table-row>" +
                "<table:table-cell office:value-type=\"float\" office:value=\"2.50\"/>" +
                "<table:table-cell office:value-type=\"date\" office:date-value=\"2026-07-15\"/>" +
                "<table:table-cell office:value-type=\"boolean\" office:boolean-value=\"false\"/>" +
                "<table:table-cell office:value-type=\"float\"/>" +
                "</table:table-row></table:table>",
        )
        // 尾巴上那格是空的（只有 value-type 没有值），跟整排空的尾巴一起被剪掉
        assertEquals(listOf(listOf("2.50", "2026-07-15", "false")), read.sheets.single().rows)
        assertTrue(read.notes.any { "没有渲染出来的文字" in it }, read.notes.toString())
        assertTrue(read.notes.any { "没存值" in it }, read.notes.toString())
    }

    @Test
    fun `格子里的空格个数与制表和换行都在`() {
        val sheet = one(
            "<table:table table:name=\"甲\"><table:table-row>" +
                "<table:table-cell office:value-type=\"string\">" +
                "<text:p>甲<text:s/><text:s text:c=\"3\"/>乙<text:tab/>丙</text:p>" +
                "<text:p>第二行</text:p></table:table-cell>" +
                "</table:table-row></table:table>",
        )
        assertEquals(listOf(listOf("甲    乙\t丙\n第二行")), sheet.rows)
    }

    @Test
    fun `公式给的是文件里存着的结果`() {
        val read = body(
            "<table:table table:name=\"甲\"><table:table-row>" +
                "<table:table-cell table:formula=\"of:=SUM([.A1:.A2])\" office:value-type=\"float\" office:value=\"3\">" +
                "<text:p>3</text:p></table:table-cell>" +
                "</table:table-row></table:table>",
        )
        assertEquals(listOf(listOf("3")), read.sheets.single().rows)
        assertTrue(read.notes.any { "公式" in it }, read.notes.toString())
    }

    @Test
    fun `缺件与空包都直说`() {
        assertThrows(IllegalArgumentException::class.java) { OdsRead.read { null } }
        assertThrows(IllegalArgumentException::class.java) {
            OdsRead.read { name ->
                if (name == OoxmlParts.ODT_CONTENT) {
                    (XML_DECL + NAMESPACES + "<office:body><office:text/></office:body></office:document-content>")
                        .toByteArray(Charsets.UTF_8)
                } else {
                    null
                }
            }
        }
    }

    @Test
    fun `写出去的包别人读得动且与自己读回来的一致`() {
        val sheets = listOf(
            SheetToWriteOds("工资", listOf(listOf("项目", "金额"), listOf("房租", "1500"), listOf("水费", "1.50"))),
            SheetToWriteOds("", listOf(listOf("只有一格"))),
        )
        val out = OdsWrite.spreadsheet(sheets, modifiedAt = 0L)
        val first = ZipReader.read(out.bytes).entries.first()
        assertEquals("mimetype", first.name)
        assertEquals(MIME.length.toLong(), first.size)
        assertTrue(first.compressedSize.toLong() == first.size, "mimetype 要原样存，不压缩")
        val read = OdsRead.read { name -> zipPart(out.bytes, name) }
        assertEquals(listOf("工资", "表2"), read.sheets.map { it.name })
        assertEquals(
            listOf(listOf("项目", "金额"), listOf("房租", "1500"), listOf("水费", "1.50")),
            read.sheets.first().rows,
        )
        // 1.50 这种写法不能被认成数值 —— 认了就已经是 1.5
        assertTrue(out.notes.any { "按文字写" in it }, out.notes.toString())
    }

    @Test
    fun `CSV 摊成的格子与写出去再读回来的一致`() {
        val text = "甲,乙\n1.50,\"多行\n第二行\"\n007, \t 空格 \n"
        val doc = Csv.parse(text, Csv.detect(text))
        val widest = doc.widest
        val rows = doc.records.map { record -> record + List(widest - record.size) { "" } }
        val out = OdsWrite.spreadsheet(listOf(SheetToWriteOds("原样", rows)), modifiedAt = 0L)
        val back = OdsRead.read { name -> zipPart(out.bytes, name) }.sheets.single()
        assertEquals(rows, back.rows, "写出去再读回来变了：" + back.rows)
        assertEquals(Csv.render(rows, Delimiter.Comma, com.fileforge.core.text.LineEnding.Lf),
            Csv.render(back.rows, Delimiter.Comma, com.fileforge.core.text.LineEnding.Lf))
    }

    @Test
    fun `纯文本与网页两条不吃同一份表格形状`() {
        val book = body(
            "<table:table table:name=\"甲\"><table:table-row>" + cell("甲") + cell("乙") + "</table:table-row></table:table>",
        )
        val sheet = book.sheets.single()
        val html = HtmlWrite.page("甲", listOf(com.fileforge.core.doc.DocTable(true, sheet.rows)), language = "zh").html
        assertTrue("<table><tr><th>甲</th><th>乙</th></tr></table>" in html, html)
        val plain = HtmlWrite.page("甲", listOf(com.fileforge.core.doc.DocTable(false, sheet.rows)), language = "zh").html
        assertTrue("<table><tr><td>甲</td><td>乙</td></tr></table>" in plain, plain)
    }

    /**
     * 真实夹具的四条产物落盘：判据跑的是磁盘上这些文件。
     *
     * 断言放在产文件**之后**：先断言会让坏实现把上一轮的旧产物留下，外部判据拿着旧文件说全绿。
     */
    @Test
    fun `产物落盘供外部判据对照`() {
        val dir = File("build/odsread").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
        listOf("ledger.ods", "padding.ods", "bare.ods", "merged.ods").forEach { name ->
            val stem = name.substringBeforeLast('.')
            val bytes = resourceBytes("odsread", name)
            val book = OdsRead.read { part -> zipPart(bytes, part) }
            require(book.sheets.isNotEmpty()) { "$stem 一张表都没读到，夹具或读法坏了" }
            File(dir, "$stem.ods").writeBytes(bytes)
            File(dir, "$stem.grid.txt").writeText(gridOf(book), Charsets.UTF_8)
            book.sheets.forEachIndexed { index, sheet ->
                File(dir, "$stem-${index + 1}.csv").writeText(Csv.render(sheet.rows, Delimiter.Comma, com.fileforge.core.text.LineEnding.Lf), Charsets.UTF_8)
            }
            File(dir, "$stem.html").writeText(
                HtmlWrite.page(
                    stem,
                    book.sheets.flatMap { sheet ->
                        listOf(
                            com.fileforge.core.doc.DocParagraph(
                                com.fileforge.core.doc.DocPara(listOf(com.fileforge.core.doc.DocRun(sheet.name)), "Heading2"),
                            ),
                            com.fileforge.core.doc.DocTable(sheet.header, sheet.rows),
                        )
                    },
                    language = "zh",
                ).html,
                Charsets.UTF_8,
            )
            val made = com.fileforge.core.office.XlsxWrite.workbook(
                book.sheets.map { com.fileforge.core.office.SheetToWrite(it.name, it.rows) },
                modifiedAt = 0L,
            )
            File(dir, "$stem.xlsx").writeBytes(made.bytes)
            File(dir, "$stem.xlsxnotes.txt").writeText(made.notes.joinToString("\n") + "\n", Charsets.UTF_8)
            File(dir, "$stem.notes.txt").writeText(book.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        // 反向那条：CSV 写成 ODS，产物也落给判据
        val csv = resource("odsread", "tricky.csv")
        val doc = Csv.parse(csv, Csv.detect(csv))
        val widest = doc.widest
        val rows = doc.records.map { record -> record + List(widest - record.size) { "" } }
        val out = OdsWrite.spreadsheet(listOf(SheetToWriteOds("tricky", rows)), modifiedAt = 0L)
        File(dir, "written.ods").writeBytes(out.bytes)
        File(dir, "written.grid.txt").writeText(gridOf(OdsRead.read { name -> zipPart(out.bytes, name) }), Charsets.UTF_8)
        File(dir, "source.csv").writeText(Csv.render(rows, Delimiter.Comma, com.fileforge.core.text.LineEnding.Lf), Charsets.UTF_8)
        assertEquals(5, dir.listFiles().orEmpty().count { it.name.endsWith(".ods") })
    }

    /**
     * 把一本工作簿摊成"表名 + 每行每格"的字面文本，判据那边按同样的形状比。
     *
     * 每行带行号：不带的话，一行全空的格子会写成一条空行，而空行在文本文件里
     * 与"没有这一行"看不出区别 —— 中间那行真空的就被格式吃掉了。
     */
    private fun gridOf(book: com.fileforge.core.office.OdsBook): String {
        val out = StringBuilder()
        book.sheets.forEach { sheet ->
            out.append("表 ").append(sheet.name).append(" 表头=").append(sheet.header).append("\n")
            sheet.rows.forEachIndexed { index, row ->
                out.append("行 ").append(index + 1).append(": ").append(row.joinToString("␟")).append("\n")
            }
        }
        return out.toString()
    }

    private fun zipPart(bytes: ByteArray, part: String): ByteArray? {
        val archive = ZipReader.read(bytes)
        val slices = ZipReader.slicing(bytes)
        val entry = archive.entries.firstOrNull { it.name == part } ?: return null
        return ZipReader.dataOf(entry, slices)
    }

    private fun resource(area: String, name: String): String = String(resourceBytes(area, name), Charsets.UTF_8)

    private fun resourceBytes(area: String, name: String): ByteArray {
        val input = javaClass.classLoader.getResourceAsStream("$area/$name")
        requireNotNull(input) { "缺少夹具 $area/$name（先跑 tools/make_ods_fixtures.py）" }
        return input.readBytes()
    }

    private companion object {
        const val MIME = "application/vnd.oasis.opendocument.spreadsheet"
        const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        const val NAMESPACES =
            "<office:document-content " +
                "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
                "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
                "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" office:version=\"1.2\">"
    }
}
