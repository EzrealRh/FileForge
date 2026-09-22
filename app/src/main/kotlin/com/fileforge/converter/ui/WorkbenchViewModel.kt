package com.fileforge.converter.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.converter.data.MediaEntry
import com.fileforge.converter.data.MediaLibrary
import com.fileforge.converter.data.MediaStorePublisher
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.FileDetail
import com.fileforge.converter.engine.MetaReader
import com.fileforge.converter.engine.OperationRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class KindFilter(val label: String, val matches: (FileKind) -> Boolean) {
    All("全部", { true }),
    Image("图片", { it.isImage }),
    Pdf("PDF", { it == FileKind.Pdf }),
    Video("视频", { it.isVideo }),
}

data class Running(val title: String, val percent: Int, val current: String)

/** 相册选择器的状态。partialAccess 表示系统只给了"部分照片"，只能看到用户挑过的那些。 */
sealed interface MediaUi {
    data object Idle : MediaUi
    data object Loading : MediaUi
    data class Ready(val entries: List<MediaEntry>, val partialAccess: Boolean) : MediaUi
    data class Empty(val partialAccess: Boolean) : MediaUi
    data class Denied(val message: String) : MediaUi
}

/** 带序号的提示：否则连着两条同文案的会被 Snackbar 吞掉，用户以为没执行。 */
data class Notice(val text: String, val seq: Int)

data class WorkbenchState(
    val items: List<WorkItem> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val filter: KindFilter = KindFilter.All,
    val running: Running? = null,
    val notice: Notice? = null,
    val sheetOpen: Boolean = false,
    val detailId: Long? = null,
    val detail: FileDetail? = null,
    val detailLoading: Boolean = false,
    val preview: Bitmap? = null,
    val confirmClear: Boolean = false,
    val mediaPickerOpen: Boolean = false,
    val media: MediaUi = MediaUi.Idle,
) {
    val selected: List<WorkItem> get() = items.filter { it.id in selection }
    val visible: List<WorkItem> get() = items.filter { filter.matches(it.kind) }
    val busy: Boolean get() = running != null
    val allVisibleSelected: Boolean get() = visible.isNotEmpty() && visible.all { it.id in selection }
}

class WorkbenchViewModel(app: Application) : AndroidViewModel(app) {

    private val workspace = Workspace(app)
    private val runner = OperationRunner(app, workspace)
    private val meta = MetaReader()
    private val gallery = MediaStorePublisher(app)
    private val mediaLibrary = MediaLibrary(app)
    private var noticeSeq = 0

    init {
        workspace.purgeStaging()
    }

    private val _state = MutableStateFlow(WorkbenchState(items = workspace.list()))
    val state = _state.asStateFlow()

    val phoneSaveSupported: Boolean get() = gallery.supported

    fun import(uris: List<Uri>) = viewModelScope.launch(Dispatchers.IO) {
        val added = ArrayList<WorkItem>()
        val failures = ArrayList<String>()
        uris.forEach { uri ->
            runCatching { workspace.import(uri, getContent()) }
                .onSuccess { added += it }
                .onFailure { failures += "${uri.lastPathSegment ?: "文件"}：${it.message}" }
        }
        _state.update { current ->
            val fresh = current.refreshed()
            fresh.copy(selection = fresh.selection + added.map { it.id }.toSet())
        }
        if (failures.isNotEmpty()) notify("${failures.size} 个文件没能加入：" + failures.first())
    }

    fun toggle(id: Long) {
        _state.update { current ->
            val picked = current.selection
            current.copy(selection = if (id in picked) picked - id else picked + id)
        }
    }

