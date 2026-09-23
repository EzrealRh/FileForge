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

/** 显示尺寸（已按旋转角交换过宽高）、时长和旋转角本身。 */
class VideoDisplayMeta(val width: Int, val height: Int, val durationUs: Long, val rotation: Int)

/** 抽帧和解码器都要靠这份元数据定尺寸与方向，所以只在这里读一次。 */
fun videoDisplayMeta(file: File): VideoDisplayMeta {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val rawWidth = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val rawHeight = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val durationMs = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        require(rawWidth > 0 && rawHeight > 0) { "读不到画面尺寸，这个视频可能损坏" }
        val swapped = rotation == 90 || rotation == 270
        return VideoDisplayMeta(
            if (swapped) rawHeight else rawWidth,
            if (swapped) rawWidth else rawHeight,
            durationMs * 1000,
            rotation,
        )
    } finally {
        runCatching { retriever.release() }
    }
}

private fun MediaMetadataRetriever.metadata(key: Int): String? = runCatching { extractMetadata(key) }.getOrNull()
