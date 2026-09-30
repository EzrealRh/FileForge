package com.fileforge.core

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocTable
import com.fileforge.core.office.RtfRead
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RTF 读入：控制字、目的群、编码与表。
 *
 * 单元用例管"规范里那一条我们按不按"；真实夹具（`tools/make_rtf_fixtures.py` 手写、
 * 再由 pandoc 的独立 RTF 读者读过一遍）的四条产物落在 `build/rtfread/`，由
 * `tools/verify_rtf_read.py` 判。pandoc 只当"这份写法是不是真能读"的旁证：它在 RTF 上
 * 有明确读不出来的东西（列表结构、`\trhdr` 表头、936 代码页），那几处判据按量出来的结果写。
 *
 * 用例里的正文一律写 ASCII：夹具的字节是按字节走的，非 ASCII 得走 `\\uN` 或 `\\'xx`，
 * 否则"编码怎么解"这件事在测试这边就被偷偷做掉了。
 */
class RtfReadTest {

    private fun rtf(vararg body: String): ByteArray =
        ("""{\rtf1\ansi\ansicpg1252""" + "\n" + body.joinToString("\n") + "\n}")
            .toByteArray(Charsets.ISO_8859_1)

    private fun read(vararg body: String): Doc = RtfRead.read(rtf(*body)).doc

    private fun paras(doc: Doc): List<DocPara> = doc.parts.filterIsInstance<DocParagraph>().map { it.para }

    private fun tables(doc: Doc): List<DocTable> = doc.parts.filterIsInstance<DocTable>()

    @Test
    fun `样式表里写了层级的才当标题`() {
        val doc = read(
            """{\stylesheet{\s1\outlinelevel0\b Title One;}{\s2\outlinelevel1 Title Two;}{\s9\fs24 Body Text;}}""",
            """\pard\plain \s1 first heading\par""",
            """\pard\plain \s2 second heading\par""",
            """\pard\plain \s9 not a heading\par""",
        )
        assertEquals(listOf("Heading1", "Heading2", "Body"), paras(doc).map { it.style })
        assertEquals(
            listOf("first heading", "second heading", "not a heading"),
            paras(doc).map { it.text },
        )
    }

    @Test
    fun `样式表里没写层级时不拿样式号猜`() {
        // 只有 \s3 而没有 \outlinelevel：猜成标题就是编的（pandoc 同样不认）
        val doc = read("""{\stylesheet{\s3\fs24 Body;}}""", """\pard\plain \s3 ordinary line\par""")
        assertEquals(listOf("Body"), paras(doc).map { it.style })
    }

    @Test
    fun `样式没被 pard 清掉时后面的段落不跟着变标题`() {
        // pandoc 会把样式一路带下去（第一个标题之后整篇都成标题），我们按段落收：
        // 真实写者每段都写 \pard，所以这条只影响没写 \pard 的文件 —— 宁可少给标题
        val doc = read(
            """{\stylesheet{\s1\outlinelevel0 Big;}}""",
            """\s1 one heading\par""",
            """the line after it\par""",
        )
        assertEquals(listOf("Heading1", "Body"), paras(doc).map { it.style })
    }

    @Test
    fun `粗体斜体下划线删除线读成记号`() {
        val doc = read("""\pard\plain a\b b\i bi\b0 i\i0 p\strike st\strike0 q\ul un\ulnone tail\par""")
        val marks = paras(doc).single().runs.map { run ->
            run.text to listOf(run.bold, run.italic, run.underline, run.strike)
        }
        fun flags(word: String): List<Boolean> = marks.first { (text, _) -> text == word }.second
        assertEquals(listOf(false, false, false, false), flags("a"))
        assertEquals(listOf(true, false, false, false), flags("b"))
        assertEquals(listOf(true, true, false, false), flags("bi"))
        assertEquals(listOf(false, true, false, false), flags("i"))
        assertEquals(listOf(false, false, false, false), flags("p"))
        assertEquals(listOf(false, false, false, true), flags("st"))
        assertEquals(listOf(false, false, true, false), flags("un"))
        assertEquals(listOf(false, false, false, false), flags("tail"))
    }

    @Test
    fun `plain 把这一层的记号清回去`() {
        val doc = read("""\pard\plain \b\i marked\plain after this\par""")
        val runs = paras(doc).single().runs
        assertTrue(runs.first { it.text == "marked" }.bold)
        assertFalse(runs.first { it.text.startsWith("after") }.bold)
    }

    @Test
    fun `字体表里标了 fmodern 的才算等宽`() {
        val doc = read(
            """{\fonttbl{\f0\fswiss Helvetica;}{\f4\fmodern Courier New;}}""",
            """\pard\plain \f4 mono text\f0 normal text\plain\par""",
        )
        val runs = paras(doc).single().runs
        assertTrue(runs.first { it.text.startsWith("mono") }.mono)
        assertFalse(runs.first { it.text.startsWith("normal") }.mono)
    }

