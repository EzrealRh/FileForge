package com.fileforge.core

import com.fileforge.core.doc.Html
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 网页表格抽格子。核心风险不是"字没抽出来"，而是**位置错**：
 * 一个 `<td colspan="3">` 不按跨度占位，整行就左移三格，出来的表看着齐、其实每一列都对不上号。
 * 所以这里的断言全是"哪个标记落在第几行第几列"，参照规矩是 pandas / 浏览器那一套（跨过的格子重复占位）。
 */
class HtmlTableTest {

    private fun tables(html: String) = Html.toTables(html)

    private fun one(html: String) = tables(html).single()

    /** 管道表里的对齐空格不该参与比对：连续空格并成一个。 */
    private fun String.collapse() = replace(Regex(" +"), " ")

    private fun page(body: String) = "<!DOCTYPE html><html><body>$body</body></html>"

    @Test
    fun `横着跨两格的那格占住两个位置`() {
        val table = one(page("<table><tr><td colspan=2>甲</td><td>乙</td></tr><tr><td>丙</td><td>丁</td><td>戊</td></tr></table>"))
        assertEquals(listOf(listOf("甲", "", "乙"), listOf("丙", "丁", "戊")), table.rows)
    }

    @Test
    fun `竖着跨两行的那格把下一行的位置也占住`() {
        val table = one(page(
            "<table><tr><td>甲</td><td rowspan=2>乙</td></tr><tr><td>丙</td></tr><tr><td>丁</td><td>戊</td></tr></table>",
        ))
        assertEquals(listOf(listOf("甲", "乙"), listOf("丙", ""), listOf("丁", "戊")), table.rows)
    }

    @Test
    fun `横竖一起跨时后面的格子按剩下的位置摆`() {
        val table = one(page(
            "<table>" +
                "<tr><td colspan=2 rowspan=2>大块</td><td>一</td></tr>" +
                "<tr><td>二</td></tr>" +
                "<tr><td>三</td><td>四</td><td>五</td></tr>" +
                "</table>",
        ))
        assertEquals(
            listOf(
                listOf("大块", "", "一"),
                listOf("", "", "二"),
                listOf("三", "四", "五"),
            ),
            table.rows,
        )
        assertTrue(table.notes.any { "colspan/rowspan" in it }, "跨度对位要说明：${table.notes}")
    }

    @Test
    fun `没有 tr 的散格子并成一行而不是丢掉`() {
        val table = one(page("<table><td>甲</td><td>乙</td></table>"))
        assertEquals(listOf(listOf("甲", "乙")), table.rows)
    }

    @Test
    fun `嵌进去的表另出一张，外层那张只留文字`() {
        val found = tables(
            page("<table><tr><th>外</th><td>话头<table><tr><td>内1</td><td>内2</td></tr></table></td></tr></table>"),
        )
        assertEquals(2, found.size, "外层与内层各一张")
        // 外层那一格放不下第二张表：按纯文本那套拍平成一行（制表符分列），另出一张才是完整的它
        assertEquals(listOf("外", "话头\n内1\t内2"), found[0].rows.single())
        assertEquals(listOf(listOf("内1", "内2")), found[1].rows)
        assertTrue(found[0].notes.any { "嵌着表" in it }, "${found[0].notes}")
    }

    @Test
    fun `表题当名字用，没有表题按序号`() {
        val found = tables(page("<table><caption>九月账单</caption><tr><td>甲</td></tr></table><table><tr><td>乙</td></tr></table>"))
        assertEquals(listOf("九月账单", "第 2 张表"), found.map { it.name })
        assertTrue(found[0].notes.any { "表题" in it }, "${found[0].notes}")
    }

    @Test
    fun `格子里的块与块之间换成行`() {
        val table = one(page("<table><tr><td>甲<br>乙</td><td><p>第一段</p><p>第二段</p></td><td><ul><li>一</li><li>二</li></ul></td></tr></table>"))
        val row = table.rows.single()
        assertEquals("甲\n乙", row[0], "br 是格内换行")
        assertEquals("第一段\n第二段", row[1], "两段挤在一格里也要分行")
        assertTrue("- 一" in row[2] && "- 二" in row[2], "列表照块级规矩写：${row[2]}")
    }

    @Test
    fun `空表与只有空行的表要说清楚`() {
        assertEquals(emptyList<List<String>>(), one(page("<table></table>")).rows)
        assertTrue(one(page("<table></table>")).notes.any { "没有格子" in it })
        val onlyBlank = one(page("<table><tr></tr><tr><td>尾</td></tr></table>"))
        assertEquals(listOf(listOf(""), listOf("尾")), onlyBlank.rows, "空行要留成一格空的行：丢掉它，后面的行就往上挪了一行")
    }

    @Test
    fun `跨度大得不合理要夹住并说明`() {
        val table = one(page("<table><tr><td colspan=99999>甲</td><td>乙</td></tr></table>"))
        assertEquals(1001, table.rows.single().size, "跨的那格夹到 1000，后面那格还在")
        assertTrue(table.notes.any { "大得不合理" in it }, "${table.notes}")
    }

    @Test
    fun `整页只有一张表也要按表来摆（html 包 body 包 table）`() {
        // 只看一层的容器判据会漏掉这种写法（body 里没有别的块级元素），邮件里全是这种整页一张表
        val wrapped = "<html><body><table><tr><td colspan=2>跨</td><td>第三格</td></tr></table></body></html>"
        assertEquals("跨\t\t第三格", Html.toPlainText(wrapped).text.trim())
        assertTrue("| 跨 | | 第三格 |" in Html.toMarkdown(wrapped).text.collapse(), Html.toMarkdown(wrapped).text)
    }

    @Test
    fun `纯文本与 Markdown 那条用的就是同一套对位`() {
        val source = page(
            "<table><tr><td colspan=2>跨</td><td>第三格</td></tr><tr><td>甲</td><td>乙</td><td>丙</td></tr></table>",
        )
        assertEquals(
            listOf("跨\t\t第三格", "甲\t乙\t丙"),
            Html.toPlainText(source).text.trim().lines(),
            "纯文本那条也要按跨度占位",
        )
        val markdown = Html.toMarkdown(source).text.trim().lines()
        assertTrue("| 跨 | | 第三格 |" in markdown.joinToString("¶").collapse(), markdown.joinToString("¶"))
        // 格子里的换行在表格里并成空格（一行一格才摆得下）
        assertTrue("甲 乙" in Html.toPlainText(page("<table><tr><td>甲<br>乙</td></tr></table>")).text)
    }

    @Test
    fun `表的先后就是文档里的先后`() {
        val found = tables(page("<table><tr><td>一</td></tr></table><p>中间</p><table><tr><td>二</td></tr></table>"))
        assertEquals(listOf(listOf(listOf("一")), listOf(listOf("二"))), found.map { it.rows })
    }

    @Test
    fun `th 与 td 一样是格子，标题行不特殊`() {
        val table = one(page("<table><thead><tr><th>列甲</th><th>列乙</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>"))
        assertEquals(listOf(listOf("列甲", "列乙"), listOf("1", "2")), table.rows)
    }

    @Test
    fun `格子里的实体与引号内容原样进格子`() {
        val table = one(page("<table><tr><td>a &amp; b</td><td>说\"你好\"</td><td>c &lt; d</td></tr></table>"))
        assertEquals(listOf("a & b", "说\"你好\"", "c < d"), table.rows.single())
    }
}
