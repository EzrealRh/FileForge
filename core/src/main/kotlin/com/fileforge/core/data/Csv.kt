package com.fileforge.core.data

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonBoolean
import com.fileforge.core.json.JsonNumber
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonRender
import com.fileforge.core.json.JsonString
import com.fileforge.core.text.LineEnding
import com.fileforge.core.text.LineEndings

/** 分隔符。CSV 从没规定必须是逗号：Excel 在欧区默认分号，TSV 是制表符，管道符常见于日志导出。 */
enum class Delimiter(val label: String, val char: Char) {
    Comma("逗号 ,", ','),
    Semicolon("分号 ;", ';'),
    Tab("制表符", '\t'),
    Pipe("竖线 |", '|'),
    ;

    companion object {
        fun of(char: Char): Delimiter? = entries.firstOrNull { it.char == char }
    }
}

/** 解析出来的表格：每条记录一组单元格。 */
class CsvDoc(val records: List<List<String>>, val delimiter: Delimiter, val ending: LineEnding) {
    val isEmpty: Boolean get() = records.isEmpty()
    val widest: Int get() = records.maxOfOrNull { it.size } ?: 0

    /** 列数与最宽那行不同的行号（从 1 数）。报行号是因为那通常是数据错了，而不是格式错了。 */
    val ragged: List<Int> get() = records.indices.filter { records[it].size != widest }.map { it + 1 }
}

/**
 * CSV 的读与写，引号规则照 RFC 4180。
 *
 * 三条从真实文件里来的经验：
 *  - **引号只在字段开头才有意义**。`他说"你好"` 这种中间带引号的字段，Excel 与 Python 的 csv
 *    都按字面处理；要是当转义吃掉，导一圈内容就变了
 *  - 行分隔符按原文件占多数的那种认，且允许引号里换行 —— Windows 导出的表几乎全是 CRLF
 *  - 末尾那个空行不是记录：文本文件习惯以换行收尾，当成一行会凭空多出条全空数据
 */
object Csv {

    /** UTF-8 BOM。用码点而不是嵌一个看不见的字符，否则源码里有什么下一个人根本看不出来。 */
    const val BOM = '\uFEFF'

    /**
     * 猜分隔符：在前 20 行里数各候选出现在**引号之外**的次数，取最多那个。
     *
     * 只看第一行会被"某个字段里正好写了逗号"骗到；数到 20 行够稳又不至于把几万行的表读一遍。
     * 并列时按 [Delimiter] 的声明顺序定胜负（逗号赢），所以这条要连声明顺序一起测。
     */
    fun detect(text: String): Delimiter {
        val body = text.trimStart(BOM).lines().take(20)
        var best = Delimiter.Comma
        var bestCount = 0
        Delimiter.entries.forEach { delimiter ->
            val count = body.sumOf { line -> countOutsideQuotes(line, delimiter.char) }
            if (count > bestCount) {
                best = delimiter
                bestCount = count
            }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, char: Char): Int {
        var inside = false
        var count = 0
        for (ch in line) {
            if (ch == '"') inside = !inside else if (!inside && ch == char) count++
        }
        return count
    }

    fun parse(text: String, delimiter: Delimiter = detect(text)): CsvDoc {
        val body = text.trimStart(BOM)
        val ending = LineEndings.detect(body) ?: LineEnding.Lf
        val records = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            val next = body.getOrNull(i + 1)
            when {
                quoted && ch == '"' && next == '"' -> { field.append('"'); i += 2 }
                quoted && ch == '"' -> { quoted = false; i++ }
                // 引号里的换行属于内容，不断行；CRLF 两个字符要一起吃掉，否则留下半个空字段
                quoted && ch == '\r' -> {
                    val crlf = next == '\n'
                    field.append(if (crlf) "\r\n" else "\r")
                    i += if (crlf) 2 else 1
                }
                quoted -> { field.append(ch); i++ }
                ch == '"' && field.isEmpty() -> { quoted = true; i++ }
                ch == delimiter.char -> { row += field.toString(); field.setLength(0); i++ }
                ch == '\r' || ch == '\n' -> {
                    row += field.toString(); field.setLength(0)
                    records += row
                    row = ArrayList()
                    i += if (ch == '\r' && next == '\n') 2 else 1
                }
                else -> { field.append(ch); i++ }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            records += row
        }
        val kept = records.filterNot { it.size == 1 && it.single().isEmpty() }
        return CsvDoc(kept, delimiter, ending)
    }

    /** 最少加引号：只在真会影响解析时才包起来，这样产物人能读、diff 也干净。 */
    fun render(
        records: List<List<String>>,
        delimiter: Delimiter = Delimiter.Comma,
        ending: LineEnding = LineEnding.Lf,
        quoteAll: Boolean = false,
    ): String {
        val out = StringBuilder()
        records.forEach { row ->
            row.forEachIndexed { index, cell ->
                if (index > 0) out.append(delimiter.char)
                out.append(
                    if (quoteAll || needsQuotes(cell, delimiter.char)) "\"${cell.replace("\"", "\"\"")}\"" else cell,
                )
            }
            out.append(ending.sample)
        }
        return out.toString()
    }

    private fun needsQuotes(cell: String, delimiter: Char): Boolean =
        delimiter in cell || '"' in cell || '\n' in cell || '\r' in cell
}

