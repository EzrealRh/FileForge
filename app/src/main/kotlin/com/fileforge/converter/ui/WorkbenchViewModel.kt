package com.fileforge.converter.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.MetaReader
import com.fileforge.converter.engine.OperationRunner
import com.fileforge.converter.engine.FileDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class KindFilter(val label: String, val matches: (FileKind) -> Boolean) {
    All("全部", { true }),
    Image("图片", { it.isImage }),
    Pdf("PDF", { it == FileKind.Pdf }),
    Video("视频", { it.isVideo }),
}

data class Running(val title: String, val percent: Int, val current: String)

data class WorkbenchState(
    val items: List<WorkItem> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val filter: KindFilter = KindFilter.All,
    val running: Running? = null,
    val notice: String? = null,
    val sheetOpen: Boolean = false,
    val detailId: Long? = null,
    val detail: FileDetail? = null,
    val preview: android.graphics.Bitmap? = null,
) {
    val selected: List<WorkItem> get() = items.filter { it.id in selection }
    val visible: List<WorkItem> get() = items.filter { filter.matches(it.kind) }
    val busy: Boolean get() = running != null
}

class WorkbenchViewModel(app: Application) : AndroidViewModel(app) {

    private val workspace = Workspace(app)
    private val runner = OperationRunner(app, workspace)
    private val meta = MetaReader()
    private val _state = MutableStateFlow(WorkbenchState(items = workspace.list()))
    val state = _state.asStateFlow()

    fun import(uris: List<Uri>) = viewModelScope.launch {
        val added = ArrayList<WorkItem>()
        val failures = ArrayList<String>()
        uris.forEach { uri ->
            runCatching { workspace.import(uri, getContent()) }
                .onSuccess { added += it }
                .onFailure { failures += "${uri.lastPathSegment ?: "文件"}：${it.message}" }
        }
        _state.value = _state.value.refreshed()
            .copy(selection = _state.value.selection + added.map { it.id }.toSet())
        if (failures.isNotEmpty()) notify("${failures.size} 个文件没能加入：" + failures.first())
    }

