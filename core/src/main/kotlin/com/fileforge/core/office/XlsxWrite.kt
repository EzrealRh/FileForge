package com.fileforge.core.office

import com.fileforge.core.archive.ZipItem
import com.fileforge.core.archive.ZipWriter

/** 要写进工作簿的一张表：表名与按行排列的格子文字。 */
class SheetToWrite(val name: String, val rows: List<List<String>>)

/** 写好的包字节，以及该跟用户交代的话。 */
class WorkbookOut(val bytes: ByteArray, val notes: List<String>)

/**
 * SpreadsheetML（.xlsx）写格子 —— 与读那一路（[Xlsx]）互为镜像，两边共用同一套引用规矩。
 *
 * 三条刻意的选择：
 *  - **格子类型只按字面判**：只有"原文本来就是数字的规范写法"才写成数字（`12`、`-3.5` 过；
 *    `007`、`1.50`、`1e5`、15 位以上的长号不过）。写成数字会把 `007` 变成 `7`，而那是这份数据
 *    里唯一的编号 —— 判据是"变成数字之后还能一字不差读回来"，不是"看着像"。
 *  - **文字一律走 inlineStr，绝不写成公式**：`=HYPERLINK(...)`、`@SUM(...)` 这种开头在表格注入里
 *    是常见招式，写成 `<f>` 等于替原作者把公式执行一遍。
 *  - **表名按 Excel 的规矩收敛，并且说清楚改了哪几张**：`/ : ? * [ ]` 在表名里是非法字符，
 *    带着它 Excel 直接判文件打不开；但只改名字不说，用户回头对不上自己那份表名。
 */
object XlsxWrite {

    /** Excel 自己的上限，也是"这份数据放不下"的拒绝线。 */
    const val MAX_ROWS = 1_048_576
    const val MAX_COLUMNS = 16_384

    private const val NAME_LIMIT = 31
    private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT_PKG = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val SHEET_DIR = "xl/worksheets/"

    /** 写一份工作簿。`modifiedAt` 只影响包里的时间戳，不参与内容比对。 */
    fun workbook(sheets: List<SheetToWrite>, modifiedAt: Long = 0L): WorkbookOut {
        require(sheets.isNotEmpty()) { "一份表都没有，写不出工作簿" }
        val notes = ArrayList<String>()
        val tally = Tally()

        val taken = ArrayList<String>()
        val named = ArrayList<Pair<String, SheetToWrite>>()
        sheets.forEachIndexed { index, sheet ->
            val name = safeName(sheet.name, index, taken, tally)
            taken += name
            named += name to sheet
        }
        named.forEach { (name, sheet) ->
            require(sheet.rows.size <= MAX_ROWS) {
                "「$name」有 ${sheet.rows.size} 行，超过 Excel 的 $MAX_ROWS 行上限"
            }
            val width = sheet.rows.maxOfOrNull { it.size } ?: 0
            require(width <= MAX_COLUMNS) { "「$name」有 $width 列，超过 Excel 的 $MAX_COLUMNS 列上限" }
        }

        val parts = LinkedHashMap<String, ByteArray>()
        named.forEachIndexed { index, (_, sheet) ->
            parts["$SHEET_DIR" + "sheet${index + 1}.xml"] = sheetXml(sheet.rows, tally)
        }
        parts[OoxmlStructure.WORKBOOK] = workbookXml(named)
        parts[OoxmlStructure.WORKBOOK_RELS] = relsXml(
            named.indices.map { index ->
                Triple("rId${index + 1}", "worksheet", "worksheets/sheet${index + 1}.xml")
            } + Triple("rId${named.size + 1}", "styles", "styles.xml"),
        )
        parts[Xlsx.STYLES] = stylesXml()
        parts["_rels/.rels"] = rootRelsXml()
        parts["[Content_Types].xml"] = contentTypesXml(named.size)

        val bytes = ZipWriter.write(parts.map { (name, content) -> ZipItem(name, content, modifiedAt) })

        if (tally.numbers + tally.texts > 0) {
            notes += "${tally.numbers} 格按原样写成数字（能一字不差读回来），${tally.texts} 格保持文字" +
                "（007、1.50 这类写法本身带信息，猜成数字就是改数据）"
        }
        if (tally.formulaish > 0) notes += "${tally.formulaish} 格以 = + @ 或制表符开头，按文字存着不会被当公式执行"
        if (tally.renamed > 0) notes += "${tally.renamed} 张表的表名不合 Excel 的规矩（非法字符、超长或重名），已换成能用的名字"
        if (tally.dropped > 0) notes += "${tally.dropped} 个字符是 XML 不允许的控制字符，只能去掉（带着它们 Excel 判文件坏了）"
        if (tally.lineEnds > 0) notes += "${tally.lineEnds} 处回车按换行存（Excel 里换行就是换行，不分 CRLF）"
        return WorkbookOut(bytes, notes)
    }

