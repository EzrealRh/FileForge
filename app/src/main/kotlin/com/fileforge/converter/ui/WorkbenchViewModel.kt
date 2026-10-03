package com.fileforge.converter.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fileforge.core.model.DayGroup
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.update.AssetDigest
import com.fileforge.core.update.ReleaseAsset
import com.fileforge.core.update.RemoteRelease
import com.fileforge.converter.data.Batch
import com.fileforge.converter.data.MediaEntry
import com.fileforge.converter.data.MediaLibrary
import com.fileforge.converter.data.MediaStorePublisher
import com.fileforge.converter.data.UpdateRepository
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.FileDetail
import com.fileforge.converter.engine.MetaReader
import com.fileforge.converter.engine.OperationRunner
import java.io.File
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

/** 应用内更新面板的状态机。 */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class Failed(val message: String) : UpdateUi
    data class UpToDate(val versionName: String) : UpdateUi
    data class Available(
        val release: RemoteRelease,
        val asset: ReleaseAsset,
        val percent: Int = -1,
        val downloaded: File? = null,
    ) : UpdateUi {
        val downloading: Boolean get() = percent >= 0 && downloaded == null
    }
}

data class WorkbenchState(
    val items: List<WorkItem> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val filter: KindFilter = KindFilter.All,
    /** 列表按日期分组（今天/昨天/本周/更早）。 */
    val groupByDate: Boolean = false,
    /** 列表按批次分组：同一次导入的、以及这批文件转出来的结果，算一组。 */
    val groupByBatch: Boolean = false,
    val batches: Map<Long, Batch> = emptyMap(),
    val running: Running? = null,
    val notice: Notice? = null,
    val sheetOpen: Boolean = false,
    val detailId: Long? = null,
    val detail: FileDetail? = null,
    val detailLoading: Boolean = false,
    val preview: Bitmap? = null,
    /** 右上角删除要干掉的那些 id；空集表示当前没有待确认的删除。 */
    val pendingDelete: Set<Long> = emptySet(),
    val mediaPickerOpen: Boolean = false,
    val media: MediaUi = MediaUi.Idle,
    val updateSheetOpen: Boolean = false,
    val update: UpdateUi = UpdateUi.Idle,
    val updateToken: String = "",
    /** 工作台的磁盘装载是否完成：装载前的"空"不是真的空，界面据此区分空态与加载中。 */
    val loaded: Boolean = false,
    /** 批量重命名对话框开着吗；要改的是选中的那批。 */
    val renameOpen: Boolean = false,
) {
    val selected: List<WorkItem> get() = items.filter { it.id in selection }
    val visible: List<WorkItem> get() = items.filter { filter.matches(it.kind) }

    /**
     * 按日期分好组。[visible] 本来就是新→旧，所以 groupBy 出来的组序和组内序都对，
     * 不用再排一次。[now] 由调用方传，测试里能固定住。
     */
    fun groupedByDate(now: Long): List<Pair<DayGroup, List<WorkItem>>> =
        visible.groupBy { DayGroup.of(it.addedAt, now) }.map { (group, list) -> group to list }

    /** 按批次分组，新批次在前（[visible] 本来就是新→旧）。 */
    fun groupedByBatch(): List<Pair<Batch?, List<WorkItem>>> =
        visible.groupBy { it.groupId }.map { (id, list) -> batches[id] to list }
    val busy: Boolean get() = running != null
    val allVisibleSelected: Boolean get() = visible.isNotEmpty() && visible.all { it.id in selection }
}

class WorkbenchViewModel(app: Application) : AndroidViewModel(app) {

    private val workspace = Workspace(app)
    private val runner = OperationRunner(app, workspace)
    private val meta = MetaReader()
    private val gallery = MediaStorePublisher(app)
    private val mediaLibrary = MediaLibrary(app)
    private val updates = UpdateRepository(app)
    private var noticeSeq = 0

    private val _state = MutableStateFlow(WorkbenchState())
    val state = _state.asStateFlow()

    init {
        // 工作台的目录扫描与逐个文件嗅探是最贵的一笔启动 I/O：挪到后台预热，
        // 装载完再把列表交回状态流。以前这整段（连清 staging）都跑在主线程构造里
        viewModelScope.launch(Dispatchers.IO) {
            workspace.prewarm()
            _state.update { it.refreshed().copy(loaded = true) }
        }
    }

