package com.fileforge.converter.engine

import com.fileforge.core.archive.ArchivePlan
import com.fileforge.core.archive.ByteSlice
import com.fileforge.core.archive.Gzip
import com.fileforge.core.archive.PackagedEntry
import com.fileforge.core.archive.Tar
import com.fileforge.core.archive.TarEntry
import com.fileforge.core.archive.TarItem
import com.fileforge.core.archive.UnpackPlan
import com.fileforge.core.archive.ZipEntry
import com.fileforge.core.archive.ZipItem
import com.fileforge.core.archive.ZipSink
import com.fileforge.core.archive.ZipReader
import com.fileforge.converter.data.FileSlices
import com.fileforge.core.archive.ZipWriter
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import com.github.junrar.Archive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

private const val MAX_ENTRY_BYTES = 1L shl 30
private const val MAX_TOTAL_BYTES = 2L shl 30
private const val MAX_ENTRIES = 10_000

/**
 * 压缩包进出。
 *
 * 字节层面的判断全在 `:core` 的 Zip* 里（那边可逐条单测，也被 Python 的 zipfile 复核过），
 * 这个类只做三件事：把文件挂成"按区间取字节"的供料口、把产物流式写进 staging、
 * 以及失败时把半成品删掉 —— 不留一份装着坏数据的文件冒充成品。
 */
class ArchiveEngine {

