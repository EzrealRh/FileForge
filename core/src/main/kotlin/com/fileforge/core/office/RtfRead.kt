package com.fileforge.core.office

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import java.nio.charset.Charset

/** 读出来的一棵文档树（RTF 的段落顺序），加上"有什么没搬"的交代。 */
class RtfBody(val doc: Doc, val notes: List<String>)

/**
 * RTF（.rtf —— WordPad、老版 Word、不少文献与邮件导出用的那套）→ [Doc]。
 *
 * 为什么要能读它：转换工具站上 .rtf 是常青条目，而它看着像纯文本 —— 直接按文本转出去
 * 会得到一份满是 `\pard\plain\intbl` 的东西。那份稿子的粗体、标题、列表与表格全写在
 * 控制字里，不解析就等于把稿子改烂。
 *
 * 认的东西（参照物是 pandoc 的 RTF 读者 —— 它有独立的读者、没有写者，所以判据只能是
 * "pandoc 读出来的结构"与"我们读出来的结构"相对得上）：
 *  - **组与目的群**：`{\*\名字 …}` 说的是"这一整块是什么"。定义类（样式表、字体表、编号
 *    定义）要读但里面的字不算正文；颜色表、书签、文档信息那类整块不要；认不出的整块跳过
 *    并数一笔 —— **里面的字不算正文**是 RTF 不烂掉的关键（图片的十六进制流要是当成字，
 *    产物里就是一堆乱码）
 *  - **记号**：`\b \i \ul \strike` 与它们的 `0` 收尾；`\plain` 把这一层清回默认；`\fN` 指到
 *    字体表里标了 `\fmodern` 的那个号时算等宽
 *  - **段落**：`\par` 收一段，`\line` 段内换行，`\tab` 制表；`\sN` 要能在样式表里查到
 *    `\outlinelevelM` 才认成标题（样式号本身不是层级，凭它猜就是编的）
 *  - **列表**：`{\listtext …}` 那一格写的是 `•` 就是圆点、写的是 `1.` 这样的就是编号；
 *    编号段落还会先在 `{\*\pn …}` 里写 `\pnlvlblt` / `\pndec`，两处都认、先说清的算
 *  - **链接**：`{\field{\*\fldinst{HYPERLINK "地址"}}{\fldrslt 文字}}` —— 地址在域指令里，
 *    文字在结果里，照字面搬、不折算
 *  - **表格**：`\trowd` 起一行、`\cellx` 数出这行声明了几列、`\cell` 收一格、`\row` 收行；
 *    格数比声明的少就按空补齐（与 docx / ODF / xlsx 那几族同一条主张）；只有 `\trhdr` 才认表头
 *  - **转义与编码**：`\{ \} \\`；`\uN`（负数按 16 位补码）后面紧跟的 `\ucN` 个兜底写法按
 *    声明丢掉；`\'xx` 攒成一串按 `\ansicpg` 声明的代码页解（GBK 那类多字节页半个字节不是字）
 *
 * 丢掉的都数一笔并写进 [RtfBody.notes]：图片与二进制数据、内嵌对象、批注与脚注正文、数学式、
 * 域代码本身、套在表里的表、缩进行距边框上下标那类记号、没认出的控制字。
 */
object RtfRead {

    private const val MAX_BYTES = 32 * 1024 * 1024

    fun read(bytes: ByteArray): RtfBody {
        require(bytes.isNotEmpty()) { "这份文件是空的，没有可读的正文" }
        require(bytes.size <= MAX_BYTES) {
            "这份 RTF 有 ${bytes.size / 1024 / 1024} MB，超过 ${MAX_BYTES / 1024 / 1024} MB 上限"
        }
        val tally = RtfTally()
        val parts = ArrayList<DocPart>()
        RtfScan(String(bytes, Charsets.ISO_8859_1), tally, parts).walk()
        val losses = tally.losses()
        return RtfBody(Doc(parts, losses), losses)
    }
}

/**
 * 一趟读完：游标 [at] 只由这里推进。
 *
 * 位置必须由拿着游标的人一手推进：`\uN` 后面跟着要跳过的兜底写法、`\binN` 后面是 N 个
 * 原始字节，把"读一个控制字"写成返回位置的小函数就传不回去。
 */
