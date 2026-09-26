package com.fileforge.core.archive

import com.fileforge.core.util.SizeInput
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * 压缩包条目名的来源约束：只按中央目录里记的偏移取字节，不去猜布局。
 *
 * 之所以做成"给我一个区间我还你字节"而不是直接收 File —— 中央目录在文件**末尾**，
 * 而条目数据散在各处；把整份压缩包读进内存去解一个 2 GB 的包是没必要的自杀，
 * 让调用方按区间供字节，安卓侧就能用 RandomAccessFile 只搬需要的那一段。
 */
fun interface ByteSlice {
    /** 取 [from, to) 区间的字节；越界按实际可读长度返回（可能短于请求）。 */
    fun slice(from: Long, to: Long): ByteArray
}

/** 压缩方式。zip 的编号是规范定的，别的编号安卓的 Inflater 解不动。 */
enum class ZipMethod(val code: Int, val label: String) {
    Stored(0, "不压缩"),
    Deflate(8, "Deflate"),
    ;

    companion object {
        /** 解不动的方式一律报出来，不硬当 Store 处理 —— 硬当不压缩处理会产出一堆垃圾字节。 */
        fun of(code: Int): ZipMethod? = entries.firstOrNull { it.code == code }
    }
}

/** 一条目录项。尺寸与校验一律取**中央目录**的值：本地头里的三项常被流式写入方填成 0。 */
data class ZipEntry(
    override val name: String,
    val method: Int,
    val flags: Int,
    val crc: Long,
    val compressedSize: Long,
    override val size: Long,
    val localHeaderAt: Long,
    val modifiedAt: Long,
    val externalAttributes: Int,
) : PackagedEntry {
    override val isDirectory: Boolean get() = name.endsWith("/")

    /** 加密条目：安卓这边没有口令输入通路，读到就明确拒。 */
    val isEncrypted: Boolean get() = flags and 0x1 != 0

    /** 符号链接（unix mode 存在外部属性的**高 16 位**）：解出来等于在别人目录里放文件。 */
    val isSymlink: Boolean get() = (externalAttributes ushr 16) and 0xF000 == 0xA000

    override val skipReason: String?
        get() = when {
            isEncrypted -> ArchivePlan.PASSWORD
            ZipMethod.of(method) == null -> "用了解不了的压缩方式（${ZipLabel.of(method)}）"
            isSymlink -> "是个符号链接，解出来等于在别人目录里放文件"
            size > ArchivePlan.MAX_ENTRY_BYTES -> "单条 ${SizeInput.format(size)} 超过上限"
            else -> null
        }
}

/** 一份压缩包的目录。 */
data class ZipArchive(val entries: List<ZipEntry>, val comment: String = "")

/**
 * zip 的读侧：EOCD → 中央目录 → 条目数据。
 *
 * 三处最容易写错的地方都留了注释，因为它们各自对应一类真实文件：
 *  - **EOCD 要从尾部倒着找**，且前面可能挂着最长 64KB 的注释；写死"末尾 22 字节"读到带注释的包就散架
 *  - **zip64**：4 字段的哨兵值（0xFFFF / 0xFFFFFFFF）出现时真值在另一条记录里，超过 4 GB 的包全靠它
 *  - **本地头的名字与扩展长度可能和中央目录不同**（加密与流式写入方会加 data descriptor），
 *    所以数据起点必须按**本地头**自己算，不能拿中央目录的长度凑
 */
object ZipReader {

    private const val EOCD_SIG = 0x06054B50
    private const val EOCD64_SIG = 0x06064B50
    private const val EOCD64_LOCATOR_SIG = 0x07064B50
    private const val CENTRAL_SIG = 0x02014B50
    private const val LOCAL_SIG = 0x04034B50
    private const val ZIP64_EXTRA_ID = 0x0001

    /** EOCD 定长 22 字节，注释最长 65535 —— 倒着扫这么多字节足够覆盖规范允许的最大值。 */
    private const val EOCD_SCAN = 22 + 65535

    /** 整份字节都在手上时的取区间写法；大压缩包该由调用方用 RandomAccessFile 供同样的区间。 */
    fun slicing(bytes: ByteArray) = ByteSlice { from, to ->
        val start = from.coerceIn(0L, bytes.size.toLong()).toInt()
        val stop = to.coerceIn(start.toLong(), bytes.size.toLong()).toInt()
        bytes.copyOfRange(start, stop)
    }

