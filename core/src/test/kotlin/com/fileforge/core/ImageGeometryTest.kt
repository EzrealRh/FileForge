package com.fileforge.core

import com.fileforge.core.image.Flip
import com.fileforge.core.image.ImageGeometry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 改一张图的方向与边界：画布多大、裁哪块、按什么顺序 —— 安卓那边与桌面判据那边都从这里拿同一份答案。
 *
 * 这一族最容易分家的两处：**90 的整倍数**（浮点 `cos(90°)` 不是恰好 0，按三角公式一律进一会多出一列空边）
 * 与**裁剪的坐标基准**（源图像素还是转完的像素）。所以钉成单测，不靠"看上去对"。
 */
class ImageGeometryTest {

    private val box = { width: Int, height: Int, degrees: Float -> ImageGeometry.boundingBox(width, height, degrees) }

    @Test
    fun `负角与超过一圈的角度收进同一格`() {
        assertEquals(270f, ImageGeometry.normalize(-90f))
        assertEquals(90f, ImageGeometry.normalize(450f))
        assertEquals(0f, ImageGeometry.normalize(360f))
    }

    @Test
    fun `九十个的整倍数认出第几格`() {
        assertEquals(0, ImageGeometry.quarterTurn(0f))
        assertEquals(1, ImageGeometry.quarterTurn(90f))
        assertEquals(2, ImageGeometry.quarterTurn(180f))
        assertEquals(3, ImageGeometry.quarterTurn(270f))
        assertEquals(3, ImageGeometry.quarterTurn(-90f))
        assertEquals(0, ImageGeometry.quarterTurn(360f))
        assertNull(ImageGeometry.quarterTurn(30f))
        assertNull(ImageGeometry.quarterTurn(91f))
    }

    @Test
    fun `浮点余量贴着九十度时仍算整倍数`() {
        assertEquals(1, ImageGeometry.quarterTurn(90.000001f))
        assertEquals(50 to 100, box(100, 50, 90.000001f))
    }

    @Test
    fun `整倍数直接换边而不是多出一列空边`() {
        assertEquals(50 to 100, box(100, 50, 90f))
        assertEquals(100 to 50, box(100, 50, 180f))
        assertEquals(50 to 100, box(100, 50, 270f))
        assertEquals(100 to 50, box(100, 50, 0f))
    }

    @Test
    fun `任意角要扩到装得下整张图`() {
        // 100*cos30 + 50*sin30 = 111.60..，另一边 93.30.. —— 一律进一，宁可多一列也不切角
        assertEquals(112 to 94, box(100, 50, 30f))
        assertEquals(142 to 142, box(100, 100, 45f))
        assertEquals(112 to 94, box(100, 50, -30f))
    }

    @Test
    fun `按像素裁就是那四个数`() {
        val crop = ImageGeometry.cropRect("10,20,30,40", 200, 100)!!
        assertEquals(10, crop.rect.left)
        assertEquals(20, crop.rect.top)
        assertEquals(30, crop.rect.width)
        assertEquals(40, crop.rect.height)
        assertTrue(crop.notes.isEmpty(), "没越界就不该有话要说：${crop.notes}")
    }

    @Test
    fun `带百分号就按各自那条边折算`() {
        val crop = ImageGeometry.cropRect("50%,50%,25%,25%", 200, 100)!!
        assertEquals(100, crop.rect.left)
        assertEquals(50, crop.rect.top)
        assertEquals(50, crop.rect.width)
        assertEquals(25, crop.rect.height)
    }

    @Test
    fun `越界的边往里收并说收了多少`() {
        val crop = ImageGeometry.cropRect("0,0,1000,1000", 100, 50)!!
        assertEquals(0, crop.rect.left)
        assertEquals(100, crop.rect.right)
        assertEquals(50, crop.rect.bottom)
        assertEquals(2, crop.notes.size)
        assertTrue(crop.notes[0].contains("900"), crop.notes.toString())
        assertTrue(crop.notes[1].contains("950"), crop.notes.toString())
    }

