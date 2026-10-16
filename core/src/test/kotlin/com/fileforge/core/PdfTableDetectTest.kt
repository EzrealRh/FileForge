package com.fileforge.core

import com.fileforge.core.pdf.PdfTableDetect
import com.fileforge.core.pdf.PdfTableSheets
import com.fileforge.core.pdf.PdfWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 从纸上的词坐标把行列还原回来。
 *
 * 全部用手工坐标：一份"看得见的表"在这里就是几行带左右边界的词，
 * 真实 PDF 的那一层（词从哪来）由 `tools/pdfprobe` 在桌面上对着 pdfplumber 量。
 */
class PdfTableDetectTest {

    private fun word(page: Int, top: Float, left: Float, right: Float, text: String) =
        PdfWord(text, left, right, top, top + 10f, page)

    /** 一行：格子的起点依次排在 56 / 200 / 330，每格宽 30。 */
    private fun row(page: Int, top: Float, cells: List<String>, width: Float = 30f): List<PdfWord> {
        val starts = listOf(56f, 200f, 330f)
        return cells.mapIndexed { index, text ->
            word(page, top, starts[index], starts[index] + width * text.length / 2f, text)
        }
    }

    @Test
    fun `三列四行的规整表按格子还原`() {
        val words = listOf(
            row(0, 100f, listOf("单号", "品名", "数量")),
            row(0, 120f, listOf("A-001", "机械键盘", "38")),
            row(0, 140f, listOf("A-002", "显示器", "12")),
            row(0, 160f, listOf("A-003", "台灯", "9")),
        ).flatten()
        val result = PdfTableDetect.tablesOf(words)
        assertEquals(1, result.tables.size, "该认出一张表：${result.notes}")
        val table = result.tables.first()
        assertEquals(listOf("单号", "品名", "数量"), table.rows.first())
        assertEquals(listOf("A-002", "显示器", "12"), table.rows[2])
        assertEquals(4, table.rows.size)
        assertEquals(2, table.boundaries.size, "三列要两处列边界")
    }

    @Test
    fun `一格里的多个词并成一格而不是多拆一列`() {
        val words = listOf(
            listOf(
                word(0, 100f, 56f, 86f, "品名"),
                word(0, 100f, 200f, 260f, "机械 键盘"),
                word(0, 100f, 330f, 360f, "数量"),
            ),
            listOf(
                word(0, 120f, 56f, 86f, "显示器"),
                word(0, 120f, 200f, 260f, "落地 支架"),
                word(0, 120f, 330f, 360f, "12"),
            ),
        ).flatten()
        val table = PdfTableDetect.tablesOf(words).tables.single()
        assertEquals(listOf("品名", "机械 键盘", "数量"), table.rows.first(), "同一格里的词要用空格并起来")
    }

    @Test
    fun `夹在表里的整句标题不入表并说明有几行没分成列`() {
        val words = listOf(
            row(0, 100f, listOf("单号", "品名", "数量")),
            row(0, 120f, listOf("A-001", "机械键盘", "38")),
            listOf(word(0, 140f, 56f, 260f, "本季度采购汇总")),
            row(0, 160f, listOf("A-002", "显示器", "12")),
            row(0, 180f, listOf("A-003", "台灯", "9")),
        ).flatten()
        val result = PdfTableDetect.tablesOf(words)
        assertEquals(2, result.tables.size, "标题把那一段切成两张表：${result.notes}")
        assertTrue(result.tables.all { it.rows.size >= 2 }, "每张表至少两行")
        assertTrue(result.notes.any { "没分成列" in it }, "要写明有几行只当正文：${result.notes}")
    }

    @Test
    fun `每行的空隙不在同一处就不算表`() {
        val words = listOf(
            listOf(word(0, 100f, 56f, 96f, "姓名"), word(0, 100f, 200f, 240f, "电话")),
            listOf(word(0, 120f, 56f, 96f, "住址"), word(0, 120f, 250f, 290f, "信箱")),
        ).flatten()
        val result = PdfTableDetect.tablesOf(words)
        assertTrue(result.tables.isEmpty(), "空隙对不齐不该硬凑一张表：${result.tables.map { it.rows }}")
        assertTrue(result.notes.any { "没找到对齐的空隙" in it }, "要说出为什么：${result.notes}")
    }

    @Test
    fun `一行文字不叫表`() {
        val result = PdfTableDetect.tablesOf(row(0, 100f, listOf("单号", "品名", "数量")))
        assertTrue(result.tables.isEmpty())
        assertTrue(result.notes.any { "只有 1 行" in it }, result.notes.toString())
    }

