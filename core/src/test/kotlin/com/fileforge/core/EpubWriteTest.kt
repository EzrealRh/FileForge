package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.book.Epub
import com.fileforge.core.book.EpubPage
import com.fileforge.core.book.EpubWrite
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocTable
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 写出来的 EPUB 合不合法、结构在不在。
 *
 * 判据里最要紧的是"别人能不能解析"：所以每份产物都过一遍 **JVM 自带的 XML 解析器**
 * （不是我们那套容错词法）—— 少闭合一个标签、漏转义一个 `&`，它当场就报错。
 */
class EpubWriteTest {

    private fun p(vararg runs: DocRun) = DocParagraph(DocPara(runs.toList()))

    private fun para(text: String, style: String = "Body", indent: Int = 0, bullet: Boolean? = null) =
        DocParagraph(DocPara(listOf(DocRun(text)), style, indent, bullet))

    private fun book(parts: List<DocPart>, title: String = "测试书") = EpubWrite.book(
        title = title,
        author = null,
        pages = EpubWrite.chaptersOf(Doc(parts, emptyList()), "文件名的那一章"),
        identifier = "urn:uuid-test",
        modifiedAt = 1_700_000_000_000L,
    )

    private fun part(bytes: ByteArray, name: String): String {
        val archive = ZipReader.read(bytes)
        val entry = archive.entries.firstOrNull { it.name == name }
        requireNotNull(entry) { "包里没有 $name：${archive.entries.map { it.name }}" }
        return String(ZipReader.dataOf(entry, ZipReader.slicing(bytes)), Charsets.UTF_8)
    }

