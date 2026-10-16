package com.fileforge.core.pdf

import kotlin.math.abs
import kotlin.math.max

/**
 * 纸上的一个词：连它在哪一页、左右上下到哪一起给。
 *
 * 为什么要到词，不要只到行：表是**列**的艺术，一行字里"姓名    电话"这种空隙才是列的边界，
 * 并成一行的整串文字把空隙抹平了 —— 从行级信息还原不出哪一格属于哪一列。
 */
class PdfWord(
    val text: String,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val page: Int,
)

/**
 * PDF 的纸上把行列还原回来（"PDF 转 Excel"那一步的判断部分）。
 *
 * 只用坐标，不猜语义：
 *  1. **成行**：按顶边排序，顶边差超过行容差就换行 —— 同一行的字基线必然挨在一起
 *  2. **找列**：列边界是**对齐的字起点**。把每一行除首词以外的起点收集起来，在多行里重复出现
 *     （挨得足够近就算同一个）的那些位置就是列的**候选**边界；窄过字距的空隙不算
 *  3. **分段**：一页常同时摆着两张表、标题和整句正文。先按"这行像不像表里的一行"切段，
 *     再在每段里单独定列 —— 段内跨着这条线的行不许多过按这条线起头的行（多过就说明那位置是句子排到了头，
 *     不是栏目在那儿起），段内没人对齐的候选一律丢掉
 *  4. **成表**：一段里连续的行按同一套边界切格子；只有唯一一格有字、又贴得比一个行距还近的，
 *     是上一格的内容接着排，并回去而不是另起一行
 *  5. 认不出的地方老实交代（"这一页有 N 行没分成列"），不硬凑
 *
 * 行容差与"对齐容差"都用**页面上量出来的行高/字号**推，不写死磅数：一份 PDF 里 8pt 的密表与
 * 24pt 的报表，同一套绝对阈值不可能都对（写死的那版会在密表里把两行并成一行、在报表里把一行拆成两行）。
 */
object PdfTableDetect {

    /** 一条表至少要几行：两行是"表头 + 一行"，一行不算表。 */
    private const val MIN_TABLE_ROWS = 2

    /** 一行里两个字之间的空隙窄于行高的这个比例，就是字距（kerning）而不是列距。 */
    private const val MIN_GAP_RATIO = 0.35f

    class Row(val top: Float, val bottom: Float, val words: List<PdfWord>)

    class Detected(val page: Int, val top: Float, val bottom: Float, val rows: List<List<String>>, val boundaries: List<Float>)

    class Result(val tables: List<Detected>, val notes: List<String>)

    /**
     * @param minRowsForColumns 一列至少要在这么多行里都对齐才算列（太少会把页码行、脚注那种
     *        "恰好两处对齐"的噪声当成表；太多会漏掉两行的小表 —— 默认取 3，行数不足时自动降）
     */
    fun tablesOf(words: List<PdfWord>, minRowsForColumns: Int = 3): Result {
        val notes = ArrayList<String>()
        val tables = ArrayList<Detected>()
        words.groupBy { it.page }.forEach { (page, onPage) ->
            val rows = rowsOf(onPage)
            if (rows.size < MIN_TABLE_ROWS) {
                notes += "第 ${page + 1} 页只有 ${rows.size} 行文字，没当成表"
                return@forEach
            }
            val tolerance = medianRowHeight(rows) * 0.6f
            val needed = minOf(minRowsForColumns, rows.size)
            val candidates = columnCuts(rows, tolerance, needed)
            if (candidates.isEmpty()) {
                notes += "第 ${page + 1} 页没找到对齐的空隙（${rows.size} 行文字），没当成表"
                return@forEach
            }
            // 一整页可能摆着两张表、两个标题和三行正文：先按"这行像不像表里的一行"切段，
            // 再在每一段里单独定列 —— 列边界是表的事，不是整页的事
            var run = ArrayList<Row>()
            val runs = ArrayList<List<Row>>()
            rows.forEach { row ->
                if (rowLooksLikeTableRow(row, candidates, tolerance)) {
                    run += row
                } else {
                    if (run.size >= MIN_TABLE_ROWS) runs += run
                    run = ArrayList()
                }
            }
            if (run.size >= MIN_TABLE_ROWS) runs += run
            if (runs.isEmpty()) {
                notes += "第 ${page + 1} 页的空隙对不出 ${MIN_TABLE_ROWS} 行以上的表（${rows.size} 行文字）"
                return@forEach
            }
            var counted = 0
            runs.forEach { body ->
                val cuts = candidates.filter { at -> body.alignsTo(at, tolerance) >= MIN_TABLE_ROWS && body.none { row -> row.crosses(at, tolerance) } }
                if (cuts.isEmpty()) {
                    notes += "第 ${page + 1} 页有一段 ${body.size} 行的字对不齐同一处，没当成表"
                    return@forEach
                }
                val grid = mergeWrappedCells(body, gridOf(body, cuts, tolerance))
                counted += body.size
                tables += Detected(
                    page = page,
                    top = body.minOf { it.top },
                    bottom = body.maxOf { it.bottom },
                    rows = grid,
                    boundaries = cuts,
                )
            }
            if (counted < rows.size) {
                notes += "第 ${page + 1} 页 ${rows.size} 行里 ${rows.size - counted} 行没分成列（标题、页码或注行），只当正文"
            }
        }
        return Result(tables, notes)
    }

