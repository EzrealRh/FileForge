package com.fileforge.core.pdf

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRun
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF 里抽出来的**一行**：字，加上这行在纸上的样子。
 *
 * PDF 文件里没有"这是标题""这是列表"这一层 —— 那只是打印时画出来的样子。
 * 真能读出来的只有字号、用的是哪一种字、左右上下的位置，所以结构是从这些量**推**出来的
 * （阈值是 `tools/measure_pdf_fonts.py` 量真文件量的，见 [PdfDoc] 的注释）。
 *
 * `bold` 要的是**按字体名**判的重面，不是 `/Flags` 里那个 Bold 位 —— 中文文件里宋体被标成
 * 粗体的能占到八九成，照标志位判会把每一行短的都变成标题。
 */
class PdfLine(
    val text: String,
    val size: Float,
    val bold: Boolean = false,
    val left: Float = 0f,
    val top: Float = 0f,
    val page: Int = 0,
    val height: Float = 0f,
    val right: Float = 0f,
)

/**
 * 一行行的文字 → [com.fileforge.core.doc.Doc]：把排版画出来的样子还原成文档结构。
 *
 * 下面几个阈值不是拍的，是 `python tools/measure_pdf_fonts.py <真 PDF 若干>` 量出来的
 * （中文讲义、实验报告、简历、英文期刊两栏各份的字号档位分布那条命令能重跑）。
 * 量出来最要紧的三条：
 *  - 正文里夹的拉丁文常比中文大 0.5pt，**占的字数能到三成** —— 光"比正文大"不够，还得这一档
 *    占比小才判标题；行内取最大字号，这一档才反过来不会把正文抬成基准
 *  - 整份用重面字（黑体 / 微软雅黑）排的稿子行行带粗 —— "只粗没变大"这条要按**整份的重面字占比**门控
 *  - 一行只有一个字符的大字号多是公式上下标与页码，不是标题（判标题要看这一档的行平均多长）
 *
 * 另有两条与字号无关，各挡一类真实的丑：跨页重复的行当页眉页脚删掉（不删的话每页多一行"第 3 页"
 * 混进正文）；行距比例当折行信号（不并段的话 Word 里一行一段，段后距叠出一屏空白）。
 */
object PdfDoc {

    /** 字号按半磅归档：同一档字在不同字体下报出来的浮点数会差出 0.0x。 */
    private const val SIZE_BUCKET = 0.5f

    /** 比正文大出 8% 才算候选。量到的真标题最小差距是 11.5→13.5pt；12.0pt 那档是正文里的拉丁文。 */
    private const val HEADING_RATIO = 1.08f

    /** 这一档的字最多占多少比例还算标题：量到的真标题在 0.1%~3%，正文里的拉丁文档占 10%~32%。 */
    private const val MAX_HEADING_SHARE = 0.12f

    /** 这一档的行平均至少这么长才算标题（一两个字符的大字号是公式、上下标与页码）。 */
    private const val MIN_HEADING_CHARS = 3

    /** 整份文件里重面字占到这个比例以上，"粗"就不再是标题信号 —— 那就是这份文件的正文字体。 */
    private const val HEAVY_BODY_SHARE = 0.5f

    /** 只粗没变大的短行按最低一级标题排；整段加粗是强调，长过这个字数就不算标题。 */
    private const val BOLD_MAX_CHARS = 30

    /** 两行起点的距离不超过 1.9 倍字号，是同一自然行被折开的，不是两段。 */
    private const val LEADING_RATIO = 1.9f

    /** 同一句话至少在这么多页的同一个高度上出现过，才敢判页眉页脚。 */
    private const val FURNITURE_MIN_PAGES = 3

    /** 还要占到有字页数的这个比例：二十页里出现两次不算常客，出现十三页才算。 */
    private const val FURNITURE_RATIO = 0.6f

    /** 纸的上/下这条带子里才算页眉页脚（页码与"第 N 页"都印在这儿）。 */
    private const val BAND = 0.12f

    /** 行的一半以上空着（或从一半以后才开始），说明这一页左右各摆了一块。 */
    private const val SPLIT_START = 0.45f

    /** 一页里这样判的行够几条才算多栏。 */
    private const val SPLIT_MIN_LINES = 3

    /** 一个缩进层按两个字宽算（中文的规矩是空两格）。 */
    private const val INDENT_CHARS = 2f

    private const val MAX_LEVEL = 6
    private const val MAX_INDENT = 8

    private val BULLET = Regex("""^\s*([•‣▪▫◦●○■□·∙]\s*|[-*][ \t　]+)""")

