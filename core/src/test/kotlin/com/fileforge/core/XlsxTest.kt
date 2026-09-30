package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.data.Csv
import com.fileforge.core.data.Delimiter
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.Html
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.office.DocxWrite
import com.fileforge.core.office.OoxmlStructure
import com.fileforge.core.office.Xlsx
import com.fileforge.core.office.XlsxSheets
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * xlsx 读格子。手写片段钉住取值规矩，真文件（openpyxl 压的）钉住"跟别人读出来一样"。
 *
 * 参照值来自 `tools/make_office_fixtures.py` 里 openpyxl **读回来**看到的样子。
 */
class XlsxTest {

    private val NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private fun sheetXml(body: String) = ("<worksheet $NS><sheetData>$body</sheetData></worksheet>").toByteArray()

    private fun styles(body: String) = ("<styleSheet $NS>$body</styleSheet>").toByteArray()

    private fun read(rows: String, strings: List<String> = emptyList(), dates: Set<Int> = emptySet()) =
        Xlsx.sheet("测试", sheetXml(rows), strings, dates)

    // ---- 引用与对位 ---------------------------------------------------------------

    @Test
    fun `列字母换成列号`() {
        assertEquals(0, Xlsx.columnOf("A1"))
        assertEquals(25, Xlsx.columnOf("Z1"))
        assertEquals(26, Xlsx.columnOf("AA1"))
        assertEquals(28, Xlsx.columnOf("AC12"))
    }

    @Test
    fun `稀疏格子按引用对位而不是按出现顺序`() {
        // r="3" 说这是第三行：前面的行号要留成空行，格子里的列位也要对得上
        val sheet = read("<row r=\"3\"><c r=\"C3\" t=\"inlineStr\"><is><t>丙</t></is></c></row>")
        assertEquals(3, sheet.rows.size)
        assertEquals(listOf("", "", "丙"), sheet.rows[2])
    }

    @Test
    fun `行号能跳过，跳过的行留成空行`() {
        val sheet = read(
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>头</t></is></c></row>" +
                "<row r=\"5\"><c r=\"A5\" t=\"inlineStr\"><is><t>尾</t></is></c></row>",
        )
        assertEquals(5, sheet.rows.size, "第五行不能顶到第二行：${sheet.rows}")
        assertEquals("头", sheet.rows[0][0])
        assertEquals("尾", sheet.rows[4][0])
    }

    @Test
    fun `列号离谱的格子丢掉并说明，不撑爆内存`() {
        val sheet = read("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>好</t></is></c><c r=\"AAAAAA1\"/></row>")
        assertTrue(sheet.notes.any { "上限" in it }, "要说清丢了格子：${sheet.notes}")
        assertEquals(listOf(listOf("好")), sheet.rows)
    }

    @Test
    fun `行号超出上限直接报错`() {
        val error = runCatching { read("<row r=\"99999999\"><c r=\"A99999999\"/></row>") }.exceptionOrNull()
        assertTrue(error != null && "上限" in (error.message ?: ""), "该报错而不是排两亿行：${error?.message}")
    }

    // ---- 格子类型 ----------------------------------------------------------------

    @Test
    fun `六种类型各自的取值位置`() {
        val sheet = read(
            "<row r=\"1\">" +
                "<c r=\"A1\" t=\"s\"><v>1</v></c>" +
                "<c r=\"B1\" t=\"inlineStr\"><is><t>内</t><t>联</t></is></c>" +
                "<c r=\"C1\" t=\"str\"><v>公式结果</v></c>" +
                "<c r=\"D1\" t=\"b\"><v>1</v></c>" +
                "<c r=\"E1\" t=\"e\"><v>#N/A</v></c>" +
                "<c r=\"F1\"><v>42</v></c>" +
                "</row>",
            strings = listOf("零号串", "共享串"),
        )
        assertEquals(listOf("共享串", "内联", "公式结果", "TRUE", "#N/A", "42"), sheet.rows.single())
    }

    @Test
    fun `越界的共享串索引给空格并说明，不是崩`() {
        val sheet = read("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>9</v></c></row>", strings = listOf("只有一个"))
        assertEquals(emptyList<List<String>>(), sheet.rows, "整行都是空格子时不留行")
        assertTrue(sheet.notes.any { "找不到" in it }, "要说明有格子指向了不存在的串：${sheet.notes}")
    }

