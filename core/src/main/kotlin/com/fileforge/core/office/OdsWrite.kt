package com.fileforge.core.office

/** 写出去的一张表。 */
class SheetToWriteOds(val name: String, val rows: List<List<String>>)

/** 写好的包字节，加上"哪几格被改了写法"的交代。 */
class OdsOut(val bytes: ByteArray, val notes: List<String>)

/**
 * 一份最小但齐全的 OpenDocument 电子表格（.odt 的同族 .ods）：`mimetype` + `META-INF/manifest.xml` + `content.xml`。
 *
 * 为什么要写这一族：转换工具站上 `csv → ods`、`excel → ods` 是常备条目，而 LibreOffice /
 * Excel / Gnumeric 都吃 .ods —— 只出不进的话，这份产物在别人手里就断了链子。
 *
 * 三条主张与 `XlsxWrite` 完全一致（同一份数据从哪条路出去，写法不该变）：
 *  - **格子类型只按"变成数字后能不能一字不差读回来"判**：`1.50`、`007`、15 位以上的编号
 *    一律按文字写，宁可让人看到原样，也不替别人把身份证号改成科学计数
 *  - **每格都写渲染出来的那份文字**：这样别的实现（和我们自己）读回来看到的是同一个字面，
 *    而不是"数值 0.12345 与写法 12.345%"两套话
 *  - 空格与制表按 ODF 的写法（`text:s` 带个数、`text:tab`），不靠裸空白 ——
 *    XML 里的连续空白谁都可能折掉，写进 `text:s` 才是"这就是三个空格"
 */
object OdsWrite {

    private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val MIME = "application/vnd.oasis.opendocument.spreadsheet"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"

    fun spreadsheet(sheets: List<SheetToWriteOds>, modifiedAt: Long = 0L): OdsOut {
        require(sheets.isNotEmpty()) { "一份表都没有，写不出工作簿" }
        val tally = OdsWriteTally()
        val named = ArrayList<Pair<String, SheetToWriteOds>>()
        val taken = HashSet<String>()
        sheets.forEachIndexed { index, sheet ->
            val name = sheet.name.trim().ifEmpty { "表${index + 1}" }
            named += (if (taken.add(name)) name else "$name-${index + 1}") to sheet
        }
        val items = ArrayList<com.fileforge.core.archive.ZipItem>()
        items += com.fileforge.core.archive.ZipItem("mimetype", MIME.length.toLong(), modifiedAt, stored = true) {
            MIME.byteInputStream()
        }
        items += com.fileforge.core.archive.ZipItem("META-INF/manifest.xml", manifest().toByteArray(Charsets.UTF_8), modifiedAt)
        items += com.fileforge.core.archive.ZipItem("content.xml", content(named, tally).toByteArray(Charsets.UTF_8), modifiedAt)
        val bytes = com.fileforge.core.archive.ZipWriter.write(items)
        val notes = ArrayList<String>()
        if (tally.texts > 0) notes += "${tally.texts} 格按文字写（数字写法一改就会丢字面的那些）"
        if (tally.numbers > 0) notes += "${tally.numbers} 格按数值写"
        if (tally.newlines > 0) notes += "${tally.newlines} 格里有换行，写成一段一行"
        return OdsOut(bytes, notes)
    }

    private fun manifest(): String = DECL +
        "<manifest:manifest xmlns:manifest=\"$MANIFEST\">" +
        "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"$MIME\"/>" +
        "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>" +
        "</manifest:manifest>"

    private fun content(sheets: List<Pair<String, SheetToWriteOds>>, tally: OdsWriteTally): String {
        val out = StringBuilder()
        out.append(DECL)
            .append("<office:document-content xmlns:office=\"").append(OFFICE)
            .append("\" xmlns:table=\"").append(TABLE)
            .append("\" xmlns:text=\"").append(TEXT)
            .append("\" office:version=\"1.2\"><office:body><office:spreadsheet>")
        sheets.forEach { (name, sheet) ->
            out.append("<table:table table:name=\"").append(attribute(name)).append("\">")
            val width = sheet.rows.maxOfOrNull { it.size } ?: 0
            if (width > 0) out.append("<table:table-column table:number-columns-repeated=\"").append(width).append("\"/>")
            sheet.rows.forEach { row ->
                out.append("<table:table-row>")
                row.forEach { value -> cell(value, out, tally) }
                out.append("</table:table-row>")
            }
            out.append("</table:table>")
        }
        out.append("</office:spreadsheet></office:body></office:document-content>")
        return out.toString()
    }

    private fun cell(value: String, out: StringBuilder, tally: OdsWriteTally) {
        val number = XlsxWrite.keepsItsMeaning(value)
        if (value.isEmpty()) {
            out.append("<table:table-cell/>")
            return
        }
        if (number) {
            tally.numbers++
            out.append("<table:table-cell office:value-type=\"float\" office:value=\"")
                .append(attribute(value)).append("\">")
        } else {
            tally.texts++
            out.append("<table:table-cell office:value-type=\"string\" office:string-value=\"")
                .append(attribute(value)).append("\">")
        }
        value.split("\n").forEachIndexed { index, line ->
            if (index > 0) tally.newlines++
            out.append("<text:p>").append(paragraph(line)).append("</text:p>")
        }
        out.append("</table:table-cell>")
    }

    /** 一行里的连续空格与制表按 ODF 的写法来，别的字面照抄。 */
    private fun paragraph(line: String): String {
        val out = StringBuilder()
        var at = 0
        while (at < line.length) {
            val ch = line[at]
            if (ch == '\t') {
                out.append("<text:tab/>")
                at++
            } else if (ch == ' ') {
                var end = at
                while (end < line.length && line[end] == ' ') end++
                val count = end - at
                out.append(if (count == 1) "<text:s/>" else "<text:s text:c=\"$count\"/>")
                at = end
            } else {
                out.append(text(ch.toString()))
                at++
            }
        }
        return out.toString()
    }

    /** 文字里的 `&` `<` `>` 逃掉，控制字符丢掉（XML 不允许那些字节）。 */
    private fun text(value: String): String {
        val out = StringBuilder(value.length)
        value.forEach { ch ->
            when {
                ch == '&' -> out.append("&amp;")
                ch == '<' -> out.append("&lt;")
                ch == '>' -> out.append("&gt;")
                ch == '\n' || ch == '\t' -> out.append(ch)
                ch.code < 0x20 -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun attribute(value: String): String = text(value).replace("\"", "&quot;")
}

private class OdsWriteTally {
    var numbers = 0
    var texts = 0
    var newlines = 0
}
