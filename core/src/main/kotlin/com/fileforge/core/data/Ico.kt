package com.fileforge.core.data


class IcoException(message: String) : Exception(message)

/** 一张图标画面：top-down 的 ARGB 像素（与安卓 Bitmap 同一套顺序）。 */
class IcoImage(val width: Int, val height: Int, val argb: IntArray, val png: ByteArray? = null) {
    init {
        require(argb.size == width * height) { "像素数 ${argb.size} 跟 $width×$height 对不上" }
    }
}

/**
 * ICO 光标的读与写。
 *
 * 写侧一律用 **32 位 DIB（BGRA + AND 掩码）**，不用 PNG 内嵌：PNG-in-ICO 从 Vista 之后
 * 才被广泛认，Pillow 那种新工具确实会写 PNG，但老资源管理器与不少图标库直接打不开。
 * 图标是要发给别人用的，挑最不容易出事的那种写。
 *
 * 读侧两种都认：DIB 解成像素；PNG 内嵌的原样把字节交出去（安卓有现成的解码器，
 * 在 `:core` 里再实现一遍 PNG 解码是没必要的重复）。
 *
 * 两个格式上的坑专门处理了：
 *  - 目录里的宽高是**单字节**，256 记成 0 —— 判错的话 256 图标会被读成 0×0
 *  - DIB 的 `biHeight` 是**图像高 + 掩码高**，也就是真高的两倍
 */
object Ico {