    /** 写这轮活里顺手数出来的账，全部翻成给用户看的话。 */
    private class Tally {
        var numbers = 0
        var texts = 0
        var formulaish = 0
        var renamed = 0
        var dropped = 0
        var lineEnds = 0
    }

    /**
     * 表名收敛到 Excel 认的那套：非法字符换成空格、掐到 31 字、去掉首尾空格与结尾的单引号
     * （`'Sheet1` 那种写法 Excel 也不认）、空名字按序号给、重名加序号、"History" 是保留字。
     */
    private fun safeName(raw: String, index: Int, taken: List<String>, tally: Tally): String {
        var name = raw.replace(ILLEGAL_IN_NAME, " ").trim().take(NAME_LIMIT).trim().trimEnd('\'')
        if (name.isEmpty()) name = "表${index + 1}"
        if (name.equals("History", ignoreCase = true)) name = "历史"
        val base = name
        var repeat = 2
        while (taken.any { it.equals(name, ignoreCase = true) }) {
            val suffix = "($repeat)"
            name = base.take(NAME_LIMIT - suffix.length) + suffix
            repeat++
        }
        if (name != raw) tally.renamed++
        return name
    }

    private val ILLEGAL_IN_NAME = Regex("[\\\\/:*?\\[\\]]")

    private fun sheetXml(rows: List<List<String>>, tally: Tally): ByteArray {
        val out = StringBuilder()
        out.append(DECL).append("<worksheet xmlns=\"").append(MAIN).append("\"><sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            out.append("<row r=\"").append(rowIndex + 1).append("\">")
            row.forEachIndexed { column, value -> cellXml(column, rowIndex + 1, value, out, tally) }
            out.append("</row>")
        }
        out.append("</sheetData></worksheet>")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun cellXml(column: Int, row: Int, value: String, out: StringBuilder, tally: Tally) {
        if (value.isEmpty()) return
        val ref = columnRef(column) + row
        if (keepsItsMeaning(value)) {
            tally.numbers++
            out.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>")
            return
        }
        if (value[0] in "=+@\t") tally.formulaish++
        val clean = writeText(value, tally)
        if (clean.isEmpty()) return
        tally.texts++
        out.append("<c r=\"")
            .append(ref)
            .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
            .append(clean)
            .append("</t></is></c>")
    }

    /**
     * 这串文字写成数字之后，能不能一字不差地读回来？
     *
     * 只认"原文就是规范写法"的那种：整数不带前导零、小数不带收尾零、不带指数与正号，
     * 有效位不超过 15 位 —— Excel 存数字就到 15 位为止，`12345678901234567` 写成数字会变成
     * `12345678901234568`，那是一个身份证号 / 订单号被改掉的经典现场。
     */
    internal fun keepsItsMeaning(text: String): Boolean {
        if (text.length > 40) return false
        val minus = text.startsWith("-")
        val digits = if (minus) text.substring(1) else text
        val whole = digits.substringBefore('.')
        val fraction = if (digits.contains('.')) digits.substringAfter('.') else null
        if (minus && digits.all { it == '0' || it == '.' }) return false    // -0 存进去就变成 0
        // 只认 ASCII 数字：全角的"１２"用 Char.isDigit() 也算数字，写进 <v> 却是非法数值，
        // Excel 会当场判这份文件坏了
        if (whole.isEmpty() || !whole.all { it in '0'..'9' }) return false
        if (whole.length > 1 && whole[0] == '0') return false
        if (fraction == null) return whole.length <= 15
        if (fraction.isEmpty() || fraction.last() == '0' || !fraction.all { it in '0'..'9' }) return false
        return (whole + fraction).trimStart('0').length <= 15
    }

