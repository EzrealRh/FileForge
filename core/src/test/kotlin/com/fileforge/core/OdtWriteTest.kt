package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.Html
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.office.OdtRead
import com.fileforge.core.office.OdtWrite
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 写 .odt（`core/office/OdtWrite.kt`）。
 *
 * 主要判法是**自己写自己读回来同一棵树**：写的那侧和读的那侧（[OdtRead]，它已经由 pandoc 逐块对过）
 * 是两条独立的路，两边对得上就说明样式名、`text:outline-level`、列表套层、
 * `table:table-header-rows` 这些位置都写在别人也认的地方，而不是只有自家读得懂。
 *
 * 真实产物落在 `build/odtwrite/`，由 `tools/verify_odt_write.py` 拿 **pandoc 的 ODT 读者**
 * 与它对同一份内容经 Markdown 那条路的读数逐块比对（pandoc 不读我们的 Kotlin，两边独立）。
 */
class OdtWriteTest {

    private fun bytes(doc: Doc, title: String = "") = OdtWrite.document(doc, title, modifiedAt = 0L)

    private fun part(file: ByteArray, name: String): String = String(ZipReader.dataOf(
        ZipReader.read(file).entries.first { it.name == name },
        ZipReader.slicing(file),
    ), Charsets.UTF_8)

    private fun readBack(file: ByteArray): Doc {
        val slices = ZipReader.slicing(file)
        val index = ZipReader.read(file).entries.associate { it.name to ZipReader.dataOf(it, slices) }
        return OdtRead.read { name -> index[name] }.doc
    }

    private fun shape(parts: List<DocPart>): List<String> = parts.map { part ->
        when (part) {
            is DocParagraph -> {
                val para = part.para
                val mark = para.runs.joinToString("") { run ->
                    (if (run.bold) "b" else "") + (if (run.italic) "i" else "") + (if (run.mono) "c" else "") +
                        (if (run.strike) "s" else "") + (if (run.underline) "u" else "") +
                        (if (!run.link.isNullOrBlank()) "L" else "")
                }
                val list = if (para.style == "ListParagraph") {
                    " 层${para.indent} ${if (para.bullet == false) "编号" else "圆点"}"
                } else {
                    ""
                }
                "${para.style}$list[${mark}] ${para.text}"
            }
            is DocRule -> "分隔线"
            is DocTable -> "表 ${part.rows.size}行×${part.rows.maxOf { it.size }}列 表头=${part.header} " +
                part.rows.joinToString("/") { it.joinToString(",") }
        }
    }

    private fun paragraph(text: String, style: String = "Body", vararg runs: DocRun) =
        DocParagraph(DocPara(if (runs.isEmpty()) listOf(DocRun(text)) else runs.toList(), style))

