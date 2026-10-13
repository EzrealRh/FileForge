package com.fileforge.core

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.PlainDoc
import com.fileforge.core.pdf.PrintPlan
import com.fileforge.core.pdf.PrintPlanner
import com.fileforge.core.pdf.TextLayoutPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ceil

/**
 * 纯文本印成 PDF：`PlainDoc` 分段，`PrintPlanner` 排页与分页。
 *
 * 宽度用一把"每个字符按码点给固定宽度"的尺子，好让断行结果可预测；
 * 真实字体的宽度模型另由 tools/pdfprobe 在桌面 PDFBox 上量。
 * 软换行（一段里的 `\n`）排成同一页里的两行，空行才是分段：分段之间比软换行多让出半个字。
 */
class PlainPrintTest {

    /** 西文 6 单位、其他（CJK）12 单位，按字号线性缩放。 */
    private fun widthOf(text: String, size: Float): Float {
        var units = 0
        text.forEach { ch -> units += if (TextLayoutPlanner.isCjk(ch)) 2 else 1 }
        return units * 6f * size / 12f
    }

    private val PAGE_W = 595f
    private val PAGE_H = 842f
    private val MARGIN = 56f
    private val CONTENT = PAGE_W - MARGIN * 2

    private fun print(
        text: String,
        size: Float = 12f,
        lineHeight: Float = 1.4f,
        indent: Float = 0f,
        pageWidth: Float = PAGE_W,
        pageHeight: Float = PAGE_H,
        margin: Float = MARGIN,
    ): PrintPlan = PrintPlanner.plan(PlainDoc.toDoc(text).parts, pageWidth, pageHeight, margin, size,
        lineHeight, indent, ::widthOf)

    private fun linesOf(result: PrintPlan) = result.pages.flatMap { page -> page.texts.map { it.text } }

    private fun baselines(result: PrintPlan) = result.pages.flatMap { page -> page.texts.map { it.y } }

    // ---- 断行 -----------------------------------------------------------------

    @Test
    fun `CJK 能在字之间断而不是整段溢出`() {
        val long = "文件工坊".repeat(60)
        val lines = linesOf(print(long))
        assertTrue(lines.size > 1, "中文长段必须切开，只得到 ${lines.size} 行")
        assertTrue(lines.all { it.isNotEmpty() }, "不该出现空行：$lines")
        lines.forEach { assertTrue(widthOf(it, 12f) <= CONTENT, "这行超宽：[${it.take(10)}] 有 ${widthOf(it, 12f)}") }
        assertEquals(long, lines.joinToString(""), "断完拼回去必须一字不差")
    }

    @Test
    fun `西文只在词边界断`() {
        val text = List(60) { "convert" }.joinToString(" ")
        val lines = linesOf(print(text))
        assertTrue(lines.size > 1)
        // 行与行之间必须正好隔着一个原处的空格：断点落在空格上，不落在词里
        lines.forEach { assertTrue(it == it.trim(), "行首行尾不许挂着半空格的怪样子：[$it]") }
        assertEquals(text.replace(" ", ""), lines.joinToString("").replace(" ", ""), "单词不该在断行中变形")
        assertEquals(text.split(" ").size, lines.sumOf { if (it.isBlank()) 0 else it.split(" ").size }, "单词数不该在断行中变化")
    }

    @Test
    fun `收尾标点不许顶在行首`() {
        val text = "啊".repeat(40) + "，" + "哈".repeat(80)
        val lines = linesOf(print(text))
        assertEquals(text, lines.joinToString(""))
        lines.forEach { assertTrue(it.isEmpty() || it.first() !in "。，、；：？！）】》”", "这行以「${it.first()}」起头：[$it]") }
    }

    @Test
    fun `开括号不许留在行尾`() {
        val text = "啊".repeat(40) + "（" + "哈".repeat(80)
        val lines = linesOf(print(text))
        assertEquals(text, lines.joinToString(""))
        lines.forEach { assertTrue(it.isEmpty() || it.last() != '（', "行尾是开括号：[$it]") }
    }

    @Test
    fun `超长串不卡死且照样推进`() {
        val word = "x".repeat(500)
        val text = "看 $word 完"
        val lines = linesOf(print(text))
        assertTrue(lines.size > 1, "超长串要一行一行吞下去")
        assertEquals(text.replace(" ", ""), lines.joinToString("").replace(" ", ""), "一个字符都不许多也不许少")
        assertTrue(lines.first().length < word.length, "第一行装不下就该断，不能整段塞一行")
    }

