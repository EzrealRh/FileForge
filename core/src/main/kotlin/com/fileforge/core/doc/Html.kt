package com.fileforge.core.doc

/**
 * HTML → 纯文本 / Markdown。
 *
 * 网页转文字是转换器的高频项，而真网页几乎都不是合法 XML：标签不闭合、大小写混着写、
 * 裸 `&`、注释里再套标签。所以这里自己走一遍容错的词法与建树（见 HtmlTokens / HtmlTree），
 * 并且把"补了几处、丢了什么"如实说出来 —— 结构靠猜的部分必须露在明面上。
 */
object Html {

    /** 不是给人读的文字：整段丢。 */
    private val DROP = setOf("head", "script", "style", "noscript", "template", "svg", "canvas", "iframe")

    private val FORMS = setOf("input", "textarea", "button", "select", "option", "label")

    /** Markdown → HTML 那条路用的块级名单，反过来也要认。 */
    private val BLOCKS = HtmlTree.BLOCKS

    private val WHITESPACE_RUN = Regex("\\s+")

    /** HTML → 纯文本：结构尽量留（列表记号、表格分列、段落空行），装饰性标记吃掉。 */
    fun toPlainText(source: String): Rendered = render(source, markdown = false)

    /** HTML → Markdown：认得出结构的写成标记，认不出的退化成纯文本。 */
    fun toMarkdown(source: String): Rendered = render(source, markdown = true)

    /**
     * 这份文字里有没有 HTML 标记？转之前先问一句：一份普通文本被"抽掉标记"之后看着一模一样，
     * 只是尖括号里的东西没了 —— 那种"看着成功其实丢了字"的产物不如不给。
     */
    fun looksLikeHtml(source: String): Boolean = MARKS.any { source.contains(it, ignoreCase = true) }

    /** 只认这些有鼻子有眼的标签：光有一个 `<b` 说明不了什么，数学式子里到处都是。 */
    private val MARKS = listOf(
        "<!doctype", "<html", "<head", "<body", "</p>", "<p>", "<div", "<span", "<br", "<hr",
        "<table", "<ul", "<ol", "<li", "<h1", "<h2", "<h3", "<a href", "<img", "<title", "<meta",
    )

    private class Ctx(val markdown: Boolean, val counted: Counted)

    private class Counted {
        var links = 0
        var images = 0
        var dropped = 0
        var tables = 0
        var forms = 0
    }

    private fun render(source: String, markdown: Boolean): Rendered {
        val tally = EntityTally()
        val built = HtmlTree.build(HtmlTokens.tokenize(source, tally))
        val counted = Counted()
        val blocks = ArrayList<String>()
        inside(built.root, Ctx(markdown, counted), blocks, "")
        val notes = ArrayList<String>()
        if (built.repaired > 0) notes += "源文件有 ${built.repaired} 处标签没按规矩闭合，按浏览器那套补的"
        if (tally.unknown > 0) notes += "${tally.unknown} 处实体没认出来，照字面留下了"
        if (counted.dropped > 0) notes += "${counted.dropped} 段脚本/样式/页眉内容丢掉（那不是给人读的文字）"
        if (counted.tables > 0) notes += "${counted.tables} 张表按行拍平（合并单元格读不出跨度）"
        // 表单的值与选中项在属性里，两种输出都只剩标签文字 —— 退化了就得说，跟输出格式无关
        if (counted.forms > 0) notes += "${counted.forms} 处表单控件只留下标签文字（填的值与选中项在属性里，读不出来）"
        if (!markdown) {
            if (counted.links > 0) notes += "${counted.links} 处链接只留下文字，地址在纯文本里没处放"
            if (counted.images > 0) notes += "${counted.images} 处图片只剩替代文字"
        }
        val text = blocks.joinToString("\n\n")
        return Rendered(if (text.isEmpty()) "" else "$text\n", notes)
    }

    /** 一个容器里的子节点：块级元素各成一块，剩下的行内内容攒成一块。 */
    private fun inside(node: HtmlNode, ctx: Ctx, out: ArrayList<String>, indent: String) {
        val run = StringBuilder()
        node.children.forEach { child ->
            if (child.name.isEmpty()) return@forEach
            if (child.name in DROP) {
                ctx.counted.dropped++
                return@forEach
            }
            if (child.name in BLOCKS || holdsBlock(child)) {
                // html / body / 各种包装 div 不在块级名单里，但里面装着块级元素 —— 不能当一坨文字吞掉
                if (run.isNotBlank()) out += pad(run.toString().trim(), ctx, indent)
                run.setLength(0)
                block(child, ctx, out, indent)
            } else {
                inline(child, run, ctx)
            }
        }
        if (run.isNotBlank()) out += pad(run.toString().trim(), ctx, indent)
    }