/**
 * JSON 树与二维表之间的桥。
 *
 * 这一层的重点不是"能不能转"，而是**必须说清楚会丢什么**：CSV 只有文字，而 JSON 有嵌套、
 * 有类型、有 null。不交代的话用户拿回来发现 `1.50` 变成 `1.5`、`007` 变成 `7`，
 * 那是数据损坏，不是转换。
 */
object TableBridge {

    /** 一张能进 CSV 的表：列名 + 行，每行长度都等于列数。 */
    class Table(val columns: List<String>, val rows: List<List<String>>) {
        val records: List<List<String>> get() = listOf(columns) + rows
    }

    /** 数字的**JSON 语法**形态。Java 的 `toDoubleOrNull` 还认 `3d`、`0x1p3`、`Infinity` ——
     *  放过去就会写出非法 JSON，所以这里自己判。 */
    private val JSON_NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][-+]?\\d+)?$")

    /** 不能转成表时，说清楚为什么。返回 null 表示可以转。 */
    fun reasonWhyNotTable(json: Json): String? = when {
        json !is JsonArray -> "最外层不是数组，CSV 装不下：CSV 是二维表，对象与标量都没有「行」可摆"
        json.arrayValue.isEmpty() -> "这个数组是空的，连列名都定不下来"
        else -> null
    }

    /**
     * JSON → 表。
     *
     * 条目是对象时列按**首次出现顺序**取并集，缺的格子留空；条目是数组时按位置列 1..n；
     * 两者都不是（纯标量数组）就单列摆一列。嵌套值压成一格 JSON 文本 ——
     * 不这么做就只能整列丢掉，那是更坏的丢法。
     */
    fun toTable(json: Json): Table? {
        if (reasonWhyNotTable(json) != null) return null
        val items = json.arrayValue
        val allObjects = items.all { it is JsonObject }
        if (allObjects) {
            val columns = LinkedHashSet<String>()
            items.forEach { columns += it.members.keys }
            val names = columns.toList()
            return Table(names, items.map { item -> names.map { column -> cell(item.field(column)) } })
        }
        if (items.all { it is JsonArray }) {
            val widest = items.maxOf { it.arrayValue.size }
            val names = (1..widest).map { index -> "列$index" }
            return Table(names, items.map { item -> names.indices.map { index -> cell(item.arrayValue.getOrNull(index)) } })
        }
        return Table(listOf("值"), items.map { listOf(cell(it)) })
    }

    /** 开着类型识别会额外丢掉什么。界面按 [inferTypes] 决定要不要把这条挂上去。 */
    val inferLosses = "开类型识别时，数字格子的**写法**会丢：`1.50` 读回去是 1.5、`1e3` 读回去是 1000" +
        "（JSON 的数字没有格式可言）。`007` 这类前导零不会被认成数字，所以不受影响"

    /** 表 → JSON 数组。[header] 为真时第一条记录是列名。 */
    fun toRowsJson(doc: CsvDoc, header: Boolean, inferTypes: Boolean): Json {
        if (doc.records.isEmpty()) return JsonArray(emptyList())
        if (!header) {
            return JsonArray(doc.records.map { row -> JsonArray(row.map { scalar(it, inferTypes) }) })
        }
        val columns = dedupe(doc.records.first())
        val rows = doc.records.drop(1).map { row ->
            val members = LinkedHashMap<String, Json>()
            columns.forEachIndexed { index, column ->
                members[column] = scalar(row.getOrNull(index) ?: "", inferTypes)
            }
            JsonObject(members)
        }
        return JsonArray(rows)
    }

    /** 表头里空的与重名的都要给个能用的键，否则 JSON 对象里会互相覆盖，数据直接少一列。 */
    fun dedupe(raw: List<String>): List<String> {
        val taken = HashSet<String>()
        return raw.mapIndexed { index, name ->
            val base = name.trim().ifBlank { "列${index + 1}" }
            var candidate = base
            var suffix = 2
            while (candidate in taken) {
                candidate = "${base}_$suffix"
                suffix++
            }
            taken += candidate
            candidate
        }
    }

    /** 转过去会丢什么，动手之前显示给用户看。 */
    fun losses(json: Json): List<String> =
        (listOf("CSV 只有文字：数字、真假、null 过去之后全是字符串，回来时不再认得类型（除非开类型识别）") +
            flatteningNotes(json)).distinct()

    /** 与目标格式无关的那几条：嵌套值要压平、null 与空格分不出来、各条目字段数不齐。 */
    fun flatteningNotes(json: Json): List<String> {
        val out = ArrayList<String>()
        val nested = json.arrayValue.any { item -> item.members.values.any { child -> child is JsonArray || child is JsonObject } }
        if (nested) out += "嵌套的对象与数组会被压成一格文字，那一列回来时还是文字，不自动还原成结构"
        if (json.arrayValue.any { it.isNull }) out += "null 与空值在表里分不出来，回来时都是空格子"
        val widths = json.arrayValue.map { it.members.size }.distinct()
        if (widths.size > 1) out += "各条目的字段数不一样：并集成一排列，缺的地方留空"
        return out.distinct()
    }

    private fun cell(value: Json?): String {
        if (value == null || value.isNull) return ""
        // stringValue / numberText 这些是带自定义 getter 的属性，判空后不能智能转换
        value.stringValue?.let { return it }
        value.numberText?.let { return it }
        value.boolValue?.let { return it.toString() }
        return JsonRender.render(value)     // 嵌套：压成一格 JSON 文本
    }

    /**
     * 单元格回 JSON。[infer] 关掉时一律当字符串。
     *
     * 开着时也只认 **JSON 语法的数字**：`007` 是编号、`1,5` 是欧区小数、`1e` 是坏值，
     * 这三个被猜成数字就再也回不去了，所以宁可留成字符串。
     */
    private fun scalar(text: String, infer: Boolean): Json {
        if (!infer) return JsonString(text)
        val trimmed = text.trim()
        when {
            JSON_NUMBER.matches(trimmed) -> return JsonNumber(trimmed)
            trimmed == "true" -> return JsonBoolean(true)
            trimmed == "false" -> return JsonBoolean(false)
            else -> return JsonString(text)     // 原样，含首尾空格：空格常常是有意义的
        }
    }
}
