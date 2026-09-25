package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.model.FileKind
import com.fileforge.core.office.OoxmlParts
import com.fileforge.core.office.SheetToWrite
import com.fileforge.core.office.Xlsx
import com.fileforge.core.office.XlsxWrite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * xlsx 写格子。
 *
 * 参照物分两层：这文件里拿**自家的读那一路**（[Xlsx]）当镜子 —— 写进去读回来必须一格不差，
 * 两边共用的是同一套引用规矩，哪边写错都会在这里对不上；真第三方（openpyxl）判"别人眼里
 * 这张表长什么样"（格子是数字还是文字、包结构合不合法），在 `tools/verify_xlsx_write.py`。
 */
class XlsxWriteTest {

    private fun partsOf(bytes: ByteArray): Map<String, ByteArray> {
        val slices = ZipReader.slicing(bytes)
        return ZipReader.read(slices, bytes.size.toLong()).entries
            .associate { it.name to ZipReader.dataOf(it, ZipReader.slicing(bytes)) }
    }

    /** 写完立刻用自家的读路打开：表名、行列、每格文字。 */
    private fun readBack(sheets: List<SheetToWrite>): List<Pair<String, List<List<String>>>> {
        val parts = partsOf(XlsxWrite.workbook(sheets).bytes)
        val load = { name: String -> parts[name] }
        val strings = Xlsx.sharedStrings(load(Xlsx.SHARED_STRINGS))
        val dates = Xlsx.dateStyles(load(Xlsx.STYLES))
        return Xlsx.sheets(load).map { ref ->
            val part = ref.part?.let(load)
            val sheet = if (part == null) null else Xlsx.sheet(ref.name, part, strings, dates)
            (ref.name to sheet?.rows.orEmpty())
        }
    }

    private fun sheetText(bytes: ByteArray): String =
        String(partsOf(bytes).getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8)

    // ---- 引用规矩 -----------------------------------------------------------------

    @Test
    fun `列号与列字母互为逆运算`() {
        assertEquals("A", XlsxWrite.columnRef(0))
        assertEquals("Z", XlsxWrite.columnRef(25))
        assertEquals("AA", XlsxWrite.columnRef(26))
        assertEquals("AZ", XlsxWrite.columnRef(51))
        assertEquals("BA", XlsxWrite.columnRef(52))
        assertEquals("ZZ", XlsxWrite.columnRef(701))
        assertEquals("AAA", XlsxWrite.columnRef(702))
        for (index in 0..3000) assertEquals(index, Xlsx.columnOf(XlsxWrite.columnRef(index) + "1"), "第 $index 列")
    }

    // ---- 数字判据 -----------------------------------------------------------------

    @Test
    fun `只有原样读得回来的写法才配变成数字`() {
        listOf("12", "0", "-7", "1.5", "-0.5", "0.05", "1000000", "123456789012345").forEach {
            assertTrue(XlsxWrite.keepsItsMeaning(it), "$it 该算数字")
        }
        listOf(
            "", "007", "1.50", "12.", ".5", "+1", "1e5", "1_000", " 12", "12 ", "12345678901234567",
            "0x1F", "-0", "1.5.2", "１２", "NaN", "1,000",
        ).forEach {
            assertFalse(XlsxWrite.keepsItsMeaning(it), "$it 不该被猜成数字")
        }
    }

    @Test
    fun `部件清单里说的部件包里都得有，命名空间不能串台`() {
        // 读那一路按本名认元素（不看命名空间），所以写错命名空间自家完全测不出来 —— openpyxl 会直接拒绝
        val bytes = XlsxWrite.workbook(listOf(SheetToWrite("表", listOf(listOf("甲"))))).bytes
        val parts = partsOf(bytes)
        val types = String(parts.getValue("[Content_Types].xml"), Charsets.UTF_8)
        assertTrue("package/2006/content-types\"" in types, "Types 的命名空间：$types")
        assertFalse("relationships/content-types" in types, "那是 relationships 的域名，不是它的：$types")
        val declared = Regex("PartName=\"([^\"]+)\"").findAll(types).map { it.groupValues[1].removePrefix("/") }.toList()
        assertEquals(declared.filter { it in parts.keys }, declared, "清单里报了但包里没：$declared")
        assertTrue(parts.keys.filter { it.endsWith(".xml") }.all { it == "[Content_Types].xml" || it in declared },
            "包里有 .xml 部件却没进清单：${parts.keys}")
        val rels = String(parts.getValue("xl/_rels/workbook.xml.rels"), Charsets.UTF_8)
        assertTrue("package/2006/relationships\"" in rels, "Relationships 的命名空间：$rels")
    }

    @Test
    fun `格子引用带行号，缺一半别人就读不懂`() {
        // 只写列字母的话，自家的读路会按"出现顺序"兜底猜对，看不出坏了 —— openpyxl 直接拒绝整份文件
        val xml = sheetText(XlsxWrite.workbook(listOf(SheetToWrite("表", listOf(listOf("甲", "乙"), listOf("", "丙"))))).bytes)
        assertTrue("<row r=\"2\">" in xml, xml)
        assertTrue("r=\"A1\"" in xml && "r=\"B1\"" in xml && "r=\"B2\"" in xml, "引用该是完整的 A1 样式：$xml")
        assertFalse("r=\"A\"" in xml, "不能只写列字母：$xml")
    }

