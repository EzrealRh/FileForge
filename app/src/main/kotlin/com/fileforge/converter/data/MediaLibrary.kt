package com.fileforge.converter.data

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fileforge.converter.engine.frameAt
import android.util.LruCache

/** 媒体库里的一条。`size` 可能为 0（部分机型不给 SIZE 列），此时不显示体积。 */
data class MediaEntry(
    val uri: Uri,
    val name: String,
    val size: Long,
    val isVideo: Boolean,
    val addedAt: Long,
)

/**
 * 直接查系统媒体库，省掉"每次都要在系统文件管理器里一层层翻"这一步。
 * 图片走 Images、视频走 Video，合并后按加入时间倒序。
 */
class MediaLibrary(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun load(limit: Int = 800): List<MediaEntry> {
        val entries = ArrayList<MediaEntry>()
        entries += query(MediaStore.Images.Media.getContentUri(VOLUME), isVideo = false)
        entries += query(MediaStore.Video.Media.getContentUri(VOLUME), isVideo = true)
        return entries.sortedByDescending { it.addedAt }.take(limit)
    }

    private fun query(collection: Uri, isVideo: Boolean): List<MediaEntry> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val result = ArrayList<MediaEntry>()
        val cursor: Cursor? = runCatching {
            context.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        }.getOrNull()
        cursor.use { found ->
            if (found == null || !found.moveToFirst()) return emptyList()
            val idColumn = found.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = found.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = found.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateColumn = found.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            do {
                val id = found.getLong(idColumn)
                result += MediaEntry(
                    uri = Uri.withAppendedPath(collection, id.toString()),
                    name = if (nameColumn >= 0) found.getString(nameColumn) ?: "未命名" else "未命名",
                    size = if (sizeColumn >= 0 && !found.isNull(sizeColumn)) found.getLong(sizeColumn) else 0L,
                    isVideo = isVideo,
                    addedAt = if (dateColumn >= 0 && !found.isNull(dateColumn)) found.getLong(dateColumn) else 0L,
                )
            } while (found.moveToNext())
        }
        return result
    }

    /** 网格缩略图：系统给 256 档，26 以上优先用 loadThumbnail，失败再自己解。 */
    fun thumbnail(entry: MediaEntry, edge: Int = 220): Bitmap? {
        val key = entry.uri.toString()
        cache.get(key)?.let { return it }
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(entry.uri, android.util.Size(edge, edge), null)
            } else {
                stillBitmap(entry, edge)
            }
        }.getOrNull() ?: stillBitmap(entry, edge)
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun stillBitmap(entry: MediaEntry, edge: Int): Bitmap? = runCatching {
        if (entry.isVideo) {
            val descriptor = context.contentResolver.openFileDescriptor(entry.uri, "r") ?: return null
            val retriever = MediaMetadataRetriever()
            try {
                descriptor.use { retriever.setDataSource(it.fileDescriptor) }
                // frameAt 内部按 API 版本分支，26 上不会撞上 API 27 方法
                retriever.frameAt(0L, edge, edge)
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
            context.contentResolver.openInputStream(entry.uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    private companion object {
        const val VOLUME = "external"
    }
}
