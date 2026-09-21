package com.fileforge.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.ImageFormat
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.OperationKind
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.ops.VideoFormat
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem

/** 选操作 + 填参数，一次敲定。可用操作按选中文件的真实类型筛过。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationSheet(items: List<WorkItem>, onDismiss: () -> Unit, onStart: (Operation) -> Unit) {
    val available = remember(items) { OperationKind.applicable(items.map { it.kind }.toSet()) }
    var chosen by remember(items) { mutableStateOf<OperationKind?>(null) }
    val kinds = items.map { it.kind }.toSet()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (chosen != null) {
                    IconButton(onClick = { chosen = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回操作列表")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        chosen?.label ?: "对 ${items.size} 个文件做什么",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        chosen?.hint ?: kinds.joinToString("、") { it.label() } + " · 结果会留在工作台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
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
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        available.forEach { option ->
                            TextButton(
                                onClick = { chosen = option },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
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
                Column(
                    Modifier.padding(horizontal = 20.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val draft = remember(kind, items) { Parameters(kind, items) }
                    draft.Content()
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { draft.build()?.let(onStart) },
                        enabled = draft.error == null,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(draft.error ?: "开始${kind.label}")
                    }
                }
            }
        }
    }
}

/** 一个操作的全部可调参数，界面状态和构建 Operation 都在这。 */
class Parameters(val kind: OperationKind, val items: List<WorkItem>) {
    var imageFormat by mutableStateOf(ImageFormat.Jpeg)
    var quality by mutableStateOf(82f)
    var maxEdge by mutableStateOf(0f)
    var targetSizeText by mutableStateOf("10")
    var pageSpec by mutableStateOf("")
    var paper by mutableStateOf(PdfPaper.A4)
    var margin by mutableStateOf(0f)
    var pdfScale by mutableStateOf(2f)
    var gifFps by mutableStateOf(10f)
    var gifColors by mutableStateOf(128f)
    var videoFormat by mutableStateOf(VideoFormat.Mp4)
    var bitrate by mutableStateOf(2500f)
    var startSecond by mutableStateOf(0f)
    var durationSecond by mutableStateOf(0f)

    var error: String? by mutableStateOf(null)

