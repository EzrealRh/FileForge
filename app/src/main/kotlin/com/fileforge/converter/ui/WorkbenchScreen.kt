package com.fileforge.converter.ui

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
import androidx.compose.material.icons.outlined.RuleFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.core.model.FileKind
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem

@OptIn(ExperimentalMaterial3Api::class)
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
                    IconButton(onClick = onSaveToPhone, enabled = state.items.isNotEmpty()) {
                        Icon(Icons.Outlined.PhotoAlbum, contentDescription = "存到手机 Download/文件工坊")
                    }
                    IconButton(onClick = onExport, enabled = state.items.isNotEmpty()) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "导出到文件夹")
                    }
                    IconButton(onClick = viewModel::askClear, enabled = state.items.isNotEmpty() && !state.busy) {
                        Icon(Icons.Outlined.Delete, contentDescription = "清空工作台")
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
                EmptyState(onPickPdf, onPickFromGallery, onAddFiles)
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
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPickPdf, enabled = !state.busy) {
                            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("加 PDF", maxLines = 1)
                        }
                        FilledTonalButton(onClick = onPickFromGallery, enabled = !state.busy) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("相册", maxLines = 1)
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

    state.detailId?.let { id ->
        val detail = state.detail
        val item = state.items.firstOrNull { it.id == id }
        if (detail != null && item != null) {
            DetailSheet(
                item = item,
                detail = detail,
                preview = state.preview,
                loading = state.detailLoading,
                selected = id in state.selection,
                onToggleSelect = { viewModel.toggle(id) },
                onConvert = { viewModel.openSheetFor(id) },
                onDelete = { viewModel.remove(id) },
                onDismiss = viewModel::closeDetail,
            )
        }
    }

    if (state.confirmClear) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClear,
            title = { Text("清空工作台？") },
            text = { Text("工作台里的 ${state.items.size} 个文件（含转换结果）会被删掉，本机原文件不受影响。已经导出到相册或文件夹的也没了。") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissClear(); viewModel.clearAll() }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissClear) { Text("取消") } },
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
            onDismiss = { viewModel.openSheet(false) },
            onStart = viewModel::run,
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
            Text("PDF 工具箱：拆分、截取、压缩、合并", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "按体积拆分、按页码截取或删除、旋转、合并、压掉内嵌图片的水分、提取文字，也能转图片和 GIF、压视频。" +
                    "文件全部留在本机处理，不联网、不上传；结果会留在工作台，可以接着做下一步。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPickPdf, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("加 PDF")
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onPickFromGallery, modifier = Modifier.fillMaxWidth()) {
                Text("从相册选图片、视频")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAddFiles, modifier = Modifier.fillMaxWidth()) {
                Text("从文件管理器选其他类型（GIF、视频、HEIC…）")
            }
        }
    }
}

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
    FileKind.Pdf -> "PDF"
    FileKind.Gif -> "GIF"
    FileKind.WebP -> "WebP"
    FileKind.Heic -> "HEIC"
    FileKind.Avif -> "AVIF"
    FileKind.Mp4 -> "MP4"
    FileKind.WebM -> "WebM"
    FileKind.Mkv -> "MKV"
    FileKind.QuickTime -> "MOV"
    else -> item.extension.uppercase()
}

private fun usedSpace(items: List<WorkItem>): String = SizeInput.format(items.sumOf { it.size })
