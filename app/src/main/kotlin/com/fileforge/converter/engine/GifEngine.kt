package com.fileforge.converter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import com.fileforge.core.gif.GifCanvasPlan
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

    fun fromVideo(item: WorkItem, operation: Operation.VideoToGif, onProgress: (Int) -> Unit = {}): EngineOutput {
        val meta = videoDisplayMeta(item.file)
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

        // 顺序解码抽帧：MediaMetadataRetriever 按时间取帧在部分机型上对 WebM 只回关键帧，
        // 转出来的 GIF 就是几十张同一画面（用户看到的「不动」就是这个）。
        val taken = VideoFrameSequence.extract(
            file = item.file,
            startUs = startUs,
            windowUs = windowUs,
            frameCount = frames,
            stepUs = stepUs,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            rotation = meta.rotation,
            onProgress = onProgress,
        )
        val collected = ArrayList<GifFrame>()
        taken.bitmaps.forEachIndexed { index, bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            collected += GifFrame(pixels, (taken.durationsMs[index] / 10).toInt().coerceAtLeast(2))
        }
        require(collected.size > 1) {
            "这台设备从这个视频里只解出一张画面（抽了 $frames 帧全是同一张），换 MP4 或缩短片段再试"
        }

        val bytes = GifEncoder(targetWidth, targetHeight, 0, 256).encode(collected)
        val merged = if (taken.bitmaps.size == frames) "" else "，合并了 ${frames - taken.bitmaps.size} 张重复画面"
        return EngineOutput(
            OutputNaming.tagged(item.name, "gif", "gif"),
            workspace.newStagingFile("gif").apply { writeBytes(bytes) },
            "${collected.size} 帧 ${targetWidth}x$targetHeight$merged，${SizeInput.format(bytes.size.toLong())}",
        )
    }

    /** GIF 拆成图片：逐帧导出，或者只取首帧做封面。 */
    fun toImages(item: WorkItem, operation: Operation.GifToImages): List<EngineOutput> {
        val source = GifDecoder.decode(item.file.readBytes(), pixelBudget = PIXEL_BUDGET)
        val picked = if (operation.firstFrameOnly) source.frames.take(1) else source.frames
        require(picked.isNotEmpty()) { "这个 GIF 里解不出画面" }
        val extension = operation.format.extension
        return picked.mapIndexed { index, frame ->
            val bitmap = Bitmap.createBitmap(frame.argb, source.width, source.height, Bitmap.Config.ARGB_8888)
            val bytes = images.encode(bitmap, operation.format, operation.quality)
            bitmap.recycle()
            EngineOutput(
                OutputNaming.tagged(item.name, if (operation.firstFrameOnly) "首帧" else "第${index + 1}帧", extension),
                workspace.newStagingFile(extension).apply { writeBytes(bytes) },
                note = if (index == 0) {
                    "${picked.size} 帧 ${source.width}x${source.height}" +
                        if (source.truncated) "，帧太多只解出前 ${source.frames.size} 张" else ""
                } else null,
            )
        }
    }

    /** 多张图合成 GIF：画布是所有图的外接矩形，小图等比放大缩小后居中，不裁切。 */
    fun fromImages(items: List<WorkItem>, operation: Operation.ImagesToGif): EngineOutput {
        require(items.isNotEmpty()) { "没有图片可合成" }
        val decoded = items.map { images.decode(it.file) }
        val (width, height) = GifCanvasPlan.canvas(
            widths = decoded.map { it.width },
            heights = decoded.map { it.height },
            maxEdge = operation.maxEdge,
            pixelBudget = PIXEL_BUDGET,
        )
        require(GifCanvasPlan.fits(width, height, decoded.size, PIXEL_BUDGET)) {
            "${decoded.size} 帧 ${width}x$height 还是超出内存上限，减少张数或把最长边调小"
        }
        val delayCs = (operation.frameDelayMs / 10).coerceIn(2, 65_535)
        val frames = decoded.map { bitmap ->
            val canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val painter = Canvas(canvas).apply { drawColor(0xFFFFFFFF.toInt()) }
            val ratio = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val drawWidth = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
            val drawHeight = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, drawWidth, drawHeight, true)
            painter.drawBitmap(scaled, ((width - drawWidth) / 2f), ((height - drawHeight) / 2f), null)
            if (scaled !== bitmap) scaled.recycle()
            val pixels = IntArray(width * height)
            canvas.getPixels(pixels, 0, width, 0, 0, width, height)
            canvas.recycle()
            GifFrame(pixels, delayCs)
        }
        decoded.forEach { it.recycle() }

        val bytes = GifEncoder(width, height, 0, 256).encode(frames)
        return EngineOutput(
            OutputNaming.tagged(items.first().name, "合成", "gif"),
            workspace.newStagingFile("gif").apply { writeBytes(bytes) },
            "${frames.size} 帧 ${width}x$height，每帧 ${delayCs * 10}ms，${SizeInput.format(bytes.size.toLong())}",
        )
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
