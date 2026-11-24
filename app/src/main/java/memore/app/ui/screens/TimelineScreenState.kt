package memore.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import memore.app.data.model.Record

/** 时间线页所有可重组的 UI 状态，收拢后主 Composable 不再持有几十个局部 State。 */
class TimelineScreenState {
    var showTypeSheet by mutableStateOf(false)
    var query by mutableStateOf("")
    var displayLimit by mutableIntStateOf(15)
    var showTopMenu by mutableStateOf(false)
    var batchTotalCount by mutableIntStateOf(0)
    var showTagSelectorFor by mutableStateOf<Record?>(null)
    var showBatchTagSelector by mutableStateOf(false)
    var showProtocolSelectorFor by mutableStateOf<Record?>(null)
    var showBatchProtocolSelector by mutableStateOf(false)
    var showAddAttachmentSheetFor by mutableStateOf<Record?>(null)
    var batchMode by mutableStateOf(false)
    var selectedIds by mutableStateOf(setOf<String>())
    var showBatchDeleteConfirm by mutableStateOf(false)
    var pendingDeleteId by mutableStateOf<String?>(null)
    var showDeleteDecryptDialog by mutableStateOf(false)
    var deleteDecryptPw by mutableStateOf("")
    var deleteDecryptErr by mutableStateOf<String?>(null)
    var batchLockedIds by mutableStateOf(listOf<String>())
    var showBatchDeleteDecryptDialog by mutableStateOf(false)
    /** 批量导出时选中含锁定记录 → 输密码解密后导出明文 */
    var showBatchExportDecryptDialog by mutableStateOf(false)
    var batchPw by mutableStateOf("")
    var batchPwErr by mutableStateOf<String?>(null)
    /** 「另存为」批量导出：是否带解密（要跨系统选择器这一趟） */
    var pendingSaveAsDecrypt by mutableStateOf(false)
    /** 「另存为」批量导出：置 true 让 TimelineScreen 唤起系统选择器（解密框在另一个 composable 里） */
    var saveAsArmed by mutableStateOf(false)
    var showAmendmentSheet by mutableStateOf<Record?>(null)
    var showAttachmentsFor by mutableStateOf<Record?>(null)
    var showSummaryFor by mutableStateOf<Record?>(null)
    var showVoiceFor by mutableStateOf<Record?>(null)
    var translatingVoiceId by mutableStateOf<String?>(null)
    var generatingVoiceId by mutableStateOf<String?>(null)
    var amendRefreshKey by mutableIntStateOf(0)
    var showQueue by mutableStateOf(false)
    // 备注：AI 任务"是否进行中"不再存内存副本（原先的 generatingSummaryId / pendingTaskCount 已删）——
    // 一律从 ai_tasks 的 Room Flow 派生，见 TimelineScreen 的 activeAiTasks / runningSummaryIds。
    /**
     * 筛选的两种对象维度（2026-11-15）：**标签与协议可以同时生效**。
     *
     * 之前是 `filterType` + `filterIds` 二选一：切到协议会把已选标签清掉，
     * 也永远做不到"标签 A 且 协议 B"。现在两维各存各的。
     */
    var filterTagIds by mutableStateOf<List<Int>>(emptyList())
    var filterProtocolIds by mutableStateOf<List<Int>>(emptyList())
    /**
     * 附加筛选维度：与"按标签 / 按协议"是**与**的关系，可任意组合。
     * `null` = 该维度不限。
     */
    var filterStart by mutableStateOf<Long?>(null)
    var filterEnd by mutableStateOf<Long?>(null)
    var filterHasAttachment by mutableStateOf<Boolean?>(null)
    var filterLocked by mutableStateOf<Boolean?>(null)

    /** 除了标签 / 协议之外，还有维度在生效。 */
    val extraFilterOn: Boolean
        get() = filterStart != null || filterEnd != null || filterHasAttachment != null || filterLocked != null

    /**
     * 时间线首屏数据缓存（2026-11-12）。
     *
     * 为什么要有它：`collectAsState(initial = emptyList())` 的**首帧一定是空**，
     * 于是每次进/回时间线都会先画一帧空列表、下一帧再冒出 15 张卡——看着就是"跳一下"。
     * 把这个列表提到导航状态里（MainActivity 持有），离开页面也不丢，
     * 回来时首帧就有内容，Flow 再到达时无感替换。
     */
    var cachedDetails by mutableStateOf<List<memore.app.data.model.RecordWithDetails>>(emptyList())

    /** 单条：改来源（从卡片「增添 → 来源」进入，2026-11-12）。 */
    var showOriginSelectorFor by mutableStateOf<memore.app.data.model.Record?>(null)

    /** 批量：改来源面板（2026-11-12）。 */
    var showBatchOriginSelector by mutableStateOf(false)

    /** 时间线顺序：false=倒序（最新在上，默认），true=正序（最早在上）。 */
    var sortAsc by mutableStateOf(false)

    /**
     * 当前是否有任何筛选在生效 —— **由维度本身推导**，不再另存一个 `filterActive` 标志
     * （标志和维度会不同步：清了维度忘了清标志，列表就"筛了个空气"）。
     *
     * ⚠️ 空态提示、清除按钮、批量"全选"范围都看它 → 以后新增维度只改这里与 [extraFilterOn]。
     */
    val filtersOn: Boolean
        get() = filterTagIds.isNotEmpty() || filterProtocolIds.isNotEmpty() || extraFilterOn

    /** 清掉所有筛选维度。 */
    fun clearFilters() {
        filterTagIds = emptyList()
        filterProtocolIds = emptyList()
        filterStart = null
        filterEnd = null
        filterHasAttachment = null
        filterLocked = null
    }

    var pendingAttachRecord by mutableStateOf<String?>(null)
    var showLinksFor by mutableStateOf<String?>(null)
}