    /** 33+ 用细分媒体权限，32 及以下只有 READ_EXTERNAL_STORAGE。 */
    fun mediaPermissions(): Array<String> = when {
        android.os.Build.VERSION.SDK_INT >= 34 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMediaPermission(): Boolean = mediaPermissions().any {
        androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun mediaPermissionDenied() {
        _state.update {
            it.copy(media = MediaUi.Denied("没有读到媒体库的权限，所以列不出相册。"))
        }
    }

    fun openMediaPicker() {
        _state.update { it.copy(mediaPickerOpen = true, media = MediaUi.Loading) }
        viewModelScope.launch(Dispatchers.IO) {
            val entries = runCatching { mediaLibrary.load() }.getOrElse { error ->
                _state.update { current ->
                    if (current.mediaPickerOpen) {
                        current.copy(media = MediaUi.Denied("读媒体库失败：${error.message ?: error.javaClass.simpleName}"))
                    } else {
                        current
                    }
                }
                return@launch
            }
            val partial = partialAccessOnly()
            _state.update { current ->
                if (!current.mediaPickerOpen) {
                    current
                } else if (entries.isEmpty()) {
                    current.copy(media = MediaUi.Empty(partial))
                } else {
                    current.copy(media = MediaUi.Ready(entries, partial))
                }
            }
        }
    }

    fun closeMediaPicker() {
        _state.update { it.copy(mediaPickerOpen = false) }
    }

    suspend fun mediaThumbnail(entry: MediaEntry): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) { mediaLibrary.thumbnail(entry) }

    /** Android 14 的"部分照片"模式：只给了 USER_SELECTED，没给整库读权限。 */
    private fun partialAccessOnly(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 34) return false
        val app = getApplication<Application>()
        val all = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val selected = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return !all && selected
    }

    /** 已经全选就清空，否则全选当前筛出来的。 */
    fun toggleSelectAll() {
        _state.update { current ->
            current.copy(
                selection = if (current.allVisibleSelected) emptySet() else current.visible.map { it.id }.toSet(),
            )
        }
    }

    fun askClear() {
        if (_state.value.busy) {
            notify("正在处理，先等这一批跑完")
            return
        }
        _state.update { it.copy(confirmClear = true) }
    }

    fun dismissClear() {
        _state.update { it.copy(confirmClear = false) }
    }

    fun clearAll() {
        if (_state.value.busy) {
            notify("正在处理，先等这一批跑完")
            return
        }
        recyclePreview()
        workspace.clearAll()
        _state.update { WorkbenchState() }
    }

    fun remove(id: Long) {
        if (_state.value.busy) {
            notify("正在处理，先等这一批跑完再删")
            return
        }
        _state.value.items.firstOrNull { it.id == id }?.let { workspace.remove(it) }
        val staleDetail = _state.value.detailId == id
        if (staleDetail) recyclePreview()
        _state.update { current ->
            val fresh = current.refreshed()
            if (staleDetail) {
                fresh.copy(detailId = null, detail = null, preview = null, detailLoading = false)
            } else {
                fresh
            }
        }
    }

    fun filter(kind: KindFilter) {
        _state.update { it.copy(filter = kind) }
    }

    fun openSheet(open: Boolean) {
        _state.update { it.copy(sheetOpen = open) }
    }

    /** 从详情页直接进参数面板：顺手把这个文件选中，省得退回列表再勾一次。 */
    fun openSheetFor(id: Long) {
        _state.update { it.copy(detailId = null, detail = null, preview = null, detailLoading = false, selection = setOf(id), sheetOpen = true) }
        recyclePreview()
    }

    fun openDetail(id: Long) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        recyclePreview()
        _state.update {
            it.copy(
                detailId = id,
                detail = FileDetail(item.name, item.extension.uppercase(), item.sizeLabel, "", item.fromOperation, emptyList(), null),
                detailLoading = true,
                preview = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val detail = runCatching { meta.read(item) }.getOrElse { error ->
                FileDetail(item.name, "读取失败", item.sizeLabel, "", item.fromOperation, emptyList(), error.message)
            }
            val preview = if (item.kind.isImage || item.kind.isVideo || item.kind == FileKind.Pdf) meta.preview(item) else null
            _state.update { current ->
                if (current.detailId != id) {
                    preview?.recycle()
                    current
                } else {
                    current.copy(detail = detail, preview = preview, detailLoading = false)
                }
            }
        }
    }

