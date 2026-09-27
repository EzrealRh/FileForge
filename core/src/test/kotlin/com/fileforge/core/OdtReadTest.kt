package com.fileforge.core

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.Html
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.model.FileKind
import com.fileforge.core.office.DocxWrite
import com.fileforge.core.office.OdtRead
import com.fileforge.core.office.OoxmlParts
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ODT 的结构读回（`:core` 的 OdtRead）。
 *
 * 这里的样本是**照 ODF 的规矩手写**的：样式表在正文之外，正文只写样式名，
 * 所以"记号对不对"只能靠把样式表与正文一起摆出来才判得出来。
 *
 * 只比这些不够：真实文件由别的实现写出来（`tools/make_odt_fixtures.py` 用 pandoc 产 .odt 夹具），
 * 那些夹具的四条产物落在 `build/odtread/`，由 `tools/verify_odt_read.py` 拿 pandoc 自己的
 * ODT 读法逐块比对 —— 判据与实现不共用一行代码。
 */
class OdtReadTest {

    private fun content(body: String, styles: String = AUTO_STYLES): ByteArray =
        (XML_DECL + NAMESPACES + "<office:automatic-styles>" + styles + "</office:automatic-styles>" +
            "<office:body><office:text>" + body + "</office:text></office:body></office:document-content>")
            .toByteArray(Charsets.UTF_8)

    private fun read(body: String, styles: String = AUTO_STYLES) =
        OdtRead.read { name ->
            when (name) {
                OoxmlParts.ODT_CONTENT -> content(body, styles)
                else -> null
            }
        }

    private fun parts(body: String, styles: String = AUTO_STYLES) = read(body, styles).doc.parts

    private fun paraOf(part: DocPart): DocPara = (part as DocParagraph).para

    @Test
    fun `标题层级照元素上写的层级认`() {
        val made = parts(
            "<text:h text:style-name=\"Heading_20_3\" text:outline-level=\"3\">三层</text:h>" +
                "<text:h text:style-name=\"Heading_20_1\">一层</text:h>" +
                "<text:p>正文</text:p>",
        )
        assertEquals(
            listOf("Heading3", "Heading1", "Body"),
            made.map { paraOf(it).style },
        )
    }

    @Test
    fun `段落样式要顺父链看_本名是自动生成的`() {
        // P5 这个名字谁也不认得，"这是引用"写在它的父样式 Quotations 上
        val made = parts(
            "<text:p text:style-name=\"P5\">引用的一句话</text:p>" +
                "<text:p text:style-name=\"P6\">一行代码</text:p>" +
                "<text:p text:style-name=\"Text_20_body\">普通的一段</text:p>",
        )
        assertEquals(listOf("Quote", "SourceCode", "Body"), made.map { paraOf(it).style })
    }

    @Test
    fun `记号从样式表问出来`() {
        val made = parts(
            "<text:p>前<text:span text:style-name=\"T1\">粗</text:span>中" +
                "<text:span text:style-name=\"T2\">斜</text:span>与" +
                "<text:span text:style-name=\"T3\">删</text:span>与" +
                "<text:span text:style-name=\"T4\">下</text:span>与" +
                "<text:span text:style-name=\"T5\">码</text:span>后</text:p>",
        ).single()
        val runs = paraOf(made).runs
        assertEquals(listOf("前", "粗", "中", "斜", "与", "删", "与", "下", "与", "码", "后"), runs.map { it.text })
        assertTrue(runs[1].bold && !runs[3].bold, "T1 才是粗体")
        assertTrue(runs[3].italic && !runs[5].italic, "T2 才是斜体")
        assertTrue(runs[5].strike, "T3 是删除线")
        assertTrue(runs[7].underline, "T4 是下划线")
        assertTrue(runs[9].mono, "T5 的字体是等宽（font-family-generic=modern）")
    }

