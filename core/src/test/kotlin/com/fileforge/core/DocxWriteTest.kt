package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.Html
import com.fileforge.core.office.DocxWrite
import com.fileforge.core.office.OfficeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * docx 写入侧。
 *
 * 镜子是自家**读那一路**（[OfficeText]，Word 的正文部件走树抽字）：写进去再读回来必须一字不差，
 * 两边共用同一套 OOXML 规矩，哪边写错这里就对不上。真第三方（pandoc 独立读 docx）在
 * `tools/verify_docx_write.py` 里判 —— 自家读路宽容，包结构写错它可能照样读得出（xlsx 那轮就是这么漏的）。
 */
class DocxWriteTest {

    private fun partsOf(bytes: ByteArray): Map<String, ByteArray> {
        val entries = ZipReader.read(ZipReader.slicing(bytes), bytes.size.toLong()).entries
        return entries.associate { it.name to ZipReader.dataOf(it, ZipReader.slicing(bytes)) }
    }

    private fun documentXml(bytes: ByteArray) = String(partsOf(bytes).getValue("word/document.xml"), Charsets.UTF_8)

    private fun written(parts: List<com.fileforge.core.doc.DocPart>) = DocxWrite.document(Doc(parts, emptyList())).bytes

    private fun paragraph(text: String, style: String = "Body", indent: Int = 0, bullet: Boolean? = null) =
        DocParagraph(DocPara(listOf(DocRun(text)), style, indent, bullet))

    // ---- 走一圈自家读路 -------------------------------------------------------------

    @Test
    fun `写完用自家读路读回来，字一个不少`() {
        val parts = listOf(
            paragraph("一级标题字多", "Heading1"),
            paragraph("正文里有加粗的一段", "Body"),
            paragraph("苹果", "ListParagraph", 0, true),
            paragraph("乙", "ListParagraph", 1, false),
            paragraph("引用里的话", "Quote"),
            paragraph("一行代码", "Code"),
            DocTable(true, listOf(listOf("名称", "数量"), listOf("苹果", "3"))),
        )
        val bytes = written(parts)
        val read = OfficeText.docx(partsOf(bytes).getValue("word/document.xml"))
        listOf("一级标题字多", "正文里有加粗的一段", "苹果", "乙", "引用里的话", "一行代码", "名称", "数量", "3")
            .forEach { piece -> assertTrue(piece in read.text, "读回来少了「$piece」：\n${read.text}") }
    }