    /** 0 → A，25 → Z，26 → AA。与 [Xlsx.columnOf] 互为逆运算，那条有单测钉住。 */
    internal fun columnRef(index: Int): String {
        var rest = index + 1
        val out = StringBuilder()
        while (rest > 0) {
            rest--
            out.insert(0, ('A' + rest % 26))
            rest /= 26
        }
        return out.toString()
    }

    /** 转成能放进 `<t>` 的文字：回车并成换行、XML 不允许的控制字符去掉，然后转义。 */
    private fun writeText(value: String, tally: Tally): String {
        val out = StringBuilder(value.length)
        var at = 0
        while (at < value.length) {
            val ch = value[at]
            when {
                ch == '\r' -> {
                    tally.lineEnds++
                    out.append('\n')
                    at++
                    if (value.getOrNull(at) == '\n') at++           // CRLF 只算一个换行
                }
                ch == '\n' || ch == '\t' -> { out.append(ch); at++ }
                ch.code < 0x20 -> { tally.dropped++; at++ }
                else -> {
                    when (ch) {
                        '&' -> out.append("&amp;")
                        '<' -> out.append("&lt;")
                        '>' -> out.append("&gt;")
                        else -> out.append(ch)
                    }
                    at++
                }
            }
        }
        return out.toString()
    }

    private fun workbookXml(sheets: List<Pair<String, SheetToWrite>>): ByteArray {
        val out = StringBuilder()
        out.append(DECL)
            .append("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"").append(REL_NS).append("\"><sheets>")
        sheets.forEachIndexed { index, (name, _) ->
            out.append("<sheet name=\"").append(attribute(name)).append("\" sheetId=\"").append(index + 1)
                .append("\" r:id=\"rId").append(index + 1).append("\"/>")
        }
        out.append("</sheets></workbook>")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun contentTypesXml(sheetCount: Int): ByteArray {
        val out = StringBuilder()
        out.append(DECL).append("<Types xmlns=\"").append(CT_PKG).append("\">")
            .append("<Default Extension=\"rels\" ContentType=\"").append(REL_PKG)
            .append("\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append(override(OoxmlStructure.WORKBOOK, "spreadsheetml.sheet.main+xml"))
        for (index in 1..sheetCount) {
            out.append(override("$SHEET_DIR" + "sheet$index.xml", "spreadsheetml.worksheet+xml"))
        }
        out.append(override(Xlsx.STYLES, "spreadsheetml.styles+xml")).append("</Types>")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun override(part: String, suffix: String) =
        "<Override PartName=\"/" + part + "\" ContentType=\"application/vnd.openxmlformats-officedocument." +
            suffix + "\"/>"

    private fun rootRelsXml(): ByteArray =
        (DECL + "<Relationships xmlns=\"" + REL_PKG + "\"><Relationship Id=\"rId1\" Type=\"" +
            REL_NS + "/officeDocument\" Target=\"" + OoxmlStructure.WORKBOOK + "\"/></Relationships>")
            .toByteArray(Charsets.UTF_8)

    /** 关联里的 Target 是相对工作簿那份部件写的（`worksheets/sheet1.xml` 在 xl/ 下面）。 */
    private fun relsXml(links: List<Triple<String, String, String>>): ByteArray {
        val out = StringBuilder()
        out.append(DECL).append("<Relationships xmlns=\"").append(REL_PKG).append("\">")
        links.forEach { (id, type, target) ->
            out.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(REL_NS)
                .append("/").append(type).append("\" Target=\"").append(attribute(target)).append("\"/>")
        }
        out.append("</Relationships>")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 最小但齐全的一份样式表。
     *
     * fonts / fills / borders / cellStyleXfs / cellXfs 一类都不能少，Excel 与 WPS 都要数它们的个数；
     * fills 必须正好 2 个（第二个是 gray125）—— 没有理由，那是从 Excel 97 传下来的规矩，
     * 少一个就报"文件里的内容与预期不符"。
     */
    private fun stylesXml(): ByteArray = (
        DECL + "<styleSheet xmlns=\"" + MAIN + "\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"常规\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
        ).toByteArray(Charsets.UTF_8)

    private fun attribute(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
