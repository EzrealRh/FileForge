package com.fileforge.converter.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.pdf.PageGroups
import com.fileforge.core.pdf.PageRangeParser
import com.fileforge.core.pdf.PdfCompressPlan
import com.fileforge.core.pdf.PdfTier
import com.fileforge.core.pdf.SplitPlanner
import com.fileforge.core.util.SizeInput
import android.graphics.pdf.PdfRenderer
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** 一个转换产出的文件，name 是给用户看的名字。 */
data class EngineOutput(val name: String, val file: File, val note: String? = null)

class PdfEngine(private val context: Context, private val workspace: Workspace) {

    /**
     * 按目标体积分割。体积必须实测——一页能引用的共享资源在不同分组里大小不同
     * （实测：4 页共用一张图的文档拆成两半，每半约 55% 而不是 50%），
     * 所以每组都要真存一遍才知道多大，[SplitPlanner] 用指数扩张把每组实测次数压到 O(log 页数)。
     */
    fun splitBySize(item: WorkItem, targetBytes: Long, onProgress: (Int) -> Unit): List<EngineOutput> {
        val source = loadForReading(item.file)
        val scratch = workspace.newStagingFile("pdf")
        try {
            val total = source.numberOfPages
            val groups = SplitPlanner(targetBytes) { pages ->
                onProgress(((pages.lastOrNull() ?: 0) + 1) * 100 / total)
                copyPages(source, pages, scratch)
            }.plan(total)
            return writeGroups(item, source, groups, total)
        } finally {
            runCatching { source.close() }
            scratch.delete()
        }
    }

    /** 按份数把页数均分拆开，不看体积。 */
    fun splitIntoParts(item: WorkItem, parts: Int): List<EngineOutput> {
        val source = loadForReading(item.file)
        try {
            val total = source.numberOfPages
            return writeGroups(item, source, PageGroups.evenSized(total, parts), total)
        } finally {
            runCatching { source.close() }
        }
    }

    private fun writeGroups(item: WorkItem, source: PDDocument, groups: List<List<Int>>, total: Int): List<EngineOutput> =
        groups.mapIndexed { index, pages ->
            val output = workspace.newStagingFile("pdf")
            copyPages(source, pages, output)
            EngineOutput(
                OutputNaming.part(item.name, index + 1, groups.size, "pdf"),
                output,
                "第 ${pages.first() + 1}-${pages.last() + 1} 页 / 共 $total 页",
            )
        }

    fun extractPages(item: WorkItem, spec: String): EngineOutput {
        val source = loadForReading(item.file)
        try {
            val pages = PageRangeParser.toPageIndices(PageRangeParser.parse(spec), source.numberOfPages)
            val output = workspace.newStagingFile("pdf")
            copyPages(source, pages, output)
            val tag = "页" + spec.trim().split(Regex("[\\s,，、;；]+")).filter { it.isNotEmpty() }.joinToString("+")
            return EngineOutput(OutputNaming.tagged(item.name, tag, "pdf"), output, "共 ${pages.size} 页")
        } finally {
            runCatching { source.close() }
        }
    }