    // 编号只认到三位数：`2019.` 这种以年份开头的是正文不是列表
    private val NUMBERED = Regex(
        """^\s*(\(?\d{1,3}[.)、][ \t　]+|[（(]\s*\d{1,3}\s*[)）][ \t　]*|[一二三四五六七八九十]{1,3}[、.)][ \t　]*)""",
    )

    /** 句号结尾的加粗短行是"一句话被加粗"，不是标题。 */
    private val SENTENCE_END = Regex("""[。！？!?…]["'”」』]?$""")

    private class Shape(
        val style: String,
        val indent: Int = 0,
        val bullet: Boolean? = null,
        val boldOnly: Boolean = false,
    )

    /** 这份文件的"尺子"：正文基准、左边缘、能当标题的字号档，以及"粗"还作不作数。 */
    private class Ruler(
        val base: Float,
        val margin: Float,
        val levels: List<Int>,
        val heavyHeadings: Boolean,
    )

    fun toDoc(lines: List<PdfLine>): Doc {
        val notes = ArrayList<String>()
        val present = lines.map {
            PdfLine(scrub(it.text), it.size, it.bold, it.left, it.top, it.page, it.height,
                if (it.right > 0f) it.right else it.left + it.size * it.text.length)
        }
            .filter { it.text.isNotBlank() }
        if (present.isEmpty()) {
            return Doc(emptyList(), listOf("这份 PDF 没抽出可用的文字（扫描件的字是画上去的，没有文字层）"))
        }

        val pages = present.map { it.page }.distinct().size
        val furniture = furnitureKeys(present, pages)
        val body = present.filter { keyOf(it) !in furniture }
        if (body.isEmpty()) return Doc(emptyList(), listOf("抽出来的行全在页眉页脚那一类里，没剩下正文"))

        val base = bodyFontSize(body)
        val ruler = Ruler(base, leftMargin(body, base), headingLevels(body, base), heavyShare(body) < HEAVY_BODY_SHARE)
        val parts = ArrayList<DocPart>()
        var boldHeads = 0
        var bullets = 0
        var numbers = 0
        var wrapped = 0
        var hyphenated = 0

        var index = 0
        while (index < body.size) {
            val line = body[index]
            val shape = shapeOf(line, ruler)
            index++
            if (shape == null) {
                // 普通段落：把后面被折开的行并进同一段，直到遇到空档、缩进变了或下一块的开头
                var text = line.text.trim()
                var last = line
                while (index < body.size) {
                    val next = body[index]
                    if (shapeOf(next, ruler) != null) break
                    // 翻页不并段：两页的 top 各算各的，跨页比距离没有意义（宁可多一段，不要把两页的话接成一句）
                    if (next.page != last.page) break
                    if (next.top - last.top > LEADING_RATIO * max(last.size, next.size)) break
                    if (abs(next.left - last.left) > max(2f, last.size)) break
                    if (abs(next.size - last.size) > SIZE_BUCKET || next.bold != last.bold) break
                    val piece = next.text.trim()
                    val joined = joinWrapped(text, piece)
                    if (joined.length < text.length + piece.length) hyphenated++ else wrapped++
                    text = joined
                    last = next
                    index++
                }
                val indent = indentOf(line.left, ruler.margin, ruler.base)
                parts += DocParagraph(DocPara(listOf(DocRun(text, bold = line.bold)), "Body", indent))
                continue
            }
            if (shape.boldOnly) boldHeads++
            if (shape.bullet == true) bullets++
            if (shape.bullet == false) numbers++
            val text = if (shape.bullet == null) line.text.trim() else stripMarker(line.text)
            parts += DocParagraph(DocPara(listOf(DocRun(text)), shape.style, shape.indent, shape.bullet))
        }

        if (parts.isEmpty()) notes += "抽出的文字一段都没排出来（每页只有几个字？那就是扫描件）"
        val dropped = present.size - body.size
        if (dropped > 0) notes += "$dropped 行跨页重复（页眉页脚那一类），没当正文搬"
        if (wrapped > 0) notes += "$wrapped 处被折开的行并回一段（PDF 里的换行是排版，不是段落）"
        if (hyphenated > 0) notes += "$hyphenated 处行尾的连字符是真断词，拼回去时把横线去掉了"
        if (bullets > 0) notes += "$bullets 行开头的圆点写成 Word 的列表，记号由 Word 画"
        if (numbers > 0) notes += "$numbers 行开头的编号写成 Word 的编号列表 —— 序号由 Word 从 1 重画，原来从几起头搬不过去"
        if (boldHeads > 0) notes += "$boldHeads 处只加粗没变大的短行按最低一级标题排"
        if (!ruler.heavyHeadings) notes += "这份文件用的字本身就偏重（黑体那一类），没按粗细分标题"
        val columns = splitPages(body)
        if (columns > 0) notes += "$columns 页看着像多栏：字是按从上到下扫出来的顺序排的，没按栏拼回去"
        notes += "正文按 ${points(base)}pt 定基准，比它大 ${percent(HEADING_RATIO)}% 以上、又只占少数的那些字号按大小排成标题层级"
        return Doc(parts, notes)
    }