    @Test
    fun `listtext 里的圆点与编号各认各的`() {
        val doc = read(
            """\pard\plain {\listtext\bullet }dot one\par""",
            """{\listtext\bullet }dot two\par""",
            """\pard\plain {\listtext 1.}num one\par""",
            """{\listtext 2.}num two\par""",
            """\pard\plain {\listtext a)}letter one\par""",
            """\pard\plain ordinary line\par""",
        )
        val list = paras(doc)
        assertEquals(listOf(true, true, false, false, false, null), list.map { it.bullet })
        assertEquals(
            listOf("ListParagraph", "ListParagraph", "ListParagraph", "ListParagraph", "ListParagraph", "Body"),
            list.map { it.style },
        )
        // 记号由下游自己画：写进文字里就会和 Word 画的圆点重复
        assertTrue(list.all { !it.text.contains('•') })
        assertEquals(listOf("dot one", "dot two", "num one", "num two", "letter one", "ordinary line"), list.map { it.text })
    }

    @Test
    fun `编号定义那一格也说清是圆点还是编号`() {
        val doc = read(
            """\pard\plain {\*\pn\pnlvlblt\ilvl1}{\listtext\bullet }deep dot\par""",
            """\pard\plain {\*\pn\pndec}{\listtext 3.}outer num\par""",
        )
        assertEquals(listOf(true, false), paras(doc).map { it.bullet })
        assertEquals(listOf(1, 0), paras(doc).map { it.indent })
    }

    @Test
    fun `链接地址在域指令里、文字在结果里`() {
        val doc = read(
            """\pard\plain before{\field{\*\fldinst{HYPERLINK "https://example.com/a"}}{\fldrslt click here}}after\par""",
        )
        val runs = paras(doc).single().runs
        assertEquals("https://example.com/a", runs.first { it.text == "click here" }.link)
        assertEquals(null, runs.first { it.text == "before" }.link)
        assertEquals(null, runs.first { it.text == "after" }.link)
        assertEquals(listOf("https://example.com/a"), doc.links)
    }

