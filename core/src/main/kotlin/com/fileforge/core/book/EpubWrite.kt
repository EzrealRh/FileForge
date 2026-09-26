package com.fileforge.core.book

import com.fileforge.core.archive.ZipItem
import com.fileforge.core.archive.ZipWriter
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocTable

/** 一本书里的一章：标题 + 那一章的文档块。 */
class EpubPage(val title: String, val parts: List<DocPart>)

/** 写好的包字节与要交代的话。 */
class EpubOut(val bytes: ByteArray, val notes: List<String>)

/**
 * EPUB 的写出侧：把 [Doc] 打成一份**合法的** `.epub`。
 *
 * 为什么复用 [Doc] 这一层而不是从 Markdown 或网页各拼一套：Word 那条路已经在这层上跑过
 * （pandoc 逐块对过结构），EPUB 再走同一层，"哪些是标题、哪格在第几列"就不会两个产物各说一套。
 *
 * 四件规范上的硬要求单独盯着，错了就是"文件看着在、阅读器打不开"：
 *  - `mimetype` 必须是包里**第一条**且**不压缩**（EPUB 唯一的硬规定）
 *  - 章节文件是 XHTML：标签自己闭合、属性带引号、`&` 与 `<` 必须转义 —— 这里按结构拼，
 *    不存在"从源文字里抄一段没转义的"那种漏法
 *  - OPF 的 spine 与 manifest 要互相对得上：spine 里每个 idref 都得在 manifest 里，
 *    少一条有些工具直接整本拒绝（我们自家读的那侧就是"对不上就报数跳过"）
 *  - 目录交两份：EPUB3 的 `nav.xhtml`（清单上标 `properties="nav"`）与 EPUB2 的 `toc.ncx`，
 *    新阅读器看前者、旧阅读器看后者，只给一份总有一批阅读器抓不到目录
 */
object EpubWrite {

    private const val MIMETYPE = "application/epub+zip"

    /**
     * 就写 `<!DOCTYPE html>`，不带公共标识符。
     *
     * 不是偷懒：带上 XHTML 1.1 的 PUBLIC/SYSTEM 标识符，严谨的解析器会照着 SYSTEM 里那个
     * 地址去**联网取 DTD**（JVM 自带的解析器实测就是这么干，离线当场报错）。
     * 我们自己的 XML 读侧正因为同一个理由拒收带 DTD 的文件 —— 写出侧不该做那个塞地址的人。
     * pandoc 导 EPUB 用的也是这一行（`<?xml …?>` + `<!DOCTYPE html>`），照着它对齐。
     */
    private const val DOCTYPE = "<!DOCTYPE html>"

    /**
     * 按**一级标题**分章：每个 `Heading1` 起一章，标题文字就是章名。
     *
     * 一级标题之前还有内容就单独算一章（叫 [LEAD_TITLE]），一个字都不丢；
     * 整份没有一级标题就成一章，名字用给来的 [fallback]（通常是文件名）。
     * 二级以下的标题**不**另起一章 —— 那是章内的小节，硬拆开会把一本书切成一堆碎片。
     */
    const val LEAD_TITLE = "开篇"

    fun chaptersOf(doc: Doc, fallback: String): List<EpubPage> {
        val pages = ArrayList<EpubPage>()
        var title: String? = null
        var buffer = ArrayList<DocPart>()
        doc.parts.forEach { part ->
            val heading = (part as? DocParagraph)?.para?.takeIf { it.style == "Heading1" }
            if (heading != null) {
                if (buffer.isNotEmpty() || title != null) pages += EpubPage(title ?: LEAD_TITLE, buffer)
                title = heading.text.ifBlank { "无题的一章" }
                // 这一行本身也留在正文里当章头：只拿它当目录名，读回来（EPUB → Markdown / Word）
                // 就少了一级标题，而 pandoc 与 Calibre 出的书都是"章文件里有这个 h1"
                buffer = ArrayList(listOf(part))
            } else {
                buffer += part
            }
        }
        if (buffer.isNotEmpty() || pages.isEmpty()) pages += EpubPage(title ?: LEAD_TITLE, buffer)
        return pages.map { page ->
            if (page.title == LEAD_TITLE && pages.size == 1 && fallback.isNotBlank()) {
                EpubPage(fallback, page.parts)
            } else {
                page
            }
        }
    }

