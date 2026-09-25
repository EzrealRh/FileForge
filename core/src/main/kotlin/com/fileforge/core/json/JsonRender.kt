package com.fileforge.core.json

/**
 * JSON 的输出侧。
 *
 * 三条刻意的取舍：
 *  - **数字照抄原文**（见 [Json.numberText]）：格式化不该把 `1` 变成 `1.0`，也不该让大整数过一遍 double 丢位
 *  - 键的插入顺序默认保留（`LinkedHashMap`），要排序是显式选项 —— 很多配置文件的顺序就是人的阅读顺序
 *  - 非 ASCII 默认**原样输出**：JSON 本身就是 UTF-8，转成 `\uXXXX` 只会让人类读不动；
 *    但有些老系统只吃 ASCII，所以那个开关留着
 */
object JsonRender {

    fun render(
        value: Json,
        indent: Int = 0,
        sortKeys: Boolean = false,
        escapeNonAscii: Boolean = false,
    ): String {
        val out = StringBuilder()
        write(out, value, if (indent > 0) indent else 0, 0, sortKeys, escapeNonAscii)
        return out.toString()
    }

    /** 字符串字面量（含引号与转义）。CSV 之类要嵌 JSON 片段时也用这个，转义只留一处实现。 */
    fun quote(text: String, escapeNonAscii: Boolean = false): String {
        val out = StringBuilder(text.length + 2).append('"')
        text.forEach { ch -> appendEscaped(out, ch, escapeNonAscii) }
        return out.append('"').toString()
    }

    private fun write(
        out: StringBuilder,
        value: Json,
        indent: Int,
        depth: Int,
        sortKeys: Boolean,
        ascii: Boolean,
    ) {
        val pad = "\n" + " ".repeat(indent * (depth + 1))
        val closing = "\n" + " ".repeat(indent * depth)
        // 这几个是带自定义 getter 的属性，判完空也不能智能转换，所以一律先落本地变量
        val text = value.stringValue
        val number = value.numberText
        val flag = value.boolValue
        when {
            value.isNull -> out.append("null")
            flag != null -> out.append(flag)
            number != null -> out.append(number)
            text != null -> out.append(quote(text, ascii))
            value.members.isNotEmpty() || value is JsonObject -> {
                out.append('{')
                val entries = if (sortKeys) value.members.toSortedMap() else value.members
                entries.entries.forEachIndexed { index, (key, child) ->
                    out.append(if (indent > 0) pad else "").append(quote(key, ascii)).append(if (indent > 0) ": " else ":")
                    write(out, child, indent, depth + 1, sortKeys, ascii)
                    if (index < entries.size - 1) out.append(',')
                }
                if (entries.isNotEmpty() && indent > 0) out.append(closing)
                out.append('}')
            }
            else -> {
                out.append('[')
                val items = value.arrayValue
                items.forEachIndexed { index, child ->
                    out.append(if (indent > 0) pad else "")
                    write(out, child, indent, depth + 1, sortKeys, ascii)
                    if (index < items.size - 1) out.append(',')
                }
                if (items.isNotEmpty() && indent > 0) out.append(closing)
                out.append(']')
            }
        }
    }

    private fun appendEscaped(out: StringBuilder, ch: Char, ascii: Boolean) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            // 控制字符没有简写形式，规范说要用 \u
            else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code))
            else if (ascii && ch.code > 0x7E) out.append("\\u%04x".format(ch.code))
            else out.append(ch)
        }
    }
}