    @Test
    fun `挨得太近的空隙是字距不是列距`() {
        val words = listOf(
            listOf(word(0, 100f, 56f, 86f, "机械"), word(0, 100f, 88f, 118f, "键盘"), word(0, 100f, 330f, 360f, "38")),
            listOf(word(0, 120f, 56f, 86f, "显示"), word(0, 120f, 88f, 118f, "支架"), word(0, 120f, 330f, 360f, "12")),
        ).flatten()
        val result = PdfTableDetect.tablesOf(words)
        val table = result.tables.singleOrNull()
        assertTrue(table != null, "两行的表该认出来：${result.notes}")
        assertEquals(listOf("机械 键盘", "38"), table!!.rows.first(), "2pt 的空隙该并成同一格：${table.rows}")
        assertEquals(1, table.boundaries.size, "字距那一处不许当列边界：${table.boundaries}")
    }

    @Test
    fun `两页各认一张表并标清是哪一页`() {
        val words = listOf(
            row(0, 100f, listOf("单号", "品名", "数量")),
            row(0, 120f, listOf("A-1", "键盘", "38")),
            row(0, 140f, listOf("A-2", "显示器", "12")),
            row(1, 100f, listOf("单号", "品名", "数量")),
            row(1, 120f, listOf("B-1", "台灯", "9")),
            row(1, 140f, listOf("B-2", "插线板", "4")),
        ).flatten()
        val tables = PdfTableDetect.tablesOf(words).tables
        assertEquals(2, tables.size)
        assertEquals(listOf(0, 1), tables.map { it.page })
        assertEquals("A-1", tables.first().rows[1][0])
        assertEquals("B-1", tables.last().rows[1][0])
    }

    @Test
    fun `密表与疏表用同一套行容差`() {
        // 密表：行距 6pt、行高 6pt；疏表：行距 28pt、行高 28pt。写死绝对阈值必有一边错
        val dense = (0 until 4).flatMap { index ->
            listOf(
                word(0, 100f + index * 6f, 56f, 80f, "甲$index"),
                word(0, 100f + index * 6f, 200f, 224f, "乙$index"),
            )
        }
        val denseResult = PdfTableDetect.tablesOf(dense.map { PdfWord(it.text, it.left, it.right, it.top, it.top + 6f, 0) })
        assertEquals(4, denseResult.tables.singleOrNull()?.rows?.size, "密表 4 行不许并成 2 行：${denseResult.notes}")

        val loose = (0 until 4).flatMap { index ->
            listOf(
                word(0, 100f + index * 28f, 56f, 80f, "甲$index"),
                word(0, 100f + index * 28f, 200f, 224f, "乙$index"),
            )
        }
        val looseResult = PdfTableDetect.tablesOf(loose.map { PdfWord(it.text, it.left, it.right, it.top, it.top + 28f, 0) })
        assertEquals(4, looseResult.tables.singleOrNull()?.rows?.size, "疏表 4 行不许拆成 8 行：${looseResult.notes}")
    }

    private fun table(page: Int, rows: List<List<String>>) =
        PdfTableDetect.Detected(page, 100f, 400f, rows, listOf(200f, 330f))

    @Test
    fun `一页上两块表各认各的，夹着的整句不把整页否了`() {
        val words = listOf(
            row(0, 100f, listOf("单号", "品名", "数量")),
            row(0, 118f, listOf("A-1", "键盘", "38")),
            row(0, 136f, listOf("A-2", "显示器", "12")),
            // 一句横跨两列的正文：它把上面那块与下面那块隔开，但不该把两块的列全否决
            listOf(word(0, 160f, 56f, 520f, "本季度采购汇总，后面这些字一路排到纸边，跨过上面每一处列起点")),
            listOf(word(0, 180f, 56f, 86f, "姓名"), word(0, 180f, 120f, 160f, "电话")),
            listOf(word(0, 198f, 56f, 86f, "住址"), word(0, 198f, 120f, 160f, "信箱")),
            listOf(word(0, 216f, 56f, 86f, "邮箱"), word(0, 216f, 120f, 160f, "备注")),
        ).flatten()
        val result = PdfTableDetect.tablesOf(words)
        assertEquals(2, result.tables.size, "该认出两张：${result.notes}")
        val (book, contact) = result.tables
        assertEquals(2, book.boundaries.size, "第一张三列要两处边界：${book.boundaries}")
        assertEquals(1, contact.boundaries.size, "第二张两列要一处边界：${contact.boundaries}")
        assertEquals(listOf("单号", "品名", "数量"), book.rows.first())
        assertEquals(listOf("姓名", "电话"), contact.rows.first())
        assertTrue(result.notes.any { "没分成列" in it }, "要说那一句没进表：${result.notes}")
    }