    /** 整份字节一把读。小包的便捷入口；大压缩包要走 [ByteSlice]，别把整包搬进堆。 */
    fun read(bytes: ByteArray): ZipArchive = read(slicing(bytes), bytes.size.toLong())

    fun read(slices: ByteSlice, length: Long): ZipArchive {
        if (length < 22) error("这份文件太小，不像是压缩包")
        val tail = slices.slice(maxOf(0L, length - EOCD_SCAN), length)
        val at = indexOfEocd(tail) ?: error("尾部找不到中央目录结束记录，包要么坏了要么是别的格式")
        val eocd = tail.copyOfRange(at, tail.size)
        var count = u16(eocd, 10).toLong()
        var directorySize = u32(eocd, 12)
        var directoryAt = u32(eocd, 16)
        val commentLength = u16(eocd, 20)
        val comment = if (commentLength > 0 && at + 22 + commentLength <= tail.size) {
            decode(tail.copyOfRange(at + 22, at + 22 + commentLength), false)
        } else {
            ""
        }

        if (count == 0xFFFFL || directorySize == 0xFFFFFFFFL || directoryAt == 0xFFFFFFFFL) {
            // slice 可能因文件比扫描窗口短而截头，所以绝对位置要按"实际给了多少字节"倒推
            val zip64 = readZip64(slices, length - tail.size + at)
            count = zip64.first
            directorySize = zip64.second
            directoryAt = zip64.third
        }
        if (directoryAt + directorySize > length) error("中央目录超出文件长度，包是残的")

        val directory = slices.slice(directoryAt, directoryAt + directorySize)
        val entries = ArrayList<ZipEntry>(count.coerceAtMost(100_000L).toInt())
        var i = 0
        while (i + 46 <= directory.size && entries.size < count) {
            if (u32(directory, i) != CENTRAL_SIG.toLong()) break
            val nameLength = u16(directory, i + 28)
            val extraLength = u16(directory, i + 30)
            val entryComment = u16(directory, i + 32)
            val flags = u16(directory, i + 8)
            val compressed = u32(directory, i + 20)
            val uncompressed = u32(directory, i + 24)
            var offset = u32(directory, i + 42)
            val fixed = i + 46
            val nameEnd = fixed + nameLength
            if (nameEnd > directory.size) break
            val extra = directory.copyOfRange(nameEnd, (nameEnd + extraLength).coerceAtMost(directory.size))
            // zip64 扩展字段按"哪几个是哨兵就补哪几个"的顺序排，所以得先看中央目录里的原值
            val zip64 = zip64Extras(extra, uncompressed, compressed, offset)
            entries += ZipEntry(
                name = decode(directory.copyOfRange(fixed, nameEnd), flags and 0x800 != 0),
                method = u16(directory, i + 10),
                flags = flags,
                crc = u32(directory, i + 16),
                compressedSize = zip64.second ?: compressed,
                size = zip64.first ?: uncompressed,
                localHeaderAt = zip64.third ?: offset,
                modifiedAt = dosToEpoch(u16(directory, i + 12), u16(directory, i + 14)),
                externalAttributes = u32(directory, i + 38).toInt(),
            )
            i = nameEnd + extraLength + entryComment
        }
        return ZipArchive(entries, comment)
    }

    /** 条目数据（已解压、CRC 已核对）。解不动或校验不过就抛，不返回半成品。 */
    fun dataOf(entry: ZipEntry, bytes: ByteArray): ByteArray = dataOf(entry, slicing(bytes))

    fun dataOf(entry: ZipEntry, slices: ByteSlice): ByteArray {
        val out = ByteArrayOutputStream()
        writeDataOf(entry, slices, out)
        return out.toByteArray()
    }

