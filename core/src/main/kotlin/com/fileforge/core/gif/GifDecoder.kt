package com.fileforge.core.gif

/** GIF 解析失败。 */
class GifException(message: String) : Exception(message)

/**
 * 一帧已经按 GIF 的 disposal 规则合成成完整画布后的像素，alpha=0 表示透明。
 * [delayCs] 是百分之一秒为单位的停留时间。
 */
data class GifFrame(val argb: IntArray, val delayCs: Int)

data class GifImage(
    val width: Int,
    val height: Int,
    val frames: List<GifFrame>,
    val loopCount: Int,
    /** 超过 pixelBudget 时后面的帧被丢掉，调用方要如实告诉用户只处理了前面一部分。 */
    val truncated: Boolean = false,
) {
    val totalDurationCs: Int get() = frames.sumOf { it.delayCs }
    val averageFps: Float get() = if (totalDurationCs <= 0) 0f else frames.size * 100f / totalDurationCs
}

/**
 * GIF89a 解码器：块结构 + LZW + 交错 + disposal 合成。
 * 对外只给"每帧都是完整画布"，上层不用关心增量帧和局部调色板。
 */
object GifDecoder {

    private const val EXTENSION = 0x21
    private const val IMAGE = 0x2C
    private const val TRAILER = 0x3B

    /** 单帧像素上限，防畸形文件把数组开爆。 */
    private const val MAX_FRAME_PIXELS = 64L * 1024 * 1024

    private val interlaceStarts = intArrayOf(0, 4, 2, 1)
    private val interlaceSteps = intArrayOf(8, 8, 4, 2)

    /**
     * 每帧都是一整张画布，帧数 x 像素数直接决定内存。用 [pixelBudget] 限制总量，
     * 超了就停在已解出的帧上，避免"打开一个 200 帧的 GIF 就把应用撑爆"。
     */
    fun decode(bytes: ByteArray, pixelBudget: Int = Int.MAX_VALUE): GifImage {
        if (bytes.size < 13) throw GifException("文件太小，不像 GIF")
        val magic = String(bytes, 0, 6, Charsets.ISO_8859_1)
        if (magic != "GIF87a" && magic != "GIF89a") throw GifException("GIF 头不对：$magic")

        var p = 6
        val width = u16(bytes, p)
        val height = u16(bytes, p + 2)
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
            throw GifException("尺寸异常 ${width}x$height")
        }
        val screenFlags = bytes[p + 4].toInt() and 0xFF
        val background = bytes[p + 5].toInt() and 0xFF
        p += 7

        var globalTable: IntArray? = null
        if (screenFlags and 0x80 != 0) {
            val entries = 2 shl (screenFlags and 0x07)
            globalTable = readColorTable(bytes, p, entries)
            p += entries * 3
        }

        var loopCount = 1
        var truncated = false
        val canvasPixels = width.toLong() * height.toLong()
        var delayCs = 0
        var disposal = 0
        var transparentIndex = -1
        val frames = ArrayList<GifFrame>()
        val canvas = IntArray(width * height)

