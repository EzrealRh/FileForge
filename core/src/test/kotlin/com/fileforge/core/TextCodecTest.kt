package com.fileforge.core

import com.fileforge.core.text.LineEnding
import com.fileforge.core.text.LineEndings
import com.fileforge.core.text.TextEncoding
import com.fileforge.core.text.TextCodecs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 编码转换的判据。夹具字节全部由 **Python 标准库的 codecs** 生成（独立实现），
 * 不是我照着"以为的编码表"手写的。
 *
 * 为什么这些要钉住：转错的产物是**能打开的乱码**，用户看不出来。所以
 * "解不出多少字符"必须是量出来的数，而不是默认成功。
 */
class TextCodecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // "中文字幕测试"
    private val utf8Cn = bytes(0xE4, 0xB8, 0xAD, 0xE6, 0x96, 0x87, 0xE5, 0xAD, 0x97, 0xE5, 0xB9, 0x95, 0xE6, 0xB5, 0x8B, 0xE8, 0xAF, 0x95)
    private val gbkCn = bytes(0xD6, 0xD0, 0xCE, 0xC4, 0xD7, 0xD6, 0xC4, 0xBB, 0xB2, 0xE2, 0xCA, 0xD4)
    private val big5Cn = bytes(0xA4, 0xA4, 0xA4, 0xE5)          // "中文"
    private val sjisHi = bytes(0x82, 0xB1, 0x82, 0xF1, 0x82, 0xC9, 0x82, 0xBF, 0x82, 0xCD) // "こんにちは"
    private val latin1Cafe = bytes(0x63, 0x61, 0x66, 0xE9)      // "café"
    private val emoji = bytes(0xF0, 0x9F, 0x98, 0x84)           // "😄"
    private val cn = "中文字幕测试"

    @Test
    fun `UTF-8 是严格语法，能逐字节判真假`() {
        assertTrue(TextCodecs.isValidUtf8(utf8Cn), "真 UTF-8 中文要判对")
        assertTrue(TextCodecs.isValidUtf8(emoji), "4 字节码点合法")
        assertTrue(TextCodecs.isValidUtf8("plain ascii".toByteArray()), "纯 ASCII 也是合法 UTF-8")
        assertFalse(TextCodecs.isValidUtf8(gbkCn), "同一句话的 GBK 字节必须判为非法 UTF-8 —— 这是分编码的关键")
        assertFalse(TextCodecs.isValidUtf8(big5Cn), "Big5 同理")
        assertFalse(TextCodecs.isValidUtf8(sjisHi), "Shift_JIS 同理")
        assertFalse(TextCodecs.isValidUtf8(latin1Cafe), "ISO-8859-1 的 é 不是合法 UTF-8")
        assertFalse(TextCodecs.isValidUtf8(bytes(0xC0, 0x80)), "overlong 的 / 必须拒")
        assertFalse(TextCodecs.isValidUtf8(bytes(0xE0, 0x80, 0x80)), "3 字节 overlong 必须拒")
        assertFalse(TextCodecs.isValidUtf8(bytes(0xE4, 0xB8)), "截断的序列必须拒")
        assertFalse(TextCodecs.isValidUtf8(bytes(0x80, 0x41)), "续字节出现在首位必须拒")
        assertFalse(TextCodecs.isValidUtf8(bytes(0xED, 0xA0, 0x80)), "代理区码点不能直接编码")
        assertFalse(TextCodecs.isValidUtf8(bytes(0xF5, 0x80, 0x80, 0x80)), "超过 U+10FFFF 必须拒")
    }

    @Test
    fun `BOM 认得出也带得回去`() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray()
        assertEquals(TextEncoding.Utf8, TextCodecs.detectBom(withBom))
        val decoded = TextCodecs.decode(withBom, TextEncoding.Utf8)
        assertTrue(decoded.hadBom, "要报出这份带 BOM，界面得让用户知道会不会多一个字符")
        assertEquals("你好", decoded.text, "BOM 本身不能留在正文里")

        // UTF-16LE 的 4 字节 BOM 不能被 2 字节那条抢走，否则后两个零字节会变成正文里的字符
        val utf16 = bytes(0xFF, 0xFE, 0x00, 0x00) + "a\u0000".toByteArray(Charsets.ISO_8859_1)
        assertEquals(TextEncoding.Utf16Le, TextCodecs.detectBom(utf16))

        val encoded = TextCodecs.encode("x", TextEncoding.Utf8, bom = true)
        assertEquals(4, encoded.bytes.size)
        assertEquals(TextEncoding.Utf8, TextCodecs.detectBom(encoded.bytes))
        assertNull(TextCodecs.bomBytes(TextEncoding.Gb18030), "GBK 没有 BOM 这种东西")
    }

    @Test
    fun `错编码不能悄悄当成功`() {
        val wrong = TextCodecs.decode(gbkCn, TextEncoding.Utf8)
        assertFalse(wrong.clean, "把 GBK 当 UTF-8 解必须报出坏字符")
        assertTrue(wrong.replaced > 0)

        val right = TextCodecs.decode(gbkCn, TextEncoding.Gb18030)
        assertTrue(right.clean)
        assertEquals(cn, right.text)
    }

    @Test
    fun `认不出来的编码不硬猜`() {
        assertEquals(TextEncoding.Utf8, TextCodecs.recognize(utf8Cn).encoding)
        assertEquals(TextEncoding.Utf8, TextCodecs.recognize("abc".toByteArray()).encoding)
        val gb = TextCodecs.recognize(gbkCn)
        assertTrue(gb.clean, "认出来的那个必须真能解干净，${gb.encoding}")
        assertEquals(cn, gb.text)
        assertEquals("", TextCodecs.recognize(ByteArray(0)).text)
    }

    @Test
    fun `转不过去的字要数出来而不是变问号`() {
        val lost = TextCodecs.encode(cn, TextEncoding.Latin1)
        assertEquals(6, lost.dropped, "六个汉字全转不出去")
        assertFalse(lost.clean)

        // emoji 是代理对：按 char 数会算成 2 个，按码点才是 1 个
        assertEquals(1, TextCodecs.encode("😄", TextEncoding.Latin1).dropped)
        assertEquals(1, TextCodecs.encode("a😄b", TextEncoding.Gbk).dropped)
        assertEquals(0, TextCodecs.encode("café", TextEncoding.Latin1).dropped)
        assertEquals("café", String(TextCodecs.encode("café", TextEncoding.Latin1).bytes, Charsets.ISO_8859_1))
    }

    @Test
    fun `中文在各中文编码里自转自回得来`() {
        assertTrue(TextCodecs.roundTrips(cn, TextEncoding.Utf8), "UTF-8")
        assertTrue(TextCodecs.roundTrips(cn, TextEncoding.Gb18030), "GB18030")
        assertTrue(TextCodecs.roundTrips(cn, TextEncoding.Gbk), "GBK")
    }

    @Test
    fun `日文繁体和 UTF-16 也自洽`() {
        assertTrue(TextCodecs.roundTrips("こんにちは", TextEncoding.ShiftJis), "Shift_JIS")
        assertTrue(TextCodecs.roundTrips("中文", TextEncoding.Big5), "Big5")
        assertTrue(TextCodecs.roundTrips("café\n第二行", TextEncoding.Utf16Le), "UTF-16LE")
    }

    @Test
    fun `装不下就是装不下不许谎称回得来`() {
        assertFalse(TextCodecs.roundTrips(cn, TextEncoding.Latin1), "拉丁1 装不下中文")
    }

    @Test
    fun `界面列出的编码这台机器都得有`() {
        // 安卓（ICU）和桌面 JDK 都带这一整套。哪天某个平台缺了，界面要按 available() 把它藏掉，
        // 而不是等用户选了之后在解码时崩 —— 这条断言就是那个前提的哨兵
        TextEncoding.entries.forEach {
            assertTrue(TextCodecs.available(it), "${it.charsetName} 应当可用；不可用时界面要藏掉它")
        }
    }
}

