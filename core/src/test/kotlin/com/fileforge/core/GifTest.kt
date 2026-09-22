package com.fileforge.core

import com.fileforge.core.gif.GifDecoder
import com.fileforge.core.gif.GifEncoder
import com.fileforge.core.gif.GifException
import com.fileforge.core.gif.GifFrame
import com.fileforge.core.util.MedianCut
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GifRoundTripTest {

    private fun frame(width: Int, height: Int, delayCs: Int, paint: (x: Int, y: Int) -> Int) =
        GifFrame(IntArray(width * height) { index -> paint(index % width, index / height) }, delayCs)

    @Test
    fun `两色动画编码后再解码 像素和时间都原样回来`() {
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF0000.toInt()
        val checker: (Int, Int) -> Int = { x, y -> if ((x + y) % 2 == 0) white else red }
        val stripes: (Int, Int) -> Int = { _, y -> if (y % 2 == 0) red else white }

        val bytes = GifEncoder(8, 8, loopCount = 0, maxColors = 256)
            .encode(listOf(frame(8, 8, 10, checker), frame(8, 8, 25, stripes)))

        val decoded = GifDecoder.decode(bytes)
        assertEquals(8, decoded.width)
        assertEquals(8, decoded.height)
        assertEquals(2, decoded.frames.size)
        assertEquals(listOf(10, 25), decoded.frames.map { it.delayCs })
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                assertEquals(checker(x, y), decoded.frames[0].argb[y * 8 + x], "第 1 帧 ($x,$y)")
                assertEquals(stripes(x, y), decoded.frames[1].argb[y * 8 + x], "第 2 帧 ($x,$y)")
            }
        }
    }

    @Test
    fun `透明像素编解码后仍然是透明`() {
        val blue = 0xFF0000FF.toInt()
        val source = listOf(frame(4, 4, 12) { x, y -> if (x == y) 0 else blue })
        val decoded = GifDecoder.decode(GifEncoder(4, 4, maxColors = 64).encode(source))

        assertEquals(1, decoded.frames.size)
        val argb = decoded.frames[0].argb
        assertEquals(0, argb[0], "对角线应该是透明")
        assertEquals(0, argb[15])
        assertEquals(blue, argb[1])
    }

    @Test
    fun `disposal 背景擦除不能把透明区画成不透明黑`() {
        // 编码器把 0 号槽同时用作透明色和 LSD 背景索引，并且每帧都写 disposal=2 + 透明标志。
        // 擦背景时必须回到透明，修好之前会画成不透明近黑，镂空处被填实。
        val blue = 0xFF0000FF.toInt()
        val green = 0xFF00FF00.toInt()
        val full = IntArray(8 * 8) { blue }
        val partial = IntArray(8 * 8) { index -> if (index < 8) green else 0 }
        val tiny = IntArray(8 * 8) { index -> if (index == 0) green else 0 }
        val bytes = GifEncoder(8, 8, maxColors = 16).encode(
            listOf(GifFrame(full, 12), GifFrame(partial, 12), GifFrame(tiny, 12)),
        )

        val decoded = GifDecoder.decode(bytes)
        // 每帧都是完整画布，所以透明处不继承上一帧，而是回到背景（=透明槽）
        assertEquals(0, decoded.frames[1].argb[63], "第二帧的透明处应该是透明")
        assertEquals(0, decoded.frames[2].argb[63], "第三帧的透明处应该是透明")
        assertEquals(blue, decoded.frames[0].argb[63], "第一帧应该铺满蓝")

        // 这张图里根本没有黑色，出现不透明黑就说明擦背景时把索引当成了颜色
        val black = 0xFF000000.toInt()
        assertTrue(decoded.frames.none { frame -> frame.argb.contains(black) }, "擦背景不能画出不透明黑")

        val again = GifDecoder.decode(GifEncoder(8, 8, maxColors = 16).encode(decoded.frames))
        assertEquals(0, again.frames[2].argb[63], "重编一次后角落仍然要是透明")
    }

    @Test
    fun `帧像素数不够时直接报错而不是挖洞`() {
        val short = GifFrame(IntArray(40), 10)
        val error = assertThrows(IllegalArgumentException::class.java) {
            GifEncoder(8, 8).encode(listOf(short))
        }
        assertTrue(error.message!!.contains("像素"), error.message)
    }

    @Test
    fun `循环次数写进文件也能读回来`() {
        val bytes = GifEncoder(2, 2, loopCount = 3).encode(listOf(frame(2, 2, 5) { _, _ -> 0xFF112233.toInt() }))
        assertEquals(3, GifDecoder.decode(bytes).loopCount)
    }

    @Test
    fun `文件头尾符合 GIF 规范`() {
        val bytes = GifEncoder(2, 2).encode(listOf(frame(2, 2, 5) { _, _ -> 0xFF000000.toInt() }))
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.ISO_8859_1))
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
    }

    @Test
    fun `坏文件报明确错误而不是崩`() {
        assertThrows(GifException::class.java) { GifDecoder.decode("NOTAGIF!".toByteArray()) }
        assertThrows(GifException::class.java) { GifDecoder.decode(ByteArray(3)) }
        val truncated = GifEncoder(8, 8).encode(listOf(frame(8, 8, 5) { _, _ -> 0xFF000000.toInt() }))
            .copyOf(30)
        assertThrows(GifException::class.java) { GifDecoder.decode(truncated) }
    }

    @Test
    fun `超出像素预算时只解前面的帧并标记截断`() {
        val frames = (0 until 12).map { index ->
            GifFrame(IntArray(16 * 16) { 0xFF000000.toInt() or (index * 0x001010) }, 10)
        }
        val bytes = GifEncoder(16, 16, maxColors = 64).encode(frames)

        val whole = GifDecoder.decode(bytes)
        assertEquals(12, whole.frames.size)
        assertTrue(!whole.truncated)

        val bounded = GifDecoder.decode(bytes, pixelBudget = 16 * 16 * 5)
        assertEquals(5, bounded.frames.size)
        assertTrue(bounded.truncated)
    }

    @Test
    fun `帧率换算给界面显示`() {
        val image = com.fileforge.core.gif.GifImage(
            2, 2,
            listOf(GifFrame(IntArray(4), 10), GifFrame(IntArray(4), 15)),
            0,
        )
        assertEquals(25, image.totalDurationCs)
        assertEquals(8f, image.averageFps, 0.01f)
    }
}

