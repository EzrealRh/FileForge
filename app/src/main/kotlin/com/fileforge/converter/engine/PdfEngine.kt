package com.fileforge.converter.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.pdf.PageRangeParser
import com.fileforge.core.pdf.SplitPlanner
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File

/** 一个转换产出的文件，name 是给用户看的名字。 */
data class EngineOutput(val name: String, val file: File, val note: String? = null)

class PdfEngine(private val context: Context, private val workspace: Workspace) {

    /**
     * 按目标体积分割。体积必须实测——一页能引用的共享对象在不同分组里大小不同，
     * 所以每组都要真存一遍才知道多大，[SplitPlanner] 用指数扩张控制实测次数。
     */
    fun splitBySize(item: WorkItem, targetBytes: Long, onProgress: (Int) -> Unit): List<EngineOutput> {
        val source = loadForReading(item.file)
        val scratch = workspace.newStagingFile("pdf")
        val outputs = ArrayList<EngineOutput>()
        try {
            val total = source.numberOfPages
            val groups = SplitPlanner(targetBytes) { pages ->
                onProgress(pages.lastOrNull()?.plus(1) ?: 0)
                buildInto(source, pages, scratch)
            }.plan(total)

            groups.forEachIndexed { index, pages ->
                val name = OutputNaming.part(item.name, index + 1, groups.size, "pdf")
                val target = workspace.newStagingFile("pdf")
                buildInto(source, pages, target)
                outputs += EngineOutput(name, target, "第 ${pages.first() + 1}-${pages.last() + 1} 页")
            }
        } finally {
            runCatching { source.close() }
            scratch.delete()
        }
        return outputs
    }

    fun extractPages(item: WorkItem, spec: String): EngineOutput {
        val source = loadForReading(item.file)
        try {
            val pages = PageRangeParser.toPageIndices(PageRangeParser.parse(spec), source.numberOfPages)
            val output = workspace.newStagingFile("pdf")
            buildInto(source, pages, output)
            val tag = "页" + spec.trim().split(Regex("[\\s,，、;；]+")).filter { it.isNotEmpty() }.joinToString("+")
            return EngineOutput(OutputNaming.tagged(item.name, tag, "pdf"), output, "共 ${pages.size} 页")
        } finally {
            runCatching { source.close() }
        }
    }

        /** 按选中顺序把多份 PDF 拼成一份。 */
    fun merge(items: List<WorkItem>): EngineOutput {
        require(items.size >= 2) { "合并 PDF 至少选两个文件" }
        val document = PDDocument()
        val output = workspace.newStagingFile("pdf")
        var pages = 0
        try {
            items.forEach { item ->
                loadForReading(item.file).use { source ->
                    for (index in 0 until source.numberOfPages) {
                        document.importPage(source.getPage(index))
                    }
                    pages += source.numberOfPages
                }
            }
            document.save(output)
        } finally {
            runCatching { document.close() }
        }
        return EngineOutput(
            OutputNaming.tagged(items.first().name, "合并${items.size}份", "pdf"),
            output,
            "共 $pages 页",
        )
    }

    fun pageCount(item: WorkItem): Int = loadForReading(item.file).use { it.numberOfPages }

    fun imagesToPdf(items: List<WorkItem>, paper: PdfPaper, marginDp: Int): EngineOutput {
        require(items.isNotEmpty()) { "没有图片可选" }
        val document = PDDocument()
        val output = workspace.newStagingFile("pdf")
        try {
            val margin = marginDp.toFloat()
            items.forEach { item ->
                val image = runCatching { PDImageXObject.createFromFileByContent(item.file, document) }
                    .getOrElse {
                        val bytes = item.file.readBytes()
                        PDImageXObject.createFromByteArray(document, bytes, item.name)
                    }
                val base = paperRectangle(paper, image)
                val page = PDPage(base)
                document.addPage(page)
                val availableWidth = (base.width - margin * 2).coerceAtLeast(1f)
                val availableHeight = (base.height - margin * 2).coerceAtLeast(1f)
                val scale = minOf(availableWidth / image.width, availableHeight / image.height, 1f)
                if (scale <= 0f) return@forEach
                val drawWidth = image.width * scale
                val drawHeight = image.height * scale
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(image, (base.width - drawWidth) / 2, (base.height - drawHeight) / 2, drawWidth, drawHeight)
                }
            }
            document.save(output)
        } finally {
            runCatching { document.close() }
        }
        val first = items.first().stem
        val name = if (items.size == 1) OutputNaming.tagged(first, "", "pdf") else OutputNaming.tagged(first, "等${items.size}张", "pdf")
        return EngineOutput(name, output, "${items.size} 页")
    }

    /** 框架自带的 PdfRenderer 走系统解码，比 PDFBox 光栅化快得多。 */
    fun pagesToImages(item: WorkItem, scale: Float, encode: (Bitmap) -> Pair<String, ByteArray>): List<EngineOutput> {
        val descriptor = ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        val outputs = ArrayList<EngineOutput>()
        try {
            for (index in 0 until renderer.pageCount) {
                renderer.openPage(index).use { page ->
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply { drawColor(0xFFFFFFFF.toInt()) }
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val (extension, bytes) = encode(bitmap)
                    bitmap.recycle()
                    outputs += EngineOutput(
                        OutputNaming.tagged(item.name, "第${index + 1}页", extension),
                        workspace.newStagingFile(extension).apply { writeBytes(bytes) },
                    )
                }
            }
        } finally {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
        return outputs
    }

    private fun buildInto(source: PDDocument, pages: List<Int>, target: File): Long {
        val document = PDDocument()
        try {
            pages.forEach { document.importPage(source.getPage(it)) }
            document.save(target)
        } finally {
            runCatching { document.close() }
        }
        return target.length()
    }

    private fun paperRectangle(paper: PdfPaper, image: PDImageXObject): PDRectangle = when (paper) {
        PdfPaper.A4 -> PDRectangle.A4
        PdfPaper.A5 -> PDRectangle.A5
        PdfPaper.Letter -> PDRectangle.LETTER
        PdfPaper.FitImage -> PDRectangle(image.width.toFloat(), image.height.toFloat())
    }

    private fun loadForReading(file: File): PDDocument =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

}
