package com.fileforge.core

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.Html
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.office.RtfRead
import com.fileforge.core.office.RtfWrite
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 写 .rtf（`core/office/RtfWrite.kt`）。
 *
 * 主要判法是**自己写自己读回来**（[RtfRead]，它已经由 pandoc 逐块对过）加上
 * **pandoc 的 RTF 读者读回来**与同一棵树写出去的 Markdown 逐块对——两边独立，
 * "只有自家读得懂"的写法（记号写进文字、层级凭样式号猜、地址丢了）在这两条下都过不去。
 *
 * pandoc 在 RTF 上有几处读不出来的东西（列表结构、`\trhdr` 表头、代理对），那几处
 * 由单元测试按写出的字节钉住，外部判据按量出来的差距写。
 */
class RtfWriteTest {

    private fun bytes(doc: Doc, title: String = "") = RtfWrite.document(doc, title).bytes

    private fun readBack(file: ByteArray): Doc = RtfRead.read(file).doc

    private fun paras(doc: Doc): List<DocPara> = doc.parts.filterIsInstance<DocParagraph>().map { it.para }

    private fun text(doc: Doc): String =
        doc.parts.filterIsInstance<DocParagraph>().joinToString("\n") { it.para.text }

    private fun paragraph(text: String, style: String = "Body", vararg runs: DocRun) =
        DocParagraph(DocPara(if (runs.isEmpty()) listOf(DocRun(text)) else runs.toList(), style))

