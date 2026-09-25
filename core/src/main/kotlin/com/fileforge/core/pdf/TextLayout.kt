package com.fileforge.core.pdf

/** 一行排好的文字，坐标已经按页面算好（y 从页面顶部往下数，交给引擎时再翻成 PDF 的从下往上）。 */
class TextLine(val text: String, val x: Float, val y: Float, val size: Float)

/** 排完的一页。 */
class TextPage(val lines: List<TextLine>)

/** 排版结果：页、以及"排的时候做了什么妥协"。 */
class TextLayout(val pages: List<TextPage>, val notes: List<String>) {
    val pageCount: Int get() = pages.size
}

/**
 * 把一段纯文本排成页。
 *
 * 这里**不含任何 PDF 与字体 API**：宽度由调用方给的 [widthOf] 算（安卓那边就是
 * `font.getStringWidth(text) / 1000 * size`）。这样断行与分页这套规则能脱离设备单测，
 * 而"宽度模型到底准不准"另由 `tools/pdfprobe` 在桌面 PDFBox 上量。
 *
 * 中文排版有三处必须照中文的规矩来，照英文那套会明显难看甚至错：
 *  1. **CJK 字符之间都能断**，不等空格。英文只在词边界断，硬套到中文上就是一整行放不下才被迫整段溢出。
 *  2. **行首不许是收尾标点**（。，、；：？！”’）】》，一个字赶下去比留白难看。
 *  3. **行尾不许是开引号/开括号**，同理。
 *
 * 半角单词仍然整体搬，不会被从中间切断。
 */
object TextLayoutPlanner {

    /** 不许出现在行首的字符：收尾标点与闭括号。 */
    private const val NO_LINE_START = "。，、；：？！）］｝〉》”’…—％%!,.;?)]}"

    /** 不许出现在行尾的字符：开括号与开引号。 */
    private const val NO_LINE_END = "（［｛〈《“‘([{"

    /**
     * @param widthOf 这段文字以 size 字号占多宽
     * @param lineHeightFactor 行距倍数（1.4 是常见默认）
     * @param firstLineIndent 段首行往右挪多少，同时这一行少排这么宽
     */
    fun layout(
        text: String,
        pageWidth: Float,
        pageHeight: Float,
        margin: Float,
        size: Float,
        lineHeightFactor: Float = 1.4f,
        firstLineIndent: Float = 0f,
        widthOf: (String, Float) -> Float,
    ): TextLayout {
        val contentWidth = pageWidth - margin * 2
        val lineHeight = size * lineHeightFactor
        val usableHeight = pageHeight - margin * 2
        require(contentWidth > 0 && usableHeight > 0) { "页边距比页面还大，排不下任何东西" }

        val notes = ArrayList<String>()
        val lines = ArrayList<Pair<String, Boolean>>()            // 文本 + 是不是段首行
        var widestNeeded = 0f
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
            val paragraph = raw.trimEnd()
            if (paragraph.isEmpty()) {
                lines += "" to false                              // 空行要留住：那是段落间隔
                return@forEach
            }
            val wrapped = wrap(paragraph, contentWidth, size, firstLineIndent, widthOf) { overflow ->
                widestNeeded = maxOf(widestNeeded, overflow)
            }
            wrapped.forEachIndexed { row, line -> lines += (line to (row == 0)) }
        }
        if (widestNeeded > contentWidth) {
            notes += "有单个字符就比正文宽度还宽（${"%.0f".format(widestNeeded)}pt > ${"%.0f".format(contentWidth)}pt），那行只能溢出"
        }

