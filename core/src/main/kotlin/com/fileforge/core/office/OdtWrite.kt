package com.fileforge.core.office

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocTable

/** 写好的 .odt 包字节，加上"按什么规矩排的"的交代。 */
class OdtOut(val bytes: ByteArray, val notes: List<String>)

/**
 * 一份最小但齐全的 OpenDocument 文字文档（.odt）：`mimetype` + 清单 + `styles.xml` + `content.xml`。
 *
 * 为什么要写这一族：转换工具站上 `docx → odt`、`markdown → odt`、`网页 → odt` 都是常备条目，
 * LibreOffice / FreeOffice / Gnumeric 家一族都吃 .odt —— 只有读没有写，这条链子就断在半路。
 *
 * 三条主张（都是 [OdtRead] 读回来的那套规矩，写读两边不各说各话）：
 *  - **记号写在样式里，正文只引样式名**：`fo:font-weight` 直接堆在 `text:span` 上，
 *    别人的编辑器照样显示，但我们自己的读路和 LibreOffice 的大纲/样式面板就当没看见
 *  - **标题用 `text:h` + `text:outline-level`**：层级写在元素上，大纲视图与目录认的是这个，
 *    样式名只是给人看的
 *  - **样式名写 ODF 的内部名**（空格编码成 `_20_`：`Heading_20_1` 就是 `Heading 1`），
 *    给人看的名字另写一份 `style:display-name` —— 换了界面语言的软件也认得这是几级标题
 *
 * `Doc` 里没有的事不编：列宽（每列等宽并在说明里写）、页面尺寸（用默认页面的继承），
 * 页眉页脚不写。
 */
object OdtWrite {

    private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val MIME = "application/vnd.oasis.opendocument.text"
    private const val BULLET_STYLE = "LB"
    private const val NUMBER_STYLE = "LN"

    /** 列表样式定义到第几层：LibreOffice 也是十层，超过十层的嵌套按最里那层排。 */
    private const val MAX_LEVEL = 10

    /** 一份文档写成一个包。[title] 非空时进 `meta.xml` 的 `dc:title`。 */
    fun document(doc: Doc, title: String = "", modifiedAt: Long = System.currentTimeMillis()): OdtOut {
        val runs = RunStyles()
        val tally = OdtWriteTally()
        val body = StringBuilder()
        val lists = ListStack(body, runs, tally)
        doc.parts.forEach { part -> block(part, body, runs, lists, tally) }
        lists.closeAll()

        val xml = StringBuilder()
        xml.append(DECL).append("<office:document-content").append(declarations).append(">")
        xml.append("<office:automatic-styles>")
        xml.append(runs.render())
        xml.append(COLUMN_STYLE)
        xml.append("</office:automatic-styles>")
        xml.append("<office:body><office:text text:use-soft-page-breaks=\"true\">")
        xml.append(body)
        xml.append("</office:text></office:body></office:document-content>")

        val items = ArrayList<com.fileforge.core.archive.ZipItem>()
        items += com.fileforge.core.archive.ZipItem("mimetype", MIME.length.toLong(), modifiedAt, stored = true) {
            MIME.byteInputStream()
        }
        val meta = if (title.isBlank()) null else metaXml(title)
        items += item("META-INF/manifest.xml", manifest(meta != null), modifiedAt)
        if (meta != null) items += item("meta.xml", meta, modifiedAt)
        items += item("styles.xml", styles(), modifiedAt)
        items += item("content.xml", xml.toString(), modifiedAt)
        val bytes = com.fileforge.core.archive.ZipWriter.write(items)
        val notes = ArrayList<String>()
        if (tally.headings > 0) notes += "${tally.headings} 个标题写成 `text:h` + `text:outline-level`"
        if (tally.lists > 0) notes += "${tally.lists} 条列表用 ODF 的列表样式，记号由软件自己画"
        if (tally.tables > 0) notes += "${tally.tables} 张表：文档树里没有列宽这件事，每列按等宽写"
        if (tally.links > 0) notes += "${tally.links} 处链接带地址写成 `text:a`"
        if (runs.size > 0) notes += "${runs.size} 组记号写进样式表（T1…），正文只引样式名"
        return OdtOut(bytes, notes)
    }