class MedianCutTest {

    @Test
    fun `颜色数不超过目标时原样保留`() {
        val colors = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF)
        assertEquals(listOf(0xFF0000, 0x00FF00, 0x0000FF), MedianCut.build(colors, 16).toList())
    }

    @Test
    fun `超限时收敛到目标颜色数且都落在原始色范围内`() {
        val colors = IntArray(4096) { it * 16 }
        val palette = MedianCut.build(colors, 64)
        assertTrue(palette.size in 2..64, "实际 ${palette.size}")
        assertTrue(palette.all { it in 0..0xFFFFFF })
    }

    @Test
    fun `单色图只出一个颜色`() {
        val palette = MedianCut.build(IntArray(500) { 0x40E0D0 }, 32)
        assertEquals(listOf(0x40E0D0), palette.toList())
    }

    @Test
    fun `空输入返回空调色板`() {
        assertEquals(0, MedianCut.build(IntArray(0), 16).size)
    }
}

/**
 * 编码产物的字节级体检。自家解码器和自家编码器可能一起错，所以这里不看解码结果，
 * 直接在字节里找时长和循环标记；顺带把一份「视频转 GIF」形状的产物写到 build/gif-probe/，
 * 方便拿 Pillow / 浏览器这类第三方实现交叉核对。
 */
class GifEncoderBytesTest {

    private fun stripe(color: Int) = GifFrame(
        IntArray(16 * 16) { index -> if ((index / 16) % 2 == 0) color else 0xFFFFFFFF.toInt() },
        12,
    )

    private fun asciiSpan(bytes: ByteArray, text: String): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        outer@ for (start in 0..bytes.size - needle.size) {
            for (offset in needle.indices) if (bytes[start + offset] != needle[offset]) continue@outer
            return start
        }
        return -1
    }

    /** 图形控制扩展：21 F9 04 <打包字节> <延迟小端> <透明索引> 00。 */
    private fun delays(bytes: ByteArray): List<Int> {
        val found = ArrayList<Int>()
        var p = 0
        while (p <= bytes.size - 6) {
            if (bytes[p].toInt() and 0xFF == 0x21 && bytes[p + 1].toInt() and 0xFF == 0xF9 &&
                bytes[p + 2].toInt() and 0xFF == 0x04
            ) {
                found += (bytes[p + 4].toInt() and 0xFF) or ((bytes[p + 5].toInt() and 0xFF) shl 8)
                p += 6
            } else {
                p++
            }
        }
        return found
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `每帧时长和无限循环标记都在字节里`() {
        val bytes = GifEncoder(16, 16, maxColors = 4)
            .encode(listOf(stripe(0xFFFFFF00.toInt()), stripe(0xFF0000FF.toInt())))

        val marker = "NETSCAPE2.0"
        val loop = asciiSpan(bytes, marker)
        assertTrue(loop > 0, "缺 NETSCAPE2.0 应用扩展，播放器只会放一遍")
        // 21 FF 0B "NETSCAPE2.0" 03 01 <循环次数小端> 00，这段布局是 hexdump 实测过的
        assertEquals(
            "21 FF 0B 4E 45 54 53 43 41 50 45 32 2E 30 03 01 00 00 00",
            hex(bytes.copyOfRange(loop - 3, loop + marker.length + 5)),
        )
        assertEquals(listOf(12, 12), delays(bytes), "两帧都该写 12cs（120 毫秒）")
    }

    @Test
    fun `指定循环次数会照写进字节`() {
        val bytes = GifEncoder(16, 16, maxColors = 4, loopCount = 3).encode(listOf(stripe(0xFFFFFF00.toInt())))
        val marker = "NETSCAPE2.0"
        val loop = asciiSpan(bytes, marker)
        assertTrue(loop > 0)
        val base = loop + marker.length
        assertEquals(3, bytes[base + 2].toInt() and 0xFF, "循环次数低字节")
        assertEquals(0, bytes[base + 3].toInt() and 0xFF, "循环次数高字节")
    }

    @Test
    fun `样品文件写到build目录供第三方解码器核对`() {
        val frames = (0 until 29).map { step ->
            GifFrame(
                IntArray(480 * 360) { index ->
                    val x = index % 480
                    val y = index / 480
                    (0xFF shl 24) or (((x + step * 7) and 0xFF) shl 16) or ((y and 0xFF) shl 8) or (step and 0xFF)
                },
                10,
            )
        }
        val bytes = GifEncoder(480, 360, maxColors = 128).encode(frames)
        val dir = java.io.File("build/gif-probe").apply { mkdirs() }
        java.io.File(dir, "from-core.gif").writeBytes(bytes)
        assertEquals(29, GifDecoder.decode(bytes).frames.size)
        assertEquals(290, GifDecoder.decode(bytes).totalDurationCs)
    }
}
