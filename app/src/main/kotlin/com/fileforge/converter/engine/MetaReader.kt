package com.fileforge.converter.engine

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import com.fileforge.core.archive.ZipReader
import com.fileforge.core.data.Ico
import com.fileforge.core.audio.AudioPlan
import com.fileforge.core.gif.GifDecoder
import com.fileforge.core.meta.ImageMeta
import com.fileforge.core.model.FileKind
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.FileSlices
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.fileforge.core.office.Xlsx
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
                item.kind == FileKind.Zip -> facts += zipFacts(item.file)
                item.kind == FileKind.Docx || item.kind == FileKind.Pptx || item.kind == FileKind.Xlsx ->
                    facts += officeFacts(item.file, item.kind)
                item.kind == FileKind.Ico -> facts += icoFacts(item.file)
                item.kind == FileKind.Pdf -> facts += pdfFacts(item.file)
                item.kind.isVideo -> facts += videoFacts(item.file)
                item.kind.isAudio -> facts += audioFacts(item.file)
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
            addAll(metaFacts(file))
        }
    }

    /**
     * 图片里带的身份信息。详情面板列出来，用户才知道"清除元数据"会扔掉什么。
     *
     * 只读文件开头一小段：EXIF 和文本块都在像素之前，为一屏文字把整张图搬进堆没必要。
     */
    private fun metaFacts(file: File): List<Pair<String, String>> {
        val report = ImageMeta.report(ImageMeta.head(file))
        if (report.container == null) return emptyList()
        return buildList {
            if (report.fields.isEmpty()) add("元数据" to "只有段落结构，没读到字段")
            report.fields.take(META_LINES).forEach { (key, value) -> add(key.removePrefix("拍摄参数 ") to value) }
            if (report.fields.size > META_LINES) add("其余字段" to "${report.fields.size - META_LINES} 项未列出")
            add(
                "可清理" to if (report.identifying.isEmpty()) "没有身份信息可清"
                else "${report.identifying.size} 段 · ${SizeInput.format(report.saving.toLong())}",
            )
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

    /** 压缩包里有什么：不解压也能先看清有多少条、解开多大、带不带口令。 */
    private fun zipFacts(file: File): List<Pair<String, String>> {
        val slices = FileSlices(file)
        return try {
            val archive = ZipReader.read(slices, file.length())
            val files = archive.entries.filterNot { it.isDirectory }
            buildList {
                add("条目" to "${archive.entries.size} 条（文件 ${files.size}）")
                add("解开后" to SizeInput.format(files.sumOf { it.size }))
                val locked = files.count { it.isEncrypted }
                if (locked > 0) add("带口令" to "$locked 条，这几条解不出来")
                files.take(META_LINES).forEach { add(it.name.substringAfterLast('/') to SizeInput.format(it.size)) }
                if (files.size > META_LINES) add("其余条目" to "${files.size - META_LINES} 条未列出")
                if (archive.comment.isNotBlank()) add("包注释" to archive.comment)
            }
        } finally {
            runCatching { slices.close() }
        }
    }

    /**
     * Office 文档的详情：不转换也能先看一眼有多大、会丢什么。
     *
     * 只按部件区间读，不整包进堆 —— 带视频的 pptx 几百 MB 是常态。
     */
    private fun officeFacts(file: File, kind: FileKind): List<Pair<String, String>> =
        if (kind == FileKind.Xlsx) {
            OoxmlFile(file).use { pack ->
                val strings = Xlsx.sharedStrings(pack.bytesOf(Xlsx.SHARED_STRINGS))
                val styles = Xlsx.dateStyles(pack.bytesOf(Xlsx.STYLES))
                val refs = pack.sheetRefs()
                buildList {
                    add("表" to "${refs.size} 张")
                    refs.take(META_LINES).forEach { ref ->
                        val bytes = ref.part?.let { pack.bytesOf(it) }
                        val size = if (bytes == null) "部件读不出来"
                        else Xlsx.sheet(ref.name, bytes, strings, styles).rows.let {
                            "${it.size} 行 × ${(it.maxOfOrNull { row -> row.size } ?: 0)} 列"
                        }
                        add(ref.name to size)
                    }
                    if (refs.size > META_LINES) add("其余的表" to "${refs.size - META_LINES} 张未列出")
                }
            }
        } else {
            val text = OfficeSource.text(file, kind)
            buildList {
                add("正文" to "${text.text.count { it == '\n' }} 行 · ${text.text.length} 字")
                if (text.losses.isEmpty()) add("转文字会丢" to "没检测到要丢的东西")
                else add("转文字会丢" to text.losses.joinToString("、"))
            }
        }

    /** 图标里有几帧、每帧多大：一个 .ico 可以同时装 16 到 256 的好几张。 */
    private fun icoFacts(file: File): List<Pair<String, String>> {
        val entries = Ico.directory(file.readBytes())
        return buildList {
            add("画面" to "${entries.size} 帧")
            add("尺寸" to entries.joinToString(" ") { "${it.width}×${it.height}" })
            add("内部格式" to if (entries.all { it.isPng }) "PNG 内嵌" else if (entries.any { it.isPng }) "PNG 与位图混着" else "位图 DIB")
            add("位深" to "${entries.first().bitCount} 位")
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

    /** 纯音频文件详情：不写这一条的话 M4A 和 WAV 在列表里只看得出扩展名。 */
    private fun audioFacts(file: File): List<Pair<String, String>> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val mimes = (0 until extractor.trackCount)
                .map { runCatching { extractor.getTrackFormat(it).stringOrNull(MediaFormat.KEY_MIME) }.getOrNull() }
            val track = AudioPlan.pickAudioTrack(mimes)
            if (track < 0) return listOf("音轨" to "读不到音频轨")
            val format = extractor.getTrackFormat(track)
            buildList {
                add("编码" to (mimes[track]?.substringAfter('/')?.uppercase() ?: "未知"))
                format.longOrNull(MediaFormat.KEY_DURATION)?.let { add("时长" to "%.1f 秒".format(it / 1_000_000f)) }
                format.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)?.let { add("采样率" to "${it / 1000} kHz") }
                format.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.let { add("声道" to "$it") }
                format.integerOrNull(MediaFormat.KEY_BIT_RATE)?.let { add("码率" to "${it / 1000} kbps") }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun kindLabel(kind: FileKind): String = when (kind) {
        FileKind.Pdf -> "PDF 文档"
        FileKind.Gif -> "GIF 动图"
        FileKind.Ico -> "ICO 图标"
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
        FileKind.Tar -> "TAR 归档"
        FileKind.Gzip -> "GZ 压缩"
        FileKind.Docx -> "DOCX 文档"
        FileKind.Xlsx -> "XLSX 表格"
        FileKind.Epub -> "EPUB 电子书"
        FileKind.Pptx -> "PPTX 演示"
        FileKind.Text -> "文本文件"
        FileKind.Html -> "HTML 网页"
        FileKind.Mp3 -> "MP3 音频"
        FileKind.Aac -> "AAC 音频"
        FileKind.M4a -> "M4A 音频"
        FileKind.Flac -> "FLAC 音频"
        FileKind.Ogg -> "OGG 音频"
        FileKind.Wav -> "WAV 音频"
        FileKind.Unknown -> "未知类型"
    }

    private fun MediaFormat.stringOrNull(key: String): String? = runCatching { getString(key) }.getOrNull()
    private fun MediaFormat.integerOrNull(key: String): Int? = runCatching { getInteger(key) }.getOrNull()
    private fun MediaFormat.longOrNull(key: String): Long? = runCatching { getLong(key) }.getOrNull()

    private companion object {
        /** 详情页只是读信息，解这么多像素足够算出颜色数，不必把整个动图搬进堆。 */
        const val FACT_PIXEL_BUDGET = 4_000_000

        /** 元数据字段最多列这么多行，剩下的报个数 —— 详情面板不是十六进制编辑器。 */
        const val META_LINES = 12
    }
}
