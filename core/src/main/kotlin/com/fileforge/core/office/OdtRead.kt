package com.fileforge.core.office

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import org.w3c.dom.Element
import org.w3c.dom.Node

/** 读出来的一棵文档树，加上"有什么没搬"的交代。 */
class OdtBody(val doc: Doc, val notes: List<String>)

/** 段内一处文字的记号。三项都用 null 表示"这一条样式没说"，好让父子链分得清"没说"和"说不要"。 */
internal class Marks(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val strike: Boolean? = null,
    val underline: Boolean? = null,
    val mono: Boolean? = null,
) {
    /** [above] 是更靠外（更具体）的那一份：它说过的就不再往上看。 */
    fun filledBy(above: Marks): Marks = Marks(
        bold = above.bold ?: bold,
        italic = above.italic ?: italic,
        strike = above.strike ?: strike,
        underline = above.underline ?: underline,
        mono = above.mono ?: mono,
    )

    fun on(name: String): Boolean = when (name) {
        "bold" -> bold == true
        "italic" -> italic == true
        "strike" -> strike == true
        "underline" -> underline == true
        else -> mono == true
    }
}

/**
 * OpenDocument 文字（.odt 的 `content.xml`）→ [Doc]。
 *
 * 为什么不复用 [DocxRead]：同一件事两种格式记在不同地方 —— docx 的段落样式是"子元素带 w:val"，
 * ODF 是父元素上的属性；docx 的粗体直接写在 `w:rPr` 里，而 ODF 正文里**一个记号都不写**，
 * 只写 `<text:span text:style-name="T1">`，"T1 是粗体"这句话在样式表里，还要顺
 * `style:parent-style-name` 往上问。硬并成一层只会两边各漏一半边角。
 *
 * 认识的记号：
 *  - `text:h` 的 `text:outline-level` → 标题层级（层级写在元素上，比样式名可靠）
 *  - 段落样式名（`_20_` 还原成空格）→ `Heading 1` / `Quotations` / `Preformatted Text` /
 *    `Source Code` / `Horizontal Line`。ODF 的内部样式名不随界面语言变（翻译的是 `style:display-name`），
 *    所以这边不像 docx 那样要防"标题 1"
 *  - `text:list` 套了几层 → 列表深度；圆点还是编号看该层在 list 样式里是
 *    `text:list-level-style-bullet` 还是 `-number`
 *  - `style:text-properties`：`fo:font-weight` → 粗体、`fo:font-style` → 斜体、
 *    `style:text-line-through-style` → 删除线、`style:text-underline-style` → 下划线、
 *    `style:font-family-generic="modern"`（或字体声明里 `style:font-pitch="fixed"`）→ 等宽
 *  - `text:a` 的 `xlink:href` → 链接，`#` 开头的是段内跳转只数一笔
 *  - `text:s`（`text:c` 给个数）→ 空格，`text:tab` → 制表，`text:line-break` → 段内换行
 *  - `table:table` → 表格，`table:table-header-rows` 里的行是表头；
 *    `table:number-columns-repeated` 与 `-spanned` 都得把列补上，否则整张表左右错格
 *
 * 段落样式自己的文字属性（标题通常是粗体）**不**往下发给每一段文字：块类型已经把"这是标题"
 * 说清楚了，再给每个 run 加粗就成了 `# **标题**`。
 *
 * 跳过的（与 docx 同一套判断）：批注、脚注尾注、图片与文本框里的字、公式、修订记录。
 */
object OdtRead {

    private const val CONTENT = "content.xml"
    private const val STYLES = "styles.xml"

    /** 重复单元的上限：正常表格几十列，超了就是拿重复当压缩包用。 */
    private const val MAX_REPEAT = 512

    private val NONE = Marks()

    /** 一条样式里关心的那几项。 */
    private class OdtStyle(val parent: String, val listStyle: String, val marks: Marks)

    /** 一个 list 样式：层号（从 1 起）→ 该层是编号（true）还是圆点（false）。 */
    private class OdtList(val parent: String, val levels: Map<Int, Boolean>)

    private class Ctx(val styles: Map<String, OdtStyle>, val lists: Map<String, OdtList>, val tally: OdtTally)

