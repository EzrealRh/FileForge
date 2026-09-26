package com.fileforge.core.archive

import com.fileforge.core.util.SizeInput
import java.io.OutputStream

/** tar 头块里那个类型字符。认不出的算 [Other]，不猜成普通文件。 */
enum class TarType(val code: Char, val label: String) {
    File('0', "普通文件"),
    OldFile('\u0000', "普通文件（老写法）"),
    Directory('5', "目录"),
    Link('1', "硬链接"),
    Symlink('2', "符号链接"),
    CharDevice('3', "字符设备"),
    BlockDevice('4', "块设备"),
    Fifo('6', "命名管道"),
    GnuLongName('L', "长名字（GNU 写法）"),
    PaxHeader('x', "每条的扩展头（POSIX）"),
    PaxGlobal('g', "整包的扩展头"),
    GnuSparse('S', "稀疏文件"),
    Volume('V', "卷标"),
    Other('?', "没见过的类型"),
    ;

    companion object {
        fun of(code: Char): TarType = entries.firstOrNull { it.code == code } ?: Other
    }
}

/**
 * tar 的一条目录项。
 *
 * [offset] 是内容在这个包里的起点 —— tar 是把内容一段段接在自己头块后面的格式，
 * 记住起点和长度就能按需读，不必把整包搬进内存。[typeCode] 留着原始字符：
 * 认不出的类型也要能告诉用户"第 3 条那个 'K' 我不认"。
 */
class TarEntry(
    override val name: String,
    override val size: Long,
    override val isDirectory: Boolean,
    val type: TarType,
    val typeCode: Char,
    val mtime: Long,
    val linkName: String,
    val offset: Long,
) : PackagedEntry {

    override val skipReason: String?
        get() = when {
            isDirectory -> "是个目录（目录里的东西会单独列出来）"
            type == TarType.Symlink -> "是个符号链接，解出来等于在别人目录里放文件"
            type == TarType.Link -> "是个硬链接（指向 ${linkName.ifBlank { "包外" }}），链接关系解出来就没了"
            type == TarType.CharDevice || type == TarType.BlockDevice || type == TarType.Fifo ->
                "是 ${type.label}，手机上没有那样东西"
            type == TarType.GnuSparse -> "是稀疏文件，块映射那套不解"
            type == TarType.Volume -> "是卷标"
            type == TarType.Other -> "类型字符是 '$typeCode'，认不出来"
            size > ArchivePlan.MAX_ENTRY_BYTES -> "单条 ${SizeInput.format(size)} 超过上限"
            else -> null
        }
}

/** 一个 tar 的目录，外加"有什么没读出来"。 */
class TarArchive(val entries: List<TarEntry>, val notes: List<String> = emptyList())

/**
 * tar 的读与写。
 *
 * 这格式简单到没有魔数也没有尾记录：**每 512 字节一个头块，内容跟在后面按 512 补齐**，
 * 读到全零头块（或读不动）算完。简单也意味着坑都在细节里：
 *  - 名字超过 100 字节要嘛走 ustar 的 `prefix` 字段，要嘛走 GNU 的 `L` 条目或 POSIX 的 `x` 扩展头，
 *    三种都得认 —— 不然中文长名的包一打开就少文件
 *  - 数字常规是 ASCII 八进制（末尾还常带空格或 NUL），但大文件与超出 1956~2242 年的时间戳
 *    用**二进制补码**写（首字节最高位为 1）
 *  - 头块自带的校验和只算这**一个头**（且把校验和自己那 8 字节当成空格）；不中就说明
 *    连长度与起点都不可信，后面的条目全是错的 —— 停下来，别硬接着读
 *  - 内容本身没有校验（zip 有 CRC），所以我们不改写也不"顺手修"，读到哪里就说哪里
 */
object Tar {

    const val BLOCK = 512

    /** POSIX 1003.1 那张表里的字段起点。 */
    private const val AT_NAME = 0
    private const val AT_MODE = 100
    private const val AT_SIZE = 124
    private const val AT_MTIME = 136
    private const val AT_CHKSUM = 148
    private const val AT_TYPE = 156
    private const val AT_LINK = 157
    private const val AT_MAGIC = 257
    private const val AT_VERSION = 263
    private const val AT_UNAME = 265
    private const val AT_PREFIX = 345
    private const val MAGIC = "ustar"

    /** 一条扩展头正常就几十字节；给个上限，别让一个坏包把整包读进堆里。 */
    private const val MAX_PAX_BYTES = 4L * 1024 * 1024

