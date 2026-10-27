package com.fileforge.converter

import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.OperationKind
import com.fileforge.core.ops.OperationKind.Companion.applicable
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.pdf.PageResizeMode
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.ui.Parameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 参数面板的纯逻辑：校验拦什么、build 拼出什么。不碰安卓 API，
 * 参数面板改坏这类判据时测试先红。
 */
class ParametersPanelTest {

    private fun item(name: String, kind: FileKind) =
        WorkItem(id = 1, name = name, file = File(name), kind = kind, size = 10, addedAt = 0L)

    private fun video() = item("clip.mp4", FileKind.Mp4)

    @Test
    fun `截取段给反了要拦下并说明`() {
        val draft = Parameters(OperationKind.VideoTrim, listOf(video())).apply { trimSpecText = "12-5" }
        assertTrue(draft.validation()!!.contains("读不出"), draft.validation())
    }

    @Test
    fun `视频截取认分秒写法并原样传给引擎`() {
        val draft = Parameters(OperationKind.VideoTrim, listOf(video())).apply { trimSpecText = "1:05-2:30" }
        assertNull(draft.validation())
        assertEquals(Operation.VideoTrim("1:05-2:30"), draft.build())
    }

    @Test
    fun `音频截取多段拼给引擎`() {
        val draft = Parameters(OperationKind.AudioTrim, listOf(item("song.mp3", FileKind.Mp3))).apply {
            audioTrimTarget = com.fileforge.core.audio.AudioTarget.Wav
            audioTrimSpecText = "5-12,30-41"
        }
        assertNull(draft.validation())
        assertEquals(
            Operation.AudioTrim(com.fileforge.core.audio.AudioTarget.Wav, "5-12,30-41"),
            draft.build(),
        )
    }

    @Test
    fun `重排页序可重复可倒序`() {
        val draft = Parameters(OperationKind.PdfReorder, listOf(video())).apply { pdfReorderSpec = "3,1,2" }
        assertNull(draft.validation())
        assertEquals(Operation.PdfReorder("3,1,2"), draft.build())
    }

    @Test
    fun `改纸张拼出纸张、缩放模式与页码范围`() {
        val draft = Parameters(OperationKind.ResizePdfPages, listOf(item("a.pdf", FileKind.Pdf))).apply {
            pdfResizePaper = PdfPaper.A5
            pdfResizeMode = PageResizeMode.Cover
            pageSpec = "1,3-5"
        }
        assertNull(draft.validation())
        assertEquals(
            Operation.ResizePdfPages(PdfPaper.A5, PageResizeMode.Cover, "1,3-5"),
            draft.build(),
        )
    }

    @Test
    fun `填表单拼出字段并拦截缺等号`() {
        val draft = Parameters(OperationKind.FillPdfForm, listOf(item("a.pdf", FileKind.Pdf))).apply {
            pdfFormSpec = "name=Alice;agree=true"
        }
        assertNull(draft.validation())
        assertEquals(Operation.FillPdfForm("name=Alice;agree=true", true), draft.build())

        draft.pdfFormSpec = "name"
        assertTrue(draft.validation()!!.contains("字段名=值"), draft.validation())
    }

    @Test
    fun `图片水印没文字没 Logo 要拦下`() {
        val draft = Parameters(OperationKind.ImageWatermark, listOf(item("p.jpg", FileKind.Jpeg))).apply {
            imageWatermarkText = ""
        }
        assertTrue(draft.validation()!!.contains("水印文字"))
    }

    @Test
    fun `图片水印的大小百分比进操作`() {
        val draft = Parameters(OperationKind.ImageWatermark, listOf(item("p.jpg", FileKind.Jpeg))).apply {
            imageWatermarkText = "水印"
            imageWatermarkSize = 250f
        }
        val made = draft.build() as Operation.ImageWatermark
        assertEquals(250, made.sizePercent)
    }

    @Test
    fun `水印文字里的换行原样带进操作`() {
        val image = Parameters(OperationKind.ImageWatermark, listOf(item("p.jpg", FileKind.Jpeg))).apply {
            imageWatermarkText = "第一行\n第二行"
        }
        assertNull(image.validation())
        assertEquals("第一行\n第二行", (image.build() as Operation.ImageWatermark).text)

        val video = Parameters(OperationKind.VideoWatermark, listOf(video())).apply {
            imageWatermarkText = "上\n下"
        }
        assertNull(video.validation())
        assertEquals("上\n下", (video.build() as Operation.VideoWatermark).text)
    }

    @Test
    fun `只有换行和空白的文字仍要拦下`() {
        val draft = Parameters(OperationKind.GifWatermark, listOf(item("a.gif", FileKind.Gif))).apply {
            imageWatermarkText = "  \n \n"
        }
        assertTrue(draft.validation()!!.contains("水印文字"))
    }