    private fun item(name: String, xml: String, modifiedAt: Long): com.fileforge.core.archive.ZipItem =
        com.fileforge.core.archive.ZipItem(name, xml.toByteArray(Charsets.UTF_8), modifiedAt)

    private val declarations: String
        get() = " xmlns:office=\"$OFFICE\" xmlns:style=\"$STYLE\" xmlns:text=\"$TEXT\" xmlns:table=\"$TABLE\"" +
            " xmlns:list=\"$LIST\" xmlns:fo=\"$FO\" xmlns:xlink=\"$XLINK\" office:version=\"1.2\""

    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val LIST = "urn:oasis:names:tc:opendocument:xmlns:list:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    private const val MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"
    private const val META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
    private const val DC = "http://purl.org/dc/elements/1.1/"

    /** 每列一条 `<table:table-column>`，都指这个自动样式；宽度是文档树里没有的信息，取等宽。 */
    private const val COLUMN_STYLE =
        "<style:style style:name=\"co1\" style:family=\"table-column\">" +
            "<style:table-column-properties style:column-width=\"2.54cm\"/></style:style>"

    private fun manifest(withMeta: Boolean): String {
        val out = StringBuilder(DECL)
            .append("<manifest:manifest xmlns:manifest=\"").append(MANIFEST).append("\">")
            .append(entry("", MIME))
        if (withMeta) out.append(entry("meta.xml", "text/xml"))
        out.append(entry("styles.xml", "text/xml")).append(entry("content.xml", "text/xml"))
        return out.append("</manifest:manifest>").toString()
    }

    private fun entry(path: String, type: String): String =
        "<manifest:file-entry manifest:full-path=\"$path\" manifest:media-type=\"$type\"/>"

    private fun metaXml(title: String): String = DECL +
        "<office:document-meta xmlns:office=\"$OFFICE\" xmlns:meta=\"$META\" xmlns:dc=\"$DC\" office:version=\"1.2\">" +
        "<office:meta><dc:title>" + escape(title) + "</dc:title>" +
        "<meta:generator>FileForge</meta:generator></office:meta></office:document-meta>"

    /**
     * `styles.xml`：正文引的那些名字都在这里定义。
     *
     * 内部名与给人看的名字分开写（`Heading_20_1` / "Heading 1"），列表的两个样式把
     * 圆点与编号定义到第 [MAX_LEVEL] 层 —— 记号由软件画，绝不当成正文里的字符。
     */
    private fun styles(): String {
        val out = StringBuilder(DECL)
        out.append("<office:document-styles xmlns:office=\"").append(OFFICE)
            .append("\" xmlns:style=\"").append(STYLE)
            .append("\" xmlns:text=\"").append(TEXT)
            .append("\" xmlns:list=\"").append(LIST)
            .append("\" xmlns:fo=\"").append(FO)
            .append("\" xmlns:svg=\"").append(SVG)
            .append("\" office:version=\"1.2\">")
        out.append("<office:font-face-decls>")
            .append("<style:font-face style:name=\"Courier\" svg:font-family=\"'Courier New', monospace\" ")
            .append("style:font-family-generic=\"modern\" style:font-pitch=\"fixed\"/>")
            .append("</office:font-face-decls>")
        out.append("<office:styles>")
            .append("<style:default-style style:family=\"paragraph\">")
            .append("<style:paragraph-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0.247cm\"/>")
            .append("<style:text-properties fo:font-size=\"12pt\"/></style:default-style>")
        out.append(paragraph("Standard"))
        out.append(paragraph("Text_20_Body", display = "Text Body", parent = "Standard"))
        for (level in 1..6) {
            out.append(
                paragraph(
                    "Heading_20_$level", display = "Heading $level", parent = "Text_20_Body",
                    onStyle = "text:outline-level=\"$level\" style:next-style-name=\"Text_20_Body\"",
                    para = "fo:break-before=\"auto\" fo:margin-top=\"0.423cm\" fo:margin-bottom=\"0.247cm\"",
                    text = "fo:font-weight=\"bold\" fo:font-size=\"${22 - 2 * level}pt\"",
                ),
            )
        }
        out.append(
            paragraph(
                "Quotations", parent = "Text_20_Body",
                para = "fo:margin-left=\"1.25cm\" fo:margin-right=\"1.25cm\"",
            ),
        )
        out.append(
            paragraph(
                "Preformatted_20_Text", display = "Preformatted Text", parent = "Standard",
                para = "fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" text:keep-with-next=\"always\" " +
                    "text:preptab-width=\"0.5cm\"",
                text = "style:font-name=\"Courier\" fo:font-family=\"'Courier New', monospace\" " +
                    "style:font-family-generic=\"modern\" style:font-pitch=\"fixed\" fo:font-size=\"11pt\"",
            ),
        )
        out.append(paragraph("Source_20_Code", display = "Source Code", parent = "Preformatted_20_Text"))
        out.append(paragraph("List_20_Paragraph", display = "List Paragraph", parent = "Text_20_Body"))
        out.append(
            paragraph(
                "Horizontal_20_Line", display = "Horizontal Line", parent = "Text_20_Body",
                para = "fo:border-bottom=\"0.05pt solid #000000\" fo:padding-bottom=\"0.045cm\"",
            ),
        )
        out.append(listStyle(BULLET_STYLE, true))
        out.append(listStyle(NUMBER_STYLE, false))
        out.append("</office:styles></office:document-styles>")
        return out.toString()
    }