    /**
     * 流式解出条目：全程内存里只有一个块，所以解一条 2 GB 的录像不会把进程顶掉。
     *
     * 校验和是边解边算的，坏包会先往 [target] 里写出去一段才发现 —— 调用方拿到异常就得把
     * 那份半成品扔掉，不能留着装成成品。
     */
    fun writeDataOf(entry: ZipEntry, slices: ByteSlice, target: java.io.OutputStream): Long {
        val method = ZipMethod.of(entry.method)
            ?: error("压缩方式 ${entry.method}（${ZipLabel.of(entry.method)}）解不了，这个包得用专门工具")
        if (entry.isEncrypted) error("条目「${entry.name}」带口令，本应用不做口令解压")
        val header = slices.slice(entry.localHeaderAt, entry.localHeaderAt + 30)
        if (header.size < 30 || u32(header, 0) != LOCAL_SIG.toLong()) error("条目「${entry.name}」的本地头不对")
        // 数据起点按本地头自己的长度算：不少写入方在这里的扩展字段里塞了和中央目录不一样的东西
        val start = entry.localHeaderAt + 30 + u16(header, 26) + u16(header, 28)
        val crc = CRC32()
        val written = when (method) {
            ZipMethod.Stored -> copyStored(slices, start, start + entry.compressedSize, target, crc)
            ZipMethod.Deflate -> inflateInto(slices, start, start + entry.compressedSize, target, crc)
        }
        if (crc.value != entry.crc) error("条目「${entry.name}」校验不过（CRC 对不上），包是坏的或被截断过")
        return written
    }

    private fun copyStored(
        slices: ByteSlice,
        from: Long,
        to: Long,
        target: java.io.OutputStream,
        crc: CRC32,
    ): Long {
        var at = from
        var total = 0L
        while (at < to) {
            val block = slices.slice(at, minOf(at + BLOCK, to))
            if (block.isEmpty()) error("条目数据比声明的短，包被截断了")
            crc.update(block)
            target.write(block)
            total += block.size
            at += block.size
        }
        return total
    }

    private fun inflateInto(
        slices: ByteSlice,
        from: Long,
        to: Long,
        target: java.io.OutputStream,
        crc: CRC32,
    ): Long {
        val inflater = Inflater(true)
        val buffer = ByteArray(BLOCK)
        var at = from
        var total = 0L
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (at >= to) error("Deflate 数据流比声明的短，包被截断了")
                    val block = slices.slice(at, minOf(at + BLOCK, to))
                    if (block.isEmpty()) error("Deflate 数据流比声明的短，包被截断了")
                    inflater.setInput(block)
                    at += block.size
                }
                val made = try {
                    inflater.inflate(buffer)
                } catch (bad: java.util.zip.DataFormatException) {
                    error("Deflate 数据流读不下去，包是坏的")
                }
                if (made == 0 && inflater.needsDictionary()) error("这个包用了字典压缩，解不了")
                if (made > 0) {
                    crc.update(buffer, 0, made)
                    target.write(buffer, 0, made)
                    total += made
                }
            }
            return total
        } finally {
            inflater.end()
        }
    }

    private const val BLOCK = 32 * 1024

    /**
     * zip64：EOCD 里出现哨兵值（0xFFFF / 0xFFFFFFFF）时，真值在 EOCD64 记录里，
     * 而它的位置由紧贴 EOCD 之前那 20 字节的定位器给出。超过 4 GB 或 65535 条的包全靠这条。
     */
    private fun readZip64(slices: ByteSlice, eocdAt: Long): Triple<Long, Long, Long> {
        if (eocdAt < 20) error("这个包用了 zip64 却没有定位器记录，读不了")
        val locator = slices.slice(eocdAt - 20, eocdAt)
        if (locator.size < 20 || u32(locator, 0) != EOCD64_LOCATOR_SIG.toLong()) {
            error("这个包用了 zip64 却没有定位器记录，读不了")
        }
        val recordAt = readLongLe(locator, 8)
        val record = slices.slice(recordAt, recordAt + 56)
        if (record.size < 56 || u32(record, 0) != EOCD64_SIG.toLong()) error("zip64 定位器指过去的不是 zip64 记录")
        return Triple(u64(record, 32), u64(record, 40), u64(record, 48))
    }

    /** 中央目录条目里的 zip64 扩展字段：只补那几个确实是哨兵的值。 */
    private fun zip64Extras(extra: ByteArray, size: Long, compressed: Long, offset: Long): Triple<Long?, Long?, Long?> {
        var size64: Long? = null
        var compressed64: Long? = null
        var offset64: Long? = null
        var i = 0
        while (i + 4 <= extra.size) {
            val id = u16(extra, i)
            val length = u16(extra, i + 2)
            val body = i + 4
            if (id == ZIP64_EXTRA_ID && body + length <= extra.size) {
                var p = body
                if (size == 0xFFFFFFFFL && p + 8 <= body + length) { size64 = readLongLe(extra, p); p += 8 }
                if (compressed == 0xFFFFFFFFL && p + 8 <= body + length) { compressed64 = readLongLe(extra, p); p += 8 }
                if (offset == 0xFFFFFFFFL && p + 8 <= body + length) { offset64 = readLongLe(extra, p) }
            }
            i = body + length
        }
        return Triple(size64, compressed64, offset64)
    }

    /** EOCD 签名可能在注释里以字节形式再次出现，所以从后往前找第一个真的"长度自洽"的位置。 */
    private fun indexOfEocd(tail: ByteArray): Int? {
        var i = tail.size - 22
        while (i >= 0) {
            if (u32(tail, i) == EOCD_SIG.toLong() && i + 22 + u16(tail, i + 20) == tail.size) return i
            i--
        }
        return null
    }

    /**
     * 名字编码：带 UTF-8 标志就照 UTF-8；没标志时按 UTF-8 严格试一次，
     * 再退 GBK（中文系统压出来的包最常见），最后才 CP437（规范默认的旧编码）。
     * 反过来的顺序会把纯 ASCII 名字以外的中文全解成乱码。
     */
    private fun decode(bytes: ByteArray, utf8Flag: Boolean): String {
        if (bytes.isEmpty()) return ""
        if (utf8Flag) return String(bytes, Charsets.UTF_8)
        if (com.fileforge.core.text.TextCodecs.isValidUtf8(bytes)) return String(bytes, Charsets.UTF_8)
        return runCatching { String(bytes, java.nio.charset.Charset.forName("GBK")) }
            .getOrDefault(String(bytes, Charsets.ISO_8859_1))
    }

    private fun dosToEpoch(time: Int, date: Int): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(1980 + (date ushr 9), ((date ushr 5) and 0xF) - 1, date and 0x1F,
            time ushr 11, (time ushr 5) and 0x3F, (time and 0x1F) * 2)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    // zip 全栈小端。这一行要是照抄图片那边的字节序，名字长度会读成 1280，
    // 然后中央目录一条都走不到 —— 表现是"包是空的"而不是报错，最难查的那种。
    private fun u16(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) =
        ((b[at + 3].toLong() and 0xFF) shl 24) or ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or (b[at].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0..7) value = value or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun readLongLe(b: ByteArray, at: Int) = u64(b, at)
}

