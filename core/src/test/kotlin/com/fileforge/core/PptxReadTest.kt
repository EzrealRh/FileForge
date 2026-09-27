package com.fileforge.core

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocTable
import com.fileforge.core.office.PptxRead
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * pptx 的**结构**读法（`:core` 的 PptxRead）。
 *
 * 形状是照 ECMA-376 DrawingML 手写的：占位符类型、`a:pPr` 里的三种列表记号、
 * run 上的 `a:rPr`、格子的 gridSpan / rowSpan、页与页的关系表 —— 这些都不在"连字"那条路上，
 * 只能把文件自己写的东西摆出来才判得出来读对没读对。
 *
 * 只比这些不够：真实夹具（`tools/make_pptx_fixtures.py` 由 pandoc 的 pptx 写者产出、
 * 再由 pandoc 自己读回来当参照）的四条产物落在 `build/pptxread/`，由 `tools/verify_pptx_read.py` 判。
 */
class PptxReadTest {

    private fun slideOf(body: String) =
        (SLIDE_DECL + "<p:sld " + NS + "><p:cSld><p:spTree>" + GROUP_HEADER + body + "</p:spTree></p:cSld></p:sld>")
            .toByteArray(Charsets.UTF_8)

    /** 一页一页地喂：`pages` 是每页 spTree 里的内容，`extra` 是关系表与演示大纲。 */
    private fun read(
        vararg pages: String,
        extra: Map<String, ByteArray> = emptyMap(),
    ): com.fileforge.core.office.PptxSlideBody {
        val parts = LinkedHashMap<String, ByteArray>()
        pages.forEachIndexed { index, body -> parts["ppt/slides/slide${index + 1}.xml"] = slideOf(body) }
        extra.forEach { (name, bytes) -> parts[name] = bytes }
        return PptxRead.read({ name -> parts[name] }, parts.keys.toList())
    }

