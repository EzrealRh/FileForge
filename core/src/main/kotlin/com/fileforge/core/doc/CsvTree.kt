package com.fileforge.core.doc

import com.fileforge.core.data.Csv
import com.fileforge.core.data.CsvDoc
import com.fileforge.core.data.Delimiter

/**
 * 分隔符文本（CSV / TSV / 分号表 / 竖线表）→ 文档树里的一张表。
 *
 * 为什么要单列这一条路：文件类型层只认魔数，CSV 在那一层就是"文本"。以前的排法把逗号原样
 * 印到纸上 —— 两列的表在纸面上是一行行的字，列与列的位置关系全丢，写进 Word 也只是一段一段。
 * 认成表之后，印成 PDF 有真网格、写成 Word / ODT / RTF / 电子书有真表格、转 HTML 出 `<table>`，
 * 五条出路共用同一次判断。
 *
 * 判"像不像一张表"从严（把散文认成表比把表当散文更糟：纸上会凭空长出一张没人写过的网格）：
 *  - 每一行的列数必须**一模一样**且至少 2 列，且至少两行 —— 文章里的逗号不会这么整齐
 *  - 至少 2 行（只有一行那是表头，不是表）
 *  - 每一行都得真的有那一列：等宽这条硬规矩已经把"只有个别行带分隔符"挡在外面了
 *  - 引号里的逗号与换行按 RFC 4180 算一个字段，不许因为里面断了行就当两行
 * 认不出来返回 null，并且说明差在哪 —— 这句话要能给用户看，不然"为什么我的 CSV 没画成表格"无从问起。
 */
object CsvTree {

    /** 至少要两行：一行是表头，两行才谈得上"表"。 */
    private const val MIN_RECORDS = 2

    /** 印成 PDF 时最多排多少行：五十万行的表印出去是两万页，那不该静悄悄发生。 */
    const val MAX_ROWS = 5000

    class Table(val parts: List<DocPart>, val columns: Int, val rows: Int, val delimiter: Delimiter, val notes: List<String>)

    /** 像不像一张表；不像时第二条是"差在哪"（写进结果说明）。 */
    fun judge(text: String): Pair<CsvDoc?, String> {
        val lines = text.trimStart(Csv.BOM).lines().filter { it.isNotBlank() }
        if (lines.size < MIN_RECORDS) return null to "只有 ${lines.size} 行有字"
        val delimiter = Csv.detect(text)
        val doc = runCatching { Csv.parse(text, delimiter) }.getOrNull()
            ?: return null to "引号没闭合，读不成规整的字段"
        val filled = doc.records.filter { row -> row.any { cell -> cell.isNotBlank() } }
        if (filled.size < MIN_RECORDS) return null to "读出来只有 ${filled.size} 行有字"
        if (doc.widest < 2) return null to "每行只有 ${doc.widest} 列"
        if (doc.ragged.isNotEmpty()) {
            val shown = doc.ragged.take(3).joinToString("、")
            return null to "${doc.ragged.size} 行的列数与别行不同（第 $shown 行）"
        }
        return doc to ""
    }

    /** 认出来就排成一张表；认不出返回 null（调用方退回按空行分段那条路）。 */
    fun table(text: String): Table? {
        val (parsed, _) = judge(text)
        parsed ?: return null
        val notes = ArrayList<String>()
        val kept = if (parsed.records.size > MAX_ROWS) {
            notes += "共 ${parsed.records.size} 行，只排前 $MAX_ROWS 行（再往下是几千页，要全排请先切成几份）"
            parsed.records.take(MAX_ROWS)
        } else {
            parsed.records
        }
        val columns = kept.maxOf { it.size }
        val rows = kept.map { row -> row + List(columns - row.size) { "" } }
        notes += "按${parsed.delimiter.label}分成 $columns 列、${kept.size} 行（首行按表头排：那条线加重，跨页时每页重复一次）"
        return Table(
            parts = listOf(DocTable(true, rows)),
            columns = columns,
            rows = kept.size,
            delimiter = parsed.delimiter,
            notes = notes,
        )
    }

    /** 只用来挑路：像表就返回真。 */
    internal fun looksLikeTable(text: String): Boolean = judge(text).first != null
}
