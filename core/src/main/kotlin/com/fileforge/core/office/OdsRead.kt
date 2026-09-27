package com.fileforge.core.office

import org.w3c.dom.Element
import org.w3c.dom.Node

/** 一张表：名字、首行是不是表头、以及摊平后的格子。 */
class OdsSheet(val name: String, val header: Boolean, val rows: List<List<String>>)

/** 读出来的一本工作簿，加上"有什么没搬/怎么搬的"的交代。 */
class OdsBook(val sheets: List<OdsSheet>, val notes: List<String>)

/**
 * OpenDocument 电子表格（.ods 的 `content.xml`）→ 一张一张的表。
 *
 * 与 xlsx 那边是同一件事的两种写法，但两处都不一样，所以各一份：
 *  - xlsx 把值存成 `v` + 样式里的格式码，日期是"序列号 + 格式码像日期"；
 *    ODS 直接写着 `office:value-type="date"` 与 `office:date-value="2023-05-01"`，不用猜
 *  - xlsx 的稀疏格子靠引用（`A12`）对位；ODS 没有引用，靠**重复计数**：
 *    `table:number-columns-repeated` 是"这一格往右再来几份"，`number-rows-repeated` 同理。
 *    不展开就少列少行，展开完还要把尾巴上整排空格剪掉 —— LibreOffice 会把没用到的
 *    那一万行写成几条 `repeated` 空行，不剪就是每张表都一万行
 *  - 合并的格子（`number-columns-spanned` 与 `covered-table-cell`）要占位，否则整张表读歪
 *
 * 取值只有一条主张：**搬你在表格里看见的那份写法**。
 * 有渲染出来的文字（`text:p`，LibreOffice 每次都写）就用它，`12.345%` 不会变成 `0.12345`；
 * 没有才退回 `office:*-value` 的那个原文（并按笔数说明退了几处）。
 * 公式（`table:formula`）搬的是文件里存着的算过的结果，不重算。
 */
object OdsRead {

    private const val CONTENT = "content.xml"

    /** 展开出来的行数上限：正常表几千行，超了多半是把整页空行也算进来了。 */
    private const val MAX_ROWS = 20000

    /** 单个重复计数的上限：LibreOffice 真会写 repeated="1048576"（整页），按上限截。 */
    private const val MAX_REPEAT = 4096

    fun read(load: (String) -> ByteArray?): OdsBook {
        val content = load(CONTENT)
            ?: throw IllegalArgumentException("这份 ODS 里没有 content.xml，表格不在这里")
        val tally = OdsTally()
        val root = OoxmlXml.root(content, "OpenDocument")
        val body = firstDescendant(root, "spreadsheet")
            ?: throw IllegalArgumentException("content.xml 里没有 office:spreadsheet，这本工作簿是空的")
        val sheets = ArrayList<OdsSheet>()
        val used = HashSet<String>()
        children(body) { node ->
            val element = node as? Element ?: return@children
            if (localName(element) != "table") return@children
            val made = table(element, tally) ?: return@children
            val name = uniqueName(odsName(element, sheets.size), used)
            sheets += OdsSheet(name, made.first, made.second)
        }
        require(sheets.isNotEmpty()) { "这份 ODS 里一张表都没有" }
        val losses = tally.losses()
        return OdsBook(sheets, losses)
    }

