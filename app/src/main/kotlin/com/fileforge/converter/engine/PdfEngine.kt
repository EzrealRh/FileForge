package com.fileforge.converter.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.pdf.Horizontal
import com.fileforge.core.pdf.PageGroups
import com.fileforge.core.pdf.PageNumberPlan
import com.fileforge.core.pdf.PageRangeParser
import com.fileforge.core.pdf.PdfCompressPlan
import com.fileforge.core.pdf.PdfDoc
import com.fileforge.core.pdf.PdfLine
import com.fileforge.core.pdf.PdfMeta
import com.fileforge.core.pdf.PdfFontProbe
import com.fileforge.core.pdf.PdfPermission
import com.fileforge.core.pdf.PdfSecurity
import com.fileforge.core.pdf.PdfTableDetect
import com.fileforge.core.pdf.PdfTableSheets
import com.fileforge.core.pdf.PdfTier
import com.fileforge.core.pdf.PdfWord
import com.fileforge.core.pdf.TableSheet
import com.fileforge.core.pdf.PrintPlanner
import com.fileforge.core.pdf.SplitPlanner
import com.fileforge.core.pdf.StampFrame
import com.fileforge.core.pdf.StampSpot
import com.fileforge.core.pdf.TextFit
import com.fileforge.core.pdf.WatermarkPlan
import com.fileforge.core.util.SizeInput
import android.graphics.pdf.PdfRenderer
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.tom_roush.fontbox.ttf.OTFParser
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.fontbox.ttf.TrueTypeFont
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
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import com.fileforge.core.model.FileKind
import com.fileforge.core.office.Extracted
import com.fileforge.core.doc.Html
import com.fileforge.core.doc.Markdown
import java.io.Closeable
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 一个转换产出的文件，name 是给用户看的名字。 */
data class EngineOutput(val name: String, val file: File, val note: String? = null)

class PdfEngine(private val context: Context, private val workspace: Workspace) {

    private val images = ImageEngine()

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

    /**
     * 按给定页序重排另存：spec 形如 3,1,2 或 5-8,1 —— 顺序随意、可重复、可倒序，
     * 页码超界直接报错。页面用与删页同一条复制通路，资源跟着页走。
     */
    fun reorder(item: WorkItem, spec: String): EngineOutput {
        val source = loadForReading(item.file)
        try {
            val order = PageRangeParser.toPageIndices(PageRangeParser.parse(spec), source.numberOfPages)
            val output = workspace.newStagingFile("pdf")
            copyPages(source, order, output)
            return EngineOutput(
                OutputNaming.tagged(item.name, "重排", "pdf"),
                output,
                "按新顺序排出 ${order.size} 页（可重复、可倒序）",
            )
        } finally {
            runCatching { source.close() }
        }
    }

    /** 旋转：只认 90 的整数倍，顺时针；spec 留空表示所有页。其余页原样带过去。 */
    /**
     * 加页码：只在页面内容流末尾追加一段文字，原页面对象、字体、图片一概不动。
     * 位置按**你看到的方向**算，所以带 /Rotate 的页也落在视觉上的那个角。
     */
    fun addPageNumbers(item: WorkItem, operation: Operation.PageNumbers): EngineOutput {
        val document = loadForReading(item.file)
        var handle: FontHandle? = null
        try {
            val total = document.numberOfPages
            val pages = pagesFor(operation.spec, total)
            require(pages.isNotEmpty()) { "没有页可加页码" }
            val sample = PageNumberPlan.text(PageNumberPlan.numberFor(pages.first(), operation.firstNumber), total, operation.style)
            handle = fontFor(document, sample)
            pages.forEach { index ->
                val text = PageNumberPlan.text(PageNumberPlan.numberFor(index, operation.firstNumber), total, operation.style)
                stamp(document, document.getPage(index), handle.font, operation.fontSize.toFloat(), text,
                    Placement.AtSpot(operation.spot, operation.margin.toFloat()), DARK, 1f, 0f)
            }
            val output = workspace.newStagingFile("pdf")
            document.save(output)
            val skipped = if (pages.size == total) "" else "，其余 ${total - pages.size} 页没动"
            return EngineOutput(
                OutputNaming.tagged(item.name, "页码", "pdf"),
                output,
                "盖了 ${pages.size} 页，形如「${PageNumberPlan.text(PageNumberPlan.numberFor(pages.first(), operation.firstNumber), total, operation.style)}」，位置 ${operation.spot.label}$skipped",
            )
        } finally {
            handle?.close()
            runCatching { document.close() }
        }
    }

    /** 文字水印：1x1 是居中一块，行列更大就平铺。中文字体只把用得到的字形嵌进文件。 */
    fun watermark(item: WorkItem, operation: Operation.PdfWatermark): EngineOutput {
        val text = operation.text.trim()
        require(text.isNotEmpty()) { "先写要盖的字" }
        val document = loadForReading(item.file)
        var handle: FontHandle? = null
        try {
            val total = document.numberOfPages
            val pages = pagesFor(operation.spec, total)
            require(pages.isNotEmpty()) { "没有页可盖水印" }
            handle = fontFor(document, text)
            val columns = operation.columns.coerceIn(1, 12)
            val rows = operation.rows.coerceIn(1, 12)
            val alpha = (operation.opacityPercent.coerceIn(3, 100)) / 100f
            val gray = (operation.grayPercent.coerceIn(0, 95)) / 100f
            val tilt = operation.tilt.toFloat()
            pages.forEach { index ->
                val page = document.getPage(index)
                val box = page.cropBox
                val frame = StampFrame.forRotation(box.width, box.height, page.rotation)
                val cellWidth = WatermarkPlan.cellWidth(frame.width, columns)
                val size = TextFit.largestFontSize(text, cellWidth, maxSize = frame.height / rows * 0.8f)
                WatermarkPlan.tiles(frame.width, frame.height, columns, rows).forEach { (x, y) ->
                    stamp(document, page, handle.font, size, text, Placement.InMiddle(x, y), gray, alpha, tilt)
                }
            }
            val output = workspace.newStagingFile("pdf")
            document.save(output)
            val tiles = if (columns * rows == 1) "居中一块" else "每页平铺 ${columns}x$rows"
            return EngineOutput(
                OutputNaming.tagged(item.name, "水印", "pdf"),
                output,
                "「$text」$tiles，${(alpha * 100).roundToInt()}% 不透明、倾斜 $tilt°，盖了 ${pages.size} 页",
            )
        } finally {
            handle?.close()
            runCatching { document.close() }
        }
    }