    /** 列表项的上下文：第几层（从 0 起）+ 这一串属于哪个 list 样式。 */
    private class Entry(val depth: Int, val style: String)

    fun read(load: (String) -> ByteArray?): OdtBody {
        val content = load(CONTENT)
            ?: throw IllegalArgumentException("这份 ODT 里没有 content.xml，正文不在这里")
        val tally = OdtTally()
        val root = OoxmlXml.root(content, "OpenDocument")
        val table = OdtStyleTable.of(root, load(STYLES))
        val text = childrenOf(root, "body").firstOrNull()?.let { childrenOf(it, "text").firstOrNull() }
            ?: throw IllegalArgumentException("content.xml 里没有 office:body/office:text，正文不在这里")
        val parts = ArrayList<DocPart>()
        blocks(text, Ctx(table.styles, table.lists, tally), parts)
        val losses = tally.losses()
        return OdtBody(Doc(parts, losses), losses)
    }

    /** 一层里的块级元素：段落、标题、列表、表。列表项的内容由 [listItem] 自己排。 */
    private fun blocks(container: Node, ctx: Ctx, out: ArrayList<DocPart>) {
        children(container) { node ->
            val element = node as? Element ?: return@children
            when (localName(element)) {
                "p" -> paragraph(element, ctx)?.let { out += it }
                "h" -> heading(element, ctx)?.let { out += it }
                "list" -> list(element, ctx, -1, out)
                "table" -> table(element, ctx)?.let { out += it }
                "annotation" -> ctx.tally.bump("annotation")
                else -> Unit
            }
        }
    }

    /**
     * 一个 `text:list`：层号比外层加一，每个 `text:list-item` 出一段。
     *
     * 深度只看嵌套层数 —— ODF 不在 item 上写"我是第几级"，层级就是 list 套 list 的层数。
     */
    private fun list(element: Element, ctx: Ctx, parentDepth: Int, out: ArrayList<DocPart>) {
        val depth = parentDepth + 1
        val declared = attr(element, "text:style-name").orEmpty()
        children(element) { node ->
            val child = node as? Element ?: return@children
            when (localName(child)) {
                "list-item" -> listItem(child, ctx, depth, declared, out)
                "list" -> list(child, ctx, depth, out)
                else -> Unit
            }
        }
    }

