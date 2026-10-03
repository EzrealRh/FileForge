package com.fileforge.converter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RuleFolder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.core.model.FileKind
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WorkbenchScreen(
    viewModel: WorkbenchViewModel,
    onAddFiles: () -> Unit,
    onPickPdf: () -> Unit,
    onExport: () -> Unit,
    onSaveToPhone: () -> Unit,
    onPickFromGallery: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var overflowOpen by remember { mutableStateOf(false) }
    var renamePrefix by remember { mutableStateOf("") }
    var renameStart by remember { mutableStateOf("1") }
    var renameDigits by remember { mutableStateOf("3") }

    // 用序号当 key，否则连着两条同文案的提示会被吞掉
    LaunchedEffect(state.notice?.seq) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.text)
        viewModel.consumeNotice()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件工坊", style = MaterialTheme.typography.titleLarge)
                        if (state.items.isNotEmpty()) {
                            Text(
                                "${state.items.size} 个文件 · ${usedSpace(state.items)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // 分享与删除跟着选中走，最常用，留在明面上；
                    // 存到手机 / 导出 / 检查更新这类低频动作收进 ⋮ 菜单，顶栏不再摆满
                    IconButton(
                        onClick = { viewModel.share(state.selected) },
                        enabled = state.selected.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = if (state.selection.isEmpty()) {
                                "先选中要分享的文件"
                            } else {
                                "分享选中的 ${state.selection.size} 个"
                            },
                        )
                    }
                    IconButton(onClick = viewModel::askDelete, enabled = state.items.isNotEmpty() && !state.busy) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = if (state.selection.isEmpty()) "清空工作台" else "删除选中的 ${state.selection.size} 个",
                        )
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("存到手机") },
                            onClick = {
                                overflowOpen = false
                                onSaveToPhone()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出到文件夹") },
                            onClick = {
                                overflowOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("批量重命名") },
                            onClick = {
                                overflowOpen = false
                                viewModel.openRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("检查更新") },
                            onClick = {
                                overflowOpen = false
                                viewModel.openUpdateSheet()
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    state.running?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${it.percent}% · ${it.current}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("正在处理", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = viewModel::toggleSelectAll, enabled = state.visible.isNotEmpty()) {
                            Text(if (state.allVisibleSelected) "取消选择" else "全选")
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.openSheet(true) },
                            enabled = state.selection.isNotEmpty() && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.selection.isEmpty()) "先选文件" else "转换 · 已选 ${state.selection.size}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.items.isEmpty()) {
                if (state.loaded) {
                    EmptyState(onPickPdf, onPickFromGallery, onAddFiles)
                } else {
                    // 后台装载还没完成：这时候的"空"是假的，别把空态页闪出来。
                    // 添加在装载期间也保持可用，别让入口跟着列表一起消失
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在读取工作台…", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onAddFiles, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                            Text("添加文件")
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 筛选条横向滚，否则四个 chip 会把「添加」挤成两行
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KindFilter.entries.forEach { filter ->
                            androidx.compose.material3.FilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.filter(filter) },
                                label = { Text(filter.label, maxLines = 1) },
                            )
                        }
                        androidx.compose.material3.FilterChip(
                            selected = state.groupByDate,
                            onClick = viewModel::toggleDateGroup,
                            label = { Text("按日期", maxLines = 1) },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = state.groupByBatch,
                            onClick = viewModel::toggleBatchGroup,
                            label = { Text("按批次", maxLines = 1) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 主入口是通用添加：应用认五十来种格式，别的类型不该藏在相册选择器里
                        Button(onClick = onAddFiles, enabled = !state.busy) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加文件", maxLines = 1)
                        }
                        FilledTonalButton(onClick = onPickFromGallery, enabled = !state.busy) {
                            Text("相册", maxLines = 1)
                        }
                        FilledTonalButton(onClick = onPickPdf, enabled = !state.busy) {
                            Text("PDF", maxLines = 1)
                        }
                    }
                }
                Text(
                    "点一下选中，长按看详情",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.groupByBatch) {
                        // 一次导入 + 它转出来的结果算一组；点组头整批选中
                        state.groupedByBatch().forEach { (batch, list) ->
                            stickyHeader(key = batch?.id ?: -1L) {
                                BatchHeader(batch, list.size, state.selection.containsAll(list.map { it.id })) {
                                    batch?.let { viewModel.selectBatch(it.id) }
                                }
                            }
                            items(list, key = { it.id }) { item ->
                                FileRow(
                                    item = item,
                                    checked = item.id in state.selection,
                                    busy = state.busy,
                                    onToggle = { viewModel.toggle(item.id) },
                                    onOpen = { viewModel.openDetail(item.id) },
                                    onDelete = { viewModel.remove(item.id) },
                                )
                            }
                        }
                    } else if (state.groupByDate) {
                        // 组头跟着滚动停在顶上，一眼看清哪堆是今天的
                        state.groupedByDate(System.currentTimeMillis()).forEach { (group, list) ->
                            stickyHeader(key = group) {
                                Text(
                                    "${group.label} · ${list.size} 个",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(vertical = 6.dp),
                                )
                            }
                            items(list, key = { it.id }) { item ->
                                FileRow(
                                    item = item,
                                    checked = item.id in state.selection,
                                    busy = state.busy,
                                    onToggle = { viewModel.toggle(item.id) },
                                    onOpen = { viewModel.openDetail(item.id) },
                                    onDelete = { viewModel.remove(item.id) },
                                )
                            }
                        }
                    } else {
                        items(state.visible, key = { it.id }) { item ->
                            FileRow(
                                item = item,
                                checked = item.id in state.selection,
                                busy = state.busy,
                                onToggle = { viewModel.toggle(item.id) },
                                onOpen = { viewModel.openDetail(item.id) },
                                onDelete = { viewModel.remove(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.detailId?.let { id ->
        val detail = state.detail
        val item = state.items.firstOrNull { it.id == id }
        if (detail != null && item != null) {
            DetailSheet(
                item = item,
                detail = detail,
                preview = state.preview,
                onShare = { viewModel.share(listOf(item)) },
                onOpenWith = { viewModel.openWith(item) },
                loading = state.detailLoading,
                selected = id in state.selection,
                onToggleSelect = { viewModel.toggle(id) },
                onConvert = { viewModel.openSheetFor(id) },
                onDelete = { viewModel.remove(id) },
                onDismiss = viewModel::closeDetail,
            )
        }
    }

    if (state.pendingDelete.isNotEmpty()) {
        val onlySelected = state.selection.isNotEmpty()
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = {
                Text(if (onlySelected) "删除选中的 ${state.pendingDelete.size} 个？" else "清空工作台（${state.pendingDelete.size} 个文件）？")
            },
            text = {
                Text(
                    "删的是工作台里这份；之前自动复制到手机存储「文件工坊」的那份不会被删。" +
                        "你导入进来的原件本来就在原处，也不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text(if (onlySelected) "删除" else "全部删除") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("取消") } },
        )
    }

    if (state.renameOpen) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRename,
            title = { Text("批量重命名") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "把选中的 ${state.selection.size} 个按「前缀 + 序号」改名，扩展名保持不变。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = renamePrefix,
                        onValueChange = { renamePrefix = it },
                        label = { Text("前缀") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = renameStart,
                        onValueChange = { renameStart = it.filter { c -> c.isDigit() } },
                        label = { Text("起始序号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = renameDigits,
                        onValueChange = { renameDigits = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("序号补零到几位") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "例：${renamePrefix}001",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmRename(
                            renamePrefix,
                            renameStart.toIntOrNull() ?: 1,
                            renameDigits.toIntOrNull() ?: 3,
                        )
                    }
                ) { Text("改名") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissRename) { Text("取消") } },
        )
    }

    if (state.mediaPickerOpen) {
        MediaPickerSheet(
            state = state.media,
            thumbnail = viewModel::mediaThumbnail,
            onConfirm = { entries ->
                viewModel.closeMediaPicker()
                viewModel.import(entries.map { it.uri })
            },
            onPickOtherFiles = { viewModel.closeMediaPicker(); onAddFiles() },
            onRequestPermission = onPickFromGallery,
            onDismiss = viewModel::closeMediaPicker,
        )
    }

    if (state.sheetOpen) {
        OperationSheet(
            items = state.selected,
            imageNames = state.items
                .filter { it.kind.isImage && state.selected.none { s -> s.id == it.id } }
                .map { it.name },
            recentKinds = viewModel.recentOperationNames(),
            onDismiss = { viewModel.openSheet(false) },
            onStart = viewModel::run,
        )
    }

    if (state.updateSheetOpen) {
        UpdateSheet(
            localVersion = viewModel.localVersionName,
            state = state.update,
            tokenDraft = state.updateToken,
            onTokenChange = { viewModel.setUpdateTokenDraft(it) },
            onSaveToken = viewModel::saveUpdateToken,
            onCheck = viewModel::checkForUpdate,
            onDownload = viewModel::downloadUpdate,
            onInstall = viewModel::installUpdate,
            onDismiss = viewModel::closeUpdateSheet,
        )
    }
}

@Composable
private fun EmptyState(onPickPdf: () -> Unit, onPickFromGallery: () -> Unit, onAddFiles: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
            Icon(
                imageVector = Icons.Outlined.RuleFolder,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("EPUB、Word、PDF、视频…什么都能进这一个口", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "五十来种格式在本机互相转：电子书、Word、表格、Markdown、网页、图片、GIF、视频、音频、压缩包。" +
                    "文件全部留在本机处理，不联网、不上传；结果会留在工作台，可以接着做下一步。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAddFiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加文件（EPUB、Word、视频…都行）")
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onPickFromGallery, modifier = Modifier.fillMaxWidth()) {
                Text("从相册选图片、视频")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onPickPdf, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Outlined.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("加 PDF")
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BatchHeader(batch: com.fileforge.converter.data.Batch?, count: Int, allSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (batch?.title ?: "未分组") + " · $count 个",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                batch?.let { "同一批导入，转出来的也在这里 · " + clock.format(java.util.Date(it.addedAt)) }
                    ?: "点一下整批选中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (allSelected) "取消整批" else "整批选中",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private val clock = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: WorkItem,
    checked: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onToggle, onLongClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumbnail(item)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(kindLabel(item), item.sizeLabel, item.fromOperation?.let { "来自 $it" })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun kindLabel(item: WorkItem): String = when (item.kind) {
    // 这两种没有统一短名，报真实扩展名更有用（.epub 和 .zip 都归 Unknown/Zip）
    FileKind.Unknown, FileKind.Zip -> item.extension.uppercase()
    else -> item.kind.badge
}

private fun usedSpace(items: List<WorkItem>): String = SizeInput.format(items.sumOf { it.size })
