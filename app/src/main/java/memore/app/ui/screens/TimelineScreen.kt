package memore.app.ui.screens

import androidx.compose.runtime.collectAsState
import memore.app.ui.components.collectKeptAsState
import memore.app.ui.components.MemoreLineInput
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.ui.focus.onFocusChanged
import memore.app.ui.components.OriginSelectorSheet
import memore.app.ui.components.IconSemantic
import androidx.compose.runtime.saveable.rememberSaveable
import memore.app.ui.components.MirrorPasswordField
import memore.app.ui.components.MirrorStackDialog
import androidx.compose.ui.unit.sp
import android.content.Context
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorSheet
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.ui.components.MirrorAlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import memore.app.data.model.Protocol
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.Tag
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.RedactionRepository
import memore.app.utils.EncryptionManager
import memore.app.utils.FeatureConsole
import memore.app.utils.RedactionEngine
import memore.app.data.repository.TagRepository
import memore.app.data.repository.TagGroupRepository
import memore.app.data.voice.RecordVoiceState
import memore.app.data.voice.RecordVoiceStatus
import memore.app.data.voice.VoiceSettingsRepository
import memore.app.data.voice.VoiceTtsRepository
import memore.app.data.worker.AiAnalysisWorker
import memore.app.ui.components.EncryptionHostState
import memore.app.ui.components.FastScrollBar
import memore.app.ui.components.IconAlbum
import memore.app.ui.components.IconClose
import memore.app.ui.components.ProtocolSelectorDialog
import memore.app.ui.components.RecordCard
import memore.app.ui.components.AttachmentListViewer
import memore.app.ui.components.SummaryViewer
import memore.app.ui.components.VoiceRecordSheet
import memore.app.ui.components.EncryptionDialogs
import memore.app.ui.components.TagSelectorDialog
import memore.app.ui.components.rememberEncryptionHost
import memore.app.ui.theme.*
import memore.app.utils.HashUtils
import memore.app.utils.LogUtil
import memore.app.utils.ExportEngine
import memore.app.utils.ExportDelivery
import memore.app.utils.ExportVerifier
import memore.app.utils.formatDuration
import memore.app.utils.formatDateOnly
import memore.app.utils.formatFileSize
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.io.File
import java.util.UUID

/** 正序时最多取这么多条。真正该做的是把排序下推到 SQL（TODO），届时这个常量可以删掉。 */
private const val MAX_ASC_DISPLAY = 400

private const val PAGE_SIZE = 60


/**
 * 「加载更多」是否该出现（2026-11-19 修 bug 后抽出来的纯判断）。
 *
 * 只有时间线分页需要它；筛选/搜索的结果已经全在手里，再显示它只会让人以为
 * "下面还有记录没加载"（实际点下去是把分页补上，卡片才长出标签/协议）。
 */