    /**
     * 这一行像不像表里的一行：真分成了两格以上，或者它的字正好落在某个列起点上。
     *
     * 后一半是给"整行只有两三格有字"的表行留的路 —— 按"至少两格有字"判会把它们踢出表，
     * 一张空了几格的行就把表劈成两张（实测：费用表里那行只有日期一格有字，就被当成了分隔行）。
     */
    private fun rowLooksLikeTableRow(row: Row, cuts: List<Float>, tolerance: Float): Boolean {
        val cells = gridOf(listOf(row), cuts, tolerance).firstOrNull() ?: return false
        if (cells.count { it.isNotBlank() } >= MIN_TABLE_ROWS) return true
        return row.words.any { word -> cuts.any { abs(it - word.left) <= tolerance } }
    }

    /** 这一段里有多少行的字正好落在这条边界上。 */
    private fun List<Row>.alignsTo(at: Float, tolerance: Float): Int =
        count { row -> row.words.any { abs(it.left - at) <= tolerance } }

    /** 有没有字**跨过**这条线：跨过去说明纸上是连着的一句话，不是两列之间。 */
    private fun Row.crosses(at: Float, tolerance: Float): Boolean =
        words.any { it.left + tolerance < at && it.right - tolerance > at }

    /**
     * 同一格里换行不成新行：这一格只有唯一一处有字、上一行那一格也有字、且这行贴得比一个行距还近 ——
     * 那是上一格的内容接着排，并回去（用换行连着，写成 CSV 会自动加引号）。
     *
     * "贴得比行距近"是从这段表自己量出来的行距推的，不写死磅数：同一份文件里密表与疏表的行距能差四五倍。
     */
    private fun mergeWrappedCells(body: List<Row>, grid: List<List<String>>): List<List<String>> {
        if (body.size < 2) return grid
        val pitch = medianPitch(body)
        val out = ArrayList<List<String>>()
        body.forEachIndexed { index, row ->
            val cells = grid[index]
            val filled = cells.indices.filter { cells[it].isNotBlank() }
            val previous = out.lastOrNull()
            val step = if (index == 0) Float.MAX_VALUE else row.top - body[index - 1].top
            if (filled.size == 1 && previous != null && step < pitch && previous[filled.single()].isNotBlank()) {
                val slot = filled.single()
                val merged = ArrayList(previous)
                merged[slot] = "${merged[slot]}\n${cells[slot]}"
                out[out.lastIndex] = merged
            } else {
                out += cells
            }
        }
        return out
    }

    /** 这一段行的行距中位数（相邻两行顶边之差）。 */
    private fun medianPitch(body: List<Row>): Float {
        val steps = ArrayList<Float>()
        for (index in 1 until body.size) {
            val step = body[index].top - body[index - 1].top
            if (step > 0f) steps.add(step)
        }
        steps.sort()
        return if (steps.isEmpty()) 0f else steps[steps.size / 2]
    }

    /** 按顶边成行：差在行高 6 成以内算同一行（同一行的字基线必然挨在一起）。 */
    internal fun rowsOf(words: List<PdfWord>): List<Row> {
        val out = ArrayList<Row>()
        words.sortedBy { it.top }.forEach { word ->
            val last = out.lastOrNull()
            val tol = max(1.5f, (last?.let { (it.bottom - it.top) * 0.6f } ?: 0f))
            if (last != null && abs(word.top - last.top) <= tol) {
                out[out.size - 1] = Row(last.top, max(last.bottom, word.bottom), last.words + word)
            } else {
                out += Row(word.top, word.bottom, listOf(word))
            }
        }
        return out.map { row -> Row(row.top, row.bottom, row.words.sortedBy { it.left }) }
    }

