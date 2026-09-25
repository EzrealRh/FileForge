package com.fileforge.core.doc

/** 渲染结果：文本，以及"哪些写法没被当成标记处理"。 */
class Rendered(val text: String, val notes: List<String>)

internal enum class Align { None, Left, Center, Right }

internal sealed interface Block

internal class Para(val lines: List<String>) : Block
internal class Head(val level: Int, val text: String) : Block
internal class Code(val info: String?, val lines: List<String>) : Block
internal class Quote(val blocks: List<Block>) : Block
internal class Hr : Block
internal class HtmlBlock(val lines: List<String>) : Block

/** 一个列表项 = 若干块（可以嵌段落、子列表）。checked 非空表示这是 GFM 任务列表的一项。 */
internal class ListItem(val blocks: List<Block>, val checked: Boolean?)

internal class ListBlock(val ordered: Boolean, val start: Int, val tight: Boolean, val items: List<ListItem>) : Block

/** GFM 表格：表头、分隔行给出的对齐、以及若干数据行。 */
internal class Table(val aligns: List<Align>, val header: List<String>, val rows: List<List<String>>) : Block

/**
 * 块级切分。
 *
 * 认的语法：围栏代码、ATX 与 Setext 标题、分割线、引用、有序/无序列表（可嵌套、可带子段落）、
 * GFM 表格、GFM 任务列表、段落、以及原样留下的 HTML 块。
 *
 * 一条原则贯穿到底：**认不出来的写法照字面留下**，绝不静悄悄吃掉 ——
 * 转换工具最坏的输出不是"没渲染"，而是"字少了"。
 */
internal object MarkdownBlocks {

    private val FENCE = Regex("""^( {0,3})(`{3,}|~{3,})[ \t]*(.*)$""")
    private val ATX = Regex("""^ {0,3}(#{1,6})(.*?)\s*$""")
    private val HR = Regex("""^ {0,3}([-*_])[ \t]*(\1[ \t]*){2,}$""")
    private val QUOTE = Regex("""^ {0,3}>[ \t]?(.*)$""")
    private val LIST = Regex("""^( *)([-*+]|\d{1,9}[.)])([ \t]+)(.*)$""")
    private val SETEXT = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
    // HTML 块的开头：整行只有这一个标签才算（CommonMark 的规矩）。
    // 不这么收就会把 `<https://x>` 这种自动链接当成 HTML 块，链接整个丢了。
    private val HTML_TAG_LINE = Regex("""^ {0,3}</?[a-zA-Z][a-zA-Z0-9-]*(?:\s[^<>]*)?/?>[ 	]*$""")
    private val HTML_OTHER = Regex("""^ {0,3}(?:<!--|<\?|<![A-Z]|!</)""")
    private val TASK = Regex("""^([ \t]*)\[([ \tXx])\][ \t]+""")
    private val TABLE_DELIM = Regex("""^ {0,3}\|?[ \t]*:?-{1,}:?[ \t]*(\|[ \t]*:?-{1,}:?[ \t]*)*\|?[ \t]*$""")