/** 压缩方式的中文名（报错时用；解不动的方式未必在这份表里）。 */
object ZipLabel {
    fun of(code: Int): String = when (code) {
        0 -> "不压缩"
        8 -> "Deflate"
        12 -> "bzip2"
        14 -> "LZMA"
        93 -> "zstd"
        98 -> "PPMd"
        else -> "方式 $code"
    }
}

/** 要打进包里的一份内容。字节可以来自内存，也可以来自一个只许打开一次的文件流。 */
class ZipItem(val name: String, val size: Long, val modifiedAt: Long, val open: () -> java.io.InputStream) {

    constructor(name: String, bytes: ByteArray, modifiedAt: Long = System.currentTimeMillis()) :
        this(name, bytes.size.toLong(), modifiedAt, { java.io.ByteArrayInputStream(bytes) })

    /** 已经压过的这些扩展名再 Deflate 一遍只会变大，所以直接存原文（流式打包时的判据）。 */
    val skipDeflate: Boolean
        get() = name.substringAfterLast('.', "").lowercase() in NO_DEFLATE

    companion object {
        val NO_DEFLATE = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "bmp",
            "mp4", "mov", "mkv", "webm", "avi",
            "mp3", "aac", "m4a", "flac", "ogg", "opus", "wav",
            "zip", "jar", "apk", "rar", "7z", "gz", "bz2", "xz",
            "pdf",
        )
    }
}

/**
 * 能回写的落点。
 *
 * 本地头里要写压缩后长度，可那个数只有等真压完才知道 —— 流式打包不能把整份先攒在内存里，
 * 所以只能「先占位、写完回填」。这就决定了落点必须能 seek，光给一个 OutputStream 不够用。
 */
interface ZipSink {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun position(): Long
    fun seek(position: Long)
}

/** 攒在内存里的落点，给小包和测试用。回填本地头要能往回写，所以自己管游标而不是用 ByteArrayOutputStream。 */
class MemoryZipSink : ZipSink {
    private var data = ByteArray(8 * 1024)