    /** 字号分档（半磅一档）下按字数加权的众数。并列时取小的那档，宁可把基准定低。 */
    private fun bodyFontSize(lines: List<PdfLine>): Float {
        val chars = HashMap<Int, Int>()
        lines.forEach { line ->
            val bucket = bucket(line.size)
            chars[bucket] = (chars[bucket] ?: 0) + weight(line.text)
        }
        val best = chars.entries.sortedBy { it.key }.maxByOrNull { it.value } ?: return 0f
        return best.key * SIZE_BUCKET
    }

    /** 正文的左边缘：只拿与正文档位大小相近的行取众数（标题与列表标记会往左探出）。 */
    private fun leftMargin(lines: List<PdfLine>, base: Float): Float {
        val pool = lines.filter { abs(bucket(it.size) - bucket(base)) <= 2 }.ifEmpty { lines }
        if (pool.isEmpty()) return 0f
        val counts = HashMap<Int, Int>()
        pool.forEach { line ->
            val bucket = (line.left / 2f).roundToInt()
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }
        val best = counts.entries.sortedBy { it.key }.maxByOrNull { it.value } ?: return 0f
        return best.key * 2f
    }

    /**
     * 能当标题的字号档，从大到小排（第一个最大的就是一级标题）。
     *
     * 两道门都是量出来的：这一档占的字数要少（正文里夹的拉丁文能占三成），
     * 这一档的行要平均有几个字（一个大字符是公式不是标题）。
     */
    private fun headingLevels(lines: List<PdfLine>, base: Float): List<Int> {
        if (base <= 0f) return emptyList()
        val total = lines.sumOf { weight(it.text) }
        if (total <= 0) return emptyList()
        val chars = HashMap<Int, Int>()
        val lengths = HashMap<Int, MutableList<Int>>()
        lines.forEach { line ->
            val bucket = bucket(line.size)
            val size = weight(line.text)
            chars[bucket] = (chars[bucket] ?: 0) + size
            lengths.getOrPut(bucket) { ArrayList() }.add(size)
        }
        return chars.keys.filter { bucket ->
            val rows = lengths.getValue(bucket)
            bucket * SIZE_BUCKET / base >= HEADING_RATIO &&
                (chars.getValue(bucket) <= total * MAX_HEADING_SHARE) &&
                median(rows) >= MIN_HEADING_CHARS
        }.sortedByDescending { it }
    }

    /** 这行是不是"另一块的开头"。返回 null 表示它接着上一段排 —— 段内的行没有自己的形状。 */
    private fun shapeOf(line: PdfLine, ruler: Ruler): Shape? {
        val text = line.text.trim()
        val ratio = if (ruler.base > 0f) line.size / ruler.base else 1f
        val level = ruler.levels.indexOf(bucket(line.size))
        if (level >= 0 && ratio >= HEADING_RATIO) return Shape(headingStyle(level + 1))
        val bullet = BULLET.findPrefix(line.text)
        val numbered = if (bullet == null) NUMBERED.findPrefix(line.text) else null
        if ((bullet != null || numbered != null) && abs(bucket(line.size) - bucket(ruler.base)) <= 1) {
            return Shape("ListParagraph", indentOf(line.left, ruler.margin, ruler.base), bullet != null)
        }
        if (ruler.heavyHeadings && line.bold && ratio < HEADING_RATIO && ratio >= 0.9f &&
            text.length <= BOLD_MAX_CHARS && !SENTENCE_END.containsMatchIn(text)
        ) {
            return Shape(headingStyle(ruler.levels.size + 1), boldOnly = true)
        }
        return null
    }

    private fun headingStyle(level: Int) = "Heading${level.coerceIn(1, MAX_LEVEL)}"

    private fun indentOf(left: Float, margin: Float, base: Float): Int {
        if (base <= 0f) return 0
        val layers = (left - margin) / (base * INDENT_CHARS)
        return if (layers <= 0.4f) 0 else layers.roundToInt().coerceIn(0, MAX_INDENT)
    }

    private fun stripMarker(text: String): String {
        val marker = BULLET.findPrefix(text) ?: NUMBERED.findPrefix(text) ?: return text.trim()
        return text.substring(marker.range.last + 1).trim()
    }