    /** 多份文件压成一个包。名字按选中顺序进包，重名自动编号。 */
    fun pack(items: List<WorkItem>, operation: Operation.PackArchive, staging: (String) -> File): EngineOutput {
        if (operation.password.isNotBlank()) return packEncrypted(items, operation.password, staging)
        val names = ArchivePlan.uniqueNames(items.map { OutputNaming.sanitize(it.name) })
        val target = staging("zip")
        val sink = FileZipSink(target)
        try {
            ZipWriter.writeTo(
                items.mapIndexed { index, item ->
                    ZipItem(names[index], item.size, item.file.lastModified()) { item.file.inputStream() }
                },
                sink,
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            runCatching { sink.close() }
        }
        val plain = items.sumOf { it.size }
        return EngineOutput(
            OutputNaming.tagged(items.first().name, "打包", "zip"),
            target,
            "${items.size} 份文件 · ${SizeInput.format(plain)} → ${SizeInput.format(target.length())}",
        )
    }

    /**
     * 解开一个包，一次给出一批产物。
     *
     * 目录结构**不保留**：解出来的文件平铺在工作台里，条目路径压进文件名（`照片/猫.jpg` →
     * `包名_照片_猫.jpg`）。这一条顺带把 `../../` 那类路径穿越变成结构性不可能 ——
     * 我们从不拿条目名去拼路径，也就没有"逃出去写到别处"这回事。
     */
    fun unpack(item: WorkItem, operation: Operation.UnpackArchive, staging: (String) -> File): List<EngineOutput> {
        val encrypted = runCatching { ZipFile(item.file).isEncrypted }.getOrDefault(false)
        if (encrypted) {
            require(operation.password.isNotBlank()) { "这个 ZIP 有密码，先把密码填上" }
            return unpackZipWithPassword(item, operation.password, staging)
        }
        val slices = FileSlices(item.file)
        return try {
            val plan = ArchivePlan.plan(ZipReader.read(slices, item.file.length()))
            if (plan is UnpackPlan.Refused) error(plan.reason)
            plan as UnpackPlan.Go
            val note = buildString {
                append("解压自 ${item.name}")
                ArchivePlan.skippedNote(plan.skipped).takeIf { it.isNotBlank() }?.let { append("；$it") }
            }
            val names = ArchivePlan.uniqueNames(plan.keep.map { ArchivePlan.outputName(item.name, it.name) })
            plan.keep.mapIndexed { index, entry ->
                val output = names[index]
                val file = staging(OutputNaming.extension(output, "bin"))
                try {
                    // 体积闸套在搬运途中：目录里报的数可能是谎报的，等校验和核出来时早就写满了
                    java.io.FileOutputStream(file).use { target -> writeEntry(entry, slices, LimitedStream(target)) }
                    EngineOutput(output, file, note)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
        } finally {
            runCatching { slices.close() }
        }
    }
    /**
     * 打成 tar.gz。
     *
     * 先写一份 tar 再套 gzip：tar 是纯归档（不逐条压），把整份一次性压下去通常比
     * zip 那种"每条各压一段"小一点，而且条目路径与时间是写在头里的 —— Unix 侧的工具链认这个。
     */
    /** 7z：固态压缩与多卷交给库；这里按条目流式取字节、平铺命名。 */
    fun unpackSevenZ(item: WorkItem, operation: Operation.UnpackSevenZ, staging: (String) -> File): List<EngineOutput> {
        val builder = SevenZFile.builder().setFile(item.file)
        if (operation.password.isNotBlank()) builder.setPassword(operation.password.toCharArray())
        val archive = try {
            builder.get()
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "这个 7z 打不开${if (operation.password.isBlank()) "（有密码就填上）" else "，密码对不对？"}：${error.message}",
            )
        }
        try {
            val entries = ArrayList<Pair<String, ByteArray>>()
            var total = 0L
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory) continue
                require(entries.size < MAX_ENTRIES) { "条目太多（超过 $MAX_ENTRIES 个），拒绝继续解" }
                val bytes = readLimited(archive.getInputStream(entry), MAX_ENTRY_BYTES)
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "解出来超过 2GB，拒绝继续解（防压缩炸弹）" }
                entries += entry.name to bytes
            }
            require(entries.isNotEmpty()) { "这个 7z 里没有文件" }
            return emitEntries(item, entries, staging, "解压自 ${item.name}")
        } finally {
            runCatching { archive.close() }
        }
    }

    /** RAR4 / RAR5：junrar 负责解码；密码在构造时给。 */
    fun unpackRar(item: WorkItem, operation: Operation.UnpackRar, staging: (String) -> File): List<EngineOutput> {
        val archive = try {
            if (operation.password.isBlank()) Archive(item.file) else Archive(item.file, operation.password)
        } catch (error: Exception) {
            if (error is com.github.junrar.exception.UnsupportedRarV5Exception) {
                throw IllegalArgumentException("这是 RAR5，暂不支持；先用别的工具转成 ZIP 或 RAR4")
            }
            throw IllegalArgumentException(
                "这个 RAR 打不开${if (operation.password.isBlank()) "（有密码就填上）" else "，密码对不对？"}：${error.message}",
            )
        }
        try {
            val entries = ArrayList<Pair<String, ByteArray>>()
            var total = 0L
            while (true) {
                val header = archive.nextFileHeader() ?: break
                if (header.isDirectory) continue
                require(entries.size < MAX_ENTRIES) { "条目太多（超过 $MAX_ENTRIES 个），拒绝继续解" }
                require(header.fullUnpackSize <= MAX_ENTRY_BYTES) {
                    "「${header.fileName}」解出来超过 1GB，拒绝继续解"
                }
                val out = ByteArrayOutputStream()
                archive.extractFile(header, out)
                val bytes = out.toByteArray()
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "解出来超过 2GB，拒绝继续解（防压缩炸弹）" }
                entries += (header.fileName ?: "条目${entries.size + 1}") to bytes
            }
            require(entries.isNotEmpty()) { "这个 RAR 里没有文件" }
            return emitEntries(item, entries, staging, "解压自 ${item.name}")
        } catch (error: com.github.junrar.exception.RarException) {
            throw IllegalArgumentException(
                "这个 RAR 解不开${if (operation.password.isBlank()) "（有密码就填上）" else "，密码对不对？"}：${error.message}",
            )
        } finally {
            runCatching { archive.close() }
        }
    }

    /** 加密 ZIP：Zip4j 的 AES-256；文件名照旧压平。 */
    private fun packEncrypted(items: List<WorkItem>, password: String, staging: (String) -> File): EngineOutput {
        require(password.length >= 4) { "加密 ZIP 的密码至少 4 位" }
        val target = staging("zip")
        try {
            val names = ArchivePlan.uniqueNames(items.map { OutputNaming.sanitize(it.name) })
            val zip = ZipFile(target, password.toCharArray())
            val params = ZipParameters().apply {
                compressionMethod = CompressionMethod.DEFLATE
                setEncryptFiles(true)
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
            items.forEachIndexed { index, item ->
                params.fileNameInZip = names[index]
                zip.addFile(item.file, params)
            }
            return EngineOutput(
                OutputNaming.tagged(items.first().name, "加密打包", "zip"),
                target,
                "${items.size} 份 · AES-256 · 密码丢了就打不开",
            )
        } catch (error: Exception) {
            target.delete()
            throw IllegalStateException("加密打包没成：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun unpackZipWithPassword(item: WorkItem, password: String, staging: (String) -> File): List<EngineOutput> {
        val zip = ZipFile(item.file, password.toCharArray())
        val headers = try {
            zip.fileHeaders.filter { !it.isDirectory }
        } catch (error: Exception) {
            throw IllegalArgumentException("这个 ZIP 打不开（密码对不对？）：${error.message}")
        }
        require(headers.isNotEmpty()) { "这个 ZIP 里没有文件" }
        val entries = ArrayList<Pair<String, ByteArray>>()
        var total = 0L
        headers.forEach { header ->
            require(entries.size < MAX_ENTRIES) { "条目太多（超过 $MAX_ENTRIES 个），拒绝继续解" }
            val bytes = readLimited(zip.getInputStream(header), MAX_ENTRY_BYTES)
            total += bytes.size
            require(total <= MAX_TOTAL_BYTES) { "解出来超过 2GB，拒绝继续解（防压缩炸弹）" }
            entries += (header.fileName ?: "条目${entries.size + 1}") to bytes
        }
        return emitEntries(item, entries, staging, "解压自 ${item.name}（用了密码）")
    }

    private fun emitEntries(
        item: WorkItem,
        entries: List<Pair<String, ByteArray>>,
        staging: (String) -> File,
        note: String,
    ): List<EngineOutput> {
        val names = ArchivePlan.uniqueNames(entries.map { ArchivePlan.outputName(item.name, it.first.replace('\\', '/')) })
        return entries.mapIndexed { index, (_, bytes) ->
            val name = names[index]
            EngineOutput(
                name,
                staging(OutputNaming.extension(name, "bin")).apply { writeBytes(bytes) },
                if (index == 0) note else null,
            )
        }
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) throw IllegalArgumentException("单个条目解出来超过 ${max / (1024 * 1024)} MB，拒绝继续解")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun packTar(items: List<WorkItem>, operation: Operation.PackTarGz, staging: (String) -> File): EngineOutput {
        require(items.isNotEmpty()) { "没有可选中要打包的文件" }
        val names = ArchivePlan.uniqueNames(items.map { OutputNaming.sanitize(it.name) })
        val plain = items.sumOf { it.size }
        val tar = staging("tar")
        var target: File? = null
        try {
            java.io.FileOutputStream(tar).use { sink ->
                Tar.writeTo(
                    items.mapIndexed { index, item ->
                        TarItem(names[index], item.size, item.file.lastModified()) { item.file.inputStream() }
                    },
                    sink,
                )
            }
            target = staging("tar.gz")
            java.io.FileOutputStream(target).use { sink ->
                tar.inputStream().use { source -> Gzip.gzip(source, sink, name = "${OutputNaming.stem(items.first().name)}.tar") }
            }
            return EngineOutput(
                OutputNaming.tagged(items.first().name, "打包", "tar.gz"),
                target,
                "${items.size} 份文件 · ${SizeInput.format(plain)} → ${SizeInput.format(target.length())}（整份一起压）",
            )
        } catch (error: Exception) {
            target?.delete()
            throw error
        } finally {
            tar.delete()
        }
    }

    /**
     * 解开 tar / tar.gz / 单个 .gz。
     *
     * `.gz` 里装的是不是一份 tar **看内容**：第一块头校验和对得上才算。是 tar 就照 zip 那套
     * 规矩平铺出多份；不是就把那一个文件交回去（名字优先用 gzip 头里记的原名，那比 `x.gz` 去掉
     * 一个后缀准得多）。解压上限在搬运途中就卡住，不留"解到一半 OOM"这种死法。
     */
    fun untar(item: WorkItem, operation: Operation.UntarArchive, staging: (String) -> File): List<EngineOutput> {
        val notes = ArrayList<String>()
        val gzipped = isGzip(item)
        val source = if (gzipped) staging("tar") else item.file
        if (gzipped) {
            java.io.FileOutputStream(source).use { sink ->
                val written = runCatching {
                    Gzip.ungzip(java.io.BufferedInputStream(item.file.inputStream()), LimitedStream(sink))
                }.getOrElse { error ->
                    source.delete()
                    throw IllegalStateException("这个 gz 解不开：${error.message ?: "内容坏了"}")
                }
                notes += "先解了 gzip（${SizeInput.format(item.size)} → ${SizeInput.format(written)}）"
            }
        }
        val head = headOf(source)
        if (!Tar.looksLikeTar(head)) {
            if (!gzipped) error("这份文件的第一块头读不出 tar 条目（名字或校验和不对），它不是 tar 归档")
            // 单个文件被 gzip 的情形：交回那一份，名字优先用 gzip 头里记的原名（比去掉 .gz 准）
            val stored = Gzip.readHeader(headOf(item.file))?.name?.substringAfterLast('/')
            val stem = item.name.removeSuffix(".gz").removeSuffix(".GZ").ifBlank { item.name }
            val name = OutputNaming.sanitize(stored ?: stem)
            val output = if (name.contains('.')) name else "$name.bin"
            return listOf(
                EngineOutput(
                    output,
                    source,
                    (notes + "这是一份 gzip 单文件（不是 tar 归档）· 解压自 ${item.name}").joinToString(" · "),
                ),
            )
        }
        return try {
            unpackTar(item, source, notes, staging)
        } finally {
            if (source !== item.file) source.delete()
        }
    }

    private fun unpackTar(
        item: WorkItem,
        source: File,
        notes: List<String>,
        staging: (String) -> File,
    ): List<EngineOutput> {
        val slices = FileSlices(source)
        return try {
            val archive = Tar.read(slices, source.length())
            val plan = ArchivePlan.plan(archive)
            if (plan is UnpackPlan.Refused) {
                if (archive.notes.isNotEmpty()) error((notes + archive.notes).joinToString(" · ") + " · " + plan.reason)
                error(plan.reason)
            }
            plan as UnpackPlan.Go
            val parts = ArrayList<String>()
            parts += "解压自 ${item.name}"
            parts += notes
            parts += archive.notes
            ArchivePlan.skippedNote(plan.skipped).takeIf { it.isNotBlank() }?.let { parts += it }
            if (plan.keep.any { it.name.contains('/') }) parts += "目录结构压进文件名了（工作台是平的）"
            val note = parts.joinToString(" · ")
            val names = ArchivePlan.uniqueNames(plan.keep.map { ArchivePlan.outputName(item.name, it.name) })
            plan.keep.mapIndexed { index, entry ->
                val output = names[index]
                val file = staging(OutputNaming.extension(output, "bin"))
                try {
                    // 体积闸套在搬运途中：目录里报的数可能是谎报的，等校验和核出来时早就写满了
                    java.io.FileOutputStream(file).use { target -> writeEntry(entry, slices, LimitedStream(target)) }
                    EngineOutput(output, file, note)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
        } finally {
            runCatching { slices.close() }
        }
    }

    private fun isGzip(item: WorkItem): Boolean {
        val head = ByteArray(2)
        item.file.inputStream().use { input -> if (input.read(head) < 2) return false }
        return Gzip.isGzip(head)
    }

    /** 两种容器的条目都从各自的读取器里按区间取字节：解压那套流程只有一份。 */
    private fun writeEntry(entry: PackagedEntry, slices: ByteSlice, target: java.io.OutputStream) {
        when (entry) {
            is ZipEntry -> ZipReader.writeDataOf(entry, slices, target)
            is TarEntry -> Tar.writeDataOf(entry, slices, target)
            else -> error("认不出的条目类型：${entry.name}")
        }
    }

    /** 头一块够判断了：tar 的名字与校验和都落在前 512 字节里。 */
    private fun headOf(file: File): ByteArray {
        val head = ByteArray(512)
        file.inputStream().use { input -> input.read(head) }
        return head
    }

    /** 解压途中的体积闸：写够了上限就报错，不给"解到一半把进程顶掉"的机会。 */
    private class LimitedStream(sink: java.io.OutputStream) : java.io.FilterOutputStream(sink) {
        private var written = 0L

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written += length
            if (written > ArchivePlan.MAX_TOTAL_BYTES) {
                throw IllegalStateException("解压后有 ${SizeInput.format(written)}，超过 ${SizeInput.format(ArchivePlan.MAX_TOTAL_BYTES)} 的上限")
            }
            out.write(bytes, offset, length)
        }
    }
}

/** staging 文件上的回写落点：本地头的长度要压完才能回填，所以得能 seek。 */
internal class FileZipSink(file: File) : ZipSink, AutoCloseable {
    private val handle = RandomAccessFile(file, "rw")

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        handle.write(bytes, offset, length)
    }

    override fun position(): Long = handle.filePointer

    override fun seek(position: Long) {
        handle.seek(position)
    }

    override fun close() {
        handle.close()
    }
}
