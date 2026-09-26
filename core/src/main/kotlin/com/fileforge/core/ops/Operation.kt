package com.fileforge.core.ops

import com.fileforge.core.audio.AudioTarget
import com.fileforge.core.data.Delimiter
import com.fileforge.core.text.LineEnding
import com.fileforge.core.text.SubtitleFormat
import com.fileforge.core.text.TextEncoding
import com.fileforge.core.pdf.PageNumberPlan
import com.fileforge.core.pdf.PdfPermission
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
     * 给 PDF 加打开密码和权限限制。所有者密码留空时按打开密码填同一个
     * （留空会让库随机造一个，用户以后自己解不开）。
     */
    data class EncryptPdf(
        val userPassword: String,
        val ownerPassword: String = "",
        val granted: Set<PdfPermission> = emptySet(),
    ) : Operation {
        override val label get() = if (userPassword.isBlank()) "限制操作" else "加密码"
    }

    /** 去掉 PDF 的密码保护，产出一份明文副本。 */
    data class DecryptPdf(val password: String) : Operation {
        override val label get() = "去掉密码"
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
     * 文本编码转换：换编码、要不要 BOM、顺带统一换行风格。
     * 解不出来的字符数会在结果说明里报出来 —— 转错的产物是"能打开的乱码"。
     */
    data class ConvertTextEncoding(
        val source: TextEncoding? = null,
        val target: TextEncoding = TextEncoding.Utf8,
        val bom: Boolean = false,
        val ending: LineEnding = LineEnding.Lf,
    ) : Operation {
        override val label get() = if (bom) "转成 ${target.label}（带 BOM）" else "转成 ${target.label}"
    }

    /** 字幕 / 歌词转格式。源格式由扩展名和各解析器定，转过去会丢什么由格式自己声明。 */
    data class ConvertSubtitle(
        val target: SubtitleFormat = SubtitleFormat.Srt,
        val source: TextEncoding? = null,
    ) : Operation {
        override val label get() = "字幕转为 ${target.label}"
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

    /**
     * PDF → Word：把"画在纸上的样子"还原成文档结构（标题层级、列表、段落），写进一份 .docx。
     *
     * spec 留空表示整份，多段用页码范围写。PDF 里没有"标题"这一层，所以层级是按字号与位置**推**的：
     * 推不出来的（多栏的读序、表格线、图片）会照实说明，不会装作搬全了。
     */
    data class PdfToDocx(val spec: String = "") : Operation {
        override val label get() = "转为 Word 文档"
    }

    /**
     * 清掉图片里的身份信息（EXIF / GPS / 机型 / 软件 / 注释），像素数据**一字节都不重编码**。
     *
     * 没有参数：要清什么由段的性质决定，见 [com.fileforge.core.meta.ImageMeta]。
     */
    data object CleanMetadata : Operation {
        override val label get() = "清除元数据"
    }

    /** 多份文件压成一个 zip。包名取第一份文件的名字，条目名重名会自动编号。 */
    data object PackArchive : Operation {
        override val label get() = "打包成 ZIP"
    }

    /**
     * 解开 zip。一次给出一批产物，目录结构压进文件名而不真的建目录 ——
     * 既因为工作台是平的，也因为不拿条目名拼路径就结构性地挡掉了 `../../` 那类穿越。
     */
    data object UnpackArchive : Operation {
        override val label get() = "解压"
    }

    /**
     * 打成 tar.gz：tar 排目录，gzip 压整体。
     *
     * 与 zip 的分别不在"能不能压"，而在 tar **保留条目路径与时间**且 Unix 侧的工具链都认它 ——
     * 文本类文件合成一份再压，通常也比一份条各压一份的 zip 小。
     */
    data object PackTarGz : Operation {
        override val label get() = "打包成 tar.gz"
    }

    /**
     * 解开 tar / tar.gz / 单个 .gz。
     *
     * `.gz` 里是不是装着一份 tar 由内容判（第一块头校验和对得上才算），不靠扩展名猜：
     * 是 tar 就照 zip 那套规矩平铺出多份产物，不是就交回那一个文件（名字优先用 gzip 头里的原名）。
     */
    data object UntarArchive : Operation {
        override val label get() = "解开这个包"
    }

    /**
     * JSON 格式化：缩进或压平，可选键排序与非 ASCII 转码。
     *
     * 数字**一律照抄原文**（`1.50` 不会变成 `1.5`）：格式化不该改写值的样子，
     * 而大整数过一遍 double 还会真的丢位。
     */
    data class FormatJson(
        val pretty: Boolean = true,
        val indent: Int = 2,
        val sortKeys: Boolean = false,
        val ascii: Boolean = false,
    ) : Operation {
        override val label get() = if (pretty) "按 $indent 空格缩进" else "压成一行"
    }

    /** JSON 数组 → CSV。会丢什么由 [com.fileforge.core.data.TableBridge.losses] 说。 */
    data class JsonToCsv(
        val delimiter: Delimiter = Delimiter.Comma,
        val ending: LineEnding = LineEnding.Lf,
        val quoteAll: Boolean = false,
    ) : Operation {
        override val label get() = "转为 CSV 表格"
    }

    /** CSV → JSON 数组。[inferTypes] 默认关：`007` 与 `1.50` 猜错就再也回不去了。 */
    data class CsvToJson(
        val header: Boolean = true,
        val inferTypes: Boolean = false,
        val indent: Int = 2,
    ) : Operation {
        override val label get() = if (header) "转为 JSON（首行当列名）" else "转为 JSON（按数组摆）"
    }

    /**
     * XML → JSON。约定写在 `:core` 的 Xml 头部：子元素一律成数组、属性加 `@`、
     * 元素自己的文字进 `#text`、注释与处理指令丢掉、带 DTD 的一律不解析。
     */
    data class XmlToJson(val indent: Int = 2) : Operation {
        override val label get() = "转为 JSON"
    }

    /** JSON → XML。[root] 为空时用源文件名当根元素名。 */
    data class JsonToXml(val root: String = "", val indent: Int = 2) : Operation {
        override val label get() = "转为 XML"
    }

    /**
     * XML → CSV：挑出重复出现的那个元素当行，属性与子元素各成一列。
     *
     * 挑了哪一处、还有几处也像表、哪些列是真嵌套被压平的，全在结果说明里（判断在 [com.fileforge.core.data.XmlTable]）。
     */
    data class XmlToCsv(
        val delimiter: Delimiter = Delimiter.Comma,
        val ending: LineEnding = LineEnding.Lf,
        val quoteAll: Boolean = false,
    ) : Operation {
        override val label get() = "转为 CSV 表格"
    }

    /** XML → xlsx。与「XML 转 CSV」挑同一张表，只是落成一份真工作簿。 */
    data object XmlToXlsx : Operation {
        override val label get() = "转为 Excel（.xlsx）"
    }

    /**
     * 图片做成 .ico。一张源图会被缩到 [sizes] 里的每个尺寸，一并写进同一份图标 ——
     * 系统按用途挑合适的那张，所以一次出多尺寸才有意义。
     */
    data class ImageToIco(val sizes: List<Int> = DEFAULT_ICO_SIZES) : Operation {
        override val label get() = "做成图标 ICO"

        companion object {
            val DEFAULT_ICO_SIZES = listOf(16, 32, 48, 256)
        }
    }

    /**
     * 纯文本印成 PDF：自动断行（中文可在字之间断）、自动分页。
     *
     * [lineHeight] 是行距倍数，[marginPt] 是四边页边距（pt），[numberPages] 决定要不要页脚页码。
     */
    data class TextToPdf(
        val fontSize: Int = 11,
        val paper: PdfPaper = PdfPaper.A4,
        val marginPt: Int = 56,
        val lineHeight: Float = 1.4f,
        val firstLineIndent: Boolean = true,
        val numberPages: Boolean = true,
    ) : Operation {
        override val label get() = "印成 PDF"
    }

    /**
     * 从 .docx / .pptx 里抽正文文字，存成 UTF-8 的 txt。
     *
     * 图片、页眉页脚、脚注正文这些搬不过来，抽的时候会逐条写明丢了什么 ——
     * 一份"看着挺全"少了脚注的文本，比一份写明少了三条脚注的更难被查出来。
     */
    data object OfficeToText : Operation {
        override val label get() = "提取文档文字"
    }

    /** .xlsx 每张表转一份 CSV。日期照样式认出来的样子写，数字写法原样搬。 */
    data class XlsxToCsv(
        val delimiter: Delimiter = Delimiter.Comma,
        val ending: LineEnding = LineEnding.Lf,
    ) : Operation {
        override val label get() = "表格转 CSV"
    }

    /**
     * Markdown → HTML 的一份完整页面（带 charset，浏览器双击就能看）。
     *
     * 没有参数：CSS 与模板是另一件事，这里只做忠实的语法转换。
     */
    data object MdToHtml : Operation {
        override val label get() = "转成 HTML"
    }

    /** Markdown → 纯文本：吃掉标记，列表记号与表格分列留下。 */
    data object MdToText : Operation {
        override val label get() = "去掉标记"
    }

    /**
     * 网页 → 纯文本：段落空行、列表记号、表格分列留着，脚本样式与页眉丢掉。
     *
     * 网页几乎都不是合法 XML，所以这里按浏览器那套补全没闭合的标签，补了几处会写在结果说明里。
     */
    data object HtmlToText : Operation {
        override val label get() = "网页抽文字"
    }

    /** 网页 → Markdown：认得出结构的写成标记，认不出的（表单、内嵌框架）退化成文字并说出来。 */
    data object HtmlToMarkdown : Operation {
        override val label get() = "网页转 Markdown"
    }

    /**
     * CSV → xlsx（一张表，表名用文件名）。
     *
     * 格子类型只按字面判：只有"变成数字后还能一字不差读回来"的写法才写成数字，
     * `007`、`1.50`、15 位以上的编号一律保持文字 —— 那是数据，不是格式问题。
     */
    data object CsvToXlsx : Operation {
        override val label get() = "写成 Excel 表格"
    }

    /** JSON（对象数组）→ xlsx：列名取第一份对象的键，套进去的值摊平成文字。 */
    data object JsonToXlsx : Operation {
        override val label get() = "JSON 写成 Excel 表格"
    }

    /**
     * 网页里的表格逐张转成 CSV。
     *
     * `colspan` / `rowspan` 按跨度占位：被盖住的位置留空格子，而不是把整行往左挤。
     */
    data class HtmlToCsv(
        val delimiter: Delimiter = Delimiter.Comma,
        val ending: LineEnding = LineEnding.Lf,
    ) : Operation {
        override val label get() = "网页表格转 CSV"
    }

    /**
     * YAML → JSON。类型按 **YAML 1.2 的核心模式**判：`yes` / `no` / `1:30` 保持文字，
     * 不会像某些库那样变成 `true` 与 `90`（那是静悄悄改数据）。
     */
    data class YamlToJson(val indent: Int = 2) : Operation {
        override val label get() = "转为 JSON"
    }

    /**
     * JSON → YAML（块式）。
     *
     * 看着像别的类型的字符串一律加引号，`1.5e3` 这类"两家读法不同"的数写成等价的十进制 ——
     * 目标是别人用任何一个 YAML 库读回去都是同一个值。
     */
    data class JsonToYaml(val indent: Int = 2) : Operation {
        override val label get() = "转为 YAML"
    }

    /** YAML（对象数组或映射的映射）→ CSV。会丢什么沿用「JSON 转 CSV」那本账。 */
    data class YamlToCsv(
        val delimiter: Delimiter = Delimiter.Comma,
        val ending: LineEnding = LineEnding.Lf,
        val quoteAll: Boolean = false,
    ) : Operation {
        override val label get() = "转为 CSV 表格"
    }

    /** CSV → YAML（一个对象一行的列表）。默认不猜类型，`007` 还是 `007`。 */
    data class CsvToYaml(val header: Boolean = true, val indent: Int = 2) : Operation {
        override val label get() = if (header) "转为 YAML（首行当列名）" else "转为 YAML（按数组摆）"
    }

    /**
     * 文本 / Markdown / 网页写成 .docx，进 Word / WPS 打得开、能继续编辑。
     *
     * 按内容挑路（见 [com.fileforge.core.doc.TextDoc]）：带标签的按网页排，带记号的按 Markdown 排，
     * 其余按空行分段。列表的圆点与序号是 Word 自己画的（不是写在文字里），删一行也不会错位。
     */
    data object TextToDocx : Operation {
        override val label get() = "写成 Word 文档"
    }

    /**
     * 文本 / Markdown / 网页 / Word 演示正文写成一份 `.epub` —— 文字出路的第四条，
     * 手机上拿阅读器读的就是它。
     *
     * 章节按**一级标题**切，与读 EPUB 那一侧同一套规矩；[title] 空着用文件名，
     * [author] 空着就不写作者（不编一个名字进去）。
     */
    data class TextToEpub(val title: String = "", val author: String = "") : Operation {
        override val label get() = if (title.isBlank()) "写成电子书" else "写成电子书（《$title》）"
    }

    /** 把 .ico 里的每一帧画面导成 PNG。 */
    data object IcoToImages : Operation {
        override val label get() = "导出图标里的画面"
    }

    /**
     * EPUB 抽正文成 txt：按 OPF 的 **spine** 顺序把一章章接起来（文件名顺序不算顺序）。
     *
     * 图片、样式与字体不是文字，不搬；缺件、对不上的 id 与非线性的项都会报数，不静悄悄少一章。
     */
    data object EpubToText : Operation {
        override val label get() = "提取全书文字"
    }

    /** EPUB → Markdown：每章的标题、列表、表格写成标记。 */
    data object EpubToMarkdown : Operation {
        override val label get() = "转为 Markdown"
    }

    /** EPUB → Word：结构与记号走 Word 的样式，可以拿去继续编辑。 */
    data object EpubToDocx : Operation {
        override val label get() = "写成 Word 文档"
    }

    /**
     * XML → YAML：树与「XML 转 JSON」同一棵，写出规矩与「JSON 转 YAML」同一条。
     *
     * XML 里没有类型，所以值一律是文字 —— `1.50` 写出去还是 `1.50`，不会被读成 1.5。
     */
    data class XmlToYaml(val indent: Int = 2) : Operation {
        override val label get() = "转为 YAML"
    }

    /** YAML → XML。[root] 空着用文件名；顶层是序列时包一层根元素，每项写成 `<item>`。 */
    data class YamlToXml(val root: String = "", val indent: Int = 2) : Operation {
        override val label get() = if (root.isBlank()) "转为 XML" else "转为 XML（根元素 $root）"
    }

    /**
     * CSV → XML：一行一个元素，列名当子元素名。
     *
     * [header] 关着时列名用 `列1`、`列2`… —— XML 里每个值都得有个元素名，
     * 那是给没名字的东西起名字，不是把用户写过的名字改掉。
     */
    data class CsvToXml(val root: String = "", val header: Boolean = true) : Operation {
        override val label get() = if (header) "转为 XML（首行当列名）" else "转为 XML（列名写成 列1、列2…）"
    }

    /** YAML → xlsx：摊表用的是「YAML 转 CSV」同一套判据，两条路出来的表一致。 */
    data object YamlToXlsx : Operation {
        override val label get() = "转为 Excel"
    }
}

enum class PdfPaper(val label: String) { A4("A4"), A5("A5"), Letter("Letter"), FitImage("按图片尺寸") }

enum class ImageFit { Contain, Cover }

private fun targetLabel(bytes: Long): String = com.fileforge.core.util.SizeInput.format(bytes)
