package com.fileforge.core

import com.fileforge.core.book.Mobi
import com.fileforge.core.doc.Html
import com.fileforge.core.model.FileKind
import com.fileforge.core.model.FileTypeSniffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MobiTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mobi/$name")) { "缺夹具 $name" }.readBytes()

    private fun record0Start(bytes: ByteArray): Int =
        ((bytes[78].toInt() and 0xFF) shl 24) or
            ((bytes[79].toInt() and 0xFF) shl 16) or
            ((bytes[80].toInt() and 0xFF) shl 8) or
            (bytes[81].toInt() and 0xFF)

    @Test
    fun `未压缩的 MOBI 读出标题作者与正文结构`() {
        val book = Mobi.read(fixture("plain.mobi"))
        assertEquals("样书标题", book.title)
        assertEquals("测试作者", book.author)
        assertTrue(book.plainText.contains("这是第一段正文"), book.plainText.take(120))
        assertTrue(book.plainText.contains("列表项一"))
        assertTrue(book.plainText.contains("格1"))
        assertTrue(book.plainText.contains("第二章的正文"))
        assertTrue(book.notes.none { it.contains("压缩") }, book.notes.toString())
    }

    @Test
    fun `PalmDOC 压缩的记录会展开且内容一致`() {
        val plain = Mobi.read(fixture("plain.mobi"))
        val packed = Mobi.read(fixture("compressed.mobi"))
        assertEquals(plain.plainText, packed.plainText)
        assertEquals(plain.html, packed.html)
        assertTrue(packed.notes.any { it.contains("PalmDOC 压缩") }, packed.notes.toString())
    }

    @Test
    fun `MOBI 正文按 HTML 结构还原成文档树`() {
        val book = Mobi.read(fixture("plain.mobi"))
        val doc = Html.toDoc(book.html)
        assertTrue(doc.parts.isNotEmpty())
        assertTrue(doc.parts.any { it is com.fileforge.core.doc.DocParagraph })
    }

    @Test
    fun `按偏移 60 的类型创建者认出 MOBI`() {
        assertEquals(FileKind.Mobi, FileTypeSniffer.sniff(fixture("plain.mobi")))
        assertEquals(FileKind.Mobi, FileTypeSniffer.sniff(fixture("compressed.mobi")))
    }

    @Test
    fun `带 DRM 的书明确拒绝而不是出乱码`() {
        val bytes = fixture("plain.mobi")
        val start = record0Start(bytes)
        bytes[start + 12] = 0
        bytes[start + 13] = 2
        val error = assertThrows(IllegalArgumentException::class.java) { Mobi.read(bytes) }
        assertTrue(error.message!!.contains("DRM"), error.message)
    }

    @Test
    fun `HUFF 压缩明确说不支持`() {
        val bytes = fixture("plain.mobi")
        val start = record0Start(bytes)
        val huff = 17480
        bytes[start] = (huff shr 8).toByte()
        bytes[start + 1] = huff.toByte()
        val error = assertThrows(IllegalArgumentException::class.java) { Mobi.read(bytes) }
        assertTrue(error.message!!.contains("HUFF"), error.message)
    }

    @Test
    fun `PalmDOC 回指与空格转义按规范展开`() {
        // 字面 5 个 "Hello"，再从距离 5 处回指 5 个 → HelloHello
        val backref = byteArrayOf(5, 'H'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(),
            'l'.code.toByte(), 'o'.code.toByte(), 0x80.toByte(), 0x2A)
        assertEquals("HelloHello", String(Mobi.decompressPalmDoc(backref), Charsets.UTF_8))
        // 0xE1 = 空格 + (0xE1 xor 0x80 = 0x61 = 'a')
        assertEquals(" a", String(Mobi.decompressPalmDoc(byteArrayOf(0xE1.toByte())), Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0), Mobi.decompressPalmDoc(byteArrayOf(0)))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual)
}
