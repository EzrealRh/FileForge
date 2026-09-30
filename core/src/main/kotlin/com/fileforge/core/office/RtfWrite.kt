package com.fileforge.core.office

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPara
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocRule
import com.fileforge.core.doc.DocRun
import com.fileforge.core.doc.DocTable

/** 写好的 .rtf 字节，加上"按什么规矩写的"的交代。 */
class RtfOut(val bytes: ByteArray, val notes: List<String>)

/**
 * 一份老老实实的 RTF（.rtf —— WordPad 与老版 Word 的通用格式）：与 [DocxWrite]、[OdtWrite]
 * 拿的是同一棵 [Doc] 树，落笔换成 RTF 的控制字。
 *
 * 为什么要写这一族：转换工具站上"转成 RTF"是常青条目，而且它是一份**纯文本**的带格式格式 ——
 * 别的格式都靠打包，就它一个文件、记事本都打得开。
 *
 * 四条主张（与 [RtfRead] 读回来的那套规矩互为镜像，写读两边不各说各话）：
 *  - **字节全 ASCII**：内容里的非 ASCII 一律写成 `\uN ?`（负数不出现：码位超 32767 的按 16 位
 *    补码折成负的，基本平面以外的字拆成代理对两个 `\u`）—— 编码页从此与文件无关，谁的读法都一样
 *  - **标题写在样式表里**：`\sN` 指到带 `\outlinelevelM` 的那条样式，样式号本身不是层级
 *    （凭号猜就是编的）；读的一侧、pandoc、Word 认的是同一处
 *  - **列表用 `{\*\pn…}` 的老写法**：`\pnlvlblt` / `\pndec` 说清圆点还是编号，`\ilvl` 说第几层，
 *    记号由软件画 —— 绝不把 `•` 与 `1.` 当正文写进文字里
 *  - **链接走 HYPERLINK 域**：地址在 `{\*\fldinst}` 指令里、文字在 `{\fldrslt}` 结果里，照字面写
 *
 * `Doc` 里没有对应物的东西不硬编，逐条交代在 [RtfOut.notes]：分隔线（写成带下边线的空段，按
 * 段落读就没了）、引用块与代码段的样式（RTF 没有"引用"，代码用等宽字体写）、紧挨着的两张表
 * （RTF 的行流没有"表到这里结束"的记号，读回来会并成一张）。
 */
object RtfWrite {

    /** 等宽字体在字体表里的号：读的一侧只认字体表里标了 `\fmodern` 的那个号。 */
    private const val MONO_FONT = 1

    /** 每列声明的宽度（缇）：文档树里没有列宽这件事，等宽是跟 ODT 那侧同一个交代。 */
    private const val CELL_WIDTH = 500

    /** 一份文档写成字节。[title] 非空时进 `{\info{\title}}`。 */
    fun document(doc: Doc, title: String = ""): RtfOut {
        val tally = RtfWriteTally()
        tally.paragraphs = doc.parts.count { it is DocParagraph && it.para.runs.isNotEmpty() }
        tally.codeParas = doc.parts.count {
            it is DocParagraph && it.para.style == "SourceCode" && it.para.runs.isNotEmpty()
        }
        tally.quoteParas = doc.parts.count {
            it is DocParagraph && it.para.style == "Quote" && it.para.runs.isNotEmpty()
        }
        tally.adjacentTables = doc.parts.zipWithNext().count { it.first is DocTable && it.second is DocTable }
        val marks = MarkState()
        val body = StringBuilder()
        doc.parts.forEach { part ->
            when (part) {
                is DocRule -> {
                    reset(marks, body)
                    body.append("\\pard\\plain\\brdrb\\brdrs\\par\n")
                    tally.rules++
                }
                is DocTable -> {
                    reset(marks, body)
                    table(part, body, tally)
                }
                is DocParagraph -> paragraph(part.para, body, marks, tally)
            }
        }

        val out = StringBuilder()
        out.append("{\\rtf1\\ansi\\ansicpg1252\\uc1\\deff0\n")
        out.append("{\\fonttbl{\\f0\\froman Times New Roman;}{\\f1\\fmodern Courier New;}{\\f2\\fswiss Arial;}}\n")
        out.append("{\\stylesheet\n{\\s0 Normal;}\n")
        for (level in 1..6) {
            // \outlinelevel 要写在样式名字的前面：pandoc 的读者只认这个顺序（实测它把写在名字后面的当没写）
            out.append("{\\s$level\\outlinelevel${level - 1}\\sb240\\sa60\\b Heading $level;}\n")
        }
        out.append("}\n")
        if (title.isNotBlank()) {
            out.append("{\\info{\\title ")
            title.forEach { ch -> if (ch == '\n' || ch == '\t') out.append(' ') else escape(ch, out, tally) }
            out.append("}}\n")
        }
        out.append(body)
        out.append("}")

        val notes = ArrayList<String>()
        notes += "${tally.paragraphs} 段" + if (tally.tables > 0) " · ${tally.tables} 张表" else ""
        if (tally.headings > 0) notes += "${tally.headings} 个标题用样式表里的 outlinelevel 写（样式号本身不是层级）"
        if (tally.lists > 0) notes += "${tally.lists} 条列表用 {\\*\\pn} 的老写法，圆点与序号由软件画"
        if (tally.links > 0) notes += "${tally.links} 处链接写成 HYPERLINK 域（地址照字面）"
        if (tally.tables > 0) notes += "表格每列按等宽声明，短了的行补空格子"
        if (tally.rules > 0) notes += "${tally.rules} 根分隔线写成带下边线的空段（RTF 没有横线这个块，按段落读就没了）"
        if (tally.codeParas > 0) notes += "代码段用等宽字体写（读回来段落样式是普通段落，字体记号还在）"
        if (tally.quoteParas > 0) notes += "引用块按普通段落写（RTF 没有引用记号）"
        if (tally.adjacentTables > 0) {
            notes += "${tally.adjacentTables} 处两张表紧挨着，中间没有段落隔开，读回来会并成一张"
        }
        if (tally.astral > 0) notes += "${tally.astral} 处基本平面以外的字符按代理对写成两个 \\u（部分老读法读不回）"
        if (tally.control > 0) notes += "${tally.control} 个控制字符去掉了（RTF 里没有它们的合法写法）"
        notes += doc.notes
        return RtfOut(out.toString().toByteArray(Charsets.US_ASCII), notes)
    }