    /** 写游标。 */
    private var cursor = 0L

    /** 到过的最远处：回填时要先 seek 回头部，再 seek 回这个位置继续写，所以两个数得分开记。 */
    private var limit = 0L

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val end = (cursor + length).toInt()
        if (end > data.size) data = data.copyOf(maxOf(end, data.size * 2))
        bytes.copyInto(data, cursor.toInt(), offset, offset + length)
        cursor += length
        if (cursor > limit) limit = cursor
    }

    override fun position(): Long = cursor

    override fun seek(position: Long) {
        require(position in 0..limit) { "只能回到已经写过的范围里" }
        cursor = position
    }

    fun bytes(): ByteArray = data.copyOf(limit.toInt())
}

/**
 * zip 的写侧：本地头 → 数据 → 中央目录 → EOCD。
 *
 * 三处刻意的选择：
 *  - 文件名一律 **UTF-8 + 置 0x800 标志位**，不置位的话中文名在别的机器上就是一堆乱码
 *  - **已经压过的类型直接存原文**（见 [ZipItem.NO_DEFLATE]）：Deflate 对 jpg/mp4/zip 只会越压越大，
 *    还白烧一遍 CPU。这条判据按扩展名而不是"先压一次比大小"，因为流式打包不能把整份先攒在内存里
 *  - 本地头的长度**先占 0 再回填**，不写 data descriptor：占位回填让流式打包成立，
 *    而 descriptor 那套反而让一些老工具找不到中央目录
 */
object ZipWriter {

    private const val LOCAL_SIG = 0x04034B50L
    private const val CENTRAL_SIG = 0x02014B50L
    private const val EOCD_SIG = 0x06054B50L
    private const val UTF8_FLAG = 0x800
    private const val VERSION = 20
    private const val MADE_BY_DOS = 0x00        // 高字节是"哪个系统写的"：0 = DOS/Windows，最不容易被挑刺
    private const val ARCHIVE_ATTRIBUTE = 0x20

    fun write(items: List<ZipItem>): ByteArray {
        val sink = MemoryZipSink()
        writeTo(items, sink)
        return sink.bytes()
    }

    /**
     * 流式打包：一份一份压，全程内存里只有一个块。
     *
     * 选哪条落点无所谓 —— 攒内存（[write]）还是往 staging 文件里写都行，因为回填靠的是
     * [ZipSink.seek]，跟能不能随机读没关系。
     */
    fun writeTo(items: List<ZipItem>, sink: ZipSink) {
        val records = ArrayList<CentralRecord>(items.size)
        items.forEach { item ->
            val name = item.name.toByteArray(Charsets.UTF_8)
            val method = if (item.skipDeflate) ZipMethod.Stored.code else ZipMethod.Deflate.code
            val (dosTime, dosDate) = dosOf(item.modifiedAt)
            val headerAt = sink.position()
            // 长度与 CRC 先占 0：压完才知道，写完回头补那 12 个字节
            sink.write(localHeader(name.size, method, 0L, 0L, 0L, dosTime, dosDate))
            sink.write(name)
            val crc = CRC32()
            val counted = CountingSink(sink)
            val plain = CountingStream(item.open())
            if (method == ZipMethod.Stored.code) {
                copyPlain(plain, SinkStream(counted), crc)
            } else {
                DeflaterOutputStream(SinkStream(counted), Deflater(Deflater.DEFAULT_COMPRESSION, true)).use {
                    copyPlain(plain, it, crc)      // true = 裸 deflate，不带 zlib 头，zip 要的就是这个
                }
            }
            val compressed = counted.count
            val size = plain.count
            sink.seek(headerAt + 14)
            sink.write(long32(crc.value) + long32(compressed) + long32(size))
            sink.seek(headerAt + 30 + name.size + compressed)
            records += CentralRecord(name, method, crc.value, compressed, size, dosTime, dosDate, headerAt)
        }
        val directoryAt = sink.position()
        records.forEach { record ->
            sink.write(centralHeader(record))
            sink.write(record.name)
        }
        val directorySize = sink.position() - directoryAt
        sink.write(eocd(records.size, directorySize, directoryAt))
    }