    private fun holdsBlock(node: HtmlNode) = node.children.any { it.name in BLOCKS || it.name in DROP }

    private fun pad(rendered: String, ctx: Ctx, indent: String): String =
        if (ctx.markdown || indent.isEmpty()) rendered
        else rendered.lines().joinToString("\n") { if (it.isEmpty()) it else "$indent$it" }

    private fun block(node: HtmlNode, ctx: Ctx, out: ArrayList<String>, indent: String) {
        when {
            node.name == "hr" -> out += pad(if (ctx.markdown) "---" else "----------", ctx, indent)
            node.name in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> {
                val run = StringBuilder()
                children(node, run, ctx)
                val body = run.toString().trim()
                if (body.isNotEmpty()) {
                    out += pad(
                        if (ctx.markdown) "#".repeat(node.name.removePrefix("h").toInt()) + " " + body else body,
                        ctx, indent,
                    )
                }
            }
            node.name == "pre" -> out += pad(code(node, ctx), ctx, indent)
            node.name == "blockquote" -> {
                val inner = ArrayList<String>()
                inside(node, ctx, inner, "")
                val body = inner.joinToString("\n\n")
                out += pad(if (ctx.markdown) body.lines().joinToString("\n") { "> $it" } else body, ctx, indent)
            }
            node.name == "ul" || node.name == "ol" -> out += pad(list(node, ctx, indent), ctx, indent)
            node.name == "table" -> out += pad(table(node, ctx), ctx, indent)
            else -> {
                // 段落、div、dl/dt/dd、标题以外的容器：里面的块各自成块，行内容攒成一块
                inside(node, ctx, out, indent)
            }
        }
    }

    private fun code(node: HtmlNode, ctx: Ctx): String {
        val run = StringBuilder()
        children(node, run, ctx, raw = true)
        val body = run.toString().trim('\n')
        if (!ctx.markdown) return body
        val language = firstCodeClass(node)
        return "```$language\n$body\n```"
    }

    private fun firstCodeClass(node: HtmlNode): String {
        val classes = node.children.firstOrNull { it.name == "code" }?.attrs?.get("class").orEmpty()
        val fallback = node.attrs["class"].orEmpty()
        return (classes.ifBlank { fallback }).split(' ').firstOrNull { it.startsWith("language-") }
            ?.removePrefix("language-").orEmpty()
    }

    private fun list(node: HtmlNode, ctx: Ctx, indent: String): String {
        val ordered = node.name == "ol"
        val items = node.children.filter { it.name == "li" }
        val out = StringBuilder()
        items.forEachIndexed { position, item ->
            val marker = if (ordered) "${position + 1}. " else "- "
            val body = StringBuilder()
            val nested = ArrayList<String>()
            if (item.children.any { it.name in BLOCKS }) {
                // 项里有块级元素（段落或子列表）：先收行内部分，子列表另起一行接着排
                val run = StringBuilder()
                item.children.forEach { child ->
                    if (child.name in setOf("ul", "ol", "table", "pre", "blockquote")) {
                        if (run.isNotBlank()) { nested += run.toString().trim(); run.setLength(0) }
                        val inner = ArrayList<String>()
                        block(child, ctx, inner, "")
                        nested += inner.joinToString("\n\n")
                    } else inline(child, run, ctx)
                }
                if (run.isNotBlank()) nested += run.toString().trim()
            } else {
                children(item, body, ctx)
                nested += body.toString().trim()
            }
            val head = nested.firstOrNull().orEmpty()
            out.append(indent).append(marker).append(head).append("\n")
            nested.drop(1).forEach { extra ->
                extra.lines().forEach { out.append(indent).append("  ").append(it).append("\n") }
            }
        }
        return out.toString().trimEnd('\n')
    }

