package com.fileforge.converter.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fileforge.core.naming.OutputNaming
import java.io.File

/**
 * 把工作台里的成品写进手机存储，落在**一个顶层目录** `/storage/emulated/0/文件工坊/`，
 * 文件管理器第一屏就翻得到，别的 App 的文件选择器也默认从这里找。
 *
 * 两条路：拿到"所有文件访问权限"就直接建顶层目录（跟其他 App 一样）；没拿到就走
 * MediaStore 分区存储写法 —— 真机实测这条会被系统塞回 `Download/文件工坊/`，
 * 所以调用方可以在合适时机提示用户去开那个权限，别默认用户知道成品为什么在 Download 里。
 *
 * 不按类型散进 Pictures / Movies / Documents，也不只留在应用私有目录：那样用户
 * 拿到的是 /data/user/0/... 这种根本进不去的地址。API 29 以下连 MediaStore 这套都没有，
 * 交给调用方回落到"选文件夹导出"。
 */
class MediaStorePublisher(private val context: Context) {

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    class Result(val paths: Map<String, String>, val failures: List<String>, val topLevel: Boolean) {
        val saved: Int get() = paths.size
        val empty: Boolean get() = paths.isEmpty() && failures.isEmpty()
    }

    /** 成品到底落在顶层还是 Download 里，决定要不要提示开权限。 */
    val topLevelGranted: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    fun save(items: List<WorkItem>): Result {
        val paths = LinkedHashMap<String, String>()
        val failures = ArrayList<String>()
        var topLevel = true
        items.forEach { item ->
            runCatching { insert(item) }
                .onSuccess { path ->
                    paths[item.name] = path
                    // 结论要的是"是不是**全都**落在了顶层"：只记最后一个的话，
                    // 一半顶层一半 Download 的混批会给出错的答案，提示要么漏了要么误报
                    topLevel = topLevel && path.startsWith(topLevelDir.absolutePath)
                }
                .onFailure { failures += "${item.name}：${it.message}" }
        }
        return Result(paths, failures, topLevel)
    }

    /** 顶层目录本身（有没有权限都能算出路径，建不建得成另说）。 */
    val topLevelDir: File get() = File(Environment.getExternalStorageDirectory(), TOP_LEVEL)

    /** 返回文件在手机里的真实位置。 */
    private fun insert(item: WorkItem): String {
        writeTopLevel(item)?.let { return it }
        val resolver = context.contentResolver
        var lastError: Throwable? = null
        for (directory in listOf(PRIMARY_DIR, FALLBACK_DIR)) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.kind.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(MediaStore.Files.getContentUri(VOLUME), values) }
                .onFailure { lastError = it }
                .getOrNull()
            if (uri == null) continue
            val written = runCatching {
                resolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "打不开输出流" }
                    item.file.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            if (written.isFailure) {
                // 写失败的行会一直以 pending 挂着，删掉，别在文件列表里留个空壳
                runCatching { resolver.delete(uri, null, null) }
                lastError = written.exceptionOrNull()
                continue
            }
            return realPath(uri) ?: "$EXTERNAL_ROOT/${directory.trimEnd('/')}/${item.name}"
        }
        throw lastError ?: error("系统拒绝了写入请求")
    }

    /**
     * 有"所有文件访问权限"就直接写顶层目录 —— MediaStore 那条路系统会把文件塞回
     * `Download/文件工坊/`，跟别的 App 的顶层目录不一样，用户找不到。
     * 写完扫一下媒体库，不然相册/文件选择器要等很久才看得见。
     */
    private fun writeTopLevel(item: WorkItem): String? {
        if (!topLevelGranted) return null
        val target = runCatching {
            val dir = topLevelDir
            if (!dir.exists() && !dir.mkdirs()) error("建不出 ${dir.absolutePath}")
            freeTarget(dir, item.name)
        }.getOrNull() ?: return null
        // 写一半失败（盘满、存储被拔）会把半份文件留在用户看得见的目录里冒充成品：
        // 删掉再交回 null，让 MediaStore 那条路接着试
        return runCatching {
            item.file.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            target.absolutePath
        }.onFailure { runCatching { target.delete() } }.getOrNull()
    }

    /**
     * 顶层直写没有 MediaStore 那套自动避让，同名会**静默覆盖上一次的成品** ——
     * 同一个文件转两次是很正常的操作。所以自己补序号，走 `:core` 里那份和
     * 工作台共用、且已被单测覆盖的命名规则。
     */
    private fun freeTarget(dir: File, requested: String): File {
        val taken = HashSet<String>()
        var name = requested
        while (File(dir, name).also { taken.add(name) }.exists()) {
            name = OutputNaming.unique(requested, taken)
        }
        return File(dir, name)
    }

    /** MediaStore 自己报的地址最可信；某些 ROM 不填 DATA 才退回拼接。 */
    private fun realPath(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.startsWith(EXTERNAL_ROOT) } else null
        }
    }.getOrNull()


    /** 给用户看的落点说明。 */
    val locationLabel: String get() = topLevelDir.absolutePath

    private companion object {
        const val VOLUME = "external"
        const val TOP_LEVEL = "文件工坊"
        const val PRIMARY_DIR = "文件工坊/"
        const val FALLBACK_DIR = "Download/文件工坊/"
        val EXTERNAL_ROOT: String get() = Environment.getExternalStorageDirectory().absolutePath
    }
}
