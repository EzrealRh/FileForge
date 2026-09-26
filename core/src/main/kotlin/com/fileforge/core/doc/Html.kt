package com.fileforge.core.doc

/**
 * 一张抽出来的表：名字、按跨度对好的格子，以及这张表的毛病。
 *
 * `named` 说清名字是哪儿来的：表题（作者写的）还是序号（我们补的）—— 拿名字起文件名的那一侧要分开对待。
 */
class HtmlTable(
    val name: String,
    val named: Boolean,
    val rows: List<List<String>>,
    val notes: List<String>,
)

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

    /**
     * 网页里的所有表，按出现的先后。
     *
     * 这里做的是**按跨度对位**：`colspan` / `rowspan` 会占住它盖到的那些位置，被盖到的格子留空。
     * 不这么做的话，一个 `<td colspan="3">` 就把整行左移三格，出来的表"看着齐，其实整列错位" ——
     * 那是转表格最容易出的、也最难在成品里看出来的错。
     */
    fun toTables(source: String): List<HtmlTable> {
        val tally = EntityTally()
        val built = HtmlTree.build(HtmlTokens.tokenize(source, tally))
        val found = ArrayList<HtmlNode>()
        collectTables(built.root, found)
        val ctx = Ctx(markdown = false, Counted())
        return found.mapIndexed { index, node -> oneTable(index, node, ctx) }
    }

    private fun collectTables(node: HtmlNode, out: ArrayList<HtmlNode>) {
        node.children.forEach { child ->
            if (child.name == "table") {
                out += child
                collectTables(child, out)                    // 嵌套表也单独出一张，同时在外层的格子里留文字
            } else {
                collectTables(child, out)
            }
        }
    }

    private fun oneTable(index: Int, node: HtmlNode, ctx: Ctx): HtmlTable {
        val notes = ArrayList<String>()
        val caption = node.children.firstOrNull { it.name == "caption" }?.let { text(it, ctx) }.orEmpty()
        if (caption.isNotEmpty()) notes += "表题「$caption」只用来起文件名，不进格子"

        val placed = placeTable(node, ctx)
        val square = placed.rows
        if (square.isEmpty()) notes += "这张表里没有格子"
        if (placed.merged > 0) {
            notes += "${placed.merged} 格带 colspan/rowspan，被它盖到的位置留了空格子（CSV 装不下跨格）"
        }
        if (placed.clamped > 0) notes += "${placed.clamped} 处跨度大得不合理（超过 $MAX_SPAN 格），按 $MAX_SPAN 算"
        if (holdsTable(node)) notes += "这张表里嵌着表，外面这张只留格子里的文字，嵌着的另出一张"
        return HtmlTable(name(index, caption), caption.isNotBlank(), square, notes)
    }

    /** 一张表摆好的方格，以及跨度上的两笔账。 */
    private class Placed(val rows: List<List<String>>, val merged: Int, val clamped: Int)

    /**
     * 按跨度把格子摆进方格：`colspan` / `rowspan` 盖住的位置留空，后面的格子跳过它们。
     *
     * 纯文本、Markdown、CSV 三条输出共用这一处对位 —— 三条各摆一套，就会出现在"哪一格是第几列"
     * 上互相不一样的表，那种不一致比摆错更难查。
     */
    private fun placeTable(node: HtmlNode, ctx: Ctx): Placed {
        val grid = Grid()
        var merged = 0
        var clamped = 0
        tableRows(node).forEachIndexed { rowAt, tr ->
            var column = 0
            tr.children.filter { it.name == "td" || it.name == "th" }.forEach { cell ->
                while (grid.occupied(rowAt, column)) column++
                val spanX = span(cell.attrs["colspan"]) { clamped++ }
                val spanY = span(cell.attrs["rowspan"]) { clamped++ }
                if (spanX > 1 || spanY > 1) merged++
                grid.put(rowAt, column, text(cell, ctx))
                for (dy in 0 until spanY) for (dx in 0 until spanX) {
                    if (dy != 0 || dx != 0) grid.cover(rowAt + dy, column + dx)
                }
                column += spanX
            }
        }
        return Placed(grid.square(), merged, clamped)
    }

    /** 一个格子里有没有嵌表（外面这张只能把它压成文字）。 */
    private fun holdsTable(node: HtmlNode): Boolean = node.children.any {
        it.name == "table" || holdsTable(it)
    }

    private fun name(index: Int, caption: String): String = caption.ifBlank { "第 ${index + 1} 张表" }

    /**
     * 这张表自己的行：碰到嵌进去的表就停（那些行属于里面那张）。
     *
     * 没有 `<tr>` 的散格子（`<table><td>甲</td><td>乙</td></table>`，网页里真这么写的不少）
     * 按浏览器那样并成一行 —— 丢掉它就是静悄悄少一格。
     */
    private fun tableRows(node: HtmlNode): List<HtmlNode> {
        val out = ArrayList<HtmlNode>()
        val loose = ArrayList<HtmlNode>()
        fun walk(from: HtmlNode) {
            from.children.forEach { child ->
                when {
                    child.name == "tr" -> out += child
                    child.name == "table" -> Unit
                    child.name == "td" || child.name == "th" -> loose += child
                    child.name in BLOCKS -> walk(child)
                }
            }
        }
        walk(node)
        if (out.isEmpty() && loose.isNotEmpty()) out += HtmlNode("tr").apply { children += loose }
        return out
    }

    private fun span(value: String?, clamp: () -> Unit): Int {
        val raw = value?.trim()?.toIntOrNull() ?: return 1
        if (raw < 1) return 1
        if (raw > MAX_SPAN) {
            clamp()
            return MAX_SPAN
        }
        return raw
    }

    /**
     * 对位用的方格：值与"被上一格的跨度盖住的位置"分开记，
     * 这样空格子（真没有内容）与被盖住的位置不会混成一回事。
     */
    private class Grid {
        private val cells = ArrayList<ArrayList<String?>>()
        private val covered = HashSet<Long>()

        private fun key(row: Int, column: Int) = (row.toLong() shl 32) or column.toLong()

        private fun line(row: Int): ArrayList<String?> {
            while (cells.size <= row) cells.add(ArrayList())
            return cells[row]
        }

        fun occupied(row: Int, column: Int): Boolean =
            cells.getOrNull(row)?.getOrNull(column) != null || key(row, column) in covered

        fun cover(row: Int, column: Int) {
            line(row)
            covered += key(row, column)
        }

        fun put(row: Int, column: Int, text: String) {
            val line = line(row)
            while (line.size <= column) line.add(null)
            line[column] = text
        }

        /** 补齐成方表：短的那几行右边给空格子。 */
        fun square(): List<List<String>> {
            val width = cells.maxOfOrNull { it.size } ?: 0
            return cells.map { row -> List(width) { column -> row.getOrNull(column).orEmpty() } }
        }
    }

    /** 一个单元格里的文字：块与块之间换成行（Excel 里就是格子内换行），列表与嵌套表照块级规矩排。 */
    private fun text(cell: HtmlNode, ctx: Ctx): String {
        val run = StringBuilder()
        val parts = ArrayList<String>()
        cell.children.forEach { child ->
            if (child.name in DROP || child.name.isEmpty()) return@forEach
            if (child.name in BLOCKS && child.name != "br" && child.name != "hr") {
                if (run.isNotBlank()) { parts += run.toString().trim(); run.setLength(0) }
                val inner = ArrayList<String>()
                block(child, ctx, inner, "")
                val body = inner.joinToString("\n").trim()
                if (body.isNotEmpty()) parts += body
            } else {
                inline(child, run, ctx)
            }
        }
        if (run.isNotBlank()) parts += run.toString().trim()
        return parts.joinToString("\n")
    }

    private const val MAX_SPAN = 1000

    /**
     * HTML → 文档树（段落 + 带记号的文字 + 表格），Word 那条路用。
     *
     * 与纯文本那条的差别只有**记号留不留**：这里把 `strong` `em` `del` `code` 与链接翻成开关
     * 和能点开的真链接，建树与表格对位仍旧共用一套 —— 三份输出在"哪段是列表、哪格在第几列"上
     * 必须一致，各摆一套迟早会出现纯文本里是列表、Word 里成普通段落那种岔。
     */
    fun toDoc(source: String): Doc {
        val tally = EntityTally()
        val built = HtmlTree.build(HtmlTokens.tokenize(source, tally))
        val counted = Counted()
        val parts = ArrayList<DocPart>()
        docChildren(built.root, Ctx(markdown = false, counted), parts, 0, 0)
        val notes = ArrayList<String>()
        if (built.repaired > 0) notes += "源文件有 ${built.repaired} 处标签没按规矩闭合，按浏览器那套补的"
        if (tally.unknown > 0) notes += "${tally.unknown} 处实体没认出来，照字面留下了"
        if (counted.dropped > 0) notes += "${counted.dropped} 段脚本/样式/页眉内容丢掉（那不是给人读的文字）"
        if (counted.links > 0) notes += "${counted.links} 处链接写成 Word 里能点开的真链接"
        if (counted.anchors > 0) notes += "${counted.anchors} 处页内锚点与不认的地址只留下文字（没有落点可跳）"
        if (counted.tables > 0) notes += "${counted.tables} 张表按跨度摆成表格（跨过的格子留空）"
        if (counted.images > 0) notes += "${counted.images} 处图片只剩替代文字（图片本体不在文字里）"
        if (counted.nestedQuotes > 0) notes += "${counted.nestedQuotes} 处嵌套引用压成多缩一层的引用段（Word 里没有第二层引用样式）"
        if (counted.rules > 0) notes += "${counted.rules} 处分隔线画成带下边线的空段（Word 里没有横线这个块）"
        if (counted.forms > 0) notes += "${counted.forms} 处表单控件只留下标签文字"
        return Doc(parts, notes)
    }

    /** 一个容器里的孩子：块级各自成块，剩下的行内内容攒成一个段落。 */
    private fun docChildren(node: HtmlNode, ctx: Ctx, parts: ArrayList<DocPart>, indent: Int, quote: Int) {
        val run = ArrayList<DocRun>()
        fun flush() {
            val kept = trimBlanks(run)
            if (kept.isNotEmpty()) parts += DocParagraph(DocPara(kept, if (quote > 0) "Quote" else "Body", indent + quote))
            run.clear()
        }
        node.children.forEach { child ->
            when {
                child.name.isEmpty() -> Unit
                child.name in DROP -> ctx.counted.dropped++
                child.name in BLOCKS || holdsBlock(child) -> {
                    flush()
                    docBlock(child, ctx, parts, indent, quote)
                }
                else -> docDocRun(child, ctx, run)
            }
        }
        flush()
    }

    private fun docBlock(node: HtmlNode, ctx: Ctx, parts: ArrayList<DocPart>, indent: Int, quote: Int) {
        val level = headingLevel(node.name)
        when {
            node.name == "hr" -> {
                ctx.counted.rules++
                parts += DocRule()
            }
            level > 0 -> {
                val run = ArrayList<DocRun>()
                node.children.forEach { child -> docDocRun(child, ctx, run) }
                val kept = trimBlanks(run)
                if (kept.isNotEmpty()) parts += DocParagraph(DocPara(kept, "Heading$level", indent))
            }
            node.name == "pre" -> {
                val source = StringBuilder()
                children(node, source, ctx, raw = true)
                val text = source.toString().trim('\n')
                if (text.isNotEmpty()) {
                    // 一整段一份代码块（行内用软回车）：拆成 N 段的话，Word 里粘回去就多了 N-1 个空行
                    parts += DocParagraph(DocPara(listOf(DocRun(text, mono = true)), "SourceCode", indent))
                }
            }
            node.name == "blockquote" -> {
                if (quote > 0) ctx.counted.nestedQuotes++
                docChildren(node, ctx, parts, indent, quote + 1)
            }
            node.name == "ul" || node.name == "ol" -> docList(node, ctx, parts, indent, quote)
            node.name == "table" -> docTable(node, ctx, parts)
            else -> docChildren(node, ctx, parts, indent, quote)
        }
    }

    private fun headingLevel(name: String): Int =
        if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') name[1] - '0' else 0

    private fun docList(node: HtmlNode, ctx: Ctx, parts: ArrayList<DocPart>, indent: Int, quote: Int) {
        val ordered = node.name == "ol"
        var number = node.attrs["start"]?.trim()?.toIntOrNull() ?: 1
        node.children.filter { it.name == "li" }.forEach { item ->
            val checkbox = item.children.firstOrNull { it.name == "input" && it.attrs["type"] == "checkbox" }
            val marker = when {
                checkbox == null -> ""
                checkbox.attrs.containsKey("checked") -> "☑ "
                else -> "☐ "
            }
            val run = ArrayList<DocRun>()
            item.children.forEach { child ->
                when {
                    child.name == "ul" || child.name == "ol" || child === checkbox -> Unit
                    child.name in BLOCKS && child.name != "br" -> child.children.forEach { inner -> docDocRun(inner, ctx, run) }
                    else -> docDocRun(child, ctx, run)
                }
            }
            val kept = trimBlanks(run)
            val box = checkbox
            if (box != null) ctx.counted.forms++
            val body = if (box != null && marker.isNotEmpty()) listOf(DocRun(marker)) + kept else kept
            if (body.isNotEmpty()) {
                parts += DocParagraph(DocPara(body, if (quote > 0) "Quote" else "ListParagraph", indent + quote, !ordered))
            }
            item.children.filter { it.name == "ul" || it.name == "ol" }
                .forEach { child -> docList(child, ctx, parts, indent + 1, quote) }
        }
    }

    private fun docTable(node: HtmlNode, ctx: Ctx, parts: ArrayList<DocPart>) {
        ctx.counted.tables++
        val placed = placeTable(node, ctx)
        if (placed.rows.isEmpty()) return
        val header = tableRows(node).firstOrNull()?.children?.any { it.name == "th" } == true
        parts += DocTable(header, placed.rows.map { row -> row.map { it.replace("\n", " ").trim() } })
    }

    private fun docDocRun(node: HtmlNode, ctx: Ctx, into: ArrayList<DocRun>) {
        if (node.name in DROP) {
            ctx.counted.dropped++
            return
        }
        if (node.raw()) {
            if (node.name == "#raw:textarea") into += DocRun(node.text) else ctx.counted.dropped++
            return
        }
        when {
            node.isText() -> into += DocRun(collapse(node.text))
            node.name == "br" -> into += DocRun("\n")
            node.name == "img" -> {
                ctx.counted.images++
                into += DocRun(node.attrs["alt"].orEmpty())
            }
            node.name == "a" -> {
                val href = linkTarget(node.attrs["href"].orEmpty())
                if (href != null) ctx.counted.links++ else ctx.counted.anchors++
                val inner = ArrayList<DocRun>()
                node.children.forEach { child -> docDocRun(child, ctx, inner) }
                inner.forEach { into += it.copy(link = href, underline = href != null) }
            }
            node.name in FORMS -> {
                ctx.counted.forms++
                node.children.forEach { child -> docDocRun(child, ctx, into) }
            }
            node.name in setOf("strong", "b") -> into += marked(node, ctx) { it.copy(bold = true) }
            node.name in setOf("em", "i", "cite") -> into += marked(node, ctx) { it.copy(italic = true) }
            node.name in setOf("del", "s", "strike") -> into += marked(node, ctx) { it.copy(strike = true) }
            node.name in setOf("code", "kbd", "samp", "tt") -> into += marked(node, ctx) { it.copy(mono = true) }
            else -> node.children.forEach { child -> docDocRun(child, ctx, into) }
        }
    }

    private fun marked(node: HtmlNode, ctx: Ctx, wrap: (DocRun) -> DocRun): List<DocRun> {
        val inner = ArrayList<DocRun>()
        node.children.forEach { child -> docDocRun(child, ctx, inner) }
        return inner.map(wrap)
    }

    /** 只有这些地址值得留成能点开的链接；`javascript:` 那种、页内锚点与空地址都只当文字。 */
    private fun linkTarget(href: String): String? {
        val value = href.trim()
        if (value.isEmpty() || value.startsWith("#")) return null
        return value.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true) ||
                it.startsWith("mailto:", true) || it.startsWith("www.", true) || EMAIL.matches(it)
        }
    }

    private val EMAIL = Regex("^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$")

    /** 首尾的纯空白段不要：段首缩进与段间空行由 Word 的样式管，搬一堆空格进去只会歪。 */
    private fun trimBlanks(runs: List<DocRun>): List<DocRun> {
        var from = 0
        var to = runs.size
        while (from < to && runs[from].text.isBlank()) from++
        while (to > from && runs[to - 1].text.isBlank()) to--
        return runs.subList(from, to).toList()
    }

    private fun collapse(value: String) = value.replace(WHITESPACE_RUN, " ")

    private class Ctx(val markdown: Boolean, val counted: Counted)

    private class Counted {
        var links = 0
        var anchors = 0
        var images = 0
        var dropped = 0
        var tables = 0
        var forms = 0
        var clamped = 0
        var rules = 0
        var nestedQuotes = 0
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
        if (counted.tables > 0) notes += "${counted.tables} 张表摆成方格：跨过的格子留空，格子里的换行并成空格"
        // 表单的值与选中项在属性里，两种输出都只剩标签文字 —— 退化了就得说，跟输出格式无关
        if (counted.clamped > 0) notes += "${counted.clamped} 处跨度大得不合理（超过 $MAX_SPAN 格），按 $MAX_SPAN 算"
        if (counted.forms > 0) notes += "${counted.forms} 处表单控件只留下标签文字（填的值与选中项在属性里，读不出来）"
        if (!markdown) {
            if (counted.links > 0) notes += "${counted.links} 处链接只留下文字，地址在纯文本里没处放"
            if (counted.images > 0) notes += "${counted.images} 处图片只剩替代文字"
        }
        // 页内锚点在摊平成一篇之后没有落点：留个会跳空的链接不如留字，但少了东西得说
        if (markdown && counted.anchors > 0) {
            notes += "${counted.anchors} 处页内锚点没有落点（整篇摊平了），只留下文字"
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

    /**
     * 这个元素里面有没有块级东西（任何深度）。
     *
     * `html`、`body`、各种包装 `div` 都不在块级名单里，只看一层会漏掉 `<body><table>…</table></body>`
     * 这种整页只有一张表的写法（邮件里全是），那样整张表会被当成一坨行内文字吞掉。
     */
    private fun holdsBlock(node: HtmlNode): Boolean = node.children.any {
        it.name in BLOCKS || it.name in DROP || holdsBlock(it)
    }

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

    /** 纯文本 / Markdown 用的表：与 CSV 那条共用一套对位，格子里的换行并成空格。 */
    private fun table(node: HtmlNode, ctx: Ctx): String {
        ctx.counted.tables++
        val placed = placeTable(node, ctx)
        ctx.counted.clamped += placed.clamped
        val rows = placed.rows.map { row -> row.map { cell -> cell.replace("\n", " ").trim() } }
        if (rows.isEmpty()) return ""
        if (!ctx.markdown) return rows.joinToString("\n") { it.joinToString("\t") }
        val width = rows.maxOf { it.size }
        fun line(cells: List<String>): String = cells.joinToString(" | ", prefix = "| ", postfix = " |") {
            it.replace("|", "\\|").ifBlank { " " }
        }
        val out = ArrayList<String>()
        out += line(rows.first())
        out += "|" + List(width) { "---" }.joinToString("|") + "|"
        rows.drop(1).forEach { out += line(it) }
        return out.joinToString("\n")
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
                val body = StringBuilder()
                children(node, body, ctx)
                val href = node.attrs["href"].orEmpty()
                val label = body.toString()
                when {
                    !ctx.markdown -> { ctx.counted.links++; into.append(label) }
                    href.isBlank() || href.startsWith("#") -> { ctx.counted.anchors++; into.append(label) }
                    else -> { ctx.counted.links++; into.append("[$label]($href)") }
                }
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
