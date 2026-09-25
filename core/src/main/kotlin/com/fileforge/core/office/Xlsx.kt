package com.fileforge.core.office

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import org.w3c.dom.Element

/** 一张工作表：表名、行（每行补齐到同宽），以及"这张表里有什么没搬过来"。 */
class Sheet(val name: String, val rows: List<List<String>>, val notes: List<String>)

/** 工作簿里声明的一张表。part 为 null 表示声明了但包里找不到那份部件。 */
class SheetRef(val name: String, val part: String?)

/**
 * SpreadsheetML（.xlsx）读格子。
 *
 * 三条最容易出错的地方，都是"文件能打开但数错了"：
 *  1. **稀疏行**：`<c r="C3">` 前面可以没有 A、B 两格，按出现顺序读会把整行左移一格。
 *  2. **行号也不连续**：`<row r="7">` 可以跳过 4~6 行，空行要留位置，否则后面的表全错位。
 *  3. **日期是数字**：格子里存的是序列号，"这是日期"写在样式表里。不查样式就会转出 `45123`。
 *
 * 数字一律照文件里的写法原样搬，不重新格式化 —— `1.50` 与 `1.5` 在表格里是两回事。
 */
object Xlsx {
    const val SHARED_STRINGS = "xl/sharedStrings.xml"
    const val STYLES = "xl/styles.xml"

    /** Excel 自己的上限，也是防爆内存的线：超过就是文件坏了，不是我们要支持的表。 */
    private const val MAX_ROWS = 1_048_576
    private const val MAX_COLUMNS = 16_384

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** 表名与它对应的部件，顺序照工作簿自己声明的那样。 */
    fun sheets(load: (String) -> ByteArray?): List<SheetRef> {
        val main = load(OoxmlStructure.WORKBOOK) ?: return emptyList()
        val targets = load(OoxmlStructure.WORKBOOK_RELS)
            ?.let { OoxmlStructure.relationships(it, base = "xl") }.orEmpty()
        return OoxmlStructure.findAll(OoxmlXml.root(main), "sheet").mapIndexed { index, element ->
            val name = element.getAttribute("name").ifBlank { "第 ${index + 1} 张表" }
            SheetRef(name, OoxmlStructure.referenceId(element)?.let { targets[it] })
        }
    }

    /** 老 Mac Excel 的工作簿可以把 1904-01-01 当第 0 天，纪元是工作簿自己声明的。 */
    fun uses1904(workbook: ByteArray?): Boolean = workbook != null &&
        OoxmlStructure.findAll(OoxmlXml.root(workbook), "workbookPr").any { onFlag(it.getAttribute("date1904")) }

    /** 共享字符串表：一个格子里的富文本（同格多种字体）拼成一段话。 */
    fun sharedStrings(part: ByteArray?): List<String> {
        if (part == null) return emptyList()
        return OoxmlStructure.findAll(OoxmlXml.root(part), "si").map { runText(it) }
    }

    /** cellXfs 里哪些下标的格式是日期 / 时间。 */
    fun dateStyles(part: ByteArray?): Set<Int> {
        if (part == null) return emptySet()
        val root = OoxmlXml.root(part)
        val custom = HashMap<Int, String>()
        OoxmlStructure.findAll(root, "numFmt").forEach { element ->
            val id = element.getAttribute("numFmtId").toIntOrNull()
            val code = element.getAttribute("formatCode")
            if (id != null && code.isNotBlank()) custom[id] = code
        }
        val cellXfs = OoxmlStructure.findAll(root, "cellXfs").firstOrNull() ?: return emptySet()
        val out = LinkedHashSet<Int>()
        OoxmlStructure.childrenOf(cellXfs, "xf").forEachIndexed { index, element ->
            val id = element.getAttribute("numFmtId").toIntOrNull() ?: 0
            if (isDateFormat(custom[id].orEmpty()) || id in BUILTIN_DATE_FORMATS) out += index
        }
        return out
    }

    /** 一张表读成二维文字。 */
    fun sheet(
        name: String,
        part: ByteArray,
        strings: List<String>,
        dateStyleIndexes: Set<Int>,
        epoch1904: Boolean = false,
    ): Sheet {
        val data = OoxmlStructure.findAll(OoxmlXml.root(part), "sheetData").firstOrNull()
            ?: return Sheet(name, emptyList(), listOf("这份部件里没有 sheetData，读不出格子"))
        val notes = ArrayList<String>()
        val rows = ArrayList<List<String>>()
        val issues = Issues()
        var clipped = 0
        OoxmlStructure.childrenOf(data, "row").forEach { row ->
            val at = row.getAttribute("r").toIntOrNull() ?: (rows.size + 1)
            require(at in 1..MAX_ROWS) { "第 $at 行超出 Excel 的行数上限，这份文件坏了" }
            while (rows.size < at - 1) rows.add(emptyList())               // 跳过的行号留成空行
            val cells = ArrayList<Pair<Int, String>>()
            OoxmlStructure.childrenOf(row, "c").forEach { cell ->
                val ref = cell.getAttribute("r")
                val column = if (ref.isNotBlank()) columnOf(ref) else cells.size
                if (column >= MAX_COLUMNS) {
                    clipped++
                } else {
                    cells += column to cellText(cell, strings, dateStyleIndexes, epoch1904, issues)
                }
            }
            val values = MutableList((cells.maxOfOrNull { it.first } ?: -1) + 1) { "" }
            cells.forEach { (column, text) -> values[column] = text }
            rows.add(values)
        }
        if (issues.noResult > 0) notes += "${issues.noResult} 格是公式但没有算过的结果，转出来是空格"
        if (issues.badString > 0) notes += "${issues.badString} 格指向的共享文字在表里找不到，那些格按空处理"
        if (clipped > 0) notes += "$clipped 格的列号超出表格宽度上限，那些格丢掉了"

        var used = rows.size
        while (used > 0 && rows[used - 1].all { it.isEmpty() }) used--
        if (used < rows.size) notes += "尾部 ${rows.size - used} 行整行空白，去掉了"
        val body = rows.subList(0, used).toList()
        val width = body.maxOfOrNull { it.size } ?: 0
        val padded = body.count { it.size < width }
        if (padded > 0) notes += "$padded 行不足 $width 列，右边补了空格子"
        val square = body.map { row -> if (row.size == width) row else row + List(width - row.size) { "" } }
        return Sheet(name, square, notes)
    }

