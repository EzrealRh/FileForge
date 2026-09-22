package com.fileforge.converter.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.max

/**
 * 取视频帧。`getScaledFrameAtTime` 是 API 27 才有的，minSdk 26 上直接调会 NoSuchMethodError，
 * 所以低版本先解全图再自己缩，两边给出同样的"最长边不超过"语义。
 */
fun MediaMetadataRetriever.frameAt(timeUs: Long, width: Int, height: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 27) {
        return getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
    }
    val raw = getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
    val ratio = minOf(width.toFloat() / raw.width, height.toFloat() / raw.height)
    if (ratio >= 1f) return raw
    val scaled = Bitmap.createScaledBitmap(
        raw,
        (raw.width * ratio).toInt().coerceAtLeast(1),
        (raw.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== raw) raw.recycle()
    return scaled
}

/** 缩略图用：只解到最长边 longEdge，按 2 的幂降采样。 */
fun decodeStillFrame(file: File, longEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > longEdge) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

/** 打开 retriever 拿一帧，用完立刻释放。 */
fun videoFrameAt(file: File, timeUs: Long, width: Int, height: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(file.absolutePath)
        retriever.frameAt(timeUs, width, height)
    }.getOrNull().also { runCatching { retriever.release() } }
}