internal fun shouldShowLoadMore(
    sortAsc: Boolean,
    searchActive: Boolean,
    filtersOn: Boolean,
    loadedCount: Int,
    totalCount: Int
): Boolean = !sortAsc && !searchActive && !filtersOn && totalCount > loadedCount

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TimelineScreen(onSearchFocusChanged: (Boolean) -> Unit = {}, semanticSearch: (suspend (String) -> memore.app.data.vector.RetrievalService.Outcome)? = null, recordRepository: RecordRepository, tagRepository: TagRepository, protocolRepository: ProtocolRepository? = null, refreshKey: Int = 0, onCreateEssay: () -> Unit, onCreateDialogue: () -> Unit, onOpenTagManage: () -> Unit = {}, onOpenProtocolManage: () -> Unit = {}, onOpenConcept: () -> Unit = {},
    onOpenTopics: () -> Unit = {}, onOpenAlbum: () -> Unit = {}, onOpenTrash: () -> Unit = {}, onOpenAnchor: () -> Unit = {}, onOpenRecord: (String) -> Unit = {}, onOpenInsightArchive: () -> Unit = {}, voiceTtsRepository: VoiceTtsRepository? = null, voiceSettingsRepository: VoiceSettingsRepository? = null, redactionRepository: RedactionRepository? = null, tagGroupRepository: TagGroupRepository? = null,
    originNames: Map<String, String> = emptyMap(),
                   /** 分区仓库：「设置区域」面板要用（2026-11-14）。null = 该入口不出现。 */
                   zoneRepository: memore.app.data.repository.ZoneRepository? = null,
                   /** 来源全量（改来源面板要用名字与 id）。 */
                   origins: List<memore.app.data.model.Origin> = emptyList(),
                   /** 把一批记录改成同一个来源；null = 不标。 */
                   onSetOriginFor: (suspend (List<String>, String?) -> Unit)? = null,
                   /** 真草稿：入口在「新建」菜单里（与随笔/对话录并列的第三个选项）。 */
                   onOpenDraft: (() -> Unit)? = null,
                   /** 转录来源：从记录卡回到那本草稿（第二参 = 那块，进草稿后滚到它）。 */
                   onOpenDraftFromRecord: ((Long, Long?) -> Unit)? = null,
                   /** ⋮ → 进「更多」页。 */
                   onOpenMore: () -> Unit = {},
                   /** 条件行里的「筛选 N」→ 直接进筛选页（2026-11-15）。 */
                   onOpenFilter: () -> Unit = {},
                   enterBatch: Boolean = false,
                   onBatchEntered: () -> Unit = {},
                   /** 状态对象可由外部持有：筛选整页要和时间线共用同一份。 */
                   state: TimelineScreenState? = null) {
    val s = state ?: remember { TimelineScreenState() }
    val enterCtx = androidx.compose.ui.platform.LocalContext.current

    // ---- v5 门闩：时间线顶部的常驻提示（2026-11-16）----
    // 两种状态共用一条提示，不弹模态框：
    //   · 还有旧密文 → 「旧加密记录待迁移」
    //   · 锁着但没设密码（导入到新机）→ 「有 N 条锁着，设置密码后可查看」
    var lockPendingCount by remember { mutableIntStateOf(0) }
    var lockNeedsMigration by remember { mutableIntStateOf(0) }
    var lockPromptMode by remember { mutableStateOf<String?>(null) } // "migrate" / "setup"
    var lockPw by remember { mutableStateOf("") }
    var lockErr by remember { mutableStateOf<String?>(null) }
    var lockBusy by remember { mutableStateOf(false) }

    LaunchedEffect(enterBatch) {
        if (enterBatch) {
            s.batchMode = true
            s.selectedIds = emptySet()
            // 进批量音效（从「更多」页进来时之前漏掉了）
            UiSoundPlayer.playBatchEnter(enterCtx)
            onBatchEntered()
        }
    }
    val scope = rememberCoroutineScope(); val context = LocalContext.current; val sheetState = rememberModalBottomSheetState(); val listState = rememberLazyListState()

    // v5 门闩：状态刷新（2026-11-16）——放在 scope 之后，因为它要用 scope.launch
    fun refreshLockState() {
        scope.launch {
            runCatching { memore.app.utils.EncryptionManager.refreshConfigStatus() }
            lockNeedsMigration = runCatching { recordRepository.cipherRecordCount() }.getOrDefault(0)
            lockPendingCount = if (!memore.app.utils.EncryptionManager.isConfigured()) {
                runCatching { recordRepository.getLockedRecords().size }.getOrDefault(0)
            } else 0
        }
    }
    LaunchedEffect(refreshKey) { refreshLockState() }
    // 「设置区域」状态（2026-11-14）：可选区域 + 每张卡已属分区
    var zoneOptions by remember { mutableStateOf<List<memore.app.data.model.Zone>>(emptyList()) }
    var zoneIdsByRecord by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    // 批量洞察（2026-11-08）
    val insightScope = rememberCoroutineScope()
    val insightSvc = remember { memore.app.data.vector.InsightService(context, recordRepository) }
    var showBatchInsight by remember { mutableStateOf(false) }
    var batchInsightRunning by remember { mutableStateOf(false) }
    var batchInsightError by remember { mutableStateOf<String?>(null) }
    var batchInsightClaims by remember { mutableStateOf<List<memore.app.data.vector.InsightService.Claim>>(emptyList()) }
    var batchInsightTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    val batchInsightModeState = memore.app.ui.components.rememberInsightMode()
    var batchInsightMode by batchInsightModeState
    var batchInsightRan by remember { mutableStateOf(false) }

    // 「另存为」批量导出：SAF 选位置 → 直接写目标流 → 回读校验；校验不过就删掉目标文件。
    // 交付方式读全局偏好（设置→导出 里选），与「导出记忆包」那条共用一个记忆。
    val batchSaveAsContract = remember { ActivityResultContracts.CreateDocument(ExportDelivery.mimeOf(false)) }
    val batchSaveAsLauncher = rememberLauncherForActivityResult(batchSaveAsContract) { uri ->
        if (uri == null) {
            context.memoreToast("已取消", Toast.LENGTH_SHORT)
        } else {
            scope.launch {
                val ok = try {
                    val out = context.contentResolver.openOutputStream(uri)
                    if (out == null) { context.memoreToast("无法打开目标文件", Toast.LENGTH_LONG); false }
                    else out.use {
                        runBatchExport(
                            context, s, recordRepository, tagRepository, protocolRepository,
                            redactionRepository, s.pendingSaveAsDecrypt, output = it
                        )
                    }
                } catch (e: Exception) {
                    context.memoreToast("导出失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG); false
                }
                if (!ok) { UiSoundPlayer.playError(context); return@launch }
                val verify = try {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) ExportVerifier.Result.Failed("读不回刚保存的文件")
                    else input.use { ExportVerifier.verifyJson(it) }
                } catch (e: Exception) {
                    ExportVerifier.Result.Failed(e.javaClass.simpleName)
                }
                when (verify) {
                    is ExportVerifier.Result.Ok -> {
                        context.memoreToast("已保存并校验通过：${verify.count} 条记录（v${verify.version ?: "?"}）", Toast.LENGTH_LONG)
                        UiSoundPlayer.playExportDone(context)
                    }
                    is ExportVerifier.Result.Failed -> {
                        ExportVerifier.deleteQuietly(context, uri)
                        context.memoreToast("校验失败，已删除目标文件：${verify.reason}", Toast.LENGTH_LONG)
                        UiSoundPlayer.playError(context)
                    }
                }
            }
        }
    }
    // 解密密码框在 TimelineDialogs 里（另一个 composable），用状态把"要另存为"这个意图传过来
    LaunchedEffect(s.saveAsArmed) {
        if (s.saveAsArmed) {
            s.saveAsArmed = false
            batchSaveAsLauncher.launch(ExportDelivery.suggestedName(false))
        }
    }
    // 加密交互（临时查看 + 锁定/解锁）共享组件
    val encryptionHost = rememberEncryptionHost(recordRepository, File(context.filesDir, "attachments_tmp"))
    // 密码未配置前，记录菜单不显示加密相关选项（屏幕在 Tab 切换时重建，此处读取即最新值）
    val encryptionAvailable = EncryptionManager.isConfigured()

    // Attachment picker launchers
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val rid = s.pendingAttachRecord ?: return@rememberLauncherForActivityResult; s.pendingAttachRecord = null
        if (uri != null) scope.launch {
            try {
                val fileName = try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use { if (it.moveToFirst()) { val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0) it.getString(idx) else null } else null }
                } catch (_: Exception) { null } ?: "image.jpg"
                val maxSize = 20L * 1024 * 1024
                val size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: return@launch
                if (size > maxSize) { context.memoreToast("文件过大", Toast.LENGTH_SHORT); return@launch }
                val attDir = File(context.filesDir, "attachments/$rid"); attDir.mkdirs()
                val hash = context.contentResolver.openInputStream(uri)?.use { HashUtils.sha256(it) } ?: UUID.randomUUID().toString()
                val ext = fileName.substringAfterLast('.', "bin")
                val destName = "${hash.take(12)}.$ext"
                val destFile = File(attDir, destName)
                if (!destFile.exists()) {
                    context.contentResolver.openInputStream(uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } } ?: return@launch
                }
                recordRepository.saveAttachment(memore.app.data.model.Attachment(recordId = rid, fileName = fileName, filePath = destFile.absolutePath, type = "IMAGE", fileSize = size))
                context.memoreToast("附件已添加", Toast.LENGTH_SHORT)
                UiSoundPlayer.playAttachComplete(context)
            } catch (e: Exception) { context.memoreToast("添加失败: ${e.message}", Toast.LENGTH_SHORT) }
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val rid = s.pendingAttachRecord ?: return@rememberLauncherForActivityResult; s.pendingAttachRecord = null
        if (uri != null) scope.launch {
            try {
                val fileName = try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use { if (it.moveToFirst()) { val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0) it.getString(idx) else null } else null }
                } catch (_: Exception) { null } ?: "audio.m4a"
                val maxSize = 50L * 1024 * 1024
                val size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: return@launch
                if (size > maxSize) { context.memoreToast("文件过大", Toast.LENGTH_SHORT); return@launch }
                val attDir = File(context.filesDir, "attachments/$rid"); attDir.mkdirs()
                val hash = context.contentResolver.openInputStream(uri)?.use { HashUtils.sha256(it) } ?: UUID.randomUUID().toString()
                val ext = fileName.substringAfterLast('.', "bin")
                val destName = "${hash.take(12)}.$ext"
                val destFile = File(attDir, destName)
                if (!destFile.exists()) {
                    context.contentResolver.openInputStream(uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } } ?: return@launch
                }
                recordRepository.saveAttachment(memore.app.data.model.Attachment(recordId = rid, fileName = fileName, filePath = destFile.absolutePath, type = "AUDIO", fileSize = size))
                context.memoreToast("附件已添加", Toast.LENGTH_SHORT)
                UiSoundPlayer.playAttachComplete(context)
            } catch (e: Exception) { context.memoreToast("添加失败: ${e.message}", Toast.LENGTH_SHORT) }
        }
    }

    // AI 任务的活动状态：**以 DB 为唯一真相**（Room Flow）。
    // 原先是内存副本 —— pendingTaskCount 进页面读一次、generatingSummaryId 是单个 id，
    // 于是「数量不会自己减」「只有第一个转圈」「切页面状态全丢」三个问题一起出现。
    // 2026-11-17：**这张表不能用跨页缓存**。
    // activeAiTasks 是"当前在跑的任务"，不是内容列表；缓存会在任务结束后留旧快照，
    // 回到时间线时旧 PROCESSING 先被画出来 → 转圈动画重播约一秒、右上角队列徽标闪一下。
    // 空首帧在这里才是正确的（没有任务就该什么都不显示）。
    val activeAiTasks = recordRepository.observeActiveTasks().collectAsState(initial = emptyList()).value
    // ⚠️ 转圈只给 **PROCESSING** 那条：activeAiTasks 里还包含"排队中/等服务器"的，
    // 直接拿来当转圈集合 → 批量入队十条就十张卡片一起转（2026-10-10 用户报的 bug）。
    // worker 有 Mutex 串行化，任何时刻至多一条在跑。
    // 2026-11-14 修：无 key 的 remember 里包 derivedStateOf 会永不重算（法条 13.2）。// design-law: allow
                // lambda 只在首次组合创建一次，捕获的是那一刻的 activeAiTasks（普通值，不是 State），
                // derivedStateOf 永远不会重算：先空着进来就永远不转，先有任务就永远转下去。
                // 用 remember(activeAiTasks)：列表一变就重算。
                val runningSummaryIds = remember(activeAiTasks) { summarySpinnerIds(activeAiTasks) }
    val searchActive = s.query.isNotBlank()
    // 搜索防抖：300ms 内不再输入才发查询；flatMapLatest 取消过期查询，避免逐键打库
    val searchFlow = remember(s.query, searchActive) {
        if (!searchActive) flowOf(emptyList())
        else flow { emit(s.query) }
            .debounce(300)
            .flatMapLatest { q ->
                val datePattern = Regex("""^\d{4}(-\d{1,2}(-\d{1,2})?)?$""").matchEntire(q.trim())
                if (datePattern != null) {
                    val parts = q.trim().split("-")
                    val range = try {
                        when (parts.size) {
                            1 -> { val ym = YearMonth.of(parts[0].toInt(), 1); Pair(ym.atDay(1), ym.plusYears(1).atDay(1)) }
                            2 -> { val ym = YearMonth.of(parts[0].toInt(), parts[1].toInt()); Pair(ym.atDay(1), ym.plusMonths(1).atDay(1)) }
                            3 -> { val d = LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()); Pair(d, d.plusDays(1)) }
                            else -> null
                        }
                    } catch (_: Exception) { null }
                    if (range != null) {
                        val zone = ZoneId.systemDefault()
                        recordRepository.searchByDateRange(range.first.atStartOfDay(zone).toInstant().toEpochMilli(), range.second.atStartOfDay(zone).toInstant().toEpochMilli())
                    } else recordRepository.searchRecordsFts(q.trim())
                } else recordRepository.searchRecordsFts(q.trim())
            }
    }
    val searchResults = searchFlow.collectKeptAsState("TimelineScreen.searchResults")
    // 语义检索（设计文档 §7）：开关打开后用「向量 + FTS 融合」替代纯 FTS
    var semanticMode by rememberSaveable { mutableStateOf(true) }   // 2026-11-05：默认开语义
    var semanticResults by remember { mutableStateOf<List<memore.app.data.model.Record>>(emptyList()) }
    var semanticWeak by remember { mutableStateOf(false) }
    var semanticLoading by remember { mutableStateOf(false) }
    LaunchedEffect(s.query, semanticMode, semanticSearch) {
        if (!semanticMode || s.query.isBlank() || semanticSearch == null) {
            semanticResults = emptyList(); semanticLoading = false
        } else {
            semanticLoading = true
            kotlinx.coroutines.delay(300)
            val outcome = runCatching { semanticSearch(s.query.trim()) }.getOrNull()
            // 2026-11-23：语义检索走向量库，不会经过 FTS 的 correction_of_id 过滤；
            // 这里补一刀，否则增修仍会作为独立结果出现。
            semanticResults = (outcome?.records ?: emptyList()).filter { it.correctionOfId == null }
            semanticWeak = outcome?.weak ?: false
            semanticLoading = false
        }
    }
    // 联合查询（含增修）：时间线记录 + 标签 + 协议 + 增修，Flow 首帧完整同步
    val detailsFlow = remember(s.displayLimit) { recordRepository.getLatestRecordsWithAmendments(s.displayLimit) }
    // 首帧用缓存（见 TimelineScreenState.cachedDetails）：离开再回来不再先画一帧空列表
    val detailsList by detailsFlow.collectAsState(initial = s.cachedDetails)
    LaunchedEffect(detailsList) { if (detailsList.isNotEmpty() && detailsList !== s.cachedDetails) s.cachedDetails = detailsList }
    val records = remember(detailsList) { detailsList.map { it.record!! } }
    // 2026-11-19：原来用「已加载行数 >= displayLimit」判断还有没有更多 —— 不可靠：
    // 分页里混着增修，父记录数常常小于 displayLimit，于是滚到底也判断不出"还有没加载的"。
    // 改成和全库可分析总数比较。
    var totalActiveCount by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey, s.amendRefreshKey, s.filtersOn, searchActive) {
        totalActiveCount = runCatching { recordRepository.countAnalyzableRecords() }.getOrDefault(0)
    }
    // 刷新不再把已翻的页数打回 15（2026-11-12 性能修）：
    // 原来每建一条新记录 refreshKey 就 +1 → displayLimit 归位 PAGE_SIZE →
    // Flow 被 remember(displayLimit) 重建 → 整个列表从头重查一遍（含标签/协议的 N+1），
    // 观感就是"保存完回来，列表塌下去再重新长出来"。现在保留当前深度，Flow 自然重发即可。
    var prevRefreshKey by remember { mutableIntStateOf(refreshKey) }
    if (refreshKey != prevRefreshKey) {
        prevRefreshKey = refreshKey
    }
    // 正序必须拿到"最早的那批"，原来直接把 displayLimit 设成 100000：
    // 一次点击 = 十万条记录 × (标签 + 协议) 的关联查询，主线程直接冻住（实测有一帧 1.7 秒）。
    // 现在封顶 MAX_ASC_DISPLAY；真正的解法是把排序下推到 SQL（见 TODO）。
    LaunchedEffect(s.sortAsc) {
        s.displayLimit = if (s.sortAsc) MAX_ASC_DISPLAY else PAGE_SIZE
    }
    LaunchedEffect(records.size) { LogUtil.i("AI-7 Flow 更新, size=${records.size}") }

    // 2026-11-15：标签与协议可以同时生效，走仓库的统一入口（内部按"只有标签/只有协议/两者"分支）
    val filterFlow = remember(s.filterTagIds, s.filterProtocolIds) {
        recordRepository.getRecordsByFilter(s.filterTagIds, s.filterProtocolIds)
    }
    val filterResults = filterFlow.collectKeptAsState("TimelineScreen.filterResults")
    val allTagsFlow = remember { tagRepository.getAllTags() }
    val allTagsList = allTagsFlow.collectKeptAsState("TimelineScreen.allTagsList")
    val allProtocolsFlow = remember { protocolRepository?.getAllProtocols() }
    val allProtocolsList by allProtocolsFlow?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }

    // 非增修列表记忆化，避免每次重组全量 filter
    val nonAmendRecords = remember(records) { records.filter { it.type != RecordType.AMENDMENT } }
    // ===== 筛选：标签/协议走仓库查询，时间/附件/加密在内存里再过一遍（两者是"与"）=====
    // ⚠️ 附加维度必须基于**全库**，不能用 `nonAmendRecords`！
    // 时间线的 records 只是"当前已加载的那十几条"（分页懒加载，滚到底才追加一页），
    // 在它上面筛"加密/附件/时间"，只会捞出这十几条里碰巧符合的两三条 ——
    // 实测：全库 12 条加密记录，最近 15 条里只有 1 条，于是"按加密筛"看起来像坏了。
    var extraFilterBase by remember { mutableStateOf<List<Record>>(emptyList()) }
    LaunchedEffect(s.extraFilterOn, refreshKey, s.amendRefreshKey) {
        extraFilterBase = if (!s.extraFilterOn) emptyList()
        else try {
            recordRepository.getAllActiveRecords().filter { it.type != RecordType.AMENDMENT }
        } catch (_: Exception) {
            emptyList()
        }
    }
    val filterBase = when {
        s.filterTagIds.isNotEmpty() || s.filterProtocolIds.isNotEmpty() -> filterResults   // 标签/协议：仓库查询（本身就是全库）
        s.extraFilterOn -> extraFilterBase          // 只有附加维度：全库拉一遍再筛
        else -> emptyList()
    }
    val filteredRecords = remember(filterBase, s.filterStart, s.filterEnd, s.filterHasAttachment, s.filterLocked) {
        if (!s.extraFilterOn) filterBase
        else filterBase.filter {
            TimelineFilter.matches(it, s.filterStart, s.filterEnd, s.filterHasAttachment, s.filterLocked)
        }
    }
    // 2026-11-15：**搜索与筛选可以同时生效**（交集），不再二选一。
    // 之前 `when { filtersOn -> filteredRecords; searchActive -> searchResults }` 意味着
    // 一旦筛了标签，搜索框直接被 chips 顶掉，两个功能互相排斥。
    val searchHits = if (semanticMode) semanticResults else searchResults
    val searchedOrFiltered = when {
        s.filtersOn && searchActive -> {
            val byId = filteredRecords.associateBy { it.id }
            searchHits.mapNotNull { byId[it.id] }
        }
        s.filtersOn -> filteredRecords
        searchActive -> searchHits
        else -> nonAmendRecords
    }
    LaunchedEffect(s.batchMode, searchActive, s.filtersOn, filteredRecords.size, searchResults.size) {
        if (s.batchMode) {
            s.batchTotalCount = when {
                searchActive && s.filtersOn -> searchedOrFiltered.size
                searchActive -> searchHits.size
                s.filtersOn -> filteredRecords.size
                else -> recordRepository.countAnalyzableRecords()
            }
        }
    }
    val displayRaw = searchedOrFiltered
    // 顺序统一在这里决定：修掉「筛选后永远正序」的问题（2026-11-08）
    val display = remember(displayRaw, s.sortAsc) {
        if (s.sortAsc) displayRaw.sortedBy { it.createdAt } else displayRaw.sortedByDescending { it.createdAt }
    }

    // 列表内容换了（筛选/排序/搜索/清空）→ 必须回到顶部。
    // 否则 LazyColumn 会用 key 锚住"原来那条"，它在新顺序里落在中间，看起来就是"没回到最上面"。
    //
    // 2026-11-21 修：这个 effect 在**重新进入时间线**时也会跑一次，把
    // rememberLazyListState 刚恢复的位置又拽回顶部 —— 从记录详情返回、或从别的 tab
    // 切回来时列表「抖一下/跳回最上面」就是它。首次组合跳过，只在条件真的变化时回顶。
    var scrollEffectReady by remember { mutableStateOf(false) }
    LaunchedEffect(
        s.sortAsc, s.filterTagIds, s.filterProtocolIds, s.filterStart, s.filterEnd,
        s.filterHasAttachment, s.filterLocked, searchActive, s.query
    ) {
        if (!scrollEffectReady) {
            scrollEffectReady = true
            return@LaunchedEffect
        }
        runCatching {
            listState.scrollToItem(0)
        }
    }

    // ===== 从联合查询结果构建关联数据 map（首帧即完整，含增修） =====
    val detailsById = remember(detailsList) { detailsList.associateBy { it.record!!.id } }
    val tagsMap = remember(detailsById) { detailsById.mapValues { it.value.tags } }
    val protocolsMap = remember(detailsById) { detailsById.mapValues { it.value.protocols } }
    val amendsMap = remember(detailsById) { detailsById.mapValues { it.value.amendments } }

    // 2026-11-19 修漏：筛选/搜索的 display 来自**全库查询**，会包含时间线分页（detailsList）
    // 之外的记录；那些记录在 tagsMap/protocolsMap/amendsMap 里查不到，卡片就缺标签/协议/增修。
    // 这里按“实际显示的 id”批量补一次，再与分页那套合并（显示集优先）。
    val overlayIds = remember(display) {
        if (s.filtersOn || searchActive) display.map { it.id } else emptyList()
    }
    var overlayTags by remember { mutableStateOf<Map<String, List<Tag>>>(emptyMap()) }
    var overlayProtocols by remember { mutableStateOf<Map<String, List<Protocol>>>(emptyMap()) }
    var overlayAmends by remember { mutableStateOf<Map<String, List<Record>>>(emptyMap()) }
    LaunchedEffect(overlayIds) {
        if (overlayIds.isEmpty()) {
            overlayTags = emptyMap(); overlayProtocols = emptyMap(); overlayAmends = emptyMap()
            return@LaunchedEffect
        }
        overlayTags = runCatching { tagRepository.getTagsForRecords(overlayIds) }.getOrDefault(emptyMap())
        overlayProtocols = protocolRepository?.let {
            runCatching { it.getProtocolsForRecords(overlayIds) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        overlayAmends = runCatching {
            recordRepository.getAmendmentsForRecords(overlayIds)
                .groupBy { it.correctionOfId ?: "" }
                .filterKeys { it.isNotEmpty() }
        }.getOrDefault(emptyMap())
    }
    val tagsMapAll = remember(tagsMap, overlayTags) { tagsMap + overlayTags }
    val protocolsMapAll = remember(protocolsMap, overlayProtocols) { protocolsMap + overlayProtocols }
    val amendsMapAll = remember(amendsMap, overlayAmends) { amendsMap + overlayAmends }
    val voiceEnabled = FeatureConsole.visible(FeatureConsole.VOICE_ICONS)
    val voiceFuncEnabled = FeatureConsole.visible(FeatureConsole.VOICE_FUNCS)
    val showEncryptionOptions = remember { FeatureConsole.visible(FeatureConsole.ENCRYPTION_FUNCS) }
    val voiceStateHolder = rememberRecordVoiceStates(display, voiceTtsRepository)
    val voiceStates = voiceStateHolder.value
    // 语音翻译 / 合成的"进行中"也改为从仓库 Flow 派生（原先存内存单值 + 跑在页面协程上，
    // 切页面会同时丢状态和被取消）。可多个同时进行。
    val emptyVoiceIds = remember { kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet()) }
    val translatingVoiceIds by (voiceTtsRepository?.translatingIds ?: emptyVoiceIds).collectAsState(initial = emptySet())
    val synthesizingVoiceIds by (voiceTtsRepository?.synthesizingIds ?: emptyVoiceIds).collectAsState(initial = emptySet())
    // 完成/失败的一次性副作用（声音 + 报错）；状态重读交给 rememberRecordVoiceStates 里的收集器
    LaunchedEffect(voiceTtsRepository) {
        voiceTtsRepository?.events?.collect { ev ->
            if (ev.ok) {
                if (ev.kind == memore.app.data.voice.VoiceTtsEvent.Kind.TRANSLATE) UiSoundPlayer.playTranslateComplete(context)
                else UiSoundPlayer.playSynthComplete(context)
            } else {
                val what = if (ev.kind == memore.app.data.voice.VoiceTtsEvent.Kind.TRANSLATE) "语音翻译失败" else "再次合成失败"
                context.memoreToast("$what：${ev.message}", Toast.LENGTH_LONG)
            }
        }
    }

    // 2026-11-15：两维并存，各自取名字（chips 要分别显示"标签：…"与"协议：…"）
    val tagNamesMap = remember(s.filterTagIds, allTagsList) {
        if (s.filterTagIds.isEmpty()) emptyMap()
        else allTagsList.map { it.id to it.name }.filter { (id, _) -> id in s.filterTagIds }.toMap()
    }
    val protocolNamesMap = remember(s.filterProtocolIds, allProtocolsList) {
        if (s.filterProtocolIds.isEmpty()) emptyMap()
        else allProtocolsList.map { it.id to it.name }.filter { (id, _) -> id in s.filterProtocolIds }.toMap()
    }

    if (showBatchInsight) {
        memore.app.ui.components.MirrorStackDialog(
            title = "洞察",
            confirmText = when { batchInsightRunning -> "读取中…"; batchInsightClaims.isEmpty() -> "开始"; else -> "保存" },
            onConfirm = {
                when {
                    batchInsightRunning -> Unit
                    batchInsightClaims.isEmpty() -> {
                        batchInsightError = null; batchInsightRunning = true
                        insightScope.launch {
                            insightSvc.askBatch(batchInsightTargets.mapNotNull { id -> display.firstOrNull { it.id == id } }, batchInsightMode)
                                .onSuccess { batchInsightClaims = it; batchInsightRan = true; batchInsightRunning = false }
                                .onFailure { batchInsightError = it.message; batchInsightRunning = false }
                        }
                    }
                    else -> {
                        val ids = batchInsightTargets
                        val dates = display.filter { it.id in ids }.sortedBy { it.createdAt }
                            .map { java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(it.createdAt)) }
                        insightSvc.save("batch", ids, batchInsightClaims, template = batchInsightMode,
                            meta = "批量 ${ids.size} 条 · " + dates.joinToString(" · "))
                        showBatchInsight = false
                    }
                }
            },
            onDismiss = { showBatchInsight = false }
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                memore.app.ui.components.InsightModeHeader(batchInsightMode) {
                    batchInsightMode = it; batchInsightClaims = emptyList(); batchInsightError = null
                }
                Spacer(Modifier.height(10.dp))
                when {
                    batchInsightRunning -> {
                        memore.app.ui.components.MirrorLoading()
                        Text("正在读这几条", style = memore.app.ui.theme.MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    batchInsightError != null -> Text(
                        batchInsightError ?: "", style = memore.app.ui.theme.MemoreType.meta,
                        color = MaterialTheme.colorScheme.error
                    )
                    batchInsightClaims.isEmpty() -> Text(
                        "这几条之间没有明显的共同点", style = memore.app.ui.theme.MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> memore.app.ui.components.InsightCardCompact(batchInsightClaims)
                }
            }
        }
    }

    // 2026-11-14：targetSdk 35 强制 edge-to-edge，这里没有自己的 Scaffold/TopAppBar，
    // 所以必须自己让出状态栏——否则搜索框会钻到时间/信号底下（真机截图实测）。
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column {
            // 首次建立索引：顶栏细条，不挡操作（2026-11-08）
            val vecState by memore.app.data.vector.VectorRebuildState.state.collectAsState()
            if (vecState.running && vecState.phase == memore.app.data.vector.VectorRebuildState.Phase.INITIAL) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        "正在建立索引 ${vecState.done}/${vecState.total}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxWidth(vecState.percent / 100f).height(2.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 搜索框常驻（2026-11-15）：以前筛选生效时它被 chips 顶掉，等于"筛了就不能搜"。
                Row(
                    Modifier.weight(1f).height(46.dp)
                        .clip(MirrorShapes.menu)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Search, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = s.query,
                        onValueChange = { s.query = it },
                        singleLine = true,
                        enabled = !s.batchMode,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).onFocusChanged { onSearchFocusChanged(it.isFocused) },
                        decorationBox = { inner ->
                            if (s.query.isEmpty()) {
                                Text(
                                    "搜索",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    )
                    if (semanticSearch != null && s.query.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            IconSemantic,
                            if (semanticMode) "语义检索：开" else "语义检索：关",
                            Modifier.size(22.dp).clickable { semanticMode = !semanticMode },
                            tint = if (semanticMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
                if (activeAiTasks.isNotEmpty()) {
                    BadgedBox(badge = { Badge { Text("${activeAiTasks.size}") } }) {
                        IconButton(onClick = { s.showQueue = true }, enabled = !s.batchMode) {
                            Icon(Icons.Outlined.List, "队列", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // 排序（2026-11-15）：视图属性，放在顶栏；以前藏在筛选页「排序」分区里。
                // 2026-11-21：批量模式下也可用 —— 它只改视图顺序，选区按 id 存，不会丢。
                if (FeatureConsole.visible(FeatureConsole.SORT_TIMELINE)) {
                    IconButton(onClick = { s.sortAsc = !s.sortAsc }) {
                        Icon(
                            if (s.sortAsc) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            if (s.sortAsc) "正序" else "倒序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 视图（2026-11-15）：一个按钮，点开是两格——左「图文集」右「区域」。
                // 图文集与区域是**同一批记录的另外两种看法**，所以合成一个入口，而不是并排两个图标
                // （顶栏右端最多 3 个动作）。浮层用共享组件 MirrorTwoCellPopover（64×60 一格）。
                // 2026-11-22：两个都可关；只开一个时直接显示那个的图标；都关就整颗消失。
                val showAlbum = FeatureConsole.visible(FeatureConsole.ALBUM)
                val showZones = FeatureConsole.visible(FeatureConsole.ZONES)
                if (showAlbum || showZones) {
                    ViewPopoverButton(
                        enabled = !s.batchMode,
                        showAlbum = showAlbum,
                        showZones = showZones,
                        onOpenAlbum = onOpenAlbum,
                        onOpenAnchor = onOpenAnchor
                    )
                }
                if (FeatureConsole.anyVisible(listOf(FeatureConsole.BATCH, FeatureConsole.FILTER, FeatureConsole.TOPICS, FeatureConsole.MANAGE_TAG_GROUPS, FeatureConsole.MANAGE_PROTOCOLS))) {
                    // ⋮ 不再弹菜单：进「更多」页（能显示筛选/聚类的当前状态）
                    IconButton(onClick = onOpenMore, enabled = !s.batchMode) {
                        Icon(Icons.Outlined.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // 条件行（2026-11-15）：只在有筛选时出现，不占空位；搜索框始终在上一行。
            if (s.filtersOn) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp).padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.FilterList, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activeDims =
                            (if (s.filterTagIds.isNotEmpty()) 1 else 0) +
                                (if (s.filterProtocolIds.isNotEmpty()) 1 else 0) +
                                (if (s.filterStart != null || s.filterEnd != null) 1 else 0) +
                                (if (s.filterHasAttachment != null) 1 else 0) +
                                (if (s.filterLocked != null) 1 else 0)
                        FilterChip(
                            selected = false,
                            onClick = onOpenFilter,
                            label = { Text("筛选 $activeDims", maxLines = 1) }
                        )
                        if (s.filterTagIds.isNotEmpty()) {
                            FilterChip(
                                selected = true,
                                onClick = { s.filterTagIds = emptyList() },
                                label = { Text("标签：${tagNamesMap.values.joinToString("、")}", maxLines = 1) },
                                trailingIcon = { Icon(Icons.Filled.Close, "取消标签筛选", Modifier.size(14.dp)) }
                            )
                        }
                        if (s.filterProtocolIds.isNotEmpty()) {
                            FilterChip(
                                selected = true,
                                onClick = { s.filterProtocolIds = emptyList() },
                                label = { Text("协议：${protocolNamesMap.values.joinToString("、")}", maxLines = 1) },
                                trailingIcon = { Icon(Icons.Filled.Close, "取消协议筛选", Modifier.size(14.dp)) }
                            )
                        }
                        if (s.filterStart != null || s.filterEnd != null) {
                            FilterChip(
                                selected = true,
                                onClick = { s.filterStart = null; s.filterEnd = null },
                                label = {
                                    Text(
                                        (s.filterStart?.let { formatDateOnly(it) } ?: "最早") +
                                            "~" + (s.filterEnd?.let { formatDateOnly(it) } ?: "今天"),
                                        maxLines = 1
                                    )
                                },
                                trailingIcon = { Icon(Icons.Filled.Close, "取消时间筛选", Modifier.size(14.dp)) }
                            )
                        }
                        s.filterHasAttachment?.let { v ->
                            FilterChip(
                                selected = true,
                                label = { Text(if (v) "有附件" else "无附件", maxLines = 1) },
                                onClick = { s.filterHasAttachment = null },
                                trailingIcon = { Icon(Icons.Filled.Close, "取消附件筛选", Modifier.size(14.dp)) }
                            )
                        }
                        s.filterLocked?.let { v ->
                            FilterChip(
                                selected = true,
                                label = { Text(if (v) "已加密" else "未加密", maxLines = 1) },
                                onClick = { s.filterLocked = null },
                                trailingIcon = { Icon(Icons.Filled.Close, "取消加密筛选", Modifier.size(14.dp)) }
                            )
                        }
                    }
                }
            }
            if (semanticMode && searchActive && semanticLoading) {
            } else if (semanticMode && searchActive && (semanticWeak || semanticResults.isEmpty())) {
                Text(
                    buildString {
                        append(if (semanticResults.isEmpty()) "没有相近的记录" else "较远的联想")
                    },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp)
                )
            }
            if (semanticMode && searchActive && semanticLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { memore.app.ui.components.MirrorLoading() } }
            else if (display.isEmpty() && (searchActive || s.filtersOn)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (searchActive) "未找到匹配的记录" else "该筛选项下无记录", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            else if (display.isNotEmpty()) {
                // 列表外面套一层 Box：右缘叠一条"时隐时现、可拖动"的滚动条（用户 2026-10-11）
                Box(Modifier.fillMaxSize()) {
                TimelineRecordList(
                    display = display, tagsMap = tagsMapAll, protocolsMap = protocolsMapAll, amendsMap = amendsMapAll,
                // 「加载更多」只属于时间线的分页；筛选/搜索时结果已经全在手里，
                // 再显示它只会让人以为还有没加载的（实际点下去只是把分页补上，装饰才出现）。
                showLoadMore = shouldShowLoadMore(s.sortAsc, searchActive, s.filtersOn, records.size, totalActiveCount),
                onLoadMore = { s.displayLimit += PAGE_SIZE },
                    batchMode = s.batchMode, selectedIds = s.selectedIds, listState = listState,
                originNames = originNames,
                    voiceEnabled = voiceEnabled, voiceFuncEnabled = voiceFuncEnabled, voiceStates = voiceStates, showEncryptionOptions = showEncryptionOptions,
                    encryptionAvailable = encryptionAvailable, encryptionHost = encryptionHost,
                    runningSummaryIds = runningSummaryIds,
                    translatingVoiceIds = translatingVoiceIds, generatingVoiceIds = synthesizingVoiceIds,
                    scope = scope, context = context,
                    recordRepository = recordRepository, tagRepository = tagRepository, protocolRepository = protocolRepository,
                    voiceTtsRepository = voiceTtsRepository, voiceStateHolder = voiceStateHolder,
                    onSelect = { id -> s.selectedIds = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id },
                    onAddAmendment = { s.showAmendmentSheet = it },
                    onToggleAnchor = { scope.launch { recordRepository.toggleAnchor(it.id) }; UiSoundPlayer.playAnchorSet(context) },
                    onAddOrigin = { s.showOriginSelectorFor = it },
                    /** 「设置区域」：菜单里换页，和「增添」同一个交互（2026-11-14）。 */
                    zoneOptions = zoneOptions,
                    zoneIdsByRecord = zoneIdsByRecord,
                    onToggleAnchorFor = { r ->
                        scope.launch {
                            recordRepository.toggleAnchor(r.id)
                            s.amendRefreshKey++
                        }
                        if (r.isAnchored) UiSoundPlayer.playAnchorClear(context) else UiSoundPlayer.playAnchorSet(context)
                    },
                    onToggleZoneFor = { r, zone ->
                        val zr = zoneRepository ?: return@TimelineRecordList
                        scope.launch {
                            val mine = zoneIdsByRecord[r.id].orEmpty()
                            if (zone.id in mine) zr.removeFromZone(zone.id, r.id) else zr.addToZone(zone.id, r.id)
                            zoneIdsByRecord = runCatching { zr.membershipsByRecord() }.getOrDefault(emptyMap())
                            s.amendRefreshKey++
                        }
                    },
                    onAddTags = { s.showTagSelectorFor = it },
                    onAddProtocols = { s.showProtocolSelectorFor = it },
                    onAddAttachments = { s.showAddAttachmentSheetFor = it },
                    onViewAttachments = { s.showAttachmentsFor = it },
                    onViewSummary = { s.showSummaryFor = it },
                    onOpenVoice = { s.showVoiceFor = it },
                    onOpenLinks = { s.showLinksFor = it.id },
                    onOpenRecord = onOpenRecord,
                    onLongPressSelect = { r -> s.batchMode = true; s.selectedIds = setOf(r.id); UiSoundPlayer.playBatchEnter(context) },
                    onOpenSourceDraft = { r -> onOpenDraftFromRecord?.invoke(r.sourceDraftId ?: 0L, r.sourceBlockId) },
                    setPendingDeleteId = { s.pendingDeleteId = it },
                    setDeleteDecryptPw = { s.deleteDecryptPw = it },
                    setDeleteDecryptErr = { s.deleteDecryptErr = it },
                    setShowDeleteDecryptDialog = { s.showDeleteDecryptDialog = it },
                    // v5（2026-11-16）：时间线顶部常驻锁提示 + 迁移/设密码面板
                    lockBanner = {
                        val bannerText = when {
                            lockNeedsMigration > 0 -> "旧加密记录待迁移（$lockNeedsMigration 条）›"
                            lockPendingCount > 0 -> "有 $lockPendingCount 条记录锁着，设置密码后可查看 ›"
                            else -> null
                        }
                        bannerText?.let { text ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        lockPromptMode = if (lockNeedsMigration > 0) "migrate" else "setup"
                                        lockPw = ""; lockErr = null
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(memore.app.ui.components.IconLock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        lockPromptMode?.let { mode ->
                            MirrorStackDialog(
                                title = if (mode == "migrate") "迁移旧加密记录" else "设置锁定密码",
                                confirmText = when { lockBusy -> "处理中…"; mode == "migrate" -> "迁移"; else -> "保存" },
                                onDismiss = { if (!lockBusy) lockPromptMode = null },
                                onConfirm = {
                                    if (lockBusy) return@MirrorStackDialog
                                    lockBusy = true; lockErr = null
                                    scope.launch {
                                        val res = if (mode == "migrate") {
                                            recordRepository.migrateLockToV5(lockPw).map { rep ->
                                                if (rep.ok) "已迁移 ${rep.records} 条" else "有 ${rep.failures.size} 处失败"
                                            }
                                        } else {
                                            memore.app.utils.EncryptionManager.setLockPassword(lockPw)
                                                .map { "已设置密码" }
                                        }
                                        lockBusy = false
                                        res.onSuccess {
                                            UiSoundPlayer.playEncryptComplete(context)
                                            lockPromptMode = null
                                            refreshLockState()
                                        }.onFailure { lockErr = it.message }
                                    }
                                }
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (mode == "migrate")
                                            "输入旧密码。迁移会把 $lockNeedsMigration 条记录解回明文，锁标记保留；完成后不可撤销。"
                                        else
                                            "设置后，$lockPendingCount 条锁定记录需要密码才能查看。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    memore.app.ui.components.MemoreLineInput(
                                        value = lockPw,
                                        onValueChange = { lockPw = it },
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                                        )
                                    )
                                    lockErr?.let {
                                        Spacer(Modifier.height(6.dp))
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                )
                    FastScrollBar(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }

        if (!s.batchMode) {
            when {
                searchActive -> SmallFloatingActionButton(onClick = { s.query = "" }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp)) { Icon(Icons.Filled.Close, "清除搜索") }
                s.filtersOn -> SmallFloatingActionButton(onClick = { s.clearFilters() }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp)) { Icon(Icons.Filled.Close, "清除筛选") }
                else -> SmallFloatingActionButton(onClick = { s.showTypeSheet = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp)) { Icon(Icons.Filled.Add, "新建记录") }
            }
        }

        // Batch floating toolbar
        if (s.batchMode) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = MirrorShapes.card,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { s.batchMode = false; s.selectedIds = emptySet(); UiSoundPlayer.playBatchExit(context) }) { Icon(IconClose, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("${s.selectedIds.size} / ${s.batchTotalCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = {
                                scope.launch {
                                    s.selectedIds = when {
                                        searchActive -> if (semanticMode) semanticResults.map { it.id }.toSet() else searchResults.map { it.id }.toSet()
                                        s.filtersOn -> filteredRecords.map { it.id }.toSet()
                                        else -> recordRepository.getAllActiveRecords().filter { it.type != RecordType.AMENDMENT }.map { it.id }.toSet()
                                    }
                                }
                            },
                            enabled = s.selectedIds.size < s.batchTotalCount
                        ) { Text("全选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = { s.selectedIds = emptySet() },
                            enabled = s.selectedIds.isNotEmpty()
                        ) { Text("取消", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = {
                                val ids = s.selectedIds.toList()
                                scope.launch {
                                    val locked = ids.filter { recordRepository.getRecordById(it)?.isLocked == true }
                                    if (locked.isNotEmpty()) {
                                        s.batchLockedIds = locked; s.batchPw = ""; s.batchPwErr = null
                                        s.showBatchDeleteDecryptDialog = true
                                    } else {
                                        s.showBatchDeleteConfirm = true
                                    }
                                }
                            },
                            enabled = s.selectedIds.isNotEmpty()
                        ) { Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = { s.showBatchTagSelector = true },
                            enabled = s.selectedIds.isNotEmpty()
                        ) { Text("标签", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = { s.showBatchProtocolSelector = true },
                            enabled = s.selectedIds.isNotEmpty()
                        ) { Text("协议", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = { s.showBatchOriginSelector = true },
                            enabled = s.selectedIds.isNotEmpty()
                        ) { Text("来源", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = {
                                val picked = display.filter { it.id in s.selectedIds }
                                if (picked.size >= 2) {
                                    batchInsightClaims = emptyList(); batchInsightError = null
                                    batchInsightTargets = picked.map { it.id }; showBatchInsight = true
                                }
                            },
                            enabled = s.selectedIds.size >= 2
                        ) { Text("洞察", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = {
                                scope.launch {
                                    val ids = s.selectedIds.toList()
                                    val locked = ids.filter { recordRepository.getRecordById(it)?.isLocked == true }
                                    if (locked.isNotEmpty()) {
                                        s.batchLockedIds = locked; s.batchPw = ""; s.batchPwErr = null
                                        s.showBatchExportDecryptDialog = true
                                    } else if (ExportDelivery.read(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)) == ExportDelivery.SAVE_AS) {
                                        s.pendingSaveAsDecrypt = false
                                        batchSaveAsLauncher.launch(ExportDelivery.suggestedName(false))
                                    } else {
                                        launchBatchExport(scope, context, s, recordRepository, tagRepository, protocolRepository, redactionRepository, decryptLocked = false)
                                    }
                                }
                            },
                            enabled = s.selectedIds.isNotEmpty()
                        ) { Text("导出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            onClick = {
                                val ids = s.selectedIds.toList()
                                scope.launch {
                                    var queued = 0
                                    ids.forEach { id ->
                                        val r = recordRepository.getRecordById(id)
                                        // 2026-11-21：AI 札记不参与总结（它是 agent 写的，分析它=分析 AI 自己的话）。
                                        if (r?.isLocked != true && r?.type != memore.app.data.model.RecordType.AI_NOTE) {
                                            recordRepository.enqueueTask(id); queued++
                                        }
                                    }
                                    (context.applicationContext as? memore.app.MemoreApp)?.kickAiWorker()
                                    s.selectedIds = emptySet()
                                    context.memoreToast("已入队 $queued 条总结", Toast.LENGTH_SHORT)
                                }
                            },
                            enabled = s.selectedIds.isNotEmpty()
                        ) { Text("总结", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    }
                }
            }
        }

        }
    TimelineDialogs(
        s = s, scope = scope, context = context, sheetState = sheetState,
        encryptionHost = encryptionHost, recordRepository = recordRepository,
        tagRepository = tagRepository, tagGroupRepository = tagGroupRepository, protocolRepository = protocolRepository,
        voiceTtsRepository = voiceTtsRepository,
        redactionRepository = redactionRepository,
        voiceStateHolder = voiceStateHolder, imageLauncher = imageLauncher,
        audioLauncher = audioLauncher, onCreateEssay = onCreateEssay, onCreateDialogue = onCreateDialogue,
        onOpenDraft = onOpenDraft,
        onOpenDraftFromRecord = onOpenDraftFromRecord,
        origins = origins,
        onSetOriginFor = onSetOriginFor
    )

    // 批量导出的进度（模态、不可取消；任务结束自动消失）
    val exportProgress by ExportEngine.progress.collectAsState()
    memore.app.ui.components.OpProgressDialog("正在导出", exportProgress)

    // 可选区域（不含札记）+ 每张卡已属分区：一次取全，卡片不再逐条查库（2026-11-14）
    LaunchedEffect(zoneRepository, s.amendRefreshKey) {
        val zr = zoneRepository ?: return@LaunchedEffect
        zoneOptions = runCatching { zr.zones() }.getOrDefault(emptyList())
            .filter { it.id != memore.app.data.model.BuiltinZones.AI_NOTE }
        zoneIdsByRecord = runCatching { zr.membershipsByRecord() }.getOrDefault(emptyMap())
    }

}

@Composable
private fun rememberRecordVoiceStates(records: List<Record>, repo: VoiceTtsRepository?): MutableState<Map<String, RecordVoiceState>> {
    val holder = remember { mutableStateOf(mapOf<String, RecordVoiceState>()) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(records.map { it.id }, repo, version) {
        holder.value = if (repo == null) emptyMap()
        else repo.loadRecordVoiceBulk(records.map { it.id })
    }
    // 翻译/合成在仓库自己的 scope 里跑完（可能发生在离开页面之后）→ 用事件触发重读。
    // 原先靠调用方在页面协程里手动回写 voiceStateHolder，切页面那条路就丢了。
    LaunchedEffect(repo) {
        repo?.events?.collect { ev ->
            holder.value = holder.value + (ev.recordId to repo.loadRecordVoice(ev.recordId))
            version++
        }
    }
    return holder
}

@Composable
private fun VoiceSheetHost(
    record: Record,
    voiceTtsRepository: VoiceTtsRepository,
    voiceStateHolder: MutableState<Map<String, RecordVoiceState>>,
    scope: CoroutineScope,
    context: Context,
    lockedNotDecrypted: Boolean,
    onUnlockTemp: () -> Unit,
    onDismiss: () -> Unit,
    onGeneratingChange: (String?) -> Unit,
    onError: (String) -> Unit
) {
    VoiceRecordSheet(
        record = record,
        voiceTtsRepository = voiceTtsRepository,
        onDismiss = onDismiss,
        onStateLoaded = { id, st -> voiceStateHolder.value = voiceStateHolder.value + (id to st) },
        lockedNotDecrypted = lockedNotDecrypted,
        onUnlockTemp = onUnlockTemp,
        onStartTts = { jp ->
            onDismiss()
            onGeneratingChange(record.id)
            scope.launch {
                runCatching { voiceTtsRepository.synthesizeForRecord(record.id, jp) }
                    .onSuccess {
                        voiceStateHolder.value = voiceStateHolder.value + (record.id to voiceTtsRepository.loadRecordVoice(record.id))
                        UiSoundPlayer.playSynthComplete(context)
                    }
                    .onFailure { onError(it.message ?: "未知错误") }
                onGeneratingChange(null)
            }
        }
    )
}

@Composable
private fun TimelineRecordList(
    display: List<Record>,
    tagsMap: Map<String, List<Tag>>,
    protocolsMap: Map<String, List<Protocol>>,
    amendsMap: Map<String, List<Record>>,
    originNames: Map<String, String> = emptyMap(),
    batchMode: Boolean,
    selectedIds: Set<String>,
    listState: LazyListState,
    /** 还有更老的没加载：底部显示「加载更多」（2026-11-12 性能修）。 */
    showLoadMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    voiceEnabled: Boolean,
    voiceFuncEnabled: Boolean,
    voiceStates: Map<String, RecordVoiceState>,
    showEncryptionOptions: Boolean,
    encryptionAvailable: Boolean,
    encryptionHost: EncryptionHostState,
    runningSummaryIds: Set<String>,
    translatingVoiceIds: Set<String>,
    generatingVoiceIds: Set<String>,
    scope: CoroutineScope,
    context: Context,
    recordRepository: RecordRepository,
    tagRepository: TagRepository,
    protocolRepository: ProtocolRepository?,
    voiceTtsRepository: VoiceTtsRepository?,
    voiceStateHolder: MutableState<Map<String, RecordVoiceState>>,
    onSelect: (String) -> Unit,
    onAddAmendment: (Record) -> Unit,
    onToggleAnchor: (Record) -> Unit,
    onAddOrigin: (Record) -> Unit,
    /** 「设置区域」（2026-11-14）：菜单里换页。三项都给才出现该入口。 */
    zoneOptions: List<memore.app.data.model.Zone> = emptyList(),
    zoneIdsByRecord: Map<String, Set<String>> = emptyMap(),
    onToggleAnchorFor: ((Record) -> Unit)? = null,
    onToggleZoneFor: ((Record, memore.app.data.model.Zone) -> Unit)? = null,
    onAddTags: (Record) -> Unit,
    onAddProtocols: (Record) -> Unit,
    onAddAttachments: (Record) -> Unit,
    onViewAttachments: (Record) -> Unit,
    onViewSummary: (Record) -> Unit,
    onOpenVoice: (Record) -> Unit,
    onOpenRecord: (String) -> Unit,
    onLongPressSelect: (Record) -> Unit,
    onOpenLinks: (Record) -> Unit,
    /** 点记录卡上那个"来源草稿"链环图标 → 回转录它的那本草稿（带块 id，落到那一行）。 */
    onOpenSourceDraft: (Record) -> Unit,
    setPendingDeleteId: (String?) -> Unit,
    setDeleteDecryptPw: (String) -> Unit,
    setDeleteDecryptErr: (String?) -> Unit,
    setShowDeleteDecryptDialog: (Boolean) -> Unit,
    /** v5：列表顶部的锁提示（可含迁移/设密码面板）。null = 不显示。 */
    lockBanner: (@Composable () -> Unit)? = null
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (batchMode) 220.dp else 150.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        lockBanner?.let { item { it() } }
        itemsIndexed(display, key = { _, r -> r.id }) { index, record ->
            val rt = tagsMap[record.id] ?: emptyList()
            val rp = protocolsMap[record.id] ?: emptyList()
            val amends = amendsMap[record.id] ?: emptyList()
            RecordCard(
                record = record, tags = rt, protocols = rp, amendments = amends, attachmentCount = record.attachmentsCount, batchMode = batchMode, isBatchSelected = record.id in selectedIds,
                onSelect = { onSelect(record.id) },
                originName = record.originId?.let { originNames[it] },
                onOpen = { onOpenRecord(record.id) },
                onLongPressSelect = { onLongPressSelect(record) },
                onAddAmendment = { onAddAmendment(record) },
                onDelete = {
                    if (record.isLocked) {
                        setPendingDeleteId(record.id); setDeleteDecryptPw(""); setDeleteDecryptErr(null)
                        setShowDeleteDecryptDialog(true)
                    } else {
                        scope.launch { recordRepository.softDeleteRecord(record.id); voiceTtsRepository?.deleteRecordVoice(record.id); tagRepository.refreshAllCounts(); protocolRepository?.refreshAllCounts() }
                        UiSoundPlayer.playDelete(context)
                    }
                },
                onToggleAnchor = { onToggleAnchor(record) },
                onOpenLinks = { onOpenLinks(record) },
                onOpenSourceDraft = { onOpenSourceDraft(record) },
                onAddOrigin = { onAddOrigin(record) },
                zoneOptions = zoneOptions,
                zoneIdsByRecord = zoneIdsByRecord,
                onToggleAnchorFor = onToggleAnchorFor?.let { cb -> { r: Record -> cb(r) } },
                onToggleZoneFor = onToggleZoneFor?.let { cb -> { r: Record, z: memore.app.data.model.Zone -> cb(r, z) } },
                onAddTags = { onAddTags(record) },
                onAddProtocols = { onAddProtocols(record) },
                onAddAttachments = { onAddAttachments(record) },
                onAiSummary = {
                    val prefs = context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
                    val endpoint = prefs.getString("ai_endpoint", "") ?: ""
                    val model = prefs.getString("ai_model", "qwen:7b") ?: "qwen:7b"
                    LogUtil.i("AI-1 onAiSummary 触发, recordId=${record.id}, endpoint=${endpoint.ifBlank { "未配置" }}, model=$model")
                    if (endpoint.isBlank()) { context.memoreToast("请先在设置中配置 AI 服务地址", Toast.LENGTH_LONG) }
                    else if (record.content.isBlank()) { context.memoreToast("记录内容为空，无法生成总结", Toast.LENGTH_LONG) }
                    else {
                        // 交给应用级 scope：不绑页面，离开时间线也不会被取消（原先正是死在这里 ——
                        // worker 刚把任务标成 PROCESSING 就被掐断，总结写不进库）。
                        // 也不再判断 count==1：队列里已有别的任务时同样要踢，否则只能干等 30 秒轮询。
                        val app = context.applicationContext as? memore.app.MemoreApp
                        if (app != null) app.enqueueAiSummary(record.id)
                        else context.memoreToast("应用上下文异常，入队失败", Toast.LENGTH_LONG)
                    }
                },
                onViewAttachments = { onViewAttachments(record) },
                onViewSummary = { onViewSummary(record) },
                onVoiceTranslate = if (voiceFuncEnabled && voiceTtsRepository != null) { {
                    val content = encryptionHost.tempDecryptedMap[record.id]?.content?.takeIf { record.isLocked } ?: record.content
                    // 交给仓库自己的 scope：不绑页面（原先 scope.launch 一离开页面就被取消）
                    voiceTtsRepository.startTranslate(record.id, content)
                } } else null,
                voiceAvailable = voiceEnabled && (voiceStates[record.id]?.status == RecordVoiceStatus.TRANSLATED || voiceStates[record.id]?.status == RecordVoiceStatus.AUDIO_READY),
                onOpenVoice = if (voiceEnabled && voiceTtsRepository != null) { { onOpenVoice(record) } } else null,
                onResynthesize = if (voiceFuncEnabled && voiceTtsRepository != null && voiceStates[record.id]?.status == RecordVoiceStatus.AUDIO_READY) { {
                    voiceTtsRepository.startResynthesize(record.id)
                } } else null,
                voiceAudioReady = voiceEnabled && voiceStates[record.id]?.status == RecordVoiceStatus.AUDIO_READY,
                showEncryptionOptions = showEncryptionOptions,
                onLock = if (encryptionAvailable) { { encryptionHost.onLockClick(record) } } else null,
                onUnlock = if (encryptionAvailable) { { encryptionHost.onUnlockClick(record) } } else null,
                tempDecryptedRecord = if (encryptionAvailable) encryptionHost.tempDecryptedMap[record.id] else null,
                tempDecryptedAmendments = if (encryptionAvailable) encryptionHost.tempDecryptedAmendsMap[record.id] else null,
                onTempDecryptClick = if (encryptionAvailable) { { encryptionHost.onTempDecryptClick(record) } } else null,
                onTempLockClick = if (encryptionAvailable) { { encryptionHost.onTempLockClick(record); UiSoundPlayer.playEncTempUndec(context) } } else null,
                isLast = index == display.lastIndex,
                // 跨周才画双线（跨天太频繁，整天都在画双线）
                weekBreak = display.getOrNull(index + 1)?.let { next ->
                    val zone = java.time.ZoneId.systemDefault()
                    val wf = java.time.temporal.WeekFields.ISO
                    val da = java.time.Instant.ofEpochMilli(record.createdAt).atZone(zone)
                    val db = java.time.Instant.ofEpochMilli(next.createdAt).atZone(zone)
                    (da.get(wf.weekBasedYear()) to da.get(wf.weekOfWeekBasedYear())) != (db.get(wf.weekBasedYear()) to db.get(wf.weekOfWeekBasedYear()))
                } ?: false,
                isGenerating = runningSummaryIds,
                isGeneratingVoice = generatingVoiceIds,
                translatingVoiceIds = translatingVoiceIds
            )
        }
        if (showLoadMore) {
            item(key = "__load_more__") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp).clickable { onLoadMore() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "加载更多",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 批量导出（时间线批量选择）：定位 = 即时向 AI 补充信息，非备份。
 * 只导选中记录本身（扁平精简字段），遵守全局脱敏约束；锁定记录由调用方先解密再传 decryptLocked=true。
 */
private suspend fun runBatchExport(
    context: Context,
    s: TimelineScreenState,
    recordRepository: RecordRepository,
    tagRepository: TagRepository,
    protocolRepository: ProtocolRepository?,
    redactionRepository: RedactionRepository?,
    decryptLocked: Boolean,
    /** 传了就直接写目标流（「另存为」），不落 cacheDir / 不写 Download / 不弹分享。 */
    output: java.io.OutputStream? = null,
    deliverToDownloads: Boolean = true,
    deliverToShare: Boolean = true
): Boolean {
    val redactOn = redactionRepository?.redactionEnabled() == true
    val groups = if (redactOn) (redactionRepository?.loadGroups() ?: emptyList()) else emptyList()
    return ExportEngine.exportSelected(
        context, recordRepository, tagRepository, s.selectedIds.toList(),
        protocolRepository,
        decryptLocked = decryptLocked,
        redact = redactOn, redactionGroups = groups,
        redactionMode = redactionRepository?.redactionMode() ?: RedactionEngine.MODE_RANDOM,
        output = output,
        deliverToDownloads = deliverToDownloads,
        deliverToShare = deliverToShare
    ).onSuccess {
        // 「另存为」的成功由回读校验决定，这里不抢先报成功
        if (output == null) UiSoundPlayer.playExportDone(context)
    }.isSuccess
}

private fun launchBatchExport(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    s: TimelineScreenState,
    recordRepository: RecordRepository,
    tagRepository: TagRepository,
    protocolRepository: ProtocolRepository?,
    redactionRepository: RedactionRepository?,
    decryptLocked: Boolean
) {
    scope.launch {
        // 交付方式读全局偏好（在设置→导出里选），与整体备份那条保持同一个记忆
        val delivery = memore.app.utils.ExportDelivery.read(
            context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)
        )
        runBatchExport(
            context, s, recordRepository, tagRepository, protocolRepository,
            redactionRepository, decryptLocked,
            deliverToDownloads = true,
            deliverToShare = delivery != memore.app.utils.ExportDelivery.DOWNLOADS
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineDialogs(
    s: TimelineScreenState,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    sheetState: androidx.compose.material3.SheetState,
    encryptionHost: memore.app.ui.components.EncryptionHostState,
    recordRepository: RecordRepository,
    tagRepository: TagRepository,
    tagGroupRepository: TagGroupRepository?,
    protocolRepository: ProtocolRepository?,
    origins: List<memore.app.data.model.Origin> = emptyList(),
    onSetOriginFor: (suspend (List<String>, String?) -> Unit)? = null,
    voiceTtsRepository: VoiceTtsRepository?,
    redactionRepository: RedactionRepository?,
    voiceStateHolder: androidx.compose.runtime.MutableState<Map<String, RecordVoiceState>>,
    imageLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>,
    audioLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>,
    onCreateEssay: () -> Unit,
    onCreateDialogue: () -> Unit,
    /** 真草稿：第三个入口（与随笔/对话录并列）。 */
    onOpenDraft: (() -> Unit)? = null,
    /** 转录来源：从记录卡回到那本草稿。 */
    onOpenDraftFromRecord: ((Long, Long?) -> Unit)? = null
) {
    // Batch delete confirm
    if (s.showBatchDeleteConfirm) {
        MirrorStackDialog(
            title = "批量删除",
            message = "将删除 ${s.selectedIds.size} 条记录",
            confirmText = "删除",
            destructive = true,
            onDismiss = { s.showBatchDeleteConfirm = false },
            onConfirm = {
                val ids = s.selectedIds.toList()
                s.showBatchDeleteConfirm = false
                s.batchMode = false
                s.selectedIds = emptySet()
                scope.launch {
                    recordRepository.softDeleteRecords(ids)
                    ids.forEach { voiceTtsRepository?.deleteRecordVoice(it) }
                    tagRepository.refreshAllCounts()
                    protocolRepository?.refreshAllCounts()
                }
                UiSoundPlayer.playDelete(context)
            }
        )
    }

    // 删除锁定记录：先解密再删除
    if (s.showDeleteDecryptDialog) {
        MirrorStackDialog(
            title = "删除已加密记录",
            message = "输入主密码解密后才能删除",
            confirmText = "删除",
            destructive = true,
            onDismiss = { s.showDeleteDecryptDialog = false; s.pendingDeleteId = null },
            onConfirm = {
                scope.launch {
                    if (EncryptionManager.verifyUnlockPasswordCached(s.deleteDecryptPw)) {
                        EncryptionManager.ensureKey(s.deleteDecryptPw)
                        val id = s.pendingDeleteId
                        if (id != null) {
                            recordRepository.unlockRecord(id, s.deleteDecryptPw).onSuccess {
                                s.showDeleteDecryptDialog = false; s.pendingDeleteId = null
                                recordRepository.softDeleteRecord(id)
                                voiceTtsRepository?.deleteRecordVoice(id)
                                tagRepository.refreshAllCounts(); protocolRepository?.refreshAllCounts()
                            }.onFailure { s.deleteDecryptErr = it.message }
                        }
                    } else {
                        s.deleteDecryptErr = "密码错误"
                        UiSoundPlayer.playPasswordError(context)
                    }
                }
            }
        ) {
            MirrorPasswordField(
                value = s.deleteDecryptPw,
                onValueChange = { s.deleteDecryptPw = it; s.deleteDecryptErr = null },
                error = s.deleteDecryptErr
            )
        }
    }

    // 批量导出锁定记录：输密码解密后导出明文
    if (s.showBatchExportDecryptDialog) {
        MirrorAlertDialog(
            onDismissRequest = { s.showBatchExportDecryptDialog = false },
            title = { Text("批量导出") },
            text = {
                Column {
                    Text("选中含 ${s.batchLockedIds.size} 条已加密记录，输入主密码解密后一并导出")
                    Spacer(Modifier.height(8.dp))
                    MemoreLineInput(
                        value = s.batchPw,
                        onValueChange = { s.batchPw = it; s.batchPwErr = null },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = MirrorShapes.row,
                        modifier = Modifier.fillMaxWidth()
                    )
                    s.batchPwErr?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (EncryptionManager.verifyUnlockPasswordCached(s.batchPw)) {
                            EncryptionManager.ensureKey(s.batchPw)
                            s.showBatchExportDecryptDialog = false
                            if (ExportDelivery.read(context.getSharedPreferences("mirror_prefs", android.content.Context.MODE_PRIVATE)) == ExportDelivery.SAVE_AS) {
                                // 唤起系统选择器的是 TimelineScreen（launcher 在那边），这里只传意图
                                s.pendingSaveAsDecrypt = true
                                s.saveAsArmed = true
                            } else {
                                launchBatchExport(scope, context, s, recordRepository, tagRepository,
                                    protocolRepository, redactionRepository, decryptLocked = true)
                            }
                        } else { s.batchPwErr = "密码错误"; UiSoundPlayer.playPasswordError(context) }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { s.showBatchExportDecryptDialog = false }) { Text("取消") } }
        )
    }

    // 批量删除锁定记录：输密码解密后一并删除
    if (s.showBatchDeleteDecryptDialog) {
        MirrorStackDialog(
            title = "批量删除",
            message = "含 ${s.batchLockedIds.size} 条已加密记录，输入主密码解密后一并删除",
            confirmText = "删除",
            destructive = true,
            onDismiss = { s.showBatchDeleteDecryptDialog = false },
            onConfirm = {
                scope.launch {
                    if (EncryptionManager.verifyUnlockPasswordCached(s.batchPw)) {
                        EncryptionManager.ensureKey(s.batchPw)
                        s.batchLockedIds.forEach { id -> recordRepository.unlockRecord(id, s.batchPw) }
                        val ids = s.selectedIds.toList()
                        s.showBatchDeleteDecryptDialog = false; s.batchMode = false; s.selectedIds = emptySet()
                        recordRepository.softDeleteRecords(ids); ids.forEach { voiceTtsRepository?.deleteRecordVoice(it) }
                        tagRepository.refreshAllCounts(); protocolRepository?.refreshAllCounts()
                    } else {
                        s.batchPwErr = "密码错误"
                        UiSoundPlayer.playPasswordError(context)
                    }
                }
            }
        ) {
            MirrorPasswordField(
                value = s.batchPw,
                onValueChange = { s.batchPw = it; s.batchPwErr = null },
                error = s.batchPwErr
            )
        }
    }

    // 加密交互对话框（共享组件）
    EncryptionDialogs(encryptionHost, recordRepository, context, scope)

    // Batch tag selector（2026-11-17：批量是唯一保留「确认」的场合 —— 一次改多条，
    // 逐点落库太重；确认键现在固定在列表下方，不会再被挤出可视区）
    if (s.showBatchTagSelector) {
        var tmpIds by remember { mutableStateOf(emptySet<Int>()) }
        TagSelectorDialog(tagRepository = tagRepository, tagGroupRepository = tagGroupRepository, currentTagIds = tmpIds,
            onSelectionChange = { tmpIds = it },
            onConfirm = { nids ->
            scope.launch {
                s.selectedIds.forEach { rid ->
                    val oldTags = tagRepository.getTagsForRecord(rid).map { it.id }.toSet()
                    (nids - oldTags).forEach { tid -> tagRepository.addTagToRecord(rid, tid) }
                    (oldTags - nids).forEach { tid -> tagRepository.removeTagFromRecord(rid, tid) }
                }
                s.amendRefreshKey++
            }
            UiSoundPlayer.playTagUse(context)
            s.showBatchTagSelector = false
        }, onDismiss = { s.showBatchTagSelector = false })
    }

    // Batch protocol selector（同上）
    if (s.showBatchProtocolSelector) {
        var tmpIds by remember { mutableStateOf(emptySet<Int>()) }
        if (protocolRepository != null) {
            ProtocolSelectorDialog(protocolRepository = protocolRepository, currentProtocolIds = tmpIds,
                onSelectionChange = { tmpIds = it },
                onConfirm = { nids ->
                scope.launch {
                    s.selectedIds.forEach { rid ->
                        val old = protocolRepository.getProtocolsForRecord(rid).map { it.id }.toSet()
                        (nids - old).forEach { pid -> protocolRepository.addProtocolToRecord(rid, pid) }
                        (old - nids).forEach { pid -> protocolRepository.removeProtocolFromRecord(rid, pid) }
                    }
                s.amendRefreshKey++
            }
            UiSoundPlayer.playProtocolUse(context)
            s.showBatchProtocolSelector = false
            }, onDismiss = { s.showBatchProtocolSelector = false })
        }
    }

    // Single record tag selector（2026-11-17：点一行就落库，不再需要确认）
    s.showTagSelectorFor?.let { record ->
        var ct by remember(record.id) { mutableStateOf<Set<Int>?>(null) }
        LaunchedEffect(record.id) { ct = tagRepository.getTagsForRecord(record.id).map { it.id }.toSet() }
        ct?.let { loadedCt ->
            TagSelectorDialog(tagRepository = tagRepository, tagGroupRepository = tagGroupRepository, currentTagIds = loadedCt,
                onSelectionChange = { nids ->
                    scope.launch { val old = tagRepository.getTagsForRecord(record.id).map { it.id }.toSet(); (nids - old).forEach { tagRepository.addTagToRecord(record.id, it) }; (old - nids).forEach { tagRepository.removeTagFromRecord(record.id, it) }; s.amendRefreshKey++ }
                    UiSoundPlayer.playTagUse(context)
                },
                onDismiss = { s.showTagSelectorFor = null })
        }
    }

    // Single record protocol selector（同上：即时落库）
    s.showProtocolSelectorFor?.let { record ->
        if (protocolRepository != null) {
            var cp by remember(record.id) { mutableStateOf<Set<Int>?>(null) }
            LaunchedEffect(record.id) { cp = protocolRepository.getProtocolsForRecord(record.id).map { it.id }.toSet() }
            cp?.let { loadedCp ->
                ProtocolSelectorDialog(protocolRepository = protocolRepository, currentProtocolIds = loadedCp,
                    onSelectionChange = { nids ->
                        scope.launch { val old = protocolRepository.getProtocolsForRecord(record.id).map { it.id }.toSet(); (nids - old).forEach { protocolRepository.addProtocolToRecord(record.id, it) }; (old - nids).forEach { protocolRepository.removeProtocolFromRecord(record.id, it) }; s.amendRefreshKey++ }
                        UiSoundPlayer.playProtocolUse(context)
                    },
                    onDismiss = { s.showProtocolSelectorFor = null })
            }
        }
    }

    // 单条：改来源（增添 → 来源，2026-11-12）
    s.showOriginSelectorFor?.let { rec ->
        OriginSelectorSheet(
            origins = origins,
            currentId = rec.originId,
            onPick = { oid ->
                scope.launch { onSetOriginFor?.invoke(listOf(rec.id), oid) }
                s.amendRefreshKey++
                s.showOriginSelectorFor = null
            },
            onDismiss = { s.showOriginSelectorFor = null }
        )
    }

    // 批量：改来源（2026-11-12）
    if (s.showBatchOriginSelector) {
        OriginSelectorSheet(
            origins = origins,
            currentId = null,
            onPick = { oid ->
                val ids = s.selectedIds.toList()
                scope.launch { onSetOriginFor?.invoke(ids, oid) }
                s.amendRefreshKey++
                s.showBatchOriginSelector = false
                UiSoundPlayer.playTagUse(context)
            },
            onDismiss = { s.showBatchOriginSelector = false }
        )
    }

    // 记录双向关联弹层（点击卡片短 ID 触发）
    s.showLinksFor?.let { recordId ->
        AddLinkedIdsSheet(
            recordId = recordId,
            recordRepository = recordRepository,
            onDismiss = { s.showLinksFor = null }
        )
    }

    // Attachment picker sheet
    s.showAddAttachmentSheetFor?.let { record ->
        val attSheetState = rememberModalBottomSheetState()
        MirrorSheet(onDismissRequest = { s.showAddAttachmentSheetFor = null }, sheetState = attSheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text("\u6DFB\u52A0\u9644\u4EF6", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                listOf(
                    Pair(Icons.Filled.Image, "\u4ECE\u76F8\u518C\u9009\u62E9") to { s.showAddAttachmentSheetFor = null; s.pendingAttachRecord = record.id; imageLauncher.launch("image/*") },
                    Pair(Icons.Filled.Folder, "\u9009\u62E9\u97F3\u9891\u6587\u4EF6") to { s.showAddAttachmentSheetFor = null; s.pendingAttachRecord = record.id; audioLauncher.launch("audio/*") }
                ).forEach { (iconAndText, action) ->
                    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 4.dp), shape = MirrorShapes.card, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = mirrorHairline()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconAndText.first, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(iconAndText.second, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { s.showAddAttachmentSheetFor = null }, modifier = Modifier.fillMaxWidth()) { Text("\u53D6\u6D88") }
            }
        }
    }

    // Type selector
    if (s.showTypeSheet) {
        MirrorSheet(onDismissRequest = { s.showTypeSheet = false }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            // P1：三行（名字 + 右侧短界定 + ›），一个半屏放得下
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(
                    "记录类型",
                    fontSize = 10.5.sp,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                TypePickRow("随笔", "想法、见闻、日常") { s.showTypeSheet = false; onCreateEssay() }
                TypePickRow("对话录", "对话、讨论、访谈") { s.showTypeSheet = false; onCreateDialogue() }
                if (onOpenDraft != null) {
                    TypePickRow("草稿", "先扔着，之后再整理") { s.showTypeSheet = false; onOpenDraft.invoke() }
                }
            }
        }
    }

    // Amendment sheet
    s.showAmendmentSheet?.let { targetRecord ->
        var amendContent by remember { mutableStateOf("") }; var amending by remember { mutableStateOf(false) }
        val amendSheetState = rememberModalBottomSheetState()
        MirrorSheet(onDismissRequest = { s.showAmendmentSheet = null }, sheetState = amendSheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text("\u589E\u4FEE \u2014 \u5BF9\u539F\u6587\u8FDB\u884C\u8FFD\u52A0\u4FEE\u6B63", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(12.dp))
                MemoreLineInput(value = amendContent, onValueChange = { amendContent = it }, placeholder = { Text("\u5728\u6B64\u8F93\u5165\u589E\u4FEE\u5185\u5BB9\u2026\u2026") }, modifier = Modifier.fillMaxWidth().height(200.dp), shape = MirrorShapes.menu, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), unfocusedBorderColor = MaterialTheme.colorScheme.outline, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface))
                Spacer(Modifier.height(16.dp))
                    Button(onClick = { if (amendContent.isNotBlank() && !amending) { amending = true; scope.launch {
                    val r = Record(id = UUID.randomUUID().toString(), type = RecordType.AMENDMENT, content = amendContent.trim(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), correctionOfId = targetRecord.id)
                    recordRepository.addRecord(r)
                    // 增修↔原记录：写一条**有向**关系（增修「补充」原记录）——
                    // 类型是 SUPPLEMENT 而不是 CORRECT：增修是在原文上**追加**，不是推翻它。
                    recordRepository.addRelation(r.id, targetRecord.id, memore.app.data.model.RecordRelationType.SUPPLEMENT)
                    recordRepository.markStale(targetRecord.id)
                    amending = false; s.showAmendmentSheet = null; s.amendRefreshKey++
                    UiSoundPlayer.playAmendmentDone(context)
                } } }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MirrorShapes.menu, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("\u4FDD\u5B58\u589E\u4FEE", color = Color.White) }
            }
        }
    }

    // Attachments viewer
    s.showAttachmentsFor?.let { record ->
        AttachmentListViewer(record = record, recordRepository = recordRepository, amendRefreshKey = s.amendRefreshKey, resolvedAttachments = encryptionHost.tempAttachmentPaths[record.id],
            onUnlockTemp = { encryptionHost.onUnlockTemp(record) },
            onDismiss = { s.showAttachmentsFor = null })
    }

    // Queue sheet
    if (s.showQueue) {
        QueueSheet(recordRepository = recordRepository, onDismiss = { s.showQueue = false })
    }

    // Summary viewer
    s.showSummaryFor?.let { record ->
        SummaryViewer(record = record, onDismiss = { s.showSummaryFor = null }, summaryOverride = encryptionHost.tempDecryptedMap[record.id]?.analysisSummary)
    }

    s.showVoiceFor?.let { rec ->
        if (voiceTtsRepository != null) {
            VoiceSheetHost(
                record = rec,
                voiceTtsRepository = voiceTtsRepository,
                voiceStateHolder = voiceStateHolder,
                scope = scope,
                context = context,
                lockedNotDecrypted = rec.isLocked && encryptionHost.tempDecryptedMap[rec.id] == null,
                onUnlockTemp = { encryptionHost.onTempDecryptClick(rec) },
                onDismiss = { s.showVoiceFor = null },
                onGeneratingChange = { s.generatingVoiceId = it },
                onError = { msg -> context.memoreToast("语音生成失败：$msg", Toast.LENGTH_LONG) }
            )
        }
    }}
/** 记录类型选择的一行：名字 + 右侧短界定 + ›。 */
@Composable
private fun TypePickRow(name: String, hint: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).clickable { onClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, fontSize = 15.5.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        )
    }
}

/**
 * 顶栏「视图」按钮 + 两格浮层（2026-11-18 性能修）。
 *
 * 之前 expanded 状态挂在 TimelineScreen 顶层，点一下会触发整个时间线重组
 * （几百条记录、筛选、派生状态全跟着算一遍），所以“呼出浮层也卡”。
 * 状态收进这个小组件后，展开/关闭只重组按钮和浮层本身。
 */
@Composable
private fun ViewPopoverButton(
    enabled: Boolean,
    showAlbum: Boolean,
    showZones: Boolean,
    onOpenAlbum: () -> Unit,
    onOpenAnchor: () -> Unit
) {
    // 2026-11-22：只开一个时不出浮层，直接显示那个入口的图标；都关时整个按钮不出现（调用处判断）。
    if (!showZones) {
        IconButton(onClick = onOpenAlbum, enabled = enabled) {
            Icon(Icons.Outlined.PhotoLibrary, "图文集", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (!showAlbum) {
        IconButton(onClick = onOpenAnchor, enabled = enabled) {
            Icon(Icons.Outlined.GridView, "区域", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = !open }, enabled = enabled) {
            Icon(
                Icons.Outlined.SpaceDashboard,
                "视图",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        memore.app.ui.components.MirrorAnchoredPopover(
            expanded = open,
            onDismissRequest = { open = false },
            verticalOffset = 64.dp
        ) {
            memore.app.ui.components.MirrorTwoCellPopover(
                cells = listOf(
                    memore.app.ui.components.MirrorPopoverCell(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = "图文集"
                    ) {
                        open = false
                        onOpenAlbum()
                    },
                    memore.app.ui.components.MirrorPopoverCell(
                        icon = Icons.Outlined.GridView,
                        label = "区域"
                    ) {
                        open = false
                        onOpenAnchor()
                    }
                )
            )
        }
    }
}