    private fun shape(id: String, placeholder: String, paragraphs: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"s$id\"/><p:cNvSpPr/><p:nvPr>$placeholder</p:nvPr></p:nvSpPr>" +
            "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>$paragraphs</p:txBody></p:sp>"

    private fun styleOf(part: DocPart): String = (part as DocParagraph).para.style
    private fun para(part: DocPart): DocPara = (part as DocParagraph).para

    @Test
    fun `占位符类型决定这块是标题还是正文`() {
        val read = read(
            shape("1", "<p:ph type=\"title\"/>", "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>页标题</a:t></a:r></a:p>") +
                shape("2", "<p:ph type=\"body\" idx=\"2\"/>", "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>正文一段</a:t></a:r></a:p>"),
        )
        // 第 1 页那一行"第 N 页"是读法自己补的页界
        assertEquals(listOf("Heading1", "Heading2", "Body"), read.doc.parts.map { styleOf(it) })
        assertEquals("页标题", para(read.doc.parts[1]).text)
    }

    @Test
    fun `圆点与编号与非列表是三种记号`() {
        val read = read(
            shape("1", "<p:ph type=\"body\"/>",
                "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>不是列表</a:t></a:r></a:p>" +
                    "<a:p><a:pPr lvl=\"0\"><a:buChar char=\"•\"/></a:pPr><a:r><a:t>圆点</a:t></a:r></a:p>" +
                    "<a:p><a:pPr lvl=\"1\"><a:buAutoNum type=\"arabicPeriod\"/></a:pPr><a:r><a:t>第二层编号</a:t></a:r></a:p>",
            ),
        )
        val list = read.doc.parts.drop(1).map { para(it) }
        assertEquals(listOf("Body", "ListParagraph", "ListParagraph"), list.map { it.style })
        assertEquals(listOf(null, true, false), list.map { it.bullet })
        assertEquals(listOf(0, 0, 1), list.map { it.indent })
    }

    @Test
    fun `没写记号的段按普通段落排并说明`() {
        // 那种白靠母版的默认列表样式，不在文件里：推成圆点就是拿猜的当读出来的
        val read = read(
            shape("1", "<p:ph type=\"body\"/>", "<a:p><a:pPr lvl=\"0\"/><a:r><a:t>只有层级没有记号</a:t></a:r></a:p>"),
        )
        assertEquals("Body", styleOf(read.doc.parts.last()))
        assertNull(para(read.doc.parts.last()).bullet)
        assertTrue(read.notes.any { "没写列表记号" in it }, read.notes.toString())
    }

    @Test
    fun `run 上的记号与等宽字体都认得`() {
        val read = read(
            shape("1", "<p:ph type=\"body\"/>",
                "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr>" +
                    "<a:r><a:rPr/><a:t>平</a:t></a:r>" +
                    "<a:r><a:rPr b=\"1\"/><a:t>粗</a:t></a:r>" +
                    "<a:r><a:rPr i=\"1\"/><a:t>斜</a:t></a:r>" +
                    "<a:r><a:rPr strike=\"sngStrike\"/><a:t>删</a:t></a:r>" +
                    "<a:r><a:rPr u=\"sngSingle\"/><a:t>下</a:t></a:r>" +
                    "<a:r><a:rPr><a:latin typeface=\"Courier\"/></a:rPr><a:t>码</a:t></a:r>" +
                    "</a:p>",
            ),
        )
        val runs = para(read.doc.parts.last()).runs
        assertEquals(listOf("平", "粗", "斜", "删", "下", "码"), runs.map { it.text })
        assertEquals(listOf(false, true, false, false, false, false), runs.map { it.bold })
        assertEquals(listOf(false, false, true, false, false, false), runs.map { it.italic })
        assertEquals(listOf(false, false, false, true, false, false), runs.map { it.strike })
        assertEquals(listOf(false, false, false, false, true, false), runs.map { it.underline })
        assertEquals(listOf(false, false, false, false, false, true), runs.map { it.mono })
    }

    @Test
    fun `链接过这一页的关系表拿照字面的地址`() {
        val rels = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships " +
            "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" " +
            "Target=\"https://example.com/a?x=1&amp;y=2\" TargetMode=\"External\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" " +
            "Target=\"slide2.xml\"/></Relationships>")
            .toByteArray(Charsets.UTF_8)
        val read = read(
            shape("1", "<p:ph type=\"body\"/>",
                "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>前面</a:t></a:r>" +
                    "<a:r><a:rPr><a:hlinkClick r:id=\"rId2\"/></a:rPr><a:t>链接在</a:t></a:r>" +
                    "<a:r><a:rPr><a:hlinkClick r:id=\"rId3\"/></a:rPr><a:t>跳到别页</a:t></a:r>" +
                    "<a:r><a:rPr><a:hlinkClick r:id=\"rId9\"/></a:rPr><a:t>找不到</a:t></a:r></a:p>",
            ),
            extra = mapOf("ppt/slides/_rels/slide1.xml.rels" to rels),
        )
        val runs = para(read.doc.parts.last()).runs
        assertEquals("https://example.com/a?x=1&y=2", runs[1].link)
        // 段内跳转（指着包里的另一页）不是能点出去的地址：字留着，地址不编。
        // 后面两段都没有记号，按同一格式并成一段（与 docx / odt 那两侧同一条做法）。
        assertEquals("前面链接在跳到别页找不到", runs.joinToString("") { it.text })
        assertEquals(3, runs.size)
        assertNull(runs[2].link)
        assertTrue(read.notes.any { "找不到目标" in it }, read.notes.toString())
        assertTrue(read.notes.any { "段内跳转" in it }, read.notes.toString())
    }

    @Test
    fun `段内换行与制表在字里`() {
        val read = read(
            shape("1", "<p:ph type=\"body\"/>",
                "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>甲</a:t></a:r><a:br/><a:r><a:t>乙</a:t></a:r>" +
                    "<a:tab/><a:r><a:t>丙</a:t></a:r></a:p>",
            ),
        )
        assertEquals("甲\n乙\t丙", para(read.doc.parts.last()).text)
    }