    /** 一段：`\pard\plain` 起头（把上一段的样式与记号清干净），标题补 `\sN`，列表补 pn 组。 */
    private fun paragraph(para: DocPara, body: StringBuilder, marks: MarkState, tally: RtfWriteTally) {
        body.append("\\pard\\plain")
        marks.current = Marks()
        val level = headingLevel(para.style)
        if (level != null) {
            body.append("\\s$level")
            tally.headings++
        }
        // 代码段的"等宽"写在段落样式上，字本身没有记号 —— 落笔时换算成字体记号
        val forceMono = para.style == "SourceCode"
        val bullet = para.bullet
        if (bullet != null) {
            // 记号交给软件画：{\pntxtb} 里那一份是给老读法看的样板，不属于正文。
            // 组一收后面直接是正文 —— 这里补的空格会成为内容，一个都不能给
            val kind = if (bullet) "\\pnlvlblt" else "\\pndec"
            val sample = if (bullet) "\\u8226 ?" else "1."
            body.append("{\\*\\pn").append(kind).append("\\ilvl${para.indent.coerceIn(0, 8)}")
                .append("\\pnf0\\pnindent0{\\pntxtb ").append(sample).append("}}")
            tally.lists++
        } else {
            // \plain 与 \s 的控制字要靠这个空格收尾，不然下一个词会并进控制字里
            body.append(' ')
        }
        para.runs.forEach { run -> runs(run, forceMono, body, marks, tally) }
        body.append("\\par\n")
    }

    /** 一段里的字：记号变了就 `\plain` 清掉再开新的（读到哪段都一样，不靠组嵌套记状态）。 */
    private fun runs(run: DocRun, forceMono: Boolean, body: StringBuilder, marks: MarkState, tally: RtfWriteTally) {
        if (run.text.isEmpty() && run.link.isNullOrBlank()) return
        val target = Marks(run.bold, run.italic, run.underline, run.strike, run.mono || forceMono)
        if (target != marks.current) {
            body.append("\\plain ")
            if (target.bold) body.append("\\b ")
            if (target.italic) body.append("\\i ")
            if (target.underline) body.append("\\ul ")
            if (target.strike) body.append("\\strike ")
            if (target.mono) body.append("\\f$MONO_FONT ")
            marks.current = target
        }
        val target2 = run.link
        if (target2 != null) {
            tally.links++
            body.append("{\\field{\\*\\fldinst{HYPERLINK \"").append(escapeUrl(target2)).append("\"}}{\\fldrslt ")
        }
        escapeText(run.text, body, tally)
        if (target2 != null) body.append("}}")
    }

