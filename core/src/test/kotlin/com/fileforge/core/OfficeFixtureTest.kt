package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.model.FileKind
import com.fileforge.core.office.OfficeText
import com.fileforge.core.office.OoxmlParts
import com.fileforge.core.office.OoxmlStructure
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 拿真包住进 zip 的 OOXML 走一遍整条路：zip 目录 → 部件名判类型 → 取正文 → 比期望。
 *
 * 夹具与期望都由 `tools/make_office_fixtures.py` 生成（期望按"一行一段"写成 `.expect`），
 * 产物落到 `core/build/office/`，再由 `tools/verify_office.py` 交给 pandoc 三方对照。
 */
class OfficeFixtureTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("office/$name").use { input ->
            requireNotNull(input) { "缺少夹具 office/$name，先跑 python tools/make_office_fixtures.py" }
            ByteArrayOutputStream().also { output -> input.copyTo(output) }.toByteArray()
        }

    private fun expect(name: String): String = String(resource("$name.expect"), Charsets.UTF_8)

    private fun entriesOf(bytes: ByteArray) = ZipReader.read(bytes).entries

    private fun partOf(bytes: ByteArray, part: String): ByteArray {
        val entry = entriesOf(bytes).firstOrNull { it.name == part }
        requireNotNull(entry) { "包里没有 $part" }
        return ZipReader.dataOf(entry, ZipReader.slicing(bytes))
    }

    @Test
    fun `prose 的段落与期望逐行相同`() {
        val bytes = resource("prose.docx")
        assertEquals(FileKind.Docx, OoxmlParts.kindOf(entriesOf(bytes).map { it.name }))
        val result = OfficeText.docx(partOf(bytes, OoxmlParts.DOCX_BODY))
        assertEquals(expect("prose.docx"), result.text)
    }

    @Test
    fun `tables 的表格文本框与图都按声明的规矩落下来`() {
        val bytes = resource("tables.docx")
        val result = OfficeText.docx(partOf(bytes, OoxmlParts.DOCX_BODY))
        assertEquals(expect("tables.docx"), result.text)
        // 这份夹具里东西齐：丢掉的每一类都得说明
        listOf("图片", "表被拍平", "脚注", "页眉", "页脚").forEach { word ->
            assertTrue(result.losses.any { word in it }, "少了「$word」这条：${result.losses}")
        }
    }

    @Test
    fun `deck 两页按页序拼起来`() {
        val bytes = resource("deck.pptx")
        val names = entriesOf(bytes).map { it.name }
        assertEquals(FileKind.Pptx, OoxmlParts.kindOf(names))
        val slides = names.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.sorted()
        assertEquals(listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml"), slides)
        val text = slides.joinToString("") { OfficeText.pptxSlide(partOf(bytes, it)).text }
        assertEquals(expect("deck.pptx"), text)
    }

    /** 给顺序判据用的最小包：只喂字节，不碰 zip。 */
    private fun xml(text: String) = text.toByteArray(Charsets.UTF_8)

    private val P_NS = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val RELS_NS = "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""

    @Test
    fun `夹具里的两页按声明顺序给出`() {
        val bytes = resource("deck.pptx")
        val names = entriesOf(bytes).map { it.name }
        val loaded: (String) -> ByteArray? = { name -> if (name in names) partOf(bytes, name) else null }
        val order = OoxmlStructure.slideOrder(loaded, names)
        assertEquals(listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml"), order.parts)
        assertEquals(emptyList<String>(), order.notes)
    }

    @Test
    fun `页序照声明走而不是文件名`() {
        // 大纲里第二页排在前面：按文件名排会得到 slide1、slide2，那是反的
        val presentation = xml("<p:presentation $P_NS><p:sldIdLst>" +
            "<p:sldId id=\"257\" r:id=\"rId3\"/><p:sldId id=\"256\" r:id=\"rId2\"/>" +
            "</p:sldIdLst></p:presentation>")
        val rels = xml("<Relationships $RELS_NS>" +
            "<Relationship Id=\"rId2\" Target=\"slides/slide1.xml\"/>" +
            "<Relationship Id=\"rId3\" Target=\"/ppt/slides/slide2.xml\"/></Relationships>")
        val available = listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml")
        val order = OoxmlStructure.slideOrder({ part ->
            when (part) {
                OoxmlStructure.DECK -> presentation
                OoxmlStructure.DECK_RELS -> rels
                else -> null
            }
        }, available)
        assertEquals(listOf("ppt/slides/slide2.xml", "ppt/slides/slide1.xml"), order.parts)
    }

    @Test
    fun `大纲里缺页或包里多页都要说清楚`() {
        val presentation = xml("<p:presentation $P_NS><p:sldIdLst>" +
            "<p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId9\"/>" +
            "</p:sldIdLst></p:presentation>")
        val rels = xml("<Relationships $RELS_NS>" +
            "<Relationship Id=\"rId2\" Target=\"slides/slide1.xml\"/>" +
            "<Relationship Id=\"rId3\" Target=\"slides/slide3.xml\"/></Relationships>")
        val order = OoxmlStructure.slideOrder({ part ->
            when (part) {
                OoxmlStructure.DECK -> presentation
                OoxmlStructure.DECK_RELS -> rels
                else -> null
            }
        }, listOf("ppt/slides/slide1.xml", "ppt/slides/slide3.xml"))
        assertEquals(listOf("ppt/slides/slide1.xml", "ppt/slides/slide3.xml"), order.parts)
        assertEquals(2, order.notes.size, "两处不吻合都得写明：${order.notes}")
        assertTrue(order.notes.any { "找不到" in it }, order.notes.toString())
        assertTrue(order.notes.any { "补在了后面" in it }, order.notes.toString())
    }

    /** 给 tools/verify_office.py 的参照物：pandoc 抽的是同一批文件，比对以行为单位。 */
    @Test
    fun `把三份产物落到 build 下`() {
        val dir = File("build/office").apply { mkdirs() }
        File(dir, "prose.docx.txt").writeBytes(OfficeText.docx(partOf(resource("prose.docx"), OoxmlParts.DOCX_BODY)).text.toByteArray())
        File(dir, "tables.docx.txt").writeBytes(OfficeText.docx(partOf(resource("tables.docx"), OoxmlParts.DOCX_BODY)).text.toByteArray())
        val bytes = resource("deck.pptx")
        val slides = entriesOf(bytes).map { it.name }.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.sorted()
        File(dir, "deck.pptx.txt").writeBytes(slides.joinToString("") { OfficeText.pptxSlide(partOf(bytes, it)).text }.toByteArray())
        assertTrue(listOf("prose.docx.txt", "tables.docx.txt", "deck.pptx.txt").all { File(dir, it).length() > 0L },
            "三份产物都要落盘")
    }
}
