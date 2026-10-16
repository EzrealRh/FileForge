package com.fileforge.core

import com.fileforge.core.pdf.PdfFontProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 字体探针的字符口径。
 *
 * 这些用例的形状就是真机上翻车的那一条：一份中文表格的摊平文字里全是制表符与换行，
 * 拿整段去问字体"有没有字形"，任何字体都答没有 —— 于是一台装了 Noto Sans CJK 的手机
 * 也报"这台系统里没找到能显示…的字体"，整次打印失败。
 */
class PdfFontProbeTest {

    private val tableText = "单号\t品名\t数量\t到货日\nA-001\t机械键盘\t38.5\t2023-05-01\n"

    @Test
    fun `需要字形的字符集里没有制表符与换行`() {
        val glyphs = PdfFontProbe.glyphChars(tableText)
        assertTrue('\t' !in glyphs && '\n' !in glyphs, "控制字符不该被当成字形需求：$glyphs")
        "单号品名机械键盘".forEach { assertTrue(it in glyphs, "中文 $it 应该要字形：$glyphs") }
        assertTrue('3' in glyphs && '.' in glyphs && '-' in glyphs)
    }

    @Test
    fun `空白与控制字符都不算需求`() {
        assertEquals("abc1", PdfFontProbe.glyphChars("a\tb\r\nc 1\u0000").toList().joinToString(""))
        assertEquals("abc", PdfFontProbe.glyphChars("abcabc").toList().distinct().joinToString(""), "去重保序")
    }

    @Test
    fun `带换行的英文整段仍然只算拉丁文字`() {
        // 老写法按整段的码点判（换行是 10，不在 32..126）会把纯英文多行文字推去找中文字体
        assertTrue(PdfFontProbe.latinOnly("Hello world\n\tsecond line\r\n"), "纯拉丁多行应该能用 Helvetica")
        assertTrue(PdfFontProbe.latinOnly("   \n\t  "), "整段都是空白也算拉丁（没有字形需求）")
    }

    @Test
    fun `真需要中文字形时不许冒充拉丁`() {
        assertFalse(PdfFontProbe.latinOnly(tableText), "这份中文表必须去找能显示中文的字体")
        assertFalse(PdfFontProbe.latinOnly("价格（含税）"), "全角括号也不是拉丁")
    }


    @Test
    fun `打包字体永远排在系统候选之后`() {
        val order = PdfFontProbe.candidateOrder(
            listOf("/system/fonts/DroidSansFallback.ttf", "  ", "/system/fonts/NotoSansCJK-Regular.ttc"),
            "fonts/lxgw-wenkai-subset.ttf",
        )
        assertEquals(
            listOf("/system/fonts/DroidSansFallback.ttf", "/system/fonts/NotoSansCJK-Regular.ttc", "fonts/lxgw-wenkai-subset.ttf"),
            order, "空白候选要丢掉、内置的要垫在最后",
        )
    }

    @Test
    fun `系统里没有可用字体时也不能把内置那份弄丢`() {
        assertEquals(listOf("fonts/a.ttf"), PdfFontProbe.candidateOrder(emptyList(), "fonts/a.ttf"))
        assertEquals(listOf<String>(), PdfFontProbe.candidateOrder(listOf("", "   "), null))
    }
}