    @Test
    fun `带记号的字照样读得出来`() {
        val bytes = written(
            listOf(
                DocParagraph(
                    DocPara(
                        listOf(
                            DocRun("粗"), DocRun("斜", italic = true), DocRun("废", strike = true),
                            DocRun("码", mono = true), DocRun("链", link = "https://example.com/a"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("粗斜废码链", OfficeText.docx(partsOf(bytes).getValue("word/document.xml")).text.trim())
    }

    // ---- 包结构 ---------------------------------------------------------------------

    @Test
    fun `每个超链接的 rId 在关联表里都有，且写着外部目标`() {
        val bytes = written(listOf(DocParagraph(DocPara(listOf(DocRun("点开", link = "https://example.com/x?a=1&b=2"))))))
        val parts = partsOf(bytes)
        val xml = String(parts.getValue("word/document.xml"), Charsets.UTF_8)
        val rels = String(parts.getValue("word/_rels/document.xml.rels"), Charsets.UTF_8)
        val ids = Regex("r:id=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(ids.isNotEmpty(), "正文里该有超链接：$xml")
        val declared = Regex("Id=\"([^\"]+)\"").findAll(rels).map { it.groupValues[1] }.toSet()
        assertEquals(ids.distinct(), ids.distinct().filter { it in declared }.toList(), "正文引了关联表没有的 rId")
        assertTrue("TargetMode=\"External\"" in rels, "外部链接必须标 External：$rels")
        assertTrue("https://example.com/x?a=1&amp;b=2" in rels, "地址里的 & 要转义：$rels")
    }

    @Test
    fun `部件清单说的部件包里都有`() {
        val bytes = written(listOf(paragraph("甲")))
        val parts = partsOf(bytes)
        val types = String(parts.getValue("[Content_Types].xml"), Charsets.UTF_8)
        val declared = Regex("PartName=\"([^\"]+)\"").findAll(types).map { it.groupValues[1].removePrefix("/") }.toList()
        assertEquals(declared.filter { it in parts.keys }, declared, "清单报了包里没的：$declared")
        assertTrue("package/2006/content-types\"" in types, "命名空间：$types")
        assertTrue(parts.keys.contains("word/styles.xml"), "部件：${parts.keys}")
    }

    @Test
    fun `正文的收尾段落属性必须有，表后面必须跟一段`() {
        val xml = documentXml(
            written(listOf(DocTable(false, listOf(listOf("甲"))), paragraph("后面"))),
        )
        assertTrue("</w:tbl><w:p/>" in xml, "表格后面要跟一段，否则两张表会并成一张：$xml")
        assertTrue(Regex("<w:sectPr><w:pgSz").containsMatchIn(xml), "缺 sectPr 时 Word 会报不可读取的内容：$xml")
    }

    @Test
    fun `列表的记号在 numbering 里定义，正文只引用编号`() {
        val bytes = written(listOf(paragraph("甲", "ListParagraph", 0, true), paragraph("乙", "ListParagraph", 0, false)))
        val parts = partsOf(bytes)
        val xml = String(parts.getValue("word/document.xml"), Charsets.UTF_8)
        val numbering = String(parts.getValue("word/numbering.xml"), Charsets.UTF_8)
        assertTrue("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/>" in xml, "点号列表引 numId 1：$xml")
        assertTrue("<w:numId w:val=\"2\"/>" in xml, "编号列表引 numId 2：$xml")
        assertTrue("w:numFmt w:val=\"bullet\"" in numbering && "w:numFmt w:val=\"decimal\"" in numbering, numbering)
        assertFalse("• " in xml, "记号不该写在文字里：$xml")
        assertEquals(
            listOf("1", "2"),
            Regex("<w:num w:numId=\"(\\d+)\">").findAll(numbering).map { it.groupValues[1] }.toList(),
        )
    }

    @Test
    fun `正文引到的样式必须在样式表里都有定义`() {
        val bytes = written(
            listOf(
                paragraph("题", "Heading3"), paragraph("引", "Quote"), paragraph("码", "SourceCode"),
                DocParagraph(DocPara(listOf(DocRun("行内码", mono = true), DocRun("链", link = "https://x.example")))),
            ),
        )
        val parts = partsOf(bytes)
        val xml = String(parts.getValue("word/document.xml"), Charsets.UTF_8)
        val styles = String(parts.getValue("word/styles.xml"), Charsets.UTF_8)
        val used = Regex("w:(?:pStyle|rStyle) w:val=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
        assertTrue(used.isNotEmpty())
        val missing = used.filter { style -> !styles.contains("w:styleId=\"$style\"") }
        assertEquals(emptyList<String>(), missing, "正文引了样式表里没有的样式：$missing（用到的：$used）")
    }

    @Test
    fun `样式名用 Word 认的那一套`() {
        val bytes = written(
            listOf(
                paragraph("标题", "Heading2"), paragraph("引用", "Quote"), paragraph("代码", "SourceCode"),
                paragraph("项", "ListParagraph", 0, true),
            ),
        )
        val parts = partsOf(bytes)
        val xml = String(parts.getValue("word/document.xml"), Charsets.UTF_8)
        val styles = String(parts.getValue("word/styles.xml"), Charsets.UTF_8)
        listOf("Heading2", "Quote", "SourceCode", "ListParagraph").forEach { style ->
            assertTrue("<w:pStyle w:val=\"$style\"" in xml, "正文该用 $style：$xml")
            assertTrue("w:styleId=\"$style\"" in styles, "$style 得在样式表里定义：$styles")
        }
        assertTrue("<w:name w:val=\"Heading 2\"/>" in styles, "Word 认的是内置样式名：$styles")
        assertTrue("<w:name w:val=\"List Paragraph\"/>" in styles, "列表的内置名：$styles")
    }

    @Test
    fun `格内换行写成软回车，不新起一段`() {
        val xml = documentXml(written(listOf(DocParagraph(DocPara(listOf(DocRun("上\n下")))))))
        assertTrue("<w:t xml:space=\"preserve\">上</w:t><w:br/><w:t xml:space=\"preserve\">下</w:t>" in xml, xml)
        assertEquals(1, Regex("<w:p>").findAll(prefix(xml)).count(), "一段里换行不该变成两段")
    }

    /** 数段落只数开头的 `<w:p>`，表里那几段不算在这条判据上。 */
    private fun prefix(xml: String) = xml.substringBefore("<w:tbl>")

    @Test
    fun `Word 不许的控制字符去掉并说明`() {
        val out = DocxWrite.document(Doc(listOf(paragraph("甲\u0001乙"), paragraph("尾")), emptyList()))
        assertTrue("控制字符" in out.notes.joinToString(" "), out.notes.joinToString(" · "))
        assertEquals("甲乙\n尾", OfficeText.docx(partsOf(out.bytes).getValue("word/document.xml")).text.trim())
    }

    @Test
    fun `表格里没有记号也不报错，空格子留着`() {
        val xml = documentXml(written(listOf(DocTable(true, listOf(listOf("甲", ""), listOf("", "乙"))))))
        assertEquals(2, Regex("<w:tr>").findAll(xml).count())
        assertEquals(4, Regex("<w:tc>").findAll(xml).count(), "空格子也要占一格，否则整列错位")
        assertTrue("<w:tblHeader/>" in xml, "第一行是表头")
    }

    // ---- 网页那条路：建树 → 文档树 -----------------------------------------------------

    @Test
    fun `网页的记号、列表、表格与链接都落到文档树里`() {
        val doc = Html.toDoc(
            "<h2>小标</h2><p>前<strong>粗</strong><em>斜</em><a href=\"https://a.example\">链</a>" +
                "<a href=\"javascript:bad()\">坏</a></p><ul><li>一</li><li>二<ul><li>二点一</li></ul></li></ul>" +
                "<table><tr><th>甲</th><th>乙</th></tr><tr><td colspan=2>跨</td></tr></table>" +
                "<pre>两行\n代码</pre><blockquote><p>引语</p></blockquote><hr>",
        )
        val styles = doc.parts.filterIsInstance<DocParagraph>().map { it.para.style }
        assertTrue("Heading2" in styles, styles.toString())
        assertTrue(
            "ListParagraph" in styles && "Quote" in styles && "SourceCode" in styles,
            styles.toString(),
        )
        val marked = doc.parts.filterIsInstance<DocParagraph>().first { "链" in it.para.text }
        assertTrue(marked.para.runs.any { it.text == "粗" && it.bold }, "加粗要落进 run")
        assertTrue(marked.para.runs.any { it.text == "斜" && it.italic }, "斜体要落进 run")
        assertEquals("https://a.example", marked.para.runs.first { it.text == "链" }.link)
        assertTrue(marked.para.runs.none { it.text == "坏" && it.link != null }, "javascript: 那种不该当链接")
        val lists = doc.parts.filterIsInstance<DocParagraph>().map { it.para.bullet }
        assertTrue(lists.contains(true), lists.toString())
        val table = doc.parts.filterIsInstance<DocTable>().single()
        assertTrue(table.header && table.rows.last()[1] == "", "跨过的格子留空：${table.rows}")
        assertTrue(doc.notes.any { "分隔线" in it }, doc.notes.toString())
        assertEquals(1, doc.links.size, "只有那条 http 的算真链接：${doc.links}")
    }

    @Test
    fun `嵌套列表按层号往里缩`() {
        val doc = Html.toDoc("<ul><li>一<ul><li>二点一</li></ul></li></ul>")
        val items = doc.parts.filterIsInstance<DocParagraph>().map { it.para.indent to it.para.text }
        assertEquals(listOf(0 to "一", 1 to "二点一"), items)
    }
}
