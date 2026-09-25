package com.fileforge.core.office

import com.fileforge.core.archive.ZipItem
import com.fileforge.core.archive.ZipWriter
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocTable

/** 写好的 docx 字节，以及该跟用户交代的话。 */
class DocxOut(val bytes: ByteArray, val notes: List<String>)

/**
 * WordprocessingML（.docx）写入侧：把 [Doc] 那份文档模型拼成一份进 Word / WPS 打得开的文件。
 *
 * 三条是从"别人读不出来"换来的：
 *  - **链接走文档外的关联表**（`r:id` + `TargetMode="External"`）：地址只写在 `<w:t>` 里，Word
 *    会把它当普通文字 —— 看着是蓝字、点开没反应；反过来正文里出现关联表没有的 `rId`，判文件坏了。
 *  - **末尾必须有 `w:sectPr`**：缺它，一些版本的 Word 打开时报"发现不可读取的内容"。
 *  - **表格后面必须跟一段**：两张表紧挨着会被并成一张。
 */
object DocxWrite {

    private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val RP = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val DOCUMENT = "word/document.xml"
    private const val STYLES = "word/styles.xml"
    private const val NUMBERING = "word/numbering.xml"
    private const val DOC_RELS = "word/_rels/document.xml.rels"
    private const val FIRST_LINK_ID = 100
    private const val INDENT_STEP = 360

    /** 分隔线：带下边线的空段。pandoc 的 docx 读路认这个形状，Word 里画出来就是一条横线。 */
    private const val RULE = "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" " +
        "w:color=\"auto\"/></w:pBdr></w:pPr></w:p>"

    /** 拼一份 docx。`modifiedAt` 只影响包里的时间戳，不参与内容比对。 */
    fun document(doc: Doc, modifiedAt: Long = 0L): DocxOut {
        val links = doc.links
        val lost = intArrayOf(0)
        val body = StringBuilder()
        doc.parts.forEach { part ->
            when (part) {
                is DocParagraph -> body.append(paragraph(part.para, links, lost))
                is DocTable -> body.append(table(part, links, lost))
                is DocRule -> body.append(RULE)
            }
        }
        val xml = buildString {
            append(DECL)
            append("<w:document xmlns:w=\"").append(W).append("\" xmlns:r=\"").append(R).append("\"><w:body>")
            append(body)
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
            append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"")
            append(" w:header=\"720\" w:footer=\"720\"/></w:sectPr></w:body></w:document>")
        }

        val parts = LinkedHashMap<String, ByteArray>()
        parts[DOCUMENT] = xml.toByteArray(Charsets.UTF_8)
        parts[STYLES] = stylesXml()
        parts[NUMBERING] = numberingXml()
        parts[DOC_RELS] = docRelsXml(links)
        parts["_rels/.rels"] = (DECL + "<Relationships xmlns=\"" + RP + "\"><Relationship Id=\"rId1\" Type=\"" +
            R + "/officeDocument\" Target=\"" + DOCUMENT + "\"/></Relationships>").toByteArray(Charsets.UTF_8)
        parts["[Content_Types].xml"] = (
            DECL + "<Types xmlns=\"" + CT + "\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                override(DOCUMENT, "wordprocessingml.document.main+xml") +
                override(STYLES, "wordprocessingml.styles+xml") +
                override(NUMBERING, "wordprocessingml.numbering+xml") +
                "</Types>"
            ).toByteArray(Charsets.UTF_8)

