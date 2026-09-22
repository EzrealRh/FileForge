package com.fileforge.converter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fileforge.core.model.FileKind
import com.fileforge.converter.data.WorkItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_LONG_EDGE = 200

/**
 * 缩略图缓存。列表来回滚会反复解同一批图，不缓存就是滚动卡顿加 native 内存抖动。
 * 位图统一交给缓存持有、不做 per-entry recycle，避免还有引用时把图回收掉。
 */
private val Thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

@Composable
fun FileThumbnail(item: WorkItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = rememberThumbnail(item)
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(52.dp), contentScale = ContentScale.Crop)
        } else {
            Icon(imageVector = iconFor(item), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberThumbnail(item: WorkItem): ImageBitmap? {
    if (!item.kind.isImage && !item.kind.isVideo) return null
    val key = item.file.absolutePath + ":" + item.file.length()
    return produceState<ImageBitmap?>(initialValue = null, key) {
        val cached = Thumbnails.get(key)
        if (cached != null) {
            value = cached.asImageBitmap()
        } else {
            val bitmap = withContext(Dispatchers.IO) { decode(item.file, item.kind) }
            if (bitmap != null) Thumbnails.put(key, bitmap)
            value = bitmap?.asImageBitmap()
        }
    }.value
}

private fun decode(file: File, kind: FileKind): Bitmap? = if (kind.isVideo) videoFrame(file) else stillFrame(file)

private fun stillFrame(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > THUMB_LONG_EDGE) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

private fun videoFrame(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(file.absolutePath)
        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, THUMB_LONG_EDGE, THUMB_LONG_EDGE)
    }.getOrNull().also { runCatching { retriever.release() } }
}

@Composable
private fun iconFor(item: WorkItem) = when {
    item.kind == FileKind.Pdf -> Icons.Outlined.PictureAsPdf
    item.kind.isVideo -> Icons.Outlined.Movie
    item.kind.isImage -> Icons.Outlined.Description
    else -> Icons.Outlined.InsertDriveFile
}