    @Test
    fun `子样式关得掉父样式说过的记号`() {
        val made = parts("<text:p><text:span text:style-name=\"T6\">不粗</text:span></text:p>").single()
        assertEquals(false, paraOf(made).runs.single().bold, "T6 写着 fo:font-weight=normal，父样式 T1 的粗体要被关掉")
    }

    @Test
    fun `圆点与编号分得开_深度按嵌套层数算`() {
        val made = parts(
            "<text:list text:style-name=\"L1\">" +
                "<text:list-item><text:p>点一</text:p></text:list-item>" +
                "<text:list-item><text:p>点二</text:p>" +
                "<text:list><text:list-item><text:p>更深的一点</text:p></text:list-item></text:list>" +
                "</text:list-item>" +
                "</text:list>" +
                "<text:list text:style-name=\"L2\">" +
                "<text:list-item><text:p>编号一</text:p></text:list-item>" +
                "</text:list>",
        )
        // 顺序是"文档顺序"：外层两项 → 第二项里嵌的那层 → 后面那份独立编号表
        assertEquals(
            listOf("ListParagraph", "ListParagraph", "ListParagraph", "ListParagraph"),
            made.map { paraOf(it).style },
        )
        assertEquals(listOf(0, 0, 1, 0), made.map { paraOf(it).indent })
        assertEquals(listOf(true, true, true, false), made.map { paraOf(it).bullet })
    }

    @Test
    fun `一个列表项里写两段算一项`() {
        val read = read(
            "<text:list text:style-name=\"L1\"><text:list-item>" +
                "<text:p>第一行</text:p><text:p>第二行</text:p>" +
                "</text:list-item></text:list>",
        )
        val para = paraOf(read.doc.parts.single())
        assertEquals("第一行\n第二行", para.text)
        assertTrue(read.notes.any { "列表项里写了两段" in it }, read.notes.toString())
    }

    @Test
    fun `空格的个数与制表和段内换行都按写的来`() {
        val made = parts("<text:p>甲<text:s/><text:s text:c=\"3\"/>乙<text:tab/>丙<text:line-break/>丁</text:p>")
        assertEquals("甲    乙\t丙\n丁", paraOf(made.single()).text)
    }

    @Test
    fun `外部链接照字面取_段内跳转只留文字`() {
        val read = read(
            "<text:p>一个链接在<text:a xlink:type=\"simple\" xlink:href=\"https://example.com/a?x=1&amp;y=2\">这里</text:a>，" +
                "后面是<text:a xlink:type=\"simple\" xlink:href=\"#书签\">跳过去</text:a>。</text:p>",
        )
        val runs = paraOf(read.doc.parts.single()).runs
        val texts = runs.joinToString("|") { "${it.text}=>${it.link}" }
        val link = runs.firstOrNull { it.text == "这里" }
        assertTrue(link != null && link.link == "https://example.com/a?x=1&y=2", "链接不对：$texts")
        // 段内跳转的那截与后面的句号记号一样，会被合成一格：判"它没有链接"要看装着它的那一格
        val jumped = runs.first { "跳过去" in it.text }
        assertEquals(null, jumped.link, texts)
        assertTrue(read.notes.any { "段内跳转" in it }, read.notes.toString())
        // 链接的文字不能跑到句尾
        assertEquals("一个链接在这里，后面是跳过去。", runs.joinToString("") { it.text })
    }

    @Test
    fun `合并列与重复列都要补齐格子`() {
        val made = parts(
            "<table:table table:name=\"T1\">" +
                "<table:table-column/><table:table-column/><table:table-column/>" +
                "<table:table-row>" +
                "<table:table-cell table:number-columns-spanned=\"2\"><text:p>跨两列</text:p></table:table-cell>" +
                "<table:table-cell><text:p>末列</text:p></table:table-cell>" +
                "</table:table-row>" +
                "<table:table-row>" +
                "<table:table-cell><text:p>一</text:p></table:table-cell>" +
                "<table:covered-table-cell/>" +
                "<table:table-cell><text:p>三</text:p></table:table-cell>" +
                "</table:table-row>" +
                "<table:table-row>" +
                "<table:table-cell table:number-columns-repeated=\"3\"><text:p/></table:table-cell>" +
                "</table:table-row>" +
                "</table:table>",
        ).single() as DocTable
        assertEquals(
            listOf(listOf("跨两列", "", "末列"), listOf("一", "", "三"), listOf("", "", "")),
            made.rows,
        )
    }

