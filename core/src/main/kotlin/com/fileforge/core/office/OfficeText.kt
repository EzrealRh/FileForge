package com.fileforge.core.office

import com.fileforge.core.model.FileKind
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * OOXML（Office 2007 起的 .docx / .xlsx / .pptx）就是"一个装了固定部件名的 zip"，
 * 所以这里不碰二进制 OLE 的 .doc / .xls / .ppt —— 那要另写一套 FAT 式的流表，
 * 认不出会直说，不会猜出一篇错字。
 */
object OoxmlParts {
    const val DOCX_BODY = "word/document.xml"
    const val XLSX_WORKBOOK = "xl/workbook.xml"
    const val PPTX_PRESENTATION = "ppt/presentation.xml"

    /**
     * 只看包里有哪些部件名。
     *
     * 先判 word 再判 xl：一份 docx 里可以嵌表格，但那个嵌入件是以 `.bin` 躺在包里的，
     * 不会变成条目名，所以三条判据不会互相串 —— 反过来说，真有 `xl/workbook.xml` 当条目名时
     * 它就是工作簿本体。
     */
    fun kindOf(entries: Collection<String>): FileKind = when {
        // EPUB 也是 zip，判据是 container.xml 这个只属于它的部件名
        entries.any { it.equals("META-INF/container.xml", ignoreCase = true) } -> FileKind.Epub
        DOCX_BODY in entries -> FileKind.Docx
        XLSX_WORKBOOK in entries -> FileKind.Xlsx
        PPTX_PRESENTATION in entries -> FileKind.Pptx
        else -> FileKind.Zip
    }
}

/** 抽出来的文本，以及"这份文件里有什么没搬过来"。 */
class Extracted(val text: String, val losses: List<String>)

/**
 * WordprocessingML（.docx 的正文部件）与 DrawingML（.pptx 每页的部件）取文字。
 *
 * 两者是同一套走树规则，只有段落与文本叶子的名字不同，所以合成一个 spec 走：
 * 分头实现会各漏一半边角（表格、文本框、修订里删掉的字），而它们在两份文件里长得一样。
 */
object OfficeText {

    private class Spec(
        val textNames: Set<String>,
        val blockNames: Set<String>,
        val lineBreakNames: Set<String>,
    )

    private val DOCX = Spec(setOf("t"), setOf("p"), setOf("br", "cr"))

    private val PPTX = Spec(setOf("t"), setOf("p"), setOf("br"))

    /** 一份 docx 的正文。 */
    fun docx(part: ByteArray): Extracted = extract(part, DOCX)

    /** 一页 slide 的全部文字（文本框、图形、表格里的都算）。 */
    fun pptxSlide(part: ByteArray): Extracted = extract(part, PPTX)

    private fun extract(part: ByteArray, spec: Spec): Extracted {
        val out = StringBuilder()
        val tally = Tally()
        walk(OoxmlXml.root(part), out, spec, tally, blockEnd = "\n")
        return Extracted(cleanup(out), tally.losses())
    }

    /**
     * 深度优先走一遍。
     *
     * [blockEnd] 是段落结束时补的字符：正文补换行，表格单元格内部只能补空格 ——
     * 格子里冒出换行会把"一行一记录"的行结构打断。
     */
    private fun walk(node: Node?, out: StringBuilder, spec: Spec, tally: Tally, blockEnd: String) {
        val element = node as? Element ?: return
        when (localName(element)) {
            // 表格单独走：一行横着拼，行与行之间才换行
            "tbl" -> table(element, out, spec, tally)
            in spec.textNames -> out.append(element.textContent)
            // 段内制表符与换行是排版信息，丢了会把两截字粘成一截
            "tab" -> out.append('\t')
            "noBreakHyphen" -> out.append('-')
            in spec.lineBreakNames -> out.append('\n')
            in spec.blockNames -> {
                val from = out.length
                children(element) { walk(it, out, spec, tally, blockEnd) }
                // 只看这一段自己产出的部分：里面嵌了段落块（文本框、格子里的段落）就已经收过尾，
                // 不再补第二个；但空段落本身一个字都没产出，那一行空白是作者留的，必须补
                if (!out.substring(from).endsWith(blockEnd)) out.append(blockEnd)
            }
            in COUNTED -> {
                tally.bump(localName(element))
                children(element) { walk(it, out, spec, tally, blockEnd) }
            }
            in SKIPPED -> tally.bump(localName(element))         // 数一笔，但整棵子树不进去
            else -> children(element) { walk(it, out, spec, tally, blockEnd) }
        }
    }

    private fun table(node: Element, out: StringBuilder, spec: Spec, tally: Tally) {
        tally.bump("tbl")
        children(node) { row ->
            if (localName(row) != "tr") return@children
            val cells = ArrayList<String>()
            children(row) { cell ->
                if (localName(cell) != "tc") return@children
                val buf = StringBuilder()
                children(cell) { walk(it, buf, spec, tally, blockEnd = " ") }
                cells += buf.toString().trim()
            }
            if (cells.isNotEmpty()) out.append(cells.joinToString("\t")).append('\n')
        }
    }

    /** 段末分隔符会在最后多留一个空行，收掉；非空结果仍以一个换行结尾。 */
    private fun cleanup(out: StringBuilder): String {
        val text = out.toString().trimEnd('\n')
        return if (text.isEmpty()) "" else "$text\n"
    }

    /** 要数着报给用户的：这些东西确实还在文件里，只是没进这份文本。 */
    private val COUNTED = setOf("drawing", "pic", "footnoteReference", "endnoteReference", "commentReference", "hyperlink")

    /** 整棵跳过的：文字确实在文件里，但按各自的规矩不该出现在正文里。 */
    private val SKIPPED = setOf("delText", "instrText", "headerReference", "footerReference")

    /** 计数表，顺带把计数翻成"会丢什么"的句子。 */
    private class Tally {
        private val counts = HashMap<String, Int>()
        fun bump(name: String) { counts[name] = (counts[name] ?: 0) + 1 }
        operator fun get(name: String): Int = counts[name] ?: 0

        fun losses(): List<String> {
            val list = ArrayList<String>()
            fun say(count: Int, write: (Int) -> String) {
                if (count > 0) list += write(count)
            }
            say(get("drawing") + get("pic")) { "文里 $it 处图片/图形搬不过来" }
            say(get("tbl")) { "$it 张表被拍平成行：单元格之间用制表符，格内多段并成一行" }
            say(get("footnoteReference")) { "$it 处脚注在 footnotes 部件里，没并进正文" }
            say(get("endnoteReference")) { "$it 处尾注在 endnotes 部件里，没并进正文" }
            say(get("commentReference")) { "$it 处批注不搬" }
            say(get("delText")) { "修订里被删掉的 $it 处文字按已删除处理，不出现在结果里" }
            say(get("instrText")) { "$it 处域代码只留算出来的结果，代码本身不搬" }
            say(get("headerReference")) { "$it 处页眉不在正文部件里，不搬" }
            say(get("footerReference")) { "$it 处页脚与页码不在正文部件里，不搬" }
            say(get("hyperlink")) { "$it 处超链接只留文字，链接目标在关系部件里" }
            return list
        }
    }

    private inline fun children(node: Node, each: (Node) -> Unit) {
        val list = node.childNodes
        for (index in 0 until list.length) each(list.item(index))
    }

    /** 元素的本名（去掉命名空间前缀）：`w:p` 与默认命名空间下的 `p` 要认成同一个。 */
    private fun localName(node: Node): String = (node as? Element)?.let { it.localName ?: it.tagName.substringAfter(':') } ?: ""
}
