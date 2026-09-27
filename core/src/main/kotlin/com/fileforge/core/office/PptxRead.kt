package com.fileforge.core.office

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import org.w3c.dom.Element
import org.w3c.dom.Node

/** 读出来的一棵文档树（一页接一页），加上"有什么没搬"的交代。 */
class PptxSlideBody(val doc: Doc, val notes: List<String>)

/**
 * PresentationML（.pptx 每页的部件）→ [Doc]。
 *
 * 为什么要有这一份：以前 .pptx 只能"把字连起来"（[OfficeText.pptxSlide]），
 * 于是转 Markdown / 转网页拿到的是**一片没有层级的段落**：这页哪句是标题、
 * 哪几条是带圆点的还是带编号的、表格的表头是哪一行，全在抽取时丢掉了。
 * 猜也不行 —— 演示文稿的字号与颜色是版式设计，不是大纲层级。
 *
 * 认识的记号（ECMA-376 DrawingML 那一套）：
 *  - 形状自己说它是干什么的：`p:nvSpPr/p:nvPr/p:ph` 的 `type`（`title` / `ctrTitle` / `body` /
 *    `subTitle` / `ctrShpT`…）→ 标题类成 `Heading2`，其余当正文。占位符类型是文件里写的，
 *    不是按字号推的
 *  - 段落的列表身份看 `a:pPr` 里的记号定义：`a:buNone` 明说"这不是列表"；
 *    `a:buChar` 是圆点；`a:buAutoNum` 是编号；**什么也没写**时按普通段落排 ——
 *    那种白靠的是母版/版式里的默认列表样式，而默认长什么样是主题设计，
 *    从"这是正文占位符"推"它该有圆点"就是拿猜的当读出来的（数几段报几句）；`lvl` 是层级深度
 *  - run 的 `a:rPr`：`b` / `i` / `strike` / `u` → 粗体 / 斜体 / 删除线 / 下划线；
 *    `a:latin typeface` 是等宽字体时给 `mono`；`a:hlinkClick r:id` 过这一页的关系表拿**照字面**的外部地址，
 *    指向包内另一个部件的那种（"跳到第 3 页"）只留文字并数一笔 —— 转出来的文件里没有能跳过去的地方
 *  - `a:br` → 段内换行，`a:tab` → 制表，`a:t` 的文字照原样（含 xml:space 里的空格）
 *  - 表格：`a:tbl` → `a:tr` 行 / `a:tc` 格子，`a:tblPr firstRow="1"` 说首行是表头，
 *    `gridSpan` / `rowSpan` 都要补空位（不补整张表读歪，与 ODF 那边同一条主张）
 *  - 每页前面补一行"第 N 页"：稿子是一页一页的，接成连续散文会让两页的句子看起来是同一段
 *
 * 跳过的（与抽文字那条同一套判断）：母版与版式里的固定文字、演讲者备注、批注、图形与图片里的文字、
 * SmartArt 与嵌入对象。
 */
object PptxRead {

    private const val TABLE_URI = "http://schemas.openxmlformats.org/drawingml/2006/table"

    /** [load] 按部件名给字节；[names] 是包里的条目名（用来排页序）。 */
    fun read(load: (String) -> ByteArray?, names: Collection<String>): PptxSlideBody {
        val tally = PptxTally()
        val order = OoxmlStructure.slideOrder(load, names)
        if (order.parts.isEmpty()) throw IllegalArgumentException("这份 pptx 里一页幻灯片都没有，它不是 pptx")
        val parts = ArrayList<DocPart>()
        order.parts.forEachIndexed { index, part ->
            val page = load(part)
            if (page == null) {
                tally.bump("missingPart")
                return@forEachIndexed
            }
            parts += DocParagraph(DocPara(listOf(DocRun("第 ${index + 1} 页")), "Heading1"))
            slide(page, pageRelationships(load, part), tally, parts)
        }
        val losses = (order.notes + tally.losses()).distinct()
        return PptxSlideBody(Doc(parts, losses), losses)
    }

