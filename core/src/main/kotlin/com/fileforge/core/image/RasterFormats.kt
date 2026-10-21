package com.fileforge.core.image

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * BMP 与 TIFF 的读写。这两种容器在这里做，是因为安卓的 `BitmapFactory` 只解 BMP 不解 TIFF，
 * 而 `Bitmap.compress` 两种都不出 —— 不在 `:core` 自己写，桌面判据那边就没有同一份实现可核。
 *
 * 口径：BMP 一律 24 位、不保透明（透明处铺白，与 JPG 那条同一条规矩）；
 * TIFF 保透明（RGBA + ExtraSamples=2），没有透明像素时写 RGB 省一半。
 */
object RasterFormats {

    data class Raster(val width: Int, val height: Int, val argb: IntArray)

    // ---------------------------------------------------------------- BMP

    /** 24 位、自下而上、每行按 4 字节对齐。透明像素先铺到白底上。 */
    fun encodeBmp(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE) { "BMP 尺寸 ${width}×$height 超出范围" }
        require(argb.size == width * height) { "像素数 ${argb.size} 跟 $width×$height 对不上" }
        val rowBytes = (width * 3 + 3) / 4 * 4
        val imageSize = rowBytes * height
        val out = ByteArrayOutputStream(54 + imageSize)
        out.le16(0x4D42)                                  // "BM"
        out.le32(54 + imageSize)
        out.le16(0); out.le16(0)
        out.le32(54)
        out.le32(40)
        out.le32(width); out.le32(height)                 // 正高度 = 自下而上
        out.le16(1); out.le16(24)
        out.le32(0); out.le32(imageSize)
        out.le32(2835); out.le32(2835)
        out.le32(0); out.le32(0)
        val padding = ByteArray(rowBytes - width * 3)
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in 0 until width) {
                val color = overWhite(argb[row + x])
                out.write(color and 0xFF)                 // B
                out.write(color ushr 8 and 0xFF)          // G
                out.write(color ushr 16 and 0xFF)         // R
            }
            out.write(padding)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- TIFF

    /** 基线 TIFF：8 位每通道、Planar=1、单条带；有透明就写 RGBA，否则 RGB。 */
    fun encodeTiff(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE) { "TIFF 尺寸 ${width}×$height 超出范围" }
        require(argb.size == width * height) { "像素数 ${argb.size} 跟 $width×$height 对不上" }
        val samples = if (argb.any { (it ushr 24) != 0xFF }) 4 else 3
        val bits = if (samples == 4) intArrayOf(8, 8, 8, 8) else intArrayOf(8, 8, 8)
        val tagCount = if (samples == 4) 12 else 11
        val bitsOffset = 8 + 2 + tagCount * 12 + 4
        val pixelOffset = bitsOffset + bits.size * 2
        val dataSize = width * height * samples

        val out = ByteArrayOutputStream(pixelOffset + dataSize)
        out.write('I'.code); out.write('I'.code)
        out.le16(42); out.le32(8)

        out.le16(tagCount)
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            out.le16(tag); out.le16(type); out.le32(count); out.le32(value)
        }
        fun shortEntry(tag: Int, value: Int) {
            out.le16(tag); out.le16(3); out.le32(1); out.le16(value); out.le16(0)
        }
        entry(256, 4, 1, width)
        entry(257, 4, 1, height)
        entry(258, 3, bits.size, bitsOffset)
        shortEntry(259, 1)                               // 不压缩
        shortEntry(262, 2)                               // RGB
        entry(273, 4, 1, pixelOffset)
        shortEntry(277, samples)
        entry(278, 4, 1, height)
        entry(279, 4, 1, dataSize)
        shortEntry(284, 1)                               // PlanarConfiguration = chunky
        if (samples == 4) shortEntry(338, 2)             // ExtraSamples = unassociated alpha
        shortEntry(339, 1)                               // SampleFormat = unsigned
        out.le32(0)                                      // 没有下一个 IFD

        bits.forEach { out.le16(it) }
        for (pixel in argb) {
            out.write(pixel ushr 16 and 0xFF)
            out.write(pixel ushr 8 and 0xFF)
            out.write(pixel and 0xFF)
            if (samples == 4) out.write(pixel ushr 24 and 0xFF)
        }
        return out.toByteArray()
    }

    /**
     * 解基线 TIFF：不压缩 / PackBits / Deflate（含 predictor=2），8 位每通道、chunky。
     * LZW 与 JPEG-in-TIFF 还没实现，直说而不是给一张错色图。方向标记（274）在这里就转正。
     */
    fun decodeTiff(bytes: ByteArray): Raster {
        if (bytes.size < 8) throw IllegalArgumentException("这份 TIFF 太短")
        val little = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> throw IllegalArgumentException("不是 TIFF（头两个字节既不是 II 也不是 MM）")
        }
        fun u8(at: Int): Int = bytes[at].toInt() and 0xFF
        fun u16(at: Int): Int = if (little) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)
        fun u32(at: Int): Long {
            val a = u8(at).toLong(); val b = u8(at + 1).toLong()
            val c = u8(at + 2).toLong(); val d = u8(at + 3).toLong()
            return if (little) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        val magic = u16(2)
        if (magic != 42) throw IllegalArgumentException("不是基线 TIFF（魔数 $magic）")
        val ifd = u32(4).toInt()
        if (ifd < 8 || ifd + 2 > bytes.size) throw IllegalArgumentException("TIFF 的 IFD 偏移越界")
        val count = u16(ifd)
        if (ifd + 2 + count * 12 + 4 > bytes.size) throw IllegalArgumentException("TIFF 的 IFD 长度越界")

        class Field(val type: Int, val count: Int, val valueAt: Int)
        val fields = HashMap<Int, Field>()
        for (index in 0 until count) {
            val at = ifd + 2 + index * 12
            fields[u16(at)] = Field(u16(at + 2), u32(at + 4).toInt(), at + 8)
        }
        fun field(tag: Int) = fields[tag]
        fun ints(tag: Int): IntArray? {
            val f = field(tag) ?: return null
            val unit = when (f.type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9 -> 4; else -> throw IllegalArgumentException("TIFF 字段 $tag 的类型 ${f.type} 不认识") }
            val size = unit * f.count
            val at = if (size <= 4) f.valueAt else u32(f.valueAt).toInt()
            if (at < 0 || at + size > bytes.size) throw IllegalArgumentException("TIFF 字段 $tag 的数据越界")
            return IntArray(f.count) { index ->
                when (unit) {
                    1 -> u8(at + index)
                    2 -> u16(at + index * 2)
                    else -> u32(at + index * 4).toInt()
                }
            }
        }
        fun one(tag: Int, fallback: Int = 0): Int = ints(tag)?.firstOrNull() ?: fallback

        val width = one(256)
        val height = one(257)
        if (width <= 0 || height <= 0) throw IllegalArgumentException("TIFF 没写宽高（$width×$height）")
        val compression = one(259, 1)
        val photometric = one(262, 2)
        val samples = one(277, 1)
        val bits = ints(258) ?: IntArray(samples) { 8 }
        val planar = one(284, 1)
        val predictor = one(317, 1)
        val stripOffsets = ints(273) ?: throw IllegalArgumentException("TIFF 没写 StripOffsets")
        val stripCounts = ints(279) ?: IntArray(stripOffsets.size) { -1 }
        if (planar != 1) throw IllegalArgumentException("TIFF 的 PlanarConfiguration=$planar，只支持 1（chunky）")
        if (samples !in 1..4) throw IllegalArgumentException("TIFF 每像素 $samples 个通道，只支持 1/3/4")
        if (bits.any { it != 8 }) throw IllegalArgumentException("TIFF 每通道位数是 ${bits.joinToString("/")}，只支持 8 位")
        if (stripOffsets.size != stripCounts.size) throw IllegalArgumentException("TIFF 的条带偏移与长度条数对不上")

        val raw = ByteArrayOutputStream()
        stripOffsets.forEachIndexed { index, offset ->
            val count = stripCounts[index]
            if (count < 0 || offset < 0 || offset + count > bytes.size) throw IllegalArgumentException("TIFF 第 ${index + 1} 条带越界")
            val body = bytes.copyOfRange(offset, offset + count)
            raw.write(when (compression) {
                1 -> body
                32773 -> unpackBits(body)
                8, 32946 -> inflate(body)
                5 -> throw IllegalArgumentException("TIFF 用 LZW 压缩，这一版还没实现")
                else -> throw IllegalArgumentException("TIFF 的压缩方式 $compression 还没支持")
            })
        }
        var data = raw.toByteArray()
        val rowBytes = width * samples
        val expected = rowBytes * height
        if (data.size < expected) throw IllegalArgumentException("TIFF 解出来的像素 ${data.size} 字节，少于 ${expected}")
        if (predictor == 2) data = undoPredictor(data, width, height, samples)

        val argb = IntArray(width * height)
        var at = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r: Int; val g: Int; val b: Int; val a: Int
                when (photometric) {
                    0 -> { val v = 255 - (data[at++].toInt() and 0xFF); r = v; g = v; b = v; a = 255 }
                    1 -> { val v = data[at++].toInt() and 0xFF; r = v; g = v; b = v; a = 255 }
                    else -> {
                        r = data[at++].toInt() and 0xFF
                        g = data[at++].toInt() and 0xFF
                        b = data[at++].toInt() and 0xFF
                        a = if (samples == 4) data[at++].toInt() and 0xFF else 255
                        if (samples == 2) at++
                    }
                }
                argb[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return orient(argb, width, height, one(274, 1))
    }

    private fun overWhite(pixel: Int): Int {
        val a = (pixel ushr 24) and 0xFF
        if (a == 255) return pixel
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        fun mix(channel: Int) = (channel * a + 255 * (255 - a) + 127) / 255
        return (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }

    private fun unpackBits(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size * 2)
        var at = 0
        while (at < body.size) {
            val n = body[at++].toInt()
            when {
                n >= 0 -> {
                    val length = n + 1
                    if (at + length > body.size) throw IllegalArgumentException("PackBits 数据截断")
                    out.write(body, at, length); at += length
                }
                n != -128 -> {
                    if (at >= body.size) throw IllegalArgumentException("PackBits 数据截断")
                    val length = 1 - n
                    repeat(length) { out.write(body[at].toInt()) }
                    at++
                }
            }
        }
        return out.toByteArray()
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(body)
            val out = ByteArrayOutputStream(body.size * 3)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0 && inflater.needsInput()) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun undoPredictor(data: ByteArray, width: Int, height: Int, samples: Int): ByteArray {
        val out = data.copyOf()
        val rowBytes = width * samples
        for (y in 0 until height) {
            val row = y * rowBytes
            for (i in samples until rowBytes) out[row + i] = (out[row + i] + out[row + i - samples]).toByte()
        }
        return out
    }

    /** TIFF 的 Orientation（274）：2/4/5/7 是镜像，3/6/8 是旋转。 */
    private fun orient(argb: IntArray, width: Int, height: Int, tag: Int): Raster {
        if (tag == 1) return Raster(width, height, argb)
        val swaps = tag in setOf(5, 6, 7, 8)
        val outW = if (swaps) height else width
        val outH = if (swaps) width else height
        val out = IntArray(argb.size)
        for (y in 0 until height) for (x in 0 until width) {
            val (dx, dy) = when (tag) {
                2 -> width - 1 - x to y
                3 -> width - 1 - x to height - 1 - y
                4 -> x to height - 1 - y
                5 -> y to x
                6 -> height - 1 - y to x
                7 -> height - 1 - y to width - 1 - x
                8 -> y to width - 1 - x
                else -> x to y
            }
            out[dy * outW + dx] = argb[y * width + x]
        }
        return Raster(outW, outH, out)
    }

    private const val MAX_EDGE = 12_000

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xFF); write(value ushr 8 and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(value: Int) {
        write(value and 0xFF); write(value ushr 8 and 0xFF)
        write(value ushr 16 and 0xFF); write(value ushr 24 and 0xFF)
    }
}