    /**
     * 打成一份 EPUB 的字节。
     *
     * [identifier] 是 `dc:identifier`，读屏软件拿它当书的唯一号；调用方给一个稳定的值
     * （我们用的是"书名 + 生成时间"哈希出来的 uuid 样子），随机数会让同一份文件每次导出都变。
     */
    fun book(
        title: String,
        author: String?,
        pages: List<EpubPage>,
        language: String = "zh",
        identifier: String,
        modifiedAt: Long,
    ): EpubOut {
        require(pages.isNotEmpty()) { "一章都没有，写不出一本书" }
        val notes = ArrayList<String>()
        val stamp = isoOf(modifiedAt)
        val items = ArrayList<ZipItem>()
        items += ZipItem("mimetype", MIMETYPE.length.toLong(), modifiedAt, stored = true) { MIMETYPE.byteInputStream() }
        items += ZipItem("META-INF/container.xml", container().toByteArray(Charsets.UTF_8), modifiedAt)
        items += ZipItem("OEBPS/content.opf", opf(title, author, pages, language, identifier, stamp).toByteArray(Charsets.UTF_8), modifiedAt)
        items += ZipItem("OEBPS/toc.ncx", ncx(title, pages, identifier, stamp).toByteArray(Charsets.UTF_8), modifiedAt)
        items += ZipItem("OEBPS/nav.xhtml", nav(title, pages, language).toByteArray(Charsets.UTF_8), modifiedAt)
        pages.forEachIndexed { index, page ->
            val body = StringBuilder()
            val lists = HtmlBody()
            page.parts.forEach { part -> xhtmlPart(part, body, lists) }
            lists.closeAll(body)
            items += ZipItem(
                "OEBPS/text/ch${index + 1}.xhtml",
                xhtml(page.title, language, body.toString()).toByteArray(Charsets.UTF_8),
                modifiedAt,
            )
        }
        if (author == null) notes += "这本书没有作者信息（源文件里没写），目录里就不编一个"
        notes += "${pages.size} 章 · 没有图片与样式表（源文件里没有可搬的）"
        return EpubOut(ZipWriter.write(items), notes)
    }

    private fun container(): String =
        DECL + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
            "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
            "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"

    private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

