package com.fileforge.core

import com.fileforge.core.model.FileKind
import com.fileforge.core.office.OfficeText
import com.fileforge.core.office.OoxmlParts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.charset.Charset
import org.junit.jupiter.api.Test

/**
 * OOXML 取文字。夹具是照 ECMA-376 的部件形状手写的最小片段，
 * 判据看的是"字有没有按读的顺序落下来"和"不该出现的字有没有混进来"。
 */
class OfficeTextTest {

    private fun doc(body: String): ByteArray =
        ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">""" +
            """<w:body>$body</w:body></w:document>""").toByteArray(Charsets.UTF_8)

    private fun text(body: String) = OfficeText.docx(doc(body)).text

    // ---- 类型识别 ---------------------------------------------------------------

    @Test
    fun `部件名说了算：有 word 正文就是 docx`() {
        assertEquals(FileKind.Docx, OoxmlParts.kindOf(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        assertEquals(FileKind.Xlsx, OoxmlParts.kindOf(listOf("[Content_Types].xml", "xl/workbook.xml")))
        assertEquals(FileKind.Pptx, OoxmlParts.kindOf(listOf("ppt/presentation.xml")))
        assertEquals(FileKind.Zip, OoxmlParts.kindOf(listOf("readme.txt", "data/photo.jpg")))
        // 电子书技术上也是个 zip：认出它靠那个规定的目录文件，不靠扩展名
        assertEquals(
            FileKind.Epub,
            OoxmlParts.kindOf(listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf")),
        )
        assertEquals(
            FileKind.Zip,
            OoxmlParts.kindOf(listOf("META-INF/container.xml.bak", "readme.txt")),
            "差一个后缀不算电子书",
        )
    }

    // ---- 正文顺序 ---------------------------------------------------------------

    @Test
    fun `段落按读的顺序落下来，一段一行`() {
        val body = "<w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t></w:r></w:p>"
        assertEquals("第一段\n第二段\n", text(body))
    }

    @Test
    fun `同一个段落里被拆开的文本框与分栏要粘回一行`() {
        // Word 会为了拼写检查把一句话切成多个 w:r，甚至把同一个词劈开
        val body = "<w:p><w:r><w:t>文件</w:t></w:r><w:r><w:rPr/><w:t>工坊</w:t></w:r><w:r><w:t>很快</w:t></w:r></w:p>"
        assertEquals("文件工坊很快\n", text(body))
    }

    @Test
    fun `段内制表与换行不丢`() {
        val body = "<w:p><w:r><w:t>甲</w:t><w:tab/><w:t>乙</w:t><w:br/><w:t>丙</w:t></w:r></w:p>"
        assertEquals("甲\t乙\n丙\n", text(body))
    }

    @Test
    fun `空段落留成空行而不是消失`() {
        val body = "<w:p><w:r><w:t>上</w:t></w:r></w:p><w:p/><w:p><w:r><w:t>下</w:t></w:r></w:p>"
        assertEquals("上\n\n下\n", text(body))
    }

    @Test
    fun `xml 里的实体与引号按解出来的样子给`() {
        val body = "<w:p><w:r><w:t>&amp; &lt;tag&gt; &quot;引号&quot; A&#8212;B</w:t></w:r></w:p>"
        assertEquals("& <tag> \"引号\" A—B\n", text(body))
    }

    @Test
    fun `空格是内容不是格式：带 preserve 的边空格不许被吃掉`() {
        // 相邻 run 各带一半空格时，trim 任何一边都会把两个词粘上
        val body = "<w:p><w:r><w:t xml:space=\"preserve\">Hello</w:t></w:r>" +
            "<w:r><w:t xml:space=\"preserve\"> world </w:t></w:r><w:r><w:t>x</w:t></w:r></w:p>"
        assertEquals("Hello world x\n", text(body))
    }

    // ---- 表格 ------------------------------------------------------------------

    @Test
    fun `表格一行一记录，单元格之间用制表符`() {
        val body = "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>名称</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>数量</w:t></w:r></w:p></w:tc></w:tr>" +
            "<w:tr><w:tc><w:p><w:r><w:t>苹果</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>3</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        assertEquals("名称\t数量\n苹果\t3\n", text(body))
    }

    @Test
    fun `格子里的多个段落并成一行，不把表格戳漏`() {
        val body = "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>第一</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>第二</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>右</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        val lines = text(body).trimEnd('\n').split("\n")
        assertEquals(1, lines.size, "一行表格必须落成一行文本：$lines")
        assertEquals("第一 第二\t右", lines[0])
    }

    @Test
    fun `正文与表格混排时顺序不乱`() {
        val body = "<w:p><w:r><w:t>前言</w:t></w:r></w:p>" +
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>表</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:p><w:r><w:t>后记</w:t></w:r></w:p>"
        assertEquals("前言\n表\n后记\n", text(body))
    }

    // ---- 该跳过的文字 -------------------------------------------------------------

    @Test
    fun `修订里删掉的字不算正文，但要报出来`() {
        val result = OfficeText.docx(
            doc(
                "<w:p><w:ins><w:r><w:t>留下的</w:t></w:r></w:ins>" +
                    "<w:del><w:r><w:delText>删掉的</w:delText></w:r></w:del></w:p>",
            ),
        )
        assertEquals("留下的\n", result.text)
        assertTrue(result.losses.any { "删掉" in it }, "要说明有修订删除：${result.losses}")
    }

    @Test
    fun `域代码只留结果不留代码`() {
        // Word 的 { TIME } 域：instrText 是代码，后面的 run 才是显示结果
        val body = "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
            "<w:r><w:instrText> TIME </w:instrText></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>14:03</w:t></w:r></w:p>"
        assertEquals("14:03\n", text(body))
    }

    @Test
    fun `页眉页脚引用不搬并说明`() {
        val result = OfficeText.docx(
            doc(
                "<w:p><w:r><w:t>正文</w:t></w:r></w:p>" +
                    "<w:p><w:pPr><w:sectPr><w:headerReference w:id=\"1\"/>" +
                    "<w:footerReference w:id=\"2\"/></w:sectPr></w:pPr></w:p>",
            ),
        )
        assertEquals("正文\n", result.text)
        assertTrue(result.losses.any { "页眉" in it }, result.losses.toString())
        assertTrue(result.losses.any { "页脚" in it }, result.losses.toString())
    }

    @Test
    fun `图片脚注批注超链接各自报数`() {
        val result = OfficeText.docx(
            doc(
                "<w:p><w:r><w:t>看</w:t></w:r><w:r><w:drawing><wp:anchor/></w:drawing></w:r>" +
                    "<w:r><w:footnoteReference w:id=\"1\"/></w:r>" +
                    "<w:r><w:commentReference w:id=\"2\"/></w:r>" +
                    "<w:hyperlink r:id=\"rId4\"><w:r><w:t>链接</w:t></w:r></w:hyperlink></w:p>",
            ),
        )
        assertTrue("看链接" in result.text.replace("\n", ""), result.text)
        listOf("图片", "脚注", "批注", "超链接").forEach { word ->
            assertTrue(result.losses.any { word in it }, "少了「$word」这条说明：${result.losses}")
        }
    }

    @Test
    fun `文本框里的字算正文`() {
        val body = "<w:p><w:r><w:t>框外</w:t></w:r></w:p>" +
            "<w:p><w:r><mc:AlternateContent><mc:Choice><w:drawing/><w:txbxContent>" +
            "<w:p><w:r><w:t>框内</w:t></w:r></w:p></w:txbxContent></mc:Choice></mc:AlternateContent></w:r></w:p>"
        assertTrue("框内" in text(body), "文本框的字要留下来")
        // 嵌套段落自己收过一次尾，外层那段不许再补一个空行
        assertEquals("框外\n框内\n", text(body))
    }

    // ---- pptx ------------------------------------------------------------------

    private fun slide(body: String): ByteArray =
        ("""<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">$body</p:sld>""").toByteArray(Charsets.UTF_8)

    @Test
    fun `一页幻灯片里每个形状的文字都收，形状之间不粘连`() {
        val body = "<p:cSld><p:spTree>" +
            "<p:sp><p:txBody><a:p><a:r><a:t>标题</a:t></a:r></a:p></p:txBody></p:sp>" +
            "<p:sp><p:txBody><a:p><a:r><a:t>正文第一行</a:t></a:r><a:br/><a:r><a:t>第二行</a:t></a:r></a:p></p:txBody></p:sp>" +
            "</p:spTree></p:cSld>"
        val result = OfficeText.pptxSlide(slide(body))
        assertEquals("标题\n正文第一行\n第二行\n", result.text)
    }

    @Test
    fun `幻灯片里的表格同样拍平成行`() {
        val body = "<p:cSld><p:spTree><p:graphicFrame><a:graphic><a:graphicData><a:tbl>" +
            "<a:tr><a:tc><a:txBody><a:p><a:r><a:t>甲</a:t></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc><a:txBody><a:p><a:r><a:t>乙</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl>" +
            "</a:graphicData></a:graphic></p:graphicFrame></p:spTree></p:cSld>"
        assertEquals("甲\t乙\n", OfficeText.pptxSlide(slide(body)).text)
    }

    @Test
    fun `幻灯片的页码域算成正文里的数字`() {
        val body = "<p:cSld><p:spTree><p:sp><p:txBody><a:p>" +
            "<a:fld id=\"{A}\" type=\"slidenumber\"><a:t>7</a:t></a:fld></a:p></p:txBody></p:sp></p:spTree></p:cSld>"
        assertEquals("7\n", OfficeText.pptxSlide(slide(body)).text)
    }

    // ---- 坏输入 ----------------------------------------------------------------

    @Test
    fun `带 DTD 的部件直接拒，不去取外部实体`() {
        val evil = ("""<!DOCTYPE w:document [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
            """<w:document><w:p><w:t>&x;</w:t></w:p></w:document>""").toByteArray()
        val error = runCatching { OfficeText.docx(evil) }.exceptionOrNull()
        assertTrue(error != null, "带 DTD 的 OOXML 部件必须报错")
        assertTrue("DTD" in (error!!.message ?: ""), error.message)
    }

    @Test
    fun `不是 UTF-8 的部件报错而不是拿替换字符凑`() {
        // GBK 的「中」是 D6 D0，按 UTF-8 读是半途截断的非法序列 —— 拿替换字符能凑出一份"看着正常"的垃圾
        val gbk = ("""<w:document><w:p><w:t>""" + "中" + """</w:t></w:p></w:document>""")
            .toByteArray(Charset.forName("GBK"))
        val error = runCatching { OfficeText.docx(gbk) }.exceptionOrNull()
        assertTrue(error != null, "读不干净就该报错，不该给一份看着正常的文本")
        assertTrue("UTF-8" in (error!!.message ?: ""), "要报的是编码读不出来，而不是随便什么解析失败：${error.message}")
    }

    @Test
    fun `空正文给出空文本而不是抛`() {
        assertEquals("", text(""))
    }

    @Test
    fun `带 BOM 的部件照样读`() {
        val withBom = ("\uFEFF" + """<w:document><w:p><w:r><w:t>带BOM</w:t></w:r></w:p></w:document>""").toByteArray()
        assertEquals("带BOM\n", OfficeText.docx(withBom).text)
    }
}
