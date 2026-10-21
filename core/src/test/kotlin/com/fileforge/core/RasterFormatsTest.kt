package com.fileforge.core

import com.fileforge.core.image.RasterFormats
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** BMP / TIFF 这一层：容器头、行序、透明与往返。外部裁判（Pillow）由 tools/verify_raster.py 单独核。 */
class RasterFormatsTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `BMP 是 24 位、行按 4 字节对齐、自下而上`() {
        val pixels = intArrayOf(red, green, blue, white, red, green)   // 3×2
        val bmp = RasterFormats.encodeBmp(3, 2, pixels)
        assertEquals('B'.code, bmp[0].toInt())
        assertEquals('M'.code, bmp[1].toInt())
        assertEquals(3, le32(bmp, 18))
        assertEquals(2, le32(bmp, 22))
        assertEquals(24, le16(bmp, 28))
        val rowBytes = (3 * 3 + 3) / 4 * 4
        assertEquals(54 + rowBytes * 2, bmp.size)
        // 文件里第一行是源图的最后一行：white, red, green → BGR
        assertEquals(0xFF, bmp[54].toInt() and 0xFF)
        assertEquals(0xFF, bmp[55].toInt() and 0xFF)
        assertEquals(0xFF, bmp[56].toInt() and 0xFF)
        assertEquals(0x00, bmp[57].toInt() and 0xFF)   // red 的 B
        assertEquals(0x00, bmp[58].toInt() and 0xFF)   // red 的 G
        assertEquals(0xFF, bmp[59].toInt() and 0xFF)   // red 的 R
        // 每行尾部补齐 3 个 0
        assertEquals(0, bmp[63].toInt())
        assertEquals(0, bmp[64].toInt())
        assertEquals(0, bmp[65].toInt())
    }

    @Test
    fun `BMP 透明像素铺到白底上`() {
        val transparent = 0x00FF0000                          // 全透明红
        val half = 0x80FF0000.toInt()                        // 半透明红
        val bmp = RasterFormats.encodeBmp(2, 1, intArrayOf(transparent, half))
        // 第一个像素：白；第二个：红 255、绿蓝各约 127
        assertEquals(255, bmp[54].toInt() and 0xFF)
        assertEquals(255, bmp[55].toInt() and 0xFF)
        assertEquals(255, bmp[56].toInt() and 0xFF)
        assertEquals(127, bmp[57].toInt() and 0xFF)
        assertEquals(127, bmp[58].toInt() and 0xFF)
        assertEquals(255, bmp[59].toInt() and 0xFF)
    }

    @Test
    fun `TIFF 往返保住每个像素`() {
        val pixels = intArrayOf(red, green, blue, white, 0x80123456.toInt(), 0xFF654321.toInt())
        val raster = RasterFormats.decodeTiff(RasterFormats.encodeTiff(3, 2, pixels))
        assertEquals(3, raster.width)
        assertEquals(2, raster.height)
        assertArrayEquals(pixels, raster.argb)
    }

    @Test
    fun `全不透明的 TIFF 不带 alpha 通道`() {
        val pixels = intArrayOf(red, green, blue, white)
        val raster = RasterFormats.decodeTiff(RasterFormats.encodeTiff(2, 2, pixels))
        assertArrayEquals(pixels, raster.argb)
        // 3 个样本时像素区是 2×2×3 字节：头 + IFD + 3 个 SHORT，最后一段就是像素
        val bytes = RasterFormats.encodeTiff(2, 2, pixels)
        assertEquals(2 * 2 * 3, bytes.size - (8 + 2 + 11 * 12 + 4 + 3 * 2))
    }

    @Test
    fun `写坏的 TIFF 直接拒`() {
        assertThrows(IllegalArgumentException::class.java) { RasterFormats.decodeTiff(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { RasterFormats.decodeTiff("MM\u0000*".toByteArray() + ByteArray(8)) }
    }

    private fun le16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
