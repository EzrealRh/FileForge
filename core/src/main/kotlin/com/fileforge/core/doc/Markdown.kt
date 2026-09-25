package com.fileforge.core.doc

/**
 * Markdown 渲染：HTML 与纯文本两种输出。
 *
 * 认的语法是 CommonMark 加上 GFM 里最常用的三样（表格、任务列表、删除线），外加围栏代码与
 * Setext 标题。**认不出的写法照字面留下并说明** —— 转换工具最坏的输出不是"没渲染"，是"字少了"。
 *
 * 这里不含安卓与网络 API：整份渲染能脱机单测，也能拿 pandoc 逐块对。
 */
object Markdown {

    // `[^id]:` 开头的是脚注定义，不是链接引用 —— 当定义吃掉就会少一行正文
    private val REFERENCE = Regex(
        """^ {0,3}\[([^\^\]]+)\]:[ \t]*(\S+)(?:[ \t]+(?:"([^"]*)"|'([^']*)'|\(([^)]*)\)))?[ \t]*$""",
    )
    private val FOOTNOTE_DEF = Regex("""^ {0,3}\[\^[^\]]+\]:""")
    private val FOOTNOTE_USE = Regex("""\[\^[^\]]+\]""")
    private val FENCE_LINE = Regex("""^---[ \t]*$""")

    /** Markdown → HTML。 */
    fun toHtml(source: String): Rendered = render(source, html = true)

    /** Markdown → 纯文本：标记吃掉，正文与结构（列表符号、表格分列）留下。 */
    fun toPlainText(source: String): Rendered = render(source, html = false)

    // 只锚了行首的判据一律用 containsMatchIn：Regex.matches 要整串匹配，
    // 用它 `# 标题` 会判成 false（HTML_START 那处踩过同一个坑）。
    /**
     * 这份文本像不像 Markdown。
     *
     * 只为一个判断服务：**不像就明说不像**，而不是硬渲染一份"看着一样但少了星号"的文件。
     * 纯 Markdown 里全是普通段落时确实认不出来，那种文件转 HTML 本来也没意义，
     * 所以宁可拒，让用户自己决定要不要按 txt 处理。
     */
    fun looksLikeMarkdown(source: String): Boolean = source.lineSequence().any { line ->
        HEADING.containsMatchIn(line) || FENCE_START.containsMatchIn(line) || LIST_MARKER.containsMatchIn(line) ||
            QUOTED.containsMatchIn(line) || TABLE_DELIM.matches(line) || SETEXT_UNDERLINE.matches(line)
    }

    private val HEADING = Regex("""^ {0,3}#{1,6}[ \t]""")

    private val FENCE_START = Regex("""^ {0,3}(```|~~~)""")
    private val LIST_MARKER = Regex("""^ {0,3}([-*+]|[0-9]{1,9}[.)])[ \t]+\S""")
    private val QUOTED = Regex("""^ {0,3}>""")
    private val SETEXT_UNDERLINE = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
    private val TABLE_DELIM = Regex("""^ {0,3}\|?[ \t]*:?-{1,}:?[ \t]*(\|[ \t]*:?-{1,}:?[ \t]*)*\|?[ \t]*$""")

    private fun render(source: String, html: Boolean): Rendered {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val notes = ArrayList<String>()
        var from = 0
        if (lines.size > 1 && FENCE_LINE.matches(lines[0])) {
            val end = (1 until lines.size).firstOrNull { FENCE_LINE.matches(lines[it]) }
            if (end != null) {
                notes += "开头的 YAML 头（第 1 到 ${end + 1} 行）没当正文，照字面留下了"
                from = end + 1
            }
        }
        val refs = LinkedHashMap<String, Inline.LinkTarget>()
        val body = ArrayList<String>()
        var footnotes = 0
        var index = from
        while (index < lines.size) {
            val line = lines[index]
            val definition = REFERENCE.matchEntire(line)
            when {
                definition != null -> {
                    refs[definition.groupValues[1].lowercase()] = Inline.LinkTarget(
                        definition.groupValues[2],
                        listOf(definition.groupValues.getOrNull(3), definition.groupValues.getOrNull(4), definition.groupValues.getOrNull(5))
                            .firstOrNull { !it.isNullOrEmpty() },
                    )
                    index++                                                     // 定义行自己不产出内容
                }
                else -> {
                    footnotes += if (FOOTNOTE_DEF.containsMatchIn(line)) 1 else FOOTNOTE_USE.findAll(line).count()
                    body += line
                    index++
                }
            }
        }
        if (footnotes > 0) notes += "脚注语法不认，$footnotes 处照字面留下"
        val indented = body.withIndex().count { (at, line) ->
            line.startsWith("    ") && (at == 0 || body[at - 1].isBlank())
        }
        if (indented > 0) {
            notes += "$indented 处四格缩进的行按普通段落处理了 —— Markdown 里那本来是代码块的写法，用反引号围起来才稳"
        }

        val counted = Inline.Notes()
        val ctx = Ctx(html, Inline(html, refs, counted), counted)
        val rendered = blocks(MarkdownBlocks.parse(body), ctx, "")
        notes += told(ctx)
        return Rendered(if (rendered.isEmpty()) "" else "$rendered\n", notes.distinct())
    }

    /** 给外面拼 HTML 用的转义（页面标题里放的是文件名，什么字符都可能）。 */
    fun htmlEscape(value: String): String = escapeHtml(value)

    private class Ctx(
        val html: Boolean,
        val inline: Inline,
        val counted: Inline.Notes,
    )

