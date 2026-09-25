package com.fileforge.core.office

/**
 * 往 OOXML 的各份部件里写字时的两条规矩。
 *
 * 读那一路宽容（本名对上就行），写这一路必须严格：
 *  - `\x00`—`\x08`、`\x0B`、`\x0C`、`\x0E`—`\x1F` 这些在 XML 1.0 里根本不算合法字符，
 *    Word 与 Excel 看到就判"文件里有不可读取的内容"，只能去掉并数出来（带着它们出一份打不开的文件更糟）
 *  - 回车不是合法的行分隔符写法，一律并成换行（Excel 里换行就是换行，不区分 CRLF）
 */
internal object OoxmlText {

    fun bleach(value: String, lost: (Int) -> Unit): String {
        val out = StringBuilder(value.length)
        var at = 0
        while (at < value.length) {
            val ch = value[at]
            when {
                ch == '\r' -> {
                    out.append('\n')
                    at++
                    if (value.getOrNull(at) == '\n') at++                   // CRLF 只算一个换行
                }
                ch == '\n' || ch == '\t' -> { out.append(ch); at++ }
                ch.code < 0x20 -> { lost(1); at++ }
                else -> { out.append(ch); at++ }
            }
        }
        return out.toString()
    }

    fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun attribute(value: String): String = escape(value).replace("\"", "&quot;")
}
