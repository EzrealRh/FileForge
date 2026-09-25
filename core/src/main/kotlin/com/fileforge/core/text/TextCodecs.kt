package com.fileforge.core.text

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException

/** 文本编码选项。charsetName 直接交给 `Charset.forName`，Android 与桌面 JVM 都自带这些。 */
enum class TextEncoding(val label: String, val charsetName: String) {
    Utf8("UTF-8", "UTF-8"),
    Gb18030("GB18030（简体中文，最全）", "GB18030"),
    Gbk("GBK（简体中文）", "GBK"),
    Big5("Big5（繁体中文）", "Big5"),
    ShiftJis("Shift_JIS（日文）", "Shift_JIS"),
    Latin1("ISO-8859-1（西欧单字节）", "ISO-8859-1"),
    Utf16Le("UTF-16 小端", "UTF-16LE"),
    Utf16Be("UTF-16 大端", "UTF-16BE"),
    ;
}

/**
 * 编码转换的纯逻辑。为什么值得自己写一遍并且单测：转错的产物是**能打开的乱码**，
 * 用户不知道已经坏了 —— 所以"有多少字节解不出来"必须量化出来给他看，而不是默认成功。
 *
 * 三条判据都有夹具：
 *  - UTF-8 有严格语法，逐字节能判；所以"是不是 UTF-8"不用猜概率。
 *  - BOM 是三到四个字节的固定前缀，认它比认编码启发式可靠。
 *  - 解不开时统计替换字符（U+FFFD）个数 —— 0 才敢说"这份没丢内容"。
 */
object TextCodecs {

    /** 一次解码的完整结论。[replaced] 是解不出来的字节数，界面必须把它亮出来。 */
    data class Decoded(
        val text: String,
        val encoding: TextEncoding,
        val hadBom: Boolean,
        val replaced: Int,
    ) {
        val clean: Boolean get() = replaced == 0
    }

    /** 各编码的 BOM（UTF-16 的两个方向靠 BOM 区分，所以两个都登记）。 */
    private val boms = listOf(
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to TextEncoding.Utf8,
        byteArrayOf(-1, -2, 0, 0) to TextEncoding.Utf16Le,
        byteArrayOf(0, 0, -2, -1) to TextEncoding.Utf16Be,
        byteArrayOf(-1, -2) to TextEncoding.Utf16Le,
        byteArrayOf(-2, -1) to TextEncoding.Utf16Be,
    )

    fun available(encoding: TextEncoding): Boolean =
        runCatching { Charset.forName(encoding.charsetName) }.isSuccess