    fun toggle(id: Long) {
        val current = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (id in current) current - id else current + id,
        )
    }

    fun selectAll(visible: Boolean) {
        _state.value = _state.value.copy(
            selection = if (visible) _state.value.visible.map { it.id }.toSet() else emptySet(),
        )
    }

    fun remove(id: Long) {
        _state.value.find(id)?.let { workspace.remove(it) }
        _state.value = _state.value.refreshed()
    }

    fun clearAll() {
        workspace.clearAll()
        _state.value = WorkbenchState()
    }

    fun filter(kind: KindFilter) {
        _state.value = _state.value.copy(filter = kind)
    }

    fun openSheet(open: Boolean) {
        _state.value = _state.value.copy(sheetOpen = open)
    }

    /** 详情和预览图都要读文件，放 IO 线程做，别卡住列表。 */
    fun openDetail(id: Long) {
        val item = _state.value.find(id) ?: return
        recyclePreview()
        _state.value = _state.value.copy(
            detailId = id,
            detail = FileDetail(item.name, "", item.sizeLabel, "", item.fromOperation, emptyList(), null),
            preview = null,
        )
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val detail = runCatching { meta.read(item) }.getOrElse { error ->
                FileDetail(item.name, "读取失败", item.sizeLabel, "", item.fromOperation, emptyList(), error.message)
            }
            val preview = if (item.kind.isImage || item.kind.isVideo || item.kind == com.fileforge.core.model.FileKind.Pdf) {
                meta.preview(item)
            } else {
                null
            }
            if (_state.value.detailId == id) {
                _state.value = _state.value.copy(detail = detail, preview = preview)
            } else {
                preview?.recycle()
            }
        }
    }

    fun closeDetail() {
        recyclePreview()
        _state.value = _state.value.copy(detailId = null, detail = null, preview = null)
    }

    private fun recyclePreview() {
        _state.value.preview?.takeIf { !it.isRecycled }?.recycle()
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun run(operation: Operation) {
        val state = _state.value
        val items = state.selected
        if (items.isEmpty()) {
            notify("先选中要处理的文件")
            return
        }
        _state.value = state.copy(sheetOpen = false, running = Running(operation.label, 0, items.first().name))
        viewModelScope.launch {
            val result = runCatching {
                runner.run(items, operation) { percent, label ->
                    _state.value = _state.value.copy(running = _state.value.running?.copy(percent = percent, current = label))
                }
            }
            val failure = result.exceptionOrNull()
            val produced = result.getOrNull()?.outputs.orEmpty()
            val errors = result.getOrNull()?.failures.orEmpty() +
                listOfNotNull(failure?.message?.let { "整批中断：$it" })
            _state.value = _state.value.refreshed().copy(
                running = null,
                selection = produced.map { it.id }.toSet(),
            )
            notify(
                when {
                    produced.isNotEmpty() && errors.isEmpty() -> "完成 ${produced.size} 个结果，可直接再加工"
                    produced.isNotEmpty() -> "完成 ${produced.size} 个，${errors.size} 个失败：${errors.first()}"
                    else -> "没做成：" + (errors.firstOrNull() ?: "未知原因")
                },
            )
        }
    }

    /** 导出到用户选的文件夹：有选中就只导选中，否则导全部。 */
    fun publish(treeUri: Uri) = viewModelScope.launch {
        val onlySelected = _state.value.selection.isNotEmpty()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val source = if (onlySelected) _state.value.selected else _state.value.items
        if (source.isEmpty()) {
            notify("没有可导出的文件")
            return@launch
        }
        val tree = DocumentFile.fromTreeUri(getApplication(), treeUri)
        if (tree == null) {
            notify("那个文件夹打不开，换一个试试")
            return@launch
        }
        var done = 0
        val failed = ArrayList<String>()
        source.forEach { item ->
            runCatching { copyTo(item, tree) }
                .onSuccess { done++ }
                .onFailure { failed += "${item.name}：${it.message}" }
        }
        notify(
            when {
                failed.isEmpty() -> "已导出 $done 个文件"
                else -> "导出 $done 个，失败 ${failed.size} 个：${failed.first()}"
            },
        )
    }

    private fun copyTo(item: WorkItem, tree: DocumentFile) {
        val created = tree.createFile(mimeOf(item), item.name) ?: error("无法在目标文件夹创建文件")
        getContent().openOutputStream(created.uri, "w").use { output ->
            requireNotNull(output) { "无法写入这个文件夹" }
            item.file.inputStream().use { it.copyTo(output) }
        }
    }

    private fun mimeOf(item: WorkItem): String = when (item.kind) {
        FileKind.Pdf -> "application/pdf"
        FileKind.Gif -> "image/gif"
        FileKind.Png -> "image/png"
        FileKind.Jpeg -> "image/jpeg"
        FileKind.WebP -> "image/webp"
        FileKind.Bmp -> "image/bmp"
        FileKind.Heic -> "image/heic"
        FileKind.Avif -> "image/avif"
        FileKind.Mp4 -> "video/mp4"
        FileKind.WebM -> "video/webm"
        FileKind.Mkv -> "video/x-matroska"
        FileKind.QuickTime -> "video/quicktime"
        FileKind.Zip -> "application/zip"
        FileKind.Unknown -> "*/*"
    }

    private fun WorkbenchState.find(id: Long) = items.firstOrNull { it.id == id }

    private fun WorkbenchState.refreshed(): WorkbenchState {
        val fresh = workspace.list()
        val alive = fresh.map { it.id }.toSet()
        return copy(items = fresh, selection = selection.filter { it in alive }.toSet())
    }

    private fun notify(message: String) {
        _state.value = _state.value.copy(notice = message)
    }

    private fun getContent() = getApplication<Application>().contentResolver
}
