package com.fileforge.core.pdf

import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocTable

/** 纸上的一条线段：表格的边框与分隔线。坐标与 [TextLine] 同一套（y 从页面顶部往下数）。 */
class PrintRule(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float)

/** 排好的一页：先画线，再把字放上去。 */
class PrintPage(val texts: List<TextLine>, val rules: List<PrintRule>)

/** 排完的一份 PDF 内容，加上"排的时候做了什么妥协"。 */
class PrintPlan(val pages: List<PrintPage>, val notes: List<String>) {
    val pageCount: Int get() = pages.size
}

/**
 * 把一棵文档树排成页：正文照 [TextLayoutPlanner] 那套断行，表格**画成真的网格**。
 *
 * 为什么要这一层：转换工具站上 "Excel 转 PDF" 是头牌条目，而这边此前只会排连续文字 ——
 * 表格摊成一行行制表符印出去，列就没有位置关系了，两列还会挤成一行。有了网格才谈得上
 * "别人拿这份 PDF 还能看出哪格属于哪一列"。
 *
 * 三条主张：
 *  - **列宽是量出来的**：先按每格内容的自然宽度排，装得下就用自然宽；装不下先给每列保底
 *    （至少放得下那一列最宽的一个字），剩下的空间按自然宽的比例分。压了哪一列、从多少压到多少，
 *    写进 [PrintPlan.notes]
 *  - **宁可溢出也不裁字**：连保底都放不下（列太多的表）就按保底宽画出去、超出纸边多少直说；
 *    一行比一整页还高就让那一行溢出去并写明第几行 —— 把字裁掉或整列丢掉是最难发现的错
 *  - **格子换行用正文同一套规矩**（[TextLayoutPlanner.wrap]）：两套断行的话，同一段话在正文里
 *    断在四字处、在格子里断在三字处，读回来对不上，判据也没法判
 *
 * 跨页时表头那一行重复（文件自己说首行是表头才重复），行不拆开跨页。列表的记号**画在纸上**
 * （圆点照画，编号按层各数各的，中间断了就从头数），引用往里缩一块；没有粗体字：
 * 标题靠字号与段前留白，表头靠那一条加重的分隔线。
 */
object PrintPlanner {

    /** 格子里左右与上下的留白（按字号算，太挤就看不清哪格是哪格）。 */
    private const val PAD_X_RATIO = 0.35f
    private const val PAD_Y_RATIO = 0.2f

    /** 分隔线的粗细：表头下面那条重一些 —— 没有粗体字时靠它把首行标出来。 */
    private const val RULE_THIN = 0.5f
    private const val RULE_HEAD = 1.2f

    fun plan(
        parts: List<DocPart>,
        pageWidth: Float,
        pageHeight: Float,
        margin: Float,
        size: Float,
        lineHeightFactor: Float = 1.4f,
        firstLineIndent: Float = 0f,
        widthOf: (String, Float) -> Float,
        bullet: String = "•",
    ): PrintPlan {
        require(pageWidth > margin * 2 && pageHeight > margin * 2) { "页边距比页面还大，排不下任何东西" }
        val flow = Flow(pageWidth, pageHeight, margin, size, lineHeightFactor, firstLineIndent, bullet, widthOf)
        parts.forEach { part ->
            when (part) {
                is DocParagraph -> flow.paragraph(part.para)
                is DocRule -> flow.rule()
                is DocTable -> flow.table(part)
            }
        }
        return flow.finish()
    }

    /**
     * 每列多宽。
     *
     * [overflow] 是"连保底也放不下"时超出纸边的量（0 表示放得下）；[pressed] 记的是被压窄的列
     * （第几列、从多少压到多少）—— 这些都要原样写给用户看，不能悄悄把宽列压扁了事。
     */
    internal class ColumnFit(val widths: List<Float>, val overflow: Float, val pressed: List<String>)