    private fun paragraph(
        name: String,
        display: String? = null,
        parent: String? = null,
        onStyle: String = "",
        para: String = "",
        text: String = "",
    ): String {
        val head = StringBuilder("<style:style style:name=\"").append(name).append("\" style:family=\"paragraph\"")
        display?.let { head.append(" style:display-name=\"").append(attribute(it)).append('"') }
        parent?.let { head.append(" style:parent-style-name=\"").append(it).append('"') }
        if (onStyle.isNotEmpty()) head.append(' ').append(onStyle)
        head.append('>')
        if (para.isNotEmpty()) head.append("<style:paragraph-properties ").append(para).append("/>")
        if (text.isNotEmpty()) head.append("<style:text-properties ").append(text).append("/>")
        return head.append("</style:style>").toString()
    }

    private fun listStyle(name: String, bullet: Boolean): String {
        val out = StringBuilder("<text:list-style style:name=\"").append(name).append("\">")
        for (level in 1..MAX_LEVEL) {
            val position = "<style:list-level-properties text:list-level-position-and-distance=\"" +
                "${"%1.3f".format(level * 0.635)}cm\"/>"
            if (bullet) {
                out.append("<text:list-level-style-bullet text:level=\"").append(level)
                    .append("\" text:bullet-char=\"\u2022\">").append(position)
                    .append("<style:text-properties fo:font-size=\"6pt\"/></text:list-level-style-bullet>")
            } else {
                out.append("<text:list-level-style-number text:level=\"").append(level)
                    .append("\" style:num-suffix=\".\" style:num-format=\"1\">").append(position)
                    .append("</text:list-level-style-number>")
            }
        }
        return out.append("</text:list-style>").toString()
    }

    /** 一块：标题、段落、列表、分隔线或表。列表外的块先把列表关干净。 */
    private fun block(
        part: DocPart,
        out: StringBuilder,
        runs: RunStyles,
        lists: ListStack,
        tally: OdtWriteTally,
    ) {
        when (part) {
            is DocRule -> {
                lists.closeAll()
                out.append("<text:p text:style-name=\"Horizontal_20_Line\"/>")
            }
            is DocTable -> {
                lists.closeAll()
                table(part, out, tally)
            }
            is DocParagraph -> {
                val para = part.para
                if (para.style == "ListParagraph") {
                    lists.item(para)
                    return
                }
                lists.closeAll()
                val heading = para.style.startsWith("Heading")
                if (heading) tally.headings++
                val style = styleFor(para.style)
                if (heading) {
                    val level = para.style.removePrefix("Heading").toIntOrNull()?.coerceIn(1, 6) ?: 1
                    out.append("<text:h text:style-name=\"").append(style)
                        .append("\" text:outline-level=\"").append(level).append("\">")
                } else {
                    out.append("<text:p text:style-name=\"").append(style).append("\">")
                }
                para.runs.forEach { run(it, out, runs, tally) }
                out.append(if (heading) "</text:h>" else "</text:p>")
            }
        }
    }

