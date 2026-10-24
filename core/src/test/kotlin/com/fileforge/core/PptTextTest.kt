package com.fileforge.core

import com.fileforge.core.model.FileKind
import com.fileforge.core.office.OldOffice
import com.fileforge.core.office.Ole2
import com.fileforge.core.office.PptText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 夹具 `simple.ppt` 来自 Apache POI 的 test-data（Apache-2.0）；
 * 期望文字是在原始字节里按 ASCII 连续串独立找到的，不是拿本解析器的输出当标准。
 */
class PptTextTest {

    private fun sample(): File {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/office/simple.ppt")).readBytes()
        val file = File.createTempFile("fileforge-ppt", ".ppt")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `OLE2 认成 PowerPoint 演示`() {
        val file = sample()
        Ole2.open(file).use { ole ->
            assertTrue("PowerPoint Document" in ole.names(), ole.names().toString())
        }
        assertEquals(FileKind.Ppt, OldOffice.detect(file))
    }

    @Test
    fun `记录树里的标题与两页正文都抽到且修订去重`() {
        val file = sample()
        val stream = Ole2.open(file).use { ole -> ole.read("PowerPoint Document") }
        val doc = PptText.read(checkNotNull(stream))
        assertTrue(doc.text.contains("This is a test title"), doc.text.take(200))
        assertTrue(doc.text.contains("This is on page 1"))
        assertTrue(doc.text.contains("This is the title on page 2"))
        assertTrue(doc.text.contains("It has several blocks of text"))
        // 同一段标题在三次保存里都出现过，产物里只留一次
        assertEquals(1, Regex("This is a test title").findAll(doc.text).count(), doc.text)
        assertTrue(doc.notes.any { it.contains("去重") }, doc.notes.toString())
    }
}