    fun closeDetail() {
        recyclePreview()
        _state.update { it.copy(detailId = null, detail = null, preview = null, detailLoading = false) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun run(operation: Operation) {
        val items = _state.value.selected
        if (items.isEmpty()) {
            notify("先选中要处理的文件")
            return
        }
        _state.update { it.copy(sheetOpen = false, running = Running(operation.label, 0, items.first().name)) }
        viewModelScope.launch {
            val result = runCatching {
                runner.run(items, operation) { percent, label ->
                    _state.update { current -> current.copy(running = current.running?.copy(percent = percent, current = label)) }
                }
            }
            val crash = result.exceptionOrNull()
            val report = result.getOrNull()
            val produced = report?.outputs.orEmpty()
            val errors = report?.failures.orEmpty().map { (name, why) -> "$name：$why" } +
                listOfNotNull(crash?.message?.let { "整批中断：$it" })
            _state.update { current ->
                current.refreshed().copy(running = null, selection = produced.map { it.id }.toSet())
            }
            notify(
                when {
                    produced.isNotEmpty() && errors.isEmpty() -> "完成 ${produced.size} 个结果，可直接再加工"
                    produced.isNotEmpty() -> "完成 ${produced.size} 个，失败 ${errors.size} 个：" + summarize(errors)
                    errors.isEmpty() -> "没产出结果文件"
                    else -> "没做成：" + summarize(errors)
                },
            )
        }
    }

    /** 直接存进相册/文档目录，聊天 App内容平台选图页马上能挑到。 */
    /** 一键把成品落到 Download/文件工坊，不再按类型散进相册各个目录。 */
    fun saveToPhone() = viewModelScope.launch(Dispatchers.IO) {
        val source = _state.value.selected.ifEmpty { _state.value.items }
        if (source.isEmpty()) {
            notify("工作台里还没有文件")
            return@launch
        }
        if (!gallery.supported) {
            notify("这台系统的存储接口太老，改用右上角「导出」选文件夹")
            return@launch
        }
        val result = runCatching { gallery.save(source) }.getOrElse { error ->
            notify("存相册失败：${error.message ?: error.javaClass.simpleName}")
            return@launch
        }
        notify(
            when {
                result.failures.isEmpty() -> "已存到 ${gallery.locationLabel}，共 ${result.saved} 个"
                else -> "存到 ${gallery.locationLabel} ${result.saved} 个，失败 ${result.failures.size} 个：" + summarize(result.failures)
            },
        )
    }

    /** 导出到用户选的文件夹：有选中就只导选中，否则导全部。 */
    fun publish(treeUri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { getContent().takePersistableUriPermission(treeUri, flags) }
        val source = _state.value.selected.ifEmpty { _state.value.items }
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
            runCatching { copyTo(item, tree) }.onSuccess { done++ }.onFailure { failed += "${item.name}：${it.message}" }
        }
        notify(
            when {
                failed.isEmpty() -> "已导出 $done 个文件"
                else -> "导出 $done 个，失败 ${failed.size} 个：" + summarize(failed)
            },
        )
    }

    override fun onCleared() {
        recyclePreview()
        super.onCleared()
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

    private fun recyclePreview() {
        _state.value.preview?.takeIf { !it.isRecycled }?.recycle()
    }

    /** 列表是唯一真相：文件没了就把选中和详情一起收掉，别留悬空 id。 */
    private fun WorkbenchState.refreshed(): WorkbenchState {
        val fresh = workspace.list()
        val alive = fresh.map { it.id }.toSet()
        val detailGone = detailId != null && detailId !in alive
        return copy(
            items = fresh,
            selection = selection.filter { it in alive }.toSet(),
            detailId = if (detailGone) null else detailId,
            detail = if (detailGone) null else detail,
            detailLoading = if (detailGone) false else detailLoading,
        )
    }

    private fun summarize(errors: List<String>): String =
        if (errors.size <= 2) errors.joinToString("；")
        else "${errors.take(2).joinToString("；")} 等共 ${errors.size} 处"

    private fun notify(message: String) {
        noticeSeq++
        _state.update { it.copy(notice = Notice(message, noticeSeq)) }
    }

    private fun getContent() = getApplication<Application>().contentResolver
}