    /** 这份字节带的 BOM（没有返回 null）。长的先匹配，否则 UTF-16LE 的 4 字节 BOM 会被 2 字节抢走。 */
    fun detectBom(bytes: ByteArray): TextEncoding? {
        for ((bom, encoding) in boms.sortedByDescending { it.first.size }) {
            if (bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it] }) return encoding
        }
        return null
    }

    fun bomBytes(encoding: TextEncoding): ByteArray? = when (encoding) {
        TextEncoding.Utf8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        TextEncoding.Utf16Le -> byteArrayOf(-1, -2)
        TextEncoding.Utf16Be -> byteArrayOf(-2, -1)
        else -> null
    }

    /**
     * 严格 UTF-8 判定。规范里能出现的字节序列是死的：
     * 首字节决定后续必须是几个 0b10xxxxxx，且不允许最长式（overlong）、代理区、>U+10FFFF。
     * 这三条都查，所以 GBK 的中文（大量 0x81-0xFE 双字节）在这里会立刻露馅。
     */
    fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val extra: Int
            var code: Int
            when {
                b < 0x80 -> { i++; continue }
                b in 0xC2..0xDF -> { extra = 1; code = b and 0x1F }
                b in 0xE0..0xEF -> { extra = 2; code = b and 0x0F }
                b in 0xF0..0xF4 -> { extra = 3; code = b and 0x07 }
                else -> return false     // 0x80-0xBF 出现在首字节、0xC0/0xC1 是 overlong、0xF5+ 超出范围
            }
            if (i + extra >= bytes.size) return false
            for (step in 1..extra) {
                val next = bytes[i + step].toInt() and 0xFF
                // 第二字节范围会因首字节而变窄（E0 后必须 >= A0，ED 后必须 <= 9F，F0 后 >= 90，F4 后 <= 8F）
                val min = if (step == 1) secondLowBound(b) else 0x80
                val max = if (step == 1) secondHighBound(b) else 0xBF
                if (next < min || next > max) return false
                code = (code shl 6) or (next and 0x3F)
            }
            if (extra == 2 && code < 0x800) return false           // overlong
            if (extra == 3 && code < 0x10000) return false         // overlong
            if (code > 0x10FFFF) return false
            if (code in 0xD800..0xDFFF) return false               // 代理区不能直接编码
            i += extra + 1
        }
        return true
    }

    private fun secondLowBound(first: Int) = when (first) {
        0xE0 -> 0xA0
        0xF0 -> 0x90
        0xF4 -> 0x80
        else -> 0x80
    }

    private fun secondHighBound(first: Int) = when (first) {
        0xED -> 0x9F
        0xF4 -> 0x8F
        else -> 0xBF
    }

    /**
     * 按指定编码解码，并统计有多少字符解不出来。
     *
     * 不能直接用 `String(bytes, charset)` —— 它把坏字节静默换成 U+FFFD，于是"GBK 当 UTF-8 解"
     * 看着也成功了，只是内容全烂。这里先严格解，解不动再退回替换模式并把 U+FFFD 数出来。
     */
    fun decode(bytes: ByteArray, encoding: TextEncoding): Decoded {
        val bom = bomBytes(encoding)
        val hasBom = bom != null && bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it] }
        val body = if (hasBom) bytes.copyOfRange(bom.size, bytes.size) else bytes
        val charset = charsetOf(encoding)
        val strict = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body)).toString()
        }
        if (strict.isSuccess) return Decoded(strict.getOrThrow(), encoding, hasBom, 0)
        val lenient = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(body)).toString()
        return Decoded(lenient, encoding, hasBom, lenient.count { it == '\uFFFD' })
    }

    /**
     * 认编码：BOM 最优先，其次"严格 UTF-8 且真含非 ASCII"，否则只能退回调用方给的猜测。
     *
     * 注意**不会**声称认得出 GBK / GB18030 / Big5 —— 它们的字节序列互不排斥，
     * 纯靠猜必然有猜错的时候，而猜错的产物是乱码还看着像成功。所以猜不出来时
     * 返回 replaced 很大的结果，让界面去问用户。
     */
    fun recognize(bytes: ByteArray, fallback: TextEncoding = TextEncoding.Utf8): Decoded {
        detectBom(bytes)?.let { return decode(bytes, it) }
        if (bytes.isEmpty()) return Decoded("", TextEncoding.Utf8, false, 0)
        if (isValidUtf8(bytes)) return Decoded(String(bytes, StandardCharsets.UTF_8), TextEncoding.Utf8, false, 0)
        for (candidate in listOf(TextEncoding.Gb18030, TextEncoding.Big5, TextEncoding.ShiftJis)) {
            if (!available(candidate)) continue
            val result = decode(bytes, candidate)
            if (result.clean) return result
        }
        return decode(bytes, fallback)
    }

    /**
     * 编码输出。[bom] 只对 UTF-8/UTF-16 有意义。
     * 转不过去的字符（比如中文转 Latin1）数出来返回，界面据此提醒"丢了几个字" ——
     * 不数就会交出一份看着正常、内容已经被换成一片问号的产物。
     */
    fun encode(text: String, encoding: TextEncoding, bom: Boolean = false): EncodeResult {
        val charset = charsetOf(encoding)
        val strict = runCatching {
            charset.newEncoder()
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text)).copiedBytes()
        }
        if (strict.isSuccess) return EncodeResult(withBom(strict.getOrThrow(), encoding, bom), 0)
        val lenient = charset.newEncoder()
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .encode(java.nio.CharBuffer.wrap(text)).copiedBytes()
        return EncodeResult(withBom(lenient, encoding, bom), unmappable(text, charset))
    }

    /**
     * 编码器给的 ByteBuffer 是**按最大可能长度分配**的，`array()` 会把后面那段没用上的
     * 零字节一起带出去 —— 解回来就多了几个 \u0000。必须按 remaining 取。
     */
    private fun java.nio.ByteBuffer.copiedBytes(): ByteArray = ByteArray(remaining()).also { get(it) }

    /**
     * 逐码点试编，数出编不出去的几个。按码点而不是按 char：emoji 是代理对，
     * 单看半个必然判错。编码器复用一个 + 每次 reset，避免每个字符新建一个。
     */
    private fun unmappable(text: String, charset: Charset): Int {
        val encoder = charset.newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT)
        var index = 0
        var missing = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            val width = Character.charCount(point)
            encoder.reset()
            val fits = runCatching {
                encoder.encode(java.nio.CharBuffer.wrap(text, index, index + width)).position() >= 0
            }.isSuccess
            if (!fits) missing++
            index += width
        }
        return missing
    }

    private fun charsetOf(encoding: TextEncoding): Charset =
        runCatching { Charset.forName(encoding.charsetName) }
            .getOrElse { error("这台机器上没有 ${encoding.charsetName} 编码，换一种试试") }

    private fun withBom(bytes: ByteArray, encoding: TextEncoding, bom: Boolean): ByteArray {
        if (!bom) return bytes
        val prefix = bomBytes(encoding) ?: return bytes
        return prefix + bytes
    }

    class EncodeResult(val bytes: ByteArray, val dropped: Int) {
        val clean: Boolean get() = dropped == 0
    }

    /** 解完之后重新数一遍：能原样解回来才敢说没丢。 */
    fun roundTrips(text: String, encoding: TextEncoding): Boolean =
        runCatching { decode(encode(text, encoding).bytes, encoding).text == text }.getOrDefault(false)
}