        while (p < bytes.size) {
            when (val block = bytes[p].toInt() and 0xFF) {
                TRAILER -> p = bytes.size

                EXTENSION -> {
                    if (p + 2 >= bytes.size) throw GifException("文件在扩展块头处结束")
                    when (val label = bytes[p + 1].toInt() and 0xFF) {
                        0xF9 -> {
                            val size = bytes[p + 2].toInt() and 0xFF
                            if (p + 3 + size > bytes.size) throw GifException("图形控制块被截断")
                            val flags = bytes[p + 3].toInt() and 0xFF
                            delayCs = u16(bytes, p + 4)
                            transparentIndex = if (flags and 0x01 != 0) bytes[p + 6].toInt() and 0xFF else -1
                            disposal = (flags shr 2) and 0x07
                            p += 3 + size + 1
                        }
                        0xFF -> {
                            // 结构是 21 FF <标识长度> <标识> <长度> <数据> 00，子块链从标识长度字节开始走
                            val identifier = p + 3
                            if (isNetscapeLoop(bytes, identifier) && identifier + 15 < bytes.size) {
                                loopCount = u16(bytes, identifier + 13)
                            }
                            p = skipSubBlocks(bytes, p + 2)
                        }
                        else -> {
                            if (label == 0x01) {
                                disposal = 0
                                transparentIndex = -1
                                delayCs = 0
                            }
                            p = skipSubBlocks(bytes, p + 2)
                        }
                    }
                }

                IMAGE -> {
                    if (p + 10 > bytes.size) throw GifException("文件在图像块头处结束")
                    val left = u16(bytes, p + 1)
                    val top = u16(bytes, p + 3)
                    val frameWidth = u16(bytes, p + 5)
                    val frameHeight = u16(bytes, p + 7)
                    val flags = bytes[p + 9].toInt() and 0xFF
                    var q = p + 10

                    val table = if (flags and 0x80 != 0) {
                        val entries = 2 shl (flags and 0x07)
                        val local = readColorTable(bytes, q, entries)
                        q += entries * 3
                        local
                    } else {
                        globalTable ?: throw GifException("帧没有局部调色板，而全局调色板缺失")
                    }
                    if (frameWidth <= 0 || frameHeight <= 0) throw GifException("帧尺寸异常")
                    val framePixels = frameWidth.toLong() * frameHeight.toLong()
                    if (framePixels > Int.MAX_VALUE || framePixels > MAX_FRAME_PIXELS) {
                        throw GifException("帧尺寸异常 ${frameWidth}x$frameHeight")
                    }

                    val indices = Lzw.minDecode(bytes, q, framePixels.toInt())
                    q = skipSubBlocks(bytes, q + 1 + compressedLength(bytes, q + 1))

                    val argb = IntArray(framePixels.toInt())
                    for (i in indices.indices) {
                        val index = indices[i]
                        if (index !in table.indices) throw GifException("索引 $index 超出调色板 ${table.size} 项")
                        argb[i] = if (index == transparentIndex) 0 else 0xFF000000.toInt() or table[index]
                    }
                    composite(canvas, width, height, left, top, frameWidth, frameHeight, argb, flags and 0x40 != 0)
                    if ((frames.size + 1).toLong() * canvasPixels > pixelBudget) {
                        truncated = true
                        p = bytes.size
                        break
                    }
                    frames += GifFrame(canvas.copyOf(), delayCs)
                    val previous = if (disposal == 3) canvas.copyOf() else null
                    when (disposal) {
                        2 -> erase(canvas, width, height, left, top, frameWidth, frameHeight, backgroundPixel(globalTable, background, transparentIndex))
                        3 -> previous?.copyInto(canvas)
                    }

                    delayCs = 0
                    disposal = 0
                    transparentIndex = -1
                    p = q
                }

                else -> throw GifException("遇到未知块 0x${block.toString(16)}")
            }
        }

