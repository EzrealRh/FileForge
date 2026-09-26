package com.fileforge.core.data

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonBoolean
import com.fileforge.core.json.JsonNull
import com.fileforge.core.json.JsonNumber
import com.fileforge.core.json.JsonRender
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonString

/** 读不动 YAML 时抛的话：一律带行号。配置文件几十上百行，只说"格式不对"等于没说。 */
class YamlException(message: String) : Exception(message)

/**
 * YAML 的一个子集：块式映射与序列、行内 `[]` / `{}`、块标量 `|` 与 `>`、两种引号、注释、
 * 锚点与别名、`<<` 合并；多份文档只取第一份。
 *
 * 进出都走现成的那棵 [Json] 树（`parse` 出它、`write` 收它），为的是 YAML↔JSON、YAML↔CSV
 * 这几条路共用同一套摊平判据，不各摆一份数据结构。
 *
 * 类型按 **YAML 1.2 的核心模式**判，不跟 PyYAML 的 1.1 走：`yes` / `no` / `on` / `off` 与
 * `1:30` 这种六十进制在 1.1 里会变成 `true` 与 `90` —— 转换工具把 `version: 1:30` 悄悄
 * 变成 90 是最坏的一类错。两边判据不同的地方在 `tools/verify_yaml.py` 里逐条钉着，不装看不见。
 */
object Yaml {

    private val NULL = Regex("""^(~|null|Null|NULL)$""")
    private val TRUE = Regex("""^(true|True|TRUE)$""")
    private val FALSE = Regex("""^(false|False|FALSE)$""")
    private val INT = Regex("""^[-+]?[0-9][0-9_]*$""")
    private val HEX = Regex("""^[-+]?0x[0-9a-fA-F][0-9a-fA-F_]*$""")
    private val OCTAL = Regex("""^[-+]?0o[0-7][0-7_]*$""")
    private val FLOAT = Regex("""^[-+]?([0-9][0-9_]*)?\.[0-9_]*([eE][-+]?[0-9]+)?$""")
    private val NOT_A_NUMBER = Regex("""^[-+]?(\.inf|\.Inf|\.INF|\.nan|\.NaN|\.NAN)$""")

    /** 写出时要加引号的"看着像别的类型"：连着 1.1 那套 yes/no/六十进制/日期 一起防。 */
    private val TYPED = listOf(
        NULL, TRUE, FALSE, INT, HEX, OCTAL, FLOAT, NOT_A_NUMBER,
        Regex("""^(yes|Yes|YES|no|No|NO|on|On|ON|off|Off|OFF|y|Y|n|N)$"""),
        Regex("""^\d{4}-\d{1,2}-\d{1,2}([T ]\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[-+]\d{1,2}:?\d{2})?)?$"""),
        // 六十进制：PyYAML 那套 1.1 会把 1:30 读成 90、1:2:3 读成 3723，每一位 1~3 个数字都算
        Regex("""^\d{1,3}:\d{1,2}(:\d{1,2}(\.\d+)?)?$"""),
    )

    private val ENTRY = Regex("""^([^\s:][^:]*|"[^"]*"|'[^']*')[ \t]*:([ \t].*)?$""")
    private val SEQUENCE = Regex("""^-([ \t].*)?$""")
    private val BLOCK = Regex("""^([|>])([+-]?)([0-9]*)$""")
    private val ANCHOR = Regex("""^&(\S+)[ \t]*(.*)$""")
    private val ALIAS = Regex("""^\*(\S+)$""")

    /** 一行：缩进、去掉注释的内容、它在原始行里的下标（块标量要按原行收正文）、给人看的行号。 */
    private class Row(val indent: Int, val text: String, val raw: Int, val number: Int)

    private class Read(val rows: MutableList<Row>, val raws: List<String>) {
        var at = 0
        val anchors = HashMap<String, Json>()
        fun peek(): Row? = rows.getOrNull(at)
    }

