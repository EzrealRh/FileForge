package com.fileforge.core.office

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * OLE2 复合文档（CFB）读取。Word 97 / Excel 97 / PowerPoint 97 都是这个容器：
 * 512 字节头 + FAT 扇区链 + 目录项；小于 4096 字节的流放在 mini stream 里，走另一张 mini FAT。
 *
 * 只读、只按名字取流；不重建树，也不支持加密（加密的 Office 文件连目录都读不出来）。
 */
class Ole2 private constructor(private val raf: RandomAccessFile) : Closeable {

    private var sectorSize = 512
    private var miniSectorSize = 64
    private var miniCutoff = 4096
    private val fat = ArrayList<Int>()
    private val miniFat = ArrayList<Int>()
    private var miniStream = ByteArray(0)
    private val entries = ArrayList<Entry>()

    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        val header = ByteArray(512)
        raf.seek(0)
        raf.readFully(header)
        val signature = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        require(signature.indices.all { (header[it].toInt() and 0xFF) == signature[it] }) {
            "不是 OLE2 复合文档（Word/Excel/PowerPoint 97 的容器）"
        }
        sectorSize = 1 shl u16(header, 30)
        miniSectorSize = 1 shl u16(header, 32)
        require(sectorSize in 128..65536 && miniSectorSize in 16..4096) { "OLE2 扇区尺寸不合理" }
        miniCutoff = i32(header, 56)
        val fatSectors = i32(header, 44)
        val firstDir = i32(header, 48)
        val firstMiniFat = i32(header, 60)
        val miniFatSectors = i32(header, 64)
        val firstDifat = i32(header, 68)
        val difatSectors = i32(header, 72)

        val fatSectorIds = ArrayList<Int>()
        for (index in 0 until 109) {
            val sector = i32(header, 76 + index * 4)
            if (sector >= 0) fatSectorIds += sector
        }
        var difat = firstDifat
        var guard = 0
        while (difat >= 0 && guard++ < difatSectors + 8) {
            val bytes = sector(difat)
            val perSector = sectorSize / 4 - 1
            for (index in 0 until perSector) {
                val sectorId = i32(bytes, index * 4)
                if (sectorId >= 0) fatSectorIds += sectorId
            }
            difat = i32(bytes, perSector * 4)
        }
        require(fatSectorIds.size >= fatSectors) { "OLE2 的 FAT 扇区表不完整" }
        for (sectorId in fatSectorIds.take(fatSectors)) {
            val bytes = sector(sectorId)
            for (index in 0 until bytes.size / 4) fat += i32(bytes, index * 4)
        }

        readDirectory(firstDir)
        val root = entries.firstOrNull { it.type == 5 }
        if (root != null && root.size > 0) {
            miniStream = chain(root.start, root.size.toInt(), mini = false)
        }
        if (miniFatSectors > 0 && firstMiniFat >= 0) {
            var sectorId = firstMiniFat
            var count = 0
            while (sectorId >= 0 && count++ < miniFatSectors + 8) {
                val bytes = sector(sectorId)
                for (index in 0 until bytes.size / 4) miniFat += i32(bytes, index * 4)
                sectorId = fat.getOrElse(sectorId) { -1 }
            }
        }
    }

    fun names(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    fun hasStream(name: String): Boolean = entries.any { it.type == 2 && it.name == name }

    fun read(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        require(entry.size in 0..(256L * 1024 * 1024)) { "流「$name」大得不像表格：${entry.size} 字节" }
        return if (entry.size < miniCutoff) miniChain(entry.start, entry.size.toInt())
        else chain(entry.start, entry.size.toInt(), mini = false)
    }

    private fun readDirectory(first: Int) {
        var sectorId = first
        var guard = 0
        while (sectorId >= 0 && guard++ < fat.size + 8) {
            val bytes = sector(sectorId)
            var offset = 0
            while (offset + 128 <= bytes.size) {
                val nameLength = u16(bytes, offset + 64)
                if (nameLength in 2..64) {
                    val name = String(bytes, offset, nameLength - 2, Charsets.UTF_16LE)
                    val type = bytes[offset + 66].toInt() and 0xFF
                    if (type in 1..5) {
                        val start = i32(bytes, offset + 116)
                        var size = 0L
                        for (index in 0 until 8) {
                            size = size or ((bytes[offset + 120 + index].toLong() and 0xFF) shl (8 * index))
                        }
                        // 老版本（v3）只有低 32 位有效
                        if (sectorSize == 512) size = size and 0xFFFFFFFFL
                        entries += Entry(name, type, start, size)
                    }
                }
                offset += 128
            }
            sectorId = fat.getOrElse(sectorId) { -1 }
        }
        require(entries.isNotEmpty()) { "OLE2 目录是空的" }
    }

    private fun chain(first: Int, size: Int, mini: Boolean): ByteArray {
        val unit = if (mini) miniSectorSize else sectorSize
        val out = ByteArray(size)
        var sectorId = first
        var written = 0
        var guard = 0
        val table = if (mini) miniFat else fat
        while (sectorId >= 0 && written < size && guard++ < table.size + 16) {
            val bytes = if (mini) miniSector(sectorId) else sector(sectorId)
            val take = minOf(unit, size - written, bytes.size)
            System.arraycopy(bytes, 0, out, written, take)
            written += take
            sectorId = table.getOrElse(sectorId) { -1 }
        }
        require(written == size) { "OLE2 流不完整（读到 $written / $size 字节）" }
        return out
    }

    private fun miniChain(first: Int, size: Int): ByteArray = chain(first, size, mini = true)

    private fun miniSector(sectorId: Int): ByteArray {
        val offset = sectorId.toLong() * miniSectorSize
        require(offset + miniSectorSize <= miniStream.size) { "OLE2 mini 扇区越界" }
        return miniStream.copyOfRange(offset.toInt(), offset.toInt() + miniSectorSize)
    }

    private fun sector(sectorId: Int): ByteArray {
        val offset = (sectorId.toLong() + 1) * sectorSize
        require(offset >= 0 && offset + sectorSize <= raf.length()) { "OLE2 扇区 $sectorId 越界" }
        val bytes = ByteArray(sectorSize)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    override fun close() {
        runCatching { raf.close() }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun i32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    companion object {
        fun open(file: File): Ole2 = Ole2(RandomAccessFile(file, "r"))
    }
}

/** 旧 Office 的真类型：容器都是 OLE2，靠里面的流名分开。 */
object OldOffice {
    fun detect(file: File): com.fileforge.core.model.FileKind? = runCatching {
        Ole2.open(file).use { ole ->
            val names = ole.names()
            when {
                "Workbook" in names || "Book" in names -> com.fileforge.core.model.FileKind.Xls
                "WordDocument" in names -> com.fileforge.core.model.FileKind.Doc
                "PowerPoint Document" in names -> com.fileforge.core.model.FileKind.Ppt
                else -> com.fileforge.core.model.FileKind.Ole
            }
        }
    }.getOrNull()
}
