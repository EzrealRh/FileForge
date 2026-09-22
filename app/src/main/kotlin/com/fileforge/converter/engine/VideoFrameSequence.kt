package com.fileforge.converter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * 顺序解码视频抽帧。
 *
 * 为什么不用 MediaMetadataRetriever.getFrameAtTime：它在不少机型上对 WebM/VP8 退化成"只回关键帧"，
 * 于是转出来的 GIF 是 30 张同一画面 —— 用户看到的就是"根本不动"。顺序解码一次 seek 都不做，
 * 每个采样都真解一遍，帧帧不同。
 */
object VideoFrameSequence {

    /** 一次抽帧的结果：等间隔的画面 + 每张画面对应停留的毫秒数。 */
    class Frames(val bitmaps: List<Bitmap>, val durationsMs: List<Long>)

    /**
     * 从 [startUs] 起、在 [windowUs] 这段里按 [stepUs] 间隔取最多 [frameCount] 张画面。
     * [rotation] 是视频自带的旋转角（顺时针），传进来是为了横拍竖播的画面不会被转歪。
     */
    fun extract(
        file: File,
        startUs: Long,
        windowUs: Long,
        frameCount: Int,
        stepUs: Long,
        targetWidth: Int,
        targetHeight: Int,
        rotation: Int,
        onProgress: (Int) -> Unit = {},
    ): Frames {
        // targetWidth/targetHeight 是「显示尺寸」：调用方给的已经是转好角度的尺寸，这里只按 rotation 反推源图坐标
        require(frameCount >= 1 && stepUs >= 1) { "抽帧参数不合法" }
        val extractor = MediaExtractor()
        val info = MediaCodec.BufferInfo()
        var decoder: MediaCodec? = null
        val collected = ArrayList<Bitmap>()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = selectVideoTrack(extractor)
            require(track >= 0) { "这个文件里没有画面" }
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("读不到视频编码格式")
            extractor.selectTrack(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            // 用「相对第一个解出来的采样」的elapsed 计时：部分机型 seek 之后采样时间会重新从 0 数
            var base = -1L
            var nextIndex = 0L
            var inputDone = false
            var outputDone = false
            var timeout = 0L
            var guard = 0L

            while (!outputDone && collected.size < frameCount && guard++ < MAX_STEPS) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeout)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex) ?: error("拿不到解码输入缓冲")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, timeout)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> timeout = 20_000L
                    outIndex >= 0 -> {
                        val time = info.presentationTimeUs
                        if (base < 0) base = time
                        if (time - base > windowUs + stepUs) {
                            decoder.releaseOutputBuffer(outIndex, false)
                            break
                        }
                        val targetIndex = ((time - base) / stepUs).toInt().coerceAtLeast(0).toLong()
                        if (targetIndex >= nextIndex && collected.size < frameCount) {
                            // 源帧率比目标高时，中间那些采样点直接跳过，不重复出图
                            nextIndex = targetIndex + 1
                            decoder.getOutputImage(outIndex)?.use { image ->
                                collected += toBitmap(image, targetWidth, targetHeight, rotation)
                            }
                            onProgress(collected.size * 100 / frameCount)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIndex, false)
                        timeout = 0L
                    }
                }
            }
            require(collected.isNotEmpty()) { "一帧都没解出来，这台设备可能不支持 $mime" }
            return dedupe(collected, stepUs)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private const val MAX_STEPS = 200_000L

    /**
     * 相邻的"完全一样"的帧合并成一张，停留时间累加。
     * 静止画面占一半时长的视频，本来就不该出 30 张重复图 —— 那只会把体积喂胖、看起来像卡住。
     */
    private fun dedupe(bitmaps: List<Bitmap>, stepUs: Long): Frames {
        val kept = ArrayList<Bitmap>()
        val durations = ArrayList<Long>()
        val stepMs = (stepUs / 1000L).coerceAtLeast(1L)
        var previous: IntArray? = null
        bitmaps.forEach { bitmap ->
            val signature = sample(bitmap)
            if (previous != null && signature.contentEquals(previous)) {
                durations[durations.size - 1] += stepMs
                bitmap.recycle()
            } else {
                kept += bitmap
                durations += stepMs
                previous = signature
            }
        }
        return Frames(kept, durations)
    }

    /** 取一组对角采样点当"指纹"，用来判断两张画面是否一模一样。 */
    private fun sample(bitmap: Bitmap): IntArray {
        val columns = bitmap.width
        val rows = bitmap.height
        val out = IntArray(64)
        for (i in 0 until 64) {
            val x = (i % 8 + 0.5f) * columns / 8
            val y = (i / 8 + 0.5f) * rows / 8
            out[i] = bitmap.getPixel(x.toInt().coerceIn(0, columns - 1), y.toInt().coerceIn(0, rows - 1))
        }
        return out
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = runCatching { extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }.getOrNull()
            if (mime?.startsWith("video/") == true) return index
        }
        return -1
    }

    /**
     * YUV_420_888 → ARGB，一步到位顺便缩放到要的大小：
     * 先在目标尺寸上算每个像素落在源图的哪里，再按 BT.601 有限范围转 RGB。
     */
    private fun toBitmap(image: android.media.Image, width: Int, height: Int, rotation: Int): Bitmap {
        val planes = image.planes
        require(planes.size == 3) { "只认 YUV_420_888，拿到 ${planes.size} 个平面" }
        val y = planes[0].buffer
        val u = planes[1].buffer
        val v = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPixel = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPixel = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPixel = planes[2].pixelStride
        val sourceWidth = image.width
        val sourceHeight = image.height
        val swap = rotation % 180 != 0
        val codedWidth = if (swap) height else width
        val codedHeight = if (swap) width else height
        val pixels = IntArray(codedWidth * codedHeight)
        val ySize = y.limit()
        val uSize = u.limit()
        val vSize = v.limit()

        for (cy in 0 until codedHeight) {
            for (cx in 0 until codedWidth) {
                // 目标像素反推到源图（0..1 归一化坐标），再按旋转角转回源图坐标
                val (u01, v01) = when (((rotation % 360) + 360) % 360) {
                    90 -> ((cy + 0.5f) / codedHeight) to (1f - (cx + 0.5f) / codedWidth)
                    180 -> (1f - (cx + 0.5f) / codedWidth) to (1f - (cy + 0.5f) / codedHeight)
                    270 -> (1f - (cy + 0.5f) / codedHeight) to ((cx + 0.5f) / codedWidth)
                    else -> ((cx + 0.5f) / codedWidth) to ((cy + 0.5f) / codedHeight)
                }
                val sx = (u01 * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
                val sy = (v01 * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
                val luma = y.get(safe(sy, sx, yRow, yPixel, ySize)).toInt() and 0xFF
                val chromaX = sx / 2
                val chromaY = sy / 2
                val cb = u.get(safe(chromaY, chromaX, uRow, uPixel, uSize)).toInt() and 0xFF
                val cr = v.get(safe(chromaY, chromaX, vRow, vPixel, vSize)).toInt() and 0xFF
                pixels[cy * codedWidth + cx] = yuvToRgb(luma, cb, cr)
            }
        }

        val coded = Bitmap.createBitmap(pixels, codedWidth, codedHeight, Bitmap.Config.ARGB_8888)
        if (!swap && codedWidth == width && codedHeight == height) return coded
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(coded, Rect(0, 0, codedWidth, codedHeight), Rect(0, 0, width, height), Paint().apply {
                isFilterBitmap = true
            })
        }
        coded.recycle()
        return out
    }

    private fun safe(row: Int, column: Int, rowStride: Int, pixelStride: Int, limit: Int): Int {
        val index = row * rowStride + column * pixelStride
        return index.coerceIn(0, (limit - 1).coerceAtLeast(0))
    }

    private fun yuvToRgb(luma: Int, cb: Int, cr: Int): Int {
        val c = (luma - 16).coerceAtLeast(0)
        val d = cb - 128
        val e = cr - 128
        val r = (298 * c + 409 * e + 128) shr 8
        val g = (298 * c - 100 * d - 208 * e + 128) shr 8
        val b = (298 * c + 516 * d + 128) shr 8
        return (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
    }
}
