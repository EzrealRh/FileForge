package com.fileforge.core

import com.fileforge.core.model.FileKind
import com.fileforge.core.office.OldOffice
import com.fileforge.core.office.Ole2
import com.fileforge.core.office.Word97
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 夹具 `simple.doc` 来自 Apache POI 的 test-data（Apache-2.0），是一份 Word 97-SR2 写的单段文档；
 * 期望正文用 `antiword` 独立读过，不是拿本解析器的输出当标准。
 */
class WordDocTest {

    private fun sample(): File {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/office/simple.doc")).readBytes()
        val file = File.createTempFile("fileforge-word", ".doc")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `OLE2 认成 Word 文档`() {
        val file = sample()
        Ole2.open(file).use { ole ->
            assertTrue("WordDocument" in ole.names(), ole.names().toString())
        }
        assertEquals(FileKind.Doc, OldOffice.detect(file))
    }

    @Test
    fun `按 piece table 抽出正文且不混进控制字符`() {
        val file = sample()
        val (word, table0, table1) = Ole2.open(file).use { ole ->
            Triple(
                ole.read("WordDocument")!!,
                ole.read("0Table") ?: ByteArray(0),
                ole.read("1Table") ?: ByteArray(0),
            )
        }
        val doc = Word97.read(word, table0, table1)
        assertTrue(doc.text.contains("This is a simple file created with Word 97-SR2."), doc.text)
        assertFalse(doc.text.contains("\u0000"))
        assertFalse(doc.text.contains("\u0013"), "字段指令不该进正文")
        assertTrue(doc.notes.any { it.contains("piece table") }, doc.notes.toString())
    }
}