    fun parse(text: String): Json {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val rows = ArrayList<Row>()
        lines.forEachIndexed { index, raw ->
            val indent = raw.takeWhile { it == ' ' }.length
            val head = raw.substring(indent)
            if (head.takeWhile { it == ' ' || it == '\t' }.contains('\t')) {
                throw YamlException("第 ${index + 1} 行的缩进里有制表符（YAML 不认 tab 缩进）")
            }
            val body = stripComment(head).trim()
            if (body.isEmpty() || body == "---" || body == "...") return@forEachIndexed
            if (body.startsWith("!") || body.startsWith("?") || body.startsWith("%")) {
                throw YamlException("第 ${index + 1} 行用的是标签、显式键或指令（!!binary、? 键、%YAML），这一版不认")
            }
            rows += Row(indent, body, index, index + 1)
        }
        if (rows.isEmpty()) throw YamlException("这份文件里没有可解析的内容（全是空行或注释？）")
        val reader = Read(rows, lines)
        val value = parseBlock(reader, rows.first().indent)
        if (reader.at < rows.size) {
            throw YamlException("第 ${rows[reader.at].number} 行往后对不上结构（缩进多了一层或少了一层）")
        }
        return value
    }

    /** 文档数（多于一份时只取第一份，这话留给调用方去说）。 */
    fun documentCount(text: String): Int =
        text.replace("\r\n", "\n").split('\n').count { it.trim() == "---" }

    /** 像不像 YAML：转之前先问一句 —— 一篇散文被"转成 JSON"是最难发现的错。 */
    fun looksLikeYaml(text: String): Boolean {
        var hits = 0
        text.lineSequence().forEach { line ->
            val body = stripComment(line.trim()).trim()
            if (body.isEmpty() || body == "---") return@forEach
            // 开头是 { 或 [ 或引号的当 JSON 文档让给 JSON 那几条路，别在这儿抢
            if (body.first() == '{' || body.first() == '[' || body.first() == '"') return@forEach
            if (SEQUENCE.matches(body) || ENTRY.matches(body)) hits++
        }
        return hits > 0
    }

    private fun parseBlock(r: Read, indent: Int): Json {
        val row = r.peek() ?: return JsonNull
        if (row.indent < indent) return JsonNull
        return if (SEQUENCE.matches(row.text)) readSequence(r, row.indent) else readMapping(r, row.indent)
    }

    private fun readMapping(r: Read, indent: Int): Json {
        val out = LinkedHashMap<String, Json>()
        while (true) {
            val row = r.peek() ?: break
            if (row.indent < indent) break
            if (SEQUENCE.matches(row.text)) break
            if (row.indent > indent) throw YamlException("第 ${row.number} 行比这一层多缩了 ${row.indent - indent} 格")
            val parts = ENTRY.matchEntire(row.text)
                ?: throw YamlException("第 ${row.number} 行不像键值对（键后面少了冒号？）：${row.text}")
            val key = unquote(parts.groupValues[1].trim())
            val inline = parts.groupValues[2].trim()
            r.at++
            val value = if (inline.isEmpty()) valueBelow(r, row.indent)
            else if (anchoredBlock(inline)) anchoredBelow(r, row.indent, inline)
            else valueOf(r, inline, row)
            if (key == "<<") merge(out, value, row) else out[key] = value
        }
        return JsonObject(out)
    }

    private fun readSequence(r: Read, indent: Int): Json {
        val out = ArrayList<Json>()
        while (true) {
            val row = r.peek() ?: break
            if (row.indent < indent || !SEQUENCE.matches(row.text)) break
            if (row.indent > indent) {
                throw YamlException("第 ${row.number} 行的减号比这一层多缩了 ${row.indent - indent} 格")
            }
            r.at++
            val body = row.text.drop(1).trim()
            when {
                body.isEmpty() -> out += valueBelow(r, row.indent)
                anchoredBlock(body) -> out += anchoredBelow(r, row.indent, body)
                ENTRY.matchEntire(body) != null -> {
                    // "- 键: 值"：冒号那部分是从减号后面那一列开始的一个映射，塞回一行按块读
                    val column = row.indent + row.text.length - row.text.drop(1).trimStart().length
                    r.rows.add(r.at, Row(column, body, row.raw, row.number))
                    out += parseBlock(r, column)
                }
                else -> out += valueOf(r, body, row)
            }
        }
        return JsonArray(out)
    }

