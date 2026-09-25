package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.TextDoc
import com.fileforge.core.office.DocxWrite
import com.fileforge.core.office.OfficeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 一栏文本走哪条路排进 Word。
 *
 * 判据一律看内容不看扩展名：一个人把网页存成 .txt、把 Markdown 存成 .text 都是常事。
 * 走错路的代价是不对称的 —— 把 Markdown 当普通文字，产物只是朴素一点；把网页当 Markdown，
 * 尖括号会被当标记吃掉，正文就少了。所以网页那条判据先跑。
 */
class TextDocTest {

    /** 写成 docx，再走自家读那一路把正文读回来。 */
    private fun readBack(source: String): String {
        val bytes = DocxWrite.document(TextDoc.read(source).doc).bytes
        val slicing = ZipReader.slicing(bytes)
        val entry = ZipReader.read(slicing, bytes.size.toLong()).entries
            .first { it.name == "word/document.xml" }
        return OfficeText.docx(ZipReader.dataOf(entry, slicing)).text
    }

    private fun stylesOf(source: String) = TextDoc.read(source).doc.parts.mapNotNull {
        (it as? DocParagraph)?.para?.style
    }

    @Test
    fun `带标签的按网页排，哪怕源文件名叫 markdown`() {
        val reading = TextDoc.read("<h2>小节</h2><p>带 <b>粗</b> 的正文</p>")
        assertEquals(TextDoc.Route.Web, reading.route)
        assertEquals("Heading2", (reading.doc.parts.first() as DocParagraph).para.style)
    }

    @Test
    fun `Markdown 稿子里嵌一个 br 不会把标题打回字面文字`() {
        // <br> 与 <img> 在 Markdown 稿子里太常见：判成网页就会让 # 与 ** 照字面进 Word
        val source = "# 标题\n\n第一行<br>第二行\n\n- 甲\n- 乙\n"
        assertEquals(TextDoc.Route.Markdown, TextDoc.read(source).route)
        assertTrue(stylesOf(source).contains("Heading1"), stylesOf(source).toString())
    }

    @Test
    fun `又是记号又有网页骨架的按网页排`() {
        // 这条路对 Markdown 记号的代价是明写的：# 会照字面留下 —— 所以结果说明里要写按哪条路排的
        val source = "# 页面标题\n<div><p>带 <b>粗</b> 的正文</p></div>"
        val reading = TextDoc.read(source)
        assertEquals(TextDoc.Route.Web, reading.route)
        assertFalse(stylesOf(source).contains("Heading1"), "网页那一路不该把 # 当标题：$reading")
        assertTrue((reading.doc.parts.first() as DocParagraph).para.text.contains("# 页面标题"))
    }

    @Test
    fun `带记号的按 Markdown 排，标题与列表各归各的样式`() {
        val reading = TextDoc.read("# 标题\n\n段落里 **粗** 一句\n\n- 甲\n- 乙\n")
        assertEquals(TextDoc.Route.Markdown, reading.route)
        val styles = reading.doc.parts.map { (it as DocParagraph).para }
        assertEquals("Heading1", styles.first().style)
        val bullets = styles.filter { it.bullet != null }
        assertEquals(2, bullets.size)
        assertTrue(bullets.all { it.bullet == true })
    }

    @Test
    fun `既不像网页也不像 Markdown 的，按空行分段`() {
        val reading = TextDoc.read("甲\n甲\n\n乙")
        assertEquals(TextDoc.Route.Plain, reading.route)
        assertEquals(2, reading.doc.parts.size)
        assertEquals("甲\n甲", (reading.doc.parts.first() as DocParagraph).para.text)
    }

    @Test
    fun `句子里的井号不会把整篇认成 Markdown`() {
        assertEquals(TextDoc.Route.Plain, TextDoc.read("引用 #3 与 #7 号，还有 100% 的把握").route)
    }

    @Test
    fun `换行风格统一过会说出来，不悄悄改写`() {
        assertTrue(TextDoc.read("甲\r\n\r\n乙").doc.notes.any { it.contains("LF") })
        assertFalse(TextDoc.read("甲\n\n乙").doc.notes.any { it.contains("LF") })
    }

    @Test
    fun `写成 docx 再读回来：字一个字不少，记号不再露在正文里`() {
        val text = readBack("# 标题\n\n普通 **粗** 一句\n\n- 甲\n- 乙\n")
        assertTrue(text.contains("标题"), text)
        assertTrue(text.contains("普通 粗 一句"), text)
        assertTrue(text.contains("甲") && text.contains("乙"), text)
        assertFalse(text.contains("#"), text)
        assertFalse(text.contains("**"), text)
        // 列表记号是 Word 自己画的：正文里不该出现项目符号或编号
        assertFalse(text.contains("- ") || text.contains("•"), text)
    }
}
