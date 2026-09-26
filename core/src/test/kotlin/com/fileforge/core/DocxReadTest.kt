package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.Html
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.office.DocxRead
import com.fileforge.core.office.DocxWrite
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * docx 的**结构**读回：Doc → docx → Doc 走一圈，标题层级、圆点还是编号、列表深度、
 * 表格与表头、记号与真链接都得原样回来。
 *
 * 只比"这一圈"是不够的：产物另外落在 `build/docxread/`，由 `tools/verify_docx_read.py`
 * 拿 pandoc（它能自己读 docx、也能读我们写的 Markdown / HTML）三方比同一套块序列。
 */
class DocxReadTest {

    private fun para(text: String, style: String = "Body", indent: Int = 0, bullet: Boolean? = null) =
        DocParagraph(DocPara(listOf(DocRun(text)), style, indent, bullet))

    private fun pack(doc: Doc): ByteArray = DocxWrite.document(doc, modifiedAt = 0L).bytes

    /** 把包摊成"部件名 → 字节"，交给读的一侧按需取。 */
    private fun loader(bytes: ByteArray): (String) -> ByteArray? {
        val archive = ZipReader.read(bytes)
        val slices = ZipReader.slicing(bytes)
        val index = archive.entries.associate { entry -> entry.name to ZipReader.dataOf(entry, slices) }
        return { name -> index[name] }
    }

    private fun roundTrip(doc: Doc): Doc = DocxRead.read(loader(pack(doc))).doc

    @Test
    fun `标题层级样式名认得回来`() {
        val doc = Doc(
            listOf(
                para("大标题", "Heading1"), para("小节", "Heading2"), para("小确", "Heading3"),
                para("正文一段"),
            ),
            emptyList(),
        )
        assertEquals(listOf("Heading1", "Heading2", "Heading3", "Body"), roundTrip(doc).parts.map { (it as DocParagraph).para.style })
    }

    @Test
    fun `圆点与编号是两回事，层级深度也要在`() {
        val doc = Doc(
            listOf(
                para("甲", "ListParagraph", 0, true),
                para("乙", "ListParagraph", 1, true),
                para("一", "ListParagraph", 0, false),
                para("二", "ListParagraph", 2, false),
            ),
            emptyList(),
        )
        val back = roundTrip(doc)
        assertEquals(listOf(true to 0, true to 1, false to 0, false to 2), back.parts.map { it ->
            val para = (it as DocParagraph).para
            (para.bullet == true) to para.indent
        })
    }

    @Test
    fun `表格的表头与行列原样回来`() {
        val doc = Doc(listOf(DocTable(true, listOf(listOf("列一", "列二"), listOf("甲", "乙\n丙")))), emptyList())
        val table = roundTrip(doc).parts.filterIsInstance<DocTable>().single()
        assertTrue(table.header, "表头那行丢了")
        assertEquals(listOf(listOf("列一", "列二"), listOf("甲", "乙\n丙")), table.rows)
    }

    @Test
    fun `记号一层不丢，链接是能点开的那个地址`() {
        val doc = Doc(
            listOf(
                DocParagraph(
                    DocPara(
                        listOf(
                            DocRun("粗", bold = true), DocRun("斜", italic = true),
                            DocRun("删", strike = true), DocRun("码", mono = true),
                            DocRun("划", underline = true),
                            DocRun("链", link = "https://example.com/x?a=1&b=2"),
                        ),
                    ),
                ),
            ),
            emptyList(),
        )
        val runs = roundTrip(doc).parts.filterIsInstance<DocParagraph>().single().para.runs
        assertEquals(listOf(true, true, true, true, true), listOf(
            runs.any { it.bold }, runs.any { it.italic }, runs.any { it.strike },
            runs.any { it.mono }, runs.any { it.underline },
        ))
        assertEquals("https://example.com/x?a=1&b=2", runs.first { it.link != null }.link, "链接目标是关系表里那个，得解出来")
    }

    @Test
    fun `分隔线还是分隔线`() {
        val doc = Doc(listOf(para("上"), DocRule(), para("下")), emptyList())
        assertEquals(listOf("DocParagraph", "DocRule", "DocParagraph"), roundTrip(doc).parts.map { it.javaClass.simpleName })
    }

    @Test
    fun `引用与代码块回来还是那两个样子`() {
        val doc = Doc(listOf(para("引用的话", "Quote"), para("第一行\n第二行", "SourceCode")), emptyList())
        val back = roundTrip(doc)
        assertEquals(listOf("Quote", "SourceCode"), back.parts.map { (it as DocParagraph).para.style })
        assertEquals("第一行\n第二行", (back.parts[1] as DocParagraph).para.runs.joinToString("") { it.text })
    }

    @Test
    fun `认不出的样式名一律当正文，不猜层级`() {
        val bytes = pack(Doc(listOf(para("一段")), emptyList()))
        val patched = replacePart(bytes, "word/styles.xml") { text ->
            text.replace("w:styleId=\"Body\"", "w:styleId=\"Body\" w:customFormat=\"1\"")
        }
        val doc = DocxRead.read(loader(patched)).doc
        assertEquals("Body", (doc.parts.first() as DocParagraph).para.style)
    }