    /**
     * 键或减号后面没有内容：往下看一层。
     *
     * 同层的 `- x` 也算这一项的内容 —— YAML 允许列表不额外缩进（`items:` 紧跟 `- 甲`），
     * 不许着这条就会把整个列表读成 null。
     */
    private fun valueBelow(r: Read, indent: Int): Json {
        val next = r.peek() ?: return JsonNull
        if (next.indent > indent) return parseBlock(r, next.indent)
        if (next.indent == indent && SEQUENCE.matches(next.text)) return readSequence(r, indent)
        return JsonNull
    }

    /** `key: &锚点` 后面紧跟一块：锚点属于那一块（不这么处理那一块会变成"多缩了一层"）。 */
    private fun anchoredBlock(inline: String): Boolean =
        ANCHOR.matchEntire(inline)?.groupValues?.get(2)?.isEmpty() == true

    private fun anchoredBelow(r: Read, indent: Int, inline: String): Json {
        val value = valueBelow(r, indent)
        ANCHOR.matchEntire(inline)?.let { r.anchors[it.groupValues[1]] = value }
        return value
    }

    private fun valueOf(r: Read, inline: String, row: Row): Json {
        val anchor = ANCHOR.matchEntire(inline)
        val body = if (anchor == null) inline else anchor.groupValues[2].trim()
        ALIAS.matchEntire(body)?.let { alias ->
            val found = r.anchors[alias.groupValues[1]]
                ?: throw YamlException("第 ${row.number} 行引用了没定义过的别名 *${alias.groupValues[1]}")
            return found
        }
        val marker = BLOCK.matchEntire(body)
        val value = when {
            marker != null -> readBlock(r, marker, row)
            body.startsWith("[") || body.startsWith("{") -> readFlow(body, row)
            body.startsWith("&") -> throw YamlException("第 ${row.number} 行的锚点写法认不出来：$body")
            else -> scalar(body)
        }
        if (anchor != null) r.anchors[anchor.groupValues[1]] = value
        return value
    }

    private fun merge(into: LinkedHashMap<String, Json>, value: Json, row: Row) {
        val sources = if (value.members.isNotEmpty()) listOf(value) else value.arrayValue
        if (sources.isEmpty()) throw YamlException("第 ${row.number} 行的 << 后面既不是映射也不是映射的列表")
        sources.forEach { source ->
            if (source.members.isEmpty()) throw YamlException("第 ${row.number} 行 << 合并的项里有不是映射的")
            source.members.forEach { (key, item) -> if (!into.containsKey(key)) into[key] = item }
        }
    }

    /**
     * 块标量（`|`、`>`，带 `-` / `+` / 缩进数字）。正文按**原始行**收 —— 那里面既有 `#` 开头的
     * 文字，也有看着像 `- 甲` 的行，按行解析会把日志和示例命令当结构读掉。
     */
    private fun readBlock(r: Read, marker: MatchResult, row: Row): Json {
        val folded = marker.groupValues[1] == ">"
        val chomp = marker.groupValues[2]
        var base = marker.groupValues[3].toIntOrNull()
        var index = row.raw + 1
        if (base == null) {
            var probe = index
            while (probe < r.raws.size && r.raws[probe].isBlank()) probe++
            val first = r.raws.getOrNull(probe)
            if (first == null || first.takeWhile { it == ' ' }.length <= row.indent) {
                return JsonString(chomped("", chomp))
            }
            base = first.takeWhile { it == ' ' }.length
        }
        val body = ArrayList<String>()
        while (index < r.raws.size) {
            val line = r.raws[index]
            if (line.isBlank() || line.takeWhile { it == ' ' }.length >= base) {
                body += line
                index++
            } else break
        }
        while (r.at < r.rows.size && r.rows[r.at].raw < index) r.at++
        val cut = body.map { if (it.isBlank()) "" else it.substring(base) }.toMutableList()
        var trailing = 0
        while (cut.isNotEmpty() && cut.last().isEmpty()) {
            cut.removeAt(cut.size - 1)
            trailing++
        }
        val text = if (folded) fold(cut) else cut.joinToString("\n")
        return JsonString(chomped(text, chomp, trailing))
    }

    /**
     * 尾部换行的三种处理：clip（默认）留一个，strip（`-`）全去掉，keep（`+`）把块尾那些空行都留回来。
     *
     * `|+` 容易写错成"跟 clip 一样"：正文里存模板与配置文件时，尾部那个空行就是内容的一部分。
     */
    private fun chomped(text: String, chomp: String, trailingBlanks: Int = 0): String = when {
        chomp == "-" -> text
        chomp == "+" -> text + "\n".repeat(if (text.isEmpty()) trailingBlanks else trailingBlanks + 1)
        text.isEmpty() -> ""
        else -> text + "\n"
    }