private class RtfScan(
    private val src: String,
    private val tally: RtfTally,
    private val out: ArrayList<DocPart>,
) {
    private var at = 0
    private val stack = ArrayList<Node>()
    private val runs = ArrayList<DocRun>()
    private val cell = StringBuilder()
    private val cells = ArrayList<String>()
    private val rows = ArrayList<List<String>>()

    /** 一串 `\'xx` 攒着整段解：多字节代码页里半个字节不是字。[ansiFrom] 记住第一个字节在哪一层。 */
    private val ansi = ArrayList<Int>()
    private var ansiFrom: Node? = null

    private val outlineLevels = HashMap<Int, Int>()
    private val monoFonts = HashSet<Int>()

    private var codepage = DEFAULT_CODEPAGE
    private var ansiCharset: Charset? = charsetFor(DEFAULT_CODEPAGE)
    private var fallback = 1
    private var style = -1
    private var indent = 0
    private var bullet: Boolean? = null
    private var styleSlot = -1
    private var fontSlot = -1
    private var inTable = false
    private var tableOpen = false
    private var nested = false
    private var columns = 0
    private var rowHeader = false
    private var tableHeader = false
    private var ignoreText = false
    private var fieldUrl: String? = null
    private var linkShown = false

    /** 一层组：记号从上一层继承，再加上"这一层该怎么对待"。 */
    private class Node(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var font: Int = -1,
        var mode: Int = BODY,
        var name: String = "",
        var context: String = "",
        var link: Boolean = false,
        var sink: StringBuilder? = null,
    ) {
        fun branch(mode: Int, name: String, context: String): Node =
            Node(bold, italic, underline, strike, font, mode, name, context, link, null)
    }

    fun walk() {
        stack.add(Node())
        while (at < src.length) {
            if (out.size + runs.size > MAX_PARTS) {
                tally.bump("truncated")
                break
            }
            when (src[at]) {
                '{' -> open()
                '}' -> close()
                '\\' -> backslash()
                '\r', '\n' -> at++          // 源码里的换行只是排版，不是内容
                else -> {
                    text(src[at].toString())
                    at++
                }
            }
        }
        finish()
    }

    /** 组名本身就是一个控制字：`{\fonttbl …}`、`{\*\shppict …}`（那个星号是 `\*` 这个符号）。 */
    private fun open() {
        at++
        val starred = at + 1 < src.length && src[at] == '\\' && src[at + 1] == '*'
        if (starred) {
            at += 2
            while (at < src.length && (src[at] == ' ' || src[at] == '\t' || src[at] == '\r' || src[at] == '\n')) at++
        }
        if (at < src.length && src[at] == '\\') at++
        val head = word()
        val name = head?.first?.lowercase() ?: ""
        val parent = stack.last()
        val mode = modeFor(name, starred, parent.mode)
        val context = when {
            parent.mode == CONTROL -> parent.context
            mode == CONTROL -> name
            else -> ""
        }
        val made = parent.branch(mode, name, context)
        if (mode == GATHER || mode == CODE) {
            made.sink = StringBuilder()
            // 域指令里的 `HYPERLINK` 这个词本身是内容的一部分：它被当成组名吃掉了，要补回去
            if (mode == CODE && parent.mode == CODE) made.sink?.append(name)?.append(' ')
        }
        if (mode == SKIP && parent.mode != SKIP && parent.mode != DROP) tally.bump(destinationLoss(name))
        if (name == "fldrslt") made.link = fieldUrl != null
        if (mode == CONTROL) definition(context, name, head?.second)
        stack.add(made)
        // `{\pard …}` 把段落记号写在组里：记号不能因为当了组名就丢掉
        if (mode == BODY && (name == "pard" || name == "plain")) controlWord(name, head?.second, made)
    }

    private fun close() {
        flushAnsi()
        val gone = if (stack.size > 1) stack.removeAt(stack.size - 1) else stack.last()
        at++
        val parent = stack.last()
        val gathered = gone.sink?.toString() ?: ""
        if (gone.mode == GATHER) {
            if (parent.mode == GATHER) parent.sink?.append(gathered) else listMarker(gathered)
        } else if (gone.mode == CODE) {
            if (parent.mode == CODE) {
                parent.sink?.append(gathered)
            } else {
                tally.bump("fieldCode")
                val url = hyperlinkOf(gathered)
                if (url != null) fieldUrl = url
            }
        }
        if (gone.name == "field") {
            if (fieldUrl != null && !linkShown) tally.bump("fieldNoText")
            fieldUrl = null
            linkShown = false
        }
    }

    private fun backslash() {
        at++
        if (at >= src.length) return
        if (!src[at].isLetter()) {
            symbol()
            return
        }
        val made = word()
        if (made == null) {
            at++
            return
        }
        val node = stack.last()
        when {
            // \bin 在**任何**模式下都得先跳字节：二进制数据不是 RTF 记号，
            // 跳过的块里不认它的话，那些字节就被当成 RTF 文本走进状态机，
            // 里头一个 { } 就能把组配对搅乱，甚至把后面的正文一并吞掉
            made.first == "bin" -> skipBinary(made.second ?: 0)
            node.mode == BODY -> controlWord(made.first, made.second, node)
            node.mode == CONTROL -> definition(node.context, made.first, made.second)
            node.mode == GATHER -> markerWord(made.first, node)
            else -> Unit                     // 域指令只取字面；跳过与丢弃的块什么都不做
        }
    }

    /** 读一个控制字：字母串 + 可选十进制参数（负数也算），参数后面那一个空格是结束符。 */
    private fun word(): Pair<String, Int?>? {
        if (at >= src.length || !src[at].isLetter()) return null
        val start = at
        while (at < src.length && src[at].isLetter()) at++
        val name = src.substring(start, at)
        var argument: Int? = null
        if (at < src.length && (src[at] == '-' || src[at].isDigit())) {
            val from = at
            if (src[at] == '-') at++
            while (at < src.length && src[at].isDigit()) at++
            argument = src.substring(from, at).toIntOrNull()
        }
        if (at < src.length && src[at] == ' ') at++
        return name to argument
    }

    private fun symbol() {
        val node = stack.last()
        when (val ch = src[at]) {
            '{', '}', '\\' -> {
                at++
                text(ch.toString())
            }
            '\'' -> {
                val byte = hex(at + 1)
                if (byte == null) {
                    at++
                    return
                }
                at += 3
                if (!ignoreText && node.mode in GATHERING) {
                    if (ansi.isEmpty()) ansiFrom = node
                    ansi += byte
                }
            }
            '~' -> {
                at++
                text(" ")
            }
            '\r', '\n' -> at++
            else -> {
                at++
                if (node.mode == BODY) tally.bump("ignoredSymbol")
            }
        }
    }

    /** `\'xx` 的两个十六进制位。 */
    private fun hex(from: Int): Int? {
        if (from + 1 >= src.length) return null
        val first = src[from]
        val second = src[from + 1]
        if (!isHex(first) || !isHex(second)) return null
        return (digit(first) * 16 + digit(second))
    }

    private fun isHex(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    private fun digit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        else -> (ch.lowercaseChar() - 'a') + 10
    }

    private fun controlWord(name: String, argument: Int?, node: Node) {
        val made = name.lowercase()
        if (ignoreText && made != "par") return
        val on = argument == null || argument != 0
        when (made) {
            "pard" -> {
                style = -1
                indent = 0
                bullet = null
            }
            "plain" -> change(node) { target ->
                target.bold = false
                target.italic = false
                target.underline = false
                target.strike = false
                target.font = -1
            }
            "b" -> change(node) { it.bold = on }
            "i" -> change(node) { it.italic = on }
            "ul" -> change(node) { it.underline = on }
            "ulnone" -> change(node) { it.underline = false }
            "strike" -> change(node) { it.strike = on }
            "f" -> change(node) { it.font = argument ?: -1 }
            "s" -> style = argument ?: -1
            "ilvl" -> indent = (argument ?: 0).coerceIn(0, 8)
            "pnlvlblt" -> bullet = true
            "pnlvlbody", "pnlvlcont" -> Unit
            "pndec", "pnucrn", "pnlnom", "pnlowerletter", "pnupperletter", "pnlroman", "pnucroml" ->
                if (bullet == null) bullet = false
            "par", "sect", "page", "columnbreak" -> {
                ignoreText = false
                if (inTable) cell.append('\n') else endParagraph()
            }
            "line" -> text("\n")
            "tab" -> text("\t")
            "emdash" -> text("—")
            "endash" -> text("–")
            "ldblquote" -> text("“")
            "rdblquote" -> text("”")
            "lquote" -> text("‘")
            "rquote" -> text("’")
            "bullet" -> text("•")
            "ansicpg" -> setCodepage(argument)
            "uc" -> fallback = (argument ?: 1).coerceIn(0, 9)
            "u" -> unicode(argument)
            "bin" -> skipBinary(argument ?: 0)
            "pict" -> {
                tally.bump("picture")
                ignoreText = true
            }
            "cell" -> closeCell()
            "row" -> {
                flushAnsi()
                nested = false
                closeRow()
            }
            "trowd" -> if (inTable) {
                nested = true
                tally.bump("nestedTable")
                cell.append(' ')
            } else {
                startRow()
            }
            "intbl" -> inTable = true
            "cellx" -> if (!nested) columns++
            "trhdr" -> rowHeader = true
            "nestcell" -> {
                flushAnsi()
                if (!nested) {
                    nested = true
                    tally.bump("nestedTable")
                }
                if (inTable) cell.append(' ')
            }
            "nestrow" -> {
                flushAnsi()
                if (nested) {
                    nested = false
                    cell.append(' ')
                } else {
                    closeRow()
                }
            }
            "fldrslt" -> node.link = fieldUrl != null
            "field", "fldinst", "flddirty", "fldlock", "fldedit" -> Unit
            else -> if (made in UNMODELED) tally.bump("unmodeled") else tally.bump("unknownWord")
        }
    }

    /** 记号一变，先前攒着的 `\'xx` 要先出字 —— 不然那一串字节会带上新的记号。 */
    private fun change(node: Node, apply: (Node) -> Unit) {
        flushAnsi()
        apply(node)
    }

    private fun setCodepage(number: Int?) {
        if (number == null || number == codepage) return
        codepage = number
        ansiCharset = charsetFor(number)
    }

    /**
     * `\uN`：负数按 16 位补码看（RTF 那条规矩）。
     *
     * 后面紧跟的 `\ucN` 个兜底写法必须真跳过：留着它，一个中文字后面就多一个 `\'xx`
     * 拼出来的怪字。
     */
    private fun unicode(argument: Int?) {
        if (argument == null) {
            tally.bump("badUnicode")
            return
        }
        val value = if (argument < 0) argument + 0x10000 else argument
        val spaces = value == 0x09 || value == 0x0A || value == 0x0D
        val usable = value in 0..0x10FFFF && value != 0xFFFF && value !in 0xD800..0xDFFF &&
            (value >= 0x20 || spaces)
        if (usable) text(String(Character.toChars(value))) else tally.bump("badUnicode")
        skipFallback()
    }

    private fun skipFallback() {
        var left = fallback
        if (left <= 0) return
        tally.bump("fallbackSkipped")
        while (left > 0 && at < src.length) {
            val ch = src[at]
            when {
                ch == '\\' && at + 1 < src.length && src[at + 1] == '\'' && hex(at + 2) != null -> {
                    at += 4
                    left--
                }
                ch == '\\' && at + 1 < src.length && src[at + 1].isLetter() -> {
                    at++
                    word()
                    left--
                }
                ch == '\\' -> {
                    at += 2
                    left--
                }
                ch == '{' || ch == '}' -> left = 0
                ch == '\r' || ch == '\n' -> at++
                else -> {
                    at++
                    left--
                }
            }
        }
    }

    /** `\binN` 后面那 N 个字节是原样二进制，不是文字。 */
    private fun skipBinary(count: Int) {
        tally.bump("binary")
        if (count <= 0) return
        at = minOf(src.length, at + count)
    }

    /** 样式表 / 字体表 / 编号定义这三处只认定义用的控制字，里面的字一律不算正文。 */
    private fun definition(context: String, name: String, argument: Int?) {
        val made = name.lowercase()
        when (context) {
            "stylesheet" -> when (made) {
                "s" -> styleSlot = argument ?: -1
                "outlinelevel" -> if (argument != null && argument in 0..7 && styleSlot >= 0) {
                    outlineLevels[styleSlot] = argument
                }
            }
            "fonttbl" -> when (made) {
                "f" -> fontSlot = argument ?: -1
                "fmodern" -> if (fontSlot >= 0) monoFonts += fontSlot
            }
            "pn" -> when (made) {
                "ilvl" -> indent = (argument ?: 0).coerceIn(0, 8)
                "pnlvlblt" -> bullet = true
                "pndec", "pnucrn", "pnlnom", "pnlowerletter", "pnupperletter", "pnlroman", "pnucroml" ->
                    if (bullet == null) bullet = false
            }
        }
    }

    private fun markerWord(name: String, node: Node) {
        when (name.lowercase()) {
            "bullet" -> node.sink?.append('•')
            "tab" -> node.sink?.append('\t')
            else -> Unit
        }
    }

    /** `{\listtext …}` 那一格写的是记号本身：`•` 是圆点列表，`1.` / `a)` 是编号列表。 */
    private fun listMarker(raw: String) {
        val marker = raw.trim('\t', ' ', '\r', '\n')
        if (marker.isEmpty()) return
        bullet = when {
            marker.any { it in BULLETS } -> true
            NUMBERED.containsMatchIn(marker) -> false
            else -> bullet
        }
    }

    /** 从域指令那一句里取地址；不是 HYPERLINK 就返回 null（域算出来的字照常搬）。 */
    private fun hyperlinkOf(code: String): String? {
        val match = HYPERLINK.find(code.replace("\r", "").replace("\n", "")) ?: return null
        val url = (match.groupValues.getOrNull(1) ?: "").trim()
        return url.ifEmpty { null }
    }

    private fun text(value: String) {
        if (value.isEmpty() || ignoreText) return
        val node = stack.last()
        when (node.mode) {
            BODY -> {
                flushAnsi()
                if (inTable) cell.append(value) else addRun(value, node)
            }
            GATHER, CODE -> node.sink?.append(value)
            else -> Unit
        }
    }

    private fun flushAnsi() {
        if (ansi.isEmpty()) return
        val node = ansiFrom ?: stack.last()
        val bytes = ByteArray(ansi.size) { ansi[it].toByte() }
        ansi.clear()
        ansiFrom = null
        val made = decode(bytes)
        if (made.isEmpty()) return
        when (node.mode) {
            BODY -> if (inTable) cell.append(made) else addRun(made, node)
            GATHER, CODE -> node.sink?.append(made)
            else -> Unit
        }
    }

    private fun decode(bytes: ByteArray): String {
        val charset = ansiCharset
        if (charset == null) {
            tally.bump("otherCodepage")
            return String(bytes, Charsets.ISO_8859_1).replace("\uFFFD", "")
        }
        val made = String(bytes, charset).replace("\uFFFD", "")
        if (made.isEmpty()) tally.bump("undecodable")
        return made
    }

    private fun addRun(value: String, node: Node) {
        val link = if (node.link) fieldUrl else null
        if (link != null) linkShown = true
        runs += DocRun(
            value,
            bold = node.bold,
            italic = node.italic,
            strike = node.strike,
            mono = node.font in monoFonts,
            underline = node.underline,
            link = link,
        )
    }

    private fun startRow() {
        flushAnsi()
        if (inTable) closeCell()
        if (cells.isNotEmpty()) closeRow()
        if (!tableOpen) {
            flushTable()
            tableHeader = false
            tableOpen = true
        }
        inTable = true
        columns = 0
        rowHeader = false
    }

    private fun closeCell() {
        flushAnsi()
        if (!inTable) {
            tally.bump("strayCell")
            endParagraph()
            return
        }
        if (nested) {
            // 这一格属于套在表里的表：并成外层那一格的文字，不动外层的行
            cell.append(' ')
            return
        }
        cells += cell.toString().trim()
        cell.setLength(0)
        style = -1
        indent = 0
        bullet = null
        // 这一行还声明了没写完的格就接着收下一格；写满了一格不再收（`\row` 才收行）
        inTable = columns == 0 || cells.size < columns
    }

    private fun closeRow() {
        if (cells.isEmpty()) {
            inTable = false
            return
        }
        if (rows.isEmpty() && rowHeader) tableHeader = true
        val width = maxOf(columns, cells.size)
        rows += cells + List(width - cells.size) { "" }
        cells.clear()
        inTable = false
    }

    private fun flushTable() {
        if (rows.isEmpty()) return
        val width = rows.maxOf { it.size }
        out += DocTable(tableHeader, rows.map { row -> row + List(width - row.size) { "" } })
        rows.clear()
        tableHeader = false
        tableOpen = false
        columns = 0
    }

    private fun endParagraph() {
        flushAnsi()
        flushTable()
        val made = runs.toList()
        runs.clear()
        if (made.none { it.text.isNotBlank() }) {
            endParagraphProps()
            return
        }
        out += DocParagraph(DocPara(merge(made), styleName(), indent = indent, bullet = bullet))
        if (linkShown) {
            fieldUrl = null
            linkShown = false
        }
        if (out.size > MAX_PARTS) tally.bump("truncated")
        endParagraphProps()
    }

    private fun endParagraphProps() {
        style = -1
        indent = 0
        bullet = null
    }

    private fun styleName(): String {
        if (bullet != null) return "ListParagraph"
        val level = if (style >= 0) outlineLevels[style] else null
        return if (level == null) "Body" else "Heading${(level + 1).coerceIn(1, 6)}"
    }

    private fun finish() {
        flushAnsi()
        if (inTable) closeCell()
        if (cells.isNotEmpty()) closeRow()
        endParagraph()
        flushTable()
    }

    /** 相邻记号一样的 run 并成一格（与 docx / ODT 那两侧同一条做法）。 */
    private fun merge(runs: List<DocRun>): List<DocRun> {
        val kept = ArrayList<DocRun>()
        runs.forEach { run ->
            val last = kept.lastOrNull()
            if (last != null && last.bold == run.bold && last.italic == run.italic && last.mono == run.mono &&
                last.strike == run.strike && last.underline == run.underline && last.link == run.link
            ) {
                kept[kept.size - 1] = DocRun(
                    last.text + run.text,
                    bold = last.bold,
                    italic = last.italic,
                    strike = last.strike,
                    mono = last.mono,
                    underline = last.underline,
                    link = last.link,
                )
            } else {
                kept += run
            }
        }
        return kept
    }

    /** 这一层该怎么对待：上一层是"整块不要"时，里面一律整块不要（规范就是这么说的）。 */
    private fun modeFor(name: String, starred: Boolean, parent: Int): Int = when {
        parent == SKIP || parent == DROP -> parent
        name.isEmpty() -> parent
        name in CONTROL_GROUPS -> CONTROL
        name == "listtext" -> GATHER
        name == "fldinst" -> CODE
        name == "fldrslt" || name == "field" -> BODY
        name in DEFINITION_GROUPS -> DROP
        name in COUNTED_GROUPS -> SKIP
        starred -> SKIP
        else -> parent
    }

    private fun destinationLoss(name: String): String = when {
        name == "pict" || name == "shppict" || name == "nonshppict" || name.endsWith("blip") -> "picture"
        name == "bmc" || name == "thlist" || name == "binary" -> "binary"
        name == "object" || name == "objet" || name == "embedobj" || name.startsWith("make") -> "object"
        name.contains("note") || name.startsWith("atn") || name == "annotation" -> "annotation"
        name.contains("math") -> "math"
        else -> "skippedGroup"
    }

    /** 代码页号 → 这台机器上的字符集；一个都找不到时返回 null，由 [decode] 报一句。 */
    private fun charsetFor(number: Int): Charset? {
        val names = when (number) {
            936 -> listOf("GBK", "windows-936", "ms936")
            932 -> listOf("Shift_JIS", "windows-31j", "ms932")
            949 -> listOf("EUC-KR", "ksc5601.1987-0", "ms949")
            1361 -> listOf("Johab", "ms1361")
            else -> listOf("windows-$number", "cp$number")
        }
        names.forEach { candidate ->
            try {
                return Charset.forName(candidate)
            } catch (_: IllegalArgumentException) {
                // 这一页这台机器上没有，接着试下一个名字
            }
        }
        return null
    }

    companion object {
        private const val BODY = 0
        private const val CONTROL = 1
        private const val GATHER = 2
        private const val CODE = 3
        private const val DROP = 4
        private const val SKIP = 5
        private const val MAX_PARTS = 20_000
        private const val DEFAULT_CODEPAGE = 1252

        private val GATHERING = setOf(BODY, GATHER, CODE)
        private val BULLETS = charArrayOf('•', '·', '◦', '‣')
        private val NUMBERED = Regex("""[(\[]?\d{1,4}[.)、]|[(\[]?[a-zA-Z]{1,3}[.)]""")
        private val HYPERLINK = Regex("""HYPERLINK\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

        /** 要读定义的目的群（只认里面的控制字，字不算正文）。 */
        private val CONTROL_GROUPS = setOf("stylesheet", "fonttbl", "pn")

        /** 定义类内容：整块不要，也不用向人报告"丢了"。 */
        private val DEFINITION_GROUPS = setOf(
            "info", "generator", "colortbl", "colorclt", "listtable", "listoverride", "listoverridetable",
            "filetbl", "bkmkstart", "bkmkend", "xmlopen", "xmlnsnow", "dataclassification",
            "colorschememapping", "themeformat", "rsid", "rsidtbl", "upr",
        )

        /** 认得但搬不了的内容群：整块跳过并数一笔。 */
        private val COUNTED_GROUPS = setOf(
            "pict", "shppict", "nonshppict", "bmc", "thlist", "object", "objet", "embedobj", "mmath",
            "footnote", "endnote", "atnid", "atnauthor", "atnsegment", "annotation", "makechartfile",
            "makepicfile", "picprop", "rcl", "bcl",
        )

        /** 规范里有、文档树里没有对应物的那些记号（缩进、行距、边框、颜色、上下标…）。 */
        private val UNMODELED = setOf(
            "li", "ri", "fi", "sb", "sa", "sl", "kern", "lso", "keepn", "keepx", "hyph", "hyphpar",
            "nofspace", "nohalfspace", "noqform", "widctlpar", "pagebbord", "brkevenins", "brkoddins",
            "brkevens", "brkodds", "fs", "v", "cs", "ulc", "cf", "cb", "cg", "cx", "chcbpat", "cbpat",
            "highlight", "shad", "sub", "super", "up", "dn", "emvisd", "embeboss", "brdrb", "brdrt",
            "brdrl", "brdrr", "brdrs", "brdrth", "brdrdb", "brdrdash", "brdrdot", "brdrbtw", "brdrcf1",
            "clbrdrb", "clbrdrt", "clbrdrl", "clbrdrr", "clvmrg", "clhtmr", "clcbpat", "clvertalt",
            "clvertalc", "clvertalb", "clfauto", "clmgf", "trgaph", "trleft", "trqc", "trql", "trqr",
            "trkeep", "trkeepfollow", "trpct", "trfts", "trwwidth", "deflang", "deflangfe",
            "lang", "lang1", "lang2", "lang6", "fchars", "lchars", "falt", "objalias", "objclass",
            "objhost", "objlink", "linkedpic", "shplid", "mac", "pc", "nesttableprops",
            "ansi", "rtf", "deff", "margl", "margr", "margt", "margb", "paperw", "paperh", "sectd",
            "headery", "footery", "pgwswn", "pghswn", "gutter", "ltrim", "rtrim", "brkpage",
            "brkcol", "brksect", "vertorient", "pgnumtbb", "pgnbrre", "nonesttables", "rtlgutter",
        )
    }
}

/** 数"有什么没搬"用的计数表。 */
private class RtfTally {
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
        say(get("picture")) { "文里 $it 处图片搬不过来" }
        say(get("binary")) { "$it 处二进制数据（内嵌的字体、图片流）丢掉" }
        say(get("object")) { "$it 处嵌入对象只是附件，没当成文字搬" }
        say(get("annotation")) { "$it 处批注与脚注正文没搬" }
        say(get("math")) { "$it 处数学式没搬（文档树里没这一类）" }
        say(get("skippedGroup")) { "$it 处认不出的目的群整块跳过（里面的字不算正文）" }
        say(get("fieldCode")) { "$it 处域代码本身不搬，搬的是它算出来的字" }
        say(get("fieldNoText")) { "$it 个链接域没有结果文字，地址无处可挂" }
        say(get("nestedTable")) { "$it 处表里又套了表，里面那些格并成了同一格的文字" }
        say(get("fallbackSkipped")) { "$it 处 `\\u` 后面跟着的兜底写法按声明丢掉" }
        say(get("badUnicode")) { "$it 处字符编号用不了，丢掉而不是编一个字" }
        say(get("otherCodepage")) { "$it 串字节按这台机器上没有的代码页解，可能不对" }
        say(get("undecodable")) { "$it 串字节按声明的代码页解不出字" }
        say(get("strayCell")) { "$it 处 `\\cell` 不在表里，那段文字按普通段落收" }
        say(get("ignoredSymbol")) { "$it 处零宽与可选字符没有对应的字，跳过" }
        say(get("unmodeled")) { "$it 处缩进、行距、边框、上下标那类记号没搬（文档树里没这些东西）" }
        say(get("unknownWord")) { "$it 处控制字不认，字照搬、记号丢了" }
        say(get("truncated")) { "$it 块之后停下了：这份文件大得反常" }
        return list
    }
}
