package com.fileforge.converter.engine

import android.content.Context
import com.fileforge.core.model.BatchLineage
import com.fileforge.core.ops.Operation
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 一批操作的结局：成功的产物和逐个失败的说明。 */
class RunResult(val outputs: List<WorkItem>, val failures: List<Pair<String, String>>) {
    val isEmpty: Boolean get() = outputs.isEmpty() && failures.isEmpty()
}

/**
 * 操作入口。引擎全在 IO 线程跑；单个文件失败只记录，不中断整批。
 * 产物先落 staging，再进工作台，所以转换结果可以立刻被再加工。
 */
class OperationRunner(context: Context, private val workspace: Workspace) {

    private val images = ImageEngine()
    private val gifs = GifEngine(workspace, images)
    private val pdf = PdfEngine(context, workspace)
    private val video = VideoEngine(workspace, images)
    private val audio = AudioEngine(workspace)
    private val text = TextEngine(workspace)
    private val archives = ArchiveEngine()
    private val office = OfficeEngine(workspace)

    suspend fun run(
        items: List<WorkItem>,
        operation: Operation,
        onProgress: (percent: Int, label: String) -> Unit,
    ): RunResult = withContext(Dispatchers.IO) {
        val outputs = ArrayList<EngineOutput>()
        val failures = ArrayList<Pair<String, String>>()

        suspend fun collect(label: String, block: suspend () -> List<EngineOutput>) {
            runCatching { block() }
                .onSuccess { outputs += it }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    failures += label to (error.message ?: error.javaClass.simpleName)
                }
        }

