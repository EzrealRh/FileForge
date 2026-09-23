package com.fileforge.converter.data

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.fileforge.core.model.BatchLineage
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
    /** 属于哪一批：同一次导入的算一批，这批转出来的结果也归这批。 */
    val groupId: Long = BatchLineage.NONE,
) {
    val sizeLabel: String get() = SizeInput.format(size)
    val extension: String get() = OutputNaming.extension(name, kind.name.lowercase())
    val stem: String get() = OutputNaming.stem(name)
}

/** 一批文件：一次导入，或一次跨批操作产生的新集合。 */
data class Batch(val id: Long, val title: String, val addedAt: Long)

class Workspace(context: Context) {

    private val root = File(context.filesDir, "workspace").apply { mkdirs() }
    private val staging = File(context.cacheDir, "staging").apply { mkdirs() }

    /** PDFBox 的中间对象落这里；进程被杀掉时它自己不清，所以启动时跟着 staging 一起扫。 */
    val pdfScratch = File(context.cacheDir, "pdf").apply { mkdirs() }
    private val ids = AtomicLong(0)
    private val batchIds = AtomicLong(0)

    /** 内存里这份表就是工作台的真相，id 必须稳定，否则选中状态会错位。 */
    private val items = LinkedHashMap<Long, WorkItem>()
    private val batches = LinkedHashMap<Long, Batch>()

    /** 批次关系写在 workspace/.batches 里，重启后还能按批次分组（文件名当外键，id 每次重启会变）。 */
    private val batchBook = File(root, ".batches")

    init {
        val membership = readBook()
        root.listFiles()?.filter { !it.name.startsWith(".") }?.sortedBy { it.lastModified() }?.forEach { file ->
            val known = membership[file.name]
            val group = known?.let { entry ->
                batches.putIfAbsent(entry.id, Batch(entry.id, entry.title, entry.addedAt))
                entry.id
            } ?: newBatch(file.name, file.lastModified())
            val item = WorkItem(
                id = ids.incrementAndGet(),
                name = file.name,
                file = file,
                kind = sniff(file),
                size = file.length(),
                addedAt = file.lastModified(),
                groupId = group,
            )
            items[item.id] = item
        }
        saveBook()
    }

    private class Entry(val id: Long, val title: String, val addedAt: Long)

    private fun readBook(): Map<String, Entry> = runCatching {
        batchBook.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            parts[3] to Entry(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[2], parts[1].toLongOrNull() ?: 0L)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveBook() {
        val lines = items.values.mapNotNull { item ->
            val batch = batches[item.groupId] ?: return@mapNotNull null
            listOf(batch.id.toString(), item.addedAt.toString(), batch.title, item.name).joinToString("\t")
        }
        runCatching { batchBook.writeText(lines.joinToString("\n")) }
    }

    private fun newBatch(title: String, at: Long = System.currentTimeMillis()): Long {
        val id = batchIds.incrementAndGet()
        batches[id] = Batch(id, OutputNaming.stem(title).take(40), at)
        return id
    }

    fun batches(): Map<Long, Batch> = synchronized(items) { batches.toMap() }

    /** 另起一批（跨批操作时用），返回批号给后续 adopt。 */
    fun startBatch(title: String): Long = newBatch(title)

    fun list(): List<WorkItem> = synchronized(items) { items.values.sortedByDescending { it.addedAt } }

    fun find(id: Long): WorkItem? = synchronized(items) { items[id] }

    fun import(uri: Uri, resolver: ContentResolver): WorkItem = importAll(listOf(uri), resolver).first()

    /** 一次选中的多个文件算一批：先落临时文件，再一起收进同一个批次。 */
    fun importAll(uris: List<Uri>, resolver: ContentResolver): List<WorkItem> {
        val staged = ArrayList<Pair<String, File>>()
        uris.forEach { uri ->
            runCatching {
                val display = displayNameOf(resolver, uri)
                val tmp = File(staging, "import-${System.nanoTime()}.bin")
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "打不开这个文件" }
                    tmp.outputStream().use { input.copyTo(it) }
                }
                staged += (if (display.contains('.')) display else "$display.bin") to tmp
            }
        }
        require(staged.isNotEmpty()) { "一个文件都没拿进来" }
        val group = newBatch(staged.first().first)
        val taken = namesInUse()
        return staged.map { (name, tmp) -> adopt(tmp, OutputNaming.unique(name, taken), null, group) }
    }

    /**
     * 把 staging 里的成品挪进工作台并登记。
     * [groupId] 给 0 表示另起一批（转换产物要传来源那一批进来，见 [BatchLineage.inherit]）。
     */
    fun adopt(source: File, name: String, fromOperation: String?, groupId: Long = BatchLineage.NONE): WorkItem {
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
            groupId = if (groupId == BatchLineage.NONE) newBatch(name) else groupId,
        )
        synchronized(items) { items[item.id] = item }
        saveBook()
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
        saveBook()
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
