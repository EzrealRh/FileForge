package com.fileforge.converter.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fileforge.core.model.FileKind

/**
 * 把工作台里的成品写进手机存储（分区存储写法，不需要运行时权限），落在
 * `/storage/emulated/0/文件工坊/` —— 跟其他 App 默认的顶层目录一样，
 * 文件管理器直接翻得到，社交 App也选得到。
 *
 * 按类型散进 Pictures / Movies / Documents 会被嫌"不方便找"；只留在应用私有目录时，
 * 用户看到的是 /data/user/0/... 那种根本进不去的地址。所以这里只认一个顶层目录，
 * 个别 ROM 不让建就退回 Download/文件工坊/。API 29 以下没有这套写法，交给调用方回落到"选文件夹导出"。
 */
class MediaStorePublisher(private val context: Context) {

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    class Result(val paths: Map<String, String>, val failures: List<String>) {
        val saved: Int get() = paths.size
        val empty: Boolean get() = paths.isEmpty() && failures.isEmpty()
    }

    fun save(items: List<WorkItem>): Result {
        val paths = LinkedHashMap<String, String>()
        val failures = ArrayList<String>()
        items.forEach { item ->
            runCatching { insert(item) }
                .onSuccess { path -> paths[item.name] = path }
                .onFailure { failures += "${item.name}：${it.message}" }
        }
        return Result(paths, failures)
    }

    /** 返回文件在手机里的真实位置。 */
    private fun insert(item: WorkItem): String {
        val resolver = context.contentResolver
        var lastError: Throwable? = null
        for (directory in listOf(PRIMARY_DIR, FALLBACK_DIR)) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(item))
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

    /** MediaStore 自己报的地址最可信；某些 ROM 不填 DATA 才退回拼接。 */
    private fun realPath(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.startsWith(EXTERNAL_ROOT) } else null
        }
    }.getOrNull()

    private fun mimeOf(item: WorkItem): String = when (item.kind) {
        FileKind.Pdf -> "application/pdf"
        FileKind.Gif -> "image/gif"
        FileKind.Png -> "image/png"
        FileKind.Jpeg -> "image/jpeg"
        FileKind.WebP -> "image/webp"
        FileKind.Bmp -> "image/bmp"
        FileKind.Heic -> "image/heic"
        FileKind.Avif -> "image/avif"
        FileKind.Mp4 -> "video/mp4"
        FileKind.WebM -> "video/webm"
        FileKind.Mkv -> "video/x-matroska"
        FileKind.QuickTime -> "video/quicktime"
        FileKind.Zip -> "application/zip"
        FileKind.Unknown -> "application/octet-stream"
    }

    /** 给用户看的落点说明。 */
    val locationLabel: String get() = "$EXTERNAL_ROOT/${PRIMARY_DIR.trimEnd('/')}"

    private companion object {
        const val VOLUME = "external"
        const val PRIMARY_DIR = "文件工坊/"
        const val FALLBACK_DIR = "Download/文件工坊/"
        val EXTERNAL_ROOT: String get() = Environment.getExternalStorageDirectory().absolutePath
    }
}
