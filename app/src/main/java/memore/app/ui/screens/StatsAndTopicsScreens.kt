package memore.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.model.EmotionStats
import memore.app.data.model.Record
import memore.app.data.repository.RecordRepository
import memore.app.data.vector.MonthlyTopicsService
import memore.app.ui.components.MirrorLoading
import memore.app.ui.theme.MemoreType
import memore.app.utils.memoreToast
import java.util.Calendar
import java.util.Locale

/** 主题月列表（2026-11-17）。
 *
 * 入口：时间线 →「更多」→ 主题。
 * 顶栏两个动作：线索（左，进 TopicThreadsScreen）、刷新（右，显式重算月度聚类 + 命名）。
 * 打开页面只读缓存，不做任何计算；线索页独立，不再塞在本页。
 */
@Composable
fun TopicMonthsScreen(
    repository: RecordRepository?,
    db: memore.app.data.local.AppDatabase,
    onBack: () -> Unit,
    onOpenMonth: (String) -> Unit,
    onOpenThreads: () -> Unit
) {
    var months by remember { mutableStateOf<List<MonthlyTopicsService.NamedMonth>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf("") }
    var confirmRefresh by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    fun service(): MonthlyTopicsService? {
        val repo = repository ?: return null
        val namer = memore.app.data.vector.TopicNamer(ctx)
        return MonthlyTopicsService(ctx, repo, db) { seeds, anchors ->
            val topics = seeds.mapIndexed { i, s ->
                memore.app.data.vector.TopicNamer.Seed(i, s.keywords.split("/").filter { it.isNotBlank() }, s.samples, s.concepts)
            }
            namer.name(topics, anchors).orEmpty()
        }
    }

    LaunchedEffect(Unit) {
        val svc = service()
        months = if (svc == null) emptyList() else runCatching { svc.cached() }.getOrNull()?.months.orEmpty()
        loading = false
    }

    fun doRefresh() {
        val svc = service() ?: return
        busy = "刷新主题…"
        scope.launch {
            val snap = runCatching { svc.refresh() }.getOrNull()
            if (snap == null) {
                ctx.memoreToast("刷新失败：AI 命名未完成，已保留旧数据", android.widget.Toast.LENGTH_SHORT)
            } else {
                months = snap.months
            }
            busy = ""
        }
    }

    memore.app.ui.components.MemorePage(
        title = "主题",
        onBack = onBack,
        actions = {
            IconButton(onClick = onOpenThreads) {
                Icon(
                    androidx.compose.ui.res.painterResource(memore.app.R.drawable.ic_topic_threads),
                    "线索", tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { confirmRefresh = true }) {
                Icon(
                    androidx.compose.ui.res.painterResource(memore.app.R.drawable.ic_topic_refresh),
                    "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (loading) {
                memore.app.ui.components.MirrorLoading()
            } else if (months.isEmpty()) {
                Text(
                    "还没有主题：先点右上角「刷新」",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                months.sortedByDescending { it.month }.forEach { m ->
                    val total = m.clusters.sumOf { it.count }
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenMonth(m.month) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.month, style = MemoreType.sectionTitle, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "$total 条 · ${m.clusters.size} 个主题",
                                style = MemoreType.micro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                }
            }
        }
    }

    if (busy.isNotEmpty()) memore.app.ui.components.BusyDialog(busy)

    if (confirmRefresh) {
        memore.app.ui.components.MirrorAlertDialog(
            onDismissRequest = { confirmRefresh = false },
            title = { Text("刷新主题", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("重新聚类，并为每个簇调用一次 AI 命名；记录多时需要一会儿。") },
            confirmButton = {
                TextButton(onClick = { confirmRefresh = false; doRefresh() }) {
                    Text("开始", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRefresh = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

/** 月份切换的列间竖线：1dp × 12dp、onSurface α0.12（同 Origin 汇总格的小竖线）。 */
@Composable
private fun MonthTabDivider() {
    Box(
        Modifier.padding(horizontal = 6.dp).width(1.dp).height(12.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    )
}

/** 本月主题页（A 版：单月显示 + 顶部切月）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TopicMonthScreen(
    repository: memore.app.data.repository.RecordRepository?,
    db: memore.app.data.local.AppDatabase,
    initialMonth: String,
    onBack: () -> Unit
) {
    var selected by remember { mutableStateOf(initialMonth) }
    var months by remember { mutableStateOf<List<MonthlyTopicsService.NamedMonth>>(emptyList()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val repo = repository ?: return@LaunchedEffect
        val namer = memore.app.data.vector.TopicNamer(ctx)
        val svc = MonthlyTopicsService(ctx, repo, db) { seeds, anchors ->
            val topics = seeds.mapIndexed { i, s ->
                memore.app.data.vector.TopicNamer.Seed(i, s.keywords.split("/").filter { it.isNotBlank() }, s.samples, s.concepts)
            }
            memore.app.data.vector.TopicNamer(ctx).name(topics, anchors).orEmpty()
        }
        months = runCatching { svc.cached() }.getOrNull()?.months.orEmpty()
    }
    val current = months.firstOrNull { it.month == selected } ?: months.lastOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 月份切换：一条横排（列间用 1dp 竖线分隔，同 Origin 汇总格）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                months.sortedBy { it.month }.forEachIndexed { index, m ->
                    if (index > 0) MonthTabDivider()
                    val on = m.month == selected
                    Text(
                        m.month.substringAfter('-') + " 月",
                        style = MemoreType.meta,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { selected = m.month }.padding(vertical = 6.dp)
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))
            Spacer(Modifier.height(14.dp))

            if (current == null) {
                Text("这个月还没有主题", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${current.month} · ${current.clusters.sumOf { it.count }} 条 · ${current.clusters.size} 个主题",
                    style = MemoreType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                current.clusters.sortedByDescending { it.count }.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(
                            c.name,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${c.count}", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** 统计页（数字优先 + 情绪占比）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(repository: RecordRepository?, onBack: () -> Unit, onOpenEmotion: (String) -> Unit = {}) {
    var loaded by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var chars by remember { mutableIntStateOf(0) }
    var prevCount by remember { mutableIntStateOf(0) }
    var medianHour by remember { mutableIntStateOf(0) }
    var emotions by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }

    LaunchedEffect(Unit) {
        val all = runCatching { repository?.getAllActiveRecords() }.getOrNull().orEmpty().filter { !it.isLocked }
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monthStart = cal.timeInMillis
        val cur = all.filter { it.createdAt >= monthStart }
        count = cur.size; chars = cur.sumOf { it.content.length }
        cal.add(Calendar.MONTH, -1); val prevStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1); val prevEnd = cal.timeInMillis
        prevCount = all.count { it.createdAt in prevStart until prevEnd }
        val hours = cur.map { val c = Calendar.getInstance(); c.timeInMillis = it.createdAt; c.get(Calendar.HOUR_OF_DAY) }.sorted()
        medianHour = if (hours.isEmpty()) 0 else hours[hours.size / 2]

        val acc = HashMap<String, Float>()
        all.filter { it.createdAt >= monthStart }.forEach { r ->
            EmotionStats.emotionEntries(r).forEach { e -> acc[e.name] = (acc[e.name] ?: 0f) + e.weight }
        }
        val total = acc.values.sum().coerceAtLeast(0.01f)
        emotions = acc.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value / total }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Text("本月", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$count", fontSize = 34.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text("条", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    val diff = count - prevCount
                    Text(
                        if (prevCount == 0) "" else (if (diff >= 0) "+$diff" else "$diff") + " 环比",
                        style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatNum(chars), fontSize = 34.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text("字", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(if (count == 0) "" else "均 ${chars / count} 字/条", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Text("多在 ${if (medianHour < 10) "0$medianHour" else "$medianHour"} 点写", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
                Spacer(Modifier.height(14.dp))

                Text("情绪主线", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                emotions.forEach { (name, ratio) ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenEmotion(name) }.padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(name, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text("${(ratio * 100).toInt()}%", style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))) {
                            Box(
                                Modifier.fillMaxWidth(ratio.coerceIn(0.02f, 1f)).height(3.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                            )
                        }
                    }
                }
                if (emotions.isEmpty()) {
                    Text("本月还没有情绪标签", style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatNum(n: Int): String =
    if (n >= 10000) String.format(Locale.US, "%.1f 万", n / 10000.0) else n.toString()