    /** 一张表：一行一个 `\trowd`，列数按最宽的一行声明，短了的行补空格子。 */
    private fun table(part: DocTable, body: StringBuilder, tally: RtfWriteTally) {
        val width = part.rows.maxOfOrNull { it.size } ?: 0
        if (width == 0) return
        tally.tables++
        part.rows.forEachIndexed { index, row ->
            body.append("\\trowd")
            if (part.header && index == 0) body.append("\\trhdr")
            repeat(width) { body.append("\\cellx$CELL_WIDTH") }
            for (column in 0 until width) {
                body.append(" \\intbl ")
                escapeText(row.getOrElse(column) { "" }, body, tally)
                body.append("\\cell")
            }
            body.append("\\row\n")
        }
    }

    /**
     * 一段文字的写法。基本平面以外的字在字符串里本来就是**两个代理字符** —— 在这里合回码位、
     * 拆成高低两个 `\u`，并数一笔（逐字扫描的话这两个码位就悄悄溜过去了，交代不出来）。
     */
    private fun escapeText(text: String, out: StringBuilder, tally: RtfWriteTally) {
        var at = 0
        while (at < text.length) {
            val ch = text[at]
            if (ch.isHighSurrogate() && at + 1 < text.length && text[at + 1].isLowSurrogate()) {
                val code = Character.toCodePoint(ch, text[at + 1])
                val high = 0xD800 + ((code - 0x10000) shr 10)
                val low = 0xDC00 + ((code - 0x10000) and 0x3FF)
                out.append("\\u").append(signed16(high)).append(" ?")
                out.append("\\u").append(signed16(low)).append(" ?")
                tally.astral++
                at += 2
                continue
            }
            escape(ch, out, tally)
            at++
        }
    }

    /**
     * 一个字符的写法。控制字后面那一个空格是**结束符**（读到就吃掉，不出现在正文里）；
     * `\uN` 的兜底写法按规矩紧跟一个 `?` —— 缺了它，别人的读法会把下一个字当兜底吃掉。
     */
    private fun escape(ch: Char, out: StringBuilder, tally: RtfWriteTally) {
        when {
            ch == '{' -> out.append("\\{")
            ch == '}' -> out.append("\\}")
            ch == '\\' -> out.append("\\\\")
            // 字面的 ~ 不能原样写（RTF 里裸的 ~ 是不换行空格），也不能写成 \~（那还是空格）——
            // 只能走 \u：两家的读者都把 \u126 读回 "~"
            ch == '~' -> out.append("\\u126 ?")
            ch == '\n' -> out.append("\\line ")
            ch == '\t' -> out.append("\\tab ")
            ch.code < 0x20 || ch.code == 0x7F -> tally.control++
            ch.code < 0x80 -> out.append(ch)
            else -> {
                val code = ch.code
                if (code <= 0xFFFF) {
                    out.append("\\u").append(signed16(code)).append(" ?")
                } else {
                    // 基本平面以外：RTF 的 \u 只有 16 位，按代理对拆成两个（读的一侧认不出代理对，
                    // 这两个码位会被当坏编号丢掉 —— 交代在说明里，不悄悄地丢）
                    val high = 0xD800 + ((code - 0x10000) shr 10)
                    val low = 0xDC00 + ((code - 0x10000) and 0x3FF)
                    out.append("\\u").append(signed16(high)).append(" ?")
                    out.append("\\u").append(signed16(low)).append(" ?")
                    tally.astral++
                }
            }
        }
    }

    /** 链接地址照字面写（Word 存超链接也是原样）；只把会破坏语法的三个字转义。 */
    private fun escapeUrl(url: String): String = buildString(url.length) {
        url.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '{' -> append("\\{")
                '}' -> append("\\}")
                else -> append(ch)
            }
        }
    }

    /** `\uN` 的 N 是带符号的 16 位数：超过 32767 的码位按补码折成负的（RTF 的老规矩）。 */
    private fun signed16(code: Int): Int = if (code > 0x7FFF) code - 0x10000 else code

    private fun headingLevel(style: String): Int? =
        style.removePrefix("Heading").toIntOrNull()?.takeIf { style.startsWith("Heading") && it in 1..6 }

    /** 进表与分隔线之前把开着的记号关掉：表里的格与下一段都不该接着上一段的粗体。 */
    private fun reset(marks: MarkState, body: StringBuilder) {
        if (marks.current != Marks()) body.append("\\plain \n")
        marks.current = Marks()
    }

    /** 当前开着的那组记号：与 [DocRun] 的记号位一一对应。 */
    private data class Marks(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val mono: Boolean = false,
    )

    private class MarkState {
        var current = Marks()
    }

    private class RtfWriteTally {
        var paragraphs = 0
        var headings = 0
        var lists = 0
        var links = 0
        var tables = 0
        var rules = 0
        var codeParas = 0
        var quoteParas = 0
        var adjacentTables = 0
        var astral = 0
        var control = 0
    }
}
