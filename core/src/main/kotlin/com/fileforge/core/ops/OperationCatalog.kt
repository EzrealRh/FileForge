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
    CleanMetadata("清除图片元数据", "删掉 EXIF / GPS / 机型 / 注释，像素不重新编码"),
    PackZip("打包成 ZIP", "把选中的几份压成一个包"),
    UnpackZip("解压 ZIP", "解出里面的文件，路径压进文件名"),
    FormatJson("JSON 格式化", "缩进或压平、键排序；数字原文不被改写"),
    JsonToCsv("JSON 转 CSV", "对象数组拆成表，会丢什么都先说明"),
    CsvToJson("CSV 转 JSON", "首行当列名；默认不猜类型，007 不会变成 7"),
    TextToPdf("文本印成 PDF", "自动断行分页，中文可在字之间断"),
    OfficeToText("Word 演示提取文字", "docx / pptx 抽正文，丢了什么逐条写明"),
    XlsxToCsv("表格转 CSV", "每张表一份 CSV，日期不再是序列号"),
    MdToHtml("Markdown 转 HTML", "出一份带 charset 的完整页面；认不出标记会直说"),
    MdToText("Markdown 去标记", "吃掉标记，列表记号与表格分列留着"),
    HtmlToText("网页抽文字", "段落列表表格留着，脚本样式页眉丢掉"),
    HtmlToMarkdown("网页转 Markdown", "标题、列表、表格、链接写成标记；补了几处标签会说明"),
    ImageToIco("做成图标 ICO", "一次出 16/32/48/256 多个尺寸"),
    IcoToImages("图标拆成图片", "把 .ico 里的每个画面导成 PNG"),
    XmlToJson("XML 转 JSON", "属性加 @、子元素成数组；带 DTD 的不解析"),
    JsonToXml("JSON 转 XML", "键当标签名，数组写成重复元素"),
    ;

    companion object {

        /** 只看文件真实类型，不看扩展名；混合选择时只留全部都能做的操作。 */
        fun applicable(kinds: Set<FileKind>): List<OperationKind> {
            if (kinds.isEmpty()) return emptyList()
            return entries.filter { kind -> kinds.all { compatible(kind, it) } }
        }

        private fun compatible(kind: OperationKind, fileKind: FileKind): Boolean = when (kind) {
            ConvertImage, CompressImage -> fileKind.isImage && fileKind != FileKind.Gif && fileKind != FileKind.Ico
            // 图标要走专门通路：解码成位图后一次出多个尺寸；普通格式转换只会给一张
            ImageToIco -> fileKind.isImage && fileKind != FileKind.Gif && fileKind != FileKind.Ico
            IcoToImages -> fileKind == FileKind.Ico
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
            ConvertTextEncoding, ConvertSubtitle -> fileKind.isTextual
            // 印成 PDF 走同一套排版：docx / pptx 先把正文抽出来，网页先把标记剔掉，抽出来的是什么就是什么
            TextToPdf -> fileKind.isTextual || fileKind == FileKind.Docx || fileKind == FileKind.Pptx
            OfficeToText -> fileKind == FileKind.Docx || fileKind == FileKind.Pptx
            XlsxToCsv -> fileKind == FileKind.Xlsx
            // 只有真带画面的类型才给"提取音频"，否则用户会对一个纯音频文件点它
            ExtractAudio -> fileKind.isVideo
            // 只放开了 JPEG 和 PNG：这两种容器的清理能做到段级照抄。
            // GIF / WebP / HEIC 的元数据规则各不相同，没做透就不给入口，免得产出一张坏图
            CleanMetadata -> fileKind == FileKind.Jpeg || fileKind == FileKind.Png
            // 打包对类型无要求：任何文件都能塞进 zip，混选更是常见诉求（把发票 pdf 和照片一起发人）
            PackZip -> true
            UnpackZip -> fileKind == FileKind.Zip
            // 数据格式这一族只看"是不是文本"，具体是不是合法 JSON / 长得对不对交给引擎判，
            // 判不动会直说 —— 与字幕那族同一个路子，不在类型层猜。Markdown 同理：
            // 纯文本里没有任何记号时"转 HTML"没意义，引擎会拒而不是硬出一页。
            // .html 也算这一族：很多人把网页存成 .txt，反向那两条（抽文字 / 转 MD）本来就要给它
            FormatJson, JsonToCsv, CsvToJson, XmlToJson, JsonToXml, MdToHtml, MdToText -> fileKind.isTextual
            // 网页那两条反过来也宽松：类型说 Text 但内容满是标签的文件照样能抽，
            // 真没有 HTML 标记时引擎会直说"这不是网页"，不硬出一份少了尖括号的文件
            HtmlToText, HtmlToMarkdown -> fileKind.isTextual
        }
    }
}
