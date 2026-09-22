package com.fileforge.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.ImageFormat
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.OperationKind
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.ops.VideoFormat
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import kotlin.math.roundToInt

/** 选操作 + 填参数，一次敲定。可用操作按选中文件的真实类型筛过。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationSheet(items: List<WorkItem>, onDismiss: () -> Unit, onStart: (Operation) -> Unit) {
    val available = remember(items) { OperationKind.applicable(items.map { it.kind }.toSet()) }
    var chosen by remember(items) { mutableStateOf<OperationKind?>(null) }
    val kinds = items.map { it.kind }.toSet()
    // 参数面板比操作列表高，不跳过半展开的话小屏上"开始"会藏在折叠线下
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (chosen != null) {
                    IconButton(onClick = { chosen = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回操作列表")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(chosen?.label ?: "对 ${items.size} 个文件做什么", style = MaterialTheme.typography.titleLarge)
                    Text(
                        chosen?.hint ?: kinds.joinToString("、") { it.label() } + " · 结果会留在工作台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()

            val kind = chosen
            if (kind == null) {
                if (available.isEmpty()) {
                    Text(
                        "这几类文件混在一起没有共同可做的操作，先只选同一类试试。",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        available.forEach { option ->
                            TextButton(
                                onClick = { chosen = option },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        option.hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val draft = remember(kind, items) { Parameters(kind, items) }
                Column(
                    Modifier.padding(horizontal = 20.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    draft.Content()
                }
                Spacer(Modifier.height(12.dp))
                val problem = draft.validation()
                Button(
                    onClick = { draft.build()?.let(onStart) },
                    enabled = problem == null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
                ) {
                    Text(problem ?: "开始${kind.label}")
                }
            }
        }
    }
}

/**
 * 一个操作的全部可调参数。
 *
 * 校验是 [validation] 这个纯函数，不是"点一下才写进去"的可变状态——否则按钮会卡在
 * 上一次的错误里灰着不动。数字输入一律先存原始字符串，避免小数点被回显吃掉。
 */
class Parameters(val kind: OperationKind, val items: List<WorkItem>) {
    var imageFormat by mutableStateOf(ImageFormat.Jpeg)
    var quality by mutableStateOf(82f)
    var maxEdge by mutableStateOf(if (kind == OperationKind.VideoToGif) 480f else 0f)
    var targetSizeText by mutableStateOf("10")
    var pageSpec by mutableStateOf("")
    var paper by mutableStateOf(PdfPaper.A4)
    var margin by mutableStateOf(0f)
    var pdfScale by mutableStateOf(2f)
    var gifFps by mutableStateOf(if (kind == OperationKind.VideoToGif) 12f else 10f)
    var gifColors by mutableStateOf(128f)
    var videoFormat by mutableStateOf(VideoFormat.Mp4)
    var videoByTarget by mutableStateOf(false)
    var bitrate by mutableStateOf(2500f)
    var startSecondText by mutableStateOf("")
    var durationSecondText by mutableStateOf("")
    var pdfLevel by mutableStateOf(1f)
    var pdfByTarget by mutableStateOf(false)
    var parts by mutableStateOf(2f)
    var rotateIndex by mutableStateOf(0)

