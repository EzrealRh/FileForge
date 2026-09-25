package com.fileforge.core

import com.fileforge.core.doc.Markdown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Markdown 渲染。期望值照 CommonMark / GFM 的写法判，不照实现判 ——
 * 实现与 pandoc 的一致性是另一条判据（tools/verify_markdown.py），这里只钉住不变量。
 */
class MarkdownTest {

    private fun html(source: String) = Markdown.toHtml(source).text

    private fun plain(source: String) = Markdown.toPlainText(source).text

    private fun lines(source: String) = source.trimEnd('\n').split("\n")

    // ---- 块级 ------------------------------------------------------------------

    @Test
    fun `ATX 标题带级别`() {
        assertEquals("<h1>甲</h1>\n<h3>丙</h3>\n", html("# 甲\n\n### 丙"))
    }

    @Test
    fun `标题结尾的井号是装饰不是正文`() {
        assertEquals("<h2>甲</h2>\n", html("## 甲 ##"))
    }

    @Test
    fun `setext 标题认第一二级`() {
        assertEquals("<h1>甲</h1>\n<h2>乙</h2>\n", html("甲\n=\n\n乙\n-\n"))
    }

    @Test
    fun `段落各自包 p，块与块之间不塞空行`() {
        // 与 pandoc 的 HTML 写法一致：块之间只换行。空行在 HTML 里不表结构，写进产物只会让比对噪声变大
        assertEquals("<p>甲</p>\n<p>乙</p>\n", html("甲\n\n乙"))
    }

    @Test
    fun `段内的换行照原样留下，行尾两格才是硬换行`() {
        assertEquals("<p>甲\n乙</p>\n", html("甲\n乙"))
        // 与 pandoc / CommonMark 一致：<br /> 后面那个换行也留着
        assertEquals("<p>甲<br />\n乙</p>\n", html("甲  \n乙"))
    }

    @Test
    fun `分割线不吞后面的正文`() {
        assertEquals("<hr />\n<p>乙</p>\n", html("---\n\n乙"))
    }

    @Test
    fun `围栏代码里的尖括号与 amp 全按字面`() {
        val rendered = html("```html\n<a href=\"#\">&amp;</a>\n```")
        assertTrue("<pre><code class=\"language-html\">" in rendered, rendered)
        assertTrue("&lt;a href=&quot;#&quot;&gt;&amp;amp;&lt;/a&gt;" in rendered, rendered)
    }

    @Test
    fun `围栏没关上就把剩下的都当代码，不丢字`() {
        val rendered = html("```\n甲\n乙")
        assertTrue("甲" in rendered && "乙" in rendered, rendered)
        assertTrue("<code>" in rendered, rendered)
    }

    @Test
    fun `波浪号围栏与反引号围栏等价`() {
        assertEquals(html("```\nx\n```"), html("~~~\nx\n~~~"))
    }

    @Test
    fun `引用可以嵌套并且不带级别限制`() {
        assertEquals("<blockquote>\n<p>甲</p>\n<blockquote>\n<p>乙</p>\n</blockquote>\n</blockquote>\n",
            html("> 甲\n>\n> > 乙"))
    }

    @Test
    fun `无序列表出 ul，有序出 ol`() {
        assertEquals("<ul>\n<li>甲</li>\n<li>乙</li>\n</ul>\n", html("- 甲\n- 乙"))
        assertEquals("<ol>\n<li>甲</li>\n<li>乙</li>\n</ol>\n", html("1. 甲\n2. 乙"))
    }

    @Test
    fun `起始编号不是 1 时带 start 属性`() {
        assertTrue("<ol start=\"7\">" in html("7. 甲\n8. 乙"), html("7. 甲\n8. 乙"))
    }

    @Test
    fun `列表能嵌套`() {
        val rendered = html("- 甲\n  - 乙\n- 丙")
        assertEquals("<ul>\n<li>甲\n<ul>\n<li>乙</li>\n</ul></li>\n<li>丙</li>\n</ul>\n", rendered)
    }

    @Test
    fun `紧列表不套段落，松列表每项包 p`() {
        assertTrue(html("- 甲\n- 乙").let { "<li>甲</li>" in it })
        val loose = html("- 甲\n\n- 乙")
        assertTrue("<li><p>甲</p></li>" in loose, loose)
    }

    @Test
    fun `列表项里带下一段也算这一项`() {
        val rendered = html("- 甲\n\n  乙\n")
        assertTrue("乙" in rendered, rendered)
        assertTrue(rendered.indexOf("乙") < rendered.indexOf("</ul>"), "续段该在列表里面：$rendered")
    }