    /** 折行：相邻文字行并成一行，空行变换行，比自己更缩进的行原样留着。 */
    private fun fold(lines: List<String>): String {
        val out = StringBuilder()
        var open = false
        lines.forEach { line ->
            when {
                line.isBlank() -> { out.append('\n'); open = false }
                line.startsWith(" ") -> {
                    if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n')
                    out.append(line)
                    open = false
                }
                else -> {
                    if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append(' ')
                    out.append(line)
                    open = true
                }
            }
        }
        return out.toString().trimEnd('\n')
    }

    private fun readFlow(text: String, row: Row): Json {
        val reader = Flow(text, row.number)
        val value = reader.value()
        reader.skipSpace()
        if (!reader.done()) throw YamlException("第 ${row.number} 行的 [ 或 { 没配平：$text")
        return value
    }

    /** 行内写法：`[甲, 乙]`、`{键: 值, 键2: [1, 2]}`。 */
    private class Flow(val text: String, val number: Int) {
        var at = 0
        fun done(): Boolean = at >= text.length
        fun skipSpace() { while (at < text.length && text[at] == ' ') at++ }

        fun value(): Json {
            skipSpace()
            val head = text.getOrNull(at)
            if (head == '[') {
                at++
                val out = ArrayList<Json>()
                if (peekClose(']')) return JsonArray(out)
                while (true) {
                    out += value()
                    if (close(']')) return JsonArray(out)
                }
            }
            if (head == '{') {
                at++
                val out = LinkedHashMap<String, Json>()
                if (peekClose('}')) return JsonObject(out)
                while (true) {
                    val key = unquote(plain(stopAtColon = true))
                    skipSpace()
                    if (text.getOrNull(at) != ':') throw YamlException("第 $number 行的 { 里键后面少了冒号")
                    at++
                    out[key] = value()
                    if (close('}')) return JsonObject(out)
                }
            }
            return scalar(plain(stopAtColon = false))
        }

        private fun peekClose(bracket: Char): Boolean {
            skipSpace()
            if (text.getOrNull(at) != bracket) return false
            at++
            return true
        }

        /** 吃掉分隔符；返回 true 表示已经到了收尾的那个括号。 */
        private fun close(bracket: Char): Boolean {
            skipSpace()
            return when (text.getOrNull(at)) {
                ',' -> { at++; false }
                bracket -> { at++; true }
                else -> throw YamlException("第 $number 行的 [ 或 { 里少了逗号或没配平")
            }
        }

        private fun plain(stopAtColon: Boolean): String {
            skipSpace()
            val first = text.getOrNull(at) ?: return ""
            if (first == '\'' || first == '"') return quoted(first)
            val out = StringBuilder()
            while (at < text.length) {
                val ch = text[at]
                if (ch == ',' || ch == ']' || ch == '}') break
                if (stopAtColon && ch == ':' && (at + 1 >= text.length || text[at + 1] == ' ')) break
                out.append(ch)
                at++
            }
            return out.toString().trim()
        }

        private fun quoted(quote: Char): String {
            val out = StringBuilder()
            at++
            while (at < text.length) {
                val ch = text[at]
                if (ch == quote) {
                    if (quote == '\'' && text.getOrNull(at + 1) == '\'') { out.append('\''); at += 2; continue }
                    at++
                    return if (quote == '\'') out.toString() else unescape(out.toString())
                }
                if (quote == '"' && ch == '\\' && at + 1 < text.length) {
                    out.append(ch).append(text[at + 1]); at += 2; continue
                }
                out.append(ch)
                at++
            }
            throw YamlException("第 $number 行的引号没关上")
        }
    }

