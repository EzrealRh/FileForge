package com.fileforge.converter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.core.ops.OperationKind
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.engine.FileDetail

/** 详情面板：大图预览 + 元信息 + 这个文件能做哪些操作。预览图由 ViewModel 持有并负责回收。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    item: WorkItem,
    detail: FileDetail,
    preview: Bitmap?,
    loading: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Text(detail.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    detail.kindLabel,
                    detail.sizeText,
                    "加入于 ${detail.addedText}",
                    detail.fromOperation?.let { "来自 $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier.fillMaxWidth().height(240.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                MotionPreview(item, Modifier.matchParentSize()) {
                    when {
                        preview != null && !preview.isRecycled -> Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxWidth().padding(6.dp),
                            contentScale = ContentScale.Fit,
                        )
                        loading -> Text("正在读取…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        else -> Text(
                            detail.previewError ?: "这类文件没有可显示的预览",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LocationRow(item.sharedPath, item.file.absolutePath)
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()

            if (detail.facts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                detail.facts.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                HorizontalDivider()
            }

            val usable = OperationKind.applicable(setOf(item.kind))
            if (usable.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("这个文件能做", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                usable.forEach { kind ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(kind.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
                        Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onConvert, modifier = Modifier.fillMaxWidth()) {
                    Text("就处理这个文件")
                }
            }
            detail.previewError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onToggleSelect, modifier = Modifier.weight(1f)) {
                    Text(if (selected) "取消选中" else "选中它")
                }
                OutlinedButton(onClick = { onDelete(); onDismiss() }) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 文件现在的位置：优先给手机存储里那份的真实路径（转换一完成就自动放过去），
 * 还没有的话说明清楚为什么。整行可一键复制。
 */
@Composable
private fun LocationRow(shared: String?, local: String) {
    val path = shared ?: local
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("位置", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("文件位置", path))
                copied = true
            }) {
                Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            if (shared != null) {
                "这份已经放在手机存储里，文件管理器和其它 App 都能直接看到；工作台里另留一份是为了能接着再加工。"
            } else {
                "这份还留在应用自己的目录里（导入的原件在原来的地方没动）。要拿出去用：顶栏「存到手机」会复制到手机存储的 文件工坊 目录。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
