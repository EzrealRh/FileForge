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
import java.io.File
import java.io.RandomAccessFile

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
                    java.io.FileOutputStream(file).use { target -> writeEntry(entry, slices, target) }
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
                    java.io.FileOutputStream(file).use { target -> writeEntry(entry, slices, target) }
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
