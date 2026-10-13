package com.fileforge.core

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocTable
import com.fileforge.core.pdf.PrintPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 表格进 PDF 的排版判定：列宽是量出来的、宁可溢出不裁字、格子换行与正文同一套规矩。
 *
 * 尺子是合成的（一个中号字 = 字号宽，拉丁字母 = 半个），这样"宽度从哪来"只看我们自己的规矩，
 * 不掺字体文件；真字体上准不准由 `tools/pdfprobe` 在桌面 PDFBox 上量，产物再由
 * `tools/verify_pdf_table.py` 拿三个独立 PDF 读路核。
 */
class PrintPlanTest {

    private val pageWidth = 595f
    private val pageHeight = 842f
    private val margin = 56f
    private val size = 10f
    private val lineHeight = 14f            // size * 1.4
    private val padX = 3.5f                 // size * 0.35
    private val padY = 2f                   // size * 0.2

    /** 中文字占 [at] 宽，拉丁字母与空格占一半。 */
    private fun ruler(text: String, at: Float): Float =
        text.sumOf { ch -> if (ch.code > 0x2E7F) at.toDouble() else (at * 0.5f).toDouble() }.toFloat()

    private fun plan(parts: List<DocPart>, height: Float = pageHeight) =
        PrintPlanner.plan(parts, pageWidth, height, margin, size, 1.4f, ::ruler)

    private fun table(rows: List<List<String>>, header: Boolean = false) = DocTable(header, rows)

    private fun para(text: String, style: String = "Body") = DocParagraph(DocPara(listOf(DocRun(text)), style))

    /** 一张表的列边界：竖线就是 x1 == x2 的那些线，去重排序即列边界。 */
    private fun edgesOf(parts: List<DocPart>, page: Int = 0): List<Float> =
        plan(parts).pages[page].rules.filter { it.x1 == it.x2 }.map { it.x1 }.distinct().sorted()

    private fun widthsOf(parts: List<DocPart>, page: Int = 0): List<Float> =
        edgesOf(parts, page).zipWithNext().map { (a, b) -> b - a }

