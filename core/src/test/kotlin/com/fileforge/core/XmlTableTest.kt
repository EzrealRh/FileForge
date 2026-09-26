package com.fileforge.core

import com.fileforge.core.data.Csv
import com.fileforge.core.data.TableBridge
import com.fileforge.core.data.Xml
import com.fileforge.core.data.XmlTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * XML → 表的那一步"挑哪一处当行、每行怎么摊"。
 *
 * 这一步没有标准答案可抄（各家 xml→csv 挑法都不一样），所以判据盯的是**我们自己说清楚的那几条**：
 * 条数多者优先、单值嵌套摊成列而不是塞成一格 JSON、属性与同名元素不能互相盖掉、
 * 挑剩下的一处要报出来。会丢东西的那几条（多值嵌套、混形状）必须说话，不许静悄悄。
 */
class XmlTableTest {

    private fun rows(source: String) = XmlTable.pick(Xml.parse(source))

    @Test
    fun `多处重复元素时挑条数多的那处，另一处说出来`() {
        val picked = rows(
            """<catalog><book><title>甲</title><price>1</price></book><book><title>乙</title><price>2</price></book>""" +
                """<cd><name>丙</name><price>9</price></cd><cd><name>丁</name><price>8</price></cd></catalog>""",
        )!!
        assertEquals("catalog.book", picked.path)
        assertEquals(2, TableBridge.toTable(picked.rows)!!.rows.size)
        assertTrue(picked.notes.any { it.contains("cd") }, picked.notes.toString())
    }

    @Test
    fun `只有一值的嵌套摊成列而不是一格 JSON`() {
        val table = rows("""<r><item><author><name>张三</name></author><t>甲</t></item></r>""")!!
        val built = TableBridge.toTable(table.rows)!!
        assertEquals(listOf("author.name", "t"), built.columns)
        assertEquals(listOf(listOf("张三", "甲")), built.rows)
    }

    @Test
    fun `属性与同名元素各占一列`() {
        val built = TableBridge.toTable(rows("""<r><i id="属性里的"><id>元素里的</id></i></r>""")!!.rows)!!
        assertEquals(listOf("@id", "id"), built.columns)
        assertEquals("属性里的", built.rows.single()[0])
        assertEquals("元素里的", built.rows.single()[1])
    }

    @Test
    fun `元素自己的文字进文本列`() {
        val built = TableBridge.toTable(rows("""<r><i lang="zh">正文在这</i></r>""")!!.rows)!!
        assertEquals(listOf("@lang", "文本"), built.columns)
        assertEquals("正文在这", built.rows.single()[1])
    }

    @Test
    fun `全是标量的那处摆成单列，空元素是空格子`() {
        val table = rows("""<r><tags><tag>甲</tag><tag/><tag>丙</tag></tags></r>""")!!
        val built = TableBridge.toTable(table.rows)!!
        assertEquals(listOf("文本"), built.columns)
        assertEquals(listOf(listOf("甲"), listOf(""), listOf("丙")), built.rows)
    }

    @Test
    fun `多值的真嵌套压成一格文本并说明会压`() {
        val table = rows(
            """<r><i><name>甲</name><tags><tag>a</tag><tag>b</tag></tags></i>""" +
                """<i><name>乙</name><tags><tag>c</tag><tag>d</tag></tags></i></r>""",
        )!!
        val built = TableBridge.toTable(table.rows)!!
        assertEquals(listOf("name", "tags.tag"), built.columns)
        assertTrue(built.rows[0][1].startsWith("["), built.rows[0].toString())
        assertEquals(2, built.rows.size)
        // 压平是我们这一步做的，交代也得由这一步给（TableBridge 那侧看见的已经是文字了）
        assertTrue(table.notes.any { it.contains("压成了一格文本") && it.contains("tags.tag") }, table.notes.toString())
    }

    @Test
    fun `没有任何重复元素时直说并指下一步`() {
        // 只有"一个元素带一个值"的文档没有"行"可摆：这种整份搬进 JSON 才对
        assertNull(rows("""<root>只有一句正文</root>"""))
        val hint = XmlTable.reasonWhyNot(Xml.parse("""<root>只有一句正文</root>"""))
        assertTrue(hint!!.contains("XML 转为 JSON"), hint)
    }

    @Test
    fun `摊出来的列名撞上时编号而不是互相盖掉`() {
        // 元素名里本来就有个点：`a.b` 这一格会与 `a` 摊出来的 `a.b` 撞名
        val built = TableBridge.toTable(rows("""<r><i><a.b>1</a.b><a><b>2</b></a></i></r>""")!!.rows)!!
        assertEquals(listOf("a.b", "a.b#2"), built.columns, built.columns.toString())
        assertEquals(listOf("1", "2"), built.rows.single())
    }

    @Test
    fun `内层条数更多时取内层`() {
        val picked = rows(
            """<o><line><sku>a</sku><qty>1</qty></line><line><sku>b</sku><qty>2</qty></line>""" +
                """<meta><k>甲</k></meta></o>""",
        )!!
        assertEquals("o.line", picked.path)
    }

    @Test
    fun `整条路走通后 CSV 逐字对得上`() {
        val table = rows("""<r><i id="1"><n>甲</n></i><i id="2"><n>乙</n></i></r>""")!!
        val built = TableBridge.toTable(table.rows)!!
        // 属性那列留着 @ 前缀：与同名的元素列分开，不然两份数据会撞成一格
        assertEquals("@id,n\n1,甲\n2,乙\n", Csv.render(built.records))
    }
}