    @Test
    fun `表头看它的行躺在哪一层`() {
        val made = parts(
            "<table:table>" +
                "<table:table-header-rows><table:table-row><table:table-cell><text:p>甲</text:p></table:table-cell></table:table-row></table:table-header-rows>" +
                "<table:table-row><table:table-cell><text:p>乙</text:p></table:table-cell></table:table-row>" +
                "</table:table>",
        ).single() as DocTable
        assertEquals(true, made.header)
        assertEquals(listOf(listOf("甲"), listOf("乙")), made.rows)
    }

    @Test
    fun `分隔线是那条说自己是横线的空段`() {
        val made = parts(
            "<text:p>上</text:p><text:p text:style-name=\"Horizontal_20_Line\"/><text:p>下</text:p>",
        )
        assertEquals(3, made.size)
        assertTrue(made[1] is DocRule, made.toString())
    }

    @Test
    fun `写着横线样式的段落里有字时把字搬走`() {
        val made = parts("<text:p text:style-name=\"Horizontal_20_Line\">这句要搬</text:p>").single()
        assertEquals("Body", paraOf(made).style)
        assertEquals("这句要搬", paraOf(made).text)
    }

    @Test
    fun `丢了什么逐条报数`() {
        val read = read(
            "<text:p>正文" +
                "<draw:frame><draw:image xlink:href=\"pic.png\"/></draw:frame>" +
                "<text:note><text:note-citation>1</text:note-citation><text:note-body><text:p>注文</text:p></text:note-body></text:note>" +
                "<office:annotation><text:p>批注的话</text:p></office:annotation>" +
                "</text:p>",
        )
        val text = paraOf(read.doc.parts.single()).text
        assertEquals("正文", text, "图、注文与批注都不该混进正文：$text")
        assertTrue(read.notes.any { "1 处图片" in it }, read.notes.toString())
        assertTrue(read.notes.any { "脚注" in it }, read.notes.toString())
        assertTrue(read.notes.any { "批注" in it }, read.notes.toString())
    }

    @Test
    fun `认不出的行内记号也要把字搬走`() {
        val read = read("<text:p>前<text:phrase text:type=\"aa\">Phrase 里的话</text:phrase>后</text:p>")
        val text = paraOf(read.doc.parts.single()).runs.joinToString("") { it.text }
        assertEquals("前Phrase 里的话后", text, "认不出的记号只丢记号，不许丢字")
    }

    @Test
    fun `样式名的编码两种写法都要解`() {
        // 规范那条是四位（_0020_），LibreOffice 与 pandoc 实际写的是最短的 _20_
        val made = parts(
            "<text:p text:style-name=\"P7\">引用</text:p><text:p text:style-name=\"P8\">代码</text:p>",
            AUTO_STYLES +
                "<style:style style:name=\"P7\" style:family=\"paragraph\" style:parent-style-name=\"Quote_0020_style\"/>" +
                "<style:style style:name=\"P8\" style:family=\"paragraph\" style:parent-style-name=\"Source_005F_Code\"/>" +
                "<style:style style:name=\"Quote_0020_style\" style:family=\"paragraph\" style:parent-style-name=\"Quotations\"/>" +
                "<style:style style:name=\"Source_005F_Code\" style:family=\"paragraph\" style:parent-style-name=\"Source Code\"/>",
        )
        assertEquals(listOf("Quote", "SourceCode"), made.map { paraOf(it).style })
    }

