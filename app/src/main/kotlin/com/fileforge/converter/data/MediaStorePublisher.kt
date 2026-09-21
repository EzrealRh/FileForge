package com.fileforge.converter.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fileforge.core.model.FileKind

/**
 * 把工作台里的成品直接写进系统相册/文档目录（分区存储，不需要运行时权限），
 * 这样微信、小红书在选图页立刻就能挑到，不用先去文件管理器里绕一圈。
 * API 29 以下没有这套写法，交给调用方回落到"选文件夹导出"。
 */
class MediaStorePublisher(private val context: Context) {

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    class Result(val saved: Int, val failures: List<String>) {
        val empty: Boolean get() = saved == 0 && failures.isEmpty()
    }

    fun save(items: List<WorkItem>): Result {
        var saved = 0
        val failures = ArrayList<String>()
        items.forEach { item ->
            runCatching { insert(item) }.onSuccess { saved++ }.onFailure { failures += "${item.name}：${it.message}" }
        }
        return Result(saved, failures)
    }

    private fun insert(item: WorkItem) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(item))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(item))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collectionFor(item), values) ?: error("系统拒绝了写入请求")
        resolver.openOutputStream(uri).use { output ->
            requireNotNull(output) { "打不开输出流" }
            item.file.inputStream().use { it.copyTo(output) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun collectionFor(item: WorkItem): Uri = when {
        item.kind.isImage -> MediaStore.Images.Media.getContentUri(VOLUME)
        item.kind.isVideo -> MediaStore.Video.Media.getContentUri(VOLUME)
        else -> MediaStore.Files.getContentUri(VOLUME)
    }

    private fun relativePath(item: WorkItem): String = when {
        item.kind.isImage -> "Pictures/$FOLDER/"
        item.kind.isVideo -> "Movies/$FOLDER/"
        else -> "Documents/$FOLDER/"
    }

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

    private companion object {
        const val VOLUME = "external"
        const val FOLDER = "文件工坊"
    }
}