    @Test
    fun `Logo 图按名挂进操作`() {
        val draft = Parameters(OperationKind.ImageWatermark, listOf(item("p.jpg", FileKind.Jpeg))).apply {
            imageWatermarkText = ""
            imageWatermarkLogo = "logo.png"
        }
        assertNull(draft.validation(), "有 Logo 时没有文字也放行")
        val made = draft.build() as Operation.ImageWatermark
        assertEquals("logo.png", made.logoFrom)
    }

    @Test
    fun `视频裁画面没写块要拦下，写了原样进操作`() {
        val empty = Parameters(OperationKind.VideoCrop, listOf(video()))
        assertTrue(empty.validation()!!.contains("左,上,宽,高"), empty.validation())
        val draft = Parameters(OperationKind.VideoCrop, listOf(video())).apply {
            cropRectText = "10%,10%,80%,80%"
        }
        assertNull(draft.validation())
        assertEquals(Operation.CropVideo("10%,10%,80%,80%"), draft.build())
    }

    @Test
    fun `烧字幕不选外挂就烧自带轨，选了就带上文件名与字号`() {
        val embedded = Parameters(OperationKind.BurnSubtitles, listOf(video()))
        assertNull(embedded.validation())
        assertEquals(Operation.BurnSubtitles("", 100, 8), embedded.build())

        val draft = Parameters(
            OperationKind.BurnSubtitles,
            listOf(video()),
            subtitleNames = listOf("a.srt"),
        ).apply {
            subtitleFrom = "a.srt"
            videoSubtitleSize = 120f
            videoSubtitleBottom = 10f
        }
        assertNull(draft.validation())
        assertEquals(Operation.BurnSubtitles("a.srt", 120, 10), draft.build())

        val gone = Parameters(OperationKind.BurnSubtitles, listOf(video())).apply {
            subtitleFrom = "a.srt"
        }
        assertTrue(gone.validation()!!.contains("不在工作台"), gone.validation())
    }

    @Test
    fun `视频水印没文字拦下，有文字带上位置与不透明度`() {
        val empty = Parameters(OperationKind.VideoWatermark, listOf(video()))
        assertTrue(empty.validation()!!.contains("水印文字"), empty.validation())
        val draft = Parameters(OperationKind.VideoWatermark, listOf(video())).apply {
            imageWatermarkText = "样片"
            imageWatermarkSpot = 1
            imageWatermarkOpacity = 60f
            imageWatermarkSize = 150f
        }
        assertNull(draft.validation())
        assertEquals(Operation.VideoWatermark("样片", 1, 60, 150), draft.build())
    }

    @Test
    fun `旧版表格转 CSV 与写成 XLSX 直接拼操作`() {
        val csv = Parameters(OperationKind.XlsToCsv, listOf(item("old.xls", FileKind.Xls)))
        assertNull(csv.validation())
        assertEquals(Operation.XlsToCsv(), csv.build())
        val xlsx = Parameters(OperationKind.XlsToXlsx, listOf(item("old.xls", FileKind.Xls)))
        assertNull(xlsx.validation())
        assertEquals(Operation.XlsToXlsx, xlsx.build())
    }

    @Test
    fun `导出字幕不需要参数`() {
        val draft = Parameters(OperationKind.ExtractSubtitles, listOf(video()))
        assertNull(draft.validation())
        assertEquals(Operation.ExtractSubtitles, draft.build())
    }

    @Test
    fun `操作目录把水印与截取派给对的类型`() {
        val forImage = applicable(setOf(FileKind.Jpeg))
        assertTrue(OperationKind.ImageWatermark in forImage)
        assertTrue(OperationKind.VideoTrim !in forImage)

        val forVideo = applicable(setOf(FileKind.Mp4))
        assertTrue(OperationKind.VideoTrim in forVideo)
        assertTrue(OperationKind.AudioTrim in forVideo)

        val forGif = applicable(setOf(FileKind.Gif))
        assertTrue(OperationKind.GifWatermark in forGif)
        assertTrue(OperationKind.ImageWatermark !in forGif)

        val forPdf = applicable(setOf(FileKind.Pdf))
        assertTrue(OperationKind.PdfReorder in forPdf)
        // 纸上的行列还原成表格：只给 PDF，图片与视频上没有"字"可量
        assertTrue(OperationKind.PdfToCsv in forPdf)
        assertTrue(OperationKind.PdfToXlsx in forPdf)
        assertTrue(OperationKind.PdfToCsv !in forImage)
        assertTrue(OperationKind.PdfToXlsx !in forVideo)
    }
}