    @Test
    fun `数字写法不被改写`() {
        // 表格里 1.50 与 1.5 是两回事（前者是作者定的精度），CSV 里也不该被"顺手规范化"
        val sheet = read("<row r=\"1\"><c r=\"A1\"><v>1.50</v></c><c r=\"B1\"><v>007</v></c></row>")
        assertEquals(listOf("1.50", "007"), sheet.rows.single())
    }

    @Test
    fun `富文本共享串把各段拼成一段话`() {
        val part = ("<sst $NS><si><t>前面</t></si><si><r><rPr/><t>粗</t></r><r><t>体</t></r></si></sst>").toByteArray()
        assertEquals(listOf("前面", "粗体"), Xlsx.sharedStrings(part))
    }

    @Test
    fun `没算过结果的公式给空格并说明`() {
        val sheet = read(
            "<row r=\"1\"><c r=\"A1\"><f>1+1</f></c><c r=\"B1\"><f t=\"shared\" si=\"0\"/></c>" +
                "<c r=\"C1\" t=\"inlineStr\"><is><t>有值</t></is></c></row>",
        )
        assertEquals(listOf("", "", "有值"), sheet.rows.single())
        assertTrue(sheet.notes.any { "公式" in it }, "要说明有格子没结果：${sheet.notes}")
    }

    // ---- 日期 -------------------------------------------------------------------

    private val stylesPart = styles(
        "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy&quot;年&quot;m&quot;月&quot;d&quot;日&quot;\"/></numFmts>" +
            "<cellXfs count=\"4\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/><xf numFmtId=\"164\"/><xf numFmtId=\"21\"/></cellXfs>",
    )

    @Test
    fun `样式说这是日期才转，纯数字样式不动`() {
        val dates = Xlsx.dateStyles(stylesPart)
        assertEquals(setOf(1, 2, 3), dates)
        val sheet = read(
            "<row r=\"1\"><c r=\"A1\" s=\"1\"><v>45047</v></c><c r=\"B1\"><v>45047</v></c>" +
                "<c r=\"C1\" s=\"2\"><v>44259</v></c></row>",
            dates = dates,
        )
        assertEquals(listOf("2023-05-01", "45047", "2021-03-04"), sheet.rows.single())
    }

    @Test
    fun `序列号换算照 Excel 的写法`() {
        assertEquals("1900-01-01", Xlsx.serialToText("1", false))
        assertEquals("1900-02-28", Xlsx.serialToText("59", false))
        assertEquals("1900-02-29", Xlsx.serialToText("60", false))        // 那个不存在的兼容日
        assertEquals("1900-03-01", Xlsx.serialToText("61", false))
        assertEquals("2023-05-01", Xlsx.serialToText("45047", false))
        assertEquals("2024-02-29 08:30:15", Xlsx.serialToText("45351.354340277778", false))
        assertEquals("12:00:00", Xlsx.serialToText("0.5", false))         // 只有时间
        assertEquals("1904-01-02", Xlsx.serialToText("1", true))
        assertEquals("45047x", Xlsx.serialToText("45047x", false), "读不出数字就原样给，不编一个日期")
    }

    @Test
    fun `格式码判日期：字面文字与颜色段里的字母不算`() {
        assertTrue(Xlsx.isDateFormat("yyyy-mm-dd"))
        assertTrue(Xlsx.isDateFormat("h:mm:ss"))
        assertTrue(Xlsx.isDateFormat("[红色]yyyy"))
        assertTrue(Xlsx.isDateFormat("yyyy\"年\"m\"月\"d\"日\""))
        assertTrue(!Xlsx.isDateFormat("\"日期\""))
        assertTrue(!Xlsx.isDateFormat("0.00"))
        assertTrue(!Xlsx.isDateFormat("General"))
        assertTrue(!Xlsx.isDateFormat("\$#,##0.00"))
        assertTrue(!Xlsx.isDateFormat("@"))
    }

    @Test
    fun `工作簿自己声明的纪元被读出来`() {
        assertTrue(Xlsx.uses1904(("<workbook $NS><workbookPr date1904=\"1\"/></workbook>").toByteArray()))
        assertTrue(!Xlsx.uses1904(("<workbook $NS><workbookPr date1904=\"0\"/></workbook>").toByteArray()))
        assertTrue(!Xlsx.uses1904(("<workbook $NS></workbook>").toByteArray()))
        assertTrue(!Xlsx.uses1904(null))
    }

