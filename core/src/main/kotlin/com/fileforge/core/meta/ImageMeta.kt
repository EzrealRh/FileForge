package com.fileforge.core.meta

import com.fileforge.core.util.SizeInput
import java.io.ByteArrayOutputStream

/** 容器里的一段（JPEG 的标记段 / PNG 的块）。只记原始字节区间，读写都照抄。 */
data class Piece(val name: String, val from: Int, val to: Int, val identifying: Boolean) {
    val bytes: Int get() = to - from
}

/** 一张图的元数据体检结果。 */
data class MetaReport(
    val container: ImageMeta.Container?,
    val fields: List<Pair<String, String>>,
    val pieces: List<Piece>,
) {
    val identifying: List<Piece> get() = pieces.filter { it.identifying }
    val nothingToClean: Boolean get() = container == null || identifying.isEmpty()
    val gps: String? get() = fields.firstOrNull { it.first == "GPS 位置" }?.second
    /** 界面直接逐行显示用。 */
    val lines: List<String> get() = fields.map { (key, value) -> "$key：$value" }
    /** 清完大概能省多少字节 —— 让用户在动手之前就知道值不值。 */
    val saving: Int get() = identifying.sumOf { it.bytes }

    /** 要扔掉的段都列出来：段名 + 大小。动手之前得让人看清扔了什么。 */
    val removalNote: String
        get() = if (identifying.isEmpty()) "没有可清理的身份信息"
        else identifying.joinToString("、") { "${it.name} ${SizeInput.format(it.bytes.toLong())}" }

    /**
     * EXIF 里的方向标签（1 才是"正着放"）。
     *
     * 清理会连 EXIF 一起丢掉，而靠这个标签摆放的照片在相册里就会横过来 ——
     * 又重又常见，所以界面上必须提前说，而不是让用户事后发现照片躺下了。
     */
    val orientation: Int? get() = fields.firstOrNull { it.first == "方向" }?.second
        ?.trim()?.substringBefore(' ')?.toIntOrNull()

    /** 清掉之后画面有可能变方向的文件要特别提醒。 */
    val willLoseRotation: Boolean get() = (orientation ?: 1) != 1


    /**
     * 为什么现在不该清。null 表示可以清。
     *
     * 和 [clean] 的退回条件必须一致：一个管文案、一个管字节，谁先改谁就得被
     * `ImageMetaTest` 里那条一致性断言逮住。
     */
    val cleanBlocker: String?
        get() = when {
            container == null -> "只有 JPEG 和 PNG 能做元数据清理"
            identifying.isEmpty() -> "这张本来就没有可清理的元数据"
            pieces.none { it.name == terminator } -> "文件缺结束标记，看着是残档，不重组"
            else -> null
        }

    private val terminator: String? get() = when (container) {
        ImageMeta.Container.Jpeg -> "EOI"
        ImageMeta.Container.Png -> "IEND"
        null -> null
    }

}

/**
 * 图片元数据的查看与清理。
 *
 * 只支持 JPEG 与 PNG：它们是"发出去被人看到机型和拍摄地"的主要载体，而且清理能做到
 * **段级照抄、一个像素都不重编码**。HEIC / WebP / TIFF 的容器规则不一样，
 * 没做就直接拒，不拿一套解析器硬套出一张坏图。
 *
 * 拆段一律**记原始字节区间**，重组时从原数组照抄 —— 不重算 JPEG 的长度字段、
 * 不重算 PNG 的 CRC。这是唯一能保证"清完还打得开、画质一点没动"的写法。
 */
object ImageMeta {

    enum class Container(val label: String) { Jpeg("JPEG"), Png("PNG") }

    fun container(bytes: ByteArray): Container? = when {
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Container.Jpeg
        bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte() -> Container.Png
        else -> null
    }

    fun pieces(bytes: ByteArray): List<Piece> = when (container(bytes)) {
        Container.Jpeg -> jpegPieces(bytes)
        Container.Png -> pngPieces(bytes)
        null -> emptyList()
    }

    fun report(bytes: ByteArray): MetaReport {
        val which = container(bytes) ?: return MetaReport(null, emptyList(), emptyList())
        val found = pieces(bytes)
        val fields = ArrayList<Pair<String, String>>()
        if (which == Container.Jpeg) {
            exifPayload(bytes, found)?.let { fields += Tiff.read(it) }
        } else {
            pngChunk(bytes, "eXIf")?.let { fields += Tiff.read(it) }
            pngTexts(bytes).forEach { fields += it }
        }
        return MetaReport(which, fields, found)
    }