        if (frames.isEmpty()) throw GifException("这个 GIF 里没有帧")
        return GifImage(width, height, frames, loopCount, truncated)
    }

    /** 帧画布左上角对齐，透明像素保留画布已有内容，结果写回画布。 */
    private fun composite(
        canvas: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        frameWidth: Int,
        frameHeight: Int,
        argb: IntArray,
        interlaced: Boolean,
    ) {
        for (sourceRow in 0 until frameHeight) {
            val y = top + if (interlaced) interlacedRow(sourceRow, frameHeight) else sourceRow
            if (y !in 0 until height) continue
            for (x in 0 until frameWidth) {
                val pixel = argb[sourceRow * frameWidth + x]
                if (pixel != 0 && left + x in 0 until width) canvas[y * width + left + x] = pixel
            }
        }
    }

    /** 背景索引查全局调色板得到颜色；它正好是透明槽时保持透明。 */
    private fun backgroundPixel(globalTable: IntArray?, background: Int, transparentIndex: Int): Int {
        if (background == transparentIndex) return 0
        return globalTable?.getOrNull(background)?.let { 0xFF000000.toInt() or it } ?: 0
    }

    private fun erase(
        canvas: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        frameWidth: Int,
        frameHeight: Int,
        clearColor: Int,
    ) {
        for (y in 0 until frameHeight) {
            val row = top + y
            if (row !in 0 until height) continue
            for (x in 0 until frameWidth) if (left + x in 0 until width) canvas[row * width + left + x] = clearColor
        }
    }

    /** 文件里存的第 sourceRow 行对应画布上的哪一行。 */
    private fun interlacedRow(sourceRow: Int, height: Int): Int {
        var passed = 0
        for (pass in 0 until 4) {
            val start = interlaceStarts[pass]
            val step = interlaceSteps[pass]
            val rowsInPass = if (start >= height) 0 else (height - 1 - start) / step + 1
            if (sourceRow < passed + rowsInPass) return start + (sourceRow - passed) * step
            passed += rowsInPass
        }
        return sourceRow
    }

    private fun isNetscapeLoop(bytes: ByteArray, start: Int): Boolean =
        start + 16 <= bytes.size && String(bytes, start, 11, Charsets.ISO_8859_1) == "NETSCAPE2.0"

    private fun readColorTable(bytes: ByteArray, at: Int, entries: Int): IntArray {
        if (at + entries * 3 > bytes.size) throw GifException("调色板被截断")
        return IntArray(entries) { i ->
            val o = at + i * 3
            ((bytes[o].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        }
    }

    /** 从 at 开始的子块链一共占了多少字节（不含结尾 0）。 */
    private fun compressedLength(bytes: ByteArray, at: Int): Int {
        var p = at
        var total = 0
        while (p < bytes.size) {
            val size = bytes[p].toInt() and 0xFF
            if (size == 0) return total
            total += size + 1
            p += size + 1
        }
        throw GifException("子块链被截断")
    }

    private fun skipSubBlocks(bytes: ByteArray, at: Int): Int = at + compressedLength(bytes, at) + 1

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
}

/**
 * GIF 的 LZW。码字按位从每个字节的低位往高位塞，这是 GIF 与多数压缩格式不同的地方。
 */
internal object Lzw {

    fun minDecode(bytes: ByteArray, at: Int, expected: Int): IntArray {
        val minCodeSize = bytes.getOrNull(at)?.toInt()?.and(0xFF)
            ?: throw GifException("缺 LZW 初始码长")
        if (minCodeSize < 2 || minCodeSize > 8) throw GifException("LZW 初始码长异常 $minCodeSize")

        var length = 0
        var p = at + 1
        while (p < bytes.size) {
            val size = bytes[p].toInt() and 0xFF
            if (size == 0) break
            length += size
            p += size + 1
        }
        val data = ByteArray(length)
        var written = 0
        var q = at + 1
        while (q < bytes.size) {
            val size = bytes[q].toInt() and 0xFF
            if (size == 0) break
            bytes.copyInto(data, written, q + 1, q + 1 + size)
            written += size
            q += size + 1
        }
        return Decoder(minCodeSize).decode(data, expected)
    }

    internal class Decoder(private val minCodeSize: Int) {
        private val clearCode = 1 shl minCodeSize
        private val endCode = clearCode + 1
        private val prefix = IntArray(4096)
        private val suffix = IntArray(4096)
        private val stack = IntArray(4096)
        private var codeSize = minCodeSize + 1
        private var nextCode = endCode + 1
        private var stackTop = 0

        fun decode(data: ByteArray, expected: Int): IntArray {
            val out = IntArray(expected)
            val reader = BitReader(data)
            var written = 0
            var oldCode = -1

            while (written < expected) {
                val code = reader.read(codeSize)
                if (code < 0) break
                if (code == clearCode) {
                    codeSize = minCodeSize + 1
                    nextCode = endCode + 1
                    oldCode = -1
                    continue
                }
                if (code == endCode) break

                if (oldCode < 0) {
                    if (code >= clearCode) throw GifException("清码之后第一个码不是颜色索引")
                    out[written++] = code
                    oldCode = code
                    continue
                }

                var tail = -1
                val first: Int = when {
                    code < nextCode -> pushChain(code)
                    code == nextCode -> {
                        // 编码端会发出"下一个即将入字典的码"，它的序列是 seq(oldCode) 再接上 oldCode 的首个索引
                        val oldFirst = pushChain(oldCode)
                        tail = oldFirst
                        oldFirst
                    }
                    else -> throw GifException("码字 $code 超前于字典 $nextCode")
                }

                while (stackTop > 0 && written < expected) out[written++] = stack[--stackTop]
                if (tail >= 0 && written < expected) out[written++] = tail

                if (nextCode < 4096) {
                    prefix[nextCode] = oldCode
                    suffix[nextCode] = first
                    nextCode++
                    if (nextCode >= (1 shl codeSize) && codeSize < 12) codeSize++
                }
                oldCode = code
            }
            return if (written == expected) out else out.copyOf(written)
        }

        /** 把 code 的索引序列倒着压进栈，返回它的首个索引。 */
        private fun pushChain(code: Int): Int {
            stackTop = 0
            var current = code
            while (current > endCode) {
                stack[stackTop++] = suffix[current]
                current = prefix[current]
            }
            stack[stackTop++] = current
            return current
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bytePosition = 0
        private var buffer = 0
        private var bits = 0

        fun read(count: Int): Int {
            while (bits < count) {
                if (bytePosition >= data.size) {
                    if (bits == 0) return -1
                    break
                }
                buffer = buffer or ((data[bytePosition++].toInt() and 0xFF) shl bits)
                bits += 8
            }
            if (bits < count) {
                val value = buffer and ((1 shl bits) - 1)
                buffer = 0
                bits = 0
                return value
            }
            val value = buffer and ((1 shl count) - 1)
            buffer = buffer shr count
            bits -= count
            return value
        }
    }

    /**
     * 编码成一串索引，返回 LZW 压缩后的字节。
     * 字典写满 4096 就发清码重新开始，这是 GIF 编码器的通行做法。
     */
    fun encode(indices: IntArray, indexCount: Int, minCodeSize: Int): ByteArray {
        require(indexCount > 0) { "没有像素可编码" }
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = endCode + 1

        val table = HashMap<Long, Int>(8192)
        val bits = BitWriter()
        bits.put(clearCode, codeSize)

        var current = indices[0] and 0xFF
        var shift = 1
        while (shift < indexCount) {
            val next = indices[shift]
            require(next in 0 until (1 shl minCodeSize)) { "索引 $next 超出 $minCodeSize 位调色板" }
            val key = (current.toLong() shl 8) or next.toLong()
            val found = table[key]
            if (found != null) {
                current = found
                shift++
                continue
            }
            require(current < nextCode) { "待编码的码字 $current 还没进字典" }
            bits.put(current, codeSize)
            if (nextCode == 4096) {
                // 表满了：先按当前码宽发清码，再从头开始。这一对的映射丢弃即可。
                bits.put(clearCode, codeSize)
                table.clear()
                codeSize = minCodeSize + 1
                nextCode = endCode + 1
            } else {
                // 加宽要按"即将分配的码"判断，先加宽再写表；晚一步就会让解码器少算一位。
                if (nextCode >= (1 shl codeSize) && codeSize < 12) codeSize++
                table[key] = nextCode
                nextCode++
            }
            current = next
            shift++
        }
        bits.put(current, codeSize)
        bits.put(endCode, codeSize)
        bits.flush()
        return bits.toByteArray()
    }

    private class BitWriter {
        private val out = ArrayList<Byte>(4096)
        private var buffer = 0
        private var bits = 0

        fun put(code: Int, size: Int) {
            buffer = buffer or ((code and ((1 shl size) - 1)) shl bits)
            bits += size
            while (bits >= 8) {
                out.add((buffer and 0xFF).toByte())
                buffer = buffer shr 8
                bits -= 8
            }
        }

        fun flush() {
            if (bits > 0) {
                out.add((buffer and 0xFF).toByte())
                buffer = 0
                bits = 0
            }
        }

        fun toByteArray(): ByteArray {
            val result = ByteArray(out.size)
            for (i in out.indices) result[i] = out[i]
            return result
        }
    }
}