    // ---- 真文件：openpyxl 压的，参照值是它自己读回来的 --------------------------------

    private val entries: List<String> by lazy { ZipReader.read(book).entries.map { it.name } }

    private fun part(name: String): ByteArray {
        val entry = ZipReader.read(book).entries.firstOrNull { it.name == name }
        requireNotNull(entry) { "包里没有 $name" }
        return ZipReader.dataOf(entry, ZipReader.slicing(book))
    }

    private fun load(name: String): ByteArray? = if (name in entries) part(name) else null

    private val book: ByteArray
        get() = javaClass.classLoader.getResourceAsStream("office/book.xlsx").use { input ->
            requireNotNull(input) { "缺少夹具 office/book.xlsx，先跑 python tools/make_office_fixtures.py" }
            ByteArrayOutputStream().also { output -> input.copyTo(output) }.toByteArray()
        }

    private fun truthBlocks(): List<Pair<String, List<String>>> {
        val text = javaClass.classLoader.getResourceAsStream("office/book.xlsx.truth").use { input ->
            requireNotNull(input) { "缺少 book.xlsx.truth，先跑 python tools/make_office_fixtures.py" }
            String(ByteArrayOutputStream().also { output -> input.copyTo(output) }.toByteArray(), Charsets.UTF_8)
        }
        val out = ArrayList<Pair<String, List<String>>>()
        var name: String? = null
        var rows = ArrayList<String>()
        text.trimEnd('\n').lines().forEach { line ->
            if (line.startsWith("# ")) {
                if (name != null) out += name!! to rows
                name = line.removePrefix("# ")
                rows = ArrayList()
            } else if (line.isNotBlank() || rows.isNotEmpty()) {
                rows += line
            }
        }
        if (name != null) out += name to rows
        return out
    }

    @Test
    fun `表名与顺序照工作簿声明的来`() {
        val sheets = Xlsx.sheets { load(it) }
        assertEquals(listOf("费用", "空表", "第三张"), sheets.map { it.name })
        assertEquals(
            listOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml"),
            sheets.map { it.part },
        )
    }

    @Test
    fun `每张表逐格与 openpyxl 读回来的相同`() {
        val strings = Xlsx.sharedStrings(load(Xlsx.SHARED_STRINGS))
        val dates = Xlsx.dateStyles(load(Xlsx.STYLES))
        val blocks = truthBlocks()
        assertEquals(3, blocks.size, "参照值该有三张表")
        val epoch = Xlsx.uses1904(load(OoxmlStructure.WORKBOOK))
        Xlsx.sheets { load(it) }.zip(blocks).forEach { (ref, block) ->
            assertEquals(block.first, ref.name, "表名要对得上")
            val mine = Xlsx.sheet(ref.name, part(requireNotNull(ref.part)), strings, dates, epoch)
            assertEquals(block.second, mine.rows.map { row -> row.joinToString("\t") })
        }
    }

    /** 给 tools/verify_xlsx.py：每张表一份 CSV，表名进文件名。 */
    @Test
    fun `把每张表落成 CSV`() {
        val strings = Xlsx.sharedStrings(load(Xlsx.SHARED_STRINGS))
        val dates = Xlsx.dateStyles(load(Xlsx.STYLES))
        val epoch = Xlsx.uses1904(load(OoxmlStructure.WORKBOOK))
        val dir = File("build/office").apply { mkdirs() }
        Xlsx.sheets { load(it) }.forEachIndexed { index, ref ->
            val name = ref.part ?: return@forEachIndexed
            val sheet = Xlsx.sheet(ref.name, part(name), strings, dates, epoch)
            val body = Csv.render(sheet.rows, Delimiter.Comma)
            File(dir, "book.xlsx.sheet${index + 1}.csv").writeText(body, Charsets.UTF_8)
            File(dir, "book.xlsx.sheet${index + 1}.notes").writeText(sheet.notes.joinToString("\n"), Charsets.UTF_8)
        }
        assertEquals(3, dir.listFiles()?.count { it.name.startsWith("book.xlsx") && it.name.endsWith(".csv") })
    }

    // ---- 一批表 → 文档树（转网页 / 转 Markdown / 写成 Word 与电子书共用这一份）--------

    private fun tree() = XlsxSheets.book { load(it) }

