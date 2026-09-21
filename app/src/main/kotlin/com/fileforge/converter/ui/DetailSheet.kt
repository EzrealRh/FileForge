package com.fileforge.converter.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    selected: Boolean,
    onToggleSelect: () -> Unit,
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
                when {
                    preview != null && !preview.isRecycled -> Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                        contentScale = ContentScale.Fit,
                    )
                    detail.facts.isEmpty() -> Text(
                        detail.previewError ?: "这类文件没有可显示的预览",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    else -> Text("正在读取…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
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
                        Text(
                            kind.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
