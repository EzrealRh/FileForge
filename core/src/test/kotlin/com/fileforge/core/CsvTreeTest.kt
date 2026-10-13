package com.fileforge.core

import com.fileforge.core.doc.CsvTree
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.TextDoc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CSV / TSV 认成一张表的那条判断。
 *
 * 两条都要测：认出来要列对行对（这是"画成网格"的前提），认不出来要说出为什么
 * —— 把散文认成表比把表当散文更糟，而用户只看到"我的表怎么没画成表格"。
 */
class CsvTreeTest {

    private val PLAIN = "姓名,电话,备注\n张三,13800000000,周一到货\n李四,13900000000,\"加急, 别拆\"\n"

    @Test
    fun `三列两行认成一张表`() {
        val table = CsvTree.table(PLAIN)
        assertNotNull(table, "这是规整的三列表：${CsvTree.judge(PLAIN).second}")
        assertEquals(3, table!!.columns)
        assertEquals(3, table.rows)
        val only = table.parts.single() as DocTable
        assertTrue(only.header, "首行按表头排")
        assertEquals(listOf("张三", "13800000000", "周一到货"), only.rows[1])
    }

    @Test
    fun `引号里的逗号与换行都算一个字段`() {
        val text = "甲,乙\n\"一,二\",2\n\"三\n四\",3\n"
        val table = CsvTree.table(text)
        assertNotNull(table, "引号里的分隔符不该拆列：${CsvTree.judge(text).second}")
        val rows = (table!!.parts.single() as DocTable).rows
        assertEquals(listOf("一,二", "2"), rows[1])
        assertEquals("三\n四", rows[2][0])
        assertEquals(2, table.columns, "带换行的字段仍是两列")
    }

    @Test
    fun `制表符与分号的表也认`() {
        listOf("甲\t乙\n1\t2\n", "甲;乙\n1;2\n", "甲|乙\n1|2\n").forEach { text ->
            val table = CsvTree.table(text)
            assertNotNull(table, "这些分隔符都是真实文件里的写法：${CsvTree.judge(text).second}")
            assertEquals(2, table!!.columns)
        }
    }

    @Test
    fun `CRLF 收尾与末尾空行都不多出一行`() {
        val table = CsvTree.table("甲,乙\r\n1,2\r\n")
        assertNotNull(table, "Windows 导出的表几乎都是 CRLF：${CsvTree.judge("甲,乙\r\n1,2\r\n").second}")
        assertEquals(2, table!!.rows, "以换行收尾不该凭空多出一行空数据")
    }

    @Test
    fun `散文不认成表`() {
        val prose = "the market was open, I bought three things, and walked home\n            it started to rain, halfway back, quite suddenly\n            this reminded me, how life goes\n"
        assertNull(CsvTree.table(prose), "列数不齐的三行散文被认成了表")
        assertTrue(CsvTree.judge(prose).second.contains("列数"), "要说出为什么不算表：${CsvTree.judge(prose).second}")
    }

    @Test
    fun `列数不齐时点名第几行`() {
        val text = "甲,乙,丙\n1,2,3\n4,5\n6,7,8,9\n"
        val why = CsvTree.judge(text).second
        assertTrue(why.contains("列数") && why.contains("2") && why.contains("3"), "要指出是第 2、3 行：$why")
    }

    @Test
    fun `一行不认成表`() {
        assertTrue(CsvTree.judge("甲,乙,丙\n").second.contains("行"), "只有表头不算表")
        assertNull(CsvTree.table("甲,乙,丙\n"))
    }

    @Test
    fun `没有分隔符时不硬认`() {
        val why = CsvTree.judge("今天很好\n明天更好\n").second
        assertTrue(why.contains("列"), "要说每行只有一列：$why")
    }

    @Test
    fun `超长的表截到上限并写明共多少行`() {
        val text = buildString {
            appendLine("列甲,列乙")
            repeat(CsvTree.MAX_ROWS + 7) { appendLine("行$it,值$it") }
        }
        val table = CsvTree.table(text)
        assertNotNull(table, "一列一列的行数超上限也要认：${CsvTree.judge(text).second}")
        assertEquals(CsvTree.MAX_ROWS, table!!.rows, "排出来的行数就是上限（含表头）")
        assertTrue(
            table.notes.any { it.contains("共 ${CsvTree.MAX_ROWS + 8} 行") && it.contains("只排前") },
            "必须写明截断：${table.notes}",
        )
    }

    @Test
    fun `首行按表头这一主张写进说明`() {
        assertTrue(CsvTree.table(PLAIN)!!.notes.any { it.contains("表头") }, CsvTree.table(PLAIN).toString())
    }

    // ---- 与那三条老路的关系：优先级必须是网页 > Markdown > 表 > 纯文本 ---------------------

    @Test
    fun `CSV 走 TextDoc 拿到的就是那张表`() {
        val reading = TextDoc.read(PLAIN)
        assertEquals(TextDoc.Route.Table, reading.route, "该按分隔符分成一张表")
        assertTrue(reading.doc.parts.any { it is DocTable }, "树里要有一张表")
    }

    @Test
    fun `Markdown 与网页仍优先按各自的结构排`() {
        val markdown = "| 甲 | 乙 |\n| --- | --- |\n| 1 | 2 |\n"
        assertEquals(TextDoc.Route.Markdown, TextDoc.read(markdown).route, "管道写的表该按 Markdown 排")
        val web = "<html><body><table><tr><td>甲</td><td>乙</td></tr></table></body></html>"
        assertEquals(TextDoc.Route.Web, TextDoc.read(web).route, "有网页骨架就该按网页排")
    }

    @Test
    fun `普通文本不会被误领进表那条路`() {
        assertEquals(TextDoc.Route.Plain, TextDoc.read("第一段说一件事。\n第二段说另一件事。\n").route)
    }
}
