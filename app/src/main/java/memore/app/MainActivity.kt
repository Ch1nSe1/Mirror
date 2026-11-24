package memore.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import android.os.Bundle
import memore.app.ui.components.MirrorAlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import memore.app.ui.components.LetterPopupCard
import memore.app.data.model.AgentCommand
import memore.app.ui.components.LetterNub
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import memore.app.data.model.RecordType
import memore.app.di.AppContainer
import memore.app.ui.nav.MemoreNavHost
import memore.app.ui.nav.NavDirection
import memore.app.ui.nav.NavTransition
import memore.app.ui.screens.*
import memore.app.ui.theme.MemoreTheme
import memore.app.utils.AuthManager
import memore.app.utils.LogUtil
import memore.app.utils.ThemeManager
import memore.app.utils.UiSoundPlayer

class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点开后直接进「信」页（2026-11-15）。 */
        const val EXTRA_OPEN_LETTER = "open_letter"
    }

    private val container by lazy { (application as MemoreApp).container }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            LogUtil.e("Global", "UncaughtException", throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
        AuthManager.init(this)
        ThemeManager.init(this)
        setContent {
            MemoreTheme {
                MemoreMainScreen(container = container)
            }
        }
    }
}

data class BottomNavItem(val label: String, val icon: ImageVector)
private val bottomNavItems = listOf(
    BottomNavItem("时间线", Icons.Filled.Home),
    BottomNavItem("回顾", Icons.Filled.Newspaper),
    BottomNavItem("秒针", Icons.Filled.Schedule),
    BottomNavItem("设置", Icons.Filled.Settings)
)
private const val TAB_TIMELINE = 0
private const val TAB_ANCHOR = 1
private const val TAB_TICKS = 2
private const val TAB_SETTINGS = 3
private const val SCREEN_MAIN = 0
private const val SCREEN_EDIT = 1
private const val SCREEN_DETAIL = 2
private const val SCREEN_TAG_MANAGE = 3
private const val SCREEN_EMOTION = 5
private const val SCREEN_EMOTION_CHART = 8
private const val SCREEN_ALIAS_MAPPING = 10
private const val SCREEN_ALIAS_EDIT = 11
private const val SCREEN_PROTOCOL_MANAGE = 13
private const val SCREEN_PROMPT_MANAGE = 15
private const val SCREEN_EMOTION_RECORDS = 17
private const val SCREEN_VOICE_LAB = 18
// ⚠️ 屏 id 必须**全局唯一**：它们只是 Int 常量，撞值编译器不会报错，
// 而 `when (currentScreenId)` 只会匹配**第一个**分支 —— 撞了就是"点 A 打开 B"。
// （19 曾经同时给了 SCREEN_EMOTION_TREND 和 SCREEN_TUTORIAL：点"教程"打开的是情绪走势。
//   ScreenIdUniquenessTest 现在会拦住这种撞车。）
private const val SCREEN_EMOTION_TREND = 26
private const val SCREEN_TUTORIAL = 19
private const val SCREEN_ALBUM = 20
private const val SCREEN_REDACTION = 21
private const val SCREEN_SYSTEM_INSTRUCTION = 22
private const val SCREEN_TRASH = 24
    private const val SCREEN_ANCHOR = 40
    private const val SCREEN_INSIGHT_ARCHIVE = 43
private const val SCREEN_ORIGIN = 46
    /** 时间线的「更多」页：⋮ 进这一页，不弹菜单。 */
    private const val SCREEN_TIMELINE_MORE = 47
    /** 筛选整页（2026-11-11）：不再是半屏抽屉。 */
    private const val SCREEN_FILTER = 48

    /** 某来源下的记录列表（2026-11-13）。 */
    private const val SCREEN_ORIGIN_RECORDS = 49
    private const val SCREEN_INSIGHT_RECORD = 44
    private const val SCREEN_INSIGHT_RECENT = 45
    private const val SCREEN_STATS = 41
    /** 2026-11-22：主题页改成「域 → 域内主题 → 主题详情」三个真实导航屏。 */
    private const val SCREEN_TOPICS_PAGE = 42
    /** 主题月列表：时间线「更多 → 主题」的入口。 */
    private const val SCREEN_TOPIC_MONTHS = 57
    /** 线索页（2026-11-17）：主题页顶栏左图标进入。 */
    private const val SCREEN_TOPIC_THREADS = 58
    /** 一条线索的记录列表（2026-11-17）：线索卡片「详情 ›」进入。 */
    private const val SCREEN_THREAD_RECORDS = 59
private const val SCREEN_TICK_RECORDS = 25
/** 真草稿页（独立于记录：只有输入框 + 定格块）。 */
private const val SCREEN_DRAFT = 27
/** 草稿的质疑 / 讨论作答页（独立页面，不用弹窗）。 */
private const val SCREEN_DRAFT_ASK = 28
/** 表盘页（一段时期的框：包络刻度）。 */
private const val SCREEN_DIAL = 29
/** 表盘的整期汇总页（情绪 / 主题）。 */
private const val SCREEN_DIAL_STAT = 30
private const val SCREEN_AI_CONFIG = 31
private const val SCREEN_FIX_TOOLS = 32
private const val SCREEN_PIN_SETUP = 33
private const val SCREEN_SECURITY_QUESTION = 34
private const val SCREEN_TICK_FORM = 35
private const val SCREEN_VECTOR_INDEX = 37
private const val SCREEN_AGENT_LINK = 50
private const val SCREEN_TAG_RECORDS = 51
private const val SCREEN_LETTER = 53
/** 信里某个动作的「相关记录」页（2026-11-15）。 */
private const val SCREEN_LETTER_RECORDS = 54
/** 信 · 历史（2026-11-20）：从右上角进入。 */
private const val SCREEN_LETTER_HISTORY = 60
private var pendingLetterActionId: String = ""
private const val SCREEN_PROTOCOL_RECORDS = 52
    /** 2026-11-16：加密相关页从设置页的覆盖层抽成独立路由屏。 */
    private const val SCREEN_ENCRYPTION_MANAGE = 55
    private const val SCREEN_ENCRYPTION_SETUP = 56
// 标签/协议记录页共用的组名（2026-11-13）
private var pendingGroupName: String = ""
private const val SCREEN_AI_RUNS = 38
private const val SCREEN_LINKS = 39
private const val NAV_DEBOUNCE_MS = 300L

/** 引导页"已看过"的键（2026-11-16）：写在 memore_prefs 里，冷启动据此决定是否再弹。 */
internal const val KEY_TUTORIAL_DONE = "tutorial_v1_done"

/**
 * 把"引导已看完/已跳过"落盘（2026-11-16）。
 *
 * 抽成独立函数只为一件事：**能被单测直接断言**。
 * 之前的 bug 是 onSkip 只改内存状态、不写 prefs，冷启动又弹一遍——
 * 这类"只在一个函数里丢一行"的错，靠 Compose 测试跑不起来就得靠这层。
 */
internal fun markTutorialDone(prefs: android.content.SharedPreferences) {
    prefs.edit().putBoolean(KEY_TUTORIAL_DONE, true).apply()
}