        val notes = ArrayList<String>()
        val paragraphs = doc.parts.filterIsInstance<DocParagraph>().count { it.para.runs.isNotEmpty() }
        val tables = doc.parts.filterIsInstance<DocTable>().size
        notes += "$paragraphs 段" + if (tables > 0) " · $tables 张表" else ""
        if (links.isNotEmpty()) notes += "${links.size} 处链接写成能点开的真链接（地址存在文件内的关联表里）"
        if (lost[0] > 0) notes += "${lost[0]} 个字符是 Word 不允许的控制字符，只能去掉（带着它们 Word 判文件坏了）"
        notes += doc.notes
        return DocxOut(ZipWriter.write(parts.map { (name, bytes) -> ZipItem(name, bytes, modifiedAt) }), notes)
    }

    private fun override(part: String, suffix: String) =
        "<Override PartName=\"/" + part + "\" ContentType=\"application/vnd.openxmlformats-officedocument." +
            suffix + "\"/>"

    private fun paragraph(p: DocPara, links: List<String>, lost: IntArray): String {
        val out = StringBuilder("<w:p><w:pPr>")
        if (p.style != "Body") out.append("<w:pStyle w:val=\"").append(p.style).append("\"/>")
        val bullet = p.bullet
        if (bullet != null) {
            // 记号交给 Word 画：删一行序号会自己接着排，复制进别的编辑器也认得出是列表
            out.append("<w:numPr><w:ilvl w:val=\"").append(minOf(8, p.indent)).append("\"/>")
                .append("<w:numId w:val=\"").append(if (bullet) 1 else 2).append("\"/></w:numPr>")
        } else if (p.indent > 0) {
            out.append("<w:ind w:left=\"").append(p.indent * INDENT_STEP).append("\"/>")
        }
        out.append("</w:pPr>")
        p.runs.forEach { run -> out.append(run(run, links, lost)) }
        return out.append("</w:p>").toString()
    }

    private fun run(r: DocRun, links: List<String>, lost: IntArray): String {
        val text = OoxmlText.bleach(r.text) { lost[0] += it }
        if (text.isEmpty()) return ""
        val body = StringBuilder()
        text.split('\n').forEachIndexed { index, line ->
            if (index > 0) body.append("<w:br/>")
            if (line.isNotEmpty()) {
                body.append("<w:t xml:space=\"preserve\">").append(OoxmlText.escape(line)).append("</w:t>")
            }
        }
        val inner = "<w:r>" + properties(r) + body + "</w:r>"
        val id = if (r.link == null) -1 else links.indexOf(r.link)
        if (id < 0) return inner
        return "<w:hyperlink r:id=\"rId${id + FIRST_LINK_ID}\">$inner</w:hyperlink>"
    }

    private fun properties(r: DocRun): String {
        val flags = StringBuilder()
        if (r.link != null) flags.append("<w:rStyle w:val=\"Hyperlink\"/>")
        if (r.bold) flags.append("<w:b/>")
        if (r.italic) flags.append("<w:i/>")
        if (r.strike) flags.append("<w:strike/>")
        if (r.underline) flags.append("<w:u w:val=\"single\"/>")
        // 等宽走字符样式而不是直接格式：pandoc / Word 都按字符样式认"这是代码"，
        // 直接格式会被当成同一种字合并掉，代码与两边的顿号就粘成一坨
        if (r.mono) flags.append("<w:rStyle w:val=\"VerbatimChar\"/>")
        return if (flags.isEmpty()) "" else "<w:rPr>$flags</w:rPr>"
    }

    private fun table(t: DocTable, links: List<String>, lost: IntArray): String {
        val out = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
            .append("<w:tblBorders>")
            .append(border("top")).append(border("left")).append(border("bottom")).append(border("right"))
            .append(border("insideH")).append(border("insideV"))
            .append("</w:tblBorders></w:tblPr>")
        t.rows.forEachIndexed { index, row ->
            out.append("<w:tr>")
            if (t.header && index == 0) out.append("<w:trPr><w:tblHeader/></w:trPr>")
            row.forEach { cell ->
                out.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr><w:p><w:pPr/>")
                    .append(run(DocRun(cell, bold = t.header && index == 0), links, lost))
                    .append("</w:p></w:tc>")
            }
            out.append("</w:tr>")
        }
        return out.append("</w:tbl><w:p/>").toString()
    }

    private fun border(edge: String) = "<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"

    private fun docRelsXml(links: List<String>): ByteArray {
        val out = StringBuilder()
        out.append(DECL).append("<Relationships xmlns=\"").append(RP).append("\">")
            .append("<Relationship Id=\"rId1\" Type=\"").append(R).append("/styles\" Target=\"styles.xml\"/>")
            .append("<Relationship Id=\"rId2\" Type=\"").append(R).append("/numbering\" Target=\"numbering.xml\"/>")
        links.forEachIndexed { index, target ->
            out.append("<Relationship Id=\"rId").append(index + FIRST_LINK_ID).append("\" Type=\"").append(R)
                .append("/hyperlink\" Target=\"").append(OoxmlText.attribute(target)).append("\" TargetMode=\"External\"/>")
        }
        out.append("</Relationships>")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 用到的样式全列出来。
     *
     * Word 认的是**内置样式的 name**（`Heading 1`、`Quote`、`List Paragraph`），不是我们编的 styleId：
     * name 写对才拿得到默认的字号与缩进，styleId 只给正文里的 `w:pStyle` 引用。
     */
    private fun stylesXml(): ByteArray {
        val sizes = listOf(36, 32, 28, 26, 24, 22)
        val headings = (1..6).joinToString("") { level ->
            paragraphStyle(
                "Heading$level", "Heading $level",
                "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:rPr><w:b/>" +
                    "<w:sz w:val=\"${sizes[level - 1]}\"/><w:szCs w:val=\"${sizes[level - 1]}\"/></w:rPr>",
            )
        }
        val list = paragraphStyle("ListParagraph", "List Paragraph",
            "<w:basedOn w:val=\"Normal\"/><w:pPr><w:contextualSpacing/></w:pPr>")
        val quote = paragraphStyle("Quote", "Quote",
            "<w:basedOn w:val=\"Normal\"/><w:pPr><w:ind w:left=\"720\"/><w:pBdr>" +
                "<w:left w:val=\"single\" w:sz=\"6\" w:space=\"4\" w:color=\"A0A0A0\"/></w:pBdr></w:pPr>" +
                "<w:rPr><w:i/><w:color w:val=\"595959\"/></w:rPr>")
        val code = paragraphStyle("SourceCode", "Source Code",
            "<w:basedOn w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>" +
                "<w:shd w:val=\"clear\" w:fill=\"F2F2F2\"/></w:rPr>")
        val hyperlink = charStyle("Hyperlink", "Hyperlink", "<w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/>")
        val verbatim = charStyle("VerbatimChar", "Verbatim Char",
            "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:eastAsia=\"SimHei\"/>" +
                "<w:shd w:val=\"clear\" w:fill=\"F2F2F2\"/>")
        val grid = "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>" +
            "<w:tblPr><w:tblBorders>" + border("top") + border("left") + border("bottom") + border("right") +
            border("insideH") + border("insideV") + "</w:tblBorders></w:tblPr></w:style>"
        val normal = "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>" +
            "<w:qFormat/><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:rPr>" +
            "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:style>"
        return (
            DECL + "<w:styles xmlns:w=\"" + W + "\">" + normal + headings + list + quote + code + hyperlink +
                verbatim + grid +
                "</w:styles>"
            ).toByteArray(Charsets.UTF_8)
    }

    /**
     * 列表的记号定义在这份部件里：Word 画点号与序号，删一行也会自动接着排。
     *
     * 点号用的是 Symbol 字体里的 （那是 Word 的写法和字体约定，不是排版出来的项目符号字符）。
     */
    private fun numberingXml(): ByteArray {
        fun levels(fmt: String, text: (Int) -> String, extra: String) = (0..8).joinToString("") { level ->
            "<w:lvl w:ilvl=\"$level\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/>" +
                "<w:lvlText w:val=\"" + text(level) + "\"/><w:lvlJc w:val=\"left\"/>" +
                "<w:pPr><w:ind w:left=\"${(level + 1) * INDENT_STEP}\" w:hanging=\"360\"/></w:pPr>$extra</w:lvl>"
        }
        val bullet = levels("bullet", { "" },
            "<w:rPr><w:rFonts w:ascii=\"Symbol\" w:hAnsi=\"Symbol\" w:hint=\"default\"/></w:rPr>")
        val decimal = levels("decimal", { level -> "%" + (level + 1) + "." }, "")
        return (
            DECL + "<w:numbering xmlns:w=\"$W\">" +
                "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>$bullet</w:abstractNum>" +
                "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>$decimal</w:abstractNum>" +
                "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
                "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>" +
                "</w:numbering>"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun paragraphStyle(id: String, name: String, extra: String) =
        "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:qFormat/>$extra</w:style>"

    private fun charStyle(id: String, name: String, extra: String) =
        "<w:style w:type=\"character\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:unbasedOn/>$extra</w:style>"
}