        val perPage = maxOf(1, (usableHeight / lineHeight).toInt())
        val pages = ArrayList<TextPage>()
        var index = 0
        while (index < lines.size) {
            val chunk = lines.subList(index, minOf(index + perPage, lines.size))
            pages += TextPage(chunk.mapIndexed { row, (line, headOfParagraph) ->
                TextLine(
                    text = line,
                    // 缩进得画在纸上：只把第一行排窄而位置不动，用户看起来就是"右边平、左边缺一块"，
                    // 中文的段首空两格要的是整行往右移
                    x = margin + if (headOfParagraph && line.isNotEmpty()) firstLineIndent else 0f,
                    y = margin + (row + 1) * lineHeight,                     // 基线：留出一个行高，别贴着顶边距
                    size = size,
                )
            })
            index += perPage
        }
        if (pages.isEmpty()) pages += TextPage(emptyList())
        return TextLayout(pages, notes)
    }

    /**
     * 一段切成若干行。
     *
     * 逐个字符往前加，超宽就在合适的位置断：CJK 边界随时可断，西文要退到上一个词边界。
     * 断完再把不合规矩的收尾标点/开括号推到下一行。
     */
    private fun wrap(
        paragraph: String,
        contentWidth: Float,
        size: Float,
        firstLineIndent: Float,
        widthOf: (String, Float) -> Float,
        onOverflow: (Float) -> Unit,
    ): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var indent = firstLineIndent
        while (start < paragraph.length) {
            var last = start
            var width = 0f
            var brokeAt = -1                                      // 最近一个"可以断"的位置（词边界或 CJK 边界）
            var index = start
            val limit = contentWidth - indent
            while (index < paragraph.length) {
                val ch = paragraph[index]
                val charWidth = widthOf(ch.toString(), size)
                if (width + charWidth > limit) {
                    // 先按"字间断"处理：CJK 之间随时能断；西文要退回到最后一个词边界
                    last = if (brokeAt > start) brokeAt else index
                    break
                }
                width += charWidth
                if (index > start && canBreakAfter(paragraph, index)) brokeAt = index + 1
                index++
                last = index
            }
            // 断在词边界时那个空格归上一步吃掉：留着它，行尾就挂着一个看不见的空格，
            // 而换行本身就是要用它，不该再出现在纸面上
            val line = paragraph.substring(start, last).trimEnd()
            // 收尾标点不许留在行首：把它从上一行末尾带到这一行开头之前，先回看一行
            if (out.isNotEmpty()) {
                val previous = out.last()
                val carry = leadingBadStart(line)
                if (carry > 0) {
                    out[out.size - 1] = previous + line.substring(0, carry)
                    start += carry
                    continue
                }
            }
            val trailing = trailingBadEnd(line, paragraph, last)
            if (trailing > 0 && line.length > trailing) {
                out += line.substring(0, line.length - trailing)
                start = last - trailing
                indent = 0f
                continue
            }
            if (last == paragraph.length) {
                out += line
                break
            }
            if (last <= start) {
                // 一个字符都放不下：只能硬吞一个字符往前走，否则会原地打转
                out += paragraph.substring(start, start + 1)
                onOverflow(widthOf(paragraph[start].toString(), size))
                start += 1
            } else {
                out += line
                start = last
            }
            indent = 0f
        }
        return out
    }

    private fun canBreakAfter(paragraph: String, index: Int): Boolean {
        val here = paragraph[index]
        val next = paragraph.getOrNull(index + 1) ?: return true
        return here == ' ' || next == ' ' || isCjk(here) || isCjk(next)
    }

    /** 这一行开头有几个字符是"不许起行"的。 */
    private fun leadingBadStart(line: String): Int {
        var count = 0
        while (count < line.length && line[count] in NO_LINE_START) count++
        return if (count == line.length) 0 else count
    }

    /** 这一行末尾有几个字符是"不许收尾"的（要把它们和后面的内容一起挪下去）。 */
    private fun trailingBadEnd(line: String, paragraph: String, end: Int): Int {
        var count = 0
        while (count < line.length && line[line.length - 1 - count] in NO_LINE_END) count++
        return if (count == line.length) 0 else count
    }

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6 || code in 0x3000..0x303F
    }
}