    @Test
    fun `非链接的域只搬算出来的字`() {
        val doc = read("""\pard\plain page{\field{\*\fldinst PAGE }{\fldrslt 3}}done\par""")
        assertTrue(paras(doc).single().runs.none { it.link != null })
        assertEquals("page3done", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("域代码") })
    }

    @Test
    fun `链接域没有结果文字时说不出来`() {
        val doc = read("""\pard\plain a{\field{\*\fldinst{HYPERLINK "https://example.com/b"}}}b\par""")
        assertEquals("ab", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("无处可挂") })
    }

    @Test
    fun `表按声明的列数补齐`() {
        val doc = read(
            """\trowd\trhdr\cellx1000\cellx2000\cellx3000\intbl aa\cell\intbl bb\cell\row""",
            """\trowd\cellx1000\cellx2000\cellx3000\intbl cc\cell\intbl dd\cell\intbl ee\cell\row""",
            """\pard\plain after the table\par""",
        )
        val table = tables(doc).single()
        assertTrue(table.header)
        assertEquals(listOf(listOf("aa", "bb", ""), listOf("cc", "dd", "ee")), table.rows)
    }

    @Test
    fun `没写 trhdr 的表不当表头`() {
        val doc = read(
            """\trowd\cellx1000\cellx2000\intbl first row\cell\intbl no mark\cell\row""",
            """\trowd\cellx1000\cellx2000\intbl second\cell\intbl row\cell\row""",
            """\pard\plain done\par""",
        )
        assertFalse(tables(doc).single().header)
        assertEquals(2, tables(doc).single().rows.size)
    }

    @Test
    fun `一个格里两段用换行分开`() {
        val doc = read(
            """\trowd\cellx1000\intbl first part\par second part\cell\row""",
            """\pard\plain done\par""",
        )
        assertEquals("first part\nsecond part", tables(doc).single().rows.single().single())
    }

    @Test
    fun `套在表里的表并成同一格的文字`() {
        val doc = read(
            """\trowd\cellx1000\cellx2000\intbl out1\trowd\cellx500\intbl inA\nestcell\intbl inB\cell\nestrow\cell""",
            """\intbl out2\cell\row""",
            """\pard\plain done\par""",
        )
        val table = tables(doc).single()
        assertEquals(listOf("out1 inA inB", "out2"), table.rows.single())
        assertTrue(doc.notes.any { it.contains("套了") })
    }

    @Test
    fun `u 后面的兜底写法按声明丢掉`() {
        val doc = read("""\pard\plain \u20013\'ee and \u8212? end\par""")
        assertEquals("中 and — end", paras(doc).single().text)
    }

    @Test
    fun `uc0 时兜底写法就是内容`() {
        val doc = read("""\pard\plain \uc0\u66\'41\par""")
        assertEquals("BA", paras(doc).single().text)
    }

    @Test
    fun `兜底写法按声明的个数吃`() {
        // \uc2 说的是"这个字在后面两种写法里占两格"（GBK 那类多字节页就是这么写的）：
        // 只吃一格的话，剩下半个字节会把后面那个真字母带坏
        val bytes = """{\rtf1\ansi\ansicpg936\deff0\pard\plain \uc2\u20013\'c4\'e3Z\par}"""
            .toByteArray(Charsets.ISO_8859_1)
        val read = RtfRead.read(bytes)
        assertEquals("中Z", paras(read.doc).single().text)
        assertFalse(read.notes.any { it.contains("解不出字") })
    }

    @Test
    fun `一串反斜杠引号按代码页整段解`() {
        val bytes = """{\rtf1\ansi\ansicpg936\deff0\pard\plain \'c4\'e3\'ba\'c3\'ca\'c0\'bd\'e7\'a1\'a3\par}"""
            .toByteArray(Charsets.ISO_8859_1)
        assertEquals("你好世界。", paras(RtfRead.read(bytes).doc).single().text)
    }

    @Test
    fun `默认代码页里的弯引号与省略号`() {
        val doc = read("""\pard\plain \'93quoted\'94 with \'96 and \'85\par""")
        assertEquals("“quoted” with – and …", paras(doc).single().text)
    }

    @Test
    fun `这台机器没有的代码页不硬编字`() {
        val bytes = """{\rtf1\ansi\ansicpg9999\deff0\pard\plain odd\'c4\'e3page\par}"""
            .toByteArray(Charsets.ISO_8859_1)
        val read = RtfRead.read(bytes)
        assertTrue(read.notes.any { it.contains("代码页") })
        assertTrue(paras(read.doc).single().text.endsWith("page"))
    }

    @Test
    fun `图片目的群里的十六进制不进正文`() {
        val doc = read("""\pard\plain a{\*\shppict{\pict\wmetafile8 01020304ABCDEF}}b\par""")
        assertEquals("ab", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("图片") })
    }

    @Test
    fun `脚注正文不搬但要说出来`() {
        val doc = read("""\pard\plain a{\footnote the note body}b\par""")
        assertEquals("ab", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("脚注") })
    }

    @Test
    fun `认不出的目的群整块跳掉并说出来`() {
        val doc = read("""\pard\plain a{\*\madeupthing skip this whole block}b\par""")
        assertEquals("ab", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("目的群") })
    }

    @Test
    fun `定义类的块不报丢`() {
        val doc = read(
            """{\*\generator Test 1.0;}""",
            """{\info{\author bob}{\title some title}}""",
            """{\colortbl ;\red0\green0\blue255;}""",
            """\pard\plain only this line\par""",
        )
        assertEquals(listOf("only this line"), paras(doc).map { it.text })
        assertFalse(doc.notes.any { it.contains("目的群") })
    }

    @Test
    fun `bin 后面的原始字节不当成字`() {
        val doc = read("""\pard\plain a\bin4abcd""", """b\par""")
        assertEquals("ab", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("二进制") })
    }

    @Test
    fun `不在表里的 cell 按段落收`() {
        val doc = read("""\pard\plain one\cell two\par""")
        assertEquals(listOf("one", "two"), paras(doc).map { it.text })
        assertTrue(doc.notes.any { it.contains("不在表里") })
    }

    @Test
    fun `零宽字符不占字`() {
        val doc = read("""\pard\plain a\|b\:c\-\par""")
        assertEquals("abc", paras(doc).single().text)
    }

    @Test
    fun `不认的控制字字照搬并记一笔`() {
        val doc = read("""\pard\plain a\madeupword b\par""")
        assertEquals("ab", paras(doc).single().text)
        assertTrue(doc.notes.any { it.contains("控制字不认") })
    }

    @Test
    fun `line 是段内换行、tab 是制表`() {
        val doc = read("""\pard\plain a\line b\tab c\par""")
        assertEquals("a\nb\tc", paras(doc).single().text)
    }

    @Test
    fun `大括号与反斜杠是字面`() {
        val doc = read("""\pard\plain \{ x \} y \\ z\par""")
        assertEquals("{ x } y \\ z", paras(doc).single().text)
    }

    @Test
    fun `空段落丢掉`() {
        val doc = read("""\pard\plain\par""", """\pard\plain \par""", """\pard\plain some text\par""")
        assertEquals(listOf("some text"), paras(doc).map { it.text })
    }

    @Test
    fun `组没闭合也不崩`() {
        val bytes = """{\rtf1\ansi\pard\plain half written""".toByteArray(Charsets.ISO_8859_1)
        assertEquals("half written", paras(RtfRead.read(bytes).doc).single().text)
    }

    @Test
    fun `多出来的收括号不吞掉后面的字`() {
        val doc = read("""\pard\plain a}}b\par""")
        assertEquals("ab", paras(doc).single().text)
    }

    @Test
    fun `段落之间挨着写也各是一段`() {
        val doc = read("""\pard\plain one\par two\par three\par""")
        assertEquals(listOf("one", "two", "three"), paras(doc).map { it.text })
    }

    @Test
    fun `光一个大括号什么也没有`() {
        assertTrue(RtfRead.read(ByteArray(1) { '{'.code.toByte() }).doc.parts.isEmpty())
    }

    /**
     * 真实夹具的四条产物落盘，给外部判据（`tools/verify_rtf_read.py`）对照。
     *
     * 断言放在产文件**之后**：先断言会让坏实现把上一轮的旧产物留下，外部判据拿着旧文件说全绿。
     */
    /**
     * 落盘这一条要**独立于别的断言**：反例轮会把别的方法改坏，Gradle 于是整类红 ——
     * 判据拿的是这些产物，若让它跟着别的断言一起倒，"外部判据没意见"就会被误读成"改不坏"。
     * 所以这里只要求每份都读到块，具体对不对由 `tools/verify_rtf_read.py` 逐条判。
     */
    @Test
    fun `产物落盘供外部判据对照`() {
        val dir = File("build/rtfread").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
        listOf("basic.rtf", "table.rtf", "nested.rtf", "codepage.rtf").forEach { name ->
            val stem = name.substringBeforeLast('.')
            val bytes = resourceBytes("rtfread", name)
            val read = RtfRead.read(bytes)
            assertTrue(read.doc.parts.isNotEmpty(), "$stem 什么块都没读到，夹具或读法坏了")
            File(dir, "$stem.rtf").writeBytes(bytes)
            File(dir, "$stem.shapes.txt").writeText(shapes(read.doc.parts).joinToString("\n") + "\n", Charsets.UTF_8)
            File(dir, "$stem.text.txt").writeText(
                com.fileforge.core.doc.HtmlWrite.text(read.doc.parts), Charsets.UTF_8,
            )
            File(dir, "$stem.md").writeText(
                com.fileforge.core.doc.Html.toMarkdown(com.fileforge.core.doc.HtmlWrite.body(read.doc.parts)).text,
                Charsets.UTF_8,
            )
            File(dir, "$stem.html").writeText(
                com.fileforge.core.doc.HtmlWrite.page(stem, read.doc.parts, language = "zh").html,
                Charsets.UTF_8,
            )
            File(dir, "$stem.docx").writeBytes(
                com.fileforge.core.office.DocxWrite.document(read.doc, modifiedAt = 0L).bytes,
            )
            File(dir, "$stem.odt").writeBytes(
                com.fileforge.core.office.OdtWrite.document(read.doc, stem, modifiedAt = 0L).bytes,
            )
            File(dir, "$stem.notes.txt").writeText(read.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        assertEquals(4, dir.listFiles().orEmpty().count { it.name.endsWith(".rtf") })
        assertEquals(4, dir.listFiles().orEmpty().count { it.name.endsWith(".shapes.txt") })
    }

    /** 块的"类型（带层级与记号）"序列：只比结构，不比内容。 */
    private fun shapes(parts: List<DocPart>): List<String> = parts.map { part ->
        when (part) {
            is DocParagraph -> {
                val para = part.para
                val mark = para.runs.joinToString("") { run ->
                    (if (run.bold) "b" else "") + (if (run.italic) "i" else "") +
                        (if (run.mono) "c" else "") + (if (run.strike) "s" else "") +
                        (if (run.underline) "u" else "") + (if (!run.link.isNullOrBlank()) "L" else "")
                }
                val list = if (para.style == "ListParagraph")
                    " 层${para.indent} ${if (para.bullet == false) "编号" else "圆点"}" else ""
                "${para.style}$list[$mark]"
            }
            is DocTable -> "表 ${part.rows.size}行×${part.rows.maxOf { it.size }}列 表头=${part.header}"
            is com.fileforge.core.doc.DocRule -> "分隔线"
        }
    }

    private fun resourceBytes(area: String, name: String): ByteArray {
        val input = javaClass.classLoader.getResourceAsStream("$area/$name")
        requireNotNull(input) { "缺少夹具 $area/$name（先跑 tools/make_rtf_fixtures.py）" }
        return input.readBytes()
    }
}