    @Test
    fun `星号与加号横杠都是列表记号`() {
        assertEquals(html("- 甲").replace("<ul>", ""), html("* 甲").replace("<ul>", ""))
        assertEquals(html("- 甲").replace("<ul>", ""), html("+ 甲").replace("<ul>", ""))
    }

    @Test
    fun `任务列表出复选框`() {
        val rendered = html("- [x] 做完\n- [ ] 没做")
        assertEquals(2, Regex("checkbox").findAll(rendered).count(), rendered)
        assertTrue("checked" in rendered, rendered)
        val text = plain("- [x] 做完\n- [ ] 没做")
        assertTrue("[x] 做完" in text && "[ ] 没做" in text, text)
    }

    @Test
    fun `表格出 thead 与 tbody，对齐写在 align 上`() {
        val rendered = html("| 甲 | 乙 |\n|:---|---:|\n| 1 | 2 |")
        assertTrue("<table>" in rendered && "<thead>" in rendered && "<tbody>" in rendered, rendered)
        assertTrue("""<th align="left">甲</th>""" in rendered, rendered)
        assertTrue("""<th align="right">乙</th>""" in rendered, rendered)
        assertTrue("""<td align="left">1</td>""" in rendered, rendered)
    }

    @Test
    fun `表格列数对不上就照字面当段落`() {
        val rendered = html("| 甲 | 乙 |\n|---|\n| 1 | 2 |")
        assertTrue("<table>" !in rendered, "列数不齐不该硬当表格：$rendered")
        assertTrue("甲" in rendered && "乙" in rendered && "1" in rendered, "一个字都不许丢：$rendered")
    }

    @Test
    fun `纯文本里表格按制表符分列`() {
        assertEquals("甲\t乙\n1\t2\n", plain("| 甲 | 乙 |\n|---|---|\n| 1 | 2 |"))
    }

    // ---- 行内 ------------------------------------------------------------------

    @Test
    fun `粗体斜体与嵌套`() {
        assertEquals("<p><strong>甲</strong></p>\n", html("**甲**"))
        assertEquals("<p><em>甲</em></p>\n", html("*甲*"))
        assertEquals("<p><strong><em>甲</em></strong></p>\n", html("***甲***"))
        assertEquals("<p><strong>甲 <em>乙</em> 丙</strong></p>\n", html("**甲 *乙* 丙**"))
    }

    @Test
    fun `下划线在词中间不算强调`() {
        assertEquals("<p>snake_case_name</p>\n", html("snake_case_name"))
    }

    @Test
    fun `行内代码里的星号不算标记`() {
        assertEquals("<p><code>a*b</code></p>\n", html("`a*b`"))
        assertEquals("<p><code>有 | 竖线</code></p>\n", html("`有 | 竖线`"))
    }

    @Test
    fun `反引号成对可以包住反引号`() {
        assertEquals("<p><code>`a`</code></p>\n", html("`` `a` ``"))
    }

