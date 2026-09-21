package com.fileforge.core.ops

import com.fileforge.core.model.FileKind

/** 操作目录：界面按它筛选可用操作，参数由界面收集后拼成 [Operation]。 */
enum class OperationKind(val label: String, val hint: String) {
    ConvertImage("图片格式转换", "JPG / PNG / WebP 互转"),
    CompressImage("压缩图片", "按质量或目标体积"),
    ImagesToPdf("图片合成 PDF", "一张一页"),
    SplitPdfBySize("PDF 按体积分割", "每份不超过目标大小"),
    ExtractPdfPages("PDF 截取指定页", "例：100-150,200-250"),
    PdfToImages("PDF 每页导出图片", "页转 JPG / PNG / WebP"),
    CompressGif("压缩 GIF", "降尺寸、降帧、减色"),
    VideoToGif("视频转 GIF", "支持 MP4 / WebM"),
    CompressVideo("压缩视频", "硬件转码，可按目标体积反推码率"),
    MergePdfs("合并 PDF", "按选中顺序拼成一份"),
    ;

    companion object {

        /** 只看文件真实类型，不看扩展名；混合选择时只留全部都能做的操作。 */
        fun applicable(kinds: Set<FileKind>): List<OperationKind> {
            if (kinds.isEmpty()) return emptyList()
            return entries.filter { kind -> kinds.all { compatible(kind, it) } }
        }

        private fun compatible(kind: OperationKind, fileKind: FileKind): Boolean = when (kind) {
            ConvertImage, CompressImage -> fileKind.isImage && fileKind != FileKind.Gif
            ImagesToPdf -> fileKind.isImage
            SplitPdfBySize, ExtractPdfPages, PdfToImages -> fileKind == FileKind.Pdf
            CompressGif -> fileKind == FileKind.Gif
            VideoToGif -> fileKind.isVideo
            CompressVideo -> fileKind.isVideo
            MergePdfs -> fileKind == FileKind.Pdf
        }
    }
}