    private fun scalar(text: String): Json {
        val body = text.trim()
        if (body.isEmpty()) return JsonNull
        val first = body.first()
        if (first == '\'' || first == '"') return JsonString(unquote(body))
        if (first == '[' || first == '{') throw YamlException("行内的 [ 或 { 没配平：$body")
        if (first == '!') throw YamlException("值上的标签写法（$body）这一版不认，硬读会变成文字")
        if (NULL.matches(body)) return JsonNull
        if (TRUE.matches(body)) return JsonBoolean(true)
        if (FALSE.matches(body)) return JsonBoolean(false)
        if (INT.matches(body)) return JsonNumber(canonicalInt(body))
        if (HEX.matches(body) || OCTAL.matches(body)) return JsonNumber(radixInt(body))
        if (FLOAT.matches(body) && body.any { it.isDigit() }) return JsonNumber(canonicalFloat(body))
        // JSON 里没有无穷与非数：照字面留成文字，总比自己造一个别人读不回来的数强
        if (NOT_A_NUMBER.matches(body)) return JsonString(body)
        return JsonString(body)
    }

    /** 数字的写法要能被 JSON 抄走：`01` 变 `1`、`+1` 变 `1`、`1_000` 变 `1000`。 */
    private fun canonicalInt(text: String): String {
        val negative = text.startsWith("-")
        val digits = text.trimStart('+', '-').replace("_", "").trimStart('0')
        val body = if (digits.isEmpty()) "0" else digits
        return if (negative && body != "0") "-$body" else body
    }

    private fun radixInt(text: String): String {
        val negative = text.startsWith("-")
        val body = text.trimStart('+', '-').drop(2).replace("_", "")
        val radix = if (text.contains("0x") || text.contains("0X")) 16 else 8
        val value = body.toLongOrNull(radix) ?: return text
        return if (negative) "-$value" else value.toString()
    }

    /** `.5` 补成 `0.5`：JSON 不接受以小数点开头的数。 */
    private fun canonicalFloat(text: String): String {
        val negative = text.startsWith("-")
        val bare = text.trimStart('+', '-').replace("_", "")
        val body = if (bare.startsWith(".")) "0$bare" else bare
        return if (negative) "-$body" else body
    }

    private val ESCAPES = mapOf(
        'n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b',
        'f' to '\u000C', 'v' to '\u000B', '0' to '\u0000', 'a' to '\u0007', 'e' to '\u001B',
    )

    private fun unquote(text: String): String {
        val body = text.trim()
        if (body.length < 2) return body
        val first = body.first()
        if (first != body.last() || (first != '\'' && first != '"')) return body
        val inner = body.substring(1, body.length - 1)
        if (first == '\'') return inner.replace("''", "'")
        return unescape(inner)
    }