    fun parse(lines: List<String>): List<Block> {
        val blocks = ArrayList<Block>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.isBlank() -> index++
                FENCE.matches(line) -> {
                    val open = FENCE.find(line)!!.groupValues
                    val body = ArrayList<String>()
                    index++
                    while (index < lines.size && !isFenceClose(lines[index], open[2])) {
                        body += lines[index].drop(open[1].length)
                        index++
                    }
                    if (index < lines.size) index++                             // 收尾那行本身不算内容
                    blocks += Code(open[3].trim().substringBefore(' ').ifBlank { null }, body)
                }
                HR.matches(line) -> { blocks += Hr(); index++ }
                ATX.matches(line) -> {
                    val found = ATX.find(line)!!.groupValues
                    blocks += Head(found[1].length, found[2].trim().trimEnd('#').trimEnd())
                    index++
                }
                QUOTE.matches(line) -> {
                    val inner = ArrayList<String>()
                    while (index < lines.size &&
                        (QUOTE.matches(lines[index]) || (inner.isNotEmpty() && lines[index].isNotBlank() && !startsBlock(lines, index)))) {
                        inner += if (QUOTE.matches(lines[index])) QUOTE.find(lines[index])!!.groupValues[1] else lines[index]
                        index++
                    }
                    blocks += Quote(parse(inner))
                }
                LIST.matches(line) -> {
                    val (next, list) = list(lines, index)
                    if (list != null) blocks += list else blocks += Para(listOf(lines[index]))
                    index = next
                }
                isHtmlStart(line) -> {
                    val body = ArrayList<String>()
                    while (index < lines.size && lines[index].isNotBlank()) { body += lines[index]; index++ }
                    blocks += HtmlBlock(body)
                }
                isTable(lines, index) != null -> {
                    val (next, block) = table(lines, index)!!
                    blocks += block
                    index = next
                }
                else -> {
                    val body = ArrayList<String>()
                    while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines, index)) {
                        body += lines[index]
                        index++
                    }
                    // Setext 标题：下一行是 === 或 --- 才算
                    if (index < lines.size && body.isNotEmpty() && SETEXT.matches(lines[index])) {
                        blocks += Head(if (lines[index].trim().startsWith('=')) 1 else 2, body.joinToString(" ").trim())
                        index++
                    } else {
                        // 行首空白去掉，行尾空白要留住：行尾两个以上空格是硬换行的记号
                        blocks += Para(body.map { it.trimStart() })
                    }
                }
            }
        }
        return blocks
    }

    /** 这一段是不是 GFM 表格的开头：下一行必须是只有破折号与竖线的分隔行，且列数对得上。 */
    private fun isTable(lines: List<String>, index: Int): Table? {
        val head = lines.getOrNull(index) ?: return null
        val delim = lines.getOrNull(index + 1) ?: return null
        if (!head.contains('|') || !TABLE_DELIM.matches(delim)) return null
        val columns = splitRow(delim)
        if (columns.size < 2 || splitRow(head).size != columns.size) return null
        return Table(aligns(columns), splitRow(head), emptyList())
    }

    private fun table(lines: List<String>, from: Int): Pair<Int, Block>? {
        val probe = isTable(lines, from) ?: return null
        val rows = ArrayList<List<String>>()
        var index = from + 2
        while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
            rows += splitRow(lines[index])
            index++
        }
        return index to Table(probe.aligns, probe.header, rows)
    }

    private fun aligns(cells: List<String>): List<Align> = cells.map { cell ->
        val trimmed = cell.trim()
        val left = trimmed.startsWith(':')
        val right = trimmed.endsWith(':')
        when {
            left && right -> Align.Center
            right -> Align.Right
            left -> Align.Left
            else -> Align.None
        }
    }

    /** 拆一行格子：先去掉首尾那根可有可无的竖线，再按没被转义的竖线切。 */
    private fun splitRow(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith('|')) body = body.substring(1)
        if (body.endsWith("|") && !body.endsWith("\\|")) body = body.substring(0, body.length - 1)
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < body.length) {
            val ch = body[index]
            when {
                ch == '\\' && index + 1 < body.length -> { cell.append(ch).append(body[index + 1]); index += 2 }
                ch == '|' -> { cells += cell.toString().trim(); cell.setLength(0); index++ }
                else -> { cell.append(ch); index++ }
            }
        }
        cells += cell.toString().trim()
        return cells
    }


    private fun isHtmlStart(line: String): Boolean =
        HTML_TAG_LINE.matches(line) || HTML_OTHER.containsMatchIn(line)

    /** 段落能不能继续吃下一行：遇到别的块状开头就停。 */
    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (FENCE.matches(line) || ATX.matches(line) || HR.matches(line) || QUOTE.matches(line) || isHtmlStart(line)) return true
        if (SETEXT.matches(line) && index > 0 && lines[index - 1].isNotBlank()) return true
        return LIST.matches(line) || isTable(lines, index) != null
    }

    /** 收尾围栏：同样的字符、不短于开头、前面最多三个空格。 */
    private fun isFenceClose(line: String, marker: String): Boolean {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        return indent <= 3 && trimmed.isNotEmpty() && trimmed.all { it == marker.first() } && trimmed.length >= marker.length
    }

    private fun list(lines: List<String>, from: Int): Pair<Int, Block?> {
        val first = LIST.matchEntire(lines[from]) ?: return from + 1 to null
        val ordered = first.groupValues[2][0].isDigit()
        val startNumber = first.groupValues[2].filter { it.isDigit() }.toIntOrNull() ?: 1
        val baseIndent = first.groupValues[1].length
        val items = ArrayList<ListItem>()
        var index = from
        var loose = false
        while (index < lines.size) {
            val match = LIST.matchEntire(lines[index]) ?: break
            if (match.groupValues[2][0].isDigit() != ordered) break
            val indent = match.groupValues[1].length
            if (indent > baseIndent) break                                     // 更深一层是上一项的内容，不该在这里吃
            val marker = match.groupValues[2]
            val contentIndent = indent + marker.length + match.groupValues[3].length
            var text = match.groupValues[4]
            val checked = TASK.find(text)?.groupValues?.get(2)?.let { it != " " }
            if (checked != null) text = text.removeRange(TASK.find(text)!!.range.first, TASK.find(text)!!.range.last + 1)
            val body = ArrayList<String>()
            body += text
            index++
            while (index < lines.size) {
                val next = lines[index]
                if (next.isBlank()) {
                    var peek = index + 1
                    while (peek < lines.size && lines[peek].isBlank()) peek++
                    val after = lines.getOrNull(peek) ?: break
                    val nextIndent = after.length - after.trimStart().length
                    val item = LIST.matchEntire(after)
                    val sibling = item != null && item.groupValues[1].length == baseIndent &&
                        item.groupValues[2][0].isDigit() == ordered
                    when {
                        // 空行后还是这一项的内容（缩进够）：整表变松，段落并进这一项
                        nextIndent >= contentIndent -> { loose = true; body += ""; index = peek }
                        // 空行后是同级下一项：还是一个列表，只是整表变松
                        sibling -> { loose = true; index = peek }
                        else -> break
                    }
                    continue
                }
                val nextIndent = next.length - next.trimStart().length
                if (LIST.matches(next) && nextIndent < contentIndent) break
                body += if (nextIndent >= contentIndent) next.substring(contentIndent.coerceAtMost(next.length)) else next.trimStart()
                index++
            }
            items += ListItem(parse(body.dropWhile { it.isBlank() }), checked)
        }
        if (items.isEmpty()) return index to null
        return index to ListBlock(ordered, startNumber, !loose, items)
    }
}