    /**
     * 这一页的关系表：`r:id` → 那条关系。
     *
     * 地址要照字面取，不能按部件目录折算（`ppt/slides/https://…` 是废地址）。
     * 两类都留着：`TargetMode="External"` 是包外的网址，没写 TargetMode 的指着包内另一个部件
     * （"点到第 3 页"就是这种写法，PowerPoint 与 LibreOffice 都这么写）。后者不是能跳出去的地址，
     * 但也不能跟"关系表里查无此 id"混成同一笔。
     */
    private fun pageRelationships(load: (String) -> ByteArray?, part: String): Map<String, Rel> {
        val directory = part.substringBeforeLast('/', "")
        val rels = load("$directory/_rels/${part.substringAfterLast('/')}.rels") ?: return emptyMap()
        val out = LinkedHashMap<String, Rel>()
        OoxmlStructure.findAll(OoxmlXml.root(rels, "OOXML"), "Relationship").forEach { element ->
            val id = element.getAttribute("Id").takeIf { it.isNotEmpty() } ?: return@forEach
            val target = attr(element, "Target") ?: return@forEach
            val mode = attr(element, "TargetMode").orEmpty()
            out[id] = Rel(target, mode.equals("External", ignoreCase = true))
        }
        return out
    }

    /** 一条关系：目标（照字面）与"它是包外的地址还是包内的另一个部件"。 */
    private class Rel(val target: String, val external: Boolean)

    private fun slide(part: ByteArray, links: Map<String, Rel>, tally: PptxTally, out: ArrayList<DocPart>) {
        val root = OoxmlXml.root(part, "OOXML")
        val tree = OoxmlStructure.findAll(root, "spTree").firstOrNull() ?: return
        OoxmlStructure.childrenOf(tree, "sp").forEach { shape -> paragraphShape(shape, links, tally, out) }
        OoxmlStructure.childrenOf(tree, "graphicFrame").forEach { frame ->
            OoxmlStructure.findAll(frame, "tbl").forEach { table ->
                table(table, links, tally, out)
            }
        }
        // 组合图形里的形状：钻一层看，组合本身不是内容
        OoxmlStructure.childrenOf(tree, "grpSp").forEach { group ->
            OoxmlStructure.findAll(group, "sp").forEach { shape -> paragraphShape(shape, links, tally, out) }
            OoxmlStructure.findAll(group, "tbl").forEach { table -> table(table, links, tally, out) }
        }
        OoxmlStructure.childrenOf(tree, "pic").forEach { _ -> tally.bump("pic") }
    }

    /** 一个 `p:sp`：占位符类型决定这块是标题还是正文。 */
    private fun paragraphShape(shape: Element, links: Map<String, Rel>, tally: PptxTally, out: ArrayList<DocPart>) {
        val placeholder = OoxmlStructure.findAll(shape, "ph").firstOrNull()
        val type = placeholder?.let { attr(it, "type") }.orEmpty()
        val title = type.equals("title", true) || type.equals("ctrTitle", true) ||
            type.equals("vertTitle", true) || type.equals("subTitle", true) || type.equals("dgmTitle", true)
        if (type.equals("tbl", true) || type.equals("chart", true) || type.equals("obj", true) ||
            type.equals("media", true) || type.equals("dt", true)
        ) {
            // 表格/图表/嵌入对象/视频/日期的占位符：文字在别的部件里，这里不当前正文
            tally.bump(if (type.equals("chart", true)) "chart" else if (type.equals("obj", true)) "object" else "otherShape")
            return
        }
        val body = OoxmlStructure.findAll(shape, "txBody").firstOrNull() ?: return
        OoxmlStructure.childrenOf(body, "p").forEach { paragraph ->
            val made = paragraph(paragraph, if (title) "Heading2" else "Body", links, tally)
            if (made != null) out += made
        }
    }

    /**
     * 一个 `a:p` → 一段。
     *
     * 列表身份只认文件自己写的记号：`buNone` 说"不是列表"，`buAutoNum` 是编号，`buChar` 是圆点，
     * 什么也没写按普通段落排（理由见 [listKind]）。标题占位符不带列表记号也不当列表。
     */
    private fun paragraph(
        element: Element,
        style: String,
        links: Map<String, Rel>,
        tally: PptxTally,
    ): DocPart? {
        val properties = childrenOf(element, "pPr").firstOrNull()
        val level = properties?.let { intAttr(it, "lvl") } ?: 0
        val bullet = properties?.let { listKind(it, tally) }
        val runs = inline(element, null, links, tally)
        if (runs.none { it.text.isNotEmpty() }) return null
        val heading = style == "Heading2"
        val resolved = if (heading) "Heading2" else if (bullet != null) "ListParagraph" else style
        return DocParagraph(
            DocPara(
                merge(runs),
                resolved,
                indent = if (heading || bullet == null) 0 else level.coerceAtLeast(0),
                bullet = if (heading) null else bullet,
            ),
        )
    }

