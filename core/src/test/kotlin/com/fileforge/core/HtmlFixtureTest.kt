package com.fileforge.core

import com.fileforge.core.doc.Html
import com.fileforge.core.doc.HtmlRaw
import com.fileforge.core.doc.HtmlTag
import com.fileforge.core.doc.HtmlText
import com.fileforge.core.doc.HtmlTokens
import com.fileforge.core.doc.HtmlTree
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 把 HTML 夹具的产物与**词法轨迹**落到 build 下，交给 `tools/verify_html.py` 与两个第三方实现对：
 * Python 标准库的 `html.parser`（另一套容错词法）与 pandoc（另一套 HTML 读取器 + 渲染器）。
 *
 * 轨迹是这里额外给出来的东西：标签切分这一步一旦与别人不同，后面的结构全都会歪，
 * 而"结构歪了"在成品文字上常常看不出来（少一个 `</p>` 就是把两段并成一段）。
 */
class HtmlFixtureTest {

    private val whitespace = Regex("\\s+")

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("html/$name").use { input ->
            requireNotNull(input) { "缺少夹具 html/$name" }
            String(input.readBytes(), Charsets.UTF_8)
        }

    @Test
    fun `两份夹具都落盘，且字没丢`() {
        val dir = File("build/html").apply { mkdirs() }
        listOf("clean.html", "dirty.html").forEach { name ->
            val source = fixture(name)
            val plain = Html.toPlainText(source)
            val markdown = Html.toMarkdown(source)
            File(dir, "$name.txt").writeText(plain.text, Charsets.UTF_8)
            File(dir, "$name.md").writeText(markdown.text, Charsets.UTF_8)
            File(dir, "$name.notes").writeText((plain.notes + markdown.notes).joinToString("\n"), Charsets.UTF_8)
            File(dir, "$name.tokens").writeText(trace(source), Charsets.UTF_8)

            // 注释整段本来就该丢，先摘掉再数字；<head> 与 <script> 里那几个字列出来当豁免
            val visible = source.replace(Regex("<!--[\\s\\S]*?-->"), "")
            val wanted = visible.filter { it in '一'..'鿿' }.toCharArray().toHashSet()
            val dropped = setOf('不', '该', '出', '现', '也', '是', '结', '构', '页', '面', '标', '题')
            val kept = plain.text.filter { it in '一'..'鿿' }.toHashSet()
            val missing = wanted - kept - dropped
            assertTrue(missing.isEmpty(), "$name 纯文本丢了这些字：$missing")
        }
    }

    /** 归一化后的 token 流：一行一个事件，与 html.parser 给的东西同一个形状。 */
    private fun trace(source: String): String {
        val tally = com.fileforge.core.doc.EntityTally()
        val out = ArrayList<String>()
        val run = StringBuilder()
        fun flushText() {
            // 与 Python 那边同一步归一：首尾空白去掉，中间空白并成一个空格（换行会把一行轨迹劈成两行）
            val text = run.toString().trim().replace(whitespace, " ")
            if (text.isNotEmpty()) out += "T $text"
            run.setLength(0)
        }
        HtmlTokens.tokenize(source, tally).forEach { token ->
            when (token) {
                is HtmlText -> run.append(token.text)
                is HtmlTag -> {
                    flushText()
                    out += if (token.closing) "E ${token.name}" else "S ${token.name}"
                }
                is HtmlRaw -> {
                    flushText()
                    val element = token.name.removePrefix("#raw:")
                    // Python 那边是 S/T/E 三个事件，这里补上收尾，形状才对得上
                    out += "S $element"
                    val body = token.text.trim().replace(whitespace, " ")
                    if (body.isNotEmpty()) out += "T $body"
                    out += "E $element"
                }
                else -> flushText()                                 // 注释与 DOCTYPE：两边都不算事件
            }
        }
        flushText()
        // 相邻的文本块在 Python 那边是同一个 data 事件，这里也并一次
        val merged = ArrayList<String>()
        out.forEach { line ->
            if (line.startsWith("T ") && merged.lastOrNull()?.startsWith("T ") == true) {
                merged[merged.size - 1] = merged.last() + " " + line.removePrefix("T ")
            } else {
                merged += line
            }
        }
        return merged.joinToString("\n") + "\n"
    }
}
