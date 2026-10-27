package com.fileforge.core.data

/** 一张从 SQL dump 里读出来的表。NULL 保留为 null，空字符串与 NULL 在 CSV 里都落成空列。 */
data class SqlTable(val name: String, val columns: List<String>, val rows: List<List<String?>>)

/**
 * 只读 SQL dump：认 `CREATE TABLE` 的列定义与 `INSERT INTO ... VALUES` 的元组，
 * 其它语句（SET/UPDATE/视图/存储过程）一律略过，不执行、不猜。
 *
 * 目标是 mysqldump / pg_dump 默认那种「建表 + 批量 INSERT」文件；复杂表达式原样当文本。
 */
object SqlDump {

    fun parse(sql: String): List<SqlTable> {
        val tables = LinkedHashMap<String, MutableTable>()
        for (statement in splitStatements(stripComments(sql))) {
            val text = statement.trim()
            if (text.isEmpty()) continue
            val upper = text.uppercase()
            when {
                upper.startsWith("CREATE TABLE") -> parseCreate(text, tables)
                upper.startsWith("INSERT INTO") || upper.startsWith("INSERT IGNORE INTO") -> parseInsert(text, tables)
            }
        }
        return tables.values.map { it.toTable() }
    }

    /** RFC 4180：逗号/引号/换行都加引号，内部引号翻倍。 */
    fun toCsv(table: SqlTable): String {
        val out = StringBuilder()
        fun row(cells: List<String?>) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) out.append(',')
                val value = cell ?: ""
                if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                    out.append('"').append(value.replace("\"", "\"\"")).append('"')
                } else {
                    out.append(value)
                }
            }
            out.append('\n')
        }
        row(table.columns)
        table.rows.forEach { row(it) }
        return out.toString()
    }

    private class MutableTable(val name: String) {
        val columns = ArrayList<String>()
        val rows = ArrayList<List<String?>>()

        fun toTable() = SqlTable(name, columns.toList(), rows.toList())
    }

    private fun parseCreate(statement: String, tables: MutableMap<String, MutableTable>) {
        val open = statement.indexOf('(')
        if (open < 0) return
        val prefix = statement.substring(0, open)
            .replace(Regex("""(?i)^\s*create\s+table\s+(if\s+not\s+exists\s+)?"""), "")
        val name = stripIdentifier(prefix)
        if (name.isEmpty()) return
        val close = matchingParen(statement, open)
        if (close < 0) return
        val columns = splitTopLevel(statement.substring(open + 1, close), ',')
            .mapNotNull { definition ->
                val cleaned = definition.trim()
                if (cleaned.isEmpty()) return@mapNotNull null
                val head = cleaned.substringBefore(' ').substringBefore('\t').substringBefore('(').trim()
                val upper = head.uppercase()
                if (upper in CONSTRAINT_WORDS || cleaned.startsWith("(")) return@mapNotNull null
                stripIdentifier(head).takeIf { it.isNotEmpty() }
            }
        val table = tables.getOrPut(name) { MutableTable(name) }
        if (table.columns.isEmpty()) table.columns.addAll(columns)
    }

    private fun parseInsert(statement: String, tables: MutableMap<String, MutableTable>) {
        val upper = statement.uppercase()
        val intoAt = upper.indexOf("INTO")
        if (intoAt < 0) return
        var at = intoAt + 4
        while (at < statement.length && statement[at].isWhitespace()) at++
        val nameBuilder = StringBuilder()
        while (at < statement.length && !statement[at].isWhitespace() && statement[at] != '(') {
            nameBuilder.append(statement[at]); at++
        }
        val name = stripIdentifier(nameBuilder.toString())
        if (name.isEmpty()) return
        val valuesAt = upper.indexOf("VALUES", at)
        if (valuesAt < 0) return

        val insertColumns = run {
            val open = statement.indexOf('(', at)
            if (open < 0 || open > valuesAt) emptyList()
            else {
                val close = matchingParen(statement, open)
                if (close < 0 || close > valuesAt) emptyList()
                else splitTopLevel(statement.substring(open + 1, close), ',')
                    .map { stripIdentifier(it.trim()) }.filter { it.isNotEmpty() }
            }
        }
        val table = tables.getOrPut(name) { MutableTable(name) }
        val body = statement.substring(valuesAt + 6)
        for (tuple in valueTuples(body)) {
            val values = splitTopLevel(tuple, ',').map { parseValue(it) }
            addRow(table, insertColumns, values)
        }
    }

    private fun addRow(table: MutableTable, insertColumns: List<String>, values: List<String?>) {
        if (table.columns.isEmpty()) {
            if (insertColumns.isNotEmpty()) table.columns.addAll(insertColumns)
            else repeat(values.size) { table.columns += "c${it + 1}" }
        }
        val mapping = ArrayList<Pair<Int, String?>>()
        if (insertColumns.isEmpty()) {
            val count = maxOf(table.columns.size, values.size)
            while (table.columns.size < count) table.columns += "c${table.columns.size + 1}"
            values.forEachIndexed { index, value -> mapping += index to value }
        } else {
            insertColumns.forEachIndexed { index, column ->
                var columnIndex = table.columns.indexOfFirst { it.equals(column, ignoreCase = true) }
                if (columnIndex < 0) {
                    table.columns += column
                    columnIndex = table.columns.lastIndex
                }
                mapping += columnIndex to values.getOrNull(index)
            }
        }
        val row = MutableList<String?>(table.columns.size) { null }
        mapping.forEach { (index, value) -> if (index in row.indices) row[index] = value }
        table.rows += row
    }

    private fun valueTuples(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = 0.toChar()
        var start = -1
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (quote != 0.toChar()) {
                if (c == '\\' && i + 1 < body.length) { i += 2; continue }
                if (c == quote) {
                    if (i + 1 < body.length && body[i + 1] == quote) i++
                    else quote = 0.toChar()
                }
                i++
                continue
            }
            when (c) {
                '\'', '"', '`' -> quote = c
                '(' -> {
                    depth++
                    if (depth == 1) start = i + 1
                }
                ')' -> {
                    if (depth == 1 && start >= 0) out += body.substring(start, i)
                    depth--
                }
            }
            i++
        }
        return out
    }

    private fun parseValue(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.equals("NULL", ignoreCase = true)) return null
        if (value.length >= 2 && (value.first() == '\'' || value.first() == '"') && value.last() == value.first()) {
            val quote = value.first()
            var out = StringBuilder()
            var i = 1
            while (i < value.length - 1) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length - 1) {
                    out.append(value[i + 1])
                    i += 2
                } else if (c == quote && i + 1 < value.length - 1 && value[i + 1] == quote) {
                    out.append(quote); i += 2
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
        return value
    }

    private fun splitStatements(sql: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote = 0.toChar()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (quote != 0.toChar()) {
                current.append(c)
                if (c == '\\' && i + 1 < sql.length) { current.append(sql[++i]); i++; continue }
                if (c == quote) {
                    if (i + 1 < sql.length && sql[i + 1] == quote) { current.append(sql[++i]); i++; continue }
                    quote = 0.toChar()
                }
                i++
                continue
            }
            when (c) {
                '\'', '"', '`' -> { quote = c; current.append(c) }
                ';' -> { out += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    private fun stripComments(sql: String): String {
        val out = StringBuilder()
        var quote = 0.toChar()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (quote != 0.toChar()) {
                out.append(c)
                if (c == '\\' && i + 1 < sql.length) { out.append(sql[++i]); i++; continue }
                if (c == quote) {
                    if (i + 1 < sql.length && sql[i + 1] == quote) { out.append(sql[++i]); i++; continue }
                    quote = 0.toChar()
                }
                i++
                continue
            }
            when {
                c == '\'' || c == '"' || c == '`' -> { quote = c; out.append(c); i++ }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i = minOf(sql.length, i + 2)
                    out.append(' ')
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var quote = 0.toChar()
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (quote != 0.toChar()) {
                if (c == '\\' && i + 1 < text.length) { i += 2; continue }
                if (c == quote) quote = 0.toChar()
            } else {
                when (c) {
                    '\'', '"', '`' -> quote = c
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return i }
                }
            }
            i++
        }
        return -1
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = 0.toChar()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != 0.toChar()) {
                if (c == '\\' && i + 1 < text.length) { i += 2; continue }
                if (c == quote) quote = 0.toChar()
                i++
                continue
            }
            when (c) {
                '\'', '"', '`' -> quote = c
                '(' -> depth++
                ')' -> depth--
                delimiter -> if (depth == 0) { out += text.substring(start, i); start = i + 1 }
            }
            i++
        }
        out += text.substring(start)
        return out
    }

    private fun stripIdentifier(raw: String): String {
        var value = raw.trim().substringAfterLast('.').trim()
        if (value.length >= 2 && (
                (value.first() == '`' && value.last() == '`') ||
                    (value.first() == '"' && value.last() == '"') ||
                    (value.first() == '[' && value.last() == ']')
                )
        ) value = value.substring(1, value.length - 1)
        return value.trim()
    }

    private val CONSTRAINT_WORDS = setOf(
        "CONSTRAINT", "PRIMARY", "UNIQUE", "KEY", "FOREIGN", "CHECK", "INDEX",
    )
}