        when (operation) {
            is Operation.ConvertImage -> items.forEach { item ->
                collect(item.name) { listOf(images.convert(item, operation, ::staging)) }
            }
            is Operation.CompressImage -> items.forEach { item ->
                collect(item.name) { listOf(images.compress(item, operation, ::staging)) }
            }
            is Operation.ImagesToPdf -> collect("${items.size} 张图片") {
                listOf(pdf.imagesToPdf(items, operation.paper, operation.marginDp))
            }
            is Operation.SplitPdfBySize -> items.forEach { item ->
                collect(item.name) { pdf.splitBySize(item, operation.targetBytes) { percent -> onProgress(percent / 2, item.name) } }
            }
            is Operation.MergePdfs -> collect("${items.size} 份 PDF") { listOf(pdf.merge(items)) }
            is Operation.ExtractPdfPages -> items.forEach { item ->
                collect(item.name) { listOf(pdf.extractPages(item, operation.spec)) }
            }
            is Operation.RemovePdfPages -> items.forEach { item ->
                collect(item.name) { listOf(pdf.removePages(item, operation.spec)) }
            }
            is Operation.RotatePdfPages -> items.forEach { item ->
                collect(item.name) { listOf(pdf.rotatePages(item, operation.spec, operation.degrees)) }
            }
            is Operation.SplitPdfIntoParts -> items.forEach { item ->
                collect(item.name) { pdf.splitIntoParts(item, operation.parts) }
            }
            is Operation.CompressPdf -> items.forEach { item ->
                collect(item.name) {
                    listOf(
                        pdf.compress(item, operation.level, operation.targetBytes) { percent, label ->
                            onProgress(percent, label)
                        },
                    )
                }
            }
            is Operation.PdfToText -> items.forEach { item ->
                collect(item.name) { listOf(pdf.toText(item, operation.spec)) }
            }
            is Operation.PageNumbers -> items.forEach { item ->
                collect(item.name) { listOf(pdf.addPageNumbers(item, operation)) }
            }
            is Operation.PdfWatermark -> items.forEach { item ->
                collect(item.name) { listOf(pdf.watermark(item, operation)) }
            }
            is Operation.PdfToImages -> items.forEach { item ->
                collect(item.name) {
                    pdf.pagesToImages(item, operation.scale) { bitmap ->
                        operation.format.extension to images.encode(bitmap, operation.format, operation.quality)
                    }
                }
            }
            is Operation.EncryptPdf -> items.forEach { item ->
                collect(item.name) { listOf(pdf.encrypt(item, operation)) }
            }
            is Operation.DecryptPdf -> items.forEach { item ->
                collect(item.name) { listOf(pdf.decrypt(item, operation)) }
            }
            is Operation.CompressGif -> items.forEach { item ->
                collect(item.name) { listOf(gifs.compress(item, operation)) }
            }
            is Operation.GifToImages -> items.forEach { item ->
                collect(item.name) { gifs.toImages(item, operation) }
            }
            is Operation.ImagesToGif -> collect("${items.size} 张图片") {
                listOf(gifs.fromImages(items, operation))
            }
            is Operation.VideoToGif -> items.forEach { item ->
                collect(item.name) {
                    listOf(gifs.fromVideo(item, operation) { percent -> onProgress(percent, item.name) })
                }
            }
            is Operation.CompressVideo -> items.forEach { item ->
                collect(item.name) {
                    listOf(video.compress(item, operation) { percent, -> onProgress(percent, item.name) })
                }
            }
            is Operation.VideoToImage -> items.forEach { item ->
                collect(item.name) { listOf(video.still(item, operation)) }
            }
            is Operation.ConvertTextEncoding -> items.forEach { item ->
                collect(item.name) { listOf(text.convertText(item, operation)) }
            }
            is Operation.ConvertSubtitle -> items.forEach { item ->
                collect(item.name) { listOf(text.convertSubtitle(item, operation)) }
            }
            is Operation.AudioConvert -> items.forEach { item ->
                collect(item.name) {
                    listOf(audio.convert(item, operation) { percent -> onProgress(percent, item.name) })
                }
            }
            is Operation.CleanMetadata -> items.forEach { item ->
                collect(item.name) { listOf(images.cleanMetadata(item, ::staging)) }
            }
            is Operation.PackArchive -> collect("${items.size} 份文件") {
                listOf(archives.pack(items, operation, ::staging))
            }
            is Operation.UnpackArchive -> items.forEach { item ->
                collect(item.name) { archives.unpack(item, operation, ::staging) }
            }
            is Operation.FormatJson -> items.forEach { item ->
                collect(item.name) { listOf(text.formatJson(item, operation)) }
            }
            is Operation.JsonToCsv -> items.forEach { item ->
                collect(item.name) { listOf(text.jsonToCsv(item, operation)) }
            }
            is Operation.CsvToJson -> items.forEach { item ->
                collect(item.name) { listOf(text.csvToJson(item, operation)) }
            }
            is Operation.XmlToJson -> items.forEach { item ->
                collect(item.name) { listOf(text.xmlToJson(item, operation)) }
            }
            is Operation.JsonToXml -> items.forEach { item ->
                collect(item.name) { listOf(text.jsonToXml(item, operation)) }
            }
            is Operation.ImageToIco -> items.forEach { item ->
                collect(item.name) { listOf(images.toIco(listOf(item), operation, ::staging)) }
            }
            is Operation.IcoToImages -> items.forEach { item ->
                collect(item.name) { images.icoToImages(item, ::staging) }
            }
            is Operation.TextToPdf -> items.forEach { item ->
                collect(item.name) { listOf(pdf.textToPdf(item, operation)) }
            }
            is Operation.OfficeToText -> items.forEach { item ->
                collect(item.name) { listOf(office.toText(item)) }
            }
            is Operation.XlsxToCsv -> items.forEach { item ->
                collect(item.name) { office.toCsv(item, operation.delimiter, operation.ending) }
            }
            is Operation.MdToHtml -> items.forEach { item ->
                collect(item.name) { listOf(text.markdownToHtml(item)) }
            }
            is Operation.MdToText -> items.forEach { item ->
                collect(item.name) { listOf(text.markdownToText(item)) }
            }
            is Operation.HtmlToText -> items.forEach { item ->
                collect(item.name) { listOf(text.htmlToText(item)) }
            }
            is Operation.HtmlToMarkdown -> items.forEach { item ->
                collect(item.name) { listOf(text.htmlToMarkdown(item)) }
            }
        }

        coroutineContext.ensureActive()
        // 来源是同一批的，产物继续留在那批里；跨批就另起一批，别把两批的历史搅浑
        val group = BatchLineage.inherit(items.map { it.groupId }) ?: workspace.startBatch(items.first().name)
        RunResult(
            outputs = outputs.map { output -> workspace.adopt(output.file, output.name, output.note ?: operation.label, group) },
            failures = failures,
        )
    }

    private fun staging(extension: String): java.io.File = workspace.newStagingFile(extension)
}
