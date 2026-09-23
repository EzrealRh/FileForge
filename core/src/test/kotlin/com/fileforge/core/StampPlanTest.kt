package com.fileforge.core

import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.OperationKind
import com.fileforge.core.pdf.Horizontal
import com.fileforge.core.pdf.PageNumberPlan
import com.fileforge.core.pdf.PageNumberPlan.STYLE_CJK
import com.fileforge.core.pdf.PageNumberPlan.STYLE_PLAIN
import com.fileforge.core.pdf.PageNumberPlan.STYLE_TOTAL
import com.fileforge.core.pdf.StampFrame
import com.fileforge.core.pdf.StampSpot
import com.fileforge.core.pdf.TextFit
import com.fileforge.core.pdf.WatermarkPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageNumberPlanTest {

    @Test
    fun `页码按物理页算只平移起始数字`() {
        assertEquals(1, PageNumberPlan.numberFor(0, 1))
        assertEquals(7, PageNumberPlan.numberFor(4, 3))
        // 截取后重排页序时，页码仍然跟着原来的物理页，不会跟着新序号跳
        assertEquals(11, PageNumberPlan.numberFor(10, 1))
    }

    @Test
    fun `三种页码格式`() {
        assertEquals("3", PageNumberPlan.text(3, 12, STYLE_PLAIN))
        assertEquals("第 3 页", PageNumberPlan.text(3, 12, STYLE_CJK))
        assertEquals("3 / 12", PageNumberPlan.text(3, 12, STYLE_TOTAL))
    }

    @Test
    fun `锚点按视觉位置算`() {
        val center = PageNumberPlan.anchor(595f, 842f, StampSpot.BottomCenter, 20f, 12f)
        assertEquals(297.5f, center.x, 0.01f)
        assertEquals(23f, center.y, 0.01f)
        assertEquals(Horizontal.CENTER, center.align)

        val right = PageNumberPlan.anchor(595f, 842f, StampSpot.BottomRight, 20f, 12f)
        assertEquals(575f, right.x, 0.01f)

        val top = PageNumberPlan.anchor(595f, 842f, StampSpot.TopCenter, 20f, 12f)
        assertEquals(810f, top.y, 0.01f)
    }

    @Test
    fun `边距大到离谱也不会把基线推到页面外面`() {
        val anchor = PageNumberPlan.anchor(100f, 100f, StampSpot.TopCenter, 500f, 12f)
        assertEquals(0f, anchor.y, 0.001f)
    }
}

class StampFrameTest {

    @Test
    fun `不旋转时是恒等变换`() {
        val frame = StampFrame.forRotation(595f, 842f, 0)
        assertEquals(0f, frame.translateX, 0.001f)
        assertEquals(0f, frame.translateY, 0.001f)
        assertEquals(0f, frame.rotateDegrees, 0.001f)
        assertEquals(595f, frame.width, 0.001f)
        assertEquals(842f, frame.height, 0.001f)
    }

    @Test
    fun `九十和二百七十要交换宽高并把坐标系掰回视觉方向`() {
        val clockwise = StampFrame.forRotation(595f, 842f, 90)
        assertEquals(90f, clockwise.rotateDegrees, 0.001f)
        assertEquals(842f, clockwise.width, 0.001f)
        assertEquals(595f, clockwise.height, 0.001f)
        // 视觉左下角对应内容空间的 (boxWidth, 0)
        assertEquals(595f, clockwise.translateX, 0.001f)
        assertEquals(0f, clockwise.translateY, 0.001f)

        val counter = StampFrame.forRotation(595f, 842f, 270)
        assertEquals(-90f, counter.rotateDegrees, 0.001f)
        assertEquals(842f, counter.width, 0.001f)
        assertEquals(0f, counter.translateX, 0.001f)
        assertEquals(842f, counter.translateY, 0.001f)
    }

    @Test
    fun `一百八十平移一页并给出原尺寸`() {
        val frame = StampFrame.forRotation(595f, 842f, 180)
        assertEquals(180f, frame.rotateDegrees, 0.001f)
        assertEquals(595f, frame.translateX, 0.001f)
        assertEquals(842f, frame.translateY, 0.001f)
        assertEquals(595f, frame.width, 0.001f)
        assertEquals(842f, frame.height, 0.001f)
    }