    /** 一个格子的文字：类型字母决定去哪儿取值。 */
    private fun cellText(
        cell: Element,
        strings: List<String>,
        dateStyleIndexes: Set<Int>,
        epoch1904: Boolean,
        issues: Issues,
    ): String {
        val type = cell.getAttribute("t").ifBlank { "n" }
        val raw = OoxmlStructure.childrenOf(cell, "v").firstOrNull()?.textContent?.trim().orEmpty()
        return when (type) {
            "s" -> raw.toIntOrNull()?.let { strings.getOrNull(it) } ?: "".also { issues.badString++ }
            "inlineStr" -> OoxmlStructure.childrenOf(cell, "is").firstOrNull()?.let { runText(it) }.orEmpty()
            "str", "e" -> raw                                 // 公式算过的结果、#N/A 这类错误文本，本身就是内容
            "b" -> when (raw) { "1" -> "TRUE"; "0" -> "FALSE"; else -> raw }
            else -> when {
                raw.isNotEmpty() ->
                    if ((cell.getAttribute("s").toIntOrNull() ?: -1) in dateStyleIndexes) serialToText(raw, epoch1904) else raw
                OoxmlStructure.childrenOf(cell, "f").isNotEmpty() -> {
                    issues.noResult++
                    ""
                }
                else -> ""                                    // 空格子
            }
        }
    }

    /** 读的过程中攒下来的毛病，最后翻成"这份表里有什么没搬过来"。 */
    private class Issues {
        var noResult = 0
        var badString = 0
    }

    /**
     * 序列号 → 日期 / 时间。
     *
     * 1900 系统里第 1 天是 1900-01-01，第 60 天是个不存在的 1900-02-29（微软当年为了让 Lotus
     * 的文件不错位留下的兼容坑）：那一段单独算，60 这一格照 Excel 的写法原样给出。
     */
    internal fun serialToText(raw: String, epoch1904: Boolean): String {
        val number = raw.toDoubleOrNull() ?: return raw
        var day = number.toLong()
        var seconds = Math.round((number - day) * 86_400)
        if (seconds >= 86_400) {
            day += seconds / 86_400
            seconds %= 86_400
        }
        if (day <= 0L) return if (seconds > 0) LocalTime.ofSecondOfDay(seconds).format(TIME) else raw
        val date = when {
            epoch1904 -> LocalDate.of(1904, 1, 1).plusDays(day)
            day in 1..59 -> LocalDate.of(1899, 12, 31).plusDays(day)
            day == 60L -> return withTime("1900-02-29", seconds)          // Excel 里那个不存在的日子
            else -> LocalDate.of(1899, 12, 30).plusDays(day)
        }
        return withTime(date.format(DAY), seconds)
    }

    private fun withTime(date: String, seconds: Long): String =
        if (seconds > 0) {
            "$date ${LocalTime.ofSecondOfDay(seconds).format(TIME)}"
        } else {
            date
        }

    /** `AC12` 这样的引用 → 列下标（0 起）。 */
    internal fun columnOf(ref: String): Int {
        var column = 0
        ref.forEach { ch ->
            val letter = ch.uppercaseChar()
            if (letter !in 'A'..'Z') return@forEach
            column = column * 26 + (letter - 'A' + 1)
        }
        return column - 1
    }

    /**
     * 自定义格式码像不像日期：先剥掉方括号段与引号里的字面文字，再看还剩不剩 y/m/d/h/s 这些码。
     * `"日期"` 是给人看的字面、`[红色]` 是颜色段，都不算格式码。
     */
    internal fun isDateFormat(code: String): Boolean {
        if (code.isBlank()) return false
        val bare = StringBuilder()
        var index = 0
        while (index < code.length) {
            val ch = code[index]
            when {
                // 段与字面文字要跳到**配对的**结束符：拿同一种字符去找会一路找到串尾
                ch == '[' || ch == '"' -> {
                    val closer = if (ch == '[') ']' else '"'
                    val close = code.indexOf(closer, index + 1)
                    index = if (close < 0) code.length else close + 1
                }
                ch == '\\' -> index += 2
                else -> {
                    bare.append(ch)
                    index++
                }
            }
        }
        return bare.any { it in "yYmMdDhHsS" }
    }

    /** 这棵子树里所有 `t` 叶子的文字（一个格子的富文本会有好几段）。 */
    private fun runText(node: Element): String =
        OoxmlStructure.findAll(node, "t").joinToString("") { it.textContent }

    private fun onFlag(value: String): Boolean = value == "1" || value.equals("true", ignoreCase = true)

    /** 规范内置的日期 / 时间格式号（0、39、44 这些是通用数值或文本格式，不在内）。 */
    private val BUILTIN_DATE_FORMATS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22,
        27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
        45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58,
    )
}