    private fun opf(
        title: String,
        author: String?,
        pages: List<EpubPage>,
        language: String,
        identifier: String,
        stamp: String,
    ): String {
        val out = StringBuilder()
        out.append(DECL).append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" ")
            .append("xml:lang=\"").append(escape(language, true)).append("\" unique-identifier=\"id\">")
        out.append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
        out.append("<dc:identifier id=\"id\">").append(escape(identifier)).append("</dc:identifier>")
        out.append("<dc:title>").append(escape(title)).append("</dc:title>")
        out.append("<dc:language>").append(escape(language)).append("</dc:language>")
        if (author != null) out.append("<dc:creator>").append(escape(author)).append("</dc:creator>")
        out.append("<dc:date>").append(stamp).append("</dc:date>")
        out.append("<meta property=\"dcterms:modified\">").append(stamp).append("</meta>")
        out.append("</metadata><manifest>")
        out.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
        out.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
        pages.indices.forEach { index ->
            out.append("<item id=\"c").append(index + 1).append("\" href=\"text/ch").append(index + 1)
                .append(".xhtml\" media-type=\"application/xhtml+xml\"/>")
        }
        out.append("</manifest><spine toc=\"ncx\">")
        pages.indices.forEach { index -> out.append("<itemref idref=\"c").append(index + 1).append("\"/>") }
        out.append("</spine></package>")
        return out.toString()
    }

    /**
     * EPUB3 的目录页：一个标着 `epub:type="toc"` 的 `nav`，里面一项链到一章。
     *
     * 链接只指到**文件**（不带 `#片段`）—— 我们按一级标题切章，一章一个文件，
     * 章内小节的 id 没保证唯一，指过去多半落空，不如老老实实把整章打开。
     */
    private fun nav(title: String, pages: List<EpubPage>, language: String): String {
        val out = StringBuilder()
        out.append(DECL).append(DOCTYPE).append("\n")
            .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" ")
            .append("xml:lang=\"").append(escape(language, true)).append("\" lang=\"").append(escape(language, true)).append("\">")
            .append("<head><meta charset=\"utf-8\"/><title>").append(escape(title)).append("</title></head>")
            .append("<body><nav epub:type=\"toc\" id=\"toc\"><ol>")
        pages.forEachIndexed { index, page ->
            out.append("<li><a href=\"text/ch").append(index + 1).append(".xhtml\">")
                .append(escape(page.title)).append("</a></li>")
        }
        out.append("</ol></nav></body></html>")
        return out.toString()
    }

    /** NCX 是 EPUB2 的目录：`navLabel` 写在 `content` 之前，与读的那侧同一套顺序，两边不能各记一套。 */
    private fun ncx(title: String, pages: List<EpubPage>, identifier: String, stamp: String): String {
        val out = StringBuilder()
        out.append(DECL).append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">")
            .append("<head><meta name=\"dtb:uid\" content=\"").append(escape(identifier)).append("\"/>")
            .append("<meta name=\"dtb:depth\" content=\"1\"/>")
            .append("<meta name=\"dtb:totalPageCount\" content=\"0\"/>")
            .append("<meta name=\"dtb:maxPageNumber\" content=\"0\"/></head>")
            .append("<docTitle><text>").append(escape(title)).append("</text></docTitle><navMap>")
        pages.forEachIndexed { index, page ->
            out.append("<navPoint id=\"n").append(index + 1).append("\" playOrder=\"").append(index + 1).append("\">")
                .append("<navLabel><text>").append(escape(page.title)).append("</text></navLabel>")
                .append("<content src=\"text/ch").append(index + 1).append(".xhtml\"/></navPoint>")
        }
        out.append("</navMap></ncx>")
        return out.toString()
    }

    private fun xhtml(title: String, language: String, body: String): String =
        DECL + DOCTYPE + "\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"" +
            escape(language, true) + "\" lang=\"" + escape(language, true) + "\">" +
            "<head><meta charset=\"utf-8\"/><title>" + escape(title) + "</title></head><body>" + body + "</body></html>"

    /** 整块文档 → XHTML。标签全靠结构拼，源文字里的 `<` 与 `&` 一律走 [escape]。 */
    private fun xhtmlPart(part: DocPart, out: StringBuilder, lists: HtmlBody) {
        when (part) {
            is DocRule -> {
                lists.closeAll(out)
                out.append("<hr/>\n")
            }
            is DocTable -> {
                lists.closeAll(out)
                table(part, out)
            }
            is DocParagraph -> lists.paragraph(part.para, out)
        }
    }

    private fun table(part: DocTable, out: StringBuilder) {
        out.append("<table>")
        part.rows.forEachIndexed { index, row ->
            val tag = if (part.header && index == 0) "th" else "td"
            out.append("<tr>").append(row.joinToString("") { cell -> "<$tag>${escape(cell)}</$tag>" }).append("</tr>")
        }
        out.append("</table>\n")
    }

    /**
     * 一页的正文渲染器。列表的层级状态放在它自己身上，而不是对象级字段上：
     * 同一时刻可能有两本书在导，共享状态会让后一本接着前一本的 `<li>` 写下去。
     */
    private class HtmlBody {
        private class Frame(val mark: String) {
            var liOpen = false
        }

        private val open = ArrayList<Frame>()

        private fun closeTop(out: StringBuilder) {
            val frame = open.removeAt(open.size - 1)
            if (frame.liOpen) out.append("</li>")
            out.append("</").append(frame.mark).append(">")
        }

        fun closeAll(out: StringBuilder) {
            while (open.isNotEmpty()) closeTop(out)
        }

        fun paragraph(para: DocPara, out: StringBuilder) {
            val plain = para.runs.joinToString("") { it.text }
            when {
                para.style == "ListParagraph" -> {
                    val mark = if (para.bullet == false) "ol" else "ul"
                    val depth = para.indent.coerceAtLeast(0)
                    while (open.size > depth + 1) closeTop(out)
                    if (open.size == depth + 1 && open.last().mark != mark) closeTop(out)
                    while (open.size <= depth) {
                        out.append("<").append(mark).append(">")
                        open += Frame(mark)
                    }
                    val top = open.last()
                    if (top.liOpen) out.append("</li>")
                    out.append("<li>")
                    top.liOpen = true
                    runs(para, out, "", "")
                    return
                }
                else -> closeAll(out)
            }
            when {
                para.style.startsWith("Heading") -> {
                    val level = para.style.removePrefix("Heading").toIntOrNull() ?: 1
                    out.append("<h").append(level).append(">").append(escape(plain))
                        .append("</h").append(level).append(">\n")
                }
                para.style == "SourceCode" -> out.append("<pre><code>")
                    .append(escape(plain)).append("</code></pre>\n")
                para.style == "Quote" -> runs(para, out, "<blockquote>", "</blockquote>\n")
                plain.isNotBlank() -> runs(para, out, "<p>", "</p>\n")
                else -> Unit                      // 全空的段落不写：段与段之间本来就有边界
            }
        }

        private fun runs(para: DocPara, out: StringBuilder, opening: String, closing: String) {
            out.append(opening)
            para.runs.forEach { run ->
                var piece = escape(run.text)
                if (run.mono) piece = "<code>$piece</code>"
                if (run.bold) piece = "<strong>$piece</strong>"
                if (run.italic) piece = "<em>$piece</em>"
                if (run.strike) piece = "<del>$piece</del>"
                val target = run.link
                if (!target.isNullOrBlank()) piece = "<a href=\"${escape(target, true)}\">$piece</a>"
                out.append(piece.replace("\n", "<br/>\n"))
            }
            out.append(closing)
        }
    }

    /** 文字进 XML：`&` `<` `>` 必须转义，控制字符丢掉（XML 里根本不允许出现那些字节）。 */
    private fun escape(value: String, attribute: Boolean = false): String {
        val out = StringBuilder(value.length)
        value.forEach { ch ->
            when {
                ch == '&' -> out.append("&amp;")
                ch == '<' -> out.append("&lt;")
                ch == '>' -> out.append("&gt;")
                ch == '"' && attribute -> out.append("&quot;")
                ch == '\t' -> out.append(ch)
                ch.code < 0x20 -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun isoOf(millis: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(millis))
    }

    /**
     * 我们自己的命名空间常量：号是按"这个名字 + 这份内容"算出来的，换一把命名空间就换一套号。
     * 不是 RFC 4122 附录 C 里那四个（那些按 DNS / URL / OID 归类，我们的种子是"书名 + 正文"，哪个都不是）。
     */
    private const val NAMESPACE = "0e7a2b1c-3f4d-5a6b-7c8d-9e0f1a2b3c4d"

    /**
     * 从**正文文字**里数出来的语言标记：中日韩字符占多数是 `zh`，拉丁字母占多数是 `en`，
     * 一个都不像是 `und`（BCP 47 里"没有语言信息"那个值）而不是硬猜一个。
     *
     * 为什么要走文档树而不是数原文：一份中文网页的源码里，`html`、`charset`、`div` 这些标签名
     * 的拉丁字母比正文的汉字还多，照原文数会把中文书标成 `en`。
     * 阅读器拿这个标挑字体与断词规则，标错是看得见的错。
     */
    fun languageOf(doc: Doc): String {
        var cjk = 0
        var latin = 0
        fun tally(value: String) {
            value.forEach { ch ->
                val code = ch.code
                when {
                    code in 0x4E00..0x9FFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF -> cjk++
                    ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                    else -> Unit
                }
            }
        }
        doc.parts.forEach { part ->
            when (part) {
                is DocParagraph -> part.para.runs.forEach { run -> tally(run.text) }
                is DocTable -> part.rows.forEach { row -> row.forEach { tally(it) } }
                is DocRule -> Unit
            }
        }
        return when {
            cjk == 0 && latin == 0 -> "und"
            cjk >= latin -> "zh"
            else -> "en"
        }
    }

    /**
     * 从内容折一个**稳定**的 `dc:identifier`：同一份正文每次导出都是同一个号。
     *
     * 为什么不用随机 UUID：阅读器拿这个号当"是不是同一本书"的判据，随机数意味着同一份文件
     * 导两次就是两本书（书架上多一条重复），而重新导出是常态（改了 Markdown 再导一遍）。
     * 算法是 RFC 4122 §4.3 的名字算法：SHA-1(命名空间字节 + 名字)，取前 16 字节，
     * 再把版本位摆成 5、变异位摆成 RFC 4122 —— 所以任何按 UUID 解析的工具读出来都是个合法 UUID。
     */
    fun identifierFor(seed: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-1")
            .digest(namespaceBytes() + seed.toByteArray(Charsets.UTF_8))
        val id = hash.copyOf(16)
        id[6] = ((id[6].toInt() and 0x0F) or 0x50).toByte()
        id[8] = ((id[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = id.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "urn:uuid:" + hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" +
            hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
    }

    private fun namespaceBytes(): ByteArray {
        val clean = NAMESPACE.replace("-", "")
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
