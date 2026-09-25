package com.fileforge.converter.engine

import com.fileforge.core.archive.ArchivePlan
import com.fileforge.core.archive.ByteSlice
import com.fileforge.core.archive.UnpackPlan
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
                    java.io.FileOutputStream(file).use { target -> ZipReader.writeDataOf(entry, slices, target) }
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
}

/** 一个文件背后的"按区间取字节"：中央目录在末尾、条目数据散在各处，整份搬进堆没道理。 */

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
