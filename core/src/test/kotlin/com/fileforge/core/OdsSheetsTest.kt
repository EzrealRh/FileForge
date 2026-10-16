package com.fileforge.core

import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocTable
import com.fileforge.core.office.OdsSheet
import com.fileforge.core.office.OdsSheets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ODS 那几张表怎么变成"一节一节"：表名当小标题、每行补到最宽、真空表不占一节。
 *
 * 这几条是「转网页」与「印成 PDF」「写成 Word」共用的规矩，所以放在 `:core` 里钉住，
 * 不让两条出路一个按文件说的表头排、另一个把首行当普通数据。
 */
class OdsSheetsTest {

    private fun sheet(name: String, header: Boolean, rows: List<List<String>>) = OdsSheet(name, header, rows)

    @Test
    fun `一张表一节，表名是小标题`() {
        val parts = OdsSheets.parts(listOf(sheet("费用", true, listOf(listOf("项目", "金额"), listOf("打车", "38.5")))))
        assertEquals(2, parts.size)
        val title = parts[0] as DocParagraph
        assertEquals("费用", title.para.text)
        assertEquals("Heading2", title.para.style)
        val table = parts[1] as DocTable
        assertTrue(table.header, "文件自己写了首行是表头，就照它")
        assertEquals(listOf(listOf("项目", "金额"), listOf("打车", "38.5")), table.rows)
    }

    @Test
    fun `首行不是表头就按文件说的办`() {
        val table = OdsSheets.parts(listOf(sheet("草稿", false, listOf(listOf("甲", "1"))))).filterIsInstance<DocTable>().single()
        assertEquals(false, table.header)
    }

    @Test
    fun `短行补到最宽而不是往左挤`() {
        val table = OdsSheets.parts(listOf(sheet("表", false, listOf(listOf("甲", "乙", "丙"), listOf("1")))))
            .filterIsInstance<DocTable>().single()
        assertEquals(listOf(listOf("甲", "乙", "丙"), listOf("1", "", "")), table.rows)
    }

    @Test
    fun `真空表不占一节但要说出来`() {
        val parts = OdsSheets.parts(listOf(sheet("空的", false, emptyList()), sheet("全空格", false, listOf(listOf("", "")))))
        assertTrue(parts.isEmpty(), "两张都空，不该印出两张只有标题的白纸")
        assertEquals(listOf("2 张表是空的（空的、全空格），没占一节"), OdsSheets.notes(listOf(
            sheet("空的", false, emptyList()), sheet("全空格", false, listOf(listOf(""))),
        )))
    }

    @Test
    fun `说明里数的行列与实际排出去的一致`() {
        val sheets = listOf(
            sheet("费用", true, listOf(listOf("项目", "金额"), listOf("打车", "38.5"))),
            sheet("备注", false, listOf(listOf("一句话"))),
            sheet("空的", false, emptyList()),
        )
        val summary = OdsSheets.summary(sheets)
        assertEquals("2 张表 · 3 行", summary.first())
        assertEquals("费用：2 行 × 2 列", summary[1])
        assertEquals("备注：1 行 × 1 列", summary[2])
        assertEquals(4, OdsSheets.parts(sheets).size, "两张表 = 两个标题 + 两张表")
    }

    @Test
    fun `补齐只补到最宽那一列，不动已填的格`() {
        val padded = OdsSheets.padded(listOf(listOf("甲", "乙"), listOf("1"), emptyList()))
        assertEquals(listOf(listOf("甲", "乙"), listOf("1", ""), listOf("", "")), padded)
    }
}