    @Test
    fun `旋转角归一化`() {
        assertEquals(StampFrame.forRotation(595f, 842f, 90), StampFrame.forRotation(595f, 842f, 450))
        assertEquals(StampFrame.forRotation(595f, 842f, 270), StampFrame.forRotation(595f, 842f, -90))
    }
}

class WatermarkPlanTest {

    @Test
    fun `平铺格子数量与中心点都落在页面内`() {
        val tiles = WatermarkPlan.tiles(600f, 800f, 3, 4)
        assertEquals(12, tiles.size)
        assertTrue(tiles.all { (x, y) -> x > 0f && x < 600f && y > 0f && y < 800f })
        assertEquals(100f, tiles.first().first, 0.01f)
        assertEquals(500f, tiles.last().first, 0.01f)
    }

    @Test
    fun `一行一列就是正居中`() {
        assertEquals(WatermarkPlan.center(600f, 800f), WatermarkPlan.tiles(600f, 800f, 1, 1).single())
    }

    @Test
    fun `行列数被夹在可用范围内`() {
        assertEquals(1, WatermarkPlan.tiles(600f, 800f, 0, -5).size)
        assertEquals(144, WatermarkPlan.tiles(600f, 800f, 99, 99).size)
    }

    @Test
    fun `每格可用宽度按列数和留白算`() {
        assertEquals(90f, WatermarkPlan.cellWidth(300f, 3, 0.9f), 0.01f)
        assertTrue(WatermarkPlan.cellWidth(300f, 3, 0f) > 0f)
    }
}

class StampCatalogTest {

    @Test
    fun `页码和水印只对 PDF 开放`() {
        val pdf = OperationKind.applicable(setOf(FileKind.Pdf))
        assertTrue(OperationKind.AddPageNumbers in pdf)
        assertTrue(OperationKind.PdfWatermark in pdf)
        val image = OperationKind.applicable(setOf(FileKind.Jpeg))
        assertTrue(OperationKind.AddPageNumbers !in image)
        assertTrue(OperationKind.PdfWatermark !in image)
    }

    @Test
    fun `页码参数默认落在页面里`() {
        val operation = Operation.PageNumbers()
        val frame = StampFrame.forRotation(595f, 842f, 0)
        val anchor = PageNumberPlan.anchor(frame.width, frame.height, operation.spot, operation.margin.toFloat(), operation.fontSize.toFloat())
        assertTrue(anchor.x in 0f..frame.width)
        assertTrue(anchor.y in 0f..frame.height)
        val text = PageNumberPlan.text(PageNumberPlan.numberFor(0, operation.firstNumber), 12, operation.style)
        assertEquals("1", text)
    }

    @Test
    fun `水印默认是一整块居中`() {
        val operation = Operation.PdfWatermark(text = "草稿")
        assertEquals(1, operation.columns * operation.rows)
        val tiles = WatermarkPlan.tiles(595f, 842f, operation.columns, operation.rows)
        assertEquals(WatermarkPlan.center(595f, 842f), tiles.single())
    }
}

class TextFitTest {

    @Test
    fun `中日韩字形按方块算拉丁字母窄一半`() {
        assertEquals(20f, TextFit.estimateWidth("中文", 10f), 0.01f)
        assertEquals(11f, TextFit.estimateWidth("ab", 10f), 0.01f)
    }

    @Test
    fun `字号按可用宽度反算`() {
        assertEquals(50f, TextFit.largestFontSize("水印文字", 200f), 0.01f)
        assertEquals(6f, TextFit.largestFontSize("很长很长很长很长很长很长的水印文字", 20f), 0.01f)
        assertEquals(160f, TextFit.largestFontSize("短", 100_000f), 0.01f)
    }

    @Test
    fun `空串和零宽度都不会算出 NaN 或负数`() {
        assertEquals(6f, TextFit.largestFontSize("", 200f), 0.01f)
        assertEquals(6f, TextFit.largestFontSize("字", 0f), 0.01f)
        assertTrue(TextFit.largestFontSize("字", -5f).isFinite())
    }
}