/** 换行符。Mac 老文件用 CR，Windows 用 CRLF，其余 LF —— 混在一起时界面要能看出来。 */
enum class LineEnding(val label: String, val sample: String) {
    Lf("LF（Unix/安卓）", "\n"),
    CrLf("CRLF（Windows）", "\r\n"),
    Cr("CR（老 Mac）", "\r"),
}

object LineEndings {

    /** 数三种换行各多少个；一个都没有时返回空表（表示这份文件是单行或没有换行）。 */
    fun tally(text: String): Map<LineEnding, Int> {
        val counts = HashMap<LineEnding, Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    counts[LineEnding.CrLf] = (counts[LineEnding.CrLf] ?: 0) + 1; i += 2
                }
                c == '\r' -> { counts[LineEnding.Cr] = (counts[LineEnding.Cr] ?: 0) + 1; i++ }
                c == '\n' -> { counts[LineEnding.Lf] = (counts[LineEnding.Lf] ?: 0) + 1; i++ }
                else -> i++
            }
        }
        return counts
    }

    /** 占多数的那种；没有任何换行时返回 null。 */
    fun detect(text: String): LineEnding? = tally(text).maxByOrNull { it.value }?.key

    /** 混排的文件（一半 CRLF 一半 LF）先统一成 LF 再转换，否则第二次替换会踩到第一次留下的 \r。 */
    fun convert(text: String, to: LineEnding): String =
        text.replace("\r\n", "\n").replace('\r', '\n').let { normalized ->
            if (to == LineEnding.Lf) normalized else normalized.replace("\n", to.sample)
        }
}
