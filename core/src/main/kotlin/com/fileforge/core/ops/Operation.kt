package com.fileforge.core.ops

enum class ImageFormat(val extension: String, val label: String) {
    Jpeg("jpg", "JPG"),
    Png("png", "PNG"),
    WebP("webp", "WebP"),
}

enum class VideoFormat(val extension: String, val label: String) {
    Mp4("mp4", "MP4 (H.264)"),
    WebM("webm", "WebM (VP8)"),
}

/** 所有操作的参数都在这里；UI 只负责收集，引擎只负责执行。 */
sealed interface Operation {

    val label: String

    /** 图片格式转换：可选尺寸，不动体积。 */
    data class ConvertImage(
        val format: ImageFormat,
        val quality: Int = 90,
        val maxEdge: Int = 0,
    ) : Operation {
        override val label get() = "转为 ${format.label}"
    }

    /** 图片压缩：可以只给目标体积，也可以给质量/最长边。 */
    data class CompressImage(
        val format: ImageFormat = ImageFormat.Jpeg,
        val quality: Int = 70,
        val maxEdge: Int = 0,
        val targetBytes: Long? = null,
    ) : Operation {
        override val label get() = if (targetBytes != null) "压到 ${targetLabel(targetBytes)} 以内" else "压缩图片"
    }

    /** 多张图片合成一份 PDF，一张一页。 */
    data class ImagesToPdf(
        val paper: PdfPaper = PdfPaper.A4,
        val marginDp: Int = 0,
        val fit: ImageFit = ImageFit.Contain,
    ) : Operation {
        override val label get() = "合成为 PDF"
    }

    /** 按目标体积分割，尾部不足一份的剩余页单独成文件。 */
    data class SplitPdfBySize(val targetBytes: Long) : Operation {
        override val label get() = "按 ${targetLabel(targetBytes)} 分割"
    }

    /** 按页码范围截取，支持多段：100-150,200-250。 */
    data class ExtractPdfPages(val spec: String) : Operation {
        override val label get() = "截取指定页"
    }

    /** PDF 每一页导出成图片。 */
    data class PdfToImages(
        val format: ImageFormat = ImageFormat.Png,
        val scale: Float = 2f,
        val quality: Int = 90,
    ) : Operation {
        override val label get() = "每页导出为 ${format.label}"
    }

    /** GIF 瘦身：缩尺寸、降帧率、减色数，三项都可单独用。 */
    data class CompressGif(
        val maxEdge: Int = 0,
        val targetFps: Int = 0,
        val colors: Int = 128,
    ) : Operation {
        override val label get() = "压缩 GIF"
    }

    /** 视频转 GIF。 */
    data class VideoToGif(
        val fps: Int = 12,
        val maxEdge: Int = 480,
        val startSecond: Double = 0.0,
        val durationSecond: Double = 0.0,
    ) : Operation {
        override val label get() = "转为 GIF"
    }

    /** 视频压缩：硬件编解码直接转码，不做 CPU 重绘除非需要缩尺寸。 */
    data class CompressVideo(
        val format: VideoFormat = VideoFormat.Mp4,
        val videoBitrateKbps: Int = 2500,
        val maxEdge: Int = 0,
        val audioBitrateKbps: Int = 96,
    ) : Operation {
        override val label get() = "压缩视频"
    }
}

enum class PdfPaper(val label: String) { A4("A4"), A5("A5"), Letter("Letter"), FitImage("按图片尺寸") }

enum class ImageFit { Contain, Cover }

private fun targetLabel(bytes: Long): String = com.fileforge.core.util.SizeInput.format(bytes)
