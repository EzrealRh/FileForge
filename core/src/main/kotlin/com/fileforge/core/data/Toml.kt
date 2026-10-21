package com.fileforge.core.data

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonBoolean
import com.fileforge.core.json.JsonNull
import com.fileforge.core.json.JsonNumber
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonString

/** 读不动 TOML 时抛的话：带行号，配置文件几十行，只说"格式不对"等于没说。 */
class TomlException(message: String) : Exception(message)

/**
 * TOML 1.0 的一个实现子集：表、数组表、点号键、内联表、数组、四种字符串、整数（含进制）、
 * 浮点、布尔与日期时间。日期时间没有 JSON 类型，一律**保持原文**当字符串 —— 与 YAML 那边
 * "不把 1:30 变成 90" 是同一条规矩。
 *
 * 进出都走 [Json] 树：TOML→JSON / YAML / CSV / Excel 共用同一套摊平判据。
 */
object Toml {

    fun parse(text: String): Json {
        val root = LinkedHashMap<String, Json>()
        var current: MutableMap<String, Json> = root
        val declared = HashSet<String>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val number = i + 1
            val line = stripComment(lines[i]).trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            when {
                line.startsWith("[[") -> {
                    if (!line.endsWith("]]")) throw TomlException("第 $number 行：数组表头要以 ]] 收尾")
                    val path = keyPath(line.substring(2, line.length - 2), number)
                    current = openArrayTable(root, path, number)
                    declared += path.joinToString(".")
                    i++
                }
                line.startsWith("[") -> {
                    if (!line.endsWith("]")) throw TomlException("第 $number 行：表头要以 ] 收尾")
                    val path = keyPath(line.substring(1, line.length - 1), number)
                    val id = path.joinToString(".")
                    if (!declared.add(id)) throw TomlException("第 $number 行：表 [${path.joinToString(".")}] 定义了两次")
                    current = openTable(root, path, number)
                    i++
                }
                else -> {
                    val eq = indexOutsideQuotes(line, '=')
                    if (eq < 0) throw TomlException("第 $number 行：这不是 key = value")
                    val path = keyPath(line.substring(0, eq), number)
                    var raw = line.substring(eq + 1).trim()
                    var next = i + 1
                    while (!valueComplete(raw)) {
                        if (next >= lines.size) throw TomlException("第 $number 行：数组、内联表或字符串没有闭合")
                        raw += "\n" + stripComment(lines[next]).trim()
                        next++
                    }
                    assign(current, path, ValueReader(raw, number).read(), number)
                    i = next
                }
            }
        }
        return JsonObject(root)
    }

    fun write(json: Json): String {
        val root = json as? JsonObject
            ?: throw TomlException("TOML 顶层必须是一张表（键值对）；这份顶层是 ${kindOf(json)}")
        val out = StringBuilder()
        writeTable(out, root, emptyList(), header = false)
        return out.toString().trimEnd() + "\n"
    }

    // ---------------------------------------------------------------- 读

    private fun openTable(root: MutableMap<String, Json>, path: List<String>, line: Int): MutableMap<String, Json> {
        var map = root
        path.forEach { key ->
            map = when (val existing = map[key]) {
                null -> LinkedHashMap<String, Json>().also { map[key] = JsonObject(it) }
                is JsonObject -> backing(existing, line)
                is JsonArray -> {
                    val last = existing.arrayValue.lastOrNull() as? JsonObject
                        ?: throw TomlException("第 $line 行：「$key」是数组，不是表")
                    backing(last, line)
                }
                else -> throw TomlException("第 $line 行：「$key」已经是一个值，不能再当表")
            }
        }
        return map
    }

    private fun openArrayTable(root: MutableMap<String, Json>, path: List<String>, line: Int): MutableMap<String, Json> {
        val parent = openTable(root, path.dropLast(1), line)
        val key = path.last()
        val list = when (val existing = parent[key]) {
            null -> ArrayList<Json>()
            is JsonArray -> ArrayList(existing.arrayValue)
            else -> throw TomlException("第 $line 行：「$key」已经是一个值，不能再当数组表")
        }
        val fresh = LinkedHashMap<String, Json>()
        list += JsonObject(fresh)
        parent[key] = JsonArray(list)
        return fresh
    }

    private fun assign(current: MutableMap<String, Json>, path: List<String>, value: Json, line: Int) {
        var map = current
        path.dropLast(1).forEach { key ->
            map = when (val existing = map[key]) {
                null -> LinkedHashMap<String, Json>().also { map[key] = JsonObject(it) }
                is JsonObject -> backing(existing, line)
                else -> throw TomlException("第 $line 行：「$key」已经是一个值，不能再往下点")
            }
        }
        val key = path.last()
        if (map.containsKey(key)) throw TomlException("第 $line 行：键「${path.joinToString(".")}」写了两次")
        map[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    private fun backing(json: JsonObject, line: Int): MutableMap<String, Json> =
        json.members as? MutableMap<String, Json>
            ?: throw TomlException("第 $line 行：这张表是只读的（内部错误）")

    private fun keyPath(raw: String, line: Int): List<String> {
        val parts = splitOutsideQuotes(raw, '.')
        if (parts.isEmpty() || parts.any { it.trim().isEmpty() }) throw TomlException("第 $line 行：键名不能为空")
        return parts.map { unquoteKey(it.trim(), line) }
    }

    private fun unquoteKey(token: String, line: Int): String = when {
        token.startsWith("\"") -> unquoteBasic(token, line)
        token.startsWith("'") -> {
            if (!token.endsWith("'") || token.length < 2) throw TomlException("第 $line 行：键的引号没闭合")
            token.substring(1, token.length - 1)
        }
        token.all { it.isLetterOrDigit() || it == '_' || it == '-' } -> token
        else -> throw TomlException("第 $line 行：键「$token」里有不认识的字符，给它加引号")
    }

    private class ValueReader(private val text: String, private val line: Int) {
        private var at = 0

        fun read(): Json {
            skipSpace()
            val value = value()
            skipSpace()
            if (at != text.length) throw TomlException("第 $line 行：值后面还有多余内容「${text.substring(at).take(20)}」")
            return value
        }

        private fun value(): Json {
            skipSpace()
            if (at >= text.length) throw TomlException("第 $line 行：等号后面没有值")
            return when {
                text.startsWith("\"\"\"", at) -> JsonString(multiline(basic = true))
                text.startsWith("'''", at) -> JsonString(multiline(basic = false))
                text[at] == '"' -> JsonString(basic())
                text[at] == '\'' -> JsonString(literal())
                text[at] == '[' -> array()
                text[at] == '{' -> inlineTable()
                else -> scalar()
            }
        }

        private fun array(): Json {
            at++                                   // [
            val values = ArrayList<Json>()
            while (true) {
                skipSpace(allowNewline = true)
                if (at >= text.length) throw TomlException("第 $line 行：数组没有闭合")
                if (text[at] == ']') {
                    at++
                    return JsonArray(values)
                }
                values += value()
                skipSpace(allowNewline = true)
                if (at < text.length && text[at] == ',') {
                    at++
                    continue
                }
                if (at < text.length && text[at] == ']') {
                    at++
                    return JsonArray(values)
                }
                throw TomlException("第 $line 行：数组里少了逗号或 ]")
            }
        }

        private fun inlineTable(): Json {
            at++                                   // {
            val map = LinkedHashMap<String, Json>()
            while (true) {
                skipSpace(allowNewline = true)
                if (at >= text.length) throw TomlException("第 $line 行：内联表没有闭合")
                if (text[at] == '}') {
                    at++
                    return JsonObject(map)
                }
                val start = at
                while (at < text.length && text[at] != '=') at++
                if (at >= text.length) throw TomlException("第 $line 行：内联表里少了 =")
                val path = keyPath(text.substring(start, at), line)
                at++                               // =
                val value = value()
                var cursor: MutableMap<String, Json> = map
                path.dropLast(1).forEach { key ->
                    cursor = (cursor[key] as? JsonObject)?.members as? MutableMap<String, Json>
                        ?: LinkedHashMap<String, Json>().also { cursor[key] = JsonObject(it) }
                }
                if (cursor.containsKey(path.last())) throw TomlException("第 $line 行：内联表里「${path.joinToString(".")}」写了两次")
                cursor[path.last()] = value
                skipSpace(allowNewline = true)
                if (at < text.length && text[at] == ',') {
                    at++
                    continue
                }
                if (at < text.length && text[at] == '}') {
                    at++
                    return JsonObject(map)
                }
                throw TomlException("第 $line 行：内联表里少了逗号或 }")
            }
        }

        private fun scalar(): Json {
            val start = at
            while (at < text.length) {
                val c = text[at]
                if (c == ',' || c == ']' || c == '}' || c == '#' || c == '\n' || c.isWhitespace()) break
                at++
            }
            val token = text.substring(start, at)
            if (token.isEmpty()) throw TomlException("第 $line 行：这里该有一个值")
            if (token == "true") return JsonBoolean(true)
            if (token == "false") return JsonBoolean(false)
            if (token == "inf" || token == "+inf" || token == "-inf" || token == "nan" || token == "+nan" || token == "-nan") {
                // JSON 里没有这几种；保留原文当字符串，写回 TOML 时仍是裸 token
                return JsonString(token)
            }
            val clean = token.replace("_", "")
            if (DATE.matches(clean)) return JsonString(clean)
            INT.matchEntire(clean)?.let { match ->
                val sign = if (clean.startsWith("-")) -1L else 1L
                val body = clean.removePrefix("+").removePrefix("-")
                val value = when {
                    body.startsWith("0x") -> body.substring(2).toLongOrNull(16)
                    body.startsWith("0o") -> body.substring(2).toLongOrNull(8)
                    body.startsWith("0b") -> body.substring(2).toLongOrNull(2)
                    else -> body.toLongOrNull(10)
                } ?: throw TomlException("第 $line 行：整数「$token」太大或写得不对")
                return JsonNumber((sign * value).toString())
            }
            if (FLOAT.matches(clean)) return JsonNumber(clean)
            throw TomlException("第 $line 行：认不出的值「$token」（字符串要加引号；TOML 没有裸的日期以外的词）")
        }

        private fun basic(): String {
            at++                                   // "
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(escape())
                    c == '\n' -> throw TomlException("第 $line 行：单行字符串里不能换行（要换行用三个引号）")
                    else -> out.append(c)
                }
            }
            throw TomlException("第 $line 行：字符串没有闭合")
        }

        private fun literal(): String {
            at++                                   // '
            val end = text.indexOf('\'', at)
            if (end < 0) throw TomlException("第 $line 行：字符串没有闭合")
            val body = text.substring(at, end)
            if (body.contains('\n')) throw TomlException("第 $line 行：单行字符串里不能换行")
            at = end + 1
            return body
        }

        private fun multiline(basic: Boolean): String {
            val mark = if (basic) "\"\"\"" else "'''"
            at += 3
            if (at < text.length && text[at] == '\n') at++
            val end = text.indexOf(mark, at)
            if (end < 0) throw TomlException("第 $line 行：多行字符串没有闭合")
            var body = text.substring(at, end)
            at = end + 3
            if (basic) {
                // 行尾反斜杠吃掉后面的空白与换行
                body = body.replace(Regex("\\\\[ \\t]*\\n[ \\t\\n]*"), "")
            }
            return if (basic) unescape(body) else body
        }

        private fun escape(): String {
            if (at >= text.length) throw TomlException("第 $line 行：转义没写完")
            return when (val c = text[at++]) {
                'b' -> "\b"; 't' -> "\t"; 'n' -> "\n"; 'f' -> "\u000C"; 'r' -> "\r"
                '"' -> "\""; '\\' -> "\\"
                'u' -> codePoint(4)
                'U' -> codePoint(8)
                else -> throw TomlException("第 $line 行：不认识的转义 \\$c")
            }
        }

        private fun codePoint(digits: Int): String {
            if (at + digits > text.length) throw TomlException("第 $line 行：\\u 后面位数不够")
            val code = text.substring(at, at + digits).toIntOrNull(16)
                ?: throw TomlException("第 $line 行：\\u 后面不是十六进制")
            at += digits
            return String(Character.toChars(code))
        }

        private fun skipSpace(allowNewline: Boolean = false) {
            while (at < text.length && (text[at] == ' ' || text[at] == '\t' || (allowNewline && text[at] == '\n'))) at++
        }
    }

    private fun unescape(body: String): String = buildString {
        var i = 0
        while (i < body.length) {
            val c = body[i++]
            if (c != '\\') {
                append(c)
                continue
            }
            when (val e = body.getOrNull(i++) ?: '\\') {
                'b' -> append('\b'); 't' -> append('\t'); 'n' -> append('\n'); 'f' -> append('\u000C')
                'r' -> append('\r'); '"' -> append('"'); '\\' -> append('\\')
                'u' -> {
                    val code = body.substring(i, minOf(i + 4, body.length)).toIntOrNull(16) ?: 0
                    i += 4
                    append(String(Character.toChars(code)))
                }
                'U' -> {
                    val code = body.substring(i, minOf(i + 8, body.length)).toIntOrNull(16) ?: 0
                    i += 8
                    append(String(Character.toChars(code)))
                }
                else -> append(e)
            }
        }
    }

    private fun unquoteBasic(token: String, line: Int): String {
        if (!token.endsWith("\"") || token.length < 2) throw TomlException("第 $line 行：键的引号没闭合")
        return unescape(token.substring(1, token.length - 1))
    }

    // ---------------------------------------------------------------- 写

    private fun writeTable(out: StringBuilder, table: JsonObject, path: List<String>, header: Boolean) {
        if (header && path.isNotEmpty()) {
            if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
            out.append('[').append(path.joinToString(".") { key(it) }).append("]\n")
        }
        val scalars = ArrayList<Pair<String, Json>>()
        val tables = ArrayList<Pair<String, JsonObject>>()
        val arrays = ArrayList<Pair<String, List<JsonObject>>>()
        table.members.forEach { (name, value) ->
            when {
                value is JsonObject -> tables += name to value
                value is JsonArray && value.arrayValue.isNotEmpty() && value.arrayValue.all { it is JsonObject } ->
                    arrays += name to value.arrayValue.map { it as JsonObject }
                else -> scalars += name to value
            }
        }
        scalars.forEach { (name, value) -> out.append(key(name)).append(" = ").append(inline(value)).append('\n') }
        tables.forEach { (name, value) -> writeTable(out, value, path + name, header = true) }
        arrays.forEach { (name, values) ->
            values.forEach { value ->
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                out.append("[[").append((path + name).joinToString(".") { key(it) }).append("]]\n")
                writeTable(out, value, path + name, header = false)
            }
        }
    }

    private fun inline(json: Json): String = when (json) {
        is JsonString -> quote(json.stringValue ?: "")
        is JsonNumber -> json.numberText ?: "0"
        is JsonBoolean -> if (json.boolValue == true) "true" else "false"
        is JsonNull -> throw TomlException("TOML 没有 null")
        is JsonArray -> json.arrayValue.joinToString(", ", "[", "]") { inline(it) }
        is JsonObject -> json.members.entries.joinToString(", ", "{ ", " }") { (name, value) ->
            "${key(name)} = ${inline(value)}"
        }
    }

    private fun key(name: String): String =
        if (name.matches(Regex("[A-Za-z0-9_-]+"))) name else quote(name)

    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun kindOf(json: Json): String = when (json) {
        is JsonObject -> "表"
        is JsonArray -> "数组"
        is JsonString -> "字符串"
        is JsonNumber -> "数字"
        is JsonBoolean -> "布尔"
        is JsonNull -> "null"
    }

    private val INT = Regex("""^[-+]?(0|[1-9](_?[0-9])*|0x[0-9a-fA-F](_?[0-9a-fA-F])*|0o[0-7](_?[0-7])*|0b[01](_?[01])*)$""")
    private val FLOAT = Regex("""^[-+]?(0|[1-9][0-9_]*)(\.[0-9_]+)?([eE][-+]?[0-9_]+)?$""")
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}([Tt ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?([Zz]|[-+]\d{2}:\d{2})?)?$|^\d{2}:\d{2}(:\d{2}(\.\d+)?)?$""")

    // ---------------------------------------------------------------- 行处理

    private fun stripComment(line: String): String {
        var quote = 0.toChar()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote == '"' && c == '\\' -> i++
                quote != 0.toChar() && c == quote -> quote = 0.toChar()
                quote == 0.toChar() && (c == '"' || c == '\'') -> quote = c
                quote == 0.toChar() && c == '#' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun indexOutsideQuotes(text: String, needle: Char): Int {
        var quote = 0.toChar()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '"' && c == '\\' -> i++
                quote != 0.toChar() && c == quote -> quote = 0.toChar()
                quote == 0.toChar() && (c == '"' || c == '\'') -> quote = c
                quote == 0.toChar() && c == needle -> return i
            }
            i++
        }
        return -1
    }

    private fun splitOutsideQuotes(text: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        var quote = 0.toChar()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '"' && c == '\\' -> i++
                quote != 0.toChar() && c == quote -> quote = 0.toChar()
                quote == 0.toChar() && (c == '"' || c == '\'') -> quote = c
                quote == 0.toChar() && c == separator -> {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        parts += text.substring(start)
        return parts
    }

    /** 括号闭合、字符串收尾都完了才算这一条值写完；没写完就继续吃下一行。 */
    private fun valueComplete(text: String): Boolean {
        var i = 0
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    if (end < 0) return false
                    i = end + 3
                }
                text.startsWith("'''", i) -> {
                    val end = text.indexOf("'''", i + 3)
                    if (end < 0) return false
                    i = end + 3
                }
                text[i] == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    if (i >= text.length) return false
                    i++
                }
                text[i] == '\'' -> {
                    i++
                    while (i < text.length && text[i] != '\'') i++
                    if (i >= text.length) return false
                    i++
                }
                text[i] == '[' || text[i] == '{' -> { depth++; i++ }
                text[i] == ']' || text[i] == '}' -> { depth--; i++ }
                else -> i++
            }
        }
        return depth == 0
    }
}
