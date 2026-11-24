package memore.app.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.EmotionStats
import memore.app.data.model.Record
import memore.app.data.repository.RecordRepository
import memore.app.ui.components.MirrorLoading
import memore.app.ui.theme.MemoreType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 回顾（2026-11-07 报刊版面）。
 *
 * 版面：报头 → 数据栏 → 情绪主线 → 本月主题 → 档案（居中衬线标题 + 节选说明）→ 页脚
 * 「每月主题」是独立页面，这里只显示本月三条。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecallScreen(
    repository: RecordRepository?,
    statsWeights: Map<String, Float> = emptyMap(),
    db: memore.app.data.local.AppDatabase,
    onOpenRecord: (String) -> Unit,
    onOpenTopics: (String) -> Unit = {},
  onOpenTopicsRoot: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenTopicsPage: (String) -> Unit = {},
    onOpenRecentInsight: () -> Unit = {},
    onOpenEmotion: () -> Unit = {}
) {
    var loaded by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf<List<Record>>(emptyList()) }
    /** 「档案」每天一条节选（2026-11-17 恢复：这是回顾页原本就有的段落，我之前误删）。 */
    var headline by remember { mutableStateOf<Record?>(null) }
    var weekCount by remember { mutableIntStateOf(0) }
    var weekChars by remember { mutableIntStateOf(0) }
    var monthCount by remember { mutableIntStateOf(0) }
    /** 累计期数：从第一条记录所在月开始算第 1 期（不是日历月份）。 */
    var issueNumber by remember { mutableIntStateOf(1) }
    var medianHour by remember { mutableIntStateOf(0) }
    var emotionLine by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    var monthTopics by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var recentInsight by remember { mutableStateOf<memore.app.data.vector.InsightService.Entry?>(null) }
    // 2026-11-23：回顾页以"回顾"为主，近一月洞察默认折叠。
    var recentExpanded by rememberSaveable { mutableStateOf(false) }
    var recentDialog by remember { mutableStateOf(false) }
    var recentRunning by remember { mutableStateOf(false) }
    var recentErr by remember { mutableStateOf<String?>(null) }
    val recentModeState = memore.app.ui.components.rememberInsightMode()
    var recentMode by recentModeState
    var recentRan by remember { mutableStateOf(false) }
    var recentClaims by remember { mutableStateOf<List<memore.app.data.vector.InsightService.Claim>>(emptyList()) }
    var insightRunning by remember { mutableStateOf(false) }
    var insightError by remember { mutableStateOf<String?>(null) }
    val insightCtx = androidx.compose.ui.platform.LocalContext.current
    val insightScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val list = runCatching { repository?.getAllActiveRecords() }.getOrNull().orEmpty()
                    .let { src ->
                        // 来源权重（2026-11-11）：wStats = 0 的来源不进统计（如「转录（AI）」）
                        if (statsWeights.isEmpty()) src
                        else src.filter { r -> (r.originId?.let { statsWeights[it] } ?: 1f) > 0f }
                    }
            .filter { !it.isLocked && it.content.isNotBlank() }
        all = list
        // 2026-11-23：期数从"第一条记录所在月"开始累计，不再直接拿日历月份。
        val firstAt = list.minOfOrNull { it.createdAt }
        if (firstAt != null) {
            val f = Calendar.getInstance().apply { timeInMillis = firstAt }
            val c = Calendar.getInstance()
            issueNumber = (c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)) -
                (f.get(Calendar.YEAR) * 12 + f.get(Calendar.MONTH)) + 1
        } else {
            issueNumber = 1
        }
        val now = System.currentTimeMillis()
        val week = list.filter { it.createdAt >= now - 7L * 86_400_000L }
        weekCount = week.size
        weekChars = week.sumOf { it.content.length }
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        monthCount = list.count { it.createdAt >= cal.timeInMillis }
        val hours = week.map { val c = Calendar.getInstance(); c.timeInMillis = it.createdAt; c.get(Calendar.HOUR_OF_DAY) }.sorted()
        medianHour = if (hours.isEmpty()) 0 else hours[hours.size / 2]

        val acc = HashMap<String, Float>()
        list.filter { it.createdAt >= now - 30L * 86_400_000L }.forEach { r ->
            EmotionStats.emotionEntries(r).forEach { e -> acc[e.name] = (acc[e.name] ?: 0f) + e.weight }
        }
        emotionLine = acc.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }

        // 「档案」：从 90 天以前挑一条（没有就退回全部），按当天日期做种子，当天不变
        val older = list.filter { it.createdAt < now - 90L * 86_400_000L }
        val pool = (older.ifEmpty { list }).filter { it.content.length >= 30 }
        val seed = (now / 86_400_000L).toInt()
        headline = if (pool.isEmpty()) null else pool[Math.floorMod(seed, pool.size)]

        loaded = true
    }

    // 本月主题：**只读缓存**（2026-11-17）。刷新改为主题页的显式按钮，
    // 打开回顾不再偷偷跑全库聚类 + 十几次 AI 命名。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(loaded) {
        val repo = repository ?: return@LaunchedEffect
        // 只读缓存：不传 namer，回顾页永远不触发聚类/AI 命名。刷新在主题页的显式按钮。
        val svc = memore.app.data.vector.MonthlyTopicsService(
            context = ctx, repository = repo, db = db
        )
        val snap = runCatching { svc.cached() }.getOrNull()
        monthTopics = MonthlyTopicsMonthOf(snap?.months ?: emptyList())
    }

    val insightSvc = remember { repository?.let { memore.app.data.vector.InsightService(insightCtx, it) } }
    LaunchedEffect(insightSvc) {
        recentInsight = runCatching { insightSvc?.latestRecent() }.getOrNull()
    }

    val fmt = remember { SimpleDateFormat("M 月 d 日", Locale.getDefault()) }
    val yearMonth = remember {
        val c = Calendar.getInstance()
        val cn = "〇一二三四五六七八九"
        val y = c.get(Calendar.YEAR).toString().map { cn[it - '0'] }.joinToString("")
        "$y 年 ${c.get(Calendar.MONTH) + 1} 月"
    }

    if (recentDialog) {
        memore.app.ui.components.MirrorStackDialog(
            title = "近一月",
            confirmText = when { recentRunning -> "读取中…"; recentClaims.isEmpty() -> "开始"; else -> "保存" },
            onConfirm = {
                when {
                    recentRunning -> Unit
                    recentClaims.isEmpty() -> {
                        recentErr = null; recentRunning = true
                        insightScope.launch {
                            insightSvc?.askRecent(recentMode)
                                ?.onSuccess { recentClaims = it; recentRan = true; recentRunning = false }
                                ?.onFailure { recentErr = it.message; recentRunning = false }
                        }
                    }
                    else -> {
                        val svc = insightSvc
                        if (svc != null) {
                            svc.save("recent", emptyList(), recentClaims, meta = "近一月 · ${recentClaims.size} 条", template = recentMode)
                            recentInsight = svc.latestRecent()
                        }
                        recentDialog = false
                    }
                }
            },
            onDismiss = { recentDialog = false }
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                memore.app.ui.components.InsightModeHeader(recentMode) {
                    recentMode = it; recentClaims = emptyList(); recentErr = null
                }
                Spacer(Modifier.height(10.dp))
                when {
                    recentRunning -> {
                        memore.app.ui.components.MirrorLoading()
                        Text("正在读这 30 天", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    recentErr != null -> Text(recentErr ?: "", style = MemoreType.meta, color = MaterialTheme.colorScheme.error)
                    recentClaims.isEmpty() && !recentRan -> Text(
                        "点「开始」", style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    recentClaims.isEmpty() -> {
                        Text("这次没读出东西", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val d = insightSvc?.lastDiag.orEmpty()
                        if (d.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(d, style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                    else -> memore.app.ui.components.InsightCardCompact(recentClaims)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            // 2026-11-15：原来这里是 title = {} 的空 TopAppBar，只有右上角一个灯泡——
            // 白占 64dp，让回顾的首行比其它三屏低 40dp。
            // 现在把报头（年月 / 第 N 期）搬进标题栏，灯泡仍在右侧：高度与设置、秒针一致，
            // 也没有多出第二行标题。
            TopAppBar(
                title = {
                    Text(
                        yearMonth,
                        style = MemoreType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                actions = {
                    Text(
                        "第 $issueNumber 期",
                        style = MemoreType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = {
                        recentClaims = emptyList(); recentErr = null; recentRunning = false; recentRan = false; recentDialog = true
                    }) {
                        Icon(
                            androidx.compose.material.icons.Icons.Outlined.Lightbulb,
                            contentDescription = "洞察",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (!loaded) {
            MirrorLoading(Modifier.padding(padding))
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 报头已经进标题栏，这里只留分隔线
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))
                Spacer(Modifier.height(14.dp))

                // ── 近一月（2026-11-23：默认折叠，只留日期；展开才看洞察卡）──
                if (recentInsight != null) {
                    recentInsight?.let { e ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { recentExpanded = !recentExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                fmt.format(Date(e.at)),
                                style = MemoreType.micro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (recentExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (recentExpanded) "收起洞察" else "展开洞察",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (recentExpanded) {
                            Spacer(Modifier.height(4.dp))
                            memore.app.ui.components.InsightCard(
                                scope = "",
                                date = fmt.format(Date(e.at)),
                                claims = e.claims,
                                showHeader = false
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // 数据栏（能进统计 → 右侧给 ›，法 2.5：这是一行可进入的数据）
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenStats() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "本周 $weekCount 条 · ${formatCount(weekChars)} 字 · 多在 ${two(medianHour)} 点写 · 本月 $monthCount 条",
                        style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text("›", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }

                // 情绪主线（点进去看完整；2026-11-17：去掉彩色小条 —— 颜色只给状态与主动作，
                // 这里改成"名字 + 次数"，是数据不是图表）
                if (emotionLine.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onOpenStats() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("情绪主线", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        emotionLine.forEachIndexed { index, (name, w) ->
                            if (index > 0) Text(" · ", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            Text(name, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurface)
                            Text(" ${w.toInt()}", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("›", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }

                // 本月主题（点进去看每月）
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clickable { onOpenTopicsPage(currentMonthKey()) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本月主题", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("›", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                    if (monthTopics.isEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp).clickable { onOpenTopicsRoot() }) {
                            Text("还没有主题", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("去刷新 ›", style = MemoreType.meta, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    monthTopics.take(3).forEach { (name, count) ->
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                            Text(name, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("$count", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))

                // 「档案」：2026-11-17 恢复。上一版我把它当成"可以由模板删掉"的段落——
                // 那是我的模板越权，回顾页本来就有这一段：居中衬线标题 + 节选说明 + 12 行正文。
                headline?.let { r ->
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(28.dp))
                        Text(
                            "档案",
                            style = MemoreType.sectionTitle.copy(letterSpacing = 4.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val pool = all.filter { it.content.length >= 30 && it.id != headline?.id }
                                if (pool.isNotEmpty()) headline = pool[(System.nanoTime() % pool.size).toInt()]
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Refresh, "换一条",
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "节选自：${fmt.format(Date(r.createdAt))}",
                        style = MemoreType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        r.content.trim().replace(Regex("\\s+"), " "),
                        style = MemoreType.reading,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpenRecord(r.id) }
                    )
                }

                // 底部留白：外层 NavigationBar 是浮在内容上的（MainActivity 的 Scaffold 忽略了自己的 padding），
                // 这里只留 30dp 会让最后几行被底栏压住、看起来"滑不下去了"（2026-11-10 报的）。
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

private fun currentMonthKey(): String {
    val c = Calendar.getInstance()
    return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}

/** 取本月主题（服务返回的是全部月份，这里只挑当前月，按条数降序）。 */
private fun MonthlyTopicsMonthOf(months: List<memore.app.data.vector.MonthlyTopicsService.NamedMonth>): List<Pair<String, Int>> {
    val key = currentMonthKey()
    val m = months.firstOrNull { it.month == key } ?: return emptyList()
    return m.clusters.sortedByDescending { it.count }.map { it.name to it.count }
}

private fun two(v: Int): String = if (v < 10) "0$v" else "$v"

private fun formatCount(n: Int): String =
    if (n >= 10000) String.format(Locale.US, "%.1f 万", n / 10000.0) else n.toString()

