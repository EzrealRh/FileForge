package com.fileforge.core

import com.fileforge.core.gif.GifDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * 夹具由 tools/make_gif_fixtures.py 用 Pillow 生成，真值像素也是 Pillow 自己解出来的。
 * 也就是说这里比对的是"另一个实现看到什么"，不是照着自家解码器反推的期望。
 */
class GifFixtureTest {

    private class Truth(val width: Int, val height: Int, val delays: List<Int>, val frames: List<IntArray>)

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("gif/$name").use { input ->
            requireNotNull(input) { "缺少夹具 gif/$name，先跑 python tools/make_gif_fixtures.py" }
            ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        }

    private fun truth(name: String): Truth {
        val bytes = resource("$name.gifx")
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        val width = u16(4)
        val height = u16(6)
        val frameCount = u16(8)
        val delayCount = u16(10)
        val delays = (0 until delayCount).map { u16(12 + it * 2) }
        var p = 12 + delayCount * 2
        val frames = ArrayList<IntArray>()
        repeat(frameCount) {
            val argb = IntArray(width * height)
            for (i in 0 until width * height) {
                val red = bytes[p].toInt() and 0xFF
                val green = bytes[p + 1].toInt() and 0xFF
                val blue = bytes[p + 2].toInt() and 0xFF
                val alpha = bytes[p + 3].toInt() and 0xFF
                argb[i] = if (alpha == 0) 0 else 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                p += 4
            }
            frames += argb
        }
        return Truth(width, height, delays, frames)
    }

    @Test
    fun `Pillow 生成的每个夹具都逐像素对得上`() {
        for (name in FIXTURES) {
            val expected = truth(name)
            val actual = GifDecoder.decode(resource("$name.gif"))

            assertEquals(expected.width, actual.width, "$name 宽度")
            assertEquals(expected.height, actual.height, "$name 高度")
            assertEquals(expected.frames.size, actual.frames.size, "$name 帧数")
            assertEquals(expected.delays, actual.frames.map { it.delayCs }, "$name 帧时长")

            for (index in expected.frames.indices) {
                val want = expected.frames[index]
                val got = actual.frames[index].argb
                assertEquals(want.size, got.size, "$name 第 $index 帧像素数")
                val firstBad = want.indices.firstOrNull { want[it] != got[it] }
                if (firstBad != null) {
                    val x = firstBad % expected.width
                    val y = firstBad / expected.width
                    assertEquals(
                        "0x" + want[firstBad].toUInt().toString(16),
                        "0x" + got[firstBad].toUInt().toString(16),
                        "$name 第 $index 帧 ($x,$y)，整帧差异像素 ${want.indices.count { want[it] != got[it] }} 个",
                    )
                }
            }
        }
    }

    @Test
    fun `颜色数在 GIF 上限内就必须无损重编`() {
        for (name in FIXTURES) {
            val source = GifDecoder.decode(resource("$name.gif"))
            val distinct = HashSet<Int>().apply { source.frames.forEach { addAll(it.argb.toList()) } }
            val reencoded = GifDecoder.decode(
                com.fileforge.core.gif.GifEncoder(source.width, source.height, source.loopCount, 256)
                    .encode(source.frames),
            )
            assertEquals(source.frames.size, reencoded.frames.size, "$name 帧数")
            assertEquals(source.width, reencoded.width, "$name 宽度")
            assertEquals(source.height, reencoded.height, "$name 高度")

            if (distinct.size > 256) {
                // rich.gif 跨帧有 792 种颜色，GIF 只能留 256 种，这里只要求误差受控
                var total = 0L
                var worst = 0
                var samples = 0
                for (frame in source.frames.indices) {
                    val want = source.frames[frame].argb
                    val got = reencoded.frames[frame].argb
                    for (i in want.indices) {
                        for (shift in 0..16 step 8) {
                            val delta = Math.abs(((want[i] shr shift) and 0xFF) - ((got[i] shr shift) and 0xFF))
                            total += delta
                            if (delta > worst) worst = delta
                        }
                        samples++
                    }
                }
                assertTrue(total.toDouble() / (samples * 3) <= 8.0, "$name 平均色差 ${(total.toDouble() / (samples * 3))}")
                assertTrue(worst <= 64, "$name 最大色差 $worst")
            } else {
                for (frame in source.frames.indices) {
                    val want = source.frames[frame].argb
                    val got = reencoded.frames[frame].argb
                    val different = want.indices.count { want[it] != got[it] }
                    assertEquals(0, different, "$name 第 $frame 帧有 $different 个像素变色")
                }
            }
        }
    }

    private companion object {
        val FIXTURES = listOf("two_frames", "interlaced", "non_square_single", "rich")
    }
}
