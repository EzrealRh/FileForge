package com.fileforge.converter.data

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.fileforge.core.model.FileKind
import com.fileforge.core.model.FileTypeSniffer
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.util.SizeInput
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 工作台里的一份文件。所有读写都走应用私有目录下的真实 File，
 * PDFBox 和 MediaCodec 都能用文件描述符工作，转换结果也能直接再被选中继续操作。
 */
data class WorkItem(
    val id: Long,
    val name: String,
    val file: File,
    val kind: FileKind,
    val size: Long,
    val addedAt: Long,
    /** 这份结果是哪个操作产出的，界面上用来标记，也用于链式操作时提示来源。 */
    val fromOperation: String? = null,
    /** 已经复制到手机共享存储（/storage/emulated/0/文件工坊）后的真实位置；null 表示还没放出去。 */
    val sharedPath: String? = null,
) {
    val sizeLabel: String get() = SizeInput.format(size)
    val extension: String get() = OutputNaming.extension(name, kind.name.lowercase())
    val stem: String get() = OutputNaming.stem(name)
}

class Workspace(context: Context) {

    private val root = File(context.filesDir, "workspace").apply { mkdirs() }
    private val staging = File(context.cacheDir, "staging").apply { mkdirs() }

    /** PDFBox 的中间对象落这里；进程被杀掉时它自己不清，所以启动时跟着 staging 一起扫。 */
    val pdfScratch = File(context.cacheDir, "pdf").apply { mkdirs() }
    private val ids = AtomicLong(0)

    /** 内存里这份表就是工作台的真相，id 必须稳定，否则选中状态会错位。 */
    private val items = LinkedHashMap<Long, WorkItem>()

    init {
        root.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
            val item = WorkItem(
                id = ids.incrementAndGet(),
                name = file.name,
                file = file,
                kind = sniff(file),
                size = file.length(),
                addedAt = file.lastModified(),
            )
            items[item.id] = item
        }
    }

    fun list(): List<WorkItem> = synchronized(items) { items.values.sortedByDescending { it.addedAt } }

    fun find(id: Long): WorkItem? = synchronized(items) { items[id] }

    fun import(uri: Uri, resolver: ContentResolver): WorkItem {
        val display = displayNameOf(resolver, uri)
        val taken = namesInUse()
        val tmp = File(staging, "import-${System.nanoTime()}.bin")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "打不开这个文件" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        val name = if (display.contains('.')) display else "$display.bin"
        return adopt(tmp, OutputNaming.unique(name, taken), null)
    }

    /** 把 staging 里的成品挪进工作台并登记。 */
    fun adopt(source: File, name: String, fromOperation: String?): WorkItem {
        // 重名必须避让：POSIX rename 会静默覆盖，两个条目指向同一个文件就全乱了
        val finalName = OutputNaming.unique(name, namesInUse())
        val target = File(root, finalName)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        val item = WorkItem(
            id = ids.incrementAndGet(),
            name = finalName,
            file = target,
            kind = sniff(target),
            size = target.length(),
            addedAt = System.currentTimeMillis(),
            fromOperation = fromOperation,
        )
        synchronized(items) { items[item.id] = item }
        return item
    }

    /** 给引擎一个还没登记的临时落盘位置，写完再由 [adopt] 收进工作台。 */
    fun newStagingFile(suffix: String): File =
        File(staging, "out-${System.nanoTime()}-${ids.incrementAndGet()}.$suffix")

    /** 记一下这份成品在共享存储里的位置，只改内存表，不动文件。 */
    fun markShared(item: WorkItem, path: String): WorkItem {
        val updated = item.copy(sharedPath = path)
        synchronized(items) { if (items.containsKey(item.id)) items[item.id] = updated }
        return updated
    }

    fun remove(item: WorkItem) {
        synchronized(items) { items.remove(item.id) }
        item.file.delete()
    }

    /** 失败或中断留下的临时文件，启动时清一次就够，不用每次操作都扫。 */
    fun purgeStaging() {
        staging.listFiles()?.forEach { it.delete() }
        pdfScratch.listFiles()?.forEach { it.delete() }
    }

    fun namesInUse(): Set<String> = list().map { it.name }.toSet()

    fun usedSpace(): Long = list().sumOf { it.size }

    private fun sniff(file: File): FileKind = runCatching {
        val header = ByteArray(64)
        file.inputStream().use { stream ->
            val read = stream.read(header)
            if (read in 0 until header.size) header.copyOf(read) else header
        }
        FileTypeSniffer.sniff(header)
    }.getOrDefault(FileKind.Unknown)

    companion object {
        fun displayNameOf(resolver: ContentResolver, uri: Uri): String {
            if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
            val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?: return uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
            cursor.use {
                return if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else "file.bin"
            }
        }
    }
}
