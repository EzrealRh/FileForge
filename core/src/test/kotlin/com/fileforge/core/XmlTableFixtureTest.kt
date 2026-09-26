package com.fileforge.core

import com.fileforge.core.data.Csv
import com.fileforge.core.data.TableBridge
import com.fileforge.core.data.Xml
import com.fileforge.core.data.PickedTable
import com.fileforge.core.data.XmlTable
import com.fileforge.core.office.SheetToWrite
import com.fileforge.core.office.XlsxWrite
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 把 `data/catalog.xml` 那份表推的产物落到 `build/xmldata/`，交给
 * `tools/verify_xml.py` 用 **ElementTree 与 openpyxl** 独立复核。
 *
 * 期望值不写在这里：那份 XML 里"哪一处重复最多、每行哪些列"由判据脚本自己用
 * ElementTree 再算一遍 —— 两边算出来的表必须是同一张，才算这条判断没跑偏。
 */
class XmlTableFixtureTest {

    private val source: String by lazy {
        String(javaClass.classLoader.getResourceAsStream("data/catalog.xml").readBytes(), Charsets.UTF_8)
    }

    private fun picked(): PickedTable = XmlTable.pick(Xml.parse(source))!!

    private fun table() = TableBridge.toTable(picked().rows)!!

    @Test
    fun `挑中的是条数最多的那处重复元素`() {
        val pick = picked()
        assertEquals("catalog.book", pick.path)
        assertTrue(pick.notes.any { it.contains("dvd") }, pick.notes.toString())
        assertTrue(pick.notes.any { it.contains("tags.tag") }, pick.notes.toString())
        assertEquals(4, table().rows.size)
    }

    @Test
    fun `列按元素路径命名且缺的格子留空`() {
        val built = table()
        assertEquals(
            listOf("@lang", "@no", "title", "author.name", "tags.tag", "price", "author.born", "文本"),
            built.columns,
        )
        val byRow = built.rows.map { row -> built.columns.zip(row).toMap() }
        assertEquals("12.50", byRow[0]["price"], "写法为 12.50 的那格被改写了")
        assertEquals("", byRow[2]["price"], "缺货那本该留空格子")
        assertEquals("这本书只多一句正文", byRow[3]["文本"], "元素自己那句话没进 文本 列")
        assertEquals(listOf("1", "2", "3", "4"), byRow.map { it["@no"] })
        assertEquals(listOf("zh", "en", "", "ja"), byRow.map { it["@lang"] })
    }

    @Test
    fun `产物落到 build 给 ElementTree 与 openpyxl 复核`() {
        val dir = File("build/xmldata").apply { mkdirs() }
        val built = table()
        File(dir, "catalog.csv").writeText(Csv.render(built.records), Charsets.UTF_8)
        val workbook = XlsxWrite.workbook(listOf(SheetToWrite("catalog", built.records)), modifiedAt = 0L)
        File(dir, "catalog.xlsx").writeBytes(workbook.bytes)
        File(dir, "notes.txt").writeText((picked().notes + workbook.notes).joinToString("\n") + "\n", Charsets.UTF_8)
        File(dir, "columns.txt").writeText(built.columns.joinToString("\n") + "\n", Charsets.UTF_8)
        assertTrue(File(dir, "catalog.xlsx").length() > 0)
    }
}
