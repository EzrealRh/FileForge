package com.fileforge.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileforge.core.util.SizeInput

/**
 * 应用内更新面板：查最新版 → 在应用里下载 → 交系统安装器装。
 *
 * 出错时把 GitHub 原话贴出来而不是只说"失败"，因为私有仓库没 token、token 过期、
 * 网络掐断这三种情况用户要做的动作完全不同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    localVersion: String,
    state: UpdateUi,
    tokenDraft: String,
    onTokenChange: (String) -> Unit,
    onSaveToken: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("检查更新", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "当前版本 v$localVersion · 全程只访问 GitHub，不传你任何文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCheck, enabled = state !is UpdateUi.Checking) {
                    Text(if (state is UpdateUi.Checking) "检查中" else "重新检查")
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            when (state) {
                UpdateUi.Idle, UpdateUi.Checking -> Checked()
                is UpdateUi.UpToDate -> {
                    Text("已经是最新版本（v${state.versionName.removePrefix("v")}）", style = MaterialTheme.typography.bodyLarge)
                }
                is UpdateUi.Failed -> {
                    Text("没查到更新", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is UpdateUi.Available -> Available(state, onDownload, onInstall)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            TokenField(tokenDraft, onTokenChange, onSaveToken)
        }
    }
}

@Composable
private fun Checked() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正在问 GitHub 最新版本…", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun Available(state: UpdateUi.Available, onDownload: () -> Unit, onInstall: () -> Unit) {
    val asset = state.asset
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "发现新版本 ${state.release.tagName} · ${SizeInput.format(asset.size)}",
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.release.dayLabel.isNotBlank()) {
            Text(
                "发布于 ${state.release.dayLabel} · 装完工作台里的文件都还在",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.release.notes.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    state.release.notes.lines().joinToString("\n") { if (it.startsWith("#")) it.removePrefix("#").trim() else it }
                        .trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 200,
                    overflow = TextOverflow.Clip,
                )
            }
        }

        when {
            state.downloaded != null -> Button(onClick = onInstall, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("安装 ${asset.name}")
            }
            state.downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("已下载 ${state.percent}% / ${SizeInput.format(asset.size)}", style = MaterialTheme.typography.bodySmall)
            }
            else -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("下载到这台手机")
            }
        }
        Text(
            "安装包存在应用缓存里，装完系统会自己处理；某些定制系统可能还会再问两三道，都点是。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TokenField(tokenDraft: String, onTokenChange: (String) -> Unit, onSaveToken: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("访问 token（可选）", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = tokenDraft,
            onValueChange = onTokenChange,
            label = { Text("只在仓库是私有需要") },
            supportingText = {
                Text("在 GitHub 生成只有 Contents: Read-only 的 fine-grained token 贴进来，只存这台手机，不上传任何东西")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSaveToken) { Text("存到本机并重新检查") }
        }
    }
}
