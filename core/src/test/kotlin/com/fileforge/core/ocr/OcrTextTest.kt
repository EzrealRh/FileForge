package com.fileforge.core.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrTextTest {

    @Test
    fun `normalize 统一换行并削掉行尾空白`() {
        assertEquals("第一行\n第二行", OcrText.normalize("第一行  \r\n第二行\t\r\n"))
    }

    @Test
    fun `normalize 把连续空行压成一个`() {
        assertEquals("甲\n\n乙", OcrText.normalize("甲\n\n\n\n\n乙"))
    }

    @Test
    fun `normalize 不合并断行也不动标点`() {
        val raw = "这是被版面断开\n的一句话，标点照旧。"
        assertEquals(raw, OcrText.normalize(raw))
    }

    @Test
    fun `空页明确写出来而不是留白`() {
        assertEquals(
            "===== 第 2 页 / 共 5 页 =====\n${OcrText.EMPTY_PAGE}",
            OcrText.page(2, "  \n\t \n", 5),
        )
    }

    @Test
    fun `页界带页码与总页数`() {
        assertEquals("===== 第 1 页 / 共 3 页 =====\n正文", OcrText.page(1, "正文", 3))
    }

    @Test
    fun `join 页间空一行并在末尾留换行`() {
        assertEquals("A\n\nB\n", OcrText.join(listOf("A", "B")))
        assertEquals("", OcrText.join(emptyList()))
    }

    @Test
    fun `字数不数空白`() {
        assertEquals(5, OcrText.bodyChars("中 文\nabc  "))
    }

    @Test
    fun `中文英文与数字混排原样保留`() {
        val raw = "文件工坊 FileForge 0.58\n第二行 123"
        assertEquals(raw, OcrText.normalize(raw))
        assertTrue(OcrText.normalize(raw).contains("文件工坊"))
    }
}
