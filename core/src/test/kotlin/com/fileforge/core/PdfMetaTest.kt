package com.fileforge.core

import com.fileforge.core.pdf.PdfMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PDF 元数据的改写单：哪些键要动、哪些要删、话怎么说。
 *
 * 这里不碰任何 PDF 库 —— 写文件的活由 app 与 `tools/pdfprobe` 那两侧照这份改写照做，
 * 做没做对由 `tools/verify_pdf_meta.py` 拿 MuPDF 与 pdfplumber 两个独立读路判。
 */
class PdfMetaTest {

    private fun patch(mode: String, vararg pairs: Pair<String, String>, keepDates: Boolean = false, stamp: Boolean = false, now: Long = 0L) =
        PdfMeta.patch(mode, mapOf(*pairs), keepDates, stamp, now)

    @Test
    fun `清除模式把认得的键与日期都删掉`() {
        val made = patch("clear")
        assertEquals(PdfMeta.FIELDS.map { it.first }, made.removes.take(6), "六个认得的键都要删：${made.removes}")
        assertTrue("CreationDate" in made.removes && "ModDate" in made.removes, made.removes.toString())
        assertTrue("*" in made.removes, "自定义键也要清：${made.removes}")
        assertEquals(emptyList<Pair<String, String>>(), made.writes, "清除模式不该顺手写值")
    }

    @Test
    fun `只清标准字段时自定义键留着`() {
        val made = patch("clear-standard")
        assertEquals(PdfMeta.FIELDS.map { it.first }, made.removes.take(6), made.removes.toString())
        assertFalse("*" in made.removes, "这一档不许扫自定义键：${made.removes}")
        assertTrue(made.notes.any { "Company" in it }, made.notes.toString())
    }

    @Test
    fun `保留日期时要说出这是身份信息`() {
        val made = patch("clear", keepDates = true)
        assertFalse("CreationDate" in made.removes, made.removes.toString())
        assertEquals(listOf("CreationDate", "ModDate"), made.keeps,
            "清剩下的键时必须绕过这两个，不然没点名的也被一起删了：${made.keeps}")
        assertTrue(made.notes.any { it.contains("身份信息") }, made.notes.toString())
    }

    @Test
    fun `改写模式空着的键既不改也不删`() {
        val made = patch("edit", "Title" to "季度报告")
        assertEquals(listOf("Title" to "季度报告"), made.writes)
        assertEquals(emptyList<String>(), made.removes, "空着那格是\"别动它\"，不能变成删除")
    }

    @Test
    fun `没填也没勾时间时说明这份原样不动`() {
        val made = patch("edit")
        assertFalse(made.changed, "什么都没要改：$made")
        assertTrue(made.notes.any { it.contains("原样不动") }, made.notes.toString())
    }

    @Test
    fun `值两端的空白不算内容`() {
        val made = patch("edit", "Author" to "   ", "Title" to "  甲  ")
        assertEquals(listOf("Title" to "甲"), made.writes, made.writes.toString())
    }

    @Test
    fun `超长的值截断并说掉多少`() {
        val long = "长".repeat(PdfMeta.MAX_VALUE + 60)
        val made = patch("edit", "Subject" to long)
        assertEquals(PdfMeta.MAX_VALUE, made.writes.single().second.length)
        assertTrue(made.notes.any { "${long.length}" in it && "没写进去" in it }, made.notes.toString())
    }

    @Test
    fun `盖修改时间要有个靠谱的时刻`() {
        assertFalse(patch("edit", stamp = true).changed, "没给毫秒数就不许盖时间")
        assertTrue(patch("edit", stamp = true, now = 1_700_000_000_000L).stampNow)
    }

    @Test
    fun `清除模式下不另盖修改时间`() {
        val made = patch("clear", stamp = true, now = 1_700_000_000_000L)
        assertFalse(made.stampNow, made.toString())
        assertTrue(made.notes.any { "别再写回去" in it }, made.notes.toString())
    }

    @Test
    fun `改写了什么要说得出是哪几项`() {
        val made = patch("edit", "Title" to "甲", "Producer" to "乙")
        assertTrue(made.notes.any { "改写 2 项" in it && "标题" in it && "转换工具" in it }, made.notes.toString())
    }

    @Test
    fun `日期写法与读法对得上`() {
        assertEquals("D:20230501083015+00'00'", PdfMeta.pdfDate(1_682_929_815_000L))
        assertEquals("2023-05-01 +00'00'", PdfMeta.readableDate("D:20230501+00'00'"))
        assertTrue("2023-05-01" in PdfMeta.readableDate("D:20230501083015+00'00'"))
        assertEquals("", PdfMeta.readableDate(null), "没有就是没有，不编一个时间")
        assertEquals("不像日期的原样", PdfMeta.readableDate("不像日期的原样"))
    }

    @Test
    fun `认不出的键按自定义说`() {
        assertEquals("标题", PdfMeta.label("Title"))
        assertEquals("创建时间", PdfMeta.label("CreationDate"))
        assertEquals("修改时间", PdfMeta.label("ModDate"))
        assertEquals("自定义（Company）", PdfMeta.label("Company"))
    }

    @Test
    fun `连着算两次不沾上次的状态`() {
        val first = patch("edit", "Title" to "甲")
        val second = patch("edit", "Title" to "甲")
        assertEquals(first.writes, second.writes)
        assertEquals(first.notes, second.notes)
    }
}