    private fun near(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 0.6f

    @Test
    fun `装得下时列宽就是量出来的自然宽`() {
        val rows = listOf(listOf("名称", "数量"), listOf("苹果派的做法", "12"))
        val widths = widthsOf(listOf(table(rows)))
        assertTrue(widths.size == 2 && near(widths[0], 60f + padX * 2) && near(widths[1], 20f + padX * 2),
            "列宽该是最长那格量出来的宽度：$widths")
    }

    @Test
    fun `装不下时按比例压并说清第几列从多少压到多少`() {
        val long = "一".repeat(60)
        val rows = listOf(listOf(long, "短"), listOf(long, "亦短"))
        val widths = widthsOf(listOf(table(rows)))
        val made = plan(listOf(table(rows)))
        val content = pageWidth - margin * 2
        assertTrue(near(widths.sum(), content), "压完要正好放进纸宽：${widths.sum()} vs $content")
        assertTrue(made.notes.any { it.contains("第 1 列从 607pt 压到") }, "说明要点名是哪一列压了多少：${made.notes}")
        assertTrue(near(widths[1], ruler("亦短", size) + padX * 2), "窄列该保住自己的自然宽：${widths[1]}")
        assertTrue(widths[0] > widths[1], "长列该分到更多空间：$widths")
    }

    @Test
    fun `列太多连保底也放不下时不丢列`() {
        val rows = List(2) { (1..30).map { "${it}字" } }
        val made = plan(listOf(table(rows)))
        val edges = edgesOf(listOf(table(rows)))
        assertEquals(31, edges.size, "30 列要有 31 条竖线，少了就是丢列了")
        assertTrue(edges.last() > pageWidth - margin, "放不下就该超出纸边（而不是压成零宽）：${edges.last()}")
        assertTrue(made.notes.any { it.contains("超出纸边") && it.contains("不裁字") }, "放不下要直说：${made.notes}")
        assertTrue(made.notes.any { it.contains("不丢列") }, "${made.notes}")
    }

    @Test
    fun `空格子也占位不塌`() {
        val rows = listOf(listOf("甲", "很长的内容", "丙"), listOf("一", "", "二"))
        val made = plan(listOf(table(rows)))
        assertEquals(listOf("甲", "很长的内容", "丙", "一", "二"), made.pages.first().texts.map { it.text })
        val edges = edgesOf(listOf(table(rows)))
        assertEquals(4, edges.size, "中间那一格空着也要有自己的边界：$edges")
        val widths = widthsOf(listOf(table(rows)))
        assertTrue(near(widths[1], ruler("很长的内容", size) + padX * 2),
            "有一行空着不许把整列压塌：${widths[1]}")
    }

    @Test
    fun `长格折行且行高按最高的那格`() {
        val rows = listOf(listOf("小", "这".repeat(60)))
        val made = plan(listOf(table(rows)))
        val lines = made.pages.first().texts.count { it.text.startsWith("这") }
        assertTrue(lines >= 2, "长格要折行，实测 $lines 行")
        val bottomRule = made.pages.first().rules.filter { it.y1 == it.y2 }.maxOf { it.y1 }
        assertTrue(near(bottomRule - margin, lines * lineHeight + padY * 2),
            "行高 = 最高那格的行数：$bottomRule vs $lines")
        assertTrue(made.notes.any { it.contains("折成了多行") }, "${made.notes}")
    }

    @Test
    fun `格子里的硬换行分成几段写`() {
        val made = plan(listOf(table(listOf(listOf("第一段\n第二段")))))
        assertEquals(listOf("第一段", "第二段"), made.pages.first().texts.map { it.text })
        assertEquals(2, made.pages.first().texts.map { it.y }.distinct().size, "两段要在不同的基线上")
    }

    @Test
    fun `行不拆开跨页且跨页时重复表头`() {
        val rows = ArrayList<List<String>>()
        rows += listOf("表头一", "表头二")
        repeat(80) { rows += listOf("第${it + 1}行", "内容") }
        val made = plan(listOf(table(rows, header = true)))
        assertTrue(made.pageCount > 1, "这么多行总要翻页，实测 ${made.pageCount} 页")
        assertTrue(made.pages.all { page -> page.texts.any { it.text == "表头一" } }, "每一页都得有表头")
        assertTrue(made.notes.any { it.contains("重复了 ${made.pageCount - 1} 次") }, "重复次数要说准：${made.notes}")
        made.pages.forEach { page ->
            val ys = page.texts.map { it.y }.groupingBy { it }.eachCount()
            assertTrue(ys.values.all { it <= 2 }, "同一基线上最多两个格，多了就是格子串到别列了：$ys")
        }
        val cells = made.pages.flatMap { page -> page.texts.map { it.text } }
        assertEquals(80, cells.count { it.startsWith("第") && it.endsWith("行") }, "一行都不许多也不能少")
        assertEquals(80, cells.count { it == "内容" })
    }

    @Test
    fun `一行比一页还高时让它溢出也不裁字`() {
        val rows = listOf(listOf("起"), listOf("巨".repeat(400)), listOf("止"))
        val made = plan(listOf(table(rows)), height = 200f)
        val drawn = made.pages.flatMap { page -> page.texts.map { it.text } }.joinToString("")
        assertEquals(400, drawn.count { it == '巨' }, "长格的字一个都不能丢")
        assertTrue(made.notes.any { it.contains("第 2 行") && it.contains("比一页还高") }, "${made.notes}")
    }

    @Test
    fun `表头下面那条线比别的重`() {
        val made = plan(listOf(table(listOf(listOf("甲", "乙"), listOf("一", "二")), header = true)))
        val horizontals = made.pages.first().rules.filter { it.y1 == it.y2 }
        assertEquals(1, horizontals.count { it.width > 1f }, "只有表头下面那条是重的：${horizontals.map { it.width }}")
        val heavy = horizontals.first { it.width > 1f }
        assertTrue(heavy.y1 > horizontals.minOf { it.y1 }, "重线不该是顶线")
    }

    @Test
    fun `标题字号大于正文且表格文字都画上`() {
        val made = plan(listOf(para("一节名字", "Heading1"), para("正文一句话"), table(listOf(listOf("格子")))))
        val texts = made.pages.first().texts
        assertTrue(texts.maxOf { it.size } > texts.minOf { it.size }, "标题该比正文大")
        assertTrue(texts.any { it.text == "格子" }, "表里的字要画上：${texts.map { it.text }}")
        assertTrue(texts.any { it.text == "正文一句话" })
    }

    @Test
    fun `分隔线横贯栏宽`() {
        val line = plan(listOf(DocRule())).pages.first().rules.single()
        assertTrue(near(line.x1, margin) && near(line.x2, pageWidth - margin), "该横贯整个栏宽：${line.x1}→${line.x2}")
    }

    @Test
    fun `纸比栏还窄时字还是要画上且有话要说`() {
        val made = PrintPlanner.plan(
            listOf(table(listOf(listOf("一二三四五".repeat(20)), listOf("字")))),
            pageWidth = margin * 2 + 30f, pageHeight = pageHeight, margin = margin, size = size,
            lineHeightFactor = 1.4f, widthOf = ::ruler,
        )
        assertTrue(made.pages.flatMap { it.texts }.isNotEmpty(), "纸比表还窄时字还是要画上")
        assertTrue(made.notes.isNotEmpty(), "这种时候必须有话要说")
    }

    @Test
    fun `带硬换行的格子按最长那一句量自然宽`() {
        // 整串连着量会把两句当一句（宽出一截，白占纸），而且有的字体根本量不动换行符
        val cell = "第一句很长很长很长很长\n短"
        val rows = listOf(listOf("甲", cell))
        val widths = widthsOf(listOf(table(rows)))
        assertTrue(near(widths[1], ruler("第一句很长很长很长很长", size) + padX * 2),
            "自然宽该是最长那一句：${widths[1]}")
    }

    @Test
    fun `连着排两次不沾上次的状态`() {
        val rows = listOf(listOf("表头"), listOf("很长的内容".repeat(6)))
        val first = plan(listOf(table(rows, header = true)))
        val second = plan(listOf(table(rows, header = true)))
        assertEquals(first.pageCount, second.pageCount, "页数不该受上一次影响")
        assertEquals(first.notes, second.notes, "说明也不该受上一次影响：${first.notes} vs ${second.notes}")
    }

    @Test
    fun `每页的横线数等于这一页的行数加顶线`() {
        val rows = listOf(listOf("甲", "乙"), listOf("一", "二"), listOf("三", "四"))
        val made = plan(listOf(table(rows, header = true)))
        val page = made.pages.first()
        val horizontals = page.rules.filter { it.y1 == it.y2 }
        assertEquals(rows.size + 1, horizontals.size, "三条行底线加一条顶线：${horizontals.size}")
    }
}