    @Test
    fun `整行只有一格有字也还是表里的一行`() {
        val words = listOf(
            row(0, 100f, listOf("单号", "品名", "数量")),
            row(0, 118f, listOf("A-1", "键盘", "38")),
            // 只有中间那一格有字（另外两格在纸上是空的）
            listOf(word(0, 136f, 200f, 240f, "2024-02-29 08:30:15")),
            row(0, 154f, listOf("A-2", "显示器", "12")),
        ).flatten()
        val table = PdfTableDetect.tablesOf(words).tables.singleOrNull()
        assertTrue(table != null, "四行该是一张表，不该被那行空格子劈成两张")
        assertEquals(4, table!!.rows.size, "行数：${table.rows}")
        assertEquals(listOf("", "2024-02-29 08:30:15", ""), table.rows[2])
    }

    @Test
    fun `同一格里换行并回上一格而不是另起一行`() {
        val words = listOf(
            listOf(word(0, 100f, 56f, 86f, "编号"), word(0, 100f, 200f, 300f, "说明")),
            listOf(word(0, 118f, 56f, 86f, "1"), word(0, 118f, 200f, 300f, "一段写得长了一些的话")),
            // 紧接着的一行贴得比一个行距近，而且只有那一格有字：是同一格接着排
            listOf(word(0, 132f, 200f, 280f, "的后半句")),
            listOf(word(0, 150f, 56f, 86f, "2"), word(0, 150f, 200f, 260f, "第二行短")),
        ).flatten()
        val table = PdfTableDetect.tablesOf(words).tables.single()
        assertEquals(3, table.rows.size, "换行不该多出一行：${table.rows}")
        assertEquals("一段写得长了一些的话\n的后半句", table.rows[1][1])
        assertEquals(listOf("2", "第二行短"), table.rows[2])
    }

    @Test
    fun `相邻两页同列数的表接成一张并去掉重印的表头`() {
        val detect = PdfTableDetect.Result(
            listOf(
                table(0, listOf(listOf("单号", "品名"), listOf("A-1", "键盘"))),
                table(1, listOf(listOf("单号", "品名"), listOf("A-2", "显示器"))),
            ),
            emptyList(),
        )
        val read = PdfTableSheets.from(detect)
        assertEquals(1, read.sheets.size, "该接成一张：${read.notes}")
        assertEquals(listOf(listOf("单号", "品名"), listOf("A-1", "键盘"), listOf("A-2", "显示器")), read.sheets.first().rows)
        assertEquals(listOf(0, 1), read.sheets.first().pages)
        assertTrue(read.notes.any { "只留一次" in it }, "要交代表头去重了：${read.notes}")
    }

    @Test
    fun `续页第一行不是表头就原样接上`() {
        val detect = PdfTableDetect.Result(
            listOf(
                table(0, listOf(listOf("单号", "品名"), listOf("A-1", "键盘"))),
                table(1, listOf(listOf("B-1", "台灯"), listOf("B-2", "插线板"))),
            ),
            emptyList(),
        )
        val sheet = PdfTableSheets.from(detect).sheets.single()
        assertEquals(4, sheet.rows.size, "不相同的第一行是数据，不许当表头吞掉")
        assertEquals(listOf("B-1", "台灯"), sheet.rows[2])
    }

    @Test
    fun `同一页上的两张表各算一张`() {
        val detect = PdfTableDetect.Result(
            listOf(
                table(0, listOf(listOf("单号", "品名"), listOf("A-1", "键盘"))),
                table(0, listOf(listOf("姓名", "电话"), listOf("张三", "13800000000"))),
            ),
            emptyList(),
        )
        val read = PdfTableSheets.from(detect)
        assertEquals(2, read.sheets.size, "同一页两张不许并：${read.sheets.map { it.rows }}")
        assertTrue(read.notes.any { "各算一张" in it }, "要说明为什么分开了：${read.notes}")
    }

    @Test
    fun `隔了一页或列数不同都不接`() {
        val gap = PdfTableDetect.Result(
            listOf(
                table(0, listOf(listOf("单号", "品名"), listOf("A-1", "键盘"))),
                table(2, listOf(listOf("单号", "品名"), listOf("A-2", "显示器"))),
            ),
            emptyList(),
        )
        assertEquals(2, PdfTableSheets.from(gap).sheets.size, "中间跳了一页，不能假定还是同一张")

        val mismatched = PdfTableDetect.Result(
            listOf(
                table(0, listOf(listOf("单号", "品名"), listOf("A-1", "键盘"))),
                table(1, listOf(listOf("单号", "品名", "数量"), listOf("A-2", "显示器", "12"))),
            ),
            emptyList(),
        )
        assertEquals(2, PdfTableSheets.from(mismatched).sheets.size, "列数不同就不是同一张")
    }
}
