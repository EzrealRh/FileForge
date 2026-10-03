package com.fileforge.converter.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import com.fileforge.core.data.Ico
import com.fileforge.core.data.IcoImage
import com.fileforge.core.meta.ImageMeta
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.ImageFormat
import com.fileforge.core.ops.Operation
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 位图解码上限：超过这个尺寸先按 2 的幂降采样，避免大图解码就 OOM。 */
private const val DECODE_CEILING = 4096

/** 图片水印的落位顺序，与操作面板上的「位置」选项一一对应。 */
private val SPOT_LABELS = listOf("居中", "右下", "左下", "右上", "左上")

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

    /**
     * 图片加文字/Logo 水印：白字带阴影或一张 Logo 图按透明度叠上去，
     * 单处（居中或四角）或隔行错位的平铺。像素重编一次，输出格式跟源走
     * （PNG 保持 PNG 留住透明通道，HEIC/其他出 JPG）。
     */
    fun watermark(
        item: WorkItem,
        operation: Operation.ImageWatermark,
        logoFile: File?,
        staging: (String) -> File,
    ): EngineOutput {
        require(operation.text.trim().isNotEmpty() || logoFile != null) {
            "先写要盖的水印文字，或选一张 Logo 图"
        }
        val decoded = decode(item.file)
        val painted = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        if (logoFile != null) {
            val logo = decode(logoFile)
            drawLogoWatermark(painted, logo, operation.spot, operation.tiled, operation.opacityPercent, operation.tilt, operation.sizePercent)
            logo.recycle()
        } else {
            drawTextWatermark(painted, operation.text.trim(), operation.spot, operation.tiled, operation.opacityPercent, operation.tilt, operation.sizePercent)
        }

        val format = when (item.kind) {
            FileKind.Png -> ImageFormat.Png
            FileKind.WebP -> ImageFormat.WebP
            else -> ImageFormat.Jpeg
        }
        val bytes = encode(painted, format, 92)
        painted.recycle()
        val placement = if (operation.tiled) "平铺" else SPOT_LABELS.getOrElse(operation.spot) { "居中" }
        val what = if (logoFile != null) "Logo「${logoFile.nameWithoutExtension.take(12)}」" else "「${operation.text.trim().take(12)}」"
        return EngineOutput(
            OutputNaming.tagged(item.name, "水印", format.extension),
            staging(format.extension).apply { writeBytes(bytes) },
            "$what$placement · ${operation.opacityPercent.coerceIn(3, 100)}% 不透明 · ${SizeInput.format(sizeOf(bytes))}",
        )
    }

    /** 文字水印的绘制：图片水印与 GIF 逐帧水印共用。 */
    fun drawTextWatermark(
        target: Bitmap,
        text: String,
        spot: Int,
        tiled: Boolean,
        opacityPercent: Int,
        tilt: Int,
        sizePercent: Int = 100,
    ) {
        val canvas = android.graphics.Canvas(target)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(opacityPercent.coerceIn(3, 100) * 255 / 100, 255, 255, 255)
            textSize = max(target.width, target.height) / 14f * sizePercent.coerceIn(20, 400) / 100f
            setShadowLayer(6f, 2f, 2f, android.graphics.Color.argb(110, 0, 0, 0))
        }
        val textWidth = paint.measureText(text)
        val metrics = paint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val margin = max(target.width, target.height) / 30f

        fun drawOne(x: Float, y: Float) {
            canvas.save()
            if (tilt != 0) canvas.rotate(tilt.toFloat(), x + textWidth / 2f, y)
            canvas.drawText(text, x, y, paint)
            canvas.restore()
        }

        if (tiled) {
            val stepX = textWidth + margin * 2f
            val stepY = textHeight + margin * 2f
            var row = 0
            var y = -textHeight
            while (y < target.height + textHeight) {
                // 隔行错位半格：铺出来才是水印的样子，不是表格
                var x = -textWidth + if (row % 2 == 0) 0f else stepX / 2f
                while (x < target.width) {
                    drawOne(x, y)
                    x += stepX
                }
                y += stepY
                row++
            }
        } else {
            val spotX = when (spot.coerceIn(0, 4)) {
                0, 2 -> (target.width - textWidth) / 2f
                1, 3 -> target.width - textWidth - margin
                else -> margin
            }
            val spotY = when (spot.coerceIn(0, 4)) {
                0 -> (target.height + textHeight) / 2f
                1, 2 -> target.height - metrics.descent - margin
                else -> margin - metrics.ascent
            }
            drawOne(spotX, spotY)
        }
    }

    /** Logo 水印：Logo 缩到画面短边的四分之一以内，按同样的落位/平铺规则叠上去。 */
    fun drawLogoWatermark(
        target: Bitmap,
        logo: Bitmap,
        spot: Int,
        tiled: Boolean,
        opacityPercent: Int,
        tilt: Int,
        sizePercent: Int = 100,
    ) {
        val canvas = android.graphics.Canvas(target)
        val longest = max(logo.width, logo.height).coerceAtLeast(1)
        val scale = (min(target.width, target.height) * 0.25f) / longest * sizePercent.coerceIn(20, 400) / 100f
        val drawWidth = (logo.width * scale).coerceAtLeast(1f)
        val drawHeight = (logo.height * scale).coerceAtLeast(1f)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            alpha = opacityPercent.coerceIn(3, 100) * 255 / 100
            isFilterBitmap = true
        }
        val margin = max(target.width, target.height) / 30f

        fun drawOne(x: Float, y: Float) {
            canvas.save()
            if (tilt != 0) canvas.rotate(tilt.toFloat(), x + drawWidth / 2f, y + drawHeight / 2f)
            canvas.drawBitmap(logo, null, android.graphics.RectF(x, y, x + drawWidth, y + drawHeight), paint)
            canvas.restore()
        }

        if (tiled) {
            val stepX = drawWidth + margin * 2f
            val stepY = drawHeight + margin * 2f
            var row = 0
            var y = -drawHeight
            while (y < target.height) {
                var x = -drawWidth + if (row % 2 == 0) 0f else stepX / 2f
                while (x < target.width) {
                    drawOne(x, y)
                    x += stepX
                }
                y += stepY
                row++
            }
        } else {
            val spotX = when (spot.coerceIn(0, 4)) {
                0, 2 -> (target.width - drawWidth) / 2f
                1, 3 -> target.width - drawWidth - margin
                else -> margin
            }
            val spotY = when (spot.coerceIn(0, 4)) {
                0 -> (target.height - drawHeight) / 2f
                1, 2 -> target.height - drawHeight - margin
                else -> margin
            }
            drawOne(spotX, spotY)
        }
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

    /**
     * 图片做成 .ico：一张源图缩出多个尺寸写进同一份文件。
     *
     * 每个尺寸都先缩放到"短边等于目标边"再**居中裁方**：图标必须是方的，
     * 只按最长边缩会让非方图变形。
     */
    fun toIco(items: List<WorkItem>, operation: Operation.ImageToIco, staging: (String) -> File): EngineOutput {
        val sizes = operation.sizes.filter { it in 1..Ico.MAX_SIDE }.distinct()
        require(sizes.isNotEmpty()) { "尺寸一个都不合法：要 1~${Ico.MAX_SIDE} 之间的数" }
        val source = decode(items.first().file)
        // source 是所有尺寸共用的解码结果，只能在整个循环结束后回收——
        // 缩第一个尺寸时就把它 recycle 掉，第二个尺寸再拿去缩就会崩
        val images = try {
            sizes.sortedByDescending { it }.map { side ->
                val square = centerCropSquare(source, side)
                val pixels = IntArray(side * side)
                square.getPixels(pixels, 0, side, 0, 0, side, side)   // 安卓的 API 是 (数组, 起点, 步长, 左, 上, 宽, 高)
                if (square !== source) square.recycle()
                IcoImage(side, side, pixels)
            }
        } finally {
            source.recycle()
        }
        val bytes = Ico.write(images)
        val file = staging("ico").apply { writeBytes(bytes) }
        return EngineOutput(
            OutputNaming.tagged(items.first().name, "图标", "ico"),
            file,
            "${sizes.sorted()} 共 ${sizes.size} 个尺寸 · ${SizeInput.format(bytes.size.toLong())}",
        )
    }

    /**
     * .ico 拆成图片。
     *
     * PNG 内嵌的那种**直接把内嵌字节交出去**：它本来就是一张完整 PNG，
     * 再解一遍重编码只会掉画质。DIB 的那种才需要重建位图再编 PNG。
     */
    fun icoToImages(item: WorkItem, staging: (String) -> File): List<EngineOutput> {
        val frames = Ico.read(item.file.readBytes())
        return frames.mapIndexed { index, frame ->
            val name = OutputNaming.part(item.name, index + 1, frames.size, "png")
            val file = staging("png")
            val embedded = frame.png
            if (embedded != null) {
                file.writeBytes(embedded)
            } else {
                val bitmap = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
                file.writeBytes(encode(bitmap, ImageFormat.Png, 100))
                bitmap.recycle()
            }
            EngineOutput(name, file, "${frame.width}×${frame.height}" + if (embedded != null) " · 内嵌 PNG 原样取出" else "")
        }
    }

    /** 缩到短边等于 [side] 之后居中裁出 side×side。不回收 [source]：它归调用方管。 */
    private fun centerCropSquare(source: Bitmap, side: Int): Bitmap {
        val shorter = minOf(source.width, source.height)
        val ratio = side.toFloat() / shorter
        val matrix = Matrix().apply { postScale(ratio, ratio) }
        val scaled = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (scaled.width == side && scaled.height == side) return scaled
        val cropped = Bitmap.createBitmap(scaled, (scaled.width - side) / 2, (scaled.height - side) / 2, side, side)
        scaled.recycle()
        return cropped
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

