package com.fileforge.core.office

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable
import com.fileforge.core.office.OoxmlStructure.localName
import org.w3c.dom.Element
import org.w3c.dom.Node

/** 读出来的一棵文档树，加上"有什么没搬"的交代。 */
class DocxBody(val doc: Doc, val notes: List<String>)

/**
 * WordprocessingML（.docx 的正文部件）→ [Doc]：`DocxWrite` 的镜像读法。
 *
 * 为什么要有这一份：Word 转 Markdown / 网页过去只能"抽平文字再猜版式"—— 而稿子里本来
 * 就写着这是二级标题、这是有编号的第三层列表、这是表头。猜出来的结构会错（一份 Word 稿
 * 转成 Markdown 结果标题全没了，或者一段列表被排成一串短段落）。
 *
 * 认识的记号（都是 ECMA-376 里 Word 自己写的那套，与写侧同一套 id）：
 *  - `w:pStyle` → 段落样式：`styles.xml` 里那条的 `w:name` 先翻成规范名（`heading 1` → `Heading1`），
 *    认不出的一律按 `Body` —— 不拿样式名当标题层级，那种文件里名字是自由文本
 *  - `w:numPr` → 列表：`numbering.xml` 里这一级的 `w:numFmt` 是 `bullet` 还是十进制之类，
 *    决定圆点还是编号；`w:ilvl` 是层级深度。编号信息找不到时按圆点排并说明（不装作知道）
 *  - `w:rPr` 的 `b` / `i` / `strike` / `rStyle val="VerbatimChar"` → 粗体 / 斜体 / 删除线 / 等宽；
 *    `w:u`（下划线）不在模型里，只数一笔
 *  - `w:hyperlink` 的 `r:id` 过一层关系表拿地址；只有 `w:anchor` 的段内跳转数一笔
 *  - `w:tbl` → 表格，第一行带 `w:tblHeader` 的算表头；格子里的多段合成一格（段间换行留着）
 *  - `w:pBdr` 带下边线的空段落 → 分隔线（写侧就是这么写的）
 *  - 修订：`w:ins` 里的字算已接受的，`w:delText` 按已删除处理不搬
 *
 * 整棵跳过的（与抽文字那条同一套判断）：页眉页脚、脚注尾注批注、域代码、文本框里的字。
 */
object DocxRead {

    private const val DOCX_RELS = "word/_rels/document.xml.rels"
    private const val DOCX_STYLES = "word/styles.xml"
    private const val DOCX_NUMBERING = "word/numbering.xml"

    /** 读一份 docx 的正文结构。[load] 按部件名给字节，找不到给 null —— 缺件不报错，只是少认一些记号。 */
    fun read(load: (String) -> ByteArray?): DocxBody {
        val document = load(OoxmlParts.DOCX_BODY)
            ?: throw IllegalArgumentException("这份 docx 里没有 word/document.xml，正文不在这里")
        val tally = DocxTally()
        val styles = styleNames(load(DOCX_STYLES), tally)
        val numbering = numberFormats(load(DOCX_NUMBERING), tally)
        val links = externalLinks(load(DOCX_RELS))
        val parts = ArrayList<DocPart>()
        val body = childrenOf(OoxmlXml.root(document), "body").firstOrNull() ?: OoxmlXml.root(document)
        children(body) { node ->
            val element = node as? Element ?: return@children
            when (localName(element)) {
                "p" -> paragraph(element, styles, numbering, links, tally)?.let { parts += it }
                "tbl" -> table(element, styles, numbering, links, tally)?.let { parts += it }
                "sectPr" -> Unit                                     // 页面尺寸与分节：文字转换用不上
                else -> Unit
            }
        }
        val losses = tally.losses()
        return DocxBody(Doc(parts, losses), losses)
    }