    /** 块与块之间：HTML 一行接一行（空白 insignificant），纯文本空一行才看得出分段。 */
    private fun blocks(list: List<Block>, ctx: Ctx, indent: String, tight: Boolean = false): String =
        list.joinToString(if (ctx.html) "\n" else "\n\n") { pad(block(it, ctx, indent, tight), ctx, indent) }

    private fun block(item: Block, ctx: Ctx, indent: String, tight: Boolean): String = when (item) {
        is Head -> if (ctx.html) "<h${item.level}>${text(item.text, ctx)}</h${item.level}>" else text(item.text, ctx)
        is Para -> if (tight && ctx.html) paragraphLines(item, ctx) else paragraph(item, ctx)
        is Code -> code(item, ctx)
        is Hr -> if (ctx.html) "<hr />" else "----------"
        is Quote -> quote(item, ctx)
        is Table -> table(item, ctx)
        is ListBlock -> list(item, ctx, indent)
        is HtmlBlock -> {
            ctx.counted.rawHtml++
            item.lines.joinToString("\n")
        }
    }

    private fun pad(rendered: String, ctx: Ctx, indent: String): String =
        if (ctx.html || indent.isEmpty()) rendered
        else rendered.lines().joinToString("\n") { if (it.isEmpty()) it else "$indent$it" }

    private fun text(value: String, ctx: Ctx): String = ctx.inline.render(value)

    /** 段落体：不包 `<p>`，给紧列表的项复用。 */
    private fun paragraphLines(block: Para, ctx: Ctx): String = block.lines.map { line ->
        val hard = line.endsWith("  ") || line.endsWith("\\")
        val body = text(line.trimEnd(' ', '\\'), ctx)
        if (ctx.html && hard) "$body<br />" else body
    }.joinToString("\n")

    private fun paragraph(block: Para, ctx: Ctx): String =
        if (ctx.html) "<p>${paragraphLines(block, ctx)}</p>" else paragraphLines(block, ctx)

    private fun code(block: Code, ctx: Ctx): String {
        val body = block.lines.joinToString("\n")
        if (!ctx.html) return body
        val info = block.info?.let { """ class="language-${escapeHtml(it)}"""" }.orEmpty()
        return "<pre><code$info>\n${escapeHtml(body)}\n</code></pre>"
    }

    private fun quote(block: Quote, ctx: Ctx): String {
        val inner = blocks(block.blocks, ctx, "")
        return if (ctx.html) "<blockquote>\n$inner\n</blockquote>"
        else inner.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    }

    private fun table(block: Table, ctx: Ctx): String {
        if (!ctx.html) {
            return (listOf(block.header) + block.rows).joinToString("\n") { row ->
                row.joinToString("\t") { text(it, ctx) }
            }
        }
        fun align(at: Int): String = when (block.aligns.getOrNull(at)) {
            Align.Left -> """ align="left""""
            Align.Center -> """ align="center""""
            Align.Right -> """ align="right""""
            else -> ""
        }
        val out = StringBuilder("<table>\n<thead>\n<tr>")
        block.header.forEachIndexed { at, cell -> out.append("<th${align(at)}>${text(cell, ctx)}</th>") }
        out.append("</tr>\n</thead>\n<tbody>")
        block.rows.forEach { row ->
            out.append("\n<tr>")
            row.forEachIndexed { at, cell -> out.append("<td${align(at)}>${text(cell, ctx)}</td>") }
            out.append("</tr>")
        }
        return out.append("\n</tbody>\n</table>").toString()
    }

    private fun list(block: ListBlock, ctx: Ctx, indent: String): String {
        val items = block.items.mapIndexed { position, item -> itemText(block, position, item, ctx, indent) }
        return if (ctx.html) {
            val open = if (!block.ordered) "<ul>" else if (block.start == 1) "<ol>" else "<ol start=\"${block.start}\">"
            items.joinToString("\n", prefix = "$open\n", postfix = "\n${if (block.ordered) "</ol>" else "</ul>"}")
        } else {
            items.joinToString("\n")
        }
    }

    private fun itemText(block: ListBlock, position: Int, item: ListItem, ctx: Ctx, indent: String): String {
        val marker = if (block.ordered) "${block.start + position}. " else "- "
        val box = when (item.checked) {
            true -> if (ctx.html) "<input type=\"checkbox\" disabled=\"\" checked=\"\" /> " else "[x] "
            false -> if (ctx.html) "<input type=\"checkbox\" disabled=\"\" /> " else "[ ] "
            null -> ""
        }
        if (ctx.html) {
            // 紧列表：项里的段落不套 <p>（紧/松是整个列表的属性，不是"这项只有一段"才不套）
            return "<li>$box${blocks(item.blocks, ctx, "", block.tight)}</li>"
        }
        // 纯文本：首行跟着列表记号走，其余行往右让两格；项内的空行在纯文本里不表结构，收掉
        val inner = blocks(item.blocks, ctx, "").lines().filter { it.isNotEmpty() }
        return (indent + marker + box + inner.first()) +
            inner.drop(1).joinToString("") { "\n$indent  $it" }
    }

    private fun told(ctx: Ctx): List<String> {
        val notes = ArrayList<String>()
        if (ctx.counted.links > 0 && !ctx.html) notes += "${ctx.counted.links} 处链接只留下文字，地址与标题在纯文本里没处放"
        if (ctx.counted.images > 0 && !ctx.html) notes += "${ctx.counted.images} 处图片在纯文本里只剩替代文字"
        if (ctx.counted.rawHtml > 0) {
            notes += if (ctx.html) "${ctx.counted.rawHtml} 段内嵌 HTML 原样搬过去，没检查它是不是合法标签"
            else "${ctx.counted.rawHtml} 段内嵌 HTML 连着标签一起留下（这一步不解析 HTML）"
        }
        return notes
    }
}
