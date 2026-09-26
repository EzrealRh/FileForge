package com.fileforge.core

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.HtmlWrite
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Doc → HTML 那套渲染器：壳的两种写法、转义、列表嵌套、块类型。
 *
 * 判据里最要紧的是"别人怎么读"：XHTML 那份过 JVM 自带的 XML 解析器（它不认容错），
 * HTML5 那份过 Python 的 html.parser 与 pandoc（`tools/verify_html_write.py`）。
 */
class HtmlWriteTest {

    private fun para(text: String, style: String = "Body", indent: Int = 0, bullet: Boolean? = null) =
        DocParagraph(DocPara(listOf(DocRun(text)), style, indent, bullet))

    private fun p(vararg runs: DocRun) = DocParagraph(DocPara(runs.toList()))

    private fun body(vararg parts: DocPart) = HtmlWrite.body(parts.toList())

    private fun body(parts: List<DocPart>) = HtmlWrite.body(parts)

    private fun parseXml(html: String) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `整页的两种壳各按各的规矩`() {
        val html = HtmlWrite.page("甲 & 乙", listOf(para("一段")), language = "zh").html
        assertTrue(html.startsWith("<!DOCTYPE html>"), html.take(60))
        assertTrue("<html lang=\"zh\">" in html, html)
        assertTrue("<meta charset=\"utf-8\">" in html, "HTML5 的 charset 声明要在前几百字节里管用：$html")
        assertTrue("<title>甲 &amp; 乙</title>" in html, html)
        val xhtml = HtmlWrite.page("甲", listOf(para("一段")), language = "zh", xhtml = true).html
        assertTrue(xhtml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), xhtml.take(80))
        assertTrue("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh\" lang=\"zh\">" in xhtml, xhtml)
        assertTrue("<meta charset=\"utf-8\"/>" in xhtml, xhtml)
        parseXml(xhtml)
    }

    @Test
    fun `块类型各归各的元素`() {
        val out = body(
            para("一级标题", "Heading1"),
            para("三级标题", "Heading3"),
            para("普通一段"),
            para("引用的话", "Quote"),
            para("代码块里的 <标签>", "SourceCode"),
            DocRule(),
            DocTable(true, listOf(listOf("表头", "乙"), listOf("<值>", "2"))),
        )
        assertTrue("<h1>一级标题</h1>" in out, out)
        assertTrue("<h3>三级标题</h3>" in out, out)
        assertTrue("<p>普通一段</p>" in out, out)
        assertTrue("<blockquote>引用的话</blockquote>" in out, out)
        assertTrue("<pre><code>代码块里的 &lt;标签&gt;</code></pre>" in out, out)
        assertTrue("<hr/>" in out, out)
        assertTrue("<table><tr><th>表头</th><th>乙</th></tr><tr><td>&lt;值&gt;</td><td>2</td></tr></table>" in out, out)
    }

    @Test
    fun `记号按结构拼，源文字里的尖括号与 & 一律转义`() {
        val out = body(
            p(
                DocRun("比 3 < 5 与 甲 & 乙"),
                DocRun("加粗", bold = true),
                DocRun("斜", italic = true),
                DocRun("废", strike = true),
                DocRun("码", mono = true),
                DocRun("链接", link = "https://example.com/a?x=1&y=2"),
            ),
        )
        assertTrue("比 3 &lt; 5 与 甲 &amp; 乙" in out, out)
        assertTrue("<strong>加粗</strong>" in out && "<em>斜</em>" in out, out)
        assertTrue("<del>废</del>" in out && "<code>码</code>" in out, out)
        assertTrue("<a href=\"https://example.com/a?x=1&amp;y=2\">链接</a>" in out, out)
    }

    @Test
    fun `列表按层级套起来并换型时先关上一层`() {
        val out = body(
            para("点一", "ListParagraph", 0, true),
            para("点二", "ListParagraph", 0, true),
            para("更深的一点", "ListParagraph", 1, true),
            para("编号一", "ListParagraph", 0, false),
            para("收尾的正文"),
        )
        val flat = out.replace("\n", "")
        assertTrue("<ul><li>点一</li><li>点二<ul><li>更深的一点</li></ul></li></ul>" in flat, flat)
        assertTrue("<ol><li>编号一</li></ol>" in flat, flat)
        listOf("ul", "ol", "li").forEach { tag ->
            assertEquals(flat.split("<$tag>").size - 1, flat.split("</$tag>").size - 1, "$tag 开闭不齐：$flat")
        }
        assertTrue(flat.endsWith("<p>收尾的正文</p>"), flat)
    }

    @Test
    fun `两次渲染互不沾对方的列表状态`() {
        val first = body(listOf(para("点一", "ListParagraph", 0, true)))
        val second = body(listOf(para("干净的一段")))
        assertTrue("<li>" in first, first)
        assertFalse("<li>" in second, second)
        assertEquals("<p>干净的一段</p>\n", second)
    }

    @Test
    fun `全空的段落不写空 p，段与段之间本来就有边界`() {
        val out = body(listOf(para(""), para("有字的一段"), para("   ")))
        assertEquals("<p>有字的一段</p>\n", out, out)
    }

    @Test
    fun `段内换行写成 br 且 XHTML 里能过 XML 解析器`() {
        val out = body(listOf(DocParagraph(DocPara(listOf(DocRun("第一行\n第二行"))))))
        assertTrue("<p>第一行<br/>第二行</p>" in out, out)
        parseXml(HtmlWrite.page("甲", listOf(para("第一行\n第二行")), xhtml = true).html)
    }

    @Test
    fun `正文里一个可读的字都没有时要说出来`() {
        val blank = HtmlWrite.page("空壳", listOf(DocRule()))
        assertTrue(blank.notes.any { "没有可读的文字" in it }, blank.notes.toString())
        assertTrue(HtmlWrite.page("有字", listOf(para("一段"))).notes.isEmpty(), "有字时不该啰嗦")
    }

    @Test
    fun `代码块与格子里的换行是内容，不许被吃掉`() {
        val feed = 10.toChar()
        val code = body(listOf(para("第一行" + feed + "第二行" + feed + "第三行", "SourceCode")))
        assertEquals("<pre><code>第一行" + feed + "第二行" + feed + "第三行</code></pre>" + feed, code, code)
        val cell = body(listOf(DocTable(false, listOf(listOf("第一行" + feed + "第二行", "乙")))))
        assertTrue("<td>第一行<br/>第二行</td>" in cell, cell)
        // 标题里的换行只变成一格：跨行标题还是标题，不该冒出 <br>
        val heading = body(listOf(para("上" + feed + "下", "Heading2")))
        assertEquals("<h2>上 下</h2>" + feed, heading, heading)
        // 行尾的 CRLF 折成一个换行，别留一个看不见的回车
        assertTrue("<p>甲<br/>乙</p>" in body(listOf(para("甲" + 13.toChar() + 10.toChar() + "乙"))), body(listOf(para("甲" + 13.toChar() + "乙"))))
        parseXml(HtmlWrite.page("甲", listOf(para("第一行" + feed + "第二行", "SourceCode")), xhtml = true).html)
    }

    @Test
    fun `看不见也传不出来的控制字符丢掉，制表符留着`() {
        val nul = 0.toChar()
        val bell = 7.toChar()
        val tab = 9.toChar()
        val feed = 10.toChar()
        val out = body(listOf(para("甲" + nul + tab + "乙" + bell + "丙")))
        assertEquals("<p>甲" + tab + "乙丙</p>" + feed, out, out)
        parseXml(HtmlWrite.page("甲", listOf(para("甲" + nul + "乙")), xhtml = true).html)
    }

    @Test
    fun `标题里带尖括号也只是文字`() {
        val out = HtmlWrite.page("<脚本>", listOf(para("一段")), xhtml = true).html
        assertTrue("<title>&lt;脚本&gt;</title>" in out, out)
        parseXml(out)
    }
}
