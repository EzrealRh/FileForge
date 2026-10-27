package com.fileforge.core

import com.fileforge.core.pdf.PageResizeMode
import com.fileforge.core.pdf.PageResizePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 改纸张的几何：等比/裁边/拉伸、横竖方向、以及 /Rotate 90 的两轴互换。 */
class PageResizePlanTest {

    private val a4w = 595f
    private val a4h = 842f

    @Test
    fun `A4 缩到 A5 等比留白，内容居中且纸张是竖向`() {
        val plan = PageResizePlan.plan(a4w, a4h, rotation = 0, paperWidth = 420f, paperHeight = 595f, mode = PageResizeMode.Contain)
        assertEquals(420f, plan.boxWidth, 0.01f)
        assertEquals(595f, plan.boxHeight, 0.01f)
        assertEquals(420f / 595f, plan.scaleX, 0.0001f)
        assertEquals(plan.scaleX, plan.scaleY, 0.0001f)
        assertEquals(0f, plan.translateX, 0.02f)
        assertEquals((595f - a4h * plan.scaleY) / 2f, plan.translateY, 0.02f)
    }

    @Test
    fun `Letter 放到 A4 等比留白，短边贴住、长边上下留白`() {
        val plan = PageResizePlan.plan(612f, 792f, rotation = 0, paperWidth = a4w, paperHeight = a4h, mode = PageResizeMode.Contain)
        val scale = a4w / 612f
        assertEquals(scale, plan.scaleX, 0.0001f)
        assertEquals(0f, plan.translateX, 0.02f)
        assertEquals((a4h - 792f * scale) / 2f, plan.translateY, 0.02f)
    }

    @Test
    fun `旋转 90 的 A4 按横向 A4 处理，不需要转两次`() {
        val plan = PageResizePlan.plan(a4w, a4h, rotation = 90, paperWidth = a4w, paperHeight = a4h, mode = PageResizeMode.Contain)
        assertEquals(595f, plan.boxWidth, 0.01f)
        assertEquals(842f, plan.boxHeight, 0.01f)
        assertEquals(842f, plan.displayWidth, 0.01f)
        assertEquals(595f, plan.displayHeight, 0.01f)
        assertEquals(1f, plan.scaleX, 0.0001f)
        assertEquals(1f, plan.scaleY, 0.0001f)
    }

    @Test
    fun `旋转 90 的 A4 缩到 A5，显示尺寸是横向 A5`() {
        val plan = PageResizePlan.plan(a4w, a4h, rotation = 90, paperWidth = 420f, paperHeight = 595f, mode = PageResizeMode.Contain)
        assertEquals(595f, plan.displayWidth, 0.01f)
        assertEquals(420f, plan.displayHeight, 0.01f)
        assertEquals(420f, plan.boxWidth, 0.01f)
        assertEquals(595f, plan.boxHeight, 0.01f)
        assertEquals(plan.scaleX, plan.scaleY, 0.0001f)
    }

    @Test
    fun `旋转 90 的拉伸把两条轴的缩放对调`() {
        val plan = PageResizePlan.plan(400f, 200f, rotation = 90, paperWidth = a4w, paperHeight = a4h, mode = PageResizeMode.Stretch)
        // 显示 200x400 → 目标竖向 A4 595x842：视觉 X 缩 2.975、Y 缩 2.105；
        // 未旋转的 X 映射到视觉 Y，所以 scaleX 取 2.105
        assertEquals(842f / 400f, plan.scaleX, 0.0001f)
        assertEquals(595f / 200f, plan.scaleY, 0.0001f)
        assertEquals(842f, plan.boxWidth, 0.01f)
        assertEquals(595f, plan.boxHeight, 0.01f)
    }

    @Test
    fun `裁边模式取更大的缩放，内容超出页框由页面裁掉`() {
        val plan = PageResizePlan.plan(612f, 792f, rotation = 0, paperWidth = a4w, paperHeight = a4h, mode = PageResizeMode.Cover)
        assertEquals(a4h / 792f, plan.scaleX, 0.0001f)
        assertEquals(plan.scaleX, plan.scaleY, 0.0001f)
        assertEquals((a4w - 612f * plan.scaleX) / 2f, plan.translateX, 0.02f)
    }

    @Test
    fun `源页左下角不在原点时，平移要把原点差补回来`() {
        val plan = PageResizePlan.plan(
            200f, 100f, sourceLowerLeftX = 20f, sourceLowerLeftY = 30f,
            rotation = 0, paperWidth = 200f, paperHeight = 100f, mode = PageResizeMode.Contain,
        )
        assertEquals(1f, plan.scaleX, 0.0001f)
        assertEquals(-20f, plan.translateX, 0.02f)
        assertEquals(-30f, plan.translateY, 0.02f)
    }
}