    /** 双引号里的转义；认不出的按 YAML 的规矩"照抄后一个字符"。 */
    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '\\' || i + 1 >= text.length) { out.append(ch); i++; continue }
            val next = text[i + 1]
            val simple = ESCAPES[next]
            when {
                simple != null -> { out.append(simple); i += 2 }
                next == '\n' -> i += 2                          // 行尾的反斜杠是折行，不算内容
                next == 'x' || next == 'u' || next == 'U' -> {
                    val width = if (next == 'x') 2 else if (next == 'u') 4 else 8
                    val digits = text.substring(i + 2, minOf(i + 2 + width, text.length))
                    val code = digits.toLongOrNull(16)
                        ?: throw YamlException("转义里的 \\$next$digits 不是合法的码点")
                    out.append(String(Character.toChars(code.toInt())))
                    i += 2 + width
                }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /**
     * 去掉行尾注释。
     *
     * 只有 `#` 前面是空白或行首才算注释：`url: http://a#b` 里那个井号是值的一部分，
     * 引号里的 `#` 也不算 —— 按"看见井号就切"会把链接与哈希值吃掉一截。
     */
    private fun stripComment(text: String): String {
        var quote = ' '
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quote == ' ' && (ch == '\'' || ch == '"') -> quote = ch
                ch == quote -> {
                    if (quote == '\'' && text.getOrNull(i + 1) == '\'') i++ else quote = ' '
                }
                quote == '"' && ch == '\\' -> i++
                ch == '#' && quote == ' ' && (i == 0 || text[i - 1] == ' ' || text[i - 1] == '\t') ->
                    return text.substring(0, i).trimEnd()
            }
            i++
        }
        return text.trimEnd()
    }

    /** 树 → YAML 文本（块式，缩进默认两格）。 */
    fun write(value: Json, indent: Int = 2): String {
        val out = StringBuilder()
        val step = if (indent < 1) 2 else indent
        when {
            value is JsonObject || value.members.isNotEmpty() -> mapping(out, value, step, 0)
            value is JsonArray || value.arrayValue.isNotEmpty() -> sequence(out, value, step, 0)
            else -> out.append(text(value)).append('\n')
        }
        return out.toString()
    }

    private fun mapping(out: StringBuilder, value: Json, step: Int, depth: Int) {
        val pad = " ".repeat(step * depth)
        value.members.forEach { (key, item) ->
            out.append(pad).append(quote(key)).append(":")
            when {
                item.members.isNotEmpty() -> { out.append('\n'); mapping(out, item, step, depth + 1) }
                item.arrayValue.isNotEmpty() -> { out.append('\n'); sequence(out, item, step, depth + 1) }
                else -> out.append(' ').append(text(item)).append('\n')
            }
        }
    }

    private fun sequence(out: StringBuilder, value: Json, step: Int, depth: Int) {
        val pad = " ".repeat(step * depth)
        value.arrayValue.forEach { item ->
            when {
                item.members.isNotEmpty() -> {
                    var first = true
                    item.members.forEach { (key, nested) ->
                        out.append(pad).append(if (first) "- " else "  ")
                        first = false
                        out.append(quote(key)).append(":")
                        when {
                            nested.members.isNotEmpty() -> { out.append('\n'); mapping(out, nested, step, depth + 2) }
                            nested.arrayValue.isNotEmpty() -> { out.append('\n'); sequence(out, nested, step, depth + 2) }
                            else -> out.append(' ').append(text(nested)).append('\n')
                        }
                    }
                }
                item.arrayValue.isNotEmpty() -> { out.append(pad).append("-\n"); sequence(out, item, step, depth + 1) }
                else -> out.append(pad).append("- ").append(text(item)).append('\n')
            }
        }
    }

    private fun text(value: Json): String = when {
        value is JsonNull -> "null"
        value is JsonBoolean -> if (value.boolValue == true) "true" else "false"
        value is JsonNumber -> number(value.numberText ?: "0")
        value is JsonObject -> "{}"
        value is JsonArray -> "[]"
        else -> quote(value.stringValue ?: "")
    }

    /**
     * 数字的写法要保证**任何**读 YAML 的实现都认成同一个数。
     *
     * `1.5e3` 是最典型的坑：YAML 1.2 的浮点式子接受它，PyYAML 那套 1.1 的式子要求指数带正负号，
     * 于是别人把它读成字符串 —— 值就变了。这种写法改成等价的十进制落出去（值不变，写法变）。
     */
    private val BOTH_READ_IT = Regex("""^-?(\d+\.\d+([eE][-+]\d+)?|\.\d+([eE][-+]\d+)?|\d+([eE][-+]\d+)?)$""")

    private fun number(raw: String): String {
        if (BOTH_READ_IT.matches(raw)) return raw
        val value = raw.toDoubleOrNull() ?: return quote(raw)
        if (value.isNaN() || value.isInfinite()) return quote(raw)
        val plain = if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString() + ".0"
        } else {
            value.toString()
        }
        return if (BOTH_READ_IT.matches(plain)) plain else quote(raw)
    }

    /**
     * 要不要加引号。**这条是整个写出侧的命门**：少加一对引号，别人重读回去就不是那个值了。
     *
     * `1.`、`yes`、`0755`、`2023-05-01`、`- 甲` 这些"看着像别的类型"的文字一律加引号；
     * 首尾带空白、含换行、含 `: ` 或 ` #` 的也加（都会被解析器吃掉一截）。
     */
    private fun quote(text: String): String {
        if (!needsQuote(text)) return text
        val escaped = text.any { it < ' ' || it == '' } || text.startsWith("'") || text.endsWith("'")
        return if (escaped) JsonRender.quote(text) else "'" + text.replace("'", "''") + "'"
    }

    private fun needsQuote(text: String): Boolean {
        if (text.isEmpty() || text != text.trim()) return true
        if (text.any { it < ' ' }) return true
        if (text.contains(": ") || text.contains(" #") || text.endsWith(":")) return true
        if (text.first() in "-?:,[]{}#&*!|>'\"%@`") return true
        if (SEQUENCE.matches(text) || ENTRY.matches(text)) return true
        return TYPED.any { it.matches(text) }
    }
}