    /**
     * 这段是列表吗：true 圆点、false 编号、null 不是列表。
     *
     * 只认文件里写着的记号：`buChar` 圆点、`buAutoNum` 编号、`buNone` 明说不是。
     * **什么也没写时不算列表** —— 那种白靠的是版式默认（在 slideLayout / slideMaster 的
     * `a:lstStyle` 里），而默认是什么取决于主题设计；从"这是正文占位符"推"它该有圆点"
     * 就是拿猜的当读出来的（pandoc 的 pptx 读者同样不推）。数几段报几句，不静悄悄。
     */
    private fun listKind(properties: Element, tally: PptxTally): Boolean? {
        if (childrenOf(properties, "buNone").isNotEmpty()) return null
        if (childrenOf(properties, "buAutoNum").isNotEmpty()) return false
        if (childrenOf(properties, "buChar").isNotEmpty()) return true
        tally.bump("noMarker")
        return null
    }

    /**
     * 一行里的格子。
     *
     * 合并的写法与 ODF 一样有两条：`gridSpan="N"` 说这格横占 N 列，后面可能**又**摆几个
     * `hMerge="1"` 的续格（LibreOffice 两种一起写）。续格占的就是跨度里那些列，
     * 各数一遍整行就比声明的列数宽 —— 与 ODS 那边同一条修正。
     */
    private fun rowCells(rowElement: Element, links: Map<String, Rel>, tally: PptxTally): List<String> {
        val cells = ArrayList<String>()
        var claimed = 0
        children(rowElement) { node ->
            val cell = node as? Element ?: return@children
            if (localName(cell) != "tc") return@children
            if (claimed > 0 && continuesMerge(cell)) {
                claimed--
                return@children
            }
            claimed = cellText(cell, links, tally, cells)
        }
        return cells
    }

    private fun continuesMerge(cell: Element): Boolean =
        attr(cell, "hMerge")?.let { it != "0" && !it.equals("false", true) } == true ||
            attr(cell, "vMerge")?.let { it != "0" && !it.equals("false", true) } == true

    /** 一个 `a:tbl` → 表格。 */
    private fun table(
        element: Element,
        links: Map<String, Rel>,
        tally: PptxTally,
        out: ArrayList<DocPart>,
    ) {
        val properties = childrenOf(element, "tblPr").firstOrNull()
        val header = properties?.let { flag(it, "firstRow") } == true
        val rows = ArrayList<List<String>>()
        childrenOf(element, "tr").forEach { rowElement ->
            val cells = rowCells(rowElement, links, tally)
            if (cells.isEmpty()) return@forEach
            rows += cells
        }
        if (rows.isEmpty()) return
        tally.bump("tbl")
        out += DocTable(header, rows)
    }

    /**
     * 一个格子，返回"它还该吃掉后面几个续格"。
     *
     * `gridSpan` 是"这格横占几列"，不补空位下面的行就会整体往左错位；
     * `rowSpan` 只数一笔（竖着合并的续格在下一行里，由 [rowCells] 认领）。
     * 格子里多段合成一格（段间换行留着），与 docx / odt 那两侧同一条做法。
     */
    private fun cellText(cell: Element, links: Map<String, Rel>, tally: PptxTally, out: ArrayList<String>): Int {
        val pieces = ArrayList<String>()
        OoxmlStructure.findAll(cell, "p").forEach { paragraph ->
            val text = inline(paragraph, null, links, tally).joinToString("") { it.text }
            if (text.isNotEmpty()) pieces += text
        }
        val spanned = (intAttr(cell, "gridSpan") ?: 1).coerceAtLeast(1)
        if ((intAttr(cell, "rowSpan") ?: 1) > 1) tally.bump("rowSpan")
        val text = pieces.joinToString("\n").trim()
        out += text
        repeat(spanned - 1) { out += "" }
        return spanned - 1
    }

    /** 段内的字按文档顺序收：链接、粗斜、换行都在 run 自己的位置上。 */
    private fun inline(
        element: Element,
        link: String?,
        links: Map<String, Rel>,
        tally: PptxTally,
    ): List<DocRun> {
        val out = ArrayList<DocRun>()
        children(element) { node ->
            val child = node as? Element ?: return@children
            when (localName(child)) {
                "r" -> {
                    val properties = childrenOf(child, "rPr").firstOrNull()
                    val target = properties?.let { runLink(it, links, tally) } ?: link
                    val text = childrenOf(child, "t").joinToString("") { it.textContent }
                    if (text.isNotEmpty() || target != null) {
                        out += DocRun(
                            text,
                            bold = properties?.let { onFlag(it, "b") } == true,
                            italic = properties?.let { onFlag(it, "i") } == true,
                            strike = properties?.let { strike(it) } == true,
                            mono = properties?.let { monospace(it) } == true,
                            underline = properties?.let { onFlag(it, "u") } == true,
                            link = target,
                        )
                    }
                }
                "br" -> out += DocRun("\n", link = link)
                "tab" -> out += DocRun("\t", link = link)
                "fld" -> {
                    // 页码 / 日期 / 文件名这类域：文件里存着算过的字就用，没算过的不编
                    val made = childrenOf(child, "t").joinToString("") { it.textContent }
                    if (made.isEmpty()) tally.bump("field") else out += DocRun(made, link = link)
                }
                else -> Unit
            }
        }
        return out
    }