    @Test
    fun `写出去再读回来是同一棵树`() {
        val source = Doc(
            listOf(
                paragraph("一级标题", "Heading1"),
                paragraph("正文里有粗体和普通", "Body", DocRun("粗的那段", bold = true), DocRun("和普通")),
                paragraph("引用的一句话", "Quote"),
                DocParagraph(DocPara(listOf(DocRun("第一行\n第二行")), "SourceCode")),
                DocParagraph(DocPara(listOf(DocRun("圆点一")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("嵌一层的圆点")), "ListParagraph", indent = 1, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("编号一")), "ListParagraph", indent = 0, bullet = false)),
                DocRule(),
                DocTable(true, listOf(listOf("名称", "数量"), listOf("甲", "1"), listOf("乙", ""))),
            ),
            emptyList(),
        )
        val read = readBack(bytes(source).bytes)
        assertEquals(shape(source.parts), shape(read.parts), "写出去再读回来不是同一棵树")
    }

    @Test
    fun `mimetype 是第一条且不压缩`() {
        val out = bytes(Doc(listOf(paragraph("一句话")), emptyList()))
        val first = ZipReader.read(out.bytes).entries.first()
        assertEquals("mimetype", first.name)
        assertTrue(first.compressedSize.toLong() == first.size, "mimetype 要原样存，不压缩")
        assertEquals(
            "application/vnd.oasis.opendocument.text",
            String(ZipReader.dataOf(first, ZipReader.slicing(out.bytes)), Charsets.UTF_8),
        )
    }

    @Test
    fun `样式名两种写法都留着`() {
        val styles = part(bytes(Doc(listOf(paragraph("题", "Heading2")), emptyList())).bytes, "styles.xml")
        assertTrue("style:name=\"Heading_20_2\"" in styles, "内部名（空格按 _20_ 编码）要写")
        assertTrue("style:display-name=\"Heading 2\"" in styles, "给人看的名字要另写一份")
        assertTrue("text:outline-level=\"2\"" in styles, "层级写在样式上")
    }

    @Test
    fun `标题写成带层级的标题元素`() {
        val content = part(bytes(Doc(listOf(paragraph("三级题", "Heading3")), emptyList())).bytes, "content.xml")
        assertTrue("<text:h text:style-name=\"Heading_20_3\" text:outline-level=\"3\">" in content, content)
    }

    @Test
    fun `表头那行躺在 header-rows 里`() {
        val withHeader = part(bytes(Doc(listOf(DocTable(true, listOf(listOf("甲", "乙"), listOf("1", "2")))), emptyList())).bytes, "content.xml")
        val without = part(bytes(Doc(listOf(DocTable(false, listOf(listOf("甲", "乙"), listOf("1", "2")))), emptyList())).bytes, "content.xml")
        assertTrue("table:table-header-rows" in withHeader, withHeader)
        assertFalse("table:table-header-rows" in without, "没表头就不该有那一层")
        assertTrue("table:table-body" in without, without)
    }

    @Test
    fun `列按声明的根数一条条写`() {
        val content = part(bytes(Doc(listOf(DocTable(false, listOf(listOf("甲", "乙", "丙")))), emptyList())).bytes, "content.xml")
        assertEquals(3, Regex("<table:table-column").findAll(content).count(), content)
        assertFalse("number-columns-repeated" in content, "不写压缩写法：别人按声明数列时要数得出来")
    }

    @Test
    fun `没记号的字不套 span`() {
        val content = part(bytes(Doc(listOf(paragraph("就是一句普通的话")), emptyList())).bytes, "content.xml")
        assertFalse("text:span" in content, "一个记号都没有就不该套 span：$content")
    }

    @Test
    fun `连续空格与制表按 ODF 的元素写`() {
        val doc = Doc(listOf(paragraph("甲  乙\t丙")), emptyList())
        val content = part(bytes(doc).bytes, "content.xml")
        assertTrue("<text:s text:c=\"2\"/>" in content, content)
        assertTrue("<text:tab/>" in content, content)
        assertEquals("甲  乙\t丙", readBack(bytes(doc).bytes).parts.filterIsInstance<DocParagraph>().single().para.text)
    }

    @Test
    fun `段内换行读回来还在`() {
        val doc = Doc(listOf(DocParagraph(DocPara(listOf(DocRun("第一行\n第二行")), "Body"))), emptyList())
        assertEquals("第一行\n第二行", readBack(bytes(doc).bytes).parts.filterIsInstance<DocParagraph>().single().para.text)
    }

    @Test
    fun `链接带地址写出去且地址照字面`() {
        val doc = Doc(
            listOf(paragraph("点这里", "Body", DocRun("点这里", link = "https://example.com/a?x=1&y=2"))),
            emptyList(),
        )
        val content = part(bytes(doc).bytes, "content.xml")
        assertTrue("xlink:href=\"https://example.com/a?x=1&amp;y=2\"" in content, content)
        assertEquals("https://example.com/a?x=1&y=2", readBack(bytes(doc).bytes).parts.filterIsInstance<DocParagraph>()
            .single().para.runs.single().link)
    }

    @Test
    fun `嵌两层就读两层`() {
        val doc = Doc(
            listOf(
                DocParagraph(DocPara(listOf(DocRun("外")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("里")), "ListParagraph", indent = 1, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("又外")), "ListParagraph", indent = 0, bullet = true)),
            ),
            emptyList(),
        )
        val back = readBack(bytes(doc).bytes).parts.filterIsInstance<DocParagraph>().map { it.para }
        assertEquals(listOf(0, 1, 0), back.map { it.indent })
        assertEquals(listOf(true, true, true), back.map { it.bullet })
    }

    @Test
    fun `换记号种类就另起一张表`() {
        val doc = Doc(
            listOf(
                DocParagraph(DocPara(listOf(DocRun("圆点")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("编号")), "ListParagraph", indent = 0, bullet = false)),
            ),
            emptyList(),
        )
        val content = part(bytes(doc).bytes, "content.xml")
        assertTrue("\"LB\"" in content && "\"LN\"" in content, content)
        val back = readBack(bytes(doc).bytes).parts.filterIsInstance<DocParagraph>().map { it.para.bullet }
        assertEquals(listOf(true, false), back)
    }

    @Test
    fun `同一份内容两次写出的字节一样`() {
        val doc = Doc(listOf(paragraph("甲"), paragraph("乙", "Heading1")), emptyList())
        assertEquals(bytes(doc).bytes.toList(), bytes(doc).bytes.toList(), "样式名与块序都该稳定")
    }

    @Test
    fun `表里的空格子与换行不丢`() {
        val doc = Doc(listOf(DocTable(false, listOf(listOf("甲", ""), listOf("第一行\n第二行", "乙")))), emptyList())
        val back = readBack(bytes(doc).bytes).parts.single() as DocTable
        assertEquals(listOf(listOf("甲", ""), listOf("第一行\n第二行", "乙")), back.rows)
    }

    @Test
    fun `没有块的文档也给得出包`() {
        val out = bytes(Doc(emptyList(), emptyList()))
        assertTrue(out.bytes.isNotEmpty())
        assertEquals(emptyList<String>(), readBack(out.bytes).parts.map { "有块" })
    }

    /**
     * 同一棵树的 .odt 与 .md 都落盘：判据拿 pandoc 分别读这两份，比的是"同一份稿子经过两条路
     * 出去，别人读回来的结构是不是同一个东西"。
     *
     * 断言放在产文件**之后**：先断言会让坏实现把上一轮的旧产物留下，判据拿着旧文件说全绿。
     */
    @Test
    fun `产物落盘供外部判据对照`() {
        val dir = File("build/odtwrite").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
        val source = Doc(
            listOf(
                paragraph("简报", "Heading1"),
                paragraph("一段话里有粗体和普通", "Body", DocRun("粗体", bold = true), DocRun("和普通")),
                paragraph("带[个链接](https://example.com/x)的一句话", "Body",
                    DocRun("带"), DocRun("个链接", link = "https://example.com/x"), DocRun("的一句话")),
                paragraph("引用的一句话", "Quote"),
                DocParagraph(DocPara(listOf(DocRun("代码第一行\n代码第二行")), "SourceCode")),
                DocParagraph(DocPara(listOf(DocRun("圆点一")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("圆点二")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("嵌在里面")), "ListParagraph", indent = 1, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("编号一")), "ListParagraph", indent = 0, bullet = false)),
                DocParagraph(DocPara(listOf(DocRun("编号二")), "ListParagraph", indent = 0, bullet = false)),
                DocRule(),
                DocTable(true, listOf(listOf("名称", "数量"), listOf("甲", "1"), listOf("乙", "22"))),
                paragraph("末了一句话", "Heading2"),
            ),
            emptyList(),
        )
        val out = bytes(source, "简报")
        require(out.bytes.isNotEmpty()) { "写出来的包是空的，写的一侧坏了" }
        val back = readBack(out.bytes)
        File(dir, "book.odt").writeBytes(out.bytes)
        File(dir, "book.md").writeText(Html.toMarkdown(HtmlWrite.body(source.parts)).text, Charsets.UTF_8)
        File(dir, "book.shape.txt").writeText(shape(back.parts).joinToString("\n") + "\n", Charsets.UTF_8)
        File(dir, "book.notes.txt").writeText(out.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        assertEquals(2, dir.listFiles().orEmpty().count { it.name.endsWith(".odt") || it.name.endsWith(".md") })
        assertEquals(shape(source.parts), shape(back.parts), "自家读路读回来的与写出去的不是同一棵树")
    }
}
