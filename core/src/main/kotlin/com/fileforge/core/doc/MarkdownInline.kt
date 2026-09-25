package com.fileforge.core.doc

/**
 * 行内语法的一把扫描器，HTML 与纯文本两种输出共用。
 *
 * 共用是故意的：两套实现最容易走岔的地方正是"什么算标记、什么算字"，
 * 一处判定，两种输出就不会出现"HTML 里是链接、纯文本里丢了半句"。
 */
internal class Inline(
    private val html: Boolean,
    private val refs: Map<String, LinkTarget>,
    private val notes: Notes,
) {

    class LinkTarget(val url: String, val title: String?)

    fun render(text: String): String = build(text)

    private fun build(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            val rest = text.substring(index)
            val consumed: Int? = when (ch) {
                '\\' -> escape(rest, out)
                '`' -> code(rest, out)
                '!' -> image(rest, out)
                '[' -> link(rest, out)
                '<' -> autoLink(rest, out)
                '*', '_' -> emphasis(rest, out, if (index == 0) null else text[index - 1])
                '~' -> strike(rest, out)
                else -> null
            }
            if (consumed == null) {
                out.append(plain(ch.toString()))
                index++
            } else {
                index += consumed
            }
        }
        return out.toString()
    }

    /** 转义：反斜杠加标点才是转义，加字母按字面留（CommonMark 的规矩）。 */
    private fun escape(rest: String, out: StringBuilder): Int? {
        if (rest.length < 2) return null
        val next = rest[1]
        if (!next.isPunctuation()) return null
        out.append(plain(next.toString()))
        return 2
    }

    private fun code(rest: String, out: StringBuilder): Int? {
        val opener = rest.takeWhile { it == '`' }
        var cursor = opener.length
        while (true) {
            val close = rest.indexOf(opener, cursor)
            if (close < 0) return null
            // 闭合的那串必须正好一样长：后面还跟着反引号就说明这不算闭合
            if (rest.getOrNull(close + opener.length) != '`') {
                var body = rest.substring(opener.length, close)
                if (body.length >= 2 && body.first() == ' ' && body.last() == ' ') body = body.substring(1, body.length - 1)
                out.append(tag("code", body, escaped = false))
                return close + opener.length
            }
            cursor = close + opener.length
        }
    }

    private fun image(rest: String, out: StringBuilder): Int? {
        val target = linkTarget(rest, "!") ?: return null
        notes.images++
        return if (html) {
            out.append(img(target.alt, target.url, target.title))
            target.consumed
        } else {
            out.append(target.alt)
            target.consumed
        }
    }

    private fun link(rest: String, out: StringBuilder): Int? {
        val target = linkTarget(rest, "") ?: return null
        notes.links++
        return if (html) {
            out.append(anchor(target.text, target.url, target.title))
            target.consumed
        } else {
            out.append(target.text)
            target.consumed
        }
    }

    private fun autoLink(rest: String, out: StringBuilder): Int? {
        val close = rest.indexOf('>')
        if (close < 2) return null
        val body = rest.substring(1, close)
        if (!body.matches(Regex("""[a-zA-Z][a-zA-Z0-9+.-]*:[^ >]*""")) && !body.contains('@')) return null
        val url = if (body.contains('@') && !body.contains(':')) "mailto:$body" else body
        notes.links++
        out.append(if (html) "<a href=\"${attribute(url)}\">${text(url)}</a>" else url)
        return close + 1
    }

    private fun emphasis(rest: String, out: StringBuilder, previous: Char?): Int? {
        val marker = rest.first()
        // `_` 在词中间不算强调：snake_case 的变量名不该变斜体
        if (marker == '_' && previous != null && previous.isLetterOrDigit()) return null
        for (size in listOf(3, 2, 1)) {
            if (!rest.startsWith(marker.toString().repeat(size))) continue
            if (rest.getOrNull(size)?.isWhitespace() == true) continue         // 开标记后面是空白就不算开启
            val closer = findCloser(rest, size, marker) ?: continue
            val body = rest.substring(size, closer)
            if (body.isBlank()) continue
            val inner = build(body)
            val rendered = when {
                !html -> inner
                size == 3 -> "<strong><em>$inner</em></strong>"
                size == 2 -> "<strong>$inner</strong>"
                else -> "<em>$inner</em>"
            }
            out.append(rendered)
            return closer + size
        }
        return null
    }

    /** 找闭合的那串标记：前面不能是空白（`a * b*` 不算强调），串尾算合法结尾。 */
    private fun findCloser(rest: String, size: Int, marker: Char): Int? {
        val needle = marker.toString().repeat(size)
        var cursor = size
        while (true) {
            val at = rest.indexOf(needle, cursor)
            if (at < 0) return null
            // 闭合只看前面贴不贴字：`乙* ` 里 * 后面跟空格，照样是合法闭合
            val before = rest[at - 1]
            if (!before.isWhitespace()) return at
            cursor = at + size
        }
    }

    private fun strike(rest: String, out: StringBuilder): Int? {
        if (!rest.startsWith("~~")) return null
        val close = rest.indexOf("~~", 2)
        if (close < 0) return null
        val body = rest.substring(2, close)
        if (body.isBlank()) return null
        out.append(if (html) "<del>${build(body)}</del>" else body)
        return close + 2
    }

    /** `[文字](地址 "标题")` 与 `[文字][引用]`；返回吃掉的长度，地址与文字一起给。 */
    private fun linkTarget(rest: String, prefix: String): Linked? {
        if (!rest.startsWith("$prefix[")) return null
        val close = matching(rest, prefix.length, '[', ']') ?: return null
        val label = rest.substring(prefix.length + 1, close)
        var cursor = close + 1
        var url = ""
        var title: String? = null
        if (cursor < rest.length && rest[cursor] == '(') {
            // 地址里可以带括号（维基那类链接），所以按配对数找右括号，不是见第一个就收
            val end = matching(rest, cursor, '(', ')') ?: return null
            val inside = rest.substring(cursor + 1, end).trim()
            val split = Regex("""^(\S*)[ \t]*(?:"([^"]*)"|'([^']*)'|\(([^)]*)\))?$""").find(inside)
            url = split?.groupValues?.get(1).orEmpty()
            title = listOfNotNull(split?.groupValues?.get(2), split?.groupValues?.get(3), split?.groupValues?.get(4))
                .firstOrNull { it.isNotEmpty() }
            cursor = end + 1
        } else {
            val reference = if (cursor < rest.length && rest[cursor] == '[') {
                val refClose = matching(rest, cursor, '[', ']') ?: return null
                val id = rest.substring(cursor + 1, refClose).ifBlank { label }
                cursor = refClose + 1
                id
            } else label
            val target = refs[reference.lowercase()] ?: return null
            url = target.url
            title = target.title
        }
        return Linked(build(label), label, url, title, cursor)
    }

    private class Linked(val text: String, val alt: String, val url: String, val title: String?, val consumed: Int)

    /** 找配对的括号，能躲开字符串里的括号与嵌套。 */
    private fun matching(text: String, from: Int, open: Char, close: Char): Int? {
        var depth = 0
        var index = from
        while (index < text.length) {
            when (text[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun tag(name: String, body: String, escaped: Boolean) =
        if (html) "<$name>${if (escaped) text(body) else codeText(body)}</$name>" else body

    private fun anchor(label: String, url: String, title: String?): String {
        val t = if (title == null) "" else " title=\"${attribute(title)}\""
        return "<a href=\"${attribute(url)}\"$t>$label</a>"
    }

    private fun img(alt: String, url: String, title: String?): String {
        val t = if (title == null) "" else " title=\"${attribute(title)}\""
        return "<img src=\"${attribute(url)}\" alt=\"${attribute(plainText(alt))}\"$t />"
    }

    /** 输出侧的文字：HTML 要转义三个字符，纯文本原样。 */
    private fun text(value: String) = if (html) escapeHtml(value) else value

    private fun plain(value: String) = text(value)

    private fun codeText(value: String) = escapeHtml(value)

    private fun plainText(value: String) = Regex("""\\(.)""").replace(value) { it.groupValues[1] }

    private fun attribute(value: String): String = escapeHtml(value)

    private fun Char.isPunctuation(): Boolean = this in "![\"#\$%&'()*+,-./:;<=>?@[]^_`{|}~"

    /** 内嵌 HTML 与链接/图片的账，最后翻成"这份文件里有什么要交代的"。 */
    internal class Notes {
        var links = 0
        var images = 0
        var rawHtml = 0
    }
}

internal fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}