    private fun tables() = XlsxSheets.parts(tree().sheets).filterIsInstance<DocTable>()

    @Test
    fun `每张表一节_表名当小标题`() {
        val parts = XlsxSheets.parts(tree().sheets)
        assertEquals(listOf("费用", "第三张"), parts.filterIsInstance<DocParagraph>().map { it.para.text })
        assertEquals(listOf(7, 1), tables().map { it.rows.size })
    }

    @Test
    fun `空表不占一节但被说出来`() {
        val notes = XlsxSheets.notes(tree().sheets)
        assertTrue(notes.any { "空表" in it && "没占一节" in it }, notes.toString())
        val made = XlsxSheets.parts(tree().sheets).filterIsInstance<DocTable>()
        assertEquals("${made.size} 张表 · ${made.sumOf { it.rows.size }} 行", XlsxSheets.summary(tree().sheets).first())
    }

    @Test
    fun `首行不被抬成表头`() {        // 普通区域里 xlsx 没说"首行是表头"：那是「格式化为表格」时由 xl/tables 另外声明的事
        assertEquals(listOf(false, false), tables().map { it.header })
    }

    @Test
    fun `稀疏的行列补在文件说的那个位置上`() {
        assertEquals(listOf(5, 1), tables().map { it.rows.maxOf { row -> row.size } })
        val table = tables().first()
        assertTrue(table.rows.all { it.size == 5 }, "有行没补齐到 5 列：${table.rows}")
        assertEquals("第五行只有 D 列有东西", table.rows[4][3])
        assertEquals("", table.rows[4].first())
        assertEquals("纯文字", table.rows[6][1])
    }

    @Test
    fun `日期与数字的写法照读回来的样子进格子`() {
        val rows = tables().first().rows
        assertEquals("38.5", rows[1][1])
        assertTrue(rows[1][2].matches(Regex("""\d{4}-\d{2}-\d{2}.*""")), "日期没转成 ISO 写法：${rows[1][2]}")
        assertTrue(rows.flatten().none { it.matches(Regex("""\d{4,6}""")) }, "格子里还留着序列号")
        assertEquals("含中文的格子，带逗号,", rows[3][0])
    }

    @Test
    fun `部件找不到的那张表进 reasons_不静悄悄少一张`() {
        val refs = Xlsx.sheets { load(it) }
        val lost = refs.mapNotNull { it.part }.first()
        val read = XlsxSheets.book { name -> if (name == lost) null else load(name) }
        assertEquals(refs.size - 1, read.sheets.size)
        assertEquals(1, read.reasons.size)
        assertTrue(read.reasons.single().isNotBlank(), read.reasons.toString())
    }

    /**
     * 真实夹具的三条产物落盘：判据跑的是磁盘上这些文件。
     *
     * 断言放在产文件**之后**：先断言会让坏实现把上一轮的旧产物留下，外部判据拿着旧文件说全绿。
     */
    @Test
    fun `表的产物落盘供外部判据对照`() {
        val dir = File("build/xlsxread").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
        val read = tree()
        val parts = XlsxSheets.parts(read.sheets)
        require(parts.isNotEmpty()) { "什么块都没读到，夹具或读法坏了" }
        val grid = read.sheets.flatMap { sheet ->
            listOf("表 ${sheet.name}") + sheet.rows.map { row -> row.joinToString("\t") }
        }.joinToString("\n")
        File(dir, "book.xlsx").writeBytes(book)
        File(dir, "book.grid.txt").writeText("$grid\n", Charsets.UTF_8)
        File(dir, "book.md").writeText(Html.toMarkdown(HtmlWrite.body(parts)).text, Charsets.UTF_8)
        File(dir, "book.html").writeText(HtmlWrite.page("book", parts, language = "zh").html, Charsets.UTF_8)
        File(dir, "book.docx").writeBytes(DocxWrite.document(Doc(parts, emptyList()), modifiedAt = 0L).bytes)
        File(dir, "book.notes.txt").writeText(
            (XlsxSheets.summary(read.sheets) + XlsxSheets.notes(read.sheets) + read.reasons)
                .joinToString("\n") + "\n",
            Charsets.UTF_8,
        )
        assertEquals(
            3,
            dir.listFiles().orEmpty().count {
                it.name.endsWith(".md") || it.name.endsWith(".html") || it.name.endsWith(".docx")
            },
        )
    }
}
