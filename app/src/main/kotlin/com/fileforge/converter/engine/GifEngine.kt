package com.fileforge.converter.engine

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.fileforge.core.gif.GifDecoder
import com.fileforge.core.gif.GifEncoder
import com.fileforge.core.gif.GifFrame
import com.fileforge.core.gif.GifImage
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * GIF 操作。解码后的帧要全部留在内存里才能合成全局调色板，所以这里对
 * "帧数 x 像素"设上限：超了就自动降尺寸/降帧数，并在结果说明里写清楚改了什么。
 */
class GifEngine(private val workspace: Workspace, private val images: ImageEngine) {

    private companion object {
        const val PIXEL_BUDGET = 14_000_000
        const val MAX_FRAMES = 400
    }

    fun compress(item: WorkItem, operation: Operation.CompressGif): EngineOutput {
        val source = GifDecoder.decode(item.file.readBytes(), pixelBudget = PIXEL_BUDGET)
        val requestedEdge = operation.maxEdge.takeIf { it > 0 } ?: max(source.width, source.height)
        val edgeAllowedByMemory = sqrt(PIXEL_BUDGET / max(1, source.frames.size).toDouble()).toInt()
        val edge = minOf(requestedEdge, max(16, edgeAllowedByMemory))

        val resized = resize(source, edge)
        val thinned = dropTo(resized, operation.targetFps)
        val bytes = GifEncoder(
            thinned.width,
            thinned.height,
            thinned.loopCount,
            operation.colors.coerceIn(2, 256),
        ).encode(thinned.frames)

        val notes = ArrayList<String>()
        if (source.truncated) notes += "帧太多，只处理了前 ${source.frames.size} 帧"
        if (edge < max(source.width, source.height)) notes += "边长→$edge"
        if (thinned.frames.size < source.frames.size) notes += "帧 ${source.frames.size}→${thinned.frames.size}"
        notes += "色→${operation.colors.coerceIn(2, 256)}"

        return EngineOutput(
            OutputNaming.tagged(item.name, "压缩", "gif"),
            workspace.newStagingFile("gif").apply { writeBytes(bytes) },
            "${SizeInput.format(bytes.size.toLong())} ← ${SizeInput.format(item.size)}（${notes.joinToString("，")}）",
        )
    }

    fun fromVideo(item: WorkItem, operation: Operation.VideoToGif): EngineOutput {
        val meta = videoMeta(item)
        val fps = operation.fps.coerceIn(1, 25)
        var frames = (meta.durationUs / 1_000_000.0 * fps).toInt().coerceIn(1, MAX_FRAMES)
        var edge = operation.maxEdge.coerceIn(64, 900)
        while (frames * edge * edge > PIXEL_BUDGET && edge > 64) {
            edge = (edge * 0.85f).roundToInt().coerceAtLeast(64)
        }
        if (frames * edge * edge > PIXEL_BUDGET) {
            frames = (PIXEL_BUDGET / (edge * edge)).coerceAtLeast(1)
        }

        val startUs = (operation.startSecond * 1_000_000).toLong().coerceIn(0, (meta.durationUs - 1).coerceAtLeast(0))
        val windowUs = if (operation.durationSecond > 0) {
            (operation.durationSecond * 1_000_000).toLong()
        } else {
            meta.durationUs - startUs
        }.coerceAtMost((meta.durationUs - startUs).coerceAtLeast(1))
        val stepUs = (windowUs / frames).coerceAtLeast(1)

        val scaleDown = edge.toFloat() / max(meta.width, meta.height)
        val targetWidth = if (scaleDown < 1f) (meta.width * scaleDown).roundToInt().coerceAtLeast(16) else meta.width
        val targetHeight = if (scaleDown < 1f) (meta.height * scaleDown).roundToInt().coerceAtLeast(16) else meta.height

        val retriever = MediaMetadataRetriever()
        val collected = ArrayList<GifFrame>()
        try {
            retriever.setDataSource(item.file.absolutePath)
            for (index in 0 until frames) {
                val bitmap = retriever.frameAt(startUs + index * stepUs, targetWidth, targetHeight) ?: break
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                collected += GifFrame(pixels, (stepUs / 10_000L).toInt().coerceAtLeast(2))
            }
        } finally {
            runCatching { retriever.release() }
        }
        require(collected.isNotEmpty()) { "一帧都没解出来，可能是不支持的编码格式" }

        val bytes = GifEncoder(targetWidth, targetHeight, 0, 256).encode(collected)
        return EngineOutput(
            OutputNaming.tagged(item.name, "gif", "gif"),
            workspace.newStagingFile("gif").apply { writeBytes(bytes) },
            "${collected.size} 帧 ${targetWidth}x$targetHeight，${SizeInput.format(bytes.size.toLong())}",
        )
    }

    private class VideoMeta(val width: Int, val height: Int, val durationUs: Long)

    private fun videoMeta(item: WorkItem): VideoMeta {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(item.file.absolutePath)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(rawWidth > 0 && rawHeight > 0) { "读不到画面尺寸，这个视频可能损坏" }
            val swapped = rotation == 90 || rotation == 270
            return VideoMeta(
                if (swapped) rawHeight else rawWidth,
                if (swapped) rawWidth else rawHeight,
                durationMs * 1000,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resize(image: GifImage, edge: Int): GifImage {
        val longest = max(image.width, image.height)
        if (edge >= longest) return image
        val ratio = edge.toFloat() / longest
        val width = (image.width * ratio).toInt().coerceAtLeast(1)
        val height = (image.height * ratio).toInt().coerceAtLeast(1)
        val frames = image.frames.map { frame ->
            val bitmap = Bitmap.createBitmap(frame.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled !== bitmap) bitmap.recycle()
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            scaled.recycle()
            GifFrame(pixels, frame.delayCs)
        }
        return GifImage(width, height, frames, image.loopCount)
    }

    /** 按目标帧率丢帧，把省下来的时间并回最后一帧，总时长保持不变。 */
    private fun dropTo(image: GifImage, targetFps: Int): GifImage {
        if (targetFps <= 0) return image
        val stepCs = max(1, 100 / targetFps)
        val kept = ArrayList<GifFrame>()
        var clock = 0
        var next = 0
        for (frame in image.frames) {
            if (clock >= next) {
                kept += frame
                next = clock + stepCs
            }
            clock += frame.delayCs
        }
        if (kept.size < 2) return image
        val last = kept.last()
        return image.copy(frames = kept.dropLast(1) + GifFrame(last.argb, last.delayCs + (clock - next)))
    }
}