    /** 中文直接接上，英文之间要补一个空格（PDF 里那个空格是行尾的空白，抽字时没了）。 */
    private fun joinWrapped(previous: String, next: String): String {
        if (previous.isEmpty()) return next
        if (next.isEmpty()) return previous
        val last = previous[previous.length - 1]
        val first = next[0]
        if (last == '-' && first.isLowerCase()) return previous.substring(0, previous.length - 1) + next
        // 只认 ASCII 的字母数字：Char.isLetterOrDigit 按 Unicode 判，"字"也算字母，
        // 那样每一行中文之间都会被塞进一个空格
        return if (last.isAsciiWord() && first.isAsciiWord()) "$previous $next" else previous + next
    }

    private fun furnitureKeys(lines: List<PdfLine>, pages: Int): Set<Pair<String, Int>> {
        if (pages < FURNITURE_MIN_PAGES) return emptySet()
        val threshold = max(FURNITURE_MIN_PAGES, (pages * FURNITURE_RATIO).roundToInt())
        val seen = HashMap<Pair<String, Int>, MutableSet<Int>>()
        lines.filter { inBand(it) }.forEach { line -> seen.getOrPut(keyOf(line)) { HashSet() }.add(line.page) }
        return seen.filter { it.value.size >= threshold }.keys.toSet()
    }

    /**
     * 页眉页脚只在纸的上下那条带子里。
     *
     * 只按"重复多少页"判会误伤：表单类文件每页中间都重复印着"姓名""联系电话"这些字段名，
     * 那是正文不是页眉（真跑一份十页的表单时它把 61 行里的 46 行当页眉删了）。
     * 量不到纸高时退回旧判据 —— 宁可少删，也不要不出活。
     */
    private fun inBand(line: PdfLine): Boolean {
        if (line.height <= 0f) return true
        return line.top < line.height * BAND || line.top > line.height * (1f - BAND)
    }

    /**
     * 有几页右边另起了一栏（同一页里 ≥3 行从纸面 45% 以后才开始）。
     *
     * 只看"从右边那半开始"这一条：短行提前收住太正常了（每段最后一行都是），
     * 而普通文档不会有一批行整批地从版面中间起头。
     * 这种版面里"从上到下扫"会把两栏的话交叉着读出来，重排成按栏读是下一步的事，
     * 但**不能不吭声**：一份"看着挺顺"的错位文档比一份说明了会错位的更难查。
     */
    private fun splitPages(lines: List<PdfLine>): Int {
        val from = lines.minOf { it.left }
        val span = (lines.maxOf { it.right } - from).coerceAtLeast(1f)
        val pages = HashMap<Int, Int>()
        lines.forEach { line ->
            if (line.left - from > span * SPLIT_START) pages[line.page] = (pages[line.page] ?: 0) + 1
        }
        return pages.values.count { it >= SPLIT_MIN_LINES }
    }

    /** 页眉页脚的指纹：同一个高度 + 把数字抹掉的字（"第 3 页"与"第 7 页"要算同一行）。 */
    private fun keyOf(line: PdfLine): Pair<String, Int> {
        val flat = line.text.trim().replace(Regex("\\s+"), " ")
            .map { if (it in '0'..'9') '#' else it }.joinToString("").take(60)
        return flat to (line.top / 4f).roundToInt()
    }

    private fun bucket(size: Float): Int = (size / SIZE_BUCKET).roundToInt()

    /**
     * 把行里的空白先归一再判断。
     *
     * PDF 里的"空格"不止一个字符：不间断空格（U+00A0，拉丁文词间常写成它）、
     * 软连字符（U+00AD，断词用的那个横线，ToUnicode 常就这么写），还有字体缺字时留下的控制字符。
     * 不归一的话，"这一行有几个字"和"行尾是不是断词"两条都会看走眼（真文件里就是这么写的）。
     */
    private fun scrub(text: String): String = text.replace('\u00a0', ' ').replace('\u00ad', '-')
        .map { if (it < ' ' && it != '\n') ' ' else it }.joinToString("")
        .replace(Regex(" {2,}"), " ").trim()

    private fun weight(text: String): Int = text.count { !it.isWhitespace() }

    private fun heavyShare(lines: List<PdfLine>): Float {
        val total = lines.sumOf { weight(it.text) }
        if (total <= 0) return 0f
        return lines.filter { it.bold }.sumOf { weight(it.text) }.toFloat() / total
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else if (sorted.isEmpty()) 0 else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun Char.isAsciiWord(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun points(value: Float): String =
        if (value % 1f == 0f) value.roundToInt().toString() else "%.1f".format(value)

    private fun percent(ratio: Float): String = ((ratio - 1f) * 100f).roundToInt().toString()

    private fun Regex.findPrefix(text: String): MatchResult? = find(text)?.takeIf { it.range.first == 0 }
}