    @Composable
    fun Content() {
        when (kind) {
            OperationKind.ConvertImage -> {
                Segmented("输出格式", ImageFormat.entries.map { it.label }, imageFormat.ordinal) {
                    imageFormat = ImageFormat.entries[it]
                }
                IntSlider("质量", quality, 30f..100f, { "%.0f".format(it) }) { quality = it }
                IntSlider("最长边不超过", maxEdge, 0f..4096f, ::edgeLabel, step = 64f) { maxEdge = it }
            }
            OperationKind.CompressImage -> {
                Segmented("输出格式", ImageFormat.entries.map { it.label }, imageFormat.ordinal) {
                    imageFormat = ImageFormat.entries[it]
                }
                SizeField("目标体积 MB（留空则只按质量压）", targetSizeText) { targetSizeText = it }
                IntSlider("质量", quality, 30f..100f, { "%.0f".format(it) }) { quality = it }
                IntSlider("最长边不超过", maxEdge, 0f..4096f, ::edgeLabel, step = 64f) { maxEdge = it }
                Summary("原图合计 ${sizeOf(items)}，压完会逐个给出实际大小")
            }
            OperationKind.ImagesToPdf -> {
                Segmented("纸张", PdfPaper.entries.map { it.label }, paper.ordinal) { paper = PdfPaper.entries[it] }
                IntSlider("页边距", margin, 0f..60f, { if (it == 0f) "无边距" else "%.0f dp".format(it) }) { margin = it }
                Summary("${items.size} 张图，按选择顺序一页一张")
            }
            OperationKind.CompressPdf -> {
                Segmented("档位", listOf("清晰", "标准", "紧凑"), pdfLevel.roundToInt()) { pdfLevel = it.toFloat() }
                Segmented("压法", listOf("只按档位", "目标体积"), if (pdfByTarget) 1 else 0) { pdfByTarget = it == 1 }
                if (pdfByTarget) SizeField("目标体积 MB", targetSizeText) { targetSizeText = it }
                Summary("只重编内嵌图片（降采样 + JPEG），文字和矢量原样保留；按目标体积时会一档一档往下试。")
                Summary("带透明通道的图、1 位掩膜和非 RGB 的图不动，结果里会说明跳了几张。合计 ${sizeOf(items)}")
            }
            OperationKind.SplitPdfBySize -> {
                SizeField("每份目标体积 MB", targetSizeText) { targetSizeText = it }
                Summary(
                    items.firstOrNull()?.let {
                        "共 ${it.sizeLabel}：按目标大小顺次切分，尾部不足一份的剩余页单独成文件，文件名按 part01、part02… 编号"
                    } ?: "",
                )
            }
            OperationKind.SplitPdfIntoParts -> {
                IntSlider("拆成几份", parts, 2f..12f, { "%.0f 份".format(it) }) { parts = it }
                items.forEach { Summary("${it.name} · ${it.sizeLabel}") }
                Summary("按页数均分，前面的份多一页；只管页数不看体积。")
            }
            OperationKind.MergePdfs -> {
                items.forEachIndexed { index, item -> Summary("${index + 1}. ${item.name} · ${item.sizeLabel}") }
                Summary("按上面的顺序拼成一份，页码连续排。")
            }
            OperationKind.ExtractPdfPages -> {
                PageSpecField("页码范围，例：100-150,200-250", "逗号/空格分隔多段；写 150-100 即倒序取页")
                items.forEach { Summary("${it.name} · ${it.sizeLabel}") }
            }
            OperationKind.RemovePdfPages -> {
                PageSpecField("要删掉的页，例：1-3,7", "逗号/空格分隔多段；其余页原样保留，页码连着排")
                items.forEach { Summary("${it.name} · ${it.sizeLabel}") }
            }
            OperationKind.RotatePdfPages -> {
                Segmented("转多少", ROTATE_OPTIONS.map { it.first }, rotateIndex) { rotateIndex = it }
                PageSpecField("留空=所有页；也可只转几页，例：1-3,7", "没点到的页原样带过去，转的方向是在原角度上加")
            }
            OperationKind.PdfToText -> {
                PageSpecField("留空=全文；也可只要几页，例：1-3,7", "只抽文字层；扫描件没有文字层，得走「每页导出图片」")
                items.forEach { Summary("${it.name} · ${it.sizeLabel}") }
            }
            OperationKind.PdfToImages -> {
                Segmented("图片格式", ImageFormat.entries.map { it.label }, imageFormat.ordinal) {
                    imageFormat = ImageFormat.entries[it]
                }
                IntSlider("清晰度", pdfScale, 1f..4f, { "%.0f 倍".format(it) }) { pdfScale = it }
                if (imageFormat != ImageFormat.Png) {
                    IntSlider("质量", quality, 40f..100f, { "%.0f".format(it) }) { quality = it }
                }
            }
            OperationKind.CompressGif -> {
                IntSlider("最长边不超过", maxEdge, 0f..1200f, ::edgeLabel, step = 40f) { maxEdge = it }
                IntSlider("帧率上限", gifFps, 0f..50f, { if (it == 0f) "不限" else "%.0f fps".format(it) }) { gifFps = it }
                IntSlider("颜色数", gifColors, 8f..256f, { "%.0f 色".format(it) }, step = 8f) { gifColors = it }
                Summary("三项都能单独用；边长和帧数降下来是体积下降的大头")
            }
            OperationKind.VideoToGif -> {
                IntSlider("帧率", gifFps, 2f..25f, { "%.0f fps".format(it) }) { gifFps = it }
                IntSlider("最长边不超过", maxEdge, 120f..720f, ::edgeLabel, step = 40f) { maxEdge = it }
                NumberField("从第几秒开始（空=0）", startSecondText) { startSecondText = it }
                NumberField("取多少秒（空=全部）", durationSecondText) { durationSecondText = it }
                Summary("帧数或尺寸超出内存上限时会自动往下收，结果里会写明")
            }
            OperationKind.CompressVideo -> {
                Segmented("封装", VideoFormat.entries.map { it.label }, videoFormat.ordinal) {
                    videoFormat = VideoFormat.entries[it]
                }
                Segmented("压法", listOf("固定码率", "目标体积"), if (videoByTarget) 1 else 0) { videoByTarget = it == 1 }
                if (videoByTarget) {
                    SizeField("目标体积 MB", targetSizeText) { targetSizeText = it }
                    Summary("按视频时长反推码率，音轨原样搬运所以会先扣掉它的占用。")
                } else {
                    IntSlider("视频码率", bitrate, 400f..12000f, { "%.0f kbps".format(it) }, step = 100f) { bitrate = it }
                }
                Summary("硬解硬编，全程不进 Java 堆；WebM 只出画面不带音轨；不改分辨率")
            }
        }
    }

