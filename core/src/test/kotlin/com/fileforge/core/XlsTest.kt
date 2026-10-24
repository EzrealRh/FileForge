package com.fileforge.core

import com.fileforge.core.model.FileKind
import com.fileforge.core.office.Biff
import com.fileforge.core.office.OldOffice
import com.fileforge.core.office.Ole2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class XlsTest {

    private fun sample(): File {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/office/sample.xls")).readBytes()
        val file = File.createTempFile("fileforge-sample", ".xls")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `OLE2 目录里有工作簿流并认成 XLS`() {
        val file = sample()
        Ole2.open(file).use { ole ->
            assertTrue(ole.names().any { it == "Workbook" || it == "Book" }, ole.names().toString())
            val stream = ole.read("Workbook") ?: ole.read("Book")
            assertTrue(stream != null && stream.size > 100)
        }
        assertEquals(FileKind.Xls, OldOffice.detect(file))
    }

    @Test
    fun `两张表的名字、文字、数字与日期都读对`() {
        val file = sample()
        val stream = Ole2.open(file).use { ole -> ole.read("Workbook") ?: ole.read("Book") }
        val result = Biff.read(checkNotNull(stream))
        assertEquals(listOf("数据", "第二表"), result.sheets.map { it.name })
        val rows = result.sheets[0].rows
        assertEquals(listOf("姓名", "数值", "日期", "备注"), rows[0])
        assertEquals(listOf("张三", "12.5", "2024-01-02", "007"), rows[1])
        assertEquals(listOf("李四", "-3", "2023-12-31", "带,逗号"), rows[2])
        assertEquals(listOf("王五", "0.125", "2025-06-30", "百分号 12%"), rows[3])
        val second = result.sheets[1].rows
        assertEquals(listOf("键", "值"), second[0])
        assertEquals(listOf("a", "1"), second[1])
    }
}
