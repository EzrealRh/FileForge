package com.fileforge.core

import com.fileforge.core.image.WatermarkAlign
import com.fileforge.core.image.WatermarkText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 多行水印的排版：换行怎么断、每行落在块里的哪一侧、块高怎么算。 */
class WatermarkTextTest {

    private fun measure(text: String) = text.length * 10f

    @Test
    fun `三种换行都算换行，行内空白去掉，首尾空行丢掉`() {
        assertEquals(listOf("A", "B", "C"), WatermarkText.split("A\r\nB\rC"))
        assertEquals(listOf("A", "", "B"), WatermarkText.split("\n  A  \n\n B \n"))
        assertTrue(WatermarkText.split("   \n  ").isEmpty())
    }

    @Test
    fun `居中时短行往块里缩，左右对齐各贴各的边`() {
        val center = WatermarkText.layout("一二\n四", ::measure, WatermarkAlign.CENTER)
        assertEquals(2, center.lines.size)
        assertEquals(20f, center.width)
        assertEquals(0f, center.lines[0].left)
        assertEquals(5f, center.lines[1].left)

        val right = WatermarkText.layout("一二\n四", ::measure, WatermarkAlign.RIGHT)
        assertEquals(10f, right.lines[1].left)

        val left = WatermarkText.layout("一二\n四", ::measure, WatermarkAlign.LEFT)
        assertEquals(0f, left.lines[1].left)
    }

    @Test
    fun `块高按行距递推，最后一行算整行字高而不是行距`() {
        val block = WatermarkText.layout("一\n二\n三", ::measure, WatermarkAlign.CENTER)
        assertEquals(3, block.lines.size)
        assertEquals(2 * 24f + 20f, block.height(lineStep = 24f, lineHeight = 20f))
        assertEquals(0f, WatermarkText.layout("", ::measure, WatermarkAlign.CENTER).height(24f, 20f))
    }

    @Test
    fun `空行保留来拉行距，宽度按最宽那行算`() {
        val block = WatermarkText.layout("甲\n\n乙丙", ::measure, WatermarkAlign.CENTER)
        assertEquals(listOf("甲", "", "乙丙"), block.lines.map { it.text })
        assertEquals(20f, block.width)
        assertEquals(0f, block.lines[2].left)
        assertEquals(5f, block.lines[0].left)
    }
}
