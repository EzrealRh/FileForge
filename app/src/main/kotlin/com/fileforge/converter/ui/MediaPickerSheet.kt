package com.fileforge.converter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.converter.data.MediaEntry
import com.fileforge.core.util.SizeInput

private enum class MediaTab(val label: String, val matches: (MediaEntry) -> Boolean) {
    All("全部", { true }),
    Image("图片", { !it.isVideo }),
    Video("视频", { it.isVideo }),
}

/** 应用内的相册选择器：网格多选，不用再进系统文件管理器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPickerSheet(
    state: MediaUi,
    thumbnail: suspend (MediaEntry) -> android.graphics.Bitmap?,
    onConfirm: (List<MediaEntry>) -> Unit,
    onPickOtherFiles: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(MediaTab.All) }
    var picked by remember { mutableStateOf(emptySet<MediaEntry>()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("从相册选", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "直接读系统媒体库，选中后复制进工作台再处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onPickOtherFiles) { Text("其他文件") }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MediaTab.entries.forEach { option ->
                    FilterChip(selected = tab == option, onClick = { tab = option }, label = { Text(option.label, maxLines = 1) })
                }
            }
            Spacer(Modifier.height(10.dp))

            when (state) {
                MediaUi.Idle, MediaUi.Loading -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                is MediaUi.Denied -> Message(
                    text = state.message + "\n\n没给权限时也可以用「其他文件」从系统里挑。",
                    action = "去授权" to onRequestPermission,
                )

                is MediaUi.Empty -> Message(
                    text = if (state.partialAccess) {
                        "当前是「部分照片」授权，只能看到你之前在系统里选过的那些。想选更多就重新授权一次。"
                    } else {
                        "媒体库里没有可读的图片或视频。也可以直接用「其他文件」挑。"
                    },
                    action = "重新授权" to onRequestPermission,
                )

                is MediaUi.Ready -> {
                    if (state.partialAccess) {
                        Text(
                            "当前是「部分照片」模式，只列你授权过的内容。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    val shown = state.entries.filter(tab.matches)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().height(380.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(shown, key = { it.uri.toString() }) { entry ->
                            val selected = entry in picked
                            MediaCell(
                                entry = entry,
                                selected = selected,
                                thumbnail = { thumbnail(entry) },
                                onClick = {
                                    picked = if (selected) picked - entry else picked + entry
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (picked.isEmpty()) "还没选" else "已选 ${picked.size} 个 · ${SizeInput.format(picked.sumOf { it.size })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (picked.isNotEmpty()) onConfirm(picked.toList()) }, enabled = picked.isNotEmpty()) {
                    Text("加入工作台")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MediaCell(entry: MediaEntry, selected: Boolean, thumbnail: suspend () -> android.graphics.Bitmap?, onClick: () -> Unit) {
    val bitmap = produceState<android.graphics.Bitmap?>(null, entry.uri) { value = thumbnail() }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        bitmap.value?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (entry.isVideo) {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp),
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Color.White, CircleShape),
            )
        }
        Text(
            entry.name.substringAfterLast('.'),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).width(48.dp),
        )
    }
}

@Composable
private fun Message(text: String, action: Pair<String, () -> Unit>) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        Button(onClick = action.second) { Text(action.first) }
    }
}