    /** 表名撞上时编号，不互相盖掉（与 CSV 摊表、xlsx 表名那套同一条规矩）。 */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        var index = 2
        while (!used.add("$name-$index")) index++
        return "$name-$index"
    }

    private fun odsName(element: Element, fallbackIndex: Int): String {
        val raw = attr(element, "table:name") ?: attr(element, "name") ?: ""
        return raw.trim().ifEmpty { "表${fallbackIndex + 1}" }
    }

    /** 一张 `table:table` → (首行是不是表头, 摊开的格子)。表名由调用方按包里说的取。 */
    private fun table(element: Element, tally: OdsTally): Pair<Boolean, List<List<String>>>? {
        val rows = ArrayList<List<String>>()
        var header = false
        children(element) { node ->
            val child = node as? Element ?: return@children
            when (localName(child)) {
                "table-row" -> row(child, rows, tally)
                "table-header-rows" -> {
                    header = true
                    children(child) { inner ->
                        val made = inner as? Element ?: return@children
                        if (localName(made) == "table-row") row(made, rows, tally)
                    }
                }
                else -> Unit
            }
        }
        val kept = trimTrailingBlank(rows, tally)
        if (kept.isEmpty()) return null
        return header to kept
    }

    /**
     * 一行。
     *
     * `table:number-rows-repeated` 展开后可能上万行，所以有上限；
     * 展开完的空行留着也没意义（`trimTrailingBlank` 会剪），这里先照字面摊开。
     */
    private fun row(element: Element, out: ArrayList<List<String>>, tally: OdsTally) {
        val cells = ArrayList<String>()
        var coveredLeft = 0
        children(element) { node ->
            val cell = node as? Element ?: return@children
            when (localName(cell)) {
                "table-cell" -> cell(cell, cells, tally).also { coveredLeft = it }
                "covered-table-cell" -> if (coveredLeft-- > 0) Unit else cells += ""
                else -> Unit
            }
        }
        var times = attr(element, "table:number-rows-repeated")?.toIntOrNull() ?: 1
        if (times > MAX_REPEAT) {
            tally.bump("huge")
            times = MAX_REPEAT
        }
        repeat(times.coerceAtLeast(1)) {
            if (out.size >= MAX_ROWS) {
                tally.bump("huge")
                return@repeat
            }
            out += cells
        }
    }

    /**
     * 一个格子写进 `out` 几份，返回"它还该吃掉几个后面的 covered 格子"。
     *
     * 合并这件事 ODF 有**两种写法**，而且真文件会两种一起写：LibreOffice 在被并掉的位置上放
     * `table:covered-table-cell`，pandoc 那类写者只给前一格加 `number-columns-spanned`。
     * 两种都照字面各占一格的话，一次合并被数了两回，整行往右错位 ——
     * 所以 spanned 补出来的那几格要留给紧跟其后的 covered 格子。
     */
    private fun cell(element: Element, out: ArrayList<String>, tally: OdsTally): Int {
        val text = value(element, tally)
        if (attr(element, "table:formula")?.isNotBlank() == true) tally.bump("formula")
        val spanned = (attr(element, "table:number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var repeated = attr(element, "table:number-columns-repeated")?.toIntOrNull() ?: 1
        if (repeated > MAX_REPEAT) {
            tally.bump("huge")
            repeated = MAX_REPEAT
        }
        repeat(repeated.coerceAtLeast(1)) {
            out += text
            repeat(spanned - 1) { out += "" }
        }
        return (spanned - 1) * repeated.coerceAtLeast(1)
    }

    /**
     * 一个格子的文字。
     *
     * 先取渲染出来的那份（多段合成一格，段间换行留着）；没有才按 `office:value-type`
     * 退回文件里存的那个原文 —— 退了几处要说明，因为退回来的是 `0.12345` 而不是 `12.345%`。
     */
    private fun value(element: Element, tally: OdsTally): String {
        val paragraphs = ArrayList<String>()
        children(element) { node ->
            val child = node as? Element ?: return@children
            if (localName(child) == "p" || localName(child) == "h") {
                paragraphs += inline(child)
            }
        }
        val rendered = paragraphs.joinToString("\n")
        if (rendered.isNotEmpty()) return rendered
        val type = attr(element, "office:value-type").orEmpty()
        if (type.isEmpty() || type == "string") return ""
        val stored = when (type) {
            "float", "double", "percentage", "currency" -> attr(element, "office:value")
            "date" -> attr(element, "office:date-value")
            "time" -> attr(element, "office:time-value")
            "boolean" -> attr(element, "office:boolean-value")
            else -> null
        }
        if (stored == null) {
            tally.bump("valueMissing")
            return ""
        }
        tally.bump("unrendered")
        return stored
    }

    /** 段内的字：文字照字面，`text:s` 按个数补空格，`text:tab` 成制表，`text:line-break` 成换行。 */
    private fun inline(element: Element): String {
        val out = StringBuilder()
        val kids = element.childNodes
        for (index in 0 until kids.length) {
            val node = kids.item(index)
            if (node.nodeType == Node.TEXT_NODE) {
                out.append(node.textContent)
                continue
            }
            val child = node as? Element ?: continue
            when (localName(child)) {
                "s" -> out.append(" ".repeat((attr(child, "text:c")?.toIntOrNull() ?: 1).coerceIn(1, 999)))
                "tab" -> out.append('\t')
                "line-break" -> out.append('\n')
                "a", "span" -> out.append(inline(child))
                "note", "annotation" -> Unit
                else -> out.append(inline(child))
            }
        }
        return out.toString()
    }

    /**
     * 剪掉尾巴上整排空行。
     *
     * ODS 的网格是"整页"的：LibreOffice 把没用到的行写成几条 `number-rows-repeated` 的空行，
     * 不剪的话每张表都是几千行空格，导出来的 CSV 也就是几千行逗号。
     * 只剪**尾巴**：中间空的那几行是稿子真空着，写出去要留着。
     */
    private fun trimTrailingBlank(rows: List<List<String>>, tally: OdsTally): List<List<String>> {
        var end = rows.size
        while (end > 0 && rows[end - 1].all { it.isEmpty() }) end--
        if (end < rows.size) tally.bump("trimmed")
        return rows.subList(0, end).map { row -> row.stripTrailingBlanks() }
    }

    /** 每行也剪掉尾巴上的空格子：一整列没人填时不必把逗号写到底。 */
    private fun List<String>.stripTrailingBlanks(): List<String> {
        var end = size
        while (end > 0 && this[end - 1].isEmpty()) end--
        return subList(0, end).toList()
    }

    private fun firstDescendant(node: Node, local: String): Element? {
        val kids = node.childNodes
        for (index in 0 until kids.length) {
            val child = kids.item(index)
            if (child !is Element) continue
            if (localName(child) == local) return child
            firstDescendant(child, local)?.let { return it }
        }
        return null
    }

    private fun attr(element: Element, name: String): String? {
        element.getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
        val tail = name.substringAfter(':')
        val map = element.attributes
        for (index in 0 until map.length) {
            val item = map.item(index)
            if (item.nodeName.substringAfterLast(':') == tail) return item.nodeValue.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun children(node: Node, each: (Node) -> Unit) {
        val kids = node.childNodes
        for (index in 0 until kids.length) each(kids.item(index))
    }

    private fun localName(node: Node): String = OoxmlStructure.localName(node)
}

/** 数"有什么没搬"用的计数表。 */
private class OdsTally {
    private val counts = HashMap<String, Int>()

    fun bump(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    operator fun get(name: String): Int = counts[name] ?: 0

    fun losses(): List<String> {
        val list = ArrayList<String>()
        fun say(count: Int, write: (Int) -> String) {
            if (count > 0) list += write(count)
        }
        say(get("formula")) { "$it 格是公式，搬的是文件里存着的算过的结果（不重算）" }
        say(get("unrendered")) { "$it 格没有渲染出来的文字，按文件里存的数值原文搬（百分比与日期也就没有 % 与格式）" }
        say(get("valueMissing")) { "$it 格说自己是数值却没存值，给了空格" }
        say(get("trimmed")) { "尾巴上 $it 行整行空着（ODF 把没用到的整页写成重复空行），剪掉了" }
        say(get("huge")) { "$it 处重复计数大得不合理，按 4096 个截断" }
        return list
    }
}