    /** 边读边算 CRC，同时数出真实字节数（[ZipItem.size] 只是调用方给的参考值）。 */
    private fun copyPlain(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        crc: CRC32,
    ) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            crc.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    /** 本地头：定长 30 字节，之后是名字与数据。 */
    private fun localHeader(
        nameLength: Int, method: Int, crc: Long,
        compressedSize: Long, size: Long, time: Int, date: Int,
    ): ByteArray = ByteArrayOutputStream(30).apply {
        write(long32(LOCAL_SIG))
        write(short16(VERSION))                     // version needed
        write(short16(UTF8_FLAG))                   // flags
        write(short16(method))
        write(short16(time)); write(short16(date))
        write(long32(crc))
        write(long32(compressedSize)); write(long32(size))
        write(short16(nameLength)); write(short16(0))   // 名字长度、扩展长度
    }.toByteArray()

    /**
     * 中央目录头：定长 46 字节。比本地头多一个「谁写的」，而且长度字段全部在这份里 ——
     * 少写那 2 字节会让所有偏移整体错开一位，所以这里**不跟本地头共用前缀**，
     * 一张表照着规范写下来，读回来对不对由 ZipTest 兜。
     */
    private fun centralHeader(record: CentralRecord): ByteArray = ByteArrayOutputStream(46).apply {
        write(long32(CENTRAL_SIG))
        write(short16(MADE_BY_DOS))                 // version made by
        write(short16(VERSION))                     // version needed
        write(short16(UTF8_FLAG))                   // flags
        write(short16(record.method))
        write(short16(record.time)); write(short16(record.date))
        write(long32(record.crc))
        write(long32(record.compressedSize)); write(long32(record.size))
        write(short16(record.name.size))            // 名字长度
        write(short16(0))                           // 扩展长度
        write(short16(0))                           // 注释长度
        write(short16(0))                           // 起始盘
        write(short16(0))                           // 内部属性
        write(long32(ARCHIVE_ATTRIBUTE.toLong()))   // 外部属性
        write(long32(record.localOffset))
    }.toByteArray()

    private fun eocd(count: Int, directorySize: Long, directoryAt: Long): ByteArray =
        ByteArrayOutputStream(22).apply {
            write(long32(EOCD_SIG))
            write(short16(0)); write(short16(0))            // 本盘号、中央目录起始盘
            write(short16(count)); write(short16(count))    // 本盘条数、总条数
            write(long32(directorySize)); write(long32(directoryAt))
            write(short16(0))                               // 注释长度
        }.toByteArray()

    /** 数出真正写出去多少字节：压缩后长度要在回填本地头时用。 */
    private class CountingSink(private val sink: ZipSink) : ZipSink {
        var count = 0L
            private set

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            sink.write(bytes, offset, length)
            count += length
        }

        override fun position(): Long = sink.position()
        override fun seek(position: Long) = sink.seek(position)
    }

    /** 数出真正读过多少字节：解压方核对的原始长度以实测为准，不信调用方报的数。 */
    private class CountingStream(stream: java.io.InputStream) : java.io.FilterInputStream(stream) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    /** ZipSink 背后套个 OutputStream，好让 DeflaterOutputStream 直接往里压。 */
    private class SinkStream(private val sink: ZipSink) : java.io.OutputStream() {
        override fun write(byte: Int) {
            sink.write(byteArrayOf(byte.toByte()))
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            sink.write(bytes, offset, length)
        }
    }

    /** DOS 时间戳只有秒级精度、且年份从 1980 起：比它更早的时间一律按 1980-01-01 写。 */
    private fun dosOf(epochMillis: Long): Pair<Int, Int> {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = maxOf(epochMillis, 315_532_800_000L)
        val time = (calendar.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (calendar.get(java.util.Calendar.MINUTE) shl 5) or (calendar.get(java.util.Calendar.SECOND) / 2)
        val date = ((calendar.get(java.util.Calendar.YEAR) - 1980) shl 9) or
            ((calendar.get(java.util.Calendar.MONTH) + 1) shl 5) or calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return time to date
    }

    private fun short16(value: Int) = byteArrayOf((value and 0xFF).toByte(), (value ushr 8 and 0xFF).toByte())

    private fun long32(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(), (value ushr 8 and 0xFF).toByte(),
        (value ushr 16 and 0xFF).toByte(), (value ushr 24 and 0xFF).toByte(),
    )

    private class CentralRecord(
        val name: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val time: Int,
        val date: Int,
        val localOffset: Long,
    )
}