    /**
     * 列边界候选：列的**起点**在多行里对齐的那些位置。
     *
     * 用起点而不是空隙中点 —— 中点会被格子内容长短拖着走（同一列这行放"台灯"、那行放"机械键盘"，
     * 中点就差出去十几 pt），真实表里几乎聚不起来；起点对齐才是"这是一列"的稳定信号。
     *
     * 这里只出**候选**，不判"这一页是不是表"：一条线上有字跨过去是常事（同一页另有整句正文），
     * 一刀切地把整页否掉会把真表也杀了。跨过与对齐的行数怎么比，留到每段表里单独判（见 [tablesOf]）。
     */
    internal fun columnCuts(rows: List<Row>, tolerance: Float, minRows: Int = 2): List<Float> {
        val clusters = ArrayList<Pair<Int, Float>>()      // 出现次数, 起点（取这一簇的平均）
        rows.forEach { row ->
            val seen = HashSet<Int>()
            val minGap = MIN_GAP_RATIO * (row.bottom - row.top)
            val words = row.words.sortedBy { it.left }
            // 每行第一个词是整个表的左边界，不是列分隔；起点前那段空隙太窄的是字距，也不算列分隔
            words.drop(1).mapIndexedNotNull { index, word ->
                if (word.left - words[index].right >= minGap) word.left else null
            }.forEach { at ->
                val slot = clusters.indexOfFirst { abs(it.second - at) <= tolerance }
                if (slot < 0) {
                    clusters += 1 to at
                } else if (seen.add(slot)) {
                    val (count, average) = clusters[slot]
                    clusters[slot] = (count + 1) to (average * count + at) / (count + 1)
                }
            }
        }
        return clusters.filter { (count, at) ->
            count >= minRows && rows.count { it.crosses(at, tolerance) } < count
        }.map { it.second }.sorted()
    }

    /** 按边界把行切成格子：落在同一对边界之间的词并成一格。 */
    internal fun gridOf(rows: List<Row>, boundaries: List<Float>, tolerance: Float): List<List<String>> {
        if (boundaries.isEmpty()) return emptyList()
        return rows.sortedBy { it.top }.map { row ->
            ArrayList<String>().apply {
                repeat(boundaries.size + 1) { add("") }
                row.words.sortedBy { it.left }.forEach { word ->
                    val slot = boundaries.count { it <= word.left + tolerance }
                    this[slot] = if (this[slot].isBlank()) word.text else "${this[slot]} ${word.text}"
                }
            }
        }
    }

    private fun medianRowHeight(rows: List<Row>): Float {
        val heights = rows.map { it.bottom - it.top }.filter { it > 0f }.sorted()
        return if (heights.isEmpty()) 10f else heights[heights.size / 2]
    }

}

/** 一张合成好的表：格子内容 + 它是从哪几页来的。 */
class TableSheet(val name: String, val rows: List<List<String>>, val pages: List<Int>)

/**
 * 把一页一页认出来的表合成"几个表"（写成 Excel 就是几个工作表，写成 CSV 就一张一个文件）。
 *
 * 只在**证据齐**的时候才接：相邻两页、各自那一页上只有它一张表、列数一样 —— 这才敢当同一张表
 * 跨页排下来。列数不同或同一页上有两张的，一律分开，不猜。
 *
 * 跨页表头是纸上表格的常规（每页顶上重印一行栏目名），所以接的时候若下一张的第一行与这一张的
 * 第一行**逐格相同**，只留一次；不相同就原样接上（那是数据，不是表头）。
 */
object PdfTableSheets {

    class Reading(val sheets: List<TableSheet>, val notes: List<String>)

    fun from(detect: PdfTableDetect.Result): Reading {
        val notes = ArrayList(detect.notes)
        val tables = detect.tables.sortedWith(compareBy({ it.page }, { it.top }))
        val sheets = ArrayList<TableSheet>()
        var cursor = 0
        while (cursor < tables.size) {
            val head = tables[cursor]
            val rows = ArrayList(head.rows)
            val pages = ArrayList(listOf(head.page))
            var next = cursor + 1
            while (next < tables.size) {
                val previous = tables[next - 1]
                val candidate = tables[next]
                val aloneOnItsPage = tables.count { it.page == candidate.page } == 1 &&
                    tables.count { it.page == previous.page } == 1
                if (!aloneOnItsPage || candidate.page != previous.page + 1 ||
                    candidate.rows.first().size != rows.first().size
                ) break
                val repeatedHeader = candidate.rows.first() == rows.first()
                if (repeatedHeader) {
                    notes += "第 ${candidate.page + 1} 页顶上那一行与第 ${previous.page + 1} 页的表头逐格相同，接过来时只留一次"
                }
                rows += if (repeatedHeader) candidate.rows.drop(1) else candidate.rows
                pages += candidate.page
                next++
            }
            sheets += TableSheet("表 ${sheets.size + 1}", rows, pages)
            cursor = next
        }
        tables.groupBy { it.page }.filter { it.value.size > 1 }.forEach { (page, group) ->
            notes += "第 ${page + 1} 页上有 ${group.size} 张表，各算一张，没并成一张"
        }
        return Reading(sheets, notes)
    }
}
