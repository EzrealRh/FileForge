package com.fileforge.core

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.pdf.PdfDoc
import com.fileforge.core.pdf.PdfLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 从"画在纸上的字"还原出文档结构。
 *
 * 这里喂的是量好的行（字号、左边缘、高度），也就是 PDF 读取层交给判断层的样子；
 * 量的过程在安卓侧与探针侧，判据是**同一份文件的真实字号**由 pdfminer 独立量一遍
 * （`tools/verify_pdf_docx.py`）。
 */
class PdfDocTest {

    private fun line(
        text: String,
        size: Float,
        bold: Boolean = false,
        left: Float = 56f,
        top: Float = 100f,
        page: Int = 0,
    ) = PdfLine(text, size, bold, left, top, page)

    private fun paras(doc: Doc): List<DocPara> = doc.parts.map { (it as DocParagraph).para }

    private fun styles(doc: Doc): List<String> = paras(doc).map { it.style }

    @Test
    fun `正文基准按字数加权，不被两次大字号带走`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("报告标题", 20f, top = 60f),
                line("正文第一行" + "字".repeat(120), 11f, top = 100f),
                line("正文第二行" + "字".repeat(120), 11f, top = 113f),
            ),
        )
        assertTrue(doc.notes.any { it.contains("正文按 11pt") }, doc.notes.toString())
    }

    @Test
    fun `字号大的按大小排层级，最大的一级`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("大标题", 20f, top = 60f),
                line("节标题", 16f, top = 100f),
                line("正文" + "字".repeat(200), 11f, top = 140f),
            ),
        )
        assertEquals(listOf("Heading1", "Heading2", "Body"), styles(doc))
    }

    @Test
    fun `被折开的行并回一段，空档才分段`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("第一段的第一行" + "字".repeat(20), 11f, top = 100f),
                line("第一段被折开的第二行", 11f, top = 113f),
                line("第一段被折开的第三行", 11f, top = 126f),
                line("第二段的第一行", 11f, top = 170f),
            ),
        )
        val paragraphs = paras(doc)
        assertEquals(2, paragraphs.size, styles(doc).toString())
        assertEquals("第一段的第一行" + "字".repeat(20) + "第一段被折开的第二行第一段被折开的第三行", paragraphs[0].text)
        assertTrue(doc.notes.any { it.contains("并回一段") }, doc.notes.toString())
    }

    @Test
    fun `中文行尾直接接上，英文行尾补一个空格`() {
        val chinese = paras(PdfDoc.toDoc(listOf(line("上半句的文字", 11f, top = 100f), line("下半句接着写", 11f, top = 112f))))
        assertEquals("上半句的文字下半句接着写", chinese.single().text)

        val english = paras(
            PdfDoc.toDoc(
                listOf(
                    line("The quick brown fox", 11f, top = 100f),
                    line("jumps over the dog", 11f, top = 112f),
                ),
            ),
        )
        assertEquals("The quick brown fox jumps over the dog", english.single().text)
    }

    @Test
    fun `行尾的连字符是真断词，拼回去要去掉`() {
        val doc = PdfDoc.toDoc(listOf(line("exam-", 11f, top = 100f), line("ples of hyphenation", 11f, top = 112f)))
        assertEquals("examples of hyphenation", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("连字符") }, doc.notes.toString())
    }

    @Test
    fun `圆点与编号各归各的列表，记号不再写在文字里`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("正文一段" + "字".repeat(60), 11f, top = 100f),
                line("• 第一项", 11f, top = 140f),
                line("3. 第二项", 11f, top = 155f),
            ),
        )
        val paragraphs = paras(doc)
        assertEquals(listOf("Body", "ListParagraph", "ListParagraph"), styles(doc))
        assertEquals(true, paragraphs[1].bullet)
        assertEquals(false, paragraphs[2].bullet)
        assertEquals("第一项", paragraphs[1].text)
        assertEquals("第二项", paragraphs[2].text)
        assertTrue(doc.notes.any { it.contains("从 1 重画") }, doc.notes.toString())
    }

    @Test
    fun `中文的一、二、也算编号列表`() {
        val doc = PdfDoc.toDoc(listOf(line("一、总则", 11f, top = 100f), line("二、范围", 11f, top = 115f)))
        val paragraphs = paras(doc)
        assertEquals(listOf("总则", "范围"), paragraphs.map { it.text })
        assertTrue(paragraphs.all { it.bullet == false })
    }

    @Test
    fun `年份开头的正文不被当成编号列表`() {
        val doc = PdfDoc.toDoc(listOf(line("2019. 全年收入合计为一百万元整", 11f, top = 100f)))
        assertEquals("Body", styles(doc).single())
    }

    @Test
    fun `缩进的行另起一段，层按字宽折算`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("外层一项" + "字".repeat(40), 11f, left = 56f, top = 100f),
                line("内层一项" + "字".repeat(40), 11f, left = 100f, top = 114f),
            ),
        )
        assertEquals(listOf(0, 2), paras(doc).map { it.indent })
    }

    @Test
    fun `正文里另一档较大的字不被当成标题（占字太多）`() {
        // 实测过：中文正文 11.5pt 时，拉丁文与表格常用 12.5~13pt，占字能到三成 —— 占比是这条的闸门
        val lines = ArrayList<PdfLine>()
        var top = 100f
        repeat(6) {
            lines += line("中文正文一行" + "字".repeat(40), 11.5f, top = top)
            top += 60f
            lines += line("A table row in a larger latin font" + " x".repeat(30), 13f, top = top)
            top += 60f
        }
        val doc = PdfDoc.toDoc(lines)
        assertTrue(styles(doc).all { it == "Body" }, styles(doc).toString())
    }

    @Test
    fun `一行只有一个字符的大字号是公式不是标题`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("正文一行" + "字".repeat(60), 11f, top = 100f),
                line("2", 18f, top = 130f),
                line("3", 18f, top = 150f),
                line("x", 18f, top = 170f),
            ),
        )
        assertTrue(styles(doc).all { it == "Body" }, styles(doc).toString())
    }

    @Test
    fun `整份都是重面字时不按粗细分标题`() {
        // 黑体 / 微软雅黑排本的稿子行行带粗：这时"粗"不再是层级的信号（实测简历那份就是全份带粗）
        val doc = PdfDoc.toDoc(
            listOf(
                line("小结", 11f, bold = true, top = 100f),
                line("整份用黑体排的正文一行" + "字".repeat(40), 11f, bold = true, top = 200f),
            ),
        )
        assertEquals(listOf("Body", "Body"), styles(doc))
        assertTrue(doc.notes.any { it.contains("本身就偏重") }, doc.notes.toString())
    }

    @Test
    fun `跨页重复的行当页眉删掉，数字抹平才算同一行`() {
        val lines = ArrayList<PdfLine>()
        (0 until 4).forEach { page ->
            lines += line("第 ${page + 1} 页 · 季度报告", 9f, top = 28f, page = page)
            lines += line("第${page + 1}页的正文内容" + "字".repeat(60), 11f, top = 100f + page * 200, page = page)
        }
        val doc = PdfDoc.toDoc(lines)
        assertEquals(4, doc.parts.size)
        assertFalse(paras(doc).any { it.text.contains("季度报告") }, paras(doc).joinToString { it.text })
        assertTrue(doc.notes.any { it.contains("跨页重复") }, doc.notes.toString())
    }

    @Test
    fun `两三页的文件不猜页眉，宁可留着`() {
        val lines = listOf(line("第 1 页 · 报告", 9f, top = 28f, page = 0), line("正文" + "字".repeat(60), 11f, page = 0))
        assertEquals(2, PdfDoc.toDoc(lines).parts.size)
    }

    @Test
    fun `只加粗没变大的短行按最低一级标题，长句与句号结尾的不算`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("小结", 11f, bold = true, top = 100f),
                line("这是一整段被加粗强调的话，长得足够不像标题了所以不该被排成标题", 11f, bold = true, top = 120f),
                line("普通的正文一行" + "字".repeat(40), 11f, top = 140f),
            ),
        )
        assertEquals(listOf("Heading1", "Body", "Body"), styles(doc))
        assertTrue(doc.notes.any { it.contains("只加粗") }, doc.notes.toString())
    }

    @Test
    fun `句号结尾的加粗行是一句话，不是标题`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("务必先备份。", 11f, bold = true, top = 100f),
                line("普通的正文一行" + "字".repeat(40), 11f, top = 200f),
            ),
        )
        assertEquals(listOf("Body", "Body"), styles(doc))
    }

    @Test
    fun `不间断空格与软连字符先归一再判断`() {
        // 真文件的 ToUnicode 就是这么写的：拉丁文词间是 U+00A0，断词那个横线是 U+00AD
        val doc = PdfDoc.toDoc(
            listOf(
                line("exam\u00ad", 11f, top = 100f),
                line("ples of a\u00a0b rule", 11f, top = 112f),
            ),
        )
        assertEquals("examples of a b rule", paras(doc).single().text)
    }

    @Test
    fun `翻页不并段，两页的话不接成一句`() {
        val doc = PdfDoc.toDoc(
            listOf(
                line("上一页的最后一行" + "字".repeat(30), 11f, top = 760f, page = 0),
                line("下一页的第一行" + "字".repeat(30), 11f, top = 90f, page = 1),
            ),
        )
        assertEquals(2, doc.parts.size)
    }

    @Test
    fun `页面中间重复的字段名不是页眉`() {
        // 表单类文件每页都重复印"姓名""联系电话"，那是正文：量到纸高时只认上下那条带子
        val lines = ArrayList<PdfLine>()
        (0 until 4).forEach { page ->
            lines += PdfLine("姓名", 11f, false, 56f, 400f, page, 842f)
            lines += PdfLine("第 ${page + 1} 页", 9f, false, 56f, 28f, page, 842f)
        }
        val doc = PdfDoc.toDoc(lines)
        assertEquals(4, paras(doc).count { it.text == "姓名" })
        assertFalse(paras(doc).any { it.text.contains("页") }, paras(doc).joinToString { it.text })
    }

    @Test
    fun `多栏的版面会说出来，不装作读顺了`() {
        val two = ArrayList<PdfLine>()
        (0 until 4).forEach { index ->
            two += line("左栏的第${index + 1}行文字" + "字".repeat(20), 11f, left = 56f, top = 100f + index * 60f)
            two += line("右栏的第${index + 1}行文字" + "字".repeat(20), 11f, left = 340f, top = 100f + index * 60f)
        }
        val columns = PdfDoc.toDoc(two).notes
        assertTrue(columns.any { it.contains("多栏") }, columns.toString())

        // 每段最后一行都提前收住，那不是多栏
        val one = (0 until 4).map { line("单栏的正文第${it + 1}段" + "字".repeat(30), 11f, top = 100f + it * 60f) }
        val single = PdfDoc.toDoc(one).notes
        assertTrue(single.none { it.contains("多栏") }, single.toString())
    }

    @Test
    fun `没有可用文字时说清是扫描件，不出一份空文件`() {
        val doc = PdfDoc.toDoc(listOf(line("   ", 11f), line("", 11f)))
        assertTrue(doc.parts.isEmpty())
        assertTrue(doc.notes.any { it.contains("扫描件") }, doc.notes.toString())
    }
}