    private fun styleFor(style: String): String = when {
        style.startsWith("Heading") -> "Heading_20_" + (style.removePrefix("Heading").toIntOrNull()?.coerceIn(1, 6) ?: 1)
        style == "Quote" -> "Quotations"
        style == "SourceCode" -> "Source_20_Code"
        style == "ListParagraph" -> "List_20_Paragraph"
        else -> "Text_20_Body"
    }

    /** 一段里的字：记号走自动样式名，链接走 `text:a`，空格、制表、换行走 ODF 自己的元素。 */
    private fun run(run: DocRun, out: StringBuilder, runs: RunStyles, tally: OdtWriteTally) {
        if (run.text.isEmpty() && run.link.isNullOrBlank()) return
        val style = runs.styleOf(run)
        val open = if (style == null) "" else "<text:span text:style-name=\"$style\">"
        val close = if (style == null) "" else "</text:span>"
        val target = run.link?.takeIf { it.isNotBlank() }
        if (target != null) {
            tally.links++
            out.append(open).append("<text:a xlink:type=\"simple\" xlink:href=\"").append(attribute(target)).append("\">")
                .append(textOf(run.text)).append("</text:a>").append(close)
        } else {
            out.append(open).append(textOf(run.text)).append(close)
        }
    }

    private fun textOf(value: String): String {
        val out = StringBuilder()
        var at = 0
        while (at < value.length) {
            val ch = value[at]
            when {
                ch == '\t' -> {
                    out.append("<text:tab/>")
                    at++
                }
                ch == '\n' -> {
                    out.append("<text:line-break/>")
                    at++
                }
                ch == ' ' -> {
                    var end = at
                    while (end < value.length && value[end] == ' ') end++
                    val count = end - at
                    out.append(if (count == 1) "<text:s/>" else "<text:s text:c=\"$count\"/>")
                    at = end
                }
                else -> {
                    var end = at
                    while (end < value.length && value[end] != ' ' && value[end] != '\t' && value[end] != '\n') end++
                    out.append(escape(value.substring(at, end)))
                    at = end
                }
            }
        }
        return out.toString()
    }

    /**
     * 一张表：列按 `table:table-column` 一条一条声明（不用"重复几根"的压缩写法），
     * 有表头时首行躺在 `table:table-header-rows` 里，其余行在 `table:table-body` 里 —— 读的那侧就按这个判表头。
     */
    private fun table(part: DocTable, out: StringBuilder, tally: OdtWriteTally) {
        val width = part.rows.maxOfOrNull { it.size } ?: 0
        if (width == 0) return
        tally.tables++
        out.append("<table:table table:name=\"").append(attribute("表" + tally.tables)).append("\">")
        repeat(width) { out.append("<table:table-column table:style-name=\"co1\"/>") }
        val headerRows = if (part.header) 1 else 0
        if (headerRows > 0) {
            out.append("<table:table-header-rows>")
            row(part.rows.first(), width, out)
            out.append("</table:table-header-rows>")
        }
        out.append("<table:table-body>")
        part.rows.drop(headerRows).forEach { row(it, width, out) }
        out.append("</table:table-body></table:table>")
    }

    private fun row(values: List<String>, width: Int, out: StringBuilder) {
        out.append("<table:table-row>")
        repeat(width) { column ->
            val value = values.getOrElse(column) { "" }
            if (value.isEmpty()) {
                out.append("<table:table-cell/>")
            } else {
                out.append("<table:table-cell office:value-type=\"string\"><text:p text:style-name=\"Text_20_Body\">")
                    .append(textOf(value)).append("</text:p></table:table-cell>")
            }
        }
        out.append("</table:table-row>")
    }

