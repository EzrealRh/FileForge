package com.fileforge.converter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ocr.OcrText
import com.fileforge.core.ops.Operation
import com.fileforge.core.pdf.PageRangeParser
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** 识别时长边目标：A4 约 1487×2105，中文小字仍能分开；再大对内存不划算。 */
private const val OCR_LONG_EDGE = 2200
private const val OCR_TIMEOUT_SECONDS = 120L

/**
 * 离线 OCR：图片整张认，PDF 逐页渲染后再认。
 *
 * PDF 不看原来有没有文字层 —— 它认的是"画面上有什么"，所以扫描件与普通 PDF 走同一条路。
 * 页界由 [OcrText.page] 写在输出里，空页也会明确留一行，不会静悄悄少一页。
 */
class OcrEngine(private val workspace: Workspace) {

    private val images = ImageEngine()

    // 中文模型内置在 AAR 里（Hani + 拉丁），不联网也不下载；进程内复用一个客户端。
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun recognize(
        item: WorkItem,
        operation: Operation.OcrText,
        onProgress: (Int) -> Unit = {},
    ): EngineOutput =
        if (item.kind == FileKind.Pdf) pdf(item, operation, onProgress) else image(item)

    private fun image(item: WorkItem): EngineOutput {
        val decoded = images.decode(item.file)
        val bitmap = images.scale(decoded, OCR_LONG_EDGE)
        try {
            val text = OcrText.normalize(textOf(bitmap))
            require(text.isNotBlank()) { "这张图没认出文字；字太小或画面糊时，放大后再试" }
            val output = workspace.newStagingFile("txt").apply { writeText(text + "\n", Charsets.UTF_8) }
            return EngineOutput(
                OutputNaming.tagged(item.name, "文字", "txt"),
                output,
                "识别 ${OcrText.bodyChars(text)} 字 · ${bitmap.width}×${bitmap.height} · 本地模型（中文+英文）",
            )
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun pdf(item: WorkItem, operation: Operation.OcrText, onProgress: (Int) -> Unit): EngineOutput {
        val descriptor = ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            runCatching { descriptor.close() }
            throw IllegalArgumentException(
                "这份 PDF 打不开（可能加密或已损坏）：${error.message ?: error.javaClass.simpleName}",
            )
        }
        try {
            val total = renderer.pageCount
            require(total > 0) { "这份 PDF 一页都没有" }
            val spec = operation.spec.trim()
            val pages = if (spec.isEmpty()) {
                (0 until total).toList()
            } else {
                PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total)
            }
            require(pages.isNotEmpty()) { "页码范围没选中任何一页" }

            val parts = ArrayList<String>(pages.size)
            var chars = 0
            var blanks = 0
            pages.forEachIndexed { index, pageIndex ->
                onProgress(index * 100 / pages.size)
                val text = renderer.render(pageIndex) { page ->
                    val bitmap = renderPage(page)
                    try {
                        OcrText.normalize(textOf(bitmap))
                    } finally {
                        runCatching { bitmap.recycle() }
                    }
                }
                val count = OcrText.bodyChars(text)
                if (count == 0) blanks++ else chars += count
                parts += OcrText.page(pageIndex + 1, text, total)
            }
            onProgress(100)

            require(chars > 0) {
                "选中的 ${pages.size} 页一页文字都没认出来；换清晰些的扫描件，" +
                    "或先用「PDF 每页导出图片」看看画面本身"
            }
            val output = workspace.newStagingFile("txt").apply {
                writeText(OcrText.join(parts), Charsets.UTF_8)
            }
            val picked = if (spec.isEmpty()) "整份 $total 页" else "${pages.size}/$total 页"
            val notes = buildList {
                add("$picked · 识别 $chars 字")
                if (blanks > 0) add("$blanks 页没认出字（页界仍留着）")
                add("按画面认，不看文字层")
            }
            return EngineOutput(
                OutputNaming.tagged(item.name, "文字", "txt"),
                output,
                notes.joinToString(" · "),
            )
        } finally {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val longest = max(page.width, page.height)
        val scale = if (longest <= 0) 2f else (OCR_LONG_EDGE.toFloat() / longest).coerceIn(1f, 2.5f)
        val bitmap = Bitmap.createBitmap(
            max(1, (page.width * scale).roundToInt()),
            max(1, (page.height * scale).roundToInt()),
            Bitmap.Config.ARGB_8888,
        )
        // PdfRenderer 不铺底色：不先刷白的话透明区域会被当成黑底，字就没了。
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun textOf(bitmap: Bitmap): String {
        val result = Tasks.await(
            recognizer.process(InputImage.fromBitmap(bitmap, 0)),
            OCR_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        return result.text
    }

    private inline fun <T> PdfRenderer.render(index: Int, block: (PdfRenderer.Page) -> T): T {
        val page = openPage(index)
        return try {
            block(page)
        } finally {
            runCatching { page.close() }
        }
    }
}