    @Test
    fun `以等号加号井号开头的格子按文字存`() {
        val bytes = XlsxWrite.workbook(listOf(SheetToWrite("表", listOf(listOf("=HYPERLINK(\"http://x\")"))))).bytes
        val xml = sheetText(bytes)
        assertTrue("inlineStr" in xml, "要按文字存：$xml")
        assertFalse("<f>" in xml || "<f " in xml, "绝不能写成公式：$xml")
        val notes = XlsxWrite.workbook(listOf(SheetToWrite("表", listOf(listOf("@SUM(1,2)"))))).notes
        assertTrue(notes.any { "公式" in it }, "写了按文字存要说明：$notes")
    }

    // ---- 写进去读回来 --------------------------------------------------------------

    @Test
    fun `自家读回来一格不差`() {
        val rows = listOf(
            listOf("名称", "数量", "单价"),
            listOf("苹果", "3", "12.5"),
            listOf("梨 带空格", "", "1.50"),                  // 中间空格子：读回来还得在中间
            listOf("带,逗号", "换\n行", "引\"号"),
            listOf("<尖括号>", "& amp 与lt;", " 尾随"),
            listOf("007", "12345678901234567", "=1+1"),
        )
        val back = readBack(listOf(SheetToWrite("测试", rows)))
        assertEquals(1, back.size)
        assertEquals("测试", back[0].first)
        assertEquals(rows.map { it.map { cell -> cell.replace("\r\n", "\n") } }, back[0].second)
    }

    @Test
    fun `行与行的位置由行号钉住，不靠出现顺序`() {
        // 第二行整行是空的：写出来那一行没有格子，但 r="2" 还在，读回来不能被挤上去
        val rows = listOf(listOf("头"), listOf(""), listOf("尾"))
        val back = readBack(listOf(SheetToWrite("表", rows)))
        assertEquals(listOf(listOf("头"), listOf(""), listOf("尾")), back[0].second)
    }

    @Test
    fun `多张表各归各位，名字与部件都对得上`() {
        val sheets = listOf(
            SheetToWrite("第一张", listOf(listOf("甲"))),
            SheetToWrite("第二张", listOf(listOf("乙"), listOf("丙"))),
        )
        val back = readBack(sheets)
        assertEquals(listOf("第一张" to listOf(listOf("甲")), "第二张" to listOf(listOf("乙"), listOf("丙"))), back)
    }

    @Test
    fun `产物的部件名要让自己认成 xlsx`() {
        val bytes = XlsxWrite.workbook(listOf(SheetToWrite("表", listOf(listOf("甲"))))).bytes
        val names = partsOf(bytes).keys
        assertEquals(FileKind.Xlsx, OoxmlParts.kindOf(names), "部件名不对：$names")
        assertTrue("[Content_Types].xml" in names && "_rels/.rels" in names, "包结构：$names")
    }

    // ---- 表名与字符 ----------------------------------------------------------------

    @Test
    fun `表名收敛到 Excel 认的样子并说改了几张`() {
        val long = "字".repeat(40)
        val sheets = listOf(
            SheetToWrite("第一季/度", listOf(listOf("甲"))),
            SheetToWrite(long, listOf(listOf("乙"))),
            SheetToWrite("重名", listOf(listOf("丙"))),
            SheetToWrite("重名", listOf(listOf("丁"))),
            SheetToWrite("   ", listOf(listOf("戊"))),
            SheetToWrite("History", listOf(listOf("己"))),
            SheetToWrite("末尾引号'", listOf(listOf("庚"))),
        )
        val out = XlsxWrite.workbook(sheets)
        val parts = partsOf(out.bytes)
        val names = Xlsx.sheets { name -> parts[name] }.map { it.name }
        assertEquals(listOf("第一季 度", "字".repeat(31), "重名", "重名(2)", "表5", "历史", "末尾引号"), names)
        assertTrue(out.notes.any { "表名" in it && "6 张" in it }, "该说改了 6 张：${out.notes}")
    }

    @Test
    fun `XML 不许的控制字符去掉，回车按换行存`() {
        val out = XlsxWrite.workbook(listOf(SheetToWrite("表", listOf(listOf("甲\u0001乙"), listOf("行1\r\n行2")))))
        assertTrue(out.notes.any { "控制字符" in it }, "${out.notes}")
        assertTrue(out.notes.any { "回车" in it }, "${out.notes}")
        val back = readBack(listOf(SheetToWrite("表", listOf(listOf("甲\u0001乙"), listOf("行1\r\n行2")))))
        assertEquals(listOf(listOf("甲乙"), listOf("行1\n行2")), back[0].second)
    }

    // ---- 上限 ---------------------------------------------------------------------

    @Test
    fun `超过 Excel 的列数上限要拒而不是写一份打不开的`() {
        val wide = List(XlsxWrite.MAX_COLUMNS + 1) { "" }
        val bad = assertThrows(IllegalArgumentException::class.java) {
            XlsxWrite.workbook(listOf(SheetToWrite("表", listOf(wide))))
        }
        assertTrue("列上限" in (bad.message ?: ""), bad.message ?: "")
    }
}
