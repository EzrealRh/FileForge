package com.fileforge.core.ops

import com.fileforge.core.model.FileKind

/** 操作目录：界面按它筛选可用操作，参数由界面收集后拼成 [Operation]。 */
enum class OperationKind(val label: String, val hint: String) {
    ConvertImage("图片格式转换", "JPG / PNG / WebP 互转"),
    CompressImage("压缩图片", "按质量或目标体积"),
    ImagesToPdf("图片合成 PDF", "一张一页"),
    CompressPdf("压缩 PDF", "内嵌图片降采样重编，文字不动"),
    SplitPdfBySize("PDF 按体积分割", "每份不超过目标大小"),
    SplitPdfIntoParts("PDF 拆成 N 份", "按页数均分"),
    ExtractPdfPages("PDF 截取指定页", "例：100-150,200-250"),
    RemovePdfPages("PDF 删除指定页", "其余页原样保留"),
    RotatePdfPages("PDF 旋转页面", "整份或指定页，90 的倍数"),
    MergePdfs("合并 PDF", "按选中顺序拼成一份"),
    PdfToText("PDF 提取文字", "输出 txt，可以只要几页"),
    AddPageNumbers("PDF 加页码", "底部或角落，可跳过前几页"),
    PdfWatermark("PDF 加水印", "文字水印，可平铺和调深浅"),
    PdfToImages("PDF 每页导出图片", "页转 JPG / PNG / WebP"),
    EncryptPdf("PDF 加密码", "设打开密码，可再限制打印/复制/修改"),
    DecryptPdf("PDF 去密码", "填对打开密码，产出不带保护的副本"),
    CompressGif("压缩 GIF", "降尺寸、降帧、减色"),
    GifToImages("GIF 转图片", "逐帧导出，或只要首帧"),
    ImagesToGif("图片合成 GIF", "一张一帧，可设每帧停留"),
    VideoToGif("视频转 GIF", "支持 MP4 / WebM"),
    VideoToImage("视频抽帧成图片", "指定秒数取那一帧，做封面"),
    CompressVideo("压缩视频", "硬件转码，可按目标体积反推码率"),
    ConvertAudio("音频转格式", "MP3 / FLAC / OGG 转 M4A 或 WAV"),
    ConvertTextEncoding("文本转编码", "GBK / UTF-8 / Big5 互转，顺带统一换行"),
    ConvertSubtitle("字幕转格式", "SRT / VTT / LRC / ASS 互转"),
    ExtractAudio("提取音频", "把视频里的声音拿出来"),
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
            CompressPdf, SplitPdfBySize, SplitPdfIntoParts, ExtractPdfPages, RemovePdfPages,
            RotatePdfPages, MergePdfs, PdfToText, PdfToImages, AddPageNumbers, PdfWatermark,
            EncryptPdf, DecryptPdf,
            -> fileKind == FileKind.Pdf
            CompressGif, GifToImages -> fileKind == FileKind.Gif
            ImagesToGif -> fileKind.isImage
            VideoToGif, VideoToImage -> fileKind.isVideo
            CompressVideo -> fileKind.isVideo
            ConvertAudio -> fileKind.isAudio
            // 两种都只认"这是个文本文件"，具体是哪种字幕交给解析器判
            ConvertTextEncoding, ConvertSubtitle -> fileKind == FileKind.Text
            // 只有真带画面的类型才给"提取音频"，否则用户会对一个纯音频文件点它
            ExtractAudio -> fileKind.isVideo
        }
    }
}