    @Composable
    fun Content() {
        when (kind) {
            OperationKind.ConvertImage -> {
                Segmented("输出格式", ImageFormat.entries.map { it.label }, imageFormat.label) {
                    imageFormat = ImageFormat.entries[it]
                }
                SliderRow("质量", quality, 30f..100f, "%.0f".format(quality)) { quality = it }
                SliderRow("最长边不超过", maxEdge, 0f..4096f, edgeLabel(maxEdge), step = 64f) { maxEdge = it }
            }
            OperationKind.CompressImage -> {
                Segmented("输出格式", ImageFormat.entries.map { it.label }, imageFormat.label) {
                    imageFormat = ImageFormat.entries[it]
                }
                SizeField("目标体积 MB（留空则只按质量压）", targetSizeText) { targetSizeText = it }
                SliderRow("质量", quality, 30f..100f, "%.0f".format(quality)) { quality = it }
                SliderRow("最长边不超过", maxEdge, 0f..4096f, edgeLabel(maxEdge), step = 64f) { maxEdge = it }
                Summary("原图合计 ${sizeOf(items)}，压完会逐个给出实际大小")
            }
            OperationKind.ImagesToPdf -> {
                Segmented("纸张", PdfPaper.entries.map { it.label }, paper.label) { paper = PdfPaper.entries[it] }
                SliderRow("页边距", margin, 0f..60f, if (margin == 0f) "无边距" else "%.0f dp".format(margin)) { margin = it }
                Summary("${items.size} 张图，按选择顺序一页一张")
            }
            OperationKind.SplitPdfBySize -> {
                SizeField("每份目标体积 MB", targetSizeText) { targetSizeText = it }
                Summary(
                    items.firstOrNull()?.let { "共 ${it.sizeLabel}：按目标大小顺次切分，尾部不足一份的剩余页单独成文件，" +
                        "文件名按 part01、part02… 编号" } ?: "",
                )
            }
            OperationKind.MergePdfs -> {
                items.forEachIndexed { index, item -> Summary("${index + 1}. ${item.name} · ${item.sizeLabel}") }
                Summary("按上面的顺序拼成一份，页码连续排。")
            }
            OperationKind.ExtractPdfPages -> {
                OutlinedTextField(
                    value = pageSpec,
                    onValueChange = { pageSpec = it; error = null },
                    label = { Text("页码范围，例：100-150,200-250") },
                    singleLine = true,
                    supportingText = { Text("逗号/空格分隔多段；写 150-100 即倒序取页") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                items.forEach { Summary("${it.name} · ${it.sizeLabel}") }
            }
            OperationKind.PdfToImages -> {
                Segmented("图片格式", ImageFormat.entries.map { it.label }, imageFormat.label) {
                    imageFormat = ImageFormat.entries[it]
                }
                SliderRow("清晰度", pdfScale, 1f..4f, "%.0f 倍".format(pdfScale)) { pdfScale = it }
                if (imageFormat != ImageFormat.Png) SliderRow("质量", quality, 40f..100f, "%.0f".format(quality)) { quality = it }
            }
            OperationKind.CompressGif -> {
                SliderRow("最长边不超过", maxEdge, 0f..1200f, edgeLabel(maxEdge), step = 40f) { maxEdge = it }
                SliderRow("帧率上限", gifFps, 0f..50f, if (gifFps == 0f) "不限" else "%.0f fps".format(gifFps)) { gifFps = it }
                SliderRow("颜色数", gifColors, 8f..256f, "%.0f 色".format(gifColors), step = 8f) { gifColors = it }
                Summary("三项都能单独用；边长和帧数降下来是体积下降的大头")
            }
            OperationKind.VideoToGif -> {
                SliderRow("帧率", gifFps, 2f..25f, "%.0f fps".format(gifFps)) { gifFps = it }
                SliderRow("最长边不超过", maxEdge, 120f..720f, "%.0f px".format(maxEdge), step = 40f) { maxEdge = it }
                NumberRow("从第几秒开始", startSecond) { startSecond = it }
                NumberRow("取多少秒（0=全部）", durationSecond) { durationSecond = it }
                Summary("帧数或尺寸超出内存上限时会自动往下收，结果里会写明")
            }
            OperationKind.CompressVideo -> {
                Segmented("封装", VideoFormat.entries.map { it.label }, videoFormat.label) {
                    videoFormat = VideoFormat.entries[it]
                }
                SliderRow("视频码率", bitrate, 400f..12000f, "%.0f kbps".format(bitrate), step = 100f) { bitrate = it }
                Summary("硬解硬编，全程不进 Java 堆；WebM 只出画面不带音轨")
            }
        }
    }

    fun build(): Operation? {
        val targetBytes = when (kind) {
            OperationKind.SplitPdfBySize, OperationKind.CompressImage -> SizeInput.parse(targetSizeText)
            else -> null
        }
        if ((kind == OperationKind.SplitPdfBySize || (kind == OperationKind.CompressImage && targetSizeText.isNotBlank())) &&
            targetBytes == null
        ) {
            error = "目标体积要写成 10 或 1.5MB 这样"
            return null
        }
        if (kind == OperationKind.ExtractPdfPages && pageSpec.isBlank()) {
            error = "先写要取哪些页"
            return null
        }
        return when (kind) {
            OperationKind.ConvertImage -> Operation.ConvertImage(imageFormat, quality.toInt(), maxEdge.toInt())
            OperationKind.CompressImage -> Operation.CompressImage(
                imageFormat, quality.toInt(), maxEdge.toInt(), targetBytes,
            )
            OperationKind.ImagesToPdf -> Operation.ImagesToPdf(paper, margin.toInt())
            OperationKind.SplitPdfBySize -> Operation.SplitPdfBySize(targetBytes ?: SizeInput.MEGA * 10)
            OperationKind.ExtractPdfPages -> Operation.ExtractPdfPages(pageSpec)
            OperationKind.MergePdfs -> Operation.MergePdfs
            OperationKind.PdfToImages -> Operation.PdfToImages(imageFormat, pdfScale, quality.toInt())
            OperationKind.CompressGif -> Operation.CompressGif(maxEdge.toInt(), gifFps.toInt(), gifColors.toInt())
            OperationKind.VideoToGif -> Operation.VideoToGif(gifFps.toInt(), maxEdge.toInt(), startSecond.toDouble(), durationSecond.toDouble())
            OperationKind.CompressVideo -> Operation.CompressVideo(videoFormat, bitrate.toInt(), 0, 96)
        }
    }
}

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

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    step: Float = 0f,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = if (step > 0) ((range.endInclusive - range.start) / step).toInt() - 1 else 0)
    }
}

@Composable
private fun Segmented(label: String, options: List<String>, selected: String, onSelect: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                if (option == selected) {
                    androidx.compose.material3.AssistChip(onClick = {}, label = { Text(option) })
                } else {
                    FilledTonalButton(onClick = { onSelect(index) }) { Text(option) }
                }
            }
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Float, onChange: (Float) -> Unit) {
    OutlinedTextField(
        value = if (value == 0f) "" else "%.0f".format(value),
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SizeField(label: String, value: String, onChange: (String) -> Unit) {
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

