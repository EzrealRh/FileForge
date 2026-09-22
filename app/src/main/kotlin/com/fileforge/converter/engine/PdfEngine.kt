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
import com.tom_roush.pdfbox.cos.COSBase
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
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
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
            copyPages(source, (0 until total).toList(), output) { index, from, to ->
                if (index in targets) to.rotation = ((from.rotation + degrees) % 360 + 360) % 360
            }
            return EngineOutput(
                OutputNaming.tagged(item.name, rotationTag(degrees), "pdf"),
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
            // 一张图都没得换就别再往下一档白跑一遍（每档都是整份读+整份写）
            if (stat.images == 0) break
            if (targetBytes == null || size <= targetBytes || stat.memoryLimited) break
        }
        val (output, stat) = best ?: error("压缩没出结果")
        if (stat.replaced == 0) {
            output.file.delete()
            error(
                when {
                    stat.images == 0 -> "这份 PDF 没有内嵌图片，压不动（文字和矢量不归这个操作管）"
                    stat.skipped > 0 -> "图片都不适合重编（带透明掩膜或 1 位扫描图），没出新文件"
                    else -> "图片重编后反而更大，原样已经够紧凑，没出新文件"
                },
            )
        }
        if (output.file.length() >= original) {
            output.file.delete()
            error("压完没比原文件小（${SizeInput.format(original)}）：这份的大头不在图片上，没出新文件")
        }
        return if (targetBytes != null && output.file.length() > targetBytes) {
            output.copy(note = "${output.note}；还没到 ${SizeInput.format(targetBytes)}，文字和矢量部分压不动")
        } else {
            output
        }
    }

    private class CompressStat {
        var images = 0
        var replaced = 0
        var kept = 0
        var memoryLimited = false
        private val reasons = LinkedHashMap<String, Int>()

        fun skip(reason: String) {
            reasons[reason] = (reasons[reason] ?: 0) + 1
        }

        val skipped: Int get() = reasons.values.sum()

        fun note(original: Long, size: Long, tier: PdfTier): String {
            val parts = ArrayList<String>()
            parts += "${SizeInput.format(original)} → ${SizeInput.format(size)}"
            parts += "重编 $replaced/$images 张图"
            if (kept > 0) parts += "$kept 张重编更大已留原图"
            if (reasons.isNotEmpty()) {
                parts += "跳过 $skipped 张（" + reasons.entries.joinToString("、") { "${it.key} ${it.value} 张" } + "）"
            }
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
                when (shrinkOne(document, reference, tier, stat)) {
                    Shrink.Replaced -> stat.replaced++
                    Shrink.Kept -> stat.kept++
                    Shrink.Skipped -> Unit
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

    /**
     * 文档里所有图片对象。软掩膜本身也是一张 /Subtype /Image 的灰度图，得先认出来排掉：
     * 把它当普通图重编成 JPEG 就变成「RGB 图 /SMask 指向一张 RGB 图」，规范不允许，安卓侧渲染会坏。
     */
    private fun imageObjects(document: PDDocument): List<COSObject> {
        val found = ArrayList<COSObject>()
        val masks = HashSet<COSBase>()
        for (reference in document.document.objects) {
            val stream = runCatching { reference.getObject() }.getOrNull() as? COSStream ?: continue
            val subtype = stream.getDictionaryObject(COSName.SUBTYPE) as? COSName ?: continue
            if (subtype.name != COSName.IMAGE.name) continue
            found += reference
            // 掩膜既可能是间接对象也可能是内联的，两种引用形式都记下来
            stream.getItem(COSName.SMASK)?.let { mask ->
                masks += mask
                if (mask is COSObject) runCatching { mask.getObject() }.getOrNull()?.let { masks += it }
            }
        }
        return found.filterNot { it in masks || it.getObject() in masks }
    }

    private fun shrinkOne(
        document: PDDocument,
        reference: COSObject,
        tier: PdfTier,
        stat: CompressStat,
    ): Shrink {
        val stream = reference.getObject() as? COSStream ?: return Shrink.Skipped
        val image = PDImageXObject(PDStream(stream), null)
        skipReason(stream, image)?.let {
            stat.skip(it)
            return Shrink.Skipped
        }
        if (image.width.toLong() * image.height > MAX_IMAGE_PIXELS) {
            stat.skip("单张超 ${MAX_IMAGE_PIXELS / 1_000_000}M 像素")
            return Shrink.Skipped
        }
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
            stat.skip("重编失败")
            Shrink.Skipped
        } finally {
            scaled?.recycle()
            raw.recycle()
        }
    }

    private fun skipReason(stream: COSStream, image: PDImageXObject): String? = when {
        stream.getItem(COSName.SMASK) != null -> "带透明掩膜"
        stream.getBoolean(COSName.IMAGE_MASK.name, false) -> "1 位掩膜图"
        stream.getInt(COSName.BITS_PER_COMPONENT, 8) == 1 -> "1 位扫描图"
        runCatching { image.colorSpace?.name }.getOrNull() !in PROCESSABLE -> "色彩空间不合适"
        else -> null
    }

    /** 文件名里的旋转说法要跟界面对得上：270 就是逆时针 90。 */
    private fun rotationTag(degrees: Int): String = when (((degrees % 360) + 360) % 360) {
        90 -> "右旋90"
        270 -> "左旋90"
        else -> "转${degrees}"
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
            var found = 0
            pages.forEach { index ->
                val stripper = PDFTextStripper()
                stripper.setSortByPosition(true)
                stripper.setStartPage(index + 1)
                stripper.setEndPage(index + 1)
                val text = stripper.getText(document).trim()
                if (text.isEmpty()) return@forEach
                found++
                if (pages.size > 1) builder.append("── 第 ${index + 1} 页 ──\n")
                builder.append(text).append('\n')
            }
            if (found == 0) error("这份 PDF 没有文字层（扫描件就是这样），要留档就走「每页导出图片」")
            val text = builder.toString()
            val output = workspace.newStagingFile("txt").apply { writeBytes(text.toByteArray()) }
            val silent = pages.size - found
            return EngineOutput(
                OutputNaming.tagged(item.name, "文字", "txt"),
                output,
                "${found} 页有文字" +
                    (if (silent > 0) "，$silent 页抽不出字（扫描页？）" else "") +
                    "，${SizeInput.format(output.length())}",
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
        /** tweak 收到的是原文件里的页号，不是本次复制列表里的下标。 */
        tweak: (pageIndex: Int, from: PDPage, to: PDPage) -> Unit = { _, _, _ -> },
    ): Long {
        val document = PDDocument(scratchSetting())
        try {
            pages.forEach { index ->
                val from = source.getPage(index)
                val to = document.importPage(from)
                from.resources?.let { to.resources = it }
                tweak(index, from, to)
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
    }.getOrElse { error ->
        val needsPassword = generateSequence<Throwable>(error) { it.cause }.any { it is InvalidPasswordException }
        throw IllegalArgumentException(
            if (needsPassword) "这份 PDF 有密码保护，先去掉密码再来"
            else "这份 PDF 读不了，可能已损坏：${error.message ?: error.javaClass.simpleName}",
        )
    }

    /** 大文档别压 Java 堆：PDFBox 的中间对象全部落到应用缓存目录。 */
    private fun scratchSetting(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(workspace.pdfScratch)

    private companion object {
        const val MAX_IMAGE_PIXELS = 12_000_000L
        val PROCESSABLE = setOf("DeviceRGB", "DeviceGray")
    }
}