    /** 删页：把要留的页原样复制成新文件。 */
    fun removePages(item: WorkItem, spec: String): EngineOutput {
        val source = loadForReading(item.file)
        try {
            val total = source.numberOfPages
            val drop = PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total).toSet()
            require(drop.size < total) { "这些范围加起来把整份都删光了，写个小点的范围" }
            val keep = (0 until total).filter { it !in drop }
            val output = workspace.newStagingFile("pdf")
            copyPages(source, keep, output)
            return EngineOutput(
                OutputNaming.tagged(item.name, "删${drop.size}页", "pdf"),
                output,
                "删掉 ${drop.size} 页，剩 ${keep.size} 页",
            )
        } finally {
            runCatching { source.close() }
        }
    }

    /** 旋转：只认 90 的整数倍，顺时针；spec 留空表示所有页。其余页原样带过去。 */
    fun rotatePages(item: WorkItem, spec: String, degrees: Int): EngineOutput {
        require(degrees % 90 == 0) { "旋转角度要是 90 的整数倍" }
        val source = loadForReading(item.file)
        try {
            val total = source.numberOfPages
            val targets = if (spec.isBlank()) (0 until total).toSet()
            else PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total).toSet()
            require(targets.isNotEmpty()) { "没有页可旋转" }
            val output = workspace.newStagingFile("pdf")
            copyPages(source, (0 until total).toList(), output) { position, from, to ->
                if (position in targets) to.rotation = ((from.rotation + degrees) % 360 + 360) % 360
            }
            return EngineOutput(
                OutputNaming.tagged(item.name, "转${degrees}", "pdf"),
                output,
                "转了 ${targets.size} 页，共 $total 页",
            )
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * 按选中顺序把多份 PDF 拼成一份。
     *
     * 用 PDFBox 的 PDFMergerUtility 而不是手写 importPage：后者只浅拷贝页字典，
     * 字体/图片/注释仍指向源文档对象，源一关就 save 出空白或损坏的文件。
     */
    fun merge(items: List<WorkItem>): EngineOutput {
        require(items.size >= 2) { "合并 PDF 至少选两个文件" }
        val output = workspace.newStagingFile("pdf")
        var pages = 0
        val merger = PDFMergerUtility().apply { destinationFileName = output.absolutePath }
        items.forEach { item ->
            merger.addSource(item.file)
            pages += pageCount(item)
        }
        merger.mergeDocuments(scratchSetting())
        return EngineOutput(
            OutputNaming.tagged(items.first().name, "合并${items.size}份", "pdf"),
            output,
            "共 $pages 页",
        )
    }

    fun pageCount(item: WorkItem): Int = loadForReading(item.file).use { it.numberOfPages }

    /**
     * 压缩：内嵌图片降采样后重编 JPEG，并原地换掉那个间接对象——所以全篇共用的一张图只处理一次，
     * 所有引用自动指到新图。文字层和矢量图形原样保留。
     * 给了目标体积就沿 [PdfCompressPlan] 的阶梯一档一档往下走，每档真存一遍量实际大小。
     */
    fun compress(item: WorkItem, level: Int, targetBytes: Long?, onProgress: (Int, String) -> Unit): EngineOutput {
        val tiers = if (targetBytes == null) listOf(PdfCompressPlan.tier(level)) else PdfCompressPlan.tiersFrom(level)
        val original = item.size
        var best: Pair<EngineOutput, CompressStat>? = null
        for ((step, tier) in tiers.withIndex()) {
            val base = step * 100 / tiers.size
            val label = "第 ${step + 1}/${tiers.size} 档 ${tier.label}"
            val candidate = workspace.newStagingFile("pdf")
            val stat = compressPass(item.file, candidate, tier) { percent -> onProgress(base + percent / tiers.size, label) }
            val size = candidate.length()
            val output = EngineOutput(OutputNaming.tagged(item.name, "压缩", "pdf"), candidate, stat.note(original, size, tier))
            val better = best == null || size < best!!.first.file.length()
            if (better) {
                best?.first?.file?.delete()
                best = output to stat
            } else {
                candidate.delete()
            }
            if (targetBytes == null || size <= targetBytes || stat.memoryLimited) break
        }
        val output = best?.first ?: error("压缩没出结果")
        return if (targetBytes != null && output.file.length() > targetBytes) {
            output.copy(note = "${output.note}；还没到 ${SizeInput.format(targetBytes)}，文字和矢量部分压不动")
        } else {
            output
        }
    }

    private class CompressStat {
        var images = 0
        var replaced = 0
        var skipped = 0
        var kept = 0
        var memoryLimited = false

        fun note(original: Long, size: Long, tier: PdfTier): String {
            val parts = ArrayList<String>()
            parts += "${SizeInput.format(original)} → ${SizeInput.format(size)}"
            parts += "重编 $replaced/$images 张图"
            if (kept > 0) parts += "$kept 张重编更大已留原图"
            if (skipped > 0) parts += "$skipped 张没动（透明通道或特殊色彩空间）"
            if (memoryLimited) parts += "内存吃紧，后面的图没再处理"
            parts += "用档 ${tier.label}"
            return parts.joinToString("，")
        }
    }

    private fun compressPass(source: File, target: File, tier: PdfTier, onProgress: (Int) -> Unit): CompressStat {
        val document = loadForReading(source)
        val stat = CompressStat()
        try {
            val images = imageObjects(document)
            stat.images = images.size
            for ((position, reference) in images.withIndex()) {
                onProgress((position + 1) * 100 / images.size)
                when (shrinkOne(document, reference, tier)) {
                    Shrink.Replaced -> stat.replaced++
                    Shrink.Kept -> stat.kept++
                    Shrink.Skipped -> stat.skipped++
                    Shrink.NoRoom -> stat.memoryLimited = true
                }
                if (stat.memoryLimited) break
            }
            document.save(target)
        } finally {
            runCatching { document.close() }
        }
        return stat
    }

    private enum class Shrink { Replaced, Kept, Skipped, NoRoom }

    private fun imageObjects(document: PDDocument): List<COSObject> {
        val found = ArrayList<COSObject>()
        for (reference in document.document.objects) {
            val stream = runCatching { reference.getObject() }.getOrNull() as? COSStream ?: continue
            val subtype = stream.getDictionaryObject(COSName.SUBTYPE) as? COSName ?: continue
            if (subtype.name == COSName.IMAGE.name) found += reference
        }
        return found
    }

    private fun shrinkOne(document: PDDocument, reference: COSObject, tier: PdfTier): Shrink {
        val stream = reference.getObject() as? COSStream ?: return Shrink.Skipped
        val image = PDImageXObject(PDStream(stream), null)
        if (skipReason(stream, image) != null) return Shrink.Skipped
        val pixels = image.width.toLong() * image.height
        if (pixels > MAX_IMAGE_PIXELS) return Shrink.Skipped
        val originalBytes = stream.length
        val raw = try {
            image.image
        } catch (error: OutOfMemoryError) {
            return Shrink.NoRoom
        } ?: return Shrink.Skipped
        var scaled: Bitmap? = null
        return try {
            scaled = toJpegReady(raw, tier.maxEdge)
            val fresh = JPEGFactory.createFromImage(document, scaled, tier.quality).cosObject as COSStream
            if (fresh.length >= originalBytes) {
                Shrink.Kept
            } else {
                reference.setObject(fresh)
                Shrink.Replaced
            }
        } catch (error: OutOfMemoryError) {
            Shrink.NoRoom
        } catch (error: Exception) {
            Shrink.Skipped
        } finally {
            scaled?.recycle()
            raw.recycle()
        }
    }

    private fun skipReason(stream: COSStream, image: PDImageXObject): String? = when {
        stream.getItem(COSName.SMASK) != null -> "带透明通道，重编会丢"
        stream.getBoolean(COSName.IMAGE_MASK.name, false) -> "1 位掩膜图，重编会糊"
        runCatching { image.colorSpace?.name }.getOrNull() !in PROCESSABLE -> "色彩空间不适合重编"
        else -> null
    }

    /** 降采样并铺白底：JPEG 不接受透明通道，也不接受 ARGB 之外的位图配置。 */
    private fun toJpegReady(source: Bitmap, maxEdge: Int): Bitmap {
        val ratio = minOf(1f, maxEdge.toFloat() / maxOf(source.width, source.height))
        val width = maxOf(1, Math.round(source.width * ratio))
        val height = maxOf(1, Math.round(source.height * ratio))
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(0xFFFFFFFF.toInt())
            drawBitmap(source, null, Rect(0, 0, width, height), Paint().apply { isFilterBitmap = true })
        }
        return out
    }

    /** 提取文字层。spec 留空取全文；给了范围就逐页抽，免得把不连续的页混成一段。 */
    fun toText(item: WorkItem, spec: String): EngineOutput {
        val document = loadForReading(item.file)
        try {
            val total = document.numberOfPages
            val pages = if (spec.isBlank()) (0 until total).toList()
            else PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total)
            val builder = StringBuilder()
            pages.forEach { index ->
                val stripper = PDFTextStripper()
                stripper.setSortByPosition(true)
                stripper.setStartPage(index + 1)
                stripper.setEndPage(index + 1)
                if (pages.size > 1) builder.append("── 第 ${index + 1} 页 ──\n")
                builder.append(stripper.getText(document).trim()).append('\n')
            }
            if (builder.isBlank()) error("这份 PDF 没有文字层，可能是扫描件；试试「每页导出图片」")
            val text = builder.toString()
            val output = workspace.newStagingFile("txt").apply { writeBytes(text.toByteArray()) }
            return EngineOutput(
                OutputNaming.tagged(item.name, "文字", "txt"),
                output,
                "${pages.size} 页，${SizeInput.format(output.length())}",
            )
        } finally {
            runCatching { document.close() }
        }
    }

    fun imagesToPdf(items: List<WorkItem>, paper: PdfPaper, marginDp: Int): EngineOutput {
        require(items.isNotEmpty()) { "没有图片可选" }
        val document = PDDocument(scratchSetting())
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
        val renderer = runCatching { PdfRenderer(descriptor) }
            .getOrElse {
                runCatching { descriptor.close() }
                throw IllegalArgumentException("这份 PDF 打不开：系统解码器不认它（可能加密或已损坏）")
            }
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

    /**
     * 复制指定页成新文件。
     *
     * importPage 只搬页字典，页面从 /Pages 继承来的 Resources 不会被带过去——PDFBox 自己就 warn
     * 「inherited resources of source document are not imported」。实测（桌面版同内核 2.0.27）：
     * 不补的话这类页在新文件里字全画不出来（整页墨迹 10.81%→10.50%，少的正好是字的部分），
     * 补上后逐页墨迹比例与原文件一致。
     */
    private fun copyPages(
        source: PDDocument,
        pages: List<Int>,
        target: File,
        tweak: (position: Int, from: PDPage, to: PDPage) -> Unit = { _, _, _ -> },
    ): Long {
        val document = PDDocument(scratchSetting())
        try {
            pages.forEachIndexed { position, index ->
                val from = source.getPage(index)
                val to = document.importPage(from)
                from.resources?.let { to.resources = it }
                tweak(position, from, to)
            }
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

    /** 读源文件；有密码的 PDF 在这里就换成人话提示，不往下抛一堆术语。 */
    private fun loadForReading(file: File): PDDocument = runCatching {
        PDDocument.load(file, scratchSetting())
    }.getOrElse { throw IllegalArgumentException(readFailureMessage(file), it) }

    private fun readFailureMessage(file: File): String {
        val emptyPassword = runCatching {
            PDDocument.load(file, "", scratchSetting()).also { runCatching { it.close() } }
        }.isSuccess
        return if (emptyPassword) "这份 PDF 读不了，可能已损坏" else "这份 PDF 有密码保护，先去掉密码再来"
    }

    /** 大文档别压 Java 堆：PDFBox 的中间对象全部落到应用缓存目录。 */
    private fun scratchSetting(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(File(context.cacheDir, "pdf").apply { mkdirs() })

    private companion object {
        const val MAX_IMAGE_PIXELS = 12_000_000L
        val PROCESSABLE = setOf("DeviceRGB", "DeviceGray")
    }
}