    @Test
    fun `没有正文部件时直说，不出一份空文档`() {
        val error = assertThrows(IllegalArgumentException::class.java) { DocxRead.read { null } }
        assertTrue(error.message!!.contains("document.xml"), error.message)
    }

    @Test
    fun `没有 numbering 部件时按圆点排并说出来`() {
        val bytes = docxOf(
            body = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"1\"/><w:numId w:val=\"7\"/></w:numPr></w:pPr>" +
                "<w:r><w:t>编号的一项</w:t></w:r></w:p>",
            numbering = null,
        )
        val read = DocxRead.read(loader(bytes))
        val made = (read.doc.parts.first() as DocParagraph).para
        assertTrue(read.notes.any { "没有 numbering 部件" in it }, read.notes.toString())
        assertEquals(true, made.bullet == true, "找不到编号信息时按圆点排")
        assertEquals(1, made.indent, "ilvl 还得认成层级深度")
    }

    @Test
    fun `修订里删掉的不出现，接受下来的留下`() {
        val bytes = docxOf(
            body = "<w:p><w:ins w:id=\"1\"><w:r><w:t>接受的字</w:t></w:r></w:ins>" +
                "<w:del w:id=\"2\"><w:r><w:delText>删掉的字</w:delText></w:r></w:del>" +
                "<w:r><w:t>留下的字</w:t></w:r></w:p>",
        )
        val read = DocxRead.read(loader(bytes))
        val text = (read.doc.parts.first() as DocParagraph).para.runs.joinToString("") { it.text }
        assertTrue("接受的字" in text && "留下的字" in text, text)
        assertFalse("删掉的字" in text, text)
        assertTrue(read.notes.any { "修订" in it }, read.notes.toString())
    }

    @Test
    fun `段内跳转只留文字并说明，真地址从关系表里解出来`() {
        val bytes = docxOf(
            body = "<w:p><w:hyperlink w:anchor=\"这里\"><w:r><w:t>跳到书签</w:t></w:r></w:hyperlink>" +
                "<w:hyperlink r:id=\"rId9\"><w:r><w:t>点开地址</w:t></w:r></w:hyperlink></w:p>",
            rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" " +
                "Target=\"https://example.com/a?x=1&amp;y=2\" TargetMode=\"External\"/></Relationships>",
        )
        val read = DocxRead.read(loader(bytes))
        val runs = (read.doc.parts.first() as DocParagraph).para.runs
        assertTrue(runs.any { it.text == "跳到书签" && it.link == null }, "段内锚点不该被当成能点开的地址：$runs")
        assertEquals("https://example.com/a?x=1&y=2", runs.first { it.text == "点开地址" }.link, "关系表里的地址要解回来（含转义的 &）")
        assertTrue(read.notes.any { "段内跳转" in it }, read.notes.toString())
    }

    @Test
    fun `句中的链接不许被挪到句尾`() {
        val bytes = docxOf(
            body = "<w:p><w:r><w:t>前面</w:t></w:r>" +
                "<w:hyperlink r:id=\"rId3\"><w:r><w:t>这里</w:t></w:r></w:hyperlink>" +
                "<w:r><w:t>后面</w:t></w:r></w:p>",
            rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" " +
                "Target=\"https://example.com/z\" TargetMode=\"External\"/></Relationships>",
        )
        val runs = DocxRead.read(loader(bytes)).doc.parts.filterIsInstance<DocParagraph>().single().para.runs
        assertEquals("前面这里后面", runs.joinToString("") { it.text }, "顺序按文档走：$runs")
        assertEquals(listOf(null, "https://example.com/z", null), runs.map { it.link }, "只有中间那一截带地址：$runs")
    }

    /** 手搭一份最小的 docx：边角情况（缺件、修订、锚点）用写侧写不出来，只能照 ECMA-376 的写法自己拼。 */
    private fun docxOf(
        body: String,
        styles: String? = "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>",
        numbering: String? = "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>",
        rels: String? = null,
    ): ByteArray {
        val head = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"
        val items = ArrayList<com.fileforge.core.archive.ZipItem>()
        items += part("word/document.xml", head + body + "</w:body></w:document>")
        if (styles != null) items += part("word/styles.xml", styles)
        if (numbering != null) items += part("word/numbering.xml", numbering)
        if (rels != null) items += part("word/_rels/document.xml.rels", rels)
        return com.fileforge.core.archive.ZipWriter.write(items)
    }

