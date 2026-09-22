package com.fileforge.core.gif

import com.fileforge.core.util.MedianCut
import java.io.ByteArrayOutputStream

/**
 * GIF89a 编码器。所有帧共用一张全局调色板；有透明像素时 0 号索引固定表示透明。
 * 传进来的帧应该是 [GifDecoder] 那样合成过的完整画布。
 */
class GifEncoder(
    private val width: Int,
    private val height: Int,
    private val loopCount: Int = 0,
    private val maxColors: Int = 256,
) {

    fun encode(frames: List<GifFrame>): ByteArray {
        require(frames.isNotEmpty()) { "没有帧可编码" }
        require(width in 1..65535 && height in 1..65535) { "尺寸超出 GIF 上限" }
        // 像素数不够时下面会静默按透明补，等于在画面上挖洞，必须在入口就挡住
        val pixelCount = width * height
        frames.forEachIndexed { index, frame ->
            require(frame.argb.size == pixelCount) {
                "第 ${index + 1} 帧有 ${frame.argb.size} 个像素，画布需要 $pixelCount 个"
            }
        }

        val sample = sampleColors(frames)
        val needsTransparency = sample.sawTransparent
        val offset = if (needsTransparency) 1 else 0
        val candidates = MedianCut.build(
            sample.colors,
            minOf(maxColors, MAX_PALETTE - offset).coerceAtLeast(1),
        ).let { if (it.isEmpty()) intArrayOf(BLACK) else it }

        val indexBits = colorTableBits(candidates.size + offset)
        val entries = 1 shl indexBits
        val table = IntArray(entries) { index ->
            if (index < offset) 0 else candidates.getOrElse(index - offset) { 0 }
        }
        val lookup = ColorLookup(candidates, offset)

        val out = ByteArrayOutputStream(1 shl 12)
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        out.le16(width)
        out.le16(height)
        out.write(0x80 or 0x70 or (indexBits - 1))
        out.write(0)
        out.write(0)
        for (color in table) {
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }
        writeLoopExtension(out, loopCount)

        val indices = IntArray(pixelCount)
        for (frame in frames) {
            for (i in 0 until pixelCount) {
                val pixel = frame.argb.getOrElse(i) { 0 }
                indices[i] = if (pixel == 0) 0 else lookup.indexOf(pixel)
            }

            // 透明槽是整段动画的属性：0 号位被让给透明后就不可能再表示真颜色，
            // 所以每帧都要带上标志位，否则"擦回背景"会把背景槽当黑色画出来。
            writeGraphicControl(out, frame.delayCs, needsTransparency)
            out.write(0x2C)
            out.le16(0)
            out.le16(0)
            out.le16(width)
            out.le16(height)
            out.write(0)

            val compressed = Lzw.encode(indices, pixelCount, maxOf(2, indexBits))
            out.write(maxOf(2, indexBits))
            var position = 0
            while (position < compressed.size) {
                val size = minOf(255, compressed.size - position)
                out.write(size)
                out.write(compressed, position, size)
                position += size
            }
            out.write(0)
        }

        out.write(0x3B)
        return out.toByteArray()
    }

    private fun writeLoopExtension(out: ByteArrayOutputStream, loop: Int) {
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.ISO_8859_1))
        out.write(0x03)
        out.write(0x01)
        out.le16(loop)
        out.write(0x00)
    }

    private fun writeGraphicControl(out: ByteArrayOutputStream, delayCs: Int, transparent: Boolean) {
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        // 位 2-4 是 disposal=2（画完恢复背景），位 0 是透明标志。
        // 每帧都是完整画布，所以一律 disposal=2：否则下一帧的镂空处会透出这一帧的画面。
        out.write(if (transparent) 0x09 else 0x08)
        out.le16(delayCs.coerceIn(0, 65535))
        out.write(0x00)
        out.write(0x00)
    }

    private fun sampleColors(frames: List<GifFrame>): Sample {
        val total = frames.sumOf { it.argb.size }
        val step = if (total <= SAMPLE_BUDGET) 1 else total / SAMPLE_BUDGET
        val colors = ArrayList<Int>(minOf(total, SAMPLE_BUDGET))
        var sawTransparent = false
        var counter = 0
        for (frame in frames) {
            for (pixel in frame.argb) {
                if (pixel == 0) {
                    sawTransparent = true
                } else if (counter % step == 0) {
                    colors.add(pixel and 0x00FFFFFF)
                }
                counter++
            }
        }
        return Sample(colors.toIntArray(), sawTransparent)
    }

    private data class Sample(val colors: IntArray, val sawTransparent: Boolean)

    companion object {
        private const val BLACK = 0x000000
        private const val MAX_PALETTE = 256
        private const val SAMPLE_BUDGET = 120_000

        /** 调色板项数必须是 2 的幂，且至少 4 项（LZW 初始码长最小为 2）。 */
        fun colorTableBits(colorsNeeded: Int): Int {
            var bits = 2
            while ((1 shl bits) < colorsNeeded && bits < 8) bits++
            return bits
        }
    }
}

/**
 * 截断位宽的最近色缓存：以 RGB 各削到 5 位当键，同格子里的像素共用一次全表搜索。
 * 单像素误差被限制在每通道 4 以内，比逐像素暴力搜快两个量级。
 */
internal class ColorLookup(private val candidates: IntArray, private val offset: Int) {

    private val cache = IntArray(32 * 32 * 32) { -1 }

    fun indexOf(rgb: Int): Int {
        val red = (rgb shr 16) and 0xFF
        val green = (rgb shr 8) and 0xFF
        val blue = rgb and 0xFF
        val key = ((red shr 3) shl 10) or ((green shr 3) shl 5) or (blue shr 3)
        val cached = cache[key]
        if (cached >= 0) return cached

        var best = 0
        var bestDistance = Long.MAX_VALUE
        for (index in candidates.indices) {
            val color = candidates[index]
            val dr = red - ((color shr 16) and 0xFF)
            val dg = green - ((color shr 8) and 0xFF)
            val db = blue - (color and 0xFF)
            val distance = dr.toLong() * dr * 299 + dg.toLong() * dg * 587 + db.toLong() * db * 114
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        val result = best + offset
        cache[key] = result
        return result
    }
}

private fun ByteArrayOutputStream.le16(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}
