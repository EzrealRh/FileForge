package com.fileforge.core.pdf

/** 纸上的一条文字：坐标已经按页面算好（y 从页面顶部往下数，交给引擎时再翻成 PDF 的从下往上）。 */
class TextLine(val text: String, val x: Float, val y: Float, val size: Float)

/**
 * 把一段文字切成行 —— 断行这一件事只有一份，正文和表格的格子都调它。
 *
 * 这里**不含任何 PDF 与字体 API**：宽度由调用方给的 [widthOf] 算（安卓那边就是
 * `font.getStringWidth(text) / 1000 * size`）。这样断行规则能脱离设备单测，
 * 而"宽度模型到底准不准"另由 `tools/pdfprobe` 在桌面 PDFBox 上量。
 *
 * 分页、坐标和表格网格都在 [PrintPlanner] 那一层：这一层只管"这段文字在该断的地方断"。
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
     * 一段切成若干行。
     *
     * 逐个字符往前加，超宽就在合适的位置断：CJK 边界随时可断，西文要退到上一个词边界。
     * 断完再把不合规矩的收尾标点/开括号推到下一行。
     *
     * 表格的格子也走这一套（见 [PrintPlanner]）：两处各写一套断行的话，同一段话在正文里
     * 断在三字处、在格子里断在四字处，读回来就对不上，更别说判据了。
     */
    internal fun wrap(
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
            val raw = paragraph.substring(start, last)
            val line = raw.trimEnd()
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
                // 退回去的字符数要按**没去空白前**的 raw 算：trailing 是在去空白后的 line 上数的，
                // 拿它直接减 last 会把括号和空白一起跳过 —— 那个字符就哪一行都不在了
                val cut = raw.length - line.length + trailing
                out += raw.substring(0, raw.length - cut)
                start = last - cut
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
