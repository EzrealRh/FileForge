package com.fileforge.converter.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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

private const val THUMB_LONG_EDGE = 200

/** 缩略图只为认文件，按 2 的幂降采样，不解全图。 */
@Composable
fun FileThumbnail(item: WorkItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = produceImage(item.file, item)
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(52.dp), contentScale = ContentScale.Crop)
        } else {
            Icon(imageVector = iconFor(item), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun produceImage(file: File, item: WorkItem): ImageBitmap? {
    val needsPixel = item.kind.isImage || item.kind.isVideo
    if (!needsPixel) return null
    return produceState<ImageBitmap?>(initialValue = null, file.absolutePath, file.length()) {
        value = runCatching {
            if (item.kind.isVideo) videoFrame(file) else stillFrame(file)
        }.getOrNull()
    }.value
}

private fun stillFrame(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > THUMB_LONG_EDGE) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private fun videoFrame(file: File): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(file.absolutePath)
        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, THUMB_LONG_EDGE, THUMB_LONG_EDGE)?.asImageBitmap()
    }.getOrNull().also { runCatching { retriever.release() } }
}

@Composable
private fun iconFor(item: WorkItem) = when {
    item.kind == FileKind.Pdf -> Icons.Outlined.PictureAsPdf
    item.kind.isVideo -> Icons.Outlined.Movie
    item.kind == FileKind.Gif -> Icons.Outlined.Movie
    item.kind.isImage -> Icons.Outlined.Description
    else -> Icons.Outlined.InsertDriveFile
}
