package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.data.Csv
import com.fileforge.core.office.SheetToWrite
import com.fileforge.core.office.Xlsx
import com.fileforge.core.office.XlsxWrite
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 把「CSV → xlsx」的产物落到 `build/xlsx-write/`，交给 `tools/verify_xlsx_write.py` 与
 * **openpyxl**（另一套完整实现，也是真 Excel 文件的常见生产者）对：
 * 包能不能被别人打开、每格是数字还是文字、值有没有变、表名合不合规矩。
 *
 * 同时落一份 `.cells`：我们自己声明"这格按数字存 / 那格按文字存"的账。
 * 判据不是"openpyxl 读出来是什么就算什么"，而是**我们说的必须与 openpyxl 看到的一致** ——
 * 写 `007` 时想的是文字，结果被别人读成 7，那正是这条要挡的事。
 */
class XlsxWriteFixtureTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("xlsxwrite/$name").use { input ->
            requireNotNull(input) { "缺少夹具 xlsxwrite/$name" }
            String(input.readBytes(), Charsets.UTF_8)
        }

    private fun partsOf(bytes: ByteArray): Map<String, ByteArray> {
        val entries = ZipReader.read(ZipReader.slicing(bytes), bytes.size.toLong()).entries
        return entries.associate { it.name to ZipReader.dataOf(it, ZipReader.slicing(bytes)) }
    }

    @Test
    fun `真 CSV 写成工作簿并落盘`() {
        val doc = Csv.parse(fixture("table.csv"))
        assertTrue(doc.records.size >= 4, "夹具该有几行：${doc.records.size}")
        val width = doc.widest
        val rows = doc.records.map { record -> record + List(width - record.size) { "" } }
        val sheets = listOf(
            SheetToWrite("table", rows),
            SheetToWrite("第一季/度", rows),
            SheetToWrite("第一季 度", rows),                       // 与上一张改名后正好撞上，逼出序号
            SheetToWrite("这张表的名字长得超过了 Excel 允许的三十一个字限制所以必须被掐断", rows),
        )
        val out = XlsxWrite.workbook(sheets, modifiedAt = 1_700_000_000_000L)
        val dir = File("build/xlsx-write").apply { mkdirs() }
        File(dir, "table.xlsx").writeBytes(out.bytes)
        File(dir, "table.notes").writeText(out.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        File(dir, "table.cells").writeText(claim(sheets), Charsets.UTF_8)

        assertTrue("3 张表" in out.notes.joinToString(" "), "表名改了 3 张要说明：${out.notes}")
        // 包是压过的，字在里面看不见 —— "有没有少字"由 openpyxl 判；这里只钉自家读路打得开自己写的包
        val parts = partsOf(out.bytes)
        val refs = Xlsx.sheets { name -> parts[name] }
        assertEquals(4, refs.size, "四张表都该在")
        val first = refs.first()
        val part = requireNotNull(first.part) { "第一张表找不到自己的部件：${refs.map { it.name to it.part }}" }
        val back = Xlsx.sheet(
            first.name,
            parts.getValue(part),
            Xlsx.sharedStrings(parts[Xlsx.SHARED_STRINGS]),
            Xlsx.dateStyles(parts[Xlsx.STYLES]),
        ).rows
        assertEquals(rows, back, "自家读回来一格不差")
    }

    /** 我们对自己产物的声明：每格算数字还是文字、原文是什么（换行写成 ␤ 好让一格占一行）。 */
    private fun claim(sheets: List<SheetToWrite>): String = buildString {
        sheets.forEachIndexed { sheetIndex, sheet ->
            sheet.rows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { column, value ->
                    if (value.isEmpty()) return@forEachIndexed
                    val type = if (XlsxWrite.keepsItsMeaning(value)) "n" else "s"
                    append(sheetIndex + 1).append('!')
                        .append(XlsxWrite.columnRef(column)).append(rowIndex + 1)
                        .append('\t').append(type)
                        .append('\t').append(value.replace("\r\n", "\n").replace('\n', '\u2424'))
                        .append('\n')
                }
            }
        }
    }
}