    /** run 上的链接：`a:hlinkClick` 的 `r:id` 过这一页的关系表拿照字面的外部地址。 */
    private fun runLink(properties: Element, links: Map<String, Rel>, tally: PptxTally): String? {
        val click = childrenOf(properties, "hlinkClick").firstOrNull() ?: return null
        val id = click.getAttribute("r:id").ifEmpty { click.getAttribute("id") }
            .ifEmpty { OoxmlStructure.referenceId(click) ?: "" }
        val rel = links[id]
        if (rel == null) {
            tally.bump("linkMissing")
            return null
        }
        if (!rel.external) {
            // 段内跳转的目标是包里的另一个部件（"点到第 3 页"），产物里没有能跳出去的地址
            tally.bump("linkInternal")
            return null
        }
        if (rel.target.isBlank()) {
            tally.bump("linkMissing")
            return null
        }
        tally.bump("link")
        return rel.target
    }

    private fun strike(properties: Element): Boolean {
        val value = attr(properties, "strike") ?: return false
        return value != "noStrike" && value != "none"
    }

    /** 等宽：看 run 自己指定的拉丁字体。演示稿里的代码就是这么标的（没有"代码样式"这个概念）。 */
    private fun monospace(properties: Element): Boolean {
        val latin = childrenOf(properties, "latin").firstOrNull() ?: return false
        val face = attr(latin, "typeface").orEmpty().trim().trim('"', '\'').lowercase()
        return face.isNotEmpty() && face in MONOSPACE
    }

    private fun onFlag(element: Element, name: String): Boolean {
        val value = attr(element, name) ?: return false
        return value != "0" && !value.equals("false", true) && !value.equals("none", true)
    }

    private fun flag(element: Element, name: String): Boolean? {
        val value = attr(element, name) ?: return null
        return value != "0" && !value.equals("false", true)
    }

    private fun intAttr(element: Element, name: String): Int? = attr(element, name)?.toIntOrNull()

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

    private val MONOSPACE = setOf(
        "courier", "courier new", "consolas", "menlo", "monaco", "liberation mono",
        "dejavu sans mono", "source code pro", "jetbrains mono", "fira mono", "ubuntu mono",
        "andale mono", "fixedsys", "noto sans mono", "inconsolata", "mono", "monospace",
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

    private fun children(node: Node, each: (Node) -> Unit) {
        val kids = node.childNodes
        for (index in 0 until kids.length) each(kids.item(index))
    }

    private fun childrenOf(node: Node, local: String): List<Element> = OoxmlStructure.childrenOf(node, local)

    private fun localName(node: Node): String = OoxmlStructure.localName(node)
}

/** 数"有什么没搬"用的计数表。 */
private class PptxTally {
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
        say(get("pic")) { "文里 $it 处图片搬不过来" }
        say(get("chart")) { "$it 处图表的字与数据在别的部件里，这里只有个占位，没搬" }
        say(get("object")) { "$it 处嵌入对象（别的文件）只是附件，没当成文字搬" }
        say(get("tbl")) { "$it 张表按行列搬过来，格子里多段合成一格（段间换行留着）" }
        say(get("rowSpan")) { "$it 处竖着合并的格子只占了一行，下面的行按空补齐" }
        say(get("link")) { "$it 处超链接带地址搬过来" }
        say(get("linkMissing")) { "$it 处链接在这页的关系表里找不到目标，只留文字" }
        say(get("linkInternal")) { "$it 处链接是段内跳转（指着这份稿子里的另一处），转出来的文件里没有能点过去的地方，只留文字" }
        say(get("field")) { "$it 处页码/日期这类域没算过，搬不出来是空的" }
        say(get("noMarker")) { "$it 段没写列表记号（那种白要靠母版的默认版式，不在文件里），按普通段落排" }
        say(get("missingPart")) { "$it 页在演示大纲里列着但包里找不到那一页的部件" }
        say(get("otherShape")) { "$it 处占位符装的不是文字（表格/视频/日期那类），没当前正文" }
        return list
    }
}
