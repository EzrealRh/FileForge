package com.fileforge.core.doc

/**
 * 一棵 [Doc] → HTML / XHTML。EPUB 的章节文件、网页产物、以及以后别处的正文渲染都走这里。
 *
 * 为什么只有一套：同一份稿子如果"写成电子书是列表、写成网页是段落"，用户看到的是
 * 同一个文件转两次得到两种结果。渲染集中在这里，格式之间只剩**外层文档壳**的差别
 * （XHTML 要 XML 声明与命名空间、HTML5 不要；空元素写法 `<hr/>` 在两种序列化里都合法）。
 *
 * 三条硬规矩：
 *  - 标签全靠结构拼，源文字里的 `<` 与 `&` 一律走 [escape] —— 不存在"抄一段没转义的"那种漏法
 *  - 列表的层级状态挂在**每次渲染**上（[body] 里那个局部对象），不挂在 object 的字段上：
 *    同一时刻可能有两份产物在渲，共享状态会让后一份接着前一份没关的 `<li>` 写下去
 *  - 全空的段落不写 `<p></p>`：段与段之间本来就有边界，多一个空段等于改了版式
 */
object HtmlWrite {

    /** 整页产物：正文片段 + 外层壳。xhtml=true 时带 XML 声明与命名空间（EPUB 的章节文件要这个）。 */
    class Page(val html: String, val notes: List<String>)

    /** 只出正文片段（不含 html/head/body），供整页与"每章一个文件"两种用法共用。 */
    fun body(parts: List<DocPart>): String {
        val out = StringBuilder()
        val lists = ListStack()
        parts.forEach { part -> render(part, out, lists) }
        lists.closeAll(out)
        return out.toString()
    }

    fun page(title: String, parts: List<DocPart>, language: String = "zh", xhtml: Boolean = false): Page {
        val content = body(parts)
        val head = if (xhtml) {
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"${escape(language, true)}\" " +
                "lang=\"${escape(language, true)}\">"
        } else {
            "<!DOCTYPE html>\n<html lang=\"${escape(language, true)}\">"
        }
        val charset = if (xhtml) "<meta charset=\"utf-8\"/>" else "<meta charset=\"utf-8\">"
        val html = head + "<head>" + charset + "<title>" + escape(title) + "</title></head><body>" +
            content + "</body></html>\n"
        val notes = ArrayList<String>()
        if (parts.none { it is DocParagraph && it.para.runs.any { run -> run.text.isNotBlank() } }) {
            notes += "这份内容里没有可读的文字，页面只有结构"
        }
        return Page(html, notes)
    }

    private fun render(part: DocPart, out: StringBuilder, lists: ListStack) {
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

    /** 表：`header=true` 时第一行是 `<th>`；每行的格子数照原文，不补齐也不裁。 */
    private fun table(part: DocTable, out: StringBuilder) {
        out.append("<table>")
        part.rows.forEachIndexed { index, row ->
            val tag = if (part.header && index == 0) "th" else "td"
            out.append("<tr>").append(row.joinToString("") { cell -> "<$tag>${breaks(escape(cell))}</$tag>" }).append("</tr>")
        }
        out.append("</table>\n")
    }

    /**
     * 文字进 XML/HTML：`&` `<` `>` 必须转义，属性值里的 `"` 也要。
     *
     * 换行与制表符**留着**：它们是内容里的排版事实（段内换行、代码块的行），
     * 交给外面决定怎么写 —— 段落里换行变 `<br>`、代码块里保持原样、表格里变 `<br>`。
     * 早先这里连 `\n` 一起丢，结果代码块整块挤成一行、段内换行变成"第一行第二行"连排：
     * 那是把稿子的行结构改没了，不是清理。
     * 其余控制字符（NUL、BEL…）丢掉：XML 里根本不允许那些字节，留在 HTML 里也只是看不见的位置。
     */
    fun escape(value: String, attribute: Boolean = false): String {
        val clean = value.replace("\r\n", "\n").replace("\r", "\n")
        val out = StringBuilder(clean.length)
        clean.forEach { ch ->
            when {
                ch == '&' -> out.append("&amp;")
                ch == '<' -> out.append("&lt;")
                ch == '>' -> out.append("&gt;")
                ch == '"' && attribute -> out.append("&quot;")
                ch == '\t' || ch == '\n' -> out.append(ch)
                ch.code < 0x20 -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * 段内换行写成 `<br/>`，后面**不补换行**。
     *
     * 补一个换行看着让源文件好读，实际会让"写出去再读回来"多出一个空白字符：
     * 读的一侧把 `<br>` 认成一个换行，再照字面把那个换行也算进文字 —— 格子里就多出一格。
     */
    private fun breaks(escaped: String): String = escaped.replace("\n", "<br/>")

    /**
     * 列表的嵌套状态：一次渲染一个实例。
     *
     * `indent` 是层的深度（来自 Word/Markdown 那侧已经算好的缩进），换圆点/编号时先关掉同层的
     * 那个列表 —— 一个 `<ul>` 里套着 `<ol>` 是另一种意思。
     */
    private class ListStack {
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
                    // 标题里不写 <br>：一个跨行的标题在网页里还是标题，只是字与字之间空一格
                    out.append("<h").append(level).append(">").append(escape(plain).replace("\n", " "))
                        .append("</h").append(level).append(">\n")
                }
                para.style == "SourceCode" -> out.append("<pre><code>")
                    .append(escape(plain)).append("</code></pre>\n")
                para.style == "Quote" -> runs(para, out, "<blockquote>", "</blockquote>\n")
                plain.isNotBlank() -> runs(para, out, "<p>", "</p>\n")
                else -> Unit
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
                out.append(breaks(piece))
            }
            out.append(closing)
        }
    }
}