    /**
     * 列表的套层：ODF 的"第几层"是 `text:list` **套了几层**决定的，所以这里按 `indent` 真的把
     * `text:list` 嵌起来；换记号种类（圆点↔编号）时把当前这层关掉另起一张。
     *
     * 套到别人里面时，父层那一格的 `text:list-item` 还不能关 —— 嵌进去的表就住在那格里。
     */
    private class ListStack(
        private val out: StringBuilder,
        private val runs: RunStyles,
        private val tally: OdtWriteTally,
    ) {
        private class Open(val bullet: Boolean, var itemOpen: Boolean)

        private val stack = ArrayList<Open>()

        fun item(para: DocPara) {
            val want = (para.bullet != false)
            var depth = (para.indent + 1).coerceAtLeast(1)
            if (depth > stack.size + 1) depth = stack.size + 1
            while (stack.size > depth) closeTop()
            if (stack.size == depth && stack.last().bullet != want) closeTop()
            while (stack.size < depth) {
                out.append("<text:list text:style-name=\"").append(styleOf(want)).append("\">")
                stack.add(Open(want, false))
            }
            val top = stack.last()
            if (top.itemOpen) {
                out.append("</text:list-item>")
                top.itemOpen = false
            }
            tally.lists++
            out.append("<text:list-item><text:p text:style-name=\"List_20_Paragraph\">")
            para.runs.forEach { run(it, out, runs, tally) }
            out.append("</text:p>")
            top.itemOpen = true
        }

        fun closeAll() {
            while (stack.isNotEmpty()) closeTop()
        }

        private fun closeTop() {
            val top = stack.removeLast()
            if (top.itemOpen) {
                out.append("</text:list-item>")
                top.itemOpen = false
            }
            out.append("</text:list>")
        }

        private fun styleOf(bullet: Boolean): String = if (bullet) BULLET_STYLE else NUMBER_STYLE
    }

    /**
     * 记号组合 → 自动样式（`T1`…）：同一组记号在一份文件里只定义一次，正文反复引它。
     *
     * 名字按出现顺序发号，所以连着写两份内容一样的文件，样式名也一样（字节可比）。
     */
    private class RunStyles {
        private val ids = LinkedHashMap<String, String>()

        var size = 0
            private set

        /** 这段的样式名；一个记号都没有就是 null（不套 `text:span`）。 */
        fun styleOf(run: DocRun): String? {
            if (!run.bold && !run.italic && !run.strike && !run.underline && !run.mono) return null
            val key = listOf(run.bold, run.italic, run.strike, run.underline, run.mono).joinToString(",")
            ids[key]?.let { return it }
            val name = "T${ids.size + 1}"
            ids[key] = name
            size = ids.size
            return name
        }

        fun render(): String {
            val out = StringBuilder()
            ids.forEach { (key, name) ->
                val flags = key.split(",")
                out.append("<style:style style:name=\"").append(name).append("\" style:family=\"text\">")
                    .append("<style:text-properties")
                if (flags[0] == "true") out.append(" fo:font-weight=\"bold\" style:font-weight-asian=\"bold\"")
                if (flags[1] == "true") out.append(" fo:font-style=\"italic\" style:font-style-asian=\"italic\"")
                if (flags[2] == "true") out.append(" style:text-line-through-style=\"solid\"")
                if (flags[3] == "true") out.append(" style:text-underline-style=\"solid\"")
                if (flags[4] == "true") {
                    out.append(" style:font-name=\"Courier\" fo:font-family=\"'Courier New', monospace\"")
                        .append(" style:font-family-generic=\"modern\" style:font-pitch=\"fixed\"")
                }
                out.append("/></style:style>")
            }
            return out.toString()
        }
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length)
        value.forEach { ch ->
            when {
                ch == '&' -> out.append("&amp;")
                ch == '<' -> out.append("&lt;")
                ch == '>' -> out.append("&gt;")
                ch.code < 0x20 && ch != '\n' && ch != '\t' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun attribute(value: String): String = escape(value).replace("\"", "&quot;")

    private class OdtWriteTally {
        var headings = 0
        var lists = 0
        var tables = 0
        var links = 0
    }
}
