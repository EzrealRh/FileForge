package com.fileforge.core.doc

/**
 * HTML 的词法：把一份可能是别人导出的网页切成 token。
 *
 * 这里**不**用 `DocumentBuilderFactory`：XML 那套要求标签配对、实体声明、大小写一致，
 * 真网页三样都不满足（`<br>` 不闭合、`&nbsp;` 没声明、`<P>` 大写）。用 XML 解析器读网页的
 * 结果不是报错就是静默少一段，所以要一份自己的容错词法。
 */
internal sealed interface HtmlToken

/** 已经解过实体的正文。 */
internal class HtmlText(val text: String) : HtmlToken

/** 注释与 `<!DOCTYPE>` 这类非元素标记：渲染时丢掉，但词法要认出来（不然 `<!-- <p> -->` 会把结构带歪）。 */
internal class HtmlOther(val text: String) : HtmlToken

/** 开 / 闭标签。closing 是 `</x>`，selfClosing 是写法上带了 `/` 的开标签。 */
internal class HtmlTag(
    val name: String,
    val attrs: Map<String, String>,
    val closing: Boolean,
    val selfClosing: Boolean,
) : HtmlToken

/** script / style / textarea 这类"里面不是标记"的元素：内容单独存，好让渲染层区分对待。 */
internal class HtmlRaw(val name: String, val text: String) : HtmlToken

internal object HtmlTokens {

    /** 自闭元素：没有结束标签，内容也不吃。 */
    val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr",
    )

    /** 内容要整段按字面收的元素（规范里的 raw text / escapable raw text）。 */
    private val RAW_TEXT = setOf("script", "style", "textarea", "title", "iframe", "noscript")

    fun tokenize(source: String, tally: EntityTally): List<HtmlToken> {
        val out = ArrayList<HtmlToken>()
        val text = StringBuilder()
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            if (ch != '<') {
                if (ch == '&') {
                    val entity = Entities.decode(source, index, tally)
                    text.append(entity.text)
                    index += entity.used
                    continue
                }
                text.append(ch)
                index++
                continue
            }
            val next = source.getOrNull(index + 1)
            if (next == '!' || next == '?') {
                val end = commentOrDeclarationEnd(source, index)
                flush(out, text)
                out += HtmlOther(source.substring(index, end))
                index = end
                continue
            }
            if (next == null || !(next.isLetter() || next == '/')) {
                // `<3`、`a < b` 这类：尖括号是正文，不是标签
                text.append(ch)
                index++
                continue
            }
            val parsed = tag(source, index, tally)
            if (parsed == null) {
                text.append(ch)
                index++
                continue
            }
            val opened = parsed.first
            flush(out, text)
            index = parsed.second
            if (!opened.closing && !opened.selfClosing && opened.name in RAW_TEXT) {
                // raw text 元素只发一个 HtmlRaw（含开与收）：再发一对 HtmlTag 就成了重复事件，
                // 与 html.parser 的轨迹对不上 —— 而这条轨迹正是要拿来逐事件比的
                val stop = rawTextEnd(source, index, opened.name)
                out += HtmlRaw(opened.name, Entities.decodeAll(source.substring(index, stop.first), tally))
                index = stop.first
                if (stop.second) index += ("</" + opened.name).length + 1
            } else {
                out += opened
            }
        }
        flush(out, text)
        return out
    }

    /** 从 [index] 处的 `<` 或 `</` 开始读一个标签；不像标签就返回 null（按字面留给正文）。 */
    private fun tag(source: String, index: Int, tally: EntityTally): Pair<HtmlTag, Int>? {
        var cursor = index + 1
        val closing = source.getOrNull(cursor) == '/'
        if (closing) cursor++
        val nameStart = cursor
        while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '-')) cursor++
        if (cursor == nameStart) return null
        val name = source.substring(nameStart, cursor).lowercase()
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        var last = ""
        while (cursor < source.length) {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (cursor >= source.length) return null
            when (source[cursor]) {
                '>' -> {
                    cursor++
                    return HtmlTag(name, attrs, closing, selfClosing) to cursor
                }
                '/', ';' -> cursor++                               // `;` 出现在标签里是脏数据，跳过
                '=' -> {
                    cursor++
                    val value = quoted(source, cursor, named = false, tally)
                    if (value != null && last.isNotEmpty()) attrs[last] = value.first
                    cursor = if (value == null) cursor + 1 else value.second
                }
                else -> {
                    val keyStart = cursor
                    while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] != '=' &&
                        source[cursor] != '>' && source[cursor] != '/' && source[cursor] != ';') cursor++
                    if (cursor == keyStart) {
                        cursor++
                        continue
                    }
                    last = source.substring(keyStart, cursor).lowercase()
                    attrs[last] = ""
                    val ahead = source.getOrNull(cursor)
                    if (ahead == '=') {
                        val value = quoted(source, cursor, named = true, tally)
                        if (value != null) {
                            attrs[last] = value.first
                            cursor = value.second
                        }
                    }
                }
            }
        }
        return null
    }

    /** 属性值：带引号的可以含 `>`；无引号的读到空白或 `>` 为止。named=true 时从 `=` 前那个位置读起。 */
    private fun quoted(source: String, from: Int, named: Boolean, tally: EntityTally): Pair<String, Int>? {
        var cursor = from
        if (named) {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (source.getOrNull(cursor) != '=') return null
            cursor++
        } else if (source.getOrNull(cursor) == '=') {
            cursor++
        }
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        val quote = source.getOrNull(cursor)
        if (quote == '"' || quote == '\'') {
            val end = source.indexOf(quote, cursor + 1)
            if (end < 0) return null
            return Entities.decodeAll(source.substring(cursor + 1, end), tally) to end + 1
        }
        val start = cursor
        while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] != '>') cursor++
        return Entities.decodeAll(source.substring(start, cursor), tally) to cursor
    }

    private fun commentOrDeclarationEnd(source: String, index: Int): Int {
        if (source.startsWith("<!--", index)) {
            val end = source.indexOf("-->", index + 4)
            return if (end < 0) source.length else end + 3
        }
        if (source.startsWith("<![CDATA[", index)) {
            val end = source.indexOf("]]>", index)
            return if (end < 0) source.length else end + 3
        }
        val end = source.indexOf('>', index)
        return if (end < 0) source.length else end + 1
    }

    /** 找 raw text 元素的收尾标签；找不到就吃到文件末尾（浏览器也这么补）。 */
    private fun rawTextEnd(source: String, from: Int, name: String): Pair<Int, Boolean> {
        val needle = "</" + name
        var cursor = from
        while (cursor < source.length) {
            val at = source.indexOf(needle, cursor, ignoreCase = true)
            if (at < 0) return source.length to false
            val after = at + needle.length
            if (after >= source.length || !source[after].isLetterOrDigit()) return at to true
            cursor = after
        }
        return source.length to false
    }

    private fun flush(out: MutableList<HtmlToken>, text: StringBuilder) {
        if (text.isEmpty()) return
        out += HtmlText(text.toString())
        text.setLength(0)
    }
}
