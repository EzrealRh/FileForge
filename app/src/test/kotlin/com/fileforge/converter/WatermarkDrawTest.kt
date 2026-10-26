package com.fileforge.converter

import android.graphics.Bitmap
import com.fileforge.converter.engine.ImageEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 多行水印真的画成两行：数一条条有字的横向带，而不是看代码猜。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkDrawTest {

    private fun uploadBands(text: String): List<IntRange> {
        val size = 400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        ImageEngine().drawTextWatermark(
            bitmap, text, spot = 0, tiled = false,
            opacityPercent = 100, tilt = 0, sizePercent = 200,
        )
        val row = IntArray(size)
        val inkRows = ArrayList<Int>()
        for (y in 0 until size) {
            bitmap.getPixels(row, 0, size, 0, y, size, 1)
            if (row.any { it ushr 24 != 0 }) inkRows += y
        }
        bitmap.recycle()
        val ranges = ArrayList<IntRange>()
        var start = -1
        var last = -2
        inkRows.forEach { y ->
            if (start < 0) {
                start = y
            } else if (y != last + 1) {
                ranges += start..last
                start = y
            }
            last = y
        }
        if (start >= 0) ranges += start..last
        return ranges
    }

    private fun inkBounds(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val row = IntArray(width)
        var minX = width
        var maxX = -1
        var minY = bitmap.height
        var maxY = -1
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (row[x] ushr 24 != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `两行文字落成两条有字的带，中间留空`() {
        val one = uploadBands("AAA")
        assertEquals("单行只该有一条字带：$one", 1, one.size)

        val two = uploadBands("AAA\nBBB")
        assertEquals("两行该有两条字带：$two", 2, two.size)
        assertTrue("两行之间要留空隙：$two", two[1].first - two[0].last > 1)
    }

    @Test
    fun `左下位置真的落在左下`() {
        val text = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        ImageEngine().drawTextWatermark(
            text, "AA", spot = 2, tiled = false,
            opacityPercent = 100, tilt = 0, sizePercent = 200,
        )
        val t = inkBounds(text)
        assertTrue("文字左边距应贴左：${t.toList()}", t[0] < 100)
        assertTrue("文字底边应贴底：${t.toList()}", t[3] > 300)

        val logo = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        logo.eraseColor(android.graphics.Color.WHITE)
        val target = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        ImageEngine().drawLogoWatermark(
            target, logo, spot = 2, tiled = false, opacityPercent = 100, tilt = 0,
        )
        val l = inkBounds(target)
        assertTrue("Logo 左边距应贴左：${l.toList()}", l[0] < 100)
        assertTrue("Logo 底边应贴底：${l.toList()}", l[3] > 300)
    }
}
