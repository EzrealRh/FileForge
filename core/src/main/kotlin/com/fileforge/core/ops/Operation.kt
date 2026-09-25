package com.fileforge.core.ops

import com.fileforge.core.audio.AudioTarget
import com.fileforge.core.pdf.PageNumberPlan
import com.fileforge.core.pdf.StampSpot

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

    /** GIF 拆成图片：默认逐帧导出，也可以只要首帧（做封面、进 PPT）。 */
    data class GifToImages(
        val format: ImageFormat = ImageFormat.Png,
        val firstFrameOnly: Boolean = false,
        val quality: Int = 92,
    ) : Operation {
        override val label get() = if (firstFrameOnly) "导出首帧为 ${format.label}" else "每帧导出为 ${format.label}"
    }

    /** 多张图片合成 GIF：一张一帧，尺寸统一到最大那张，其余等比缩放居中。 */
    data class ImagesToGif(
        val frameDelayMs: Int = 1000,
        val maxEdge: Int = 0,
    ) : Operation {
        override val label get() = "合成为 GIF"
    }

    /** 视频里抽一帧成图片；秒数给 0 就是第一帧。 */
    data class VideoToImage(
        val format: ImageFormat = ImageFormat.Jpeg,
        val second: Double = 0.0,
        val quality: Int = 92,
        val maxEdge: Int = 0,
    ) : Operation {
        override val label get() = "抽一帧为 ${format.label}"
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

    /**
     * 视频压缩：硬件编解码直接转码。给了 targetBytes 就按时长反推视频码率
     * （见 [com.fileforge.core.video.VideoBitratePlan]），否则用固定码率。
     */
    data class CompressVideo(
        val format: VideoFormat = VideoFormat.Mp4,
        val videoBitrateKbps: Int = 2500,
        val maxEdge: Int = 0,
        val audioBitrateKbps: Int = 96,
        val targetBytes: Long? = null,
    ) : Operation {
        override val label get() = if (targetBytes != null) "压到 ${targetLabel(targetBytes)} 以内" else "压缩视频"
    }

    /**
     * 音频转格式 / 从视频里提声音。目标只有两个是安卓真的编得出来、解得开的：
     * M4A(AAC) 和 WAV —— 系统没有 MP3 编码器，所以"转成 mp3"这条做不了，不给选项。
     * 给 targetBytes 就按时长反推码率（见 [com.fileforge.core.audio.AudioPlan]）。
     */
    data class AudioConvert(
        val target: AudioTarget = AudioTarget.M4a,
        val targetBytes: Long? = null,
    ) : Operation {
        override val label get() = if (targetBytes != null) "转成 ${target.label}（约 ${targetLabel(targetBytes)}）"
        else "转成 ${target.label}"
    }

    /**
     * 给 PDF 加页码。位置、边距都按**你看到的方向**算，带 /Rotate 的页也一样落在视觉底部。
     * spec 留空表示所有页；firstNumber 只平移起始数字，中间页不重排。
     */
    data class PageNumbers(
        val spec: String = "",
        val style: Int = PageNumberPlan.STYLE_PLAIN,
        val spot: StampSpot = StampSpot.BottomCenter,
        val firstNumber: Int = 1,
        val fontSize: Int = 11,
        val margin: Int = 20,
    ) : Operation {
        override val label get() = "加页码"
    }

    /** 文字水印：1x1 就是居中一块，行列大于 1 就平铺。中文字体会子集内嵌进文件。 */
    data class PdfWatermark(
        val text: String,
        val columns: Int = 1,
        val rows: Int = 1,
        val opacityPercent: Int = 18,
        val tilt: Int = 45,
        val grayPercent: Int = 45,
        val spec: String = "",
    ) : Operation {
        override val label get() = "加水印"
    }

    /** 多份 PDF 按选中顺序合成一份。 */
    data object MergePdfs : Operation {
        override val label get() = "合并 PDF"
    }

    /**
     * PDF 压缩：只重编内嵌图片（降采样 + JPEG），文字和矢量图形原样保留。
     * level 对应「清晰/标准/紧凑」，给了 targetBytes 就沿档位阶梯往下走到达标或走完。
     */
    data class CompressPdf(
        val level: Int = 1,
        val targetBytes: Long? = null,
    ) : Operation {
        override val label get() = if (targetBytes != null) "压到 ${targetLabel(targetBytes)} 以内" else "压缩 PDF"
    }

    /** 删掉指定页，其余页原样保留。 */
    data class RemovePdfPages(val spec: String) : Operation {
        override val label get() = "删除指定页"
    }

    /** 旋转页面，度数是 90 的整数倍；spec 留空表示所有页。 */
    data class RotatePdfPages(val spec: String, val degrees: Int) : Operation {
        override val label get() = if (spec.isBlank()) "旋转所有页 $degrees°" else "旋转指定页 $degrees°"
    }

    /** 按份数把页数均分拆开，和按体积分割是两回事。 */
    data class SplitPdfIntoParts(val parts: Int) : Operation {
        override val label get() = "拆成 $parts 份"
    }

    /** 提取文字层成 txt；spec 留空表示全文，多段用页码范围写。 */
    data class PdfToText(val spec: String = "") : Operation {
        override val label get() = "提取文字"
    }
}

enum class PdfPaper(val label: String) { A4("A4"), A5("A5"), Letter("Letter"), FitImage("按图片尺寸") }

enum class ImageFit { Contain, Cover }

private fun targetLabel(bytes: Long): String = com.fileforge.core.util.SizeInput.format(bytes)
