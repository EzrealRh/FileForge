package com.fileforge.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.core.audio.AudioTarget
import com.fileforge.core.data.Delimiter
import com.fileforge.core.data.Ico
import com.fileforge.core.data.Xml
import com.fileforge.core.meta.ImageMeta
import com.fileforge.core.meta.MetaReport
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.ImageFormat
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.OperationKind
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.ops.VideoFormat
import com.fileforge.core.text.LineEnding
import com.fileforge.core.text.LineEndings
import com.fileforge.core.text.SubtitleFormat
import com.fileforge.core.text.TextCodecs
import com.fileforge.core.text.TextEncoding
import com.fileforge.core.pdf.PdfPermission
import com.fileforge.core.pdf.PdfSecurity
import com.fileforge.core.pdf.StampSpot
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
    var imageFormat by mutableStateOf(if (kind == OperationKind.GifToImages) ImageFormat.Png else ImageFormat.Jpeg)
    var quality by mutableStateOf(if (kind == OperationKind.GifToImages || kind == OperationKind.VideoToImage) 92f else 82f)
    var maxEdge by mutableStateOf(if (kind == OperationKind.VideoToGif) 480f else 0f)
    var targetSizeText by mutableStateOf("10")
    var pageSpec by mutableStateOf("")
    var paper by mutableStateOf(PdfPaper.A4)
    var margin by mutableStateOf(0f)
    var pdfScale by mutableStateOf(2f)
    var gifFps by mutableStateOf(if (kind == OperationKind.VideoToGif) 12f else 10f)
    var gifColors by mutableStateOf(128f)
    var gifFrameDelay by mutableStateOf(1000f)
    var firstFrameOnly by mutableStateOf(false)
    var videoFormat by mutableStateOf(VideoFormat.Mp4)
    var videoByTarget by mutableStateOf(false)
    var audioTarget by mutableStateOf(AudioTarget.M4a)
    var audioByTarget by mutableStateOf(false)
    var pdfUserPw by mutableStateOf("")
    var pdfOwnerPw by mutableStateOf("")
    var pdfOpenPw by mutableStateOf("")

    /** 勾选项 = 还允许的权限。默认只留"允许打印"，其余三项关着。 */
    var pdfAllowed by mutableStateOf(setOf(PdfPermission.Print))

    /** -1 表示"按内容自动认"；其余是 TextEncoding.entries 的下标。 */
    var textSource by mutableStateOf(-1)
    var textTarget by mutableStateOf(TextEncoding.Utf8)
    var textBom by mutableStateOf(false)
    var textEnding by mutableStateOf(LineEnding.Lf)
    var subtitleTarget by mutableStateOf(SubtitleFormat.Srt)
    var subtitleSource by mutableStateOf(-1)

    var jsonPretty by mutableStateOf(true)
    var jsonIndent by mutableStateOf(2f)
    var jsonSort by mutableStateOf(false)
    var jsonAscii by mutableStateOf(false)
    var csvDelimiter by mutableStateOf(Delimiter.Comma)
    var csvQuoteAll by mutableStateOf(false)
    var csvEnding by mutableStateOf(LineEnding.Lf)
    var csvHeader by mutableStateOf(true)
    var csvInfer by mutableStateOf(false)
    var xmlRoot by mutableStateOf("")
    var icoSizesText by mutableStateOf("16,32,48,256")
    var textSize by mutableStateOf(11f)
    var textLeading by mutableStateOf(1.4f)
    var textMargin by mutableStateOf(56f)
    var textIndent by mutableStateOf(true)
    var textNumber by mutableStateOf(true)

    private val encodingOptions get() = listOf("自动检测") + TextEncoding.entries.map { it.label }

    private fun encodingAt(index: Int): TextEncoding? = TextEncoding.entries.getOrNull(index)

    var bitrate by mutableStateOf(2500f)
    var startSecondText by mutableStateOf("")
    var durationSecondText by mutableStateOf("")
    var pdfLevel by mutableStateOf(1f)
    var pdfByTarget by mutableStateOf(false)
    var parts by mutableStateOf(2f)
    var rotateIndex by mutableStateOf(0)
    var numberStyle by mutableStateOf(0)
    var numberSpot by mutableStateOf(0)
    var firstNumber by mutableStateOf(1f)
    var numberSize by mutableStateOf(11f)
    var watermarkText by mutableStateOf("")
    var watermarkColumns by mutableStateOf(1f)
    var watermarkRows by mutableStateOf(1f)
    var watermarkOpacity by mutableStateOf(18f)
    var watermarkTilt by mutableStateOf(45f)
    var watermarkGray by mutableStateOf(45f)

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
                Summary("按页数均分，前面的份多一页；只管页数不看体积。份数超不过页数，超了就按页数出。")
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
            OperationKind.GifToImages -> {
                Segmented("导出哪些帧", listOf("全部帧", "只要首帧"), if (firstFrameOnly) 1 else 0) { firstFrameOnly = it == 1 }
                Segmented("图片格式", ImageFormat.entries.map { it.label }, imageFormat.ordinal) {
                    imageFormat = ImageFormat.entries[it]
                }
                if (imageFormat != ImageFormat.Png) {
                    IntSlider("质量", quality, 40f..100f, { "%.0f".format(it) }) { quality = it }
                }
                Summary("PNG 会保住 GIF 的透明边；逐帧导出时一张一帧，帧多就出得多")
            }
            OperationKind.ImagesToGif -> {
                IntSlider("每帧停留", gifFrameDelay, 100f..5000f, { "%.0f ms".format(it) }, step = 100f) { gifFrameDelay = it }
                IntSlider("最长边不超过", maxEdge, 0f..1200f, ::edgeLabel, step = 40f) { maxEdge = it }
                Summary("${items.size} 张按选择顺序一张一帧；尺寸统一到最大那张，其余等比缩放居中，空的地方填白")
            }
            OperationKind.VideoToImage -> {
                Segmented("图片格式", ImageFormat.entries.map { it.label }, imageFormat.ordinal) {
                    imageFormat = ImageFormat.entries[it]
                }
                NumberField("第几秒（空=第一帧）", startSecondText) { startSecondText = it }
                if (imageFormat != ImageFormat.Png) {
                    IntSlider("质量", quality, 40f..100f, { "%.0f".format(it) }) { quality = it }
                }
                IntSlider("最长边不超过", maxEdge, 0f..1920f, ::edgeLabel, step = 64f) { maxEdge = it }
                Summary("取离那一秒最近的画面，横拍竖存会自动转正；抽的是真帧，不是缩略图")
            }
            OperationKind.AddPageNumbers -> {
                Segmented("样式", listOf("1", "第 1 页", "1 / 总页数"), numberStyle) { numberStyle = it }
                Segmented("位置", SPOTS.map { it.label }, numberSpot) { numberSpot = it }
                PageSpecField("留空=所有页；也可只给几页加，例：3-20,25", "页码数字始终按物理页算，所以跳页也不会串号")
                IntSlider("起始页码", firstNumber, 1f..99f, { "%.0f 起".format(it) }) { firstNumber = it }
                IntSlider("字号", numberSize, 7f..28f, { "%.0f pt".format(it) }) { numberSize = it }
                Summary("只在页面末尾补一行字，原有内容和排版不动；横拍竖存那种带旋转的页也按你看到的方向落位")
            }
            OperationKind.PdfWatermark -> {
                OutlinedTextField(
                    value = watermarkText,
                    onValueChange = { watermarkText = it },
                    label = { Text("水印文字") },
                    supportingText = { Text("中文会挑系统字体，只把用得到的字形嵌进文件（实测四个汉字只多 3KB）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IntSlider("每行几块", watermarkColumns, 1f..6f, { "%.0f 列".format(it) }) { watermarkColumns = it }
                IntSlider("竖几块", watermarkRows, 1f..6f, { "%.0f 行".format(it) }) { watermarkRows = it }
                IntSlider("不透明度", watermarkOpacity, 3f..100f, { "%.0f%%".format(it) }) { watermarkOpacity = it }
                IntSlider("深浅", watermarkGray, 0f..90f, { if (it < 34) "深" else if (it < 67) "中" else "浅" }) { watermarkGray = it }
                IntSlider("倾斜", watermarkTilt, -90f..90f, { "%.0f°".format(it) }, step = 5f) { watermarkTilt = it }
                IntSlider("颜色深浅", watermarkGray, 0f..95f, { "%.0f".format(it) }, step = 5f) { watermarkGray = it }
                IntSlider("倾斜角度", watermarkTilt, 0f..90f, { "%.0f°".format(it) }, step = 5f) { watermarkTilt = it }
                PageSpecField("留空=整份都盖；也可只盖几页，例：1,5-8", "没点到的页原样带过去")
                Summary("1x1 就是页面正中一块；行列调大就平铺，字会自动按格子宽度缩放")
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
            OperationKind.ConvertAudio, OperationKind.ExtractAudio -> {
                Segmented("目标格式", AudioTarget.entries.map { it.label }, audioTarget.ordinal) {
                    audioTarget = AudioTarget.entries[it]
                }
                Segmented("取法", listOf("跟随源码率", "按目标体积"), if (audioByTarget) 1 else 0) {
                    audioByTarget = it == 1
                }
                if (audioByTarget) {
                    SizeField("目标体积 MB", targetSizeText) { targetSizeText = it }
                    Summary("按时长反推码率，落在 64~320 kbps 之间（系统 AAC 编码器就吃这个范围）。")
                }
                if (kind == OperationKind.ExtractAudio) {
                    Summary("源音轨本来就是 AAC 时直接搬出来，不重编、无损；其它编码会解码重编。")
                } else {
                    Summary("WAV 是解码出来的无损裸数据，成品会比 M4A 大一大截。")
                }
                Summary("系统没有 MP3 编码器，所以转不成 mp3 —— 只给 M4A 和 WAV 两个目标。")
            }
            OperationKind.ConvertTextEncoding -> {
                PickerRow("源编码", encodingOptions, textSource + 1) { textSource = it - 1 }
                PickerRow("目标编码", TextEncoding.entries.map { it.label }, textTarget.ordinal) {
                    textTarget = TextEncoding.entries[it]
                }
                Segmented("BOM 头", listOf("不加", "加"), if (textBom) 1 else 0) { textBom = it == 1 }
                Segmented("换行风格", LineEnding.entries.map { it.label }, LineEnding.entries.indexOf(textEnding)) {
                    textEnding = LineEnding.entries[it]
                }
                Summary("源编码选「自动检测」时只在一种编码能完全读通的情况下才转；读不通会直接报错让你指定，不会硬转出一份乱码。")
                Summary("UTF-8 什么都装得下；目标装不下的字会算出来告诉你，不会悄悄换成问号。")

            }
            OperationKind.ConvertSubtitle -> {
                PickerRow("源编码", encodingOptions, subtitleSource + 1) { subtitleSource = it - 1 }
                PickerRow("目标格式", SubtitleFormat.entries.map { it.label }, subtitleTarget.ordinal) {
                    subtitleTarget = SubtitleFormat.entries[it]
                }
                Summary("源格式按扩展名认，认不出会按内容试；输出一律写 UTF-8（播放器对非 UTF-8 字幕普遍直接显示乱码）。")
                Summary(subtitleLimitsNote(subtitleTarget))
            }
            OperationKind.EncryptPdf -> {
                OutlinedTextField(
                    value = pdfUserPw,
                    onValueChange = { pdfUserPw = it },
                    label = { Text("打开密码（留空＝不设打开密码）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("别人打开这份文件时要输它。少于 ${PdfSecurity.MIN_PASSWORD} 位会被拦，那等于没设。") },
                    isError = pdfUserPw.isNotEmpty() && pdfUserPw.length < PdfSecurity.MIN_PASSWORD,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pdfOwnerPw,
                    onValueChange = { pdfOwnerPw = it },
                    label = { Text("所有者密码（可留空）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("留空就按打开密码填同一个。库在所有者密码留空时会自己造一个随机值，那样以后连自己都解不开。") },
                    modifier = Modifier.fillMaxWidth(),
                )
                PdfPermission.entries.forEach { permission ->
                    Segmented(permission.blockedName, listOf("不允许", "允许"), if (permission in pdfAllowed) 1 else 0) { on ->
                        pdfAllowed = if (on == 1) pdfAllowed + permission else pdfAllowed - permission
                    }
                }
                Summary(PdfSecurity.ADVISORY_NOTE)
                Summary("密码强度固定用 128 位 AES。四项全允许＝什么都没限制，那样会被拦下来。")
            }
            OperationKind.DecryptPdf -> {
                OutlinedTextField(
                    value = pdfOpenPw,
                    onValueChange = { pdfOpenPw = it },
                    label = { Text("打开密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("产出不带保护的副本，原文件不动。要填的是打开密码，所有者密码不能用来打开。") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OperationKind.FormatJson -> {
                Segmented("形态", listOf("压成一行", "缩进展开"), if (jsonPretty) 1 else 0) { jsonPretty = it == 1 }
                if (jsonPretty) {
                    PickerRow("缩进宽度", listOf("1", "2", "4", "8"), listOf(1, 2, 4, 8).indexOf(jsonIndent.roundToInt())) {
                        jsonIndent = listOf(1f, 2f, 4f, 8f)[it]
                    }
                }
                Segmented("键排序", listOf("保持原顺序", "按字母排"), if (jsonSort) 1 else 0) { jsonSort = it == 1 }
                Segmented("非 ASCII", listOf("原样写 UTF-8", "转成 Unicode 转义"), if (jsonAscii) 1 else 0) { jsonAscii = it == 1 }
                Summary("数字一律照抄原文：1.50 不会变成 1.5，大整数也不会丢位。键的先后顺序默认保持原样。")
                Summary("这份不是合法 JSON 时会直接说清楚是哪一步读不下去，不会给你一个改坏了的文件。")
            }
            OperationKind.JsonToCsv -> {
                PickerRow("分隔符", Delimiter.entries.map { it.label }, Delimiter.entries.indexOf(csvDelimiter)) {
                    csvDelimiter = Delimiter.entries[it]
                }
                Segmented("换行", LineEnding.entries.map { it.label }, LineEnding.entries.indexOf(csvEnding)) {
                    csvEnding = LineEnding.entries[it]
                }
                Segmented("引号", listOf("必要处才加", "每格都加"), if (csvQuoteAll) 1 else 0) { csvQuoteAll = it == 1 }
                Summary("要最外层是数组的 JSON。数组里是对象就当一行、键当列名（列取并集）；是数组就按位置摆。")
                Summary("CSV 只有文字：数字、真假、null 过去之后全成字符串；嵌套的对象与数组会被压成一格 JSON 文本。")
                Summary("转过去会丢什么在结果说明里逐条写出来，不假装是无损转换。")
            }
            OperationKind.CsvToJson -> {
                Segmented("首行", listOf("当普通数据行", "当列名"), if (csvHeader) 1 else 0) { csvHeader = it == 1 }
                Segmented("类型识别", listOf("格子一律字符串", "认数字与真假"), if (csvInfer) 1 else 0) { csvInfer = it == 1 }
                if (csvInfer) {
                    Summary("认了类型之后，格子的**写法**就丢了：1.50 读回去是 1.5、1e3 读回去是 1000。007 这类前导零不会被认成数字，所以不受影响。")
                }
                Summary("分隔符按引号以外的票数自动认（逗号 / 分号 / 制表符 / 竖线），引号里的逗号不会骗到它。")
                Summary("列数不齐照样转，缺的格子留空，并在结果里报是第几行 —— 那通常是数据错了而不是格式错了。")
            }
            OperationKind.TextToPdf -> {
                Segmented("纸张", listOf("A4", "A5", "Letter"), listOf(PdfPaper.A4, PdfPaper.A5, PdfPaper.Letter).indexOf(paper)) {
                    paper = listOf(PdfPaper.A4, PdfPaper.A5, PdfPaper.Letter)[it]
                }
                IntSlider("字号", textSize, 7f..24f, { "%.0f pt".format(it) }) { textSize = it }
                IntSlider("行距", textLeading, 1.0f..2.5f, { "%.1f 倍".format(it) }, step = 0.1f) { textLeading = it }
                IntSlider("页边距", textMargin, 0f..120f, { "%.0f pt".format(it) }, step = 4f) { textMargin = it }
                Segmented("首行缩进", listOf("不缩", "缩两字"), if (textIndent) 1 else 0) { textIndent = it == 1 }
                Segmented("页码", listOf("不加", "加在页脚中间"), if (textNumber) 1 else 0) { textNumber = it == 1 }
                Summary("中文按字断行，收尾标点不会顶到行首；英文仍按整词断。")
                Summary("源编码自动认（认不干净会直接报错，不硬转成乱码）；中文字形从系统字体里取，取不到就报错，不会画成一堆方框。")
                Summary("一行的字排到栏宽就换行，排满一页就翻页，页数不用你管。")
            }
            OperationKind.ImageToIco -> {
                OutlinedTextField(
                    value = icoSizesText,
                    onValueChange = { icoSizesText = it },
                    label = { Text("尺寸（逗号分隔）") },
                    singleLine = true,
                    supportingText = { Text("默认 16,32,48,256。每个尺寸都从原图缩出一帧正方形，最长 ${Ico.MAX_SIDE}") },
                    isError = icoSizesText.isNotBlank() && icoSizes().isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Summary("一次出多个尺寸：系统按用途挑合适的那张，只给一个尺寸时在别的场合会被强制缩放而发虚。")
                Summary("非方图先缩到短边等于目标尺寸、再居中裁方，不会拉扁。")
            }
            OperationKind.OfficeToText -> {
                Summary("段落一段一行；表格拍平成行，单元格之间用制表符。")
                Summary("图片、页眉页脚、脚注正文这些搬不过来，抽完在结果里逐条写明丢了什么。")
                Summary("只认 Office 2007 起的 zip 容器（docx / pptx）；老式 .doc / .ppt 认不出会直说，不猜。")
            }
            OperationKind.XlsxToCsv -> {
                PickerRow("分隔符", Delimiter.entries.map { it.label }, Delimiter.entries.indexOf(csvDelimiter)) {
                    csvDelimiter = Delimiter.entries[it]
                }
                Segmented("换行", LineEnding.entries.map { it.label }, LineEnding.entries.indexOf(csvEnding)) {
                    csvEnding = LineEnding.entries[it]
                }
                Summary("每张表出一份 CSV，表名进文件名；只有一张表时不加后缀。")
                Summary("日期格子按样式认出来再转成 ISO 写法 —— 不认样式的话，转出来是 45047 这种序列号。")
                Summary("数字照文件里的写法原样搬：1.50 不会变成 1.5，007 不会变成 7。")
                Summary("公式给的是文件里存着的**算过的结果**；没算过的格子（比如别的工具刚写完还没打开过的）会是空格，并写明有几格。")
            }
            OperationKind.MdToHtml -> {
                Summary("认 CommonMark 加上 GFM 常用的表格、任务列表、删除线；围栏代码带语言名。")
                Summary("成品是一份完整网页（带 charset），浏览器双击就能看；认不出任何 Markdown 记号时会直接说明，不硬转。")
                Summary("脚注与四格缩进的代码块这两种写法这里不认，会照字面留下并在结果里写明。")
            }
            OperationKind.MdToText -> {
                Summary("标记吃掉，正文留下：列表还是带记号，表格改成制表符分列。")
                Summary("链接的地址与标题、图片本体在纯文本里没处放，会逐条写明丢了几处。")
            }
            OperationKind.HtmlToText -> {
                Summary("段落空行、列表记号、表格分列都留着；脚本、样式与页眉里那些不是给人读的字会丢掉并写明几段。")
                Summary("标签没闭合的按浏览器那套补上，补了几处会说出来 —— 那说明源文件本身写坏了。")
                Summary("实体认常用的一批（&amp; &nbsp; &#8212; 与老式不带分号的 &amp），认不出的照字面留着并计数。")
            }
            OperationKind.HtmlToMarkdown -> {
                Summary("标题、列表、表格、链接、图片、代码块写成 Markdown 标记；加粗斜体删除线照写。")
                Summary("表单控件与内嵌框架里放不进标记的东西，只留文字并写明几处。")
                Summary("成品里的 `*` `_` `<` 都是转义过的字面字符，别人再读这份 Markdown 不会把正文读成标记。")
            }
            OperationKind.CsvToXlsx -> {
                Summary("写一份真 .xlsx（一张表，表名用文件名），Excel / WPS / Numbers 直接打得开。")
                Summary("格子类型只按字面判：能一字不差读回来的写法才写成数字，`007`、`1.50`、15 位以上的编号一律保持文字。")
                Summary("以 `=` `+` `@` 开头的格子按文字存，不会被当成公式执行。")
            }
            OperationKind.JsonToXlsx -> {
                Summary("要的是对象数组：第一行是列名（取各条目键的并集，按首次出现的顺序），每条一行。")
                Summary("嵌套的对象与数组会压成一格文字，字段数不齐时缺的地方留空 —— 都会逐条写明。")
                Summary("与「JSON 转 CSV」用的是同一套摊平判据，两条路出来的表内容一致。")
            }
            OperationKind.IcoToImages -> {
                Summary("PNG 内嵌的那种直接把内嵌字节原样取出，不重新编码；老式位图（DIB）的要重建像素再编 PNG。")
                Summary("一个图标里有几个尺寸就出几张图，名字带序号。")
            }
            OperationKind.XmlToJson -> {
                Segmented("缩进宽度", listOf("1", "2", "4", "8"), listOf(1, 2, 4, 8).indexOf(jsonIndent.roundToInt())) {
                    jsonIndent = listOf(1f, 2f, 4f, 8f)[it]
                }
                Summary("对应关系是约定：子元素一律成数组（只有一个也是），属性名前面加 @，元素自己的文字进 #text。")
                Summary("注释、处理指令会丢掉；带 DTD 或实体定义的一律不解析 —— 实体能让转换工具去访问别人写的地址。")
                Summary("命名空间前缀原样留在名字里，不展开成 URI。")
            }
            OperationKind.JsonToXml -> {
                OutlinedTextField(
                    value = xmlRoot,
                    onValueChange = { xmlRoot = it },
                    label = { Text("根元素名（留空用文件名）") },
                    singleLine = true,
                    supportingText = { Text("留空就是 ${OutputNaming.stem(items.first().name)}。顶层只有一个键时，那个键直接当根元素。") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Segmented("缩进宽度", listOf("1", "2", "4", "8"), listOf(1, 2, 4, 8).indexOf(jsonIndent.roundToInt())) {
                    jsonIndent = listOf(1f, 2f, 4f, 8f)[it]
                }
                Summary("数组写成一组同名元素；键名不能当 XML 标签的会直接报错让你改名，不会悄悄换成别的。")
                Summary("空数组留一个空元素，空值写成自闭合标签。")
            }
            OperationKind.CleanMetadata -> {
                val reports = remember(items) { items.map { it.name to metaReportOf(it) } }
                reports.forEach { (name, report) ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Summary(report?.removalNote ?: "打不开这个文件，读不到元数据")
                }
                val saving = reports.mapNotNull { it.second }.sumOf { it.saving }
                Summary("合计约省 ${SizeInput.format(saving.toLong())}。像素数据整段照抄，不重新编码，画质一点不动。")
                if (reports.any { it.second?.willLoseRotation == true }) {
                    Summary("其中有靠 EXIF 记着角度的照片：清掉之后部分查看器会把它横过来。要保住方向，就先做一次格式转换（会按方向把像素重画正），再清这份新的。")
                }
                Summary("影响显示的部分会留着：ICC 色彩配置、JFIF 密度、Adobe 通道序。删掉它们照片会变色，那就不是清理而是损坏。")
            }
            OperationKind.PackZip -> {
                Summary("${items.size} 份文件、合计 ${sizeOf(items)}，压成一个 zip。")
                Summary("进包用原文件名，重名会自动补编号；jpg / mp4 / zip 这类已经压过的直接存原文，不再压第二遍。")
                Summary("包名跟着第一份文件走：${OutputNaming.stem(items.first().name)}_打包.zip")
            }
            OperationKind.UnpackZip -> {
                Summary("解出来的文件平铺在工作台里，路径压进名字（包名_目录_文件.扩展名），不保留目录层级。")
                Summary("带口令的条目、符号链接和解不了的压缩方式会跳过并在结果里说明；整包都是口令包就直接不做。")
                Summary("只解 zip。rar / 7z 用的是另一套算法，这里不支持，会直接告诉你不是 zip。")
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
            (kind == OperationKind.CompressPdf && pdfByTarget) ||
            ((kind == OperationKind.ConvertAudio || kind == OperationKind.ExtractAudio) && audioByTarget)
        if (needsTarget && SizeInput.parse(targetSizeText) == null) return "目标体积写成 10 或 1.5MB 这样"
        if (kind == OperationKind.ExtractPdfPages && pageSpec.isBlank()) return "先写要取哪些页"
        if (kind == OperationKind.RemovePdfPages && pageSpec.isBlank()) return "先写要删哪些页"
        if (kind == OperationKind.MergePdfs && items.size < 2) return "合并 PDF 至少选两个文件"
        if (kind == OperationKind.PdfWatermark && watermarkText.isBlank()) return "先写要盖的水印文字"
        if (kind == OperationKind.JsonToXml && xmlRoot.isNotBlank() && !Xml.isElementName(xmlRoot.trim())) {
            return "根元素名「${xmlRoot.trim()}」不能当 XML 标签用"
        }
        if (kind == OperationKind.EncryptPdf) PdfSecurity.validate(pdfUserPw, pdfOwnerPw, pdfAllowed)?.let { return it }
        if (kind == OperationKind.ImageToIco && icoSizes().isEmpty()) return "尺寸要写 1~${Ico.MAX_SIDE} 之间的数，逗号分隔"
        if (startSecondText.isNotBlank() && startSecondText.toFloatOrNull() == null) return "开始秒数不是数字"
        if (durationSecondText.isNotBlank() && durationSecondText.toFloatOrNull() == null) return "取多少秒不是数字"
        return null
    }

    /** 图标尺寸那一栏：只留合法值，重复的合并（引擎会按从大到小写目录）。 */
    private fun icoSizes(): List<Int> =
        icoSizesText.split(',', '，', ' ').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..Ico.MAX_SIDE }.distinct()

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
            OperationKind.AddPageNumbers -> Operation.PageNumbers(
                pageSpec.trim(), numberStyle, SPOTS[numberSpot],
                firstNumber.roundToInt(), numberSize.roundToInt(),
            )
            OperationKind.PdfWatermark -> Operation.PdfWatermark(
                watermarkText.trim(), watermarkColumns.roundToInt(), watermarkRows.roundToInt(),
                watermarkOpacity.roundToInt(), watermarkTilt.roundToInt(), watermarkGray.roundToInt(),
                pageSpec.trim(),
            )
            OperationKind.MergePdfs -> Operation.MergePdfs
            OperationKind.PdfToImages -> Operation.PdfToImages(imageFormat, pdfScale.roundToInt().toFloat(), quality.roundToInt())
            OperationKind.CompressGif -> Operation.CompressGif(maxEdge.roundToInt(), gifFps.roundToInt(), gifColors.roundToInt())
            OperationKind.GifToImages -> Operation.GifToImages(imageFormat, firstFrameOnly, quality.roundToInt())
            OperationKind.ImagesToGif -> Operation.ImagesToGif(gifFrameDelay.roundToInt(), maxEdge.roundToInt())
            OperationKind.VideoToImage -> Operation.VideoToImage(
                imageFormat,
                startSecondText.toFloatOrNull()?.toDouble() ?: 0.0,
                quality.roundToInt(),
                maxEdge.roundToInt(),
            )
            OperationKind.VideoToGif -> Operation.VideoToGif(
                gifFps.roundToInt(), maxEdge.roundToInt(),
                startSecondText.toFloatOrNull()?.toDouble() ?: 0.0,
                durationSecondText.toFloatOrNull()?.toDouble() ?: 0.0,
            )
            OperationKind.CompressVideo -> Operation.CompressVideo(
                videoFormat, bitrate.roundToInt(), 0, 96,
                if (videoByTarget) targetBytes else null,
            )
            OperationKind.ConvertAudio, OperationKind.ExtractAudio -> Operation.AudioConvert(
                audioTarget, if (audioByTarget) targetBytes else null,
            )
            // 密码原样传，不做 trim：首尾空格可能就是用户密码的一部分
            OperationKind.EncryptPdf -> Operation.EncryptPdf(pdfUserPw, pdfOwnerPw, pdfAllowed)
            OperationKind.ConvertTextEncoding -> Operation.ConvertTextEncoding(
                encodingAt(textSource), textTarget, textBom, textEnding,
            )
            OperationKind.ConvertSubtitle -> Operation.ConvertSubtitle(subtitleTarget, encodingAt(subtitleSource))
            OperationKind.DecryptPdf -> Operation.DecryptPdf(pdfOpenPw)
            OperationKind.CleanMetadata -> Operation.CleanMetadata
            OperationKind.PackZip -> Operation.PackArchive
            OperationKind.UnpackZip -> Operation.UnpackArchive
            OperationKind.FormatJson -> Operation.FormatJson(
                jsonPretty, jsonIndent.roundToInt(), jsonSort, jsonAscii,
            )
            OperationKind.JsonToCsv -> Operation.JsonToCsv(csvDelimiter, csvEnding, csvQuoteAll)
            OperationKind.CsvToJson -> Operation.CsvToJson(csvHeader, csvInfer, jsonIndent.roundToInt())
            OperationKind.XmlToJson -> Operation.XmlToJson(jsonIndent.roundToInt())
            OperationKind.JsonToXml -> Operation.JsonToXml(xmlRoot.trim(), jsonIndent.roundToInt())
            OperationKind.TextToPdf -> Operation.TextToPdf(
                textSize.roundToInt(), paper, textMargin.roundToInt(), textLeading, textIndent, textNumber,
            )
            OperationKind.ImageToIco -> Operation.ImageToIco(icoSizes())
            OperationKind.OfficeToText -> Operation.OfficeToText
            OperationKind.XlsxToCsv -> Operation.XlsxToCsv(csvDelimiter, csvEnding)
            OperationKind.MdToHtml -> Operation.MdToHtml
            OperationKind.MdToText -> Operation.MdToText
            OperationKind.HtmlToText -> Operation.HtmlToText
            OperationKind.HtmlToMarkdown -> Operation.HtmlToMarkdown
            OperationKind.CsvToXlsx -> Operation.CsvToXlsx
            OperationKind.JsonToXlsx -> Operation.JsonToXlsx
            OperationKind.IcoToImages -> Operation.IcoToImages
        }
    }
}

private val ROTATE_OPTIONS = listOf("顺时针 90°" to 90, "180°" to 180, "逆时针 90°" to 270)

private val SPOTS = listOf(StampSpot.BottomCenter, StampSpot.BottomRight, StampSpot.BottomLeft, StampSpot.TopCenter)

private fun edgeLabel(edge: Float): String = if (edge == 0f) "不改" else "%.0f px".format(edge)

private fun sizeOf(items: List<WorkItem>): String = SizeInput.format(items.sumOf { it.size })

/**
 * 参数面板报"要扔哪些段"用的这份读**只读文件开头**：预览不该为一屏字把 20 MB 的原图搬进堆。
 * 像素之后的文本块可能漏计，所以界面写的是"约省"；真正清理时引擎读整份文件，报的是准数。
 */
private fun metaReportOf(item: WorkItem): MetaReport? =
    runCatching { ImageMeta.report(ImageMeta.head(item.file)) }.getOrNull()

private fun FileKind.label(): String = badge

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

/** 目标格式装不下什么 —— 按格式的静态能力说，不猜具体文件。 */
private fun subtitleLimitsNote(target: SubtitleFormat): String = buildString {
    append("转到 ${target.label}：")
    val loses = ArrayList<String>()
    if (!target.keepsEndTime) loses += "没有结束时间"
    if (!target.keepsLineBreaks) loses += "多行并成一行"
    if (target.resolutionMs > 1) loses += "时间精度到 ${target.resolutionMs} 毫秒"
    append(if (loses.isEmpty()) "不丢东西" else "会丢" + loses.joinToString("、"))
}

/**
 * 下拉选择器。编码有八九种，塞进 SegmentedButtonRow 会被挤成一串省略号，
 * 所以选项多于四个时用这个。
 */
@Composable
private fun PickerRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    options.getOrElse(selected) { "选一个" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(if (index == selected) "✓  $option" else option) },
                        onClick = { onSelect(index); open = false },
                    )
                }
            }
        }
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