    private fun part(name: String, xml: String) =
        com.fileforge.core.archive.ZipItem(
            name,
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + xml).toByteArray(Charsets.UTF_8),
            0L,
        )

    /** 在写侧产出的 docx 上就地补几段：修订的 ins / del 与一段十进制编号的列表。 */
    private fun packWithRevision(doc: Doc): ByteArray {
        val body = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr></w:pPr>" +
            "<w:r><w:t>编号的一行</w:t></w:r></w:p>"
        val patched = replacePart(pack(doc), "word/document.xml") { text ->
            // 修订就贴在同一句的后面（真改稿子就是这个形状：一句话里补几个字、划掉几个字），
            // 单独立一段全是 ins/del 的段落，别的实现会当成空段整段丢掉
            val revision = "<w:ins w:id=\"1\"><w:r><w:t>补进去的</w:t></w:r></w:ins>" +
                "<w:del w:id=\"2\"><w:r><w:delText>划掉的</w:delText></w:r></w:del>"
            if ("</w:p>" !in text) throw AssertionError("锚点没了：</w:p>")
            val withRevision = text.replaceFirst("</w:p>", "$revision</w:p>")
            val tail = "</w:body>"
            if (tail !in withRevision) throw AssertionError("锚点没了：$tail")
            withRevision.replace(tail, body + tail)
        }
        return patched
    }

    /** 换掉包里某个部件的内容（给 null 就是删掉那一份），其余字节原样。 */
    private fun replacePart(bytes: ByteArray, name: String, edit: (String) -> String?): ByteArray {
        val archive = ZipReader.read(bytes)
        val slices = ZipReader.slicing(bytes)
        val items = ArrayList(archive.entries.filter { it.name != name }.map { entry ->
            com.fileforge.core.archive.ZipItem(entry.name, ZipReader.dataOf(entry, slices), entry.modifiedAt)
        })
        val target = archive.entries.firstOrNull { it.name == name }
        if (target != null) {
            val text = edit(String(ZipReader.dataOf(target, slices), Charsets.UTF_8))
            if (text != null) {
                items += com.fileforge.core.archive.ZipItem(name, text.toByteArray(Charsets.UTF_8), 0L)
            }
        }
        return com.fileforge.core.archive.ZipWriter.write(items.sortedBy { it.name })
    }

    @Test
    fun `产物落盘供外部判据对照`() {
        val dir = File("build/docxread").apply { mkdirs() }
        val sources = listOf("note.md", "head.md", "page.html", "plain.txt", "english.md")
        // 再加一份带修订（接受的一截 + 删掉的一截）与编号列表的：写侧不会产出修订，判据要能判
        // "删掉的字不许被搬回来"，就得真有一份带修订的文件 —— 在写侧产出的包上就地补这几段，
        // 整包其余部件（[Content_Types]、rels、styles、numbering）都是齐的，别的实现才读得动
        val revised = packWithRevision(Doc(listOf(para("定稿的话")), emptyList()))
        dump(dir, "revision", revised)
        sources.forEach { name ->
            val stem = name.substringBeforeLast('.')
            val doc = com.fileforge.core.doc.TextDoc.read(resource("epubwrite", name)).doc
            dump(dir, stem, pack(doc))
        }
        assertEquals(sources.size + 1, dir.listFiles().orEmpty().count { it.name.endsWith(".docx") })
    }

    /** 一份 docx 摊成三条产物：docx 本体、我们读出来再写的 Markdown 与网页，外加读的时候说了什么。 */
    private fun dump(dir: File, stem: String, bytes: ByteArray) {
        File(dir, "$stem.docx").writeBytes(bytes)
        val read = DocxRead.read(loader(bytes))
        val markdown = Html.toMarkdown(HtmlWrite.body(read.doc.parts)).text
        File(dir, "$stem.md").writeText(markdown, Charsets.UTF_8)
        File(dir, "$stem.html").writeText(HtmlWrite.page(stem, read.doc.parts, language = "zh").html, Charsets.UTF_8)
        File(dir, "$stem.notes.txt").writeText(read.notes.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    /**
     * 样本本身得是对的：判据跑在一份"其实没有修订"的文件上，等于反例白做。
     *
     * 这一条特意不放在落盘那条里 —— 断言放在产文件之前，改坏实现时这条一红，
     * 文件就不再生成，外部判据会拿着上一轮的旧产物说"全绿"（真发生过）。
     */
    @Test
    fun `修订与编号那份样本确实带着修订与编号`() {
        val read = DocxRead.read(loader(packWithRevision(Doc(listOf(para("定稿的话")), emptyList()))))
        val text = read.doc.parts.joinToString("") { (it as? DocParagraph)?.para?.runs?.joinToString("") { run -> run.text } ?: "" }
        assertTrue("定稿的话" in text && "补进去的" in text, text)
        assertFalse("划掉的" in text, "被删的字不该出现：$text")
        val list = read.doc.parts.last() as DocParagraph
        assertEquals(false, list.para.bullet == true, "编号列表该认成编号不是圆点")
    }

    private fun resource(area: String, name: String): String {
        val input = javaClass.classLoader.getResourceAsStream("$area/$name")
        requireNotNull(input) { "缺少夹具 $area/$name" }
        return String(input.readBytes(), Charsets.UTF_8)
    }
}