    internal fun fitColumns(
        rows: List<List<String>>,
        contentWidth: Float,
        size: Float,
        padX: Float,
        widthOf: (String, Float) -> Float,
    ): ColumnFit {
        val columns = rows.maxOfOrNull { it.size } ?: 0
        if (columns == 0) return ColumnFit(emptyList(), 0f, emptyList())
        val natural = ArrayList<Float>(columns)
        val floor = ArrayList<Float>(columns)
        for (index in 0 until columns) {
            var widest = 0f
            var widestChar = 0f
            rows.forEach { row ->
                val cell = row.getOrElse(index) { "" }
                // 带硬换行的格子，自然宽是**最长那一句**的宽度：整串连着量会把两句话算成一句，
                // 有些字体还会因为换行符没有字形直接量不动
                cell.split('\n').forEach { piece -> widest = maxOf(widest, widthOf(piece, size)) }
                cell.forEach { ch -> if (ch != '\n') widestChar = maxOf(widestChar, widthOf(ch.toString(), size)) }
            }
            natural += widest + padX * 2
            // 一整列都空也要占位：那是"这些行没有这一格"，不是这一列不存在
            floor += (if (widestChar > 0f) widestChar else size).coerceAtLeast(size * 0.6f) + padX * 2
        }
        if (natural.sum() <= contentWidth) return ColumnFit(natural, 0f, emptyList())
        val minimum = floor.sum()
        if (minimum > contentWidth) {
            return ColumnFit(floor, minimum - contentWidth, pressed(natural, floor))
        }
        // 要腾空间就从**最宽的那一列**先减，减到底再换下一宽列：窄列本来只够那一句话，
        // 跟着一起瘦就是把"12"压成两行这种没必要的难看
        val widths = ArrayList(natural)
        var excess = natural.sum() - contentWidth
        while (excess > 0.5f) {
            val widest = widths.indices
                .filter { widths[it] > floor[it] + 0.5f }
                .maxByOrNull { widths[it] }
                ?: break
            val take = minOf(widths[widest] - floor[widest], excess)
            widths[widest] -= take
            excess -= take
        }
        return ColumnFit(widths, 0f, pressed(natural, widths))
    }

    /** 哪几列被压窄了：`第 N 列 从 Xpt 压到 Ypt`。 */
    private fun pressed(natural: List<Float>, widths: List<Float>): List<String> {
        val out = ArrayList<String>()
        for (index in widths.indices) {
            if (natural[index] > widths[index] + 0.5f) {
                out += "第 ${index + 1} 列从 ${"%.0f".format(natural[index])}pt 压到 ${"%.0f".format(widths[index])}pt"
            }
        }
        return out
    }