    /**
     * 清掉身份信息，其余照抄。
     *
     * 两个保守之处：
     *  - 没有 EOI / IEND 的文件原样退回 —— 那是残档，硬重组只会把"能勉强打开"变成"打不开"
     *  - 一个身份段都没有时**不写文件**，让上层报"这张本来就没元数据"，而不是产出一份和源
     *    一模一样的假成品
     */
    fun clean(bytes: ByteArray): ByteArray? {
        val which = container(bytes) ?: error("只支持 JPEG 和 PNG 的元数据清理")
        val found = pieces(bytes)
        val terminator = if (which == Container.Jpeg) "EOI" else "IEND"
        if (found.none { it.name == terminator }) return null
        val keep = found.filterNot { it.identifying }
        if (keep.sumOf { it.bytes } == found.sumOf { it.bytes }) return null
        val out = ByteArrayOutputStream(bytes.size)
        keep.forEach { out.write(bytes, it.from, it.bytes) }
        return out.toByteArray()
    }

    /**
     * 只读文件开头这么多字节。
     *
     * 详情页只是把字段显示出来，为此把一张 20 MB 的照片整个搬进堆不值当：EXIF、ICC、
     * 文本块都在熵数据之前，1 MB 足够覆盖带大缩略图和厚 ICC 的文件。
     *
     * **但别拿这份前缀去决定要不要写文件** —— 判断"能不能清"要看得到 EOI / IEND，
     * 那在文件末尾。清理一律喂完整字节。
     */
    const val HEAD_BYTES = 1 shl 20

