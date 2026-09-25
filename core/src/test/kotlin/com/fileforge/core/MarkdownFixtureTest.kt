package com.fileforge.core

import com.fileforge.core.doc.Markdown
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 把 markdown 夹具的两种产物落到 build 下，交给 `tools/verify_markdown.py` 与 pandoc 对。
 *
 * 判据不在这里：pandoc 是另一套完整实现（Haskell 写的），逐块比对由那个脚本做。
 * 这条测试只保证"产物存在且非空"，以及渲染不抛。
 */
class MarkdownFixtureTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("markdown/$name").use { input ->
            requireNotNull(input) { "缺少夹具 markdown/$name" }
            String(input.readBytes(), Charsets.UTF_8)
        }

    @Test
    fun `两种产物的三份夹具都落盘`() {
        val dir = File("build/markdown").apply { mkdirs() }
        listOf("prose.md", "common.md").forEach { name ->
            val source = fixture(name)
            val html = Markdown.toHtml(source)
            val plain = Markdown.toPlainText(source)
            File(dir, "$name.html").writeText(html.text, Charsets.UTF_8)
            File(dir, "$name.txt").writeText(plain.text, Charsets.UTF_8)
            File(dir, "$name.notes").writeText((html.notes + plain.notes).joinToString("\n"), Charsets.UTF_8)

            // 判据用"汉字多重集"而不是分词：中文没有词边界，`带**粗体**` 与输出里的 `带粗体`
            // 会被分词切成不同的词，字却一个没少 —— 数汉字才是这条判据真正的意思
            val wanted = cjk(source)
            listOf(html.text, plain.text).forEach { rendered ->
                val got = cjk(rendered)
                val lost = wanted.filter { (ch, count) -> (got[ch] ?: 0) < count }
                assertTrue(lost.isEmpty(), "$name 丢了这些字：$lost")
            }
        }
        assertTrue(File(dir, "prose.md.notes").readText().isNotEmpty(), "prose 夹具故意带了要说明的写法，notes 不该空")
    }

    private fun cjk(text: String): Map<Char, Int> =
        text.filter { it in '\u4e00'..'\u9fff' }.groupingBy { it }.eachCount()
}