    @Test
    fun `缺正文部件与不对的包都直说`() {
        assertThrows(IllegalArgumentException::class.java) { OdtRead.read { null } }
        assertThrows(IllegalArgumentException::class.java) {
            OdtRead.read { name ->
                if (name == OoxmlParts.ODT_CONTENT) {
                    (XML_DECL + NAMESPACES + "<office:body><office:spreadsheet/></office:body></office:document-content>")
                        .toByteArray(Charsets.UTF_8)
                } else {
                    null
                }
            }
        }
    }

    @Test
    fun `包里读得出 mimetype 才算 ODT`() {
        val names = listOf("mimetype", "META-INF/manifest.xml", "content.xml", "styles.xml")
        fun kindWith(mimetype: String?) = OoxmlParts.kindOf(names) { name ->
            if (name == "mimetype") mimetype?.toByteArray(Charsets.UTF_8) else null
        }
        assertEquals(FileKind.Odt, kindWith("application/vnd.oasis.opendocument.text"))
        assertEquals(FileKind.Odt, kindWith("application/vnd.oasis.opendocument.text.template"))
        assertEquals(FileKind.Zip, kindWith("application/vnd.oasis.opendocument.spreadsheet"))
        assertEquals(FileKind.Zip, kindWith(null))
        // 只有条目名判不出 ODT（这是规矩不是缺陷）：docx 那三条仍然先判
        assertEquals(FileKind.Docx, OoxmlParts.kindOf(listOf("mimetype", "content.xml", "word/document.xml")))
    }

    @Test
    fun `纯文本那条按组的疏密排`() {
        val made = listOf<DocPart>(
            DocParagraph(DocPara(listOf(DocRun("正文一段")), "Body")),
            DocParagraph(DocPara(listOf(DocRun("点一")), "ListParagraph", 0, true)),
            DocParagraph(DocPara(listOf(DocRun("点二")), "ListParagraph", 0, true)),
            DocParagraph(DocPara(listOf(DocRun("一段代码")), "SourceCode")),
            DocTable(false, listOf(listOf("甲", "乙"), listOf("1", "2"))),
        )
        val text = HtmlWrite.text(made)
        assertEquals("正文一段\n\n点一\n点二\n\n一段代码\n\n甲\t乙\n1\t2\n", text)
    }