    const val MAX_SIDE = 256
    private const val HEADER = 6
    private const val ENTRY = 16
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    /** 打成一份 .ico。画面按传入顺序进目录，尺寸重复的会保留（有的应用要同尺寸不同深度）。 */
    fun write(images: List<IcoImage>): ByteArray {
        if (images.isEmpty()) throw IcoException("没有可写进图标的画面")
        images.forEach { image ->
            require(image.width in 1..MAX_SIDE && image.height in 1..MAX_SIDE) {
                "图标只能是 1~$MAX_SIDE 的方形或矩形，这张是 ${image.width}×${image.height}"
            }
        }
        val bodies = images.map { dibBytes(it) }
        val out = java.io.ByteArrayOutputStream()
        out.write(u16(0)); out.write(u16(1)); out.write(u16(images.size))
        var offset = HEADER + ENTRY * images.size
        images.forEachIndexed { index, image ->
            val body = bodies[index]
            out.write(if (image.width >= MAX_SIDE) 0 else image.width)
            out.write(if (image.height >= MAX_SIDE) 0 else image.height)
            out.write(0)                                    // 调色板色数：32 位不走调色板
            out.write(0)                                    // 保留字节
            out.write(u16(1))                               // 平面数
            out.write(u16(32))                              // 位深
            out.write(u32(body.size))
            out.write(u32(offset))
            offset += body.size
        }
        bodies.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** DIB 载荷：BITMAPINFOHEADER + 自下而上的 BGRA + 全 0 的 AND 掩码。 */
    private fun dibBytes(image: IcoImage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(u32(40))
        out.write(i32(image.width))
        out.write(i32(image.height * 2))                    // 高 = 图像 + 掩码
        out.write(u16(1)); out.write(u16(32))
        out.write(u32(0))                                   // BI_RGB，不压缩
        out.write(u32(image.width * image.height * 4))
        out.write(u32(0)); out.write(u32(0)); out.write(u32(0)); out.write(u32(0))
        for (y in image.height - 1 downTo 0) {
            val row = y * image.width
            for (x in 0 until image.width) {
                val color = image.argb[row + x]
                out.write(color and 0xFF)                   // B
                out.write(color shr 8 and 0xFF)             // G
                out.write(color shr 16 and 0xFF)            // R
                out.write(color shr 24 and 0xFF)            // A
            }
        }
        // AND 掩码：每行按 4 字节对齐。全 0 表示"不额外抠透明"，透明度由 alpha 通道管
        val maskRow = (image.width + 31) / 32 * 4
        repeat(image.height) { out.write(ByteArray(maskRow)) }
        return out.toByteArray()
    }

    /** 目录项，供"这个图标里有哪几个尺寸"这类展示用。 */
    class Entry(
        val width: Int,
        val height: Int,
        val bitCount: Int,
        val isPng: Boolean,
        val offset: Int,
        val size: Int,
    )

    fun directory(bytes: ByteArray): List<Entry> {
        if (bytes.size < HEADER) throw IcoException("这份文件太短，不是图标")
        val type = u16(bytes, 2)
        if (u16(bytes, 0) != 0 || (type != 1 && type != 2)) throw IcoException("不是 ICO 光标/图标格式")
        val count = u16(bytes, 4)
        if (count == 0 || HEADER + count * ENTRY > bytes.size) throw IcoException("图标目录长度不对")
        return (0 until count).map { index ->
            val at = HEADER + index * ENTRY
            val size = u32(bytes, at + 8).toInt()
            val offset = u32(bytes, at + 12).toInt()
            Entry(
                width = if (bytes[at].toInt() == 0) MAX_SIDE else bytes[at].toInt() and 0xFF,
                height = if (bytes[at + 1].toInt() == 0) MAX_SIDE else bytes[at + 1].toInt() and 0xFF,
                bitCount = u16(bytes, at + 6),
                isPng = offset + 4 <= bytes.size && PNG_MAGIC.indices.all { bytes[offset + it] == PNG_MAGIC[it] },
                offset = offset,
                size = size,
            )
        }
    }

    /** 读出所有画面。PNG 内嵌的那条只交出原始字节（由安卓的解码器去解），DIB 的在这里解成像素。 */
    fun read(bytes: ByteArray): List<IcoImage> = directory(bytes).map { entry ->
        if (entry.offset + entry.size > bytes.size) throw IcoException("图标数据超出文件长度")
        val body = bytes.copyOfRange(entry.offset, entry.offset + entry.size)
        when {
            entry.isPng -> IcoImage(entry.width, entry.height, IntArray(entry.width * entry.height), png = body)
            else -> decodeDib(entry, body)
        }
    }

    private fun decodeDib(entry: Entry, body: ByteArray): IcoImage {
        if (body.size < 40) throw IcoException("DIB 头都不完整")
        val width = i32(body, 4)
        val height = i32(body, 8) / 2                                      // 存的是图像高 + 掩码高
        if (entry.bitCount != 32 && entry.bitCount != 24) {
            throw IcoException("只支持 24/32 位图标，这份是 ${entry.bitCount} 位（带调色板的那种要单独处理）")
        }
        if (i32(body, 16) != 0) throw IcoException("这个图标用了压缩的 DIB，解不了")
        val bytesPerPixel = entry.bitCount / 8
        val stride = (width * bytesPerPixel + 3) / 4 * 4
        // 像素区的末尾不是文件末尾：后面还跟着 AND 掩码。自下而上的行号必须以像素区算，
        // 拿 body.size 当底就会把掩码的 0 当成一排像素读进来
        val pixelEnd = 40 + stride * height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = pixelEnd - (y + 1) * stride
            if (row < 40) throw IcoException("图标像素区比声明的短")
            for (x in 0 until width) {
                val at = row + x * bytesPerPixel
                val b = body[at].toInt() and 0xFF
                val g = body[at + 1].toInt() and 0xFF
                val r = body[at + 2].toInt() and 0xFF
                val a = if (bytesPerPixel == 4) body[at + 3].toInt() and 0xFF else 0xFF
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        applyAndMask(body, pixels, width, height, 40 + stride * height)
        return IcoImage(width, height, pixels)
    }

    /** AND 掩码位为 1 的地方是透明：老图标没有 alpha 通道，全靠它抠形状。 */
    private fun applyAndMask(body: ByteArray, pixels: IntArray, width: Int, height: Int, start: Int) {
        val maskRow = (width + 31) / 32 * 4
        if (start < 40 || start + maskRow * height > body.size) return
        for (y in 0 until height) {
            for (x in 0 until width) {
                val byte = body[start + y * maskRow + x / 8]
                if (byte.toInt() and (1 shl (7 - x % 8)) != 0) pixels[y * width + x] = 0
            }
        }
    }

    /** 目录里有哪些尺寸，给界面显示。 */
    fun sizes(bytes: ByteArray): List<String> = directory(bytes).map { "${it.width}×${it.height}" }.distinct()

    private fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
    private fun u32(value: Int) = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )
    private fun i32(value: Int) = u32(value)

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun i32(b: ByteArray, at: Int) = u32(b, at).toInt()
}
