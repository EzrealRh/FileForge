package com.fileforge.core

import com.fileforge.core.text.TimedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimedTextTest {

    private fun tx3g(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload
    }

    @Test
    fun `tx3g 按两字节长度取正文`() {
        assertEquals("第一句", TimedText.decodeTx3g(tx3g("第一句")))
        // 长度后面的填充字节不能混进正文
        assertEquals("AB", TimedText.decodeTx3g(tx3g("AB") + byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `tx3g 认 UTF-16BE 的 BOM`() {
        val payload = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "中文".toByteArray(Charsets.UTF_16BE)
        val sample = byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload
        assertEquals("中文", TimedText.decodeTx3g(sample))
    }

    @Test
    fun `tx3g 空采样不报错`() {
        assertEquals("", TimedText.decodeTx3g(ByteArray(0)))
        assertEquals("", TimedText.decodeTx3g(byteArrayOf(0, 0)))
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    @Test
    fun `WebVTT 从 vttc 里的 payl 取正文`() {
        val payload = box("payl", "这是 VTT".toByteArray(Charsets.UTF_8))
        val sample = box("vttc", payload)
        assertEquals("这是 VTT", TimedText.decodeWebVttSample(sample))
    }

    @Test
    fun `WebVTT 前面还有别的盒子也能走到 payl`() {
        val sample = box("vttc", box("sttg", "align:center".toByteArray()) + box("payl", "text".toByteArray()))
        assertEquals("text", TimedText.decodeWebVttSample(sample))
    }

    @Test
    fun `SSA 采样的转义与整行 Dialogue 都认`() {
        assertEquals("第一行\n第二行", TimedText.decodeSsaSample("第一行\\N第二行"))
        assertEquals("你好", TimedText.decodeSsaSample("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,你好"))
        assertEquals("正文", TimedText.decodeSsaSample("0,0:00:01.00,0:00:02.00,Default,,0,0,0,,正文"))
    }

    @Test
    fun `SSA 普通正文里有逗号时不被切走`() {
        assertEquals("甲，乙,丙", TimedText.decodeSsaSample("甲，乙,丙"))
    }
}