    /** 一页一页往下放的现场。状态都在这一个实例上：两次排版不许共用计数器。 */
    private class Flow(
        private val pageWidth: Float,
        private val pageHeight: Float,
        private val margin: Float,
        private val size: Float,
        private val lineHeightFactor: Float,
        private val firstLineIndent: Float,
        private val bullet: String,
        private val widthOf: (String, Float) -> Float,
    ) {
        private val notes = ArrayList<String>()
        private val pages = ArrayList<PrintPage>()
        private val texts = ArrayList<TextLine>()
        private val rules = ArrayList<PrintRule>()
        private val contentWidth = pageWidth - margin * 2
        private val bottom = pageHeight - margin
        private var y = margin
        private var wrapped = 0
        private var repeated = 0
        private var lastHeading = ""
        private var saidCodeFont = false
        private val listCount = HashMap<Int, Int>()          // 层号 → 这一层排到第几个（只编号列表用）

        fun finish(): PrintPlan {
            seal()
            if (wrapped > 0) notes += "有 $wrapped 格因为列宽不够折成了多行"
            if (repeated > 0) notes += "跨页时表头那一行重复了 $repeated 次"
            if (pages.isEmpty()) pages += PrintPage(emptyList(), emptyList())
            return PrintPlan(pages, notes)
        }

        /**
         * 一段正文、小标题、列表项或引用。
         *
         * 列表的记号**画在纸上**：文件里那一层是"这是列表第几项"，纸上没有 Word 的 numbering 可借，
         * 不画就等于把列表摊平成段落。编号按层各数各的，一层里排到第几就是几；
         * 出现别的块（正文、表、分隔线）就把计数清掉 —— 那是新的一段列表。
         */
        fun paragraph(para: DocPara) {
            val text = para.text
            if (text.isBlank()) {
                y += size * lineHeightFactor
                return
            }
            val level = if (para.style.startsWith("Heading")) para.style.removePrefix("Heading").toIntOrNull() ?: 1 else 0
            if (level > 0) lastHeading = text.trim()
            val scale = when (level) {
                0 -> 1f
                1 -> 1.6f
                2 -> 1.4f
                3 -> 1.25f
                else -> 1.1f
            }
            val sizeNow = size * scale
            val lineHeight = sizeNow * lineHeightFactor
            val isList = para.bullet != null
            var indent = para.indent * size * 2f
            if (!isList) listCount.clear()                       // 列表断了一次，下一段从头数
            if (para.style == "Quote") indent += size * 1.5f     // 引用没有边框，靠往里缩一块认出来
            if (para.style == "SourceCode" && !saidCodeFont) {
                saidCodeFont = true
                notes += "代码块用的是正文字体（这份字体不是等宽的，竖线对不齐）"
            }
            val marker = if (isList) markerFor(para) else ""
            // 记号与文字之间留半个字：紧贴着画的话，"•字" 在 PDF 的词抽取里会被当成一个词
            val markerWidth = if (marker.isEmpty()) 0f else widthOf(marker, sizeNow) + sizeNow * 0.5f
            // 记号钉在缩进那一列，文字往后走：折行回来的第二行也要跟第一行的字对齐（悬挂缩进），
            // 让记号跟着折回来就把"1."顶到下一行去了
            if (level > 0) y += sizeNow * 0.4f                       // 标题前留一点：纸上没有段前距这一说
            val canIndent = level == 0 && !isList && para.style != "SourceCode"
            splitLines(text).forEachIndexed { pieceIndex, piece ->
                val shift = if (pieceIndex == 0 && canIndent) firstLineIndent else 0f
                wrapInto(piece, (contentWidth - indent - markerWidth - shift).coerceAtLeast(size), sizeNow)
                    .forEachIndexed { lineIndex, line ->
                        advance(lineHeight)
                        val at = margin + indent + markerWidth + if (lineIndex == 0) shift else 0f
                        if (lineIndex == 0 && marker.isNotEmpty()) {
                            texts += TextLine(marker, margin + indent, y + sizeNow, sizeNow)
                        }
                        texts += TextLine(line, at, y + sizeNow, sizeNow)
                        y += lineHeight
                    }
            }
            y += size * 0.5f
        }

        /** 这一项前面画什么记号：圆点不动计数，编号按层各数各的。 */
        private fun markerFor(para: DocPara): String {
            val layer = para.indent.coerceAtLeast(0)
            listCount.keys.filter { it > layer }.forEach { listCount.remove(it) }
            return if (para.bullet == true) {
                bullet                                       // 这一笔由调用方按字体给：字体没有 U+2022 时
            } else {
                val next = (listCount[layer] ?: 0) + 1
                listCount[layer] = next
                "$next."
            }
        }

        /** 分隔线：一条横贯栏宽的细线。 */
        fun rule() {
            listCount.clear()
            advance(size * lineHeightFactor)
            val at = y + size * 0.3f
            rules += PrintRule(margin, at, margin + contentWidth, at, RULE_THIN)
            y += size * lineHeightFactor
        }

        /**
         * 一张表：每格的行先**只折一次**（同时数出这一行该多高），再逐行摆；
         * 行放不下就翻页，并在新一页上先重复表头。
         *
         * 竖线按"这一页上这段表"一次画完（从这段的第一行顶到最后一行底），跨页的表在每一页
         * 都有自己的框，不会把上一段的线拖到下一页去。
         */
        fun table(table: DocTable) {
            if (table.rows.isEmpty()) return
            listCount.clear()                                       // 中间插了一张表，列表就是从这一段断开了
            val padX = size * PAD_X_RATIO
            val padY = size * PAD_Y_RATIO
            val lineHeight = size * lineHeightFactor
            val fit = fitColumns(table.rows, contentWidth, size, padX, widthOf)
            // 26 列的宽表能报出二十多条"从多少压到多少"，那种篇幅等于没说：点名最狠的三条，其余给总数
            val who = if (lastHeading.isEmpty()) "" else "「$lastHeading」"
            fit.pressed.take(3).forEach { notes += "$who$it（列宽按内容量的自然宽压下来的）" }
            if (fit.pressed.size > 3) notes += "${who}另有 ${fit.pressed.size - 3} 列也被压窄（压到只剩一个最宽的字）"
            if (fit.overflow > 0f) {
                notes += "${if (lastHeading.isEmpty()) "" else "「$lastHeading」"}有一张表连保底宽也要超出纸边 ${"%.0f".format(fit.overflow)}pt：宁可行出纸外也不裁字、不丢列"
            }
            val edges = ArrayList<Float>()
            edges += margin
            var running = margin
            // 逐列累加出边界。这里不要写成 .also { running += it }：also 的 it 是那个新边界，
            // 累加一次就把"上一步的结果"又加进去一遍，列就全错位了
            fit.widths.forEach { width ->
                running += width
                edges += running
            }

            val cells = table.rows.map { row -> rowCells(row, fit.widths, padX) }
            val heights = cells.map { lines -> (lines.maxOfOrNull { it.size } ?: 1) * lineHeight + padY * 2 }
            val headerHeight = if (table.header) heights.first() else 0f

            var spanTop = -1f
            var index = 0
            while (index < table.rows.size) {
                if (y + heights[index] > bottom) {
                    spanTop = closeSpan(spanTop, edges)
                    seal()
                    y = margin
                    if (heights[index] > bottom - margin) {
                        notes += "$who 这张表第 ${index + 1} 行有 ${cells[index].maxOfOrNull { it.size } ?: 1} 行字，比一页还高，那一行会超出纸边"
                    }
                    if (table.header && index > 0) {
                        spanTop = y
                        paintCells(cells.first(), edges, padX, padY, lineHeight)
                        rules += PrintRule(margin, y + headerHeight, edges.last(), y + headerHeight, RULE_HEAD)
                        y += headerHeight
                        repeated++
                    }
                }
                if (spanTop < 0f) spanTop = y
                paintCells(cells[index], edges, padX, padY, lineHeight)
                rules += PrintRule(
                    margin, y + heights[index], edges.last(), y + heights[index],
                    if (index == 0 && table.header) RULE_HEAD else RULE_THIN,
                )
                y += heights[index]
                index++
            }
            closeSpan(spanTop, edges)
            y += size * 0.5f
        }

        /** 每一格折成几行（一次算清：行高与画字用的是同一份，不会数两遍折行）。 */
        private fun rowCells(row: List<String>, widths: List<Float>, padX: Float): List<List<String>> =
            widths.indices.map { index ->
                val cell = row.getOrElse(index) { "" }
                if (cell.isEmpty()) listOf("") else {
                    val made = splitLines(cell).flatMap { wrapInto(it, (widths[index] - padX * 2).coerceAtLeast(size), size) }
                    if (made.size > 1) wrapped++
                    made
                }
            }

        private fun paintCells(cells: List<List<String>>, edges: List<Float>, padX: Float, padY: Float, lineHeight: Float) {
            for (index in cells.indices) {
                val left = edges[index] + padX
                cells[index].forEachIndexed { line, text ->
                    if (text.isNotEmpty()) texts += TextLine(text, left, y + padY + size + line * lineHeight, size)
                }
            }
        }

        /** 收这一页上的一段表：竖线与最上面那条线一起画，返回 -1 表示下一段要重新起顶。 */
        private fun closeSpan(top: Float, edges: List<Float>): Float {
            if (top < 0f || y <= top) return -1f
            edges.forEach { at -> rules += PrintRule(at, top, at, y, RULE_THIN) }
            rules += PrintRule(edges.first(), top, edges.last(), top, RULE_THIN)
            return -1f
        }

        /** 一格/一段里的硬换行先切开，再各自按同一套规矩折。 */
        private fun splitLines(text: String): List<String> = text.split('\n').ifEmpty { listOf("") }

        private fun wrapInto(text: String, width: Float, sizeNow: Float): List<String> {
            if (text.isEmpty()) return listOf("")
            var widest = 0f
            val made = TextLayoutPlanner.wrap(text, width, sizeNow, 0f, widthOf) { need -> widest = maxOf(widest, need) }
            if (widest > width) {
                notes += "有个字比它那一栏的可用宽度还宽（${"%.0f".format(widest)}pt > ${"%.0f".format(width)}pt），那一行只能溢出"
            }
            return made
        }

        /** 往下走 [need]：放不下就交掉这一页、从顶边距接着排。 */
        private fun advance(need: Float) {
            if (y + need > bottom) {
                seal()
                y = margin
            }
        }

        private fun seal() {
            if (texts.isEmpty() && rules.isEmpty()) return
            pages += PrintPage(texts.toList(), rules.toList())
            texts.clear()
            rules.clear()
        }
    }
}
