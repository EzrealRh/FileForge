package com.fileforge.core

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocTable
import com.fileforge.core.doc.Html
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.doc.TextDoc
import com.fileforge.core.data.Csv
import com.fileforge.core.json.JsonRender
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.office.DocxWrite
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 拿真来料走一遍 Doc → HTML：认路 → 渲染 → 落盘到 `build/htmlwrite/`，同时把同一棵 Doc 写成 docx
 * 交给判据比"跨格式是否同一套结构"。
 *
 * 期望值不写在这里：块序列与文字由 `tools/verify_html_write.py` 拿 **pandoc**（自己会读 HTML 与 docx）
 * 与 **Python 的 html.parser** 各独立判一遍。这里只管自家两条读路与产物别自相矛盾。
 */
class HtmlWriteFixtureTest {

    private fun resource(area: String, name: String): String {
        val input = javaClass.classLoader.getResourceAsStream("$area/$name")
        requireNotNull(input) { "缺少夹具 $area/$name" }
        val bytes = ByteArrayOutputStream().also { output -> input.copyTo(output) }.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    private val sources = listOf("note.md", "head.md", "page.html", "plain.txt", "english.md")

    @Test
    fun `自己写的页面自己读回来还是同一棵树`() {
        sources.forEach { name ->
            val source = resource("epubwrite", name)
            val doc = TextDoc.read(source).doc
            val page = HtmlWrite.page(OutputNaming.stem(name), doc.parts, language = "zh").html
            val back = Html.toDoc(page)
            assertEquals(shapes(doc.parts), shapes(back.parts), "$name 转一圈形状变了")
            assertEquals(textOf(doc.parts), textOf(back.parts), "$name 转一圈字变了")
        }
    }

    @Test
    fun `表转网页再转回表是同一张表`() {
        listOf("table.csv", "tricky.csv").forEach { name ->
            val text = resource("data", name)
            val doc = Csv.parse(text, Csv.detect(text))
            val widest = doc.widest
            val rows = doc.records.map { record -> record + List(widest - record.size) { "" } }
            val parts = listOf(DocTable(true, rows))
            val page = HtmlWrite.page(OutputNaming.stem(name), parts, language = "zh").html
            val back = Html.toDoc(page)
            val table = back.parts.filterIsInstance<DocTable>().single()
            // 格子里的 <br> 在"网页 → 文档树"这一路读成一个空格（网页表格的格子本来就是一行文字），
            // 保留格内换行的是「网页表格转 CSV」那条路 —— 这里按读的一侧的规矩比，不硬要两边一样
            val squeezed = rows.map { row -> row.map { cell -> cell.replace(10.toChar().toString(), " ") } }
            assertEquals(squeezed, table.rows, "$name 的表转一圈行列变了")
            assertTrue(table.header, "$name 的首行该还是表头")
        }
    }

    @Test
    fun `产物落到 build 供外部判据对照`() {
        val dir = File("build/htmlwrite").apply { mkdirs() }
        sources.forEach { name ->
            val source = resource("epubwrite", name)
            val doc = TextDoc.read(source).doc
            val stem = OutputNaming.stem(name)
            val page = HtmlWrite.page(stem, doc.parts, language = "zh")
            File(dir, "$stem.html").writeText(page.html, Charsets.UTF_8)
            // 同一棵 Doc 再写成 docx：判据拿 pandoc 分别读这两份，比的是同一次结构还原的结果
            File(dir, "$stem.docx").writeBytes(DocxWrite.document(doc, modifiedAt = 0L).bytes)
            File(dir, "$stem.blocks.txt").writeText(shapes(doc.parts).joinToString("\n") + "\n", Charsets.UTF_8)
        }
        listOf("table.csv", "tricky.csv").forEach { name ->
            val text = resource("data", name)
            val doc = Csv.parse(text, Csv.detect(text))
            val rows = doc.records.map { record -> record + List(doc.widest - record.size) { "" } }
            val parts = listOf(DocTable(true, rows))
            val stem = OutputNaming.stem(name)
            File(dir, "$stem.table.html").writeText(
                HtmlWrite.page(stem, parts, language = "zh").html, Charsets.UTF_8,
            )
            // 格子里本来就有换行，所以行与列不能用文本行分隔 —— 写成 JSON 的二维数组给判据
            File(dir, "$stem.table.rows.json").writeText(
                com.fileforge.core.json.JsonRender.render(
                    com.fileforge.core.json.JsonArray(
                        rows.map { row ->
                            com.fileforge.core.json.JsonArray(row.map { cell -> com.fileforge.core.json.JsonString(cell) })
                        },
                    ),
                ),
                Charsets.UTF_8,
            )
        }
        assertEquals(5 + 2, dir.listFiles().orEmpty().count { it.name.endsWith(".html") })
    }

    /** 块的"类型(带层级)"序列：只比结构，不比内容。 */
    private fun shapes(parts: List<com.fileforge.core.doc.DocPart>): List<String> = parts.map { part ->
        when (part) {
            is DocParagraph -> {
                val para = part.para
                val mark = para.runs.joinToString("") { run ->
                    (if (run.bold) "b" else "") + (if (run.italic) "i" else "") +
                        (if (run.mono) "c" else "") + (if (run.strike) "s" else "") +
                        (if (!run.link.isNullOrBlank()) "L" else "")
                }
                "${para.style}[$mark]"
            }
            is DocTable -> "表 ${part.rows.size}行×${part.rows.maxOf { it.size }}列 表头=${part.header}"
            is DocRule -> "分隔线"
        }
    }

    /** 摊成一句话：内容比的是字，不比标签。 */
    private fun textOf(parts: List<com.fileforge.core.doc.DocPart>): String = parts.joinToString("␞") { part ->
        when (part) {
            is DocParagraph -> part.para.runs.joinToString("") { it.text }
            is DocTable -> JsonRender.render(
                com.fileforge.core.json.JsonArray(
                    part.rows.map { row ->
                        com.fileforge.core.json.JsonArray(row.map { cell -> com.fileforge.core.json.JsonString(cell) })
                    },
                ), sortKeys = true,
            )
            else -> "─"
        }
    }
}
