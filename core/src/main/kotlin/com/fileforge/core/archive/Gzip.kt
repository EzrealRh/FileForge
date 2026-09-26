package com.fileforge.core.archive

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream

/** gzip 头部那 10 个字节里的事：压缩方式、时间、有没有带原名。 */
class GzipHeader(val method: Int, val mtime: Long, val name: String?, val flags: Int) {
    /** 除 DEFLATE(8) 之外的方式安卓的 Inflater 解不动（`file` 里常见的 .z 是 compress/LZW）。 */
    val supported: Boolean get() = method == 8
}

/**
 * gzip 的读与写（RFC 1952）。
 *
 * 库里的 GZIPInputStream 只管把字挤出来，**头里的原始文件名它不看** —— 而那份名字正是
 * 单个 .gz 该解成什么名字的依据（`a.txt.gz` 里可能写着 `报告/长一点的名.txt`）。
 * 所以这里自己读那 10 个字节加可选字段，内容仍旧交给库。
 *
 * 写侧同理：库写出的头里恒为"无名字、时间 0"，这里按同样的规矩自己拼头 + 裸 DEFLATE +
 * CRC32 与原始长度（那两个 4 字节是**唯一**的内容校验，缺了它任何一位翻错都没人知道）。
 */
object Gzip {

    private const val ID1 = 0x1F
    private const val ID2 = 0x8B
    private const val DEFLATE = 8
    private const val FEXTRA = 4
    private const val FNAME = 8

    fun isGzip(header: ByteArray): Boolean =
        header.size >= 10 && header[0].toInt() and 0xFF == ID1 && header[1].toInt() and 0xFF == ID2

    /** 读头；不是 gzip 给 null。尾部数据一概不碰。 */
    fun readHeader(bytes: ByteArray): GzipHeader? {
        if (!isGzip(bytes)) return null
        val method = bytes[2].toInt() and 0xFF
        val mtime = dword(bytes, 4)
        var at = 10
        val flags = bytes[3].toInt() and 0xFF
        if (flags and FEXTRA != 0) {
            if (bytes.size < at + 2) return null
            at += (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        }
        var name: String? = null
        if (flags and FNAME != 0) {
            var stop = at
            while (stop < bytes.size && bytes[stop].toInt() != 0) stop++
            if (stop >= bytes.size) return null
            name = String(bytes, at, stop - at, Charsets.UTF_8)
        }
        return GzipHeader(method, mtime, name, flags)
    }

    /**
     * 解到 [target]，交回写出的字节数。
     *
     * 尾部的 CRC32 与原始长度由库自己核对 —— 对不上会抛，不会给出一份"看着解完了"的坏文件。
     */
    fun ungzip(source: InputStream, target: OutputStream): Long {
        var total = 0L
        GZIPInputStream(source, 8192).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                target.write(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    /**
     * 套一层 gzip 写出去。
     *
     * [name] 是"解回去该叫什么"的提示，按 RFC 1952 那是个 NUL 结尾的字节串 —— 我们写 UTF-8；
     * 太长（头字段加不进 512 字节也没意义）就不写，反正名字主要还是靠 `.gz` 前那个文件名。
     */
    fun gzip(source: InputStream, target: OutputStream, mtime: Long = 0L, name: String? = null) {
        val crc = CRC32()
        val flags = if (name == null) 0 else FNAME
        target.write(
            byteArrayOf(
                ID1.toByte(), ID2.toByte(), DEFLATE.toByte(), flags.toByte(),
                (mtime and 0xFF).toByte(), ((mtime shr 8) and 0xFF).toByte(),
                ((mtime shr 16) and 0xFF).toByte(), ((mtime shr 24) and 0xFF).toByte(),
                0, 3,
            ),
        )
        if (name != null) {
            target.write(name.toByteArray(Charsets.UTF_8).take(MAX_NAME_BYTES).toByteArray())
            target.write(0)
        }
        val counted = CountingStream(source, crc)
        // nowrap=true：头与尾由我们自己写，库只管中间那段裸 DEFLATE
        DeflaterOutputStream(target, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { sink ->
            counted.copyTo(sink)
            sink.finish()
        }
        val size = counted.read
        val trailer = ByteArray(8)
        for (index in 0 until 4) {
            trailer[index] = ((crc.value shr (8 * index)) and 0xFF).toByte()
            trailer[4 + index] = ((size shr (8 * index)) and 0xFF).toByte()
        }
        target.write(trailer)
        target.flush()
    }

    /** FNAME 字段是 NUL 结尾的字节串；太长的名字不写（写一半比不写更坏）。 */
    private const val MAX_NAME_BYTES = 200

    private fun dword(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, index -> acc or ((bytes[at + index].toLong() and 0xFF) shl (8 * index)) }

    /** 边读边算 CRC32 与**未压缩**长度：gzip 尾部那两个数就是它俩，必须在压缩之前算。 */
    private class CountingStream(private val source: InputStream, private val crc: CRC32) : InputStream() {
        var read: Long = 0
            private set

        override fun read(): Int = source.read().also { if (it >= 0) { crc.update(it); read++ } }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            source.read(bytes, offset, length).also { taken ->
                if (taken > 0) {
                    crc.update(bytes, offset, taken)
                    read += taken
                }
            }

        override fun close() = source.close()
    }
}
