package com.fileforge.core

import com.fileforge.core.doc.Html
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HTML 读取那一路。期望值照浏览器与 HTML 规范的容错写法判：
 * 真网页不会有人手工闭合每一个标签，判据必须假定它是脏的。
 */
class HtmlTest {

    private fun text(source: String) = Html.toPlainText(source).text

    private fun md(source: String) = Html.toMarkdown(source).text

    // ---- 容错 ------------------------------------------------------------------

    @Test
    fun `段落标签不闭合也能分开`() {
        assertEquals("甲\n\n乙\n", text("<p>甲<p>乙"))
    }

    @Test
    fun `收尾标签多余时忽略而不是把父节点带跑`() {
        assertEquals("甲\n", text("<div></div></section><p>甲</p>"))
    }

    @Test
    fun `大写标签与属性一样认`() {
        assertEquals("[文字](https://x.y)\n", md("<P><A HREF=\"https://x.y\">文字</A></P>"))
        assertEquals("文字\n", text("<P><A HREF=\"https://x.y\">文字</A></P>"))
    }

    @Test
    fun `属性值里的尖括号不截断标签`() {
        val rendered = md("<a href=\"https://x.y/a?b=1&c=2\">文字</a>")
        assertTrue("https://x.y/a?b=1&c=2" in rendered, rendered)
    }

    @Test
    fun `裸的尖括号是比较符号不是标签`() {
        assertEquals("a < b 且 c > d\n", text("a < b 且 c > d"))
    }

    @Test
    fun `注释里的标签不算结构`() {
        assertEquals("甲\n\n乙\n", text("<!-- <p>不该出现</p> --><p>甲</p><p>乙</p>"))
    }

    @Test
    fun `整页文档的 head 与样式内容被丢掉并说明`() {
        val result = Html.toPlainText(
            "<html><head><meta charset=utf-8><title>页名</title><style>p{color:red}</style></head>" +
                "<body><p>正文</p><script>var a = 1;</script></body></html>",
        )
        assertEquals("正文\n", result.text)
        assertTrue(result.notes.any { "脚本" in it || "页眉" in it }, result.notes.toString())
    }

    @Test
    fun `textarea 里的内容算文字，script 里的不算`() {
        assertEquals("写好的草稿\n", text("<textarea>写好的草稿</textarea>"))
        assertEquals("", text("<script>写好的草稿</script>"))
    }

    // ---- 实体 ------------------------------------------------------------------

    @Test
    fun `命名与数字实体都解，nbsp 是真不换行空格`() {
        assertEquals("甲 & 乙 <丙> “引号” 10°C —5—\n", text("甲 &amp; 乙 &lt;丙&gt; &ldquo;引号&rdquo; 10&deg;C &#8212;5&#8212;"))
        assertEquals("甲 乙\n", text("甲&nbsp;乙"))
    }

    @Test
    fun `认不出的实体照字面留下并说明`() {
        val result = Html.toPlainText("甲 &unknownthing; 乙")
        assertTrue("&unknownthing;" in result.text, result.text)
        assertTrue(result.notes.any { "实体" in it }, result.notes.toString())
    }

    @Test
    fun `数字实体超出范围不崩`() {
        assertEquals("甲&#xFFFFFFFF;乙\n", text("甲&#xFFFFFFFF;乙"))
    }

    // ---- 结构与两种输出 ------------------------------------------------------------

    @Test
    fun `列表出记号，嵌套缩进`() {
        assertEquals("- 甲\n  - 乙\n- 丙\n", text("<ul><li>甲<ul><li>乙</li></ul></li><li>丙</li></ul>"))
        // 有序列表在纯文本里也留编号：那是结构，不是装饰
        assertEquals("1. 甲\n2. 乙\n", text("<ol><li>甲</li><li>乙</li></ol>"))
    }

    @Test
    fun `有序列表在 Markdown 里带编号`() {
        assertEquals("1. 甲\n2. 乙\n", md("<ol><li>甲</li><li>乙</li></ol>"))
    }

    @Test
    fun `标题按级别写成井号`() {
        assertEquals("# 一\n\n### 三\n", md("<h1>一</h1><h3>三</h3>"))
    }

    @Test
    fun `强调与代码转成 Markdown 标记`() {
        val tick = "`"
        assertEquals("**粗** 和 *斜* 和 " + tick + "码" + tick + " 和 ~~废~~\n",
            md("<strong>粗</strong> 和 <em>斜</em> 和 <code>码</code> 和 <del>废</del>"))
    }

    @Test
    fun `链接与图片两种输出各自处理`() {
        assertEquals("[文字](https://x.y)\n", md("<a href=\"https://x.y\">文字</a>"))
        assertEquals("文字\n", text("<a href=\"https://x.y\">文字</a>"))
        assertEquals("![注](a.png)\n", md("<img src=\"a.png\" alt=\"注\">"))
        assertEquals("注\n", text("<img src=\"a.png\" alt=\"注\">"))
    }

    @Test
    fun `锚点链接不带地址过去，正文留着`() {
        assertEquals("目录\n", md("<a href=\"#top\">目录</a>"))
    }

    @Test
    fun `br 是换行不是新段落`() {
        assertEquals("甲\n乙\n", text("甲<br>乙"))
        // Markdown 的硬换行写法是行尾两格
        assertEquals("甲  \n乙\n", md("甲<br>乙"))
    }

    @Test
    fun `引用块与代码块各自成块`() {
        assertEquals("> 引用\n\n甲\n", md("<blockquote><p>引用</p></blockquote><p>甲</p>"))
        assertEquals("```python\nx = 1\n```\n", md("<pre><code class=\"language-python\">x = 1</code></pre>"))
    }

    @Test
    fun `表格转成 GFM 管道表`() {
        assertEquals("| 甲 | 乙 |\n|---|---|\n| 1 | 2 |\n", md("<table><tr><th>甲</th><th>乙</th></tr><tr><td>1</td><td>2</td></tr></table>"))
    }

    @Test
    fun `表格在纯文本里按制表符分列`() {
        assertEquals("甲\t乙\n1\t2\n", text("<table><tr><td>甲</td><td>乙</td></tr><tr><td>1</td><td>2</td></tr></table>"))
    }

    @Test
    fun `格子里的竖线转义，不撑破表格`() {
        assertTrue("\\|" in md("<table><tr><td>a|b</td></tr></table>"))
    }

    @Test
    fun `正文里的星号与井号转义，回去还是原字`() {
        assertEquals("\\*不是强调\\*\n", md("<p>*不是强调*</p>"))
        assertEquals("\\# 不是标题\n", md("<p># 不是标题</p>"))
    }

    @Test
    fun `补过的标签要说出来`() {
        val result = Html.toMarkdown("<p>甲<p>乙<p>丙")
        assertTrue(result.notes.any { "没按规矩闭合" in it }, result.notes.toString())
    }

    @Test
    fun `空文档给空结果，不是一份只有换行的文件`() {
        assertEquals("", text(""))
        assertEquals("", text("   \n  "))
        assertEquals("", text("<html><head><title>只有标题</title></head><body></body></html>"))
    }

    @Test
    fun `嵌套表格与行内标签不吞字`() {
        val source = "<div><p>前一段<b>加粗的词</b>后</p><table><tr><td><em>斜的格子</em></td></tr></table></div>"
        listOf(text(source), md(source)).forEach { rendered ->
            assertTrue("前一段" in rendered && "加粗的词" in rendered && "后" in rendered, rendered)
            assertTrue("斜的格子" in rendered, rendered)
        }
    }
}