    @Test
    fun `一个字比它那一栏还宽时要报溢出而不是静悄悄`() {
        // 栏宽有个"至少放得下一个正文字"的兜底，所以纯正文怎么窄都塞得下；
        // 标题按 1.6 倍放大，一个标题字就比那一栏宽 —— 这是溢出提示唯一能真实发生的地方
        val parts = listOf(DocParagraph(DocPara(listOf(DocRun("标题")), style = "Heading1")))
        val result = PrintPlanner.plan(parts, 110f, PAGE_H, 50f, 12f, 1.4f, 0f, ::widthOf)
        assertTrue(result.notes.any { it.contains("溢出") }, "必须说明有行会溢出：${result.notes}")
    }

    // ---- 分页与坐标 -------------------------------------------------------------

    @Test
    fun `每页行数不超过可用高度`() {
        val perPage = ((PAGE_H - MARGIN * 2) / (12f * 1.4f)).toInt()
        val result = print((1..200).joinToString("\n") { "第 $it 行" })
        assertEquals(200, linesOf(result).size, "源文件 200 行就该排出 200 行")
        assertEquals(ceil(200.0 / perPage).toInt(), result.pageCount)
        assertTrue(result.pages.all { it.texts.size <= perPage }, "有页超过了 $perPage 行")
    }

    @Test
    fun `空行分段比软换行多让出间隔`() {
        val soft = print("第一段\n第二段")
        val blank = print("第一段\n\n第二段")
        assertEquals(listOf("第一段", "第二段"), linesOf(blank), "空行不该排出一行空字")
        val gapSoft = baselines(soft)[1] - baselines(soft)[0]
        val gapBlank = baselines(blank)[1] - baselines(blank)[0]
        assertTrue(gapBlank > gapSoft, "分段要有看得出来的间隔：软换行 $gapSoft，空行 $gapBlank")
    }

    @Test
    fun `行坐标都落在页边距内`() {
        val result = print("啊".repeat(900))
        result.pages.forEach { page ->
            page.texts.forEach { line ->
                assertEquals(MARGIN, line.x, "行首要贴左边距")
                assertTrue(line.y > MARGIN, "基线不能顶到上边距：$line")
                assertTrue(line.y <= PAGE_H - MARGIN, "不许越过下边距：$line")
            }
        }
    }

    @Test
    fun `行距越大页数越多`() {
        val text = "啊".repeat(6000)
        assertTrue(print(text, lineHeight = 1.0f).pageCount < print(text, lineHeight = 2.0f).pageCount)
    }

    @Test
    fun `首行缩进既让第一行少装一点也把整行往右挪`() {
        val paragraph = "啊".repeat(40)
        val plain = linesOf(print(paragraph))
        val indented = print(paragraph, indent = 24f)
        assertTrue(linesOf(indented).first().length < plain.first().length, "缩进没起作用：[${indented.pages[0].texts.first().text}]")
        assertEquals(paragraph, linesOf(indented).joinToString(""))
        val first = indented.pages[0].texts.first()
        assertEquals(MARGIN + 24f, first.x, "段首行要真的往右挪，光排窄等于没缩进")
        assertEquals(MARGIN, indented.pages[0].texts[1].x, "第二行不缩进")
    }

    @Test
    fun `每一行的右端都不越出右边距`() {
        val result = print("啊".repeat(300) + "\n" + "哈".repeat(300), indent = 24f)
        result.pages.forEach { page ->
            page.texts.forEach { line ->
                val right = line.x + widthOf(line.text, 12f)
                assertTrue(right <= MARGIN + CONTENT, "这行伸出右边距：x=${line.x} 宽=${widthOf(line.text, 12f)}")
            }
        }
    }

    @Test
    fun `多段各自独立成行`() {
        assertEquals(listOf("甲", "乙", "丙"), linesOf(print("甲\n乙\n丙")))
    }

    @Test
    fun `边距比页面还大要直接报错`() {
        val error = runCatching {
            PrintPlanner.plan(PlainDoc.toDoc("字").parts, 100f, 100f, 80f, 12f, 1.4f, 0f, ::widthOf)
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("页边距"), error.message)
    }

    @Test
    fun `空文本也要给出一页而不是零页`() {
        assertEquals(1, print("").pageCount, "零页会让上层画不出任何东西还报成功")
    }
}