    @Test
    fun `写出去再读回来是同一棵树（差该差的）`() {
        val source = Doc(
            listOf(
                paragraph("简报", "Heading1"),
                paragraph("正文里有粗体和普通", "Body", DocRun("粗体", bold = true), DocRun("和普通")),
                paragraph(
                    "带链接的一句话", "Body",
                    DocRun("带"), DocRun("个链接", link = "https://example.com/x?a=1&b=2"), DocRun("的一句话"),
                ),
                paragraph("引用的一句话", "Quote"),
                DocParagraph(DocPara(listOf(DocRun("代码第一行\n代码第二行")), "SourceCode")),
                DocParagraph(DocPara(listOf(DocRun("圆点一")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("圆点二")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("嵌在里面")), "ListParagraph", indent = 1, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("编号一")), "ListParagraph", indent = 0, bullet = false)),
                DocRule(),
                DocTable(true, listOf(listOf("名称", "数量"), listOf("甲", "1"), listOf("乙", ""))),
                paragraph("末了", "Heading2"),
            ),
            emptyList(),
        )
        // 三处是写读两边都认账的差：引用块按普通段落写、代码段的段落样式换成了等宽字体记号、
        // 分隔线是带下边线的空段（读的一侧不收没有字的段）——其余一处不差
        val expected = listOf(
            "Heading1[] 简报",
            "Body[b] 粗体和普通",
            "Body[L] 带个链接的一句话",
            "Body[] 引用的一句话",
            "Body[c] 代码第一行\n代码第二行",
            "ListParagraph 层0 圆点[] 圆点一",
            "ListParagraph 层0 圆点[] 圆点二",
            "ListParagraph 层1 圆点[] 嵌在里面",
            "ListParagraph 层0 编号[] 编号一",
            "表 3行×2列 表头=true 名称,数量/甲,1/乙,",
            "Heading2[] 末了",
        )
        assertEquals(expected, shape(readBack(bytes(source)).parts))
    }

    @Test
    fun `字面花括号反斜杠与波浪线读回来不坏`() {
        val source = "a { b } c \\ d ~ e"
        assertEquals(source, paras(readBack(bytes(Doc(listOf(paragraph(source)), emptyList())))).single().text)
    }

    @Test
    fun `中文与弯引号按 uN 写且字节全 ASCII`() {
        val source = "你好，“引号”—破折号…省略号"
        val out = bytes(Doc(listOf(paragraph(source)), emptyList()))
        assertTrue(out.all { it in 0..127 }, "写出的字节该全 ASCII")
        val written = String(out, Charsets.US_ASCII)
        assertTrue("\\u20320 ?" in written, "“你”该按 \\u 写：$written")
        assertTrue("\\u8220 ?" in written, "“该按 \\u 写：$written")
        assertTrue("\\u-244 ?" in written, "全角逗号（超 32767 的按补码写成负数）该按 \\u 写：$written")
        assertEquals(source, paras(readBack(out)).single().text)
    }

    @Test
    fun `控制字符丢掉并数一笔`() {
        val out = bytes(Doc(listOf(paragraph("甲\u0001乙")), emptyList()))
        assertEquals("甲乙", paras(readBack(out)).single().text)
        assertTrue(RtfWrite.document(Doc(listOf(paragraph("甲\u0001乙")), emptyList())).notes.any { it.contains("控制字符") })
    }

    @Test
    fun `连续空格与制表读回来不丢`() {
        val source = "甲  乙\t丙"
        assertEquals(source, paras(readBack(bytes(Doc(listOf(paragraph(source)), emptyList())))).single().text)
    }

    @Test
    fun `段内换行读回来还在`() {
        val doc = Doc(listOf(DocParagraph(DocPara(listOf(DocRun("第一行\n第二行")), "Body"))), emptyList())
        assertEquals("第一行\n第二行", paras(readBack(bytes(doc))).single().text)
    }

    @Test
    fun `链接地址照字面（带 & 与 =）`() {
        val url = "https://example.com/a?x=1&y=2"
        val doc = Doc(listOf(paragraph("点这里", "Body", DocRun("点这里", link = url))), emptyList())
        val written = String(bytes(doc), Charsets.US_ASCII)
        assertTrue("HYPERLINK \"$url\"" in written, written)
        assertEquals(url, paras(readBack(bytes(doc))).single().runs.single().link)
    }

    @Test
    fun `列表的层级与种类各就各位`() {
        val doc = Doc(
            listOf(
                DocParagraph(DocPara(listOf(DocRun("外层圆点")), "ListParagraph", indent = 0, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("里层圆点")), "ListParagraph", indent = 2, bullet = true)),
                DocParagraph(DocPara(listOf(DocRun("外层编号")), "ListParagraph", indent = 0, bullet = false)),
                DocParagraph(DocPara(listOf(DocRun("普通一段")))),
            ),
            emptyList(),
        )
        val written = String(bytes(doc), Charsets.US_ASCII)
        assertTrue("\\pnlvlblt" in written && "\\pndec" in written, written)
        assertTrue("\\ilvl2" in written, "层号要写在 ilvl 上：$written")
        // 记号由软件画：圆点与序号不进正文（“•”与“1.”只许出现在 {\pntxtb} 那份样板里）
        assertTrue(paras(readBack(bytes(doc))).all { !it.text.contains('•') && !it.text.startsWith('1') })
        val back = paras(readBack(bytes(doc)))
        assertEquals(listOf(true, true, false, null), back.map { it.bullet })
        assertEquals(listOf(0, 2, 0, 0), back.map { it.indent })
        assertEquals(listOf("ListParagraph", "ListParagraph", "ListParagraph", "Body"), back.map { it.style })
    }

    @Test
    fun `表头行带 trhdr 而普通表不带`() {
        val header = bytes(Doc(listOf(DocTable(true, listOf(listOf("甲", "乙"), listOf("1", "2")))), emptyList()))
        val plain = bytes(Doc(listOf(DocTable(false, listOf(listOf("甲", "乙"), listOf("1", "2")))), emptyList()))
        assertEquals(1, Regex("\\\\trhdr").findAll(String(header, Charsets.US_ASCII)).count())
        assertFalse("\\trhdr" in String(plain, Charsets.US_ASCII))
        assertTrue(readBack(header).parts.filterIsInstance<DocTable>().single().header)
        assertFalse(readBack(plain).parts.filterIsInstance<DocTable>().single().header)
    }

    @Test
    fun `格子不足按空补齐`() {
        val doc = Doc(listOf(DocTable(false, listOf(listOf("甲", "乙", "丙"), listOf("1")))), emptyList())
        assertEquals(
            listOf(listOf("甲", "乙", "丙"), listOf("1", "", "")),
            readBack(bytes(doc)).parts.filterIsInstance<DocTable>().single().rows,
        )
    }

    @Test
    fun `同一份内容两次写出的字节一样`() {
        val doc = Doc(listOf(paragraph("甲"), paragraph("乙", "Heading1")), emptyList())
        assertEquals(bytes(doc).toList(), bytes(doc).toList(), "控制字的先后与转义的写法都该稳定")
    }

    @Test
    fun `书名写进 info 的 title`() {
        val written = String(bytes(Doc(listOf(paragraph("正文")), emptyList()), title = "简报"), Charsets.US_ASCII)
        assertTrue("{\\info{\\title " in written && "\\u31616 ?" in written, written)
        val untitled = String(bytes(Doc(listOf(paragraph("正文")), emptyList())), Charsets.US_ASCII)
        assertFalse("{\\info" in untitled, "没给书名就不该写 info 组：$untitled")
    }

    @Test
    fun `引用与代码段的去处有交代`() {
        val doc = Doc(
            listOf(paragraph("引用的一句话", "Quote"), DocParagraph(DocPara(listOf(DocRun("代码")), "SourceCode"))),
            emptyList(),
        )
        val notes = RtfWrite.document(doc).notes
        assertTrue(notes.any { it.contains("引用") }, notes.joinToString("\n"))
        assertTrue(notes.any { it.contains("等宽") }, notes.joinToString("\n"))
    }

    @Test
    fun `基本平面以外的字符按代理对写`() {
        // U+1D11E（ musical符号）：RTF 的 \u 只有 16 位，拆成高低两个代理码位，按补码都是负数
        val note = String(Character.toChars(0x1D11E))
        fun escape16(code: Int): String =
            "\\u${if (code > 0x7FFF) code - 0x10000 else code} ?"
        val high = 0xD800 + ((0x1D11E - 0x10000) shr 10)
        val low = 0xDC00 + ((0x1D11E - 0x10000) and 0x3FF)
        val out = bytes(Doc(listOf(paragraph("音符$note here")), emptyList()))
        val written = String(out, Charsets.US_ASCII)
        assertTrue(escape16(high) in written && escape16(low) in written, written)
        assertTrue(RtfWrite.document(Doc(listOf(paragraph("音符$note")), emptyList())).notes.any { it.contains("代理对") })
        // 自家的读法认不出代理对：这两个码位按坏编号丢掉 —— 交代过，不悄悄地丢
        assertFalse(note in text(readBack(out)))
    }

    @Test
    fun `没有块的文档也给得出文件`() {
        val out = RtfWrite.document(Doc(emptyList(), emptyList()))
        assertTrue(out.bytes.isNotEmpty())
        assertTrue(String(out.bytes, Charsets.US_ASCII).startsWith("{\\rtf1"))
    }

    /**
     * 同一棵树的 .rtf 与 .md 都落盘：判据拿 pandoc 分别读这两份，比的是"同一份稿子经过两条路
     * 出去，别人读回来的结构是不是同一个东西"。
     *
     * 断言放在产文件**之后**、且只断"每份都读到块"：反例轮会把别的方法改坏，Gradle 整类红，
     * 判据若跟着倒，"外部判据没意见"就会被误读成"改不坏"。
     */
    @Test
    fun `产物落盘供外部判据对照`() {
        val dir = File("build/rtfwrite").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
        val fixtures = mapOf(
            "book" to Doc(
                listOf(
                    paragraph("简报", "Heading1"),
                    paragraph("一段话里有粗体和普通", "Body", DocRun("粗体", bold = true), DocRun("和普通")),
                    paragraph(
                        "带[个链接](https://example.com/x?a=1&b=2)的一句话", "Body",
                        DocRun("带"), DocRun("个链接", link = "https://example.com/x?a=1&b=2"), DocRun("的一句话"),
                    ),
                    paragraph("引用的一句话", "Quote"),
                    paragraph("花括号 { 与 反斜杠 \\ 与 波浪 ~ 都照字面"),
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
            ),
            "grid" to Doc(
                listOf(
                    DocTable(false, listOf(listOf("甲", "乙", "丙"), listOf("1", "2", "3"), listOf("11"))),
                    paragraph("两张表中间的话"),
                    DocTable(true, listOf(listOf("甲", "乙"), listOf("1", ""))),
                ),
                emptyList(),
            ),
            "east" to Doc(
                listOf(
                    paragraph("中文标题", "Heading1"),
                    paragraph("你好，世界——“引号”——破折号…省略号"),
                    paragraph("斜体和粗体", "Body", DocRun("斜体", italic = true), DocRun("和", italic = true, bold = true), DocRun("粗体", bold = true)),
                ),
                emptyList(),
            ),
        )
        fixtures.forEach { (stem, source) ->
            val out = RtfWrite.document(source, title = if (stem == "book") "简报" else "")
            require(out.bytes.isNotEmpty()) { "$stem 写出来的文件是空的，写的一侧坏了" }
            val back = readBack(out.bytes)
            assertTrue(back.parts.isNotEmpty(), "$stem 写出去读不回任何块，夹具或写法坏了")
            File(dir, "$stem.rtf").writeBytes(out.bytes)
            File(dir, "$stem.md").writeText(Html.toMarkdown(HtmlWrite.body(source.parts)).text, Charsets.UTF_8)
            File(dir, "$stem.shape.txt").writeText(shape(source.parts).joinToString("\n") + "\n", Charsets.UTF_8)
            File(dir, "$stem.back.txt").writeText(shape(back.parts).joinToString("\n") + "\n", Charsets.UTF_8)
            File(dir, "$stem.notes.txt").writeText(out.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        assertEquals(3, dir.listFiles().orEmpty().count { it.name.endsWith(".rtf") })
        assertEquals(3, dir.listFiles().orEmpty().count { it.name.endsWith(".md") })
    }

    /** 块的"类型（带层级与记号）+ 文字"序列：结构与内容都比。 */
    private fun shape(parts: List<DocPart>): List<String> = parts.map { part ->
        when (part) {
            is DocParagraph -> {
                val para = part.para
                val mark = para.runs.joinToString("") { run ->
                    (if (run.bold) "b" else "") + (if (run.italic) "i" else "") + (if (run.mono) "c" else "") +
                        (if (run.strike) "s" else "") + (if (run.underline) "u" else "") +
                        (if (!run.link.isNullOrBlank()) "L" else "")
                }
                val list = if (para.style == "ListParagraph")
                    " 层${para.indent} ${if (para.bullet == false) "编号" else "圆点"}" else ""
                "${para.style}$list[${mark}] ${para.text}"
            }
            is DocRule -> "分隔线"
            is DocTable -> "表 ${part.rows.size}行×${part.rows.maxOf { it.size }}列 表头=${part.header} " +
                part.rows.joinToString("/") { it.joinToString(",") }
        }
    }
}
