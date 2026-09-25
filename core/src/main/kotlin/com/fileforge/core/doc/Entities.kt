package com.fileforge.core.doc

/** 解出来的实体文字，以及它吃掉源文本几个字符。 */
internal class Decoded(val text: String, val used: Int)

/** 认不出的实体计数：解不出必须说出来，不然"少了几个字"没人知道。 */
internal class EntityTally {
    var unknown = 0
}

/**
 * HTML 实体。
 *
 * 命名实体只带常用的一批（HTML5 那张表有两千多条，手机上用不到那么全），
 * 认不出的**照字面留下并计数** —— 静默丢字或丢分号都是坏转换，写明白才是好转换。
 */
internal object Entities {

    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "thinsp" to "\u2009", "ensp" to "\u2002", "emsp" to "\u2003",
        "shy" to "", "zwj" to "\u200D", "zwnj" to "\u200C",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "micro" to "µ",
        "sect" to "§", "para" to "¶", "middot" to "·", "bull" to "•", "dagger" to "†",
        "hellip" to "…", "prime" to "′", "Prime" to "″",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "sbquo" to "‚", "bdquo" to "„", "laquo" to "«", "raquo" to "»",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
        "mdash" to "—", "ndash" to "–",
        "plusmn" to "±", "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼",
        "sup1" to "¹", "sup2" to "²", "sup3" to "³",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à", "ccedil" to "ç",
        "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß",
    )

    fun decode(source: String, at: Int, tally: EntityTally): Decoded {
        if (at >= source.length || source[at] != '&') return Decoded("&", 1)
        if (source.getOrNull(at + 1) == '#') return numeric(source, at, tally)
        var cursor = at + 1
        while (cursor < source.length && source[cursor].isLetterOrDigit()) cursor++
        val name = source.substring(at + 1, cursor)
        if (name.isEmpty()) return Decoded("&", 1)
        if (source.getOrNull(cursor) == ';') {
            val used = cursor + 1 - at
            val mapped = NAMED[name]
            if (mapped != null) return Decoded(mapped, used)
            tally.unknown++
            return Decoded(source.substring(at, at + used), used)
        }
        // 老写法允许没有分号（`&amp`、`&nbsp`）：按能认出的最长前缀解 —— 浏览器与 html.parser 都这么读
        var best = 0
        for (length in 1..name.length) if (NAMED.containsKey(name.substring(0, length))) best = length
        if (best > 0) return Decoded(NAMED.getValue(name.substring(0, best)), best + 1)
        tally.unknown++
        return Decoded("&", 1)
    }

    /** `&#123;` 与 `&#xAB;`：没有分号也照最长一串数字读下去（html.parser 的行为）。 */
    private fun numeric(source: String, at: Int, tally: EntityTally): Decoded {
        val marker = source.getOrNull(at + 2)
        val hex = marker == 'x' || marker == 'X'
        val digitsStart = if (hex) at + 3 else at + 2
        var end = digitsStart
        while (end < source.length && source[end].let { if (hex) it.isDigit() || it in 'a'..'f' || it in 'A'..'F' else it.isDigit() }) end++
        val code = source.substring(digitsStart, end).toIntOrNull(if (hex) 16 else 10)
        val stop = if (source.getOrNull(end) == ';') end + 1 else end
        val used = stop - at
        if (code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF) {
            tally.unknown++
            return Decoded(source.substring(at, minOf(source.length, at + used)), used)
        }
        return Decoded(String(Character.toChars(code)), used)
    }

    fun decodeAll(text: String, tally: EntityTally): String = buildString {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch != '&') {
                append(ch)
                index++
                continue
            }
            val decoded = decode(text, index, tally)
            append(decoded.text)
            index += decoded.used
        }
    }
}