    /**
     * 真实夹具的产物落盘：pandoc 写的 .odt 与手写的 .odt 都从这一条走。
     *
     * 断言放在产文件**之后**：反例跑的是磁盘上的文件，先断言会让坏实现把上一轮的旧产物留下，
     * 外部判据拿着旧文件说全绿（docx 那边真发生过）。
     */
    @Test
    fun `产物落盘供外部判据对照`() {
        val dir = File("build/odtread").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
        listOf("letter.odt", "kinds.odt", "spans.odt").forEach { name ->
            val stem = name.substringBeforeLast('.')
            val bytes = resourceBytes("odtread", name)
            val read = OdtRead.read { part -> zipPart(bytes, part) }
            require(read.doc.parts.isNotEmpty()) { "$stem 什么块都没读到，夹具或读法坏了" }
            File(dir, "$stem.odt").writeBytes(bytes)
            File(dir, "$stem.shapes.txt").writeText(shapes(read.doc.parts).joinToString("\n") + "\n", Charsets.UTF_8)
            File(dir, "$stem.text.txt").writeText(HtmlWrite.text(read.doc.parts), Charsets.UTF_8)
            File(dir, "$stem.md").writeText(Html.toMarkdown(HtmlWrite.body(read.doc.parts)).text, Charsets.UTF_8)
            File(dir, "$stem.html").writeText(HtmlWrite.page(stem, read.doc.parts, language = "zh").html, Charsets.UTF_8)
            File(dir, "$stem.docx").writeBytes(DocxWrite.document(read.doc, modifiedAt = 0L).bytes)
            File(dir, "$stem.notes.txt").writeText(read.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        assertEquals(3, dir.listFiles().orEmpty().count { it.name.endsWith(".odt") })
        assertEquals(3, dir.listFiles().orEmpty().count { it.name.endsWith(".shapes.txt") })
    }

    private fun shapes(parts: List<DocPart>): List<String> = parts.map { part ->
        when (part) {
            is DocParagraph -> {
                val para = part.para
                val mark = para.runs.joinToString("") { run ->
                    (if (run.bold) "b" else "") + (if (run.italic) "i" else "") +
                        (if (run.mono) "c" else "") + (if (run.strike) "s" else "") +
                        (if (run.underline) "u" else "") + (if (!run.link.isNullOrBlank()) "L" else "")
                }
                val list = if (para.style == "ListParagraph") " 层${para.indent} ${if (para.bullet == false) "编号" else "圆点"}" else ""
                "${para.style}$list[$mark]"
            }
            is DocTable -> "表 ${part.rows.size}行×${part.rows.maxOf { it.size }}列 表头=${part.header}"
            is DocRule -> "分隔线"
        }
    }

    private fun zipPart(bytes: ByteArray, part: String): ByteArray? {
        val archive = com.fileforge.core.archive.ZipReader.read(bytes)
        val slices = com.fileforge.core.archive.ZipReader.slicing(bytes)
        val entry = archive.entries.firstOrNull { it.name == part } ?: return null
        return com.fileforge.core.archive.ZipReader.dataOf(entry, slices)
    }

    private fun resourceBytes(area: String, name: String): ByteArray {
        val input = javaClass.classLoader.getResourceAsStream("$area/$name")
        requireNotNull(input) { "缺少夹具 $area/$name（先跑 tools/make_odt_fixtures.py）" }
        return input.readBytes()
    }

    private companion object {
        const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        const val NAMESPACES =
            "<office:document-content " +
                "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
                "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
                "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
                "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
                "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" " +
                "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
                "xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" " +
                "xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
                "office:version=\"1.2\">"

        /** 一份够用的小样式表：记号、段落母样式、两种列表样式。 */
        const val AUTO_STYLES =
            "<style:style style:name=\"T1\" style:family=\"text\">" +
                "<style:text-properties fo:font-weight=\"bold\"/></style:style>" +
            "<style:style style:name=\"T2\" style:family=\"text\">" +
                "<style:text-properties fo:font-style=\"italic\"/></style:style>" +
            "<style:style style:name=\"T3\" style:family=\"text\">" +
                "<style:text-properties style:text-line-through-style=\"solid\"/></style:style>" +
            "<style:style style:name=\"T4\" style:family=\"text\">" +
                "<style:text-properties style:text-underline-style=\"solid\"/></style:style>" +
            "<style:style style:name=\"T5\" style:family=\"text\">" +
                "<style:text-properties style:font-family-generic=\"modern\"/></style:style>" +
            "<style:style style:name=\"T6\" style:family=\"text\" style:parent-style-name=\"T1\">" +
                "<style:text-properties fo:font-weight=\"normal\"/></style:style>" +
            "<style:style style:name=\"Text_20_body\" style:family=\"paragraph\"/>" +
            "<style:style style:name=\"Quotations\" style:family=\"paragraph\"/>" +
            "<style:style style:name=\"Preformatted_20_Text\" style:family=\"paragraph\"/>" +
            "<style:style style:name=\"P5\" style:family=\"paragraph\" style:parent-style-name=\"Quotations\"/>" +
            "<style:style style:name=\"P6\" style:family=\"paragraph\" style:parent-style-name=\"Preformatted_20_Text\"/>" +
            "<text:list-style style:name=\"L1\">" +
                "<text:list-level-style-bullet text:level=\"1\" text:bullet-char=\"•\"/>" +
                "<text:list-level-style-bullet text:level=\"2\" text:bullet-char=\"◦\"/></text:list-style>" +
            "<text:list-style style:name=\"L2\">" +
                "<text:list-level-style-number text:level=\"1\" style:num-format=\"1\"/></text:list-style>"
    }
}