    fun head(file: java.io.File, limit: Int = HEAD_BYTES): ByteArray {
        val out = ByteArray(minOf(limit, file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        if (out.isEmpty()) return out
        file.inputStream().use { input ->
            var written = 0
            while (written < out.size) {
                val read = input.read(out, written, out.size - written)
                if (read < 0) break
                written += read
            }
            if (written < out.size) return out.copyOf(written)
        }
        return out
    }

    // ---- JPEG ---------------------------------------------------------------

    /**
     * 拆 JPEG 段。SOS 之后的熵编码数据整块算一段保留 —— 里面有压缩后的像素，
     * 动不得；找下一段时要把 `FF 00` 填充和 `FF D0-D7` 重启标记跳过去，
     * 否则会误把数据里的 `FF` 当段头，那张图就废了。
     */
    internal fun jpegPieces(bytes: ByteArray): List<Piece> {
        val out = ArrayList<Piece>()
        out += Piece("SOI", 0, 2, false)
        var i = 2
        while (i + 1 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                out += Piece(jpegMarkerName(marker), i, i + 2, false)
                i += 2
                continue
            }
            if (marker == 0xD9) {
                out += Piece("EOI", i, i + 2, false)
                return out                                     // EOI 之后的尾巴（有的工具在那儿塞注释）整块丢掉
            }
            if (i + 3 >= bytes.size) break
            val length = u16(bytes, i + 2)
            if (length < 2 || i + 2 + length > bytes.size) break
            if (marker == 0xDA) {
                val stop = endOfScan(bytes, i + 2 + length)
                out += Piece("SOS+图像数据", i, stop, false)
                i = stop
                continue
            }
            val payload = bytes.copyOfRange(i + 4, i + 2 + length)
            val name = jpegMarkerName(marker)
            out += Piece(name, i, i + 2 + length, identifying(marker, payload))
            i += 2 + length
        }
        return out
    }

    /** 熵数据到哪为止：第一个不是填充、不是重启标记的 `FF xx` 处（不吞掉它，留给下一轮）。 */
    private fun endOfScan(bytes: ByteArray, from: Int): Int {
        var j = from
        while (j + 1 < bytes.size) {
            if (bytes[j] == 0xFF.toByte()) {
                val next = bytes[j + 1].toInt() and 0xFF
                if (next != 0x00 && next != 0xFF && next !in 0xD0..0xD7) return j
            }
            j++
        }
        return bytes.size
    }

    /**
     * 哪些段算"身份信息"。保留的三类都是**影响显示而不是身份**的：
     * JFIF 的 APP0（只有密度与缩略偏移）、ICC 描述文件（删了照片会变色）、
     * Adobe 的 APP14（删了 CMYK 转出来的 JPEG 通道序会错）。
     * EXIF 连着它的内嵌缩略图一起丢 —— 缩略图是最常见的"以为删了其实还在"。
     */
    private fun identifying(marker: Int, payload: ByteArray): Boolean = when (marker) {
        0xE0 -> false
        0xE1 -> !payload.startsWith("adobe")           // EXIF 与 XMP 都在这里
        0xE2 -> !payload.startsWith("ICC_PROFILE")     // 非 ICC 的 APP2 丢，ICC 留
        0xEE -> false                                   // Adobe APP14：影响颜色
        in 0xE3..0xED -> true                           // Photoshop、Ducky 与各厂商私有段
        0xEF -> true
        0xFE -> true                                    // COM：作者、版权、处理说明常写在这
        else -> false
    }

    private fun jpegMarkerName(marker: Int) = when (marker) {
        0xE0 -> "JFIF (APP0)"
        0xE1 -> "APP1"
        0xE2 -> "APP2"
        0xED -> "Photoshop (APP13)"
        0xEE -> "Adobe (APP14)"
        0xEF -> "APP15"
        0xFE -> "注释 (COM)"
        in 0xC0..0xC2 -> "帧头 SOF"
        0xC4 -> "Huffman 表 (DHT)"
        0xDB -> "量化表 (DQT)"
        0xDD -> "Restart 间隔 (DRI)"
        0x01 -> "模板 (TEM)"
        0xD9 -> "EOI"
        0xDA -> "SOS"
        in 0xE0..0xEF -> "APP${marker - 0xE0}"
        // 没名字也要报个准数：清理清单里出现 "APP-14" 这种话，用户就再也不信这屏了
        else -> "标记 0x%02X".format(marker)
    }

    /** EXIF 的 TIFF 主体：APP1 段里跳过 `Exif\0\0` 六个字节。 */
    private fun exifPayload(bytes: ByteArray, pieces: List<Piece>): ByteArray? {
        val app1 = pieces.firstOrNull { it.name == "APP1" } ?: return null
        val payload = bytes.copyOfRange(app1.from + 4, app1.to)
        if (payload.size < 6) return null
        val head = payload.copyOfRange(0, 4)
        if (!(head[0] == 'E'.code.toByte() && head[1] == 'x'.code.toByte() &&
                head[2] == 'i'.code.toByte() && head[3] == 'f'.code.toByte())) return null
        return payload.copyOfRange(6, payload.size)
    }

    // ---- PNG ----------------------------------------------------------------

    /** 拆 PNG 块：块 = 长度 4 + 类型 4 + 数据 + CRC 4，整段原样保留才不用重算校验。 */
    internal fun pngPieces(bytes: ByteArray): List<Piece> {
        val out = ArrayList<Piece>()
        out += Piece("签名", 0, 8, false)
        var i = 8
        while (i + 8 <= bytes.size) {
            val length = i32(bytes, i)
            if (length < 0 || i + 12L + length > bytes.size) break
            val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
            out += Piece(type, i, i + 12 + length, type in PNG_IDENTIFYING)
            i += 12 + length
            if (type == "IEND") return out
        }
        return out
    }

    /**
     * PNG 里丢的块：文本类三兄弟（tEXt / iTXt / zTXt，键值随便写，最常见是作者和软件）、
     * eXIf（PIL 等会写）、tIME（最后修改时间）。
     * icCp / gAMA / cHRM / sTER 影响颜色与立体显示，一律留。
     */
    private val PNG_IDENTIFYING = setOf("tEXt", "iTXt", "zTXt", "eXIf", "tIME")

    private fun pngChunk(bytes: ByteArray, type: String): ByteArray? {
        var found: ByteArray? = null
        pngPieces(bytes).firstOrNull { it.name == type }?.let { piece ->
            found = bytes.copyOfRange(piece.from + 8, piece.to - 4)
        }
        return found
    }

    private fun pngTexts(bytes: ByteArray): List<Pair<String, String>> =
        pngPieces(bytes).filter { it.name in setOf("tEXt", "iTXt", "zTXt") }.map { piece ->
            val payload = bytes.copyOfRange(piece.from + 8, piece.to - 4)
            val key = payload.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.ISO_8859_1)
            "${piece.name} 文本块" to (key.ifBlank { "无键名" } + " · ${piece.bytes} 字节")
        }

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].toByte() }

    private fun u16(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun i32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