    /**
     * 样式 id → 规范样式名。
     *
     * Word 文件里 `w:pStyle` 写的是 id，人读的名字在 `styles.xml` 的 `w:name` 里，
     * 而"标题 1"这类名字各语言与各家写法都不完全一样，所以只认对得上的那些；
     * 对不上的一律 `Body`：宁可少排一级标题，也不要把作者起的名字当层级结构。
     */
    private fun styleNames(styles: ByteArray?, tally: DocxTally): Map<String, String> {
        if (styles == null) {
            tally.bump("styles")
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        childrenOf(OoxmlXml.root(styles), "style").forEach { element ->
            if (element.getAttribute("type").isNotEmpty() && element.getAttribute("type") != "paragraph") return@forEach
            val id = element.getAttribute("w:styleId").ifEmpty { element.getAttribute("styleId") }
            if (id.isEmpty()) return@forEach
            val name = childrenOf(element, "name").firstOrNull()?.let {
                it.getAttribute("w:val").ifEmpty { it.getAttribute("val") }
            }.orEmpty()
            out[id] = canonical(name.ifEmpty { id })
        }
        return out
    }

    private fun canonical(name: String): String {
        val flat = name.trim().lowercase().replace(Regex("[\\s_-]+"), " ")
        val heading = Regex("^heading ([1-9])$").find(flat)?.groupValues?.get(1)
        if (heading != null) return "Heading${heading.toInt().coerceAtMost(6)}"
        return when (flat) {
            "title" -> "Heading1"                     // 封面题名进一级：文档大纲上它就在最上层
            "quote", "block text", "intense quote" -> "Quote"
            "preformatted text", "html preformatted", "source code", "code" -> "SourceCode"
            "list paragraph", "list bullet", "list number", "list 1", "list 2" -> "ListParagraph"
            "body text", "normal", "default paragraph font", "first paragraph" -> "Body"
            else -> "Body"
        }
    }

    /**
     * 编号 id → 每一级是圆点还是编号。
     *
     * `w:num` 只给"这份编号叫什么"，真正的格式在 `w:abstractNum` 的每一级里；
     * 两级都要过一遍，少过一级就会把十进制编号排成圆点。
     */
    private fun numberFormats(numbering: ByteArray?, tally: DocxTally): Map<String, List<Boolean>> {
        if (numbering == null) {
            tally.bump("numbering")
            return emptyMap()
        }
        val root = OoxmlXml.root(numbering)
        val abstract = HashMap<String, List<Boolean>>()
        childrenOf(root, "abstractNum").forEach { element ->
            val id = attr(element, "abstractNumId") ?: return@forEach
            val levels = childrenOf(element, "lvl").sortedBy { (attr(it, "ilvl") ?: "0").toIntOrNull() ?: 0 }
            abstract[id] = levels.map { level ->
                val format = childrenOf(level, "numFmt").firstOrNull()?.let { attr(it, "val") } ?: "bullet"
                format != "bullet"             // true = 有编号（十进制、字母、罗马数字…）
            }
        }
        val out = LinkedHashMap<String, List<Boolean>>()
        childrenOf(root, "num").forEach { element ->
            val id = attr(element, "numId") ?: return@forEach
            val target = childrenOf(element, "abstractNumId").firstOrNull()?.let { attr(it, "val") }
                ?: abstract.keys.firstOrNull()
            target?.let { abstract[it]?.let { formats -> out[id] = formats } }
        }
        return out
    }

    private fun attr(element: Element, name: String): String? =
        element.getAttribute("w:$name").takeIf { it.isNotEmpty() } ?: element.getAttribute(name).takeIf { it.isNotEmpty() }

    private fun children(node: Node, each: (Node) -> Unit) {
        val list = node.childNodes
        for (index in 0 until list.length) each(list.item(index))
    }

    // 与 OoxmlStructure 共用同一套"按本名找元素"的认法（那里是内部成员，这里转一下手）
    private fun childrenOf(node: Node, local: String): List<Element> = OoxmlStructure.childrenOf(node, local)

    /**
     * 超链接的目标：`TargetMode="External"` 的那几条**照字面**取。
     *
     * 不能复用 [OoxmlStructure.relationships] —— 那个是给"部件在包里的哪里"用的，
     * 会把目标按源部件所在目录折算（`word/` 前缀）。外部地址那么一折就成了
     * `word/https://…`，看着还像个链接，点开什么都没有。
     */
    private fun externalLinks(rels: ByteArray?): Map<String, String> {
        if (rels == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        childrenOf(OoxmlXml.root(rels), "Relationship").forEach { element ->
            val id = element.getAttribute("Id").takeIf { it.isNotEmpty() } ?: return@forEach
            val mode = attr(element, "TargetMode").orEmpty()
            val target = attr(element, "Target") ?: return@forEach
            if (mode.equals("External", ignoreCase = true) && target.isNotBlank()) out[id] = target
        }
        return out
    }

    private fun localName(node: Node): String = OoxmlStructure.localName(node)

    /**
     * 一个 `w:p` → 一段。
     *
     * 返回 null 只有一种情况：这一段什么都没有（连空白文字都没有）且不带分隔线 ——
     * Word 文件里段末总跟着些空段落，全写出去会让产物多出一堆空行。
     */
    private fun paragraph(
        element: Element,
        styles: Map<String, String>,
        numbering: Map<String, List<Boolean>>,
        links: Map<String, String>,
        tally: DocxTally,
    ): DocPart? {
        val properties = childrenOf(element, "pPr").firstOrNull()
        // pStyle 是"子元素带 w:val"的写法（<w:pStyle w:val="Heading1"/>），不是父元素上的属性：
        // 认错这一处，整份稿子的标题层级会全变成正文，还不报错
        var style = properties?.let { childrenOf(it, "pStyle").firstOrNull() }?.let { attr(it, "val") }?.let { styles[it] } ?: "Body"
        var bullet: Boolean? = null
        var indent = 0
        val numPr = properties?.let { childrenOf(it, "numPr").firstOrNull() }
        if (numPr != null) {
            style = "ListParagraph"
            val ilvl = childrenOf(numPr, "ilvl").firstOrNull()?.let { attr(it, "val")?.toIntOrNull() } ?: 0
            indent = ilvl.coerceAtLeast(0)
            val numId = childrenOf(numPr, "numId").firstOrNull()?.let { attr(it, "val") }
            val formats = numId?.let { numbering[it] }
            if (formats == null) {
                bullet = true
                tally.bump("numUnknown")
            } else {
                bullet = !formats.getOrElse(indent) { formats.last() }
            }
        }
        val runs = inlineRuns(element, null, links, tally)
        val text = runs.joinToString("") { it.text }
        if (isRule(properties, text)) return DocRule()
        if (runs.isEmpty() && text.isEmpty()) return null
        return DocParagraph(DocPara(merge(runs), style, indent, bullet))
    }

    /** 写侧把分隔线写成"带下边线的空段落"，读侧照同一条认回来（只认这一段确实没有字的情况）。 */
    private fun isRule(properties: Element?, text: String): Boolean {
        if (text.isNotBlank()) return false
        val border = properties?.let { childrenOf(it, "pBdr").firstOrNull() } ?: return false
        return childrenOf(border, "bottom").isNotEmpty()
    }

    /** 相邻且记号完全一样的run合成一个：写侧会拆开的（比如中途换了字体），不该在产物里露出来。 */
    private fun merge(runs: List<DocRun>): List<DocRun> {
        val out = ArrayList<DocRun>()
        runs.forEach { run ->
            val last = out.lastOrNull()
            if (last != null && last.sameMarksAs(run)) {
                out[out.size - 1] = DocRun(last.text + run.text, last.bold, last.italic, last.strike, last.mono, last.underline, last.link)
            } else {
                out += run
            }
        }
        return out
    }

    private fun DocRun.sameMarksAs(other: DocRun): Boolean =
        bold == other.bold && italic == other.italic && mono == other.mono &&
            strike == other.strike && mono == other.mono && underline == other.underline && link == other.link

    /**
     * 一段里的字按**文档顺序**收。
     *
     * 不能"先把所有 w:r 收完、再收 w:hyperlink 里的" —— 那样句中的链接会掉到句尾：
     * `一个链接在[这里]，加粗的…` 变成 `一个链接在 ，加粗的…[这里]`。
     * 同理，`w:ins`（修订里被接受的那截）也得留在它原来所在的位置。
     */
    private fun inlineRuns(
        element: Element,
        inherited: String?,
        links: Map<String, String>,
        tally: DocxTally,
    ): List<DocRun> {
        val out = ArrayList<DocRun>()
        children(element) { node ->
            val child = node as? Element ?: return@children
            when (localName(child)) {
                "r" -> out += runs(listOf(child), inherited, tally)
                "hyperlink" -> {
                    val target = linkTarget(child, links, tally)
                    out += inlineRuns(child, target, links, tally)
                }
                "ins" -> out += inlineRuns(child, inherited, links, tally)
                "del" -> tally.bump("del")
                else -> Unit
            }
        }
        return out
    }

    /** 链接目标：外部地址从关系表里照字面取；只有书签的段内跳转数一笔，不当地址。 */
    private fun linkTarget(link: Element, links: Map<String, String>, tally: DocxTally): String? {
        val target = OoxmlStructure.referenceId(link)?.let { links[it] }
        if (target.isNullOrBlank()) {
            val anchor = link.getAttribute("w:anchor").ifEmpty { link.getAttribute("anchor") }
            tally.bump(if (anchor.isNotEmpty()) "anchor" else "linkMissing")
            return null
        }
        tally.bump("link")
        return target
    }

    /** 一串 `w:r` → 带记号的文字。[inherited] 是外层 `w:hyperlink` 给的地址。 */
    private fun runs(elements: List<Element>, inherited: String?, tally: DocxTally): List<DocRun> {
        val out = ArrayList<DocRun>()
        elements.forEach { run ->
            val properties = childrenOf(run, "rPr").firstOrNull()
            val bold = flag(properties, "b")
            val italic = flag(properties, "i")
            val strike = flag(properties, "strike") || flag(properties, "dstrike")
            val style = properties?.let { childrenOf(it, "rStyle").firstOrNull() }?.let { attr(it, "val") }
            val mono = style?.lowercase()?.contains("verbatim") == true || style?.lowercase() == "code"
            val underline = flag(properties, "u")
            if (underline) tally.bump("underline")
            val link = inherited
            val text = StringBuilder()
            children(run) { piece ->
                val element = piece as? Element ?: return@children
                when (localName(element)) {
                    "t" -> text.append(element.textContent)
                    "tab" -> text.append('\t')
                    "br", "cr" -> text.append('\n')
                    "noBreakHyphen" -> text.append('-')
                    "delText" -> tally.bump("delText")
                    "drawing", "pic" -> tally.bump("drawing")
                    "sym" -> tally.bump("sym")
                    else -> Unit
                }
            }
            if (text.isEmpty()) return@forEach
            out += DocRun(text.toString(), bold, italic, strike, mono, underline, link?.takeIf { it.isNotBlank() })
        }
        return out
    }

    /** `w:b w:val="0"` 是"这里不加粗"：值不是 on/true/1 的按关处理。 */
    private fun flag(properties: Element?, name: String): Boolean {
        val node = properties?.let { childrenOf(it, name).firstOrNull() } ?: return false
        val value = attr(node, "val")?.lowercase() ?: return true
        return value != "0" && value != "false" && value != "none" && value != "off"
    }

    /** 一张 `w:tbl` → 表格。跨格（gridSpan/vMerge）不还原，只数一笔 —— 与抽文字那条一致。 */
    private fun table(
        element: Element,
        styles: Map<String, String>,
        numbering: Map<String, List<Boolean>>,
        links: Map<String, String>,
        tally: DocxTally,
    ): DocPart? {
        val rows = ArrayList<List<String>>()
        var header = false
        children(element) { rowNode ->
            val row = rowNode as? Element ?: return@children
            if (localName(row) != "tr") return@children
            val cells = ArrayList<String>()
            childrenOf(row, "tc").forEach { cell ->
                val parts = ArrayList<String>()
                children(cell) { inner ->
                    val node = inner as? Element ?: return@children
                    when (localName(node)) {
                        "p" -> paragraph(node, styles, numbering, links, tally)?.let { made ->
                            if (made is DocParagraph) parts += made.para.runs.joinToString("") { it.text }
                        }
                        "tbl" -> {
                            tally.bump("nestedTable")
                            parts += nestedText(node, styles, numbering, links, tally)
                        }
                        else -> Unit
                    }
                }
                cells += parts.joinToString("\n").trim()
            }
            if (cells.isEmpty()) return@children
            if (childrenOf(row, "trPr").any { childrenOf(it, "tblHeader").isNotEmpty() }) header = true
            rows += cells
        }
        if (rows.isEmpty()) return null
        tally.bump("tbl")
        return DocTable(header, rows)
    }

    /** 嵌在格子里的表：不再单独成表，只把里面的行列拍平成一格文字（读的那侧也只数不递归）。 */
    private fun nestedText(
        element: Element,
        styles: Map<String, String>,
        numbering: Map<String, List<Boolean>>,
        links: Map<String, String>,
        tally: DocxTally,
    ): String {
        val made = table(element, styles, numbering, links, tally) as? DocTable ?: return ""
        return made.rows.joinToString(10.toChar().toString()) { row -> row.joinToString(9.toChar().toString()) }
    }
}

/** 数"有什么没搬"用的计数表。 */
private class DocxTally {
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
        say(get("link")) { "$it 处超链接带地址搬过来" }
        say(get("anchor")) { "$it 处段内跳转（跳到某个书签）在网页/Markdown 里没有落点，只留文字" }
        say(get("linkMissing")) { "$it 处超链接的关系表里找不到目标，只留文字" }
        say(get("underline")) { "$it 处下划线写成 <u>（Markdown 里没这种记号，转 Markdown 会丢）" }
        say(get("sym")) { "$it 处符号字符（Wingdings 那类）没有对应文字，没搬" }
        say(get("del") + get("delText")) { "修订里被删掉的 $it 处文字按已删除处理，不出现在结果里" }
        say(get("numUnknown")) { "$it 段的编号在 numbering 部件里找不到，按圆点排" }
        if (get("styles") > 0) list += "这份 docx 没有 styles 部件，样式名只能按 id 猜（认不出的都当正文）"
        if (get("numbering") > 0) list += "这份 docx 没有 numbering 部件，列表全按圆点排（分不出圆点还是编号）"
        return list
    }
}