    @Composable
    private fun PageSpecField(label: String, support: String) {
        OutlinedTextField(
            value = pageSpec,
            onValueChange = { pageSpec = it },
            label = { Text(label) },
            singleLine = true,
            supportingText = { Text(support) },
            isError = pageSpec.isNotBlank() && validation() != null,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** 参数当前能不能提交；返回 null 表示没问题。 */
    fun validation(): String? {
        val needsTarget = kind == OperationKind.SplitPdfBySize ||
            (kind == OperationKind.CompressImage && targetSizeText.isNotBlank()) ||
            (kind == OperationKind.CompressVideo && videoByTarget) ||
            (kind == OperationKind.CompressPdf && pdfByTarget)
        if (needsTarget && SizeInput.parse(targetSizeText) == null) return "目标体积写成 10 或 1.5MB 这样"
        if (kind == OperationKind.ExtractPdfPages && pageSpec.isBlank()) return "先写要取哪些页"
        if (kind == OperationKind.RemovePdfPages && pageSpec.isBlank()) return "先写要删哪些页"
        if (kind == OperationKind.MergePdfs && items.size < 2) return "合并 PDF 至少选两个文件"
        if (startSecondText.isNotBlank() && startSecondText.toFloatOrNull() == null) return "开始秒数不是数字"
        if (durationSecondText.isNotBlank() && durationSecondText.toFloatOrNull() == null) return "取多少秒不是数字"
        return null
    }

    fun build(): Operation? {
        if (validation() != null) return null
        val targetBytes = SizeInput.parse(targetSizeText)
        return when (kind) {
            OperationKind.ConvertImage -> Operation.ConvertImage(imageFormat, quality.roundToInt(), maxEdge.roundToInt())
            OperationKind.CompressImage -> Operation.CompressImage(
                imageFormat, quality.roundToInt(), maxEdge.roundToInt(),
                if (targetSizeText.isBlank()) null else targetBytes,
            )
            OperationKind.ImagesToPdf -> Operation.ImagesToPdf(paper, margin.roundToInt())
            OperationKind.SplitPdfBySize -> Operation.SplitPdfBySize(targetBytes ?: SizeInput.MEGA * 10)
            OperationKind.SplitPdfIntoParts -> Operation.SplitPdfIntoParts(parts.roundToInt())
            OperationKind.CompressPdf -> Operation.CompressPdf(
                pdfLevel.roundToInt(), if (pdfByTarget) targetBytes else null,
            )
            OperationKind.ExtractPdfPages -> Operation.ExtractPdfPages(pageSpec)
            OperationKind.RemovePdfPages -> Operation.RemovePdfPages(pageSpec)
            OperationKind.RotatePdfPages -> Operation.RotatePdfPages(pageSpec.trim(), ROTATE_OPTIONS[rotateIndex].second)
            OperationKind.PdfToText -> Operation.PdfToText(pageSpec.trim())
            OperationKind.MergePdfs -> Operation.MergePdfs
            OperationKind.PdfToImages -> Operation.PdfToImages(imageFormat, pdfScale.roundToInt().toFloat(), quality.roundToInt())
            OperationKind.CompressGif -> Operation.CompressGif(maxEdge.roundToInt(), gifFps.roundToInt(), gifColors.roundToInt())
            OperationKind.VideoToGif -> Operation.VideoToGif(
                gifFps.roundToInt(), maxEdge.roundToInt(),
                startSecondText.toFloatOrNull()?.toDouble() ?: 0.0,
                durationSecondText.toFloatOrNull()?.toDouble() ?: 0.0,
            )
            OperationKind.CompressVideo -> Operation.CompressVideo(
                videoFormat, bitrate.roundToInt(), 0, 96,
                if (videoByTarget) targetBytes else null,
            )
        }
    }
}

private val ROTATE_OPTIONS = listOf("顺时针 90°" to 90, "180°" to 180, "逆时针 90°" to 270)

private fun edgeLabel(edge: Float): String = if (edge == 0f) "不改" else "%.0f px".format(edge)

private fun sizeOf(items: List<WorkItem>): String = SizeInput.format(items.sumOf { it.size })

private fun FileKind.label(): String = when (this) {
    FileKind.Pdf -> "PDF"
    FileKind.Gif -> "GIF"
    FileKind.WebP -> "WebP"
    FileKind.Heic -> "HEIC"
    FileKind.Avif -> "AVIF"
    FileKind.Mp4 -> "MP4"
    FileKind.WebM -> "WebM"
    FileKind.Mkv -> "MKV"
    FileKind.QuickTime -> "MOV"
    else -> "图片"
}

/** 整数档位滑块：显示值和实际执行值必须一致，否则会出现"显示 13fps 跑 12"。 */
@Composable
private fun IntSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    step: Float = 1f,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = maxOf(0, ((range.endInclusive - range.start) / step).roundToInt() - 1),
        )
    }
}

@Composable
private fun Segmented(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    selected = index == selected,
                    onClick = { onSelect(index) },
                ) {
                    Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { char -> char.isDigit() || char == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SizeField(label: String, value: String, onChange: (String) -> Unit) {
    NumberField(label, value, onChange)
}

@Composable
private fun Summary(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}