    @Test
    fun `表格的表头与跨列要照文件说的来`() {
        val table = "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"7\" name=\"t\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl>" +
            "<a:tblPr firstRow=\"1\"/><a:tr>" +
            "<a:tc><a:txBody><a:p><a:r><a:t>甲</a:t></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc gridSpan=\"2\"><a:txBody><a:p><a:r><a:t>跨两列</a:t></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc rowSpan=\"2\"><a:txBody><a:p><a:r><a:t>竖合并</a:t></a:r></a:p></a:txBody></a:tc>" +
            "</a:tr><a:tr>" +
            "<a:tc><a:txBody><a:p><a:r><a:t>一</a:t></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc><a:txBody><a:p><a:r><a:t>二</a:t></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc><a:txBody><a:p><a:r><a:t>三</a:t></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc><a:txBody><a:p><a:r><a:t>四</a:t></a:r></a:p></a:txBody></a:tc>" +
            "</a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
        val read = read(table)
        val made = read.doc.parts.last() as DocTable
        assertEquals(true, made.header)
        assertEquals(listOf(listOf("甲", "跨两列", "", "竖合并"), listOf("一", "二", "三", "四")), made.rows)
        assertTrue(read.notes.any { "竖着合并" in it }, read.notes.toString())
    }

    @Test
    fun `页序按大纲_每页前补一行页界`() {
        val deck = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:presentation " + NS + "><p:sldIdLst>" +
            "<p:sldId id=\"257\" r:id=\"rIdB\"/><p:sldId id=\"258\" r:id=\"rIdA\"/></p:sldIdLst></p:presentation>")
            .toByteArray(Charsets.UTF_8)
        val rels = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships " +
            "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdA\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide1.xml\"/>" +
            "<Relationship Id=\"rIdB\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide2.xml\"/>" +
            "</Relationships>").toByteArray(Charsets.UTF_8)
        val read = read(
            shape("1", "<p:ph type=\"title\"/>", "<a:p><a:r><a:t>第一页在文件里是后写的</a:t></a:r></a:p>"),
            shape("1", "<p:ph type=\"title\"/>", "<a:p><a:r><a:t>第二页排在前面</a:t></a:r></a:p>"),
            extra = mapOf(
                "ppt/presentation.xml" to deck,
                "ppt/_rels/presentation.xml.rels" to rels,
            ),
        )
        assertEquals(listOf("第 1 页", "第二页排在前面", "第 2 页", "第一页在文件里是后写的"), read.doc.parts.map { para(it).text })
    }

    @Test
    fun `图形与图表与嵌入对象不当前正文但要说`() {
        val read = read(
            shape("1", "<p:ph type=\"chart\"/>", "<a:p><a:r><a:t>图的占位符</a:t></a:r></a:p>") +
                shape("2", "<p:ph type=\"obj\"/>", "<a:p><a:r><a:t>嵌入对象</a:t></a:r></a:p>") +
                "<p:pic><p:nvPicPr><p:cNvPr id=\"9\" name=\"pic\"/></p:nvPicPr></p:pic>" +
                shape("3", "<p:ph type=\"body\"/>", "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>正文</a:t></a:r></a:p>"),
        )
        assertEquals(listOf("Heading1", "Body"), read.doc.parts.map { styleOf(it) })
        assertEquals("正文", para(read.doc.parts.last()).text)
        assertTrue(read.notes.any { "图表" in it }, read.notes.toString())
        assertTrue(read.notes.any { "嵌入对象" in it }, read.notes.toString())
        assertTrue(read.notes.any { "图片" in it }, read.notes.toString())
    }

    @Test
    fun `组合图形里的形状要钻进去看`() {
        val read = read(
            "<p:grpSp><p:nvGrpSpPr><p:cNvPr id=\"5\" name=\"g\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
                shape("6", "<p:ph type=\"body\"/>", "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>组合里的正文</a:t></a:r></a:p>") +
                "</p:grpSp>",
        )
        assertEquals("组合里的正文", para(read.doc.parts.last()).text)
    }

    @Test
    fun `一页都没有要直说`() {
        assertThrows(IllegalArgumentException::class.java) {
            PptxRead.read({ null }, emptyList())
        }
    }

    @Test
    fun `大纲里那页在包里找不到要报数`() {
        val deck = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:presentation " + NS + "><p:sldIdLst>" +
            "<p:sldId id=\"257\" r:id=\"rIdZ\"/></p:sldIdLst></p:presentation>").toByteArray(Charsets.UTF_8)
        val rels = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships " +
            "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdZ\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide9.xml\"/>" +
            "</Relationships>").toByteArray(Charsets.UTF_8)
        val read = read(
            shape("1", "<p:ph type=\"body\"/>", "<a:p><a:pPr lvl=\"0\"><a:buNone/></a:pPr><a:r><a:t>只有一页</a:t></a:r></a:p>"),
            extra = mapOf("ppt/presentation.xml" to deck, "ppt/_rels/presentation.xml.rels" to rels),
        )
        assertTrue(read.notes.any { "找不到那一页" in it || "按文件名补" in it }, read.notes.toString())
    }

    /**
     * 真实夹具的四条产物落盘：判据跑的是磁盘上这些文件。
     *
     * 断言放在产文件**之后**：先断言会让坏实现把上一轮的旧产物留下，外部判据拿着旧文件说全绿。
     */
    @Test
    fun `产物落盘供外部判据对照`() {
        val dir = File("build/pptxread").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
        listOf("deck.pptx", "notes.pptx", "shapes.pptx").forEach { name ->
            val stem = name.substringBeforeLast('.')
            val bytes = resourceBytes("pptxread", name)
            val read = pptx(bytes)
            require(read.doc.parts.isNotEmpty()) { "$stem 什么块都没读到，夹具或读法坏了" }
            File(dir, "$stem.pptx").writeBytes(bytes)
            File(dir, "$stem.shapes.txt").writeText(shapes(read.doc.parts).joinToString("\n") + "\n", Charsets.UTF_8)
            File(dir, "$stem.text.txt").writeText(com.fileforge.core.doc.HtmlWrite.text(read.doc.parts), Charsets.UTF_8)
            File(dir, "$stem.md").writeText(
                com.fileforge.core.doc.Html.toMarkdown(com.fileforge.core.doc.HtmlWrite.body(read.doc.parts)).text,
                Charsets.UTF_8,
            )
            File(dir, "$stem.html").writeText(
                com.fileforge.core.doc.HtmlWrite.page(stem, read.doc.parts, language = "zh").html,
                Charsets.UTF_8,
            )
            File(dir, "$stem.docx").writeBytes(com.fileforge.core.office.DocxWrite.document(read.doc, modifiedAt = 0L).bytes)
            File(dir, "$stem.notes.txt").writeText(read.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        assertEquals(3, dir.listFiles().orEmpty().count { it.name.endsWith(".pptx") })
        assertEquals(3, dir.listFiles().orEmpty().count { it.name.endsWith(".shapes.txt") })
    }

    /** 块的"类型(带层级与记号)"序列：只比结构，不比内容。 */
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
            is com.fileforge.core.doc.DocRule -> "分隔线"
        }
    }

    private fun pptx(bytes: ByteArray): com.fileforge.core.office.PptxSlideBody {
        val archive = com.fileforge.core.archive.ZipReader.read(bytes)
        val slices = com.fileforge.core.archive.ZipReader.slicing(bytes)
        val index = archive.entries.associate { entry -> entry.name to com.fileforge.core.archive.ZipReader.dataOf(entry, slices) }
        return PptxRead.read({ name -> index[name] }, index.keys.toList())
    }

    private fun resourceBytes(area: String, name: String): ByteArray {
        val input = javaClass.classLoader.getResourceAsStream("$area/$name")
        requireNotNull(input) { "缺少夹具 $area/$name（先跑 tools/make_pptx_fixtures.py）" }
        return input.readBytes()
    }

    @Test
    fun `跨列与续格一起写时只占一次`() {
        // LibreOffice 会既写 gridSpan="2" 又在后面放一个 hMerge 的续格：两种都数一次，
        // 整行就比表格声明的列数宽（与 ODF 那边同一条坑）
        val table = "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"7\" name=\"t\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl>" +
            "<a:tblPr/><a:tr>" +
            "<a:tc gridSpan=\"2\"><a:txBody><a:p><a:r><a:t>跨两列</a:t></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc hMerge=\"1\"><a:txBody><a:p><a:r><a:t/></a:r></a:p></a:txBody></a:tc>" +
            "<a:tc><a:txBody><a:p><a:r><a:t>末列</a:t></a:r></a:p></a:txBody></a:tc>" +
            "</a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
        val made = read(table).doc.parts.last() as DocTable
        assertEquals(listOf(listOf("跨两列", "", "末列")), made.rows)
    }

    private companion object {
        const val SLIDE_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        const val NS =
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
        const val GROUP_HEADER =
            "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"
    }
}
