package com.fileforge.converter.ui

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RuleFolder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.converter.data.WorkItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    viewModel: WorkbenchViewModel,
    onAddFiles: () -> Unit,
    onExport: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
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
                    IconButton(onClick = onExport, enabled = state.items.isNotEmpty()) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "导出到文件夹")
                    }
                    IconButton(onClick = viewModel::clearAll, enabled = state.items.isNotEmpty()) {
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
                            Spacer(Modifier.width(12.dp))
                            Text("正在处理", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { viewModel.selectAll(state.visible.isNotEmpty()) }, enabled = !state.busy) {
                            Text(if (state.selection.isEmpty()) "全选" else "取消选择")
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.openSheet(true) },
                            enabled = state.selection.isNotEmpty() && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.selection.isEmpty()) "先选文件" else "转换 · ${state.selection.size}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.items.isEmpty()) {
                EmptyState(onAddFiles)
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
                    Button(onClick = onAddFiles, enabled = !state.busy) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加", maxLines = 1)
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.visible, key = { it.id }) { item ->
                        FileRow(
                            item = item,
                            checked = item.id in state.selection,
                            onToggle = { viewModel.toggle(item.id) },
                            onDelete = { viewModel.remove(item.id) },
                        )
                    }
                }
            }
        }
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
private fun EmptyState(onAddFiles: () -> Unit) {
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
            Text("图片、PDF、视频、GIF 都在这里转", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "文件全部留在本机处理，不联网、不上传。转换结果会留在工作台里，可以接着再做下一步。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAddFiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加文件")
            }
        }
    }
}

@Composable
private fun FileRow(item: WorkItem, checked: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onToggle,
        colors = CardDefaults.cardColors(containerColor = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun kindLabel(item: WorkItem): String = when (item.kind) {
    com.fileforge.core.model.FileKind.Pdf -> "PDF"
    com.fileforge.core.model.FileKind.Gif -> "GIF"
    com.fileforge.core.model.FileKind.WebP -> "WebP"
    com.fileforge.core.model.FileKind.Heic -> "HEIC"
    com.fileforge.core.model.FileKind.Avif -> "AVIF"
    com.fileforge.core.model.FileKind.Mp4 -> "MP4"
    com.fileforge.core.model.FileKind.WebM -> "WebM"
    com.fileforge.core.model.FileKind.Mkv -> "MKV"
    com.fileforge.core.model.FileKind.QuickTime -> "MOV"
    else -> item.extension.uppercase()
}

private fun usedSpace(items: List<WorkItem>): String =
    com.fileforge.core.util.SizeInput.format(items.sumOf { it.size })