    /** POSIX 的八进制字段能放下的最大数（11 位八进制 = 33 位二进制），超了要改写成二进制补码。 */
    private const val OCTAL_MAX = (1L shl 33) - 1

    fun read(bytes: ByteArray): TarArchive = read(ZipReader.slicing(bytes), bytes.size.toLong())

    fun read(slices: ByteSlice, length: Long): TarArchive {
        val entries = ArrayList<TarEntry>()
        val notes = ArrayList<String>()
        var at = 0L
        var longName: String? = null
        var pax = emptyMap<String, String>()
        var order = 0
        while (at + BLOCK <= length) {
            val block = slices.slice(at, at + BLOCK)
            if (block.all { it == 0.toByte() }) break
            val stored = octal(block, AT_CHKSUM, 8)
            if (stored != checksum(block)) {
                notes += "第 ${order + 1} 个 512 字节的头块校验和不对，到这里为止读得出来，后面的不能接着信"
                break
            }
            order++
            val code = block[AT_TYPE].toInt().toChar()
            val type = TarType.of(code)
            val size = octal(block, AT_SIZE, 12)
            val dataAt = at + BLOCK
            when (type) {
                TarType.GnuLongName -> longName = String(raw(slices, dataAt, size), Charsets.UTF_8).substringBefore('\u0000')
                TarType.PaxHeader -> pax = paxRecords(raw(slices, dataAt, size))
                else -> {
                    val path = longName ?: pax["path"] ?: fullName(block)
                    entries += TarEntry(
                        name = path,
                        size = pax["size"]?.toLongOrNull() ?: size,
                        isDirectory = type == TarType.Directory || path.endsWith("/"),
                        type = type,
                        typeCode = code,
                        mtime = pax["mtime"]?.toLongOrNull() ?: octal(block, AT_MTIME, 12),
                        linkName = pax["linkpath"] ?: fixed(block, AT_LINK, 100),
                        offset = dataAt,
                    )
                    pax = emptyMap()
                    longName = null
                }
            }
            at = dataAt + roundUp(size)
        }
        return TarArchive(entries, notes)
    }

    /** 一条的内容。目录与符号链接这些没有内容，给空。 */
    fun dataOf(entry: TarEntry, slices: ByteSlice): ByteArray =
        if (entry.size <= 0L) ByteArray(0) else slices.slice(entry.offset, entry.offset + entry.size)

    fun writeDataOf(entry: TarEntry, slices: ByteSlice, target: OutputStream): Long {
        if (entry.size <= 0L) return 0L
        val bytes = dataOf(entry, slices)
        target.write(bytes)
        return bytes.size.toLong()
    }

    /**
     * 头块第 257 字节那五个字符是 `ustar` 且**这个头自己的校验和对得上**才算 tar。
     *
     * 光看魔数不行：tar 没有开头的标识，任何文件里都可能有一处看着像 `ustar` 的字节；
     * 加上校验和以后，误判需要凑出 12 个字节的八进制数来配平，实际碰不到。
     */
    fun looksLikeTar(header: ByteArray): Boolean =
        header.size >= BLOCK && fixed(header, AT_MAGIC, 5) == MAGIC &&
            octal(header, AT_CHKSUM, 8) == checksum(header.copyOf(BLOCK))

    /** ustar 的 `prefix` 字段非空时接在 name 前面 —— POSIX 的长名字办法。 */
    private fun fullName(block: ByteArray): String {
        val name = fixed(block, AT_NAME, 100)
        val prefix = fixed(block, AT_PREFIX, 155)
        return if (prefix.isEmpty() || name.isEmpty()) name else "$prefix/$name"
    }

    /** 定长字段读到 NUL 为止；名字这族按 UTF-8 解（现代打包工具都写 UTF-8）。 */
    private fun fixed(block: ByteArray, at: Int, size: Int): String {
        val stop = (at until (at + size).coerceAtMost(block.size)).firstOrNull { block[it].toInt() == 0 }
            ?: (at + size).coerceAtMost(block.size)
        return String(block, at, stop - at, Charsets.UTF_8)
    }

