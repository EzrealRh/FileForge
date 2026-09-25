package com.fileforge.converter.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import com.fileforge.core.meta.ImageMeta
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.ImageFormat
import com.fileforge.core.ops.Operation
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** 位图解码上限：超过这个尺寸先按 2 的幂降采样，避免大图解码就 OOM。 */
private const val DECODE_CEILING = 4096

class ImageEngine {

    fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("这张图解不开")

        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > DECODE_CEILING) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("这张图的内容不支持（可能是系统缺解码器）")
        return withRotation(decoded, file)
    }

    fun scale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        if (maxEdge <= 0) return bitmap
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        val matrix = Matrix().apply { postScale(ratio, ratio) }
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun encode(bitmap: Bitmap, format: ImageFormat, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream(1 shl 16)
        val ok = bitmap.compress(compressFormat(format), quality.coerceIn(1, 100), stream)
        if (!ok) throw IllegalStateException("${format.label} 编码失败")
        return stream.toByteArray()
    }

    fun convert(item: WorkItem, operation: Operation.ConvertImage, staging: (String) -> File): EngineOutput {
        val bitmap = decode(item.file)
        val sized = scale(bitmap, operation.maxEdge)
        val bytes = encode(sized, operation.format, operation.quality)
        if (sized !== bitmap) sized.recycle() else bitmap.recycle()
        val name = OutputNaming.tagged(item.name, "", operation.format.extension)
        return EngineOutput(name, staging(operation.format.extension).apply { writeBytes(bytes) }, SizeInput.format(sizeOf(bytes)))
    }

    /**
     * 压缩：给了目标体积就先二分质量，质量压到底还超就继续缩边，
     * 最后一定给出 ≤ 目标的结果，或者在明确放弃前把尺寸压到 320。
     */
    fun compress(item: WorkItem, operation: Operation.CompressImage, staging: (String) -> File): EngineOutput {
        val bitmap = decode(item.file)
        val target = operation.targetBytes
        var working = scale(bitmap, operation.maxEdge)

        if (target == null) {
            val bytes = encode(working, operation.format, operation.quality)
            val file = staging(operation.format.extension).apply { writeBytes(bytes) }
            working.recycle()
            return EngineOutput(
                OutputNaming.tagged(item.name, "压缩", operation.format.extension),
                file,
                "${SizeInput.format(bytes.size.toLong())}（原 ${SizeInput.format(item.size)}）",
            )
        }

        var edge = max(working.width, working.height)
        var best: ByteArray? = null
        while (best == null && edge >= 320) {
            if (edge != max(working.width, working.height)) {
                working = scaleTo(working, edge)
            }
            val candidate = searchQuality(working, operation.format, target)
            if (candidate != null) best = candidate else edge = (edge * 0.75f).roundToInt()
        }
        if (best == null) best = encode(working, operation.format, 30)
        working.recycle()

        return EngineOutput(
            OutputNaming.tagged(item.name, "压缩", operation.format.extension),
            staging(operation.format.extension).apply { writeBytes(best) },
            "${SizeInput.format(best.size.toLong())} ≤ 目标 ${SizeInput.format(target)}",
        )
    }

    /**
     * 清元数据：段级照抄，像素一个字节都不重新编码。
     *
     * 能不能清、清不了是为什么，全由 [com.fileforge.core.meta.MetaReport.cleanBlocker] 说；
     * 引擎只在它说「可以」的时候搬字节。没有可清的东西时不产出成品 —— 一批里混一张
     * 干净的图，用户看到的应该是"这张没得清"，而不是一份和源文件一模一样的假结果。
     */
    fun cleanMetadata(item: WorkItem, staging: (String) -> File): EngineOutput {
        val bytes = item.file.readBytes()
        val report = ImageMeta.report(bytes)
        report.cleanBlocker?.let { throw IllegalArgumentException(it) }
        val cleaned = ImageMeta.clean(bytes)
            ?: throw IllegalStateException("这份文件清不出结果，已按原样保留，没动原图")
        val extension = if (report.container == ImageMeta.Container.Png) "png" else "jpg"
        val note = buildString {
            append(SizeInput.format(bytes.size.toLong())).append(" → ").append(SizeInput.format(cleaned.size.toLong()))
            append("，删了 ").append(report.identifying.size).append(" 段")
            if (report.willLoseRotation) append("；原图靠 EXIF 站着，清完可能横过来")
        }
        return EngineOutput(
            OutputNaming.tagged(item.name, "无元数据", extension),
            staging(extension).apply { writeBytes(cleaned) },
            note,
        )
    }

    private fun searchQuality(bitmap: Bitmap, format: ImageFormat, target: Long): ByteArray? {
        var low = 1
        var high = 100
        var best: ByteArray? = null
        while (low <= high) {
            val middle = (low + high) / 2
            val bytes = encode(bitmap, format, middle)
            if (sizeOf(bytes) <= target) {
                best = bytes
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun scaleTo(bitmap: Bitmap, edge: Int): Bitmap {
        val ratio = edge.toFloat() / max(bitmap.width, bitmap.height)
        val matrix = Matrix().apply { postScale(ratio, ratio) }
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun sizeOf(bytes: ByteArray): Long = bytes.size.toLong()

    private fun compressFormat(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.Jpeg -> Bitmap.CompressFormat.JPEG
        ImageFormat.Png -> Bitmap.CompressFormat.PNG
        ImageFormat.WebP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
    }

    /** JPEG 里的 EXIF 方向要转成真实像素，否则横拍竖存。 */
    private fun withRotation(bitmap: Bitmap, file: File): Bitmap {
        val degrees = exifDegrees(file)
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun exifDegrees(file: File): Int = runCatching {
        val values = android.media.ExifInterface(file.absolutePath)
        when (values.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}

