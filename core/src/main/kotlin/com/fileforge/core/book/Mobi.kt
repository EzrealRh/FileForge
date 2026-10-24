package com.fileforge.core.book

import com.fileforge.core.doc.Html
import java.nio.charset.Charset

/**
 * MOBI / AZW3（KF8）读取。
 *
 * 容器是 Palm 的 PDB：记录 0 放 PalmDOC 头与 MOBI 头，后面若干条记录拼成正文。
 * 正文可能是明文、PalmDOC 的 LZ77，或 HUFF/CDIC；**HUFF 这里不实现**（要另一张码表），
 * 遇到就明确说，不返回一份乱码。带 DRM 的书同样明确拒绝。
 *
 * AZW3 的流式排版（KF8）正文仍是 HTML 记录，按普通 HTML 拼得出来；它的 FDST 分片与
 * 图片资源不搬，图片位置会少，说明里写清。
 */
object Mobi {

    class Book(
        val title: String,
        val author: String?,
        val html: String,
        val plainText: String,
        val notes: List<String>,
    )

    private const val HUFF = 17480

    fun read(bytes: ByteArray): Book {
        require(bytes.size > 78) { "文件太短，不像 MOBI" }
        val type = ascii(bytes, 60, 4)
        val creator = ascii(bytes, 64, 4)
        require(type == "BOOK" || type == "TEXt") { "不是 MOBI / PalmDOC（类型是「$type$creator」）" }
        val recordCount = be16(bytes, 76)
        require(recordCount >= 2) { "记录数不对（$recordCount），文件可能损坏" }
        val offsets = IntArray(recordCount) { index -> be32(bytes, 78 + index * 8) }
        fun record(index: Int): ByteArray {
            val start = offsets.getOrElse(index) { bytes.size }
            val end = if (index + 1 < recordCount) offsets[index + 1] else bytes.size
            require(start in 0..bytes.size && end <= bytes.size && end >= start) {
                "第 ${index + 1} 条记录越界（$start..$end / ${bytes.size}）"
            }
            return bytes.copyOfRange(start, end)
        }

        val first = record(0)
        require(first.size >= 16) { "PalmDOC 头不完整" }
        val compression = be16(first, 0)
        val textLength = be32Long(first, 4)
        val textRecords = be16(first, 8)
        val encryption = be16(first, 12)
        require(encryption == 0) {
            "这本书带 DRM 加密，读不了；请在购买它的阅读器里导出无 DRM 的副本再试"
        }
        require(compression == 1 || compression == 2 || compression == HUFF) {
            "不认识的 PalmDOC 压缩方式：$compression"
        }
        require(compression != HUFF) {
            "这本书用 HUFF/CDIC 压缩，当前不支持；把它转成普通 MOBI（不带 HUFF）再试"
        }
        require(textRecords in 1 until recordCount) {
            "正文记录数不对（$textRecords / $recordCount）"
        }

        val notes = ArrayList<String>()
        val mobiAt = if (first.size >= 20 && ascii(first, 16, 4) == "MOBI") 16 else -1
        var encoding = 65001
        var fileVersion = 6
        var pdbTitle = ascii(bytes, 0, 32).trimEnd('\u0000', ' ')
        var fullName: String? = null
        var author: String? = null
        if (mobiAt >= 0) {
            encoding = be32(first, mobiAt + 12)
            fileVersion = be32(first, mobiAt + 20)
            val headerLength = be32(first, mobiAt + 4)
            val nameOffset = be32(first, mobiAt + 68)
            val nameLength = be32(first, mobiAt + 72)
            if (nameOffset in 0 until first.size && nameLength in 1..4096 &&
                nameOffset + nameLength <= first.size
            ) {
                fullName = decode(first.copyOfRange(nameOffset, nameOffset + nameLength), encoding)
            }
            val exthFlags = if (mobiAt + 112 + 4 <= first.size) be32(first, mobiAt + 112) else 0
            if (exthFlags and 0x40 != 0) {
                val exthAt = mobiAt + headerLength
                val exth = readExth(first, exthAt)
                author = exth[100]?.trim()?.takeIf { it.isNotEmpty() }
                exth[503]?.trim()?.takeIf { it.isNotEmpty() }?.let { fullName = it }
            }
            if (fileVersion >= 8) {
                notes += "KF8 / AZW3 流式排版按 HTML 拼；分片索引与内嵌图片不搬"
            }
        } else {
            notes += "没有 MOBI 头，按 PalmDOC 纯文本读"
        }
        if (compression == 2) notes += "PalmDOC 压缩已展开"

        val builder = java.io.ByteArrayOutputStream(textLength.coerceAtMost(64L * 1024 * 1024).toInt())
        for (index in 1..textRecords) {
            val raw = record(index)
            val plain = if (compression == 2) decompressPalmDoc(raw) else raw
            builder.write(plain)
        }
        val all = builder.toByteArray()
        val cut = if (textLength in 1..all.size.toLong()) all.copyOf(textLength.toInt()) else all
        val html = decode(cut, encoding).replace("\u0000", "")
        require(html.isNotBlank()) { "正文是空的；这本书可能只用图片排版" }
        val plain = Html.toPlainText(html)
        if (plain.notes.isNotEmpty()) notes += "正文里有 ${plain.notes.size} 处结构没认出来，按纯文字读"
        val title = fullName?.takeIf { it.isNotBlank() }
            ?: pdbTitle.takeIf { it.isNotBlank() }
            ?: "未命名"
        return Book(title, author, html, plain.text, notes)
    }