    @Test
    fun `链接出 a，标题出 title`() {
        assertEquals("<p><a href=\"https://example.com\">文字</a></p>\n", html("[文字](https://example.com)"))
        assertTrue("""title="说明"""" in html("""[文字](https://a.b "说明")"""))
    }

    @Test
    fun `引用式链接从定义行取地址`() {
        val rendered = html("[文字][id]\n\n[id]: https://example.com \"说明\"")
        assertTrue("""<a href="https://example.com" title="说明">文字</a>""" in rendered, rendered)
        assertTrue("说明" !in rendered.substringAfter(">文字</a>"), "定义行不该再出现在正文里：$rendered")
    }

    @Test
    fun `引用式链接找不到定义就照字面留`() {
        assertEquals("<p>[文字][没有这个]</p>\n", html("[文字][没有这个]"))
    }

    @Test
    fun `图片出 img 并带替代文字`() {
        val rendered = html("![图注](a.png)")
        assertTrue("""<img src="a.png" alt="图注" />""" in rendered, rendered)
    }

    @Test
    fun `自动链接认带尖括号的地址`() {
        assertEquals("<p><a href=\"https://example.com\">https://example.com</a></p>\n", html("<https://example.com>"))
    }

    @Test
    fun `删除线出 del`() {
        assertEquals("<p><del>甲</del></p>\n", html("~~甲~~"))
    }

    @Test
    fun `链接标题进 HTML 属性，纯文本里说明它没处放`() {
        val source = """[文字](https://example.com "示例站")"""
        assertTrue("""title="示例站"""" in html(source), html(source))
        val plain = Markdown.toPlainText(source)
        assertEquals("文字\n", plain.text)
        assertTrue(plain.notes.any { "标题" in it }, plain.notes.toString())
    }

    @Test
    fun `反斜杠转义的星号是字面星号`() {
        assertEquals("<p>*甲*</p>\n", html("\\*甲\\*"))
    }

    @Test
    fun `正文里的尖括号与 amp 必须转义`() {
        assertEquals("<p>a &lt; b &amp; c</p>\n", html("a < b & c"))
    }

    @Test
    fun `链接地址里的引号被转义，不能破出属性`() {
        val rendered = html("[甲](url\"onload=alert(1))")
        assertTrue("<a href=\"url&quot;onload=alert(1)\">" in rendered, rendered)
    }

    // ---- 纯文本输出 --------------------------------------------------------------

    @Test
    fun `纯文本吃掉标记但留下结构`() {
        // 标题在纯文本里没有 #（那还是标记）：分段用空行、列表用记号
        assertEquals("甲\n\n乙\n\n- 一\n- 二\n", plain("# 甲\n\n**乙**\n\n- 一\n- 二\n"))
    }

    @Test
    fun `纯文本里链接只留文字并说明地址丢了`() {
        val result = Markdown.toPlainText("[文字](https://example.com)")
        assertEquals("文字\n", result.text)
        assertTrue(result.notes.any { "链接" in it }, result.notes.toString())
    }

    @Test
    fun `纯文本里图片留替代文字并说明`() {
        val result = Markdown.toPlainText("![图注](a.png)")
        assertEquals("图注\n", result.text)
        assertTrue(result.notes.any { "图片" in it }, result.notes.toString())
    }

    // ---- 不丢字这条底线 -------------------------------------------------------------

    @Test
    fun `什么写法都不许把中文字吃掉`() {
        val source = """
            # 标题甲

            正文一段，带`代码`与[链接乙](https://x.y)还有*强调丙*。

            > 引用丁

            - 列表戊
              - 嵌套己

            | 表头庚 | 表头辛 |
            |:---|---:|
            | 壬 | 癸 |

            ```kotlin
            fun 子() = 1
            ```

            脚注引用丑[^note] 与未认的 [^note]: 说明寅

            <div>内嵌 HTML 卯</div>
        """.trimIndent()
        val visible = Regex("[\u4e00-\u9fa5]").findAll(source).map { it.value }.toList()
        listOf(Markdown.toHtml(source), Markdown.toPlainText(source)).forEach { rendered ->
            val kept = Regex("[\u4e00-\u9fa5]").findAll(rendered.text).map { it.value }.toList()
            assertEquals(visible, kept, "汉字必须一个不少（输出：${rendered.text}）")
        }
    }

    @Test
    fun `认不出的写法要说明而不是静悄悄`() {
        val result = Markdown.toHtml("正文\n[^a]: 脚注定义\n\n    缩进四格")
        assertTrue(result.notes.any { "脚注" in it }, result.notes.toString())
        assertTrue(result.notes.any { "缩进" in it }, result.notes.toString())
    }

    @Test
    fun `有没有 Markdown 记号判得出来，不靠记号的文本不许硬转`() {
        val fence = "`".repeat(3) + "kotlin"
        listOf("# 标题", "- 一项", "1. 一项", "> 引用", fence, "甲\n=== ", "| 甲 | 乙 |\n|---|---|")
            .forEach { source ->
                assertTrue(Markdown.looksLikeMarkdown(source), "这份该认成 Markdown：$source")
            }
        listOf("就是普通的一段话，两行。\n第二行而已。", "a,b\n1,2", "{\"key\": 1}").forEach { source ->
            assertTrue(!Markdown.looksLikeMarkdown(source), "这份没有记号，该拒：$source")
        }
    }

    @Test
    fun `空输入给空输出而不是异常`() {
        assertEquals("", html(""))
        assertEquals("", plain(""))
    }

    @Test
    fun `CRLF 与 CR 都先统一成 LF`() {
        assertEquals(html("甲\n\n乙"), html("甲\r\n\r\n乙"))
        assertEquals(html("甲\n\n乙"), html("甲\r\r乙"))
    }

    @Test
    fun `输出的 HTML 以换行收尾`() {
        assertTrue(html("甲").endsWith("\n"))
    }

    @Test
    fun `列表在纯文本里保留记号与缩进`() {
        assertEquals("- 甲\n  - 乙\n", plain("- 甲\n  - 乙\n"))
    }
}