    private sealed interface Placement {
        data class AtSpot(val spot: StampSpot, val margin: Float) : Placement
        data class InMiddle(val x: Float, val y: Float) : Placement
    }

    /** 真往页面上写字的那一步：先把坐标系掰成视觉方向，再按角度转文字。 */
    private fun stamp(
        document: PDDocument,
        page: PDPage,
        font: com.tom_roush.pdfbox.pdmodel.font.PDFont,
        size: Float,
        text: String,
        place: Placement,
        gray: Float,
        alpha: Float,
        tiltDegrees: Float,
    ) {
        val box = page.cropBox
        val frame = StampFrame.forRotation(box.width, box.height, page.rotation)
        val width = font.getStringWidth(text) / 1000f * size
        val radians = Math.toRadians(tiltDegrees.toDouble())
        val origin = when (place) {
            is Placement.AtSpot -> {
                val anchor = PageNumberPlan.anchor(frame.width, frame.height, place.spot, place.margin, size)
                val x = when (anchor.align) {
                    Horizontal.LEFT -> anchor.x
                    Horizontal.CENTER -> anchor.x - width / 2f
                    Horizontal.RIGHT -> anchor.x - width
                }
                x to anchor.y
            }
            is Placement.InMiddle -> place.x - cos(radians).toFloat() * width / 2f to
                place.y - sin(radians).toFloat() * width / 2f
        }
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true).use { stream ->
            if (alpha < 0.999f) {
                stream.setGraphicsStateParameters(
                    PDExtendedGraphicsState().apply {
                        nonStrokingAlphaConstant = alpha
                        strokingAlphaConstant = alpha
                    },
                )
            }
            stream.transform(
                Matrix().apply {
                    // 上面的视觉坐标是相对裁剪框的：裁剪框原点不在 (0,0) 的页（扫描、裁边过的 PDF 常见）
                    // 不把原点补回来，整块水印就整体偏出可见区域
                    translate(box.lowerLeftX + frame.translateX, box.lowerLeftY + frame.translateY)
                    rotate(Math.toRadians(frame.rotateDegrees.toDouble()))
                },
            )
            stream.beginText()
            stream.setFont(font, size)
            stream.setNonStrokingColor(gray)
            stream.setTextMatrix(
                Matrix().apply {
                    translate(origin.first, origin.second)
                    rotate(radians)
                },
            )
            stream.showText(text)
            stream.endText()
        }
    }

    /** spec 留空就是所有页；否则按页码范围挑。 */
    private fun pagesFor(spec: String, total: Int): List<Int> =
        if (spec.isBlank()) (0 until total).toList()
        else PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total)

    /** 字体可能来自字体集合，底层句柄要活到存盘之后，所以连它一起交给调用方关。 */
    private class FontHandle(val font: com.tom_roush.pdfbox.pdmodel.font.PDFont, private val owned: List<Closeable>, val source: String = "") {
        fun close() = owned.forEach { runCatching { it.close() } }
    }

    /**
     * 能显示这段字的最便宜的字体：纯 ASCII 用标准 Helvetica（不内嵌，零体积），
     * 其余到系统字体目录里逐个试，判据是"真的能编码这段字"而不是猜字体名。
     */
    private fun fontFor(document: PDDocument, text: String): FontHandle {
        // 探针只问"需要字形的字符"能不能画：换行与制表符不进画布，
        // 拿整段去问会把每个候选字体都判成"没这个字"（真机上就是整次打印失败）
        val needed = PdfFontProbe.glyphChars(text)
        if (PdfFontProbe.latinOnly(text)) return FontHandle(PDType1Font.HELVETICA, emptyList(), "Helvetica（内置罗马字）")
        for (path in SYSTEM_FONTS) {
            val file = File(path)
            if (!file.isFile) continue
            val opened = runCatching { openTrueType(file) }.getOrNull() ?: continue
            val (owner, face) = opened
            val font = runCatching { PDType0Font.load(document, face, true) }.getOrNull()
            val fits = font != null && runCatching {
                font.encode(needed)
                font.getStringWidth(needed)
                true
            }.getOrDefault(false)
            if (fits) {
                return FontHandle(font!!, listOfNotNull(owner, face as Closeable), File(path).name)
            }
            runCatching { face.close() }
            runCatching { owner?.close() }
        }
        // 系统里挑不出来就用随应用打包的那一份：AOSP / GMS 镜像的中文字体是 CFF 轮廓的 OTC
        // （NotoSansCJK-Regular.ttc），pdfbox-android 认不了 CFF —— 只靠系统字体就等于
        // "这台机器上印不出中文"，而桌面替身是直接加载字体文件的，永远走不到这一步
        val bundled = runCatching {
            val stream = context.assets.open(BUNDLED_FONT)
            val font = com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(document, stream, true)
            if (runCatching { font.encode(needed); font.getStringWidth(needed); true }.getOrDefault(false)) {
                font
            } else {
                // 覆盖不全**也用它**：子集承诺的是 ASCII + Latin-1 + 中文标点 + GB2312，
                // 软字号、emoji 与 GB2312 之外的生僻字照样画不出。为一两个符号让整次打印失败是本末倒置 ——
                // 画不出的由 onlyDrawable 那层逐字跳过、并且数给用户看
                // （Ç 曾在子集外面，真机上「印成 PDF」就这么被一个符号整份顶掉过）
                val drawable = needed.count { ch ->
                    runCatching { font.encode(ch.toString()); true }.getOrDefault(false)
                }
                if (drawable > 0) font else null
            }
        }.getOrNull()
        if (bundled != null) return FontHandle(bundled, emptyList(), "内置霞鹜文楷（子集）")
        throw IllegalArgumentException(
            "这台系统里没找到能显示「${needed.take(24)}」这些字的字体，换个带中文的字体或把文字改成英文数字再试",
        )
    }

    /** .ttc 是字体集合，只能按名字挑一个；单个 ttf/otf 直接解析。返回 (需要一起关的集合, 真正的面)。 */
    private fun openTrueType(file: File): Pair<Closeable?, TrueTypeFont> {
        if (!file.name.endsWith(".ttc", ignoreCase = true)) {
            val parser = if (file.name.endsWith(".otf", ignoreCase = true)) OTFParser() else TTFParser()
            return null to parser.parse(file)
        }
        val collection = TrueTypeCollection(file)
        val name = TTC_FONT_NAMES.firstOrNull { runCatching { collection.getFontByName(it) }.isSuccess }
            ?: error("字体集合里认不出可用的字面")
        return collection to collection.getFontByName(name)
    }

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
    /**
     * PDF 元数据：改写单由 `:core` 那份 `PdfMeta` 算（哪几项要写、哪几项要删、话怎么说），
     * 这里只负责把它落到 `/Info` 上。
     *
     * 带口令的先拦住：加密文件里 `/Info` 与其他对象一起被包在密文里，改完不重算加密
     * 等于交一份打不开的东西，而重算又要用户把两个密码都填对 —— 不如让他先出明文副本。
     */
    fun metadata(item: WorkItem, operation: Operation.PdfMetadata): EngineOutput {
        val now = System.currentTimeMillis()
        val patch = PdfMeta.patch(
            mode = operation.mode,
            values = operation.values(),
            keepDates = operation.keepDates,
            stampNow = operation.stampNow,
            nowMillis = now,
        )
        require(patch.changed) { (patch.notes + "没什么要改的").joinToString(" · ") }
        val document = PDDocument.load(item.file, MemoryUsageSetting.setupTempFileOnly())
        try {
            require(!document.isEncrypted) { "这份 PDF 带口令，先用「去掉密码」出一份明文副本再来改属性" }
            val info = document.documentInformation ?: com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
            val before = PdfMeta.FIELDS.mapNotNull { (key, label) ->
                value(info, key)?.takeIf { it.isNotBlank() }?.let { label to it }
            } + PdfMeta.dateLabels().mapNotNull { (key, label) ->
                value(info, key)?.takeIf { it.isNotBlank() }?.let { label to PdfMeta.readableDate(it) }
            }
            patch.removes.forEach { key ->
                when {
                    key == "*" -> metadataKeys(info).filterNot { it in patch.keeps }
                        .forEach { info.setCustomMetadataValue(it, null) }
                    key == "CreationDate" -> info.setCreationDate(null)
                    key == "ModDate" -> info.setModificationDate(null)
                    else -> info.setCustomMetadataValue(key, null)
                }
            }
            patch.writes.forEach { (key, value) -> info.setCustomMetadataValue(key, value) }
            if (patch.stampNow) {
                val whenAt = java.util.Calendar.getInstance().apply { timeInMillis = now }
                info.setModificationDate(whenAt)
            }
            document.documentInformation = info
            val output = workspace.newStagingFile("pdf")
            document.save(output)
            val notes = ArrayList(patch.notes)
            if (patch.removes.isNotEmpty()) {
                val gone = before.filter { (label, _) -> patch.removes.any { key -> PdfMeta.label(key) == label } }
                notes += if (gone.isEmpty()) "原本就没有可清的属性"
                else "清掉了 ${gone.size} 项：${gone.joinToString("、") { (label, old) -> "$label（原来写着「${old.take(24)}」）" }}"
            }
            notes += if (patch.stampNow) "修改时间已盖成现在" else ""
            notes.removeAll { it.isEmpty() }
            notes += "${document.numberOfPages} 页不变 · 正文与图片没重新编码"
            return EngineOutput(
                OutputNaming.tagged(item.name, if (operation.mode == "edit") "改属性" else "清属性", "pdf"),
                output,
                notes.joinToString(" · "),
            )
        } finally {
            runCatching { document.close() }
        }
    }

    /** 一个键现在的值。标准键与自定义键都从这一处问，免得两边读法不同。 */
    private fun value(info: com.tom_roush.pdfbox.pdmodel.PDDocumentInformation, key: String): String? =
        runCatching { info.getCustomMetadataValue(key) }.getOrNull()

    private fun metadataKeys(info: com.tom_roush.pdfbox.pdmodel.PDDocumentInformation): List<String> =
        runCatching { info.metadataKeys.toList() }.getOrDefault(emptyList())

    /**
     * 印成 PDF：**所有来源都走同一棵文档树**（`SourceText.of` 判一次，`PrintPlanner` 排一次）。
     *
     * 与"抽成一片文字再排版"不是一条路 —— 那种排法把表格摊平成制表符、把"这是二级标题"
     * 与"这是一列表"抹平，纸上就只剩字号均匀的连续文字；而"网页转 PDF""Markdown 转 PDF"
     * 是转换工具站上的头几条，要的恰恰是表格还长在表格里。
     */
    fun textToPdf(item: WorkItem, operation: Operation.TextToPdf): EngineOutput = treeToPdf(item, operation)

    /**
     * 文档树印成 PDF：**表格画成真的网格**（一行一格、列边界画线、表头那条线加重），
     * 标题按层级抬字号，列表把圆点与编号画在纸上，引用往里缩一块。
     *
     * 树由 [SourceText] 给 —— 写成 Word、写成电子书与这一条拿的是同一棵树，同一份文件在三种产物里
     * 才是同一个结构。与"抽文字再排版"不是一条路：那种排法把格子摊平成制表符，
     * 两列的表在纸上就是一行字，列与列的位置关系全丢 —— 而"表格转 PDF"要保住的恰恰是这个。
     */
    private fun treeToPdf(item: WorkItem, operation: Operation.TextToPdf): EngineOutput {
        val reading = SourceText.of(item)
        val parts = reading.doc.parts
        require(parts.isNotEmpty()) { (reading.notes + "这份文件里没有可排的内容").joinToString(" · ") }
        val document = PDDocument()
        var handle: FontHandle? = null
        val missing = intArrayOf(0)
        try {
            val rectangle = when (operation.paper) {
                PdfPaper.A5 -> PDRectangle.A5
                PdfPaper.Letter -> PDRectangle.LETTER
                else -> PDRectangle.A4
            }
            val pageHeight = rectangle.height
            val margin = operation.marginPt
                .coerceIn(0, (minOf(rectangle.width, pageHeight) / 4).toInt())
                .toFloat()
            val size = operation.fontSize.toFloat()
            handle = fontFor(document, com.fileforge.core.doc.HtmlWrite.text(parts).take(4000))
            val font = handle.font
            val bullet = bulletFor(font)
            val plan = PrintPlanner.plan(
                parts = parts,
                pageWidth = rectangle.width,
                pageHeight = pageHeight - if (operation.numberPages) size * 2.5f else 0f,
                margin = margin,
                size = size,
                lineHeightFactor = operation.lineHeight,
                firstLineIndent = if (operation.firstLineIndent) size * 2f else 0f,
                widthOf = { text, pt -> measure(font, text, pt) },
                bullet = bullet,
            )
            plan.pages.forEachIndexed { index, page ->
                val pdPage = PDPage(rectangle)
                document.addPage(pdPage)
                val stream = PDPageContentStream(document, pdPage)
                stream.setLineCapStyle(0)
                page.rules.forEach { rule ->
                    stream.setLineWidth(rule.width)
                    stream.moveTo(rule.x1, pageHeight - rule.y1)
                    stream.lineTo(rule.x2, pageHeight - rule.y2)
                    stream.stroke()
                }
                stream.beginText()
                stream.setNonStrokingColor(0f, 0f, 0f)
                page.texts.forEach { line ->
                    if (line.text.isEmpty()) return@forEach
                    stream.setFont(font, line.size)
                    stream.setTextMatrix(Matrix.getTranslateInstance(line.x, pageHeight - line.y))
                    stream.showText(onlyDrawable(font, line.text, missing))
                }
                stream.endText()
                if (operation.numberPages) {
                    drawPageNumber(stream, font, size, rectangle, margin, index + 1, plan.pageCount)
                }
                stream.close()
            }
            val output = workspace.newStagingFile("pdf")
            document.save(output)
            // 说明由 SourceText 那一份给（按哪条路读的、几张表、丢了什么），这里不重数一遍；
            // 两条来源可能写同一句话，去重后再接排版那层的交代
            val notes = ArrayList(LinkedHashSet(reading.notes + reading.doc.notes))
            if (bullet != "•") notes += "这份字体没有圆点（U+2022），列表的记号用「$bullet」"
            notes += "${plan.pageCount} 页 · ${size.toInt()}pt · ${operation.paper.label} · 表格画成网格"
            handle?.source?.takeIf { it.isNotEmpty() }?.let { notes += "字体：$it" }
            if (missing[0] > 0) notes += "选中字体没有 ${missing[0]} 个字，这些位置会缺字"
            plan.notes.forEach { notes += it }
            return EngineOutput(OutputNaming.tagged(item.name, "", "pdf"), output, notes.joinToString(" · "))
        } finally {
            runCatching { handle?.close() }
            runCatching { document.close() }
        }
    }

    /**
     * 圆点这一笔要看字体有没有：安卓上挑到的那款不一定带 U+2022，硬画就得到一个方框，
     * 或者被 [onlyDrawable] 吃掉 —— 列表看起来就跟正文没区别了。挑不到就用中圆点，再退到连字符。
     */
    private fun bulletFor(font: com.tom_roush.pdfbox.pdmodel.font.PDFont): String =
        listOf("•", "·", "-").firstOrNull { mark -> runCatching { font.getStringWidth(mark) }.isSuccess } ?: ""

    /**
     * 一行有多长。字体里没有那个字时按一个字宽占位 —— 缺几个由 [onlyDrawable] 数，
     * 这里再数一遍会把同一批字报两次。
     */
    private fun measure(
        font: com.tom_roush.pdfbox.pdmodel.font.PDFont,
        text: String,
        size: Float,
    ): Float = runCatching { font.getStringWidth(text) / 1000f * size }.getOrElse { text.length * size }

    /**
     * 把字体画不出来的字剔掉。
     *
     * PDFBox 在 showText 里遇到编码不出来的字符会直接抛异常，让整个任务失败 ——
     * 与其崩在半路留一份写了一半的 PDF，不如把能画的都画上、把缺字数量如实报出来。
     */
    private fun onlyDrawable(
        font: com.tom_roush.pdfbox.pdmodel.font.PDFont,
        text: String,
        missing: IntArray,
    ): String {
        if (runCatching { font.getStringWidth(text); true }.getOrDefault(false)) return text
        val kept = StringBuilder()
        text.forEach { ch ->
            if (runCatching { font.getStringWidth(ch.toString()); true }.getOrDefault(false)) kept.append(ch) else missing[0]++
        }
        return kept.toString()
    }

    /** 页码画在页面底部正中，跟正文同一个内容流：同一页开两条流会互相覆盖。 */
    private fun drawPageNumber(
        stream: PDPageContentStream,
        font: com.tom_roush.pdfbox.pdmodel.font.PDFont,
        size: Float,
        rectangle: PDRectangle,
        margin: Float,
        number: Int,
        total: Int,
    ) {
        val text = "$number / $total"
        val width = measure(font, text, size * 0.9f)
        stream.beginText()
        stream.setFont(font, size * 0.9f)
        stream.setNonStrokingColor(0.45f, 0.45f, 0.45f)
        // 页码放进下边距这条带子里：贴着纸边打印机会吃掉一半（不可打印区就有 5mm ≈ 14pt）
        stream.newLineAtOffset((rectangle.width - width) / 2f, (margin - size * 1.5f).coerceAtLeast(size))
        stream.showText(text)
        stream.endText()
    }

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

    /**
     * PDF → Word：逐页量出每行的字与样子，交给 `:core` 那份判断层还原结构，再写成 docx。
     *
     * 判断全在 `:core`（那边能脱机单测，也能拿 pdfminer 独立量的字号对答案）；这里只管量与落盘。
     */
    fun toDocx(item: WorkItem, operation: Operation.PdfToDocx): EngineOutput {
        val document = loadForReading(item.file)
        try {
            val read = structureOf(document, operation.spec)
            val doc = read.first
            require(doc.parts.isNotEmpty()) {
                doc.notes.joinToString(" · ").ifBlank { "这份 PDF 没抽出可用的文字" } +
                    "；扫描件要留档就走「每页导出图片」"
            }
            val out = com.fileforge.core.office.DocxWrite.document(doc, modifiedAt = item.file.lastModified())
            val file = workspace.newStagingFile("docx").apply { writeBytes(out.bytes) }
            val notes = ArrayList(listOf("${doc.parts.size} 段 · ${read.second} 页") + doc.notes + out.notes)
            if (read.third.isEmpty()) notes += "没量到任何一行"
            return EngineOutput(
                OutputNaming.tagged(item.name, "Word", "docx"),
                file,
                notes.joinToString(" · "),
            )
        } finally {
            runCatching { document.close() }
        }
    }

    /**
     * PDF → 网页：与「PDF 转 Word」量同一批行、走同一个结构还原层，只是落成 HTML。
     *
     * 落成 HTML 而不是 XHTML：浏览器直接双击打开就行；标题、列表、表格、引用、代码块都在，
     * 链接与图片不在 —— 那份 PDF 里的行只有字与位置，没有"这是个链接"这件事。
     */
    fun toHtml(item: WorkItem, operation: Operation.PdfToHtml): EngineOutput {
        val document = loadForReading(item.file)
        try {
            val (doc, pages, lines) = structureOf(document, operation.spec)
            require(doc.parts.isNotEmpty()) {
                doc.notes.joinToString(" · ").ifBlank { "这份 PDF 没抽出可用的文字" } +
                    "；扫描件要留档就走「每页导出图片」"
            }
            val page = com.fileforge.core.doc.HtmlWrite.page(
                title = operation.title.ifBlank { OutputNaming.stem(item.name) },
                parts = doc.parts,
                language = com.fileforge.core.book.EpubWrite.languageOf(doc),
            )
            val file = workspace.newStagingFile("html").apply { writeText(page.html, Charsets.UTF_8) }
            val notes = ArrayList(
                listOf("${doc.parts.size} 段 · $pages 页", "带 charset 的完整页面，浏览器直接打开"),
            )
            notes += doc.notes
            notes += page.notes
            if (lines.isEmpty()) notes += "没量到任何一行"
            return EngineOutput(
                OutputNaming.tagged(item.name, "网页", "html"),
                file,
                notes.filter { it.isNotBlank() }.joinToString(" · "),
            )
        } finally {
            runCatching { document.close() }
        }
    }

    /**
     * 插白页：0 表示最前面；从后往前插，前面要用的页号就不会被自己插乱。
     * 纸张取相邻那一页的 MediaBox，插在正文中间的空白页不会比正文大一圈。
     */
    fun insertBlankPages(item: WorkItem, afterSpec: String): EngineOutput {
        val source = loadForReading(item.file)
        try {
            val total = source.numberOfPages
            require(total > 0) { "这份 PDF 一页都没有" }
            val spots = insertSpots(afterSpec, total)
            require(spots.isNotEmpty()) { "要插在哪几页后？写 1,3-5；0 表示最前面" }
            val output = workspace.newStagingFile("pdf")
            // 不能对新建页直接 insertAfter：这份 PDF 的页没有 /Parent，PDFBox 会判 orphan。
            // 与拆分/合并同一条路：按顺序 importPage 进新文档（它自己会把页加进去），
            // 在指定位置补一张同尺寸白页；**别再 addPage 一次**，否则每页都会翻倍。
            val target = PDDocument(scratchSetting())
            try {
                target.documentInformation = source.documentInformation
                if (0 in spots) target.addPage(PDPage(source.getPage(0).mediaBox))
                for (index in 0 until total) {
                    val from = source.getPage(index)
                    val to = target.importPage(from)
                    from.resources?.let { to.resources = it }
                    if (index + 1 in spots) target.addPage(PDPage(from.mediaBox))
                }
                target.save(output)
            } finally {
                runCatching { target.close() }
            }
            val where = spots.joinToString("、") { if (it == 0) "最前面" else "第 $it 页后" }
            return EngineOutput(
                OutputNaming.tagged(item.name, "插${spots.size}张白页", "pdf"),
                output,
                "在 $where 各插一张，$total→${total + spots.size} 页 · 页按新文件重排，书签目录不搬",
            )
        } finally {
            runCatching { source.close() }
        }
    }

    private fun insertSpots(spec: String, total: Int): List<Int> {
        val spots = sortedSetOf<Int>()
        spec.split(Regex("[\\s,，、;；]+")).filter { it.isNotBlank() }.forEach { part ->
            val match = Regex("^(\\d+)(?:-(\\d+))?$").matchEntire(part)
                ?: throw IllegalArgumentException("「$part」不是页码；写 1,3-5，0 表示最前面")
            val from = match.groupValues[1].toInt()
            val to = match.groupValues[2].ifBlank { match.groupValues[1] }.toInt()
            require(from in 0..total && to in 0..total) {
                "页码要在 0..$total 之间（0 表示最前面），「$part」超了"
            }
            spots += minOf(from, to)..maxOf(from, to)
        }
        return spots.toList()
    }

    /** 裁页边：MediaBox 与 CropBox 一起收；内容坐标不动，只是可视范围小了。 */
    fun cropPages(item: WorkItem, marginPt: Int): EngineOutput {
        require(marginPt in 1..500) { "四边各裁 1~500 pt" }
        val document = loadForReading(item.file)
        try {
            document.pages.forEach { page ->
                val box = page.mediaBox
                require(box.width - 2 * marginPt >= 10 && box.height - 2 * marginPt >= 10) {
                    "裁掉 $marginPt pt 后剩下的页面太小（原页 ${box.width}×${box.height} pt）"
                }
                val rect = PDRectangle(
                    box.lowerLeftX + marginPt,
                    box.lowerLeftY + marginPt,
                    box.width - 2 * marginPt,
                    box.height - 2 * marginPt,
                )
                page.mediaBox = rect
                page.cropBox = rect
            }
            val output = workspace.newStagingFile("pdf")
            document.save(output)
            return EngineOutput(
                OutputNaming.tagged(item.name, "裁边", "pdf"),
                output,
                "${document.numberOfPages} 页四边各收 $marginPt pt（内容不重排）",
            )
        } finally {
            runCatching { document.close() }
        }
    }

    /** 提取页面里引用的位图；同一张图被多页引用时按对象去重。 */
    fun extractImages(item: WorkItem, operation: Operation.ExtractPdfImages): List<EngineOutput> {
        val document = loadForReading(item.file)
        val outputs = ArrayList<EngineOutput>()
        try {
            val seen = HashSet<String>()
            for (pageIndex in 0 until document.numberOfPages) {
                val resources = document.getPage(pageIndex).resources ?: continue
                for (name in resources.xObjectNames) {
                    val objectImage = runCatching { resources.getXObject(name) }.getOrNull()
                    if (objectImage !is PDImageXObject) continue
                    val width = objectImage.width
                    val height = objectImage.height
                    if (minOf(width, height) < operation.minEdge) continue
                    val bitmap = runCatching { objectImage.image }.getOrNull() ?: continue
                    val key = "${width}x$height:${System.identityHashCode(objectImage.cosObject)}"
                    if (!seen.add(key)) {
                        bitmap.recycle()
                        continue
                    }
                    val bytes = images.encode(bitmap, operation.format, operation.quality)
                    bitmap.recycle()
                    outputs += EngineOutput(
                        OutputNaming.tagged(item.name, "图${outputs.size + 1}", operation.format.extension),
                        workspace.newStagingFile(operation.format.extension).apply { writeBytes(bytes) },
                        if (outputs.isEmpty()) "${width}×$height（第 ${pageIndex + 1} 页起）" else null,
                    )
                }
            }
            require(outputs.isNotEmpty()) {
                "这份 PDF 里没找到可提取的内嵌位图（整页扫描图或纯矢量）；要整页画面就走「每页导出图片」"
            }
            return outputs
        } finally {
            runCatching { document.close() }
        }
    }

    /** 按一级书签拆；第一个书签之前的页单独成一份「开篇」。 */
    fun splitByOutline(item: WorkItem): List<EngineOutput> {
        val source = loadForReading(item.file)
        try {
            val outline = source.documentCatalog.documentOutline
                ?: throw IllegalArgumentException("这份 PDF 没有书签目录；按页拆可以用「拆成 N 份」或「按体积分割」")
            val marks = ArrayList<Pair<String, Int>>()
            var node = outline.firstChild
            while (node != null) {
                val page = runCatching { node.findDestinationPage(source) }.getOrNull()
                if (page != null) {
                    val index = source.pages.indexOf(page)
                    if (index >= 0) {
                        val title = node.title?.trim()?.takeIf { it.isNotEmpty() } ?: "第 ${index + 1} 页"
                        marks += title to index
                    }
                }
                node = node.nextSibling
            }
            require(marks.isNotEmpty()) { "书签里没有能落到具体页的条目" }
            val total = source.numberOfPages
            val starts = marks.sortedBy { it.second }.distinctBy { it.second }
            val groups = ArrayList<Triple<String, Int, Int>>()
            if (starts.first().second > 0) groups += Triple("开篇", 0, starts.first().second)
            starts.forEachIndexed { index, (title, start) ->
                val end = starts.getOrNull(index + 1)?.second ?: total
                if (start < end) groups += Triple(title, start, end)
            }
            require(groups.isNotEmpty()) { "书签只指到最后一页之后，拆不出内容" }
            return groups.map { (title, start, end) ->
                val output = workspace.newStagingFile("pdf")
                copyPages(source, (start until end).toList(), output)
                EngineOutput(
                    OutputNaming.tagged(item.name, safeTag(title) + "_第${start + 1}-${end}页", "pdf"),
                    output,
                    "《$title》 第 ${start + 1}-$end 页（共 ${end - start} 页）",
                )
            }
        } finally {
            runCatching { source.close() }
        }
    }

    private fun safeTag(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(24).ifBlank { "节" }

    /** PDF → EPUB：与转 Word / 网页共用同一棵结构树，只是落成电子书。 */
    fun toEpub(item: WorkItem, operation: Operation.PdfToEpub): EngineOutput {
        val document = loadForReading(item.file)
        try {
            val (doc, pages, _) = structureOf(document, "")
            require(doc.parts.isNotEmpty()) {
                doc.notes.joinToString(" · ").ifBlank { "这份 PDF 没抽出可用的文字" } +
                    "；扫描件要留档就走「每页导出图片」"
            }
            val title = operation.title.ifBlank { OutputNaming.stem(item.name) }
            val author = operation.author.ifBlank { null }
            val chapters = com.fileforge.core.book.EpubWrite.chaptersOf(doc, OutputNaming.stem(item.name))
            val language = com.fileforge.core.book.EpubWrite.languageOf(doc)
            val out = com.fileforge.core.book.EpubWrite.book(
                title = title,
                author = author,
                pages = chapters,
                language = language,
                identifier = com.fileforge.core.book.EpubWrite.identifierFor(title + item.name),
                modifiedAt = item.file.lastModified(),
            )
            return EngineOutput(
                OutputNaming.tagged(item.name, "", "epub"),
                workspace.newStagingFile("epub").apply { writeBytes(out.bytes) },
                "《$title》 · ${chapters.size} 章 · $pages 页 · 语言标 $language · " +
                    (doc.notes + out.notes).joinToString(" · "),
            )
        } finally {
            runCatching { document.close() }
        }
    }

    /** 按页码范围量行 → 还原结构。写成 Word 与写成网页共用，两个产物不会一个说 12 段一个说 14 段。 */
    private fun structureOf(document: PDDocument, spec: String): Triple<com.fileforge.core.doc.Doc, Int, List<PdfLine>> {
        val total = document.numberOfPages
        val pages = if (spec.isBlank()) (0 until total).toList()
        else PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total)
        val lines = ArrayList<PdfLine>()
        pages.forEach { index ->
            val scribe = PdfLineScribe(index)
            scribe.setStartPage(index + 1)
            scribe.setEndPage(index + 1)
            scribe.getText(document)
            lines += scribe.lines
        }
        return Triple(PdfDoc.toDoc(lines), pages.size, lines)
    }

    /**
     * PDF → CSV / Excel 共用的那一次读：逐页量词 → 按对齐找列 → 把跨页的同一张表接起来。
     *
     * 判断全在 `:core`（`PdfTableDetect` 与 `PdfTableSheets`，那边能脱机单测，也能在桌面上对着
     * pdfplumber 对答案）；这里只管量与落盘，两个产物因此不会一个说 4 行一个说 5 行。
     */
    private fun sheetsOf(document: PDDocument, spec: String): Pair<List<TableSheet>, List<String>> {
        val total = document.numberOfPages
        val pages = if (spec.isBlank()) (0 until total).toList()
        else PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total)
        val words = ArrayList<PdfWord>()
        pages.forEach { index ->
            val scribe = PdfLineScribe(index)
            scribe.setStartPage(index + 1)
            scribe.setEndPage(index + 1)
            scribe.getText(document)
            words += scribe.words
        }
        val read = PdfTableSheets.from(PdfTableDetect.tablesOf(words))
        return read.sheets to (read.notes + "看了 ${pages.size} 页 · ${words.size} 个词")
    }

    /** 认不出表时怎么说：把核心那一页一页的交代带上，并给一条能走的路，不说空话。 */
    private fun noTableMessage(notes: List<String>): String =
        notes.joinToString(" · ")
            .ifBlank { "纸上的行没在同一个地方断开，看不出一列一列的样子" } +
            "；扫描件（字是图片）没有文字层，要留档走「每页导出图片」，要文字走「PDF 提取文字」"

    fun toCsv(item: WorkItem, operation: Operation.PdfToCsv): List<EngineOutput> {
        val document = loadForReading(item.file)
        try {
            val (sheets, notes) = sheetsOf(document, operation.spec)
            require(sheets.isNotEmpty()) { noTableMessage(notes) }
            return sheets.map { sheet ->
                val text = com.fileforge.core.data.Csv.render(sheet.rows)
                val file = workspace.newStagingFile("csv").apply { writeText(text, Charsets.UTF_8) }
                EngineOutput(
                    OutputNaming.tagged(item.name, if (sheets.size == 1) "" else sheet.name, "csv"),
                    file,
                    (listOf(
                        "${sheet.rows.size} 行 × ${sheet.rows.maxOf { it.size }} 列",
                        "来自第 ${sheet.pages.joinToString("、") { (it + 1).toString() }} 页",
                    ) + notes).filter { it.isNotBlank() }.joinToString(" · "),
                )
            }
        } finally {
            runCatching { document.close() }
        }
    }

    fun toXlsx(item: WorkItem, operation: Operation.PdfToXlsx): EngineOutput {
        val document = loadForReading(item.file)
        try {
            val (sheets, notes) = sheetsOf(document, operation.spec)
            require(sheets.isNotEmpty()) { noTableMessage(notes) }
            val out = com.fileforge.core.office.XlsxWrite.workbook(
                sheets.map { com.fileforge.core.office.SheetToWrite(it.name, it.rows) },
                modifiedAt = item.file.lastModified(),
            )
            val file = workspace.newStagingFile("xlsx").apply { writeBytes(out.bytes) }
            val which = sheets.joinToString("、") { sheet ->
                "${sheet.name}（第 ${sheet.pages.joinToString("+") { (it + 1).toString() }} 页 ${sheet.rows.size} 行）"
            }
            return EngineOutput(
                OutputNaming.tagged(item.name, "Excel", "xlsx"),
                file,
                (listOf("${sheets.size} 张表 · ${sheets.sumOf { it.rows.size }} 行", which) + notes)
                    .filter { it.isNotBlank() }.joinToString(" · "),
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

    /**
     * 加密码 + 权限限制。产物一律 128 位 AES。
     *
     * 所有者密码留空时按打开密码填同一个：PDFBox 遇到空的所有者密码会**自己造一个随机值**，
     * 用户看不见也记不住，以后连自己都解不开（实测过）。
     * 权限位是"给阅读器看的约定"而不是锁 —— 实测勾了不允许复制，抽取器照样抽得出字，
     * 所以界面上按 [com.fileforge.core.pdf.PdfSecurity.ADVISORY_NOTE] 那句话说，不吹成加密锁。
     */
    fun encrypt(item: WorkItem, operation: Operation.EncryptPdf): EngineOutput {
        PdfSecurity.validate(operation.userPassword, operation.ownerPassword, operation.granted)
            ?.let { error(it) }
        val source = loadForReading(item.file)
        try {
            require(!source.isEncrypted) { "这份 PDF 已经带密码了，先去密码再加新的" }
            val permission = AccessPermission().apply {
                setCanPrint(PdfPermission.Print in operation.granted)
                setCanExtractContent(PdfPermission.Copy in operation.granted)
                setCanModify(PdfPermission.Modify in operation.granted)
                setCanModifyAnnotations(PdfPermission.Modify in operation.granted)
                setCanFillInForm(PdfPermission.FillForms in operation.granted)
            }
            val policy = StandardProtectionPolicy(
                PdfSecurity.ownerPasswordFor(operation.ownerPassword, operation.userPassword),
                operation.userPassword,
                permission,
            ).apply {
                encryptionKeyLength = AES_KEY_LENGTH
                isPreferAES = true
            }
            source.protect(policy)
            val output = workspace.newStagingFile("pdf")
            source.save(output)
            if (operation.userPassword.isNotBlank()) {
                // 回读验一遍加密真的生效了：设了打开密码却还能直接打开，是最坏的一种"成功"
                val opensWithoutPassword = runCatching {
                    PDDocument.load(output, scratchSetting()).use { }
                }.isSuccess
                require(!opensWithoutPassword) { "加密没生效：不设密码仍然打得开" }
            }
            val opensWith = if (operation.userPassword.isBlank()) "不用密码就能打开" else "要打开密码"
            return EngineOutput(
                OutputNaming.tagged(item.name, "加密", "pdf"),
                output,
                "$opensWith · ${PdfSecurity.summarize(operation.granted)} · ${source.numberOfPages} 页",
            )
        } finally {
            runCatching { source.close() }
        }
    }

    /** 去掉密码，产出一份明文副本（原文件不动）。 */
    fun decrypt(item: WorkItem, operation: Operation.DecryptPdf): EngineOutput {
        val source = loadWithPassword(item.file, operation.password)
        try {
            require(source.isEncrypted) { "这份 PDF 本来就没有密码，不用去" }
            source.setAllSecurityToBeRemoved(true)
            val output = workspace.newStagingFile("pdf")
            source.save(output)
            // 存完回读一次确认真的解开了。这类产物"看起来正常"和"真的正常"只差一个标志位，
            // 不验就会交出一份还是要密码的文件（encrypt 那边同理）
            val stillLocked = runCatching {
                PDDocument.load(output, scratchSetting()).use { it.isEncrypted }
            }.getOrDefault(true)
            require(!stillLocked) { "解除密码没生效，产物仍然要密码才能打开" }
            return EngineOutput(
                OutputNaming.tagged(item.name, "去密码", "pdf"),
                output,
                "${source.numberOfPages} 页 · 已不带密码保护",
            )
        } finally {
            runCatching { source.close() }
        }
    }

    /** 带密码读源文件。密码错、和没给密码，是两句不同的话。 */
    private fun loadWithPassword(file: File, password: String): PDDocument = runCatching {
        PDDocument.load(file, password, scratchSetting())
    }.getOrElse { error ->
        val wrongPassword = generateSequence<Throwable>(error) { it.cause }.any { it is InvalidPasswordException }
        throw IllegalArgumentException(
            when {
                !wrongPassword -> "这份 PDF 读不了，可能已损坏：${error.message ?: error.javaClass.simpleName}"
                password.isBlank() -> "这份 PDF 有密码保护，把打开密码填进来"
                else -> "密码不对。注意要填的是打开密码（用户密码），所有者密码不能用来打开"
            },
        )
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
            if (needsPassword) "这份 PDF 有密码保护：先用「PDF 去密码」填对打开密码，再来做这一步"
            else "这份 PDF 读不了，可能已损坏：${error.message ?: error.javaClass.simpleName}",
        )
    }

    /** 大文档别压 Java 堆：PDFBox 的中间对象全部落到应用缓存目录。 */
    private fun scratchSetting(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(workspace.pdfScratch)

    private companion object {
        const val AES_KEY_LENGTH = 128
        const val MAX_IMAGE_PIXELS = 12_000_000L
        val PROCESSABLE = setOf("DeviceRGB", "DeviceGray")

        /** 随应用打包的中文字体（SIL OFL 1.1，子集：ASCII + Latin-1 补充 + 常用中文标点 + GB2312 全量，
         *  覆盖由 tools/verify_pdf_font.py 承诺、tools/make_pdf_font_subset.py 照着切）。 */
        const val BUNDLED_FONT = "fonts/lxgw-wenkai-subset.ttf"

        /** 页码用的深色：0.15 灰比纯黑柔和，扫成灰底也还看得清。 */
        const val DARK = 0.15f

        /** 安卓系统字体目录里的候选，单个字面排在集合前面（集合要按名字挑，麻烦且容易挑空）。 */
        val SYSTEM_FONTS = listOf(
            "/system/fonts/DroidSansFallbackFull.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansCJKsc-Regular.otf",
            "/system/fonts/SourceHanSansCN-Regular.otf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSerifCJK-Regular.ttc",
        )

        /** .ttc 里认中文字面的常用名字，按顺序试。 */
        val TTC_FONT_NAMES = listOf(
            "NotoSansCJKsc-Regular",
            "NotoSansCJK-Regular",
            "SourceHanSansCN-Regular",
            "DroidSansFallback",
            "NotoSerifCJKsc-Regular",
        )
    }
}