    /**
     * 数字字段：常规是 ASCII 八进制（前后常有空格或 NUL 垫着），
     * 首字节最高位为 1 时是**二进制补码**（大文件与超出 1956~2242 年的时间戳就这么写）。
     */
    private fun octal(block: ByteArray, at: Int, size: Int): Long {
        if (at + size > block.size) return 0L
        val lead = block[at].toInt() and 0xFF
        if (lead and 0x80 != 0) {
            var value = if (lead == 0xFF) -1L else 0L
            for (index in 1 until size) value = (value shl 8) or (block[at + index].toInt() and 0xFF).toLong()
            return value
        }
        var sum = 0L
        var started = false
        for (index in at until at + size) {
            val digit = block[index].toInt() and 0xFF
            if (digit == ' '.code || (!started && digit == 0)) continue
            if (digit < '0'.code || digit > '7'.code) break
            started = true
            sum = sum * 8 + (digit - '0'.code)
        }
        return sum
    }

    /** 头块自己的校验和：把那 8 个字节当空格算，其余按无符号字节相加。 */
    private fun checksum(block: ByteArray): Long {
        var sum = 0L
        block.forEachIndexed { index, byte ->
            val value = if (index in (AT_CHKSUM until AT_CHKSUM + 8)) ' '.code else byte.toInt() and 0xFF
            sum += value.toLong()
        }
        return sum
    }

    private fun raw(slices: ByteSlice, at: Long, size: Long): ByteArray =
        if (size <= 0L) ByteArray(0) else slices.slice(at, at + size.coerceAtMost(MAX_PAX_BYTES))

    /** PAX 扩展头的记录是 `长度 键=值\n`，长度把"长度"自己那串数字与换行都算进去、按**字节**算。 */
    private fun paxRecords(body: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var at = 0
        while (at < body.size) {
            var stop = at
            while (stop < body.size && body[stop].toInt() != ' '.code) stop++
            val declared = String(body, at, stop - at, Charsets.US_ASCII).toLongOrNull() ?: break
            if (declared <= 0 || at + declared > body.size) break
            val record = String(body, at + stop - at + 1, (declared - (stop - at) - 2).toInt(), Charsets.UTF_8)
            if (record.contains('=')) out[record.substringBefore('=')] = record.substringAfter('=').trimEnd('\n')
            at += declared.toInt()
        }
        return out
    }

    private fun roundUp(size: Long): Long = if (size % BLOCK == 0L) size else size + (BLOCK - size % BLOCK)

    /** 把内容补到 512 的整数倍要加的那几字节。 */
    private fun padding(size: Long): ByteArray = ByteArray((roundUp(size) - size).toInt())

    /**
     * 写一条的头块（可能需要先写一条 PAX 扩展头）。
     *
     * 名字放不下的时候按这个顺序挑：能整段塞进 `name[100]` 就直写；纯 ASCII 且能在一处 `/`
     * 切开、前段 ≤155 后段 ≤100 就走 ustar 的 `prefix`；剩下的情形写一条 `path=` 的扩展头，
     * 头块里那份名字放**末 100 字节**（GNU tar 与 bsdtar 都这么干，认扩展头的工具看得见的就是它）。
     */
    private fun headerOf(item: TarItem, out: OutputStream) {
        val bytes = item.name.toByteArray(Charsets.UTF_8)
        var path = ""
        var name = item.name
        var prefix = ""
        if (bytes.size > 100) {
            val split = asciiPrefixSplit(item.name)
            if (split == null) {
                path = item.name
                name = tailWithin(item.name, 100)
            } else {
                prefix = split.first
                name = split.second
            }
        }
        val mtimeFits = item.mtime in 0..OCTAL_MAX
        val sizeFits = item.size <= OCTAL_MAX
        if (path.isNotEmpty()) writePax(path, item, out)
        val header = ByteArray(BLOCK)
        put(header, AT_NAME, name)
        // 0o755 = 493、0o644 = 420：Kotlin 没有八进制字面量，写成十进制并在注释里留着原值
        putOctal(header, AT_MODE, if (item.directory) 493 else 420, 8)
        putOctal(header, AT_SIZE, item.size, 12, binary = !sizeFits)
        putOctal(header, AT_MTIME, item.mtime, 12, binary = !mtimeFits)
        put(header, AT_CHKSUM, "        ")
        header[AT_TYPE] = (if (item.directory) TarType.Directory else TarType.File).code.toByte()
        put(header, AT_MAGIC, MAGIC)
        header[AT_MAGIC + 5] = 0
        put(header, AT_VERSION, "00")
        put(header, AT_UNAME, "fileforge")
        put(header, AT_PREFIX, prefix)
        putOctal(header, AT_CHKSUM, checksum(header), 8, plain = true)
        out.write(header)
    }