    /**
     * 一个列表项。
     *
     * item 里写了两段时合成一项（段间换行留着），不拆成两项：ODF 里"续段"与"新条目"
     * 是同一层 item 的两种写法，拆开会让一项凭空变两项。
     */
    private fun listItem(element: Element, ctx: Ctx, depth: Int, listStyle: String, out: ArrayList<DocPart>) {
        val paragraphs = ArrayList<Element>()
        val nested = ArrayList<Element>()
        children(element) { node ->
            val child = node as? Element ?: return@children
            when (localName(child)) {
                "p", "h" -> paragraphs += child
                "list" -> nested += child
                "annotation" -> ctx.tally.bump("annotation")
                else -> Unit
            }
        }
        var style = listStyle
        if (style.isEmpty() && paragraphs.isNotEmpty()) {
            val own = attr(paragraphs.first(), "text:style-name").orEmpty()
            style = ctx.styles[own]?.listStyle.orEmpty()
        }
        val runs = ArrayList<DocRun>()
        paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0) {
                runs += DocRun("\n")
                ctx.tally.bump("multiParaItem")
            }
            runs += inline(paragraph, null, NONE, ctx)
        }
        if (runs.any { it.text.isNotEmpty() }) {
            val entry = Entry(depth, style)
            out += DocParagraph(DocPara(merge(runs), "ListParagraph", entry.depth, bulletOf(entry, ctx)))
        }
        nested.forEach { list(it, ctx, depth, out) }
    }

    /** 该层是圆点还是编号：list 样式里逐级写着，找不到就按圆点排并说明（不装作知道）。 */
    private fun bulletOf(entry: Entry, ctx: Ctx): Boolean? {
        val numbered = numbered(entry.style, ctx, entry.depth + 1)
        if (numbered == null) {
            ctx.tally.bump("listUnknown")
            return true
        }
        return !numbered
    }

    private fun numbered(style: String, ctx: Ctx, level: Int): Boolean? {
        var current = style
        val seen = HashSet<String>()
        while (current.isNotEmpty() && seen.add(current)) {
            val node = ctx.lists[current] ?: return null
            node.levels[level]?.let { return it }
            current = node.parent
        }
        return null
    }

    /** `text:h` → 标题：层级看 `text:outline-level`，没有就看样式名里的那个数字。 */
    private fun heading(element: Element, ctx: Ctx): DocPart? {
        val runs = inline(element, null, NONE, ctx)
        if (runs.none { it.text.isNotEmpty() }) return null
        // 标题里的段内换行折成空格：Markdown 与网页的标题里放不进硬换行
        val flat = runs.map { run -> run.copy(text = run.text.replace("\n", " ")) }
        val named = styleKind(attr(element, "text:style-name").orEmpty(), ctx)
        val level = attr(element, "text:outline-level")?.toIntOrNull()
            ?: Regex("^Heading([1-6])$").find(named)?.groupValues?.get(1)?.toInt()
            ?: 1
        return DocParagraph(DocPara(merge(flat), "Heading${level.coerceIn(1, 6)}"))
    }

    /** `text:p` → 一段。分隔线在 ODF 里就是"样式名叫 Horizontal Line 的空段"。 */
    private fun paragraph(element: Element, ctx: Ctx): DocPart? {
        val runs = inline(element, null, NONE, ctx)
        val style = styleKind(attr(element, "text:style-name").orEmpty(), ctx)
        if (style == "HorizontalLine") {
            if (runs.joinToString("") { it.text }.isBlank()) return DocRule()
            // 写着"横线"样式的段落里居然有字：字要搬，样式名不是模型里的任何一种，按正文排
            return DocParagraph(DocPara(merge(runs), "Body"))
        }
        if (runs.none { it.text.isNotEmpty() }) return null      // 空段不写：全写出去产物会多出一堆空行
        return DocParagraph(DocPara(merge(runs), style))
    }

    /** 一张 `table:table`。表头看它的行是不是躺在 `table:table-header-rows` 里。 */
    private fun table(element: Element, ctx: Ctx): DocPart? {
        val rows = ArrayList<List<String>>()
        var header = false
        children(element) { node ->
            val child = node as? Element ?: return@children
            when (localName(child)) {
                "table-row" -> row(child, ctx, rows)
                "table-body", "table-footer-rows" ->
                    // 真文件（LibreOffice 与 pandoc 写的）把正文的行裹在 table:table-body 里：
                    // 只认裸的 table-row 与 header-rows 的话，一张表除了表头一行都读不出来
                    children(child) { inner ->
                        val made = inner as? Element ?: return@children
                        if (localName(made) == "table-row") row(made, ctx, rows)
                    }
                "table-header-rows" -> {
                    header = true
                    children(child) { inner ->
                        val made = inner as? Element ?: return@children
                        if (localName(made) == "table-row") row(made, ctx, rows)
                    }
                }
                else -> Unit
            }
        }
        if (rows.isEmpty()) return null
        ctx.tally.bump("tbl")
        return DocTable(header, rows)
    }

    private fun row(element: Element, ctx: Ctx, out: ArrayList<List<String>>) {
        val cells = ArrayList<String>()
        var coveredLeft = 0
        children(element) { node ->
            val cell = node as? Element ?: return@children
            when (localName(cell)) {
                "table-cell" -> cell(cell, ctx, cells).also { coveredLeft = it }
                // 合并有两种写法（LibreOffice 放 covered 格，pandoc 那类只给前一格加 spanned），
                // 真文件会两种一起写：被 spanned 补出来的那几格不能再被 covered 数一遍，
                // 否则一次合并占两格，整行往右错位
                "covered-table-cell" -> if (coveredLeft-- > 0) Unit else cells += ""
                else -> Unit
            }
        }
        if (cells.isEmpty()) return
        var times = attr(element, "table:number-rows-repeated")?.toIntOrNull() ?: 1
        if (times > MAX_REPEAT) {
            ctx.tally.bump("hugeRepeat")
            times = MAX_REPEAT
        }
        repeat(times.coerceAtLeast(1)) { out += cells }
    }

    /**
     * 一个格子。
     *
     * 两个"重复"不是一回事，混了会把一格字抄成两格：
     *  - `table:number-columns-spanned` 是**合并**：这一格横着占几列，占掉的列要补空，
     *    否则表头四列正文三列，读回来整张表是斜的
     *  - `table:number-columns-repeated` 是**同样的格子再来几份**（LibreOffice 用它写一排空格子），
     *    每份自己再按跨度补空列
     *
     * 返回"这一格已经替后面几个 `covered-table-cell` 占好了位置"。
     */
    private fun cell(element: Element, ctx: Ctx, out: ArrayList<String>): Int {
        val parts = ArrayList<String>()
        children(element) { node ->
            val child = node as? Element ?: return@children
            when (localName(child)) {
                "p", "h" -> {
                    val text = inline(child, null, NONE, ctx).joinToString("") { it.text }
                    if (text.isNotEmpty()) parts += text
                }
                "list" -> {
                    val nested = ArrayList<DocPart>()
                    list(child, ctx, -1, nested)
                    nested.filterIsInstance<DocParagraph>().forEach { made -> parts += made.para.text }
                }
                "table" -> {
                    ctx.tally.bump("nestedTable")
                    val nested = table(child, ctx) as? DocTable ?: return@children
                    parts += nested.rows.joinToString(10.toChar().toString()) { r ->
                        r.joinToString(9.toChar().toString())
                    }
                }
                "annotation" -> ctx.tally.bump("annotation")
                else -> Unit
            }
        }
        val text = parts.joinToString("\n").trim()
        val spanned = (attr(element, "table:number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var repeated = attr(element, "table:number-columns-repeated")?.toIntOrNull() ?: 1
        if ((attr(element, "table:number-rows-spanned")?.toIntOrNull() ?: 1) > 1) ctx.tally.bump("rowSpan")
        if (repeated > MAX_REPEAT) {
            ctx.tally.bump("hugeRepeat")
            repeated = MAX_REPEAT
        }
        val times = repeated.coerceAtLeast(1)
        repeat(times) {
            out += text
            repeat(spanned - 1) { out += "" }
        }
        return (spanned - 1) * times
    }

    /**
     * 段内的字按**文档顺序**收。
     *
     * 记号只能从样式表问出来：`text:span` 自己只写样式名，所以往下传一份 [Marks]，
     * 更靠外的（外层 span、链接）那份优先。顺序也不能打乱：句中链接挪到句尾是这类读法
     * 最容易犯的错（docx 那边踩过，见 [DocxRead]）。
     */
    private fun inline(element: Element, link: String?, marks: Marks, ctx: Ctx): List<DocRun> {
        val out = ArrayList<DocRun>()
        val kids = element.childNodes
        for (index in 0 until kids.length) {
            val node = kids.item(index)
            if (node.nodeType == Node.TEXT_NODE) {
                append(out, node.textContent, link, marks)
                continue
            }
            val child = node as? Element ?: continue
            when (localName(child)) {
                "span" -> {
                    val own = marksOf(attr(child, "text:style-name").orEmpty(), ctx).filledBy(marks)
                    if (own.on("underline")) ctx.tally.bump("underline")
                    out += inline(child, link, own, ctx)
                }
                "s" -> append(out, " ".repeat((attr(child, "text:c")?.toIntOrNull() ?: 1).coerceIn(1, 999)), link, marks)
                "tab" -> append(out, "\t", link, marks)
                "line-break" -> append(out, "\n", link, marks)
                "a" -> anchor(child, link, marks, ctx, out)
                "note" -> ctx.tally.bump("note")
                "annotation" -> ctx.tally.bump("annotation")
                "frame", "image", "object" -> ctx.tally.bump("drawing")
                "math" -> ctx.tally.bump("math")
                "bookmark", "bookmark-start", "bookmark-end", "soft-page-break", "page-number",
                "author-name", "description", "user-defined", "text-syllables",
                -> Unit                                                   // 书签、分页符、域：不是正文
                "change", "change-start", "change-end", "changed-region", "track-changes" ->
                    ctx.tally.bump("revision")                            // 修订：正文照字面搬，改动本身不搬
                else -> out += inline(child, link, marks, ctx)  // 认不出的记号：字照搬，记号丢了（不静默少一段）
            }
        }
        return out
    }

    /** 链接：外部地址照字面取；`#` 开头的是段内跳转，只数一笔，不当地址。 */
    private fun anchor(link: Element, inherited: String?, marks: Marks, ctx: Ctx, out: ArrayList<DocRun>) {
        val target = attr(link, "xlink:href").orEmpty()
        if (target.isEmpty()) {
            ctx.tally.bump("linkMissing")
            out += inline(link, inherited, marks, ctx)
            return
        }
        if (target.startsWith("#")) {
            ctx.tally.bump("anchor")
            out += inline(link, null, marks, ctx)
            return
        }
        ctx.tally.bump("link")
        out += inline(link, target, marks, ctx)
    }

    private fun append(out: ArrayList<DocRun>, text: String, link: String?, marks: Marks) {
        if (text.isEmpty()) return
        out += DocRun(
            text,
            bold = marks.bold == true,
            italic = marks.italic == true,
            strike = marks.strike == true,
            mono = marks.mono == true,
            underline = marks.underline == true,
            link = link,
        )
    }

    /** 相邻且记号完全一样的合成一处：样式表拆开的（中途换了字号）不该在产物里露出来。 */
    private fun merge(runs: List<DocRun>): List<DocRun> {
        val out = ArrayList<DocRun>()
        runs.forEach { run ->
            val last = out.lastOrNull()
            if (last != null && last.bold == run.bold && last.italic == run.italic && last.mono == run.mono &&
                last.strike == run.strike && last.underline == run.underline && last.link == run.link
            ) {
                out[out.size - 1] = DocRun(
                    last.text + run.text, run.bold, run.italic, run.strike, run.mono, run.underline, run.link,
                )
            } else {
                out += run
            }
        }
        return out
    }

    /** 顺父子链问一条样式的记号：child 说过的就不再看父样式（父的粗体能被子的 normal 关掉）。 */
    private fun marksOf(name: String, ctx: Ctx): Marks {
        var current = name
        var marks = NONE
        val seen = HashSet<String>()
        while (current.isNotEmpty() && seen.add(current)) {
            val style = ctx.styles[current] ?: break
            marks = style.marks.filledBy(marks)
            current = style.parent
        }
        return marks
    }

    /**
     * 段落样式（含父链）→ 模型里的那种段落。
     *
     * 必须往上一层看：`text:p` 上写的常是 `P5` 这种自动名，谁也不认得，
     * 但它的 `style:parent-style-name="Quotations"` 认得 —— 只比本名的话，
     * 引用与代码块会整批掉成正文，还不报错。子样式说过的优先，所以从下往上第一个非 Body 就是答案。
     */
    private fun styleKind(name: String, ctx: Ctx): String {
        var current = name
        val seen = HashSet<String>()
        while (current.isNotEmpty() && seen.add(current)) {
            val kind = canonical(current)
            if (kind != "Body") return kind
            current = ctx.styles[current]?.parent ?: return "Body"
        }
        return "Body"
    }

    /**
     * 样式名 → 模型里的样式名。
     *
     * ODF 的内部名用 `_20_` 这种"下划线 + 四位十六进制"编码空格（`Heading_20_1` 就是 `Heading 1`），
     * 先还原再比。母样式那一层的名字才是结构信号：`P5` 这种自动名谁也不认得，
     * 但它的 `style:parent-style-name="Quotations"` 认得，所以调用方给的名字逐级往上找过。
     */
    private fun canonical(name: String): String {
        val flat = decode(name).trim().lowercase().replace(Regex("[\\s_-]+"), " ")
        val heading = Regex("^heading ([1-9])$").find(flat)?.groupValues?.get(1)
        if (heading != null) return "Heading${heading.toInt().coerceAtMost(6)}"
        return when (flat) {
            "title" -> "Heading1"                     // 封面题名进一级：大纲上它就在最上层
            "quote", "quotations", "block quote", "blockquote", "intense quote" -> "Quote"
            "preformatted text", "source code", "code", "verbatim", "source text" -> "SourceCode"
            "horizontal line" -> "HorizontalLine"
            else -> "Body"
        }
    }

    /**
     * ODF 样式名解码：下划线包住的十六进制码点（`Heading_20_1` 就是 `Heading 1`）。
     *
     * 位数不能写死：规范那条是四位（`_0020_`），而 LibreOffice 与 pandoc 实际写的是最短形式
     * （`_20_`）。只按四位解的话，所有带空格的样式名都对不上表 —— 标题层级、引用与代码块
     * 会整批掉成正文，还不报错。
     */
    private fun decode(name: String): String {
        if (!name.contains('_')) return name
        return Regex("_([0-9a-fA-F]{2,4})_").replace(name) { match ->
            val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
            if (code == 0 || code > 0xFFFF) return@replace match.value
            code.toChar().toString()
        }
    }

    private fun attr(element: Element, name: String): String? {
        element.getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
        val tail = name.substringAfter(':')
        val wanted = name.substringBefore(':', "")
        val map = element.attributes
        var loose: String? = null
        for (index in 0 until map.length) {
            val item = map.item(index)
            if (item.nodeName.substringAfterLast(':') != tail) continue
            if (item.nodeName == tail || (wanted.isNotEmpty() && item.nodeName.substringBefore(':').isEmpty())) {
                return item.nodeValue.takeIf { it.isNotEmpty() }
            }
            if (wanted.isNotEmpty() && item.nodeName.substringBefore(':') != wanted && loose == null) {
                loose = item.nodeValue.takeIf { it.isNotEmpty() }
            }
        }
        return loose
    }

    private fun children(node: Node, each: (Node) -> Unit) {
        val kids = node.childNodes
        for (index in 0 until kids.length) each(kids.item(index))
    }

    private fun childrenOf(node: Node, local: String): List<Element> = OoxmlStructure.childrenOf(node, local)

    private fun localName(node: Node): String = OoxmlStructure.localName(node)

    /** 样式表：content.xml 的自动样式与命名样式，加上 styles.xml 里的母样式（同名时文档级那份优先）。 */
    private class OdtStyleTable(val styles: Map<String, OdtStyle>, val lists: Map<String, OdtList>) {
        companion object {
            fun of(content: Element, stylesXml: ByteArray?): OdtStyleTable {
                val roots = ArrayList<Element>()
                roots += content
                stylesXml?.let { runCatching { roots += OoxmlXml.root(it, "OpenDocument") } }
                val styles = LinkedHashMap<String, OdtStyle>()
                val lists = LinkedHashMap<String, OdtList>()
                val fixed = HashSet<String>()
                val ordered = roots.reversed()          // 后收集的覆盖前面的：content.xml 要最后收
                ordered.forEach { root -> collectAll(root, styles, lists, fixed) }
                return OdtStyleTable(styles, lists)
            }

            private fun collectAll(root: Element, styles: MutableMap<String, OdtStyle>, lists: MutableMap<String, OdtList>, fixed: MutableSet<String>) {
                OoxmlStructure.findAll(root, "font-face").forEach { face -> collectFont(face, fixed) }
                OoxmlStructure.findAll(root, "style").forEach { element ->
                    val name = attr(element, "style:name") ?: return@forEach
                    val properties = childrenOf(element, "text-properties").firstOrNull()
                    styles[name] = OdtStyle(
                        parent = attr(element, "style:parent-style-name").orEmpty(),
                        listStyle = attr(element, "style:list-style-name").orEmpty(),
                        marks = marks(properties, fixed),
                    )
                }
                OoxmlStructure.findAll(root, "list-style").forEach { element ->
                    val name = attr(element, "style:name") ?: return@forEach
                    val levels = LinkedHashMap<Int, Boolean>()
                    childrenOf(element, "list-level-style-bullet").forEach {
                        attr(it, "text:level")?.toIntOrNull()?.let { level -> levels[level] = false }
                    }
                    childrenOf(element, "list-level-style-number").forEach {
                        attr(it, "text:level")?.toIntOrNull()?.let { level -> levels[level] = true }
                    }
                    lists[name] = OdtList(attr(element, "style:parent-style-name").orEmpty(), levels)
                }
            }

            private fun collectFont(face: Element, fixed: MutableSet<String>) {
                val generic = attr(face, "style:font-family-generic")
                val pitch = attr(face, "style:font-pitch")
                val family = attr(face, "svg:font-family").orEmpty().trim().trim('"', '\'')
                if (pitch != "fixed" && !(generic == "modern" && family.isNotEmpty())) return
                if (family.isNotEmpty()) fixed += family.lowercase()
                attr(face, "style:name")?.let { fixed += it.lowercase() }
            }

            private fun marks(properties: Element?, fixed: Set<String>): Marks {
                if (properties == null) return NONE
                return Marks(
                    bold = weightIsBold(attr(properties, "fo:font-weight")),
                    italic = attr(properties, "fo:font-style").orEmpty().lowercase() in setOf("italic", "oblique"),
                    strike = on(attr(properties, "style:text-line-through-style")),
                    underline = on(attr(properties, "style:text-underline-style")),
                    mono = mono(properties, fixed),
                )
            }

            private fun mono(properties: Element, fixed: Set<String>): Boolean? {
                val generic = attr(properties, "style:font-family-generic")
                if (generic == "modern") return true
                if (generic != null && generic != "automatic") return false
                if (attr(properties, "style:font-pitch") == "fixed") return true
                val family = attr(properties, "fo:font-family").orEmpty().trim().trim('"', '\'').lowercase()
                if (family.isEmpty()) return null
                return family in fixed || family in MONOSPACE
            }

            private fun on(value: String?): Boolean? = when (value) {
                null -> null
                "" -> null
                "none", "false", "auto", "default" -> false
                else -> true
            }

            private fun weightIsBold(value: String?): Boolean? {
                if (value == null || value.isEmpty() || value == "auto") return null
                if (value == "normal") return false
                if (value == "bold") return true
                return value.toIntOrNull()?.let { it >= 600 }
            }

            private val MONOSPACE = setOf(
                "monospace", "courier", "courier new", "consolas", "menlo", "monaco",
                "liberation mono", "dejavu sans mono", "nimbus mono l", "source code pro",
                "jetbrains mono", "fira mono", "ubuntu mono", "andale mono", "fixedsys",
                "noto sans mono", "pt mono", "inconsolata", "cutive mono",
            )

            private fun attr(element: Element, name: String): String? {
                element.getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
                val tail = name.substringAfter(':')
                val map = element.attributes
                for (index in 0 until map.length) {
                    val item = map.item(index)
                    if (item.nodeName.substringAfterLast(':') == tail) return item.nodeValue.takeIf { it.isNotEmpty() }
                }
                return null
            }

            private fun childrenOf(node: Node, local: String): List<Element> = OoxmlStructure.childrenOf(node, local)
        }
    }
}

/** 数"有什么没搬"用的计数表。 */
private class OdtTally {
    private val counts = HashMap<String, Int>()

    fun bump(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    operator fun get(name: String): Int = counts[name] ?: 0

    fun losses(): List<String> {
        val list = ArrayList<String>()
        fun say(count: Int, write: (Int) -> String) {
            if (count > 0) list += write(count)
        }
        say(get("drawing")) { "文里 $it 处图片/图形搬不过来" }
        say(get("tbl")) { "$it 张表按行列搬过来，格子里多段合成一格（段间换行留着）" }
        say(get("nestedTable")) { "$it 处表里嵌着表，里面只留文字" }
        say(get("rowSpan")) { "$it 处竖着合并的格子只占了一行，下面的行按空补齐" }
        say(get("link")) { "$it 处超链接带地址搬过来" }
        say(get("anchor")) { "$it 处段内跳转（跳到某个书签）在网页/Markdown 里没有落点，只留文字" }
        say(get("linkMissing")) { "$it 处超链接没写地址，只留文字" }
        say(get("underline")) { "$it 处下划线写成 <u>（Markdown 里没这种记号，转 Markdown 会丢）" }
        say(get("note")) { "$it 处脚注/尾注在正文里只留了编号，注文在别处没搬" }
        say(get("annotation")) { "$it 处批注没搬" }
        say(get("math")) { "$it 处公式（MathML）没有对应文字，没搬" }
        say(get("revision")) { "$it 处修订记录按已接受处理，正文照字面搬" }
        say(get("multiParaItem")) { "$it 个列表项里写了两段，合成一项（段间换行留着）" }
        say(get("listUnknown")) { "$it 个列表项在样式表里查不到该层的记号，按圆点排" }
        say(get("hugeRepeat")) { "$it 处表格里的重复单元多到不合理，按 512 个截断" }
        return list
    }
}