    private fun readExth(record: ByteArray, start: Int): Map<Int, String> {
        if (start + 12 > record.size || ascii(record, start, 4) != "EXTH") return emptyMap()
        val length = be32(record, start + 4).coerceAtMost(record.size - start)
        val count = be32(record, start + 8)
        val out = HashMap<Int, String>()
        var pos = start + 12
        var seen = 0
        while (seen < count && pos + 8 <= start + length) {
            val type = be32(record, pos)
            val size = be32(record, pos + 4)
            if (size < 8 || pos + size > start + length) break
            out[type] = decode(record.copyOfRange(pos + 8, pos + size), 65001)
            pos += size
            seen++
        }
        return out
    }

    /** PalmDOC LZ77：0 字面、1..8 拷贝、9..0x7F 直出、0x80..0xBF 回指、0xC0..0xFF 空格+字符。 */
    fun decompressPalmDoc(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val value = data[i++].toInt() and 0xFF
            when {
                value == 0 -> out.write(0)
                value <= 8 -> {
                    val end = minOf(data.size, i + value)
                    out.write(data, i, end - i)
                    i = end
                }
                value <= 0x7F -> out.write(value)
                value <= 0xBF -> {
                    if (i >= data.size) break
                    val next = data[i++].toInt() and 0xFF
                    val pair = (value shl 8) or next
                    val distance = (pair shr 3) and 0x7FF
                    val length = (pair and 0x7) + 3
                    val bytesOut = out.toByteArray()
                    if (distance in 1..bytesOut.size) {
                        var from = bytesOut.size - distance
                        repeat(length) {
                            val index = from++
                            if (index < bytesOut.size) out.write(bytesOut[index].toInt())
                        }
                    }
                }
                else -> {
                    out.write(' '.code)
                    out.write(value xor 0x80)
                }
            }
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, encoding: Int): String {
        val charset = runCatching {
            when (encoding) {
                1252 -> Charset.forName("windows-1252")
                936 -> Charset.forName("GBK")
                950 -> Charset.forName("Big5")
                932 -> Charset.forName("Shift_JIS")
                else -> Charsets.UTF_8
            }
        }.getOrDefault(Charsets.UTF_8)
        return String(bytes, charset)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (offset < 0 || offset + length > bytes.size) "" else String(bytes, offset, length, Charsets.US_ASCII)

    private fun be16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun be32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun be32Long(bytes: ByteArray, offset: Int): Long = be32(bytes, offset).toLong() and 0xFFFFFFFFL
}
