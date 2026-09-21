package com.fileforge.core.pdf

class PageRangeException(message: String) : Exception(message)

/** 一段页码，1-based 闭区间。end 为 null 表示到最后一页；start 为 null 表示从第一页开始。 */
data class PageSpan(val start: Int?, val end: Int?) {
    val reversed: Boolean get() = start != null && end != null && start > end

    /** 解析成确定的 1-based 区间；倒序时步长为 -1。 */
    fun resolve(totalPages: Int): IntProgression {
        val from = (start ?: 1).coerceIn(1, totalPages)
        val to = (end ?: totalPages).coerceIn(1, totalPages)
        return if (from > to) from downTo to else from..to
    }
}

object PageRangeParser {

    private val tokenSplit = Regex("[,，;；、\\s]+")
    private val numeric = Regex("""\d+""")

    /**
     * 解析 "100-150,200-250"、"5"、"-8"、"300-"、"150-100"（倒序）等形式。
     * 逗号/分号/顿号/空格都算分隔符，顺序与重复都按用户写的来（可用于调页序）。
     */
    fun parse(spec: String): List<PageSpan> {
        val tokens = spec.trim().split(tokenSplit).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) throw PageRangeException("页码范围不能为空，例如 100-150,200-250")

        return tokens.map { token ->
            val dash = token.indexOf('-')
            if (dash < 0) {
                val single = requireNumber(token, dash = -1) { token }
                PageSpan(single, single)
            } else {
                val head = token.substring(0, dash).trim()
                val tail = token.substring(dash + 1).trim()
                if (head.isEmpty() && tail.isEmpty()) throw PageRangeException("「$token」不是页码范围")
                if (tail.contains('-')) throw PageRangeException("「$token」里只有一个横杠才能表示范围")
                PageSpan(head.takeIf { it.isNotEmpty() }?.let { requireNumber(it, -1) { head } },
                    tail.takeIf { it.isNotEmpty() }?.let { requireNumber(it, -1) { tail } })
            }
        }
    }

    private fun requireNumber(text: String, dash: Int, original: () -> String): Int {
        if (!numeric.matches(text)) throw PageRangeException("「${original()}」里的「$text」不是数字")
        return text.toIntOrNull() ?: throw PageRangeException("「$text」超出可处理范围")
    }

    /** 展开成 0-based 页索引，保持用户给的顺序；越界直接报错而不是悄悄裁掉。 */
    fun toPageIndices(spans: List<PageSpan>, totalPages: Int): List<Int> {
        if (totalPages <= 0) throw PageRangeException("这个 PDF 里没有页面")
        spans.forEach { span ->
            val start = span.start
            val end = span.end
            if (start != null && (start < 1 || start > totalPages)) {
                throw PageRangeException("第 $start 页不存在（本文档共 $totalPages 页）")
            }
            if (end != null && (end < 1 || end > totalPages)) {
                throw PageRangeException("第 $end 页不存在（本文档共 $totalPages 页）")
            }
        }
        return spans.flatMap { span -> span.resolve(totalPages).map { it - 1 } }
    }
}