    val phoneSaveSupported: Boolean get() = gallery.supported

    /** 一次选中的算一批：整批交给 Workspace，产物以后也归这批。 */
    fun import(uris: List<Uri>) = viewModelScope.launch(Dispatchers.IO) {
        val added = runCatching { workspace.importAll(uris, getContent()) }
            .getOrElse { error ->
                notify("没能加入：" + (error.message ?: error.javaClass.simpleName))
                return@launch
            }
        _state.update { current ->
            val fresh = current.refreshed()
            fresh.copy(selection = fresh.selection + added.map { it.id }.toSet())
        }
        if (added.size < uris.size) notify("${uris.size - added.size} 个文件没能加入")
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

    /** 右上角删除：有选中就只删选中，一个都没选就是清空整个工作台。 */
    fun askDelete() {
        if (_state.value.busy) {
            notify("正在处理，先等这一批跑完")
            return
        }
        val state = _state.value
        val targets = state.selection.ifEmpty { state.items.map { it.id }.toSet() }
        if (targets.isEmpty()) {
            notify("工作台已经是空的了")
            return
        }
        _state.update { it.copy(pendingDelete = targets) }
    }

    fun dismissDelete() {
        _state.update { it.copy(pendingDelete = emptySet()) }
    }

    fun openRename() {
        _state.update { it.copy(renameOpen = true) }
    }

    fun dismissRename() {
        _state.update { it.copy(renameOpen = false) }
    }

    /**
     * 批量重命名：选中的（新→旧顺序）按「前缀 + 补零序号」改名，扩展名原样保留。
     * 名字冲突由 Workspace 的避让规则兜底（自动编号），不会互相覆盖。
     */
    fun confirmRename(prefix: String, startNumber: Int, digits: Int) {
        _state.update { it.copy(renameOpen = false) }
        val targets = _state.value.selected
        if (targets.isEmpty()) {
            notify("先选中要重命名的文件")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var number = startNumber.coerceAtLeast(0)
            var failed = 0
            targets.forEach { item ->
                runCatching {
                    val ext = item.name.substringAfterLast('.', "")
                    val padded = number.toString().padStart(digits.coerceIn(1, 6), '0')
                    workspace.rename(item, prefix + padded + if (ext.isBlank()) "" else ".$ext")
                    number++
                }.onFailure { failed++ }
            }
            _state.update { it.refreshed() }
            notify(
                when {
                    failed == 0 -> "改好 ${targets.size} 个名字"
                    failed == targets.size -> "一个都没改成，文件可能被占用"
                    else -> "改好 ${targets.size - failed} 个，$failed 个没改成"
                },
            )
        }
    }

    fun confirmDelete() {
        val state = _state.value
        val ids = state.pendingDelete
        if (ids.isEmpty()) return
        if (state.busy) {
            notify("正在处理，先等这一批跑完")
            return
        }
        recyclePreview()
        val doomed = state.items.filter { it.id in ids }
        viewModelScope.launch(Dispatchers.IO) {
            doomed.forEach { workspace.remove(it) }
            _state.update { current ->
                current.refreshed().copy(pendingDelete = emptySet(), selection = current.selection - ids)
            }
            notify("删掉 ${doomed.size} 个")
        }
    }

    fun remove(id: Long) {
        if (_state.value.busy) {
            notify("正在处理，先等这一批跑完再删")
            return
        }
        _state.value.items.firstOrNull { it.id == id }?.let { item ->
            viewModelScope.launch(Dispatchers.IO) { workspace.remove(item) }
        }
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

    /** 两种分组互斥，开一个就关另一个。 */
    fun toggleDateGroup() {
        _state.update { current -> current.copy(groupByDate = !current.groupByDate, groupByBatch = false) }
    }

    fun toggleBatchGroup() {
        _state.update { current -> current.copy(groupByBatch = !current.groupByBatch, groupByDate = false) }
    }

    /** 点组头：把这一批（当前筛选下看得见的）整批选中或取消。 */
    fun selectBatch(groupId: Long) {
        _state.update { current ->
            val inBatch = current.visible.filter { it.groupId == groupId }.map { it.id }.toSet()
            if (inBatch.isEmpty()) return@update current
            val allOn = inBatch.all { it in current.selection }
            current.copy(
                selection = if (allOn) current.selection - inBatch else current.selection + inBatch,
            )
        }
    }

    /**
     * 交给系统分享面板：社交 App、蓝牙、邮件都能收。
     * 文件在应用私有目录，必须经 FileProvider 换成 content:// 并临时授权，直接给路径对方读不到。
     */
    fun share(items: List<WorkItem>) {
        if (items.isEmpty()) {
            notify("先选中要分享的文件")
            return
        }
        val app = getApplication<Application>()
        val uris = ArrayList<Uri>()
        items.forEach { item ->
            runCatching { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", item.file) }
                .onSuccess { uris += it }
                .onFailure { notify("${item.name} 拿不到分享地址：${it.message ?: it.javaClass.simpleName}") }
        }
        if (uris.isEmpty()) return
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (uris.size == 1) items.first().kind.mimeType else "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            app.startActivity(
                Intent.createChooser(intent, "分享 ${uris.size} 个文件")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { notify("这台机器上没有能接收文件的 App") }
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
        // 底栏那颗「转换」在忙时会禁用，但详情页的入口（就处理这个文件）不在：
        // 不拦一下，长任务没跑完时能再开一个，两边一起写工作台互相踩
        if (_state.value.busy) {
            notify("正在处理，先等这一批跑完")
            return
        }
        rememberOperation(operation)
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
            // 取消不是失败：往下走会把半批产物当"没产出"丢掉，也不该再发"整批中断"的假通知
            val crash = result.exceptionOrNull()
            if (crash is kotlinx.coroutines.CancellationException) throw crash
            val report = result.getOrNull()
            val produced = report?.outputs.orEmpty()
            val outcome = if (produced.isEmpty()) {
                PublishOutcome(emptyMap(), emptyList(), null)
            } else {
                publish(produced)
            }
            val placed = outcome.paths
            val publishFailed = outcome.failures
            val errors = report?.failures.orEmpty().map { (name, why) -> "$name：$why" } +
                listOfNotNull(crash?.message?.let { "整批中断：$it" }) +
                publishFailed.map { "没放进手机存储：$it" }
            _state.update { current ->
                current.refreshed().copy(running = null, selection = produced.map { it.id }.toSet())
            }
            val where = placed.values.firstOrNull()?.substringBeforeLast('/')
            notify(
                when {
                    produced.isNotEmpty() && errors.isEmpty() ->
                        "完成 ${produced.size} 个结果" + (where?.let { "，已放到 $it" } ?: "，可直接再加工")
                    produced.isNotEmpty() -> "完成 ${produced.size} 个，失败 ${errors.size} 个：" + summarize(errors)
                    errors.isEmpty() -> "没产出结果文件"
                    else -> "没做成：" + summarize(errors)
                } + (outcome.hint?.let { "；$it" } ?: ""),
            )
        }
    }

    /** 顶栏按钮：把选中的（没选就是全部）再往手机存储放一遍。 */
    fun saveToPhone() = viewModelScope.launch(Dispatchers.IO) {
        val source = _state.value.selected.ifEmpty { _state.value.items }
        if (source.isEmpty()) {
            notify("工作台里还没有文件")
            return@launch
        }
        val outcome = publish(source)
        if (outcome.paths.isNotEmpty()) {
            // 报**实际**落点：没开顶层权限时成品在 Download/文件工坊，别说成顶层目录误导人
            val where = outcome.paths.values.firstOrNull()?.substringBeforeLast('/') ?: gallery.locationLabel
            notify("已存到 $where，共 ${source.size} 个" + (outcome.hint?.let { "；$it" } ?: ""))
        }
        if (outcome.failures.isNotEmpty()) notify("有 ${outcome.failures.size} 个没放进去：" + summarize(outcome.failures))
    }

    /**
     * 复制到 /storage/emulated/0/文件工坊 并记住位置。转换一完成就自动来一次，
     * 省得用户还得记得点"存到手机"；放不进去也不影响工作台里那份继续加工。
     *
     * 没放进去的那几个**不在这里报**：自动发布（run）收尾还有一条总结通知，先报一条
     * 马上就被盖掉，用户根本看不见。交回给调用方，跟结果合并成一条再说。
     */
    private suspend fun publish(items: List<WorkItem>): PublishOutcome {
        if (!gallery.supported) {
            notify("这台系统的存储接口太老，用右上角「导出」选文件夹")
            return PublishOutcome(emptyMap(), emptyList(), null)
        }
        val result = withContext(Dispatchers.IO) { runCatching { gallery.save(items) } }
            .getOrElse { error ->
                notify("放到手机存储失败：${error.message ?: error.javaClass.simpleName}")
                return PublishOutcome(emptyMap(), emptyList(), null)
            }
        result.paths.forEach { (name, path) ->
            items.firstOrNull { it.name == name }?.let { item -> workspace.markShared(item, path) }
        }
        val hint = offerTopLevelHint(result)
        return PublishOutcome(result.paths, result.failures, hint)
    }

    /** 一次发布的结果：落点、没放进去的（调用方并进通知说）、以及要不要带一句顶层目录的说明。 */
    private class PublishOutcome(
        val paths: Map<String, String>,
        val failures: List<String>,
        val hint: String?,
    )

    /**
     * 成品落到 Download 而不是顶层目录时，说明一句"怎么去顶层"。
     *
     * 只作为**通知里的一句话**存在，不再弹模态对话框：那个弹窗每个会话都来一次，
     * 看着像在索要"所有文件访问权限"，其实不开也一切可用。一台设备只提一次（记进偏好）；
     * 已经开了权限、或这批本来就落在顶层的，什么都不说。
     */
    private fun offerTopLevelHint(result: MediaStorePublisher.Result): String? {
        if (result.topLevel || result.paths.isEmpty()) return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || gallery.topLevelGranted) return null
        val prefs = getApplication<Application>().getSharedPreferences("fileforge_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("topLevelHintShown", false)) return null
        prefs.edit().putBoolean("topLevelHintShown", true).apply()
        return "想去存储根目录的「文件工坊」，可在系统设置里给本应用开「所有文件访问权限」（不开不影响使用）"
    }

    /**
     * 用其他应用打开这个文件：PDF 交给阅读器、视频交给播放器。
     * 系统里没有能接的 App 就退回分享面板，不给一个"点了没反应"。
     */
    fun openWith(item: WorkItem) {
        val app = getApplication<Application>()
        val uri = runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", item.file)
        }.getOrElse {
            notify("${item.name} 拿不到打开地址：${it.message ?: it.javaClass.simpleName}")
            return
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.kind.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val handler = runCatching { view.resolveActivity(app.packageManager) }.getOrNull()
        if (handler == null) {
            notify("${item.name} 没有能直接打开它的应用，改用分享")
            share(listOf(item))
            return
        }
        runCatching { app.startActivity(Intent.createChooser(view, "打开 ${item.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { notify("打不开：${it.message ?: it.javaClass.simpleName}") }
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
        val created = tree.createFile(item.kind.mimeType, item.name) ?: error("无法在目标文件夹创建文件")
        getContent().openOutputStream(created.uri, "w").use { output ->
            requireNotNull(output) { "无法写入这个文件夹" }
            item.file.inputStream().use { it.copyTo(output) }
        }
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
            batches = workspace.batches(),
            selection = selection.filter { it in alive }.toSet(),
            detailId = if (detailGone) null else detailId,
            detail = if (detailGone) null else detail,
            detailLoading = if (detailGone) false else detailLoading,
        )
    }

    private fun summarize(errors: List<String>): String =
        if (errors.size <= 2) errors.joinToString("；")
        else "${errors.take(2).joinToString("；")} 等共 ${errors.size} 处"

    val localVersionName: String get() = updates.localVersionName()

    fun openUpdateSheet() {
        _state.update { it.copy(updateSheetOpen = true, updateToken = updates.token) }
        checkForUpdate()
    }

    fun closeUpdateSheet() {
        _state.update { it.copy(updateSheetOpen = false) }
    }

    /** 输入框里改字只动界面状态，落盘要等"存到本机"那一下。 */
    fun setUpdateTokenDraft(value: String) {
        _state.update { it.copy(updateToken = value) }
    }

    /** token 只写进本机 SharedPreferences，跟着这个 App 卸载就没了。 */
    fun saveUpdateToken() {
        updates.token = _state.value.updateToken
        checkForUpdate()
    }

    /** 最近用过的操作（新→旧，最多 8 个）：操作面板据此把常用的排前面。 */
    fun recentOperationNames(): List<String> =
        getApplication<Application>().getSharedPreferences("fileforge_ui", Context.MODE_PRIVATE)
            .getString("recentOps", "")?.split(',').orEmpty().filter { it.isNotBlank() }

    private fun rememberOperation(operation: Operation) {
        val name = operation::class.simpleName ?: return
        val prefs = getApplication<Application>().getSharedPreferences("fileforge_ui", Context.MODE_PRIVATE)
        val updated = recentOperationNames().toMutableList().apply {
            removeAll { it == name }
            add(0, name)
        }.take(8)
        prefs.edit().putString("recentOps", updated.joinToString(",")).apply()
    }

    /** 别的 App 分享进来的纯文本：落成工作台里的 txt，接着就能转格式。 */
    fun importPlainText(text: String, title: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(getApplication<Application>().cacheDir, "shared-${System.nanoTime()}.txt")
                tmp.writeText(text)
                val name = OutputNaming.sanitize((title?.take(40) ?: "分享文本").ifBlank { "分享文本" }) + ".txt"
                workspace.adopt(tmp, name, null)
            }.onSuccess { item ->
                _state.update { current -> current.refreshed().copy(selection = current.selection + item.id) }
                notify("分享的文字已进工作台：${item.name}")
            }.onFailure { error ->
                notify("没能收下分享的文字：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun checkForUpdate() = viewModelScope.launch(Dispatchers.IO) {
        _state.update { it.copy(update = UpdateUi.Checking) }
        val release = runCatching { updates.check() }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            // 失败原因要原样贴出来：私有仓库、token 无效、网络被掐，三种处理办法不一样
            _state.update { current -> current.copy(update = UpdateUi.Failed(error.message ?: error.javaClass.simpleName)) }
            return@launch
        }
        val apk = release.pickApk()
        _state.update { current ->
            current.copy(
                update = when {
                    apk == null -> UpdateUi.Failed("最新一版里没有可安装的安装包")
                    release.isNewerThan(updates.localVersion()) -> UpdateUi.Available(release, apk)
                    else -> UpdateUi.UpToDate(updates.localVersionName())
                },
            )
        }
    }

    fun downloadUpdate() {
        val available = _state.value.update as? UpdateUi.Available ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(update = available.copy(percent = 0, downloaded = null)) }
            val file = runCatching {
                updates.download(
                    available.asset,
                    AssetDigest.fromNotes(available.release.notes, available.asset.name),
                ) { percent, _, _ ->
                    _state.update { current ->
                        val now = current.update as? UpdateUi.Available ?: return@update current
                        current.copy(update = now.copy(percent = percent))
                    }
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { current ->
                    val now = current.update as? UpdateUi.Available ?: return@update current
                    current.copy(update = now.copy(percent = -1))
                }
                notify("下载失败：${error.message ?: error.javaClass.simpleName}")
                return@launch
            }
            _state.update { current ->
                val now = current.update as? UpdateUi.Available ?: return@update current
                current.copy(update = now.copy(percent = 100, downloaded = file))
            }
            notify("安装包下好了，点「安装」就走系统安装器")
        }
    }

    /** 真正装包的是系统安装器，我们只把文件递过去；某些定制系统还会再多问几道。 */
    fun installUpdate() {
        val file = (_state.value.update as? UpdateUi.Available)?.downloaded ?: return
        val app = getApplication<Application>()
        if (updates.needsInstallPermission()) {
            runCatching { app.startActivity(updates.installPermissionIntent()) }
                .onFailure { notify("这台机器不让去设置里授权") }
            notify("先允许「安装未知应用」，回来再点一次安装")
            return
        }
        runCatching { app.startActivity(updates.installIntent(file)) }
            .onFailure { notify("没有能安装 apk 的程序，去系统设置里手动装") }
    }

    private fun notify(message: String) {
        noticeSeq++
        _state.update { it.copy(notice = Notice(message, noticeSeq)) }
    }

    private fun getContent() = getApplication<Application>().contentResolver
}