@Composable
fun MemoreMainScreen(container: AppContainer) {
    // 向量索引：启动补齐 + 订阅写入信号（设计文档 §6）
    // 2026-11-18 性能修：这三个动作会构造 vectorIndexService → 加载 22.9MB ONNX 模型，
    // 旧实现跑在 LaunchedEffect 主线程上（实测 Davey 827ms、Skipped 47 frames）。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.wireLinkCandidates() }
            runCatching { container.bootstrapVectors() }
            runCatching { container.purgeOrphanLinks() }
        }
    }

    // Agent 服务自启（2026-11-13）：上次是运行中，就把它拉起来。
    // 在 Activity 里启动是有意的——Android 12 起后台不能启前台服务，只有前台可见时才允许。
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        val prefs = appContext.getSharedPreferences("memore_prefs", android.content.Context.MODE_PRIVATE)
        // 2026-11-14：默认开启。只有用户显式点过「停止服务」才不启动。
        // 原来默认 false，一旦那次落到 false，之后打开 App 也不会自启 —— 出门就连不上。
        // 2026-11-14：App 打开 = 服务跑（只要有过 token）。
        // 不再看 agent_should_run —— 那个键一旦被写成 false，之后打开 App 也不会自启，
        // 出门断开 WLAN 就连不上（今天的真因）。
        val shouldRun = !prefs.getString("agent_token", null).isNullOrBlank()
        if (shouldRun && !memore.app.service.AgentServerState.running) {
            runCatching { memore.app.service.AgentServerService.start(appContext) }
        }
    }
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    // 信：待批准的动作列表（数量决定弹窗还是页面，2026-11-14；2026-11-15 改为按动作）
    var letterItems by remember { mutableStateOf<List<memore.app.data.model.AgentAction>>(emptyList()) }
    var letterPopup by remember { mutableStateOf(false) }
    val letterActions = remember {
        memore.app.data.agent.MirrorActionService(appContext as memore.app.MemoreApp)
    }
    val letterBus = remember { memore.app.service.AgentCommandBus(appContext as memore.app.MemoreApp) }
    val mainScope = rememberCoroutineScope()
    /** 信里的批准/忽略要下到 IO 去做（执行 1~3 秒，放主线程就是"点了没反应"）。 */
    val letterScope = mainScope
    var currentScreenId by rememberSaveable { mutableIntStateOf(SCREEN_MAIN) }
    // 「更多」页点批量选择 / 筛选后回时间线，用一次性信号打开对应面板
    var pendingBatchEnter by remember { mutableStateOf(false) }
    // 时间线的 UI 状态提到这里持有：筛选整页要和它共用同一份
    val timelineState = remember { TimelineScreenState() }
    val linkListState = remember { memore.app.ui.screens.LinkListUiState() }
    // 来源名称表：id → 名字（卡片/详情显示用；只读一次 Flow，避免每张卡片各自查库）
    val originNames by container.originRepository.observeAll()
        .collectAsState(initial = emptyList<memore.app.data.model.Origin>())
    val originsAll = originNames
    var tickFormIsDial by remember { mutableStateOf(false) }
    var tickRefreshKey by remember { mutableIntStateOf(0) }
    // 草稿的质疑 / 讨论：从草稿页把"问出来的题"带到作答页
    var pendingDraftId by remember { mutableStateOf(1L) }
    var pendingDraftAskKind by remember { mutableStateOf<String?>(null) }
    // 从作答页回草稿页时滚到刚写完的那一块（一次性）
    var draftScrollToNew by remember { mutableStateOf(false) }
    /** 从记录那边点"来源草稿"进来时要落到的那一块（null = 只回本）。 */
    var pendingDraftFocusBlock by remember { mutableStateOf<Long?>(null) }
    /** 「转录」带进编辑器的内容与来源（随笔 → 正文框；对话录 → 第二个框）。 */
    var pendingEditPrefill by remember { mutableStateOf<String?>(null) }
    var pendingEditPrefillReply by remember { mutableStateOf<String?>(null) }
    var pendingEditSourceDraftId by remember { mutableStateOf<Long?>(null) }
    var pendingEditSourceBlockId by remember { mutableStateOf<Long?>(null) }
    var pendingDraftAskItems by remember { mutableStateOf<List<memore.app.data.model.DraftAskItem>>(emptyList()) }
    var pendingEditorType by remember { mutableStateOf<RecordType?>(null) }
    var pendingEditRecordId by remember { mutableStateOf<String?>(null) }
    var pendingDetailRecordId by remember { mutableStateOf<String?>(null) }
    /** 来源记录页要显示的来源（id to 名字）。 */
    var pendingOriginRecords by remember { mutableStateOf<Pair<String, String>?>(null) }
    var timelineRefreshKey by remember { mutableIntStateOf(0) }
    var showDraftRecovery by remember { mutableStateOf(false) }
    var isUnlocked by remember { mutableStateOf(!AuthManager.isLockEnabled()) }
    var unlockSoundPlayed by remember { mutableStateOf(false) }
    var lastNavTime by remember { mutableStateOf(0L) }
    /** 这一次换屏的方向：goTo 记 Forward、backAction 记 Backward（法条 8.2 的唯一来源）。 */
    var navDirection by remember { mutableStateOf(NavDirection.Forward) }
    var detailReturnScreen by remember { mutableIntStateOf(SCREEN_MAIN) }
    var pendingEmotionTag by remember { mutableStateOf("") }
    var emotionReturnToChart by remember { mutableStateOf(false) }
    /** 进教程时要定位到的章节（2026-11-18：Agent 页右上角教程键带过来）；离开教程即清空。 */
    var tutorialStartSection by remember { mutableStateOf<String?>(null) }
    var pendingEditAliasGroup by remember { mutableStateOf<memore.app.data.model.AliasGroup?>(null) }
    var pendingTickId by remember { mutableStateOf<String?>(null) }
    var pendingDialId by remember { mutableStateOf("") }
    /** 刻度页是从表盘里点进来的 → 返回要回表盘，而不是回秒针列表。 */
    var tickFromDial by remember { mutableStateOf(false) }
    /** 汇总页看哪一类（DialStatKind.EMOTION / THEME）。 */
    var pendingDialStat by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 仅 debug：am start --es screen origin 直达页面（开发期验证用）
    LaunchedEffect(Unit) {
        val dbg = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (dbg && (context as? android.app.Activity)?.intent?.getStringExtra("screen") == "origin") {
            currentScreenId = SCREEN_ORIGIN
            currentTab = TAB_SETTINGS
        }
        if (dbg && (context as? android.app.Activity)?.intent?.getStringExtra("screen") == "more") {
            currentScreenId = SCREEN_TIMELINE_MORE
            currentTab = TAB_TIMELINE
        }
        if (dbg && (context as? android.app.Activity)?.intent?.getStringExtra("screen") == "filter") {
            currentScreenId = SCREEN_FILTER
            currentTab = TAB_TIMELINE
        }
        // 2026-11-15：从「agent 想改 N 条记录」那条通知点进来 → 直接进信页。
        // 这条**不限定 debug**：它是功能本身，不是开发捷径。
        if ((context as? android.app.Activity)?.intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_LETTER, false) == true) {
            currentScreenId = SCREEN_LETTER
            currentTab = TAB_TIMELINE
        }
    }
    // 键盘是否弹出（2026-11-05）：adjustResize 下窗口会变矮，用根视图高度对比真实屏幕高判断。
    // 键盘弹出时**隐藏底部四个 tab**——否则它们会被顶到键盘上沿（用户报的"tab 上升"）。
    val rootView = androidx.compose.ui.platform.LocalView.current
    var imeVisible by remember { mutableStateOf(false) }
    // 输入框聚焦也算"正在打字"：adjustResize 下键盘高度不一定能算出来，焦点是确定信号
    var inputFocused by remember { mutableStateOf(false) }
    DisposableEffect(rootView) {
        // 只认一个权威来源：窗口高度。insets 只用来"确认弹出"，不用来"确认收起"，
        // 因为 adjustResize 下 inset 常常一直是 0，用它置 false 会把隐藏态卡死。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            if (ime > 0) imeVisible = true
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
            insets
        }
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val dm = rootView.context.resources.displayMetrics
            val h = rootView.rootView.height
            when {
                h < (dm.heightPixels * 0.92f).toInt() -> imeVisible = true      // 键盘占位：隐藏 tab
                h >= (dm.heightPixels * 0.97f).toInt() -> imeVisible = false   // 窗口恢复：tab 回来
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView, null)
            rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    // 轮询 IME 可见性（2026-11-06）：adjustResize 下窗口高度/inset 在部分 ROM 上都不可靠，
    // 唯一稳的办法是定期问一句"键盘现在可见吗"。
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var imeWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(rootView) {
        while (true) {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(rootView)
            val ime = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            imeVisible = ime
            if (ime) imeWasShown = true
            // 只有"键盘先出现过、又收起"才放掉焦点。
            // 之前写成 !ime 就清，会在键盘还没弹出的那 200ms 里把焦点清掉 → 键盘永远起不来 / 起来又下去。
            if (!ime && imeWasShown) {
                imeWasShown = false
                inputFocused = false
                runCatching { focusManager.clearFocus() }
            }
            kotlinx.coroutines.delay(250)
        }
    }

    val prefs = context.getSharedPreferences("memore_prefs", android.content.Context.MODE_PRIVATE)
    var tutorialSeen by rememberSaveable { mutableStateOf(prefs.getBoolean("tutorial_v1_done", false)) }
    var selectedMonth by remember { mutableStateOf("") }
    var pendingInsightRecordId by remember { mutableStateOf<String?>(null) }
    var pendingThreadName by remember { mutableStateOf<String?>(null) }
    var pendingThreadMembers by remember { mutableStateOf<List<String>>(emptyList()) }

    // ===== 导航栈（2026-11-05）=====
    // 不能靠"观察 currentScreenId 变化"来推断前进：Compose 副作用在重组之后才跑，
    // 连续跳转时会漏记/错记（表现为返回跳到图文集、教程）。改为在跳转处显式压栈。
    val screenStack = remember { mutableStateListOf<Int>() }
    var pendingTopicsUnclassified by remember { mutableStateOf(false) }

    /**
     * 原始赋值：只给"返回"和内部兜底用，不压栈。
     *
     * 2026-11-16：回主屏时**连栈一起清掉**。原来只有 goTo 才清，
     * 各屏用 setScreenRaw(SCREEN_MAIN) 收尾时栈会残留（加密设置"启用完成"就是这种），
     * 之后再按返回会从旧栈里弹出一个已经离开的屏。
     */
    fun setScreenRaw(id: Int) {
        if (id == SCREEN_MAIN) screenStack.clear()
        currentScreenId = id
    }

    /** 前进导航：压栈 + 跳转。所有打开新页面的地方都走它。 */
    fun goTo(id: Int) {
        if (id == currentScreenId) return
        // 回主屏永远算"退"（保存后回时间线、离开草稿页都走这里），其余算"进"
        navDirection = if (id == SCREEN_MAIN) NavDirection.Backward else NavDirection.Forward
        // 回主屏 = 一条链结束：清空栈，免得旧入口留在里面被"返回"弹出来
        if (id == SCREEN_MAIN) {
            screenStack.clear()
            currentScreenId = id
            return
        }
        screenStack.add(currentScreenId)
        currentScreenId = id
    }

    fun navigate(a: () -> Unit) { val now = System.currentTimeMillis(); if (now - lastNavTime < NAV_DEBOUNCE_MS) return; lastNavTime = now; a() }

    /** 清掉「转录」带进编辑器的那套一次性参数（新建 / 编辑普通记录时不许残留）。 */
    fun clearEditPrefill() {
        pendingEditPrefill = null
        pendingEditPrefillReply = null
        pendingEditSourceDraftId = null
        pendingEditSourceBlockId = null
    }

    // 统一“返回”动作：供系统返回键/侧滑手势与各屏返回箭头共用。
    // 需与每个子屏的 onBack 保持一致的导航目标（当前系统返回直接开 this 分支）。
    fun leaveDraft() {
        // 离开草稿：正在这一本要是空的（新建后没输入）就删掉，防止乱堆。
        // ⚠️ 必须用**草稿页报上来的当前本**，不是"进来时那一本"（用户 2026-10-11 报的导航问题之一）
        val leaving = pendingDraftId
        mainScope.launch { container.draftRepository.deleteIfEmpty(leaving) }
        draftScrollToNew = false
        pendingDraftFocusBlock = null
        goTo(SCREEN_MAIN)
    }

    /** 旧的目标表：只在导航栈为空时兜底（比如直接恢复进某个子屏）。 */
    fun legacyBack() {
        when (currentScreenId) {
            SCREEN_ALIAS_MAPPING -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_ALIAS_EDIT -> { setScreenRaw(SCREEN_ALIAS_MAPPING) }
            SCREEN_EDIT -> { timelineRefreshKey++; pendingEditorType = null; pendingEditRecordId = null; clearEditPrefill(); setScreenRaw(SCREEN_MAIN) }
            SCREEN_DETAIL -> { pendingDetailRecordId = null; currentScreenId = detailReturnScreen }
            SCREEN_AGENT_LINK -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_LETTER -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_LETTER_RECORDS -> { setScreenRaw(SCREEN_LETTER) }
            SCREEN_LETTER_HISTORY -> { setScreenRaw(SCREEN_LETTER) }
            SCREEN_TAG_RECORDS -> { setScreenRaw(SCREEN_TAG_MANAGE) }
            SCREEN_PROTOCOL_RECORDS -> { setScreenRaw(SCREEN_PROTOCOL_MANAGE) }
            SCREEN_VECTOR_INDEX -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_AI_RUNS -> { setScreenRaw(SCREEN_VECTOR_INDEX) }
            SCREEN_LINKS -> { setScreenRaw(SCREEN_VECTOR_INDEX) }
            SCREEN_ORIGIN_RECORDS -> { pendingOriginRecords = null; setScreenRaw(SCREEN_ORIGIN) }
            SCREEN_EMOTION -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_EMOTION_CHART -> { setScreenRaw(SCREEN_EMOTION) }
            SCREEN_EMOTION_RECORDS -> { currentScreenId = if (emotionReturnToChart) SCREEN_EMOTION_CHART else SCREEN_EMOTION }
            SCREEN_EMOTION_TREND -> { setScreenRaw(SCREEN_EMOTION) }
            SCREEN_TAG_MANAGE -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_PROTOCOL_MANAGE -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_PROMPT_MANAGE -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            // 2026-11-16：加密两页是独立路由屏，返回回设置
            SCREEN_ENCRYPTION_MANAGE -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_ENCRYPTION_SETUP -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_SECURITY_QUESTION -> { memore.app.utils.AuthManager.clear(); currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_TICK_FORM -> { currentTab = TAB_TICKS; setScreenRaw(SCREEN_MAIN); tickRefreshKey++ }
            SCREEN_PIN_SETUP -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_FIX_TOOLS -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_AI_CONFIG -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_VOICE_LAB -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_TUTORIAL -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_ALBUM -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_SYSTEM_INSTRUCTION -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_REDACTION -> { currentTab = TAB_SETTINGS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_TRASH -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_TICK_RECORDS -> { if (tickFromDial) setScreenRaw(SCREEN_DIAL) else { currentTab = TAB_TICKS; setScreenRaw(SCREEN_MAIN) } }
            SCREEN_DIAL -> { currentTab = TAB_TICKS; setScreenRaw(SCREEN_MAIN) }
            SCREEN_DIAL_STAT -> { setScreenRaw(SCREEN_DIAL) }
            SCREEN_DRAFT -> leaveDraft()
            SCREEN_DRAFT_ASK -> { pendingDraftAskKind = null; pendingDraftAskItems = emptyList(); setScreenRaw(SCREEN_DRAFT) }

            // 2026-11-15：补 8 个原本漏在这一张表里的屏。
            // 症状：在这些屏上旋转屏幕或进程被重建后，**返回键和顶栏 ← 都没反应**
            // （导航栈随进程清空，只能靠这张表兜底，而表里没有它们）。
            SCREEN_ANCHOR -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_INSIGHT_ARCHIVE -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_INSIGHT_RECORD -> { pendingInsightRecordId = null; setScreenRaw(SCREEN_INSIGHT_ARCHIVE) }
            SCREEN_INSIGHT_RECENT -> { setScreenRaw(SCREEN_INSIGHT_ARCHIVE) }
            SCREEN_STATS -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_TOPICS_PAGE -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_TOPIC_MONTHS -> { setScreenRaw(SCREEN_TIMELINE_MORE) }
            SCREEN_TOPIC_THREADS -> { setScreenRaw(SCREEN_TOPIC_MONTHS) }
            SCREEN_THREAD_RECORDS -> { pendingThreadName = null; pendingThreadMembers = emptyList(); setScreenRaw(SCREEN_TOPIC_THREADS) }
            SCREEN_TIMELINE_MORE -> { setScreenRaw(SCREEN_MAIN) }
            SCREEN_FILTER -> { setScreenRaw(SCREEN_MAIN) }
        }
    }

    /**
     * 统一返回：**先回上一屏**（导航栈），栈空才用旧目标表兜底。
     * 离开某屏需要的收尾（刷新、清参数）在这里按"正在离开的屏"做，和跳去哪无关。
     */
    fun backAction() {
        val leaving = currentScreenId
        when (leaving) {
            SCREEN_EDIT -> { timelineRefreshKey++; pendingEditorType = null; pendingEditRecordId = null; clearEditPrefill() }
            SCREEN_DETAIL -> pendingDetailRecordId = null
            SCREEN_TICK_FORM -> tickRefreshKey++
            SCREEN_SECURITY_QUESTION -> memore.app.utils.AuthManager.clear()
            SCREEN_DRAFT_ASK -> { pendingDraftAskKind = null; pendingDraftAskItems = emptyList() }
            SCREEN_THREAD_RECORDS -> { pendingThreadName = null; pendingThreadMembers = emptyList() }
            // 教程的定位只在这一次打开有效：离开就清掉，下次从设置进仍是上次读到的位置。
            SCREEN_TUTORIAL -> tutorialStartSection = null
        }
        if (leaving == SCREEN_DRAFT) { leaveDraft(); return }

        navDirection = NavDirection.Backward
        val prev = screenStack.removeLastOrNull()
        if (prev != null && prev != leaving) {
            setScreenRaw(prev)
        } else {
            legacyBack()
        }
    }
    LaunchedEffect(Unit) { val dc = prefs.getString("draft_content", null); if (!dc.isNullOrBlank()) showDraftRecovery = true }
    LaunchedEffect(isUnlocked) {
        if (isUnlocked && !unlockSoundPlayed && tutorialSeen) {
            unlockSoundPlayed = true
            UiSoundPlayer.playGreeting(context)
        }
    }

    // 根 insets（2026-11-18）：状态栏 / 手势条 / 刘海 / 键盘的安全区，在这里一次性让出。
    //   · Android 15（targetSdk 35）起系统强制 edge-to-edge：这些 inset 是真值，
    //     这一层保证页面不会被状态栏、手势条压住；
    //   · Android 12 及以下窗口由系统内缩：inset 为 0，这一层等于不存在，界面不变。
    // 用 safeDrawing 一并吃掉 ime，所以原来那句 imePadding() 由它代替（二者都会吃键盘 inset，
    // 同时写会叠两次）。padding 会向下消费 inset，页内自己写的 statusBarsPadding / TopAppBar
    // 的默认 insets 因此都变成 0，不会重复留白。
    // 底色照旧用页面背景：edge-to-edge 之后系统栏那一条露出来的就是它。
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        if (!isUnlocked) {
            PinLockScreen(onUnlock = { isUnlocked = true })
        } else if (!tutorialSeen) {
            // 2026-11-16：**跳过也必须落盘**。
            // 原实现 onSkip 只改内存里的 tutorialSeen，进程一死 prefs 仍为 false →
            // 下次冷启动又弹一遍引导（用户实际踩到过）。两个出口现在都写 prefs。
            // 落盘抽成 markTutorialDone()，好让单测能直接断言"写的是哪个键"。
            val markTutorialSeen = {
                markTutorialDone(prefs)
                tutorialSeen = true
            }
            TutorialOnboardingScreen(
                onFinish = { markTutorialSeen() },
                onSkip = { markTutorialSeen() },
                onOpenTutorial = {
                    markTutorialSeen()
                    navigate { goTo(SCREEN_TUTORIAL) }
                }
            )
        } else {
            // 系统返回键/侧滑手势：非主屏时执行应用内返回；主屏保持默认退出（底层弹窗/对话框自成 BackHandler 会优先接管）
            BackHandler(enabled = currentScreenId != SCREEN_MAIN) { backAction() }
            if (showDraftRecovery) {
                MirrorAlertDialog(
                    onDismissRequest = { showDraftRecovery = false },
                    title = { Text("发现未完成草稿") }, text = { Text("是否恢复上次未完成的内容？") },
                    confirmButton = { TextButton(onClick = { showDraftRecovery = false; val dt = prefs.getString("draft_type", null); pendingEditorType = dt?.let { try { RecordType.valueOf(it) } catch (_: Exception) { null } }; goTo(SCREEN_EDIT) }) { Text("恢复") } },
                    dismissButton = { TextButton(onClick = { showDraftRecovery = false; prefs.edit().remove("draft_type").remove("draft_content").apply() }) { Text("取消") } }
                )
            }
            Scaffold(
                bottomBar = {
                    if (currentScreenId == SCREEN_MAIN && !imeVisible && !inputFocused) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.background, contentColor = Color(0xFFC0B8D0)) {
                            bottomNavItems.forEachIndexed { i, item ->
                                NavigationBarItem(selected = currentTab == i, onClick = { navigate { currentTab = i } },
                                    icon = { Icon(item.icon, item.label) }, label = { Text(item.label) },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFFA78BFA), selectedTextColor = Color(0xFFA78BFA), unselectedIconColor = Color(0xFFC0B8D0), unselectedTextColor = Color(0xFFC0B8D0), indicatorColor = Color(0xFFA78BFA).copy(alpha = 0.1f)))
                            }
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { _ ->
                Box(Modifier.fillMaxSize()) {
                // 转场只有这一处（法条 8.2）：方向由栈给出，前进右进、返回左进
                MemoreNavHost(currentScreenId, navDirection, NavTransition.Page) { screenId ->
                when (screenId) {
                    SCREEN_MAIN -> when (currentTab) {
                        TAB_TIMELINE -> TimelineScreen(
                            onSearchFocusChanged = { inputFocused = it },
                            semanticSearch = { q -> container.retrievalService.searchOutcome(q) },recordRepository = container.recordRepository, tagRepository = container.tagRepository, tagGroupRepository = container.tagGroupRepository, protocolRepository = container.protocolRepository, refreshKey = timelineRefreshKey,
                            originNames = originNames.associate { o: memore.app.data.model.Origin -> o.id to o.name },
                            origins = originNames,
                            zoneRepository = container.zoneRepository,
                            onSetOriginFor = { ids, oid -> container.originRepository.assign(ids, oid) },
                            voiceTtsRepository = container.voiceTtsRepository, voiceSettingsRepository = container.voiceSettingsRepository,
                            redactionRepository = container.redactionRepository,
                            onCreateEssay = { navigate { pendingEditorType = RecordType.ESSAY; pendingEditRecordId = null; clearEditPrefill(); goTo(SCREEN_EDIT) } },
                            onCreateDialogue = { navigate { pendingEditorType = RecordType.DIALOGUE; pendingEditRecordId = null; clearEditPrefill(); goTo(SCREEN_EDIT) } },
                            onOpenTagManage = { navigate { goTo(SCREEN_TAG_MANAGE) } },
                            onOpenProtocolManage = { navigate { goTo(SCREEN_PROTOCOL_MANAGE) } },
                            onOpenMore = { navigate { goTo(SCREEN_TIMELINE_MORE) } },
                            // 2026-11-15：条件行的「筛选 N」直接进筛选页，不必绕「更多」
                            onOpenFilter = { navigate { goTo(SCREEN_FILTER) } },
                            enterBatch = pendingBatchEnter,
                            onBatchEntered = { pendingBatchEnter = false },
                            state = timelineState,
                            // 筛选整页与时间线共用 timelineState
                                    onOpenAlbum = { navigate { goTo(SCREEN_ALBUM) } },
                            onOpenTrash = { navigate { goTo(SCREEN_TRASH) } },
                            onOpenInsightArchive = { navigate { goTo(SCREEN_INSIGHT_ARCHIVE) } },
                            onOpenAnchor = { navigate { goTo(SCREEN_ANCHOR) } },
                            onOpenRecord = { id -> pendingDetailRecordId = id; detailReturnScreen = SCREEN_MAIN; goTo(SCREEN_DETAIL) },
                            // 真草稿：入口在时间线的「新建」菜单里，与随笔/对话录并列（第三个）
                            // ⚠️ 「新建」就**当场开一本新的**（用户 2026-10-14 报的 bug：进来直接落在
                            // 列表第一本上，而那一本可能是空的 → 顶上那个 ＋ 还是灰的，建不了新的）。
                            // 先建好拿到 id、再切页面，免得页面先按"没指定"挑了别本（挑定之后就不换了）。
                            onOpenDraft = { navigate {
                                mainScope.launch {
                                    pendingDraftId = container.draftRepository.createDraft("")
                                    draftScrollToNew = false
                                    pendingDraftFocusBlock = null
                                    goTo(SCREEN_DRAFT)
                                }
                            } },
                            // 转录来源：从记录卡回到那本草稿（并落到转录出去的那一块）
                            onOpenDraftFromRecord = { draftKey, blockKey -> navigate {
                                if (draftKey > 0) {
                                    pendingDraftId = draftKey
                                    pendingDraftFocusBlock = blockKey
                                    draftScrollToNew = false
                                    goTo(SCREEN_DRAFT)
                                }
                            } })
                        TAB_ANCHOR -> RecallScreen(
                            repository = container.recordRepository,
                        statsWeights = originNames.associate { o: memore.app.data.model.Origin -> o.id to o.wStats },
                            db = container.database,
                            onOpenRecord = { id -> pendingDetailRecordId = id; goTo(SCREEN_DETAIL) },
                            onOpenStats = { navigate { goTo(SCREEN_STATS) } },
                            onOpenTopicsPage = { m -> selectedMonth = m; navigate { goTo(SCREEN_TOPICS_PAGE) } },
  onOpenTopicsRoot = { navigate { goTo(SCREEN_TOPIC_MONTHS) } },
                            onOpenRecentInsight = { navigate { goTo(SCREEN_INSIGHT_RECENT) } }
                        )
                        TAB_TICKS -> TickScreen(
                            tickRepository = container.tickRepository,
                            onOpenTick = { tick -> navigate { tickFromDial = false; pendingTickId = tick.id; goTo(SCREEN_TICK_RECORDS) } },
                            dialRepository = container.dialRepository,
                            onOpenDial = { dialId -> navigate { pendingDialId = dialId; goTo(SCREEN_DIAL) } },
                            refreshKey = tickRefreshKey,
                            onNewTick = { tickFormIsDial = false; navigate { goTo(SCREEN_TICK_FORM) } },
                            onNewDial = { tickFormIsDial = true; navigate { goTo(SCREEN_TICK_FORM) } }
                        )
                        TAB_SETTINGS -> SettingsScreen(recordRepository = container.recordRepository, tagRepository = container.tagRepository, protocolRepository = container.protocolRepository, aliasMappingRepo = container.aliasMappingRepo, redactionRepository = container.redactionRepository, tickRepository = container.tickRepository,
                            onOpenEmotion = { navigate { goTo(SCREEN_EMOTION) } },
                            onOpenAliasMapping = { navigate { goTo(SCREEN_ALIAS_MAPPING) } },
                            onOpenPromptManage = { navigate { goTo(SCREEN_PROMPT_MANAGE) } },
                            onOpenVoiceLab = { navigate { goTo(SCREEN_VOICE_LAB) } },
                onOpenAiConfig = { navigate { goTo(SCREEN_AI_CONFIG) } },
                onOpenVectorIndex = { navigate { goTo(SCREEN_VECTOR_INDEX) } },
                    onOpenAgentLink = { navigate { goTo(SCREEN_AGENT_LINK) } },
        onOpenEncryptionManage = { navigate { goTo(SCREEN_ENCRYPTION_MANAGE) } },
        onOpenEncryptionSetup = { navigate { goTo(SCREEN_ENCRYPTION_SETUP) } },
                onOpenInsightArchive = { navigate { goTo(SCREEN_INSIGHT_ARCHIVE) } },
                        onOpenTopics = { navigate { goTo(SCREEN_TOPIC_MONTHS) } },
                onOpenFixTools = { navigate { goTo(SCREEN_FIX_TOOLS) } },
                onOpenPinSetup = { navigate { goTo(SCREEN_PIN_SETUP) } },
                            onOpenTutorial = { navigate { goTo(SCREEN_TUTORIAL) } },
                            onOpenRedactionExport = { navigate { goTo(SCREEN_REDACTION) } },
                            onOpenSystemInstruction = { navigate { goTo(SCREEN_SYSTEM_INSTRUCTION) } })
                    }
                    SCREEN_ALIAS_MAPPING -> AliasMappingScreen(
                        repo = container.aliasMappingRepo,
                        onBack = { backAction() },
                        onEditGroup = { group -> pendingEditAliasGroup = group; goTo(SCREEN_ALIAS_EDIT) }
                    )
                    SCREEN_ALIAS_EDIT -> AliasMappingEditScreen(
                        repo = container.aliasMappingRepo,
                        editGroup = pendingEditAliasGroup,
                        onBack = { backAction() },
                        // 2026-11-21 修：保存后必须走「返回」（弹栈），不能再 goTo(列表)。
                        // 原来 goTo 会把当前这屏（编辑）压进栈：列表 → 返回 → 又看到编辑页，
                        // 返回键看起来"回到编辑映射不止一次"。
                        onSaved = { backAction() }
                    )
                    SCREEN_EDIT -> EditRecordScreen(initialType = pendingEditorType, recordRepository = container.recordRepository, tagRepository = container.tagRepository, tagGroupRepository = container.tagGroupRepository, protocolRepository = container.protocolRepository, existingRecordId = pendingEditRecordId,
                        prefillContent = pendingEditPrefill, prefillReply = pendingEditPrefillReply,
                        sourceDraftId = pendingEditSourceDraftId, sourceBlockId = pendingEditSourceBlockId,
                   originRepository = container.originRepository,
                        // 2026-11-17：新建页的「＋区域」与「预设」
                        zoneRepository = container.zoneRepository,
                        presetRepository = container.presetRepository,
                        onBack = { backAction() })
                    SCREEN_DETAIL -> androidx.compose.runtime.key(pendingDetailRecordId) { RecordDetailScreen(recordId = pendingDetailRecordId ?: "", repository = container.recordRepository, onBack = { backAction() }, onOpenInsight = { id -> pendingInsightRecordId = id; navigate { goTo(SCREEN_INSIGHT_RECORD) } }, showRelated = detailReturnScreen != SCREEN_ALBUM, onOpenRecord = { id -> pendingDetailRecordId = id }, loadSimilar = { id -> container.retrievalService.similar(id).map { it.record to it.snippet } }, loadLinks = { id -> container.recordRepository.linksFor(id) }, origins = originsAll, onSetOrigin = { oid -> mainScope.launch { container.originRepository.setOriginOf(pendingDetailRecordId ?: "", oid) } }) }
                    SCREEN_LINKS -> LinkListScreen(
                        state = linkListState,
                        loadLinks = { container.recordRepository.allLinksWithRecords() },
                        loadAnchors = { container.anchorRepository.groups() },
                        onOpenRecord = { id -> pendingDetailRecordId = id; detailReturnScreen = SCREEN_LINKS; goTo(SCREEN_DETAIL) },
                        onReject = { a, b -> container.recordRepository.rejectLink(a, b) },
                        // 2026-11-21：概念长按菜单——改名只是跳转，删除只删锚点行。
                        onOpenAliasMapping = { navigate { goTo(SCREEN_ALIAS_MAPPING) } },
                        onDeleteConcept = { name -> container.anchorRepository.deleteConcept(name) },
                        onBack = { backAction() }
                    )
                    SCREEN_AI_RUNS -> AiRunLogScreen(
                        runs = remember { memore.app.data.vector.AiRunLog.read(context, 60) },
                        onBack = { backAction() }
                    )
                    SCREEN_LETTER -> LetterScreen(
                context = context,
                onOpenRecords = { id -> pendingLetterActionId = id; goTo(SCREEN_LETTER_RECORDS) },
                onOpenHistory = { goTo(SCREEN_LETTER_HISTORY) },
                onBack = { backAction() }
            )
            SCREEN_LETTER_HISTORY -> LetterHistoryScreen(
                context = context,
                onOpenRecords = { id -> pendingLetterActionId = id; goTo(SCREEN_LETTER_RECORDS) },
                onBack = { backAction() }
            )
            SCREEN_LETTER_RECORDS -> ActionRecordsScreen(
                context = context,
                actionId = pendingLetterActionId,
                onBack = { backAction() }
            )
            SCREEN_AGENT_LINK -> AgentLinkScreen(
                context = context,
                onOpenLetter = { goTo(SCREEN_LETTER) },
                onOpenTutorial = { section ->
                    tutorialStartSection = section
                    navigate { goTo(SCREEN_TUTORIAL) }
                },
                onBack = { backAction() }
            )
            SCREEN_TAG_RECORDS -> GroupRecordsScreen(
                title = "#" + pendingGroupName,
                load = {
                    val ids = container.database.recordDao().recordIdsByTagName(pendingGroupName)
                    container.recordRepository.getRecordsByIds(ids)
                },
                onOpenRecord = { id ->
                    pendingDetailRecordId = id; detailReturnScreen = SCREEN_TAG_RECORDS; navigate { goTo(SCREEN_DETAIL) }
                },
                onBack = { backAction() }
            )
            SCREEN_PROTOCOL_RECORDS -> GroupRecordsScreen(
                title = pendingGroupName,
                load = {
                    val ids = container.database.recordDao().recordIdsByProtocolName(pendingGroupName)
                    container.recordRepository.getRecordsByIds(ids)
                },
                onOpenRecord = { id ->
                    pendingDetailRecordId = id; detailReturnScreen = SCREEN_PROTOCOL_RECORDS; navigate { goTo(SCREEN_DETAIL) }
                },
                onBack = { backAction() }
            )
            SCREEN_VECTOR_INDEX -> VectorIndexScreen(
                        loadStats = { runCatching { container.vectorIndexService.stats() }.getOrNull() },
                        loadOverview = {
                            val links = runCatching { container.recordRepository.linkCount() }.getOrDefault(0)
                            // 2026-11-21：这里要显示的是**归并后**的概念数（关联页的「N 个概念」），
                            // 不是 record_entities 的原始行数 —— 否则映射把 4 个别名并成 1 个，
                            // 这边还显示 4，看起来像没生效。
                            val anchorRows = runCatching {
                                container.anchorRepository.groups().size
                            }.getOrDefault(0)
                            val runs = runCatching { memore.app.data.vector.AiRunLog.read(context, 50) }.getOrDefault(emptyList())
                            VectorOverview(
                                total = runCatching { container.recordRepository.getAllActiveRecords().size }.getOrDefault(0),
                                linkCount = links,
                                anchorCount = anchorRows,
                                lastRunText = runs.firstOrNull()?.atText,
                                okRuns = runs.count { it.ok },
                                failedRuns = runs.count { !it.ok }
                            )
                        },
                        onRebuildIndex = { container.vectorIndexService.rebuildInBackground() },
                        onOpenLinks = { goTo(SCREEN_LINKS) },
                        onOpenAiRuns = { goTo(SCREEN_AI_RUNS) },
                        onBack = { backAction() }
                    )

                    SCREEN_EMOTION -> EmotionScreen(
                        recordRepository = container.recordRepository,
                        onBack = { backAction() },
                        onViewEmotionRecords = { tag -> pendingEmotionTag = tag; emotionReturnToChart = false; goTo(SCREEN_EMOTION_RECORDS) },
                        onOpenChart = { navigate { goTo(SCREEN_EMOTION_CHART) } },
                        onOpenTrend = { navigate { goTo(SCREEN_EMOTION_TREND) } }
                    )
                    SCREEN_EMOTION_TREND -> EmotionTrendScreen(
                        recordRepository = container.recordRepository,
                        onBack = { backAction() },
                        onViewEmotionRecords = { tag -> pendingEmotionTag = tag; emotionReturnToChart = false; goTo(SCREEN_EMOTION_RECORDS) }
                    )
                    SCREEN_EMOTION_CHART -> EmotionChartScreen(
                        recordRepository = container.recordRepository,
                        onBack = { backAction() },
                        onViewEmotionRecords = { tag -> pendingEmotionTag = tag; emotionReturnToChart = true; goTo(SCREEN_EMOTION_RECORDS) }
                    )
                    SCREEN_EMOTION_RECORDS -> EmotionRecordsScreen(
                        recordRepository = container.recordRepository,
                        emotionTag = pendingEmotionTag,
                        onBack = { backAction() },
                        onOpenRecord = { id -> detailReturnScreen = SCREEN_EMOTION_RECORDS; pendingDetailRecordId = id; goTo(SCREEN_DETAIL) }
                    )
                    SCREEN_TAG_MANAGE -> TagManageScreen(
                tagRepository = container.tagRepository,
                tagGroupRepository = container.tagGroupRepository,
                onOpenRecords = { name -> pendingGroupName = name; navigate { goTo(SCREEN_TAG_RECORDS) } },
                onBack = { backAction() }
            )
                    SCREEN_PROTOCOL_MANAGE -> ProtocolManageScreen(
                protocolRepository = container.protocolRepository,
                onOpenRecords = { name -> pendingGroupName = name; navigate { goTo(SCREEN_PROTOCOL_RECORDS) } },
                onBack = { backAction() }
            )
                    SCREEN_PROMPT_MANAGE -> PromptManagerScreen(
                        onBack = { backAction() }
                    )
                    SCREEN_TICK_FORM -> TickFormScreen(
                        title = if (tickFormIsDial) "新建表盘" else "新建刻度",
                        submitLabel = "创建",
                        onSave = { name, start, end ->
                            mainScope.launch {
                                if (tickFormIsDial) container.dialRepository.createDial(name, start, end)
                                else container.tickRepository.create(name, start, end)
                            }
                            tickRefreshKey++
                            currentTab = TAB_TICKS; goTo(SCREEN_MAIN)
                        },
                        onBack = { backAction() }
                    )
                    SCREEN_PIN_SETUP -> PinSetupScreen(
                        onComplete = { goTo(SCREEN_SECURITY_QUESTION) },
                        onBack = { backAction() }
                    )
                    SCREEN_SECURITY_QUESTION -> SecurityQuestionScreen(
                        onComplete = { currentTab = TAB_SETTINGS; goTo(SCREEN_MAIN) },
                        onBack = { backAction() }
                    )
                    SCREEN_FIX_TOOLS -> FixToolsScreen(
                        onOpenRedaction = { navigate { goTo(SCREEN_REDACTION) } },
                        onOpenSystemInstruction = { navigate { goTo(SCREEN_SYSTEM_INSTRUCTION) } },
                        // 清空关联/概念后让「关联」页的内存缓存失效，否则它还显示旧列表。
                        onClearCaches = {
                            linkListState.linksLoaded = false
                            linkListState.links = emptyList()
                            linkListState.anchorsLoaded = false
                            linkListState.anchors = emptyList()
                        },
                        onBack = { backAction() }
                    )
                    SCREEN_AI_CONFIG -> AiConfigScreen(onBack = { backAction() })
                    // 2026-11-16：加密管理 / 加密设置 —— 真正的路由屏，不再是设置页里的覆盖层
                    SCREEN_ENCRYPTION_MANAGE -> EncryptionManageScreen(
                        recordRepository = container.recordRepository,
                        onBack = { backAction() },
                        // 没有密码时**替换**而不是压栈：若压栈，从设置加密返回会回到管理屏，
                        // 它又立刻发现"没有密码"再送你去设置加密 —— 死循环。
                        onNeedSetup = { navigate { setScreenRaw(SCREEN_ENCRYPTION_SETUP) } }
                    )
                    SCREEN_ENCRYPTION_SETUP -> EncryptionSetupScreen(
                        recordRepository = container.recordRepository,
                        onComplete = { currentTab = TAB_SETTINGS; navigate { goTo(SCREEN_MAIN) } },
                        onBack = { backAction() }
                    )
                    SCREEN_VOICE_LAB -> VoiceLabScreen(
                        voiceSettingsRepository = container.voiceSettingsRepository,
                        voiceTtsRepository = container.voiceTtsRepository,
                        onBack = { backAction() }
                    )
                    SCREEN_TUTORIAL -> TutorialScreen(
                        onBack = { backAction() },
                        startSection = tutorialStartSection
                    )
                    SCREEN_DRAFT -> DraftScreen(
                        draftId = pendingDraftId,
                        draftRepository = container.draftRepository,
                        focusBlockId = pendingDraftFocusBlock,
                        // 草稿页把"现在到底开着哪一本"报上来：离开时要清空本、去作答页要带对 id
                        onCurrentDraftChanged = { pendingDraftId = it },
                        scrollToNew = draftScrollToNew,
                        onScrollToNewHandled = { draftScrollToNew = false },
                        // 「转录」：把这一块做成一条记录 —— 随笔进正文框，对话录进第二个框（AI 那栏）
                        onTranscribe = { text, target, sourceDraft, sourceBlock ->
                            pendingEditPrefill = if (target == RecordType.ESSAY) text else null
                            pendingEditPrefillReply = if (target == RecordType.DIALOGUE) text else null
                            pendingEditSourceDraftId = sourceDraft
                            pendingEditSourceBlockId = sourceBlock
                            pendingEditorType = target
                            pendingEditRecordId = null
                            navigate { goTo(SCREEN_EDIT) }
                        },
                        onOpenAsk = { draftKey, kind, items ->
                            pendingDraftId = draftKey
                            pendingDraftAskKind = kind
                            pendingDraftAskItems = items
                            navigate { goTo(SCREEN_DRAFT_ASK) }
                        }
                    )
                    SCREEN_DRAFT_ASK -> DraftAskScreen(
                        draftId = pendingDraftId,
                        kind = pendingDraftAskKind ?: memore.app.data.model.DraftKind.QA,
                        items = pendingDraftAskItems,
                        draftRepository = container.draftRepository,
                        onDone = {
                            pendingDraftAskKind = null
                            pendingDraftAskItems = emptyList()
                            // 回来时滚到刚提交的那一块（否则落在顶部，新内容在底部看不见）
                            draftScrollToNew = true
                            goTo(SCREEN_DRAFT)
                        }
                    )
                    SCREEN_ALBUM -> AlbumScreen(
                        recordRepository = container.recordRepository,
                        onBack = { backAction() },
                        onOpenRecord = { id -> detailReturnScreen = SCREEN_ALBUM; pendingDetailRecordId = id; goTo(SCREEN_DETAIL) }
                    )
                    SCREEN_SYSTEM_INSTRUCTION -> SystemInstructionScreen(
                        onBack = { backAction() }
                    )
                    SCREEN_REDACTION -> RedactionExportScreen(
                        redactionRepository = container.redactionRepository,
                        onBack = { backAction() }
                    )
    
                SCREEN_INSIGHT_RECORD -> InsightRecordScreen(recordId = pendingInsightRecordId ?: "", repository = container.recordRepository, onBack = { backAction() })
                SCREEN_FILTER -> FilterScreen(
                    state = timelineState,
                    onBack = { navigate { backAction() } },
                    // 2026-11-15：应用筛选后直接回时间线，而不是退回「更多」菜单。
                    // 曾经这里只有 onBack，于是从「更多」进来会被弹回「更多」，卡手一次。
                    onApplied = { navigate { goTo(SCREEN_MAIN) } },
                    tagRepository = container.tagRepository,
                    protocolRepository = container.protocolRepository
                )
                SCREEN_TIMELINE_MORE -> TimelineMoreScreen(
                    onBack = { navigate { backAction() } },
                    onBatchSelect = { pendingBatchEnter = true; navigate { goTo(SCREEN_MAIN) } },
                    onOpenTrash = { navigate { goTo(SCREEN_TRASH) } },
                    onOpenFilter = { navigate { goTo(SCREEN_FILTER) } },
                onOpenOrigin = { navigate { goTo(SCREEN_ORIGIN) } },
                    onOpenTagManage = { navigate { goTo(SCREEN_TAG_MANAGE) } },
                    onOpenProtocolManage = { navigate { goTo(SCREEN_PROTOCOL_MANAGE) } },
                    // 2026-11-15：图文集与锚点从时间线顶栏移到这里（顶栏要给排序腾位）
                    onOpenAlbum = { navigate { goTo(SCREEN_ALBUM) } },
                    onOpenAnchor = { navigate { goTo(SCREEN_ANCHOR) } },
                    db = container.database,
                    recordRepository = container.recordRepository,
                    tagRepository = container.tagRepository,
                    tagGroupRepository = container.tagGroupRepository,
                    protocolRepository = container.protocolRepository,
                    // 2026-11-15：这个参数以前从没传过，那一行永远显示「未设置条件」。
                    // 现在按 timelineState 的维度拼成一行（只报数量，不报名字——名字会很长）。
                    filterSummary = run {
                        val parts = buildList {
                            if (timelineState.filterTagIds.isNotEmpty()) add("标签 ${timelineState.filterTagIds.size} 个")
                            if (timelineState.filterProtocolIds.isNotEmpty()) add("协议 ${timelineState.filterProtocolIds.size} 个")
                            if (timelineState.filterStart != null || timelineState.filterEnd != null) add("时间")
                            timelineState.filterHasAttachment?.let { add(if (it) "有附件" else "无附件") }
                            timelineState.filterLocked?.let { add(if (it) "已加密" else "未加密") }
                        }
                        if (parts.isEmpty()) null else parts.joinToString(" · ")
                    }
                )
                SCREEN_ORIGIN -> OriginScreen(
                        repository = container.originRepository,
                        onBack = { navigate { backAction() } },
                        debugDetailId = (context as? android.app.Activity)?.intent?.getStringExtra("originId"),
                        debugConfirmDelete = (context as? android.app.Activity)?.intent?.getBooleanExtra("confirmDelete", false) == true,
                        debugRestoreOriginal = (context as? android.app.Activity)?.intent?.getBooleanExtra("restoreOrigin", false) == true,
            onOpenRecords = { id, name ->
                pendingOriginRecords = id to name
                navigate { goTo(SCREEN_ORIGIN_RECORDS) }
            }
                    )
                SCREEN_ORIGIN_RECORDS -> pendingOriginRecords?.let { (oid, oname) ->
                OriginRecordsScreen(
                    originId = oid,
                    originName = oname,
                    repository = container.originRepository,
                    onOpenRecord = { rid ->
                        pendingDetailRecordId = rid
                        detailReturnScreen = SCREEN_ORIGIN_RECORDS
                        navigate { goTo(SCREEN_DETAIL) }
                    },
                    onBack = { backAction() }
                )
            }
            SCREEN_INSIGHT_ARCHIVE -> InsightArchiveScreen(repository = container.recordRepository, onBack = { backAction() }, onOpenRecord = { id -> pendingDetailRecordId = id; navigate { goTo(SCREEN_DETAIL) } })
                SCREEN_STATS -> StatsScreen(repository = container.recordRepository, onBack = { backAction() }, onOpenEmotion = { name -> pendingEmotionTag = name; navigate { goTo(SCREEN_EMOTION_RECORDS) } })
                SCREEN_TOPICS_PAGE -> TopicMonthScreen(repository = container.recordRepository, db = container.database, initialMonth = selectedMonth, onBack = { backAction() })
                SCREEN_TOPIC_MONTHS -> TopicMonthsScreen(
                    repository = container.recordRepository,
                    db = container.database,
                    onBack = { backAction() },
                    onOpenMonth = { m -> selectedMonth = m; navigate { goTo(SCREEN_TOPICS_PAGE) } },
                    onOpenThreads = { navigate { goTo(SCREEN_TOPIC_THREADS) } }
                )
                SCREEN_TOPIC_THREADS -> TopicThreadsScreen(
                    repository = container.recordRepository,
                    db = container.database,
                    onBack = { backAction() },
                    onOpenThread = { name, members ->
                        pendingThreadName = name
                        pendingThreadMembers = members
                        navigate { goTo(SCREEN_THREAD_RECORDS) }
                    }
                )
                SCREEN_THREAD_RECORDS -> pendingThreadName?.let { name ->
                    ThreadRecordsScreen(
                        repository = container.recordRepository,
                        db = container.database,
                        threadName = name,
                        members = pendingThreadMembers,
                        onBack = { backAction() },
                        onOpenRecord = { id ->
                            pendingDetailRecordId = id
                            detailReturnScreen = SCREEN_THREAD_RECORDS
                            navigate { goTo(SCREEN_DETAIL) }
                        }
                    )
                }
                SCREEN_ANCHOR -> ZoneScreen(
                    container = container,
                    onBack = { backAction() },
                    onOpenRecord = { id ->
                        pendingDetailRecordId = id
                        detailReturnScreen = SCREEN_ANCHOR
                        goTo(SCREEN_DETAIL)
                    }
                )
                SCREEN_TRASH -> TrashScreen(
                        repository = container.recordRepository,
                        tagRepository = container.tagRepository,
                        protocolRepository = container.protocolRepository,
                        voiceTtsRepository = container.voiceTtsRepository,
                        tickRepository = container.tickRepository,
                        onBack = { backAction() }
                    )
                    SCREEN_DIAL -> DialScreen(
                        dialId = pendingDialId,
                        dialRepository = container.dialRepository,
                        onBack = { backAction() },
                        onOpenRecord = { id -> detailReturnScreen = SCREEN_DIAL; pendingDetailRecordId = id; goTo(SCREEN_DETAIL) },
                        // 点表盘里的一段 → 进那一段自己的页面；返回时回到表盘（不是回秒针列表）
                        onOpenTick = { tickId -> tickFromDial = true; pendingTickId = tickId; goTo(SCREEN_TICK_RECORDS) },
                        onOpenStat = { kind -> pendingDialStat = kind; goTo(SCREEN_DIAL_STAT) }
                    )
                    SCREEN_DIAL_STAT -> DialRankScreen(
                        dialId = pendingDialId,
                        kind = pendingDialStat,
                        dialRepository = container.dialRepository,
                        onBack = { backAction() }
                    )
                    SCREEN_TICK_RECORDS -> TickRecordsScreen(
                        tickRepository = container.tickRepository,
                        tickId = pendingTickId ?: "",
                        reviewService = container.reviewService,
                        onBack = { backAction() },
                        onOpenRecord = { id -> detailReturnScreen = SCREEN_TICK_RECORDS; pendingDetailRecordId = id; goTo(SCREEN_DETAIL) }
                    )
                }
                }

                // 角落里那封信：只挂在四个主 TAB 屏上（2026-11-13）。
                // 它是全局层，不写四遍；有未读才露得更多，没未读只露一点。
                if (currentScreenId == SCREEN_MAIN && !imeVisible && !inputFocused) {
                    LetterNub(
                        count = letterItems.size,
                        // 2026-11-15：点一下**直接进信页**，不再弹小窗。
                        // 小窗既放不下"刚做的/撤回"，又挡在时间线上被误点。
                        onClick = { navigate { goTo(SCREEN_LETTER) } },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 84.dp)
                    )
                }

                // 待批准动作：只在主屏轮询（5 秒一次）。信必须能自己更新——
                // 否则 agent 提了动作，用户不切屏就永远看不到那封信。
                LaunchedEffect(currentScreenId) {
                    while (currentScreenId == SCREEN_MAIN) {
                        letterItems = runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                letterActions.pendingActions()
                            }
                        }.getOrDefault(emptyList())
                        kotlinx.coroutines.delay(5000)
                    }
                }
                }
            }
        }
    }
}

/** 命令 → 一句人话（收件箱和信共用，2026-11-14）。 */
private fun describeAgentCommand(cmd: memore.app.data.model.AgentCommand): String = runCatching {
    val p = org.json.JSONObject(cmd.payload)
    when (cmd.kind) {
        memore.app.data.model.AgentCommand.KIND_TAG -> {
            val n = p.optJSONArray("record_ids")?.length() ?: 0
            "给 $n 条记录打标签"
        }
        memore.app.data.model.AgentCommand.KIND_CREATE -> "新建一条记录"
        memore.app.data.model.AgentCommand.KIND_AMEND -> "给一条记录加增修"
        memore.app.data.model.AgentCommand.KIND_ORIGIN -> "指派来源"
        memore.app.data.model.AgentCommand.KIND_RELATE -> "建立关联"
        memore.app.data.model.AgentCommand.KIND_TAG_MERGE -> "合并两个标签"
        else -> cmd.kind
    }
}.getOrDefault(cmd.kind)