    private fun table(node: HtmlNode, ctx: Ctx): String {
        ctx.counted.tables++
        val rows = ArrayList<List<String>>()
        node.children.forEach { row ->
            if (row.name in BLOCKS && row.name != "tr") {
                row.children.filter { it.name == "tr" }.forEach { tr -> rows += cells(tr, ctx) }
            } else if (row.name == "tr") {
                rows += cells(row, ctx)
            }
        }
        if (rows.isEmpty()) return ""
        if (!ctx.markdown) return rows.joinToString("\n") { it.joinToString("\t") }
        val width = rows.maxOf { it.size }
        fun line(cells: List<String>): String {
            val filled = ArrayList(cells)
            while (filled.size < width) filled += ""
            return filled.joinToString(" | ", prefix = "| ", postfix = " |") {
                it.replace("|", "\\|").ifBlank { " " }
            }
        }
        val out = ArrayList<String>()
        out += line(rows.first())
        out += "|" + List(width) { "---" }.joinToString("|") + "|"
        rows.drop(1).forEach { out += line(it) }
        return out.joinToString("\n")
    }

    private fun cells(row: HtmlNode, ctx: Ctx): List<String> {
        val out = ArrayList<String>()
        row.children.forEach { cell ->
            if (cell.name in setOf("td", "th")) {
                val run = StringBuilder()
                children(cell, run, ctx)
                out += run.toString().trim()
            }
        }
        return out
    }

    private fun children(node: HtmlNode, into: StringBuilder, ctx: Ctx, raw: Boolean = false) {
        node.children.forEach { inline(it, into, ctx, raw) }
    }

    private fun inline(node: HtmlNode, into: StringBuilder, ctx: Ctx, raw: Boolean = false) {
        if (node.name in DROP) {
            ctx.counted.dropped++
            return
        }
        if (node.raw()) {
            // 原始文本元素：textarea 里是给人看的草稿（空白要照原样留着），script/style/title/iframe 里不是
            if (node.name == "#raw:textarea") into.append(if (ctx.markdown) escape(node.text) else node.text)
            else ctx.counted.dropped++
            return
        }
        if (raw) {
            // <pre> 里面没有标记可言：整棵子树只取文字，别再套 `code` 的反引号
            if (node.isText()) into.append(node.text)
            else node.children.forEach { child -> inline(child, into, ctx, raw = true) }
            return
        }
        when {
            node.isText() -> into.append(write(node.text, ctx))
            node.name == "br" -> into.append(if (ctx.markdown) "  \n" else "\n")
            node.name == "img" -> {
                ctx.counted.images++
                val alt = node.attrs["alt"].orEmpty()
                into.append(if (ctx.markdown) "![${escape(alt)}](${node.attrs["src"].orEmpty()})" else alt)
            }
            node.name == "a" -> {
                ctx.counted.links++
                val body = StringBuilder()
                children(node, body, ctx)
                val href = node.attrs["href"].orEmpty()
                val label = body.toString()
                into.append(
                    when {
                        !ctx.markdown -> label
                        href.isBlank() || href.startsWith("#") -> label
                        else -> "[$label]($href)"
                    },
                )
            }
            node.name in FORMS -> {
                ctx.counted.forms++
                children(node, into, ctx)
            }
            else -> {
                val body = StringBuilder()
                children(node, body, ctx)
                into.append(decorate(node.name, body.toString(), ctx))
            }
        }
    }

    private fun decorate(name: String, body: String, ctx: Ctx): String {
        if (!ctx.markdown || body.isBlank()) return body
        return when (name) {
            "strong", "b" -> "**$body**"
            "em", "i" -> "*$body*"
            "del", "s", "strike" -> "~~$body~~"
            "code", "kbd", "samp", "var", "tt" -> "`$body`"
            "sub" -> "~$body~"
            "sup" -> "^$body^"
            else -> body
        }
    }

    private fun write(value: String, ctx: Ctx): String {
        // 源文件里的换行与缩进是写给编辑器看的，浏览器一律并成一个空格（&nbsp; 是 \u00A0，不在 \s 里）
        val collapsed = value.replace(WHITESPACE_RUN, " ")
        return if (ctx.markdown) escape(collapsed) else collapsed
    }

    /** Markdown 里这些字符有别的用处，正文中出现要转义；行首的更凶，单独处理。 */
    private fun escape(value: String): String = buildString {
        value.forEachIndexed { index, ch ->
            val atLineStart = index == 0 || this[lastIndex] == '\n'
            when {
                ch in "\\`*_[]<>" || (ch == '~' && value.getOrNull(index + 1) == '~') -> append('\\').append(ch)
                ch == '#' && atLineStart -> append("\\#")
                (ch == '-' || ch == '+') && atLineStart && value.getOrNull(index + 1) == ' ' -> append('\\').append(ch)
                ch == '>' && atLineStart -> append("\\>")
                else -> append(ch)
            }
        }
    }
}