    /**
     * 用 JVM 的解析器判良构：它不认容错，标签少闭合就抛。
     *
     * 顺手把"外部 DTD 一律不加载"打开 —— 判据要离线也能跑出同一个结论，
     * 而且产物真塞了联网地址的话，这里不会替它把那次请求发出去。
     */
    private fun assertWellFormed(xml: String, what: String) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertNotNull(document.documentElement, what)
    }

    private fun assertNotNull(value: Any?, what: String) {
        assertTrue(value != null, "$what：拿到 null")
    }

    @Test
    fun `mimetype 排第一且不压缩，别的条目在它后面`() {
        val archive = ZipReader.read(book(listOf(para("正文"))).bytes)
        assertEquals("mimetype", archive.entries.first().name)
        assertEquals(0, archive.entries.first().method)
        assertTrue(archive.entries.map { it.name }.containsAll(listOf("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/toc.ncx", "OEBPS/nav.xhtml", "OEBPS/text/ch1.xhtml")))
    }

    @Test
    fun `一级标题分章，开篇之前有内容就单算一章`() {
        val pages = EpubWrite.chaptersOf(
            Doc(
                listOf(para("先说两句"), para("第一章的标题", "Heading1"), para("内容甲"), para("第二章的标题", "Heading1"), para("内容乙")),
                emptyList(),
            ),
            "文件名",
        )
        assertEquals(listOf("开篇", "第一章的标题", "第二章的标题"), pages.map { it.title })
    }

    @Test
    fun `二级标题不分章，整份没有一级标题就成一章并用文件名当章名`() {
        val pages = EpubWrite.chaptersOf(Doc(listOf(para("甲", "Heading2"), para("乙")), emptyList()), "笔记")
        assertEquals(listOf("笔记"), pages.map { it.title })
    }

    @Test
    fun `章节文件是良构 XHTML，特殊字符一个都不漏`() {
        val bytes = book(
            listOf(
                p(DocRun("比 3 < 5 与 甲 & 乙"), DocRun("带引号的地址", link = "https://example.com/a?x=1&y=2")),
                DocRule(),
                DocTable(true, listOf(listOf("表头 & 尖", "乙"), listOf("<值>", "2"))),
            ),
        ).bytes
        val xhtml = part(bytes, "OEBPS/text/ch1.xhtml")
        assertWellFormed(xhtml, "章节文件")
        assertTrue("比 3 < 5 与 甲 & 乙" in xhtml.replace("&lt;", "<").replace("&amp;", "&"), xhtml.take(400))
        assertTrue("<hr/>" in xhtml, xhtml)
        assertTrue("<th>表头 &amp; 尖</th>" in xhtml, xhtml)
        assertTrue("<td>&lt;值&gt;</td>" in xhtml, xhtml)
        assertTrue("<a href=\"https://example.com/a?x=1&amp;y=2\">" in xhtml, xhtml)
    }

    @Test
    fun `列表按层级套起来并且闭合齐整`() {
        val xhtml = part(
            book(
                listOf(
                    para("点一", "ListParagraph", 0, true),
                    para("点二", "ListParagraph", 0, true),
                    para("更深的一点", "ListParagraph", 1, true),
                    para("编号一", "ListParagraph", 0, false),
                    para("收尾的正文"),
                ),
            ).bytes,
            "OEBPS/text/ch1.xhtml",
        )
        val body = xhtml.substringAfter("<body>").substringBefore("</body>")
        listOf("ul", "ol", "li").forEach { tag ->
            assertEquals(body.split("<$tag>").size - 1, body.split("</$tag>").size - 1, "$tag 的开与闭不齐：$body")
        }
        assertTrue("<ul><li>点一</li><li>点二<ul><li>更深的一点</li></ul></li></ul>" in body.replace("\n", ""), body)
        assertTrue("<ol><li>编号一</li></ol>" in body, body)
    }

    @Test
    fun `连着导两本书不共用列表状态`() {
        // 上一本以列表收尾：状态若挂在 object 上，下一本的第一段会被塞进上一本没关的 li 里
        book(listOf(para("点一", "ListParagraph", 0, true)))
        val xhtml = part(book(listOf(para("干净的一段"))).bytes, "OEBPS/text/ch1.xhtml")
        assertFalse("<li>" in xhtml, xhtml)
        assertTrue("<body><p>干净的一段</p>" in xhtml, xhtml)
    }

    @Test
    fun `OPF 与 NCX 良构且 spine 的每个 idref 都在清单里`() {
        val bytes = book(listOf(para("甲"), para("第一章标题", "Heading1"), para("乙"))).bytes
        val opf = part(bytes, "OEBPS/content.opf")
        val ncx = part(bytes, "OEBPS/toc.ncx")
        assertWellFormed(opf, "OPF")
        assertWellFormed(ncx, "NCX")
        assertWellFormed(part(bytes, "META-INF/container.xml"), "container")
        val ids = Regex("<item id=\"([^\"]+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        val refs = Regex("idref=\"([^\"]+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        assertEquals(listOf("ncx", "nav", "c1", "c2"), ids)
        assertEquals(listOf("c1", "c2"), refs.filter { it != "ncx" })
        assertTrue("urn:uuid-test" in ncx && "<dc:identifier id=\"id\">urn:uuid-test</dc:identifier>" in opf, "两处该是同一个号")
        assertTrue("<dc:date>2023-11-14T22:13:20Z</dc:date>" in opf, opf)
        assertEquals("3.0", Regex("<package[^>]*version=\"([0-9.]+)\"").find(opf)!!.groupValues[1])
    }

    @Test
    fun `目录两份都对得上章数，且不指向别人的地址`() {
        val bytes = book(listOf(para("甲"), para("第一章标题", "Heading1"), para("乙"), para("第二章标题", "Heading1"), para("丙"))).bytes
        val nav = part(bytes, "OEBPS/nav.xhtml")
        val ncx = part(bytes, "OEBPS/toc.ncx")
        assertWellFormed(nav, "nav")
        assertEquals(listOf("text/ch1.xhtml", "text/ch2.xhtml", "text/ch3.xhtml"), Regex("href=\"([^\"]+)\"").findAll(nav).map { it.groupValues[1] }.toList())
        assertEquals(listOf("开篇", "第一章标题", "第二章标题"), Regex("<a href=\"[^\"]+\">([^<]*)</a>").findAll(nav).map { it.groupValues[1] }.toList())
        assertEquals(3, Regex("<navPoint ").findAll(ncx).count())
        // nav 的链接必须逐条落在清单里：指错一个，阅读器点目录就是白屏
        val hrefs = Regex("<item id=\"[^\"]+\" href=\"([^\"]+)\"").findAll(part(bytes, "OEBPS/content.opf")).map { it.groupValues[1] }.toSet()
        Regex("href=\"([^\"]+)\"").findAll(nav).map { it.groupValues[1] }.toList().forEach { assertTrue(it in hrefs, "nav 指的 $it 不在清单里：$hrefs") }
        Regex("<content src=\"([^\"]+)\"").findAll(ncx).map { it.groupValues[1] }.toList().forEach { assertTrue(it in hrefs, "NCX 指的 $it 不在清单里：$hrefs") }
        // DTD 里那个 SYSTEM 地址会让解析器联网取文件，我们读别人的一侧正因为这个拒收带 DTD 的
        Regex("<!DOCTYPE[^>]*>").findAll(nav + ncx + part(bytes, "OEBPS/content.opf") + part(bytes, "META-INF/container.xml") + part(bytes, "OEBPS/text/ch1.xhtml"))
            .map { it.value }.toList().forEach { assertEquals("<!DOCTYPE html>", it, "这里冒出了一个带外部标识符的 DTD") }
    }

    @Test
    fun `自家读的那一侧能整本读回来`() {
        val bytes = book(
            listOf(
                para("前言"), para("第一章标题", "Heading1"), para("正文"),
                para("第二章标题", "Heading1"), para("收尾"),
            ),
        ).bytes
        val archive = ZipReader.read(bytes)
        val slices = ZipReader.slicing(bytes)
        val read = Epub.read(archive.entries.map { it.name }) { name ->
            val entry = archive.entries.firstOrNull { it.name == name } ?: return@read null
            ZipReader.dataOf(entry, slices)
        }
        assertEquals("测试书", read.title)
        assertEquals(listOf("开篇", "第一章标题", "第二章标题"), read.chapters.map { it.title })
        assertTrue(read.notes.none { it.contains("校验和") }, read.notes.toString())
        // 目录那份（nav.xhtml）不在 spine 里，读的一侧不该把它算成"漏掉的正文"，也不该多读出一章目录
        assertTrue(read.notes.none { it.contains("没被 spine 用到") }, read.notes.toString())
        assertTrue("前言" in read.chapters[0].source && "正文" in read.chapters[1].source && "收尾" in read.chapters[2].source,
            read.chapters.joinToString(" | ") { it.source.take(120) })
        assertTrue(read.chapters.none { "<ol>" in it.source }, "读出来的一章里冒出了目录：${read.chapters.map { it.part }}")
    }

    @Test
    fun `一级标题既当章名也留在正文里`() {
        val bytes = book(listOf(para("第一章标题", "Heading1"), para("内容甲"))).bytes
        val xhtml = part(bytes, "OEBPS/text/ch1.xhtml")
        assertTrue("<h1>第一章标题</h1>" in xhtml, xhtml)
        assertTrue("<p>内容甲</p>" in xhtml, xhtml)
        // 章名与正文里的标题是同一句，读回来（EPUB → Markdown）才不会少一级标题
        val read = ZipReader.read(bytes)
        assertTrue("第一章标题" in part(bytes, "OEBPS/toc.ncx"))
        assertEquals(1, read.entries.count { it.name == "OEBPS/text/ch1.xhtml" })
    }

    @Test
    fun `同一个内容每次导出是同一个号`() {
        val a = EpubWrite.identifierFor("同一份正文")
        assertEquals(a, EpubWrite.identifierFor("同一份正文"))
        assertFalse(a == EpubWrite.identifierFor("同一份正文改了一个字"))
        // 形状要能被任何按 UUID 读的工具接住：8-4-4-4-12、版本位 5、变异位是 RFC 4122 那一档
        assertTrue(
            Regex("^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a),
            a,
        )
        val parsed = java.util.UUID.fromString(a.removePrefix("urn:uuid:"))
        assertEquals(5, parsed.version())
        assertEquals(2, parsed.variant())
    }

    @Test
    fun `语言按正文文字数出来，标签名不算正文`() {
        fun lang(vararg texts: String) = EpubWrite.languageOf(Doc(texts.map { para(it) }, emptyList()))
        assertEquals("zh", lang("这一章讲的是中文的段落"))
        assertEquals("en", lang("This chapter is written in English."))
        // 中文为主的段落里夹几个英文词，仍然是 zh
        assertEquals("zh", lang("这一章讲了 alpha 与 beta 两个词，剩下的都是中文。"))
        // 反过来，英文稿里夹一个中文词还是 en —— 硬标 zh 会让整本按中文断词
        assertEquals("en", lang("This chapter has one 中 token and nothing else."))
        assertEquals("und", lang("1234 · 5678"))
        assertEquals("und", lang(""))
        // 中文网页摊成文档树之后，标签名（html / div / charset）不该把语言带偏
        val page = Doc(listOf(para("这一页正文是中文的，标签是英文的。")), emptyList())
        assertEquals("zh", EpubWrite.languageOf(page))
        val table = Doc(
            listOf(DocTable(true, listOf(listOf("表头", "乙"), listOf("值一", "值二")))),
            emptyList(),
        )
        assertEquals("zh", EpubWrite.languageOf(table))
    }

    @Test
    fun `没有作者时说出来而不是编一个`() {
        val opf = part(book(listOf(para("正文"))).bytes, "OEBPS/content.opf")
        assertFalse("<dc:creator" in opf, opf)
        assertTrue(book(listOf(para("正文"))).notes.any { it.contains("没有作者") })
        val withAuthor = EpubWrite.book(
            title = "有作者的书", author = "某人", pages = listOf(EpubPage("一章", listOf(para("正文")))),
            identifier = "urn:uuid-a", modifiedAt = 0L,
        )
        assertTrue("<dc:creator>某人</dc:creator>" in part(withAuthor.bytes, "OEBPS/content.opf"))
    }
}