    @Test
    fun `写错的裁法逐条拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.cropRect("10,20,30", 200, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.cropRect("10,20,abc,40", 200, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.cropRect("10%,20,30,40", 200, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.cropRect("-10,20,30,40", 200, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.cropRect("10,20,0,40", 200, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.cropRect("500,10,30,40", 200, 100) }
    }

    @Test
    fun `宽高比居中取放得下的最大块`() {
        val square = ImageGeometry.aspectRect("1:1", 200, 100)!!
        assertEquals(50, square.rect.left)
        assertEquals(0, square.rect.top)
        assertEquals(100, square.rect.width)
        assertEquals(100, square.rect.height)

        val wide = ImageGeometry.aspectRect("16:9", 100, 100)!!
        assertEquals(100, wide.rect.width)
        assertEquals(56, wide.rect.height)
        assertEquals(22, wide.rect.top)
        // 两边各自向下取整，所以永远不会顶出图外
        assertTrue(wide.rect.right <= 100 && wide.rect.bottom <= 100, wide.rect.toString())
    }

    @Test
    fun `写错的宽高比与取不出块的都拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.aspectRect("0:1", 200, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.aspectRect("4", 200, 100) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.aspectRect("a:b", 200, 100) }
        // 4×4 上取 16:9 是取得出的（4×2 就是那块），取不出的是比例悬殊到某一边凑不满一个像素
        assertEquals(2, ImageGeometry.aspectRect("16:9", 4, 4)!!.rect.height)
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.aspectRect("1:1000", 4, 4) }
    }

    @Test
    fun `先裁再转，尺寸是裁完那块推出来的`() {
        val plan = ImageGeometry.plan(200, 100, degrees = 90f, cropSpec = "0,0,100,100")
        assertEquals(100, plan.outWidth)
        assertEquals(100, plan.outHeight)
        assertTrue(plan.quarterTurn)
        assertTrue(plan.notes.any { it.contains("从原图 (0,0) 起裁 100×100") }, plan.notes.toString())
        assertTrue(plan.notes.any { it.contains("顺时针转 90°") }, plan.notes.toString())
        assertEquals("出来是 100×100", plan.notes.last())
    }

    @Test
    fun `裁边与宽高比都给了就按显式那块并说出来`() {
        val plan = ImageGeometry.plan(200, 100, cropSpec = "0,0,50,50", aspectSpec = "1:1")
        assertEquals(50, plan.crop!!.width)
        assertTrue(plan.notes.any { it.contains("宽高比没用上") }, plan.notes.toString())
    }

    @Test
    fun `任意角那条要说清重采样与扩画布`() {
        val plan = ImageGeometry.plan(100, 50, degrees = 30f)
        assertFalse(plan.quarterTurn)
        assertEquals(112, plan.outWidth)
        assertTrue(plan.notes.any { it.contains("重采样") }, plan.notes.toString())
    }

    @Test
    fun `只镜像不改尺寸`() {
        val plan = ImageGeometry.plan(100, 50, flip = Flip.Horizontal)
        assertEquals(100, plan.outWidth)
        assertEquals(50, plan.outHeight)
        assertTrue(plan.notes.any { it == "左右镜像" }, plan.notes.toString())
        assertNull(plan.crop)
    }

    @Test
    fun `整圈与上下左右都翻`() {
        assertEquals(100 to 50, ImageGeometry.plan(100, 50, degrees = 360f).let { it.outWidth to it.outHeight })
        assertTrue(ImageGeometry.plan(100, 50, flip = Flip.Both).notes.any { it.contains("左右再上下") })
    }

    @Test
    fun `太大的边长直接拒`() {
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.plan(13_000, 10) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.plan(0, 10) }
    }

    @Test
    fun `给用户看的角度不带多余的小数`() {
        assertEquals("30", ImageGeometry.formatDegrees(30f))
        assertEquals("30.5", ImageGeometry.formatDegrees(30.5f))
        assertEquals("0", ImageGeometry.formatDegrees(0f))
    }

    @Test
    fun `填白这句话只有不留透明的容器才有`() {
        assertEquals("JPG 不留透明，空出来的地方填白", ImageGeometry.transparentFallback(true))
        assertNull(ImageGeometry.transparentFallback(false), "PNG 留着透明，就不该说填白")
    }
}
