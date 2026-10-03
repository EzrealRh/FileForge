package com.fileforge.converter

import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.OperationKind
import com.fileforge.core.ops.OperationKind.Companion.applicable
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
    }
}
