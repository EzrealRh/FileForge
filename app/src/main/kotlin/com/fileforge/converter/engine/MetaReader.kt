package com.fileforge.converter.engine

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import com.fileforge.core.gif.GifDecoder
import com.fileforge.core.model.FileKind
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.text.DateFormat
import java.util.Date

/** 一个文件在详情面板里要展示的信息。 */
data class FileDetail(
    val name: String,
    val kindLabel: String,
    val sizeText: String,
    val addedText: String,
    val fromOperation: String?,
    val facts: List<Pair<String, String>>,
    val previewError: String?,
)

/**
 * 只读元信息。全部走"不解全图/不整档载入"的路子：图片用 inJustDecodeBounds，
 * PDF 用临时文件模式打开，视频用 MediaExtractor 读轨道头，避免看个详情就把内存吃满。
 */
class MetaReader {

    fun read(item: WorkItem): FileDetail {
        val facts = ArrayList<Pair<String, String>>()
        var previewError: String? = null
        runCatching {
            when {
                item.kind == FileKind.Gif -> facts += gifFacts(item.file)
                item.kind == FileKind.Pdf -> facts += pdfFacts(item.file)
                item.kind.isVideo -> facts += videoFacts(item.file)
                item.kind.isImage -> facts += imageFacts(item.file)
                else -> Unit
            }
        }.onFailure { error -> previewError = error.message ?: error.javaClass.simpleName }

        return FileDetail(
            name = item.name,
            kindLabel = kindLabel(item.kind),
            sizeText = SizeInput.format(item.size),
            addedText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.addedAt)),
            fromOperation = item.fromOperation,
            facts = facts,
            previewError = previewError,
        )
    }

    /** 预览图：按最长边 1000 解一张，够看清内容又不至于吃内存。 */
    fun preview(item: WorkItem, longEdge: Int = 1000): android.graphics.Bitmap? = runCatching {
        when {
            item.kind.isVideo -> videoFrame(item.file)
            item.kind == FileKind.Pdf -> pdfFirstPage(item.file)
            item.kind.isImage -> stillFrame(item.file, longEdge)
            else -> null
        }
    }.getOrNull()

    private fun stillFrame(file: File, longEdge: Int): android.graphics.Bitmap? = decodeStillFrame(file, longEdge)

    private fun videoFrame(file: File): android.graphics.Bitmap? = videoFrameAt(file, 0L, 900, 900)

    private fun pdfFirstPage(file: File): android.graphics.Bitmap? {
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(descriptor)
        return try {
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val scale = 2f
                val bitmap = android.graphics.Bitmap.createBitmap(
                    (page.width * scale).toInt(),
                    (page.height * scale).toInt(),
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                android.graphics.Canvas(bitmap).apply { drawColor(0xFFFFFFFF.toInt()) }
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } finally {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    private fun imageFacts(file: File): List<Pair<String, String>> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return listOf("画面" to "读不到尺寸，可能不是有效图片")
        val megapixels = bounds.outWidth * bounds.outHeight / 1_000_000f
        return buildList {
            add("画面" to "${bounds.outWidth} × ${bounds.outHeight}")
            add("像素" to "%.1f 百万".format(megapixels))
            bounds.outMimeType?.let { add("解码格式" to it.removePrefix("image/").uppercase()) }
            val degrees = runCatching {
                android.media.ExifInterface(file.absolutePath)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(0)
            if (degrees != android.media.ExifInterface.ORIENTATION_NORMAL && degrees != 0) add("EXIF 旋转" to "第 $degrees 档")
        }
    }

    private fun gifFacts(file: File): List<Pair<String, String>> {
        // 只为看信息，不解全部帧：200 帧的大 GIF 全解出来会直接把进程顶爆
        val image = GifDecoder.decode(file.readBytes(), pixelBudget = FACT_PIXEL_BUDGET)
        val seen = HashSet<Int>()
        image.frames.forEach { frame ->
            for (pixel in frame.argb) if (pixel != 0) seen.add(pixel and 0xFFFFFF)
        }
        return buildList {
            add("画面" to "${image.width} × ${image.height}")
            add("帧数" to if (image.truncated) "${image.frames.size}（只解了前这么多）" else "${image.frames.size}")
            add("时长" to "%.1f 秒".format(image.totalDurationCs / 100f))
            add("平均帧率" to "%.1f fps".format(image.averageFps))
            add("循环" to if (image.loopCount == 0) "无限" else "${image.loopCount} 次")
            add("实际颜色" to "${seen.size} 种")
        }
    }

    private fun pdfFacts(file: File): List<Pair<String, String>> = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
        buildList {
            add("页数" to "${document.numberOfPages}")
            val first = document.getPage(0).mediaBox
            add("首页尺寸" to "${first.width.toInt()} × ${first.height.toInt()} pt")
            val landscape = first.width > first.height
            add("方向" to (if (landscape) "横版" else "竖版"))
            if (runCatching { document.documentCatalog.documentOutline?.firstChild != null }.getOrDefault(false)) {
                add("有书签" to "是")
            }
            runCatching { document.documentInformation?.title }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { add("文档标题" to it) }
        }
    }

    private fun videoFacts(file: File): List<Pair<String, String>> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            val video = tracks.firstOrNull { it.second.stringOrNull(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audio = tracks.firstOrNull { it.second.stringOrNull(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            buildList {
                add("轨道" to "${tracks.size} 条（${if (audio != null) "含音轨" else "无音轨"}）")
                video?.second?.let { format ->
                    val width = format.integerOrNull(MediaFormat.KEY_WIDTH)
                    val height = format.integerOrNull(MediaFormat.KEY_HEIGHT)
                    if (width != null && height != null) add("画面" to "$width × $height")
                    format.stringOrNull(MediaFormat.KEY_MIME)?.let { add("视频编码" to it.substringAfter('/').uppercase()) }
                    format.longOrNull(MediaFormat.KEY_DURATION)?.let { add("时长" to "%.1f 秒".format(it / 1_000_000f)) }
                    format.integerOrNull(MediaFormat.KEY_FRAME_RATE)?.let { add("帧率" to "$it fps") }
                    format.integerOrNull(MediaFormat.KEY_BIT_RATE)?.let { add("视频码率" to "%.1f Mbps".format(it / 1_000_000f)) }
                    format.integerOrNull(MediaFormat.KEY_ROTATION)?.takeIf { it != 0 }?.let { add("旋转" to "$it°") }
                }
                audio?.second?.let { format ->
                    add("音频" to (format.stringOrNull(MediaFormat.KEY_MIME)?.substringAfter('/')?.uppercase() ?: "未知"))
                    format.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.let { add("声道" to "$it") }
                    format.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)?.let { add("采样率" to "${it / 1000} kHz") }
                }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun kindLabel(kind: FileKind): String = when (kind) {
        FileKind.Pdf -> "PDF 文档"
        FileKind.Gif -> "GIF 动图"
        FileKind.WebP -> "WebP 图片"
        FileKind.Heic -> "HEIC 图片"
        FileKind.Avif -> "AVIF 图片"
        FileKind.Mp4 -> "MP4 视频"
        FileKind.WebM -> "WebM 视频"
        FileKind.Mkv -> "Matroska 视频"
        FileKind.QuickTime -> "MOV 视频"
        FileKind.Png -> "PNG 图片"
        FileKind.Jpeg -> "JPEG 图片"
        FileKind.Bmp -> "BMP 图片"
        FileKind.Zip -> "ZIP 压缩包"
        FileKind.Unknown -> "未知类型"
    }

    private fun MediaFormat.stringOrNull(key: String): String? = runCatching { getString(key) }.getOrNull()
    private fun MediaFormat.integerOrNull(key: String): Int? = runCatching { getInteger(key) }.getOrNull()
    private fun MediaFormat.longOrNull(key: String): Long? = runCatching { getLong(key) }.getOrNull()

    private companion object {
        /** 详情页只是读信息，解这么多像素足够算出颜色数，不必把整个动图搬进堆。 */
        const val FACT_PIXEL_BUDGET = 4_000_000
    }
}