    /** `prefix[155]` 这个办法只给纯 ASCII 的名字用：老工具按字节切，中文塞进去会断在半字上。 */
    private fun asciiPrefixSplit(full: String): Pair<String, String>? {
        if (!full.all { it.code < 0x80 }) return null
        var cut = full.lastIndexOf('/')
        while (cut > 0) {
            val head = full.substring(0, cut)
            val tail = full.substring(cut + 1)
            if (head.length <= 155 && tail.length <= 100) return head to tail
            cut = full.lastIndexOf('/', cut - 1)
        }
        return null
    }

    /** 取末尾这么多**字节**，且开头不许是个半个字符（代理对的下半边）。 */
    private fun tailWithin(text: String, limit: Int): String {
        var bytes = 0
        var from = text.length
        while (from > 0) {
            val code = text[from - 1].code
            val width = if (code < 0x80) 1 else if (code < 0x800) 2 else if (code < 0x10000) 3 else 4
            if (bytes + width > limit) break
            bytes += width
            from--
        }
        val start = if (from < text.length && text[from].isLowSurrogate()) from + 1 else from
        return text.substring(start.coerceAtMost(text.length - 1))
    }

    private fun writePax(path: String, item: TarItem, out: OutputStream) {
        val value = "path=$path\n"
        val width = value.toByteArray(Charsets.UTF_8).size
        var declared = width + 3
        while (declared.toString().toByteArray(Charsets.UTF_8).size + 1 + width != declared) declared++
        val body = "$declared $value".toByteArray(Charsets.UTF_8)
        val header = ByteArray(BLOCK)
        put(header, AT_NAME, PAX_NAME)
        putOctal(header, AT_MODE, 0, 8)
        putOctal(header, AT_SIZE, body.size.toLong(), 12)
        putOctal(header, AT_MTIME, item.mtime, 12)
        put(header, AT_CHKSUM, "        ")
        header[AT_TYPE] = TarType.PaxHeader.code.toByte()
        put(header, AT_MAGIC, MAGIC)
        header[AT_MAGIC + 5] = 0
        put(header, AT_VERSION, "00")
        putOctal(header, AT_CHKSUM, checksum(header), 8, plain = true)
        out.write(header)
        out.write(body)
        out.write(padding(body.size.toLong()))
    }

    private fun put(header: ByteArray, at: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        bytes.copyInto(header, at, 0, bytes.size.coerceAtMost(header.size - at))
    }

    /** 数字字段：默认八进制（末尾留 NUL）；[plain] 是校验和那格要的"六位 + NUL + 空格"写法。 */
    private fun putOctal(
        header: ByteArray, at: Int, value: Long, size: Int, binary: Boolean = false, plain: Boolean = false,
    ) {
        if (binary) {
            header[at] = (if (value < 0) 0xFF else 0x80).toByte()
            var rest = value
            for (index in at + size - 1 downTo at + 1) {
                header[index] = (rest and 0xFF).toByte()
                rest = rest shr 8
            }
            return
        }
        put(header, at, if (plain) "%06o".format(value) + "\u0000 " else "%${size - 1}o".format(value))
    }

    private const val PAX_NAME = "./PaxHeaders.0/entry"

    /** tar 是顺序格式：不用回跳，收尾两个全零块就完。 */
    fun writeTo(items: List<TarItem>, out: OutputStream) {
        items.forEach { item ->
            headerOf(item, out)
            if (item.size > 0 && !item.directory) {
                val written = item.open().use { source -> source.copyTo(out) }
                out.write(padding(written))
                if (written != item.size) {
                    throw IllegalStateException("${item.name} 声明 ${item.size} 字节，实际写出 $written 字节")
                }
            }
        }
        out.write(ByteArray(BLOCK * 2))
    }

    fun write(items: List<TarItem>): ByteArray {
        val sink = java.io.ByteArrayOutputStream()
        writeTo(items, sink)
        return sink.toByteArray()
    }
}

/**
 * 要打进 tar 的一条。内容用 [open] 现取：打包不必先把整包攒进内存。
 *
 * [size] 必须与 [open] 给的字节数一致 —— 头块是先写的，对不上会产出一份"看着齐、
 * 后面全错位"的包，所以写的时候直接报错而不是将就。
 */
class TarItem(
    val name: String,
    val size: Long,
    val mtime: Long,
    val directory: Boolean = false,
    val open: () -> java.io.InputStream,
)