class LineEndingTest {

    @Test
    fun `混排要数得清并且少数派也算进去`() {
        val text = "a\r\nb\nc\r\nd"
        val tally = LineEndings.tally(text)
        assertEquals(2, tally[LineEnding.CrLf])
        assertEquals(1, tally[LineEnding.Lf])
        assertEquals(LineEnding.CrLf, LineEndings.detect(text), "占多数的那个才是文件的换行风格")
        assertNull(LineEndings.detect("一行到底"), "没有换行就不该硬说一种")
    }

    @Test
    fun `单独的 CR 不会被当成 CRLF 的一半`() {
        val tally = LineEndings.tally("a\rb\rc")
        assertEquals(2, tally[LineEnding.Cr])
        assertNull(tally[LineEnding.CrLf])
    }

    @Test
    fun `先统一再转换，二次转换不会留下半个回车`() {
        val mixed = "a\r\nb\nc"
        val toLf = LineEndings.convert(mixed, LineEnding.Lf)
        assertEquals("a\nb\nc", toLf)
        // 反向：先转 CR 再转 LF 不能变成 \r\r\n 这种鬼东西
        assertEquals("a\nb\nc", LineEndings.convert(LineEndings.convert(mixed, LineEnding.Cr), LineEnding.Lf))
        assertEquals("a\r\nb\r\nc", LineEndings.convert(mixed, LineEnding.CrLf))
        assertEquals("a\rb\rc", LineEndings.convert(mixed, LineEnding.Cr))
        // 幂等
        assertEquals("a\r\nb\r\nc", LineEndings.convert(LineEndings.convert(mixed, LineEnding.CrLf), LineEnding.CrLf))
    }
}
